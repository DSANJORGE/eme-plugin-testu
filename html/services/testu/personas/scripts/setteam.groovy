import groovy.json.JsonOutput
import org.entermediadb.asset.MediaArchive
import org.openedit.Data

void audit(MediaArchive archive, String action, String targettype, String targetid, Object before, Object after) {
  def s = archive.getSearcher("auditevent")
  Data e = s.createNewData() // ponytail: no setId - BaseElasticSearcher.nextId() always throws ("Should not call next ID"); saveData below auto-assigns an ES id when none is set
  e.setValue("datecreated", new Date())
  e.setValue("actor", context.getUser()?.getId())
  e.setValue("action", action)
  e.setValue("targettype", targettype)
  e.setValue("targetid", targetid)
  e.setValue("before", before == null ? "" : JsonOutput.toJson(before))
  e.setValue("after", after == null ? "" : JsonOutput.toJson(after))
  s.saveData(e, context.getUser())
}
void reply(Map m) { context.putPageValue("json", JsonOutput.toJson(m)) }
void fail(int code, String msg) { context.getResponse().setStatus(code); reply([ok: false, error: msg]); context.setCancelActions(true) }
Map snapshot(Data d, List fields) { d == null ? null : fields.collectEntries { [(it): d.get(it)] } }

MediaArchive archive = context.getPageValue("mediaarchive")
String userid = context.getRequestParameter("userid"); String team = context.getRequestParameter("team") ?: ""
def users = archive.getSearcher("user")
Data u = users.searchById(userid)
if (u == null) { fail(404, "no user"); return }
if (team && archive.getCachedData("team", team) == null) { fail(400, "unknown team"); return }
String before = u.get("team")
u.setValue("team", team ?: null)
users.saveData(u, context.getUser())
audit(archive, "user.team", "user", userid, [team: before], [team: team])
reply([ok: true])
