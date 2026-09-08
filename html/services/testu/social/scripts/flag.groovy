import groovy.json.JsonOutput
import org.entermediadb.asset.MediaArchive
import org.openedit.Data

void reply(Map m) { context.putPageValue("json", JsonOutput.toJson(m)) }
void fail(int code, String msg) { context.getResponse().setStatus(code); reply([ok: false, error: msg]); context.setCancelActions(true) }
// Mirrors data/lists/questionflagreason.xml; the app sends the id, never the label.
Set REASONS = ["wrong", "unclear", "outdated", "other"] as Set

MediaArchive archive = context.getPageValue("mediaarchive")
String userid = context.getUser()?.getId()
if (!userid) { fail(401, "not signed in"); return }
String question = context.getRequestParameter("entityquestion") ?: ""
if (!question) { fail(400, "entityquestion required"); return }
String reason = context.getRequestParameter("reason") ?: ""
if (!(reason in REASONS)) { fail(400, "bad reason"); return }
if (archive.getData("entityquestion", question) == null) { fail(404, "no such question"); return }
String note = (context.getRequestParameter("note") ?: "").trim()
if (note.length() > 1000) note = note.substring(0, 1000)
String tutorial = context.getRequestParameter("entitytutorial") ?: ""

def searcher = archive.getSearcher("questionflag")
Data d = searcher.createNewData()
d.setValue("user", userid); d.setValue("datecreated", new Date())
d.setValue("entityquestion", question); if (tutorial) d.setValue("entitytutorial", tutorial)
d.setValue("reason", reason); d.setValue("note", note); d.setValue("status", "open")
archive.saveData("questionflag", d)
reply([ok: true, id: d.getId()])
