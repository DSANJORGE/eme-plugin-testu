#!/bin/sh
# Analytics v1: the three read endpoints (overview, activity, person) and the manager scope.
set -eu
B=${EME_BASE:-http://localhost:8080/site/mediadb}; J=$(mktemp); M=$(mktemp)
# saveteam.groovy writes every field from the request, so the manager-scope block would otherwise
# null the chosen team's parent/location/costcenter and drop its real manager. Save and restore.
TEAM=; TEAMNAME=; TEAMPARENT=; TEAMLOC=; TEAMCC=; TEAMMGR=
saveteam() { curl -sf -b "$J" -X POST "$B/services/testu/personas/saveteam.json" -d id="$TEAM" -d name="$TEAMNAME" -d parent="$TEAMPARENT" -d location="$TEAMLOC" -d costcenter="$TEAMCC" -d manager="$1" >/dev/null; }
cleanup() { if [ -n "$TEAM" ]; then saveteam "$TEAMMGR" || echo "WARN: could not restore team $TEAM"; fi; rm -f "$J" "$M"; }
trap 'rc=$?; cleanup; [ "$rc" -eq 0 ] || echo "FAIL (exit $rc): a request failed -- is the local admin in orgadmin (Task 0 of the plan) and Tomcat up?" >&2; exit $rc' EXIT
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
c = o['cohort']
assert c['total'] >= c['activated'] >= c['active30d'] >= c['active7d'], c
f = a['funnel']
assert f['cohort'] >= f['answered'] >= f['active30d'] >= f['active7d'], f
for name, body in (('overview', o), ('activity', a), ('person', p)):
    assert '"query"' not in json.dumps(body), f'{name} leaks query text'
print('ok: shapes, one denominator, monotonic funnel, no question text; person rows', len(p['rows']))
PY
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$J" "$B/services/testu/analytics/person.json?user=nobody"); [ "$code" = 403 ] && echo "ok: out-of-scope person is 403" || { echo "FAIL: $code"; exit 1; }
# manager scope: one team, one manager, fresh login (profile cache is cleared by setrole; createuser sets the role at creation).
# A leaf team keeps the assertion exact -- scope.groovy also grants a manager every descendant team.
T=$(curl -sf -b "$J" "$B/services/testu/personas/teams.json" | python3 -c 'import sys,json
t = json.load(sys.stdin)["teams"]; parents = {x["parent"] for x in t}
leaf = [x for x in t if x["id"] not in parents]
print("\t".join(leaf[0][k] or "" for k in ("id", "name", "parent", "location", "costcenter", "manager")) if leaf else "")')
[ -n "$T" ] || { echo "SKIP: no teams locally"; exit 0; }
TEAM=$(printf '%s' "$T" | cut -f1); TEAMNAME=$(printf '%s' "$T" | cut -f2); TEAMPARENT=$(printf '%s' "$T" | cut -f3)
TEAMLOC=$(printf '%s' "$T" | cut -f4); TEAMCC=$(printf '%s' "$T" | cut -f5); TEAMMGR=$(printf '%s' "$T" | cut -f6)
DIEGOTEAM=$(python3 -c 'import json;print(json.load(open("/tmp/person.json"))["user"]["team"] or "")')
curl -sf -b "$J" -X POST "$B/services/testu/personas/createuser.json" -d email=mgr.check@testu.local -d firstName=Check -d lastName=Manager -d role=manager -d team="$TEAM" >/dev/null || true
saveteam mgr.check@testu.local
curl -sf -b "$J" -X POST "$B/services/authentication/usersave.json" -d username=mgr.check@testu.local -d field=password -d passwordvalue=Checkpass123 >/dev/null
login "$M" mgr.check@testu.local Checkpass123
curl -sf -b "$M" "$B/services/testu/analytics/overview.json" | python3 -c "import sys,json;o=json.load(sys.stdin);ids={t['id'] for t in o['teams']};assert ids <= {'$TEAM'}, ids;print('ok: manager sees only', ids)"
want=403; [ "$DIEGOTEAM" = "$TEAM" ] && want=200
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$M" "$B/services/testu/analytics/person.json?user=diego")
[ "$code" = "$want" ] && echo "ok: manager person scope (diego team '$DIEGOTEAM' vs '$TEAM' -> $code)" || { echo "FAIL: manager person.json?user=diego -> $code, want $want"; exit 1; }
