#!/bin/sh
# Learning engine v1 (spec docs/superpowers/specs/2026-09-14-learning-engine.md): pure dc-v0 checks, then server checks
# against a local eMe as the test user learn.check@testu.local. LOCAL ONLY. Every row it writes (tutoranswer, tutorexposure,
# dailychallengeset, learningsession, subtopicunlock, subtopicpolicy versions it added, topicrequirement lcheck-*, the user's tutormastery/tutordaily
# rows) is deleted at the end, except the auditevent rows of its policy changes (audit is append-only); the test
# user is left disabled, the topic threshold override cleared (eMe stores a cleared number as 0 = org default) and the
# testu_timezone / testu_dailychallenge_min|max catalog settings put back as they were. One learner-visible question's option_d is blanked
# for a few seconds (content_unavailable) and restored; its automatic questionflag is deleted.
# The test user gets a fresh random password every run. Admin credentials are required: EME_USER / EME_PASSWORD (no default).
# Usage: EME_USER=... EME_PASSWORD=... plugins/testu/tools/check_learning.sh   (from the server root; EME_BASE / ES override the local URLs)
set -eu
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
CP="$ROOT/build:$(find "$ROOT/plugins/system/lib" "$ROOT/plugins/finder/lib" "$ROOT/plugins/community/lib" "$ROOT/tomcat/lib" -type f -name '*.jar' | tr '\n' ':')"
java -cp "$CP" "$ROOT/plugins/testu/tools/LearningEngineCheck.java"
TESTU_TOOLS="$ROOT/plugins/testu/tools" exec python3 - "$@" <<'PY'
import datetime, json, math, os, secrets, sys, urllib.error, urllib.request
from zoneinfo import ZoneInfo
from http.cookiejar import CookieJar
from urllib.parse import quote, urlencode, urlparse

B = os.environ.get("EME_BASE", "http://localhost:8080/site/mediadb")
ES = os.environ.get("ES", "http://localhost:9200/site_catalog")
for u in (B, ES):
    if urlparse(u).hostname not in ("localhost", "127.0.0.1"):
        sys.exit(f"refusing non-local host: {u}")
if not os.environ.get("EME_USER") or not os.environ.get("EME_PASSWORD"):
    sys.exit("set EME_USER and EME_PASSWORD (a local admin) to run the server checks")
USER, PASSWORD = "learn.check@testu.local", "Lc9-" + secrets.token_urlsafe(24)  # per run, never reused
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
            raw = r.read()
            status = r.status
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
counter = [0]
NOW = datetime.datetime.now(datetime.timezone.utc)


def iso(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%S.000Z")


def es_ids(table, query):
    st, res = call(es, "POST", f"/{table}/_search", body={"size": 10000, "query": query, "_source": False}, base=ES)
    return [h["_id"] for h in res.get("hits", {}).get("hits", [])] if st == 200 else []


def delete_rows(table, ids):
    for i in ids:
        call(admin, "DELETE", f"/services/lists/data/{table}/{quote(i)}.json")


USER_TABLES = ("tutoranswer", "tutorexposure", "dailychallengeset", "learningsession", "tutormastery", "tutordaily", "subtopicunlock")


def wipe_user_rows(tables=USER_TABLES):
    call(es, "POST", "/_refresh", body={}, base=ES)
    for t in tables:
        delete_rows(t, es_ids(t, {"term": {"user": USER}}))


def answer(q, correct=True, conf="confident", mode="learn", at=None, hint=0):
    """Seeds a history row directly (back-dated). mode "" = a row without mode (legacy). Live answers go through post_answer."""
    counter[0] += 1
    rid = f"lcheck-a-{counter[0]}"
    must("seed tutoranswer", call(admin, "PUT", f"/services/lists/data/tutoranswer/{rid}.json", body={
        "id": rid, "user": USER, "entityquestion": q, "iscorrect": "true" if correct else "false", "answerconfidence": conf,
        "mode": mode, "hintlevel": str(hint), "datecreated": iso(at or NOW), "componentsection": SECTION_OF.get(q, "")}))


def post_answer(**form):
    """services/testu/learn/answer.json as the learner; attemptid generated unless given."""
    form.setdefault("attemptid", "lc" + secrets.token_hex(12))
    return call(me, "POST", "/services/testu/learn/answer.json", form={k: v for k, v in form.items() if v is not None})


def es_doc(table, rid):
    call(es, "POST", "/_refresh", body={}, base=ES)
    st, raw = call(es, "GET", f"/{table}/{quote(rid, safe='')}", base=ES)
    return raw.get("_source") if st == 200 and raw.get("found") else None


def setting(sid, value):
    """Catalog setting value (None deletes the row). Returns the previous value."""
    prev = (es_doc("catalogsettings", sid) or {}).get("value")
    if value is None:
        call(admin, "DELETE", f"/services/lists/data/catalogsettings/{quote(sid)}.json")
    else:
        must(f"setting {sid}", call(admin, "PUT", f"/services/lists/data/catalogsettings/{quote(sid)}.json", body={"id": sid, "name": sid, "value": value}))
    return prev


def dc_rows():
    call(es, "POST", "/_refresh", body={}, base=ES)
    st, res = call(es, "POST", "/dailychallengeset/_search", body={"size": 50, "query": {"term": {"user": USER}}}, base=ES)
    return [(h["_id"], h["_source"]) for h in res.get("hits", {}).get("hits", [])] if st == 200 else []


def dc_invariants(name, d):
    """Hard new cap: newcount <= ceil(0.7 n) unless reinforcement was exhausted after its relaxations (recorded newcap)."""
    inp = stored_inputs()
    n, maxnew, rel = inp.get("n"), inp.get("maxnew"), inp.get("relaxations", [])
    ok(f"{name}: size n, maxnew = ceil(0.7 n), new cap respected or newcap recorded",
       len(d["items"]) == n and maxnew == math.ceil(n * 0.7) and (d["newcount"] <= maxnew or ("newcap" in rel and "newfill" in rel)), (d["newcount"], inp))


def reset_dc():
    delete_rows("dailychallengeset", [i for i, _ in dc_rows()])


def stored_inputs():
    """Inputs of the learner's stored set (the checks keep at most one: reset_dc before each rebuild)."""
    rows = dc_rows()
    return json.loads(rows[0][1]["inputs"]) if rows else {}


def state(topic=None):
    return must("state.json", call(me, "GET", "/services/testu/learn/state.json" + (f"?topicid={quote(topic)}" if topic else "")))


def nxt(**params):
    return call(me, "GET", "/services/testu/learn/next.json?" + urlencode(params))


def topic_of(st, tid):
    return next(t for t in st["topics"] if t["id"] == tid)


LEVELS = ["beginner", "competent", "expert"]
CONF = {"confident": 1.0, "mostlysure": 0.85, "notsure": 0.6, "noidea": 0.4}


def difficulty(v):  # canonical ids only (source labels are mapped at import)
    v = v.get("id") if isinstance(v, dict) else v
    return v if v in ("competent", "expert") else "beginner"


def expected_percent(qids, latest):
    w = ws = 0.0
    for q in qids:
        wt = {"beginner": 1, "competent": 2, "expert": 4}[DIFF[q]]
        a = latest.get(q)
        w += wt
        ws += wt * (CONF[a[1]] * (1 - 0.25 * a[2]) if a and a[0] else 0)
    return 0 if w == 0 else math.floor(100 * ws / w + 0.5)


def band(p, cmin=60, emin=85):
    return "expert" if p >= emin else "competent" if p >= cmin else "beginner"


PP = "/services/testu/learn/subtopicpolicy.json"
PRIOR_POLICY = []


def policy_rows(tid):
    call(es, "POST", "/_refresh", body={}, base=ES)
    return es_ids("subtopicpolicy", {"term": {"entitytopic": tid}})


def restore_policy():
    """Deletes the subtopicpolicy versions this run added to T (versions that existed before are kept)."""
    delete_rows("subtopicpolicy", [i for i in policy_rows(T) if i not in PRIOR_POLICY])
    call(es, "POST", "/_refresh", body={}, base=ES)


def save_policy(policy, level=None, op=None, expected=None):
    """POST subtopicpolicy.json for T with expectedversion = the current version unless given."""
    if expected is None:
        expected = must("policy get", call(admin, "GET", PP + "?topicid=" + quote(T)))["topic"]["version"]
    return call(op or admin, "POST", PP, form={k: v for k, v in {"topicid": T, "policy": policy, "requiredlevel": level, "expectedversion": str(expected)}.items() if v is not None})


def unlock_row(section):
    """Realtime get of the learner's subtopicunlock row for section (None when absent)."""
    st, raw = call(es, "GET", f"/subtopicunlock/{quote(USER + '_' + section, safe='')}", base=ES)
    return raw.get("_source") if st == 200 and raw.get("found") else None


def audits(tid):
    call(es, "POST", "/_refresh", body={}, base=ES)
    st, res = call(es, "POST", "/auditevent/_search", body={"size": 200, "query": {"bool": {"must": [
        {"match_phrase": {"targetid": tid}}, {"match_phrase": {"action": "subtopicpolicy.change"}}]}}}, base=ES)
    return [h["_source"] for h in res.get("hits", {}).get("hits", [])] if st == 200 else []


def restore_topic(tid):
    call(admin, "PUT", f"/services/lists/data/entitytopic/{quote(tid)}.json", body={"id": tid, "competentmin": "", "expertmin": ""})


# ---------------------------------------------------------------- setup
users = must("users.json", call(admin, "GET", "/services/testu/personas/users.json"))["users"]
existing = next((u for u in users if u["id"] == USER), None)
if existing is None:
    must("createuser", call(admin, "POST", "/services/testu/personas/createuser.json", form={"email": USER, "firstName": "Learn", "lastName": "Check", "role": "users"}))
elif not existing["enabled"]:
    must("enable user", call(admin, "POST", "/services/authentication/usersave.json", form={"username": USER, "field": "enabled", "enabledvalue": "true"}))
must("password", call(admin, "POST", "/services/authentication/usersave.json", form={"username": USER, "field": "password", "passwordvalue": PASSWORD}))
must("clear jobrole", call(admin, "POST", "/services/authentication/usersave.json", form={"username": USER, "field": "jobrole", "jobrolevalue": ""}))
wipe_user_rows()
delete_rows("topicrequirement", ["lcheck-r1", "lcheck-r2", "lcheck-r3"])
me = login(USER, PASSWORD)

st0 = state()
TOPICS = [t["id"] for t in st0["topics"]]
T, T2 = TOPICS[0], TOPICS[1]
SEQ, SECTION_OF, SECTIONS, ITEM = {}, {}, {}, {}
for tid in TOPICS:
    items = must("learn full", nxt(mode="learn", topicid=tid))["items"]
    SEQ[tid] = [i["questionid"] for i in items]
    for i in items:
        SECTION_OF[i["questionid"]] = i["sectionid"]
        ITEM[i["questionid"]] = i
        SECTIONS.setdefault(i["sectionid"], []).append(i["questionid"])
qhits = must("entityquestion search", call(admin, "GET", "/services/lists/search/entityquestion/search.json?hitsperpage=5000"))["results"]
DIFF = {q["id"]: difficulty(q.get("mcqcognitivelevel")) for q in qhits}
fid = lambda v: v.get("id") if isinstance(v, dict) else v
QROW = {q["id"]: q for q in qhits}
TOPIC_SECTIONS = {tid: [s["id"] for s in topic_of(st0, tid)["sections"]] for tid in TOPICS}
S1, S2 = TOPIC_SECTIONS[T][0], TOPIC_SECTIONS[T][1]
print(f"content: {', '.join(f'{t}={len(SEQ[t])}' for t in TOPICS)} questions; S1={S1} ({len(SECTIONS[S1])})")

try:
    # ------------------------------------------------------------ fresh user
    t0 = topic_of(st0, T)
    ok("fresh: state answered 0, band null, next = q1, improve unavailable",
       t0["answered"] == 0 and t0["band"] is None and t0["nextquestionid"] == SEQ[T][0] and not t0["improveavailable"] and not t0["learncomplete"], t0)
    first = SEQ and must("learn", nxt(mode="learn", topicid=T))
    ok("fresh: learn starts at q1 (position 1)", first["items"][0]["questionid"] == SEQ[T][0] and first["items"][0]["position"] == 1 and first["complete"] is False, first["items"][:1])
    for params in ({"topicid": T}, {"topicid": T, "sectionid": S1}):
        stc, body = nxt(mode="improve", **params)
        ok(f"fresh: improve locked {params}", stc == 409 and body == {"ok": False, "error": "improve_locked"}, (stc, body))

    reset_dc()
    dc1 = must("dc", nxt(mode="dailychallenge"))
    dc2 = must("dc again", nxt(mode="dailychallenge"))
    ids1 = [i["questionid"] for i in dc1["items"]]
    dc_invariants("dc cold start", dc1)
    ok("dc cold start: new past maxnew only via newfill/newcap", "newcap" in stored_inputs().get("relaxations", []) or dc1["newcount"] <= stored_inputs().get("maxnew"), stored_inputs())
    ok("dc 0 learning-answered: all new, dc-v0", dc1["algorithmversion"] == "dc-v0" and dc1["newcount"] == len(ids1) == dc1["total"] and dc1["reinforcementcount"] == 0, dc1)
    ok("dc same set on second call", ids1 == [i["questionid"] for i in dc2["items"]], (ids1, dc2["items"]))
    inp = stored_inputs()
    ok("dc assignment_data_unavailable reason", inp.get("topicsreason") == "assignment_data_unavailable", inp)

    def never_skips(dc):
        pos = {}
        for i in dc["items"]:
            if i["bucket"] == "new":
                seq = [q for q in SEQ[i["topicid"]] if q not in answered_learning]
                pos.setdefault(i["topicid"], []).append(i["questionid"])
        return all(v == [q for q in SEQ[t] if q not in answered_learning][:len(v)] for t, v in pos.items())

    answered_learning = set()
    ok("dc never skips ahead within a topic", never_skips(dc1), dc1["items"])
    reset_dc()
    dc3 = must("dc recompute", nxt(mode="dailychallenge"))
    strip = lambda d: [(i["questionid"], i["bucket"], i["reason"]) for i in d["items"]]
    ok("dc deterministic: recomputed without the stored set = identical", strip(dc1) == strip(dc3), (strip(dc1), strip(dc3)))

    # exposure.json: validated like answer.json, hierarchy and position resolved by the server
    q0, it0 = SEQ[T][0], ITEM[SEQ[T][0]]
    canon0 = {"questionid": q0, "topicid": T, "tutorialid": it0["tutorialid"], "sectionid": it0["sectionid"], "componentid": it0["componentid"], "position": it0["position"]}
    outside = next(x for x in reversed(SEQ[T]) if x not in ids1)
    EXP = "/services/testu/learn/exposure.json"
    DCS = dc3["sessionid"]
    ok("dc sessionid = today's set id", DCS == dc_rows()[0][0] == f"{USER}_{dc3['localdate'].replace('-', '')}", (DCS, dc_rows()[:1]))
    ex = call(me, "POST", EXP, form={"questionid": q0, "mode": "dailychallenge", "topicid": T, "sessionid": DCS})
    ok("exposure.json: ok with the server-resolved hierarchy and position", ex[0] == 200 and ex[1] == dict(canon0, ok=True, mode="dailychallenge"), ex)
    call(es, "POST", "/_refresh", body={}, base=ES)
    exrows = [h["_source"] for h in call(es, "POST", "/tutorexposure/_search", body={"size": 50, "query": {"term": {"user": USER}}}, base=ES)[1]["hits"]["hits"]]
    ok("exposure row stores canonical topic/tutorial/section/position, no scope", len(exrows) == 1 and fid(exrows[0].get("entitytutorial")) == it0["tutorialid"] and fid(exrows[0].get("componentsection")) == it0["sectionid"]
       and fid(exrows[0].get("entitytopic")) == T and int(exrows[0].get("position")) == it0["position"] and fid(exrows[0].get("mode")) == "dailychallenge" and not exrows[0].get("scopetype"), exrows)
    REJECTS = [
        ({"questionid": q0}, 400, "missing_mode"),
        ({"questionid": q0, "mode": "quiz"}, 400, "bad_mode"),  # evaluation is an accepted mode now (2026-09-16-evaluation-mode)
        ({"questionid": q0, "mode": "dailychallenge", "tutorialid": "not-" + it0["tutorialid"]}, 409, "hierarchy_mismatch"),
        ({"questionid": q0, "mode": "dailychallenge", "componentid": "not-" + it0["componentid"]}, 409, "hierarchy_mismatch"),
        ({"questionid": q0, "mode": "dailychallenge", "scopetype": "topic", "scopeid": T}, 400, "scope_not_allowed"),
        ({"questionid": outside, "mode": "dailychallenge", "sessionid": DCS}, 409, "not_in_daily_challenge"),
        ({"questionid": q0, "mode": "dailychallenge"}, 400, "missing_sessionid"),
        ({"questionid": q0, "mode": "dailychallenge", "sessionid": USER + "_20000101"}, 409, "session_expired"),
        ({"questionid": q0, "mode": "dailychallenge", "sessionid": "someone_20000101"}, 409, "unknown_session"),
        ({"questionid": q0, "mode": "learn", "scopetype": "topic", "scopeid": T}, 400, "missing_sessionid"),
        ({"questionid": q0, "mode": "learn", "scopetype": "topic", "scopeid": T, "sessionid": USER + "_nope"}, 404, "unknown_session"),
        ({"questionid": q0, "mode": "learn", "scopetype": "topic", "scopeid": T, "sessionid": DCS}, 404, "unknown_session"),
        ({"questionid": q0, "mode": "learn"}, 400, "missing_scope"),
        ({"questionid": q0, "mode": "learn", "scopetype": "chapter", "scopeid": T}, 400, "bad_scopetype"),
        ({"questionid": q0, "mode": "learn", "scopetype": "topic", "scopeid": T2}, 409, "scope_mismatch"),
        ({"questionid": q0, "mode": "learn", "scopetype": "subtopic", "scopeid": S2}, 409, "scope_mismatch"),
        ({"questionid": q0, "mode": "learn", "scopetype": "topic", "scopeid": "no-such-topic"}, 404, "unknown_scope"),
        ({"questionid": q0, "mode": "improve", "scopetype": "topic", "scopeid": T}, 409, "improve_locked"),
        ({"questionid": q0, "mode": "improve", "scopetype": "subtopic", "scopeid": S1}, 409, "improve_locked"),
        ({"questionid": "no-such-question", "mode": "learn", "scopetype": "topic", "scopeid": T}, 404, "unknown_question"),
    ]
    for form, code, err in REJECTS:
        r = call(me, "POST", EXP, form=form)
        ok(f"exposure.json rejects {err} {sorted(form)}", r[0] == code and r[1] == {"ok": False, "error": err}, r)
    call(es, "POST", "/_refresh", body={}, base=ES)
    ok("rejected exposures store nothing", len(es_ids("tutorexposure", {"term": {"user": USER}})) == 1)
    reset_dc()
    dc4 = must("dc after exposure", nxt(mode="dailychallenge"))
    dc_invariants("dc after exposure", dc4)
    topics_json = must("topics.json", call(me, "GET", "/services/module/entitytopic/topics.json"))
    ok("state.json topics = learner-visible topics with tutorials (topics.json)", sorted(TOPICS) == sorted(t["id"] for t in topics_json["topics"] if t["tutorials"]), (TOPICS, topics_json["topics"]))
    ok("shown (exposure) but not submitted stays new", any(i["questionid"] == SEQ[T][0] and i["bucket"] == "new" for i in dc4["items"]), dc4["items"])

    # ------------------------------------------------------------ content_unavailable: unservable content never starts a session
    call(es, "POST", "/_refresh", body={}, base=ES)
    cu = next(((tid, b) for tid in TOPICS for sc, b in [nxt(mode="learn", topicid=tid)] if sc == 200 and b["items"]), None)
    if cu is None:
        ok("content_unavailable: a topic with learn items left to check", False)
    else:
        ctid, cq = cu[0], cu[1]["items"][0]
        CQ, CFLAG = cq["questionid"], "contentunavailable_" + cu[1]["items"][0]["questionid"]
        qsrc = es_doc("entityquestion", CQ)
        sessions_before = len(es_ids("learningsession", {"term": {"user": USER}}))
        try:
            must("blank option_d", call(admin, "PUT", f"/services/lists/data/entityquestion/{quote(CQ)}.json", body={"id": CQ, "option_d": ""}))
            r = nxt(mode="learn", topicid=ctid)
            call(es, "POST", "/_refresh", body={}, base=ES)
            u = ((r[1].get("items") if isinstance(r[1], dict) else None) or [{}])[0]
            ok("content_unavailable: next.json 409 with question/component/section ids and the problem, no session started",
               r[0] == 409 and r[1].get("error") == "content_unavailable" and u.get("questionid") == CQ and u.get("componentid") == cq["componentid"]
               and u.get("sectionid") == cq["sectionid"] and u.get("tutorialid") == cq["tutorialid"] and u.get("problem") == "missing_option_d"
               and len(es_ids("learningsession", {"term": {"user": USER}})) == sessions_before, r)
            flag = es_doc("questionflag", CFLAG)
            ok("content_unavailable: one open questionflag for administrators", flag is not None and fid(flag.get("reason")) == "content_unavailable"
               and fid(flag.get("status")) == "open" and fid(flag.get("entityquestion")) == CQ and "missing_option_d" in str(flag.get("note")), flag)
            nxt(mode="learn", topicid=ctid)
            call(es, "POST", "/_refresh", body={}, base=ES)
            flags = es_ids("questionflag", {"bool": {"must": [{"term": {"entityquestion": CQ}}, {"term": {"reason": "content_unavailable"}}]}})
            ok("content_unavailable: a second refusal reports nothing new (create-only flag)", flags == [CFLAG], flags)
        finally:
            must("restore option_d", call(admin, "PUT", f"/services/lists/data/entityquestion/{quote(CQ)}.json", body={"id": CQ, "option_d": qsrc.get("option_d")}))
            delete_rows("questionflag", [CFLAG])
        r = nxt(mode="learn", topicid=ctid)
        ok("content restored: learn serves the question again", r[0] == 200 and r[1]["items"][0]["questionid"] == CQ and r[1]["sessionid"], r[0])

    # ------------------------------------------------------------ answer.json: server-verified, synchronous, retry-safe
    ids4 = [i["questionid"] for i in dc4["items"]]
    outside = next(x for x in reversed(SEQ[T]) if x not in ids4)
    right = fid(QROW[q0].get("correctoption"))
    letters = [L for L in "ABCDEF" if (QROW[q0].get("option_" + L.lower()) or "").strip()]
    wrong = next(L for L in letters if L != right)
    A1 = "lc" + secrets.token_hex(12)
    LS1 = must("learn s1 session", nxt(mode="learn", topicid=T, sectionid=S1))
    ok("next.json learn: sessionid issued, items = the stored snapshot in order", LS1.get("sessionid") and LS1["items"][0]["questionid"] == q0
       and json.loads((es_doc("learningsession", LS1["sessionid"]) or {}).get("questionlist", "[]")) == [i["questionid"] for i in LS1["items"]]
       and fid((es_doc("learningsession", LS1["sessionid"]) or {}).get("mode")) == "learn" and (es_doc("learningsession", LS1["sessionid"]) or {}).get("expiresat"), LS1.get("sessionid"))
    LT = must("learn topic session", nxt(mode="learn", topicid=T))
    r = post_answer(questionid=SEQ[T][1], selectedoption=fid(QROW[SEQ[T][1]].get("correctoption")), confidence="confident", hintlevel="0", mode="learn", scopetype="topic", scopeid=T, sessionid=LT["sessionid"])
    ok("answer.json learn: skipping ahead of the next unanswered question = 409 out_of_order", r == (409, {"ok": False, "error": "out_of_order"}), r)
    r = call(me, "POST", EXP, form={"questionid": SEQ[T][1], "mode": "learn", "scopetype": "topic", "scopeid": T, "sessionid": LT["sessionid"]})
    ok("exposure.json learn: a later question of the session may be shown", r[0] == 200, r)
    wipe_user_rows(("tutorexposure",))
    good = {"questionid": q0, "selectedoption": right, "confidence": "notsure", "hintlevel": "1", "mode": "learn", "scopetype": "subtopic", "scopeid": it0["sectionid"], "sessionid": LS1["sessionid"],
            "topicid": T, "tutorialid": it0["tutorialid"], "sectionid": it0["sectionid"], "componentid": it0["componentid"]}
    r = post_answer(attemptid=A1, **good)
    ok("answer.json learn: stored, correctness and hierarchy from the server", r[0] == 200 and r[1].get("answerid") == f"{USER}_{A1}" and r[1].get("duplicate") is False and r[1].get("iscorrect") is True
       and all(r[1].get(k) == v for k, v in canon0.items()), r)
    row = es_doc("tutoranswer", f"{USER}_{A1}") or {}
    ok("answer row: mode, scope, hint, canonical hierarchy, attemptid persisted", fid(row.get("mode")) == "learn" and fid(row.get("scopetype")) == "subtopic" and fid(row.get("scopeid")) == it0["sectionid"]
       and int(row.get("hintlevel", -1)) == 1 and fid(row.get("entitytutorial")) == it0["tutorialid"] and fid(row.get("componentsection")) == it0["sectionid"] and str(row.get("iscorrect")).lower() == "true"
       and row.get("attemptid") == A1 and fid(row.get("answerconfidence")) == "notsure" and fid(row.get("user")) == USER,
       {k: row.get(k) for k in ("mode", "scopetype", "scopeid", "hintlevel", "entitytutorial", "componentsection", "iscorrect", "attemptid", "answerconfidence", "user")})
    t = topic_of(state(T), T)
    ok("answer.json learn advances the sequence (answered 1, next = q2)", t["answered"] == 1 and t["nextquestionid"] == SEQ[T][1], (t["answered"], t["nextquestionid"]))
    r = post_answer(attemptid=A1, **good)
    ok("answer.json retry with the same attemptid: duplicate, same row", r[0] == 200 and r[1].get("duplicate") is True and r[1].get("answerid") == f"{USER}_{A1}", r)
    r = post_answer(attemptid=A1, **dict(good, selectedoption=wrong))
    ok("answer.json same attemptid, different payload: 409 attempt_conflict", r == (409, {"ok": False, "error": "attempt_conflict"}), r)
    base = dict(good, topicid=None, tutorialid=None, sectionid=None, componentid=None)
    ANSWER_REJECTS = [
        (dict(base, attemptid=""), 400, "missing_attemptid"),
        (dict(base, attemptid="short"), 400, "bad_attemptid"),
        (dict(base, selectedoption=None), 400, "missing_selectedoption"),
        (dict(base, selectedoption="G"), 400, "bad_selectedoption"),
        (dict(base, confidence=None), 400, "missing_confidence"),
        (dict(base, confidence="sure"), 400, "bad_confidence"),
        (dict(base, hintlevel=None), 400, "missing_hintlevel"),
        (dict(base, hintlevel="4"), 400, "bad_hintlevel"),
        (dict(base, hintlevel="-1"), 400, "bad_hintlevel"),
        (dict(base, hintlevel="1.5"), 400, "bad_hintlevel"),
        (dict(base, mode=None), 400, "missing_mode"),
        (dict(base, mode="quiz"), 400, "bad_mode"),  # evaluation is an accepted mode now (2026-09-16-evaluation-mode)
        (dict(base, scopetype=None, scopeid=None), 400, "missing_scope"),
        (dict(base, scopetype="topic", scopeid=T2), 409, "scope_mismatch"),
        (dict(base, scopeid=S2), 409, "scope_mismatch"),
        (dict(base, tutorialid="not-" + it0["tutorialid"]), 409, "hierarchy_mismatch"),
        (dict(base, topicid=T2), 409, "hierarchy_mismatch"),
        (dict(base, mode="improve", scopetype="topic", scopeid=T), 409, "improve_locked"),
        (dict(base, mode="dailychallenge", scopetype=None, scopeid=None, sessionid=dc4["sessionid"], questionid=outside, selectedoption=fid(QROW[outside].get("correctoption"))), 409, "not_in_daily_challenge"),
        (dict(base, mode="dailychallenge"), 400, "scope_not_allowed"),
        (dict(base, sessionid=None), 400, "missing_sessionid"),
        (dict(base, sessionid=USER + "_nope"), 404, "unknown_session"),
        (dict(base, scopetype="topic", scopeid=T), 409, "session_mismatch"),
        (dict(base), 409, "already_answered"),
        (dict(base, sessionid=must("s1 session after q0", nxt(mode="learn", topicid=T, sectionid=S1))["sessionid"]), 409, "not_in_session"),
        (dict(base, questionid="no-such-question"), 404, "unknown_question"),
    ]
    q1 = SECTIONS[S1][1]
    empty1 = next((L for L in "ABCDEF" if not (QROW[q1].get("option_" + L.lower()) or "").strip()), None)
    if empty1:  # a blank option of the next question (q0 is answered by now)
        ANSWER_REJECTS.append((dict(base, questionid=q1, selectedoption=empty1, sessionid=must("s1 session q1", nxt(mode="learn", topicid=T, sectionid=S1))["sessionid"]), 400, "bad_selectedoption"))
    for form, code, err in ANSWER_REJECTS:
        r = post_answer(**form)
        ok(f"answer.json rejects {err}", r == (code, {"ok": False, "error": err}), (form, r))
    call(es, "POST", "/_refresh", body={}, base=ES)
    ok("rejected answers store nothing (one row)", len(es_ids("tutoranswer", {"term": {"user": USER}})) == 1)
    ok("answer row stores its learningsession", (es_doc("tutoranswer", f"{USER}_{A1}") or {}).get("learningsession") == LS1["sessionid"])
    dcq = next(i for i in dc4["items"] if i["questionid"] != q0)
    r = post_answer(questionid=dcq["questionid"], selectedoption=wrong if dcq["questionid"] == q0 else fid(QROW[dcq["questionid"]].get("correctoption")), confidence="confident", hintlevel="0", mode="dailychallenge", sessionid=dc4["sessionid"])
    d = must("dc after answer", nxt(mode="dailychallenge"))
    ok("answer.json dailychallenge (question in today's set): stored, item done, same set", r[0] == 200 and fid((es_doc("tutoranswer", r[1]["answerid"]) or {}).get("mode")) == "dailychallenge"
       and [i["questionid"] for i in d["items"]] == ids4 and next(i for i in d["items"] if i["questionid"] == dcq["questionid"])["done"] is True, (r, d["items"][:3]))
    before = topic_of(state(T), T)
    answer(outside, True, "confident", "")
    after = topic_of(state(T), T)
    ln = must("learn", nxt(mode="learn", topicid=T))
    ok("row without mode reads as legacy: does not advance the sequence", after["answered"] == before["answered"] and outside in [i["questionid"] for i in ln["items"]], (before["answered"], after["answered"]))
    wipe_user_rows(("tutoranswer", "dailychallengeset", "tutorexposure", "learningsession"))

    # ------------------------------------------------------------ Daily Challenge day = org timezone (catalog setting testu_timezone, UTC fallback)
    prev_tz = setting("testu_timezone", "Pacific/Kiritimati")
    try:
        def tz_case(tz, expect_tz, reason):
            reset_dc()
            before_day = datetime.datetime.now(ZoneInfo(expect_tz)).date()
            d = must("dc tz", nxt(mode="dailychallenge"))
            after_day = datetime.datetime.now(ZoneInfo(expect_tz)).date()
            rows = dc_rows()
            src = rows[0][1] if rows else {}
            day = datetime.date.fromisoformat(d.get("localdate", "1970-01-01"))
            ok(f"dc timezone {tz}: localdate/id in {expect_tz}, stored date + timezone + version + created, reason {reason}",
               d.get("timezone") == expect_tz and day in (before_day, after_day) and len(rows) == 1 and rows[0][0] == f"{USER}_{day.strftime('%Y%m%d')}"
               and src.get("localdate") == d["localdate"] and src.get("timezone") == expect_tz and fid(src.get("algorithmversion")) == "dc-v0" and src.get("datecreated")
               and json.loads(src.get("inputs", "{}")).get("timezonereason") == reason, (d.get("timezone"), d.get("localdate"), rows))
            return d
        d1 = tz_case("Pacific/Kiritimati", "Pacific/Kiritimati", None)
        d2 = must("dc reopen", nxt(mode="dailychallenge"))
        ok("dc same local day: reopen returns the identical set, still one row", [i["questionid"] for i in d1["items"]] == [i["questionid"] for i in d2["items"]] and len(dc_rows()) == 1)
        setting("testu_timezone", "Etc/GMT+12")  # UTC-12: always a different date than UTC+14
        d3 = must("dc other tz", nxt(mode="dailychallenge"))
        ok("dc timezone change moves the challenge day (new set id)", d3.get("localdate") != d1.get("localdate") and len(dc_rows()) == 2, (d1.get("localdate"), d3.get("localdate")))
        setting("testu_timezone", "Mars/Olympus_Mons")
        tz_case("invalid", "UTC", "timezone_invalid")
        setting("testu_timezone", None)
        tz_case("unset", "UTC", "timezone_not_configured")
    finally:
        setting("testu_timezone", prev_tz)

    # ------------------------------------------------------------ Daily Challenge size settings: invalid -> defaults 5 / 20 + reason
    prev_min, prev_max = (es_doc("catalogsettings", "testu_dailychallenge_min") or {}).get("value"), (es_doc("catalogsettings", "testu_dailychallenge_max") or {}).get("value")
    try:
        for mn, mx, reason, emin, emax in (("0", "20", "min_below_1", 5, 20), ("10", "5", "max_below_min", 5, 20), ("5", "51", "above_safe_max", 5, 20), ("1", "50", None, 1, 50), ("3", "3", None, 3, 3)):
            setting("testu_dailychallenge_min", mn)
            setting("testu_dailychallenge_max", mx)
            reset_dc()
            d = must("dc sizes", nxt(mode="dailychallenge"))
            inp = stored_inputs()
            ok(f"dc sizes min {mn} max {mx} -> {emin}/{emax}, reason {reason}", inp.get("min") == emin and inp.get("max") == emax and inp.get("sizesreason") == reason and emin <= len(d["items"]) <= emax, inp)
    finally:
        setting("testu_dailychallenge_min", prev_min)
        setting("testu_dailychallenge_max", prev_max)
        reset_dc()

    # ------------------------------------------------------------ remediation boundaries (fresh history per case)
    def remediation_case(name, attempts, expect):
        wipe_user_rows(("tutoranswer", "dailychallengeset"))
        base = NOW - datetime.timedelta(days=20)
        for k, (q, correct, conf, mode, hint) in enumerate(attempts):
            answer(q, correct, conf, mode, base + datetime.timedelta(minutes=k), hint)
        reset_dc()
        d = must("dc remediation", nxt(mode="dailychallenge"))
        got = stored_inputs().get("remediation")
        ok(f"remediation {name} -> {expect}", got is expect and (not expect or d["items"][0]["bucket"] == "reinforcement"), (stored_inputs(), d["items"][:2]))

    q = SEQ[T]
    hci_same = lambda n, h: [(q[0], False, "confident", "learn", 0) if k < h else (q[1 + (k - h) % 9], True, "confident", "learn", 0) for k in range(n)]
    remediation_case("(b) 7 recent, 3 HCI", hci_same(7, 3), False)
    remediation_case("(b) exactly 8 recent, 2 HCI (25%)", hci_same(8, 2), False)
    remediation_case("(b) exactly 8 recent, 3 HCI", hci_same(8, 3), True)
    remediation_case("(b) 12 recent, 3 HCI (25%)", hci_same(12, 3), True)
    three = [(q[i], False, "mostlysure", "learn", 0) for i in range(3)]
    remediation_case("(a) 3 distinct unresolved HCI", three, True)
    remediation_case("(a) resolved by confident unassisted correct", three + [(q[0], True, "confident", "improve", 1), (q[0], True, "confident", "improve", 0)], False)
    wipe_user_rows(("tutoranswer", "dailychallengeset"))

    # fill order on the server: every learning-answered question inside the 48 h cooldown, most content pending
    for x in (q[3], q[4], q[5]):
        answer(x, True, "confident", "learn", NOW - datetime.timedelta(hours=1))
    reset_dc()
    d = must("dc cooldown fill", nxt(mode="dailychallenge"))
    inp = stored_inputs()
    dc_invariants("dc all reinforcement in cooldown", d)
    ok("dc cooldown relaxed for reinforcement before new overflow (new <= maxnew)", "cooldown" in inp.get("relaxations", []) and d["newcount"] <= inp["maxnew"] and d["reinforcementcount"] >= 1, (d["newcount"], d["reinforcementcount"], inp))
    wipe_user_rows(("tutoranswer", "dailychallengeset"))

    # ------------------------------------------------------------ subtopic progression: per-topic versioned policy, server-owned unlocks
    PRIOR_POLICY[:] = policy_rows(T)
    SUBS = TOPIC_SECTIONS[T]
    S3 = SUBS[2] if len(SUBS) > 2 else None
    locked_q = [x for x in SEQ[T] if SECTION_OF[x] != S1]
    if not PRIOR_POLICY:
        t = topic_of(state(T), T)
        ok("policy: unconfigured topic = open, version 0, policy_not_configured, all subtopics unlocked", t["subtopicunlockpolicy"] == "open" and t["subtopicpolicyversion"] == 0
           and t["subtopicpolicyreason"] == "policy_not_configured" and all(x["unlocked"] for x in t["sections"]), {k: t[k] for k in t if k.startswith("subtopic")})
    r = call(me, "POST", PP, form={"topicid": T, "policy": "sequential_completion", "expectedversion": "0"})
    ok("policy: learner cannot change progression (403), nothing stored", r[0] == 403 and r[1] == {"ok": False, "error": "forbidden"} and policy_rows(T) == PRIOR_POLICY, r)
    ok("policy: learner cannot read the policy admin endpoint (403); state canmanageprogression false", call(me, "GET", PP)[0] == 403 and state()["canmanageprogression"] is False)
    v0 = must("policy get", call(admin, "GET", PP + "?topicid=" + quote(T)))["topic"]["version"]
    for form, code, err in (({"topicid": T, "policy": "bogus", "expectedversion": v0}, 400, "bad_policy"), ({"topicid": T, "expectedversion": v0}, 400, "bad_policy"),
                            ({"topicid": T, "policy": "sequential_mastery", "expectedversion": v0}, 400, "missing_requiredlevel"),
                            ({"topicid": T, "policy": "sequential_mastery", "requiredlevel": "master", "expectedversion": v0}, 400, "bad_requiredlevel"),
                            ({"topicid": "no-such-topic", "policy": "open", "expectedversion": v0}, 404, "unknown_topic"), ({"policy": "open"}, 400, "missing_topicid"),
                            ({"topicid": T, "policy": "open"}, 400, "missing_expectedversion"), ({"topicid": T, "policy": "open", "expectedversion": "x"}, 400, "bad_expectedversion")):
        r = call(admin, "POST", PP, form=form)
        ok(f"policy: admin save rejects {err}", r[0] == code and r[1] == {"ok": False, "error": err} and policy_rows(T) == PRIOR_POLICY, r)
    a0 = len(audits(T))
    r = must("policy save", save_policy("sequential_completion", "expert", expected=v0))
    ok("policy: admin save appends version n+1 (level dropped unless mastery)", r["unchanged"] is False and r["topic"]["version"] == v0 + 1 and r["topic"]["policy"] == "sequential_completion"
       and r["topic"]["requiredlevel"] is None and r["canmanage"] is True, r)
    r2 = must("policy save same", save_policy("sequential_completion", expected=v0 + 1))
    hist = must("policy history", call(admin, "GET", PP + "?topicid=" + quote(T)))
    ok("policy: same policy again = unchanged, no new version", r2["unchanged"] is True and r2["topic"]["version"] == v0 + 1 and len(policy_rows(T)) == len(PRIOR_POLICY) + 1, r2)
    r3 = save_policy("open", expected=v0)
    ok("policy: stale expectedversion = 409 version_conflict with the current version, nothing stored", r3[0] == 409 and r3[1].get("error") == "version_conflict" and r3[1].get("currentversion") == v0 + 1
       and len(policy_rows(T)) == len(PRIOR_POLICY) + 1, r3)
    ok("policy: history newest first with user, appliesto, date", hist["versions"][0]["version"] == v0 + 1 and hist["versions"][0]["user"] == os.environ["EME_USER"]
       and hist["versions"][0]["appliesto"] == "unreached_subtopics" and hist["versions"][0]["datecreated"], hist["versions"][:2])
    au = audits(T)
    # auditevent before/after are index="false" and not returned by search; the immutable version rows hold the values
    ok("policy: change audited (actor, action, topic)", len(au) == a0 + 1 and fid(max(au, key=lambda x: x.get("datecreated", ""))["actor"]) == os.environ["EME_USER"], au[-1:] if au else au)
    from concurrent.futures import ThreadPoolExecutor
    racers = [("open", None), ("sequential_mastery", "competent"), ("sequential_mastery", "expert"), ("open", None), ("sequential_mastery", "beginner"), ("sequential_mastery", "competent")]
    with ThreadPoolExecutor(len(racers)) as pool:
        results = list(pool.map(lambda pl: save_policy(pl[0], pl[1], op=login(os.environ["EME_USER"], os.environ["EME_PASSWORD"]), expected=v0 + 1), racers))
    wins = [x for x in results if x[0] == 200]
    ok(f"policy: {len(racers)} concurrent saves of version {v0 + 2}: exactly one stored, the rest 409 version_conflict, no overwrite",
       len(wins) == 1 and all(x[0] == 409 and x[1].get("error") == "version_conflict" for x in results if x[0] != 200) and len(policy_rows(T)) == len(PRIOR_POLICY) + 2
       and fid((es_doc("subtopicpolicy", f"{T}_v{v0 + 2}") or {}).get("unlockpolicy")) == wins[0][1]["topic"]["policy"], [(x[0], x[1].get("error"), (x[1].get("topic") or {}).get("policy")) for x in results])
    r = must("policy back to completion", save_policy("sequential_completion"))
    VC = r["topic"]["version"]
    allp = must("policy list", call(admin, "GET", PP))
    ok("policy: list has every topic with ordered subtopics", {x["id"] for x in allp["topics"]} >= set(TOPICS) and [x["id"] for x in next(x for x in allp["topics"] if x["id"] == T)["subtopics"]] == SUBS, allp["topics"][:1])

    t = topic_of(state(T), T)
    sec = {x["id"]: x for x in t["sections"]}
    ok("completion: first subtopic unlocked, second locked (previous_subtopic_incomplete), next question in first", sec[S1]["unlocked"] and sec[S1]["unlockreason"] == "first_subtopic"
       and not sec[S2]["unlocked"] and sec[S2]["unlockreason"] == "previous_subtopic_incomplete" and sec[S2]["previoussubtopicid"] == S1 and sec[S2]["previouscomplete"] is False
       and SECTION_OF[t["nextquestionid"]] == S1 and sec[S2]["position"] == 2, (sec[S1], sec[S2]))
    stc, body = nxt(mode="learn", topicid=T, sectionid=S2)
    ok("completion: learn next in a locked subtopic = 409 subtopic_locked", stc == 409 and body == {"ok": False, "error": "subtopic_locked"}, (stc, body))
    ln = must("learn topic", nxt(mode="learn", topicid=T))
    ok("completion: learn topic scope only the unlocked subtopic, lockedquestions counted, not blocked", [i["questionid"] for i in ln["items"]] == SECTIONS[S1] and ln["lockedquestions"] == len(locked_q)
       and ln["complete"] is False and ln["blocked"] is False, (ln["total"], ln["lockedquestions"]))
    xq = SECTIONS[S2][0]
    r = post_answer(questionid=xq, selectedoption=fid(QROW[xq].get("correctoption")), confidence="confident", hintlevel="0", mode="learn", scopetype="subtopic", scopeid=S2, sessionid=ln["sessionid"])
    ok("completion: answer.json learn in a locked subtopic = 409 subtopic_locked", r[0] == 409 and r[1] == {"ok": False, "error": "subtopic_locked"}, r)
    r = call(me, "POST", EXP, form={"questionid": xq, "mode": "learn", "scopetype": "topic", "scopeid": T, "sessionid": ln["sessionid"]})
    ok("completion: exposure.json learn in a locked subtopic = 409 subtopic_locked", r[0] == 409 and r[1] == {"ok": False, "error": "subtopic_locked"}, r)
    reset_dc()
    d = must("dc locked", nxt(mode="dailychallenge"))
    prog = stored_inputs().get("progression", {}).get(T, {})
    ok("dc: no unanswered question from a locked subtopic", not any(i["bucket"] == "new" and i["questionid"] in locked_q for i in d["items"]) and stored_inputs().get("lockedsubtopics", 0) >= 1,
       ([i["questionid"] for i in d["items"] if i["questionid"] in locked_q], stored_inputs().get("lockedsubtopics")))
    ok("dc: stored inputs keep the progression policy context (version, policy) and locked subtopic ids", prog.get("policyversion") == VC and prog.get("policy") == "sequential_completion"
       and S2 in stored_inputs().get("lockedsubtopicids", []), stored_inputs().get("progression"))
    old = NOW - datetime.timedelta(days=12)
    for k, x in enumerate(SECTIONS[S1]):
        answer(x, False, "confident", "evaluation", old + datetime.timedelta(minutes=k))
    ok("completion: evaluation answers do not unlock", not next(x for x in topic_of(state(T), T)["sections"] if x["id"] == S2)["unlocked"])
    for k, x in enumerate(SECTIONS[S1]):
        answer(x, False, "noidea", "improve", old + datetime.timedelta(minutes=30 + k))
    ok("completion: improve answers do not complete the learning sequence", not next(x for x in topic_of(state(T), T)["sections"] if x["id"] == S2)["unlocked"])
    last1 = SECTIONS[S1][-1]
    for k, x in enumerate(SECTIONS[S1][:-1]):
        answer(x, False, "noidea", "dailychallenge" if k % 2 else "learn", old + datetime.timedelta(hours=1, minutes=k))
    s1 = must("learn s1 last", nxt(mode="learn", topicid=T, sectionid=S1))
    ok("completion: session of the subtopic's last unanswered question", [i["questionid"] for i in s1["items"]] == [last1] and s1["total"] == 1, s1)
    r = post_answer(questionid=last1, selectedoption=next(L for L in "ABCDEF" if L != fid(QROW[last1].get("correctoption")) and (QROW[last1].get("option_" + L.lower()) or "").strip()),
                    confidence="noidea", hintlevel="0", mode="learn", scopetype="subtopic", scopeid=S1, sessionid=s1["sessionid"])
    row = unlock_row(S2)
    ev = json.loads(row["unlockevidence"]) if row else {}
    ok("completion: the accepted answer that completes the subtopic records the unlock at once (all wrong still completes)", r[0] == 200 and row is not None, (r, row))
    ok("unlock recorded: topic, subtopic, previous, version, policy, condition, evidence, date", row and fid(row["componentsection"]) == S2 and fid(row["entitytopic"]) == T
       and row["previoussection"] == S1 and int(row["policyversion"]) == VC and row["unlockpolicy"] == "sequential_completion" and row["unlockcondition"] == "previous_subtopic_complete"
       and ev.get("answered") == ev.get("questions") == len(SECTIONS[S1]) and row.get("datecreated"), row)
    s2 = next(x for x in topic_of(state(T), T)["sections"] if x["id"] == S2)
    ok("completion: state shows the recorded unlock with its date", s2["unlocked"] and s2["unlockreason"] == "unlock_recorded" and s2["unlockedat"], s2)

    # reads never write: without the row, state.json reports the condition unlock and records nothing
    delete_rows("subtopicunlock", [f"{USER}_{S2}"])
    s2a = next(x for x in topic_of(state(T), T)["sections"] if x["id"] == S2)
    s2b = next(x for x in topic_of(state(T), T)["sections"] if x["id"] == S2)
    ok("state.json is read-only: condition unlock reported, unlockedat null, no row written", s2a["unlocked"] and s2a["unlockreason"] == "previous_subtopic_complete" and s2a["unlockedat"] is None
       and s2b["unlockedat"] is None and unlock_row(S2) is None, (s2a, unlock_row(S2)))
    lt = must("learn topic s2", nxt(mode="learn", topicid=T))
    ok("next.json is read-only for unlocks", unlock_row(S2) is None and lt["items"][0]["questionid"] == SECTIONS[S2][0], lt["items"][:1])
    r = call(me, "POST", EXP, form={"questionid": SECTIONS[S2][0], "mode": "learn", "scopetype": "topic", "scopeid": T, "sessionid": lt["sessionid"]})
    row = unlock_row(S2)
    ok("exposure.json in the reached subtopic records its unrecorded condition unlock", r[0] == 200 and row and row["unlockcondition"] == "previous_subtopic_complete", (r, row))

    # backfill: explicit, idempotent, create-only
    BF = "/services/testu/learn/unlockbackfill.json"
    ok("backfill: learner 403, GET 405", call(me, "POST", BF, form={"user": USER})[0] == 403 and call(admin, "GET", BF + "?user=" + quote(USER))[0] == 405)
    kept = unlock_row(S2)["datecreated"]
    b1 = must("backfill again", call(admin, "POST", BF, form={"user": USER}))
    ok("backfill: rerun creates nothing and keeps the original date and evidence", b1["created"] == 0 and b1["learners"] == 1 and unlock_row(S2)["datecreated"] == kept, (b1, unlock_row(S2)))
    delete_rows("subtopicunlock", [f"{USER}_{S2}"])
    b2 = must("backfill", call(admin, "POST", BF, form={"user": USER}))
    ok("backfill: recreates a missing condition unlock", b2["created"] == 1 and unlock_row(S2) and unlock_row(S2)["unlockcondition"] == "previous_subtopic_complete", (b2, unlock_row(S2)))

    r = must("policy mastery", save_policy("sequential_mastery", "competent"))
    VM = r["topic"]["version"]
    t = topic_of(state(T), T)
    sec = {x["id"]: x for x in t["sections"]}
    ok("stricter policy (mastery competent) does not relock a recorded unlock (S1 is beginner)", t["subtopicunlockpolicy"] == "sequential_mastery" and t["subtopicpolicyversion"] == VM
       and sec[S2]["unlocked"] and sec[S2]["unlockreason"] == "unlock_recorded" and sec[S2]["previousband"] == "beginner" and sec[S2]["previousmeetsrequirement"] is False, sec[S2])
    if S3:
        last2 = SECTIONS[S2][-1]
        for k, x in enumerate(SECTIONS[S2][:-1]):
            answer(x, False, "confident", "learn", old + datetime.timedelta(hours=2, minutes=k))
        s2s = must("learn s2 last", nxt(mode="learn", topicid=T, sectionid=S2))
        r = post_answer(questionid=last2, selectedoption=next(L for L in "ABCDEF" if L != fid(QROW[last2].get("correctoption")) and (QROW[last2].get("option_" + L.lower()) or "").strip()),
                        confidence="confident", hintlevel="0", mode="learn", scopetype="subtopic", scopeid=S2, sessionid=s2s["sessionid"])
        s3 = next(x for x in topic_of(state(T), T)["sections"] if x["id"] == S3)
        ok("mastery: complete but below the required level stays locked, nothing recorded", r[0] == 200 and not s3["unlocked"] and s3["unlockreason"] == "previous_subtopic_below_required_mastery"
           and s3["previouscomplete"] is True and s3["requiredlevel"] == "competent" and unlock_row(S3) is None, (r, s3))
        bl = must("learn blocked", nxt(mode="learn", topicid=T))
        ok("mastery: topic learn with nothing unlocked left = blocked (reason, subtopics, level, band, improve recommendation), no session",
           bl["items"] == [] and bl["complete"] is False and bl["blocked"] is True and bl["blockedreason"] == "previous_subtopic_below_required_mastery" and bl["blockedsubtopicid"] == S3
           and bl["previoussubtopicid"] == S2 and bl["requiredlevel"] == "competent" and bl["currentband"] == "beginner" and bl["recommendedmode"] == "improve"
           and bl["recommendedscope"] == {"scopetype": "subtopic", "scopeid": S2} and bl["lockedquestions"] > 0 and bl["sessionid"] is None, bl)
        imp = must("improve s2", nxt(mode="improve", topicid=T, sectionid=S2, size=len(SECTIONS[S2])))
        outside_imp = SECTIONS[S1][0]
        r = post_answer(questionid=outside_imp, selectedoption=fid(QROW[outside_imp].get("correctoption")), confidence="confident", hintlevel="0", mode="improve", scopetype="subtopic", scopeid=S2, sessionid=imp["sessionid"])
        ok("improve: a question outside the scope is rejected before session membership (scope_mismatch)", r == (409, {"ok": False, "error": "scope_mismatch"}), r)
        imp_small = must("improve s2 size 1", nxt(mode="improve", topicid=T, sectionid=S2, size=1))
        notsel = next(i["questionid"] for i in imp["items"] if i["questionid"] != imp_small["items"][0]["questionid"])
        r = post_answer(questionid=notsel, selectedoption=fid(QROW[notsel].get("correctoption")), confidence="confident", hintlevel="0", mode="improve", scopetype="subtopic", scopeid=S2, sessionid=imp_small["sessionid"])
        ok("improve: a question not in the server-selected set = 409 not_in_session", r == (409, {"ok": False, "error": "not_in_session"}), r)
        stored = 0
        for i in imp["items"]:
            x = i["questionid"]
            rr = post_answer(questionid=x, selectedoption=fid(QROW[x].get("correctoption")), confidence="confident", hintlevel="0", mode="improve", scopetype="subtopic", scopeid=S2, sessionid=imp["sessionid"])
            stored += rr[0] == 200
            if unlock_row(S3):
                break
        row3 = unlock_row(S3)
        ok("mastery: improve answers that reach the level record the unlock (mastery_met)", row3 and row3["unlockcondition"] == "previous_subtopic_mastery_met" and int(row3["policyversion"]) == VM
           and json.loads(row3["unlockevidence"]).get("band") in ("competent", "expert"), (stored, row3))
        for k, x in enumerate(SECTIONS[S2]):
            answer(x, False, "confident", "improve", datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(minutes=1 + k))
        s3 = next(x for x in topic_of(state(T), T)["sections"] if x["id"] == S3)
        ok("mastery: later lower mastery (improve wrong) does not relock", s3["unlocked"] and s3["unlockreason"] == "unlock_recorded" and s3["previousmeetsrequirement"] is False, s3)
    restore_policy()
    t = topic_of(state(T), T)
    ok("policy versions removed -> topic back to its prior policy", t["subtopicpolicyversion"] == (0 if not PRIOR_POLICY else t["subtopicpolicyversion"]), t["subtopicpolicyversion"])
    wipe_user_rows(("tutoranswer", "dailychallengeset", "subtopicunlock", "learningsession"))

    # ------------------------------------------------------------ partial / evaluation
    old = NOW - datetime.timedelta(days=10)
    answer(q[0], True, "confident", "learn", old)
    answer(q[2], True, "confident", "dailychallenge", old + datetime.timedelta(minutes=1))
    t = topic_of(state(T), T)
    ln = must("learn", nxt(mode="learn", topicid=T))
    ok("partial: resume at first unanswered although q3 was answered in dailychallenge",
       t["nextquestionid"] == q[1] and t["answered"] == 2 and ln["items"][0]["questionid"] == q[1] and q[2] not in [i["questionid"] for i in ln["items"]], (t["nextquestionid"], t["answered"], ln["items"][:3]))
    answer(q[1], True, "confident", "evaluation", old + datetime.timedelta(minutes=2))
    t = topic_of(state(T), T)
    ln = must("learn", nxt(mode="learn", topicid=T))
    ok("evaluation answer does not advance learn", t["nextquestionid"] == q[1] and t["answered"] == 2 and ln["items"][0]["questionid"] == q[1], (t["nextquestionid"], t["answered"]))
    reset_dc()
    d = must("dc", nxt(mode="dailychallenge"))
    ok("dc evaluation-only answer stays new", any(i["questionid"] == q[1] and i["bucket"] == "new" for i in d["items"]), d["items"])

    # ------------------------------------------------------------ subtopic complete + priority order
    s1 = SECTIONS[S1]
    plain = [x for x in s1 if DIFF[x] != "expert"]
    a, b, c = plain[0], plain[1], plain[2]
    latest = {}
    for k, x in enumerate(s1):
        correct, conf = (False, "confident") if x == a else (True, "notsure") if x == b else (False, "notsure") if x == c else (True, "confident")
        answer(x, correct, conf, "learn", NOW - datetime.timedelta(days=9) + datetime.timedelta(minutes=k))
        latest[x] = (correct, conf, 0)
    st = state(T)
    t = topic_of(st, T)
    sec = {s["id"]: s for s in t["sections"]}
    ok("subtopic complete unlocks subtopic Improve only",
       sec[S1]["learncomplete"] and sec[S1]["improveavailable"] and not sec[S2]["improveavailable"] and not t["improveavailable"], (sec[S1], sec[S2]["improveavailable"], t["improveavailable"]))
    stc, body = nxt(mode="improve", topicid=T)
    ok("topic Improve still locked", stc == 409 and body.get("error") == "improve_locked", (stc, body))
    imp = must("improve s1", nxt(mode="improve", topicid=T, sectionid=S1, size=len(s1)))
    got = [(i["questionid"], i["reason"]) for i in imp["items"]]
    ok("priority order: highconfwrong, lowconfright, wrong first", got[:3] == [(a, "highconfwrong"), (b, "lowconfright"), (c, "wrong")], got[:4])
    rest = [DIFF[i["questionid"]] for i in imp["items"][3:]]
    s1band = sec[S1]["band"]
    wts = [{"beginner": 1, "competent": 2, "expert": 4}[x] for x in rest]
    ok("priority order: class 4 harder first when band >= competent", LEVELS.index(s1band) < 1 or wts == sorted(wts, reverse=True), (s1band, rest))
    ok("improve default size 10", must("improve", nxt(mode="improve", topicid=T, sectionid=S1))["total"] == min(10, len(s1)))

    # ------------------------------------------------------------ bands + topic override
    exp_s1 = expected_percent(s1, latest)
    exp_t = expected_percent(SEQ[T], latest)
    ok("mastery % and band (org defaults 60/85)", sec[S1]["masterypercent"] == exp_s1 and sec[S1]["band"] == band(exp_s1) and t["masterypercent"] == exp_t and t["band"] == band(exp_t) and t["competentmin"] == 60 and t["expertmin"] == 85,
       (sec[S1]["masterypercent"], exp_s1, sec[S1]["band"], t["masterypercent"], exp_t, t["band"]))
    must("override", call(admin, "PUT", f"/services/lists/data/entitytopic/{quote(T)}.json", body={"id": T, "competentmin": str(max(1, exp_t)), "expertmin": str(max(1, exp_t) + 1)}))
    t = topic_of(state(T), T)
    s1o = next(s for s in t["sections"] if s["id"] == S1)
    ok("topic override moves topic and section bands", t["competentmin"] == max(1, exp_t) and t["band"] == band(exp_t, max(1, exp_t), max(1, exp_t) + 1) == "competent" and s1o["band"] == band(exp_s1, max(1, exp_t), max(1, exp_t) + 1),
       (t["competentmin"], t["expertmin"], t["band"], s1o["band"]))
    restore_topic(T)
    t = topic_of(state(T), T)
    ok("cleared override falls back to org default", t["competentmin"] == 60 and t["expertmin"] == 85 and t.get("thresholdsreason") is None, (t["competentmin"], t["expertmin"]))
    for cmin, emin, reason in (("90", "80", "competentmin_not_below_expertmin"), ("85", "", "competentmin_not_below_expertmin"), ("", "101", "expertmin_above_100"), ("99", "100", None), ("1", "2", None)):
        must("override", call(admin, "PUT", f"/services/lists/data/entitytopic/{quote(T)}.json", body={"id": T, "competentmin": cmin, "expertmin": emin}))
        t = topic_of(state(T), T)
        want = (int(cmin or 60), int(emin or 85)) if reason is None else (60, 85)
        ok(f"topic boundaries {cmin or '-'}/{emin or '-'} -> {want}, reason {reason}", (t["competentmin"], t["expertmin"]) == want and t.get("thresholdsreason") == reason, (t["competentmin"], t["expertmin"], t.get("thresholdsreason")))
    restore_topic(T)
    r = call(me, "PUT", f"/services/lists/data/entitytopic/{quote(T)}.json", body={"id": T, "competentmin": "1", "expertmin": "2"})
    t = topic_of(state(T), T)
    ok("learner cannot override topic boundaries (only admins)", t["competentmin"] == 60 and t["expertmin"] == 85, (r, t["competentmin"], t["expertmin"]))
    restore_topic(T)

    # ------------------------------------------------------------ expert evidence
    experts = [x for x in s1 if DIFF[x] == "expert"]
    noexp = next((s for s in TOPIC_SECTIONS[T] + TOPIC_SECTIONS[T2] if not any(DIFF[x] == "expert" for x in SECTIONS.get(s, []))), None)
    st = state()
    sec_all = {s["id"]: s for tt in st["topics"] for s in tt["sections"]}
    if experts:
        ok("expert evidence ok when all expert questions latest-correct", sec_all[S1]["expertevidence"] == {"ok": True}, sec_all[S1]["expertevidence"])
    ok("topic expert evidence insufficient", topic_of(st, T)["expertevidence"] == {"ok": False, "reason": "insufficient"}, topic_of(st, T)["expertevidence"])
    if noexp:
        ok("no_expert_questions reason", sec_all[noexp]["expertevidence"] == {"ok": False, "reason": "no_expert_questions"}, sec_all[noexp])
    if experts and len(experts) <= 6:
        answer(experts[0], False, "notsure", "learn", NOW - datetime.timedelta(days=8))
        latest[experts[0]] = (False, "notsure", 0)
        s = next(s for s in topic_of(state(T), T)["sections"] if s["id"] == S1)
        ok("one expert question wrong -> insufficient", s["expertevidence"] == {"ok": False, "reason": "insufficient"}, s["expertevidence"])

    # ------------------------------------------------------------ required level (strictest across job roles) + one eligible topic
    for rid, role, lvl in (("lcheck-r1", "lcheck", "competent"), ("lcheck-r2", "otherrole", "expert")):
        must("topicrequirement", call(admin, "PUT", f"/services/lists/data/topicrequirement/{rid}.json", body={"id": rid, "jobrole": role, "entitytopic": T, "requiredlevel": lvl}))
    must("jobrole", call(admin, "POST", "/services/authentication/usersave.json", form={"username": USER, "field": "jobrole", "jobrolevalue": "lcheck"}))
    me = login(USER, PASSWORD)
    st = state()
    t, t2 = topic_of(st, T), topic_of(st, T2)
    ok("required level from own job role only", t["requiredlevel"] == "competent" and t["meetsrequirement"] == (LEVELS.index(t["band"]) >= 1) and t2["requiredlevel"] is None and t2["meetsrequirement"] is None, (t["requiredlevel"], t["meetsrequirement"], t2["requiredlevel"]))
    must("topicrequirement r3", call(admin, "PUT", "/services/lists/data/topicrequirement/lcheck-r3.json", body={"id": "lcheck-r3", "jobrole": "lcheck", "entitytopic": T, "requiredlevel": "expert"}))
    t = topic_of(state(T), T)
    ok("strictest requirement wins; expert needs evidence", t["requiredlevel"] == "expert" and t["meetsrequirement"] is False, (t["requiredlevel"], t["meetsrequirement"]))
    reset_dc()
    d = must("dc one topic", nxt(mode="dailychallenge"))
    dc_invariants("dc one topic", d)
    inp = stored_inputs()
    ok("dc one eligible topic: only that topic, cap not applied", d["items"] and all(i["topicid"] == T for i in d["items"]) and len(d["items"]) == inp.get("n") and "topiccap" not in inp.get("relaxations", []) and inp.get("topicsreason") is None, (inp, [i["topicid"] for i in d["items"]]))
    ok("dc guardrails: >= 1 new and >= 1 reinforcement", d["newcount"] >= 1 and d["reinforcementcount"] >= 1, (d["newcount"], d["reinforcementcount"], inp))

    # done flag: answer one item of today's set
    d1 = must("dc", nxt(mode="dailychallenge"))
    target = d1["items"][0]["questionid"]
    answer(target, True, "confident", "dailychallenge", datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(seconds=2))
    d2 = must("dc", nxt(mode="dailychallenge"))
    ok("dc answered item reported done, same set", [i["questionid"] for i in d1["items"]] == [i["questionid"] for i in d2["items"]] and d2["items"][0]["done"] is True and not any(i["done"] for i in d2["items"][1:]), d2["items"][:2])
    # modes keep their roles: a Learn or Improve answer to a question in today's set neither completes, shrinks nor
    # replaces the item; the Daily Challenge answer still counts as answered for Learn (skipped there)
    later = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(seconds=3)
    for mode_, item in (("learn", next(i for i in d1["items"][1:] if i["bucket"] == "new")), ("improve", next(i for i in d1["items"][1:] if i["bucket"] == "reinforcement"))):
        answer(item["questionid"], True, "confident", mode_, later)
        d3 = must("dc", nxt(mode="dailychallenge"))
        it = next((i for i in d3["items"] if i["questionid"] == item["questionid"]), None)
        ok(f"dc item answered in {mode_} first: still pending, same set and total", it is not None and it["done"] is False and d3["sessionid"] == d1["sessionid"] and d3["total"] == d1["total"]
           and [i["questionid"] for i in d3["items"]] == [i["questionid"] for i in d1["items"]] and sum(i["done"] for i in d3["items"]) == 1, (mode_, it, d3["total"]))
    ok("dc answer counts as answered for Learn (skipped there)", target not in [i["questionid"] for i in must("learn after dc", nxt(mode="learn", topicid=T))["items"]], target)

    # ------------------------------------------------------------ 0 unanswered, small fresh pool: both relaxations
    delete_rows("topicrequirement", ["lcheck-r1", "lcheck-r2", "lcheck-r3"])
    recent = NOW - datetime.timedelta(hours=1)
    done_ids = {x for x in latest} | {q[0], q[2], target}
    for tid in TOPICS:
        for x in SEQ[tid]:
            if x not in done_ids:
                answer(x, True, "confident", "learn", recent)
    reset_dc()
    d = must("dc all answered", nxt(mode="dailychallenge"))
    dc_invariants("dc all answered", d)
    inp = stored_inputs()
    ok("dc 0 unanswered: all reinforcement, size n", d["newcount"] == 0 and d["reinforcementcount"] == len(d["items"]) == inp.get("n") > 0, (d["newcount"], d["reinforcementcount"], inp))
    ok("dc small fresh pool: cooldown and topic cap relaxations recorded", inp.get("relaxations") == ["cooldown", "topiccap"], inp)
    ln = must("learn done", nxt(mode="learn", topicid=T))
    t = topic_of(state(T), T)
    ok("learn complete: empty list, complete true, Improve available", ln["items"] == [] and ln["complete"] is True and t["learncomplete"] and t["improveavailable"], (ln, t["learncomplete"]))
    ok("topic Improve unlocked", must("improve topic", nxt(mode="improve", topicid=T))["total"] == 10)
    for scopetype, scopeid in (("topic", T), ("subtopic", S1)):
        sess = must("improve session", nxt(mode="improve", topicid=T, **({"sectionid": S1} if scopetype == "subtopic" else {}), size=100))
        x = next(i["questionid"] for i in sess["items"] if SECTION_OF[i["questionid"]] == S1)
        r = post_answer(questionid=x, selectedoption=fid(QROW[x].get("correctoption")), confidence="mostlysure", hintlevel="2", mode="improve", scopetype=scopetype, scopeid=scopeid, sessionid=sess["sessionid"])
        row = es_doc("tutoranswer", r[1].get("answerid", "")) if r[0] == 200 else {}
        ok(f"answer.json improve ({scopetype}) once unlocked: mode, scope, hint 2 persisted", r[0] == 200 and fid(row.get("mode")) == "improve" and fid(row.get("scopetype")) == scopetype
           and fid(row.get("scopeid")) == scopeid and int(row.get("hintlevel", -1)) == 2, (r, row))

    # learningsession retention: usable 7 days, kept 30 more, then purged by the computemastery event (idempotent)
    for rid, expired_days in (("lcheck-s-old", 31), ("lcheck-s-recent", 29)):
        must("seed learningsession", call(admin, "PUT", f"/services/lists/data/learningsession/{rid}.json", body={
            "id": rid, "user": USER, "mode": "learn", "scopetype": "topic", "scopeid": T, "questionlist": "[]",
            "datecreated": iso(NOW - datetime.timedelta(days=expired_days + 7)), "expiresat": iso(NOW - datetime.timedelta(days=expired_days))}))

    # ------------------------------------------------------------ computemastery pass 1 + analytics on stored band
    must("recompute", call(admin, "GET", "/services/testu/analytics/recompute.json"))
    import time
    for _ in range(90):  # the shared event runs in the background
        if call(es, "GET", f"/tutormastery/{quote(USER + '_topic_' + T)}", base=ES)[1].get("found"):
            break
        time.sleep(2)
    for _ in range(90):
        if es_doc("learningsession", "lcheck-s-old") is None:
            break
        time.sleep(2)
    ok("retention: session expired 31 days ago purged, 29 days ago kept", es_doc("learningsession", "lcheck-s-old") is None and es_doc("learningsession", "lcheck-s-recent") is not None)
    st = state()
    t = topic_of(st, T)
    s1s = next(s for s in t["sections"] if s["id"] == S1)
    st_s1, row_s1 = call(es, "GET", f"/tutormastery/{quote(USER + '_' + S1)}", base=ES)
    st_t, row_t = call(es, "GET", f"/tutormastery/{quote(USER + '_topic_' + T)}", base=ES)
    r1, rt = row_s1.get("_source", {}), row_t.get("_source", {})
    ok("computemastery section row = engine (band, level, masterypercent)", r1.get("band") == s1s["band"] == r1.get("level") and r1.get("masterypercent") == s1s["masterypercent"], (r1, s1s["band"], s1s["masterypercent"]))
    ok("computemastery topic row (blank section) = engine", rt.get("band") == t["band"] and rt.get("masterypercent") == t["masterypercent"] and not rt.get("componentsection"), rt)
    ov = call(admin, "GET", "/services/testu/analytics/overview.json")
    ok("analytics overview ok on stored bands", ov[0] == 200 and isinstance(ov[1], dict) and "levels" in ov[1], str(ov)[:300])

    # person.json: required topics vs role requirement + lowestrequiredtopic risk signal (overall mastery untouched)
    must("topicrequirement r1", call(admin, "PUT", "/services/lists/data/topicrequirement/lcheck-r1.json", body={"id": "lcheck-r1", "jobrole": "lcheck", "entitytopic": T, "requiredlevel": "expert"}))
    pr = call(admin, "GET", f"/services/testu/analytics/person.json?user={quote(USER)}")
    if pr[0] == 200:
        risk = pr[1].get("risk", {})
        low = risk.get("lowestrequiredtopic") or {}
        t = topic_of(state(T), T)
        ok("person.json risk: lowestrequiredtopic = required topic with its band/requirement", low.get("id") == T and low.get("requiredlevel") == "expert" and low.get("band") == t["band"]
           and low.get("meetsrequirement") == t["meetsrequirement"] and risk.get("requiredgaps") == (0 if t["meetsrequirement"] else 1), risk)
        ok("person.json topics keep stored topic band (not replaced by risk)", all(x.get("level") in (None, "beginner", "competent", "expert") for x in pr[1].get("topics", [])), pr[1].get("topics"))
    else:
        ok("person.json reachable for the test user", False, pr)

    # check_mastery.py: v1 model vs stored rows (all users) and live state.json for the test user, with a topic override
    must("override", call(admin, "PUT", f"/services/lists/data/entitytopic/{quote(T)}.json", body={"id": T, "competentmin": "20", "expertmin": "40"}))
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
    must("recompute", call(admin, "GET", "/services/testu/analytics/recompute.json"))
    for _ in range(90):
        row = call(es, "GET", f"/tutormastery/{quote(USER + '_topic_' + T)}", base=ES)[1].get("_source", {})
        if row.get("computedat", "") >= stamp:
            break
        time.sleep(2)
    import subprocess
    env = dict(os.environ, EME_BASE=B, EME_CHECK_USER=USER, EME_CHECK_PASSWORD=PASSWORD)
    cm = subprocess.run([sys.executable, os.path.join(os.environ["TESTU_TOOLS"], "check_mastery.py")], env=env, capture_output=True, text=True)
    print("  check_mastery.py: " + " | ".join(cm.stdout.strip().splitlines()[-2:]))
    ok("check_mastery.py (weights, confidence x hint, bands, topic override, expert evidence) passes", cm.returncode == 0, cm.stdout[-1500:] + cm.stderr[-500:])
finally:
    restore_topic(T)
    restore_policy()
    call(admin, "GET", "/services/testu/analytics/recompute.json")  # stored bands back to org defaults (runs in background)
    delete_rows("topicrequirement", ["lcheck-r1", "lcheck-r2", "lcheck-r3"])
    wipe_user_rows()
    call(admin, "POST", "/services/authentication/usersave.json", form={"username": USER, "field": "jobrole", "jobrolevalue": ""})
    call(admin, "POST", "/services/testu/personas/disableuser.json", form={"userid": USER})
    call(es, "POST", "/_refresh", body={}, base=ES)
    left = sum(len(es_ids(tb, {"term": {"user": USER}})) for tb in USER_TABLES)
    print(f"cleanup: {left} test rows left, test user disabled")

print("PASS" if not FAILS else f"FAIL ({len(FAILS)}): " + "; ".join(FAILS))
sys.exit(1 if FAILS else 0)
PY
