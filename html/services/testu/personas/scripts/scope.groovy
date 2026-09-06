import org.entermediadb.asset.MediaArchive
import org.openedit.Data
import org.openedit.profile.UserProfile
MediaArchive archive = context.getPageValue("mediaarchive")
UserProfile profile = context.getUserProfile()
// "all" is granted on any manage/operate permission of either domain -- fine under the four-role model
// (orgadmin/training/manager/users), where personas_* and analytics_* always travel together per role;
// a future split-permission role (e.g. analytics-only operate without personas) would need a per-domain check.
boolean all = profile != null && (profile.hasPermission("personas_manage") || profile.hasPermission("personas_operate") || profile.hasPermission("analytics_manage") || profile.hasPermission("analytics_operate"))
Map teams = [:]
for (Data t in archive.query("team").all().search()) teams[t.getId()] = t
context.putPageValue("allteams", teams)
if (all) { context.putPageValue("scopeteams", null); return }
String me = context.getUser().getId()
Set scope = teams.values().findAll { it.get("manager") == me }.collect { it.getId() } as Set
// ponytail: grew-loop is bounded by teams.size() -- scope only ever grows and never
// shrinks, and a team can only be added once, so it terminates in at most
// teams.size() passes even if `parent` contains a cycle (a cycle just stops
// growing scope once every team in the cycle is already included).
boolean grew = true
while (grew) {
  grew = false
  for (Data t in teams.values()) { if (t.get("parent") in scope && !(t.getId() in scope)) { scope << t.getId(); grew = true } }
}
context.putPageValue("scopeteams", scope)
