import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.entermediadb.asset.MediaArchive
import org.openedit.Data
// IRIS analyst: turns the shared analytics model into a fact sheet, asks the LLM with it, then
// verifies every citation server-side. The model never sees the text of a question a learner asked.
void reply(Map m) { context.putPageValue("json", JsonOutput.toJson(m)) }
void fail(int code, String msg) { context.getResponse().setStatus(code); reply([ok: false, error: msg]); context.setCancelActions(true) }
void audit(MediaArchive archive, String action, String targettype, String targetid, Object before, Object after) {
  def s = archive.getSearcher("auditevent")
  Data e = s.createNewData() // ponytail: no setId - BaseElasticSearcher.nextId() always throws ("Should not call next ID"); saveData below auto-assigns an ES id when none is set
  e.setValue("datecreated", new Date())
  e.setValue("actor", context.getUser()?.getId())
  e.setValue("action", action)
  e.setValue("targettype", targettype)
  e.setValue("targetid", targetid)
  e.setValue("before", before == null ? "" : JsonOutput.toJson(before))
  e.setValue("after", after == null ? "" : JsonOutput.toJson(after))
  s.saveData(e, context.getUser())
}
String pct(Number x) { x == null ? "—" : Math.round(x * 100) + " %" }

MediaArchive archive = context.getPageValue("mediaarchive")
Map a = context.getPageValue("analytics"); if (a == null) return
String period = context.getRequestParameter("period") ?: ""
String topic = context.getRequestParameter("entitytopic") ?: ""
String team = context.getRequestParameter("team") ?: ""
// A citation links back to the window aggregate.groovy actually used: from/to with the inclusive end,
// exactly as overview.json echoes it. `period` is only a console shorthand -- pass it through when the
// caller sent one, never default it, or every fact claims "30d" while the data came from from/to.
Map base = [from: a.day(a.from), to: a.day(a.to - 1)] + (period ? [period: period] : [:]) + [entitytopic: topic, team: team]
// ponytail: f and add are closures, not script methods -- a script method runs in its own scope and
// cannot see the typed script locals (base, facts) it needs. Same rule as aggregate.groovy.
def f = { String view, Map extra = [:] -> base + extra }

// ---- Fact sheet: every number the model may use, each with the view it comes from.
List facts = []; int i = 0
def add = { String label, Object value, String view, Map filters = [:] -> facts << [id: "f" + (++i), label: label, value: value, view: view, filters: f(view, filters)] }
add("Personas en el alcance", a.cohort.total, "overview"); add("Personas que han respondido alguna vez", a.cohort.activated, "overview")
add("Personas activas en los últimos 7 días", a.cohort.active7d, "overview"); add("Personas activas en los últimos 30 días", a.cohort.active30d, "overview")
add("Activas 7 días en el periodo anterior", a.previous.active7d, "overview"); add("Respuestas en el periodo", a.series.sum { it.answers }, "overview"); add("Respuestas en el periodo anterior", a.previous.answers, "overview")
add("Minutos en la app en el periodo", a.series.sum { it.minutes }, "overview"); add("Conceptos erróneos (respuestas seguras y erróneas) en el periodo", a.series.sum { it.certainwrong }, "overview")
add("Personas por nivel (Sin empezar / Principiante / Competente / Experto)", a.levels, "overview")
add("Calibración de confianza: consolidado, frágil, lagunas conocidas, concepto erróneo", a.calibration, "overview")
a.topicStats.each { t -> add("Tema «${t.name}»: personas por nivel", t.levels, "overview", [entitytopic: t.id]); if (t.weakest) add("Tema «${t.name}»: subtema más débil", "${t.weakest.name} (Principiante en ${t.weakest.beginners} personas)", "mastery", [entitytopic: t.id]) }
a.sectionStats.sort(false) { -it.beginners }.take(15).each { s -> add("Subtema «${s.name}» (${s.topic}): personas por nivel, preguntas al tutor, conceptos erróneos", [levels: s.levels, questions: s.questions, misconceptions: s.misconceptions], "mastery", [entitytopic: s.topicId]) }
a.gaps.eachWithIndex { g, k -> add("Brecha ${k + 1}: «${g.name}» (${g.topic})", "Principiante en ${g.beginners} de ${g.people}; ${g.questions} preguntas al tutor, ${g.unanswered} sin respuesta; ${g.misconceptions} conceptos erróneos", "overview") }
a.teamStats.each { t -> add("Equipo «${t.name ?: 'Sin equipo'}»: personas, han empezado, activas 7 días, niveles, tema más débil", [members: t.members, activated: t.activated, active7d: t.active7d, levels: t.levels, weakest: t.weakest], "team", [team: t.id]) }
if (a.median) add("Mediana de la organización: cuota de activas 7 días y de expertos (anónima)", [active: pct(a.median.activeShare), expert: pct(a.median.expertShare)], "overview")
a.inactive.take(20).each { p -> add("Sin actividad: ${p.name} (${a.allteams[p.team]?.getName() ?: 'sin equipo'})", p.lastactivity ? "última actividad ${p.lastactivity.take(10)}" : "nunca ha respondido", "person", [user: p.user]) }
a.users.values().collect { u -> [u: u, p: a.perUser[u.getId()]] }.findAll { it.p && (it.p.attempts >= 10 && a.levelByUser[it.u.getId()] == "beginner" || it.p.attempts > 0 && it.p.cw / (double) it.p.attempts >= 0.3) }.take(15).each { x ->
  add("En riesgo: ${a.nameOf(x.u)}", "nivel ${a.levelByUser[x.u.getId()] ?: 'sin empezar'}, ${x.p.cw} conceptos erróneos en ${x.p.attempts} intentos", "person", [user: x.u.getId()]) }
add("Preguntas al tutor en el periodo: total, personas, con fuente, valoradas, útiles", [questions: a.iris.questions, people: a.iris.people, cited: pct(a.iris.citedShare), rated: pct(a.iris.ratedShare), helpful: pct(a.iris.helpfulShare)], "activity")
a.iris.themes.each { t -> add("Preguntas al tutor de tipo «${t.theme}»", t.count, "activity") }
a.iris.sections.take(10).each { s -> add("Preguntas al tutor sobre «${s.name}»", s.questions, "activity") }
a.iris.labels.take(20).each { l -> add("Sobre qué preguntan (etiqueta): «${l.label}»", l.count, "activity") }
String sel = (context.getRequestParameter("user") ?: "").toLowerCase()
if (sel && a.users[sel]) { Data u = a.users[sel]; def p = a.perUser[sel]
  add("Persona seleccionada: ${a.nameOf(u)}", [team: a.allteams[u.get("team")]?.getName(), level: a.levelByUser[sel], answered: p?.answered ?: 0, mastered: p?.mastered ?: 0, misconceptions: p?.cw ?: 0, lastactivity: p?.last?.format("yyyy-MM-dd")], "person", [user: sel])
  a.perUserTopic[sel]?.each { tid, pt -> add("${a.nameOf(u)} en «${a.topics[tid]}»", "${a.levelOf(pt.mastered, pt.answered) ?: 'sin empezar'}, ${pt.mastered} de ${pt.answered} dominadas", "person", [user: sel, entitytopic: tid]) }
  List qs = a.tq.findAll { it.get("user") == sel }; add("${a.nameOf(u)}: preguntas al tutor en el periodo", qs.size(), "person", [user: sel]) }
if (context.getRequestParameter("debug") == "facts") { reply([ok: true, facts: facts]); return }

// ---- LLM
String question = (context.getRequestParameter("question") ?: "").trim().take(500)
if (!question) { fail(400, "no question"); return }
def persona = archive.getData("tutorpersona", archive.getCatalogSettingValue("tutorpersona") ?: "iris")
List history = []
try { history = (new JsonSlurper().parseText(context.getRequestParameter("history") ?: "[]") as List).takeRight(6) } catch (Exception e) {}
def ctx = new org.entermediadb.ai.llm.BaseAgentContext()
ctx.putContextValue("personaname", persona?.getName() ?: "Iris"); ctx.putContextValue("organization", persona?.get("organization") ?: "")
ctx.putContextValue("language", persona?.get("tutorlanguage") ?: "es"); ctx.putContextValue("screen", context.getRequestParameter("screen") ?: "overview")
ctx.putContextValue("facts", JsonOutput.toJson(facts.collect { [id: it.id, label: it.label, value: it.value] })); ctx.putContextValue("history", JsonOutput.toJson(history)); ctx.putContextValue("question", question)
Map out
try { out = archive.getLlmConnection("thinking").callStructure(ctx, "analytics_ask").getMessageStructured() as Map } catch (Exception e) { log.error("analytics ask failed", e); fail(503, "llm"); return }
if (out == null) { fail(503, "llm"); return }
Map byId = facts.collectEntries { [(it.id): it] }
List cited = ((out.citations ?: []) as List).collect { it.toString() }.findAll { byId.containsKey(it) }.unique()
String answer = (out.answer ?: "").toString()
// Markers the model left in the text but forgot to list still count; unknown ids are stripped from the text.
(answer =~ /\[(f\d+)\]/).each { m, id -> if (byId.containsKey(id) && !(id in cited)) cited << id }
answer = answer.replaceAll(/ ?\[(f\d+)\]/) { m, id -> byId.containsKey(id) ? m : "" }   // the space goes with the marker, so stripping one leaves no orphaned gap before the punctuation
audit(archive, "analytics.ask", "analytics", context.getRequestParameter("screen") ?: "", [question: question], [citations: cited, ok: true])
reply([ok: true, answer: answer, citations: cited.collect { byId[it] }, followups: ((out.followups ?: []) as List).take(3), model: "thinking"])
