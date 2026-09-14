#!/usr/bin/env python3
"""Read-only content validation for the TestU learning engine. Reads Elasticsearch only (GET / _search); never writes.

Reports: questions in tutorial content the app cannot render (LearningEngine.contentProblem; next.json refuses them with
content_unavailable), draft/test topics, unpublished topics, topics without valid titles, placeholder questions, non-canonical
difficulty, topics without eligible questions, topics accidentally visible to learners. Exit 1 when anything is reported.

Usage: python3 tools/validate_content.py [--es http://host:9200/site_catalog]
       (ES alias of the catalog index; default $ES or http://localhost:9200/site_catalog)"""
import json, re, sys, urllib.request

ES = next((a.split("=", 1)[1] for a in sys.argv if a.startswith("--es=")), None)
if "--es" in sys.argv:
    ES = sys.argv[sys.argv.index("--es") + 1]
ES = (ES or __import__("os").environ.get("ES", "http://localhost:9200/site_catalog")).rstrip("/")
DRAFT = re.compile(r"\b(test|testing|prueba|draft|borrador|demo|dummy|copy|copia|tmp|temp|sample|ejemplo|xxx)\b", re.I)
# ponytail: TODO/TBD case-sensitive, Spanish "todo" (= all) is common in real questions
PLACEHOLDER = re.compile(r"(?i:lorem|ipsum|placeholder|xxx|pregunta de prueba|test question)|\bTODO\b|\bTBD\b|^\W*$")
STATUS_FIELDS = ("published", "publishstatus", "status", "entitystatus", "active", "archived", "enabled", "hidden")
CANONICAL = {"beginner", "competent", "expert"}


def get(path, body=None):
    req = urllib.request.Request(f"{ES}{path}", data=None if body is None else json.dumps(body).encode(), method="GET" if body is None else "POST",
                                 headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=60))


def rows(table):
    out, frm = [], 0
    while True:
        hits = get(f"/{table}/_search", {"from": frm, "size": 1000, "query": {"match_all": {}}})["hits"]["hits"]
        out += [dict(h["_source"], id=h["_id"]) for h in hits]
        if len(hits) < 1000:
            return out
        frm += 1000


def name_of(d):
    n = d.get("name")
    if isinstance(n, dict):
        n = n.get("es") or n.get("en") or next(iter(n.values()), "")
    return n or (d.get("name_int") or {}).get("es") or (d.get("name_int") or {}).get("en") or ""


issues = {}


def report(kind, what):
    issues.setdefault(kind, []).append(what)


mapping = next(iter(get("/_mapping").values()))["mappings"]
topic_props = mapping.get("entitytopic", {}).get("properties", {})
status_fields = [f for f in STATUS_FIELDS if f in topic_props]
topics = rows("entitytopic")
tutorials = rows("entitytutorial")
sections = [s for s in rows("componentsection") if s.get("playbackentitymoduleid") == "entitytutorial"]
contents = [c for c in rows("componentcontent") if c.get("componenttype") == "mcq" and c.get("questionid")]
questions = {q["id"]: q for q in rows("entityquestion")}

tut_topic = {t["id"]: t.get("entitytopic") for t in tutorials}
sec_topic = {s["id"]: tut_topic.get(s.get("playbackentityid")) for s in sections}
eligible = {}


def content_problem(q):
    """Same rule as LearningEngine.contentProblem."""
    blank = lambda v: not str(v or "").strip()
    if blank(q.get("question")):
        return "missing_question"
    for o in "abcd":
        if blank(q.get(f"option_{o}")):
            return f"missing_option_{o}"
    c = re.sub(r"^option_", "", str(q.get("correctoption") or "").strip().lower())
    return None if re.fullmatch("[a-f]", c) and not blank(q.get(f"option_{c}")) else "bad_correctoption"


for c in contents:
    q = questions.get(str(c["questionid"]))
    tid = sec_topic.get(str(c.get("componentsectionid")))
    if tid and q and str(q.get("evaluationreserved")).lower() != "true":
        eligible.setdefault(tid, set()).add(q["id"])
        if content_problem(q):
            report("unrenderable questions in tutorials (content_unavailable)", f"{q['id']}: {content_problem(q)} (component {c['id']}, section {c.get('componentsectionid')})")

for q in questions.values():
    text = str(q.get("question") or "").strip()
    opts = [q.get(f"option_{k}") for k in "abcdef" if q.get(f"option_{k}")]
    correct = str(q.get("correctoption") or "").strip().lower()
    problems = []
    if len(text) < 15 or PLACEHOLDER.search(text):
        problems.append("text")
    if len(opts) < 2 or any(PLACEHOLDER.search(str(o)) for o in opts):
        problems.append("options")
    if not correct or not q.get(f"option_{correct}"):
        problems.append("correctoption")
    if problems:
        report("placeholder questions", f"{q['id']}: {','.join(problems)} {text[:60]!r}")
    lvl = q.get("mcqcognitivelevel")
    if lvl and lvl not in CANONICAL:
        report("non-canonical difficulty (run tools/normalize_difficulty.py)", f"{q['id']}: {lvl!r}")

for t in topics:
    name = name_of(t).strip()
    label = f"{t['id']} ({name or 'no title'})"
    flags = []
    if DRAFT.search(name) or DRAFT.search(t.get("sourcepath") or ""):
        report("draft/test topics", label)
        flags.append("draft/test")
    for f in status_fields:
        v = str(t.get(f)).lower()
        if (f in ("archived", "hidden") and v == "true") or (f in ("published", "active", "enabled") and v == "false") or (f in ("status", "publishstatus", "entitystatus") and v not in ("published", "active", "none")):
            report("unpublished topics", f"{label}: {f}={t.get(f)}")
            flags.append("unpublished")
    if not name or name == t["id"] or re.fullmatch(r"[A-Za-z0-9_-]{16,}", name):
        report("topics without valid titles", label)
        flags.append("no title")
    has_tutorial = t["id"] in tut_topic.values()
    if has_tutorial and not eligible.get(t["id"]):
        report("topics without eligible questions", label)
        flags.append("no eligible questions")
    visible = str(t.get("securityenabled")).lower() != "true" or bool(t.get("viewroles") or t.get("viewgroups"))
    if has_tutorial and visible and flags:
        report("topics accidentally visible to learners", f"{label}: {', '.join(flags)}")

print(f"ES {ES}: {len(topics)} topics, {len(tutorials)} tutorials, {len(sections)} sections, {len(questions)} questions")
for t in topics:
    print(f"  topic {t['id']}: {name_of(t)!r}, eligible questions {len(eligible.get(t['id'], ()))}, securityenabled={t.get('securityenabled')}")
if not status_fields:
    print(f"note: entitytopic has no status/published field ({'/'.join(STATUS_FIELDS)}); unpublished cannot be detected, "
          "visibility is entity security only (securityenabled, viewusers/groups/roles)")
for kind in ("unrenderable questions in tutorials (content_unavailable)", "draft/test topics", "unpublished topics", "topics without valid titles", "placeholder questions",
             "non-canonical difficulty (run tools/normalize_difficulty.py)", "topics without eligible questions", "topics accidentally visible to learners"):
    found = issues.get(kind, [])
    print(f"{kind}: {len(found)}")
    for f in found[:20]:
        print(f"  - {f}")
    if len(found) > 20:
        print(f"  ... {len(found) - 20} more")
sys.exit(1 if issues else 0)
