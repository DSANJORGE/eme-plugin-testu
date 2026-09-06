import groovy.json.JsonOutput
import org.entermediadb.asset.MediaArchive
import org.openedit.Data
MediaArchive archive = context.getPageValue("mediaarchive")
Set scope = context.getPageValue("scopeteams"); Map teams = context.getPageValue("allteams")
Map members = [:]
def hits = archive.query("user").all().search(); hits.enableBulkOperations()
for (Data u in hits) { String t = u.get("team"); if (t) members[t] = (members[t] ?: 0) + 1 }
List out = teams.values().findAll { scope == null || it.getId() in scope }.collect { Data t ->
  [id: t.getId(), name: t.getName(), parent: t.get("parent"), manager: t.get("manager"), location: t.get("location"), costcenter: t.get("costcenter"), members: members[t.getId()] ?: 0]
}
context.putPageValue("json", JsonOutput.toJson([teams: out]))
