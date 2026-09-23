#!/bin/sh
# Certifications (spec 2026-09-23): server checks of the submitevaluation pass hook, state.json's top-level certifications
# array and startevaluation's 409 valid_until refusal, against a local eMe as cert.check@testu.local. LOCAL ONLY. Header
# (guards, session, call, ok, must, login, es_ids, delete_rows, refresh, put_row, es_doc, usersave, make_user, audits,
# blueprint, wipe_user_rows) copied verbatim from check_evaluation.sh, adapted to a single fixed topic/user.
# Usage: EME_USER=... EME_PASSWORD=... plugins/testu/tools/check_certification.sh   (from the server root)
set -eu
exec python3 - "$@" <<'PY'
import json, os, secrets, sys, urllib.error, urllib.request
from http.cookiejar import CookieJar
from urllib.parse import quote, urlencode, urlparse

B = os.environ.get("EME_BASE", "http://localhost:8080/site/mediadb")
ES = os.environ.get("ES", "http://localhost:9200/site_catalog")
for u in (B, ES):
    if urlparse(u).hostname not in ("localhost", "127.0.0.1"):
        sys.exit(f"refusing non-local host: {u}")
if not os.environ.get("EME_USER") or not os.environ.get("EME_PASSWORD"):
    sys.exit("set EME_USER and EME_PASSWORD (a local admin)")
FAILS = []


def session():
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))


def call(op, method, path, body=None, form=None, base=B):
    data, headers = None, {}
    if body is not None:
        data, headers = json.dumps(body).encode(), {"Content-Type": "application/json"}
    elif form is not None:
        data, headers = urlencode(form).encode(), {"Content-Type": "application/x-www-form-urlencoded"}
    req = urllib.request.Request(base + path, data=data, method=method, headers=headers)
    try:
        with op.open(req, timeout=300) as r:
            raw, status = r.read(), r.status
    except urllib.error.HTTPError as e:
        raw, status = e.read(), e.code
    try:
        return status, json.loads(raw)
    except ValueError:
        return status, raw


def ok(name, cond, detail=""):
    print(("ok: " if cond else "FAIL: ") + name + ("" if cond else f" -> {str(detail)[:600]}"))
    if not cond:
        FAILS.append(name)


def must(what, res):
    if res[0] // 100 != 2:
        raise SystemExit(f"{what} failed: HTTP {res[0]} {str(res[1])[:300]}")
    return res[1]


def login(user, password):
    op = session()
    st, body = call(op, "POST", "/services/authentication/login.json", body={"id": user, "password": password})
    if st != 200 or body.get("response", {}).get("status") != "ok":
        raise SystemExit(f"login {user} failed: {st} {body}")
    return op


admin = login(os.environ["EME_USER"], os.environ["EME_PASSWORD"])
es = session()


def es_ids(table, query):
    st, res = call(es, "POST", f"/{table}/_search", body={"size": 10000, "query": query, "_source": False}, base=ES)
    return [h["_id"] for h in res.get("hits", {}).get("hits", [])] if st == 200 else []


def delete_rows(table, ids):
    for i in ids:
        call(admin, "DELETE", f"/services/lists/data/{table}/{quote(i)}.json")


def refresh():
    call(es, "POST", "/_refresh", body={}, base=ES)


USER_TABLES = ("tutoranswer", "tutorexposure", "dailychallengeset", "learningsession", "tutormastery", "tutordaily", "subtopicunlock", "evaluationattempt")


def wipe_user_rows(user):
    refresh()
    for t in USER_TABLES:
        delete_rows(t, es_ids(t, {"term": {"user": user}}))


def usersave(field, value, user):
    must(f"usersave {field}", call(admin, "POST", "/services/authentication/usersave.json", form={"username": user, "field": field, field + "value": value}))


def make_user(email, password, role="users"):
    users = must("users.json", call(admin, "GET", "/services/testu/personas/users.json"))["users"]
    if next((u for u in users if u["id"] == email), None) is None:
        must("createuser", call(admin, "POST", "/services/testu/personas/createuser.json", form={"email": email, "firstName": "Cert", "lastName": "Check", "role": role}))
    else:
        usersave("enabled", "true", email)
    usersave("password", password, email)
    usersave("jobrole", "", email)
    usersave("primaryjobrole", "", email)


def put_row(table, rid, body):
    body = dict(body, id=rid)
    must(f"{table} {rid}", call(admin, "PUT", f"/services/lists/data/{table}/{quote(rid)}.json", body=body))


def es_doc(table, rid):
    refresh()
    st, raw = call(es, "GET", f"/{table}/{quote(rid, safe='')}", base=ES)
    return raw.get("_source") if st == 200 and raw.get("found") else None


AUDIT_SINCE = [""]


def audits(action, target):
    # auditevent rows are saved through eMe's queue: poll until at least one shows up (a genuinely missing row still fails, just later).
    for attempt in range(24):
        refresh()
        st, res = call(es, "POST", "/auditevent/_search", body={"size": 50, "sort": [{"datecreated": "desc"}], "query": {"bool": {"must": [
            {"match_phrase": {"targetid": target}}, {"match_phrase": {"action": action}}]}}}, base=ES)
        rows = [h["_source"] for h in res["hits"]["hits"]] if st == 200 else []
        if [r for r in rows if str(r.get("datecreated", ""))[:19] >= AUDIT_SINCE[0][:19]]:
            break
        import time
        time.sleep(0.5)
    if st != 200:
        return []
    return [h["_source"] for h in res["hits"]["hits"] if str(h["_source"].get("datecreated", ""))[:19] >= AUDIT_SINCE[0][:19]]


BP = []
VERSION = [0]


def blueprint(**f):
    """Seeds the next blueprint version of T directly (this check exercises the learner side, not the admin endpoint)."""
    import datetime
    VERSION[0] += 1
    rid = f"{T}_v{VERSION[0]}"
    body = {"entitytopic": T, "blueprintversion": str(VERSION[0]), "active": "true", "strategy": "random", "maxquestions": "4",
            "minpersubtopic": "0", "excludedsections": "[]", "difficultymix": "proportional", "passpercent": "60", "subtopicminpercent": "0",
            "timerminutes": "0", "retakewaithours": "0", "maxattempts": "0", "requirelearncomplete": "false", "user": os.environ["EME_USER"],
            "datecreated": datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%S.000Z")}
    body.update({k: str(v) for k, v in f.items()})
    put_row("evaluationblueprint", rid, body)
    BP.append(rid)
    refresh()


def state_all():
    return must("state", call(me, "GET", "/services/testu/learn/state.json"))


def topic_of(st, tid):
    return next(t for t in st["topics"] if t["id"] == tid)


def state(topic):
    return topic_of(state_all(), topic)


def today_ymd():
    return (state_all().get("now") or "0")[:10]


def ev():
    return call(me, "GET", f"/services/testu/learn/evaluation.json?topicid={quote(T)}")


def start():
    return call(me, "POST", "/services/testu/learn/startevaluation.json", form={"topicid": T})


def submit(sid):
    return call(me, "POST", "/services/testu/learn/submitevaluation.json", form={"sessionid": sid})


def answer(sid, qid, option):
    form = {"mode": "evaluation", "scopetype": "topic", "scopeid": T, "questionid": qid, "sessionid": sid, "selectedoption": option,
            "confidence": "confident", "hintlevel": "0", "attemptid": "cc" + secrets.token_hex(8)}
    return call(me, "POST", "/services/testu/learn/answer.json", form=form)


fid = lambda v: v.get("id") if isinstance(v, dict) else v


def correct_option(it):
    q = es_doc("entityquestion", it["questionid"])
    return fid(q.get("correctoption")).upper()


def wrong_option(it):
    q = es_doc("entityquestion", it["questionid"])
    c = fid(q.get("correctoption")).upper()
    return next(o for o in "ABCD" if o != c and (q.get("option_" + o.lower()) or "").strip())


T = "ciberseguridad"                      # a topic with sequence questions on the local server
USER = "cert.check@testu.local"
PASSWORD = "Cc9-" + secrets.token_urlsafe(24)
ROLE, ROW = "ccheck-role", "ccheck-role_" + T
BP_EXTRA = [("jobrole", ROLE), ("topicrequirement", ROW), ("certification", USER + "_" + T)]


def cleanup():
    for t, i in BP_EXTRA:
        delete_rows(t, [i])
    delete_rows("evaluationblueprint", BP)
    wipe_user_rows(USER)
    refresh()


try:
    make_user(USER, PASSWORD, role="users")
    put_row("jobrole", ROLE, {"id": ROLE, "name": "Cert check"})
    put_row("topicrequirement", ROW, {"id": ROW, "jobrole": ROLE, "entitytopic": T, "position": "1", "mandatory": "true",
            "afterfinish": "keep", "validitymonths": "6", "passpercent": "50", "renewalwindowdays": "30"})
    must("assign profile", call(admin, "POST", "/services/testu/personas/setprofiles.json", form={"user": USER, "primary": ROLE, "extras": "[]"}))
    blueprint(active="true", strategy="random", maxquestions="4", passpercent="90", requirelearncomplete="false", timerminutes="0", retakewaithours="0", maxattempts="0")
    refresh()
    me = login(USER, PASSWORD)
    AUDIT_SINCE[0] = ev()[1]["now"]  # evaluation.json's own clock (pre-existing field), so this doesn't depend on state.json's new "now"
    st = state(T)
    c = st["certification"]
    ok("state: certification block not_certified", c and c["status"] == "not_certified" and c["passpercent"] == 50 and c["validitymonths"] == 6, c)
    ok("state: top-level certifications lists the topic", any(x["topic"] == T and x["status"] == "not_certified" for x in state_all().get("certifications", [])), "")
    s = start()
    ok("start: 200", s[0] == 200, s)
    s = s[1]
    sid = s["sessionid"]
    # answer half right: passes at 50 (profile), would fail at 90 (plan)
    items = s["items"]
    for i, it in enumerate(items):
        answer(sid, it["questionid"], correct_option(it) if i % 2 == 0 else wrong_option(it))
    st2, r = submit(sid)
    ok("submit: passpercent used = 50 and passed", r["passpercent"] == 50 and r["passed"] is True, r)
    cb = r.get("certification") or {}
    ok("submit: certification block certified with expiry ~6 months", cb.get("status") == "certified" and (cb.get("expiry") or "") > today_ymd(), cb)
    row = es_doc("certification", USER + "_" + T)
    ok("row written: passedat/attemptid/validuntil", row and row.get("attemptid") == sid and row.get("validuntil"), row)
    ok("audit certification.pass", audits("certification.pass", USER + "_" + T), "")
    st3, e = ev()
    ok("evaluation: certified -> not_available valid_until", e["status"] == "not_available" and e["reason"] == "valid_until" and e.get("validuntil"), e)
    st4, body = call(me, "POST", "/services/testu/learn/startevaluation.json", form={"topicid": T})
    ok("start: 409 valid_until", st4 == 409 and body.get("reason") == "valid_until", body)
finally:
    cleanup()
print("certification checks: " + ("PASS" if not FAILS else f"{len(FAILS)} FAILED: {FAILS}"))
sys.exit(1 if FAILS else 0)
PY
