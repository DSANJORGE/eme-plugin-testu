import groovy.json.JsonOutput
import org.entermediadb.asset.MediaArchive
import org.openedit.Data
import java.security.MessageDigest

void reply(Map m) { context.putPageValue("json", JsonOutput.toJson(m)) }
void fail(int code, String msg) { context.getResponse().setStatus(code); reply([ok: false, error: msg]); context.setCancelActions(true) }
// The app's TestuReaction enum, by name.
Set NAMES = ["like", "applause", "support", "love", "idea", "laugh"] as Set

MediaArchive archive = context.getPageValue("mediaarchive")
String me = context.getUser()?.getId()
if (!me) { fail(401, "not signed in"); return }
String messageid = context.getRequestParameter("messageid") ?: ""
String name = context.getRequestParameter("name") ?: ""
if (name && !(name in NAMES)) { fail(400, "bad reaction"); return }
Data msg = archive.getSearcher("chatterbox").searchById(messageid)
if (msg == null || msg.get("functionname") != "testu_social") { fail(404, "no such comment"); return }

// ChatModule.toggleReaction, in groovy: one row per user per message; the same name again removes it.
// An empty name is an explicit clear (the app knows the new state, so it never has to send the old name).
def reactions = archive.getSearcher("chatterboxreaction")
Data found = archive.query("chatterboxreaction").exact("messageid", messageid).exact("user", me).searchOne()
String mine = null
if (!name || (found != null && found.get("name") == name)) {
  if (found != null) reactions.delete(found, context.getUser())
} else {
  if (found == null) { found = reactions.createNewData(); found.setValue("messageid", messageid); found.setValue("user", me) }
  found.setValue("date", new Date()); found.setValue("name", name)
  archive.saveData("chatterboxreaction", found)
  mine = name
}
Map counts = [:]
for (Data r in archive.query("chatterboxreaction").exact("messageid", messageid).search()) counts[r.get("name")] = (counts[r.get("name")] ?: 0) + 1
String author = msg.get("user")

// Part D: notifications are created here.
//   me         actor user id
//   author     the comment's author user id -> "reaction" recipient, skip when == me
//   messageid  the comment reacted to; channel = msg.get("channel"); msg.get("moduleid") / msg.get("entityid")
//   mine       the reaction now standing (null = removed). One notification row per actor per comment:
//              update it in place when mine changes, delete it when mine is null, never add a second.
// ---- Part D: notifications. Same `notify` lives in comment.groovy: eMe page scripts cannot import each other.
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
String md5(String s) { MessageDigest.getInstance("MD5").digest(s.bytes).encodeHex().toString() }
// One notification per actor+comment: a change of reaction rewrites it, removing the reaction removes it.
// Part C leaves `msg` (the comment), `author`, `me` and `mine` (null = reaction removed) in scope.
String nid = md5(me + "|" + messageid + "|reaction")
def ns = archive.getSearcher("learnernotification")
if (mine == null) { Data old = ns.searchById(nid); if (old != null) ns.delete(old, context.getUser()) }
else notify(archive, author, me, "reaction", msg, nid)
reply([ok: true, mine: mine, reacts: counts])
