import groovy.json.JsonOutput
import org.entermediadb.asset.MediaArchive
import org.openedit.Data

void reply(Map m) { context.putPageValue("json", JsonOutput.toJson(m)) }
void fail(int code, String msg) { context.getResponse().setStatus(code); reply([ok: false, error: msg]); context.setCancelActions(true) }

MediaArchive archive = context.getPageValue("mediaarchive")
String userid = context.getUser()?.getId()
if (!userid) { fail(401, "not signed in"); return }
// Newest 50 of mine. ponytail: no paging; a learner with 50 unread has a bigger problem than a missing "load more".
List rows = archive.query("learnernotification").exact("user", userid).sort("datecreatedDown").hitsPerPage(50).search().getPageOfHits()
int unread = archive.query("learnernotification").exact("user", userid).exact("read", false).search().size()
reply([ok: true, unread: unread, notifications: rows.collect { Data n -> [
  id: n.getId(), type: n.get("type"), actor: n.get("actor"), actorname: n.get("actorname"), text: n.get("text"),
  channel: n.get("channel"), messageid: n.get("messageid"),
  entitytutorial: n.get("entitytutorial"), entitytopic: n.get("entitytopic"), entityquestion: n.get("entityquestion"),
  read: "true".equals(String.valueOf(n.get("read"))),
  date: n.getDate("datecreated")?.format("yyyy-MM-dd'T'HH:mm:ssX", TimeZone.getTimeZone("UTC"))
] }])
