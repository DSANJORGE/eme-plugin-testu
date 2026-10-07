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


def setting(sid, value):
    """Catalog setting value (None deletes the row). Returns the previous value. Copied from check_learning.sh."""
    prev = (es_doc("catalogsettings", sid) or {}).get("value")
    if value is None:
        call(admin, "DELETE", f"/services/lists/data/catalogsettings/{quote(sid)}.json")
    else:
        must(f"setting {sid}", call(admin, "PUT", f"/services/lists/data/catalogsettings/{quote(sid)}.json", body={"id": sid, "name": sid, "value": value}))
    return prev


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


def coach(op):
    # coach.json is TTL-cached per scope (final review I4) and rows put straight into ES bypass its invalidation; any
    # coachaction write clears it, so dismiss a throwaway key first (cleanup() deletes the admin's dismissals).
    call(admin, "POST", "/services/testu/analytics/coachaction.json", form={"action": "dismiss", "key": "check:cachebust:all"})
    return call(op, "GET", "/services/testu/analytics/coach.json")


def audits_all():
    """Every auditevent since AUDIT_SINCE (any action/target). Polls like audits(), for a set-membership check."""
    rows = []
    for attempt in range(24):
        refresh()
        st, res = call(es, "POST", "/auditevent/_search", body={"size": 200, "sort": [{"datecreated": "desc"}],
            "query": {"range": {"datecreated": {"gte": AUDIT_SINCE[0]}}}}, base=ES)
        rows = [h["_source"] for h in res["hits"]["hits"]] if st == 200 else []
        if {"coach.nudge", "learnertarget.set", "coach.dismiss"} <= {r.get("action") for r in rows}:
            break
        import time
        time.sleep(0.5)
    return rows


UID = "mission.check@testu.local"
PASSWORD = "Mc9-" + secrets.token_urlsafe(24)
UID2 = "mission.check2@testu.local"
PASSWORD2 = "Mc9-" + secrets.token_urlsafe(24)
# A third learner, on its own jobrole (no withindays -- no learnertarget cascade), used only for the cert_expiring
# "already scheduled" exclusion case: its deadline must come purely from the certification row, not a competing target.
UID3 = "mission.check3@testu.local"
PASSWORD3 = "Mc9-" + secrets.token_urlsafe(24)
PROFILE = [None]
PROFILE2 = [None]
# The negative-scope manager (fix round 1, item 6): a one-off account, unlike UID/UID2/UID3 which are reused across runs --
# deleted outright in cleanup(), not just reset, since it exists only to probe an out-of-scope coachaction.
MGREMAIL = "mgr.check.mission@testu.local"
MGRPASS = "Mc9-" + secrets.token_urlsafe(24)
PREV_ONLY = [False]  # False = untouched (don't restore); None/str = the previous setting value to put back


def cleanup():
    if PROFILE[0]:
        delete_rows("topicrequirement", es_ids("topicrequirement", {"term": {"jobrole": PROFILE[0]}}))
        delete_rows("jobrole", [PROFILE[0]])
    delete_rows("learnertarget", es_ids("learnertarget", {"term": {"user": UID}}))
    delete_rows("learnernotification", es_ids("learnernotification", {"term": {"user": UID}}))
    delete_rows("missionpush", es_ids("missionpush", {"term": {"user": UID}}))
    delete_rows("missionpush", es_ids("missionpush", {"term": {"user": UID2}}))
    usersave("lastmissionstatus", "", UID)  # so a rerun sees announce on its first mission.json read again
    wipe_user_rows(UID)
    delete_rows("learnertarget", es_ids("learnertarget", {"term": {"user": UID2}}))
    delete_rows("learnernotification", es_ids("learnernotification", {"term": {"user": UID2}}))
    delete_rows("coachdismissal", es_ids("coachdismissal", {"term": {"user": os.environ["EME_USER"]}}))
    wipe_user_rows(UID2)
    if PROFILE2[0]:
        delete_rows("topicrequirement", es_ids("topicrequirement", {"term": {"jobrole": PROFILE2[0]}}))
        delete_rows("jobrole", [PROFILE2[0]])
    delete_rows("certification", es_ids("certification", {"term": {"user": UID3}}))
    delete_rows("learnertarget", es_ids("learnertarget", {"term": {"user": UID3}}))
    wipe_user_rows(UID3)
    delete_rows("learnernotification", es_ids("learnernotification", {"term": {"user": MGREMAIL}}))
    delete_rows("learnertarget", es_ids("learnertarget", {"term": {"user": MGREMAIL}}))
    delete_rows("coachdismissal", es_ids("coachdismissal", {"term": {"user": MGREMAIL}}))
    wipe_user_rows(MGREMAIL)
    call(admin, "POST", "/services/testu/personas/deleteuser.json", form={"userid": MGREMAIL})  # one-off account: delete, don't just reset
    if PREV_ONLY[0] is not False:  # nudges ran: restore the pre-existing testu_dailychallengeemail_only (mayReceive's allowlist)
        setting("testu_dailychallengeemail_only", PREV_ONLY[0])
        PREV_ONLY[0] = False
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

    # task 13: person.json mirrors that same target as duedate/duesource on the required topic (TestUAnalyticsModule.loadPerson).
    s, person = call(admin, "GET", f"/services/testu/analytics/person.json?user={UID}")
    rt = next((t for t in (person.get("risk") or {}).get("requiredtopics", []) if t["id"] == TOPIC), None)
    ok("person.json shows the profile target's due date", s == 200 and rt and rt.get("duedate") and rt.get("duesource") == "profile", rt)

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

    # --- tutor chat actions (task 8, spec 2026-10-05): the chat reply may carry up to two [[do ...]] lines, chosen by the LLM
    # from this learner's own mission actions (TestULearningModule.actionsFor) and written by the server, never by the LLM
    # itself. This live-LLM run is flaky by nature (model variance) -- the deterministic gate for the offered-id filter is
    # MissionPlanner.doLinesCheck in MissionPlannerCheck.java (check_learning.sh), asserted there with no LLM involved.
    # check_tutor.sh's OTP/Bearer login is broken locally for a throwaway *.testu.local account (memory: "Local learner login
    # OTP"), so this reuses the cookie-session login the rest of this script already uses (login(), not OTP) for the chat
    # call and for testu/tutor/history.json, which needs only a signed-in user, not a Bearer token (history.groovy).
    import re, time
    item0 = must("learn item for chat", call(me, "GET", "/services/testu/learn/next.json?mode=learn&topicid=" + quote(TOPIC)))["items"][0]
    s, ch = call(me, "POST", "/services/module/entitytutorial/tutorhistory.json?dataid=" + quote(item0["tutorialid"]))
    ids = [(ch.get(k) or {}).get("id", "") for k in ("activechannel", "currentchannel")] if s == 200 else []
    CH = next((i for i in ids if i and not i.startswith("$")), None)
    ok("tutor chat: channel available", bool(CH), ch)

    def ask_tutor(question):
        """Posts question on CH and polls testu/tutor/history.json for the tutor's reply; None if none arrived in 90s."""
        must("tutor chat post", call(me, "POST", "/services/module/entitytutorial/continue.json", form={
            "currentscenario": "chat_tutor", "functionname": "chat_tutor_usercomment", "context_tutorialid": item0["tutorialid"],
            "channel": CH, "context_query": question, "context_sectionid": item0["sectionid"], "context_componentid": item0["componentid"],
            "context_skiploader": "true"}))
        for _ in range(45):
            time.sleep(2)
            s, hist = call(me, "GET", "/services/testu/tutor/history.json?channel=" + quote(CH))
            turns = hist.get("turns", []) if s == 200 else []
            if len(turns) >= 2 and turns[-1]["from"] == "tutor" and turns[-2]["from"] == "user" and turns[-2]["text"] == question:
                return turns[-1]["text"]
        return None

    reply = ask_tutor("¿qué hago ahora?")
    ok("tutor chat: got a reply within 90 s (llamat down?)", reply is not None, reply)
    if reply is not None:
        ok("tutor chat: [[do ...]] present (the learner asked what to do next)", "[[do" in reply, reply)
        dolines = re.findall(r"\[\[do ([a-z_]+) topic=(\S+?)(?: mode=\S+)?(?: section=\S+)?\]\]", reply)
        ok("tutor chat: at most 2 [[do ...]] lines, allowed types only, topic = the goal topic",
           len(dolines) <= 2 and all(t[0] in ("start_session", "book_evaluation", "schedule_certification", "remind_later") and t[1] == TOPIC for t in dolines),
           (dolines, TOPIC, reply))
        ok("tutor chat: [[do ...]] lines (if any) come after the >> follow-ups", not dolines or reply.rfind(">>") < reply.rfind("[[do"), reply)

    # fix round 1, item 4: a negative case -- a greeting carries no [[do line (rule 12: never on small talk/closings).
    greet = ask_tutor("hola, gracias")
    ok("tutor chat: got a reply to the greeting within 90 s (llamat down?)", greet is not None, greet)
    if greet is not None:
        ok("tutor chat: no [[do ...]] on a greeting/thanks (small talk is never an action turn)", "[[do" not in greet, greet)

    # --- visibility agreement (fix round 2, item 3/N2): visibleTopicsFor (actionsFor/offer, the chat path -- no
    # WebPageRequest there, option (b)) should agree with topics.json (visibleTopics(inReq), the same rule state.json/
    # mission.json use) on what a learner may see. A real group/role-secured topic could not be set up live here:
    # entitytopic's securityenabled/viewgroups are recomputed from the category tree at index time
    # (ElasticAssetDataConnector.addSecurity / BaseElasticSearcher, securityfield="rootcategory") and are NOT settable
    # by a direct REST PUT on the entitytopic record -- confirmed by hand: PUT {"securityenabled": true} round-trips
    # back to false once the indexed doc is refreshed. Mutating the shared category tree to secure one for real was not
    # attempted (shared dev server, other sessions may read it). Falls back to the weaker but honest check available
    # without that: the topic the chat actually offered an action for must be one topics.json lists for this learner.
    s, tj = call(me, "GET", "/services/module/entitytopic/topics.json")
    visible_topics = {t["id"] for t in tj.get("topics", [])} if s == 200 else set()
    ok("visibility agreement: the goal topic (actionsFor/visibleTopicsFor's pick) is one topics.json lists for this learner",
       TOPIC in visible_topics, (TOPIC, visible_topics))

    # --- remind.json
    s, r = call(me, "POST", "/services/testu/learn/remind.json", form={"topic": TOPIC, "when": "tomorrow"})
    ok("remind ok", s == 200 and r.get("ok"), r)
    s, r = call(me, "POST", "/services/testu/learn/remind.json", form={"topic": TOPIC, "when": "2h"})
    ok("remind returns remindat", s == 200 and r.get("remindat"), r)
    refresh()
    pend = es_ids("learnernotification", {"bool": {"must": [{"term": {"user": UID}}, {"term": {"type": "mission"}}, {"exists": {"field": "remindat"}}], "must_not": [{"exists": {"field": "pushedat"}}]}})
    ok("second remind replaces the first", len(pend) == 1, pend)
    s, r = call(me, "POST", "/services/testu/learn/remind.json", form={"topic": TOPIC, "when": "someday"})
    ok("unknown when -> 400", s == 400, r)
    # amendment 2026-10-06: the pending reminder shows on mission.json and can be undone until it is pushed.
    s, m = call(me, "GET", "/services/testu/learn/mission.json")
    ok("mission.json exposes the pending reminder", m.get("reminder") and m["reminder"]["topic"] == TOPIC and m["reminder"]["remindat"], m.get("reminder"))
    s, r = call(me, "POST", "/services/testu/learn/remind.json", form={"when": "cancel"})
    ok("when=cancel deletes the pending reminder", s == 200 and r.get("ok") and r.get("cancelled") is True, r)
    ok("cancelled reminder row gone", es_doc("learnernotification", f"{UID}_mission_remind") is None)
    s, m = call(me, "GET", "/services/testu/learn/mission.json")
    ok("no reminder after cancel", m.get("reminder") is None, m.get("reminder"))
    s, r = call(me, "POST", "/services/testu/learn/remind.json", form={"when": "cancel"})
    ok("cancel with nothing pending: 200, cancelled false", s == 200 and r.get("cancelled") is False, r)

    # --- nudges
    # Nudges are gated by the existing Daily Challenge email mayReceive rule (no dedicated permission): master switch
    # testu_dailychallengeemail may be off in this shared dev environment, so allowlist UID via testu_dailychallengeemail_only,
    # same as a tester would before the org switch is on. Restored in cleanup().
    # Both UID and UID2 up front: getCatalogSettingValue caches this id for the rest of the JVM's life, so a second
    # setting() call later (for the coach section's UID2) would silently keep serving this first value.
    PREV_ONLY[0] = setting("testu_dailychallengeemail_only", f"{UID},{UID2}")
    put_row("learnernotification", f"{UID}_mission_remind", {"user": UID, "type": "mission", "entitytopic": TOPIC, "remindat": "2020-01-01T00:00:00Z", "read": False, "actor": "tutor"})
    refresh()
    s, r = call(admin, "GET", "/services/testu/learn/missionnudges.json")
    ok("nudges ran", s == 200 and r.get("ok"), r)
    refresh()
    ok("due reminder delivered (pushedat + text set)", es_doc("learnernotification", f"{UID}_mission_remind").get("pushedat") and es_doc("learnernotification", f"{UID}_mission_remind").get("text"))
    s, r2 = call(admin, "GET", "/services/testu/learn/missionnudges.json")
    ok("status push not repeated on a second run", r2.get("sent", 0) == 0, r2)
    ok("delivered reminder carries kind=remind", es_doc("learnernotification", f"{UID}_mission_remind").get("kind") == "remind")
    ok("delivered reminder logged in missionpush", any(i.startswith(f"{UID}_mission_remind_") for i in es_ids("missionpush", {"term": {"user": UID}})))
    # amendment 2026-10-06: 2 learning answers today on the goal topic -> week.todayanswers 2; the on-demand sweep (no clock gate)
    # pushes "Te faltan 3 preguntas…" once, and not again the same day.
    for i in range(2):
        put_row("tutoranswer", f"mcheck-a-{i}", {"user": UID, "entityquestion": item0["questionid"], "iscorrect": "true", "answerconfidence": "confident",
                "mode": "learn", "hintlevel": "0", "datecreated": iso(datetime.datetime.utcnow()), "componentsection": item0["sectionid"]})
    refresh()
    s, m = call(me, "GET", "/services/testu/learn/mission.json")
    ok("week.todayanswers counts today's learning answers", m["week"]["todayanswers"] == 2 and m["week"]["todaycounted"] is False, m.get("week"))
    call(admin, "GET", "/services/testu/learn/missionnudges.json")
    refresh()
    unf = [i for i in es_ids("learnernotification", {"term": {"user": UID}}) if "_mission_unfinished_" in i]
    ok("unfinished push: one row today", len(unf) == 1, unf)
    urow = es_doc("learnernotification", unf[0]) if unf else {}
    ok("unfinished row: kind + text", urow.get("kind") == "unfinished" and "Te faltan 3 preguntas" in (urow.get("text") or ""), urow)
    ok("unfinished push logged in missionpush", any(i.startswith(f"{UID}_mission_unfinished_") for i in es_ids("missionpush", {"term": {"user": UID}})))
    s, r3 = call(admin, "GET", "/services/testu/learn/missionnudges.json")
    refresh()
    ok("unfinished push not repeated the same day", len([i for i in es_ids("learnernotification", {"term": {"user": UID}}) if "_mission_unfinished_" in i]) == 1 and r3.get("sent", 0) == 0, r3)

    # --- coach (task 7, spec 2026-10-05): manager suggestions over the team's missions, and approve-to-act actions
    make_user(UID2, PASSWORD2, role="users")
    s, r = call(admin, "POST", "/services/testu/personas/setprofiles.json", form={"user": UID2, "primary": PROFILE[0], "extras": "[]"})
    ok("setprofiles UID2: 200", s == 200, r)
    put_row("learnertarget", f"{UID2}_{TOPIC}", {"user": UID2, "entitytopic": TOPIC, "duedate": "2026-01-01T00:00:00.000Z", "source": "manual"})
    refresh()
    learner2 = login(UID2, PASSWORD2)

    s, c = coach(admin)
    ok("coach ok", s == 200 and c.get("ok"), c)
    sug = next((x for x in c["suggestions"] if x["kind"] == "overdue" and x["topic"] == TOPIC), None)
    ok("overdue suggestion lists the learner", sug and any(u["id"] == UID2 for u in sug["users"]), c)

    s, r = call(admin, "POST", "/services/testu/analytics/coachaction.json", form={"action": "nudge", "topic": TOPIC, "users": json.dumps([UID2])})
    ok("nudge sent", r.get("done") == 1, r)
    refresh()
    ok("coach nudge carries kind=coach", (es_doc("learnernotification", f"{UID2}_coach_{TOPIC}") or {}).get("kind") == "coach")
    ok("coach nudge logged in missionpush", any(i.startswith(f"{UID2}_coach_{TOPIC}_") for i in es_ids("missionpush", {"term": {"user": UID2}})))
    s, r = call(admin, "POST", "/services/testu/analytics/coachaction.json", form={"action": "nudge", "topic": TOPIC, "users": json.dumps([UID2])})
    ok("second nudge within 3 days skipped", r.get("skipped") == 1, r)

    s, r = call(admin, "POST", "/services/testu/analytics/coachaction.json", form={"action": "setdue", "topic": TOPIC, "users": json.dumps([UID2]), "duedate": "2030-01-01"})
    refresh()
    ok("setdue writes a manual target", es_doc("learnertarget", f"{UID2}_{TOPIC}").get("source") == "manual", r)

    s, r = call(admin, "POST", "/services/testu/analytics/coachaction.json", form={"action": "dismiss", "key": sug["key"]})
    ok("dismiss ok", s == 200 and r.get("ok"), r)
    s, c = coach(admin)
    ok("dismissed suggestion hidden", all(x["key"] != sug["key"] for x in c["suggestions"]), c)

    s, r = call(learner2, "POST", "/services/testu/analytics/coachaction.json", form={"action": "dismiss", "key": "x"})
    ok("learner gets 403", s == 403, r)

    ok("coach writes audited", {"coach.nudge", "learnertarget.set", "coach.dismiss"} <= {a.get("action") for a in audits_all()})

    # --- cert_expiring must exclude an already-scheduled renewal (fix round 1, item 1). UID3 gets its own jobrole with no
    # withindays, so the topic's only deadline source is the certification row -- no competing learnertarget to confuse it.
    make_user(UID3, PASSWORD3, role="users")
    s, r = call(admin, "POST", "/services/testu/personas/saveprofile.json", form={"id": "", "name": "Mission cert check", "rows": json.dumps(
        [{"topic": TOPIC, "requiredlevel": "competent", "mandatory": True, "requiresprevious": False, "afterfinish": "keep",
          "validitymonths": 6, "passpercent": 50, "renewalwindowdays": 30}])})
    ok("saveprofile cert rule: 200", s == 200 and r["profile"]["rows"][0]["validitymonths"] == 6, r)
    PROFILE2[0] = r["profile"]["id"]
    s, r = call(admin, "POST", "/services/testu/personas/setprofiles.json", form={"user": UID3, "primary": PROFILE2[0], "extras": "[]"})
    ok("setprofiles UID3: 200", s == 200, r)
    # Passed ~170 days ago, validitymonths 6 (~182d): expiry ~12 days out, inside the 30-day renewal window -- renewal_due and
    # cert_expiring-eligible (0-14 days) while unscheduled.
    put_row("certification", f"{UID3}_{TOPIC}", {"user": UID3, "entitytopic": TOPIC, "passedat": iso(NOW - datetime.timedelta(days=170))})
    refresh()
    s, c = coach(admin)
    ok("coach ok (cert case)", s == 200 and c.get("ok"), c)
    certsug = next((x for x in c["suggestions"] if x["kind"] == "cert_expiring" and x["topic"] == TOPIC), None)
    ok("unscheduled renewal surfaces as cert_expiring", certsug and any(u["id"] == UID3 for u in certsug["users"]), c)

    future = (NOW + datetime.timedelta(days=30)).strftime("%Y-%m-%d")
    put_row("certification", f"{UID3}_{TOPIC}", {"user": UID3, "entitytopic": TOPIC, "passedat": iso(NOW - datetime.timedelta(days=170)), "scheduledfor": future})
    refresh()
    s, c = coach(admin)
    certsug2 = next((x for x in c["suggestions"] if x["kind"] == "cert_expiring" and x["topic"] == TOPIC), None)
    ok("scheduled renewal excluded from cert_expiring", not (certsug2 and any(u["id"] == UID3 for u in certsug2["users"])), c)

    # --- negative scope test (fix round 1, item 6): a manager scoped to someone else's team gets 403 on coachaction for a
    # user outside that scope, and coach.json for that manager never lists them either.
    # The "manager" role (team-scoped: no personas_manage/operate or analytics_manage/operate) has no training_manage in this
    # seed, so it can never reach coachAction's per-user scope check otherwise (canManageProgression fails first). Grant it
    # training_manage for just this block and restore the role's permissions list exactly after -- via a raw ES partial
    # update (_update merges one field; the app's generic lists/data PUT re-renders "permissions" into display objects and
    # drops anything it doesn't recognize, so it's not safe for a round-trip here). A fresh login (not an already-open
    # session) is required to pick up the change, same as check_setrole.sh found for role-permission edits.
    s, teams = call(admin, "GET", "/services/testu/personas/teams.json")
    team_list = teams.get("teams", []) if s == 200 else []
    parents = {t.get("parent") for t in team_list}
    leaf = next((t for t in team_list if t["id"] not in parents), None)
    if leaf is None:
        print("SKIP: no teams locally for the coach out-of-scope test")
    else:
        MGRTEAM = leaf["id"]
        orig_mgr = leaf.get("manager") or ""

        def saveteam(mgr):
            must("saveteam", call(admin, "POST", "/services/testu/personas/saveteam.json", form={
                "id": MGRTEAM, "name": leaf.get("name") or "", "parent": leaf.get("parent") or "",
                "location": leaf.get("location") or "", "costcenter": leaf.get("costcenter") or "", "manager": mgr}))

        role_doc = es_doc("settingsrole", "manager") or {}
        orig_role_perms = list(role_doc.get("permissions") or [])

        def role_perms(perms):
            call(es, "POST", "/settingsrole/manager/_update", body={"doc": {"permissions": perms}}, base=ES)
            refresh()

        # fix round 2, item 5: if this run is killed between here and the role_perms(orig_role_perms) restore in finally
        # below, the "manager" role is left with training_manage granted org-wide. To check/fix by hand afterward:
        #   curl -s localhost:9200/site_catalog/settingsrole/manager | python3 -c \
        #     'import json,sys;print("training_manage" in json.load(sys.stdin)["_source"]["permissions"])'
        # and if that prints True, remove it with the same _update call role_perms() below makes, passing the role's
        # current permissions list minus "training_manage".
        try:
            make_user(MGREMAIL, MGRPASS, role="manager")
            saveteam(MGREMAIL)
            # final review I1: without training_manage a coach reader may dismiss (a view preference) but never write.
            reader = login(MGREMAIL, MGRPASS)
            s, r = call(reader, "POST", "/services/testu/analytics/coachaction.json", form={"action": "nudge", "topic": TOPIC, "users": json.dumps([UID2])})
            ok("manager without training_manage: nudge -> 403", s == 403, r)
            if any(x in orig_role_perms for x in ("resumen_admin", "actividad_admin", "dominio_admin", "prevision_admin")):
                s, r = call(reader, "POST", "/services/testu/analytics/coachaction.json", form={"action": "dismiss", "key": "check:readerdismiss:all"})
                ok("manager without training_manage: dismiss -> 200", s == 200 and r.get("ok"), r)
            else:
                print("SKIP: manager role has no analytics tab permission locally (dismiss-with-read case)")
            role_perms(orig_role_perms + ["training_manage"])
            manager = login(MGREMAIL, MGRPASS)

            # UID2's team is None, which is never in a specific-team manager's scope.
            s, r = call(manager, "POST", "/services/testu/analytics/coachaction.json", form={"action": "setdue", "topic": TOPIC, "users": json.dumps([UID2]), "duedate": "2030-01-01"})
            ok("out-of-scope manager setdue -> 403", s == 403, r)

            s, c = coach(manager)
            ok("out-of-scope manager coach ok", s == 200 and c.get("ok"), c)
            ok("out-of-scope manager never sees UID2/UID3", all(UID2 not in [u["id"] for u in x["users"]] and UID3 not in [u["id"] for u in x["users"]] for x in c["suggestions"]), c)
        finally:
            saveteam(orig_mgr)
            role_perms(orig_role_perms)
finally:
    cleanup()

if FAILS:
    print(f"\n{len(FAILS)} FAILED: {FAILS}")
    sys.exit(1)
print("\nall ok")
PY
