#!/bin/sh
# Internal (support) accounts: flagged users keep their rows but leave every analytics number.
# Flags the first non-admin learner in report.json, checks users.json / overview cohort / person.json, then unflags.
set -eu
set -a; . ~/.eme-local.env; set +a
B=${EME_BASE:-http://localhost:8080/site/mediadb}; J=$(mktemp); U=
cleanup() { [ -n "$U" ] && curl -sf -b "$J" -X POST "$B/services/testu/personas/setinternal.json" -d userid="$U" -d internal=false >/dev/null || true; rm -f "$J"; }
trap cleanup EXIT
curl -sf -c "$J" -o /dev/null "$B/services/authentication/login.json" -H 'Content-Type: application/json' -d "{\"id\":\"$EME_USER\",\"password\":\"$EME_PASSWORD\"}"
U=$(curl -sf -b "$J" "$B/services/testu/analytics/report.json" | python3 -c 'import sys,json;print(json.load(sys.stdin)["rows"][0]["user"])')
T0=$(curl -sf -b "$J" "$B/services/testu/analytics/overview.json" | python3 -c 'import sys,json;print(json.load(sys.stdin)["cohort"]["total"])')
curl -sf -b "$J" -X POST "$B/services/testu/personas/setinternal.json" -d userid="$U" -d internal=true >/dev/null
curl -sf -b "$J" "$B/services/testu/personas/users.json" | python3 -c "import sys,json;u=[x for x in json.load(sys.stdin)['users'] if x['id']=='$U'][0];assert u['internal'] is True,u;print('ok: users.json marks internal')"
curl -sf -b "$J" "$B/services/testu/analytics/overview.json" | python3 -c "import sys,json;c=json.load(sys.stdin)['cohort'];assert c['total']==$T0-1 and c['internal']>=1,c;print('ok: cohort total',c['total'],'internal',c['internal'])"
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$J" "$B/services/testu/analytics/person.json?user=$U"); [ "$code" = 403 ] && echo "ok: internal person is out of scope (403)" || { echo "FAIL: person.json $code"; exit 1; }
curl -sf -b "$J" "$B/services/testu/analytics/report.json" | python3 -c "import sys,json;assert not [r for r in json.load(sys.stdin)['rows'] if r['user']=='$U'];print('ok: report drops internal rows')"
