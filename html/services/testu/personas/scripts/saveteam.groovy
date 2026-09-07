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
String id = (context.getRequestParameter("id") ?: "").trim().toLowerCase()
if (!(id ==~ /[a-z0-9]+/)) { fail(400, "id must be [a-z0-9]+"); return }
String parent = context.getRequestParameter("parent") ?: ""
if (parent == id) { fail(400, "a team cannot be its own parent"); return }
if (parent && archive.getCachedData("team", parent) == null) { fail(400, "unknown parent"); return }
String manager = context.getRequestParameter("manager") ?: ""
if (manager && archive.getSearcher("user").searchById(manager) == null) { fail(400, "unknown manager"); return }
def teams = archive.getSearcher("team")
Data t = teams.searchById(id)
List fields = ["name", "parent", "manager", "location", "costcenter"]
Map before = snapshot(t, fields)
if (t == null) { t = teams.createNewData(); t.setId(id) }
fields.each { f -> t.setValue(f, context.getRequestParameter(f) ?: null) }
t.setValue("enabled", "true")
teams.saveData(t, context.getUser())
audit(archive, "team.save", "team", id, before, snapshot(t, fields))
reply([ok: true, id: id])
