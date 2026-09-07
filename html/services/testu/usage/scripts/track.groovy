import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.entermediadb.asset.MediaArchive
import org.openedit.Data
import java.security.MessageDigest

void reply(Map m) { context.putPageValue("json", JsonOutput.toJson(m)) }
void fail(int code, String msg) { context.getResponse().setStatus(code); reply([ok: false, error: msg]); context.setCancelActions(true) }
String md5(String s) { MessageDigest.getInstance("MD5").digest(s.bytes).encodeHex().toString() }
Set TYPES = ["open", "resume", "pause", "iris_rate"] as Set

MediaArchive archive = context.getPageValue("mediaarchive")
String userid = context.getUser()?.getId()
if (!userid) { fail(401, "not signed in"); return }
List events
try { events = new JsonSlurper().parseText(context.getRequestParameter("events") ?: "[]") as List } catch (Exception e) { fail(400, "bad events"); return }
if (events.size() > 200) { fail(400, "too many events"); return }
def searcher = archive.getSearcher("usageevent")
List tosave = []
for (Map e in events) {
  String type = e.type?.toString(); if (!(type in TYPES)) continue
  Date at = null
  try { at = Date.parse("yyyy-MM-dd'T'HH:mm:ssX", e.at?.toString()?.replaceAll(/\.\d+/, "")) } catch (Exception ex) { continue }
  String id = md5(userid + "|" + e.sessionid + "|" + type + "|" + e.at)
  if (searcher.searchById(id) != null) continue   // idempotent: a retried batch never double-counts
  Data d = searcher.createNewData(); d.setId(id)
  d.setValue("user", userid); d.setValue("datecreated", at); d.setValue("type", type)
  d.setValue("sessionid", e.sessionid?.toString() ?: ""); d.setValue("seconds", ((e.seconds ?: 0) as Number).intValue())
  ["channel", "componentsection", "entityquestion", "rating", "platform", "appversion"].each { k -> if (e[k] != null) d.setValue(k, e[k].toString()) }
  tosave << d
}
if (tosave) searcher.saveAllData(tosave, null)
reply([ok: true, saved: tosave.size()])
