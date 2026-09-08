import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.entermediadb.asset.MediaArchive
import org.openedit.Data

void reply(Map m) { context.putPageValue("json", JsonOutput.toJson(m)) }
void fail(int code, String msg) { context.getResponse().setStatus(code); reply([ok: false, error: msg]); context.setCancelActions(true) }

MediaArchive archive = context.getPageValue("mediaarchive")
String userid = context.getUser()?.getId()
if (!userid) { fail(401, "not signed in"); return }
def searcher = archive.getSearcher("learnernotification")
List rows
if ("true".equals(context.getRequestParameter("all"))) {
  def hits = archive.query("learnernotification").exact("user", userid).exact("read", false).search()
  hits.enableBulkOperations()
  rows = hits.collect { it }
} else {
  List ids
  try { ids = new JsonSlurper().parseText(context.getRequestParameter("ids") ?: "[]") as List } catch (Exception e) { fail(400, "bad ids"); return }
  if (ids.size() > 200) { fail(400, "too many ids"); return }
  // Only my own rows flip: an id guessed from someone else's feed is ignored, not an error.
  rows = ids.collect { searcher.searchById(it.toString()) }.findAll { it != null && userid.equals(it.get("user")) && !"true".equals(String.valueOf(it.get("read"))) }
}
rows.each { Data n -> n.setValue("read", true) }
if (rows) searcher.saveAllData(rows, null)
reply([ok: true, marked: rows.size()])
