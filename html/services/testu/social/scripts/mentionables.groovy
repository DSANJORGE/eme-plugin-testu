import groovy.json.JsonOutput
import org.entermediadb.asset.MediaArchive
import org.openedit.Data

void reply(Map m) { context.putPageValue("json", JsonOutput.toJson(m)) }
void fail(int code, String msg) { context.getResponse().setStatus(code); reply([ok: false, error: msg]); context.setCancelActions(true) }
Set STAFF = ["manager", "training", "orgadmin"] as Set

MediaArchive archive = context.getPageValue("mediaarchive")
def who = context.getUser()
String me = who?.getId()
if (!me) { fail(401, "not signed in"); return }
String myteam = who.get("team") ?: ""
Map roles = [:]
def profiles = archive.query("userprofile").all().search(); profiles.enableBulkOperations()
for (Data p in profiles) roles[p.getId()] = p.get("settingsgroup")
// Learners in my team plus everyone who can answer from the console. Same roster walk as users.groovy.
List out = []
def hits = archive.query("user").all().search(); hits.enableBulkOperations()
for (Data u in hits) {
  String id = u.getId()
  if (id == me || "false".equals(String.valueOf(u.get("enabled")))) continue
  String role = roles[id] ?: "users"
  boolean teammate = myteam && u.get("team") == myteam
  if (!teammate && !(role in STAFF)) continue
  String name = "${u.get('firstName') ?: ''} ${u.get('lastName') ?: ''}".trim()
  out << [id: id, name: name ?: id, role: role]
}
out.sort { it.name.toLowerCase() }
reply([ok: true, people: out])
