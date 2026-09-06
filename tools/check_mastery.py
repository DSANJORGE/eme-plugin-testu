#!/usr/bin/env python3
"""Recomputes the app's mastery formula from raw tutoranswer rows (via eMe's generic list search, as admin)
and compares with what the server stored in tutormastery. Exit 1 on any mismatch."""
import json, os, sys, urllib.request, urllib.parse, http.cookiejar
B = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("EME_BASE", "http://localhost:8080/site/mediadb")
# eMe has no HTTP Basic: log in once, keep the session cookie (see tools/seed_lists.py).
# ponytail: renamed opener var (brief called it `http`, shadowing the http module we import above).
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
opener.open(f"{B}/services/authentication/login.json", urllib.parse.urlencode(
    {"id": os.environ.get("EME_USER", "admin"), "password": os.environ.get("EME_PASSWORD", "admin")}).encode()).read()

def search(t):  # GET: an empty-body POST 500s in eMe core
    return json.load(opener.open(f"{B}/services/lists/search/{t}/search.json?hitsperpage=5000"))["results"]

# ponytail: eMe's JSON search API serializes list-type fields ("user", "componentsection",
# "entityquestion", "level") as {"id":..,"name":..} objects, not plain strings. Verified against
# the local server (see task-4-report.md); unwrap to the id, brief assumed flat strings.
def fid(v):
    return v.get("id") if isinstance(v, dict) else v

answers = sorted(search("tutoranswer"), key=lambda a: a.get("datecreated", ""))
groups = {}
for a in answers:
    u, s = fid(a.get("user")), fid(a.get("componentsection"))
    if not u or not s: continue
    g = groups.setdefault(f"{u}_{s}", {"latest": {}})
    g["latest"][str(fid(a.get("entityquestion")))] = str(a.get("iscorrect")) == "true"
stored = {r["id"]: r for r in search("tutormastery")}
bad = 0
for k, g in groups.items():
    answered = len(g["latest"]); mastered = sum(g["latest"].values())
    share = mastered / answered if answered else 0
    level = None if not answered else "beginner" if share < 0.5 else "competent" if share < 0.9 else "expert"
    r = stored.get(k)
    got = None if r is None else (int(r.get("answered", 0)), int(r.get("mastered", 0)), fid(r.get("level")))
    if got != (answered, mastered, level):
        bad += 1; print("MISMATCH", k, "expected", (answered, mastered, level), "got", got)
print(f"{len(groups)} groups checked, {bad} mismatches, {len(stored) - len(groups)} extra stored rows")
sys.exit(1 if bad or len(stored) != len(groups) else 0)
