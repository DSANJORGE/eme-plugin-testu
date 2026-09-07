#!/usr/bin/env python3
"""Seed data/lists/*.xml rows into a running eMe server via the generic create.json
upsert (see task-2 report, Fix round 1 / ruling R3). Idempotent: same id overwrites."""
import glob
import json
import os
import sys
import urllib.error
import urllib.request
from http.cookiejar import CookieJar
from urllib.parse import quote
from xml.etree import ElementTree as ET

BASE = os.environ.get("EME_BASE", "http://localhost:8080/site/mediadb")
USER = os.environ.get("EME_USER", "admin")
PASSWORD = os.environ.get("EME_PASSWORD", "admin")
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LISTS_DIR = os.path.join(REPO_ROOT, "data", "lists")

opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))


def post(url, data, content_type):
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", content_type)
    try:
        with opener.open(req, timeout=10) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


status, body = post(f"{BASE}/services/authentication/login.json",
                     f"id={quote(USER)}&password={quote(PASSWORD)}".encode(),
                     "application/x-www-form-urlencoded")
if status // 100 != 2 or json.loads(body)["response"]["status"] != "ok":
    sys.exit(f"login failed: {status} {body!r}")

# ponytail: whole-file replay every run, no diffing against existing docs; fine for a handful of seed rows.
files = sorted(glob.glob(os.path.join(LISTS_DIR, "*.xml")) + glob.glob(os.path.join(LISTS_DIR, "*", "*.xml")))
failed = False
for path in files:
    rel = os.path.relpath(path, LISTS_DIR).split(os.sep)
    searchtype = os.path.splitext(rel[0])[0] if len(rel) == 1 else rel[0]
    for row in ET.parse(path).getroot():
        rowid = row.get("id")
        fields = {k: v for k, v in row.attrib.items() if k != "id"}
        fields.update({child.tag: (child.text or "") for child in row})
        url = f"{BASE}/services/module/{searchtype}/create.json?id={quote(rowid)}"
        status, body = post(url, json.dumps(fields).encode(), "application/json")
        if status == 404:  # no services/module/<type>/ route (plain picklists like masterylevel/suitesurface)
            print(f"{searchtype}/{rowid} skip (no module route)")
            continue
        print(f"{searchtype}/{rowid} {status}")
        if status // 100 != 2:
            failed = True

sys.exit(1 if failed else 0)
