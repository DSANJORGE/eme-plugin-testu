import groovy.json.JsonOutput
import org.entermediadb.asset.MediaArchive
import org.openedit.Data
import org.openedit.hittracker.HitTracker

void reply(Map m) { context.putPageValue("json", JsonOutput.toJson(m)) }
void fail(int code, String msg) { context.getResponse().setStatus(code); reply([ok: false, error: msg]); context.setCancelActions(true) }

MediaArchive archive = context.getPageValue("mediaarchive")
String me = context.getUser()?.getId()
if (!me) { fail(401, "not signed in"); return }

// Author lookups, memoised per request. Name from the user record (users.groovy reads the same two fields),
// role from userprofile.settingsgroup (person.groovy's role source).
Map users = [:], roles = [:]
def userOf = { String uid -> if (!users.containsKey(uid)) users[uid] = archive.getUserManager().getUser(uid); users[uid] }
def nameOf = { String uid -> def u = userOf(uid); String n = u == null ? "" : "${u.get('firstName') ?: ''} ${u.get('lastName') ?: ''}".trim(); n ?: uid }
def roleOf = { String uid -> if (!roles.containsKey(uid)) roles[uid] = archive.getSearcher("userprofile").searchById(uid)?.get("settingsgroup") ?: "users"; roles[uid] }
def iso = { Date d -> d?.format("yyyy-MM-dd'T'HH:mm:ssXXX") }
def row = { Data m -> String uid = m.get("user")
  return [id: m.getId(), userId: uid, name: nameOf(uid), role: roleOf(uid), date: iso(m.getDate("date")), text: m.get("message") ?: ""] }

String channel = context.getRequestParameter("channel") ?: ""
if (channel) {
  if (!(channel ==~ /[qt]-[A-Za-z0-9_\-]+/)) { fail(400, "bad channel"); return }
  HitTracker rows = archive.query("chatterbox").exact("channel", channel).exact("functionname", "testu_social").sort("dateUp").search()
  rows.enableBulkOperations()
  List ids = rows.collect { it.getId() }
  Map reacts = [:], mine = [:]   // messageid -> [name: count], messageid -> my reaction name (ChatModule.loadReactions, grouped)
  if (ids) for (Data r in archive.query("chatterboxreaction").orgroup("messageid", ids).search()) {
    String mid = r.get("messageid"); String name = r.get("name")
    Map c = reacts.get(mid); if (c == null) { c = [:]; reacts[mid] = c }
    c[name] = (c[name] ?: 0) + 1
    if (r.get("user") == me) mine[mid] = name
  }
  Map byId = [:]; List top = []
  for (Data m in rows) {
    Map c = row(m) + [reacts: reacts[m.getId()] ?: [:], mine: mine[m.getId()], replies: []]
    byId[m.getId()] = c
    // dateUp: a parent always precedes its replies. comment.groovy pins replies to a top-level parent,
    // so an orphan (parent deleted) simply lists at the top rather than vanishing.
    Map parent = m.get("replytoid") ? byId[m.get("replytoid")] : null
    (parent == null ? top : parent.replies) << c
  }
  reply([ok: true, channel: channel, comments: top]); return
}

// No channel: the console's cross-thread listing, scoped like users.json. scope.groovy ran first
// (xconf keeps <user/> so the channel branch above still serves learners); this branch is console-only.
if (context.getUserProfile()?.hasPermission("personas_view") != true) { fail(403, "not allowed"); return }
Set scope = context.getPageValue("scopeteams")
def inScope = { String uid -> scope == null || (userOf(uid)?.get("team") in scope) }
def labelOf = { String moduleid, String entityid ->
  if (!moduleid || !entityid) return entityid ?: ""
  Data d = archive.getData(moduleid, entityid)
  if (d == null) return entityid
  return (moduleid == "entityquestion" ? (d.get("question") ?: d.getName()) : d.getName()) ?: entityid }
// ponytail: newest 50 kept, query page already bounded by the sort+iteration below -- one request per console open, pilot volumes; page it when a client outgrows that.
HitTracker all = archive.query("chatterbox").exact("functionname", "testu_social").sort("dateDown").search(); all.enableBulkOperations()
List recent = []
for (Data m in all) {
  if (recent.size() >= 50) break
  if (!inScope(m.get("user"))) continue
  recent << row(m) + [channel: m.get("channel"), moduleid: m.get("moduleid"), entityid: m.get("entityid"), label: labelOf(m.get("moduleid"), m.get("entityid")), replytoid: m.get("replytoid")]
}
List flags = []
HitTracker fl = archive.query("questionflag").exact("status", "open").sort("datecreatedDown").search(); fl.enableBulkOperations()
for (Data f in fl) {
  if (flags.size() >= 50) break
  String uid = f.get("user"); if (!inScope(uid)) continue
  flags << [id: f.getId(), entityquestion: f.get("entityquestion"), entitytutorial: f.get("entitytutorial"), label: labelOf("entityquestion", f.get("entityquestion")),
            reason: f.get("reason"), note: f.get("note") ?: "", userId: uid, name: nameOf(uid), date: iso(f.getDate("datecreated"))]
}
reply([ok: true, recent: recent, flags: flags])
