#!/bin/sh
# Analytics v1: the IRIS analyst endpoint -- fact sheet shape, then a grounded ask with verified citations.
set -eu
B=${EME_BASE:-http://localhost:8080/site/mediadb}; J=$(mktemp); trap 'rm -f "$J"' EXIT
curl -sf -c "$J" -o /dev/null "$B/services/authentication/login.json" -H 'Content-Type: application/json' -d '{"id":"admin","password":"admin"}'
curl -sf -b "$J" -X POST "$B/services/testu/analytics/ask.json" -d debug=facts -d screen=overview > /tmp/facts.json
curl -sf -b "$J" "$B/services/testu/analytics/overview.json" > /tmp/overview.json
python3 -c "import json;f=json.load(open('/tmp/facts.json'))['facts'];assert len(f)>=10 and all(k in f[0] for k in ('id','label','value','view'));assert all(x['filters'].keys()>={'from','to'} and 'period' not in x['filters'] for x in f);assert '\"query\"' not in json.dumps(f);print('ok: facts',len(f),'window',f[0]['filters']['from'],f[0]['filters']['to'])"
code=$(curl -s -o /tmp/ask1.json -w '%{http_code}' -b "$J" -X POST "$B/services/testu/analytics/ask.json" --data-urlencode 'question=¿Cuántas personas activas hay esta semana?' -d screen=overview)
if [ "$code" = 503 ]; then echo "SKIP llm: ask returned 503 (llamat down); facts and shapes verified"; exit 0; fi
curl -s -o /tmp/ask2.json -b "$J" -X POST "$B/services/testu/analytics/ask.json" --data-urlencode 'question=¿Cuánto cobra Luis al mes?' -d screen=overview
python3 - <<'PY'
import json, re
o = json.load(open('/tmp/overview.json')); a1 = json.load(open('/tmp/ask1.json')); a2 = json.load(open('/tmp/ask2.json'))
assert a1['ok'], a1
ids = {c['id'] for c in a1['citations']}
assert all(m in ids for m in re.findall(r'\[(f\d+)\]', a1['answer'])), (a1['answer'], ids)
assert any(c['value'] == o['cohort']['active7d'] for c in a1['citations']), a1['citations']
assert a2['ok'] and not a2['citations'] and not re.search(r'\d', a2['answer']), a2
print('ok: cited active7d; off-data question answered without figures')
PY
