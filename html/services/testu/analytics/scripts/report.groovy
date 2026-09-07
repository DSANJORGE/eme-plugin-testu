import groovy.json.JsonOutput
import org.entermediadb.asset.MediaArchive
import org.openedit.Data
MediaArchive archive = context.getPageValue("mediaarchive")
Set scope = context.getPageValue("scopeteams")
String topicFilter = context.getRequestParameter("entitytopic"); String teamFilter = context.getRequestParameter("team")
if (scope != null && teamFilter && !(teamFilter in scope)) { context.getResponse().setStatus(400); context.putPageValue("json", '{"ok":false,"error":"out of scope"}'); return }
Map users = [:]
def uh = archive.query("user").all().search(); uh.enableBulkOperations()
for (Data u in uh) users[u.getId()] = u
Map topics = [:]
for (Data t in archive.query("entitytopic").all().search()) topics[t.getId()] = t.getName()
Map sections = [:]
for (Data s in archive.query("componentsection").exact("playbackentitymoduleid", "entitytutorial").search()) sections[s.getId()] = s.getName()
Date week = new Date() - 7
Set active = [] as Set; int answers7d = 0; Map levels = [beginner: 0, competent: 0, expert: 0]
List rows = []
// closure, not a method: a script method cannot see the script's own locals
def n = { Data d, String f -> (d.get(f) as Integer) ?: 0 }
def mh = archive.query("tutormastery").all().search(); mh.enableBulkOperations()
for (Data r in mh) {
  Data u = users[r.get("user")]
  if (u == null) continue
  String team = u.get("team")
  if (scope != null && !(team in scope)) continue
  if (teamFilter && team != teamFilter) continue
  if (topicFilter && r.get("entitytopic") != topicFilter) continue
  Date la = r.getDate("lastactivity")
  if (la && la > week) { active << u.getId() }
  String lvl = r.get("level"); if (lvl in levels.keySet()) levels[lvl]++
  rows << [user: u.getId(), name: ((u.get("firstName") ?: "") + " " + (u.get("lastName") ?: "")).trim() ?: u.getId(), team: team,
           entitytopic: r.get("entitytopic"), topic: topics[r.get("entitytopic")], componentsection: r.get("componentsection"), section: sections[r.get("componentsection")],
           questions: r.get("questions") as Integer, answered: r.get("answered") as Integer, mastered: r.get("mastered") as Integer,
           attempts: r.get("attempts") as Integer, correct: r.get("correct") as Integer, level: lvl, lastactivity: la?.format("yyyy-MM-dd'T'HH:mm:ssXXX"),
           certaincorrect: n(r, "certaincorrect"), certainwrong: n(r, "certainwrong"), unsurecorrect: n(r, "unsurecorrect"), unsurewrong: n(r, "unsurewrong"),
           computedat: r.getDate("computedat")?.format("yyyy-MM-dd'T'HH:mm:ssXXX")]
}
// answers in the last 7 days come from tutoranswer directly (tutormastery only keeps totals)
def ah = archive.query("tutoranswer").after("datecreated", week).search(); ah.enableBulkOperations()
for (Data a in ah) { Data u = users[a.get("user")]; if (u != null && (scope == null || u.get("team") in scope) && (!teamFilter || u.get("team") == teamFilter)) answers7d++ }
context.putPageValue("json", JsonOutput.toJson([rows: rows, summary: [activeusers7d: active.size(), answers7d: answers7d, levels: levels],
  topics: topics.collect { k, v -> [id: k, name: v] }]))
