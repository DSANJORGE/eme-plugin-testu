#!/usr/bin/env python3
"""LOCAL ONLY demo cohort for the console (24 people, 3 teams, 21 days).
LOCAL ONLY. Never point this at minsur.genailabs.tech.
Refuses non-localhost hosts. --wipe removes what it created (demo.* only).

Usage:  python3 tools/seed_demo_cohort.py [--wipe] [--no-recompute]

Rows go straight into Elasticsearch for tutoranswer / usageevent / chatterbox
(no module route for those entities); users and teams go through the personas
endpoints. Every id is deterministic (demo-<kind>-<n>, random.seed(7)) so a
rerun overwrites instead of duplicating.
"""
import datetime
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
for _u in (BASE, ES):
    if urlparse(_u).hostname not in ("localhost", "127.0.0.1"):
        sys.exit(f"refusing non-local host: {_u}")
USER = os.environ.get("EME_USER", "admin")
PASSWORD = os.environ.get("EME_PASSWORD", "admin")
ES_ROOT, ES_INDEX = ES.rsplit("/", 1)
random.seed(7)

WIPE = "--wipe" in sys.argv
NO_RECOMPUTE = "--no-recompute" in sys.argv

# ---------------------------------------------------------------- http
# Helpers copied from tools/seed_lists.py: that file logs in and seeds at import time, so it cannot be imported.
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))


def call(url, data=None, content_type="application/json", timeout=120):
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


def login():
    status, body = form(f"{BASE}/services/authentication/login.json", id=USER, password=PASSWORD)
    if status // 100 != 2 or json.loads(body)["response"]["status"] != "ok":
        sys.exit(f"login failed: {status} {body!r}")


def es_search(entity, body):
    status, raw = call(f"{ES}/{entity}/_search", json.dumps(body).encode())
    if status // 100 != 2:
        sys.exit(f"ES search {entity} failed: {status} {raw!r}")
    return json.loads(raw)


def es_bulk(lines):
    """lines: list of (action_dict, source_or_None). Chunked, then refreshed. Fails loud."""
    for i in range(0, len(lines), 500):
        payload = "".join(
            json.dumps(a) + "\n" + ("" if s is None else json.dumps(s, ensure_ascii=False) + "\n")
            for a, s in lines[i:i + 500]
        ).encode()
        status, raw = call(f"{ES}/_bulk", payload, "application/x-ndjson")
        if status // 100 != 2:
            sys.exit(f"ES bulk failed: {status} {raw!r}")
        res = json.loads(raw)
        if res.get("errors"):
            bad = [it for it in res["items"] if list(it.values())[0].get("error")][:3]
            sys.exit(f"ES bulk errors: {json.dumps(bad)[:800]}")
    call(f"{ES}/_refresh", b"")


# ---------------------------------------------------------------- cohort definition
TEAMS = [  # saveteam.groovy: id must match [a-z0-9]+ (no hyphens), and it nulls every field not passed
    {"id": "demoops", "name": "Operaciones Pisco", "parent": "", "location": "Pisco", "costcenter": "CC-1001"},
    {"id": "demomant", "name": "Mantenimiento", "parent": "", "location": "Pisco", "costcenter": "CC-1002"},
    {"id": "demoadm", "name": "Administración Lima", "parent": "", "location": "Lima", "costcenter": "CC-2001"},
]
MANAGED_TEAM = "demoops"
MANAGER_N = 9  # demo.09 runs Operaciones Pisco, so the manager view has something to show
# createuser.groovy gives every user a random secret (ruling R8), so nobody could actually sign in as the
# manager. Local demo only: give that one account a known password, exactly as tools/check_analytics.sh does.
MANAGER_PASSWORD = "Demopass123"

NAMES = [
    ("Ana", "Quispe"), ("Luis", "Mamani"), ("Rosa", "Huamán"), ("Carlos", "Ticona"),
    ("Marta", "Chura"), ("Jorge", "Apaza"), ("Elena", "Ccama"), ("Pedro", "Yupanqui"),
    ("Sofía", "Vargas"), ("Miguel", "Chávez"), ("Lucía", "Paredes"), ("Raúl", "Sánchez"),
    ("Nuria", "Ramos"), ("Iván", "Castillo"), ("Paula", "Rojas"), ("Diego", "Salazar"),
    ("Karina", "Flores"), ("Óscar", "Peralta"), ("Fiorella", "Cárdenas"), ("Bruno", "Zegarra"),
    ("Milagros", "Navarro"), ("Andrés", "Bustos"), ("Claudia", "Ferrer"), ("Renzo", "Ibáñez"),
]
TEAM_OF = ["demoops"] * 9 + ["demomant"] * 8 + ["demoadm"] * 7   # 24 people, 3 teams

# level target -> share of last attempts correct. aggregate.groovy: <0.5 beginner, <0.9 competent, else expert.
PROFILE = {n: ("competent", 0.75) for n in range(1, 25)}
for _n in (1, 4, 10, 18, 19):
    PROFILE[_n] = ("expert", 0.95)
for _n in (7, 8, 14, 16, 17, 21):
    PROFILE[_n] = ("beginner", 0.42)
PROFILE[5] = ("atrisk", 0.33)      # many confident-wrong answers -> misconceptions + calibration signal
for _n in (12, 23):
    PROFILE[_n] = ("idle", 0.0)    # never started: lands in `notstarted` and in the inactive list
INACTIVE = {7, 14, 21}             # answered, but nothing in the last 12+ days

QUESTIONS = [
    "¿Qué diferencia hay entre un impacto y un riesgo en derechos humanos?",
    "¿Quién es responsable de aplicar la debida diligencia en una operación minera?",
    "¿En qué casos una empresa puede ser responsable por su cadena de suministro?",
    "¿Cómo se documenta una denuncia si el denunciante quiere permanecer anónimo?",
    "¿Qué plazo tiene la empresa para responder un reclamo de la comunidad?",
    "¿Puedo usar el canal de denuncias si el hecho lo cometió mi jefe directo?",
    "¿Qué significa consentimiento libre, previo e informado en la práctica?",
    "¿Los contratistas están cubiertos por la misma política de derechos humanos?",
    "¿Cuál es la diferencia entre remediación y compensación económica?",
    "¿Qué hago si detecto trabajo infantil en un proveedor local?",
    "¿Un acuerdo firmado con la comunidad reemplaza la consulta previa?",
    "¿Cómo se prioriza un riesgo cuando afecta a un grupo vulnerable?",
    "¿Qué evidencia se necesita para cerrar un plan de acción de DDHH?",
    "¿Puede un trabajador negarse a una tarea por motivos de derechos humanos?",
    "¿Qué rol tiene el área de seguridad física frente a los Principios Voluntarios?",
    "¿Cómo se mide si un mecanismo de reclamación realmente funciona?",
    "¿Qué debo hacer si recibo un correo que pide mis credenciales?",
    "¿Cómo distingo un phishing bien hecho de un correo interno legítimo?",
    "¿Puedo conectar mi celular personal a la red de planta?",
    "¿Qué hago si sospecho que mi equipo tiene ransomware?",
    "¿Es seguro usar el mismo gestor de contraseñas para lo personal y lo laboral?",
    "¿Por qué el segundo factor no basta si apruebo cualquier notificación?",
    "¿Qué información no debo pegar nunca en una herramienta de IA?",
    "¿Cómo verifico que una tienda en línea no es fraudulenta?",
    "¿Qué diferencia hay entre malware y ransomware en la práctica?",
    "¿Debo reportar un incidente aunque no esté seguro de que lo sea?",
    "¿Cómo protejo la información cuando trabajo desde una red pública?",
    "¿Qué señales indican que una noticia es desinformación?",
    "¿Me pueden pedir el celular en una auditoría de seguridad?",
    "¿Qué pasa si comparto un archivo interno por WhatsApp?",
]
REPLIES = [
    "La distinción está en el material del curso: el riesgo es la posibilidad de afectación y el impacto es la afectación ya ocurrida. La debida diligencia trabaja sobre ambos, con acciones distintas.",
    "La responsabilidad es de la línea operativa, con acompañamiento del área de sostenibilidad. El curso lo desarrolla en la sección sobre gobernanza.",
    "Sí, siempre que la empresa haya contribuido al impacto o esté directamente vinculada a él por su relación comercial. La medida de la respuesta cambia en cada caso.",
    "El canal admite denuncias anónimas y registra el caso igual. Lo que cambia es la capacidad de pedir información adicional al denunciante.",
    "El estándar interno es acusar recibo en 5 días hábiles y dar una respuesta de fondo en 30. Los casos graves se escalan de inmediato.",
    "Sí. El canal está diseñado para que la línea jerárquica no bloquee un reporte, y prevé separar el caso del jefe involucrado.",
    "Es un proceso, no una firma: información suficiente, tiempo para deliberar, sin presión y antes de la decisión. Un acta por sí sola no lo acredita.",
    "Sí. La política aplica a contratistas y subcontratistas, y forma parte de las cláusulas contractuales.",
    "La remediación busca devolver a la persona a la situación anterior al daño; la compensación es solo una de las formas de hacerlo.",
    "Se detiene la relación con ese proveedor y se activa el protocolo con la autoridad competente. Nunca se resuelve de forma bilateral.",
    "No. Un acuerdo puede complementar la consulta, pero no sustituye el proceso cuando la norma lo exige.",
    "Se pondera la severidad sobre la probabilidad, y la pertenencia a un grupo vulnerable eleva la severidad.",
    "Evidencia verificable del cierre: registro documental, verificación en campo y validación de la parte afectada cuando corresponde.",
    "Sí, y la política protege esa negativa frente a represalias.",
    "Los Principios Voluntarios rigen el uso de la fuerza y la relación con la seguridad pública y privada; el área debe entrenarse en ellos.",
    "Por el uso real, no por el diseño: casos recibidos, tiempos de respuesta y satisfacción de quien reclama.",
    "Nunca respondas y reporta el correo al canal de seguridad. Ninguna área interna pide credenciales por correo.",
    "Revisa el dominio real del remitente, el tono de urgencia y los enlaces. Ante la duda, verifica por otro canal.",
    "Solo a la red de invitados. La red de planta está segmentada y no admite equipos personales.",
    "Desconecta el equipo de la red sin apagarlo y llama a la mesa de ayuda de inmediato.",
    "Es preferible separarlos. Si el gestor personal se ve comprometido, no arrastra las credenciales corporativas.",
    "Porque aprobar sin verificar convierte el segundo factor en un trámite: el atacante solo necesita insistir hasta que apruebes.",
    "Datos personales, información de clientes, credenciales y cualquier documento clasificado. Si dudas, no lo pegues.",
    "Verifica el dominio, la antigüedad del sitio, los medios de pago y la existencia de datos de contacto reales.",
    "El ransomware es un tipo de malware que cifra la información y pide rescate; el resto del malware persigue otros fines.",
    "Sí. Un falso positivo cuesta minutos; un incidente no reportado puede costar días de operación.",
    "Usa la VPN corporativa y evita abrir sesiones sensibles en redes abiertas.",
    "Fuente no identificable, urgencia emocional, ausencia de fecha y falta de confirmación en medios independientes.",
    "Solo bajo el procedimiento formal y con tu consentimiento informado, según la política vigente.",
    "Queda fuera de los controles corporativos. El canal aprobado es el repositorio interno con permisos.",
]
CITATION = " [Guía DDHH, p. 12]"   # the event's `cited` regex wants [Title, p. N] at the very end
CONF = ["noidea", "notsure", "mostlysure", "confident"]


def iso(dt):
    """Naive local datetime -> yyyy-MM-dd'T'HH:mm:ss.SSSZ. A date written without milliseconds makes the
    server's getDate() return null and the row is silently skipped by the event.
    astimezone() on a naive value resolves the local offset *for that date*, so the 21-day span stays at
    07:00-20:00 local even across a DST boundary."""
    return dt.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def email(n):
    return f"demo.{n:02d}@testu.local"


def cluster_name():
    status, raw = call(f"{ES_ROOT}/")
    if status // 100 != 2:
        return "entermedia-testcluster"
    return json.loads(raw).get("cluster_name", "entermedia-testcluster")


# ---------------------------------------------------------------- wipe
DEMO_TYPES = ["tutoranswer", "usageevent", "chatterbox", "tutormastery", "tutordaily", "tutorquestion"]


def demo_rows(entity):
    """Rows this seeder created: a `demo-` id (that catches the agent reply rows, whose user is "agent")
    or a `demo.` user (that catches the derived tutormastery/tutordaily/tutorquestion rows, whose ids the
    event mints). Filtered client-side so there are no mapping assumptions, and nothing else can match.
    ponytail: one 10k window instead of a scroll; these types stay in the low thousands locally."""
    res = es_search(entity, {"size": 10000, "query": {"match_all": {}}, "_source": ["user"]})
    total = res["hits"]["total"]
    if total > 10000:
        sys.exit(f"{entity} has {total} rows, above the 10000 window -- add scrolling before wiping")
    return [h["_id"] for h in res["hits"]["hits"]
            if h["_id"].startswith("demo-") or str(h["_source"].get("user", "")).startswith("demo.")]


def wipe():
    login()
    deletes = []
    for entity in DEMO_TYPES:
        ids = demo_rows(entity)
        print(f"wipe {entity}: {len(ids)}")
        deletes += [({"delete": {"_index": ES_INDEX, "_type": entity, "_id": i}}, None) for i in ids]
    if deletes:
        es_bulk(deletes)
    # personas has no delete for users, only disableuser.json; a disabled user is dropped by
    # aggregate.groovy's isLearner(), so it leaves the cohort exactly as a deletion would.
    status, raw = call(f"{BASE}/services/testu/personas/users.json")
    users = [u["id"] for u in json.loads(raw)["users"] if u["id"].startswith("demo.") and u["enabled"]]
    for uid in users:
        form(f"{BASE}/services/testu/personas/disableuser.json", userid=uid)
    print(f"wipe users: {len(users)} disabled (personas offers no delete endpoint)")
    print("wipe teams: left in place (no delete endpoint; empty demo teams are inert)")
    if not NO_RECOMPUTE:
        recompute_and_report(seeding=False)


# ---------------------------------------------------------------- seed
def ensure_people():
    status, raw = call(f"{BASE}/services/testu/personas/users.json")
    existing = {u["id"]: u for u in json.loads(raw)["users"]}
    for t in TEAMS:   # teams first: createuser rejects an unknown team
        form(f"{BASE}/services/testu/personas/saveteam.json", manager="", **t)
    created = reenabled = 0
    for n in range(1, 25):
        uid, (first, last) = email(n), NAMES[n - 1]
        team, role = TEAM_OF[n - 1], ("manager" if n == MANAGER_N else "users")
        if uid not in existing:
            st, body = form(f"{BASE}/services/testu/personas/createuser.json",
                            email=uid, firstName=first, lastName=last, role=role, team=team)
            if st // 100 != 2:
                sys.exit(f"createuser {uid} failed: {st} {body!r}")
            created += 1
            continue
        if not existing[uid]["enabled"]:   # a previous --wipe disabled it; bring it back
            form(f"{BASE}/services/authentication/usersave.json", username=uid, field="enabled", enabledvalue="true")
            reenabled += 1
        form(f"{BASE}/services/testu/personas/setteam.json", userid=uid, team=team)
        form(f"{BASE}/services/testu/personas/setrole.json", userid=uid, role=role)
    for t in TEAMS:   # re-save with the manager, now that the user exists
        form(f"{BASE}/services/testu/personas/saveteam.json",
             manager=(email(MANAGER_N) if t["id"] == MANAGED_TEAM else ""), **t)
    form(f"{BASE}/services/authentication/usersave.json", username=email(MANAGER_N),
         field="password", passwordvalue=MANAGER_PASSWORD)
    print(f"people: 3 teams, 24 users ({created} created, {reenabled} re-enabled), "
          f"manager {email(MANAGER_N)} / {MANAGER_PASSWORD} of {MANAGED_TEAM}")


def read_content():
    secs = es_search("componentsection", {"size": 200, "query": {"term": {"playbackentitymoduleid": "entitytutorial"}},
                                          "_source": ["playbackentityid"]})["hits"]["hits"]
    tutorial = {h["_id"]: h["_source"].get("playbackentityid") for h in secs}
    mcq = es_search("componentcontent", {"size": 1000, "query": {"term": {"componenttype": "mcq"}},
                                         "_source": ["componentsectionid", "questionid"]})["hits"]["hits"]
    qs = {}
    for h in mcq:
        s = h["_source"]
        if s.get("questionid"):
            qs.setdefault(str(s["componentsectionid"]), set()).add(str(s["questionid"]))
    qs = {k: sorted(v) for k, v in qs.items() if k in tutorial}
    if not qs:
        sys.exit("no tutorial sections with MCQs found -- is the local catalog loaded?")
    print(f"content: {len(qs)} sections, {sum(len(v) for v in qs.values())} MCQs")
    return tutorial, qs


def confidence(correct, level):
    if level == "atrisk" or correct:   # at risk = certain and wrong, which is the point of the profile
        return random.choices(CONF, [0.05, 0.15, 0.35, 0.45])[0]
    return random.choices(CONF, [0.25, 0.45, 0.20, 0.10])[0]


def build_rows(tutorial, qs, cluster):
    """All rows for the cohort, deterministic under random.seed(7)."""
    end = datetime.date.today() - datetime.timedelta(days=1)   # keep the span inside a to=yesterday report window
    start = end - datetime.timedelta(days=20)                  # 21 days inclusive
    pool = [d for d in (start + datetime.timedelta(days=i) for i in range(21))
            for _ in range(3 if d.weekday() < 5 else 1)]       # weekday-heavy
    # Local catalog: everyone studies the DDHH course (the one the seeded citations quote) and every
    # second person also studies Ciberseguridad, so both entitytopic rollups have people in them.
    # If the cyber course is not loaded, `cyber` is empty and everybody just does DDHH.
    ddhh = sorted(s for s, t in tutorial.items() if t != "ciberseguridad-1" and s in qs)
    cyber = sorted(s for s, t in tutorial.items() if t == "ciberseguridad-1" and s in qs)
    lines, active, usersections = [], {}, {}
    counters = {"ans": 0, "ev": 0}
    now = iso(datetime.datetime.now())
    stat = {"recorddeleted": False, "mastereditclusterid": cluster, "lastmodifiedclusterid": cluster,
            "recordmodificationdate": now, "masterrecordmodificationdate": now}

    def put(entity, rid, src):
        src["id"] = rid
        src["emrecordstatus"] = stat
        lines.append(({"index": {"_index": ES_INDEX, "_type": entity, "_id": rid}}, src))

    def usage(uid, at, etype, **extra):
        counters["ev"] += 1
        put("usageevent", f"demo-ev-{counters['ev']}",
            dict(user=uid, datecreated=iso(at), type=etype, platform="ios", appversion="1.0.0-demo", **extra))

    for n in range(1, 25):
        uid, (level, p) = email(n), PROFILE[n]
        if level == "idle":
            active[n], usersections[n] = [], []
            continue
        secs = random.sample(ddhh, min(2, len(ddhh)))
        if n % 2 == 0 and cyber:
            secs += random.sample(cyber, min(2, len(cyber)))
        usersections[n] = secs
        # 3-7 study days; the "inactive" profiles only ever touch the first third of the span
        choices = [d for d in pool if d <= end - datetime.timedelta(days=12)] if n in INACTIVE else pool
        active[n] = sorted(set(random.sample(choices, min(random.randint(3, 7), len(set(choices))))))
        plan = []   # (section, questionid, correct) in answering order; the last attempt per question sets mastery
        for s in secs:
            for q in random.sample(qs[s], min(random.randint(9, 18), len(qs[s]))):
                ok = random.random() < p
                if random.random() < 0.22:      # a wrong first try before the attempt that counts
                    plan.append((s, q, False))
                plan.append((s, q, ok))
        per_day = max(1, -(-len(plan) // len(active[n])))
        for di, day in enumerate(active[n]):
            chunk = plan[di * per_day:(di + 1) * per_day]
            if not chunk:
                continue
            at = datetime.datetime.combine(day, datetime.time(random.randint(7, 17), random.randint(0, 59)))
            first = at
            for s, q, ok in chunk:
                at += datetime.timedelta(seconds=random.randint(35, 190))
                if at.hour >= 20:
                    at = at.replace(hour=19, minute=random.randint(0, 59))
                counters["ans"] += 1
                put("tutoranswer", f"demo-ans-{counters['ans']}", {
                    "user": uid, "entitytutorial": tutorial[s], "componentsection": s, "entityquestion": q,
                    "selectedoption": random.choice("ABCDE"), "answerconfidence": confidence(ok, level),
                    "iscorrect": "true" if ok else "false", "channel": f"demo-ch-{n:02d}",
                    "datecreated": iso(at), "lastpenalty": iso(at), "pointsearned": 0.0, "bonusearned": 0.0})
            # foreground spans: one `open`, then resume/pause pairs (only `pause` carries the seconds)
            usage(uid, first - datetime.timedelta(minutes=2), "open", sessionid=f"demo-s-{n:02d}-{di}")
            span = first
            for k in range(random.randint(1, 3)):
                length = random.randint(300, 1500)
                sid = f"demo-s-{n:02d}-{di}-{k}"
                usage(uid, span, "resume", sessionid=sid)
                usage(uid, span + datetime.timedelta(seconds=length), "pause", sessionid=sid, seconds=length)
                span += datetime.timedelta(seconds=length + random.randint(120, 900))

    # ---- IRIS: one chatterbox pair per ask, half the replies cited, ~55% rated
    askers = [n for n in range(1, 25) if active[n]]
    pairs = 0
    for i in range(66):
        n = askers[i % len(askers)]
        uid, s = email(n), random.choice(usersections[n])
        at = datetime.datetime.combine(random.choice(active[n]),
                                       datetime.time(random.randint(7, 19), random.randint(0, 59)))
        pairs += 1
        channel, qid = f"demo-ch-q{pairs:02d}", random.choice(qs[s])
        put("chatterbox", f"demo-q-{pairs}", {
            "user": uid, "channel": channel, "date": iso(at), "messagetype": "system",
            "functionname": "chat_tutor_usercomment", "chatmessagestatus": "completed",
            "agentcontextvalues": json.dumps({"skiploader": "true", "componentid": s,
                                              "query": QUESTIONS[(i * 7 + n) % len(QUESTIONS)],
                                              "sectionid": s, "questionid": qid,
                                              "tutorialid": tutorial[s]}, ensure_ascii=False)})
        reply_at = at + datetime.timedelta(minutes=1)
        put("chatterbox", f"demo-a-{pairs}", {
            "user": "agent", "channel": channel, "date": iso(reply_at), "replytoid": f"demo-q-{pairs}",
            "message": REPLIES[(i * 11 + n) % len(REPLIES)] + (CITATION if pairs % 2 == 0 else ""),
            "functionname": "chat_tutor_usercomment", "chatmessagestatus": "completed"})
        if random.random() < 0.55:   # same channel + user, inside the event's 10-minute join window
            usage(uid, reply_at + datetime.timedelta(seconds=random.randint(30, 400)), "iris_rate",
                  channel=channel, componentsection=s, entityquestion=qid,
                  rating="helpful" if random.random() < 0.75 else "nothelpful")
    print(f"rows: {counters['ans']} tutoranswer, {counters['ev']} usageevent, {pairs} chatterbox pairs, "
          f"{start.isoformat()}..{end.isoformat()}")
    return lines


# ---------------------------------------------------------------- recompute + report
def recompute_and_report(seeding=True):
    status, raw = call(f"{BASE}/services/testu/analytics/recompute.json", b"")
    if status // 100 != 2:
        sys.exit(f"recompute failed: {status} {raw!r}")
    deadline, o = time.time() + 300, {}
    while time.time() < deadline:
        time.sleep(6)
        status, raw = call(f"{BASE}/services/testu/analytics/overview.json")
        o = json.loads(raw) if status // 100 == 2 else {}
        if not o.get("ok"):
            continue
        if seeding and o["cohort"]["activated"] >= 20 and o["iris"]["questions"] >= 50:
            break
        if not seeding and o["iris"]["questions"] < 60:   # the rebuild has pruned the demo rows
            break
    if not o.get("ok"):
        print("overview.json not ready; re-run tools/check_analytics.sh once the event finishes")
        return
    c, ir = o["cohort"], o["iris"]
    print(f"cohort: total={c['total']} activated={c['activated']} active30d={c['active30d']} active7d={c['active7d']}")
    print(f"levels: {o['levels']}")
    print(f"iris: questions={ir['questions']} people={ir['people']} cited={ir['citedShare']} "
          f"rated={ir['ratedShare']} helpful={ir['helpfulShare']}")


if WIPE:
    wipe()
else:
    login()
    ensure_people()
    tutorials, questions = read_content()
    es_bulk(build_rows(tutorials, questions, cluster_name()))
    if NO_RECOMPUTE:
        print("rows indexed; skipping recompute (--no-recompute)")
    else:
        recompute_and_report()
