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
String email = (context.getRequestParameter("email") ?: "").trim().toLowerCase()
String team = context.getRequestParameter("team") ?: ""
String role = context.getRequestParameter("role") ?: "users"
if (!(email ==~ /[^@\s]+@[^@\s]+\.[^@\s]+/)) { fail(400, "invalid email"); return }
if (!(role in ["users", "manager", "training", "orgadmin"])) { fail(400, "invalid role"); return }
if (role in ["training", "orgadmin"] && !context.getUserProfile().hasPermission("personas_manage")) { fail(403, "role requires personas_manage"); return }
if (team && archive.getCachedData("team", team) == null) { fail(400, "unknown team"); return }
def users = archive.getSearcher("user")
if (users.searchById(email) != null || archive.getUserManager().getUserByEmail(email) != null) { fail(400, "user exists"); return }
Data u = users.createNewData()
u.setId(email)
u.setValue("email", email)
u.setValue("firstName", context.getRequestParameter("firstName") ?: "")
u.setValue("lastName", context.getRequestParameter("lastName") ?: "")
u.setValue("enabled", "true")
// Ruling R8: random secret, never returned or logged; eMe sessions need md5(password), OTP stays the only login path
u.setValue("password", UUID.randomUUID().toString())
if (team) u.setValue("team", team)
users.saveData(u, context.getUser())
def profiles = archive.getSearcher("userprofile")
Data p = profiles.searchById(email) ?: profiles.createNewData()
p.setId(email); p.setValue("userid", email); p.setValue("settingsgroup", role)
profiles.saveData(p, context.getUser())
audit(archive, "user.create", "user", email, null, [email: email, team: team, role: role])
reply([ok: true, id: email])
