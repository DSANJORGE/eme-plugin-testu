import groovy.json.JsonOutput
import org.entermediadb.asset.MediaArchive
import org.openedit.Data
MediaArchive archive = context.getPageValue("mediaarchive")
Set scope = context.getPageValue("scopeteams")
Map roles = [:]
for (Data p in archive.query("userprofile").all().search()) roles[p.getId()] = p.get("settingsgroup")
Map last = [:]
def m = archive.query("tutormastery").all().search(); m.enableBulkOperations()
for (Data r in m) { Date d = r.getDate("lastactivity"); if (d && (last[r.get("user")] == null || d > last[r.get("user")])) last[r.get("user")] = d }
List out = []
def hits = archive.query("user").all().search(); hits.enableBulkOperations()
for (Data u in hits) {
  if (scope != null && !(u.get("team") in scope)) continue
  out << [id: u.getId(), email: u.get("email"), firstName: u.get("firstName"), lastName: u.get("lastName"), team: u.get("team"),
          role: roles[u.getId()] ?: "users", enabled: !"false".equals(String.valueOf(u.get("enabled"))),
          lastactivity: last[u.getId()]?.format("yyyy-MM-dd'T'HH:mm:ssXXX")]
}
context.putPageValue("json", JsonOutput.toJson([users: out]))
