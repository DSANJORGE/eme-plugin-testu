#!/bin/sh
# Job profiles (spec docs/superpowers/specs/2026-09-16-job-profiles-design.md): server checks against a local eMe as
# profile.check@testu.local. LOCAL ONLY. Writes and removes: jobrole pcheck-*, topicrequirement pcheck-*, the user's learning rows.
# Usage: EME_USER=... EME_PASSWORD=... plugins/testu/tools/check_profiles.sh   (from the server root)
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
USER, PASSWORD = "profile.check@testu.local", "Pc9-" + secrets.token_urlsafe(24)
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


USER_TABLES = ("tutoranswer", "tutorexposure", "dailychallengeset", "learningsession", "tutormastery", "tutordaily", "subtopicunlock")


def wipe_user_rows():
    refresh()
    for t in USER_TABLES:
        delete_rows(t, es_ids(t, {"term": {"user": USER}}))


def usersave(field, value):
    must(f"usersave {field}", call(admin, "POST", "/services/authentication/usersave.json", form={"username": USER, "field": field, field + "value": value}))


def put_row(table, rid, body):
    body = dict(body, id=rid)
    must(f"{table} {rid}", call(admin, "PUT", f"/services/lists/data/{table}/{quote(rid)}.json", body=body))


def state(topic=None):
    return must("state", call(me, "GET", "/services/testu/learn/state.json" + (f"?topicid={topic}" if topic else "")))


def topic_of(st, tid):
    return next(t for t in st["topics"] if t["id"] == tid)


def nxt(**params):
    return call(me, "GET", "/services/testu/learn/next.json?" + urlencode(params))


# entityquestion rows, keyed by id: correctoption may be a plain string or a {id: ...} map (see check_learning.sh).
qhits = must("entityquestion search", call(admin, "GET", "/services/lists/search/entityquestion/search.json?hitsperpage=5000"))["results"]
QROW = {q["id"]: q for q in qhits}
fid = lambda v: v.get("id") if isinstance(v, dict) else v

P1, P2 = "pcheck-pilot", "pcheck-safety"
ROWS = []
try:
    # ---- setup: learner, two profiles
    users = must("users.json", call(admin, "GET", "/services/testu/personas/users.json"))["users"]
    if next((u for u in users if u["id"] == USER), None) is None:
        must("createuser", call(admin, "POST", "/services/testu/personas/createuser.json", form={"email": USER, "firstName": "Profile", "lastName": "Check", "role": "users"}))
    else:
        usersave("enabled", "true")
    usersave("password", PASSWORD)
    usersave("jobrole", "")
    usersave("primaryjobrole", "")
    wipe_user_rows()
    me = login(USER, PASSWORD)
    base = state()
    TOPICS = [t["id"] for t in base["topics"] if t["questions"] > 0]
    if len(TOPICS) < 3:
        sys.exit("need at least 3 learner-visible topics with questions")
    T1, T2, T3 = TOPICS[:3]
    ok("no profile: no position, no locks, no profiles", all(t.get("position") is None and not t.get("locked") for t in base["topics"]) and base.get("profiles") == [] and base.get("removedtopics") == [], base.get("profiles"))

    put_row("jobrole", P1, {"name": "Pcheck Pilot"})
    put_row("jobrole", P2, {"name": "Pcheck Safety"})
    for rid, role, tid, pos, lvl, mand, prev, after in (
        ("pcheck-r1", P1, T3, 1, "", "true", "false", "remove"),
        ("pcheck-r2", P1, T1, 2, "competent", "true", "true", "keep"),
        ("pcheck-r3", P2, T2, 1, "expert", "false", "false", "keep"),
        ("pcheck-r4", P2, T1, 2, "expert", "true", "false", "keep"),
    ):
        put_row("topicrequirement", rid, {"jobrole": role, "entitytopic": tid, "position": str(pos), "requiredlevel": lvl, "mandatory": mand, "requiresprevious": prev, "afterfinish": after})
        ROWS.append(rid)
    usersave("jobrole", f"{P1}|{P2}")
    usersave("primaryjobrole", P1)
    refresh()

    # ---- state.json
    st = state()
    ids = [t["id"] for t in st["topics"]]
    ok("state: assignment order T3, T1, T2 then the rest", ids[:3] == [T3, T1, T2], ids[:4])
    t1, t2, t3 = topic_of(st, T1), topic_of(st, T2), topic_of(st, T3)
    ok("state: positions 1..3, profile ids and names", (t3["position"], t1["position"], t2["position"]) == (1, 2, 3) and t3["profile"] == P1 and t3["profilename"] == "Pcheck Pilot" and t2["profile"] == P2, (t3, t2))
    ok("state: T1 locked behind T3 with reason and previoustopic", t1["locked"] is True and t1["lockreason"] == "previous_topic_incomplete" and t1["previoustopic"] == T3, t1)
    ok("state: strictest level on shared T1 (expert) and mandatory", t1["requiredlevel"] == "expert" and t1["mandatory"] is True, t1)
    ok("state: T2 optional, not locked, no level -> requiredlevel expert from row", t2["mandatory"] is False and t2["locked"] is False, t2)
    ok("state: afterfinish/finished present", t3["afterfinish"] == "remove" and t3["finished"] is False and t1["afterfinish"] == "keep", t3)
    ok("state: profiles primary first", [p["id"] for p in st["profiles"]] == [P1, P2] and st["profiles"][0]["primary"] is True and st["profiles"][0]["name"] == "Pcheck Pilot", st["profiles"])
    ok("state: unassigned topics have null position", all(t.get("position") is None for t in st["topics"][3:]), st["topics"][3:4])

    # ---- locks on next/answer/exposure
    stc, body = nxt(mode="learn", topicid=T1)
    ok("next: learn on a locked topic = 409 topic_locked with previoustopic", stc == 409 and body["error"] == "topic_locked" and body["topic"] == T1 and body["previoustopic"] == T3, (stc, body))
    stc, body = nxt(mode="improve", topicid=T1)
    ok("next: improve on a locked topic = 409 topic_locked", stc == 409 and body["error"] == "topic_locked", (stc, body))
    ln = must("learn T3", nxt(mode="learn", topicid=T3))
    # T1 is locked and we have no session for it (next.json refuses with topic_locked); post an exposure only the
    # topic lock can reject: same topic, a real question of T1 (state.json's nextquestionid, unaffected by the topic
    # lock), no session. resolve() checks topic_locked right after scope_mismatch and before the session checks, so
    # topic_locked is the error actually returned; scope_mismatch/missing_sessionid are listed only as a defensive fallback.
    r = call(me, "POST", "/services/testu/learn/exposure.json", form={"mode": "learn", "scopetype": "topic", "scopeid": T1, "questionid": t1["nextquestionid"]})
    ok("exposure: locked topic never accepted (409)", r[0] == 409 and r[1]["error"] in ("topic_locked", "missing_sessionid", "scope_mismatch"), r)
    dc = must("dailychallenge", nxt(mode="dailychallenge"))
    ok("dailychallenge: no question of the locked topic, first new from T3", all(i["topicid"] != T1 for i in dc["items"]) and any(i["topicid"] == T3 for i in dc["items"]), [i["topicid"] for i in dc["items"]])

    # ---- finish T3 (all correct, confident) -> removed; T1 unlocks
    batch = ln
    while True:
        items = batch["items"]
        for i in items:
            qid = i["questionid"]
            must("answer", call(me, "POST", "/services/testu/learn/answer.json", form={
                "mode": "learn", "scopetype": "topic", "scopeid": T3, "questionid": qid, "sessionid": batch["sessionid"],
                "selectedoption": fid(QROW[qid].get("correctoption")), "confidence": "confident", "hintlevel": "0",
                "attemptid": "pc" + secrets.token_hex(8)}))
        if batch["complete"] or not items:
            break
        batch = must("learn T3 next batch", nxt(mode="learn", topicid=T3))
    refresh()
    st2 = state()
    ok("finish: T3 removed (absent from topics, listed in removedtopics)", T3 not in [t["id"] for t in st2["topics"]] and st2["removedtopics"] == [T3], st2["removedtopics"])
    ok("finish: T1 unlocked, keeps position 2", topic_of(st2, T1)["locked"] is False and topic_of(st2, T1)["position"] == 2, topic_of(st2, T1))
    stc, body = call(me, "GET", f"/services/testu/learn/state.json?topicid={T3}")
    ok("state: removed topic by id = 404 unknown_topic", stc == 404 and body["error"] == "unknown_topic", (stc, body))

    # ---- analytics person.json in profile order
    pj = must("person.json", call(admin, "GET", f"/services/testu/analytics/person.json?user={quote(USER)}"))
    req = pj["risk"]["requiredtopics"]
    ok("person: required topics in profile order, T3 excluded (removed), profile/position present", [x["id"] for x in req] == [T1, T2] and req[0]["profile"] == P1 and req[0]["position"] == 2, req)
finally:
    delete_rows("topicrequirement", ROWS)
    call(admin, "POST", "/services/authentication/usersave.json", form={"username": USER, "field": "jobrole", "jobrolevalue": ""})
    call(admin, "POST", "/services/authentication/usersave.json", form={"username": USER, "field": "primaryjobrole", "primaryjobrolevalue": ""})
    delete_rows("jobrole", [P1, P2])
    wipe_user_rows()
    call(admin, "POST", "/services/testu/personas/disableuser.json", form={"userid": USER})
    print("cleanup done")

print("PASS" if not FAILS else f"FAIL ({len(FAILS)}): " + "; ".join(FAILS))
sys.exit(1 if FAILS else 0)
PY
