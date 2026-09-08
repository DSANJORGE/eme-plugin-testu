import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.entermediadb.asset.MediaArchive
import org.openedit.Data

void reply(Map m) { context.putPageValue("json", JsonOutput.toJson(m)) }
void fail(int code, String msg) { context.getResponse().setStatus(code); reply([ok: false, error: msg]); context.setCancelActions(true) }

MediaArchive archive = context.getPageValue("mediaarchive")
def who = context.getUser()
String me = who?.getId()
if (!me) { fail(401, "not signed in"); return }
String channel = context.getRequestParameter("channel") ?: ""
if (!(channel ==~ /[qt]-[A-Za-z0-9_\-]+/)) { fail(400, "bad channel"); return }
// The channel's entity must exist, same check flag.groovy does for entityquestion.
String entityid = channel.substring(2)
String entitytype = channel.startsWith("q-") ? "entityquestion" : "entitytutorial"
if (archive.getData(entitytype, entityid) == null) { fail(404, "no such " + (entitytype == "entityquestion" ? "question" : "tutorial")); return }
String message = (context.getRequestParameter("message") ?: "").trim()
if (!message) { fail(400, "empty message"); return }
if (message.length() > 2000) { fail(400, "message too long"); return }

def chats = archive.getSearcher("chatterbox")
// Replies nest one level (the app's LinkedIn rule): a reply to a reply hangs off the same parent.
String replytoid = context.getRequestParameter("replytoid") ?: ""
Data parent = null
String parentauthor = null
if (replytoid) {
  parent = chats.searchById(replytoid)
  if (parent == null || parent.get("channel") != channel) { fail(400, "bad replytoid"); return }
  // parentauthor is read here, before the one-level re-point below, so it names whoever the
  // learner actually addressed (the comment they replied to), not the thread root.
  parentauthor = parent.get("user")
  if (parent.get("replytoid")) { replytoid = parent.get("replytoid"); parent = chats.searchById(replytoid) ?: parent }
}
// The app sends the ids it picked from mentionables.json; nothing is parsed out of the text.
List mentions = []
try {
  def m = new JsonSlurper().parseText(context.getRequestParameter("mentions") ?: "[]")
  if (!(m instanceof List)) { fail(400, "bad mentions"); return }
  mentions = m.collect { it.toString() }.findAll { it && it != me }.unique()
} catch (Exception e) { fail(400, "bad mentions"); return }
def um = archive.getUserManager()
// Same scope mentionables.groovy offers to pick from: the actor's team, or
// staff who can answer from the console.
Set STAFF = ["manager", "training", "orgadmin"] as Set
String myteam = who.get("team") ?: ""
mentions = mentions.findAll { String uid ->
  def u = um.getUser(uid)
  if (u == null) return false
  String role = archive.getSearcher("userprofile").searchById(uid)?.get("settingsgroup") ?: "users"
  boolean teammate = myteam && u.get("team") == myteam
  teammate || role in STAFF
}.take(20)

Data d = chats.createNewData()
d.setValue("channel", channel); d.setValue("user", me); d.setValue("date", new Date())
d.setValue("message", message); d.setValue("messageplain", message)
// The marker every social query keys on; keeps computemastery's chat_tutor_usercomment pass clear of these rows.
d.setValue("functionname", "testu_social")
d.setValue("moduleid", entitytype); d.setValue("entityid", entityid)
if (replytoid) d.setValue("replytoid", replytoid)
archive.saveData("chatterbox", d)
String messageid = d.getId()

// Part D: notifications are created here.
//   me            actor user id
//   messageid     id of the chatterbox row just saved
//   channel       "q-<entityquestion>" | "t-<entitytutorial>"; d.get("moduleid") / d.get("entityid") split it
//   replytoid     storage parent: the top-level comment id, "" when this comment is itself top level
//   parentauthor  the author of the comment actually addressed (a reply's author when replying to a
//                 reply; null when top level) -> "reply" recipient, skip when == me
//   mentions      List<String> of user ids: de-duplicated, self removed, verified to exist -> "mention" recipients
//   message       the text, for the one-line preview
// ---- Part D: notifications. Same `notify` lives in react.groovy: eMe page scripts cannot import each other.
void notify(MediaArchive archive, String recipient, String actor, String type, Data msg, String id = null) {
  if (!recipient || recipient == actor) return   // never notify yourself
  def s = archive.getSearcher("learnernotification")
  Data n = id == null ? null : s.searchById(id)
  if (n == null) { n = s.createNewData(); if (id) n.setId(id) }   // reactions upsert on a fixed id; replies/mentions get an ES id
  String channel = msg.get("channel")?.toString() ?: ""
  String qid = channel.startsWith("q-") ? channel.substring(2) : ""
  String tid = channel.startsWith("t-") ? channel.substring(2) : ""
  // componentsection stores its tutorial under playbackentityid, not entityid: matches
  // computemastery.groovy/report.groovy/aggregate.groovy's .exact("playbackentitymoduleid", "entitytutorial").
  if (qid) { def cc = archive.query("componentcontent").exact("questionid", qid).searchOne(); tid = cc ? (archive.getData("componentsection", cc.get("componentsectionid"))?.get("playbackentityid") ?: "") : "" }
  def a = archive.getSearcher("user").searchById(actor)
  n.setValue("user", recipient); n.setValue("actor", actor); n.setValue("type", type)
  n.setValue("datecreated", new Date()); n.setValue("read", false)
  n.setValue("actorname", [a?.get("firstName"), a?.get("lastName")].findAll { it }.join(" ") ?: actor)
  n.setValue("text", (msg.get("message") ?: "").toString().replaceAll(/<[^>]*>/, "").replaceAll(/\s+/, " ").trim().take(120))
  n.setValue("channel", channel); n.setValue("messageid", msg.getId())
  n.setValue("entityquestion", qid); n.setValue("entitytutorial", tid)
  n.setValue("entitytopic", tid ? (archive.getData("entitytutorial", tid)?.get("entitytopic") ?: "") : "")
  s.saveData(n, null)
}
// Part C already computed `parentauthor` (null when top level) and `mentions` (self removed, verified).
notify(archive, parentauthor, me, "reply", d)
mentions.each { String mid -> if (mid != parentauthor) notify(archive, mid, me, "mention", d) }
reply([ok: true, id: messageid, replytoid: replytoid ?: null, mentions: mentions])
