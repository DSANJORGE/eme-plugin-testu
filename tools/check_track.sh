#!/bin/sh
set -eu
B=${EME_BASE:-http://localhost:8080/site/mediadb}; ES=${ES:-http://localhost:9200/site_catalog}; J=$(mktemp); trap 'rm -f "$J"' EXIT
curl -sf -c "$J" -o /dev/null "$B/services/authentication/login.json" -H 'Content-Type: application/json' -d '{"id":"admin","password":"admin"}'
S="chk-$(date +%s)"
EV="[{\"type\":\"open\",\"sessionid\":\"$S\",\"at\":\"2026-09-06T10:00:00Z\",\"user\":\"someoneelse\",\"platform\":\"test\",\"appversion\":\"1.1.1+6\"},{\"type\":\"pause\",\"sessionid\":\"$S\",\"seconds\":95,\"at\":\"2026-09-06T10:01:35Z\"},{\"type\":\"iris_rate\",\"sessionid\":\"$S\",\"at\":\"2026-09-06T10:01:00Z\",\"channel\":\"c1\",\"componentsection\":\"s1\",\"rating\":\"helpful\"}]"
post() { curl -sf -b "$J" -X POST "$B/services/testu/usage/track.json" --data-urlencode "events=$EV" | python3 -c 'import sys,json;print(json.load(sys.stdin)["saved"])'; }
a=$(post); b=$(post)
sleep 2
# sessionid is analyzed text (term and sessionid.keyword both miss 0 hits); match_phrase isolates the exact session.
u=$(curl -sf -X POST "$ES/_search?size=1" -H 'Content-Type: application/json' -d "{\"query\":{\"match_phrase\":{\"sessionid\":\"$S\"}}}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["hits"]["hits"][0]["_source"]["user"])')
[ "$a" = 3 ] && [ "$b" = 0 ] && [ "$u" = admin ] && echo "ok: saved 3 then 0, user from session" || { echo "FAIL: saved $a/$b user $u"; exit 1; }
