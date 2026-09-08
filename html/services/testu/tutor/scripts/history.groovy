import groovy.json.JsonOutput
import org.entermediadb.asset.MediaArchive
import org.openedit.Data
import org.openedit.MultiValued
// IRIS history for the learner app: the learner's follow-ups and the tutor's replies on one of
// the caller's own tutor channels, oldest first, last `limit` (50). The stock tutorhistory.json
// hides the follow-ups -- the app posts them as `system` rows with the text only in
// agentcontextvalues.query (AgentModule / AssistantManager.sendSystemMessage) -- so phone and
// web could not show the same conversation without this page.
void reply(Map m) { context.putPageValue("json", JsonOutput.toJson(m)) }
void fail(int code, String msg) { context.getResponse().setStatus(code); reply([ok: false, error: msg]); context.setCancelActions(true) }

MediaArchive archive = context.getPageValue("mediaarchive")
String userid = context.getUser()?.getId()
if (!userid) { fail(401, "not signed in"); return }
String channelid = context.getRequestParameter("channel") ?: ""
Data channel = channelid ? archive.getData("channel", channelid) : null
if (channel == null) { fail(404, "no channel"); return }
if (!userid.equals(channel.get("user"))) { fail(403, "not your channel"); return }
int limit = 50
try { limit = Math.max(1, Math.min(200, Integer.parseInt(context.getRequestParameter("limit") ?: "50"))) } catch (Exception e) {}

List turns = []
// ponytail: functionname is indextype="not_analyzed" on chatterbox (see computemastery.groovy's
// own .exact("functionname", "chat_tutor_usercomment")), so filtering both fields in the query
// is safe and cheaper than pulling the whole channel; a channel holds a few hundred rows at most.
// Newest first, capped at limit*2 rows (one system row + one answer row per turn is enough for
// `limit` turns), then reversed below to the oldest-first response order.
for (MultiValued m in archive.query("chatterbox").exact("channel", channelid).exact("functionname", "chat_tutor_usercomment").sort("dateDown").hitsPerPage(limit * 2).search()) {
  if ("system".equals(m.get("messagetype"))) {
    def q = null
    try { q = m.getJSONValue("agentcontextvalues")?.get("query") } catch (Exception e) { }
    if (q) turns << [id: m.getId(), from: "user", text: q.toString(), date: m.get("date")]
  } else if ("agent".equals(m.get("user"))) {
    String text = m.get("message") ?: ""
    if (text.trim()) turns << [id: m.getId(), from: "tutor", text: text, date: m.get("date")]
  }
}
List ordered = turns.reverse()
reply([ok: true, turns: ordered.size() > limit ? ordered[-limit..-1] : ordered])
