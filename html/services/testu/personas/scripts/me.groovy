import groovy.json.JsonOutput
import org.entermediadb.asset.MediaArchive
import org.openedit.Data
MediaArchive archive = context.getPageValue("mediaarchive")
def u = context.getUser(); def p = context.getUserProfile()
List perms = ["personas_manage","personas_operate","personas_view","analytics_manage","analytics_operate","analytics_view","training_manage","training_operate","training_view"].findAll { p != null && p.hasPermission(it) }
List modules = archive.query("suitemodule").all().sort("ordering").search().collect { Data m ->
  [id: m.getId(), name: m.getName(), surfaces: (m.getValues("surfaces") ?: []) as List, enabled: "true".equals(String.valueOf(m.get("enabled")))]
}
context.putPageValue("json", JsonOutput.toJson([
  user: [id: u.getId(), email: u.get("email"), firstName: u.get("firstName"), lastName: u.get("lastName")],
  role: p?.get("settingsgroup") ?: "users", permissions: perms, modules: modules]))
