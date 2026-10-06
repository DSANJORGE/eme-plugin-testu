#!/bin/sh
# Mission agent (task 4, spec 2026-10-05-mission-agent-design): server checks that job-profile withindays writes a
# learnertarget row on setprofiles/saveprofile, and that a written date never moves. LOCAL ONLY, as mission.check@testu.local.
# Header (guards, session, call, ok, must, login, es_ids, delete_rows, refresh, put_row, es_doc, usersave, make_user, audits,
# wipe_user_rows) copied verbatim from check_certification.sh.
# Usage: EME_USER=... EME_PASSWORD=... plugins/testu/tools/check_mission.sh   (from the server root)
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

import datetime

NOW = datetime.datetime.utcnow()


def iso(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%S.000Z")


def wipe_user_rows(user):
    refresh()
    for t in USER_TABLES:
        delete_rows(t, es_ids(t, {"term": {"user": user}}))


def usersave(field, value, user):
    must(f"usersave {field}", call(admin, "POST", "/services/authentication/usersave.json", form={"username": user, "field": field, field + "value": value}))


def make_user(email, password, role="users"):
    users = must("users.json", call(admin, "GET", "/services/testu/personas/users.json"))["users"]
    if next((u for u in users if u["id"] == email), None) is None:
        must("createuser", call(admin, "POST", "/services/testu/personas/createuser.json", form={"email": email, "firstName": "Mission", "lastName": "Check", "role": role}))
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


UID = "mission.check@testu.local"
PASSWORD = "Mc9-" + secrets.token_urlsafe(24)
PROFILE = [None]


def cleanup():
    if PROFILE[0]:
        delete_rows("topicrequirement", es_ids("topicrequirement", {"term": {"jobrole": PROFILE[0]}}))
        delete_rows("jobrole", [PROFILE[0]])
    delete_rows("learnertarget", es_ids("learnertarget", {"term": {"user": UID}}))
    delete_rows("learnernotification", es_ids("learnernotification", {"term": {"user": UID}}))
    usersave("lastmissionstatus", "", UID)  # so a rerun sees announce on its first mission.json read again
    wipe_user_rows(UID)
    refresh()


try:
    make_user(UID, PASSWORD, role="users")
    usersave("jobrole", "", UID)
    usersave("primaryjobrole", "", UID)
    delete_rows("topicrequirement", es_ids("topicrequirement", {"term": {"jobrole": "mission-check"}}))  # leftover from a prior run
    delete_rows("jobrole", ["mission-check"])
    cleanup()
    me = login(UID, PASSWORD)
    base = must("state", call(me, "GET", "/services/testu/learn/state.json"))
    TOPIC = next(t["id"] for t in base["topics"] if t["questions"] > 0)
    AUDIT_SINCE[0] = base["now"]

    # --- profile targets
    # 1. blank withindays: assigning the profile writes no target.
    s, r = call(admin, "POST", "/services/testu/personas/saveprofile.json", form={"id": "", "name": "Mission check", "rows": json.dumps([{"topic": TOPIC, "requiredlevel": "competent", "mandatory": True, "requiresprevious": False, "afterfinish": "keep"}])})
    ok("saveprofile: blank withindays", s == 200 and r["profile"]["rows"][0]["withindays"] is None, r)
    PROFILE[0] = r["profile"]["id"]
    s, r = call(admin, "POST", "/services/testu/personas/setprofiles.json", form={"user": UID, "primary": PROFILE[0], "extras": "[]"})
    ok("setprofiles: 200", s == 200, r)
    refresh()
    ok("setprofiles with blank withindays writes no target", es_doc("learnertarget", f"{UID}_{TOPIC}") is None, es_doc("learnertarget", f"{UID}_{TOPIC}"))

    # 2. saving a withindays onto that row now (UID already an existing member, via setprofiles above) must write a target
    # for UID too, through saveProfile's own member cascade -- not through setprofiles.
    s, r = call(admin, "POST", "/services/testu/personas/saveprofile.json", form={"id": PROFILE[0], "name": "Mission check", "rows": json.dumps([{"topic": TOPIC, "requiredlevel": "competent", "mandatory": True, "requiresprevious": False, "afterfinish": "keep", "withindays": 30}])})
    ok("saveprofile with withindays", s == 200 and r["profile"]["rows"][0]["withindays"] == 30, r)
    refresh()
    row = es_doc("learnertarget", f"{UID}_{TOPIC}")
    ok("saveprofile's withindays cascade writes a target for the existing member", row and row.get("source") == "profile", row)
    due1 = row and row.get("duedate")

    # 3. a later withindays edit on the same row must not move the date already written.
    call(admin, "POST", "/services/testu/personas/saveprofile.json", form={"id": PROFILE[0], "name": "Mission check", "rows": json.dumps([{"topic": TOPIC, "requiredlevel": "competent", "mandatory": True, "requiresprevious": False, "afterfinish": "keep", "withindays": 5}])})
    refresh()
    row2 = es_doc("learnertarget", f"{UID}_{TOPIC}")
    ok("changing withindays does not move a written date", row2 and row2.get("duedate") == due1, row2)
    ok("learnertarget.set audited", bool(audits("learnertarget.set", UID)), "")

    # --- mission.json
    s, m = call(me, "GET", "/services/testu/learn/mission.json")
    ok("mission.json ok", s == 200 and m.get("ok"), m)
    ok("goal is the profile topic with a deadline", m.get("goal") and m["goal"]["topic"] == TOPIC and m["goal"]["deadlinesource"] == "profile", m)
    ok("status on_track or at_risk", m.get("status") in ("on_track", "at_risk"), m)
    ok("announce set on first read", m.get("announce") and m["announce"]["topic"] == TOPIC, m)
    call(me, "GET", "/services/testu/learn/mission.json?ack=1")
    s, m = call(me, "GET", "/services/testu/learn/mission.json")
    ok("ack clears announce", m.get("announce") is None, m)
    ok("no book_evaluation when canstart is false", all(a["type"] != "book_evaluation" for a in m["actions"]), m)

    # --- remind.json
    s, r = call(me, "POST", "/services/testu/learn/remind.json", form={"topic": TOPIC, "when": "tomorrow"})
    ok("remind ok", s == 200 and r.get("ok"), r)
    s, r = call(me, "POST", "/services/testu/learn/remind.json", form={"topic": TOPIC, "when": "2h"})
    refresh()
    pend = es_ids("learnernotification", {"bool": {"must": [{"term": {"user": UID}}, {"term": {"type": "mission"}}, {"exists": {"field": "remindat"}}], "must_not": [{"exists": {"field": "pushedat"}}]}})
    ok("second remind replaces the first", len(pend) == 1, pend)
    s, r = call(me, "POST", "/services/testu/learn/remind.json", form={"topic": TOPIC, "when": "someday"})
    ok("unknown when -> 400", s == 400, r)

    # --- nudges
    put_row("learnernotification", f"{UID}_mission_remind", {"user": UID, "type": "mission", "entitytopic": TOPIC, "remindat": "2020-01-01T00:00:00Z", "read": False, "actor": "tutor"})
    refresh()
    s, r = call(admin, "GET", "/services/testu/learn/missionnudges.json")
    ok("nudges ran", s == 200 and r.get("ok"), r)
    refresh()
    ok("due reminder delivered (pushedat + text set)", es_doc("learnernotification", f"{UID}_mission_remind").get("pushedat") and es_doc("learnernotification", f"{UID}_mission_remind").get("text"))
    s, r2 = call(admin, "GET", "/services/testu/learn/missionnudges.json")
    ok("status push not repeated on a second run", r2.get("sent", 0) == 0, r2)
finally:
    cleanup()

if FAILS:
    print(f"\n{len(FAILS)} FAILED: {FAILS}")
    sys.exit(1)
print("\nall ok")
PY
