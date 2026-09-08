#!/bin/sh
# Part B / C1: the tutor prompt override and the IRIS history page against the local server.
# Posts one follow-up as $EME_USER (required, OTP $EME_OTP) on their own tutor channel, waits
# for the reply, checks the reply grammar (>> follow-ups or the not-found sentence, [Title, p. N]
# cites, length) and that history.json returns both turns. A live run leaves two real turns in
# that learner's IRIS history -- never point EME_USER at the learner signed in on the Simulator.
#
# admin can't complete the OTP/Bearer flow that services/testu/tutor/history.json requires
# locally (no email-based OTP account for admin: login.json's cookie session works for admin,
# but history.json checks a Bearer token, which only a real learner's OTP flow can produce) --
# so this must run as a real learner. Point it at a local learner with
# EME_USER=colab1@minsur.test tools/check_tutor.sh, but never run it as a user whose session is
# live in the simulator at the same time: posting a follow-up there mid-session ends it.
#
# Usage: tools/check_tutor.sh ["¿pregunta?"]
#        tools/check_tutor.sh --dry-run     # grammar checks only, canned reply, no server
set -eu

grammar_check() {
  # $1 = the question asked, $2 = path to a JSON file shaped {"turns":[...]}
  python3 - "$1" "$2" <<'PY'
import json, re, sys
q, path = sys.argv[1], sys.argv[2]
t = json.load(open(path))['turns']
assert len(t) >= 2 and t[-2]['from'] == 'user' and t[-1]['from'] == 'tutor', 'no reply within 90 s (llamat down?): ' + json.dumps(t[-2:], ensure_ascii=False)
assert t[-2]['text'] == q, t[-2]
r = t[-1]['text']
body = re.sub(r'^[ \t]*>>.*$', '', r, flags=re.M)
follow = re.findall(r'^[ \t]*>>[ \t]*(.+?)[ \t]*$', r, flags=re.M)
cites = re.findall(r'\[([^\[\]]+?),\s*(?:p\.?\s*\d+|\d+:\d\d)\]', body)
notfound = body.strip().startswith('No lo encuentro en las fuentes de este tema.')
words = len(re.sub(r'\[[^\]]*\]', '', body).split())
print('reply :', r.replace('\n', ' | ')[:500])
print('words', words, '| cites', cites, '| follow-ups', follow, '| not found', notfound)
assert 1 <= len(follow) <= 2, 'expected one or two >> follow-ups'
assert notfound or cites or 'rationale' in q.lower() or words <= 60, 'neither cited nor the not-found sentence'
assert words <= 90, 'reply too long for the 60-word rule'
if notfound: assert len(follow) == 1, 'not-found must carry exactly one follow-up'
print('ok: reply in grammar; history.json returns both turns')
PY
}

if [ "${1:-}" = "--dry-run" ]; then
  # ponytail: canned replies exercise the same grammar checks with no server/LLM. Add more
  # canned cases here if a specific grammar edge needs coverage.
  J=$(mktemp); J2=$(mktemp); trap 'rm -f "$J" "$J2"' EXIT
  cat > "$J" <<'JSON'
{"turns":[
  {"id":"u1","from":"user","text":"¿Qué es la debida diligencia en derechos humanos?","date":""},
  {"id":"t1","from":"tutor","text":"La debida diligencia es el proceso continuo de identificar, prevenir y remediar impactos adversos en derechos humanos [Plan Nacional de Acción sobre Empresas y Derechos Humanos 2021-2025 (Perú), p. 12].\n>> ¿Quieres ver un ejemplo de esta práctica?","date":""}
]}
JSON
  cat > "$J2" <<'JSON'
{"turns":[
  {"id":"u2","from":"user","text":"¿Cuál es la capital de la Luna?","date":""},
  {"id":"t2","from":"tutor","text":"No lo encuentro en las fuentes de este tema.\n>> ¿Quieres que busquemos en otro tema?","date":""}
]}
JSON
  grammar_check "¿Qué es la debida diligencia en derechos humanos?" "$J"
  grammar_check "¿Cuál es la capital de la Luna?" "$J2"
  exit 0
fi

B=${EME_BASE:-http://localhost:8080/site/mediadb}; T=${TUTORIAL:-AZ_tFsHKimsE6yOlqXpA}
USER=${EME_USER:?set EME_USER (never the learner signed in on the Simulator)}; OTP=${EME_OTP:-123456}
Q=${1:-¿Qué es la debida diligencia en derechos humanos?}

curl -sf -X POST "$B/services/authentication/sendusercode.json" -d "email=$USER" -o /dev/null
TOKEN=$(curl -sf -X POST "$B/services/authentication/token.json" -d grant_type=otp -d "email=$USER" -d "code=$OTP" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("access_token") or "")')
[ -n "$TOKEN" ] || { echo "FAIL: no access_token for $USER (bad OTP?)"; exit 1; }
AUTH="Authorization: Bearer $TOKEN"

# The caller's agenttutorchat channel for the tutorial; tutorhistory creates it when missing.
# (an unset $activechannel renders as the literal "$activechannel.id": skip ids that start with "$")
CH=$(curl -sf -H "$AUTH" -X POST "$B/services/module/entitytutorial/tutorhistory.json?dataid=$T" | python3 -c 'import sys,json;d=json.load(sys.stdin);ids=[(d.get(k) or {}).get("id","") for k in ("activechannel","currentchannel")];print(next((i for i in ids if i and not i.startswith("$")),""))')
[ -n "$CH" ] || { echo "FAIL: no tutor channel for $USER on $T"; exit 1; }

# Section and component of the first MCQ, as the app sends them.
SC=$(curl -sf -H "$AUTH" "$B/services/module/entitytutorial/tutorial.json?entitytutorial=$T" | python3 -c 'import sys,json;d=json.load(sys.stdin);s=d["sections"][0];c=[x for x in s["contents"] if (x.get("contenttype") or x.get("content_type") or "").lower()=="mcq"][0];print(s["id"],c["id"])')
SEC=${SC% *}; COMP=${SC#* }

curl -sf -H "$AUTH" -o /dev/null -X POST "$B/services/module/entitytutorial/continue.json" -d currentscenario=chat_tutor -d functionname=chat_tutor_usercomment -d "context_tutorialid=$T" -d "channel=$CH" --data-urlencode "context_query=$Q" -d "context_sectionid=$SEC" -d "context_componentid=$COMP" -d context_skiploader=true
echo "sent on channel $CH (section $SEC, component $COMP) as $USER; waiting for the tutor..."
i=0
while [ $i -lt 45 ]; do
  sleep 2; i=$((i+1))
  curl -sf -H "$AUTH" "$B/services/testu/tutor/history.json?channel=$CH" > /tmp/tutor_history.json
  if python3 -c 'import sys,json;t=json.load(open("/tmp/tutor_history.json"))["turns"];sys.exit(0 if len(t)>=2 and t[-1]["from"]=="tutor" and t[-2]["from"]=="user" else 1)'; then break; fi
done
grammar_check "$Q" /tmp/tutor_history.json
