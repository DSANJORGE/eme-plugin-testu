#!/bin/sh
# Analytics v1: the three read endpoints (overview, activity, person) and the manager scope.
set -eu
B=${EME_BASE:-http://localhost:8080/site/mediadb}; J=$(mktemp); M=$(mktemp); trap 'rm -f "$J" "$M"' EXIT
login() { curl -sf -c "$1" -o /dev/null "$B/services/authentication/login.json" -H 'Content-Type: application/json' -d "{\"id\":\"$2\",\"password\":\"$3\"}"; }
login "$J" admin admin
for e in overview activity; do curl -sf -b "$J" "$B/services/testu/analytics/$e.json?from=2026-08-08&to=2026-09-06" > "/tmp/$e.json"; done
curl -sf -b "$J" "$B/services/testu/analytics/person.json?user=diego" > /tmp/person.json
python3 - <<'PY'
import json
o = json.load(open('/tmp/overview.json')); a = json.load(open('/tmp/activity.json')); p = json.load(open('/tmp/person.json'))
assert o['ok'] and a['ok'] and p['ok']
assert sum(o['levels'].values()) == o['cohort']['total'], (o['levels'], o['cohort'])
assert len(o['series']) == 30, len(o['series'])
assert len(o['gaps']) <= 5 and p['rows']
for name, body in (('overview', o), ('activity', a), ('person', p)):
    assert '"query"' not in json.dumps(body), f'{name} leaks query text'
print('ok: shapes, one denominator, no question text; person rows', len(p['rows']))
PY
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$J" "$B/services/testu/analytics/person.json?user=nobody"); [ "$code" = 403 ] && echo "ok: out-of-scope person is 403" || { echo "FAIL: $code"; exit 1; }
# manager scope: one team, one manager, fresh login (profile cache is cleared by setrole; createuser sets the role at creation).
# A leaf team keeps the assertion exact -- scope.groovy also grants a manager every descendant team.
T=$(curl -sf -b "$J" "$B/services/testu/personas/teams.json" | python3 -c 'import sys,json
t = json.load(sys.stdin)["teams"]; parents = {x["parent"] for x in t}
leaf = [x for x in t if x["id"] not in parents]
print("%s\t%s" % (leaf[0]["id"], leaf[0]["name"] or "") if leaf else "")')
[ -n "$T" ] || { echo "SKIP: no teams locally"; exit 0; }
TEAM=$(printf '%s' "$T" | cut -f1); TEAMNAME=$(printf '%s' "$T" | cut -f2)
curl -sf -b "$J" -X POST "$B/services/testu/personas/createuser.json" -d email=mgr.check@testu.local -d firstName=Check -d lastName=Manager -d role=manager -d team="$TEAM" >/dev/null || true
curl -sf -b "$J" -X POST "$B/services/testu/personas/saveteam.json" -d id="$TEAM" -d name="$TEAMNAME" -d manager=mgr.check@testu.local >/dev/null
curl -sf -b "$J" -X POST "$B/services/authentication/usersave.json" -d username=mgr.check@testu.local -d field=password -d passwordvalue=Checkpass123 >/dev/null
login "$M" mgr.check@testu.local Checkpass123
curl -sf -b "$M" "$B/services/testu/analytics/overview.json" | python3 -c "import sys,json;o=json.load(sys.stdin);ids={t['id'] for t in o['teams']};assert ids <= {'$TEAM'}, ids;print('ok: manager sees only', ids)"
