#!/usr/bin/env python3
"""Rewrites entityquestion.mcqcognitivelevel from source labels (Baja/Media/Alta) to the canonical list ids
(beginner/competent/expert) the learning engine reads. Dry run by default; --apply writes through eMe's
lists/data API (only that one field). Idempotent.

Usage: python3 tools/normalize_difficulty.py [--apply]   (EME_BASE, EME_USER, EME_PASSWORD)"""
import json, os, sys, urllib.request
from http.cookiejar import CookieJar
from urllib.parse import quote

B = os.environ.get("EME_BASE", "http://localhost:8080/site/mediadb")
MAP = {"baja": "beginner", "media": "competent", "alta": "expert"}
op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))


def call(method, path, body=None):
    req = urllib.request.Request(B + path, data=None if body is None else json.dumps(body).encode(), method=method,
                                 headers={"Content-Type": "application/json"})
    return json.load(op.open(req, timeout=60))


if not os.environ.get("EME_USER") or not os.environ.get("EME_PASSWORD"):
    sys.exit("set EME_USER and EME_PASSWORD (an admin of the target server)")
call("POST", "/services/authentication/login.json", {"id": os.environ["EME_USER"], "password": os.environ["EME_PASSWORD"]})
rows = call("GET", "/services/lists/search/entityquestion/search.json?hitsperpage=10000")["results"]
todo = []
for q in rows:
    v = q.get("mcqcognitivelevel")
    v = v.get("id") if isinstance(v, dict) else v
    if v and v.strip().lower() in MAP:
        todo.append((q["id"], v, MAP[v.strip().lower()]))
print(f"{len(rows)} questions, {len(todo)} with source labels")
for qid, old, new in todo:
    if "--apply" in sys.argv:
        call("PUT", f"/services/lists/data/entityquestion/{quote(qid)}.json", {"id": qid, "mcqcognitivelevel": new})
print("applied" if "--apply" in sys.argv else "dry run (pass --apply to write)")
