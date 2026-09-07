#!/bin/sh
# Regression check for setrole.json: the new role must be visible to the target
# user on their next login without a Tomcat restart (profile cache bug, 06-09).
# Usage: EME_BASE=http://localhost:8080/site/mediadb EME_USER=admin EME_PASSWORD=admin \
#        TARGET=diego TARGET_PASSWORD=Testpass123 ./tools/check_setrole.sh
# Leaves the target with its original role. Logging in as TARGET ends any open session it has.
set -eu
B=${EME_BASE:-http://localhost:8080/site/mediadb}
A=$(mktemp); T=$(mktemp); trap 'rm -f "$A" "$T"' EXIT
login() { curl -sf -c "$1" -o /dev/null "$B/services/authentication/login.json" -H 'Content-Type: application/json' -d "{\"id\":\"$2\",\"password\":\"$3\"}"; }
role() { rm -f "$T"; login "$T" "$TARGET" "$TARGET_PASSWORD"; curl -sf -b "$T" "$B/services/testu/personas/me.json" | python3 -c 'import sys,json; print(json.load(sys.stdin)["role"])'; }
setrole() { curl -sf -b "$A" -X POST "$B/services/testu/personas/setrole.json" -d "userid=$TARGET&role=$1" >/dev/null; }

login "$A" "${EME_USER:-admin}" "${EME_PASSWORD:-admin}"
orig=$(role)
new=$([ "$orig" = orgadmin ] && echo manager || echo orgadmin)
setrole "$new"; seen=$(role)
setrole "$orig"; back=$(role)
[ "$seen" = "$new" ] && [ "$back" = "$orig" ] && echo "ok: $orig -> $new -> $orig visible without restart" \
  || { echo "FAIL: set $new, next login saw '$seen'; restored $orig, saw '$back'"; exit 1; }
