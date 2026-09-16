#!/usr/bin/env python3
"""Demo cohort for every console screen: people, teams, 75 days of learning, IRIS asks, conversations,
reports, corrections and notifications, all on the catalog's REAL topics, subtopics and questions.

    python3 tools/seed_demo.py                 seed (idempotent: same ids every run)
    python3 tools/seed_demo.py --wipe          remove everything it created
    python3 tools/seed_demo.py --days 75 --people 48 --no-recompute

Every row it writes is marked: ids start with `demo-`, users are `demo.NN@testu.local`, teams `demo*`.
--wipe deletes exactly those (plus the derived tutormastery / tutormasteryday / tutordaily / tutorquestion
rows of those users). Real learners' data is never touched.

Refuses non-localhost hosts unless DEMO_REMOTE=yes is set, because on a live pilot catalog the demo cohort
lands in the same org-wide totals as the real one. Run it from the server's shell (localhost there), or
against a copy.

Rows go straight into Elasticsearch (tutoranswer, learningsession, usageevent, chatterbox, chatterboxreaction,
questionflag, learnernotification, auditevent); users and teams go through the personas endpoints so the
profiles, roles and settingsgroups exist the way the app expects. Then recompute.json builds mastery, the
per-day mastery history the forecast fits, the daily rollups and the IRIS question table.
"""
import datetime
import hashlib
import json
import os
import random
import sys
import time
import urllib.error
import urllib.request
from http.cookiejar import CookieJar
from urllib.parse import quote, urlparse

BASE = os.environ.get("EME_BASE", "http://localhost:8080/site/mediadb")
ES = os.environ.get("ES", "http://localhost:9200/site_catalog")
if os.environ.get("DEMO_REMOTE") != "yes":
    for _u in (BASE, ES):
        if urlparse(_u).hostname not in ("localhost", "127.0.0.1"):
            sys.exit(f"refusing non-local host {_u} (set DEMO_REMOTE=yes if you really mean it)")
USER = os.environ.get("EME_USER", "admin")
PASSWORD = os.environ.get("EME_PASSWORD", "admin")
ES_ROOT, ES_INDEX = ES.rsplit("/", 1)

ARGS = sys.argv[1:]
WIPE = "--wipe" in ARGS
NO_RECOMPUTE = "--no-recompute" in ARGS


def arg(name, default):
    return int(ARGS[ARGS.index(name) + 1]) if name in ARGS else default


DAYS = arg("--days", 75)
PEOPLE = arg("--people", 48)
random.seed(2026)

# ---------------------------------------------------------------- http
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))


def call(url, data=None, content_type="application/json", timeout=180):
    req = urllib.request.Request(url, data=data, method="POST" if data is not None else "GET")
    if data is not None:
        req.add_header("Content-Type", content_type)
    try:
        with opener.open(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def form(url, **fields):
    body = "&".join(f"{k}={quote(str(v))}" for k, v in fields.items()).encode()
    return call(url, body, "application/x-www-form-urlencoded")


def must(what, result):
    status, body = result
    if status // 100 != 2:
        sys.exit(f"{what} failed: HTTP {status} {body[:300]!r}")
    return body


def login():
    body = must("login", form(f"{BASE}/services/authentication/login.json", id=USER, password=PASSWORD))
    if json.loads(body)["response"]["status"] != "ok":
        sys.exit(f"login failed: {body!r}")


def read_users():
    return json.loads(must("users.json", call(f"{BASE}/services/testu/personas/users.json"))).get("users", [])


def es_search(entity, body):
    status, raw = call(f"{ES}/{entity}/_search", json.dumps(body).encode())
    if status // 100 != 2:
        sys.exit(f"ES search {entity} failed: {status} {raw!r}")
    return json.loads(raw)


def es_scan(entity, source):
    """Every row of a type, `_id` + the requested `_source` fields, through the scroll API (ES 2.x)."""
    status, raw = call(f"{ES}/{entity}/_search?scroll=2m",
                       json.dumps({"size": 2000, "query": {"match_all": {}}, "_source": source}).encode())
    if status // 100 != 2:
        sys.exit(f"ES scan {entity} failed: {status} {raw!r}")
    res = json.loads(raw)
    while res["hits"]["hits"]:
        for h in res["hits"]["hits"]:
            yield h
        status, raw = call(f"{ES_ROOT}/_search/scroll", json.dumps({"scroll": "2m", "scroll_id": res["_scroll_id"]}).encode())
        if status // 100 != 2:
            break
        res = json.loads(raw)


def es_bulk(lines):
    for i in range(0, len(lines), 1000):
        payload = "".join(
            json.dumps(a) + "\n" + ("" if s is None else json.dumps(s, ensure_ascii=False) + "\n")
            for a, s in lines[i:i + 1000]
        ).encode()
        status, raw = call(f"{ES}/_bulk", payload, "application/x-ndjson")
        if status // 100 != 2:
            sys.exit(f"ES bulk failed: {status} {raw!r}")
        res = json.loads(raw)
        if res.get("errors"):
            bad = [it for it in res["items"] if list(it.values())[0].get("error")][:3]
            sys.exit(f"ES bulk errors: {json.dumps(bad)[:800]}")
    call(f"{ES}/_refresh", b"")


def cluster_name():
    status, raw = call(f"{ES_ROOT}/")
    return json.loads(raw).get("cluster_name", "entermedia-testcluster") if status // 100 == 2 else "entermedia-testcluster"


def iso(dt):
    """Naive local datetime -> yyyy-MM-dd'T'HH:mm:ss.SSSZ (a date without millis parses as null server-side)."""
    return dt.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def hexid(*parts):
    return hashlib.md5("|".join(str(p) for p in parts).encode()).hexdigest()


# ---------------------------------------------------------------- cohort
TEAMS = [
    ("demosra", "San Rafael · Guardia A", "San Rafael", "CC-3101", -14.23, -70.32),
    ("demosrb", "San Rafael · Guardia B", "San Rafael", "CC-3102", -14.23, -70.32),
    ("demopisco", "Planta Pisco", "Pisco", "CC-2201", -13.80, -76.25),
    ("demopuca", "Pucamarca", "Tacna", "CC-3301", -17.60, -69.90),
    ("demoraura", "Raura · Superficie", "Raura", "CC-3401", -10.44, -76.74),
    ("demomant", "Mantenimiento", "San Rafael", "CC-3150", -14.23, -70.32),
    ("demolima", "Administración Lima", "Lima", "CC-1001", -12.05, -77.04),
]
FIRST = ["Ana", "Luis", "Rosa", "Carlos", "Marta", "Jorge", "Elena", "Pedro", "Sofía", "Miguel", "Lucía", "Raúl",
         "Nuria", "Iván", "Paula", "Diego", "Karina", "Óscar", "Fiorella", "Bruno", "Milagros", "Andrés", "Claudia",
         "Renzo", "Aldo", "Sandra", "Nadia", "María", "Elías", "Karla", "Jhon", "Yesenia", "Wilber", "Rocío", "Édgar",
         "Liz", "Percy", "Gloria", "Néstor", "Vanessa", "Hugo", "Ruth", "Alonso", "Mirian", "Julio", "Tania", "Efraín",
         "Beatriz", "César", "Nataly", "Wilmer", "Ángela", "Rubén", "Delia", "Fredy", "Katia", "Adolfo", "Silvia"]
LAST = ["Quispe", "Mamani", "Huamán", "Ticona", "Chura", "Apaza", "Ccama", "Yupanqui", "Vargas", "Chávez", "Paredes",
        "Sánchez", "Ramos", "Castillo", "Rojas", "Salazar", "Flores", "Peralta", "Cárdenas", "Zegarra", "Navarro",
        "Bustos", "Ferrer", "Ibáñez", "Huanca", "Vilca", "Condori", "Choque", "Cutipa", "Mendoza", "Pari", "Arias",
        "Villanueva", "Calla", "Machaca", "Colque", "Taipe", "Sucari", "Ordóñez", "Zapana"]

# One staff member per role, so replies, corrections and mentions have real authors.
STAFF = {  # n -> (role, team)
    1: ("training", "demolima"),   # Carla-like instructor: corrects questions, replies to reports
    2: ("manager", "demosra"),     # runs San Rafael A + B
    3: ("manager", "demopisco"),   # runs Pisco + Mantenimiento
}
MANAGES = {"demosra": 2, "demosrb": 2, "demopisco": 3, "demomant": 3}
MANAGER_PASSWORD = "Demopass123"


def email(n):
    return f"demo.{n:02d}@testu.local"


def name_of(n):
    return FIRST[(n * 7) % len(FIRST)], LAST[(n * 11 + n // 3) % len(LAST)]


def first_of(n):
    return name_of(n)[0]


def team_of(n):
    if n in STAFF:
        return STAFF[n][1]
    return TEAMS[(n * 5) % (len(TEAMS) - 1)][0] if n % 9 else "demolima"


# Growth profile: accuracy start -> end over the person's active span; how much of the span they use.
PROFILES = {
    "fast":    (0.45, 0.98, 1.00),
    "steady":  (0.35, 0.90, 1.00),
    "late":    (0.30, 0.80, 0.60),   # starts in the second half, still climbing
    "slow":    (0.25, 0.62, 1.00),
    "stuck":   (0.30, 0.42, 1.00),   # confident and wrong: misconceptions
    "lapsed":  (0.40, 0.75, 0.45),   # active early, nothing in the last weeks
    "idle":    (0, 0, 0),
}
PROFILE_MIX = ["fast"] * 10 + ["steady"] * 18 + ["late"] * 6 + ["slow"] * 5 + ["stuck"] * 3 + ["lapsed"] * 3 + ["idle"] * 3


def profile_of(n):
    return "idle" if n in STAFF else PROFILE_MIX[(n * 13) % len(PROFILE_MIX)]


CONF = ["noidea", "notsure", "mostlysure", "confident"]


def confidence(ok, profile, acc):
    if profile == "stuck":
        return random.choices(CONF, [0.05, 0.10, 0.35, 0.50])[0]
    if ok:
        return random.choices(CONF, [0.02, 0.10 * (1 - acc), 0.25, 0.65 * acc + 0.1])[0]
    return random.choices(CONF, [0.30, 0.42, 0.20, 0.08])[0]


# ---------------------------------------------------------------- text
IRIS_QUESTIONS = [
    "En «{sec}», ¿cuál es la diferencia práctica entre {kw}?",
    "¿Qué hago si en campo no puedo cumplir lo que dice «{sec}»?",
    "¿Esto aplica igual para contratistas y personal propio?",
    "¿Dónde encuentro el PETS que respalda la pregunta sobre {kw}?",
    "No entiendo por qué la respuesta correcta es la que indica. ¿Me lo explicas con un ejemplo?",
    "¿Cada cuánto se revisa lo que dice «{sec}»?",
    "¿Quién autoriza en la práctica lo que describe esta sección?",
    "¿Qué pasa si detecto una desviación de {kw} y el supervisor no está?",
    "¿Puedes resumirme «{sec}» en tres puntos?",
    "¿Cuál es el error más común en {kw}?",
]
IRIS_REPLIES = [
    "Según el material del curso, {kw}. La sección «{sec}» lo desarrolla con el procedimiento paso a paso.",
    "Aplica a todo el personal que interviene, contratistas incluidos: la política no distingue por vínculo laboral.",
    "Si no puedes cumplirlo en campo, detén la tarea y comunícalo por el canal formal antes de continuar.",
    "La revisión es periódica y cada versión reemplaza a la anterior; la vigente es la que cita la pregunta.",
    "En tres puntos: identificar, controlar y verificar. Cada uno tiene responsables definidos en la sección.",
    "El error más frecuente es asumir en lugar de verificar. La sección insiste en la confirmación antes de actuar.",
]
CITATION = " [{tut}, p. {page}]"

LEARNER_COMMENTS = [
    "Sobre «{q}»: en mi guardia lo hacemos distinto, ¿cuál es el estándar vigente?",
    "No me queda clara la opción {opt}. ¿Alguien me explica por qué no aplica?",
    "Confirmado con el supervisor: es como dice la explicación. Gracias.",
    "Esta pregunta me salió dos veces en el reto diario y las dos me confundí con la {opt}.",
    "¿Este procedimiento aplica también para contratistas?",
    "Muy útil la fuente citada, la busqué y está en la página que indica.",
    "Creo que la respuesta cambió con la última versión del estándar, ¿pueden revisar?",
    "Buen ejemplo el de la explicación. Se entiende mejor que en la charla de cinco minutos.",
    "@{staff} ¿podemos ver esto en la reunión de seguridad del lunes?",
    "Lo que dice la explicación coincide con el video de inducción.",
    "Me equivoqué por leer rápido. La clave está en «{kw}».",
    "¿Hay un formato para registrar esto en campo o solo se comunica por radio?",
    "A mí también me pasó, {peer}. Lo importante es la parte de «{kw}».",
    "Gracias, ya lo vi.",
    "Pregunta bien planteada, pero la imagen no ayuda mucho.",
]
STAFF_REPLIES = [
    "Buena observación, {first}. El estándar vigente es el que indica la explicación; si en tu guardia se hace distinto, avísame y lo revisamos con el supervisor.",
    "Aplica a todos, contratistas incluidos. Está en las cláusulas del contrato y en el procedimiento.",
    "Lo llevo a la reunión del lunes. Gracias por levantarlo.",
    "Correcto, {first}. La clave es exactamente esa: «{kw}».",
    "Lo revisamos con contenidos: la fuente citada es la vigente. Si encuentras una versión más nueva, repórtala desde la pregunta.",
    "Gracias, {first}. Comparto tu comentario con el equipo de capacitación.",
    "Sí, se registra en el formato de campo y además se comunica por radio. Las dos cosas.",
]
TUTOR_REPLIES = [
    "Según el material del curso, {kw}. La explicación de la pregunta lo desarrolla con la fuente citada.",
    "Buena pregunta, {first}. La respuesta correcta se apoya en «{kw}»; la opción {opt} describe una práctica que el procedimiento descarta.",
]
TUTORIAL_COMMENTS = [
    "Muy útil este tutorial, más así por favor.",
    "Sugerencia: agregar un caso real de nuestra unidad en la sección {sec}.",
    "¿Habrá versión en quechua para el personal de superficie?",
    "Terminé el módulo. Las preguntas de {sec} son las más difíciles.",
]
FLAG_NOTES = {
    "wrong": [
        "El estándar vigente dice otra cosa; la respuesta marcada como correcta ya no aplica.",
        "La opción {opt} también es válida según el procedimiento, no debería haber una sola correcta.",
        "En campo el valor es distinto al que indica la respuesta.",
    ],
    "unclear": [
        "La pregunta se puede leer de dos formas y las dos llevan a opciones distintas.",
        "Las opciones {opt} y {opt2} dicen casi lo mismo.",
        "No se entiende qué se pregunta hasta leer la explicación.",
    ],
    "outdated": [
        "Cita una versión anterior del estándar; hubo cambios este año.",
        "Desde el memo del mes pasado esto se hace distinto.",
    ],
    "other": ["La imagen no carga en Android.", "Hay un error de tipeo en la opción {opt}.", ""],
}
FIX_CHIPS = {
    "wrong": (["Respuesta correcta cambiada", "Explicación ampliada"], "La respuesta correcta ahora es {ok}; se amplió la explicación con la fuente vigente."),
    "unclear": (["Enunciado aclarado", "Opciones reescritas"], "Se reescribió el enunciado y se diferenciaron las opciones."),
    "outdated": (["Fuente actualizada", "Explicación ampliada"], "Se actualizó la cita a la versión vigente del estándar."),
    "other": (["Imagen corregida"], "Se reemplazó la imagen."),
}


def kw_of(q):
    words = (q.get("rationale") or q.get("question") or "").split()
    return " ".join(words[:6]).strip(" .,;:") if words else "el procedimiento"


def opts_of(q):
    return [l for l in "ABCDEF" if (q.get("option_" + l.lower()) or "").strip()]


def correct_of(q):
    c = (q.get("correctoption") or "A").strip().upper().replace("OPTION_", "")
    return c if c in "ABCDEF" and len(c) == 1 else "A"


# ---------------------------------------------------------------- content
def read_content():
    topics = {h["_id"]: h["_source"].get("name") or h["_id"]
              for h in es_scan("entitytopic", ["name"])}
    tutorials = {h["_id"]: (h["_source"].get("name") or h["_id"], h["_source"].get("entitytopic"))
                 for h in es_scan("entitytutorial", ["name", "entitytopic"])}
    sections = {}
    for h in es_scan("componentsection", ["name", "ordering", "playbackentityid", "playbackentitymoduleid"]):
        s = h["_source"]
        if s.get("playbackentitymoduleid") == "entitytutorial" and s.get("playbackentityid") in tutorials:
            sections[h["_id"]] = {"id": h["_id"], "name": s.get("name") or h["_id"], "ordering": int(s.get("ordering") or 0),
                                  "tutorial": s["playbackentityid"], "topic": tutorials[s["playbackentityid"]][1], "questions": []}
    slots = []
    for h in es_scan("componentcontent", ["componentsectionid", "questionid", "componenttype", "ordering"]):
        s = h["_source"]
        if s.get("componenttype") == "mcq" and s.get("questionid") and str(s.get("componentsectionid")) in sections:
            slots.append((str(s["componentsectionid"]), str(s["questionid"]), int(s.get("ordering") or 0), h["_id"]))
    questions = {}
    for h in es_scan("entityquestion", ["question", "option_a", "option_b", "option_c", "option_d", "option_e", "option_f",
                                         "correctoption", "rationale", "mcqcognitivelevel", "evaluationreserved"]):
        questions[h["_id"]] = h["_source"]
    seen = set()
    for sec, qid, ordering, cid in sorted(slots, key=lambda x: (x[0], x[2])):
        q = questions.get(qid)
        if not q or qid in seen or str(q.get("evaluationreserved")) == "true":
            continue
        if not (q.get("question") and q.get("option_a") and q.get("correctoption")):
            continue
        seen.add(qid)
        sections[sec]["questions"].append(qid)
    by_topic = {}
    for s in sorted(sections.values(), key=lambda s: (s["topic"] or "", s["ordering"])):
        if s["questions"] and s["topic"] in topics:
            by_topic.setdefault(s["topic"], []).append(s)
    if not by_topic:
        sys.exit("no topics with MCQ sections found -- is the catalog loaded?")
    print(f"content: {len(by_topic)} topics, {sum(len(v) for v in by_topic.values())} sections, {len(seen)} questions")
    return topics, tutorials, by_topic, questions


# ---------------------------------------------------------------- wipe
DEMO_TYPES = ["tutoranswer", "learningsession", "tutorexposure", "usageevent", "chatterbox", "chatterboxreaction",
              "questionflag", "learnernotification", "auditevent", "tutormastery", "tutormasteryday", "tutordaily",
              "tutorquestion", "subtopicunlock", "dailychallengeset", "learneronboarding"]


def wipe():
    login()
    deletes = []
    for entity in DEMO_TYPES:
        ids = [h["_id"] for h in es_scan(entity, ["user", "actor"])
               if h["_id"].startswith("demo-") or h["_id"].startswith("demo.")
               or str(h["_source"].get("user", "")).startswith("demo.")
               or str(h["_source"].get("actor", "")).startswith("demo.")]
        print(f"wipe {entity}: {len(ids)}")
        deletes += [({"delete": {"_index": ES_INDEX, "_type": entity, "_id": i}}, None) for i in ids]
    if deletes:
        es_bulk(deletes)
    users = [u["id"] for u in read_users() if u["id"].startswith("demo.")]
    for uid in users:
        status, body = form(f"{BASE}/services/testu/personas/deleteuser.json", userid=uid)
        if status // 100 != 2:   # older server without deleteuser: disable instead
            must(f"setenabled {uid}", form(f"{BASE}/services/testu/personas/setenabled.json", userid=uid, enabled="false"))
    print(f"wipe users: {len(users)}")
    print("wipe teams: left in place (empty demo teams are inert)")
    if not NO_RECOMPUTE:
        recompute_and_report(seeding=False)


# ---------------------------------------------------------------- people
def ensure_people():
    existing = {u["id"]: u for u in read_users()}
    for tid, name, location, cc, _, _ in TEAMS:
        must(f"saveteam {tid}", form(f"{BASE}/services/testu/personas/saveteam.json", id=tid, name=name, parent="",
                                     manager="", location=location, costcenter=cc))
    created = 0
    for n in range(1, PEOPLE + 1):
        uid, (first, last) = email(n), name_of(n)
        team, role = team_of(n), STAFF.get(n, ("users", ""))[0]
        if uid not in existing:
            must(f"createuser {uid}", form(f"{BASE}/services/testu/personas/createuser.json",
                                           email=uid, firstName=first, lastName=last, role=role, team=team))
            created += 1
        else:
            if not existing[uid]["enabled"]:
                must(f"setenabled {uid}", form(f"{BASE}/services/testu/personas/setenabled.json", userid=uid, enabled="true"))
            must(f"setteam {uid}", form(f"{BASE}/services/testu/personas/setteam.json", userid=uid, team=team))
            must(f"setrole {uid}", form(f"{BASE}/services/testu/personas/setrole.json", userid=uid, role=role))
    for tid, name, location, cc, _, _ in TEAMS:
        must(f"saveteam {tid} manager", form(f"{BASE}/services/testu/personas/saveteam.json", id=tid, name=name, parent="",
                                             manager=email(MANAGES[tid]) if tid in MANAGES else "", location=location, costcenter=cc))
    for n in STAFF:
        must(f"password {email(n)}", form(f"{BASE}/services/authentication/usersave.json", username=email(n),
                                          field="password", passwordvalue=MANAGER_PASSWORD))
    print(f"people: {len(TEAMS)} teams, {PEOPLE} users ({created} created); staff "
          + ", ".join(f"{email(n)} ({STAFF[n][0]})" for n in STAFF) + f", password {MANAGER_PASSWORD}")


# ---------------------------------------------------------------- rows
class Rows:
    def __init__(self, cluster):
        self.lines = []
        self.n = {}
        now = iso(datetime.datetime.now())
        self.stat = {"recorddeleted": False, "mastereditclusterid": cluster, "lastmodifiedclusterid": cluster,
                     "recordmodificationdate": now, "masterrecordmodificationdate": now}

    def put(self, entity, rid, src):
        src["id"] = rid
        src["emrecordstatus"] = self.stat
        self.lines.append(({"index": {"_index": ES_INDEX, "_type": entity, "_id": rid}}, src))
        self.n[entity] = self.n.get(entity, 0) + 1
        return rid

    def next(self, kind):
        self.n[kind + "#"] = self.n.get(kind + "#", 0) + 1
        return f"demo-{kind}-{self.n[kind + '#']}"


def build(topics, tutorials, by_topic, questions, cluster):
    R = Rows(cluster)
    today = datetime.date.today()
    end = today - datetime.timedelta(days=1)
    start = end - datetime.timedelta(days=DAYS - 1)
    topic_ids = list(by_topic)
    staff_ids = [email(n) for n in STAFF]
    training = email(1)
    site_of = {t[0]: (t[4], t[5]) for t in TEAMS}
    platforms = ["android", "android", "android", "ios", "web"]

    # ---- learning: every person, their topics, a growth curve, sessions on study days
    plan_by_user = {}
    studied = {}     # user -> set of question ids
    channels = {}    # question -> [user ids who answered it]
    for n in range(1, PEOPLE + 1):
        uid, prof = email(n), profile_of(n)
        a0, a1, span = PROFILES[prof]
        if prof == "idle":
            continue
        my_topics = topic_ids if len(topic_ids) <= 2 else random.sample(topic_ids, 2 if n % 3 else 1)
        if prof == "late":
            s0, s1 = start + datetime.timedelta(days=int(DAYS * 0.5)), end
        elif prof == "lapsed":
            s0, s1 = start, start + datetime.timedelta(days=int(DAYS * span))
        else:
            s0, s1 = start + datetime.timedelta(days=random.randint(0, 6)), end
        n_days = max(3, int((s1 - s0).days * random.uniform(0.28, 0.5)))
        pool = [s0 + datetime.timedelta(days=i) for i in range((s1 - s0).days + 1)]
        pool = [d for d in pool for _ in (range(3) if d.weekday() < 5 else range(1))]
        days = sorted(set(random.sample(pool, min(n_days, len(set(pool))))))
        studied[uid] = set()
        rounds = []   # (topic, section, [questions]) in the order they are studied: sequential subtopics, repeated
        # three full passes, topics interleaved: mastery reads the LATEST attempt per question, so every topic's
        # last pass, answered at the person's final accuracy, is what sets their band
        for rnd in range(3):
            for t in my_topics:
                for s in by_topic[t]:
                    rounds.append((t, s, s["questions"][:]))
        # the person walks through `rounds` across their study days; accuracy climbs with progress
        total_q = sum(len(r[2]) for r in rounds)
        per_day = max(4, -(-total_q // max(1, len(days))))
        cursor = 0
        flat = [(t, s, q) for t, s, qs in rounds for q in qs]
        site = site_of[team_of(n)]
        for di, day in enumerate(days):
            chunk = flat[cursor:cursor + per_day]
            cursor += per_day
            if not chunk:
                break
            progress = di / max(1, len(days) - 1)
            acc = a0 + (a1 - a0) * (1 / (1 + 2.718 ** (-9 * (progress - 0.4))))   # the last pass runs at the final accuracy
            at = datetime.datetime.combine(day, datetime.time(random.choice([6, 7, 8, 12, 13, 14, 17, 18, 19]), random.randint(0, 59)))
            first = at
            platform = random.choice(platforms)
            sid_open = R.next("s")
            lat, lon = site[0] + random.uniform(-0.03, 0.03), site[1] + random.uniform(-0.03, 0.03)
            R.put("usageevent", R.next("ev"), dict(user=uid, datecreated=iso(at - datetime.timedelta(minutes=1)), type="open",
                                                  sessionid=sid_open, platform=platform, appversion="1.4.0",
                                                  lat=round(lat, 2), lon=round(lon, 2)))
            # group the chunk by section into learn sessions; every 4th day is a daily challenge instead
            mode = "dailychallenge" if di % 4 == 3 else "learn"
            by_sec = {}
            for t, s, q in chunk:
                by_sec.setdefault(s["id"], (t, s, []))[2].append(q)
            for sec_id, (t, s, qs) in by_sec.items():
                if mode == "learn" and progress > 0.6 and random.random() < 0.3:
                    mode_here = "improve"
                else:
                    mode_here = mode
                sess = uid + "_" + hexid(uid, day, sec_id)
                R.put("learningsession", sess, dict(user=uid, mode=mode_here, scopetype=None if mode_here == "dailychallenge" else "subtopic",
                                                    scopeid=None if mode_here == "dailychallenge" else sec_id,
                                                    questionlist=json.dumps(qs), algorithmversion=f"{mode_here}-v1", policyversion=1,
                                                    inputs="{}", datecreated=iso(at), expiresat=iso(at + datetime.timedelta(days=7))))
                for q in qs:
                    at += datetime.timedelta(seconds=random.randint(25, 160))
                    qd = questions[q]
                    ok = random.random() < acc
                    opts = opts_of(qd)
                    correct = correct_of(qd)
                    wrong = [o for o in opts if o != correct] or opts
                    hint = 0 if ok or random.random() < 0.7 else random.randint(1, 2)
                    attempt = hexid("att", uid, q, at)[:24]
                    R.put("tutoranswer", f"{uid}_{attempt}", {
                        "user": uid, "attemptid": attempt, "entityquestion": q, "entitytutorial": s["tutorial"],
                        "componentsection": sec_id, "mode": mode_here,
                        "scopetype": None if mode_here == "dailychallenge" else "subtopic",
                        "scopeid": None if mode_here == "dailychallenge" else sec_id,
                        "selectedoption": correct if ok else random.choice(wrong),
                        "answerconfidence": confidence(ok, prof, acc), "hintlevel": hint,
                        "iscorrect": "true" if ok else "false",
                        "pointsearned": ({"beginner": 10, "competent": 20, "expert": 40}.get(qd.get("mcqcognitivelevel"), 10) if ok else 0.0),
                        "bonusearned": 0.0, "channel": f"demo-ch-{n:02d}", "learningsession": sess,
                        "datecreated": iso(at), "lastpenalty": iso(at)})
                    studied[uid].add(q)
                    channels.setdefault(q, []).append((uid, at, ok, s))
            # foreground spans
            span_at = first
            for k in range(random.randint(1, 3)):
                length = random.randint(240, 1500)
                sid = R.next("s")
                R.put("usageevent", R.next("ev"), dict(user=uid, datecreated=iso(span_at), type="resume", sessionid=sid, platform=platform))
                R.put("usageevent", R.next("ev"), dict(user=uid, datecreated=iso(span_at + datetime.timedelta(seconds=length)), type="pause",
                                                      sessionid=sid, seconds=length, platform=platform))
                span_at += datetime.timedelta(seconds=length + random.randint(60, 600))
        plan_by_user[uid] = (prof, days, my_topics)
        # onboarding: everybody who studied finished it on their first day
        R.put("learneronboarding", uid, dict(user=uid, started=iso(datetime.datetime.combine(days[0], datetime.time(6, 50))),
                                             finished=iso(datetime.datetime.combine(days[0], datetime.time(6, 58))), opens=1,
                                             skipped=False, notify=True, goals="Aprobar la evaluación anual",
                                             whenlearn=random.choice(["shiftstart", "break", "dayend"])))

    # ---- IRIS asks: chatterbox pairs the event turns into tutorquestion rows, ratings on half
    askers = [u for u, (p, d, t) in plan_by_user.items() if d]
    for i in range(int(PEOPLE * 2.6)):
        uid = askers[i % len(askers)]
        prof, days, my_topics = plan_by_user[uid]
        t = random.choice(my_topics)
        s = random.choice(by_topic[t])
        q = random.choice(s["questions"])
        qd = questions[q]
        at = datetime.datetime.combine(random.choice(days), datetime.time(random.randint(7, 19), random.randint(0, 59)))
        ch = R.next("chq")
        qid_row = R.put("chatterbox", ch, {
            "user": uid, "channel": ch, "date": iso(at), "messagetype": "system", "functionname": "chat_tutor_usercomment",
            "chatmessagestatus": "completed",
            "agentcontextvalues": json.dumps({"skiploader": "true", "componentid": s["id"], "sectionid": s["id"], "questionid": q,
                                              "tutorialid": s["tutorial"],
                                              "query": random.choice(IRIS_QUESTIONS).format(sec=s["name"], kw=kw_of(qd))}, ensure_ascii=False)})
        reply_at = at + datetime.timedelta(seconds=random.randint(20, 90))
        reply = random.choice(IRIS_REPLIES).format(sec=s["name"], kw=kw_of(qd))
        if i % 2 == 0:
            reply += CITATION.format(tut=tutorials[s["tutorial"]][0][:40], page=random.randint(2, 30))
        R.put("chatterbox", R.next("cha"), {"user": "agent", "channel": ch, "date": iso(reply_at), "replytoid": qid_row,
                                            "message": reply, "functionname": "chat_tutor_usercomment", "chatmessagestatus": "completed"})
        if random.random() < 0.6:
            R.put("usageevent", R.next("ev"), dict(user=uid, datecreated=iso(reply_at + datetime.timedelta(seconds=random.randint(20, 300))),
                                                  type="iris_rate", channel=ch, componentsection=s["id"], entityquestion=q,
                                                  rating="helpful" if random.random() < 0.78 else "nothelpful", platform="android"))

    # ---- conversations: threads on the questions people answered, heavier in the last two weeks
    def notify(recipient, actor, ntype, text, channel, msgid, at, q=None, tut=None, topic=None):
        if recipient == actor or not recipient.startswith("demo."):
            return
        R.put("learnernotification", R.next("n"), dict(user=recipient, actor=actor, actorname=(" ".join(name_of(int(actor[5:7]))) if actor.startswith("demo.") else "IRIS"),
                                                       type=ntype, text=text[:120], channel=channel, messageid=msgid, entityquestion=q or "",
                                                       entitytutorial=tut or "", entitytopic=topic or "", read=at < datetime.datetime.now() - datetime.timedelta(days=3),
                                                       datecreated=iso(at)))

    popular = sorted(channels.items(), key=lambda kv: -len(kv[1]))
    thread_qs = [q for q, _ in popular[: max(12, len(popular) // 3)]]
    waiting_left = max(4, len(thread_qs) // 6)
    for i, q in enumerate(thread_qs):
        qd, answered = questions[q], channels[q]
        s = answered[0][3]
        ch = f"q-{q}"
        recent_bias = i % 3 != 0
        base_day = end - datetime.timedelta(days=random.randint(0, 12) if recent_bias else random.randint(10, DAYS - 5))
        at = datetime.datetime.combine(base_day, datetime.time(random.randint(6, 20), random.randint(0, 59)))
        people = [u for u, _, _, _ in answered]
        opener_uid = random.choice(people)
        opts = opts_of(qd)
        correct = correct_of(qd)
        wrong = [o for o in opts if o != correct] or opts
        staff_n = random.choice(list(STAFF))
        text = random.choice(LEARNER_COMMENTS).format(q=(qd.get("question") or "")[:70].rstrip("? ") , opt=random.choice(wrong), kw=kw_of(qd),
                                                      staff=" ".join(name_of(staff_n)), peer=first_of(int(random.choice(people)[5:7])))
        top = R.put("chatterbox", R.next("c"), {"user": opener_uid, "channel": ch, "date": iso(at), "message": text, "messageplain": text,
                                               "functionname": "testu_social", "moduleid": "entityquestion", "entityid": q})
        if "@" in text:
            notify(email(staff_n), opener_uid, "mention", text, ch, top, at, q, s["tutorial"], s["topic"])
        last_by_learner = True
        n_replies = random.choice([0, 0, 1, 1, 2, 3, 4])
        prev = top
        for k in range(n_replies):
            at += datetime.timedelta(hours=random.uniform(0.3, 30))
            if at > datetime.datetime.now():
                break
            who_kind = random.choices(["learner", "staff", "tutor"], [0.45, 0.4, 0.15])[0]
            if who_kind == "staff":
                who = random.choice(staff_ids)
                text = random.choice(STAFF_REPLIES).format(first=first_of(int(opener_uid[5:7])), kw=kw_of(qd))
                last_by_learner = False
            elif who_kind == "tutor":
                who = "tutor"
                text = random.choice(TUTOR_REPLIES).format(first=first_of(int(opener_uid[5:7])), kw=kw_of(qd), opt=random.choice(wrong))
                last_by_learner = False
            else:
                who = random.choice(people)
                text = random.choice(LEARNER_COMMENTS[:8] + LEARNER_COMMENTS[9:]).format(q=(qd.get("question") or "")[:70].rstrip("? "), opt=random.choice(wrong),
                                                                                           kw=kw_of(qd), staff="", peer=first_of(int(opener_uid[5:7])))
                last_by_learner = True
            mid = R.put("chatterbox", R.next("c"), {"user": who, "channel": ch, "date": iso(at), "message": text, "messageplain": text,
                                                   "functionname": "testu_social", "moduleid": "entityquestion", "entityid": q,
                                                   "replytoid": top if k % 2 == 0 else prev})
            notify(opener_uid, who, "reply", text, ch, mid, at, q, s["tutorial"], s["topic"])
            prev = mid
            for _ in range(random.randint(0, 3)):
                ru = random.choice(people)
                R.put("chatterboxreaction", R.next("r"), dict(messageid=mid, user=ru, name=random.choice(["like", "like", "applause", "support", "idea"]),
                                                             date=iso(at + datetime.timedelta(minutes=random.randint(2, 300)))))
                notify(who, ru, "reaction", text, ch, mid, at + datetime.timedelta(minutes=5), q, s["tutorial"], s["topic"])
        # keep a few RECENT threads genuinely unanswered; every older one and most recent ones get a staff close
        stale = (datetime.datetime.now() - at).days > 6
        if last_by_learner and (stale or waiting_left <= 0 or random.random() < 0.8):
            at += datetime.timedelta(hours=random.uniform(1, 20))
            if at < datetime.datetime.now():
                who = random.choice(staff_ids)
                text = random.choice(STAFF_REPLIES).format(first=first_of(int(opener_uid[5:7])), kw=kw_of(qd))
                mid = R.put("chatterbox", R.next("c"), {"user": who, "channel": ch, "date": iso(at), "message": text, "messageplain": text,
                                                       "functionname": "testu_social", "moduleid": "entityquestion", "entityid": q, "replytoid": top})
                notify(opener_uid, who, "reply", text, ch, mid, at, q, s["tutorial"], s["topic"])
            else:
                waiting_left -= 1
        elif last_by_learner:
            waiting_left -= 1
    # tutorial-level threads
    for tid, (tname, topic) in tutorials.items():
        if topic not in by_topic:
            continue
        for k in range(random.randint(2, 4)):
            uid = random.choice(askers)
            at = datetime.datetime.combine(end - datetime.timedelta(days=random.randint(0, 30)), datetime.time(random.randint(7, 20), random.randint(0, 59)))
            text = random.choice(TUTORIAL_COMMENTS).format(sec=random.choice(by_topic[topic])["name"])
            top = R.put("chatterbox", R.next("c"), {"user": uid, "channel": f"t-{tid}", "date": iso(at), "message": text, "messageplain": text,
                                                   "functionname": "testu_social", "moduleid": "entitytutorial", "entityid": tid})
            if k % 2 == 0 or (datetime.datetime.now() - at).days > 6:
                at2 = at + datetime.timedelta(hours=random.uniform(1, 30))
                if at2 < datetime.datetime.now():
                    who = random.choice(staff_ids + ["tutor"])
                    text = f"Gracias, {first_of(int(uid[5:7]))}. Lo comparto con el equipo de contenidos."
                    mid = R.put("chatterbox", R.next("c"), {"user": who, "channel": f"t-{tid}", "date": iso(at2), "message": text, "messageplain": text,
                                                           "functionname": "testu_social", "moduleid": "entitytutorial", "entityid": tid, "replytoid": top})
                    notify(uid, who, "reply", text, f"t-{tid}", mid, at2, None, tid, topic)

    # ---- reports and corrections: open ones recent, some already fixed with a change log
    flag_qs = random.sample(thread_qs, min(len(thread_qs), 14)) + random.sample([q for q, _ in popular[len(thread_qs):]], min(10, max(0, len(popular) - len(thread_qs))))
    random.shuffle(flag_qs)  # so corrected reports (with their change log) also land on questions that show in the inbox
    for i, q in enumerate(flag_qs):
        qd, answered = questions[q], channels[q]
        s = answered[0][3]
        opts = opts_of(qd)
        correct = correct_of(qd)
        wrong = [o for o in opts if o != correct] or opts
        reason = random.choices(["wrong", "unclear", "outdated", "other"], [0.45, 0.3, 0.15, 0.1])[0]
        status = "open" if i < len(flag_qs) * 0.55 else ("resolved" if random.random() < 0.75 else "dismissed")
        n_rep = 2 if (status == "open" and i % 4 == 0) else 1
        ids = []
        for r in range(n_rep):
            uid = random.choice([u for u, _, _, _ in answered])
            back = random.randint(0, 9) if status == "open" else random.randint(12, DAYS - 10)
            at = datetime.datetime.combine(end - datetime.timedelta(days=back), datetime.time(random.randint(7, 20), random.randint(0, 59)))
            note = random.choice(FLAG_NOTES[reason]).format(opt=random.choice(wrong), opt2=random.choice(opts))
            row = dict(user=uid, datecreated=iso(at), entityquestion=q, entitytutorial=s["tutorial"], reason=reason, note=note, status=status)
            if status != "open":
                chips, fixnote = FIX_CHIPS[reason]
                fixnote = fixnote.format(ok=correct)
                done = at + datetime.timedelta(days=random.randint(1, 4))
                row.update(resolvedby=training, resolvedate=iso(done), fixchips=json.dumps(chips if status == "resolved" else [], ensure_ascii=False),
                           fixnote=fixnote if status == "resolved" else "La pregunta está bien; el estándar vigente es el que cita.")
                if r == 0 and status == "resolved":
                    before = {"rationale": (qd.get("rationale") or "")[:60].rstrip() + "…"}
                    after = {"rationale": qd.get("rationale") or ""}
                    if reason == "wrong":
                        before["correctoption"] = random.choice(wrong)
                        after["correctoption"] = correct
                    R.put("auditevent", R.next("a"), dict(datecreated=iso(done - datetime.timedelta(minutes=20)), actor=training, action="question.edit",
                                                         targettype="entityquestion", targetid=q, before=json.dumps(before, ensure_ascii=False),
                                                         after=json.dumps(after, ensure_ascii=False)))
                notify(uid, training, "fixed" if status == "resolved" else "reviewed", " · ".join(chips) + ". " + fixnote if status == "resolved" else row["fixnote"],
                       f"q-{q}", "pending", done, q, s["tutorial"], s["topic"])
            elif r == 0 and random.random() < 0.4:
                rat = at + datetime.timedelta(hours=random.uniform(2, 30))
                if rat < datetime.datetime.now():
                    who = random.choice(staff_ids)
                    text = f"Gracias, {first_of(int(uid[5:7]))}. Lo estoy revisando con contenidos, te aviso en cuanto lo corrija."
                    row["replies"] = json.dumps([{"by": who, "name": " ".join(name_of(int(who[5:7]))), "date": iso(rat), "text": text}], ensure_ascii=False)
                    notify(uid, who, "flagreply", text, f"q-{q}", "pending", rat, q, s["tutorial"], s["topic"])
            fid = R.put("questionflag", R.next("f"), row)
            ids.append(fid)
        if status != "open":
            R.put("auditevent", R.next("a"), dict(datecreated=row["resolvedate"], actor=training, action="question." + ("fixed" if status == "resolved" else "dismissed"),
                                                 targettype="entityquestion", targetid=q, before="",
                                                 after=json.dumps({"verdict": "fixed" if status == "resolved" else "dismissed",
                                                                   "chips": json.loads(row["fixchips"]), "note": row["fixnote"], "flags": ids}, ensure_ascii=False)))
    print("rows: " + ", ".join(f"{v} {k}" for k, v in sorted(R.n.items()) if not k.endswith("#")))
    print(f"span: {start.isoformat()}..{end.isoformat()}")
    return R.lines


# ---------------------------------------------------------------- recompute + report
def recompute_and_report(seeding=True):
    must("recompute", call(f"{BASE}/services/testu/analytics/recompute.json", b""))
    deadline, o = time.time() + 600, {}
    while time.time() < deadline:
        time.sleep(8)
        status, raw = call(f"{BASE}/services/testu/analytics/overview.json")
        o = json.loads(raw) if status // 100 == 2 else {}
        if not o.get("ok"):
            continue
        if seeding and o["cohort"]["activated"] >= PEOPLE * 0.8:
            break
        if not seeding and o["cohort"]["total"] < PEOPLE:
            break
    if not o.get("ok"):
        print("overview.json not ready yet; check again in a minute")
        return
    c = o["cohort"]
    print(f"cohort: total={c['total']} activated={c['activated']} active30d={c['active30d']} active7d={c['active7d']}")
    print(f"levels: {o['levels']}")
    status, raw = call(f"{BASE}/services/testu/analytics/forecast.json")
    if status // 100 == 2:
        f = json.loads(raw)
        print(f"forecast: overall {f.get('overall', {}).get('status')} current={f.get('overall', {}).get('current')} "
              + ", ".join(f"{t.get('name')}: {t.get('status')}" for t in f.get("topics", [])))


if WIPE:
    wipe()
else:
    wipe_rows_only = True
    login()
    for entity in DEMO_TYPES:   # a re-seed first drops the previous run's rows: their ids follow the random stream
        ids = [h["_id"] for h in es_scan(entity, ["user", "actor"])
               if h["_id"].startswith("demo-") or h["_id"].startswith("demo.")
               or str(h["_source"].get("user", "")).startswith("demo.")
               or str(h["_source"].get("actor", "")).startswith("demo.")]
        if ids:
            es_bulk([({"delete": {"_index": ES_INDEX, "_type": entity, "_id": i}}, None) for i in ids])
    ensure_people()
    content = read_content()
    es_bulk(build(*content, cluster_name()))
    if NO_RECOMPUTE:
        print("rows indexed; skipping recompute (--no-recompute)")
    else:
        recompute_and_report()
