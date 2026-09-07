# Analytics v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the v0 console into a client-facing analytics product for the Minsur pilot: usage data from the app, daily rollups on the server, a premium Flutter web console with charts, and an IRIS side panel that answers from cited, role-scoped facts.

**Architecture:** The plugin's 15-minute event writes `tutormastery` (+ calibration), `tutordaily` (user × day) and `tutorquestion` (learner questions to the tutor, classified by the LLM); one shared `aggregate.groovy` builds an in-scope model per request that `overview`, `activity`, `person` and `ask` shape into JSON. The app (build 1.1.1+6) records answers for every user and posts foreground-time and rating events. The console is rebuilt on a small component system over the app's tokens with `fl_chart`, and an IRIS panel that renders server-verified citations.

**Tech Stack:** eMe plugin (Groovy scripts, `.xconf` pages, field/list XML, Velocity JSON templates), Elasticsearch behind `archive.query`, Flutter 3.44 web (`main_admin.dart`), `fl_chart ^1.2.0`, `eme_app_package` (`EmeHttp`, `FakeEmeHttp`), Python 3 tools.

**Spec:** Traycer artifact `epics/016e93d6-be42-47c3-ba0a-e36434a5132e/artifacts/analytics-v1/index.md` (file: `/Users/DSANJORGE/.traycer/epics/016e93d6-be42-47c3-ba0a-e36434a5132e/artifacts/analytics-v1/index.md`). Sections are cited as §n.

## Global Constraints

- No new Java. No edits under the server's `plugins/*`. Everything server-side is Groovy, XML, `.xconf` and Velocity inside `eme-plugin-testu`.
- Never create accounts, seed data or run tests against `minsur.genailabs.tech`. Local server only: `http://localhost:8080/site/mediadb`, Elasticsearch at `http://localhost:9200`, index `site_catalog`.
- Never run destructive reindex/clearIndex on `settingsgroup`/`permissionsapp`. `deploy.sh` never deletes server files.
- Never commit the `objectVersion = 60 → 54` downgrade in `ios/Runner.xcodeproj/project.pbxproj` (`git checkout -- ios/Runner.xcodeproj/project.pbxproj` if it shows up).
- Commit locally on branch `analytics-v1` in each repo; never push, never open PRs (the user asks for that).
- Every user-visible console string is `L('English', 'Español')`; Spanish is the shipping language (Spanish runs 20–30 % longer, layouts must survive it).
- Console colours come from `TestuTokens` (`lib/testu/testu_theme.dart`) and the new `AdminTokens`; no `DataTable`, `DropdownButton`, `NavigationRail`, `SnackBar` or `CircularProgressIndicator` remains under `lib/admin/` when the plan is done.
- Only new Dart dependency: `fl_chart: ^1.2.0`.
- Levels are counted per person, never per row (spec §3). Medians appear only when the organisation has ≥ 5 people. No read endpoint ever returns a learner's question text (`tutorquestion.query`) (spec §6.8).
- The app file `lib/testu/testu_session.dart` changes only inside `_VerdictExtrasState` (the reaction handler).
- Motion: 200 ms state crossfades, 300 ms chart draw on first data only, `TestuTokens.curve`, zero durations under `MediaQuery.disableAnimations`.
- Accessibility: body/table text ≥ 4.5:1 (`ink`/`mut` on `bg`/`card`); `faint` only on mono eyebrow labels; every chart value also exists as text.

## Environment facts the tasks rely on

- Local server root: `/Users/DSANJORGE/Code/eme-server-minsur` (symlinked into the workspace as `eme-server-minsur`). Deploy the plugin with `./deploy.sh` (defaults to that root). Groovy scripts and `.xconf` pages take effect without restart; **new field XMLs and list XMLs need a Tomcat restart**.
- Restart procedure (Task 0 records the exact pid): kill the `java -Dappname=eme-server-minsur … Bootstrap start` process, then from `/Users/DSANJORGE/Code/eme-server-minsur` run `JAVA_HOME=~/.sdkman/candidates/java/26-tem bin/compile.sh` and `java -Dappname=eme-server-minsur @tomcat/work/tomcat-args.txt org.apache.catalina.startup.Bootstrap start` in a Traycer shell (long-lived). Ready when `curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/site/mediadb/services/testu/personas/me.json` returns `401` or `200`.
- Admin login: `admin` / `admin` via `POST services/authentication/login.json` with JSON body `{"id":"admin","password":"admin"}` and a cookie jar. Task 0 gives `admin` the `orgadmin` group locally so every check runs as admin. Never log in as `diego` from scripts (it ends the user's browser session).
- Entities without a `module` row (e.g. `tutoranswer`, `tutordaily`) have no `services/module/<x>/search.json`; verify them through Elasticsearch: `curl -s -X POST localhost:9200/site_catalog/_search -H 'Content-Type: application/json' -d '{"query":{"term":{"_type":"tutordaily"}}}'`.
- `chatterbox` rows: the learner's question row has `functionname = chat_tutor_usercomment`, `messagetype = system`, `user = <learner>`, `agentcontextvalues` JSON with `query`, `sectionid`, `questionid`, `componentid`, `tutorialid`; the tutor's reply row has `user = agent`, `replytoid = <learner row id>`, `message` (HTML/markdown, citations as `[Title, p. N]` or `[Title, m:ss]` at the end).
- LLM: `archive.getLlmConnection("thinking")` (llamat, Qwen3-14B); `callStructure(agentContext, "name")` loads `/<mediadb>/ai/<protocol>/calls/name.json`; protocol `llama` falls back to `openai` then `default`, so plugin templates live in `html/ai/default/calls/`. Context values set with `putContextValue` are available in the template as `${name}`; JSON strings inside the template go through `#jesc("...")`.
- App test helpers: `package:eme_app_package/testing/fake_eme_http.dart` (`FakeEmeHttp` with `canned[path] = {...}`); `test/flutter_test_config.dart` pins `testuLang` to `en`, so widget tests assert English copy.

---

## Task 0: Branches, local prerequisites, ledger

**Files:** none in git (environment only).

- [ ] **Step 1: Branches**

```bash
cd "/Users/DSANJORGE/Code/EME-GenAI Labs/eme-plugin-testu" && git checkout main && git pull --ff-only 2>/dev/null; git checkout -b analytics-v1
cd "/Users/DSANJORGE/Code/EME-GenAI Labs/app-genailabs" && git checkout main && git checkout -b analytics-v1
```

- [ ] **Step 2: Make `admin` an orgadmin on the local server (reversible, local only)**

```bash
J=$(mktemp)
curl -s -c "$J" -o /dev/null http://localhost:8080/site/mediadb/services/authentication/login.json -H 'Content-Type: application/json' -d '{"id":"admin","password":"admin"}'
curl -s -b "$J" -X POST http://localhost:8080/site/mediadb/services/authentication/usersave.json -d username=admin -d field=settingsgroup -d settingsgroupvalue=orgadmin
```

Expected: JSON with `"status":"ok"`. The profile cache keeps the old role until the restart in Task 1 Step 6; do not test permissions before that.

- [ ] **Step 3: Record the server pid for restarts**

```bash
pgrep -f 'appname=eme-server-minsur' || ps -eo pid,command | grep '[B]ootstrap start'
```

Write the pid into the ledger. Restarts in later tasks: `kill <pid>`, wait for the port to close (`until ! nc -z localhost 8080; do sleep 1; done`), then start as described under "Environment facts" in a Traycer shell and wait for `me.json` to answer.

- [ ] **Step 4: Ledger** — create `.superpowers/sdd/2026-09-06-analytics-v1/progress.md` in the plugin repo via `scripts/sdd-workspace` with the first line naming this plan file, and note the pid and the admin-role change (revert at the end: `settingsgroupvalue=administrator`).

---

## Task 1: Plugin data model (fields, lists) + deploy + restart

**Files:**
- Create: `data/fields/tutordaily.xml`, `data/fields/usageevent.xml`, `data/fields/tutorquestion.xml`, `data/lists/usageeventtype.xml`, `data/lists/tutorquestiontheme.xml`
- Modify: `data/fields/tutormastery.xml` (four counters)
- Test: `tools/check_fields.sh`

**Interfaces:**
- Produces: the five entities exactly as the spec §3 table; `tutormastery.certaincorrect|certainwrong|unsurecorrect|unsurewrong`.

- [ ] **Step 1: Add the calibration counters to `data/fields/tutormastery.xml`** (before `lastactivity`):

```xml
  <property id="certaincorrect" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Correct · certain</language><language id="es">Correctas · seguro</language></name></property>
  <property id="certainwrong" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Wrong · certain</language><language id="es">Incorrectas · seguro</language></name></property>
  <property id="unsurecorrect" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Correct · unsure</language><language id="es">Correctas · inseguro</language></name></property>
  <property id="unsurewrong" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Wrong · unsure</language><language id="es">Incorrectas · inseguro</language></name></property>
```

- [ ] **Step 2: Create `data/fields/tutordaily.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Analytics v1: one row per user x calendar day, written by events/testu/computemastery. id = <user>_<yyyyMMdd>. -->
<properties beanname="dataSearcher">
  <property id="id" index="true" stored="true" editable="false" keyword="true"><name><language id="en">ID</language></name></property>
  <property id="user" index="true" stored="true" editable="false" type="list" listid="user"><name><language id="en">User</language><language id="es">Usuario</language></name></property>
  <property id="day" index="true" stored="true" editable="false" type="date"><name><language id="en">Day</language><language id="es">Día</language></name></property>
  <property id="answers" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Answers</language><language id="es">Respuestas</language></name></property>
  <property id="correct" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Correct</language><language id="es">Correctas</language></name></property>
  <property id="certainwrong" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Misconceptions</language><language id="es">Conceptos erróneos</language></name></property>
  <property id="sessions" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Sessions</language><language id="es">Sesiones</language></name></property>
  <property id="minutes" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Minutes</language><language id="es">Minutos</language></name></property>
  <property id="questions" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Questions to the tutor</language><language id="es">Preguntas al tutor</language></name></property>
  <property id="helpful" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Helpful ratings</language><language id="es">Valoraciones útiles</language></name></property>
  <property id="nothelpful" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Not helpful ratings</language><language id="es">Valoraciones no útiles</language></name></property>
  <property id="firstactivity" index="true" stored="true" editable="false" type="date"><name><language id="en">First activity</language><language id="es">Primera actividad</language></name></property>
  <property id="lastactivity" index="true" stored="true" editable="false" type="date"><name><language id="en">Last activity</language><language id="es">Última actividad</language></name></property>
  <property id="computedat" index="true" stored="true" editable="false" type="date"><name><language id="en">Computed at</language><language id="es">Calculado</language></name></property>
</properties>
```

- [ ] **Step 3: Create `data/fields/usageevent.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Analytics v1: append-only events posted by the collaborator app (services/testu/usage/track). user comes from the session, never the body. -->
<properties beanname="dataSearcher">
  <property id="id" index="true" stored="true" editable="false" keyword="true"><name><language id="en">ID</language></name></property>
  <property id="user" index="true" stored="true" editable="false" type="list" listid="user"><name><language id="en">User</language><language id="es">Usuario</language></name></property>
  <property id="datecreated" index="true" stored="true" editable="false" type="date"><name><language id="en">Date</language><language id="es">Fecha</language></name></property>
  <property id="type" index="true" stored="true" editable="false" type="list" listid="usageeventtype"><name><language id="en">Type</language><language id="es">Tipo</language></name></property>
  <property id="sessionid" index="true" stored="true" editable="false" keyword="true"><name><language id="en">Session</language><language id="es">Sesión</language></name></property>
  <property id="seconds" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Seconds</language><language id="es">Segundos</language></name></property>
  <property id="channel" index="true" stored="true" editable="false" keyword="true"><name><language id="en">Channel</language><language id="es">Canal</language></name></property>
  <property id="componentsection" index="true" stored="true" editable="false" keyword="true"><name><language id="en">Section</language><language id="es">Subtema</language></name></property>
  <property id="entityquestion" index="true" stored="true" editable="false" keyword="true"><name><language id="en">Question</language><language id="es">Pregunta</language></name></property>
  <property id="rating" index="true" stored="true" editable="false" keyword="true"><name><language id="en">Rating</language><language id="es">Valoración</language></name></property>
  <property id="platform" index="true" stored="true" editable="false" keyword="true"><name><language id="en">Platform</language><language id="es">Plataforma</language></name></property>
  <property id="appversion" index="true" stored="true" editable="false" keyword="true"><name><language id="en">App version</language><language id="es">Versión</language></name></property>
</properties>
```

- [ ] **Step 4: Create `data/fields/tutorquestion.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Analytics v1: one row per question a learner asked the tutor in the app, derived from chatterbox by events/testu/computemastery.
     `query` is stored for classification only and is never returned by services/testu/analytics/*. -->
<properties beanname="dataSearcher">
  <property id="id" index="true" stored="true" editable="false" keyword="true"><name><language id="en">ID</language></name></property>
  <property id="user" index="true" stored="true" editable="false" type="list" listid="user"><name><language id="en">User</language><language id="es">Usuario</language></name></property>
  <property id="datecreated" index="true" stored="true" editable="false" type="date"><name><language id="en">Date</language><language id="es">Fecha</language></name></property>
  <property id="channel" index="true" stored="true" editable="false" keyword="true"><name><language id="en">Channel</language><language id="es">Canal</language></name></property>
  <property id="entitytutorial" index="true" stored="true" editable="false" type="list" listid="entitytutorial"><name><language id="en">Tutorial</language></name></property>
  <property id="entitytopic" index="true" stored="true" editable="false" type="list" listid="entitytopic"><name><language id="en">Topic</language><language id="es">Tema</language></name></property>
  <property id="componentsection" index="true" stored="true" editable="false" type="list" listid="componentsection"><name><language id="en">Section</language><language id="es">Subtema</language></name></property>
  <property id="entityquestion" index="true" stored="true" editable="false" type="list" listid="entityquestion"><name><language id="en">Question</language><language id="es">Pregunta</language></name></property>
  <property id="query" index="false" stored="true" editable="false" type="textarea"><name><language id="en">Learner's words</language><language id="es">Texto del colaborador</language></name></property>
  <property id="replied" index="true" stored="true" editable="false" type="boolean" datatype="boolean"><name><language id="en">Answered</language><language id="es">Respondida</language></name></property>
  <property id="cited" index="true" stored="true" editable="false" type="boolean" datatype="boolean"><name><language id="en">Cited</language><language id="es">Con fuente</language></name></property>
  <property id="rating" index="true" stored="true" editable="false" keyword="true"><name><language id="en">Rating</language><language id="es">Valoración</language></name></property>
  <property id="theme" index="true" stored="true" editable="false" type="list" listid="tutorquestiontheme"><name><language id="en">Theme</language><language id="es">Tipo de pregunta</language></name></property>
  <property id="topiclabel" index="true" stored="true" editable="false" keyword="true"><name><language id="en">Topic label</language><language id="es">Etiqueta</language></name></property>
  <property id="classifiedat" index="true" stored="true" editable="false" type="date"><name><language id="en">Classified at</language><language id="es">Clasificada</language></name></property>
</properties>
```

- [ ] **Step 5: Lists**

`data/lists/usageeventtype.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<usageeventtypes>
  <usageeventtype id="open"><name><![CDATA[Apertura]]></name></usageeventtype>
  <usageeventtype id="resume"><name><![CDATA[Vuelve a la app]]></name></usageeventtype>
  <usageeventtype id="pause"><name><![CDATA[Sale de la app]]></name></usageeventtype>
  <usageeventtype id="iris_rate"><name><![CDATA[Valora una respuesta del tutor]]></name></usageeventtype>
</usageeventtypes>
```

`data/lists/tutorquestiontheme.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<tutorquestionthemes>
  <tutorquestiontheme id="concept"><name><![CDATA[Aclarar un concepto]]></name></tutorquestiontheme>
  <tutorquestiontheme id="procedure"><name><![CDATA[Cómo se hace]]></name></tutorquestiontheme>
  <tutorquestiontheme id="example"><name><![CDATA[Pedir un ejemplo]]></name></tutorquestiontheme>
  <tutorquestiontheme id="source"><name><![CDATA[Dónde está en la fuente]]></name></tutorquestiontheme>
  <tutorquestiontheme id="challenge"><name><![CDATA[Discutir la respuesta]]></name></tutorquestiontheme>
  <tutorquestiontheme id="offtopic"><name><![CDATA[Fuera del tema]]></name></tutorquestiontheme>
  <tutorquestiontheme id="other"><name><![CDATA[Otro]]></name></tutorquestiontheme>
</tutorquestionthemes>
```

- [ ] **Step 6: Deploy, restart, seed lists**

```bash
./deploy.sh && <restart Tomcat per Environment facts> && python3 tools/seed_lists.py
```

Expected: `deployed to …`, server answers again, seed prints `usageeventtype/open 200` … (a `skip (no module route)` line for plain picklists is fine).

- [ ] **Step 7: Write `tools/check_fields.sh`** (executable) and run it

```sh
#!/bin/sh
# Fields of Task 1 are known to the server: a search on each new searchtype answers (0 hits is fine, 404/500 is not).
set -eu
ES=${ES:-http://localhost:9200/site_catalog}
for t in tutordaily usageevent tutorquestion; do
  n=$(curl -sf -X POST "$ES/_search?size=0" -H 'Content-Type: application/json' -d "{\"query\":{\"term\":{\"_type\":\"$t\"}}}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["hits"]["total"])')
  echo "$t: $n rows"
done
B=${EME_BASE:-http://localhost:8080/site/mediadb}; J=$(mktemp); trap 'rm -f "$J"' EXIT
curl -sf -c "$J" -o /dev/null "$B/services/authentication/login.json" -H 'Content-Type: application/json' -d '{"id":"admin","password":"admin"}'
curl -sf -b "$J" "$B/services/testu/personas/me.json" | python3 -c 'import sys,json; d=json.load(sys.stdin); assert d["role"]=="orgadmin", d; print("admin is orgadmin, perms", len(d["permissions"]))'
```

Expected: three `… rows` lines and `admin is orgadmin, perms 9`.

- [ ] **Step 8: Commit**

```bash
git add data tools/check_fields.sh && git commit -m "analytics v1: tutordaily, usageevent, tutorquestion, calibration counters, lists"
```

---

## Task 2: `usage/track.json` ingestion

**Files:**
- Create: `html/services/testu/usage/track.xconf`, `html/services/testu/usage/track.json`, `html/services/testu/usage/scripts/_site.xconf`, `html/services/testu/usage/scripts/track.groovy`
- Test: `tools/check_track.sh`

**Interfaces:**
- Consumes: `usageevent` fields (Task 1).
- Produces: `POST services/testu/usage/track.json` form field `events` = JSON array of `{type, sessionid, seconds, at (ISO-8601), platform, appversion, channel, componentsection, entityquestion, rating}` → `{ok:true, saved:n}`; 400 `{ok:false,error:"bad events"}` on unparsable input; 401 when not signed in.

- [ ] **Step 1: `track.xconf`** (any signed-in user; `<user/>` is the guard `me.xconf` already uses)

```xml
<page>
  <path-action name="Script.run"><script>/${applicationid}/services/testu/usage/scripts/track.groovy</script></path-action>
  <permission name="view"><user/></permission>
</page>
```

`track.json`: one line `$json`. `scripts/_site.xconf`: copy of `html/services/testu/analytics/scripts/_site.xconf` (`view` = false).

- [ ] **Step 2: `track.groovy`**

```groovy
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.entermediadb.asset.MediaArchive
import org.openedit.Data
import java.security.MessageDigest

void reply(Map m) { context.putPageValue("json", JsonOutput.toJson(m)) }
void fail(int code, String msg) { context.getResponse().setStatus(code); reply([ok: false, error: msg]); context.setCancelActions(true) }
String md5(String s) { MessageDigest.getInstance("MD5").digest(s.bytes).encodeHex().toString() }
static final Set TYPES = ["open", "resume", "pause", "iris_rate"] as Set

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
```

- [ ] **Step 3: `tools/check_track.sh`** — logs in as admin, posts three events twice, asserts `saved` 3 then 0, and asserts the stored `user` is `admin` even though the body claims `user: "someoneelse"`.

```sh
#!/bin/sh
set -eu
B=${EME_BASE:-http://localhost:8080/site/mediadb}; ES=${ES:-http://localhost:9200/site_catalog}; J=$(mktemp); trap 'rm -f "$J"' EXIT
curl -sf -c "$J" -o /dev/null "$B/services/authentication/login.json" -H 'Content-Type: application/json' -d '{"id":"admin","password":"admin"}'
S="chk-$(date +%s)"
EV="[{\"type\":\"open\",\"sessionid\":\"$S\",\"at\":\"2026-09-06T10:00:00Z\",\"user\":\"someoneelse\",\"platform\":\"test\",\"appversion\":\"1.1.1+6\"},{\"type\":\"pause\",\"sessionid\":\"$S\",\"seconds\":95,\"at\":\"2026-09-06T10:01:35Z\"},{\"type\":\"iris_rate\",\"sessionid\":\"$S\",\"at\":\"2026-09-06T10:01:00Z\",\"channel\":\"c1\",\"componentsection\":\"s1\",\"rating\":\"helpful\"}]"
post() { curl -sf -b "$J" -X POST "$B/services/testu/usage/track.json" --data-urlencode "events=$EV" | python3 -c 'import sys,json;print(json.load(sys.stdin)["saved"])'; }
a=$(post); b=$(post)
sleep 2
u=$(curl -sf -X POST "$ES/_search?size=1" -H 'Content-Type: application/json' -d "{\"query\":{\"term\":{\"sessionid\":\"$S\"}}}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["hits"]["hits"][0]["_source"]["user"])')
[ "$a" = 3 ] && [ "$b" = 0 ] && [ "$u" = admin ] && echo "ok: saved 3 then 0, user from session" || { echo "FAIL: saved $a/$b user $u"; exit 1; }
```

- [ ] **Step 4: Deploy, run, commit**

```bash
./deploy.sh && chmod +x tools/check_track.sh && tools/check_track.sh
git add html/services/testu/usage tools/check_track.sh && git commit -m "analytics v1: usage/track.json ingestion (idempotent, user from session)"
```

---

## Task 3: Event passes — calibration, `tutordaily`, `tutorquestion` + classification

**Files:**
- Modify: `catalog/events/scripts/testu/computemastery.groovy`
- Create: `html/ai/default/calls/analytics_classify_questions.json`
- Test: `tools/check_rollups.sh`

**Interfaces:**
- Consumes: Tasks 1–2 entities; `chatterbox` rows (Environment facts).
- Produces: filled `tutormastery.certain*/unsure*`, `tutordaily`, `tutorquestion` rows. Reading code in Task 4 depends on the field names exactly as in Task 1.

- [ ] **Step 1: Calibration counters** — in the existing grouped loop, after `g.latest[...] = ok`:

```groovy
  boolean certain = String.valueOf(a.get("answerconfidence")) in ["confident", "mostlysure"]
  g[certain ? (ok ? "cc" : "cw") : (ok ? "uc" : "uw")] = (g[certain ? (ok ? "cc" : "cw") : (ok ? "uc" : "uw")] ?: 0) + 1
```

and when saving the row:

```groovy
  row.setValue("certaincorrect", g.cc ?: 0); row.setValue("certainwrong", g.cw ?: 0)
  row.setValue("unsurecorrect", g.uc ?: 0); row.setValue("unsurewrong", g.uw ?: 0)
```

- [ ] **Step 2: `tutordaily` pass** — append after the stale-row prune, before the final `log.info`:

```groovy
// ---- Pass 2: tutordaily (user x calendar day, server timezone). Full rebuild, same ceiling as tutormastery.
TimeZone tz = TimeZone.getDefault()
String dayOf(Date d) { d.format("yyyyMMdd", tz) }
Map days = [:]   // "<user>_<yyyyMMdd>" -> map
def dayFor(String user, Date at) {
  String k = user + "_" + dayOf(at)
  Map d = days[k]
  if (d == null) { d = [user: user, day: Date.parse("yyyyMMdd", dayOf(at)), answers: 0, correct: 0, certainwrong: 0, questions: 0, helpful: 0, nothelpful: 0, minutes: 0.0d, spans: 0, first: at, last: at, times: []]; days[k] = d }
  if (at < d.first) d.first = at
  if (at > d.last) d.last = at
  return d
}
for (Data a in rows) {  // `rows` = every tutoranswer, already sorted by datecreated
  String user = a.get("user"); Date at = a.getDate("datecreated"); if (!user || !at) continue
  Map d = dayFor(user, at)
  boolean ok = "true".equals(String.valueOf(a.get("iscorrect")))
  d.answers++; if (ok) d.correct++
  if (!ok && String.valueOf(a.get("answerconfidence")) in ["confident", "mostlysure"]) d.certainwrong++
  d.times << at
}
// Foreground spans from the app: a pause carries the seconds of its span.
HitTracker ev = archive.query("usageevent").all().search(); ev.enableBulkOperations()
Map ratings = [:]  // channel -> [[at, rating, section, question]] for pass 3
for (Data e in ev) {
  String user = e.get("user"); Date at = e.getDate("datecreated"); String type = e.get("type"); if (!user || !at) continue
  if (type == "iris_rate") { ratings.get(e.get("channel"), []) << [at: at, rating: e.get("rating"), user: user]; Map d = dayFor(user, at); d[e.get("rating") == "helpful" ? "helpful" : "nothelpful"]++; continue }
  Map d = dayFor(user, at)
  if (type == "pause") { d.minutes += ((e.get("seconds") ?: "0") as Double) / 60d; d.spans++ }
}
def daily = archive.getSearcher("tutordaily")
List dsave = []
for (Map d in days.values()) {
  Data row = daily.searchById(d.user + "_" + dayOf(d.day)) ?: daily.createNewData()
  row.setId(d.user + "_" + dayOf(d.day))
  row.setValue("user", d.user); row.setValue("day", d.day)
  ["answers", "correct", "certainwrong", "questions", "helpful", "nothelpful"].each { row.setValue(it, d[it]) }
  // sessions: foreground spans when the app reported any that day, else answer bursts (gap > 30 min starts a new one)
  int bursts = 0; Date prev = null
  for (Date t in d.times.sort()) { if (prev == null || t.time - prev.time > 30 * 60 * 1000L) bursts++; prev = t }
  row.setValue("sessions", d.spans > 0 ? d.spans : bursts)
  row.setValue("minutes", Math.round(d.minutes) as Integer)
  row.setValue("firstactivity", d.first); row.setValue("lastactivity", d.last); row.setValue("computedat", now)
  dsave << row
}
if (dsave) daily.saveAllData(dsave, null)
HitTracker olddaily = daily.query().all().search(); olddaily.enableBulkOperations()
List ddel = []; for (Data r in olddaily) { if (!days.containsKey(r.getId())) ddel << r }
if (ddel) daily.deleteAll(ddel, null)
```

Note: `questions` per day is filled in pass 3 below **before** `dsave` is saved — move the `daily.saveAllData` block after pass 3 (pass 3 increments `dayFor(user, at).questions`).

- [ ] **Step 3: `tutorquestion` pass + classification** (place between the `days` loop and the `tutordaily` save):

```groovy
// ---- Pass 3: tutorquestion from chatterbox (learner question rows + their reply), ratings joined by channel within 10 min after the reply.
def tq = archive.getSearcher("tutorquestion")
HitTracker msgs = archive.query("chatterbox").exact("functionname", "chat_tutor_usercomment").search(); msgs.enableBulkOperations()
Map replies = [:]   // replytoid -> reply Data
List asks = []
for (Data m in msgs) { if (m.get("user") == "agent") { if (m.get("replytoid")) replies[m.get("replytoid")] = m } else if (m.get("user")) asks << m }
def slurper = new groovy.json.JsonSlurper()
List qsave = []; Set qids = [] as Set
for (Data m in asks) {
  Map ctx = [:]
  try { ctx = slurper.parseText(m.get("agentcontextvalues") ?: "{}") as Map } catch (Exception e) {}
  String query = ctx.query?.toString(); Date at = m.getDate("date"); String user = m.get("user")
  if (!query || !at) continue
  qids << m.getId()
  Data row = tq.searchById(m.getId()) ?: tq.createNewData(); row.setId(m.getId())
  row.setValue("user", user); row.setValue("datecreated", at); row.setValue("channel", m.get("channel"))
  String section = ctx.sectionid?.toString(); row.setValue("componentsection", section)
  row.setValue("entitytutorial", ctx.tutorialid?.toString() ?: sections[section]?.tutorial)
  row.setValue("entitytopic", topicOf[ctx.tutorialid?.toString() ?: sections[section]?.tutorial])
  row.setValue("entityquestion", ctx.questionid?.toString()); row.setValue("query", query)
  Data reply = replies[m.getId()]
  row.setValue("replied", reply != null)
  String text = reply?.get("message") ?: ""
  row.setValue("cited", (text =~ /\[[^\]\n]+,\s*(p\.\s*\d+|\d+:\d\d)\]\s*(\[\[hl[^\]]*\]\])?\s*$/).find())
  Date rat = reply?.getDate("date") ?: at
  def r = (ratings[m.get("channel")] ?: []).find { it.user == user && it.at >= rat && it.at.time - rat.time <= 10 * 60 * 1000L }
  if (r) row.setValue("rating", r.rating)
  dayFor(user, at).questions++
  qsave << row
}
if (qsave) tq.saveAllData(qsave, null)
HitTracker oldq = tq.query().all().search(); oldq.enableBulkOperations()
List qdel = []; for (Data r in oldq) { if (!qids.contains(r.getId())) qdel << r }
if (qdel) tq.deleteAll(qdel, null)

// Classification: theme + topic label, 20 per LLM call, 60 s budget, unclassified rows wait for the next run.
long budgetEnd = System.currentTimeMillis() + 60_000L
List pending = []
for (Data r in tq.query().all().search()) { if (!r.getDate("classifiedat")) pending << r }
try {
  def llm = archive.getLlmConnection("thinking")
  while (pending && System.currentTimeMillis() < budgetEnd) {
    List batch = pending.take(20); pending = pending.drop(20)
    def ctx = new org.entermediadb.ai.llm.BaseAgentContext()
    ctx.putContextValue("questions", groovy.json.JsonOutput.toJson(batch.collect { [id: it.getId(), section: sections[it.get("componentsection")]?.name ?: "", text: it.get("query")] }))
    def res = llm.callStructure(ctx, "analytics_classify_questions")
    def items = res.getMessageStructured()?.get("items")
    List csave = []
    for (Map it in (items ?: [])) {
      Data row = batch.find { b -> b.getId() == it.id }
      if (row == null) continue
      row.setValue("theme", (it.theme in ["concept", "procedure", "example", "source", "challenge", "offtopic"]) ? it.theme : "other")
      row.setValue("topiclabel", (it.topiclabel ?: "").toString().trim().take(40)); row.setValue("classifiedat", now)
      csave << row
    }
    if (csave) tq.saveAllData(csave, null)
  }
} catch (Exception e) { log.info("testu computemastery: classification skipped this run: " + e.getMessage()) }
```

`sections[...]` needs the section name: in the first loop of the script, change `sections[s.getId()] = [tutorial: …, questions: q]` to also carry `name: s.getName()`.

- [ ] **Step 4: `html/ai/default/calls/analytics_classify_questions.json`**

```json
{
	"model": "${model}",
	"messages": [
		{ "role": "system", "content": #jesc("You classify questions that learners asked their AI tutor inside a corporate training app. For each item return its id, one theme and a topic label.
Themes (use exactly one id): concept = asks to clarify or explain a concept; procedure = asks how something is done or what to do; example = asks for an example or a case; source = asks where something is in the reference material or for the citation; challenge = disputes or discusses the correctness of an answer or question; offtopic = unrelated to the training; other = none of the above.
topiclabel: the subject of the question in at most 5 words, in the language of the question, lowercase, no punctuation, generic enough that similar questions get the same label (e.g. \"debida diligencia proveedores\", \"correos de phishing\"). Never include names of people.") },
		{ "role": "user", "content": #jesc("Questions (JSON): ${questions}") }
	],
	"response_format": { "type": "json_schema", "json_schema": { "name": "classification", "strict": true, "schema": {
		"type": "object", "properties": { "items": { "type": "array", "items": { "type": "object", "properties": {
			"id": { "type": "string" }, "theme": { "type": "string" }, "topiclabel": { "type": "string" } },
			"required": ["id", "theme", "topiclabel"], "additionalProperties": false } } },
		"required": ["items"], "additionalProperties": false } } }
}
```

- [ ] **Step 5: `tools/check_rollups.sh`** — deploy, trigger the event through `recompute.json` (admin is orgadmin since Task 1), wait 20 s, then assert with Python against Elasticsearch: (a) for `diego`, the four counters of each `tutormastery` row sum to `attempts`; (b) `tutordaily` for `diego` has, for every day, `answers` equal to the count of `tutoranswer` rows of that user on that day (server timezone); (c) every learner `chatterbox` row with `functionname=chat_tutor_usercomment` has a `tutorquestion` with the same id; (d) the `iris_rate` event posted by `check_track.sh` (channel `c1`) did not attach to any question (no reply on channel `c1`), while a synthetic check passes: post one `iris_rate` on the channel of diego's latest question, 2 minutes after its reply, rerun the event, assert that question's `rating` is `helpful`; (e) `cited` is true for at least one row whose reply ends with `[…, p. N]` when such a row exists locally.

```sh
#!/bin/sh
set -eu
B=${EME_BASE:-http://localhost:8080/site/mediadb}; ES=${ES:-http://localhost:9200/site_catalog}; J=$(mktemp); trap 'rm -f "$J"' EXIT
curl -sf -c "$J" -o /dev/null "$B/services/authentication/login.json" -H 'Content-Type: application/json' -d '{"id":"admin","password":"admin"}'
curl -sf -b "$J" -X POST "$B/services/testu/analytics/recompute.json" >/dev/null; sleep 20
python3 - "$ES" <<'PY'
import sys, json, urllib.request, collections, datetime
ES = sys.argv[1]
def q(body, size=2000):
    r = urllib.request.Request(f"{ES}/_search?size={size}", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    return [h["_source"] | {"_id": h["_id"]} for h in json.load(urllib.request.urlopen(r))["hits"]["hits"]]
def rows(t, **kw):
    must = [{"term": {"_type": t}}] + [{"term": {k: v}} for k, v in kw.items()]
    return q({"query": {"bool": {"must": must}}})
m = rows("tutormastery", user="diego"); assert m, "no tutormastery for diego"
for r in m:
    s = sum(int(r.get(k) or 0) for k in ("certaincorrect", "certainwrong", "unsurecorrect", "unsurewrong"))
    assert s == int(r.get("attempts") or 0), (r["_id"], s, r.get("attempts"))
ans = rows("tutoranswer", user="diego")
per = collections.Counter(datetime.datetime.fromisoformat(a["datecreated"].replace("Z", "+00:00")).astimezone().strftime("%Y%m%d") for a in ans if a.get("datecreated"))
d = {r["_id"]: r for r in rows("tutordaily", user="diego")}
for day, n in per.items():
    assert int(d[f"diego_{day}"]["answers"]) == n, (day, n, d.get(f"diego_{day}"))
asks = [c for c in rows("chatterbox", functionname="chat_tutor_usercomment") if c.get("user") != "agent"]
tq = {r["_id"]: r for r in rows("tutorquestion")}
missing = [a["_id"] for a in asks if a["_id"] not in tq and json.loads(a.get("agentcontextvalues") or "{}").get("query")]
assert not missing, missing
print(f"ok: counters sum to attempts on {len(m)} rows; tutordaily matches {len(per)} days; {len(tq)} tutorquestions; cited={sum(1 for r in tq.values() if str(r.get('cited'))=='true')}")
PY
```

Add the rating join check as a second block that posts the `iris_rate` event (via `track.json`, as admin) with `channel` = the channel of the most recent `tutorquestion` of any user and `at` = its reply date + 2 min; because `track` stores `user = admin` and the join requires the same user, expect **no** rating on that row; then assert exactly that (documents the same-user rule). Print `ok: rating join requires the same user`.

- [ ] **Step 6: Run, commit**

```bash
./deploy.sh && chmod +x tools/check_rollups.sh && tools/check_rollups.sh
git add catalog html/ai tools/check_rollups.sh && git commit -m "analytics v1: calibration counters, tutordaily and tutorquestion rollups with LLM classification"
```

---

## Task 4: `aggregate.groovy` + `overview.json`, `activity.json`, `person.json`

**Files:**
- Create: `html/services/testu/analytics/scripts/aggregate.groovy`, `overview.xconf/.json`, `activity.xconf/.json`, `person.xconf/.json`, and their `scripts/*.groovy`
- Test: `tools/check_analytics.sh`

**Interfaces:**
- Consumes: `scopeteams`/`allteams` page values from `personas/scripts/scope.groovy`; Task 1 fields.
- Produces: the exact JSON shapes of spec §4 (table). Dart models in Task 9 parse these keys verbatim: `cohort.total|activated|active7d|active30d`, `series[].day|people|answers|minutes|sessions|certainwrong|questions`, `levels.notstarted|beginner|competent|expert`, `topics[].id|name|people|levels|weakest{section,name,beginners}`, `calibration.cc|cu|ic|iu`, `teams[].id|name|members|activated|active7d|levels|weakest`, `median.activeShare|expertShare`, `previous.active7d|answers|minutes|certainwrong|questions`, `gaps[].section|name|topic|score|beginners|questions|misconceptions|unanswered`, `iris.questions|people|citedShare|ratedShare|helpfulShare|themes[]{theme,count}|sections[]{section,name,questions,helpfulShare}|labels[]{label,count}`, `funnel.cohort|signedin|answered|active7d|active30d`, `hours[[wd,h,n]]`, `inactive[].user|name|team|lastactivity`, `person.json`: `user{id,name,team,role,enabled,lastlogin}`, `rows[]` (report shape + the four counters), `series`, `calibration`, `topics[]{id,name,level,mastered,answered,weakest}`, `usage{sessions,minutes,activeDays}`, `iris{questions,sections[]{section,name,questions},helpfulShare}`.

- [ ] **Step 1: page files.** Each `.xconf` chains `scope.groovy` → `aggregate.groovy` → its own script, permission `analytics_view` (copy `report.xconf`, add the aggregate line with `allowduplicates="true"`); each `.json` is `$json`.

```xml
<page>
  <path-action name="Script.run"><script>/${applicationid}/services/testu/personas/scripts/scope.groovy</script></path-action>
  <path-action name="Script.run" allowduplicates="true"><script>/${applicationid}/services/testu/analytics/scripts/aggregate.groovy</script></path-action>
  <path-action name="Script.run" allowduplicates="true"><script>/${applicationid}/services/testu/analytics/scripts/overview.groovy</script></path-action>
  <permission name="view"><userprofile property="analytics_view" equals="true"/></permission>
</page>
```

- [ ] **Step 2: `aggregate.groovy`**

```groovy
import org.entermediadb.asset.MediaArchive
import org.openedit.Data
// Builds the in-scope analytics model once per request; overview/activity/person/ask only shape it.
MediaArchive archive = context.getPageValue("mediaarchive")
Set scope = context.getPageValue("scopeteams"); Map allteams = context.getPageValue("allteams") ?: [:]
String topicFilter = context.getRequestParameter("entitytopic") ?: ""; String teamFilter = context.getRequestParameter("team") ?: ""
if (scope != null && teamFilter && !(teamFilter in scope)) { context.getResponse().setStatus(400); context.putPageValue("json", '{"ok":false,"error":"out of scope"}'); context.setCancelActions(true); return }
Date now = new Date()
Date to = (context.getRequestParameter("to") ? Date.parse("yyyy-MM-dd", context.getRequestParameter("to")) : now).clearTime() + 1   // exclusive end
Date from = (context.getRequestParameter("from") ? Date.parse("yyyy-MM-dd", context.getRequestParameter("from")) : to - 30).clearTime()
int span = (to - from) as int; Date prevFrom = from - span; Date prevTo = from
String day(Date d) { d.format("yyyy-MM-dd") }
String levelOf(int mastered, int answered) { answered == 0 ? null : (mastered / (double) answered < 0.5 ? "beginner" : (mastered / (double) answered < 0.9 ? "competent" : "expert")) }
Map levelsOf(Collection lv) { [notstarted: lv.count { it == null }, beginner: lv.count { it == "beginner" }, competent: lv.count { it == "competent" }, expert: lv.count { it == "expert" }] }

// Users: everyone in the org (for the median) and the subset in scope + team filter.
Map allUsers = [:]; def uh = archive.query("user").all().search(); uh.enableBulkOperations(); for (Data u in uh) allUsers[u.getId()] = u
boolean inScope(Data u) { String t = u.get("team"); (scope == null || t in scope) && (!teamFilter || t == teamFilter) }
boolean isLearner(Data u) { !(u.getId() in ["admin", "agent"]) && u.get("enabled") != "false" }
Map users = allUsers.findAll { k, u -> isLearner(u) && inScope(u) }
String nameOf(Data u) { ((u.get("firstName") ?: "") + " " + (u.get("lastName") ?: "")).trim() ?: u.getId() }
Map topics = [:]; for (Data t in archive.query("entitytopic").all().search()) topics[t.getId()] = t.getName()
Map sections = [:]; for (Data s in archive.query("componentsection").exact("playbackentitymoduleid", "entitytutorial").search()) sections[s.getId()] = s.getName()

// Mastery rows (all, for the median) then in scope + topic filter.
List allMastery = []; def mh = archive.query("tutormastery").all().search(); mh.enableBulkOperations(); for (Data r in mh) allMastery << r
List mastery = allMastery.findAll { users.containsKey(it.get("user")) && (!topicFilter || it.get("entitytopic") == topicFilter) }
int n(Data r, String f) { (r.get(f) ?: "0") as Integer }
Map perUser = [:]   // uid -> [mastered, answered, attempts, cw, last]
Map perUserTopic = [:] // uid -> topic -> [mastered, answered]
Map perSection = [:] // section -> [people: set, levels: [], answered, attempts, cw, beginners]
for (Data r in mastery) {
  String u = r.get("user"); def pu = perUser.get(u, [mastered: 0, answered: 0, attempts: 0, cw: 0, cc: 0, uc: 0, uw: 0, last: null])
  pu.mastered += n(r, "mastered"); pu.answered += n(r, "answered"); pu.attempts += n(r, "attempts"); pu.cw += n(r, "certainwrong"); pu.cc += n(r, "certaincorrect"); pu.uc += n(r, "unsurecorrect"); pu.uw += n(r, "unsurewrong")
  Date la = r.getDate("lastactivity"); if (la && (pu.last == null || la > pu.last)) pu.last = la
  def pt = perUserTopic.get(u, [:]).get(r.get("entitytopic"), [mastered: 0, answered: 0]); pt.mastered += n(r, "mastered"); pt.answered += n(r, "answered")
  def ps = perSection.get(r.get("componentsection"), [topic: r.get("entitytopic"), levels: [], answered: 0, attempts: 0, cw: 0])
  ps.levels << levelOf(n(r, "mastered"), n(r, "answered")); ps.answered += n(r, "answered"); ps.attempts += n(r, "attempts"); ps.cw += n(r, "certainwrong")
}
Map levelByUser = users.keySet().collectEntries { u -> [(u): perUser[u] ? levelOf(perUser[u].mastered, perUser[u].answered) : null] }

// Daily rows in period and previous period (in scope).
List dailyAll = []; def dh = archive.query("tutordaily").all().search(); dh.enableBulkOperations(); for (Data r in dh) { if (users.containsKey(r.get("user"))) dailyAll << r }
List daily = dailyAll.findAll { Date d = it.getDate("day"); d >= from && d < to }
List prevDaily = dailyAll.findAll { Date d = it.getDate("day"); d >= prevFrom && d < prevTo }
Map series = [:]; for (Date d = from; d < to; d = d + 1) series[day(d)] = [day: day(d), people: 0, answers: 0, minutes: 0, sessions: 0, certainwrong: 0, questions: 0]
for (Data r in daily) { def s = series[day(r.getDate("day"))]; if (s == null) continue; if (n(r, "answers") > 0) s.people++; ["answers", "minutes", "sessions", "certainwrong", "questions"].each { s[it] += n(r, it) } }
Set activeSince(List rowsIn, int daysBack) { Date since = to - daysBack; rowsIn.findAll { it.getDate("day") >= since && n(it, "answers") > 0 }.collect { it.get("user") } as Set }
Map cohort = [total: users.size(), activated: users.keySet().count { perUser[it]?.answered > 0 }, active7d: activeSince(dailyAll, 7).size(), active30d: activeSince(dailyAll, 30).size()]
Map sums(List rowsIn) { [answers: rowsIn.sum { n(it, "answers") } ?: 0, minutes: rowsIn.sum { n(it, "minutes") } ?: 0, certainwrong: rowsIn.sum { n(it, "certainwrong") } ?: 0, questions: rowsIn.sum { n(it, "questions") } ?: 0] }
Map previous = sums(prevDaily) + [active7d: (prevDaily.findAll { it.getDate("day") >= prevTo - 7 && n(it, "answers") > 0 }.collect { it.get("user") } as Set).size()]

// Topics, sections, teams.
List topicStats = topics.collect { tid, tname ->
  if (topicFilter && tid != topicFilter) return null
  def lv = users.keySet().collect { u -> def pt = perUserTopic[u]?.get(tid); pt ? levelOf(pt.mastered, pt.answered) : null }
  def secs = perSection.findAll { k, v -> v.topic == tid }
  def weakest = secs.max { k, v -> v.levels.count { it == "beginner" } }
  [id: tid, name: tname, people: lv.count { it != null }, levels: levelsOf(lv), weakest: weakest ? [section: weakest.key, name: sections[weakest.key], beginners: weakest.value.levels.count { it == "beginner" }] : null]
}.findAll { it != null }
// Tutor questions in scope + period.
List tqAll = []; def qh = archive.query("tutorquestion").all().search(); qh.enableBulkOperations(); for (Data r in qh) { if (users.containsKey(r.get("user")) && (!topicFilter || r.get("entitytopic") == topicFilter)) tqAll << r }
List tq = tqAll.findAll { Date d = it.getDate("datecreated"); d >= from && d < to }
Map qBySection = tq.groupBy { it.get("componentsection") }
List sectionStats = perSection.collect { sid, v ->
  List qs = qBySection[sid] ?: []
  int people = v.levels.size(); int beginners = v.levels.count { it == "beginner" }; int unanswered = qs.count { it.get("replied") != "true" }
  double score = 3 * beginners / Math.max(people, 1) + 2 * qs.size() / Math.max(v.attempts, 1) + 2 * unanswered / Math.max(qs.size(), 1) + 3 * v.cw / Math.max(v.attempts, 1)
  [section: sid, name: sections[sid], topic: topics[v.topic], topicId: v.topic, people: people, levels: levelsOf(v.levels), beginners: beginners, questions: qs.size(), misconceptions: v.cw, unanswered: unanswered, score: Math.round(score * 100) / 100d,
   helpfulShare: qs.count { it.get("rating") } ? qs.count { it.get("rating") == "helpful" } / (double) qs.count { it.get("rating") } : null]
}
List gaps = sectionStats.findAll { it.people > 0 }.sort { -it.score }.take(5)
Map byTeam = users.values().groupBy { it.get("team") ?: "" }
List teamStats = byTeam.collect { tid, members ->
  Set ids = members.collect { it.getId() } as Set
  def lv = ids.collect { levelByUser[it] }
  def weakest = topicStats.max { t -> ids.count { u -> def pt = perUserTopic[u]?.get(t.id); pt && levelOf(pt.mastered, pt.answered) == "beginner" } }
  [id: tid, name: tid ? (allteams[tid]?.getName() ?: tid) : "", members: ids.size(), activated: ids.count { perUser[it]?.answered > 0 }, active7d: (activeSince(dailyAll, 7).intersect(ids)).size(), levels: levelsOf(lv), weakest: weakest?.name]
}.sort { it.name }
// Calibration (in scope + topic filter, all attempts).
Map calibration = [cc: perUser.values().sum { it.cc } ?: 0, cu: perUser.values().sum { it.uc } ?: 0, ic: perUser.values().sum { it.uw } ?: 0, iu: perUser.values().sum { it.cw } ?: 0]
// Org-wide median, only when the org has >= 5 learners; ignores scope on purpose (anonymous comparison).
Map orgUsers = allUsers.findAll { k, u -> isLearner(u) }
Map median = null
if (orgUsers.size() >= 5) {
  Map orgPer = [:]; for (Data r in allMastery) { if (orgUsers.containsKey(r.get("user"))) { def p = orgPer.get(r.get("user"), [m: 0, a: 0]); p.m += n(r, "mastered"); p.a += n(r, "answered") } }
  Set orgActive = [] as Set; for (Data r in dh) { if (orgUsers.containsKey(r.get("user")) && r.getDate("day") >= to - 7 && n(r, "answers") > 0) orgActive << r.get("user") }
  median = [activeShare: orgActive.size() / (double) orgUsers.size(), expertShare: orgUsers.keySet().count { u -> orgPer[u] && levelOf(orgPer[u].m, orgPer[u].a) == "expert" } / (double) orgUsers.size()]
}
List inactive = users.values().findAll { u -> Date l = perUser[u.getId()]?.last; l == null || l < now - 7 }.collect { u -> [user: u.getId(), name: nameOf(u), team: u.get("team"), lastactivity: perUser[u.getId()]?.last?.format("yyyy-MM-dd'T'HH:mm:ssXXX")] }.sort { it.lastactivity ?: "" }
// Tutor usage aggregates (never the text).
Map irisAgg(List qs) {
  int rated = qs.count { it.get("rating") }
  Map labels = qs.findAll { it.get("topiclabel") }.groupBy { it.get("topiclabel") }.findAll { k, v -> v.size() >= 3 }
  [questions: qs.size(), people: (qs.collect { it.get("user") } as Set).size(), citedShare: qs ? qs.count { it.get("cited") == "true" } / (double) qs.size() : null, ratedShare: qs ? rated / (double) qs.size() : null,
   helpfulShare: rated ? qs.count { it.get("rating") == "helpful" } / (double) rated : null,
   themes: qs.groupBy { it.get("theme") ?: "unclassified" }.collect { k, v -> [theme: k, count: v.size()] }.sort { -it.count },
   sections: qs.groupBy { it.get("componentsection") }.collect { k, v -> [section: k, name: sections[k], questions: v.size(), helpfulShare: v.count { it.get("rating") } ? v.count { it.get("rating") == "helpful" } / (double) v.count { it.get("rating") } : null] }.sort { -it.questions },
   labels: labels.collect { k, v -> [label: k, count: v.size()] }.sort { -it.count }.take(30)]
}
context.putPageValue("analytics", [from: from, to: to, users: users, allteams: allteams, topics: topics, sections: sections, mastery: mastery, perUser: perUser, perUserTopic: perUserTopic, levelByUser: levelByUser,
  dailyAll: dailyAll, daily: daily, series: series.values() as List, cohort: cohort, previous: previous, levels: levelsOf(levelByUser.values()), topicStats: topicStats, sectionStats: sectionStats, gaps: gaps,
  teamStats: teamStats, calibration: calibration, median: median, inactive: inactive, tq: tq, iris: irisAgg(tq), nameOf: this.&nameOf, levelOf: this.&levelOf, levelsOf: this.&levelsOf, day: this.&day, n: this.&n])
```

- [ ] **Step 3: `overview.groovy`**

```groovy
import groovy.json.JsonOutput
Map a = context.getPageValue("analytics"); if (a == null) return
context.putPageValue("json", JsonOutput.toJson([ok: true, from: a.day(a.from), to: a.day(a.to - 1), cohort: a.cohort, series: a.series, levels: a.levels, topics: a.topicStats, calibration: a.calibration,
  teams: a.teamStats, median: a.median, previous: a.previous, gaps: a.gaps, iris: a.iris]))
```

- [ ] **Step 4: `activity.groovy`**

```groovy
import groovy.json.JsonOutput
import org.openedit.Data
Map a = context.getPageValue("analytics"); if (a == null) return
def archive = context.getPageValue("mediaarchive")
Map hours = [:]
def ah = archive.query("tutoranswer").after("datecreated", a.from).search(); ah.enableBulkOperations()
for (Data x in ah) { Date d = x.getDate("datecreated"); if (!a.users.containsKey(x.get("user")) || d >= a.to) continue; def c = d.toCalendar(); String k = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + "_" + c.get(Calendar.HOUR_OF_DAY); hours[k] = (hours[k] ?: 0) + 1 }
Map funnel = [cohort: a.cohort.total, signedin: a.users.values().count { it.get("lastlogin") }, answered: a.cohort.activated, active7d: a.cohort.active7d, active30d: a.cohort.active30d]
context.putPageValue("json", JsonOutput.toJson([ok: true, series: a.series, funnel: funnel, hours: hours.collect { k, v -> [k.split("_")[0] as int, k.split("_")[1] as int, v] }, inactive: a.inactive, iris: a.iris]))
```

- [ ] **Step 5: `person.groovy`**

```groovy
import groovy.json.JsonOutput
import org.openedit.Data
Map a = context.getPageValue("analytics"); if (a == null) return
String uid = (context.getRequestParameter("user") ?: "").toLowerCase()
Data u = a.users[uid]
if (u == null) { context.getResponse().setStatus(403); context.putPageValue("json", '{"ok":false,"error":"out of scope"}'); return }
List rows = a.mastery.findAll { it.get("user") == uid }.collect { r -> [entitytopic: r.get("entitytopic"), topic: a.topics[r.get("entitytopic")], componentsection: r.get("componentsection"), section: a.sections[r.get("componentsection")],
  questions: a.n(r, "questions"), answered: a.n(r, "answered"), mastered: a.n(r, "mastered"), attempts: a.n(r, "attempts"), correct: a.n(r, "correct"), level: r.get("level"), lastactivity: r.getDate("lastactivity")?.format("yyyy-MM-dd'T'HH:mm:ssXXX"),
  certaincorrect: a.n(r, "certaincorrect"), certainwrong: a.n(r, "certainwrong"), unsurecorrect: a.n(r, "unsurecorrect"), unsurewrong: a.n(r, "unsurewrong")] }
def pu = a.perUser[uid] ?: [cc: 0, uc: 0, uw: 0, cw: 0]
List topics = a.topics.collect { tid, tname -> def pt = a.perUserTopic[uid]?.get(tid); if (!pt) return null
  def weak = rows.findAll { it.entitytopic == tid && it.answered > 0 }.min { it.mastered / (double) it.answered }
  [id: tid, name: tname, level: a.levelOf(pt.mastered, pt.answered), mastered: pt.mastered, answered: pt.answered, weakest: weak?.section] }.findAll { it != null }
List mine = a.daily.findAll { it.get("user") == uid }
Map series = [:]; for (Date d = a.from; d < a.to; d = d + 1) series[a.day(d)] = [day: a.day(d), answers: 0, correct: 0, minutes: 0, sessions: 0, questions: 0]
for (Data r in mine) { def s = series[a.day(r.getDate("day"))]; if (s) ["answers", "correct", "minutes", "sessions", "questions"].each { s[it] += a.n(r, it) } }
List qs = a.tq.findAll { it.get("user") == uid }; int rated = qs.count { it.get("rating") }
context.putPageValue("json", JsonOutput.toJson([ok: true, user: [id: uid, name: a.nameOf(u), team: u.get("team"), role: (archive.getSearcher("userprofile").searchById(uid)?.get("settingsgroup") ?: "users"), enabled: u.get("enabled") != "false", lastlogin: u.get("lastlogin")],
  rows: rows, series: series.values() as List, calibration: [cc: pu.cc, cu: pu.uc, ic: pu.uw, iu: pu.cw], topics: topics,
  usage: [sessions: mine.sum { a.n(it, "sessions") } ?: 0, minutes: mine.sum { a.n(it, "minutes") } ?: 0, activeDays: mine.count { a.n(it, "answers") > 0 }],
  iris: [questions: qs.size(), sections: qs.groupBy { it.get("componentsection") }.collect { k, v -> [section: k, name: a.sections[k], questions: v.size()] }, helpfulShare: rated ? qs.count { it.get("rating") == "helpful" } / (double) rated : null]]))
```

(`archive` in person.groovy: add `def archive = context.getPageValue("mediaarchive")` at the top.)

- [ ] **Step 6: `tools/check_analytics.sh`** — as admin: `overview.json` returns `ok`, `levels` values sum to `cohort.total`, `series` has exactly `span` entries, `gaps.size() <= 5`, no key named `query` anywhere in the three bodies; `person.json?user=diego` returns `ok` with `rows` non-empty; `person.json?user=nobody` → 403. Then create a local manager: `createuser.json` (`email=mgr.check@testu.local`, role `manager`, team = an existing team id) and a team whose `manager` is that user (via `saveteam.json`), set a password with `usersave.json -d username=mgr.check@testu.local -d field=password -d passwordvalue=Checkpass123`, log in as it, and assert `overview.json` `teams[]` only contains that team's id and `person.json?user=diego` → 403 unless diego is in that team.

```sh
#!/bin/sh
set -eu
B=${EME_BASE:-http://localhost:8080/site/mediadb}; J=$(mktemp); M=$(mktemp); trap 'rm -f "$J" "$M"' EXIT
login() { curl -sf -c "$1" -o /dev/null "$B/services/authentication/login.json" -H 'Content-Type: application/json' -d "{\"id\":\"$2\",\"password\":\"$3\"}"; }
login "$J" admin admin
for e in overview activity; do curl -sf -b "$J" "$B/services/testu/analytics/$e.json?from=2026-08-08&to=2026-09-06" > "/tmp/$e.json"; done
curl -sf -b "$J" "$B/services/testu/analytics/person.json?user=diego" > /tmp/person.json
python3 - <<'PY'
import json
o = json.load(open('/tmp/overview.json')); a = json.load(open('/tmp/activity.json')); p = json.load(open('/tmp/person.json'))
assert o['ok'] and a['ok'] and p['ok']
assert sum(o['levels'].values()) == o['cohort']['total'], (o['levels'], o['cohort'])
assert len(o['series']) == 30, len(o['series'])
assert len(o['gaps']) <= 5 and p['rows']
for name, body in (('overview', o), ('activity', a), ('person', p)):
    assert '"query"' not in json.dumps(body), f'{name} leaks query text'
print('ok: shapes, one denominator, no question text; person rows', len(p['rows']))
PY
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$J" "$B/services/testu/analytics/person.json?user=nobody"); [ "$code" = 403 ] && echo "ok: out-of-scope person is 403" || { echo "FAIL: $code"; exit 1; }
# manager scope: one team, one manager, fresh login (profile cache is cleared by setrole; createuser sets the role at creation)
TEAM=$(curl -sf -b "$J" "$B/services/testu/personas/teams.json" | python3 -c 'import sys,json;t=json.load(sys.stdin)["teams"];print(t[0]["id"] if t else "")')
[ -n "$TEAM" ] || { echo "SKIP: no teams locally"; exit 0; }
curl -sf -b "$J" -X POST "$B/services/testu/personas/createuser.json" -d email=mgr.check@testu.local -d firstName=Check -d lastName=Manager -d role=manager -d team="$TEAM" >/dev/null || true
curl -sf -b "$J" -X POST "$B/services/testu/personas/saveteam.json" -d id="$TEAM" -d name="$(curl -sf -b "$J" "$B/services/testu/personas/teams.json" | python3 -c 'import sys,json;print(json.load(sys.stdin)["teams"][0]["name"])')" -d manager=mgr.check@testu.local >/dev/null
curl -sf -b "$J" -X POST "$B/services/authentication/usersave.json" -d username=mgr.check@testu.local -d field=password -d passwordvalue=Checkpass123 >/dev/null
login "$M" mgr.check@testu.local Checkpass123
curl -sf -b "$M" "$B/services/testu/analytics/overview.json" | python3 -c "import sys,json;o=json.load(sys.stdin);ids={t['id'] for t in o['teams']};assert ids <= {'$TEAM'}, ids;print('ok: manager sees only', ids)"
```

- [ ] **Step 7: Deploy, run, commit**

```bash
./deploy.sh && chmod +x tools/check_analytics.sh && tools/check_analytics.sh
git add html/services/testu/analytics tools/check_analytics.sh && git commit -m "analytics v1: shared aggregate model; overview, activity, person endpoints"
```

---

## Task 5: `ask.json` (IRIS analyst) + `analytics_ask.json` template

**Files:**
- Create: `html/services/testu/analytics/ask.xconf/.json`, `scripts/ask.groovy`, `html/ai/default/calls/analytics_ask.json`
- Test: `tools/check_ask.sh`

**Interfaces:**
- Consumes: the `analytics` page value (Task 4); `tutorpersona` via catalog setting `tutorpersona`; `auditevent` (existing).
- Produces: `POST services/testu/analytics/ask.json` form fields `question`, `screen`, `user` (optional), `history` (JSON `[{role:"user"|"assistant", text}]`, ≤ 6), plus the filter params; `debug=facts` returns `{ok:true, facts:[…]}` without calling the LLM. Reply `{ok:true, answer, citations:[{id,label,value,view,filters}], followups:[…], model}`; LLM failure → `{ok:false, error:"llm"}` with HTTP 503. The answer text carries markers `[f12]` that the console renders as numbered citations in order of first appearance.

- [ ] **Step 1: `ask.xconf`** — same chain as Task 4 with `ask.groovy` last, permission `analytics_view`. `ask.json` = `$json`.

- [ ] **Step 2: `ask.groovy`**

```groovy
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.entermediadb.asset.MediaArchive
import org.openedit.Data
MediaArchive archive = context.getPageValue("mediaarchive")
Map a = context.getPageValue("analytics"); if (a == null) return
void reply(Map m) { context.putPageValue("json", JsonOutput.toJson(m)) }
String period = context.getRequestParameter("period") ?: "30d"; String topic = context.getRequestParameter("entitytopic") ?: ""; String team = context.getRequestParameter("team") ?: ""
Map f(String view, Map extra = [:]) { [period: period, entitytopic: topic, team: team] + extra }
String pct(Number x) { x == null ? "—" : Math.round(x * 100) + " %" }
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
a.sectionStats.sort { -it.beginners }.take(15).each { s -> add("Subtema «${s.name}» (${s.topic}): personas por nivel, preguntas al tutor, conceptos erróneos", [levels: s.levels, questions: s.questions, misconceptions: s.misconceptions], "mastery", [entitytopic: s.topicId]) }
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
if (!question) { context.getResponse().setStatus(400); reply([ok: false, error: "no question"]); return }
def persona = archive.getData("tutorpersona", archive.getCatalogSettingValue("tutorpersona") ?: "iris")
List history = []
try { history = (new JsonSlurper().parseText(context.getRequestParameter("history") ?: "[]") as List).takeRight(6) } catch (Exception e) {}
def ctx = new org.entermediadb.ai.llm.BaseAgentContext()
ctx.putContextValue("personaname", persona?.getName() ?: "Iris"); ctx.putContextValue("organization", persona?.get("organization") ?: "")
ctx.putContextValue("language", persona?.get("tutorlanguage") ?: "es"); ctx.putContextValue("screen", context.getRequestParameter("screen") ?: "overview")
ctx.putContextValue("facts", JsonOutput.toJson(facts.collect { [id: it.id, label: it.label, value: it.value] })); ctx.putContextValue("history", JsonOutput.toJson(history)); ctx.putContextValue("question", question)
Map out
try { out = archive.getLlmConnection("thinking").callStructure(ctx, "analytics_ask").getMessageStructured() as Map } catch (Exception e) { log.error("analytics ask failed", e); context.getResponse().setStatus(503); reply([ok: false, error: "llm"]); return }
if (out == null) { context.getResponse().setStatus(503); reply([ok: false, error: "llm"]); return }
Map byId = facts.collectEntries { [(it.id): it] }
List cited = ((out.citations ?: []) as List).collect { it.toString() }.findAll { byId.containsKey(it) }.unique()
String answer = (out.answer ?: "").toString()
// Markers the model left in the text but forgot to list still count; unknown ids are stripped from the text.
(answer =~ /\[(f\d+)\]/).each { m, id -> if (byId.containsKey(id) && !(id in cited)) cited << id }
answer = answer.replaceAll(/\[(f\d+)\]/) { m, id -> byId.containsKey(id) ? "[" + id + "]" : "" }
def audit = archive.getSearcher("auditevent"); Data e = audit.createNewData()
e.setValue("datecreated", new Date()); e.setValue("actor", context.getUser().getId()); e.setValue("action", "analytics.ask"); e.setValue("targettype", "analytics"); e.setValue("targetid", context.getRequestParameter("screen") ?: "")
e.setValue("before", JsonOutput.toJson([question: question])); e.setValue("after", JsonOutput.toJson([citations: cited, ok: true])); audit.saveData(e, context.getUser())
reply([ok: true, answer: answer, citations: cited.collect { byId[it] }, followups: ((out.followups ?: []) as List).take(3), model: "thinking"])
```

- [ ] **Step 3: `html/ai/default/calls/analytics_ask.json`**

```json
{
	"model": "${model}",
	"messages": [
		{ "role": "system", "content": #jesc("You are ${personaname}, the training tutor of ${organization}, now helping a training lead or a manager read the analytics console. Answer in the language code \"${language}\".
RULES, all mandatory:
1. Use ONLY the FACTS below. Every number, name or list you mention must come from a fact, and you must put the fact id in square brackets right after it, like [f3]. Never invent a figure, a name or a source.
2. If the facts do not cover the question, say so in one sentence and tell what you can answer instead (activity, mastery by topic and subtopic, teams, people at risk, calibration, questions asked to the tutor). Do not guess.
3. Mastery is described with the level labels (Principiante, Competente, Experto, Sin empezar) and concrete counts, never as invented scores or percentages that are not in the facts.
4. Tone: a calm senior colleague. Short sentences. Lead with the finding that matters most (people who need help, misconceptions, the weakest subtopic). No greetings, no exclamation marks, no emoji.
5. Maximum 120 words unless the user asks for a list.
6. followups: up to three short questions the user could ask next, answerable from the facts.
The user is looking at the console screen \"${screen}\".
FACTS (JSON array of {id, label, value}):
${facts}") },
		{ "role": "user", "content": #jesc("Previous turns (JSON, may be empty): ${history}
Question: ${question}") }
	],
	"response_format": { "type": "json_schema", "json_schema": { "name": "analyst", "strict": true, "schema": {
		"type": "object", "properties": {
			"answer": { "type": "string" },
			"citations": { "type": "array", "items": { "type": "string" } },
			"followups": { "type": "array", "items": { "type": "string" } } },
		"required": ["answer", "citations", "followups"], "additionalProperties": false } } }
}
```

- [ ] **Step 4: `tools/check_ask.sh`** — as admin: `debug=facts` returns ≥ 10 facts with `id`, `label`, `value`, `view`; then a real ask "¿Cuántas personas activas hay esta semana?" and assert `ok` and that at least one citation's `value` equals `overview.cohort.active7d` (compare with `overview.json`), and that every `[fN]` in `answer` is in `citations`; then "¿Cuánto cobra Luis?" → `citations` empty and no digit in `answer` (allow the run to print `SKIP llm` and exit 0 if the first real ask returns 503, so an llamat outage does not block the plan; the ledger records it).

```sh
#!/bin/sh
set -eu
B=${EME_BASE:-http://localhost:8080/site/mediadb}; J=$(mktemp); trap 'rm -f "$J"' EXIT
curl -sf -c "$J" -o /dev/null "$B/services/authentication/login.json" -H 'Content-Type: application/json' -d '{"id":"admin","password":"admin"}'
curl -sf -b "$J" -X POST "$B/services/testu/analytics/ask.json" -d debug=facts -d screen=overview > /tmp/facts.json
curl -sf -b "$J" "$B/services/testu/analytics/overview.json" > /tmp/overview.json
python3 -c "import json;f=json.load(open('/tmp/facts.json'))['facts'];assert len(f)>=10 and all(k in f[0] for k in ('id','label','value','view'));print('ok: facts',len(f))"
code=$(curl -s -o /tmp/ask1.json -w '%{http_code}' -b "$J" -X POST "$B/services/testu/analytics/ask.json" --data-urlencode 'question=¿Cuántas personas activas hay esta semana?' -d screen=overview)
[ "$code" = 503 ] && { echo "SKIP llm: ask returned 503 (llamat down); facts and shapes verified"; exit 0; }
curl -s -o /tmp/ask2.json -b "$J" -X POST "$B/services/testu/analytics/ask.json" --data-urlencode 'question=¿Cuánto cobra Luis al mes?' -d screen=overview
python3 - <<'PY'
import json, re
o = json.load(open('/tmp/overview.json')); a1 = json.load(open('/tmp/ask1.json')); a2 = json.load(open('/tmp/ask2.json'))
assert a1['ok'], a1
ids = {c['id'] for c in a1['citations']}
assert all(m in ids for m in re.findall(r'\[(f\d+)\]', a1['answer'])), (a1['answer'], ids)
assert any(c['value'] == o['cohort']['active7d'] for c in a1['citations']), a1['citations']
assert a2['ok'] and not a2['citations'] and not re.search(r'\d', a2['answer']), a2
print('ok: cited active7d; off-data question answered without figures')
PY
```

- [ ] **Step 5: Deploy, run, commit**

```bash
./deploy.sh && chmod +x tools/check_ask.sh && tools/check_ask.sh
git add html/services/testu/analytics/ask.* html/services/testu/analytics/scripts/ask.groovy html/ai/default/calls/analytics_ask.json tools/check_ask.sh && git commit -m "analytics v1: ask.json — fact-sheet grounded IRIS analyst with verified citations"
```

---

## Task 6: Local demo cohort seeder

**Files:**
- Create: `tools/seed_demo_cohort.py`

**Interfaces:** writes users via `createuser.json` (email `demo.<n>@testu.local`), teams via `saveteam.json`, and `tutoranswer`, `usageevent`, `chatterbox` rows straight into Elasticsearch (`site_catalog`, `_type` = entity) because those entities have no module route. Refuses any `EME_BASE`/`ES` host that is not `localhost`/`127.0.0.1`. `--wipe` deletes only the rows it created (users `demo.*`, ES rows with `user` prefix `demo.`).

- [ ] **Step 1: Write the script** — 24 users in 3 teams (`Operaciones Pisco`, `Mantenimiento`, `Administración Lima`), 21 days of answers (`datecreated` spread over 07:00–20:00, weekday-heavy), per-user profiles (beginner / competent / expert mix, one "at risk" with many `confident` wrong answers, three inactive for 10+ days), `answerconfidence` drawn from `noidea|notsure|mostlysure|confident`, `iscorrect` as `"true"/"false"` strings, `entityquestion`, `componentsection`, `entitytutorial` taken from the local tutorial's real sections and MCQs (read them via ES: `componentsection` with `playbackentitymoduleid=entitytutorial`, `componentcontent` with `componenttype=mcq`); ~60 `chatterbox` question/reply pairs (`functionname=chat_tutor_usercomment`, learner row with `agentcontextvalues` JSON, agent row with `replytoid`, half of the replies ending in `[Guía DDHH, p. 12]`); `usageevent` `resume`/`pause` pairs (5–25 min) and a few `iris_rate`. IDs deterministic (`demo-<kind>-<n>`) so reruns overwrite. After writing, call `recompute.json` as admin and print the `overview.json` cohort line. The script header repeats the constraint: "LOCAL ONLY. Never point this at minsur.genailabs.tech."

Skeleton the implementer completes (all helpers are present in `seed_lists.py`; ES writes use the bulk API):

```python
#!/usr/bin/env python3
"""LOCAL ONLY demo cohort for the console (24 people, 3 teams, 21 days). Refuses non-localhost hosts. --wipe removes what it created."""
import json, os, random, sys, datetime, urllib.request
from http.cookiejar import CookieJar
from urllib.parse import quote, urlparse
BASE = os.environ.get("EME_BASE", "http://localhost:8080/site/mediadb"); ES = os.environ.get("ES", "http://localhost:9200/site_catalog")
for u in (BASE, ES):
    if urlparse(u).hostname not in ("localhost", "127.0.0.1"): sys.exit(f"refusing non-local host: {u}")
random.seed(7)
# ... login (as seed_lists.py), read sections + mcqs from ES, create teams/users, build rows, bulk-index, recompute, print cohort.
```

- [ ] **Step 2: Run and verify** — `python3 tools/seed_demo_cohort.py` then `tools/check_analytics.sh`; `overview.json` must show `cohort.total >= 24` and `iris.questions >= 50` after the event ran (wait 30 s; classification may take a second run).

- [ ] **Step 3: Commit** — `git add tools/seed_demo_cohort.py && git commit -m "analytics v1: local-only demo cohort seeder"`.

---

## Task 7: App — record answers for everyone, usage + rating events, build 1.1.1+6

**Files:**
- Modify: `app-genailabs/lib/testu/testu_live.dart:174`, `lib/main_testu.dart` (`_TestuAppState.didChangeAppLifecycleState`, `_restore`), `lib/testu/testu_session.dart` (`_VerdictExtrasState` only), `pubspec.yaml` (version)
- Create: `lib/testu/testu_usage.dart`
- Test: `test/testu_usage_test.dart`

**Interfaces:**
- Produces: `TestuUsage.open()`, `resume()`, `pause()`, `rate({channel, sectionId, questionId, helpful})`, `flush()`; posts to `services/testu/usage/track.json` (Task 2 contract).

- [ ] **Step 1: Remove the guard** in `testu_live.dart` — replace `if (AuthService.userId != 'diego' || questionId == null) return;` with `if (questionId == null) return;` and update the doc comment above it (the "Diego's own account only" sentence goes; say "Every signed-in learner's attempts are recorded; the server aggregates them for the console.").

- [ ] **Step 2: Failing test `test/testu_usage_test.dart`**

```dart
import 'package:eme_app_package/testing/fake_eme_http.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:genai_labs/testu/testu_usage.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));
  test('open + pause queue two events and flush posts them once', () async {
    final http = FakeEmeHttp();
    http.canned['services/testu/usage/track.json'] = {'ok': true, 'saved': 2};
    final u = TestuUsage(http: http, platform: 'test', appVersion: '1.1.1+6');
    await u.open();
    await Future<void>.delayed(const Duration(milliseconds: 20));
    await u.pause();
    await u.flush();
    final sent = http.posted.single; // FakeEmeHttp records postForm calls
    final events = sent.fields['events']!;
    expect(events, contains('"type":"open"'));
    expect(events, contains('"type":"pause"'));
    expect(events, contains('"seconds":'));
    expect(await u.pending(), 0);
  });
  test('a failed flush keeps the queue', () async {
    final http = FakeEmeHttp(); // no canned reply -> throws
    final u = TestuUsage(http: http, platform: 'test', appVersion: '1.1.1+6');
    await u.rate(channel: 'c', sectionId: 's', questionId: 'q', helpful: true);
    await u.flush();
    expect(await u.pending(), 1);
  });
}
```

If `FakeEmeHttp` has no `posted` recorder, add a minimal one in `eme_app_package/lib/testing/fake_eme_http.dart` (`final posted = <({String path, Map<String, String> fields})>[];` appended in `postForm`) — it is our fork's test helper.

- [ ] **Step 3: Run it to see it fail** — `flutter test test/testu_usage_test.dart` → "testu_usage.dart not found".

- [ ] **Step 4: `lib/testu/testu_usage.dart`**

```dart
import 'dart:convert';
import 'package:eme_app_package/eme_http.dart';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Foreground-time and tutor-rating events for the console's usage analytics.
/// Queue in shared_preferences, flushed in batches; silent on failure.
/// ponytail: one JSON list in prefs, dropped past 500 events; a real store if it ever matters.
class TestuUsage {
  TestuUsage({EmeHttp? http, required this.platform, required this.appVersion}) : _http = http ?? DioEmeHttp();
  final EmeHttp _http;
  final String platform, appVersion;
  static const _key = 'testu_usage_queue';
  String? _session;
  Stopwatch? _fg;

  Future<void> open() async { _start(); await _enqueue({'type': 'open'}); await flush(); }
  Future<void> resume() async { _start(); await _enqueue({'type': 'resume'}); }
  Future<void> pause() async {
    final s = _fg; _fg = null;
    await _enqueue({'type': 'pause', 'seconds': s?.elapsed.inSeconds ?? 0});
    await flush();
  }
  Future<void> rate({required String channel, required String sectionId, String? questionId, required bool helpful}) =>
      _enqueue({'type': 'iris_rate', 'channel': channel, 'componentsection': sectionId, 'entityquestion': questionId ?? '', 'rating': helpful ? 'helpful' : 'nothelpful'});

  void _start() { _session = '${DateTime.now().microsecondsSinceEpoch}-${UniqueKey().hashCode}'; _fg = Stopwatch()..start(); }

  Future<List<Map<String, dynamic>>> _queue() async {
    final p = await SharedPreferences.getInstance();
    return [for (final e in (jsonDecode(p.getString(_key) ?? '[]') as List)) Map<String, dynamic>.from(e as Map)];
  }
  Future<void> _save(List<Map<String, dynamic>> q) async =>
      (await SharedPreferences.getInstance()).setString(_key, jsonEncode(q.length > 500 ? q.sublist(q.length - 500) : q));
  Future<void> _enqueue(Map<String, dynamic> e) async {
    final q = await _queue();
    q.add({...e, 'sessionid': _session ?? '', 'at': DateTime.now().toUtc().toIso8601String(), 'platform': platform, 'appversion': appVersion});
    await _save(q);
  }
  Future<int> pending() async => (await _queue()).length;

  Future<void> flush() async {
    final q = await _queue();
    if (q.isEmpty) return;
    try {
      final r = await _http.postForm('services/testu/usage/track.json', [MapEntry('events', jsonEncode(q.take(200).toList()))]);
      if (r['ok'] == true) await _save(q.skip(200).toList());
    } catch (e) {
      debugPrint('TestU usage flush failed ($e)');
    }
  }
}
```

- [ ] **Step 5: Wire it in `main_testu.dart`** — a file-level `final testuUsage = TestuUsage(platform: defaultTargetPlatform.name, appVersion: '1.1.1+6');` next to the app; in `_TestuAppState.didChangeAppLifecycleState`: on `paused` call `testuUsage.pause()` (before the existing `_leftAt` line), on `resumed` call `testuUsage.resume()`; in `_restore()` after the session is known to be signed in, call `testuUsage.open()`; when `TestuAuth.onSessionEnded` fires nothing extra (unsent events flush on the next open). Events posted while signed out get 401 and stay queued; that is intended.

- [ ] **Step 6: Rating hook in `testu_session.dart`** — inside `_VerdictExtrasState`, where the reaction on a tutor reply is set (`_qMine` changes), add `testuUsage.rate(channel: <the live tutor channel id>, sectionId: <q.sectionId>, questionId: <q.id>, helpful: r == TestuReaction.like)`; read the channel from `testu_live.dart` by exposing `String? get liveTutorChannelId => …` next to `_tutorChannelFor` (the cached `TutorChannel.id`, null when offline). Import `../main_testu.dart`? No: move the `testuUsage` singleton into `testu_usage.dart` as `final testuUsage = TestuUsage(...)` with `appVersion` read from a `const kTestuAppVersion = '1.1.1+6'` in the same file, and import that from both `main_testu.dart` and `testu_session.dart`.

- [ ] **Step 7: Version** — `pubspec.yaml` `version: 1.1.1+6`.

- [ ] **Step 8: Tests** — `flutter test test/testu_usage_test.dart test/testu_session_engine_test.dart test/testu_tutor_channel_test.dart` → pass. `flutter analyze lib/testu/testu_usage.dart lib/main_testu.dart` → no issues.

- [ ] **Step 9: Commit** — `git add -A lib/testu/testu_usage.dart lib/testu/testu_live.dart lib/testu/testu_session.dart lib/main_testu.dart pubspec.yaml test/testu_usage_test.dart ../eme_app_package/lib/testing/fake_eme_http.dart && git commit -m "TestU 1.1.1+6: record every learner's answers; usage and rating events for the console"` (check `git status` first: the pbxproj must not be in the diff).

Store rebuild (`build_store.sh`) is Task 19; it needs the whole plan done so the build carries the final code.

---

## Task 8: Console foundation — `admin_theme.dart`, `admin_nav.dart`, `admin_ui.dart`, `admin_charts.dart`, `fl_chart`

**Files:**
- Create: `lib/admin/admin_theme.dart`, `lib/admin/admin_nav.dart`, `lib/admin/admin_ui.dart`, `lib/admin/admin_charts.dart`
- Modify: `pubspec.yaml` (`fl_chart: ^1.2.0`)
- Test: `test/admin_ui_test.dart`, `test/admin_nav_test.dart`

**Interfaces (produced; every later task uses these names):**

```dart
// admin_theme.dart
class AdminTokens { static Color focus, compare, seriesPositive, seriesNegative, grid, axis, hover; static Color level(String? level); static TextStyle title, eyebrow, body, table, mono(double size), reading; }
// admin_nav.dart
enum Period { d7, d30, d90, pilot }
class AnalyticsFilters extends ChangeNotifier { Period period; String? topic; String? team; DateTime get from; DateTime get to; Map<String, String> get query; void set({Period? period, String? topic, bool clearTopic, String? team, bool clearTeam}); }
class ConsoleRoute { const ConsoleRoute(this.section, {this.entityId, this.highlight}); final String section; final String? entityId; final String? highlight; }
class ConsoleNav extends ValueNotifier<ConsoleRoute> { void go(String section, {String? entityId, String? highlight}); }
// admin_ui.dart
class AdminScaffold extends StatelessWidget { AdminScaffold({required this.org, required this.me, required this.sections /*id,label*/, required this.nav, required this.title, required this.body, this.contextBar, this.endPanel, required this.onSignOut}); }
class ContextBar extends StatelessWidget { ContextBar({required this.filters, required this.topics /*id->name*/, required this.teams /*AdminTeam list*/, this.lockTeam, this.actions = const []}); }
class Reading extends StatelessWidget { Reading({required this.personaName, this.avatarUrl, required this.sentences}); }
class StatBlock extends StatelessWidget { StatBlock({required this.label, required this.value, this.delta, this.deltaPositive, this.spark, this.highlight = false}); }
class StatRow extends StatelessWidget { StatRow(this.blocks); }
class ChartCard extends StatelessWidget { ChartCard({required this.eyebrow, this.legend = const [] /*(color,label)*/, required this.child, this.footnote, this.height = 220, this.trailing}); }
class LevelBar extends StatelessWidget { LevelBar(this.levels /*Map<String,int>*/, {this.height = 6}); }
class LevelLegend extends StatelessWidget {}
class Quad extends StatelessWidget { Quad({required this.cc, required this.cu, required this.ic, required this.iu}); }
class Funnel extends StatelessWidget { Funnel(this.steps /*List<(String label,int value)>*/); }
class AdminTable<T> extends StatefulWidget { AdminTable({required this.columns /*List<AdminColumn<T>>*/, required this.rows, this.onTap, this.emptyText, this.initialSort}); }
class AdminColumn<T> { AdminColumn(this.label, this.cell, {this.sortKey, this.width, this.flex = 1, this.numeric = false}); }
class Select<T> extends StatelessWidget { Select({required this.value, required this.items /*List<(T?,String)>*/, required this.onChanged, this.hint, this.enabled = true}); }
class Segmented<T> extends StatelessWidget { Segmented({required this.value, required this.items /*List<(T,String)>*/, required this.onChanged}); }
class Skeleton extends StatelessWidget { Skeleton({this.lines = 4, this.height}); }
class EmptyState extends StatelessWidget { EmptyState({required this.eyebrow, required this.text, this.action}); }
class ConsolePanelError extends StatelessWidget { ConsolePanelError({required this.text, required this.onRetry}); }
void showToast(BuildContext context, String text, {bool error = false});
class Pulse extends StatefulWidget { Pulse({required this.active, required this.child}); } // one 600 ms focus ring when `active` turns true
Widget crossfade(Widget child) // AnimatedSwitcher 200 ms, 0 under disableAnimations
// admin_charts.dart
LineChartData sparklineData(List<num> values);
Widget activityChart({required List<DayPoint> series, List<DayPoint>? previous, required String peopleLabel, required String answersLabel});
Widget dailyBars(List<DayPoint> series, {required num Function(DayPoint) value, Color? color});
Widget correctIncorrectBars(List<({String label, int correct, int incorrect})> days);
Widget hoursHeatmap(List<(int wd, int h, int n)> cells);
```

- [ ] **Step 1: `pubspec.yaml`** — add `fl_chart: ^1.2.0` under dependencies; `flutter pub get`.

- [ ] **Step 2: `admin_theme.dart`** (spec §5 tokens; all from `TestuTokens.instance`):

```dart
import 'package:flutter/material.dart';
import '../testu/testu_theme.dart';

const _t = TestuTokens.instance;

/// Console extension of the app tokens (spec analytics-v1 §5). Same meanings as the app:
/// orange = the thing in focus, level trio only inside data, hairlines everywhere.
class AdminTokens {
  static final Color focus = _t.orange;
  static final Color compare = _t.mut.withValues(alpha: 0.45);
  static const Color seriesPositive = Color(0xFF3F7D5F);
  static const Color seriesNegative = Color(0xFF8F4444);
  static final Color grid = _t.line;
  static final Color axis = _t.faint;
  static final Color hover = _t.card2;
  static const Color levelNone = Color(0xFF3A3A40);
  static Color level(String? level) => switch (level) { 'expert' => _t.green, 'competent' => _t.amber, 'beginner' => _t.red, _ => levelNone };
  static Color levelTint(String? level) => level == null ? const Color(0xFF141417) : AdminTokens.level(level).withValues(alpha: 0.22);

  static final title = TextStyle(fontFamily: 'Sora', fontWeight: FontWeight.w700, fontSize: 20, letterSpacing: -0.2, color: _t.ink);
  static final reading = TextStyle(fontFamily: 'Sora', fontWeight: FontWeight.w600, fontSize: 15, height: 1.45, color: _t.ink);
  static final body = TextStyle(fontFamily: 'Geist', fontSize: 13, height: 1.5, color: _t.ink);
  static final table = TextStyle(fontFamily: 'Geist', fontSize: 12.5, color: _t.ink);
  static final tableHead = TextStyle(fontFamily: 'Geist', fontSize: 11, fontWeight: FontWeight.w500, color: _t.mut);
  static final muted = TextStyle(fontFamily: 'Geist', fontSize: 12, color: _t.mut);
  static TextStyle mono(double size, {Color? color}) => TextStyle(fontFamily: 'GeistMono', fontWeight: FontWeight.w500, fontSize: size, fontFeatures: const [FontFeature.tabularFigures()], color: color ?? _t.ink);
  static Duration dur(BuildContext c, int ms) => MediaQuery.of(c).disableAnimations ? Duration.zero : Duration(milliseconds: ms);
}
```

- [ ] **Step 3: `admin_nav.dart`** with the interfaces above. `from`/`to`: `d7` = today−6…today, `d30` = today−29, `d90` = today−89, `pilot` = `kPilotStart` (`DateTime(2026, 9, 14)`; `// ponytail: constant; a catalog setting when a second client exists`) to today; `query` → `{'from': yyyy-MM-dd, 'to': yyyy-MM-dd, 'period': name, if topic 'entitytopic', if team 'team'}`. Test `test/admin_nav_test.dart`: `d7` spans 7 days inclusive; `query` omits null filters; `ConsoleNav.go` notifies once.

- [ ] **Step 4: `admin_ui.dart`** — implement every component per spec §5. Non-negotiables in the code: `AdminScaffold` nav is text-only (`Text` items, 12.5 px Geist, `mut`, active = `ink` with a 2 px `focus` line above, exactly like the app's bottom nav), 220 px wide on `card` with a 1 px `line` right edge, org name Sora 13/700 at the top, footer = user name (`muted`), role `TestuPill`, "Cerrar sesión" `TextButton` in `red`; content column `ConstrainedBox(maxWidth: 1280)` with 24 px padding, `Text(title, style: AdminTokens.title)`, then `contextBar`, then `body` in a `ListView`; `endPanel` (nullable) is laid out as a 360 px column right of the content with a 1 px `line` left edge. `AdminTable`: header row 32 px `tableHead`, rows 40 px with a 1 px `line` bottom hairline, `MouseRegion` hover paints `hover`, `InkWell`-free (`GestureDetector` + `FocusableActionDetector` for keyboard: Enter activates `onTap`), sort by tapping a header with a `sortKey`, caret `▲/▾` in `focus`, `emptyText` rendered as one muted row. `Select`: a quiet bordered button (`line2`, 7 px radius, 6 × 12 padding, 11.5 px) opening a `MenuAnchor` styled on `card2` with hairline; `Segmented`: same border, items 6 × 10, selected on `card2`. `Skeleton`: `lines` blocks of `card2` (14 px high, widths 60/85/70/40 %) → wrap content in `crossfade`. `showToast`: `Overlay` entry bottom-right, `card2`, hairline, 4 s, red text when `error`. `Pulse`: `AnimatedContainer` border `focus` → transparent over 600 ms when `active` flips true. `Reading`: 26 px `CircleAvatar` (network `avatarUrl` or a `card2` disc), `TestuEyebrow(personaName.toUpperCase())`, then each sentence in `reading` style with `text-wrap`-like behaviour (plain `Text`, `softWrap`). `StatBlock`: eyebrow, `mono(22)` value, delta in `kLabel` coloured `greenText`/`red`, optional 28 px `spark` (a `LineChart`), separated by 1 px `line` verticals inside `StatRow` (an `IntrinsicHeight(Row)` with hairline top/bottom).

- [ ] **Step 5: `admin_charts.dart`** — `fl_chart` factories with the house theme: transparent backgrounds, `FlGridData(horizontalInterval auto, getDrawingHorizontalLine: (_) => FlLine(color: AdminTokens.grid, strokeWidth: 1))`, no vertical grid, no borders, axis titles in `AdminTokens.mono(9.5, color: AdminTokens.axis)`, tooltips `LineTouchTooltipData(getTooltipColor: (_) => t.card2, tooltipBorder: BorderSide(color: t.line))` with `mono(11)` text, `LineChartBarData(color: AdminTokens.focus, barWidth: 1.8, dotData: hidden, belowBarData: BarAreaData(show: true, color: focus.withValues(alpha: 0.12)))` for the people line, previous period as `AdminTokens.compare` line, answers as `BarChart` bars in `t.mut.withValues(alpha: 0.55)` (stack the `LineChart` over the `BarChart` in a `Stack` with identical `minY/maxY` scaling by two axes: normalise answers to the people axis with a right-side axis label). `duration: AdminTokens.dur(context, 300)` on first build, `Duration.zero` afterwards (keep a `bool _animated` in a `StatefulWidget` wrapper). `hoursHeatmap`: a 7 × 24 `GridView.count` of 14 px cells tinted `focus` at `n/max` alpha (0.08–0.9), mono day labels, tooltip on hover with `n`.

- [ ] **Step 6: Tests `test/admin_ui_test.dart`** (widget): `AdminTable` sorts numerically when a numeric column header is tapped (rows `[('b', 2), ('a', 10)]` → after tap on the numeric header the first row shows `10`); `LevelBar` gives each segment a width proportional to its count (`beginner: 1, competent: 1, expert: 2` → the expert segment's `Expanded.flex == 2`); `Segmented` calls `onChanged` with the tapped value; `showToast` shows and removes the text after 4 s (`tester.pump(const Duration(seconds: 5))`).

- [ ] **Step 7: Run, analyze, commit**

```bash
flutter test test/admin_ui_test.dart test/admin_nav_test.dart && flutter analyze lib/admin
git add pubspec.yaml pubspec.lock lib/admin/admin_theme.dart lib/admin/admin_nav.dart lib/admin/admin_ui.dart lib/admin/admin_charts.dart test/admin_ui_test.dart test/admin_nav_test.dart && git commit -m "console: design system — tokens, nav/filters, components, chart theme (fl_chart)"
```

---

## Task 9: Models + API for the new endpoints

**Files:**
- Modify: `lib/admin/admin_models.dart`, `lib/admin/admin_api.dart`
- Test: `test/admin_api_test.dart` (extend)

**Interfaces (produced):**

```dart
class DayPoint { final DateTime day; final int people, answers, minutes, sessions, certainwrong, questions, correct; }
class Levels { final int notstarted, beginner, competent, expert; int get total; Map<String,int> get asMap; }
class Weakest { final String section, name; final int beginners; }
class TopicStat { final String id, name; final int people; final Levels levels; final Weakest? weakest; }
class TeamStat { final String id, name; final int members, activated, active7d; final Levels levels; final String? weakest; }
class Calibration { final int cc, cu, ic, iu; int get total; double? get calibrated; }
class Gap { final String section, name, topic; final double score; final int beginners, questions, misconceptions, unanswered, people; }
class SectionQ { final String section, name; final int questions; final double? helpfulShare; }
class IrisStats { final int questions, people; final double? citedShare, ratedShare, helpfulShare; final Map<String,int> themes; final List<SectionQ> sections; final List<(String label, int count)> labels; }
class Cohort { final int total, activated, active7d, active30d; }
class Overview { cohort, series, levels, topics, calibration, teams, median (({double activeShare, double expertShare})?), previous (Map<String,int>), gaps, iris }
class InactivePerson { user, name, team, lastActivity }
class Activity { series, funnel (Map<String,int>), hours (List<(int,int,int)>), inactive, iris }
class PersonTopic { id, name, level, mastered, answered, weakest }
class PersonReport { user (AdminUser), rows (List<MasteryRow>), series, calibration, topics, sessions, minutes, activeDays, irisQuestions, irisSections (List<SectionQ>), irisHelpfulShare }
class Citation { id, label, value (String, pre-formatted), view, filters (Map<String,String>) }
class AskReply { answer, citations, followups }
// AdminApi
Future<Overview> overview(Map<String,String> q); Future<Activity> activity(Map<String,String> q); Future<PersonReport> person(String user, Map<String,String> q);
Future<AskReply> ask({required String question, required String screen, String? user, List<Map<String,String>> history = const [], Map<String,String> q = const {}}); // throws AskUnavailable on 503/{ok:false}
```

- [ ] **Step 1: Failing tests** — extend `test/admin_api_test.dart` with canned bodies for `overview.json` (a two-day series, one topic, one team, gaps, iris), `activity.json`, `person.json` and `ask.json`; assert parsed fields, `Levels.total`, `Calibration.calibrated == (cc+ic)/total`, `Citation.value` of a map value is JSON-encoded compactly, and `ask` with `{ok:false,error:'llm'}` throws `AskUnavailable`.

- [ ] **Step 2: Implement** with the same defensive parsing style as the file's existing classes (`(j['x'] as num?)?.toInt() ?? 0`, `'${j['x']}'`). `AdminApi.overview` → `getJson('services/testu/analytics/overview.json', query: q)`, same for `activity`; `person` adds `'user': user`; `ask` → `postForm('services/testu/analytics/ask.json', [...q.entries, MapEntry('question', question), MapEntry('screen', screen), if (user != null) MapEntry('user', user), MapEntry('history', jsonEncode(history))])`.

- [ ] **Step 3: Run, commit** — `flutter test test/admin_api_test.dart`; `git commit -am "console: models and API for overview, activity, person, ask"`.

---

## Task 10: Shell rewrite — sections, routing, drill pages

**Files:**
- Modify: `lib/admin/admin_shell.dart`, `lib/main_admin.dart` (pass `AdminMe`, org name from `me`), `test/admin_shell_test.dart`

**Interfaces:**
- Consumes: Task 8 `AdminScaffold`, `ConsoleNav`, `AnalyticsFilters`.
- Produces: `sectionsFor(AdminMe)` now returns ids `overview`, `activity`, `mastery` (each `analytics_view` + web module `analytics`), `people` (`personas_view`), `teams` (`personas_operate`); drill routes `person` and `team` (rendered only when the caller may see them: `analytics_view`). `AdminShell` owns one `ConsoleNav`, one `AnalyticsFilters`, one shared `_cache` of topics/teams, and the IRIS panel toggle (`bool _iris`, wired in Task 16; until then `endPanel: null`).

- [ ] **Step 1: Update `test/admin_shell_test.dart`** expectations: manager → `['overview', 'activity', 'mastery', 'people']`; training → `['overview', 'activity', 'mastery', 'people', 'teams']`; analytics disabled → `['people']`; plus a widget test that `AdminShell` with a manager `me` renders the nav labels `Overview`, `Activity`, `Mastery`, `People` (English under the test config) and not `Teams`, and that the no-access screen still signs out.

- [ ] **Step 2: Rewrite `admin_shell.dart`**: `sectionsFor` as above with labels `L('Overview','Resumen')`, `L('Activity','Actividad')`, `L('Mastery','Dominio')`, `L('People','Colaboradores')`, `L('Teams','Equipos')`; `_AdminShellState` builds `AdminScaffold(org: widget.me.organization ?? 'TestU', …)` — add `organization` to `AdminMe` from `me.json` if the server sends it, else read the `tutorpersona.organization` through a new `AdminApi.persona()` (`services/module/tutorpersona/data.json`? — no: `me.groovy` already runs server-side: extend it to include `persona: {name, avatar, organization, language}` from `archive.getData("tutorpersona", catalogsetting)` — one line in the plugin, commit there as "me.json: tutor persona"). Body = `ValueListenableBuilder<ConsoleRoute>` switching on `route.section`: `overview` → `AdminOverview`, `activity` → `AdminActivity`, `mastery` → `AdminMastery`, `people` → `AdminPeople`, `teams` → `AdminTeams`, `person` → `AdminPerson(userId: route.entityId!)`, `team` → `AdminTeamPage(teamId: route.entityId!)`; each receives `api`, `me`, `filters`, `nav`. Browser back: push a `history` entry per `go` via `SystemNavigator.routeInformationUpdated(uri: Uri(path: '/${section}', queryParameters: {...}))` and parse `Uri.base` on start (ponytail: no router package; deep links to a section are enough).

- [ ] **Step 3: `main_admin.dart`** — unchanged flow; the `theme: testuTheme()` stays.

- [ ] **Step 4: Run, commit** — `flutter test test/admin_shell_test.dart`; commit "console: shell with nav, shared filters and drill routes".

---

## Task 11: Resumen (`admin_overview.dart`) + reading rules (`admin_reading.dart`)

**Files:**
- Create: `lib/admin/admin_overview.dart`, `lib/admin/admin_reading.dart`
- Test: `test/admin_reading_test.dart`, `test/admin_overview_test.dart`

**Interfaces:**
- Produces: `List<String> overviewReading(Overview o)`, `List<String> activityReading(Activity a, Cohort c)`, `List<String> personReading(PersonReport p)`, `List<String> teamReading(TeamStat t, Overview o)`; `AdminOverview({api, me, filters, nav})`.

- [ ] **Step 1: Failing tests for the rules** (`admin_reading_test.dart`, English under the test config):
  - active 12 of 24 with previous 9 → first sentence `'12 of 24 people active this week, 3 more than the week before.'`;
  - a topic with weakest `Debida diligencia` beginners 6 → `'In Derechos Humanos the weakest subtopic is “Debida diligencia”: Beginner for 6 people.'`;
  - certainwrong sum 9 → `'9 confident but wrong answers in the period: misconceptions, the finding that matters most.'`;
  - cohort 0 answers → single sentence `'Nobody has answered yet. Data appears 15 minutes after the first session.'`;
  - at most three sentences even when every rule fires.

- [ ] **Step 2: `admin_reading.dart`** — pure functions with `L()` pairs (Spanish: `'12 de 24 personas activas esta semana, 3 más que la anterior.'`, `'En {topic} el subtema más débil es «{section}»: Principiante en {n} personas.'`, `'{n} respuestas seguras y erróneas en el periodo: concepto erróneo, el hallazgo que más importa.'`, `'Nadie ha respondido todavía. Los datos aparecen 15 minutos después de la primera sesión.'`). Delta wording: `+n`/`−n` → `'{n} more/fewer than the week before'` / `'{n} más/menos que la anterior'`, `'same as the week before'` / `'igual que la anterior'` when 0. Person rule mirrors the app's tutor greeting (`testu_tutor.dart:_liveGreeting`): last worked section with its score, weakest section; team rule: active share vs median when present.

- [ ] **Step 3: `admin_overview.dart`** — `StatefulWidget` loading `api.overview(filters.query)` on init and on `filters` change (listener; debounce 150 ms); layout per spec §6.1 in this order: `Reading` → `StatRow([StatBlock Activos 7 d (delta vs previous.active7d, spark = series.people last 7), Respuestas (delta vs previous.answers, spark = answers), Minutos en la app (delta text '≈ n min por persona activa'), Conceptos erróneos (delta vs previous.certainwrong, deltaPositive false when up), Preguntas a Iris (iris.questions, delta vs previous.questions)])` → `ChartCard('Actividad diaria', legend: personas activas/respuestas/periodo anterior, child: activityChart(...), footnote)` → `Row` of two `ChartCard`s: "Dominio por tema · personas" (topic rows: name 150 px, `LevelBar`, weakest `'↓ {section} · {n} princ.'` muted) with `LevelLegend`, and "Calibración de confianza · {pct}" (`Quad`) → `ChartCard('Brechas de conocimiento')` rows (name + topic muted, sentence, `LevelBar`; tap → `nav.go('mastery')` after `filters.set(topic: gap.topicId)`) → `ChartCard('Equipos')` with `AdminTable<TeamStat>` (Equipo, Personas, Han empezado %, Activos 7 d, Niveles `LevelBar` 220 px, Tema más débil; `onTap` → `nav.go('team', entityId: t.id)`). States: `Skeleton` while loading, `ConsolePanelError` on failure, `EmptyState` when `cohort.activated == 0` (reading shows the empty sentence and the stat row still renders zeros). Widget test: with a canned overview, the reading's first sentence and the five stat labels render; tapping the team row calls `nav.go('team', …)`.

- [ ] **Step 4: Run, commit** — `flutter test test/admin_reading_test.dart test/admin_overview_test.dart`; commit "console: Resumen with reading header, stat row, activity chart, topics, calibration, gaps, teams".

---

## Task 12: Actividad (`admin_activity.dart`)

**Files:** Create `lib/admin/admin_activity.dart`; test `test/admin_activity_test.dart`.

- [ ] **Step 1:** Layout per spec §6.2 + §6.8: `Reading(activityReading)` → `ChartCard('Adopción', Funnel([Cohorte, Han entrado, Han respondido, Activos 7 d, Activos 30 d]))` → `ChartCard('Actividad diaria', trailing: Segmented(Personas|Respuestas|Minutos|Sesiones), child: dailyBars(series, value: …))`; when the selected series is all zeros and it is minutes/sessions, footnote `L('Available from app version 1.1.1.', 'Disponible desde la versión 1.1.1 de la app.')` → `ChartCard('Cuándo aprenden', hoursHeatmap(hours))` → `Row`: `ChartCard('Uso de Iris', dailyBars(questions) + two StatBlocks Valoradas % / Útiles %)` and `ChartCard('Sobre qué preguntan', horizontal bars per iris.sections (name, questions; helpful % on hover))` → `ChartCard('Temas de las preguntas', LevelBar-style stacked bar over iris.themes with a legend + chip cloud of iris.labels sized 11–15 px by count, footnote L('Aggregated: no individual question is ever shown.', 'Agregado: nunca se muestra ninguna pregunta individual.'))` → `ChartCard('Sin actividad', AdminTable<InactivePerson>(Nombre, Equipo, Última actividad, Días; onTap → nav.go('person', entityId: user)))`. Theme labels: `concept` → `L('Clarify a concept','Aclarar un concepto')`, `procedure` → `L('How it is done','Cómo se hace')`, `example` → `L('Ask for an example','Pedir un ejemplo')`, `source` → `L('Where in the source','Dónde está en la fuente')`, `challenge` → `L('Discuss the answer','Discutir la respuesta')`, `offtopic` → `L('Off topic','Fuera del tema')`, `other`/`unclassified` → `L('Other','Otro')`.

- [ ] **Step 2: Test** — canned activity: the funnel renders five labels, the inactive table has the canned name, switching the segmented control to Minutes with an all-zero series shows the "Available from app version 1.1.1." footnote.

- [ ] **Step 3: Run, commit** — "console: Actividad — adoption funnel, daily series, hours heatmap, Iris usage, inactive list".

---

## Task 13: Dominio rewrite (`admin_mastery.dart`)

**Files:** Modify `lib/admin/admin_mastery.dart`; create `lib/admin/admin_heatmap.dart` (`HeatmapGrid`); update `test/admin_mastery_test.dart`.

**Interfaces:** `HeatmapGrid({required List<HeatCol> cols /*topic, name*/, required List<HeatRow> rows /*id, label, group, cells: List<HeatCell?> (level, mastered, answered, questions)*/, required void Function(String rowId) onRow})`; keep `levelOf(int mastered, int answered)` exported as today (tests use it).

- [ ] **Step 1:** Keep the data helpers (`_people`, `_byUserTopic`, `_byUser`, `_topicNames`) and the report/CSV loading; replace the UI: `Reading(masteryReading)` (from `admin_reading.dart`: weakest topic by beginners and weakest subtopic sentence built from the report rows) → a summary strip (`LevelBar` per subtopic under topic headers, weakest highlighted with a `focus` underline) → `Segmented(Por persona | Por equipo)` → `HeatmapGrid`. Cells: `levelTint` fill, `m/n` in `mono(10.5)` on hover only (default shows nothing but the tint, plus a 1 px inset `ink` ring on hover/focus), tooltip = `card2` box with name · subtopic, level `TestuPill`, `'{m} de {n} preguntas dominadas · {attempts} intentos · última actividad {date}'`; rows grouped by team with a group row whose cells show the team's `LevelBar` for that subtopic; `Por equipo` collapses rows to teams. Sort control: name / team / weakest. Export CSV = existing columns + the four counters (present in `report.json` rows since Task 3? — `report.groovy` is unchanged; add the four fields to its row map in the plugin, one line, commit there "report.json: calibration counters"). Recalcular: call `api.recompute()`, then poll `report.json` every 5 s (max 6) until the max `computedat` changes (add `computedat` to report rows in that same plugin commit), showing an inline `L('Recomputing…','Recalculando…')` next to the button; `showToast` on completion.

- [ ] **Step 2: Tests** — keep the existing `levelOf` and aggregation tests; add: the heatmap renders one column per subtopic present in rows and one group row per team; tapping a person row calls `nav.go('person', …)`; the CSV includes the header `certainwrong`.

- [ ] **Step 3: Run, commit** — plugin commit first (`report.groovy` counters + `computedat`), then app commit "console: Dominio as a person × subtopic heatmap with team groups, summary strip, CSV with calibration".

---

## Task 14: Persona and Equipo pages

**Files:** Create `lib/admin/admin_person.dart`, `lib/admin/admin_team.dart`; tests `test/admin_person_test.dart`, `test/admin_team_test.dart`.

- [ ] **Step 1: `AdminPerson({api, me, filters, nav, userId})`** per spec §6.4: header row (name `title`, team, role `TestuPill`, `'Última actividad {date}'`, `'En la app desde {creationdate}'` when known) → `Reading(personReading)` → `Row`: `ChartCard('Dominio por tema')` rows with `TestuPill` labels exactly as the app (`'Competente · Repasar pronto'` / `'Experto · Estable'` / `'Principiante · Necesita práctica'` / `'Sin empezar'`; colours gold/green/red-outline/gray as `design-language`) + `'{m} de {n} preguntas · revisar {weakest}'`, and `ChartCard('Calibración', Quad)` → `ChartCard('Últimos 30 días', correctIncorrectBars(series))` → usage line: `'{sessions} sesiones · {minutes} min · {activeDays} días activos'` and the Iris line `'{q} preguntas a Iris · sobre {sections joined} · {helpful} útiles'` (omit parts that are null/0) → `AdminTable<MasteryRow>` (Tema, Subtema, Nivel pill, Dominadas/Respondidas/Preguntas, Intentos, Última actividad). 403 → `ConsolePanelError(L('Outside your scope.','Fuera de tu alcance.'))`.

- [ ] **Step 2: `AdminTeamPage({api, me, filters, nav, teamId})`** per §6.5: loads `overview` with `team` forced to `teamId` (a local copy of the filters) and the unfiltered overview for the median; header (name, manager, parent, members) → `Reading(teamReading)` → `StatRow` (Activos 7 d with `'mediana de la organización {pct}'` when `median != null`, Respuestas, Minutos, Preguntas a Iris) → `ChartCard('Actividad', activityChart(team series, previous: null))` → `ChartCard('Sobre qué preguntan')` bars → `AdminTable` of members (from `api.users()` filtered by team, joined with `report.json` rows for level and last activity; columns Nombre, Han empezado, Activos 7 d, Niveles `LevelBar`, Tema más débil, Última actividad; row → person).

- [ ] **Step 3: Tests** — canned person: pill label and the Iris line render; 403 → scope message. Canned team: member row tap navigates.

- [ ] **Step 4: Commit** — "console: Persona and Equipo drill-down pages".

---

## Task 15: Restyle Colaboradores and Equipos on the component system

**Files:** Modify `lib/admin/admin_people.dart`, `lib/admin/admin_teams.dart`; tests `test/admin_people_test.dart` (new), `test/admin_teams_test.dart` (update).

- [ ] **Step 1:** Replace `DataTable` with `AdminTable<AdminUser>` (Nombre, Correo muted, Equipo `Select` when operate else text, Rol `Select` when manage else text, Última actividad, Estado green/faint, actions: `TestuAct('Ver ficha')` → `nav.go('person', …)`, disable icon → a red-text `TextButton`), search field styled as the app's composer pill (`TestuComposer` in facade-less mode is a text input: reuse `TestuComposer(hint: …, onSend: …)`? No — it sends; use a plain `TextField` with `InputDecoration` on `line2` border, 999 radius, 12.5 px). Dialogs keep their content but use `card` background, 18 px radius, hairline; `SnackBar` → `showToast`. Teams: same treatment; the team row gains member count and manager name, and a `TestuAct('Ver equipo')` → `nav.go('team', …)`.

- [ ] **Step 2: Tests** — people: the row action navigates; role `Select` only when `personas_manage`; teams: existing tests pass with `AdminTable` (update finders from `DataRow` to row text).

- [ ] **Step 3:** `grep -rn "DataTable\|DropdownButton\|NavigationRail\|SnackBar\|CircularProgressIndicator" lib/admin` → no matches. Commit "console: Colaboradores and Equipos on the component system".

---

## Task 16: IRIS panel (`admin_iris.dart`) + citations

**Files:** Create `lib/admin/admin_iris.dart`; modify `lib/admin/admin_shell.dart` (toggle + `endPanel`), `lib/admin/admin_ui.dart` (`CitationChip`); test `test/admin_iris_test.dart`.

**Interfaces:** `IrisPanel({api, nav, filters, persona (name, avatar), screen (String), selectedUser (String?), onClose})`; `CitationChip({index, citation, onTap})`. The shell passes `screen = route.section` and `selectedUser = route.section == 'person' ? route.entityId : null`. Toggle: avatar button at the right of the title row (`TestuPressable` with the persona avatar 26 px + name in `kLabel`) and the shortcut `⌘/` / `Ctrl+/` via `CallbackShortcuts`.

- [ ] **Step 1: Failing widget test** — canned `ask.json` reply `{ok:true, answer:'Tres personas destacan. Jorge [f1] lleva 9 días sin entrar.', citations:[{id:'f1',label:'Sin actividad: Jorge',value:'última actividad 2026-08-28',view:'person',filters:{user:'jorge'}}], followups:['¿Qué le recomiendo a Jorge?']}`: after typing a question in the composer and sending, the panel shows the answer with `[1]` rendered (not `[f1]`), one `CitationChip` with label `1 · Sin actividad: Jorge`, a follow-up chip; tapping the citation calls `nav.go('person', entityId: 'jorge', highlight: 'f1')`; a canned `{ok:false,error:'llm'}` shows `Iris is not available right now.` with a retry and keeps the thread.

- [ ] **Step 2: Implement** per spec §6.7 wireframe: header (26 px avatar, name Sora 13/600, subtitle `L('Tutor of {org} · reads this console','Tutora de {org} · analiza esta consola')`, close ✕), privacy line `kNote`, thread `ListView` (`_Turn` = user `TestuYouMsg` or Iris message: `TestuEyebrow(name)` + `RichText(mdSpans(answer with [fN] → superscript-style ' [n]' in focus))` + `Wrap` of `CitationChip`s + `'Ver los datos usados'` `TextButton` in `blue` toggling a two-column facts list (`label` / `value`) + follow-up chips (`TestuPill`-like quiet chips, tap = send)), suggestion chips when the thread is empty (per screen: overview → `¿Quién necesita ayuda esta semana?`, `¿Qué subtema es el más débil?`, `Compara los equipos`; activity → `¿Quién lleva más de 7 días sin entrar?`, `¿Cuándo aprende la gente?`, `¿Sobre qué preguntan a Iris?`; mastery → `¿Qué subtema es el más débil?`, `¿Quién tiene conceptos erróneos?`; person → `¿En qué debería centrarse {nombre}?`, `¿Tiene conceptos erróneos?`; team → `¿Cómo va este equipo frente a la organización?`), typing dots (three `AnimatedOpacity` dots, 850 ms loop, still under `disableAnimations`) while awaiting, `TestuComposer(hint: L('Ask Iris…','Pregunta a Iris…'))` pinned at the bottom, disabled while busy. `history` sent = the last six turns as `{role, text}`. Errors: `AskUnavailable` → the unavailable message + `TestuAct('Reintentar')`. Citation tap → `filters.set(...)` from `citation.filters` (`period`, `entitytopic`, `team`) then `nav.go(view, entityId: filters['user'] ?? filters['team'], highlight: citation.id)`; the target screen wraps its stat row / table in `Pulse(active: route.highlight != null)`.

- [ ] **Step 3: Shell** — `endPanel: _iris ? IrisPanel(...) : null`, state kept in the shell so the thread survives navigation; `crossfade` on open/close.

- [ ] **Step 4: Run, commit** — `flutter test test/admin_iris_test.dart test/admin_shell_test.dart`; "console: IRIS panel — cited answers from the fact sheet, citation navigation, follow-ups".

---

## Task 17: Sign-in restyle, empty/skeleton audit, build into the plugin

**Files:** Modify `lib/admin/admin_signin.dart`; run `build_admin.sh`; plugin `html/admin/**`.

- [ ] **Step 1: Sign-in** — error text in `t.red` (not orange); add `TextButton(L('Change email','Cambiar correo'))` and `L('Resend code','Reenviar código')` in the code stage (seguimiento M9, cheap here); org name (from `tutorpersona.organization` is unknown before sign-in — keep `CONSOLA DE ADMINISTRACIÓN` eyebrow and add the product line `TestU Learn` in `kCaption`).

- [ ] **Step 2: Audit every screen for the three states** (`Skeleton` / `EmptyState` / `ConsolePanelError`) with `FakeEmeHttp` returning empty bodies and throwing; fix what is missing.

- [ ] **Step 3: Build** — `./build_admin.sh` (writes `../eme-plugin-testu/html/admin`), `./deploy.sh` in the plugin, open `http://localhost:8080/site/mediadb/admin/` signed in as `admin` (orgadmin locally) with the demo cohort: every section loads, the IRIS panel answers (or shows the unavailable state if llamat is down).

- [ ] **Step 4: Commit** — app: "console: sign-in restyle, state audit"; plugin: `git add html/admin && git commit -m "console build: analytics v1"`.

---

## Task 18: Screenshot rig + polish pass

**Files:** Create `test/console_shots_test.dart` (tag `shots`), `test/console_shots/*.png`.

- [ ] **Step 1:** Mirror `test/landscape_shots_test.dart`'s font loading and golden setup; render `AdminShell` with a canned `FakeEmeHttp` (the Task 11–16 fixtures, plus the IRIS panel open) at 1280 × 800 and 1024 × 768 for Resumen, Actividad, Dominio, Persona, Equipo, Colaboradores, and the sign-in; write PNGs with `flutter test --update-goldens --run-skipped test/console_shots_test.dart`.

- [ ] **Step 2: Polish** — look at every PNG against the spec wireframes: text overflow in Spanish at 1024, contrast (`faint` only on mono labels), no two label styles on one card, hairlines not doubled, chart tooltips readable, empty states present. Run `/impeccable polish lib/admin` and `/impeccable audit lib/admin` if the session has them; fix findings inline. Re-capture.

- [ ] **Step 3: Commit** — "console: screenshot rig and polish pass".

---

## Task 19: Store build, delivery artefacts, runbook and seguimiento updates

**Files:** app `build_store.sh` output; Traycer artifacts `personas-analytics-v0/runbook-produccion`, `personas-analytics-v0/entrega-builds-entermedia`, `personas-analytics-v0/seguimiento-v0-1`, `analytics-v1/plan`.

- [ ] **Step 1:** `git status` in the app: `project.pbxproj` unchanged (if it shows the objectVersion downgrade, `git checkout -- ios/Runner.xcodeproj/project.pbxproj`). Run `./build_store.sh`; note the two artefact paths.
- [ ] **Step 2:** Update `entrega-builds-entermedia` with version `1.1.1+6` and the reason (answers recorded for every learner; usage events). Update the runbook: plugin `main` HEAD placeholder for the merge the user will do, the Tomcat restart is now also required for the three new field XMLs and two lists, `seed_lists.py` seeds `usageeventtype` and `tutorquestiontheme`, console build path unchanged, and a new smoke step: `overview.json` returns `ok` and `ask.json?debug=facts` returns facts. Add to seguimiento: M12 closed by v1; new parked items from the polish pass; Task 0's local admin role change reverted (`settingsgroupvalue=administrator`) — do the revert.
- [ ] **Step 3:** Mark the Traycer plan ticket `status: 2` and list the commits of both branches (`git log --oneline main..analytics-v1`) in it. Do **not** push or open PRs.
