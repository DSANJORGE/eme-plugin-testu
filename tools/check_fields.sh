#!/bin/sh
# Fields of Task 1 are known to the server: a search on each new searchtype answers (0 hits is fine, 404/500 is not).
set -eu
ES=${ES:-http://localhost:9200/site_catalog}
for t in tutordaily usageevent tutorquestion; do
  n=$(curl -sf -X POST "$ES/_search?size=0" -H 'Content-Type: application/json' -d "{\"query\":{\"term\":{\"_type\":\"$t\"}}}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["hits"]["total"])')
  echo "$t: $n rows"
done
B=${EME_BASE:-http://localhost:8080/site/mediadb}; J=$(mktemp); trap 'rm -f "$J"' EXIT
curl -sf -c "$J" -o /dev/null "$B/services/authentication/login.json" -H 'Content-Type: application/json' -d '{"id":"admin","password":"admin"}'
curl -sf -b "$J" "$B/services/testu/personas/me.json" | python3 -c 'import sys,json; d=json.load(sys.stdin); assert d["role"]=="orgadmin", d; print("admin is orgadmin, perms", len(d["permissions"]))'
