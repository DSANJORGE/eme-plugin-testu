import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.entermediadb.asset.MediaArchive
import org.openedit.Data
import java.security.MessageDigest

void reply(Map m) { context.putPageValue("json", JsonOutput.toJson(m)) }
void fail(int code, String msg) { context.getResponse().setStatus(code); reply([ok: false, error: msg]); context.setCancelActions(true) }
String md5(String s) { MessageDigest.getInstance("MD5").digest(s.bytes).encodeHex().toString() }
int seconds(def e) {
  def s = e.seconds
  if (s instanceof Number) return s.intValue()
  if (s == null) return 0
  try { return Double.parseDouble(s.toString()).intValue() } catch (Exception ex) { return 0 }
}
Set TYPES = ["open", "resume", "pause", "iris_rate"] as Set

MediaArchive archive = context.getPageValue("mediaarchive")
String userid = context.getUser()?.getId()
if (!userid) { fail(401, "not signed in"); return }
def parsed
try { parsed = new JsonSlurper().parseText(context.getRequestParameter("events") ?: "[]") } catch (Exception e) { fail(400, "bad events"); return }
if (!(parsed instanceof List)) { fail(400, "bad events"); return }
List events = parsed
if (events.size() > 200) { fail(400, "too many events"); return }
def searcher = archive.getSearcher("usageevent")
List tosave = []
Set seen = [] as Set
for (def e in events) {
  if (!(e instanceof Map)) continue   // unknown shapes are skipped, never fail the whole batch
  String type = e.type?.toString(); if (!(type in TYPES)) continue
  Date at = null
  try { at = Date.parse("yyyy-MM-dd'T'HH:mm:ssX", e.at?.toString()?.replaceAll(/\.\d+/, "")) } catch (Exception ex) { continue }
  String id = md5(userid + "|" + e.sessionid + "|" + type + "|" + e.at)
  if (!seen.add(id)) continue   // two byte-identical events in one batch share the md5 id: one row, one count
  if (searcher.searchById(id) != null) continue   // idempotent: a retried batch never double-counts
  Data d = searcher.createNewData(); d.setId(id)
  d.setValue("user", userid); d.setValue("datecreated", at); d.setValue("type", type)
  d.setValue("sessionid", e.sessionid?.toString() ?: ""); d.setValue("seconds", seconds(e))
  ["channel", "componentsection", "entityquestion", "rating", "platform", "appversion"].each { k -> if (e[k] != null) d.setValue(k, e[k].toString()) }
  tosave << d
}
if (tosave) searcher.saveAllData(tosave, null)
reply([ok: true, saved: tosave.size()])
