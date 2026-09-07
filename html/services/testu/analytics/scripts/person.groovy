import groovy.json.JsonOutput
import org.openedit.Data
Map a = context.getPageValue("analytics"); if (a == null) return
def archive = context.getPageValue("mediaarchive")
String uid = (context.getRequestParameter("user") ?: "").toLowerCase()
Data u = a.users[uid]
if (u == null) { context.getResponse().setStatus(403); context.putPageValue("json", '{"ok":false,"error":"out of scope"}'); return }
List rows = a.mastery.findAll { it.get("user") == uid }.collect { r -> [entitytopic: r.get("entitytopic"), topic: a.topics[r.get("entitytopic")], componentsection: r.get("componentsection"), section: a.sections[r.get("componentsection")],
  questions: a.n(r, "questions"), answered: a.n(r, "answered"), mastered: a.n(r, "mastered"), attempts: a.n(r, "attempts"), correct: a.n(r, "correct"), level: r.get("level"), lastactivity: r.getDate("lastactivity")?.format("yyyy-MM-dd'T'HH:mm:ssXXX"),
  certaincorrect: a.n(r, "certaincorrect"), certainwrong: a.n(r, "certainwrong"), unsurecorrect: a.n(r, "unsurecorrect"), unsurewrong: a.n(r, "unsurewrong")] }
def pu = a.perUser[uid] ?: [cc: 0, uc: 0, uw: 0, cw: 0]
List topics = a.topics.collect { tid, tname -> def pt = a.perUserTopic[uid]?.get(tid); if (!pt) return null
  def weak = rows.findAll { it.entitytopic == tid && it.answered > 0 }.min { it.mastered / (double) it.answered }
  [id: tid, name: tname, level: a.levelOf(pt.mastered, pt.answered), mastered: pt.mastered, answered: pt.answered, weakest: weak?.section] }.findAll { it != null }
List mine = a.daily.findAll { it.get("user") == uid }
Map series = [:]; for (Date d = a.from; d < a.to; d = d + 1) series[a.day(d)] = [day: a.day(d), answers: 0, correct: 0, minutes: 0, sessions: 0, questions: 0]
for (Data r in mine) { def s = series[a.day(r.getDate("day"))]; if (s) ["answers", "correct", "minutes", "sessions", "questions"].each { s[it] += a.n(r, it) } }
List qs = a.tq.findAll { it.get("user") == uid }; int rated = qs.count { it.get("rating") }
context.putPageValue("json", JsonOutput.toJson([ok: true, user: [id: uid, name: a.nameOf(u), team: u.get("team"), role: (archive.getSearcher("userprofile").searchById(uid)?.get("settingsgroup") ?: "users"), enabled: u.get("enabled") != "false", lastlogin: u.get("lastlogin")],
  rows: rows, series: series.values() as List, calibration: [cc: pu.cc, cu: pu.uc, ic: pu.uw, iu: pu.cw], topics: topics,
  usage: [sessions: mine.sum { a.n(it, "sessions") } ?: 0, minutes: mine.sum { a.n(it, "minutes") } ?: 0, activeDays: mine.count { a.n(it, "answers") > 0 }],
  iris: [questions: qs.size(), sections: qs.groupBy { it.get("componentsection") }.collect { k, v -> [section: k, name: a.sections[k], questions: v.size()] }, helpfulShare: rated ? qs.count { it.get("rating") == "helpful" } / (double) rated : null]]))
