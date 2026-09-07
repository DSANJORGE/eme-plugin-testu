#!/bin/sh
# Analytics v1: calibration counters, tutordaily and tutorquestion rollups.
# Triggers the catalog event through recompute.json and asserts the result against Elasticsearch.
set -eu
B=${EME_BASE:-http://localhost:8080/site/mediadb}; ES=${ES:-http://localhost:9200/site_catalog}; J=$(mktemp)
# The injected rating event must go even when an assertion aborts the script under `set -eu`:
# its id is fixed, so a survivor would re-attach a rating and fail the negative block on the next run.
trap 'rm -f "$J"; curl -s -X DELETE "$ES/usageevent/chk-rating-pos?refresh=true" -o /dev/null || true' EXIT
curl -sf -c "$J" -o /dev/null "$B/services/authentication/login.json" -H 'Content-Type: application/json' -d '{"id":"admin","password":"admin"}'
# The endpoint returns immediately; the event runs async and its last write is tutordaily,
# so wait for a tutordaily row stamped after the trigger rather than guessing a sleep
# (one LLM classification round can hold the run for its whole 60 s budget).
recompute() {
  T0=$(python3 -c 'import time; print(int(time.time() * 1000))')
  curl -sf -b "$J" -X POST "$B/services/testu/analytics/recompute.json" >/dev/null
  python3 - "$ES" "$T0" <<'WAIT'
import sys, json, time, datetime, urllib.request
ES, t0 = sys.argv[1], int(sys.argv[2]) / 1000.0
deadline = time.time() + 240
while time.time() < deadline:
    r = urllib.request.Request(f"{ES}/_search?size=1", headers={"Content-Type": "application/json"},
        data=json.dumps({"query": {"term": {"_type": "tutordaily"}}, "sort": [{"computedat": "desc"}]}).encode())
    hits = json.load(urllib.request.urlopen(r))["hits"]["hits"]
    if hits and datetime.datetime.fromisoformat(hits[0]["_source"]["computedat"].replace("Z", "+00:00")).timestamp() >= t0 - 2:
        break
    time.sleep(3)
else:
    raise SystemExit("recompute did not produce a fresh tutordaily within 240 s")
WAIT
}
recompute
python3 - "$ES" <<'PY'
import sys, json, re, urllib.request, collections, datetime
ES = sys.argv[1]
def q(body, size=2000):
    r = urllib.request.Request(f"{ES}/_search?size={size}", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    return [h["_source"] | {"_id": h["_id"]} for h in json.load(urllib.request.urlopen(r))["hits"]["hits"]]
def rows(t, **kw):
    must = [{"term": {"_type": t}}] + [{"term": {k: v}} for k, v in kw.items()]
    return q({"query": {"bool": {"must": must}}})
# (a) the four calibration buckets partition every attempt
m = rows("tutormastery", user="diego"); assert m, "no tutormastery for diego"
for r in m:
    s = sum(int(r.get(k) or 0) for k in ("certaincorrect", "certainwrong", "unsurecorrect", "unsurewrong"))
    assert s == int(r.get("attempts") or 0), (r["_id"], s, r.get("attempts"))
# (b) tutordaily answers per server-timezone day match the raw tutoranswer rows
ans = rows("tutoranswer", user="diego")
per = collections.Counter(datetime.datetime.fromisoformat(a["datecreated"].replace("Z", "+00:00")).astimezone().strftime("%Y%m%d") for a in ans if a.get("datecreated"))
d = {r["_id"]: r for r in rows("tutordaily", user="diego")}
for day, n in per.items():
    assert f"diego_{day}" in d, (day, sorted(d))
    assert int(d[f"diego_{day}"]["answers"]) == n, (day, n, d[f"diego_{day}"])
# (c) every learner chatterbox question that carries a query became a tutorquestion
chat = rows("chatterbox", functionname="chat_tutor_usercomment")
asks = [c for c in chat if c.get("user") != "agent"]
tq = {r["_id"]: r for r in rows("tutorquestion")}
missing = [a["_id"] for a in asks if a["_id"] not in tq and json.loads(a.get("agentcontextvalues") or "{}").get("query")]
assert not missing, missing
# (d) the iris_rate check_track.sh posted on channel c1 has no question to attach to
assert not [r for r in tq.values() if r.get("channel") == "c1"], "unexpected tutorquestion on channel c1"
# (e) a reply that ends in a citation marks its question cited
pat = re.compile(r"\[[^\]\n]+,\s*(p\.\s*\d+|\d+:\d\d)\]\s*(\[\[hl[^\]]*\]\])?\s*$")
replies = {c["replytoid"]: c for c in chat if c.get("user") == "agent" and c.get("replytoid")}
want = {i for i, r in replies.items() if i in tq and pat.search(r.get("message") or "")}
got = {i for i, r in tq.items() if str(r.get("cited")).lower() == "true"}
assert want <= got, sorted(want - got)
assert not want or got, "no cited tutorquestion although a reply ends with a citation"
print(f"ok: counters sum to attempts on {len(m)} rows; tutordaily matches {len(per)} days; {len(tq)} tutorquestions; cited={len(got)}")
PY

# The rating join requires the same user. track.json always stores user = the session user (admin here),
# so an admin iris_rate on a diego question's channel must NOT attach.
T=$(python3 - "$ES" <<'PY'
import sys, json, urllib.request, datetime
ES = sys.argv[1]
def q(body, size=2000):
    r = urllib.request.Request(f"{ES}/_search?size={size}", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    return [h["_source"] | {"_id": h["_id"]} for h in json.load(urllib.request.urlopen(r))["hits"]["hits"]]
def rows(t, **kw):
    return q({"query": {"bool": {"must": [{"term": {"_type": t}}] + [{"term": {k: v}} for k, v in kw.items()]}}})
chat = rows("chatterbox", functionname="chat_tutor_usercomment")
replies = {c["replytoid"]: c for c in chat if c.get("user") == "agent" and c.get("replytoid")}
tq = [r for r in rows("tutorquestion", user="diego") if r["_id"] in replies and replies[r["_id"]].get("date")]
assert tq, "no answered tutorquestion for diego"
row = max(tq, key=lambda r: r["datecreated"])
at = datetime.datetime.fromisoformat(replies[row["_id"]]["date"].replace("Z", "+00:00")) + datetime.timedelta(minutes=2)
print(row["_id"], row["channel"], at.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
PY
)
QID=$(echo "$T" | cut -d' ' -f1); CH=$(echo "$T" | cut -d' ' -f2); AT=$(echo "$T" | cut -d' ' -f3)
curl -sf -b "$J" -X POST "$B/services/testu/usage/track.json" \
  --data-urlencode "events=[{\"type\":\"iris_rate\",\"sessionid\":\"rollupchk\",\"at\":\"$AT\",\"channel\":\"$CH\",\"rating\":\"helpful\"}]" >/dev/null
recompute
python3 - "$ES" "$QID" <<'PY'
import sys, json, urllib.request
ES, qid = sys.argv[1], sys.argv[2]
r = urllib.request.Request(f"{ES}/_search?size=1", data=json.dumps({"query": {"bool": {"must": [{"term": {"_type": "tutorquestion"}}, {"term": {"_id": qid}}]}}}).encode(), headers={"Content-Type": "application/json"})
hits = json.load(urllib.request.urlopen(r))["hits"]["hits"]
assert hits, f"tutorquestion {qid} vanished"
assert not hits[0]["_source"].get("rating"), ("admin rating attached to a diego question", hits[0]["_source"])
print("ok: rating join requires the same user")
PY

# Positive path: the same learner, the same channel, inside the 10-minute window. track.json cannot
# produce this (it always stamps the session user), so the usageevent goes straight into the index.
curl -sf -X PUT "$ES/usageevent/chk-rating-pos?refresh=true" -H 'Content-Type: application/json' -o /dev/null \
  -d "{\"id\":\"chk-rating-pos\",\"user\":\"diego\",\"type\":\"iris_rate\",\"rating\":\"helpful\",\"channel\":\"$CH\",\"datecreated\":\"${AT%Z}.000Z\",\"sessionid\":\"chk-pos\",\"seconds\":0}"
recompute
python3 - "$ES" "$QID" <<'PY'
import sys, json, urllib.request
ES, qid = sys.argv[1], sys.argv[2]
r = urllib.request.Request(f"{ES}/_search?size=1", headers={"Content-Type": "application/json"},
    data=json.dumps({"query": {"bool": {"must": [{"term": {"_type": "tutorquestion"}}, {"term": {"_id": qid}}]}}}).encode())
hits = json.load(urllib.request.urlopen(r))["hits"]["hits"]
assert hits, f"tutorquestion {qid} vanished"
assert hits[0]["_source"].get("rating") == "helpful", ("rating did not attach", hits[0]["_source"])
PY
echo "ok: rating join attaches helpful on same user + channel within 10 min"
