#!/usr/bin/env python3
"""Learning engine v1 mastery cross-check (spec docs/superpowers/specs/2026-09-14-learning-engine.md, "Mastery").
Recomputes, from raw content + tutoranswer rows (eMe list search, as admin), what the computemastery event must have
stored in tutormastery, and compares every row:
  - weights beginner 1 / competent 2 / expert 4; per-question evidence = latest attempt in ANY mode
  - score = confidence weight (confident 1, mostlysure .85, notsure .6, noidea .4) x hint factor (1, .75, .5, .25)
  - masterypercent = round(100 x sum(w x score) / sum(w)); band from masterylevel.minpercent, topic override when > 0
  - level = band; section rows (<user>_<section>) and topic rows (<user>_topic_<topic>, blank section); legacy counters
Expert evidence and live band/percent are checked against state.json when EME_CHECK_USER / EME_CHECK_PASSWORD are set.
Users with an answer newer than their rows' computedat (live activity) are skipped, not failed. Read-only. Exit 1 on mismatch.
Usage: EME_USER=... EME_PASSWORD=... python3 tools/check_mastery.py [BASE]   (no default credentials)"""
import json, math, os, sys, urllib.request, http.cookiejar
from urllib.parse import quote

B = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("EME_BASE", "http://localhost:8080/site/mediadb")
CONF = {"confident": 1.0, "mostlysure": 0.85, "notsure": 0.6, "noidea": 0.4}
WEIGHT = {"beginner": 1, "competent": 2, "expert": 4}


def login(user, password):
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    req = urllib.request.Request(f"{B}/services/authentication/login.json", json.dumps({"id": user, "password": password}).encode(),
                                 headers={"Content-Type": "application/json"})
    op.open(req).read()
    return op


if not os.environ.get("EME_USER") or not os.environ.get("EME_PASSWORD"):
    sys.exit("set EME_USER and EME_PASSWORD (an admin of the target server)")
admin = login(os.environ["EME_USER"], os.environ["EME_PASSWORD"])


def fid(v):
    return v.get("id") if isinstance(v, dict) else v


def search(t):
    out, page = [], 1
    while True:
        d = json.load(admin.open(f"{B}/services/lists/search/{t}/search.json?hitsperpage=1000&page={page}"))
        out += d["results"]
        if page >= int(d["response"].get("pages") or 1):
            return out
        page += 1


def num(v, default=0):
    try:
        return float(fid(v))
    except (TypeError, ValueError):
        return default


def rnd(x):  # Java Math.round
    return math.floor(x + 0.5)


# ---- content (order only matters for duplicates; mastery is over sets)
levels = {r["id"]: r for r in search("masterylevel")}
ORG = (int(num(levels.get("competent", {}).get("minpercent"), 60)) or 60, int(num(levels.get("expert", {}).get("minpercent"), 85)) or 85)
topics = {t["id"]: t for t in search("entitytopic")}
tut_topic = {t["id"]: fid(t.get("entitytopic")) for t in search("entitytutorial") if fid(t.get("entitytopic")) in topics}
sec_topic = {s["id"]: tut_topic[fid(s.get("playbackentityid"))] for s in search("componentsection")
             if fid(s.get("playbackentitymoduleid")) == "entitytutorial" and fid(s.get("playbackentityid")) in tut_topic}
questions = {q["id"]: q for q in search("entityquestion")}
sec_qs, topic_qs, seen = {}, {}, set()
for c in sorted(search("componentcontent"), key=lambda c: num(c.get("ordering"))):
    s, q = fid(c.get("componentsectionid")), fid(c.get("questionid"))
    if fid(c.get("componenttype")) != "mcq" or not q or s not in sec_topic or q not in questions or q in seen:
        continue
    if str(questions[q].get("evaluationreserved")) == "true":
        continue
    seen.add(q)
    sec_qs.setdefault(s, []).append(q)
    topic_qs.setdefault(sec_topic[s], []).append(q)
DIFF = {q: (fid(questions[q].get("mcqcognitivelevel")) if fid(questions[q].get("mcqcognitivelevel")) in WEIGHT else "beginner") for q in seen}


def thresholds(tid):
    t = topics[tid]
    c, e = int(num(t.get("competentmin"))), int(num(t.get("expertmin")))
    return (c if c > 0 else ORG[0], e if e > 0 else ORG[1])


def score(a):
    if not a or not a["correct"]:
        return 0.0
    return CONF.get(a["conf"], 1.0) * (1 - 0.25 * max(0, min(3, a["hint"])))


def pct(qs, latest):
    w = sum(WEIGHT[DIFF[q]] for q in qs)
    return 0 if w == 0 else rnd(100 * sum(WEIGHT[DIFF[q]] * score(latest.get(q)) for q in qs) / w)


def mastery(qs, latest, th):
    p = pct(qs, latest)
    band = None if not any(q in latest for q in qs) else "expert" if p >= th[1] else "competent" if p >= th[0] else "beginner"
    ex = [q for q in qs if DIFF[q] == "expert"]
    if not ex:
        ev = {"ok": False, "reason": "no_expert_questions"}
    else:
        good = pct(ex, latest) >= th[1] and sum(1 for q in ex if latest.get(q, {}).get("correct")) >= min(3, len(ex))
        ev = {"ok": True} if good else {"ok": False, "reason": "insufficient"}
    return p, band, ev


# ---- attempts
by_user = {}
for a in search("tutoranswer"):
    u, q = fid(a.get("user")), fid(a.get("entityquestion"))
    if u and q:
        by_user.setdefault(u, []).append({"q": q, "at": a.get("datecreated", ""), "correct": str(a.get("iscorrect")) == "true",
                                          "conf": fid(a.get("answerconfidence")), "hint": int(num(a.get("hintlevel")))})
for u in by_user:
    by_user[u].sort(key=lambda a: a["at"])


def expected_rows(u):
    atts = by_user[u]
    latest = {a["q"]: a for a in atts}
    rows = {}
    for scope_id, qs, tid, is_topic in [(s, qs, sec_topic[s], False) for s, qs in sec_qs.items()] + [(t, qs, t, True) for t, qs in topic_qs.items()]:
        mine = [a for a in atts if a["q"] in set(qs)]
        if not mine:
            continue
        p, band, _ = mastery(qs, latest, thresholds(tid))
        rid = f"{u}_topic_{tid}" if is_topic else f"{u}_{scope_id}"
        rows[rid] = {"masterypercent": p, "band": band, "level": band, "questions": len(qs), "attempts": len(mine),
                     "correct": sum(a["correct"] for a in mine), "answered": sum(1 for q in qs if q in latest),
                     "mastered": sum(1 for q in qs if latest.get(q, {}).get("correct")), "componentsection": None if is_topic else scope_id}
    return rows


stored = {r["id"]: r for r in search("tutormastery")}
bad = skipped = checked = 0
expected_ids = set()
for u in by_user:
    exp = expected_rows(u)
    mine = [r for r in stored.values() if fid(r.get("user")) == u]
    computed = min((r.get("computedat", "") for r in mine), default="")
    if mine and by_user[u][-1]["at"] > computed:
        skipped += 1
        expected_ids |= {r["id"] for r in mine}
        continue
    expected_ids |= set(exp)
    for rid, e in exp.items():
        r = stored.get(rid)
        checked += 1
        got = None if r is None else {k: (fid(r.get(k)) if k in ("band", "level", "componentsection") else int(num(r.get(k))) if r.get(k) is not None else None) for k in e}
        if got is not None and not got.get("componentsection"):
            got["componentsection"] = None
        if got != e:
            bad += 1
            print("MISMATCH", rid, "expected", e, "got", got)
extra = set(stored) - expected_ids
for rid in sorted(extra)[:10]:
    print("EXTRA stored row", rid)
print(f"stored rows: {checked} checked, {bad} mismatches, {len(extra)} extra, {skipped} users skipped (activity after last compute)")

# ---- live: expert evidence, band, percent against state.json for one learner
cu, cp = os.environ.get("EME_CHECK_USER"), os.environ.get("EME_CHECK_PASSWORD")
live_bad = 0
if cu and cp:
    me = login(cu, cp)
    st = json.load(me.open(f"{B}/services/testu/learn/state.json"))
    latest = {a["q"]: a for a in by_user.get(cu, [])}
    for t in st["topics"]:
        scopes = [(t, topic_qs.get(t["id"], []))] + [(s, sec_qs.get(s["id"], [])) for s in t["sections"]]
        for obj, qs in scopes:
            p, band, ev = mastery(qs, latest, thresholds(t["id"]))
            got = (obj["masterypercent"], obj["band"], obj["expertevidence"])
            if got != (p, band, ev):
                live_bad += 1
                print("LIVE MISMATCH", cu, obj["id"], "expected", (p, band, ev), "got", got)
    print(f"live state.json for {cu}: {live_bad} mismatches")
else:
    print("live state.json check skipped (set EME_CHECK_USER / EME_CHECK_PASSWORD)")
sys.exit(1 if bad or extra or live_bad else 0)
