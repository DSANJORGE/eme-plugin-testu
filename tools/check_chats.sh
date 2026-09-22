#!/bin/sh
# Tutor chat history against the local server: chat.json create/rename/hide/unhide, chats.json list
# and search (accent-insensitive, multi-word, reply-only match), 403 on someone else's chat, and
# sessions never resolving to a free chat. Leaves one hidden chat with one real exchange in the
# learner's history. Never point EME_USER at the learner signed in on the Simulator/browser pane.
# Usage: EME_USER=demo.30@testu.local tools/check_chats.sh
set -eu
B=${EME_BASE:-http://localhost:8080/site/mediadb}; T=${TUTORIAL:-AZ_tFsHKimsE6yOlqXpA}
USER=${EME_USER:-demo.30@testu.local}; OTHER=${EME_OTHER:-demo.31@testu.local}

token() {  # $1 email -> access token (OTP read from local ES, single use)
  curl -sf -X POST "$B/services/authentication/sendusercode.json" -d "email=$1" -o /dev/null
  code=$(curl -sf "http://localhost:9200/system-0/_search?q=email:$1%20AND%20_type:templogincode&size=1&sort=date:desc" | python3 -c 'import sys,json;print(json.load(sys.stdin)["hits"]["hits"][0]["_source"]["securitycode"])')
  curl -sf -X POST "$B/services/authentication/token.json" -d grant_type=otp -d "email=$1" -d "code=$code" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])'
}
A="Authorization: Bearer $(token "$USER")"
O="Authorization: Bearer $(token "$OTHER")"
j() { python3 -c "import sys,json;d=json.load(sys.stdin);$1"; }

CH=$(curl -sf -H "$A" -X POST "$B/services/testu/tutor/chat.json" -d action=create -d "tutorialid=$T" --data-urlencode "title=Energía de prueba" -d docid=DOC1 --data-urlencode "doctitle=RITRA" -d docpage=42 | j 'assert d["ok"];print(d["chat"]["id"])')
echo "created $CH"

# One real exchange on it, as the app sends it (first section with an MCQ, and that MCQ's component).
SC=$(curl -sf -H "$A" "$B/services/module/entitytutorial/tutorial.json?entitytutorial=$T" | j '
s=next(sec for sec in d["sections"] if any((x.get("contenttype") or x.get("content_type") or "").lower()=="mcq" for x in sec["contents"]))
c=next(x for x in s["contents"] if (x.get("contenttype") or x.get("content_type") or "").lower()=="mcq")
print(s["id"],c["id"])')
curl -sf -H "$A" -o /dev/null -X POST "$B/services/module/entitytutorial/continue.json" -d currentscenario=chat_tutor -d functionname=chat_tutor_usercomment -d "context_tutorialid=$T" -d "channel=$CH" --data-urlencode "context_query=¿Qué es el candado personal de bloqueo?" -d "context_sectionid=${SC% *}" -d "context_componentid=${SC#* }" -d context_skiploader=true

# Poll up to ~20s for the system row to land (only the learner row matters here; no tutor reply needed).
i=0
while [ $i -lt 10 ]; do
  curl -sf -H "$A" "$B/services/testu/tutor/chats.json" -o /tmp/chats_poll.json
  if python3 -c "import sys,json;d=json.load(open('/tmp/chats_poll.json'));sys.exit(0 if any(x['id']=='$CH' for x in d['chats']) else 1)"; then break; fi
  i=$((i+1)); sleep 2
done

curl -sf -H "$A" "$B/services/testu/tutor/chats.json" | j "c=[x for x in d['chats'] if x['id']=='$CH'];assert c, 'new chat not listed';c=c[0];assert c['docTitle']=='RITRA' and str(c['docPage'])=='42' and c['at'];print('ok: listed',c['title'])"
curl -sf -H "$A" -G "$B/services/testu/tutor/chats.json" --data-urlencode "q=energia" | j "assert any(x['id']=='$CH' for x in d['chats']);print('ok: accent-insensitive title match')"
curl -sf -H "$A" -G "$B/services/testu/tutor/chats.json" --data-urlencode "q=CANDADO bloqueo" | j "c=[x for x in d['chats'] if x['id']=='$CH'];assert c and 'candado' in c[0]['snippet'].lower();print('ok: multi-word message match:',c[0]['snippet'])"
curl -sf -H "$A" -G "$B/services/testu/tutor/chats.json" --data-urlencode "q=zzqxnomatch" | j "assert not any(x['id']=='$CH' for x in d['chats']);print('ok: no false match')"

curl -sf -H "$A" -X POST "$B/services/testu/tutor/chat.json" -d action=rename -d "channel=$CH" --data-urlencode "title=Bloqueo y etiquetado" | j 'assert d["ok"]'
curl -sf -H "$A" "$B/services/testu/tutor/chats.json" | j "assert [x for x in d['chats'] if x['id']=='$CH'][0]['title']=='Bloqueo y etiquetado';print('ok: renamed')"

st=$(curl -s -o /dev/null -w '%{http_code}' -H "$O" -X POST "$B/services/testu/tutor/chat.json" -d action=hide -d "channel=$CH")
[ "$st" = 403 ] || { echo "FAIL: other user got $st hiding $CH"; exit 1; }; echo "ok: 403 for another learner"

# Sessions never land on the free chat.
curl -sf -H "$A" -X POST "$B/services/module/entitytutorial/tutorhistory.json?dataid=$T" | j "ids=[(d.get(k) or {}).get('id') for k in ('activechannel','currentchannel')]+[c.get('id') for c in d.get('channelhistory') or []];assert '$CH' not in ids, ids;print('ok: tutorhistory skips the free chat')"
# tutorsession.json doesn't exist on this server (nor anywhere in the repo) -- dailychallenge.json
# is the other real endpoint on the same ChatModule.findExistingChannel(agenttutorchat) path
# (searchtype tutordailychallenge instead of entitytutorial), so it covers the same concern.
curl -sf -H "$A" "$B/services/module/entitytutorial/dailychallenge.json?dataid=$T" | j "assert '$CH' not in json.dumps(d);print('ok: dailychallenge skips the free chat')"

curl -sf -H "$A" -X POST "$B/services/testu/tutor/chat.json" -d action=hide -d "channel=$CH" | j 'assert d["ok"]'
curl -sf -H "$A" "$B/services/testu/tutor/chats.json" | j "assert not any(x['id']=='$CH' for x in d['chats']);print('ok: hidden')"
curl -sf -H "$A" -X POST "$B/services/testu/tutor/chat.json" -d action=unhide -d "channel=$CH" | j 'assert d["ok"]'
curl -sf -H "$A" "$B/services/testu/tutor/chats.json" | j "assert any(x['id']=='$CH' for x in d['chats']);print('ok: unhidden')"
curl -sf -H "$A" -X POST "$B/services/testu/tutor/chat.json" -d action=hide -d "channel=$CH" -o /dev/null
echo "ALL OK"
