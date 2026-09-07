import org.entermediadb.asset.MediaArchive
import org.openedit.Data
// Builds the in-scope analytics model once per request; overview/activity/person/ask only shape it.
// ponytail: every helper is a closure, not a script method -- script-level methods run in their own
// scope and cannot see the typed script locals (scope, teamFilter, to, sections) they need.
MediaArchive archive = context.getPageValue("mediaarchive")
Set scope = context.getPageValue("scopeteams"); Map allteams = context.getPageValue("allteams") ?: [:]
String topicFilter = context.getRequestParameter("entitytopic") ?: ""; String teamFilter = context.getRequestParameter("team") ?: ""
if (scope != null && teamFilter && !(teamFilter in scope)) { context.getResponse().setStatus(400); context.putPageValue("json", '{"ok":false,"error":"out of scope"}'); context.setCancelActions(true); return }
Date now = new Date()
Date to = (context.getRequestParameter("to") ? Date.parse("yyyy-MM-dd", context.getRequestParameter("to")) : now).clearTime() + 1   // exclusive end
Date from = (context.getRequestParameter("from") ? Date.parse("yyyy-MM-dd", context.getRequestParameter("from")) : to - 30).clearTime()
int span = (to - from) as int; Date prevFrom = from - span; Date prevTo = from
def day = { Date d -> d.format("yyyy-MM-dd") }
def levelOf = { int mastered, int answered -> answered == 0 ? null : (mastered / (double) answered < 0.5 ? "beginner" : (mastered / (double) answered < 0.9 ? "competent" : "expert")) }
def levelsOf = { Collection lv -> [notstarted: lv.count { it == null }, beginner: lv.count { it == "beginner" }, competent: lv.count { it == "competent" }, expert: lv.count { it == "expert" }] }

// Users: everyone in the org (for the median) and the subset in scope + team filter.
Map allUsers = [:]; def uh = archive.query("user").all().search(); uh.enableBulkOperations(); for (Data u in uh) allUsers[u.getId()] = u
def inScope = { Data u -> String t = u.get("team"); (scope == null || t in scope) && (!teamFilter || t == teamFilter) }
def isLearner = { Data u -> !(u.getId() in ["admin", "agent"]) && u.get("enabled") != "false" }
Map users = allUsers.findAll { k, u -> isLearner(u) && inScope(u) }
def nameOf = { Data u -> ((u.get("firstName") ?: "") + " " + (u.get("lastName") ?: "")).trim() ?: u.getId() }
Map topics = [:]; for (Data t in archive.query("entitytopic").all().search()) topics[t.getId()] = t.getName()
Map sections = [:]; for (Data s in archive.query("componentsection").exact("playbackentitymoduleid", "entitytutorial").search()) sections[s.getId()] = s.getName()

// Mastery rows (all, for the median) then in scope + topic filter.
List allMastery = []; def mh = archive.query("tutormastery").all().search(); mh.enableBulkOperations(); for (Data r in mh) allMastery << r
List mastery = allMastery.findAll { users.containsKey(it.get("user")) && (!topicFilter || it.get("entitytopic") == topicFilter) }
def n = { Data r, String f -> (r.get(f) ?: "0") as Integer }
Map perUser = [:]   // uid -> [mastered, answered, attempts, cw, cc, uc, uw, last]
Map perUserTopic = [:] // uid -> topic -> [mastered, answered]
Map perSection = [:] // section -> [topic, levels, answered, attempts, cw]
for (Data r in mastery) {
  String u = r.get("user"); def pu = perUser.get(u, [mastered: 0, answered: 0, attempts: 0, cw: 0, cc: 0, uc: 0, uw: 0, last: null])
  pu.mastered += n(r, "mastered"); pu.answered += n(r, "answered"); pu.attempts += n(r, "attempts"); pu.cw += n(r, "certainwrong"); pu.cc += n(r, "certaincorrect"); pu.uc += n(r, "unsurecorrect"); pu.uw += n(r, "unsurewrong")
  Date la = r.getDate("lastactivity"); if (la && (pu.last == null || la > pu.last)) pu.last = la
  def pt = perUserTopic.get(u, [:]).get(r.get("entitytopic"), [mastered: 0, answered: 0]); pt.mastered += n(r, "mastered"); pt.answered += n(r, "answered")
  def ps = perSection.get(r.get("componentsection"), [topic: r.get("entitytopic"), levels: [], answered: 0, attempts: 0, cw: 0])
  ps.levels << levelOf(n(r, "mastered"), n(r, "answered")); ps.answered += n(r, "answered"); ps.attempts += n(r, "attempts"); ps.cw += n(r, "certainwrong")
}
Map levelByUser = users.keySet().collectEntries { u -> [(u): perUser[u] ? levelOf(perUser[u].mastered, perUser[u].answered) : null] }

// Daily rows in period and previous period (in scope). One pass over the hit tracker: a bulk
// tracker's scroll is consumed by the first iteration, so the org-wide median reads this list too.
List allDaily = []; def dh = archive.query("tutordaily").all().search(); dh.enableBulkOperations(); for (Data r in dh) allDaily << r
List dailyAll = allDaily.findAll { users.containsKey(it.get("user")) }
// Cohort/funnel deliberately ignore the topic filter: activated must stay a superset of
// active30d >= active7d, and those come from tutordaily, which has no topic.
Set activatedIds = dailyAll.findAll { n(it, "answers") > 0 }.collect { it.get("user") } as Set
List daily = dailyAll.findAll { Date d = it.getDate("day"); d >= from && d < to }
List prevDaily = dailyAll.findAll { Date d = it.getDate("day"); d >= prevFrom && d < prevTo }
Map series = [:]; for (Date d = from; d < to; d = d + 1) series[day(d)] = [day: day(d), people: 0, answers: 0, minutes: 0, sessions: 0, certainwrong: 0, questions: 0]
for (Data r in daily) { def s = series[day(r.getDate("day"))]; if (s == null) continue; if (n(r, "answers") > 0) s.people++; ["answers", "minutes", "sessions", "certainwrong", "questions"].each { s[it] += n(r, it) } }
def activeSince = { List rowsIn, int daysBack -> Date since = to - daysBack; rowsIn.findAll { Date d = it.getDate("day"); d >= since && d < to && n(it, "answers") > 0 }.collect { it.get("user") } as Set }
Map cohort = [total: users.size(), activated: activatedIds.size(), active7d: activeSince(dailyAll, 7).size(), active30d: activeSince(dailyAll, 30).size()]
def sums = { List rowsIn -> [answers: rowsIn.sum { n(it, "answers") } ?: 0, minutes: rowsIn.sum { n(it, "minutes") } ?: 0, certainwrong: rowsIn.sum { n(it, "certainwrong") } ?: 0, questions: rowsIn.sum { n(it, "questions") } ?: 0] }
Map previous = sums(prevDaily) + [active7d: (prevDaily.findAll { it.getDate("day") >= prevTo - 7 && n(it, "answers") > 0 }.collect { it.get("user") } as Set).size()]

// Topics, sections, teams.
List topicStats = topics.collect { tid, tname ->
  if (topicFilter && tid != topicFilter) return null
  def lv = users.keySet().collect { u -> def pt = perUserTopic[u]?.get(tid); pt ? levelOf(pt.mastered, pt.answered) : null }
  def secs = perSection.findAll { k, v -> v.topic == tid }
  def weakest = secs.max { e -> e.value.levels.count { it == "beginner" } }   // one param: a two-param max closure is a Comparator
  int wb = weakest ? weakest.value.levels.count { it == "beginner" } : 0   // max() picks the first on a tie -- 0 beginners is no signal, not a weakest section
  [id: tid, name: tname, people: lv.count { it != null }, levels: levelsOf(lv), weakest: wb > 0 ? [section: weakest.key, name: sections[weakest.key], beginners: wb] : null]
}.findAll { it != null }
// Tutor questions in scope + period.
List tqAll = []; def qh = archive.query("tutorquestion").all().search(); qh.enableBulkOperations(); for (Data r in qh) { if (users.containsKey(r.get("user")) && (!topicFilter || r.get("entitytopic") == topicFilter)) tqAll << r }
List tq = tqAll.findAll { Date d = it.getDate("datecreated"); d >= from && d < to }
Map qBySection = tq.groupBy { it.get("componentsection") }
List sectionStats = perSection.collect { sid, v ->
  List qs = qBySection[sid] ?: []
  int people = v.levels.size(); int beginners = v.levels.count { it == "beginner" }; int unanswered = qs.count { it.get("replied") != "true" }
  double score = 3 * beginners / Math.max(people, 1) + 2 * qs.size() / Math.max(v.attempts, 1) + 2 * unanswered / Math.max(qs.size(), 1) + 3 * v.cw / Math.max(v.attempts, 1)
  [section: sid, name: sections[sid], topic: topics[v.topic], topicId: v.topic, people: people, levels: levelsOf(v.levels), beginners: beginners, questions: qs.size(), misconceptions: v.cw, unanswered: unanswered, score: Math.round(score * 100) / 100d,
   helpfulShare: qs.count { it.get("rating") } ? qs.count { it.get("rating") == "helpful" } / (double) qs.count { it.get("rating") } : null]
}
List gaps = sectionStats.findAll { it.people > 0 }.sort { -it.score }.take(5)
Map byTeam = users.values().groupBy { it.get("team") ?: "" }
Set active7dIds = activeSince(dailyAll, 7)
List teamStats = byTeam.collect { tid, members ->
  Set ids = members.collect { it.getId() } as Set
  def lv = ids.collect { levelByUser[it] }
  def weakest = topicStats.collect { t -> [name: t.name, beginners: ids.count { u -> def pt = perUserTopic[u]?.get(t.id); pt && levelOf(pt.mastered, pt.answered) == "beginner" }] }.max { it.beginners }
  [id: tid, name: tid ? (allteams[tid]?.getName() ?: tid) : "", members: ids.size(), activated: ids.count { it in activatedIds }, active7d: (active7dIds.intersect(ids)).size(), levels: levelsOf(lv), weakest: weakest?.beginners > 0 ? weakest.name : null]
}.sort { it.name }
// Calibration (in scope + topic filter, all attempts).
Map calibration = [cc: perUser.values().sum { it.cc } ?: 0, cu: perUser.values().sum { it.uc } ?: 0, ic: perUser.values().sum { it.uw } ?: 0, iu: perUser.values().sum { it.cw } ?: 0]
// Org-wide median, only when the org has >= 5 learners; ignores scope on purpose (anonymous comparison).
Map orgUsers = allUsers.findAll { k, u -> isLearner(u) }
Map median = null
if (orgUsers.size() >= 5) {
  Map orgPer = [:]; for (Data r in allMastery) { if (orgUsers.containsKey(r.get("user"))) { def p = orgPer.get(r.get("user"), [m: 0, a: 0]); p.m += n(r, "mastered"); p.a += n(r, "answered") } }
  Set orgActive = [] as Set; for (Data r in allDaily) { Date d = r.getDate("day"); if (orgUsers.containsKey(r.get("user")) && d >= to - 7 && d < to && n(r, "answers") > 0) orgActive << r.get("user") }
  median = [activeShare: orgActive.size() / (double) orgUsers.size(), expertShare: orgUsers.keySet().count { u -> orgPer[u] && levelOf(orgPer[u].m, orgPer[u].a) == "expert" } / (double) orgUsers.size()]
}
List inactive = users.values().findAll { u -> Date l = perUser[u.getId()]?.last; l == null || l < now - 7 }.collect { u -> [user: u.getId(), name: nameOf(u), team: u.get("team"), lastactivity: perUser[u.getId()]?.last?.format("yyyy-MM-dd'T'HH:mm:ssXXX")] }.sort { it.lastactivity ?: "" }
// Tutor usage aggregates (never the text).
def irisAgg = { List qs ->
  int rated = qs.count { it.get("rating") }
  int people = (qs.collect { it.get("user") } as Set).size()
  // Free text: >= 3 repeats is not anonymity. Below the same k as the median gate, publish nothing.
  Map labels = people >= 5 ? qs.findAll { it.get("topiclabel") }.groupBy { it.get("topiclabel") }.findAll { k, v -> v.size() >= 3 } : [:]
  [questions: qs.size(), people: people, citedShare: qs ? qs.count { it.get("cited") == "true" } / (double) qs.size() : null, ratedShare: qs ? rated / (double) qs.size() : null,
   helpfulShare: rated ? qs.count { it.get("rating") == "helpful" } / (double) rated : null,
   themes: qs.groupBy { it.get("theme") ?: "unclassified" }.collect { k, v -> [theme: k, count: v.size()] }.sort { -it.count },
   sections: qs.groupBy { it.get("componentsection") }.collect { k, v -> [section: k, name: sections[k], questions: v.size(), helpfulShare: v.count { it.get("rating") } ? v.count { it.get("rating") == "helpful" } / (double) v.count { it.get("rating") } : null] }.sort { -it.questions },
   labels: labels.collect { k, v -> [label: k, count: v.size()] }.sort { -it.count }.take(30)]
}
context.putPageValue("analytics", [from: from, to: to, users: users, allteams: allteams, topics: topics, sections: sections, mastery: mastery, perUser: perUser, perUserTopic: perUserTopic, levelByUser: levelByUser,
  dailyAll: dailyAll, daily: daily, series: series.values() as List, cohort: cohort, previous: previous, levels: levelsOf(levelByUser.values()), topicStats: topicStats, sectionStats: sectionStats, gaps: gaps,
  teamStats: teamStats, calibration: calibration, median: median, inactive: inactive, tq: tq, iris: irisAgg(tq), nameOf: nameOf, levelOf: levelOf, levelsOf: levelsOf, day: day, n: n])
