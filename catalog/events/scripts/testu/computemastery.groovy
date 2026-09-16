import org.entermediadb.asset.MediaArchive
import org.openedit.Data
import org.openedit.hittracker.HitTracker

// Pass 1: tutormastery (user x section and user x topic) from the learning engine v1 (weighted mastery, bands);
// see tech.genailabs.tutor.LearningEngine.recomputeMastery. sections/topicOf/rows stay here for passes 2-3.
MediaArchive archive = context.getPageValue("mediaarchive")
Map sections = [:]  // sectionid -> [tutorial, name]
HitTracker secs = archive.query("componentsection").exact("playbackentitymoduleid", "entitytutorial").search()
secs.enableBulkOperations()
for (Data s in secs) sections[s.getId()] = [tutorial: s.get("playbackentityid"), name: s.getName()]
Map topicOf = [:]
HitTracker tuts = archive.query("entitytutorial").all().search()
for (Data t in tuts) topicOf[t.getId()] = t.get("entitytopic")

HitTracker answers = archive.query("tutoranswer").all().search()
answers.enableBulkOperations()
List rows = []
for (Data a in answers) rows << a
rows.sort { it.getDate("datecreated") ?: new Date(0) }

int[] mastery = new tech.genailabs.tutor.LearningEngine(archive).recomputeMastery()
Date now = new Date()
// learningsession retention: 7 days usable + 30 days kept for diagnostics, then deleted (idempotent)
int purgedSessions = new tech.genailabs.tutor.LearningEngine(archive).purgeSessions(now)

// ---- Pass 2: tutordaily (user x calendar day in the organisation's zone). Full rebuild, same ceiling as tutormastery.
// Helpers are closures, not script methods: a script-level method cannot see typed script locals (tz, days).
// A "day" is the learners' day (catalog setting testu_timezone, the engine's own), not the server's: a server two
// zones east would file every late-shift answer under tomorrow. Unconfigured = the server's zone, as before.
String tzid = archive.getCatalogSettingValue("testu_timezone")
TimeZone tz = (tzid && TimeZone.getTimeZone(tzid).getID() == tzid) ? TimeZone.getTimeZone(tzid) : TimeZone.getDefault()
def dayOf = { Date d -> d.format("yyyyMMdd", tz) }
Map days = [:]   // "<user>_<yyyyMMdd>" -> map
def dayFor = { String user, Date at ->
  String k = user + "_" + dayOf(at)
  Map d = days[k]
  if (d == null) { d = [user: user, day: Date.parse("yyyyMMdd", dayOf(at)), answers: 0, correct: 0, certainwrong: 0, questions: 0, helpful: 0, nothelpful: 0, minutes: 0.0d, first: at, last: at, iv: []]; days[k] = d }
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
  d.iv << [at.time, at.time]
}
// Foreground spans from the app: a pause carries the seconds of its span.
HitTracker ev = archive.query("usageevent").all().search(); ev.enableBulkOperations()
Map ratings = [:]  // channel -> [[at, rating, user]] for pass 3
for (Data e in ev) {
  String user = e.get("user"); Date at = e.getDate("datecreated"); String type = e.get("type"); if (!user || !at) continue
  if (type == "iris_rate") { ratings.get(e.get("channel"), []) << [at: at, rating: e.get("rating"), user: user]; Map d = dayFor(user, at); d[e.get("rating") == "helpful" ? "helpful" : "nothelpful"]++; continue }
  Map d = dayFor(user, at)
  double span = type == "pause" ? ((e.get("seconds") ?: "0") as Double) : 0d
  d.minutes += span / 60d
  d.iv << [at.time - (long) (span * 1000), at.time]  // a pause carries its foreground span; any other event is a point
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
  Map qd = dayFor(user, at); qd.questions++; qd.iv << [at.time, at.time]
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
  // sessions: runs of activity (foreground spans, answers, questions, usage events) with no gap over 30 min.
  // Not one per foreground span: a browser tab switch pauses and resumes the app, which inflated it (121 in a day).
  int sessions = 0; long end = 0L
  for (List iv in d.iv.sort { it[0] }) { if (iv[0] - end > 30 * 60 * 1000L) sessions++; end = Math.max(end, iv[1]) }
  row.setValue("sessions", sessions)
  row.setValue("minutes", Math.round(d.minutes) as Integer)
  row.setValue("firstactivity", d.first); row.setValue("lastactivity", d.last); row.setValue("computedat", now)
  dsave << row
}
if (dsave) daily.saveAllData(dsave, null)
HitTracker olddaily = daily.query().all().search(); olddaily.enableBulkOperations()
List ddel = []; for (Data r in olddaily) { if (!days.containsKey(r.getId())) ddel << r }
if (ddel) daily.deleteAll(ddel, null)

log.info("testu computemastery: " + mastery[0] + " rows from " + mastery[1] + " answers, " + mastery[2] + " stale rows pruned; "
  + dsave.size() + " tutordaily, " + qsave.size() + " tutorquestion (" + classified + " classified this run); " + purgedSessions + " expired learningsession rows purged")
