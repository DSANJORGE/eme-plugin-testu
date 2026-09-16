#!/bin/sh
# Evaluation Mode (spec docs/superpowers/specs/2026-09-16-evaluation-mode-design.md): server checks of the learner side
# (evaluation.json, startevaluation.json, submitevaluation.json, answer.json mode evaluation) against a local eMe as
# eval.check@testu.local. LOCAL ONLY. Writes and removes: evaluationblueprint versions of one topic, the user's learning
# and evaluationattempt rows. Blueprints are seeded directly (the admin endpoint is Task 4's).
# Usage: EME_USER=... EME_PASSWORD=... plugins/testu/tools/check_evaluation.sh   (from the server root)
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
USER, PASSWORD = "eval.check@testu.local", "Ec9-" + secrets.token_urlsafe(24)
USER2, PASSWORD2 = "eval.check2@testu.local", "Ec9-" + secrets.token_urlsafe(24)  # a second learner: someone else's attempt
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


import datetime, math

NOW = datetime.datetime.utcnow()
counter = [0]
USER_TABLES = ("tutoranswer", "tutorexposure", "dailychallengeset", "learningsession", "tutormastery", "tutordaily", "subtopicunlock", "evaluationattempt")


def iso(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%S.000Z")


def wipe_user_rows():
    refresh()
    for t in USER_TABLES:
        for u in (USER, USER2):
            delete_rows(t, es_ids(t, {"term": {"user": u}}))


def usersave(field, value, user=None):
    must(f"usersave {field}", call(admin, "POST", "/services/authentication/usersave.json", form={"username": user or USER, "field": field, field + "value": value}))


def make_user(email, password, first):
    users = must("users.json", call(admin, "GET", "/services/testu/personas/users.json"))["users"]
    if next((u for u in users if u["id"] == email), None) is None:
        must("createuser", call(admin, "POST", "/services/testu/personas/createuser.json", form={"email": email, "firstName": first, "lastName": "Check", "role": "users"}))
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


def state(topic=None):
    return must("state", call(me, "GET", "/services/testu/learn/state.json" + (f"?topicid={topic}" if topic else "")))


def topic_of(st, tid):
    return next(t for t in st["topics"] if t["id"] == tid)


def ev():
    return call(me, "GET", f"/services/testu/learn/evaluation.json?topicid={quote(T)}")


def start():
    return call(me, "POST", "/services/testu/learn/startevaluation.json", form={"topicid": T})


def submit(sid):
    return call(me, "POST", "/services/testu/learn/submitevaluation.json", form={"sessionid": sid})


def answer(sid, qid, option, op=None, **extra):
    form = {"mode": "evaluation", "scopetype": "topic", "scopeid": T, "questionid": qid, "sessionid": sid, "selectedoption": option,
            "confidence": "confident", "hintlevel": "0", "attemptid": "ec" + secrets.token_hex(8)}
    form.update(extra)
    return call(op or me, "POST", "/services/testu/learn/answer.json", form={k: v for k, v in form.items() if v is not None})


def audits(action, target):
    refresh()
    st, res = call(es, "POST", "/auditevent/_search", body={"size": 50, "query": {"bool": {"must": [
        {"match_phrase": {"targetid": target}}, {"match_phrase": {"action": action}}]}}}, base=ES)
    return [h["_source"] for h in res.get("hits", {}).get("hits", [])] if st == 200 else []


qhits = must("entityquestion search", call(admin, "GET", "/services/lists/search/entityquestion/search.json?hitsperpage=5000"))["results"]
QROW = {q["id"]: q for q in qhits}
fid = lambda v: v.get("id") if isinstance(v, dict) else v


def right(qid):
    return fid(QROW[qid].get("correctoption")).upper()


def wrong(qid):
    return next(o for o in "ABCD" if o != right(qid) and (QROW[qid].get("option_" + o.lower()) or "").strip())


BP = []
VERSION = [0]


def blueprint(**f):
    """Seeds the next blueprint version of T directly (the admin endpoint is Task 4's; these checks exercise the learner side)."""
    VERSION[0] += 1
    rid = f"{T}_v{VERSION[0]}"
    body = {"entitytopic": T, "blueprintversion": str(VERSION[0]), "active": "true", "strategy": "random", "maxquestions": str(MAXQ),
            "minpersubtopic": "0", "excludedsections": "[]", "difficultymix": "proportional", "passpercent": "60", "subtopicminpercent": "0",
            "timerminutes": "0", "retakewaithours": "0", "maxattempts": "0", "requirelearncomplete": "false", "user": os.environ["EME_USER"],
            "datecreated": iso(NOW)}
    body.update({k: str(v) for k, v in f.items()})
    put_row("evaluationblueprint", rid, body)
    BP.append(rid)
    refresh()


def cleanup():
    delete_rows("evaluationblueprint", BP)
    wipe_user_rows()
    refresh()


try:
    # ---- setup: learner, topic with enough questions
    make_user(USER, PASSWORD, "Eval")
    make_user(USER2, PASSWORD2, "Eval2")
    wipe_user_rows()
    me = login(USER, PASSWORD)
    other = login(USER2, PASSWORD2)
    base = state()
    cands = [t for t in base["topics"] if t["questions"] >= 6 and len(t["sections"]) >= 2 and t["evaluation"]["status"] == "not_available"]
    if not cands:
        sys.exit("need a learner-visible topic with >= 6 questions, >= 2 subtopics and no blueprint")
    T = cands[0]["id"]
    POOL = cands[0]["questions"]
    MAXQ = max(1, min(5, POOL // 2))
    TSECTIONS = {x["id"] for x in cands[0]["sections"]}
    slots = must("componentcontent search", call(admin, "GET", "/services/lists/search/componentcontent/search.json?hitsperpage=10000"))["results"]
    TQUESTIONS = {fid(c.get("questionid")) for c in slots if fid(c.get("componentsectionid")) in TSECTIONS and fid(c.get("componenttype")) == "mcq"}
    TQUESTIONS = {q for q in TQUESTIONS if q in QROW}
    refresh()
    VERSION[0] = max([int(es_doc("evaluationblueprint", i).get("blueprintversion", 0)) for i in es_ids("evaluationblueprint", {"term": {"entitytopic": T}})] or [0])
    ok("no blueprint: every topic not_available / not_configured, no evaluation UI data", all(t["evaluation"]["status"] == "not_available" for t in base["topics"]) and cands[0]["evaluation"]["reason"] == "not_configured", cands[0]["evaluation"])

    # ---- available -> start -> answers -> submit (pass)
    blueprint()
    st, e = ev()
    ok("evaluation.json: available, canstart, blueprint carried", st == 200 and e["status"] == "available" and e["canstart"] is True and e["blueprint"]["maxquestions"] == MAXQ and e["attempts"] == 0 and e["attempthistory"] == [], e)
    A0 = len(audits("evaluation.start", f"{USER}_{T}_a1"))  # auditevent is append-only: earlier runs reused this id
    st, s1 = start()
    ok("start: next.json shape, mode evaluation, n items, expiresat, nothing done", st == 200 and s1["mode"] == "evaluation" and s1["total"] == MAXQ and len(s1["items"]) == MAXQ and s1["expiresat"] and all(i["done"] is False for i in s1["items"]) and s1["sessionid"] == f"{USER}_{T}_a1", s1)
    ok("start: positions 1..n, every item of T", [i["position"] for i in s1["items"]] == list(range(1, MAXQ + 1)) and all(i["topicid"] == T for i in s1["items"]), s1["items"])
    row = es_doc("evaluationattempt", s1["sessionid"])
    ok("start: attempt row inprogress, attemptnumber 1, questionlist stored, inputs with seed", row and row["status"] == "inprogress" and int(row["attemptnumber"]) == 1 and len(json.loads(row["questionlist"])) == MAXQ and "seed" in json.loads(row["inputs"]), row)
    st, s1b = start()
    ok("start again: resumes the same attempt", st == 200 and s1b["sessionid"] == s1["sessionid"], s1b)
    ok("audit evaluation.start once", len(audits("evaluation.start", s1["sessionid"])) - A0 == 1, audits("evaluation.start", s1["sessionid"]))
    st, e = ev()
    ok("evaluation.json: in_progress with attemptid and 0 answered", e["status"] == "in_progress" and e["inprogress"]["attemptid"] == s1["sessionid"] and e["inprogress"]["answered"] == 0, e)
    q0 = s1["items"][0]["questionid"]
    r = answer(s1["sessionid"], q0, right(q0), hintlevel="1")
    ok("answer: hint -> 400 hints_not_allowed", r[0] == 400 and r[1]["error"] == "hints_not_allowed", r)
    r = answer(s1["sessionid"], q0, right(q0), scopetype="subtopic", scopeid=s1["items"][0]["sectionid"])
    ok("answer: subtopic scope -> 400 bad_scopetype", r[0] == 400 and r[1]["error"] == "bad_scopetype", r)
    r = answer(s1["sessionid"], q0, right(q0), scopeid="nope")
    ok("answer: wrong topic scope -> 409 scope_mismatch", r[0] == 409 and r[1]["error"] == "scope_mismatch", r)
    r = answer("nope", q0, right(q0))
    ok("answer: unknown attempt -> 404 unknown_session", r[0] == 404 and r[1]["error"] == "unknown_session", r)
    outside = next((q for q in sorted(TQUESTIONS - {i["questionid"] for i in s1["items"]}) if QROW[q].get("correctoption")), None)
    ok("topic T has a question outside the attempt to test with", outside is not None, (len(TQUESTIONS), MAXQ))
    if outside:
        r = answer(s1["sessionid"], outside, right(outside))
        ok("answer: a question of T outside the attempt -> 409 not_in_session", r[0] == 409 and r[1]["error"] == "not_in_session", r)
    r = answer(s1["sessionid"], q0, right(q0), op=other)
    ok("answer: another learner on this attempt -> 409 session_mismatch", r[0] == 409 and r[1]["error"] == "session_mismatch", r)
    LEARN0 = {k: topic_of(state(), T)[k] for k in ("masterypercent", "band", "answered", "learncomplete", "nextquestionid")}
    for i in s1["items"]:
        r = answer(s1["sessionid"], i["questionid"], right(i["questionid"]))
        ok(f"answer {i['position']}: stored, deferred, no iscorrect", r[0] == 200 and r[1]["ok"] is True and r[1]["deferred"] is True and "iscorrect" not in r[1] and r[1]["mode"] == "evaluation", r)
    r = answer(s1["sessionid"], q0, right(q0))
    ok("answer: second answer to the same question -> 409 already_answered", r[0] == 409 and r[1]["error"] == "already_answered", r)
    refresh()
    arow = [h for h in call(es, "POST", "/tutoranswer/_search", body={"size": 50, "query": {"term": {"user": USER}}}, base=ES)[1]["hits"]["hits"]]
    ok("tutoranswer rows: mode evaluation, learningsession = attempt id, scopetype topic", len(arow) == MAXQ and all(h["_source"]["mode"] == "evaluation" and h["_source"]["learningsession"] == s1["sessionid"] and h["_source"]["scopetype"] == "topic" for h in arow), [h["_source"].get("mode") for h in arow])
    t = topic_of(state(), T)
    ok("state: evaluation answers never count as learning answers (answered 0), in_progress with n answered", t["answered"] == 0 and t["evaluation"]["status"] == "in_progress" and t["evaluation"]["inprogress"]["answered"] == MAXQ, t["evaluation"])
    ok("state: mastery, band and the learn sequence are untouched by evaluation answers", {k: t[k] for k in LEARN0} == LEARN0, (LEARN0, {k: t[k] for k in LEARN0}))
    sub0 = {x["id"]: (x["masterypercent"], x["band"], x["answered"], x["unlocked"]) for x in t["sections"]}
    ok("state: no subtopic gained mastery or an unlock from the evaluation", all(v[0] == 0 and v[1] is None and v[2] == 0 for k, v in sub0.items() if k in {i["sectionid"] for i in s1["items"]}), sub0)
    A1 = len(audits("evaluation.submit", s1["sessionid"]))
    st, res = submit(s1["sessionid"])
    ok("submit: passed 100, correct n, subtopics listed, status submitted", st == 200 and res["passed"] is True and res["scorepercent"] == 100 and res["correct"] == MAXQ and res["status"] == "submitted" and res["subtopics"] and res["duplicate"] is False and res["evaluationstatus"] == "passed", res)
    st, res2 = submit(s1["sessionid"])
    ok("submit again: duplicate with the same score", st == 200 and res2["duplicate"] is True and res2["scorepercent"] == 100, res2)
    ok("audit evaluation.submit once", len(audits("evaluation.submit", s1["sessionid"])) - A1 == 1, "")
    st, e = ev()
    ok("evaluation.json: passed is terminal (canstart false, passedat, lastresult)", e["status"] == "passed" and e["canstart"] is False and e["passedat"] and e["lastresult"]["scorepercent"] == 100 and e["attempts"] == 1 and len(e["attempthistory"]) == 1, e)
    r = start()
    ok("start after pass -> 409 evaluation_not_available passed", r[0] == 409 and r[1]["error"] == "evaluation_not_available" and r[1]["status"] == "passed", r)
    r = answer(s1["sessionid"], q0, right(q0))
    ok("answer on a closed attempt -> 409 attempt_closed", r[0] == 409 and r[1]["error"] == "attempt_closed", r)

    # ---- fail -> waiting -> retake avoids earlier questions -> exhausted
    delete_rows("evaluationattempt", es_ids("evaluationattempt", {"term": {"user": USER}}))
    delete_rows("tutoranswer", es_ids("tutoranswer", {"term": {"user": USER}}))
    refresh()
    blueprint(passpercent="100", retakewaithours="1", maxattempts="2")
    st, s2 = start()
    ok("fresh history: attempt a1 again", st == 200 and s2["sessionid"].endswith("_a1"), s2)
    for i in s2["items"]:
        answer(s2["sessionid"], i["questionid"], wrong(i["questionid"]))
    st, res = submit(s2["sessionid"])
    ok("submit: all wrong -> 0 %, failedrule overall, not passed", res["passed"] is False and res["scorepercent"] == 0 and res["failedrule"] == "overall" and res["evaluationstatus"] == "waiting" and res["nextallowedat"], res)
    st, e = ev()
    ok("evaluation.json: waiting with nextallowedat, attemptsleft 1", e["status"] == "waiting" and e["nextallowedat"] and e["attemptsleft"] == 1 and e["scorepercent"] == 0, e)
    r = start()
    ok("start while waiting -> 409 with nextallowedat", r[0] == 409 and r[1]["status"] == "waiting" and r[1]["nextallowedat"], r)
    blueprint(passpercent="100", retakewaithours="0", maxattempts="2")
    st, e = ev()
    ok("new version without wait: available again", e["status"] == "available" and e["attemptsleft"] == 1, e)
    st, s3 = start()
    prev = {i["questionid"] for i in s2["items"]}
    overlap = len(prev & {i["questionid"] for i in s3["items"]})
    ok("retake a2 avoids a1's questions where the pool allows", st == 200 and s3["sessionid"].endswith("_a2") and overlap <= max(0, 2 * MAXQ - POOL), (overlap, POOL, MAXQ))
    for i in s3["items"]:
        answer(s3["sessionid"], i["questionid"], wrong(i["questionid"]))
    st, res = submit(s3["sessionid"])
    ok("second failure -> exhausted", res["evaluationstatus"] == "exhausted" and res["attemptsleft"] == 0, res)
    r = start()
    ok("start when exhausted -> 409", r[0] == 409 and r[1]["status"] == "exhausted", r)

    # ---- timer: expired attempt is scored by the clock
    delete_rows("evaluationattempt", es_ids("evaluationattempt", {"term": {"user": USER}}))
    delete_rows("tutoranswer", es_ids("tutoranswer", {"term": {"user": USER}}))
    refresh()
    blueprint(timerminutes="1")
    st, s4 = start()
    exp = datetime.datetime.strptime(s4["expiresat"][:19], "%Y-%m-%dT%H:%M:%S")
    ok("timer: expiresat about one minute ahead", st == 200 and 30 <= (exp - datetime.datetime.utcnow()).total_seconds() <= 90 and s4["timerminutes"] == 1, s4["expiresat"])
    q = s4["items"][0]["questionid"]
    answer(s4["sessionid"], q, right(q))
    put_row("evaluationattempt", s4["sessionid"], {"expiresat": iso(NOW - datetime.timedelta(hours=1))})
    refresh()
    r = answer(s4["sessionid"], s4["items"][-1]["questionid"], "A")
    ok("timer: answer past expiresat -> 409 session_expired", r[0] == 409 and r[1]["error"] == "session_expired", r)
    row = es_doc("evaluationattempt", s4["sessionid"])
    ok("timer: attempt finalized by timer with the answered subset scored", row and row["status"] == "expired" and row["finalizedby"] == "timer" and int(row["answered"]) == 1 and int(row["correct"]) == 1, row)
    st, e = ev()
    ok("evaluation.json after expiry: available again (unlimited attempts), lastresult expired", e["status"] == "available" and e["lastresult"]["status"] == "expired" and e["attempts"] == 1 and len(e["attempthistory"]) == 1, e)

    # ---- a submit after the window closes is the clock's doing, not the learner's
    delete_rows("evaluationattempt", es_ids("evaluationattempt", {"term": {"user": USER}}))
    delete_rows("tutoranswer", es_ids("tutoranswer", {"term": {"user": USER}}))
    refresh()
    blueprint(timerminutes="1")
    st, s5 = start()
    q = s5["items"][0]["questionid"]
    answer(s5["sessionid"], q, right(q))
    put_row("evaluationattempt", s5["sessionid"], {"expiresat": iso(NOW - datetime.timedelta(hours=1))})
    refresh()
    st, res = submit(s5["sessionid"])
    row = es_doc("evaluationattempt", s5["sessionid"])
    ok("late submit: scored as expired / finalizedby timer, not a learner submission", st == 200 and res["status"] == "expired" and row["finalizedby"] == "timer" and res["duplicate"] is False and res["correct"] == 1, (res.get("status"), row.get("finalizedby")))

    # ---- learn must be complete
    blueprint(requirelearncomplete="true")
    st, e = ev()
    ok("requirelearncomplete: locked learn_incomplete", e["status"] == "locked" and e["reason"] == "learn_incomplete" and e["canstart"] is False, e)
    r = start()
    ok("start while locked -> 409", r[0] == 409 and r[1]["reason"] == "learn_incomplete", r)

    # ---- empty pool: a reserved-strategy blueprint on a topic with no reserved questions
    blueprint(strategy="reserved", requirelearncomplete="false")
    r = start()
    ok("empty pool: start -> 409 pool_insufficient, no attempt row", r[0] == 409 and r[1]["error"] == "pool_insufficient" and es_doc("evaluationattempt", f"{USER}_{T}_a2") is None, r)

    # ---- inactive and legacy rows
    blueprint(active="false")
    st, e = ev()
    ok("inactive version: not_available inactive, blueprint null", e["status"] == "not_available" and e["reason"] == "inactive" and e["blueprint"] is None, e)
    t = topic_of(state(), T)
    ok("state.json: evaluation block mirrors evaluation.json", t["evaluation"]["status"] == "not_available" and t["evaluation"]["reason"] == "inactive", t["evaluation"])
finally:
    cleanup()

print("evaluation checks: " + ("PASS" if not FAILS else f"{len(FAILS)} FAILED: {FAILS}"))
sys.exit(1 if FAILS else 0)
PY
