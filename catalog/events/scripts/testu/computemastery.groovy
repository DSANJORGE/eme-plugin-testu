import org.entermediadb.asset.MediaArchive
import org.openedit.Data
import org.openedit.hittracker.HitTracker

// Same rule as app-genailabs lib/testu/testu_live.dart (_tally / SectionProgress / masteryOf):
// per user x section: attempts, correct, latest[question] = iscorrect of the last attempt,
// answered = latest.size(), mastered = latest.count(true); level by mastered/answered: <0.5 beginner, <0.9 competent, else expert.
// ponytail: full recompute every run, in memory; fine for a 50-person cohort. Incremental by lastactivity when it takes > 1 min.
MediaArchive archive = context.getPageValue("mediaarchive")
Map sections = [:]  // sectionid -> [tutorial, questions]
HitTracker secs = archive.query("componentsection").exact("playbackentitymoduleid", "entitytutorial").search()
secs.enableBulkOperations()
for (Data s in secs) {
  HitTracker contents = archive.query("componentcontent").exact("componentsectionid", s.getId()).exact("componenttype", "mcq").search()
  int q = 0
  for (Data c in contents) { if (c.get("questionid")) q++ }
  sections[s.getId()] = [tutorial: s.get("playbackentityid"), questions: q]
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

log.info("testu computemastery: " + tosave.size() + " rows from " + rows.size() + " answers, " + todelete.size() + " stale rows pruned")
