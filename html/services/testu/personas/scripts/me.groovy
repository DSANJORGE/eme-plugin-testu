import groovy.json.JsonOutput
import org.entermediadb.asset.MediaArchive
import org.openedit.Data
MediaArchive archive = context.getPageValue("mediaarchive")
def u = context.getUser(); def p = context.getUserProfile()
List perms = ["personas_manage","personas_operate","personas_view","analytics_manage","analytics_operate","analytics_view","training_manage","training_operate","training_view"].findAll { p != null && p.hasPermission(it) }
List modules = archive.query("suitemodule").all().sort("ordering").search().collect { Data m ->
  [id: m.getId(), name: m.getName(), surfaces: (m.getValues("surfaces") ?: []) as List, enabled: "true".equals(String.valueOf(m.get("enabled")))]
}
// The site's tutor, same lookup as ask.groovy: the console shows the
// organisation's name and the tutor's face, and Iris speaks its language.
def persona = archive.getData("tutorpersona", archive.getCatalogSettingValue("tutorpersona") ?: "iris")
context.putPageValue("json", JsonOutput.toJson([
  user: [id: u.getId(), email: u.get("email"), firstName: u.get("firstName"), lastName: u.get("lastName")],
  role: p?.get("settingsgroup") ?: "users", permissions: perms, modules: modules,
  persona: persona == null ? null : [name: persona.getName(), avatar: persona.get("avatar"), organization: persona.get("organization"), language: persona.get("tutorlanguage")]]))
