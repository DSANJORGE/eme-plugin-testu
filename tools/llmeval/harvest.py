#!/usr/bin/env python3
"""Build golden rows from real tutor conversations stored in the local catalog.

Rebuilds the variables AdaptiveTutorialUserCommentSkill hands to chat_tutor_usercomment
(learnerprompt, chathistory, referenceexcerpts, mode) for given chatterbox message ids,
reading Elasticsearch directly, and prints one golden row per message.

Usage: tools/llmeval/harvest.py <chatterbox-id>... >> tools/llmeval/golden.jsonl
       tools/llmeval/harvest.py --list        # real learner messages to pick from
  ES (default http://localhost:9200), ES_INDEX (default site_catalog1789325035891)

ponytail: mirrors the skill's composition (lesson components, question block, past answers,
mastery, recent turns, keyword excerpts) closely enough for evaluation; not byte-identical.
Mastery is today's value, not the one at the time of the message.
"""
import json, os, re, sys, urllib.request

ES = os.environ.get('ES', 'http://localhost:9200') + '/' + os.environ.get('ES_INDEX', 'site_catalog1789325035891')
RUBRIC = ('Eres el evaluador de IRIS, tutora corporativa de Minsur. La réplica debe estar en español, tutear, sonar a '
          'colega sénior, citar solo con [Título, p. N] copiado del extracto, o empezar exactamente con "No lo encuentro '
          'en las fuentes de este tema." cuando ni los extractos ni la pregunta en juego lo cubren. Para pedidos de pista: '
          'orientar sin revelar la opción correcta ni su texto; si la revela, correctness = 1. Cerrar con una o dos líneas '
          '>> con preguntas de seguimiento respondibles desde el material.')
NOT_FOUND = 'No lo encuentro en las fuentes de este tema.'


def search(table, body):
    req = urllib.request.Request(f"{ES}/{table}/_search", data=json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
    return [h['_source'] | {'_id': h['_id']} for h in json.load(urllib.request.urlopen(req))['hits']['hits']]


def get(table, id):
    try:
        return json.load(urllib.request.urlopen(f"{ES}/{table}/{id}")).get('_source')
    except Exception:
        return None


def term(field, value):
    return {'term': {field: value}}


def ctx(row):
    try:
        return json.loads(row.get('agentcontextvalues') or '{}') or {}
    except Exception:
        return {}


def lesson(sectionid):
    """AdaptiveTutorialBaseSkill.getReleaventChatHistory: headings, paragraphs, first explanation after each mcq."""
    out, after_mcq = [], -1
    for c in search('componentcontent', {'size': 500, 'sort': ['ordering'], 'query': term('componentsectionid', sectionid)}):
        t = c.get('componenttype')
        if t == 'heading':
            after_mcq = -1
        elif t in ('mcq', 'asset'):
            after_mcq = 0 if t == 'mcq' else after_mcq
            continue
        elif after_mcq >= 0:
            after_mcq += 1
            if after_mcq > 1:
                continue
        content = c.get('content') or ''
        if not content:
            continue
        if after_mcq > 0 and len(content) > 300:
            cut = content.rfind('. ', 0, 300)
            content = content[:cut + 1] if cut > 120 else content[:content.rfind(' ', 0, 300)] + '…'
        out.append({'role': 'assistant', 'content': content})
    return out


def question_block(q):
    if not q:
        return ''
    s = f"Current question: {q.get('question')}\n"
    for k in ('option_a', 'option_b', 'option_c', 'option_d', 'option_e', 'option_f'):
        if q.get(k):
            s += f"{k[7:].upper()}: {q[k]}\n"
    s += f"Correct option: {q.get('correctoption')}\n"
    if q.get('rationale'):
        s += f"Rationale: {q['rationale']}\n"
    if q.get('sourcecite'):
        page = '' if q.get('sourcepage') in (None, '', '0') else f", p. {q['sourcepage']}"
        s += f"Source of the question [{q['sourcecite']}{page}]: {q.get('sourcequote') or ''}\n"
    return s


def answer_history(user, questionid, tutorialid, sectionid, before):
    out = ''
    if questionid:
        past = search('tutoranswer', {'size': 50, 'sort': [{'datecreated': 'desc'}], 'query': {'bool': {'must': [
            term('user', user), term('entityquestion', questionid), {'range': {'datecreated': {'lt': before}}}]}}})
        if past:
            items = '; '.join(f"{a.get('selectedoption')} ({a.get('answerconfidence')}, {'correct' if str(a.get('iscorrect')).lower() == 'true' else 'incorrect'})" for a in past)
            correct = sum(1 for a in past if str(a.get('iscorrect')).lower() == 'true')
            out += f"Past answers of the learner on this question, most recent first: {items}. Total {len(past)} attempts, {correct} correct.\n"
    section = get('componentsection', sectionid) if sectionid else None
    out += mastery(f"current subtopic '{section['name']}'" if section else 'current subtopic', get('tutormastery', f"{user}_{sectionid}"))
    tutorial = get('entitytutorial', tutorialid) if tutorialid else None
    topicid = tutorial and tutorial.get('entitytopic')
    if topicid:
        topic = get('entitytopic', topicid)
        out += mastery(f"topic '{topic['name']}'" if topic else 'topic', get('tutormastery', f"{user}_topic_{topicid}"))
    return out


def mastery(scope, row):
    if not row:
        return ''
    return (f"Mastery of the learner in the {scope}: {row.get('masterypercent')}% (band {row.get('band')}), {row.get('answered')} of "
            f"{row.get('questions')} questions answered, {row.get('certainwrong')} wrong answers given with confidence, "
            f"{row.get('unsurecorrect')} right answers given unsure.\n")


def recent(channel, before, current):
    rows = search('chatterbox', {'size': 7, 'sort': [{'date': 'desc'}], 'query': {'bool': {'must': [
        term('channel', channel), {'terms': {'functionname': ['chat_tutor_usercomment', 'chat_tutor_answer']}}, {'range': {'date': {'lt': before}}}]}}})
    turns = []
    for r in rows:
        if r['_id'] == current:
            continue
        if r.get('user') == 'agent':
            text = 'Tutor: ' + str(r.get('message'))
        else:
            v = ctx(r)
            if not v:
                continue
            if v.get('query'):
                text = 'Learner: ' + v['query']
            elif v.get('selectedoption'):
                text = f"Learner answered {v['selectedoption']} ({v.get('confidence')})"
            else:
                continue
        if text.endswith('null') or text.endswith('No content available') or text.startswith('Tutor: No lo encuentro en las fuentes'):
            continue
        turns.insert(0, text[:400] + '…' if len(text) > 400 else text)
    return 'Recent conversation, oldest first:\n' + '\n'.join(turns) + '\n' if turns else ''


def excerpts(tutorialid, *queries):
    """AdaptiveTutorialUserCommentSkill.findReferenceExcerpts: all words of a query first, then any word."""
    termsets = []
    for q in queries:
        words = [w for w in re.split(r'[^\w]+', q or '', flags=re.U) if len(w) >= 4]
        if words:
            termsets.append(words)
    docs = search('entityasset', {'size': 50, 'query': term('entitytutorial', tutorialid)}) if tutorialid else []
    if not termsets or not docs:
        return ''
    ids = [d['_id'] for d in docs]
    pages = []
    for op in (' AND ', ' OR '):
        for words in termsets:
            body = {'size': 3, 'query': {'bool': {'must': [{'terms': {'entityasset': ids}}, {'query_string': {'default_field': 'description', 'query': op.join(words)}}]}}}
            pages = search('entityassetpage', body)
            if pages:
                break
        if pages:
            break
    out = ''
    for p in pages:
        text = p.get('markdowncontent') or ''
        if not text:
            continue
        doc = next((d for d in docs if d['_id'] == p.get('entityasset')), None)
        out += f"[{doc['name'] if doc else 'Reference document'}, p. {p.get('pagenum')}]\n{text[:4000]}\n\n"
    return out


def build(msgid):
    m = get('chatterbox', msgid)
    if not m:
        return None
    v = ctx(m)
    user, channel, when = m.get('user'), m.get('channel'), m.get('date')
    tutorialid, sectionid, questionid = v.get('tutorialid'), v.get('sectionid'), v.get('questionid')
    text = (v.get('query') or m.get('message') or '').strip()
    q = get('entityquestion', questionid) if questionid else None
    prompt = text
    if v.get('selectedoption'):
        prompt = f"Answer of the learner: {v['selectedoption']}\nConfidence: {v.get('confidence')}\nMessage of the learner: {text}"
    history = answer_history(user, questionid, tutorialid, sectionid, when)
    if history:
        prompt = history + '\n' + prompt
    qtext = f"{q.get('question')} {q.get('option_' + str(q.get('correctoption')).lower(), '')}" if q else None
    refs = excerpts(tutorialid, text, qtext)
    qb = question_block(q)
    if qb:
        prompt = qb + '\n' + prompt
    if v.get('mode'):
        prompt = f"Mode: {v['mode']}\n" + prompt
    rc = recent(channel, when, msgid)
    if rc:
        prompt = rc + '\n' + prompt
    expected = {'followups_min': 1, 'followups_max': 2, 'max_words': 90}
    if q and re.search(r'\bpista\b', text, re.I):
        expected['must_not_say'] = [q.get('option_' + str(q.get('correctoption')).lower(), ''), NOT_FOUND[:15]]
    return {
        'id': 'real-' + msgid, 'function': 'chat_tutor_usercomment',
        'question': text, 'reference': (qb + '\n' + refs).strip(), 'rubric': RUBRIC,
        'input': {'learnerprompt': prompt, 'chathistory': lesson(sectionid) if sectionid else [], 'referenceexcerpts': refs, 'mode': v.get('mode') or 'learn'},
        'expected': expected,
        'source': {'user': user, 'date': when, 'tutorial': tutorialid, 'section': sectionid, 'question': questionid},
    }


if __name__ == '__main__':
    args = sys.argv[1:]
    if args[:1] == ['--list']:
        rows = search('chatterbox', {'size': 500, 'sort': [{'date': 'desc'}], 'query': {'bool': {'must': [term('functionname', 'chat_tutor_usercomment')], 'must_not': [term('user', 'agent'), {'prefix': {'user': 'demo.'}}]}}})
        for r in rows:
            v = ctx(r)
            print(r['_id'], r.get('user'), v.get('mode'), v.get('questionid'), v.get('selectedoption'), '|', (v.get('query') or r.get('message') or '')[:100])
        sys.exit(0)
    for msgid in args:
        row = build(msgid)
        if row:
            print(json.dumps(row, ensure_ascii=False))
        else:
            print('no such message: ' + msgid, file=sys.stderr)
