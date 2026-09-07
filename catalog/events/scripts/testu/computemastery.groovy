import org.entermediadb.asset.MediaArchive
import org.openedit.Data
import org.openedit.hittracker.HitTracker

// Same rule as app-genailabs lib/testu/testu_live.dart (_tally / SectionProgress / masteryOf):
// per user x section: attempts, correct, latest[question] = iscorrect of the last attempt,
// answered = latest.size(), mastered = latest.count(true); level by mastered/answered: <0.5 beginner, <0.9 competent, else expert.
// ponytail: full recompute every run, in memory; fine for a 50-person cohort. Incremental by lastactivity when it takes > 1 min.
MediaArchive archive = context.getPageValue("mediaarchive")
Map sections = [:]  // sectionid -> [tutorial, questions, name]
HitTracker secs = archive.query("componentsection").exact("playbackentitymoduleid", "entitytutorial").search()
secs.enableBulkOperations()
for (Data s in secs) {
  HitTracker contents = archive.query("componentcontent").exact("componentsectionid", s.getId()).exact("componenttype", "mcq").search()
  int q = 0
  for (Data c in contents) { if (c.get("questionid")) q++ }
  sections[s.getId()] = [tutorial: s.get("playbackentityid"), questions: q, name: s.getName()]
}
Map topicOf = [:]
HitTracker tuts = archive.query("entitytutorial").all().search()
for (Data t in tuts) topicOf[t.getId()] = t.get("entitytopic")

HitTracker answers = archive.query("tutoranswer").all().search()
answers.enableBulkOperations()
List rows = []
for (Data a in answers) rows << a
// Sort ties (equal/null datecreated) fall back to backend order; add a secondary
// tiebreaker (e.g. id) only if that becomes observable in practice.
rows.sort { it.getDate("datecreated") ?: new Date(0) }

Map groups = [:]
for (Data a in rows) {
  String user = a.get("user"); String section = a.get("componentsection")
  if (!user || !section || sections[section] == null) continue
  String key = user + "_" + section
  Map g = groups[key]
  if (g == null) { g = [user: user, section: section, attempts: 0, correct: 0, latest: [:], last: null]; groups[key] = g }
  boolean ok = "true".equals(String.valueOf(a.get("iscorrect")))
  g.attempts++
  if (ok) g.correct++
  g.latest[String.valueOf(a.get("entityquestion"))] = ok
  // Calibration: the four buckets always sum back to attempts.
  boolean certain = String.valueOf(a.get("answerconfidence")) in ["confident", "mostlysure"]
  g[certain ? (ok ? "cc" : "cw") : (ok ? "uc" : "uw")] = (g[certain ? (ok ? "cc" : "cw") : (ok ? "uc" : "uw")] ?: 0) + 1
  g.last = a.getDate("datecreated")
}

def searcher = archive.getSearcher("tutormastery")
Date now = new Date()
List tosave = []
for (Map g in groups.values()) {
  String id = g.user + "_" + g.section
  Data row = searcher.searchById(id) ?: searcher.createNewData()
  row.setId(id)
  row.setValue("user", g.user)
  row.setValue("componentsection", g.section)
  row.setValue("entitytutorial", sections[g.section].tutorial)
  row.setValue("entitytopic", topicOf[sections[g.section].tutorial])
  row.setValue("questions", sections[g.section].questions)
  int answered = g.latest.size()
  int mastered = g.latest.values().count { it }
  row.setValue("answered", answered)
  row.setValue("mastered", mastered)
  row.setValue("attempts", g.attempts)
  row.setValue("correct", g.correct)
  row.setValue("certaincorrect", g.cc ?: 0); row.setValue("certainwrong", g.cw ?: 0)
  row.setValue("unsurecorrect", g.uc ?: 0); row.setValue("unsurewrong", g.uw ?: 0)
  double share = answered == 0 ? 0 : mastered / (double) answered
  row.setValue("level", answered == 0 ? null : (share < 0.5 ? "beginner" : (share < 0.9 ? "competent" : "expert")))
  row.setValue("lastactivity", g.last)
  row.setValue("computedat", now)
  tosave << row
}
if (tosave) searcher.saveAllData(tosave, null)

// ponytail: full diff every run against groups.keySet() -- derived data, so a wrong
// delete (e.g. mid-recompute race) just self-heals on the next run.
HitTracker existing = searcher.query().all().search()
existing.enableBulkOperations()
List todelete = []
for (Data row in existing) { if (!groups.containsKey(row.getId())) todelete << row }
if (todelete) searcher.deleteAll(todelete, null)

// ---- Pass 2: tutordaily (user x calendar day, server timezone). Full rebuild, same ceiling as tutormastery.
// Helpers are closures, not script methods: a script-level method cannot see typed script locals (tz, days).
TimeZone tz = TimeZone.getDefault()
def dayOf = { Date d -> d.format("yyyyMMdd", tz) }
Map days = [:]   // "<user>_<yyyyMMdd>" -> map
def dayFor = { String user, Date at ->
  String k = user + "_" + dayOf(at)
  Map d = days[k]
  if (d == null) { d = [user: user, day: Date.parse("yyyyMMdd", dayOf(at)), answers: 0, correct: 0, certainwrong: 0, questions: 0, helpful: 0, nothelpful: 0, minutes: 0.0d, spans: 0, first: at, last: at, times: []]; days[k] = d }
  if (at < d.first) d.first = at
  if (at > d.last) d.last = at
  return d
}
for (Data a in rows) {  // `rows` = every tutoranswer, already sorted by datecreated
  String user = a.get("user"); Date at = a.getDate("datecreated"); if (!user || !at) continue
  Map d = dayFor(user, at)
  boolean ok = "true".equals(String.valueOf(a.get("iscorrect")))
  d.answers++; if (ok) d.correct++
  if (!ok && String.valueOf(a.get("answerconfidence")) in ["confident", "mostlysure"]) d.certainwrong++
  d.times << at
}
// Foreground spans from the app: a pause carries the seconds of its span.
HitTracker ev = archive.query("usageevent").all().search(); ev.enableBulkOperations()
Map ratings = [:]  // channel -> [[at, rating, user]] for pass 3
for (Data e in ev) {
  String user = e.get("user"); Date at = e.getDate("datecreated"); String type = e.get("type"); if (!user || !at) continue
  if (type == "iris_rate") { ratings.get(e.get("channel"), []) << [at: at, rating: e.get("rating"), user: user]; Map d = dayFor(user, at); d[e.get("rating") == "helpful" ? "helpful" : "nothelpful"]++; continue }
  Map d = dayFor(user, at)
  if (type == "pause") { d.minutes += ((e.get("seconds") ?: "0") as Double) / 60d; d.spans++ }
}

// ---- Pass 3: tutorquestion from chatterbox (learner question rows + their reply), ratings joined by channel within 10 min after the reply.
def tq = archive.getSearcher("tutorquestion")
HitTracker msgs = archive.query("chatterbox").exact("functionname", "chat_tutor_usercomment").search(); msgs.enableBulkOperations()
Map replies = [:]   // replytoid -> reply Data
List asks = []
for (Data m in msgs) { if (m.get("user") == "agent") { if (m.get("replytoid")) replies[m.get("replytoid")] = m } else if (m.get("user")) asks << m }
def slurper = new groovy.json.JsonSlurper()
List qsave = []; Set qids = [] as Set
for (Data m in asks) {
  Map ctx = [:]
  try { ctx = slurper.parseText(m.get("agentcontextvalues") ?: "{}") as Map } catch (Exception e) {}
  String query = ctx.query?.toString(); Date at = m.getDate("date"); String user = m.get("user")
  if (!query || !at) continue
  qids << m.getId()
  Data row = tq.searchById(m.getId()) ?: tq.createNewData(); row.setId(m.getId())
  row.setValue("user", user); row.setValue("datecreated", at); row.setValue("channel", m.get("channel"))
  String section = ctx.sectionid?.toString(); row.setValue("componentsection", section)
  row.setValue("entitytutorial", ctx.tutorialid?.toString() ?: sections[section]?.tutorial)
  row.setValue("entitytopic", topicOf[ctx.tutorialid?.toString() ?: sections[section]?.tutorial])
  row.setValue("entityquestion", ctx.questionid?.toString()); row.setValue("query", query)
  Data reply = replies[m.getId()]
  // `replied`, not `answered`: ES 2.4 types a field name index-wide and tutormastery.answered is a long.
  row.setValue("replied", reply != null)
  String text = reply?.get("message") ?: ""
  row.setValue("cited", (text =~ /\[[^\]\n]+,\s*(p\.\s*\d+|\d+:\d\d)\]\s*(\[\[hl[^\]]*\]\])?\s*$/).find())
  Date rat = reply?.getDate("date") ?: at
  def r = (ratings[m.get("channel")] ?: []).find { it.user == user && it.at >= rat && it.at.time - rat.time <= 10 * 60 * 1000L }
  row.setValue("rating", r?.rating)   // unconditional: a full rebuild must not leave a stale rating behind
  dayFor(user, at).questions++
  qsave << row
}
if (qsave) tq.saveAllData(qsave, null)
HitTracker oldq = tq.query().all().search(); oldq.enableBulkOperations()
List qdel = []; for (Data r in oldq) { if (!qids.contains(r.getId())) qdel << r }
if (qdel) tq.deleteAll(qdel, null)

// Classification: theme + topic label, 20 per LLM call, 60 s budget, unclassified rows wait for the next run.
// Classify the in-memory rows: `query` is index="false", so a row read back from the searcher has no text.
long budgetEnd = System.currentTimeMillis() + 60_000L
List pending = qsave.findAll { !it.getDate("classifiedat") }
int classified = 0
try {
  def llm = archive.getLlmConnection("thinking")
  while (pending && System.currentTimeMillis() < budgetEnd) {
    List batch = pending.take(20); pending = pending.drop(20)
    def ctx = new org.entermediadb.ai.llm.BaseAgentContext()
    ctx.putContextValue("questions", groovy.json.JsonOutput.toJson(batch.collect { [id: it.getId(), section: sections[it.get("componentsection")]?.name ?: "", text: it.get("query")] }))
    def res = llm.callStructure(ctx, "analytics_classify_questions")
    def items = res.getMessageStructured()?.get("items")
    List csave = []
    for (Map it in (items ?: [])) {
      Data row = batch.find { b -> b.getId() == it.id }
      if (row == null) continue
      row.setValue("theme", (it.theme in ["concept", "procedure", "example", "source", "challenge", "offtopic"]) ? it.theme : "other")
      row.setValue("topiclabel", (it.topiclabel ?: "").toString().trim().take(40)); row.setValue("classifiedat", now)
      csave << row
    }
    if (csave) { tq.saveAllData(csave, null); classified += csave.size() }
  }
} catch (Exception e) { log.info("testu computemastery: classification skipped this run: " + e.getMessage()) }

// tutordaily is saved last: pass 3 fills `questions` per day.
def daily = archive.getSearcher("tutordaily")
List dsave = []
for (Map d in days.values()) {
  Data row = daily.searchById(d.user + "_" + dayOf(d.day)) ?: daily.createNewData()
  row.setId(d.user + "_" + dayOf(d.day))
  row.setValue("user", d.user); row.setValue("day", d.day)
  ["answers", "correct", "certainwrong", "questions", "helpful", "nothelpful"].each { row.setValue(it, d[it]) }
  // sessions: foreground spans when the app reported any that day, else answer bursts (gap > 30 min starts a new one)
  int bursts = 0; Date prev = null
  for (Date t in d.times.sort()) { if (prev == null || t.time - prev.time > 30 * 60 * 1000L) bursts++; prev = t }
  row.setValue("sessions", d.spans > 0 ? d.spans : bursts)
  row.setValue("minutes", Math.round(d.minutes) as Integer)
  row.setValue("firstactivity", d.first); row.setValue("lastactivity", d.last); row.setValue("computedat", now)
  dsave << row
}
if (dsave) daily.saveAllData(dsave, null)
HitTracker olddaily = daily.query().all().search(); olddaily.enableBulkOperations()
List ddel = []; for (Data r in olddaily) { if (!days.containsKey(r.getId())) ddel << r }
if (ddel) daily.deleteAll(ddel, null)

log.info("testu computemastery: " + tosave.size() + " rows from " + rows.size() + " answers, " + todelete.size() + " stale rows pruned; "
  + dsave.size() + " tutordaily, " + qsave.size() + " tutorquestion (" + classified + " classified this run)")
