# Evaluation Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Admins activate a per-topic evaluation blueprint; eligible learners take a server-issued, timed, no-teaching evaluation attempt in the app; the server selects, scores and decides pass/fail; a job-profile row may require the pass for the topic to count as Finished.

**Architecture:** Server-owned. `LearningEngine` gains a pure evaluation core (blueprint validation, pool report, seeded selection, strict scoring, status machine) plus storage for `evaluationattempt` rows. `TestULearningModule` exposes `evaluation.json`, `startevaluation.json`, `submitevaluation.json` (learner) and `evaluationblueprint.json` (admin); `answer.json` / `exposure.json` accept `mode=evaluation` through `resolve()`. The admin console gets an Evaluations section modelled on Progression; the learner app gets an Evaluation card on topic home, an evaluation variant of the session screen and a result screen.

**Tech Stack:** Java (EME/EnterMedia modules, json-simple), EME data XML (fields/lists), Groovy event script, Flutter/Dart (admin console + learner app), shell/Python check scripts.

**Spec:** `plugins/testu/docs/superpowers/specs/2026-09-16-evaluation-mode-design.md` (read it first; it holds the rules this plan implements).

## Global Constraints

- Three git repos: server plugin = `eme-server-minsur/plugins/testu` (its own repo; run git inside it), server = `eme-server-minsur` (webapp mirrors, `bin/sync-testu.sh`, `plugins/finder`), app = `app-genailabs`. Peers may be editing the same working trees: exact-string edits only, never reformat whole files, never `dart format` in app-genailabs, `git add <paths>` explicitly (never `git add -A`), tell the user before restarting Tomcat.
- Product-first: one generic model, no pilot/client-specific logic. With no `evaluationblueprint` rows the engine, endpoints and app behave byte-for-byte as today (`evaluation.status = not_available`, `finished` unchanged, no UI shown).
- Server owns every decision (pool, selection, score, pass, status, timer). Flutter renders; it never scores, never decides eligibility, never reveals correctness during an attempt.
- Names (exact): tables `evaluationblueprint`, `evaluationattempt`; lists `evaluationstrategy` (`random` | `reserved`), `difficultymix` (`proportional` | `balanced`); `topicrequirement.evaluationrequired` (boolean); answer mode `evaluation`; statuses `not_available` | `in_progress` | `passed` | `locked` | `waiting` | `exhausted` | `available`; reasons `not_configured` | `inactive` | `invalid_<field>` | `topic_locked` | `learn_incomplete`; attempt status `inprogress` | `submitted` | `expired`; error codes `evaluation_not_available`, `attempt_closed`, `hints_not_allowed`, `pool_insufficient`, `version_conflict`, `session_expired`, `not_in_session`, `already_answered`, `session_mismatch`, `unknown_session`; `failedrule` `overall` | `subtopic`; pool shortfalls `pool_below_max` | `subtopic_below_min` | `minimums_exceed_max`.
- Blueprint field ranges: `maxquestions` 1–100, `minpersubtopic` ≥ 0, `passpercent` 1–100, `subtopicminpercent` 0–100, `timerminutes` 0–480 (0 = none), `retakewaithours` ≥ 0, `maxattempts` ≥ 0 (0 = unlimited). Untimed attempts expire 24 h after start (`UNTIMED_WINDOW_MS`).
- Scoring: strict, one point per served question, unanswered = wrong, `scorepercent = floor(100 × correct / total + 0.5)`, confidence ignored, hints refused.
- Permissions: blueprint read `training_view` | `training_manage`, write `training_manage`; learner endpoints need a signed-in user only. Endpoints reply `{ok:true,...}` / `{ok:false,error}` via `reply()` / `fail()`.
- Java style: tabs, Allman braces, `inX` parameter prefix. `bin/compile.sh` from the server root compiles; a Tomcat restart serves changed classes (see memory `local-eme-server-ops`).
- Web bundles are committed (no CI): Task 8 rebuilds from a clean worktree and commits both copies.

---

## File map

Server plugin (`eme-server-minsur/plugins/testu`):
- Create `data/fields/evaluationblueprint.xml`, `data/fields/evaluationattempt.xml`, `data/lists/evaluationstrategy.xml`, `data/lists/difficultymix.xml`.
- Modify `data/fields/topicrequirement.xml` — +`evaluationrequired`.
- Modify `code/tech/genailabs/tutor/LearningEngine.java` — constants, `Blueprint`, `EvalAttempt`, reserved questions in `Content`/`Topic`, `Attempt.session`, `Learner.evaluations`, `ProfileRow.evaluationrequired`, `finished` conjunct, pool/selection/scoring/status (pure), storage (load/save/start/finalize/expire), `resolve` evaluation branch, `unavailable` reserved lookup, `topicState.evaluation`.
- Modify `code/tech/genailabs/tutor/TestULearningModule.java` — `evaluation`, `startEvaluation`, `submitEvaluation`, `evaluationBlueprint`; `answer` hint rule + deferred reply + attempt bookkeeping.
- Modify `code/tech/genailabs/tutor/TestUProfileModule.java` — row field `evaluationrequired`.
- Modify `code/tech/genailabs/tutor/TestUAnalyticsModule.java` — person.json evaluation fields.
- Create `html/services/testu/learn/{evaluation,startevaluation,submitevaluation,evaluationblueprint}.{xconf,json}`.
- Modify `catalog/events/scripts/testu/computemastery.groovy` — expire attempts.
- Modify `tools/LearningEngineCheck.java` — `evaluationChecks()`.
- Create `tools/check_evaluation.sh` — server checks.

Server repo (`eme-server-minsur`):
- Modify `bin/sync-testu.sh` — the four new data files; run it to mirror `webapp/WEB-INF/data/...`.
- Modify `plugins/finder/code/org/entermediadb/ai/skills/AdaptiveTutorialAnswerSkill.java` — procedural reply for evaluation answers.

App (`app-genailabs`):
- Modify `lib/admin/admin_models.dart` — `EvaluationBlueprint`, `BlueprintVersion`, `ProfileRow.evaluationRequired`.
- Modify `lib/admin/admin_api.dart` — `evaluationBlueprints()`, `evaluationBlueprint()`, `saveEvaluationBlueprint()`.
- Create `lib/admin/admin_evaluations.dart` — `AdminEvaluations` screen.
- Modify `lib/admin/admin_shell.dart` — section `evaluations`.
- Modify `lib/admin/admin_profiles.dart` — Evaluation column.
- Modify `lib/testu/testu_learn.dart` — `EvaluationState`, `TopicState.evaluation`, `NextResult.expiresAt`, `fetchEvaluation`, `startEvaluation`, `submitEvaluation`, `EvaluationResult`.
- Modify `lib/testu/testu_question_source.dart` — `expiresAt`, `submitEvaluation()` seams.
- Modify `lib/testu/testu_live.dart` — `EmeQuestionSource` evaluation mode.
- Modify `lib/testu/testu_session_engine.dart` — `deferVerdict`.
- Modify `lib/testu/testu_session.dart` — evaluation variant (eyebrow, intro, no hint/chat/verdict, countdown, stop chips, result handoff).
- Create `lib/testu/testu_evaluation_result.dart` — result screen.
- Modify `lib/testu/testu_topics.dart` — Evaluation card on topic home.
- Tests: `test/admin_evaluations_test.dart`, `test/admin_profiles_test.dart` (+1), `test/testu_evaluation_test.dart`, `test/testu_session_evaluation_test.dart`, `test/testu_session_engine_test.dart` (+1).

---

### Task 1: Data definitions

**Files:**
- Create: `plugins/testu/data/fields/evaluationblueprint.xml`
- Create: `plugins/testu/data/fields/evaluationattempt.xml`
- Create: `plugins/testu/data/lists/evaluationstrategy.xml`
- Create: `plugins/testu/data/lists/difficultymix.xml`
- Modify: `plugins/testu/data/fields/topicrequirement.xml`
- Modify: `eme-server-minsur/bin/sync-testu.sh`

**Interfaces:**
- Produces: table ids and field ids used verbatim by every later task (see Global Constraints).

- [ ] **Step 1: Create the blueprint table**

`plugins/testu/data/fields/evaluationblueprint.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Evaluation Mode (spec 2026-09-16-evaluation-mode): one row per topic blueprint version, append-only (id = <topicid>_v<blueprintversion>).
     The highest version is the topic's current blueprint; active=false or no row = evaluation not offered. Written only by
     services/testu/learn/evaluationblueprint.json (training_manage): create-only under the engine write lock, 409 version_conflict when the
     caller's expectedversion is stale, 409 pool_insufficient when activating over a pool that cannot satisfy it. -->
<properties beanname="dataSearcher">
  <property id="id" index="true" stored="true" editable="false" keyword="true"><name><language id="en">ID</language></name></property>
  <property id="entitytopic" index="true" stored="true" editable="false" type="list" listid="entitytopic"><name><language id="en">Topic</language><language id="es">Tema</language></name></property>
  <property id="blueprintversion" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Version</language><language id="es">Versión</language></name></property>
  <property id="active" index="true" stored="true" editable="false" type="boolean" datatype="boolean"><name><language id="en">Active</language><language id="es">Activa</language></name></property>
  <property id="strategy" index="true" stored="true" editable="false" type="list" listid="evaluationstrategy"><name><language id="en">Selection strategy</language><language id="es">Estrategia de selección</language></name></property>
  <property id="maxquestions" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Max questions</language><language id="es">Máximo de preguntas</language></name></property>
  <property id="minpersubtopic" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Min per subtopic</language><language id="es">Mínimo por subtema</language></name></property>
  <property id="excludedsections" index="true" stored="true" editable="false" type="text"><name><language id="en">Excluded subtopics (JSON)</language><language id="es">Subtemas excluidos (JSON)</language></name></property>
  <property id="difficultymix" index="true" stored="true" editable="false" type="list" listid="difficultymix"><name><language id="en">Difficulty mix</language><language id="es">Mezcla de dificultad</language></name></property>
  <property id="passpercent" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Pass %</language><language id="es">% para aprobar</language></name></property>
  <property id="subtopicminpercent" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Subtopic min %</language><language id="es">% mínimo por subtema</language></name></property>
  <property id="timerminutes" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Timer (minutes)</language><language id="es">Tiempo (minutos)</language></name></property>
  <property id="retakewaithours" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Wait between attempts (hours)</language><language id="es">Espera entre intentos (horas)</language></name></property>
  <property id="maxattempts" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Max attempts</language><language id="es">Máximo de intentos</language></name></property>
  <property id="requirelearncomplete" index="true" stored="true" editable="false" type="boolean" datatype="boolean"><name><language id="en">Requires Learn complete</language><language id="es">Requiere Aprender completado</language></name></property>
  <property id="user" index="true" stored="true" editable="false" type="list" listid="user"><name><language id="en">Changed by</language><language id="es">Cambiado por</language></name></property>
  <property id="datecreated" index="true" stored="true" editable="false" type="date"><name><language id="en">Created</language><language id="es">Creado</language></name></property>
</properties>
```

- [ ] **Step 2: Create the attempt table**

`plugins/testu/data/fields/evaluationattempt.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Evaluation Mode (spec 2026-09-16-evaluation-mode): one row per evaluation attempt (id = <user>_<uuid>). Created by
     services/testu/learn/startevaluation.json; answers = JSON {questionid: correct} appended by answer.json (mode evaluation) under the
     engine write lock; finalized once (status submitted | expired, score fields) by submitevaluation.json, by an answer past expiresat,
     or by the computemastery event. questionlist = JSON [{questionid, sectionid, difficulty, position}] in served order. -->
<properties beanname="dataSearcher">
  <property id="id" index="true" stored="true" editable="false" keyword="true"><name><language id="en">ID</language></name></property>
  <property id="user" index="true" stored="true" editable="false" type="list" listid="user"><name><language id="en">User</language><language id="es">Usuario</language></name></property>
  <property id="entitytopic" index="true" stored="true" editable="false" type="list" listid="entitytopic"><name><language id="en">Topic</language><language id="es">Tema</language></name></property>
  <property id="blueprintversion" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Blueprint version</language><language id="es">Versión del blueprint</language></name></property>
  <property id="strategy" index="true" stored="true" editable="false" keyword="true"><name><language id="en">Strategy</language><language id="es">Estrategia</language></name></property>
  <property id="attemptnumber" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Attempt number</language><language id="es">Número de intento</language></name></property>
  <property id="questionlist" index="true" stored="true" editable="false" type="text"><name><language id="en">Questions (JSON)</language><language id="es">Preguntas (JSON)</language></name></property>
  <property id="total" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Questions</language><language id="es">Preguntas</language></name></property>
  <property id="answers" index="true" stored="true" editable="false" type="text"><name><language id="en">Answers (JSON)</language><language id="es">Respuestas (JSON)</language></name></property>
  <property id="status" index="true" stored="true" editable="false" keyword="true"><name><language id="en">Status</language><language id="es">Estado</language></name></property>
  <property id="datecreated" index="true" stored="true" editable="false" type="date"><name><language id="en">Started</language><language id="es">Iniciado</language></name></property>
  <property id="expiresat" index="true" stored="true" editable="false" type="date"><name><language id="en">Expires</language><language id="es">Vence</language></name></property>
  <property id="submitted" index="true" stored="true" editable="false" type="date"><name><language id="en">Submitted</language><language id="es">Entregado</language></name></property>
  <property id="finalizedby" index="true" stored="true" editable="false" keyword="true"><name><language id="en">Finalized by</language><language id="es">Cerrado por</language></name></property>
  <property id="answered" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Answered</language><language id="es">Respondidas</language></name></property>
  <property id="correct" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Correct</language><language id="es">Correctas</language></name></property>
  <property id="scorepercent" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Score %</language><language id="es">Puntaje %</language></name></property>
  <property id="passed" index="true" stored="true" editable="false" type="boolean" datatype="boolean"><name><language id="en">Passed</language><language id="es">Aprobado</language></name></property>
  <property id="subtopicresults" index="true" stored="true" editable="false" type="text"><name><language id="en">Subtopic results (JSON)</language><language id="es">Resultados por subtema (JSON)</language></name></property>
  <property id="exposed" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Exposed questions</language><language id="es">Preguntas ya vistas</language></name></property>
  <property id="reused" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Reused questions</language><language id="es">Preguntas reutilizadas</language></name></property>
  <property id="exposurerisk" index="true" stored="true" editable="false" type="boolean" datatype="boolean"><name><language id="en">Exposure risk</language><language id="es">Riesgo de exposición</language></name></property>
  <property id="inputs" index="true" stored="true" editable="false" type="text"><name><language id="en">Inputs (JSON)</language><language id="es">Entradas (JSON)</language></name></property>
</properties>
```

- [ ] **Step 3: Create the two lists**

`plugins/testu/data/lists/evaluationstrategy.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<evaluationstrategys>
  <evaluationstrategy id="random"><name><![CDATA[Aleatoria por persona]]></name></evaluationstrategy>
  <evaluationstrategy id="reserved"><name><![CDATA[Preguntas reservadas]]></name></evaluationstrategy>
</evaluationstrategys>
```

`plugins/testu/data/lists/difficultymix.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<difficultymixs>
  <difficultymix id="proportional"><name><![CDATA[Proporcional al banco]]></name></difficultymix>
  <difficultymix id="balanced"><name><![CDATA[Equilibrada]]></name></difficultymix>
</difficultymixs>
```

- [ ] **Step 4: Add `evaluationrequired` to the profile row**

In `plugins/testu/data/fields/topicrequirement.xml`, after the exact line

```xml
  <property id="afterfinish" index="true" stored="true" editable="true" type="list" listid="afterfinish"><name><language id="en">After finish</language><language id="es">Al terminar</language></name></property>
```

insert

```xml
  <property id="evaluationrequired" index="true" stored="true" editable="true" type="boolean" datatype="boolean"><name><language id="en">Evaluation required</language><language id="es">Evaluación requerida</language></name></property>
```

- [ ] **Step 5: Register the files in the sync script and mirror them**

In `eme-server-minsur/bin/sync-testu.sh`, after the exact line

```sh
	"data/fields/dailychallengeset.xml         $F/fields/dailychallengeset.xml"
```

insert

```sh
	"data/fields/evaluationblueprint.xml       $F/fields/evaluationblueprint.xml"
	"data/fields/evaluationattempt.xml         $F/fields/evaluationattempt.xml"
```

and after the exact line

```sh
	"data/lists/jobrole.xml                    $F/lists/jobrole.xml"
```

insert

```sh
	"data/lists/evaluationstrategy.xml         $F/lists/evaluationstrategy.xml"
	"data/lists/difficultymix.xml              $F/lists/difficultymix.xml"
```

Run from `eme-server-minsur`: `bin/sync-testu.sh` then `bin/sync-testu.sh --check`. Expected: the second prints no drift and exits 0.

- [ ] **Step 6: Commit (both repos, explicit paths)**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/testu && git add data/fields/evaluationblueprint.xml data/fields/evaluationattempt.xml data/lists/evaluationstrategy.xml data/lists/difficultymix.xml data/fields/topicrequirement.xml && git commit -m "feat(evaluation): blueprint and attempt tables, strategy/mix lists, evaluationrequired row field"
```

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && git add bin/sync-testu.sh webapp/WEB-INF/data/site/catalog/fields/evaluationblueprint.xml webapp/WEB-INF/data/site/catalog/fields/evaluationattempt.xml webapp/WEB-INF/data/site/catalog/fields/topicrequirement.xml webapp/WEB-INF/data/site/catalog/lists/evaluationstrategy.xml webapp/WEB-INF/data/site/catalog/lists/difficultymix.xml && git commit -m "feat(evaluation): mirror evaluation data files"
```

---

### Task 2: Engine — pure evaluation core with checks

**Files:**
- Modify: `plugins/testu/code/tech/genailabs/tutor/LearningEngine.java`
- Modify: `plugins/testu/tools/LearningEngineCheck.java`

**Interfaces:**
- Consumes: `Topic`, `Section`, `Question`, `Learner`, `Attempt`, `ProfileRow`, `finished()`, `mastery()`, `iso()`, `LEVELS`, `intOr()`.
- Produces (used by Tasks 3–4): `LearningEngine.EVALUATION`, `ACCEPTED_MODES`, `STRATEGIES`, `MIXES`, `UNTIMED_WINDOW_MS`; `Blueprint` (fields above, `usable()`, `toJson()`), `static String invalidField(Blueprint)`, `static Blueprint settle(Blueprint)`; `EvalAttempt` (fields, `open(Date)`, `finalized()`, `toResultJson()`); `Topic.blueprint`, `Topic.evaluationrequired`, `Topic.reserved`, `Content.reserved`, `Attempt.session`, `Learner.evaluations`, `ProfileRow.evaluationrequired`; `static List<Question> evaluationPool(Topic, Blueprint)`, `static JSONObject poolReport(Topic, Blueprint)`, `static JSONObject buildEvaluation(Topic, Blueprint, Learner, long seed)`, `static JSONObject scoreEvaluation(EvalAttempt, Blueprint, Map<String,String> titles)`, `static JSONObject evaluationStatus(Topic, Learner, Date)`, `static boolean evaluationPassed(Learner, String topicid)`, `static EvalAttempt latestFinalized(Learner, String)`, `static EvalAttempt openAttempt(Learner, String, Date)`, `static boolean learnComplete(Topic, Learner)`.

- [ ] **Step 1: Constants, model fields**

After the exact line `	public static final int[] DEFAULT_THRESHOLDS = {60, 85};` insert:

```java
	// Evaluation Mode (spec 2026-09-16-evaluation-mode-design)
	public static final String EVALUATION = "evaluation";
	/** Modes answer.json / exposure.json accept: the learning modes plus evaluation. */
	public static final Set<String> ACCEPTED_MODES = Set.of("learn", "dailychallenge", "improve", EVALUATION);
	public static final Set<String> STRATEGIES = Set.of("random", "reserved");
	public static final Set<String> MIXES = Set.of("proportional", "balanced");
	/** An attempt without a timer stays open this long: one sitting = one bounded window. ponytail: a catalog setting if an org asks. */
	public static final long UNTIMED_WINDOW_MS = 24L * 3600 * 1000;
```

In `Topic`, after the exact line `		public boolean mandatory, requiresprevious, locked, finished, removed;` insert:

```java
		// Evaluation Mode: the topic's current blueprint (null before loadContent / in pure fixtures = not configured), whether the
		// learner's merged profile row requires the pass, and the evaluation-reserved questions (outside the learning sequence).
		public Blueprint blueprint;
		public boolean evaluationrequired;
		public List<Question> reserved = new ArrayList<>();
```

In `Content`, after the exact line `		public Map<String, Question> questions = new HashMap<>();` insert:

```java
		public Map<String, Question> reserved = new HashMap<>(); // evaluation-reserved questions by id (never in questions/sections)
```

In `Attempt` (class starting `public static class Attempt`), after the exact line `		public int hintlevel;` insert:

```java
		public String session; // learningsession id (an evaluationattempt id for mode evaluation); null on legacy rows
```

In `attemptOf`, after the exact line `		t.hintlevel = Math.max(0, Math.min(3, intOr(a.get("hintlevel"), 0)));` insert:

```java
		t.session = a.get("learningsession");
```

In `Learner`, after the exact line `		public String primaryjobrole; // orders the assignment; null = none (extras alone, by name)` insert:

```java
		public List<EvalAttempt> evaluations = new ArrayList<>(); // the learner's evaluation attempts, oldest first (loadLearner)
```

In `ProfileRow`, replace the exact line `		public boolean mandatory = true, requiresprevious;` with:

```java
		public boolean mandatory = true, requiresprevious, evaluationrequired;
```

In `rowOf`, after the exact line `		r.afterfinish = "remove".equals(d.get("afterfinish")) ? "remove" : "keep";` insert:

```java
		r.evaluationrequired = "true".equals(String.valueOf(d.get("evaluationrequired")));
```

In `applyProfiles`, replace the exact block

```java
					t.mandatory |= r.mandatory;
					if ("keep".equals(r.afterfinish))
```

with

```java
					t.mandatory |= r.mandatory;
					t.evaluationrequired |= r.evaluationrequired;
					if ("keep".equals(r.afterfinish))
```

and replace the exact line `					t.afterfinish = r.afterfinish;` with

```java
					t.afterfinish = r.afterfinish;
					t.evaluationrequired = r.evaluationrequired;
```

- [ ] **Step 2: `finished` gains the evaluation conjunct**

Replace the exact block

```java
	/** Learn complete AND (no level, or band >= assignedlevel with expert evidence when expert). */
	public static boolean finished(Topic t, Learner l)
	{
		Mastery m = mastery(t.questions, l, t.competentmin, t.expertmin);
```

with

```java
	/** Learn complete AND (no level, or band >= assignedlevel with expert evidence when expert) AND (evaluation not required, or passed). */
	public static boolean finished(Topic t, Learner l)
	{
		if (t.evaluationrequired && !evaluationPassed(l, t.id))
		{
			return false;
		}
		Mastery m = mastery(t.questions, l, t.competentmin, t.expertmin);
```

- [ ] **Step 3: The pure evaluation section**

Insert the whole block below immediately before the exact line `	// ---------------------------------------------------------------- subtopic progression`:

```java
	// ---------------------------------------------------------------- evaluation (spec 2026-09-16-evaluation-mode-design)

	/** A topic's evaluation blueprint = its highest evaluationblueprint version. reason null = active and valid (usable). */
	public static class Blueprint
	{
		public String topicid, strategy = "random", mix = "proportional", user;
		/** not_configured | inactive | invalid_<field>; null = usable. */
		public String reason = "not_configured";
		public int version, maxquestions = 20, minpersubtopic, passpercent = 70, subtopicminpercent, timerminutes, retakewaithours, maxattempts;
		public boolean active, requirelearncomplete = true;
		public Set<String> excludedsections = new HashSet<>();
		public Date created;

		public boolean usable()
		{
			return reason == null;
		}

		public JSONObject toJson()
		{
			JSONObject o = new JSONObject();
			o.put("version", version);
			o.put("active", active);
			o.put("strategy", strategy);
			o.put("maxquestions", maxquestions);
			o.put("minpersubtopic", minpersubtopic);
			o.put("difficultymix", mix);
			o.put("passpercent", passpercent);
			o.put("subtopicminpercent", subtopicminpercent);
			o.put("timerminutes", timerminutes);
			o.put("retakewaithours", retakewaithours);
			o.put("maxattempts", maxattempts);
			o.put("requirelearncomplete", requirelearncomplete);
			JSONArray ex = new JSONArray();
			ex.addAll(new java.util.TreeSet<>(excludedsections));
			o.put("excludedsections", ex);
			o.put("reason", reason);
			return o;
		}
	}

	/** The first field of b outside its allowed range or list, or null when every value is allowed. */
	public static String invalidField(Blueprint b)
	{
		if (!STRATEGIES.contains(b.strategy))
		{
			return "strategy";
		}
		if (!MIXES.contains(b.mix))
		{
			return "difficultymix";
		}
		if (b.maxquestions < 1 || b.maxquestions > 100)
		{
			return "maxquestions";
		}
		if (b.minpersubtopic < 0)
		{
			return "minpersubtopic";
		}
		if (b.passpercent < 1 || b.passpercent > 100)
		{
			return "passpercent";
		}
		if (b.subtopicminpercent < 0 || b.subtopicminpercent > 100)
		{
			return "subtopicminpercent";
		}
		if (b.timerminutes < 0 || b.timerminutes > 480)
		{
			return "timerminutes";
		}
		if (b.retakewaithours < 0)
		{
			return "retakewaithours";
		}
		if (b.maxattempts < 0)
		{
			return "maxattempts";
		}
		return null;
	}

	/** Sets b.reason from its values: invalid_<field> (stored data the endpoint would refuse), inactive, or null = usable. */
	public static Blueprint settle(Blueprint b)
	{
		String bad = invalidField(b);
		b.reason = bad != null ? "invalid_" + bad : !b.active ? "inactive" : null;
		return b;
	}

	/** One evaluation attempt (table evaluationattempt). answers = question -> correct, as accepted by answer.json. */
	public static class EvalAttempt
	{
		public String id, user, topicid, strategy, status = "inprogress", finalizedby;
		public int version, number, total, answered, correct, scorepercent, exposed, reused;
		public boolean passed;
		public List<String> questions = new ArrayList<>(); // served order
		public Map<String, String> sectionOf = new HashMap<>(); // question -> section, as resolved at start
		public Map<String, Boolean> answers = new LinkedHashMap<>();
		public Date created, expires, submitted;
		public JSONArray subtopicresults = new JSONArray();
		public JSONObject inputs = new JSONObject();

		public boolean finalized()
		{
			return !"inprogress".equals(status);
		}

		/** Still answerable at inNow. */
		public boolean open(Date inNow)
		{
			return !finalized() && (expires == null || !expires.before(inNow));
		}

		/** {attemptid, number, status, datecreated, submitted, scorepercent, passed, correct, answered, total, subtopics}. */
		public JSONObject toResultJson()
		{
			JSONObject o = new JSONObject();
			o.put("attemptid", id);
			o.put("number", number);
			o.put("status", status);
			o.put("datecreated", iso(created));
			o.put("submitted", iso(submitted));
			o.put("scorepercent", scorepercent);
			o.put("passed", passed);
			o.put("correct", correct);
			o.put("answered", answered);
			o.put("total", total);
			o.put("subtopics", subtopicresults);
			return o;
		}
	}

	/** Questions b may draw from: the sequence (random) or the reserved set (reserved), renderable, in a covered subtopic. */
	public static List<Question> evaluationPool(Topic t, Blueprint b)
	{
		List<Question> out = new ArrayList<>();
		for (Question q : "reserved".equals(b.strategy) ? t.reserved : t.questions)
		{
			if (q.contentproblem == null && !b.excludedsections.contains(q.sectionid))
			{
				out.add(q);
			}
		}
		return out;
	}

	/**
	 * Pure. {size, sufficient, shortfall, bysection:[{id, title, position, covered, sequence, reserved, inpool}], bydifficulty:{beginner,
	 * competent, expert}}. Sufficient = size >= maxquestions, every covered subtopic with pool questions has >= minpersubtopic of them,
	 * and the per-subtopic minimums fit in maxquestions. shortfall names the first failed rule (pool_below_max | subtopic_below_min |
	 * minimums_exceed_max) or is null.
	 */
	public static JSONObject poolReport(Topic t, Blueprint b)
	{
		List<Question> pool = evaluationPool(t, b);
		JSONObject o = new JSONObject();
		o.put("size", pool.size());
		String shortfall = pool.size() >= b.maxquestions ? null : "pool_below_max";
		JSONArray sections = new JSONArray();
		int minsum = 0, pos = 0;
		for (Section s : t.sections)
		{
			int seq = 0, res = 0, inpool = 0;
			for (Question q : s.questions)
			{
				if (q.contentproblem == null)
				{
					seq++;
				}
			}
			for (Question q : t.reserved)
			{
				if (s.id.equals(q.sectionid) && q.contentproblem == null)
				{
					res++;
				}
			}
			for (Question q : pool)
			{
				if (s.id.equals(q.sectionid))
				{
					inpool++;
				}
			}
			JSONObject so = new JSONObject();
			so.put("id", s.id);
			so.put("title", s.title);
			so.put("position", ++pos);
			so.put("covered", !b.excludedsections.contains(s.id));
			so.put("sequence", seq);
			so.put("reserved", res);
			so.put("inpool", inpool);
			sections.add(so);
			if (b.minpersubtopic > 0 && inpool > 0)
			{
				minsum += Math.min(b.minpersubtopic, inpool);
				if (inpool < b.minpersubtopic && shortfall == null)
				{
					shortfall = "subtopic_below_min";
				}
			}
		}
		if (minsum > b.maxquestions && shortfall == null)
		{
			shortfall = "minimums_exceed_max";
		}
		JSONObject diff = new JSONObject();
		for (String d : LEVELS)
		{
			int n = 0;
			for (Question q : pool)
			{
				if (d.equals(q.difficulty))
				{
					n++;
				}
			}
			diff.put(d, n);
		}
		o.put("bysection", sections);
		o.put("bydifficulty", diff);
		o.put("sufficient", shortfall == null);
		o.put("shortfall", shortfall);
		return o;
	}

	/**
	 * Pure. Draws an attempt's questions for t under b for l: n = min(maxquestions, pool); coverage first (minpersubtopic per covered
	 * subtopic, subtopics in content order), then the difficulty mix over the remaining slots (proportional = shuffled pool order,
	 * balanced = round-robin beginner/competent/expert). Rank 0 (never in one of l's earlier attempts of t) always precedes rank 1;
	 * ties follow a shuffle seeded with inSeed, so the same seed gives the same list. Presentation order = subtopic content order,
	 * then pick order. Returns {items:[{questionid, sectionid, componentid, tutorialid, topicid, difficulty, position}], inputs:{n,
	 * pool, seed, relaxations, coverage, mix}, exposed, reused}.
	 */
	public static JSONObject buildEvaluation(Topic t, Blueprint b, Learner l, long inSeed)
	{
		List<Question> pool = evaluationPool(t, b);
		Collections.shuffle(pool, new java.util.Random(inSeed));
		Set<String> usedBefore = new HashSet<>();
		for (EvalAttempt a : l.evaluations)
		{
			if (t.id.equals(a.topicid))
			{
				usedBefore.addAll(a.questions);
			}
		}
		pool.sort(Comparator.comparingInt(q -> usedBefore.contains(q.id) ? 1 : 0)); // stable: the shuffle decides ties
		int n = Math.min(b.maxquestions, pool.size());
		List<Question> picked = new ArrayList<>();
		JSONArray relax = new JSONArray();
		JSONObject coverage = new JSONObject();
		if (b.minpersubtopic > 0)
		{
			for (Section s : t.sections)
			{
				int taken = 0, available = 0;
				for (Question q : pool)
				{
					if (s.id.equals(q.sectionid))
					{
						available++;
						if (taken < b.minpersubtopic && picked.size() < n)
						{
							picked.add(q);
							taken++;
						}
					}
				}
				coverage.put(s.id, taken);
				if (available > 0 && taken < b.minpersubtopic && !relax.contains("minpersubtopic_short"))
				{
					relax.add("minpersubtopic_short");
				}
			}
		}
		List<Question> rest = new ArrayList<>();
		for (Question q : pool)
		{
			if (!picked.contains(q))
			{
				rest.add(q);
			}
		}
		int remaining = n - picked.size();
		if ("balanced".equals(b.mix))
		{
			Map<String, List<Question>> buckets = new LinkedHashMap<>();
			for (String d : LEVELS)
			{
				buckets.put(d, new ArrayList<>());
			}
			for (Question q : rest)
			{
				buckets.get(LEVELS.contains(q.difficulty) ? q.difficulty : "beginner").add(q);
			}
			for (List<Question> bk : buckets.values())
			{
				if (bk.size() < remaining / 3 && !relax.contains("mix_short"))
				{
					relax.add("mix_short");
				}
			}
			boolean any = true;
			while (picked.size() < n && any)
			{
				any = false;
				for (String d : LEVELS)
				{
					List<Question> bk = buckets.get(d);
					if (!bk.isEmpty() && picked.size() < n)
					{
						picked.add(bk.remove(0));
						any = true;
					}
				}
			}
		}
		else
		{
			for (Question q : rest)
			{
				if (picked.size() >= n)
				{
					break;
				}
				picked.add(q);
			}
		}
		Map<String, Integer> sectionIndex = new HashMap<>();
		for (Section s : t.sections)
		{
			sectionIndex.put(s.id, sectionIndex.size());
		}
		picked.sort(Comparator.comparingInt(q -> sectionIndex.getOrDefault(q.sectionid, Integer.MAX_VALUE))); // stable
		JSONArray items = new JSONArray();
		int exposed = 0, reused = 0, pos = 0;
		for (Question q : picked)
		{
			JSONObject i = new JSONObject();
			i.put("questionid", q.id);
			i.put("sectionid", q.sectionid);
			i.put("componentid", q.componentid);
			i.put("tutorialid", q.tutorialid);
			i.put("topicid", q.topicid);
			i.put("difficulty", q.difficulty);
			i.put("position", ++pos);
			items.add(i);
			if (l.lastShown.containsKey(q.id))
			{
				exposed++;
			}
			if (usedBefore.contains(q.id))
			{
				reused++;
			}
		}
		if (reused > 0)
		{
			relax.add("reused");
		}
		JSONObject inputs = new JSONObject();
		inputs.put("n", n);
		inputs.put("pool", pool.size());
		inputs.put("seed", inSeed);
		inputs.put("relaxations", relax);
		inputs.put("coverage", coverage);
		inputs.put("mix", b.mix);
		inputs.put("strategy", b.strategy);
		JSONObject out = new JSONObject();
		out.put("items", items);
		out.put("inputs", inputs);
		out.put("exposed", exposed);
		out.put("reused", reused);
		return out;
	}

	private static int percentOf(int inCorrect, int inTotal)
	{
		return inTotal == 0 ? 0 : (int) Math.floor(100.0 * inCorrect / inTotal + 0.5);
	}

	/**
	 * Pure strict scoring of a: one point per served question, unanswered = wrong, confidence ignored. passed = score >= passpercent
	 * AND (subtopicminpercent == 0 or every served subtopic >= it). Returns {total, answered, correct, scorepercent, passed, failedrule
	 * (overall | subtopic | null), subtopics:[{id, title, questions, correct, percent, met}], weakest:[section ids, lowest first, max 3]}.
	 */
	public static JSONObject scoreEvaluation(EvalAttempt a, Blueprint b, Map<String, String> inSectionTitles)
	{
		int correct = 0, answered = 0;
		Map<String, int[]> bySection = new LinkedHashMap<>(); // section -> {questions, correct}
		for (String qid : a.questions)
		{
			int[] c = bySection.computeIfAbsent(a.sectionOf.getOrDefault(qid, ""), k -> new int[2]);
			c[0]++;
			Boolean ok = a.answers.get(qid);
			if (ok != null)
			{
				answered++;
				if (ok)
				{
					correct++;
					c[1]++;
				}
			}
		}
		int total = a.questions.size();
		int score = percentOf(correct, total);
		boolean passed = score >= b.passpercent;
		String failedrule = passed ? null : "overall";
		JSONArray subs = new JSONArray();
		List<JSONObject> order = new ArrayList<>();
		for (Map.Entry<String, int[]> e : bySection.entrySet())
		{
			int[] c = e.getValue();
			int pct = percentOf(c[1], c[0]);
			boolean met = b.subtopicminpercent == 0 || pct >= b.subtopicminpercent;
			if (!met && passed)
			{
				passed = false;
				failedrule = "subtopic";
			}
			JSONObject so = new JSONObject();
			so.put("id", e.getKey());
			so.put("title", inSectionTitles.getOrDefault(e.getKey(), e.getKey()));
			so.put("questions", c[0]);
			so.put("correct", c[1]);
			so.put("percent", pct);
			so.put("met", met);
			subs.add(so);
			order.add(so);
		}
		order.sort(Comparator.comparingInt(o -> (Integer) o.get("percent")));
		JSONArray weakest = new JSONArray();
		for (JSONObject o : order)
		{
			if (weakest.size() < 3)
			{
				weakest.add(o.get("id"));
			}
		}
		JSONObject r = new JSONObject();
		r.put("total", total);
		r.put("answered", answered);
		r.put("correct", correct);
		r.put("scorepercent", score);
		r.put("passed", passed);
		r.put("failedrule", failedrule);
		r.put("subtopics", subs);
		r.put("weakest", weakest);
		return r;
	}

	/** The learner's newest finalized attempt of the topic, or null. */
	public static EvalAttempt latestFinalized(Learner l, String inTopicid)
	{
		EvalAttempt out = null;
		for (EvalAttempt a : l.evaluations)
		{
			if (inTopicid.equals(a.topicid) && a.finalized())
			{
				out = a; // oldest first: the last match wins
			}
		}
		return out;
	}

	/** The learner's resumable attempt of the topic at inNow (in progress, not past expiresat), or null. */
	public static EvalAttempt openAttempt(Learner l, String inTopicid, Date inNow)
	{
		for (EvalAttempt a : l.evaluations)
		{
			if (inTopicid.equals(a.topicid) && a.open(inNow))
			{
				return a;
			}
		}
		return null;
	}

	public static boolean evaluationPassed(Learner l, String inTopicid)
	{
		EvalAttempt a = latestFinalized(l, inTopicid);
		return a != null && a.passed;
	}

	/** Every sequence question of t answered in learn or dailychallenge. */
	public static boolean learnComplete(Topic t, Learner l)
	{
		for (Question q : t.questions)
		{
			if (!l.answeredInSequence.contains(q.id))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Pure. The learner's evaluation status on t at inNow, precedence: not_available (no usable blueprint), in_progress (open attempt),
	 * passed (terminal in v1), locked (topic_locked | learn_incomplete), waiting (retakewaithours after a failed attempt), exhausted
	 * (maxattempts reached), available. Returns {status, reason, canstart, required, passed, passedat, scorepercent, attempts,
	 * attemptsleft, nextallowedat, inprogress:{attemptid, expiresat, total, answered} | null, lastresult | null}.
	 */
	public static JSONObject evaluationStatus(Topic t, Learner l, Date inNow)
	{
		Blueprint b = t.blueprint;
		JSONObject o = new JSONObject();
		o.put("required", t.evaluationrequired);
		int finalized = 0;
		for (EvalAttempt a : l.evaluations)
		{
			if (t.id.equals(a.topicid) && a.finalized())
			{
				finalized++;
			}
		}
		EvalAttempt last = latestFinalized(l, t.id);
		o.put("attempts", finalized);
		o.put("passed", last != null && last.passed);
		o.put("passedat", last != null && last.passed ? iso(last.submitted) : null);
		o.put("scorepercent", last == null ? null : Integer.valueOf(last.scorepercent));
		o.put("lastresult", last == null ? null : last.toResultJson());
		o.put("attemptsleft", b == null || b.maxattempts == 0 ? null : Integer.valueOf(Math.max(0, b.maxattempts - finalized)));
		o.put("nextallowedat", null);
		o.put("inprogress", null);
		EvalAttempt open = openAttempt(l, t.id, inNow);
		String status, reason = null;
		if (b == null || !b.usable())
		{
			status = "not_available";
			reason = b == null ? "not_configured" : b.reason;
		}
		else if (open != null)
		{
			status = "in_progress";
			JSONObject ip = new JSONObject();
			ip.put("attemptid", open.id);
			ip.put("expiresat", iso(open.expires));
			ip.put("total", open.total);
			ip.put("answered", open.answers.size());
			o.put("inprogress", ip);
		}
		else if (last != null && last.passed)
		{
			status = "passed";
		}
		else if (t.locked)
		{
			status = "locked";
			reason = "topic_locked";
		}
		else if (b.requirelearncomplete && !learnComplete(t, l))
		{
			status = "locked";
			reason = "learn_incomplete";
		}
		else if (last != null && last.submitted != null && b.retakewaithours > 0 && inNow.before(new Date(last.submitted.getTime() + b.retakewaithours * 3600_000L)))
		{
			status = "waiting";
			o.put("nextallowedat", iso(new Date(last.submitted.getTime() + b.retakewaithours * 3600_000L)));
		}
		else if (b.maxattempts > 0 && finalized >= b.maxattempts)
		{
			status = "exhausted";
		}
		else
		{
			status = "available";
		}
		o.put("status", status);
		o.put("reason", reason);
		o.put("canstart", "available".equals(status));
		return o;
	}

```

- [ ] **Step 4: Compile**

From `eme-server-minsur`: `bin/compile.sh`. Expected: no errors (the checks below compile the class from `build/`).

- [ ] **Step 5: Pure checks**

In `tools/LearningEngineCheck.java`, after the exact line `		profileChecks();` insert `		evaluationChecks();`. Add the import `import tech.genailabs.tutor.LearningEngine.Blueprint;` and `import tech.genailabs.tutor.LearningEngine.EvalAttempt;` after the exact line `import tech.genailabs.tutor.LearningEngine.Attempt;`. Then insert before the exact line `	// ---- fixtures: 2 topics x 10 questions (one section each), both required, min 5 / max 20`:

```java
	// ---- Evaluation Mode (spec 2026-09-16-evaluation-mode-design): pool, sufficiency, selection, scoring, status, Finished conjunct

	static void evaluationChecks()
	{
		Topic t = evalTopic();
		Blueprint b = bp("random", 9, 2, "balanced", 70, 0, 0, 0, 0, true);

		// pool
		ok("eval pool random = renderable sequence questions minus excluded subtopics", LearningEngine.evaluationPool(t, b).size() == 17, LearningEngine.evaluationPool(t, b).size());
		Blueprint ex = bp("random", 9, 2, "balanced", 70, 0, 0, 0, 0, true);
		ex.excludedsections.add("es3");
		ok("eval pool drops an excluded subtopic", LearningEngine.evaluationPool(t, ex).size() == 11, LearningEngine.evaluationPool(t, ex).size());
		ok("eval pool reserved = reserved questions only", LearningEngine.evaluationPool(t, bp("reserved", 5, 0, "proportional", 70, 0, 0, 0, 0, true)).size() == 6, "");

		// sufficiency
		ok("eval pool sufficient", Boolean.TRUE.equals(LearningEngine.poolReport(t, b).get("sufficient")) && LearningEngine.poolReport(t, b).get("shortfall") == null, LearningEngine.poolReport(t, b));
		ok("eval pool below max -> pool_below_max", "pool_below_max".equals(LearningEngine.poolReport(t, bp("random", 40, 0, "proportional", 70, 0, 0, 0, 0, true)).get("shortfall")), "");
		ok("eval subtopic below min -> subtopic_below_min", "subtopic_below_min".equals(LearningEngine.poolReport(t, bp("random", 17, 6, "proportional", 70, 0, 0, 0, 0, true)).get("shortfall")), "");
		ok("eval minimums exceed max -> minimums_exceed_max", "minimums_exceed_max".equals(LearningEngine.poolReport(t, bp("random", 9, 5, "proportional", 70, 0, 0, 0, 0, true)).get("shortfall")), "");
		JSONArray bys = (JSONArray) LearningEngine.poolReport(t, b).get("bysection");
		ok("eval pool report per subtopic (sequence 6, reserved 2, inpool 5 for es1)", bys.size() == 3 && ((JSONObject) bys.get(0)).get("sequence").equals(6) && ((JSONObject) bys.get(0)).get("reserved").equals(2) && ((JSONObject) bys.get(0)).get("inpool").equals(5), bys);

		// selection
		JSONObject e1 = LearningEngine.buildEvaluation(t, b, learner(), 42L);
		ok("eval selection n = maxquestions", items(e1).size() == 9, items(e1).size());
		ok("eval selection min per subtopic honoured", perSection(e1, "es1") >= 2 && perSection(e1, "es2") >= 2 && perSection(e1, "es3") >= 2, sectionsOf(e1));
		ok("eval selection presented in subtopic order", sectionsOf(e1).equals(sortedSections(e1)), sectionsOf(e1));
		ok("eval selection deterministic per seed", ids(items(e1)).equals(ids(items(LearningEngine.buildEvaluation(t, b, learner(), 42L)))), "");
		boolean differs = false;
		for (long s = 1; s <= 5 && !differs; s++)
		{
			differs = !ids(items(LearningEngine.buildEvaluation(t, b, learner(), s))).equals(ids(items(e1)));
		}
		ok("eval selection varies with the seed", differs, "");
		JSONObject bal = LearningEngine.buildEvaluation(t, bp("random", 9, 0, "balanced", 70, 0, 0, 0, 0, true), learner(), 7L);
		ok("eval balanced mix 3/3/3 when every level has enough", perDifficulty(bal, "beginner") == 3 && perDifficulty(bal, "competent") == 3 && perDifficulty(bal, "expert") == 3, difficultiesOf(bal));
		ok("eval no relaxation on a sufficient pool", ((JSONArray) inputs(bal).get("relaxations")).isEmpty(), inputs(bal));
		JSONObject shortMix = LearningEngine.buildEvaluation(t, bp("reserved", 6, 0, "balanced", 70, 0, 0, 0, 0, true), learner(), 7L);
		ok("eval balanced mix over an all-competent reserved pool -> mix_short, still fills n", ((JSONArray) inputs(shortMix).get("relaxations")).contains("mix_short") && items(shortMix).size() == 6, inputs(shortMix));
		JSONObject shortCov = LearningEngine.buildEvaluation(t, bp("random", 17, 6, "proportional", 70, 0, 0, 0, 0, true), learner(), 7L);
		ok("eval coverage short -> minpersubtopic_short", ((JSONArray) inputs(shortCov).get("relaxations")).contains("minpersubtopic_short"), inputs(shortCov));

		// retake avoids earlier questions; exposure counted
		Learner l = learner();
		EvalAttempt prev = new EvalAttempt();
		prev.topicid = "e1";
		prev.status = "submitted";
		prev.questions.addAll(ids(items(e1)));
		l.evaluations.add(prev);
		JSONObject e2 = LearningEngine.buildEvaluation(t, b, l, 43L);
		int overlap = 0;
		for (String id : ids(items(e2)))
		{
			if (prev.questions.contains(id))
			{
				overlap++;
			}
		}
		// 17 in pool, 9 used before, 9 drawn: at least 1 reused; coverage (2 per subtopic) can force up to 2 more when a subtopic was fully used
		ok("eval retake reuses only what the pool and coverage force (1..3 of 9), counted and flagged", overlap >= 1 && overlap <= 3 && e2.get("reused").equals(overlap) && ((JSONArray) inputs(e2).get("relaxations")).contains("reused"), overlap);
		Learner seen = learner(List.of(attempt("e1q1", "learn", true, "confident", 0, 5), attempt("e1q2", "improve", false, "notsure", 0, 4)));
		JSONObject e3 = LearningEngine.buildEvaluation(t, bp("random", 17, 0, "proportional", 70, 0, 0, 0, 0, true), seen, 1L);
		ok("eval exposed counts questions seen in learning modes", e3.get("exposed").equals(2), e3.get("exposed"));
		JSONObject e4 = LearningEngine.buildEvaluation(t, bp("reserved", 6, 0, "proportional", 70, 0, 0, 0, 0, true), seen, 1L);
		ok("eval reserved strategy: exposed 0", e4.get("exposed").equals(0) && items(e4).size() == 6, e4);

		// scoring
		EvalAttempt a = attemptOf(t, e1);
		int i = 0;
		for (String q : a.questions)
		{
			if (i < 7)
			{
				a.answers.put(q, true);
			}
			else if (i < 9)
			{
				a.answers.put(q, false);
			}
			i++;
		}
		a.answers.remove(a.questions.get(8)); // one unanswered
		JSONObject r = LearningEngine.scoreEvaluation(a, b, Map.of());
		ok("eval score 7/9 -> 78, answered 8, passed at 70", r.get("scorepercent").equals(78) && r.get("answered").equals(8) && Boolean.TRUE.equals(r.get("passed")) && r.get("failedrule") == null, r);
		ok("eval score fails at 80 -> failedrule overall", "overall".equals(LearningEngine.scoreEvaluation(a, bp("random", 9, 2, "balanced", 80, 0, 0, 0, 0, true), Map.of()).get("failedrule")), "");
		EvalAttempt sub = attemptOf(t, e1);
		for (String q : sub.questions)
		{
			sub.answers.put(q, !sub.sectionOf.get(q).equals("es3")); // every es3 question wrong
		}
		JSONObject rs = LearningEngine.scoreEvaluation(sub, bp("random", 9, 2, "balanced", 30, 50, 0, 0, 0, true), Map.of("es3", "Three")); // es3 holds 2..5 of 9: overall >= 44 passes 30, es3 at 0 fails the 50 floor
		ok("eval subtopic minimum fails -> failedrule subtopic, weakest first", "subtopic".equals(rs.get("failedrule")) && ((JSONArray) rs.get("weakest")).get(0).equals("es3"), rs);
		ok("eval subtopic titles carried", ((JSONArray) rs.get("subtopics")).toString().contains("Three"), rs.get("subtopics"));
		ok("eval rounding 2/3 -> 67", LearningEngine.scoreEvaluation(third(t), bp("random", 3, 0, "proportional", 67, 0, 0, 0, 0, true), Map.of()).get("scorepercent").equals(67), "");

		// status precedence
		Topic n = evalTopic();
		ok("eval status: no blueprint -> not_available not_configured", "not_available".equals(status(n, learner()).get("status")) && "not_configured".equals(status(n, learner()).get("reason")), status(n, learner()));
		n.blueprint = bp("random", 9, 0, "proportional", 70, 0, 0, 0, 0, false);
		n.blueprint.active = false;
		LearningEngine.settle(n.blueprint);
		ok("eval status: inactive", "inactive".equals(status(n, learner()).get("reason")), status(n, learner()));
		n.blueprint = bp("random", 0, 0, "proportional", 70, 0, 0, 0, 0, false);
		ok("eval status: invalid stored value -> invalid_maxquestions", "invalid_maxquestions".equals(status(n, learner()).get("reason")), status(n, learner()));
		n.blueprint = bp("random", 9, 0, "proportional", 70, 0, 0, 2, 1, true);
		ok("eval status: learn incomplete -> locked learn_incomplete", "locked".equals(status(n, learner()).get("status")) && "learn_incomplete".equals(status(n, learner()).get("reason")), status(n, learner()));
		Learner done = learner(allLearned(n));
		ok("eval status: available with attemptsleft 1", "available".equals(status(n, done).get("status")) && Boolean.TRUE.equals(status(n, done).get("canstart")) && status(n, done).get("attemptsleft").equals(1), status(n, done));
		n.locked = true;
		ok("eval status: topic lock wins over learn state", "topic_locked".equals(status(n, done).get("reason")), status(n, done));
		n.locked = false;
		EvalAttempt open = new EvalAttempt();
		open.id = "u_x";
		open.topicid = "e1";
		open.total = 9;
		open.expires = new Date(NOW.getTime() + 60_000);
		done.evaluations.add(open);
		ok("eval status: open attempt -> in_progress with attemptid", "in_progress".equals(status(n, done).get("status")) && "u_x".equals(((JSONObject) status(n, done).get("inprogress")).get("attemptid")), status(n, done));
		open.expires = new Date(NOW.getTime() - 60_000);
		ok("eval status: a past expiresat is not open (in progress by row, expired by clock)", !"in_progress".equals(status(n, done).get("status")), status(n, done));
		done.evaluations.clear();
		EvalAttempt failed = new EvalAttempt();
		failed.topicid = "e1";
		failed.status = "submitted";
		failed.scorepercent = 40;
		failed.submitted = new Date(NOW.getTime() - 3600_000L);
		done.evaluations.add(failed);
		ok("eval status: failed 1 h ago with 2 h wait -> waiting with nextallowedat", "waiting".equals(status(n, done).get("status")) && status(n, done).get("nextallowedat") != null && status(n, done).get("scorepercent").equals(40), status(n, done));
		failed.submitted = new Date(NOW.getTime() - 3 * 3600_000L);
		ok("eval status: wait over but maxattempts 1 reached -> exhausted", "exhausted".equals(status(n, done).get("status")), status(n, done));
		n.blueprint = bp("random", 9, 0, "proportional", 70, 0, 0, 0, 0, true);
		ok("eval status: unlimited attempts -> available again, lastresult carried", "available".equals(status(n, done).get("status")) && ((JSONObject) status(n, done).get("lastresult")).get("scorepercent").equals(40), status(n, done));
		EvalAttempt passed = new EvalAttempt();
		passed.topicid = "e1";
		passed.status = "submitted";
		passed.passed = true;
		passed.scorepercent = 90;
		passed.submitted = NOW;
		done.evaluations.add(passed);
		ok("eval status: passed is terminal", "passed".equals(status(n, done).get("status")) && Boolean.FALSE.equals(status(n, done).get("canstart")) && status(n, done).get("passedat") != null, status(n, done));

		// Finished conjunct (job profiles)
		Topic f = evalTopic();
		f.evaluationrequired = true;
		Learner learned = learner(allLearned(f));
		ok("finished: evaluation required and not passed -> false even with Learn complete", !LearningEngine.finished(f, learned), "");
		learned.evaluations.add(passed);
		ok("finished: evaluation required and passed -> true", LearningEngine.finished(f, learned), "");
		f.evaluationrequired = false;
		ok("finished: not required -> unchanged rule", LearningEngine.finished(f, learner(allLearned(f))), "");
	}

	/** Topic e1: 3 subtopics x 6 sequence questions (difficulty cycling beginner/competent/expert), es1q6 unrenderable, plus 2 reserved per subtopic. */
	static Topic evalTopic()
	{
		Topic t = new Topic();
		t.id = "e1";
		t.title = "Eval";
		t.competentmin = 60;
		t.expertmin = 85;
		for (int s = 1; s <= 3; s++)
		{
			Section sec = new Section();
			sec.id = "es" + s;
			sec.title = "Sub " + s;
			sec.topicid = t.id;
			for (int i = 1; i <= 6; i++)
			{
				Question q = new Question();
				q.id = "e1q" + ((s - 1) * 6 + i);
				q.componentid = "c" + q.id;
				q.sectionid = sec.id;
				q.topicid = t.id;
				q.difficulty = LearningEngine.LEVELS.get((i - 1) % 3);
				q.weight = LearningEngine.weightOf(q.difficulty);
				q.position = t.questions.size() + 1;
				if (s == 1 && i == 6)
				{
					q.contentproblem = "missing_option_c";
				}
				sec.questions.add(q);
				t.questions.add(q);
			}
			for (int i = 1; i <= 2; i++)
			{
				Question q = new Question();
				q.id = "e1r" + s + i;
				q.componentid = "c" + q.id;
				q.sectionid = sec.id;
				q.topicid = t.id;
				q.difficulty = "competent";
				q.weight = 2;
				t.reserved.add(q);
			}
			t.sections.add(sec);
		}
		return t;
	}

	static Blueprint bp(String strategy, int max, int min, String mix, int pass, int submin, int timer, int wait, int maxatt, boolean requireLearn)
	{
		Blueprint b = new Blueprint();
		b.topicid = "e1";
		b.version = 1;
		b.active = true;
		b.strategy = strategy;
		b.maxquestions = max;
		b.minpersubtopic = min;
		b.mix = mix;
		b.passpercent = pass;
		b.subtopicminpercent = submin;
		b.timerminutes = timer;
		b.retakewaithours = wait;
		b.maxattempts = maxatt;
		b.requirelearncomplete = requireLearn;
		return LearningEngine.settle(b);
	}

	static JSONObject status(Topic t, Learner l)
	{
		return LearningEngine.evaluationStatus(t, l, NOW);
	}

	static List<Attempt> allLearned(Topic t)
	{
		List<Attempt> at = new ArrayList<>();
		for (Question q : t.questions)
		{
			at.add(attempt(q.id, "learn", true, "confident", 0, 10));
		}
		return at;
	}

	/** An in-progress attempt over built's items. */
	static EvalAttempt attemptOf(Topic t, JSONObject built)
	{
		EvalAttempt a = new EvalAttempt();
		a.id = "u_a";
		a.topicid = t.id;
		for (Object o : items(built))
		{
			JSONObject i = (JSONObject) o;
			a.questions.add((String) i.get("questionid"));
			a.sectionOf.put((String) i.get("questionid"), (String) i.get("sectionid"));
		}
		a.total = a.questions.size();
		return a;
	}

	/** 3 questions, 2 correct. */
	static EvalAttempt third(Topic t)
	{
		EvalAttempt a = new EvalAttempt();
		a.topicid = t.id;
		for (int i = 1; i <= 3; i++)
		{
			a.questions.add("e1q" + i);
			a.sectionOf.put("e1q" + i, "es1");
			a.answers.put("e1q" + i, i < 3);
		}
		a.total = 3;
		return a;
	}

	static List<String> ids(JSONArray inItems)
	{
		List<String> out = new ArrayList<>();
		for (Object o : inItems)
		{
			out.add((String) ((JSONObject) o).get("questionid"));
		}
		return out;
	}

	static List<String> sectionsOf(JSONObject built)
	{
		List<String> out = new ArrayList<>();
		for (Object o : items(built))
		{
			out.add((String) ((JSONObject) o).get("sectionid"));
		}
		return out;
	}

	static List<String> sortedSections(JSONObject built)
	{
		List<String> s = new ArrayList<>(sectionsOf(built));
		java.util.Collections.sort(s);
		return s;
	}

	static int perSection(JSONObject built, String inSection)
	{
		int n = 0;
		for (String s : sectionsOf(built))
		{
			if (s.equals(inSection))
			{
				n++;
			}
		}
		return n;
	}

	static List<String> difficultiesOf(JSONObject built)
	{
		List<String> out = new ArrayList<>();
		for (Object o : items(built))
		{
			out.add((String) ((JSONObject) o).get("difficulty"));
		}
		return out;
	}

	static int perDifficulty(JSONObject built, String inLevel)
	{
		int n = 0;
		for (String d : difficultiesOf(built))
		{
			if (d.equals(inLevel))
			{
				n++;
			}
		}
		return n;
	}

```

Notes for the implementer: `items()` and `inputs()` already exist in the file. `sortedSections` relies on section ids `es1` < `es2` < `es3` sorting like content order. The 3/3/3 expectation: with min 0 the 17-question pool has beginner 6, competent 6, expert 5 (es1's q6 is expert but unrenderable); balanced round-robin over 9 slots gives 3 each. The `mix_short` check uses the reserved pool (6 questions, all competent): remaining/3 = 2, the beginner and expert buckets are empty → flagged, and the list still fills n = 6 from what remains (the flag is informational, never a refusal).

- [ ] **Step 6: Run the pure checks**

From `eme-server-minsur`:

```bash
bin/compile.sh && java -cp "build:$(ls -d plugins/*/lib 2>/dev/null | sed 's#$#/*#' | paste -sd: -)" plugins/testu/tools/LearningEngineCheck.java | grep -c "^FAIL" ; java -cp "build:$(ls -d plugins/*/lib 2>/dev/null | sed 's#$#/*#' | paste -sd: -)" plugins/testu/tools/LearningEngineCheck.java | grep "^FAIL"
```

Expected: `0` failures (use the exact `java -cp` line `tools/check_learning.sh` uses if the classpath above misses a jar; it is printed near its top).

- [ ] **Step 7: Commit**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/testu && git add code/tech/genailabs/tutor/LearningEngine.java tools/LearningEngineCheck.java && git commit -m "feat(evaluation): pure engine core (blueprint, pool, selection, scoring, status) with checks"
```

---

### Task 3: Engine storage + learner endpoints (`evaluation`, `startevaluation`, `submitevaluation`, `answer.json` mode evaluation) + server checks

**Files:**
- Modify: `plugins/testu/code/tech/genailabs/tutor/LearningEngine.java`
- Modify: `plugins/testu/code/tech/genailabs/tutor/TestULearningModule.java`
- Create: `plugins/testu/html/services/testu/learn/evaluation.xconf`, `evaluation.json`, `startevaluation.xconf`, `startevaluation.json`, `submitevaluation.xconf`, `submitevaluation.json`
- Modify: `plugins/testu/catalog/events/scripts/testu/computemastery.groovy`
- Create: `plugins/testu/tools/check_evaluation.sh`

**Interfaces:**
- Consumes: everything Task 2 produced; `resolve()`, `Resolved`, `startSession`, `next()` (private static, same class), `contentUnavailable`, `freshUser`, `visibleTopics`, `param`, `requireUser`, `audit`, `notifyUser`, `WRITE_LOCK`.
- Produces: `Blueprint blueprintOf(Data, String topicid)`, `EvalAttempt evalAttemptOf(Data)`, `EvalAttempt loadEvalAttempt(String)`, `EvalAttempt startEvaluation(Topic, Learner, Date)`, `EvalAttempt finalizeEvaluation(EvalAttempt, Content, String by)`, `void recordEvaluationAnswer(EvalAttempt, String questionid, boolean correct)`, `void expireStale(Learner, Content)`, `int expireEvaluations(Date)`, `static JSONObject evaluationItems(EvalAttempt, Content)`, `static void replaceAttempt(Learner, EvalAttempt)`, `Resolved.attempt`; endpoints `services/testu/learn/evaluation.json`, `startevaluation.json`, `submitevaluation.json`; `answer.json` accepts `mode=evaluation` (response `deferred: true`, no `iscorrect`); `state.json` topics carry `evaluation`.

Ruling recorded for this task (the spec said attempt rows are create-only apart from finalization, and ids `<user>_<uuid>`): the attempt row keeps its own `answers` JSON map, updated on every accepted answer under the write lock with a realtime read, so scoring never depends on search-index lag right after the last answer; ids are `<user>_<topic>_a<n>` so the start endpoint can probe them with realtime gets like `subtopicpolicy` versions (no duplicate attempt on a double tap). Also: the pool already excludes unrenderable questions, so `startevaluation` has no `content_unavailable` path (the app's own miss still fails the load as today).

- [ ] **Step 1: Load reserved questions and blueprints in `loadContent`**

Replace the exact block

```java
						Data qd = questionData.get(slot.get("questionid"));
						if (qd == null || "true".equals(qd.get("evaluationreserved")) || c.questions.containsKey(qd.getId()))
						{
							continue; // ponytail: a question in two slots/topics counts once, at its first position
						}
```

with

```java
						Data qd = questionData.get(slot.get("questionid"));
						if (qd == null || c.questions.containsKey(qd.getId()) || c.reserved.containsKey(qd.getId()))
						{
							continue; // ponytail: a question in two slots/topics counts once, at its first position
						}
```

and the exact block

```java
						q.topicindex = topic.index;
						q.position = topic.questions.size() + 1;
```

with

```java
						q.topicindex = topic.index;
						if ("true".equals(qd.get("evaluationreserved")))
						{
							q.position = 0; // outside the learning sequence: the Evaluation pool only (spec 2026-09-16-evaluation-mode)
							topic.reserved.add(q);
							c.reserved.put(q.id, q);
							continue;
						}
						q.position = topic.questions.size() + 1;
```

Before the exact block

```java
		for (Map.Entry<String, List<String>> e : tutorialsByTopic.entrySet())
		{
			Data td = topicData.get(e.getKey());
```

insert

```java
		Map<String, Data> blueprints = new HashMap<>();
		for (Object o : fieldArchive.query("evaluationblueprint").orgroup("entitytopic", topicData.keySet()).search())
		{
			Data p = (Data) o;
			Data prev = blueprints.get(p.get("entitytopic"));
			if (prev == null || intOr(p.get("blueprintversion"), 0) > intOr(prev.get("blueprintversion"), 0))
			{
				blueprints.put(p.get("entitytopic"), p);
			}
		}
```

After the exact line `			topic.policyversion = policy == null ? 0 : intOr(policy.get("policyversion"), 0);` insert

```java
			topic.blueprint = blueprintOf(blueprints.get(topic.id), topic.id);
```

- [ ] **Step 2: Load the learner's attempts**

In `loadLearner(String inUserid, Collection<String> inJobroles)`, before the exact block

```java
		if (inJobroles != null)
		{
			l.jobroles = inJobroles;
		}
```

insert

```java
		HitTracker ev = fieldArchive.query("evaluationattempt").exact("user", inUserid).search();
		ev.enableBulkOperations();
		for (Object o : ev)
		{
			l.evaluations.add(evalAttemptOf((Data) o));
		}
		l.evaluations.sort(Comparator.comparing(a -> a.created == null ? new Date(0) : a.created));
```

In `withAttempt`, after the exact line `		out.jobroles = l.jobroles;` insert

```java
		out.primaryjobrole = l.primaryjobrole;
		out.evaluations = l.evaluations;
```

- [ ] **Step 3: Storage methods**

Insert immediately before the exact line `	// ---------------------------------------------------------------- subtopic progression` (after Task 2's pure block):

```java
	/** The stored blueprint row as a Blueprint (settled); null row = not configured for inTopicid. */
	public static Blueprint blueprintOf(Data d, String inTopicid)
	{
		Blueprint b = new Blueprint();
		b.topicid = inTopicid;
		if (d == null)
		{
			b.reason = "not_configured";
			return b;
		}
		b.version = intOr(d.get("blueprintversion"), 0);
		b.active = "true".equals(String.valueOf(d.get("active")));
		b.strategy = d.get("strategy") == null ? "" : d.get("strategy");
		b.mix = d.get("difficultymix") == null ? "" : d.get("difficultymix");
		b.maxquestions = intOr(d.get("maxquestions"), 0);
		b.minpersubtopic = intOr(d.get("minpersubtopic"), 0);
		b.passpercent = intOr(d.get("passpercent"), 0);
		b.subtopicminpercent = intOr(d.get("subtopicminpercent"), 0);
		b.timerminutes = intOr(d.get("timerminutes"), 0);
		b.retakewaithours = intOr(d.get("retakewaithours"), 0);
		b.maxattempts = intOr(d.get("maxattempts"), 0);
		b.requirelearncomplete = !"false".equals(String.valueOf(d.get("requirelearncomplete")));
		Object ex = JSONValue.parse(String.valueOf(d.get("excludedsections")));
		if (ex instanceof List)
		{
			for (Object o : (List) ex)
			{
				b.excludedsections.add(String.valueOf(o));
			}
		}
		b.user = d.get("user");
		b.created = DateStorageUtil.getStorageUtil().parseFromObject(d.getValue("datecreated"));
		return settle(b);
	}

	/** The exact blueprint version an attempt was built with (null when the row is gone). */
	protected Blueprint loadBlueprintVersion(String inTopicid, int inVersion)
	{
		Data d = (Data) fieldArchive.getSearcher("evaluationblueprint").searchById(inTopicid + "_v" + inVersion);
		return d == null ? null : blueprintOf(d, inTopicid);
	}

	public static EvalAttempt evalAttemptOf(Data d)
	{
		EvalAttempt a = new EvalAttempt();
		a.id = d.getId();
		a.user = d.get("user");
		a.topicid = d.get("entitytopic");
		a.strategy = d.get("strategy");
		a.status = d.get("status") == null || d.get("status").isEmpty() ? "inprogress" : d.get("status");
		a.finalizedby = d.get("finalizedby");
		a.version = intOr(d.get("blueprintversion"), 0);
		a.number = intOr(d.get("attemptnumber"), 0);
		a.total = intOr(d.get("total"), 0);
		a.answered = intOr(d.get("answered"), 0);
		a.correct = intOr(d.get("correct"), 0);
		a.scorepercent = intOr(d.get("scorepercent"), 0);
		a.exposed = intOr(d.get("exposed"), 0);
		a.reused = intOr(d.get("reused"), 0);
		a.passed = "true".equals(String.valueOf(d.get("passed")));
		Object list = JSONValue.parse(String.valueOf(d.get("questionlist")));
		if (list instanceof List)
		{
			for (Object o : (List) list)
			{
				if (o instanceof Map)
				{
					String q = String.valueOf(((Map) o).get("questionid"));
					a.questions.add(q);
					a.sectionOf.put(q, String.valueOf(((Map) o).get("sectionid")));
				}
			}
		}
		Object ans = JSONValue.parse(String.valueOf(d.get("answers")));
		if (ans instanceof Map)
		{
			for (Object e : ((Map) ans).entrySet())
			{
				Map.Entry en = (Map.Entry) e;
				a.answers.put(String.valueOf(en.getKey()), Boolean.TRUE.equals(en.getValue()));
			}
		}
		Object subs = JSONValue.parse(String.valueOf(d.get("subtopicresults")));
		if (subs instanceof JSONArray)
		{
			a.subtopicresults = (JSONArray) subs;
		}
		Object in = JSONValue.parse(String.valueOf(d.get("inputs")));
		if (in instanceof JSONObject)
		{
			a.inputs = (JSONObject) in;
		}
		a.created = DateStorageUtil.getStorageUtil().parseFromObject(d.getValue("datecreated"));
		a.expires = DateStorageUtil.getStorageUtil().parseFromObject(d.getValue("expiresat"));
		a.submitted = DateStorageUtil.getStorageUtil().parseFromObject(d.getValue("submitted"));
		return a;
	}

	/** Realtime get by id; null when no such attempt. */
	public EvalAttempt loadEvalAttempt(String inId)
	{
		if (inId == null)
		{
			return null;
		}
		Data d = (Data) fieldArchive.getSearcher("evaluationattempt").searchById(inId);
		return d == null ? null : evalAttemptOf(d);
	}

	protected void saveEvalAttempt(EvalAttempt a)
	{
		Searcher searcher = fieldArchive.getSearcher("evaluationattempt");
		Data d = (Data) searcher.searchById(a.id);
		if (d == null)
		{
			d = searcher.createNewData();
			d.setId(a.id);
		}
		d.setValue("user", a.user);
		d.setValue("entitytopic", a.topicid);
		d.setValue("blueprintversion", a.version);
		d.setValue("strategy", a.strategy);
		d.setValue("attemptnumber", a.number);
		JSONArray list = new JSONArray();
		int pos = 0;
		for (String q : a.questions)
		{
			JSONObject i = new JSONObject();
			i.put("questionid", q);
			i.put("sectionid", a.sectionOf.get(q));
			i.put("position", ++pos);
			list.add(i);
		}
		d.setValue("questionlist", list.toJSONString());
		d.setValue("total", a.total);
		d.setValue("answers", JSONObject.toJSONString(a.answers));
		d.setValue("status", a.status);
		d.setValue("datecreated", a.created);
		d.setValue("expiresat", a.expires);
		d.setValue("submitted", a.submitted);
		d.setValue("finalizedby", a.finalizedby);
		d.setValue("answered", a.answered);
		d.setValue("correct", a.correct);
		d.setValue("scorepercent", a.scorepercent);
		d.setValue("passed", a.passed);
		d.setValue("subtopicresults", a.subtopicresults.toJSONString());
		d.setValue("exposed", a.exposed);
		d.setValue("reused", a.reused);
		d.setValue("exposurerisk", a.exposed + a.reused > 0);
		d.setValue("inputs", a.inputs.toJSONString());
		searcher.saveData(d, null);
	}

	/** l.evaluations with inAttempt replacing the row of the same id (or appended). */
	public static void replaceAttempt(Learner l, EvalAttempt inAttempt)
	{
		for (int i = 0; i < l.evaluations.size(); i++)
		{
			if (l.evaluations.get(i).id.equals(inAttempt.id))
			{
				l.evaluations.set(i, inAttempt);
				return;
			}
		}
		l.evaluations.add(inAttempt);
	}

	/**
	 * The learner's attempt to serve: under the write lock, probes the next attempt ids (<user>_<topic>_a<n>) with realtime gets past
	 * what l knows; the newest one is resumed when still open, finalized by the timer when past its window; otherwise a new attempt is
	 * built (buildEvaluation, seed = id hash) and stored with expiresat = now + timer (or UNTIMED_WINDOW_MS). The caller decides
	 * eligibility (evaluationStatus) before calling.
	 */
	public EvalAttempt startEvaluation(Topic t, Learner l, Content c, Date inNow)
	{
		synchronized (WRITE_LOCK)
		{
			Searcher searcher = fieldArchive.getSearcher("evaluationattempt");
			int n = 0;
			for (EvalAttempt a : l.evaluations)
			{
				if (t.id.equals(a.topicid))
				{
					n = Math.max(n, a.number);
				}
			}
			EvalAttempt latest = null;
			for (EvalAttempt a : l.evaluations)
			{
				if (t.id.equals(a.topicid) && a.number == n)
				{
					latest = a;
				}
			}
			while (true)
			{
				Data d = (Data) searcher.searchById(l.userid + "_" + t.id + "_a" + (n + 1));
				if (d == null)
				{
					break;
				}
				latest = evalAttemptOf(d);
				n++;
			}
			if (latest != null && latest.open(inNow))
			{
				return latest;
			}
			if (latest != null && !latest.finalized())
			{
				finalizeEvaluation(latest, c, "timer");
			}
			Blueprint b = t.blueprint;
			EvalAttempt a = new EvalAttempt();
			a.id = l.userid + "_" + t.id + "_a" + (n + 1);
			a.number = n + 1;
			a.user = l.userid;
			a.topicid = t.id;
			a.strategy = b.strategy;
			a.version = b.version;
			JSONObject built = buildEvaluation(t, b, l, a.id.hashCode());
			for (Object o : (JSONArray) built.get("items"))
			{
				JSONObject i = (JSONObject) o;
				a.questions.add((String) i.get("questionid"));
				a.sectionOf.put((String) i.get("questionid"), (String) i.get("sectionid"));
			}
			a.total = a.questions.size();
			a.exposed = ((Number) built.get("exposed")).intValue();
			a.reused = ((Number) built.get("reused")).intValue();
			a.inputs = (JSONObject) built.get("inputs");
			a.created = inNow;
			a.expires = new Date(inNow.getTime() + (b.timerminutes > 0 ? b.timerminutes * 60_000L : UNTIMED_WINDOW_MS));
			saveEvalAttempt(a);
			return a;
		}
	}

	/** Records an accepted evaluation answer on its attempt (realtime re-read under the lock; a closed attempt is left alone). */
	public void recordEvaluationAnswer(EvalAttempt inAttempt, String inQuestionid, boolean inCorrect)
	{
		synchronized (WRITE_LOCK)
		{
			EvalAttempt a = loadEvalAttempt(inAttempt.id);
			if (a == null || a.finalized())
			{
				return;
			}
			a.answers.put(inQuestionid, inCorrect);
			saveEvalAttempt(a);
		}
	}

	/**
	 * Scores and closes inAttempt unless already finalized (realtime re-read under the lock; idempotent). inBy = learner | timer
	 * (status submitted | expired). The blueprint used is the version the attempt started with. Returns the stored attempt.
	 */
	public EvalAttempt finalizeEvaluation(EvalAttempt inAttempt, Content c, String inBy)
	{
		synchronized (WRITE_LOCK)
		{
			EvalAttempt a = loadEvalAttempt(inAttempt.id);
			if (a == null)
			{
				a = inAttempt;
			}
			if (a.finalized())
			{
				return a;
			}
			Topic t = c == null ? null : c.topics.get(a.topicid);
			Blueprint b = loadBlueprintVersion(a.topicid, a.version);
			if (b == null)
			{
				b = t != null && t.blueprint != null ? t.blueprint : settle(new Blueprint());
			}
			Map<String, String> titles = new HashMap<>();
			if (c != null)
			{
				for (Section s : c.sections.values())
				{
					titles.put(s.id, s.title);
				}
			}
			JSONObject r = scoreEvaluation(a, b, titles);
			a.answered = ((Number) r.get("answered")).intValue();
			a.correct = ((Number) r.get("correct")).intValue();
			a.scorepercent = ((Number) r.get("scorepercent")).intValue();
			a.passed = Boolean.TRUE.equals(r.get("passed"));
			a.subtopicresults = (JSONArray) r.get("subtopics");
			a.inputs.put("failedrule", r.get("failedrule"));
			a.inputs.put("weakest", r.get("weakest"));
			a.inputs.put("passpercent", b.passpercent);
			a.inputs.put("subtopicminpercent", b.subtopicminpercent);
			a.status = "timer".equals(inBy) ? "expired" : "submitted";
			a.finalizedby = inBy;
			a.submitted = new Date();
			saveEvalAttempt(a);
			return a;
		}
	}

	/** Lazy finalization for one learner: every attempt in l past its window is scored by the timer; l.evaluations updated. */
	public void expireStale(Learner l, Content c)
	{
		Date now = new Date();
		for (int i = 0; i < l.evaluations.size(); i++)
		{
			EvalAttempt a = l.evaluations.get(i);
			if (!a.finalized() && a.expires != null && a.expires.before(now))
			{
				l.evaluations.set(i, finalizeEvaluation(a, c, "timer"));
			}
		}
	}

	/** Idempotent sweep for the computemastery event: scores every in-progress attempt past its expiresat. Returns rows finalized. */
	public int expireEvaluations(Date inNow)
	{
		HitTracker hits = fieldArchive.query("evaluationattempt").exact("status", "inprogress").before("expiresat", inNow).search();
		hits.enableBulkOperations();
		List<EvalAttempt> stale = new ArrayList<>();
		for (Object o : hits)
		{
			stale.add(evalAttemptOf((Data) o));
		}
		if (stale.isEmpty())
		{
			return 0;
		}
		Content c = loadContent();
		int n = 0;
		for (EvalAttempt a : stale)
		{
			if (finalizeEvaluation(a, c, "timer").finalized())
			{
				n++;
			}
		}
		return n;
	}

	/** next.json-shaped response for attempt a: {ok, mode: evaluation, sessionid, total, expiresat, timerminutes?, attemptnumber, answered, items[done]}. */
	public static JSONObject evaluationItems(EvalAttempt a, Content c)
	{
		JSONArray items = new JSONArray();
		int pos = 0;
		for (String qid : a.questions)
		{
			Question q = c.questions.get(qid);
			if (q == null)
			{
				q = c.reserved.get(qid);
			}
			JSONObject i = new JSONObject();
			i.put("questionid", qid);
			i.put("componentid", q == null ? null : q.componentid);
			i.put("sectionid", q == null ? a.sectionOf.get(qid) : q.sectionid);
			i.put("tutorialid", q == null ? null : q.tutorialid);
			i.put("topicid", a.topicid);
			i.put("position", ++pos);
			i.put("done", a.answers.containsKey(qid));
			items.add(i);
		}
		JSONObject o = next(EVALUATION, false, items);
		o.put("sessionid", a.id);
		o.put("expiresat", iso(a.expires));
		o.put("attemptnumber", a.number);
		o.put("answered", a.answers.size());
		return o;
	}

```

- [ ] **Step 4: `resolve()` accepts mode evaluation; `unavailable()` and `topicState` know reserved questions and the status**

In `Resolved`, after the exact line `		public Learner learner;` insert

```java
		public EvalAttempt attempt; // mode evaluation: the open attempt the question belongs to
```

In `resolve`, replace the exact block

```java
		if (!MODES.contains(inMode))
		{
			return Resolved.fail(400, "bad_mode");
		}
		if (inQuestionid == null)
		{
			return Resolved.fail(400, "missing_questionid");
		}
		Question q = c.questions.get(inQuestionid);
		if (q == null)
		{
			return Resolved.fail(404, "unknown_question");
		}
```

with

```java
		if (!ACCEPTED_MODES.contains(inMode))
		{
			return Resolved.fail(400, "bad_mode");
		}
		if (inQuestionid == null)
		{
			return Resolved.fail(400, "missing_questionid");
		}
		Question q = c.questions.get(inQuestionid);
		if (q == null && EVALUATION.equals(inMode))
		{
			q = c.reserved.get(inQuestionid); // reserved questions exist for Evaluation only
		}
		if (q == null)
		{
			return Resolved.fail(404, "unknown_question");
		}
```

Immediately before the exact line `		if ("dailychallenge".equals(inMode))` (inside `resolve`; the only such line) insert

```java
		if (EVALUATION.equals(inMode))
		{
			// Evaluation: scope = the question's topic, session = the learner's open attempt; any order; one answer per question; no hints
			// (the module rejects hintlevel > 0 before calling). Correctness is never sent back during the attempt.
			if (inScopetype == null || inScopeid == null)
			{
				return Resolved.fail(400, "missing_scope");
			}
			if (!"topic".equals(inScopetype))
			{
				return Resolved.fail(400, "bad_scopetype");
			}
			if (!inScopeid.equals(q.topicid))
			{
				return Resolved.fail(409, "scope_mismatch");
			}
			if (inSessionid == null)
			{
				return Resolved.fail(400, "missing_sessionid");
			}
			EvalAttempt a = loadEvalAttempt(inSessionid);
			if (a == null)
			{
				return Resolved.fail(404, "unknown_session");
			}
			if (!l.userid.equals(a.user) || !q.topicid.equals(a.topicid))
			{
				return Resolved.fail(409, "session_mismatch");
			}
			if (a.finalized())
			{
				return Resolved.fail(409, "attempt_closed");
			}
			if (a.expires != null && a.expires.before(new Date()))
			{
				finalizeEvaluation(a, c, "timer");
				return Resolved.fail(409, "session_expired");
			}
			if (!a.questions.contains(q.id))
			{
				return Resolved.fail(409, "not_in_session");
			}
			if (inAnswer && a.answers.containsKey(q.id))
			{
				return Resolved.fail(409, "already_answered");
			}
			Resolved r = new Resolved();
			r.question = q;
			r.status = 200;
			r.attempt = a;
			return r;
		}
```

In `unavailable`, replace the exact line `			Question q = c.questions.get(item.get("questionid"));` with

```java
			Question q = c.questions.get(item.get("questionid"));
			if (q == null)
			{
				q = c.reserved.get(item.get("questionid"));
			}
```

In `topicState`, after the exact line `		o.put("finished", t.position == null ? null : Boolean.valueOf(t.finished));` insert

```java
		o.put("evaluation", evaluationStatus(t, l, new Date()));
```

- [ ] **Step 5: `answer.json` — hint rule, attempt bookkeeping, deferred reply**

In `TestULearningModule.answer`, after the exact line `		int hintlevel = Integer.parseInt(hint);` insert

```java
		if (LearningEngine.EVALUATION.equals(mode) && hintlevel > 0)
		{
			fail(inReq, 400, "hints_not_allowed");
			return;
		}
```

Replace the exact block

```java
		engine.recordUnlocks(r.content.topics.get(r.question.topicid), LearningEngine.withAttempt(r.learner, attempt));
		notifyUser(user.getId(), "progress", null);
		reply(inReq, answerReply(answer, false, r.question));
```

with

```java
		if (r.attempt != null)
		{
			// Evaluation: the attempt keeps its own answer map (scored on submit); no unlocks, no progress event, no verdict in the reply.
			engine.recordEvaluationAnswer(r.attempt, r.question.id, correct);
		}
		else
		{
			engine.recordUnlocks(r.content.topics.get(r.question.topicid), LearningEngine.withAttempt(r.learner, attempt));
			notifyUser(user.getId(), "progress", null);
		}
		reply(inReq, answerReply(answer, false, r.question));
```

In `answerReply`, after the exact line `		o.put("hintlevel", LearningEngine.intOr(inAnswer.get("hintlevel"), 0));` insert

```java
		if (LearningEngine.EVALUATION.equals(inAnswer.get("mode")))
		{
			o.remove("iscorrect"); // withheld until the attempt is submitted
			o.put("deferred", Boolean.TRUE);
		}
```

`exposure.json` needs no change: `resolve` accepts the mode and the learn/dailychallenge unlock branch is untouched.

- [ ] **Step 6: Learner endpoints**

In `TestULearningModule`, insert before the exact line `	/**` that precedes `	 * Subtopic unlock policy per topic.` (i.e. before the `subtopicPolicy` Javadoc):

```java
	/** Content + learner + profiles for the signed-in user, as state.json builds them (null user already failed the request). */
	private Object[] load(WebPageRequest inReq, User inUser)
	{
		LearningEngine engine = new LearningEngine(getMediaArchive(inReq));
		LearningEngine.Content content = engine.loadContent(visibleTopics(inReq));
		Data urec = freshUser(getMediaArchive(inReq), inUser);
		LearningEngine.Learner learner = engine.loadLearner(inUser.getId(), LearningEngine.jobrolesOf(urec), LearningEngine.primaryJobroleOf(urec));
		engine.applyProfiles(content, learner);
		return new Object[] {engine, content, learner};
	}

	/**
	 * services/testu/learn/evaluation.json?topicid= -- the learner's evaluation status on the topic (LearningEngine.evaluationStatus), the
	 * usable blueprint (null when not offered) and the attempt history. Read-only apart from finalizing an attempt past its window.
	 */
	public void evaluation(WebPageRequest inReq)
	{
		User user = requireUser(inReq);
		if (user == null)
		{
			return;
		}
		Object[] ctx = load(inReq, user);
		LearningEngine engine = (LearningEngine) ctx[0];
		LearningEngine.Content content = (LearningEngine.Content) ctx[1];
		LearningEngine.Learner learner = (LearningEngine.Learner) ctx[2];
		String topicid = param(inReq, "topicid");
		LearningEngine.Topic topic = topicid == null ? null : content.topics.get(topicid);
		if (topic == null)
		{
			fail(inReq, topicid == null ? 400 : 404, topicid == null ? "missing_topicid" : "unknown_topic");
			return;
		}
		engine.expireStale(learner, content);
		JSONObject resp = LearningEngine.evaluationStatus(topic, learner, new Date());
		resp.put("ok", Boolean.TRUE);
		resp.put("topic", topic.id);
		resp.put("blueprint", topic.blueprint.usable() ? topic.blueprint.toJson() : null);
		JSONArray attempts = new JSONArray();
		for (LearningEngine.EvalAttempt a : learner.evaluations)
		{
			if (topic.id.equals(a.topicid))
			{
				attempts.add(a.toResultJson());
			}
		}
		resp.put("attempts", attempts);
		reply(inReq, resp);
	}

	/**
	 * services/testu/learn/startevaluation.json (POST topicid) -- resumes the open attempt or creates one when evaluationStatus says
	 * canstart; replies in next.json shape (mode evaluation, sessionid = attempt id, expiresat, items with done). 409
	 * evaluation_not_available {status, reason, nextallowedat} otherwise. A new attempt writes an auditevent evaluation.start.
	 */
	public void startEvaluation(WebPageRequest inReq)
	{
		User user = requireUser(inReq);
		if (user == null)
		{
			return;
		}
		Object[] ctx = load(inReq, user);
		LearningEngine engine = (LearningEngine) ctx[0];
		LearningEngine.Content content = (LearningEngine.Content) ctx[1];
		LearningEngine.Learner learner = (LearningEngine.Learner) ctx[2];
		String topicid = param(inReq, "topicid");
		LearningEngine.Topic topic = topicid == null ? null : content.topics.get(topicid);
		if (topic == null)
		{
			fail(inReq, topicid == null ? 400 : 404, topicid == null ? "missing_topicid" : "unknown_topic");
			return;
		}
		engine.expireStale(learner, content);
		Date now = new Date();
		JSONObject status = LearningEngine.evaluationStatus(topic, learner, now);
		if (!"in_progress".equals(status.get("status")) && !Boolean.TRUE.equals(status.get("canstart")))
		{
			JSONObject err = new JSONObject();
			err.put("ok", Boolean.FALSE);
			err.put("error", "evaluation_not_available");
			err.put("status", status.get("status"));
			err.put("reason", status.get("reason"));
			err.put("nextallowedat", status.get("nextallowedat"));
			if (inReq.getResponse() != null)
			{
				inReq.getResponse().setStatus(409);
			}
			reply(inReq, err);
			inReq.setCancelActions(true);
			return;
		}
		LearningEngine.EvalAttempt a = engine.startEvaluation(topic, learner, content, now);
		boolean isNew = true;
		for (LearningEngine.EvalAttempt known : learner.evaluations)
		{
			if (known.id.equals(a.id))
			{
				isNew = false;
			}
		}
		if (isNew)
		{
			JSONObject after = new JSONObject();
			after.put("topic", topic.id);
			after.put("number", a.number);
			after.put("total", a.total);
			after.put("strategy", a.strategy);
			after.put("blueprintversion", a.version);
			after.put("expiresat", LearningEngine.iso(a.expires));
			after.put("exposed", a.exposed);
			after.put("reused", a.reused);
			audit(inReq, getMediaArchive(inReq), "evaluation.start", "evaluationattempt", a.id, null, after);
		}
		JSONObject resp = LearningEngine.evaluationItems(a, content);
		resp.put("timerminutes", topic.blueprint.timerminutes);
		reply(inReq, resp);
	}

	/**
	 * services/testu/learn/submitevaluation.json (POST sessionid) -- finalizes the learner's attempt (idempotent: an attempt already
	 * closed returns the same result with duplicate true) and replies the result, the topic's new evaluation status and Finished.
	 */
	public void submitEvaluation(WebPageRequest inReq)
	{
		User user = requireUser(inReq);
		if (user == null)
		{
			return;
		}
		String sessionid = param(inReq, "sessionid");
		if (sessionid == null)
		{
			fail(inReq, 400, "missing_sessionid");
			return;
		}
		Object[] ctx = load(inReq, user);
		LearningEngine engine = (LearningEngine) ctx[0];
		LearningEngine.Content content = (LearningEngine.Content) ctx[1];
		LearningEngine.Learner learner = (LearningEngine.Learner) ctx[2];
		LearningEngine.EvalAttempt a = engine.loadEvalAttempt(sessionid);
		if (a == null || !user.getId().equals(a.user))
		{
			fail(inReq, 404, "unknown_session");
			return;
		}
		boolean duplicate = a.finalized();
		a = engine.finalizeEvaluation(a, content, "learner");
		LearningEngine.replaceAttempt(learner, a);
		LearningEngine.Topic topic = content.topics.get(a.topicid);
		JSONObject resp = a.toResultJson();
		resp.put("ok", Boolean.TRUE);
		resp.put("duplicate", duplicate);
		resp.put("failedrule", a.inputs.get("failedrule"));
		resp.put("weakest", a.inputs.get("weakest"));
		resp.put("passpercent", a.inputs.get("passpercent"));
		resp.put("subtopicminpercent", a.inputs.get("subtopicminpercent"));
		if (topic != null)
		{
			topic.finished = LearningEngine.finished(topic, learner);
			JSONObject st = LearningEngine.evaluationStatus(topic, learner, new Date());
			resp.put("evaluationstatus", st.get("status"));
			resp.put("nextallowedat", st.get("nextallowedat"));
			resp.put("attemptsleft", st.get("attemptsleft"));
			resp.put("finished", topic.position == null ? null : Boolean.valueOf(topic.finished));
		}
		if (!duplicate)
		{
			audit(inReq, getMediaArchive(inReq), "evaluation.submit", "evaluationattempt", a.id, null, a.toResultJson());
			notifyUser(user.getId(), "progress", null);
		}
		reply(inReq, resp);
	}

```

Then simplify nothing else: `state`, `next`, `answer` keep their own loading code (peers edit those; the helper is for the new endpoints only).

- [ ] **Step 7: Endpoint wiring files**

Create `plugins/testu/html/services/testu/learn/evaluation.xconf`:

```xml
<page>
  <path-action name="TestULearningModule.evaluation"/>
  <permission name="view"><user/></permission>
</page>
```

`startevaluation.xconf` the same with `TestULearningModule.startEvaluation`; `submitevaluation.xconf` with `TestULearningModule.submitEvaluation`. Each `.json` file contains exactly:

```
$json
```

- [ ] **Step 8: Event sweep**

In `plugins/testu/catalog/events/scripts/testu/computemastery.groovy`, after the exact line

```groovy
int purgedSessions = new tech.genailabs.tutor.LearningEngine(archive).purgeSessions(now)
```

insert

```groovy
// Evaluation attempts past expiresat are scored by the timer (idempotent; spec 2026-09-16-evaluation-mode).
int expiredEvaluations = new tech.genailabs.tutor.LearningEngine(archive).expireEvaluations(now)
if (expiredEvaluations > 0) log.info("testu computemastery: " + expiredEvaluations + " evaluation attempts expired")
```

Run `bin/sync-testu.sh` from the server root to refresh the webapp copy (it lists this script), then `bin/sync-testu.sh --check`.

- [ ] **Step 9: Compile, notify peers, restart, smoke**

`bin/compile.sh` (server root) → no errors. Tell the peer sessions (SendMessage to every live peer `ListAgents` shows, one line: "restarting local Tomcat for evaluation endpoints in ~1 min"), then restart per memory `local-eme-server-ops` (kill the Bootstrap pid, relaunch the saved command under nohup, wait for "Server startup in"). Smoke as the check user (the script below does the real checks):

```bash
set -a; . ~/.eme-local.env; set +a; curl -s -c /tmp/ev.jar -H 'Content-Type: application/json' -d "{\"id\":\"$EME_USER\",\"password\":\"$EME_PASSWORD\"}" http://localhost:8080/site/mediadb/services/authentication/login.json >/dev/null; curl -s -b /tmp/ev.jar 'http://localhost:8080/site/mediadb/services/testu/learn/state.json' | python3 -c 'import json,sys; t=json.load(sys.stdin)["topics"]; print([x["evaluation"]["status"] for x in t][:5])'
```

Expected: a list of `not_available` (no blueprint rows yet).

- [ ] **Step 10: Server checks — `tools/check_evaluation.sh`**

Create `plugins/testu/tools/check_evaluation.sh` (same skeleton as `check_profiles.sh`: copy its first 80 lines verbatim — shebang, header comment adapted, `session`, `call`, `ok`, `must`, `login`, `es_ids`, `delete_rows`, `refresh` — with `USER, PASSWORD = "eval.check@testu.local", "Ec9-" + secrets.token_urlsafe(24)` and a header comment naming this spec). Then the body:

```python
import datetime, math

NOW = datetime.datetime.utcnow()
counter = [0]
USER_TABLES = ("tutoranswer", "tutorexposure", "dailychallengeset", "learningsession", "tutormastery", "tutordaily", "subtopicunlock", "evaluationattempt")


def iso(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%S.000Z")


def wipe_user_rows():
    refresh()
    for t in USER_TABLES:
        delete_rows(t, es_ids(t, {"term": {"user": USER}}))


def usersave(field, value):
    must(f"usersave {field}", call(admin, "POST", "/services/authentication/usersave.json", form={"username": USER, "field": field, field + "value": value}))


def put_row(table, rid, body):
    body = dict(body, id=rid)
    must(f"{table} {rid}", call(admin, "PUT", f"/services/lists/data/{table}/{quote(rid)}.json", body=body))


def es_doc(table, rid):
    refresh()
    st, raw = call(es, "GET", f"/{table}/{quote(rid, safe='')}", base=ES)
    return raw.get("_source") if st == 200 and raw.get("found") else None


def state(topic=None):
    return must("state", call(me, "GET", "/services/testu/learn/state.json" + (f"?topicid={topic}" if topic else "")))


def topic_of(st, tid):
    return next(t for t in st["topics"] if t["id"] == tid)


def ev():
    return call(me, "GET", f"/services/testu/learn/evaluation.json?topicid={quote(T)}")


def start():
    return call(me, "POST", "/services/testu/learn/startevaluation.json", form={"topicid": T})


def submit(sid):
    return call(me, "POST", "/services/testu/learn/submitevaluation.json", form={"sessionid": sid})


def answer(sid, qid, option, **extra):
    form = {"mode": "evaluation", "scopetype": "topic", "scopeid": T, "questionid": qid, "sessionid": sid, "selectedoption": option,
            "confidence": "confident", "hintlevel": "0", "attemptid": "ec" + secrets.token_hex(8)}
    form.update(extra)
    return call(me, "POST", "/services/testu/learn/answer.json", form={k: v for k, v in form.items() if v is not None})


def audits(action, target):
    refresh()
    st, res = call(es, "POST", "/auditevent/_search", body={"size": 50, "query": {"bool": {"must": [
        {"match_phrase": {"targetid": target}}, {"match_phrase": {"action": action}}]}}}, base=ES)
    return [h["_source"] for h in res.get("hits", {}).get("hits", [])] if st == 200 else []


qhits = must("entityquestion search", call(admin, "GET", "/services/lists/search/entityquestion/search.json?hitsperpage=5000"))["results"]
QROW = {q["id"]: q for q in qhits}
fid = lambda v: v.get("id") if isinstance(v, dict) else v


def right(qid):
    return fid(QROW[qid].get("correctoption")).upper()


def wrong(qid):
    return next(o for o in "ABCD" if o != right(qid) and (QROW[qid].get("option_" + o.lower()) or "").strip())


BP = []
VERSION = [0]


def blueprint(**f):
    """Seeds the next blueprint version of T directly (the admin endpoint is Task 4's; these checks exercise the learner side)."""
    VERSION[0] += 1
    rid = f"{T}_v{VERSION[0]}"
    body = {"entitytopic": T, "blueprintversion": str(VERSION[0]), "active": "true", "strategy": "random", "maxquestions": str(MAXQ),
            "minpersubtopic": "0", "excludedsections": "[]", "difficultymix": "proportional", "passpercent": "60", "subtopicminpercent": "0",
            "timerminutes": "0", "retakewaithours": "0", "maxattempts": "0", "requirelearncomplete": "false", "user": os.environ["EME_USER"],
            "datecreated": iso(NOW)}
    body.update({k: str(v) for k, v in f.items()})
    put_row("evaluationblueprint", rid, body)
    BP.append(rid)
    refresh()


def cleanup():
    delete_rows("evaluationblueprint", BP)
    wipe_user_rows()
    refresh()


try:
    # ---- setup: learner, topic with enough questions
    users = must("users.json", call(admin, "GET", "/services/testu/personas/users.json"))["users"]
    if next((u for u in users if u["id"] == USER), None) is None:
        must("createuser", call(admin, "POST", "/services/testu/personas/createuser.json", form={"email": USER, "firstName": "Eval", "lastName": "Check", "role": "users"}))
    else:
        usersave("enabled", "true")
    usersave("password", PASSWORD)
    usersave("jobrole", "")
    usersave("primaryjobrole", "")
    wipe_user_rows()
    me = login(USER, PASSWORD)
    base = state()
    cands = [t for t in base["topics"] if t["questions"] >= 6 and len(t["sections"]) >= 2 and t["evaluation"]["status"] == "not_available"]
    if not cands:
        sys.exit("need a learner-visible topic with >= 6 questions, >= 2 subtopics and no blueprint")
    T = cands[0]["id"]
    POOL = cands[0]["questions"]
    MAXQ = max(1, min(5, POOL // 2))
    refresh()
    VERSION[0] = max([int(es_doc("evaluationblueprint", i).get("blueprintversion", 0)) for i in es_ids("evaluationblueprint", {"term": {"entitytopic": T}})] or [0])
    ok("no blueprint: every topic not_available / not_configured, no evaluation UI data", all(t["evaluation"]["status"] == "not_available" for t in base["topics"]) and cands[0]["evaluation"]["reason"] == "not_configured", cands[0]["evaluation"])

    # ---- available -> start -> answers -> submit (pass)
    blueprint()
    st, e = ev()
    ok("evaluation.json: available, canstart, blueprint carried", st == 200 and e["status"] == "available" and e["canstart"] is True and e["blueprint"]["maxquestions"] == MAXQ and e["attempts"] == [], e)
    st, s1 = start()
    ok("start: next.json shape, mode evaluation, n items, expiresat, nothing done", st == 200 and s1["mode"] == "evaluation" and s1["total"] == MAXQ and len(s1["items"]) == MAXQ and s1["expiresat"] and all(i["done"] is False for i in s1["items"]) and s1["sessionid"] == f"{USER}_{T}_a1", s1)
    ok("start: positions 1..n, every item of T", [i["position"] for i in s1["items"]] == list(range(1, MAXQ + 1)) and all(i["topicid"] == T for i in s1["items"]), s1["items"])
    row = es_doc("evaluationattempt", s1["sessionid"])
    ok("start: attempt row inprogress, attemptnumber 1, questionlist stored, inputs with seed", row and row["status"] == "inprogress" and int(row["attemptnumber"]) == 1 and len(json.loads(row["questionlist"])) == MAXQ and "seed" in json.loads(row["inputs"]), row)
    st, s1b = start()
    ok("start again: resumes the same attempt", st == 200 and s1b["sessionid"] == s1["sessionid"], s1b)
    ok("audit evaluation.start once", len(audits("evaluation.start", s1["sessionid"])) == 1, audits("evaluation.start", s1["sessionid"]))
    st, e = ev()
    ok("evaluation.json: in_progress with attemptid and 0 answered", e["status"] == "in_progress" and e["inprogress"]["attemptid"] == s1["sessionid"] and e["inprogress"]["answered"] == 0, e)
    q0 = s1["items"][0]["questionid"]
    r = answer(s1["sessionid"], q0, right(q0), hintlevel="1")
    ok("answer: hint -> 400 hints_not_allowed", r[0] == 400 and r[1]["error"] == "hints_not_allowed", r)
    r = answer(s1["sessionid"], q0, right(q0), scopetype="subtopic", scopeid=s1["items"][0]["sectionid"])
    ok("answer: subtopic scope -> 400 bad_scopetype", r[0] == 400 and r[1]["error"] == "bad_scopetype", r)
    r = answer(s1["sessionid"], q0, right(q0), scopeid="nope")
    ok("answer: wrong topic scope -> 409 scope_mismatch", r[0] == 409 and r[1]["error"] == "scope_mismatch", r)
    r = answer("nope", q0, right(q0))
    ok("answer: unknown attempt -> 404 unknown_session", r[0] == 404 and r[1]["error"] == "unknown_session", r)
    outside = next((t for t in QROW if t not in {i["questionid"] for i in s1["items"]} and QROW[t].get("correctoption")), None)
    if outside:
        r = answer(s1["sessionid"], outside, "A")
        ok("answer: question outside the attempt -> 409 not_in_session (or unknown/scope)", r[0] in (404, 409), r)
    for i in s1["items"]:
        r = answer(s1["sessionid"], i["questionid"], right(i["questionid"]))
        ok(f"answer {i['position']}: stored, deferred, no iscorrect", r[0] == 200 and r[1]["ok"] is True and r[1]["deferred"] is True and "iscorrect" not in r[1] and r[1]["mode"] == "evaluation", r)
    r = answer(s1["sessionid"], q0, right(q0))
    ok("answer: second answer to the same question -> 409 already_answered", r[0] == 409 and r[1]["error"] == "already_answered", r)
    refresh()
    arow = [h for h in call(es, "POST", "/tutoranswer/_search", body={"size": 50, "query": {"term": {"user": USER}}}, base=ES)[1]["hits"]["hits"]]
    ok("tutoranswer rows: mode evaluation, learningsession = attempt id, scopetype topic", len(arow) == MAXQ and all(h["_source"]["mode"] == "evaluation" and h["_source"]["learningsession"] == s1["sessionid"] and h["_source"]["scopetype"] == "topic" for h in arow), [h["_source"].get("mode") for h in arow])
    t = topic_of(state(), T)
    ok("state: evaluation answers never count as learning answers (answered 0), in_progress with n answered", t["answered"] == 0 and t["evaluation"]["status"] == "in_progress" and t["evaluation"]["inprogress"]["answered"] == MAXQ, t["evaluation"])
    st, res = submit(s1["sessionid"])
    ok("submit: passed 100, correct n, subtopics listed, status submitted", st == 200 and res["passed"] is True and res["scorepercent"] == 100 and res["correct"] == MAXQ and res["status"] == "submitted" and res["subtopics"] and res["duplicate"] is False and res["evaluationstatus"] == "passed", res)
    st, res2 = submit(s1["sessionid"])
    ok("submit again: duplicate with the same score", st == 200 and res2["duplicate"] is True and res2["scorepercent"] == 100, res2)
    ok("audit evaluation.submit once", len(audits("evaluation.submit", s1["sessionid"])) == 1, "")
    st, e = ev()
    ok("evaluation.json: passed is terminal (canstart false, passedat, lastresult)", e["status"] == "passed" and e["canstart"] is False and e["passedat"] and e["lastresult"]["scorepercent"] == 100 and len(e["attempts"]) == 1, e)
    r = start()
    ok("start after pass -> 409 evaluation_not_available passed", r[0] == 409 and r[1]["error"] == "evaluation_not_available" and r[1]["status"] == "passed", r)
    r = answer(s1["sessionid"], q0, right(q0))
    ok("answer on a closed attempt -> 409 attempt_closed", r[0] == 409 and r[1]["error"] == "attempt_closed", r)

    # ---- fail -> waiting -> retake avoids earlier questions -> exhausted
    delete_rows("evaluationattempt", es_ids("evaluationattempt", {"term": {"user": USER}}))
    delete_rows("tutoranswer", es_ids("tutoranswer", {"term": {"user": USER}}))
    refresh()
    blueprint(passpercent="100", retakewaithours="1", maxattempts="2")
    st, s2 = start()
    ok("fresh history: attempt a1 again", st == 200 and s2["sessionid"].endswith("_a1"), s2)
    for i in s2["items"]:
        answer(s2["sessionid"], i["questionid"], wrong(i["questionid"]))
    st, res = submit(s2["sessionid"])
    ok("submit: all wrong -> 0 %, failedrule overall, not passed", res["passed"] is False and res["scorepercent"] == 0 and res["failedrule"] == "overall" and res["evaluationstatus"] == "waiting" and res["nextallowedat"], res)
    st, e = ev()
    ok("evaluation.json: waiting with nextallowedat, attemptsleft 1", e["status"] == "waiting" and e["nextallowedat"] and e["attemptsleft"] == 1 and e["scorepercent"] == 0, e)
    r = start()
    ok("start while waiting -> 409 with nextallowedat", r[0] == 409 and r[1]["status"] == "waiting" and r[1]["nextallowedat"], r)
    blueprint(passpercent="100", retakewaithours="0", maxattempts="2")
    st, e = ev()
    ok("new version without wait: available again", e["status"] == "available" and e["attemptsleft"] == 1, e)
    st, s3 = start()
    prev = {i["questionid"] for i in s2["items"]}
    overlap = len(prev & {i["questionid"] for i in s3["items"]})
    ok("retake a2 avoids a1's questions where the pool allows", st == 200 and s3["sessionid"].endswith("_a2") and overlap <= max(0, 2 * MAXQ - POOL), (overlap, POOL, MAXQ))
    for i in s3["items"]:
        answer(s3["sessionid"], i["questionid"], wrong(i["questionid"]))
    st, res = submit(s3["sessionid"])
    ok("second failure -> exhausted", res["evaluationstatus"] == "exhausted" and res["attemptsleft"] == 0, res)
    r = start()
    ok("start when exhausted -> 409", r[0] == 409 and r[1]["status"] == "exhausted", r)

    # ---- timer: expired attempt is scored by the clock
    delete_rows("evaluationattempt", es_ids("evaluationattempt", {"term": {"user": USER}}))
    delete_rows("tutoranswer", es_ids("tutoranswer", {"term": {"user": USER}}))
    refresh()
    blueprint(timerminutes="1")
    st, s4 = start()
    exp = datetime.datetime.strptime(s4["expiresat"][:19], "%Y-%m-%dT%H:%M:%S")
    ok("timer: expiresat about one minute ahead", st == 200 and 30 <= (exp - datetime.datetime.utcnow()).total_seconds() <= 90 and s4["timerminutes"] == 1, s4["expiresat"])
    q = s4["items"][0]["questionid"]
    answer(s4["sessionid"], q, right(q))
    put_row("evaluationattempt", s4["sessionid"], {"expiresat": iso(NOW - datetime.timedelta(hours=1))})
    refresh()
    r = answer(s4["sessionid"], s4["items"][-1]["questionid"], "A")
    ok("timer: answer past expiresat -> 409 session_expired", r[0] == 409 and r[1]["error"] == "session_expired", r)
    row = es_doc("evaluationattempt", s4["sessionid"])
    ok("timer: attempt finalized by timer with the answered subset scored", row and row["status"] == "expired" and row["finalizedby"] == "timer" and int(row["answered"]) == 1 and int(row["correct"]) == 1, row)
    st, e = ev()
    ok("evaluation.json after expiry: available again (unlimited attempts), lastresult expired", e["status"] == "available" and e["lastresult"]["status"] == "expired" and e["attempts"] == 1, e)

    # ---- learn must be complete
    blueprint(requirelearncomplete="true")
    st, e = ev()
    ok("requirelearncomplete: locked learn_incomplete", e["status"] == "locked" and e["reason"] == "learn_incomplete" and e["canstart"] is False, e)
    r = start()
    ok("start while locked -> 409", r[0] == 409 and r[1]["reason"] == "learn_incomplete", r)

    # ---- inactive and legacy rows
    blueprint(active="false")
    st, e = ev()
    ok("inactive version: not_available inactive, blueprint null", e["status"] == "not_available" and e["reason"] == "inactive" and e["blueprint"] is None, e)
    t = topic_of(state(), T)
    ok("state.json: evaluation block mirrors evaluation.json", t["evaluation"]["status"] == "not_available" and t["evaluation"]["reason"] == "inactive", t["evaluation"])
finally:
    cleanup()

print("evaluation checks: " + ("PASS" if not FAILS else f"{len(FAILS)} FAILED: {FAILS}"))
sys.exit(1 if FAILS else 0)
PY
```

Run from the server root: `set -a; . ~/.eme-local.env; set +a; plugins/testu/tools/check_evaluation.sh`. Expected: `evaluation checks: PASS`. Also rerun `plugins/testu/tools/check_learning.sh` and `check_profiles.sh`: both PASS (nothing existing changed behaviour).

- [ ] **Step 11: Commit**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/testu && git add code/tech/genailabs/tutor/LearningEngine.java code/tech/genailabs/tutor/TestULearningModule.java html/services/testu/learn/evaluation.xconf html/services/testu/learn/evaluation.json html/services/testu/learn/startevaluation.xconf html/services/testu/learn/startevaluation.json html/services/testu/learn/submitevaluation.xconf html/services/testu/learn/submitevaluation.json catalog/events/scripts/testu/computemastery.groovy tools/check_evaluation.sh && git commit -m "feat(evaluation): attempts storage, learner endpoints, answer.json mode evaluation, timer sweep, server checks"
```

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && git add webapp/WEB-INF/data/site/catalog/events/scripts/testu/computemastery.groovy && git commit -m "chore(evaluation): mirror computemastery sweep"
```

(If `bin/sync-testu.sh` mirrors the groovy file to a different path, add that path instead; `git status` shows it.)

---

### Task 4: Admin blueprint endpoint, profile row field, analytics fields, tutor guard, server checks

**Files:**
- Modify: `plugins/testu/code/tech/genailabs/tutor/TestULearningModule.java`
- Create: `plugins/testu/html/services/testu/learn/evaluationblueprint.xconf`, `evaluationblueprint.json`
- Modify: `plugins/testu/code/tech/genailabs/tutor/TestUProfileModule.java`
- Modify: `plugins/testu/code/tech/genailabs/tutor/TestUAnalyticsModule.java`
- Modify (conditional, see Step 5): `eme-server-minsur/plugins/finder/code/org/entermediadb/ai/skills/AdaptiveTutorialAnswerSkill.java`
- Modify: `plugins/testu/tools/check_evaluation.sh`

**Interfaces:**
- Consumes: `Blueprint`, `invalidField`, `settle`, `poolReport`, `blueprintOf`, `canManageProgression`, `audit`, `WRITE_LOCK`, `ProfileRow.evaluationrequired`, `topicState().evaluation`.
- Produces: `services/testu/learn/evaluationblueprint.json` (GET list / GET `?topicid=` / POST) exactly as the spec's Endpoints section; `profiles.json` rows and `saveprofile.json` rows carry `evaluationrequired`; `person.json` `risk.requiredtopics[]` rows carry `evaluationrequired`, `evaluation {status, scorepercent, passedat, attempts}`, `evaluationmet`; `requiredgaps` counts unmet evaluations.

- [ ] **Step 1: Blueprint endpoint**

In `TestULearningModule`, insert before the exact line `	/**` that precedes `	 * POST, training_manage: idempotent subtopicunlock backfill` (the `unlockBackfill` Javadoc):

```java
	/**
	 * Evaluation blueprint per topic (spec 2026-09-16-evaluation-mode). GET: every topic with its current blueprint, pool report and
	 * attempt stats (or ?topicid= with its version history). POST topicid + fields + expectedversion: appends version n+1 to
	 * evaluationblueprint and an auditevent, create-only under LearningEngine.WRITE_LOCK; expectedversion no longer the latest = 409
	 * version_conflict; active=true over a pool that cannot satisfy the blueprint = 409 pool_insufficient (nothing written; save it
	 * inactive instead). Reading needs training_view or training_manage, writing training_manage. An in-progress attempt keeps the
	 * version it started with; a new version applies from the next startevaluation.
	 */
	public void evaluationBlueprint(WebPageRequest inReq)
	{
		User user = requireUser(inReq);
		if (user == null)
		{
			return;
		}
		boolean canmanage = canManageProgression(inReq);
		org.openedit.profile.UserProfile profile = inReq.getUserProfile();
		if (!canmanage && (profile == null || !profile.hasPermission("training_view")))
		{
			fail(inReq, 403, "forbidden");
			return;
		}
		MediaArchive archive = getMediaArchive(inReq);
		LearningEngine.Content content = new LearningEngine(archive).loadContent();
		java.util.Map<String, int[]> stats = attemptStats(archive);
		String topicid = param(inReq, "topicid");
		boolean post = inReq.getRequest() != null && "POST".equalsIgnoreCase(inReq.getRequest().getMethod());
		if (topicid == null)
		{
			if (post)
			{
				fail(inReq, 400, "missing_topicid");
				return;
			}
			JSONArray topics = new JSONArray();
			for (LearningEngine.Topic t : content.topics.values())
			{
				topics.add(blueprintJson(t, stats));
			}
			JSONObject resp = new JSONObject();
			resp.put("ok", Boolean.TRUE);
			resp.put("canmanage", canmanage);
			resp.put("topics", topics);
			reply(inReq, resp);
			return;
		}
		LearningEngine.Topic topic = content.topics.get(topicid);
		if (topic == null)
		{
			fail(inReq, 404, "unknown_topic");
			return;
		}
		Searcher searcher = archive.getSearcher("evaluationblueprint");
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("canmanage", canmanage);
		if (post)
		{
			if (!canmanage)
			{
				fail(inReq, 403, "forbidden");
				return;
			}
			String expected = param(inReq, "expectedversion");
			if (expected == null)
			{
				fail(inReq, 400, "missing_expectedversion");
				return;
			}
			if (!expected.matches("[0-9]{1,6}"))
			{
				fail(inReq, 400, "bad_expectedversion");
				return;
			}
			LearningEngine.Blueprint b = new LearningEngine.Blueprint();
			b.topicid = topic.id;
			b.active = "true".equals(param(inReq, "active"));
			b.strategy = param(inReq, "strategy") == null ? "random" : param(inReq, "strategy");
			b.mix = param(inReq, "difficultymix") == null ? "proportional" : param(inReq, "difficultymix");
			b.requirelearncomplete = !"false".equals(param(inReq, "requirelearncomplete"));
			String[][] numbers = {{"maxquestions", "20"}, {"minpersubtopic", "0"}, {"passpercent", "70"}, {"subtopicminpercent", "0"}, {"timerminutes", "0"}, {"retakewaithours", "0"}, {"maxattempts", "0"}};
			int[] values = new int[numbers.length];
			for (int i = 0; i < numbers.length; i++)
			{
				String v = param(inReq, numbers[i][0]);
				if (v == null)
				{
					v = numbers[i][1];
				}
				if (!v.matches("-?[0-9]{1,6}"))
				{
					fail(inReq, 400, "bad_" + numbers[i][0]);
					return;
				}
				values[i] = Integer.parseInt(v);
			}
			b.maxquestions = values[0];
			b.minpersubtopic = values[1];
			b.passpercent = values[2];
			b.subtopicminpercent = values[3];
			b.timerminutes = values[4];
			b.retakewaithours = values[5];
			b.maxattempts = values[6];
			String excluded = param(inReq, "excludedsections");
			if (excluded != null)
			{
				Object parsed = org.json.simple.JSONValue.parse(excluded);
				if (!(parsed instanceof java.util.List))
				{
					fail(inReq, 400, "bad_excludedsections");
					return;
				}
				for (Object o : (java.util.List) parsed)
				{
					String sid = String.valueOf(o);
					boolean known = false;
					for (LearningEngine.Section s : topic.sections)
					{
						known |= s.id.equals(sid);
					}
					if (!known)
					{
						fail(inReq, 404, "unknown_section");
						return;
					}
					b.excludedsections.add(sid);
				}
			}
			String bad = LearningEngine.invalidField(b);
			if (bad != null)
			{
				fail(inReq, 400, "bad_" + bad);
				return;
			}
			LearningEngine.settle(b);
			JSONObject pool = LearningEngine.poolReport(topic, b);
			if (b.active && !Boolean.TRUE.equals(pool.get("sufficient")))
			{
				if (inReq.getResponse() != null)
				{
					inReq.getResponse().setStatus(409);
				}
				JSONObject err = new JSONObject();
				err.put("ok", Boolean.FALSE);
				err.put("error", "pool_insufficient");
				err.put("pool", pool);
				reply(inReq, err);
				inReq.setCancelActions(true);
				return;
			}
			boolean unchanged;
			synchronized (LearningEngine.WRITE_LOCK)
			{
				// loadContent's search can lag a just-saved version: probe the next ids with realtime gets.
				int v = topic.blueprint.version;
				Data latest = null;
				while (true)
				{
					Data d = (Data) searcher.searchById(topic.id + "_v" + (v + 1));
					if (d == null)
					{
						break;
					}
					latest = d;
					v++;
				}
				if (latest != null)
				{
					topic.blueprint = LearningEngine.blueprintOf(latest, topic.id);
				}
				if (Integer.parseInt(expected) != topic.blueprint.version)
				{
					if (inReq.getResponse() != null)
					{
						inReq.getResponse().setStatus(409);
					}
					JSONObject err = new JSONObject();
					err.put("ok", Boolean.FALSE);
					err.put("error", "version_conflict");
					err.put("currentversion", topic.blueprint.version);
					err.put("topic", blueprintJson(topic, stats));
					reply(inReq, err);
					inReq.setCancelActions(true);
					return;
				}
				unchanged = topic.blueprint.version > 0 && sameBlueprint(topic.blueprint, b);
				if (!unchanged)
				{
					JSONObject before = blueprintJson(topic, stats);
					before.remove("pool");
					before.remove("stats");
					int version = topic.blueprint.version + 1;
					b.version = version;
					b.user = user.getId();
					b.created = new Date();
					Data row = searcher.createNewData();
					row.setId(topic.id + "_v" + version);
					row.setValue("entitytopic", topic.id);
					row.setValue("blueprintversion", version);
					row.setValue("active", b.active);
					row.setValue("strategy", b.strategy);
					row.setValue("maxquestions", b.maxquestions);
					row.setValue("minpersubtopic", b.minpersubtopic);
					JSONArray ex = new JSONArray();
					ex.addAll(new java.util.TreeSet<>(b.excludedsections));
					row.setValue("excludedsections", ex.toJSONString());
					row.setValue("difficultymix", b.mix);
					row.setValue("passpercent", b.passpercent);
					row.setValue("subtopicminpercent", b.subtopicminpercent);
					row.setValue("timerminutes", b.timerminutes);
					row.setValue("retakewaithours", b.retakewaithours);
					row.setValue("maxattempts", b.maxattempts);
					row.setValue("requirelearncomplete", b.requirelearncomplete);
					row.setValue("user", b.user);
					row.setValue("datecreated", b.created);
					searcher.saveData(row, user);
					topic.blueprint = b;
					JSONObject after = blueprintJson(topic, stats);
					after.remove("pool");
					after.remove("stats");
					audit(inReq, archive, "evaluationblueprint.change", "entitytopic", topic.id, before, after);
				}
			}
			resp.put("unchanged", unchanged);
			resp.put("topic", blueprintJson(topic, stats));
			reply(inReq, resp);
			return;
		}
		java.util.List<Data> rows = new java.util.ArrayList<>();
		for (Object o : searcher.query().exact("entitytopic", topic.id).search())
		{
			rows.add((Data) o);
		}
		rows.sort((a, c) -> LearningEngine.intOr(c.get("blueprintversion"), 0) - LearningEngine.intOr(a.get("blueprintversion"), 0));
		JSONArray versions = new JSONArray();
		for (Data d : rows)
		{
			LearningEngine.Blueprint v = LearningEngine.blueprintOf(d, topic.id);
			JSONObject vj = v.toJson();
			vj.put("user", v.user);
			vj.put("datecreated", LearningEngine.iso(v.created));
			versions.add(vj);
		}
		resp.put("topic", blueprintJson(topic, stats));
		resp.put("versions", versions);
		reply(inReq, resp);
	}

	/** {id, title, ...blueprint fields, reason, pool, stats:{attempts, passed, inprogress}}; an unconfigured topic reports version 0 with the default pool. */
	private static JSONObject blueprintJson(LearningEngine.Topic t, java.util.Map<String, int[]> inStats)
	{
		JSONObject o = t.blueprint.toJson();
		o.put("id", t.id);
		o.put("title", t.title);
		o.put("pool", LearningEngine.poolReport(t, t.blueprint));
		int[] s = inStats.getOrDefault(t.id, new int[3]);
		JSONObject stats = new JSONObject();
		stats.put("attempts", s[0]);
		stats.put("passed", s[1]);
		stats.put("inprogress", s[2]);
		o.put("stats", stats);
		return o;
	}

	/** Same configured values (version, reason, user and date ignored). */
	private static boolean sameBlueprint(LearningEngine.Blueprint a, LearningEngine.Blueprint b)
	{
		JSONObject x = a.toJson();
		JSONObject y = b.toJson();
		for (String k : new String[] {"version", "reason"})
		{
			x.remove(k);
			y.remove(k);
		}
		return x.toJSONString().equals(y.toJSONString());
	}

	/** topic -> {finalized attempts, passed, in progress} over every evaluationattempt row (ponytail: full scan, fine for a pilot cohort). */
	private static java.util.Map<String, int[]> attemptStats(MediaArchive inArchive)
	{
		java.util.Map<String, int[]> out = new java.util.HashMap<>();
		HitTracker hits = inArchive.query("evaluationattempt").all().search();
		hits.enableBulkOperations();
		for (Object o : hits)
		{
			Data d = (Data) o;
			int[] s = out.computeIfAbsent(d.get("entitytopic"), k -> new int[3]);
			String status = d.get("status");
			if (status == null || "inprogress".equals(status))
			{
				s[2]++;
			}
			else
			{
				s[0]++;
				if ("true".equals(String.valueOf(d.get("passed"))))
				{
					s[1]++;
				}
			}
		}
		return out;
	}

```

Create `plugins/testu/html/services/testu/learn/evaluationblueprint.xconf`:

```xml
<page>
  <path-action name="TestULearningModule.evaluationBlueprint"/>
  <permission name="view"><user/></permission>
</page>
```

and `evaluationblueprint.json` containing exactly `$json`.

- [ ] **Step 2: Profile rows carry `evaluationrequired`**

In `TestUProfileModule.java`:

After the exact line `			r.requiresprevious = "true".equals(String.valueOf(m.get("requiresprevious")));` insert

```java
			r.evaluationrequired = "true".equals(String.valueOf(m.get("evaluationrequired")));
```

After the exact line `				d.setValue("requiresprevious", r.requiresprevious ? "true" : "false");` insert

```java
				d.setValue("evaluationrequired", r.evaluationrequired ? "true" : "false");
```

After the exact line `			o.put("requiresprevious", Boolean.valueOf(r.requiresprevious));` insert

```java
			o.put("evaluationrequired", Boolean.valueOf(r.evaluationrequired));
```

- [ ] **Step 3: Analytics person.json**

In `TestUAnalyticsModule.java`, after the exact line `			ro.put("position", t.position);` insert

```java
			JSONObject evfull = (JSONObject) st.get("evaluation");
			JSONObject ev = new JSONObject();
			ev.put("status", evfull == null ? null : evfull.get("status"));
			ev.put("scorepercent", evfull == null ? null : evfull.get("scorepercent"));
			ev.put("passedat", evfull == null ? null : evfull.get("passedat"));
			ev.put("attempts", evfull == null ? null : evfull.get("attempts"));
			boolean evmet = !t.evaluationrequired || (evfull != null && Boolean.TRUE.equals(evfull.get("passed")));
			ro.put("evaluationrequired", Boolean.valueOf(t.evaluationrequired));
			ro.put("evaluation", ev);
			ro.put("evaluationmet", Boolean.valueOf(evmet));
```

and replace the exact block

```java
			if (!Boolean.TRUE.equals(st.get("meetsrequirement")))
				gaps++;
```

with

```java
			if (!Boolean.TRUE.equals(st.get("meetsrequirement")) || !evmet)
				gaps++;
```

- [ ] **Step 4: Compile, restart, extend the server checks**

`bin/compile.sh`; notify peers; restart Tomcat; wait for "Server startup in".

Append to `tools/check_evaluation.sh`, inside the `try:` block just before the `# ---- inactive and legacy rows` section (so the blueprint rows it creates are cleaned up by `cleanup()`), the admin/profile/analytics section. It uses the seeded versions so far (`VERSION[0]` is the current version):

```python
    # ---- admin endpoint: evaluationblueprint.json
    BPP = "/services/testu/learn/evaluationblueprint.json"
    r = call(me, "GET", BPP)
    ok("blueprint: learner 403", r[0] == 403 and r[1]["error"] == "forbidden", r)
    r = call(me, "POST", BPP, form={"topicid": T, "expectedversion": "0"})
    ok("blueprint: learner POST 403", r[0] == 403, r)
    lst = must("blueprint list", call(admin, "GET", BPP))
    row = next(t for t in lst["topics"] if t["id"] == T)
    ok("blueprint list: canmanage, current version, pool report with per-subtopic numbers and stats", lst["canmanage"] is True and row["version"] == VERSION[0] and row["pool"]["size"] >= 1 and len(row["pool"]["bysection"]) >= 2 and "attempts" in row["stats"], row)
    other = next((t for t in lst["topics"] if t["id"] != T), None)
    if other:
        ok("blueprint list: unconfigured topic reports version 0 / not_configured with its pool", other["version"] == 0 and other["reason"] == "not_configured" and "size" in other["pool"], other)

    def save(op=None, expected=None, **f):
        if expected is None:
            expected = must("blueprint get", call(admin, "GET", BPP + "?topicid=" + quote(T)))["topic"]["version"]
        form = {"topicid": T, "expectedversion": str(expected), "active": "true", "strategy": "random", "maxquestions": str(MAXQ), "minpersubtopic": "0",
                "difficultymix": "proportional", "passpercent": "60", "subtopicminpercent": "0", "timerminutes": "0", "retakewaithours": "0", "maxattempts": "0",
                "requirelearncomplete": "false"}
        form.update({k: str(v) for k, v in f.items()})
        return call(op or admin, "POST", BPP, form=form)

    for field, value, err in (("maxquestions", "0", "bad_maxquestions"), ("maxquestions", "x", "bad_maxquestions"), ("passpercent", "101", "bad_passpercent"),
                              ("strategy", "common", "bad_strategy"), ("difficultymix", "odd", "bad_difficultymix"), ("timerminutes", "481", "bad_timerminutes"),
                              ("retakewaithours", "-1", "bad_retakewaithours"), ("excludedsections", "nope", "bad_excludedsections"), ("excludedsections", '["nope"]', "unknown_section")):
        r = save(**{field: value})
        ok(f"blueprint save: {field}={value} -> {err}", r[0] in (400, 404) and r[1]["error"] == err, r)
    r = save(expected="999")
    ok("blueprint save: stale expectedversion -> 409 version_conflict with currentversion", r[0] == 409 and r[1]["error"] == "version_conflict" and r[1]["currentversion"] == VERSION[0], r)
    r = call(admin, "POST", BPP, form={"topicid": T, "active": "true"})
    ok("blueprint save: missing expectedversion -> 400", r[0] == 400 and r[1]["error"] == "missing_expectedversion", r)
    r = save(minpersubtopic="1000")
    ok("blueprint save: activating over an insufficient pool -> 409 pool_insufficient with the report", r[0] == 409 and r[1]["error"] == "pool_insufficient" and r[1]["pool"]["shortfall"] == "subtopic_below_min", r)
    r = save(minpersubtopic="1000", active="false")
    ok("blueprint save: the same draft saves inactive as v+1", r[0] == 200 and r[1]["unchanged"] is False and r[1]["topic"]["version"] == VERSION[0] + 1 and r[1]["topic"]["active"] is False and r[1]["topic"]["reason"] == "inactive", r)
    VERSION[0] += 1
    BP.append(f"{T}_v{VERSION[0]}")
    r = save(minpersubtopic="1000", active="false")
    ok("blueprint save: identical values -> unchanged, no version", r[0] == 200 and r[1]["unchanged"] is True and r[1]["topic"]["version"] == VERSION[0], r)
    sec = min(topic_of(state(), T)["sections"], key=lambda s: s["questions"])["id"]  # the smallest subtopic, so the rest still covers MAXQ
    r = save(excludedsections=json.dumps([sec]), minpersubtopic="1")
    ok("blueprint save: active with an excluded subtopic and min 1 -> v+1, excludedsections stored", r[0] == 200 and r[1]["topic"]["version"] == VERSION[0] + 1 and r[1]["topic"]["excludedsections"] == [sec] and r[1]["topic"]["reason"] is None, r)
    VERSION[0] += 1
    BP.append(f"{T}_v{VERSION[0]}")
    hist = must("blueprint history", call(admin, "GET", BPP + "?topicid=" + quote(T)))
    ok("blueprint history: newest first, user and date", hist["versions"][0]["version"] == VERSION[0] and hist["versions"][0]["user"] == os.environ["EME_USER"] and hist["versions"][0]["datecreated"], hist["versions"][:2])
    ok("blueprint audit written", any(a["action"] == "evaluationblueprint.change" for a in audits("evaluationblueprint.change", T)), "")
    st, e = ev()
    ok("learner sees the new version's pool (excluded subtopic never served)", e["status"] == "available", e)
    st, s5 = start()
    ok("start: no item from the excluded subtopic", st == 200 and all(i["sectionid"] != sec for i in s5["items"]), [i["sectionid"] for i in s5["items"]])
    delete_rows("evaluationattempt", es_ids("evaluationattempt", {"term": {"user": USER}}))
    refresh()

    # ---- profile row + analytics
    put_row("jobrole", "echeck-role", {"name": "Echeck Role"})
    put_row("topicrequirement", "echeck-r1", {"jobrole": "echeck-role", "entitytopic": T, "position": "1", "requiredlevel": "", "mandatory": "true", "requiresprevious": "false", "afterfinish": "keep", "evaluationrequired": "true"})
    BP_EXTRA = [("topicrequirement", "echeck-r1"), ("jobrole", "echeck-role")]
    usersave("jobrole", "echeck-role")
    usersave("primaryjobrole", "echeck-role")
    refresh()
    t = topic_of(state(), T)
    ok("state: profile row marks the evaluation required; topic not finished", t["evaluation"]["required"] is True and t["finished"] is False, t["evaluation"])
    prof = must("profiles.json", call(admin, "GET", "/services/testu/personas/profiles.json"))
    prow = next(p for p in prof["profiles"] if p["id"] == "echeck-role")["rows"][0]
    ok("profiles.json: evaluationrequired on the row", prow["evaluationrequired"] is True, prow)
    r = call(admin, "POST", "/services/testu/personas/saveprofile.json", form={"id": "echeck-role", "name": "Echeck Role", "rows": json.dumps([{"topic": T, "requiredlevel": "", "mandatory": True, "requiresprevious": False, "afterfinish": "keep", "evaluationrequired": False}])})
    ok("saveprofile: evaluationrequired round-trips (false)", r[0] == 200 and r[1]["profile"]["rows"][0]["evaluationrequired"] is False, r)
    r = call(admin, "POST", "/services/testu/personas/saveprofile.json", form={"id": "echeck-role", "name": "Echeck Role", "rows": json.dumps([{"topic": T, "requiredlevel": "", "mandatory": True, "requiresprevious": False, "afterfinish": "keep", "evaluationrequired": True}])})
    ok("saveprofile: evaluationrequired round-trips (true)", r[0] == 200 and r[1]["profile"]["rows"][0]["evaluationrequired"] is True, r)
    refresh()
    person = must("person.json", call(admin, "GET", "/services/testu/analytics/person.json?userid=" + quote(USER)))
    prow = next((x for x in person["risk"]["requiredtopics"] if x["id"] == T), None)
    ok("person.json: evaluationrequired, evaluation status and evaluationmet false; counted as a gap", prow and prow["evaluationrequired"] is True and prow["evaluationmet"] is False and prow["evaluation"]["status"] in ("available", "locked", "waiting", "exhausted") and person["risk"]["requiredgaps"] >= 1, prow)
    usersave("jobrole", "")
    usersave("primaryjobrole", "")
    for table, rid in BP_EXTRA:
        delete_rows(table, [rid])
    refresh()
```

If `person.json` takes its user parameter under another name, read the top of `TestUAnalyticsModule.person` and use that name. Run the script: `evaluation checks: PASS`.

- [ ] **Step 5: Tutor guard (conditional)**

Run `git -C /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur status --short plugins/finder/code/org/entermediadb/ai/skills/AdaptiveTutorialAnswerSkill.java`. **If the file is already modified by a peer (an `M` line), skip this step and record in the ledger: "tutor guard parked — file dirty from a peer session; the app never opens the chat during an attempt".** Otherwise, in that file, after the exact line

```java
			iscorrect = "true".equals(String.valueOf(stored.getValue("iscorrect")));
```

insert

```java
			if ("evaluation".equals(stored.get("mode")))
			{
				// Evaluation Mode is procedural-only (TestU spec 2026-09-16-evaluation-mode): no verdict, explanation, hint or source during an attempt.
				LlmResponse procedural = new BasicLlmResponse();
				procedural.setMessage("Respuesta registrada. Verás tu resultado al terminar la evaluación.");
				tutorMessageContext.setLastResponse(procedural);
				tutorMessageContext.putContextValue("messagerendertype", "answereval");
				return;
			}
```

then `bin/compile.sh`, and commit that single file in the server repo on its current branch:

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && git add plugins/finder/code/org/entermediadb/ai/skills/AdaptiveTutorialAnswerSkill.java && git commit -m "feat(evaluation): procedural-only tutor reply for evaluation answers"
```

- [ ] **Step 6: Commit**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/testu && git add code/tech/genailabs/tutor/TestULearningModule.java code/tech/genailabs/tutor/TestUProfileModule.java code/tech/genailabs/tutor/TestUAnalyticsModule.java html/services/testu/learn/evaluationblueprint.xconf html/services/testu/learn/evaluationblueprint.json tools/check_evaluation.sh && git commit -m "feat(evaluation): blueprint endpoint, evaluationrequired on profile rows, analytics fields, checks"
```

---

### Task 5: Admin console — models, API, Evaluations screen, profile-editor column

**Files:**
- Modify: `app-genailabs/lib/admin/admin_models.dart`
- Modify: `app-genailabs/lib/admin/admin_api.dart`
- Create: `app-genailabs/lib/admin/admin_evaluations.dart`
- Modify: `app-genailabs/lib/admin/admin_shell.dart`
- Modify: `app-genailabs/lib/admin/admin_profiles.dart`
- Test: `app-genailabs/test/admin_evaluations_test.dart` (new), `app-genailabs/test/admin_profiles_test.dart` (+1 case, wider surface)

**Interfaces:**
- Consumes: `evaluationblueprint.json` (Task 4), `profiles.json` rows with `evaluationrequired`; existing `Select`, `AdminTable`, `AdminColumn`, `StatRow`, `StatBlock`, `Skeleton`, `ConsolePanelError`, `crossfade`, `consoleField`, `AdminTokens`, `errText`, `AdminSection`.
- Produces: `EvaluationBlueprint`, `BlueprintPool`, `BlueprintVersion`, `ProfileRow.evaluationRequired`; `AdminApi.evaluationBlueprints()`, `evaluationBlueprint(topicId)`, `saveEvaluationBlueprint(...)`; `AdminEvaluations` widget; shell section id `evaluations`.

Never run `dart format` on these files (hand-formatted).

- [ ] **Step 1: Models**

In `admin_models.dart`, in `ProfileRow`:
- constructor: after the exact line `    this.afterFinish = 'keep',` insert `    this.evaluationRequired = false,`.
- fields: replace the exact line `  final bool mandatory, requiresPrevious;` with `  final bool mandatory, requiresPrevious, evaluationRequired;`.
- `fromJson`: after the exact line `        afterFinish: j['afterfinish'] == 'remove' ? 'remove' : 'keep',` insert `        evaluationRequired: j['evaluationrequired'] == true,`.
- `copyWith`: add parameter `    bool? evaluationRequired,` after `    String? afterFinish,` and the line `        evaluationRequired: evaluationRequired ?? this.evaluationRequired,` after `        afterFinish: afterFinish ?? this.afterFinish,`.
- `toJson`: add `        'evaluationrequired': evaluationRequired,` as the last map entry (after the `'afterfinish'` entry; read the map to place it).

Append after the `PolicyVersion` class:

```dart
/// A topic's evaluation blueprint (learn/evaluationblueprint.json). [version]
/// 0 = never configured; [reason] not_configured | inactive | invalid_<field>
/// | null (usable). The server owns the pool report and the rules; the
/// console only shows them and posts what the admin picked.
class EvaluationBlueprint {
  EvaluationBlueprint({
    required this.id,
    required this.title,
    this.version = 0,
    this.active = false,
    this.strategy = 'random',
    this.maxQuestions = 20,
    this.minPerSubtopic = 0,
    this.excludedSections = const [],
    this.difficultyMix = 'proportional',
    this.passPercent = 70,
    this.subtopicMinPercent = 0,
    this.timerMinutes = 0,
    this.retakeWaitHours = 0,
    this.maxAttempts = 0,
    this.requireLearnComplete = true,
    this.reason,
    this.pool,
    this.attempts = 0,
    this.passed = 0,
    this.inProgress = 0,
  });
  final String id, title, strategy, difficultyMix;
  final int version, maxQuestions, minPerSubtopic, passPercent, subtopicMinPercent, timerMinutes, retakeWaitHours, maxAttempts;
  final int attempts, passed, inProgress;
  final bool active, requireLearnComplete;
  final List<String> excludedSections;
  final String? reason;
  final BlueprintPool? pool;

  bool get usable => reason == null;

  factory EvaluationBlueprint.fromJson(Map j) => EvaluationBlueprint(
        id: '${j['id'] ?? ''}',
        title: '${j['title'] ?? j['id'] ?? ''}',
        version: (j['version'] as num?)?.toInt() ?? 0,
        active: j['active'] == true,
        strategy: '${j['strategy'] ?? 'random'}',
        maxQuestions: (j['maxquestions'] as num?)?.toInt() ?? 20,
        minPerSubtopic: (j['minpersubtopic'] as num?)?.toInt() ?? 0,
        excludedSections: [for (final s in j['excludedsections'] as List? ?? const []) '$s'],
        difficultyMix: '${j['difficultymix'] ?? 'proportional'}',
        passPercent: (j['passpercent'] as num?)?.toInt() ?? 70,
        subtopicMinPercent: (j['subtopicminpercent'] as num?)?.toInt() ?? 0,
        timerMinutes: (j['timerminutes'] as num?)?.toInt() ?? 0,
        retakeWaitHours: (j['retakewaithours'] as num?)?.toInt() ?? 0,
        maxAttempts: (j['maxattempts'] as num?)?.toInt() ?? 0,
        requireLearnComplete: j['requirelearncomplete'] != false,
        reason: j['reason']?.toString(),
        pool: j['pool'] is Map ? BlueprintPool.fromJson(j['pool'] as Map) : null,
        attempts: ((j['stats'] as Map?)?['attempts'] as num?)?.toInt() ?? 0,
        passed: ((j['stats'] as Map?)?['passed'] as num?)?.toInt() ?? 0,
        inProgress: ((j['stats'] as Map?)?['inprogress'] as num?)?.toInt() ?? 0,
      );

  EvaluationBlueprint copyWith({
    bool? active,
    String? strategy,
    int? maxQuestions,
    int? minPerSubtopic,
    List<String>? excludedSections,
    String? difficultyMix,
    int? passPercent,
    int? subtopicMinPercent,
    int? timerMinutes,
    int? retakeWaitHours,
    int? maxAttempts,
    bool? requireLearnComplete,
  }) =>
      EvaluationBlueprint(
        id: id,
        title: title,
        version: version,
        active: active ?? this.active,
        strategy: strategy ?? this.strategy,
        maxQuestions: maxQuestions ?? this.maxQuestions,
        minPerSubtopic: minPerSubtopic ?? this.minPerSubtopic,
        excludedSections: excludedSections ?? this.excludedSections,
        difficultyMix: difficultyMix ?? this.difficultyMix,
        passPercent: passPercent ?? this.passPercent,
        subtopicMinPercent: subtopicMinPercent ?? this.subtopicMinPercent,
        timerMinutes: timerMinutes ?? this.timerMinutes,
        retakeWaitHours: retakeWaitHours ?? this.retakeWaitHours,
        maxAttempts: maxAttempts ?? this.maxAttempts,
        requireLearnComplete: requireLearnComplete ?? this.requireLearnComplete,
        reason: reason,
        pool: pool,
        attempts: attempts,
        passed: passed,
        inProgress: inProgress,
      );

  /// What evaluationblueprint.json takes on POST (expectedversion added by the API).
  Map<String, String> toForm() => {
        'active': '$active',
        'strategy': strategy,
        'maxquestions': '$maxQuestions',
        'minpersubtopic': '$minPerSubtopic',
        'excludedsections': jsonEncode(excludedSections),
        'difficultymix': difficultyMix,
        'passpercent': '$passPercent',
        'subtopicminpercent': '$subtopicMinPercent',
        'timerminutes': '$timerMinutes',
        'retakewaithours': '$retakeWaitHours',
        'maxattempts': '$maxAttempts',
        'requirelearncomplete': '$requireLearnComplete',
      };
}

/// The server's pool report for a blueprint: can the topic's questions
/// satisfy it? [shortfall] pool_below_max | subtopic_below_min |
/// minimums_exceed_max | null.
class BlueprintPool {
  BlueprintPool(this.size, this.sufficient, this.shortfall, this.bySection, this.byDifficulty);
  final int size;
  final bool sufficient;
  final String? shortfall;
  final List<({String id, String title, int position, bool covered, int sequence, int reserved, int inPool})> bySection;
  final Map<String, int> byDifficulty;

  factory BlueprintPool.fromJson(Map j) => BlueprintPool(
        (j['size'] as num?)?.toInt() ?? 0,
        j['sufficient'] == true,
        j['shortfall']?.toString(),
        [
          for (final s in j['bysection'] as List? ?? const [])
            (
              id: '${s['id']}',
              title: '${s['title'] ?? ''}',
              position: (s['position'] as num?)?.toInt() ?? 0,
              covered: s['covered'] != false,
              sequence: (s['sequence'] as num?)?.toInt() ?? 0,
              reserved: (s['reserved'] as num?)?.toInt() ?? 0,
              inPool: (s['inpool'] as num?)?.toInt() ?? 0,
            ),
        ],
        {
          for (final e in (j['bydifficulty'] as Map? ?? const {}).entries)
            '${e.key}': (e.value as num?)?.toInt() ?? 0,
        },
      );
}

class BlueprintVersion {
  BlueprintVersion(this.version, this.active, this.strategy, this.maxQuestions, this.passPercent, this.timerMinutes, this.user, this.dateCreated);
  final int version, maxQuestions, passPercent, timerMinutes;
  final bool active;
  final String strategy;
  final String? user, dateCreated;

  factory BlueprintVersion.fromJson(Map j) => BlueprintVersion(
        (j['version'] as num?)?.toInt() ?? 0,
        j['active'] == true,
        '${j['strategy'] ?? ''}',
        (j['maxquestions'] as num?)?.toInt() ?? 0,
        (j['passpercent'] as num?)?.toInt() ?? 0,
        (j['timerminutes'] as num?)?.toInt() ?? 0,
        j['user']?.toString(),
        j['datecreated']?.toString(),
      );
}
```

`jsonEncode` needs `import 'dart:convert';` at the top of `admin_models.dart` if not already imported (check the first lines).

- [ ] **Step 2: API client**

In `admin_api.dart`, after the exact line `  static const _policyPath = 'services/testu/learn/subtopicpolicy.json';` insert:

```dart
  static const _blueprintPath = 'services/testu/learn/evaluationblueprint.json';

  /// Every topic's evaluation blueprint with its pool report and stats.
  Future<({bool canManage, List<EvaluationBlueprint> topics})> evaluationBlueprints() async {
    final j = await _http.getJson(_blueprintPath);
    return (
      canManage: j['canmanage'] == true,
      topics: [for (final t in j['topics'] as List? ?? const []) EvaluationBlueprint.fromJson(t)],
    );
  }

  /// One topic's blueprint with its version history, newest first.
  Future<({bool canManage, EvaluationBlueprint topic, List<BlueprintVersion> versions})> evaluationBlueprint(String topicId) async {
    final j = await _http.getJson(_blueprintPath, query: {'topicid': topicId});
    return (
      canManage: j['canmanage'] == true,
      topic: EvaluationBlueprint.fromJson(j['topic'] as Map? ?? const {}),
      versions: [for (final v in j['versions'] as List? ?? const []) BlueprintVersion.fromJson(v)],
    );
  }

  /// Saves [b] as a new version on top of [expectedVersion]. A refusal
  /// (403 forbidden, 400 bad_<field>, 404 unknown_section, 409
  /// version_conflict | pool_insufficient) throws `Exception(code)`.
  Future<({EvaluationBlueprint topic, bool unchanged})> saveEvaluationBlueprint(EvaluationBlueprint b, {required int expectedVersion}) async {
    try {
      final j = await _http.postForm(_blueprintPath, [
        MapEntry('topicid', b.id),
        MapEntry('expectedversion', '$expectedVersion'),
        ...b.toForm().entries,
      ]);
      if (j['ok'] != true) throw Exception('${j['error'] ?? 'error'}');
      return (topic: EvaluationBlueprint.fromJson(j['topic'] as Map? ?? const {}), unchanged: j['unchanged'] == true);
    } on EmeHttpException catch (e) {
      final b = e.body;
      if (b is Map && b['error'] != null) throw Exception('${b['error']}');
      rethrow;
    }
  }
```

- [ ] **Step 3: The Evaluations screen**

Create `app-genailabs/lib/admin/admin_evaluations.dart`:

```dart
import 'package:flutter/material.dart';

import '../testu/testu_i18n.dart';
import '../testu/testu_theme.dart';
import 'admin_api.dart';
import 'admin_models.dart';
import 'admin_theme.dart';
import 'admin_ui.dart';

/// Evaluaciones: each topic's evaluation blueprint (learn/evaluationblueprint.json)
/// with the server's pool report and version history. The server owns the
/// rules, the pool arithmetic and the permission: the editor shows only when
/// it says `canmanage`, and a refusal shows here as the server phrased it.
class AdminEvaluations extends StatefulWidget {
  const AdminEvaluations({super.key, required this.api, required this.me});
  final AdminApi api;
  final AdminMe me;

  @override
  State<AdminEvaluations> createState() => _AdminEvaluationsState();
}

String strategyLabel(String s) => switch (s) {
      'reserved' => L('Reserved questions', 'Preguntas reservadas'),
      _ => L('Random per person', 'Aleatoria por persona'),
    };

String strategyDescribe(String? s) => switch (s) {
      'reserved' => L('Measures knowledge on questions never shown in Learn, Improve or Daily Challenge. Trade-off: fewer questions for learning.',
          'Mide con preguntas nunca vistas en Aprender, Mejorar ni Reto diario. Costo: menos preguntas para aprender.'),
      _ => L('Each attempt draws its own set from the pool, comparable through the blueprint. Trade-off: needs a pool larger than the maximum.',
          'Cada intento saca su propio set del banco, comparable por el blueprint. Costo: necesita un banco mayor que el máximo.'),
    };

String mixLabel(String m) => m == 'balanced' ? L('Balanced', 'Equilibrada') : L('Proportional to the pool', 'Proporcional al banco');

/// One line for the table: what the pool report says about this blueprint.
String poolLine(BlueprintPool? p) {
  if (p == null) return '';
  return switch (p.shortfall) {
    null => L('${p.size} in pool · sufficient', '${p.size} en el banco · suficiente'),
    'pool_below_max' => L('${p.size} in pool · below the maximum', '${p.size} en el banco · menos que el máximo'),
    'subtopic_below_min' => L('${p.size} in pool · a subtopic is short of its minimum', '${p.size} en el banco · un subtema no llega al mínimo'),
    _ => L('${p.size} in pool · minimums exceed the maximum', '${p.size} en el banco · los mínimos superan el máximo'),
  };
}

String statusLabel(EvaluationBlueprint b) => b.version == 0
    ? L('Not configured', 'Sin configurar')
    : b.usable
        ? L('Active', 'Activa')
        : b.reason == 'inactive'
            ? L('Draft', 'Borrador')
            : L('Invalid', 'Inválida');

String _errorLine(String code) => switch (code) {
      'forbidden' => L('You do not have permission to change evaluations.', 'No tienes permiso para cambiar evaluaciones.'),
      'version_conflict' => L('Someone else changed this blueprint while you were editing. It has been reloaded: review it and save again.',
          'Alguien cambió este blueprint mientras editabas. Se recargó: revísalo y guarda de nuevo.'),
      'pool_insufficient' => L('The topic does not have enough questions for this blueprint. Lower the maximum or the minimums, or save it as a draft.',
          'El tema no tiene preguntas suficientes para este blueprint. Baja el máximo o los mínimos, o guárdalo como borrador.'),
      'unknown_section' => L('One of the subtopics no longer exists. Reload and try again.', 'Uno de los subtemas ya no existe. Recarga e inténtalo de nuevo.'),
      _ when code.startsWith('bad_') => L('The value of "${code.substring(4)}" is out of range.', 'El valor de «${code.substring(4)}» está fuera de rango.'),
      _ => L('Could not save: $code', 'No se pudo guardar: $code'),
    };

class _AdminEvaluationsState extends State<AdminEvaluations> {
  List<EvaluationBlueprint>? _topics;
  bool _canManage = false;
  Object? _error;

  /// The open topic, its history, and the editor's unsaved draft.
  String? _open;
  List<BlueprintVersion>? _versions;
  EvaluationBlueprint? _draft;
  final _numbers = <String, TextEditingController>{};
  String? _saveError, _saveNote;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    for (final c in _numbers.values) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final r = await widget.api.evaluationBlueprints();
      if (!mounted) return;
      setState(() {
        _topics = r.topics;
        _canManage = r.canManage;
        _error = null;
      });
    } catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  TextEditingController _num(String key, int value) =>
      _numbers.putIfAbsent(key, () => TextEditingController())..text = '$value';

  Future<void> _select(EvaluationBlueprint t) async {
    setState(() {
      _open = t.id;
      _versions = null;
      _draft = t.version == 0 ? t.copyWith(active: false) : t;
      _saveError = null;
      _saveNote = null;
      for (final e in {
        'maxquestions': t.maxQuestions,
        'minpersubtopic': t.minPerSubtopic,
        'passpercent': t.passPercent,
        'subtopicminpercent': t.subtopicMinPercent,
        'timerminutes': t.timerMinutes,
        'retakewaithours': t.retakeWaitHours,
        'maxattempts': t.maxAttempts,
      }.entries) {
        _num(e.key, e.value);
      }
    });
    try {
      final r = await widget.api.evaluationBlueprint(t.id);
      if (!mounted || _open != t.id) return;
      setState(() {
        _versions = r.versions;
        _canManage = r.canManage;
      });
    } catch (_) {
      if (mounted && _open == t.id) setState(() => _versions = const []);
    }
  }

  int _int(String key) => int.tryParse(_numbers[key]?.text.trim() ?? '') ?? -1;

  /// The draft with the number fields read back from their controllers.
  EvaluationBlueprint get _payload => _draft!.copyWith(
        maxQuestions: _int('maxquestions'),
        minPerSubtopic: _int('minpersubtopic'),
        passPercent: _int('passpercent'),
        subtopicMinPercent: _int('subtopicminpercent'),
        timerMinutes: _int('timerminutes'),
        retakeWaitHours: _int('retakewaithours'),
        maxAttempts: _int('maxattempts'),
      );

  Future<void> _save() async {
    final id = _open!;
    final loaded = _topics?.where((t) => t.id == id).firstOrNull;
    setState(() {
      _saving = true;
      _saveError = null;
      _saveNote = null;
    });
    String? note, error;
    try {
      final r = await widget.api.saveEvaluationBlueprint(_payload, expectedVersion: loaded?.version ?? 0);
      note = r.unchanged
          ? L('No change: this is already the current blueprint (v${r.topic.version}).', 'Sin cambios: ya es el blueprint actual (v${r.topic.version}).')
          : L('Saved as v${r.topic.version}.', 'Guardado como v${r.topic.version}.');
    } catch (e) {
      error = _errorLine(errText(e));
    }
    if (!mounted) return;
    // A refusal that is not a conflict keeps the draft; a conflict or a save reloads what the server has.
    final keepDraft = error != null && !error.startsWith(_errorLine('version_conflict'));
    await _load();
    final t = _topics?.where((t) => t.id == id).firstOrNull;
    if (t != null && !keepDraft) await _select(t);
    if (mounted) {
      setState(() {
        _saving = false;
        _saveNote = note;
        _saveError = error;
      });
    }
  }

  @override
  Widget build(BuildContext context) => crossfade(_body(context));

  Widget _body(BuildContext context) {
    if (_error != null) {
      return ConsolePanelError(
        text: L('Could not load evaluation blueprints.', 'No se pudieron cargar los blueprints de evaluación.'),
        onRetry: _load,
      );
    }
    final topics = _topics;
    if (topics == null) return const Skeleton(lines: 6, height: 22);
    final t = TestuTokens.of(context);
    final open = topics.where((x) => x.id == _open).firstOrNull;
    final active = topics.where((x) => x.usable).length;
    final drafts = topics.where((x) => x.version > 0 && !x.usable).length;
    final short = topics.where((x) => x.usable && x.pool?.sufficient == false).length;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        StatRow([
          StatBlock(label: L('Topics', 'Temas'), value: grouped(topics.length)),
          StatBlock(label: L('Active evaluations', 'Evaluaciones activas'), value: grouped(active)),
          StatBlock(label: L('Drafts', 'Borradores'), value: grouped(drafts)),
          StatBlock(
            label: L('Pool warnings', 'Avisos de banco'),
            value: grouped(short),
            highlight: short > 0,
            delta: short == 0 ? L('every active blueprint is covered', 'todo blueprint activo está cubierto') : L('content shrank after activation', 'el contenido se redujo tras activar'),
            deltaPositive: short == 0 ? true : null,
          ),
        ]),
        const SizedBox(height: 24),
        Text(
          L('A new version applies from the next attempt; an attempt in progress keeps the version it started with.',
              'Una versión nueva aplica desde el siguiente intento; un intento en curso conserva la versión con la que empezó.'),
          style: AdminTokens.footnote,
        ),
        const SizedBox(height: 16),
        AdminTable<EvaluationBlueprint>(
          rows: topics,
          onTap: _select,
          emptyText: L('No topics yet.', 'Todavía no hay temas.'),
          columns: [
            AdminColumn(L('Topic', 'Tema'), (x) => Text(x.title, maxLines: 1, overflow: TextOverflow.ellipsis), sortKey: (x) => x.title, flex: 2),
            AdminColumn(L('Status', 'Estado'), (x) => Row(children: [
                  Text(statusLabel(x)),
                  if (x.usable && x.pool?.sufficient == false) ...[
                    const SizedBox(width: 6),
                    Tooltip(message: poolLine(x.pool), child: Text('!', key: Key('pool-warning-${x.id}'), style: AdminTokens.mono(11.5, color: AdminTokens.focus))),
                  ],
                ]), width: 130),
            AdminColumn(L('Strategy', 'Estrategia'), (x) => Text(x.version == 0 ? '—' : strategyLabel(x.strategy), maxLines: 1, overflow: TextOverflow.ellipsis), flex: 1),
            AdminColumn(L('Questions', 'Preguntas'), (x) => Text(x.version == 0 ? '—' : x.minPerSubtopic > 0 ? '${x.maxQuestions} · ${L('min', 'mín')} ${x.minPerSubtopic}' : '${x.maxQuestions}'), width: 110),
            AdminColumn(L('Pass', 'Aprobar'), (x) => Text(x.version == 0 ? '—' : x.subtopicMinPercent > 0 ? '${x.passPercent} % · ${x.subtopicMinPercent} %' : '${x.passPercent} %'), width: 110),
            AdminColumn(L('Timer', 'Tiempo'), (x) => Text(x.version == 0 || x.timerMinutes == 0 ? '—' : '${x.timerMinutes} min', style: TextStyle(color: t.mut)), width: 80),
            AdminColumn(L('Attempts', 'Intentos'), (x) => Text('${x.attempts} · ${x.passed} ${L('passed', 'aprob.')}', style: TextStyle(color: t.mut)), width: 120),
            AdminColumn(L('Version', 'Versión'), (x) => Text(x.version == 0 ? '—' : 'v${x.version}', style: TextStyle(color: t.mut)), width: 80, numeric: true),
          ],
        ),
        if (open != null && _draft != null) ...[
          const SizedBox(height: 24),
          Text(open.title.toUpperCase(), style: AdminTokens.eyebrow),
          const SizedBox(height: 10),
          if (_canManage) _editor(t, open) else _readOnly(open),
          const SizedBox(height: 14),
          _poolPanel(t, open.pool),
          const SizedBox(height: 20),
          Text(L('VERSION HISTORY', 'HISTORIAL DE VERSIONES'), style: AdminTokens.eyebrow),
          const SizedBox(height: 8),
          if (_versions == null)
            const Skeleton(lines: 3, height: 18)
          else if (_versions!.isEmpty)
            Text(L('Never configured.', 'Nunca configurado.'), style: AdminTokens.muted)
          else
            for (final v in _versions!)
              Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: Text(
                  [
                    'v${v.version}',
                    v.active ? L('active', 'activa') : L('draft', 'borrador'),
                    strategyLabel(v.strategy),
                    '${v.maxQuestions} ${L('questions', 'preguntas')}',
                    '${v.passPercent} %',
                    if (v.timerMinutes > 0) '${v.timerMinutes} min',
                    ?v.user,
                    ?v.dateCreated,
                  ].join(' · '),
                  style: AdminTokens.table,
                ),
              ),
        ],
      ],
    );
  }

  Widget _readOnly(EvaluationBlueprint b) => Text(
        b.version == 0
            ? L('Not configured.', 'Sin configurar.')
            : '${statusLabel(b)} · ${strategyLabel(b.strategy)} · ${b.maxQuestions} ${L('questions', 'preguntas')} · ${b.passPercent} %',
        key: const ValueKey('blueprint-readonly'),
        style: AdminTokens.body,
      );

  Widget _field(TestuTokens t, String key, String label, {String? hint}) => SizedBox(
        width: 190,
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(label, style: AdminTokens.tableHead),
          const SizedBox(height: 4),
          TextField(
            key: Key('blueprint-$key'),
            controller: _numbers[key],
            keyboardType: TextInputType.number,
            style: AdminTokens.table,
            decoration: consoleField(t, hint: hint),
          ),
        ]),
      );

  Widget _editor(TestuTokens t, EvaluationBlueprint loaded) {
    final d = _draft!;
    final sections = loaded.pool?.bySection ?? const [];
    return Column(
      key: const ValueKey('blueprint-editor'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(spacing: 10, runSpacing: 12, crossAxisAlignment: WrapCrossAlignment.end, children: [
          SizedBox(
            width: 260,
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(L('Strategy', 'Estrategia'), style: AdminTokens.tableHead),
              const SizedBox(height: 4),
              Select<String>(
                key: const ValueKey('blueprint-strategy'),
                value: d.strategy,
                fill: true,
                items: [('random', strategyLabel('random')), ('reserved', strategyLabel('reserved'))],
                describe: strategyDescribe,
                onChanged: (v) => setState(() => _draft = d.copyWith(strategy: v ?? 'random')),
              ),
            ]),
          ),
          _field(t, 'maxquestions', L('Max questions', 'Máximo de preguntas')),
          _field(t, 'minpersubtopic', L('Min per subtopic (0 = none)', 'Mínimo por subtema (0 = ninguno)')),
          SizedBox(
            width: 220,
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(L('Difficulty mix', 'Mezcla de dificultad'), style: AdminTokens.tableHead),
              const SizedBox(height: 4),
              Select<String>(
                value: d.difficultyMix,
                fill: true,
                items: [('proportional', mixLabel('proportional')), ('balanced', mixLabel('balanced'))],
                onChanged: (v) => setState(() => _draft = d.copyWith(difficultyMix: v ?? 'proportional')),
              ),
            ]),
          ),
          _field(t, 'passpercent', L('Pass %', '% para aprobar')),
          _field(t, 'subtopicminpercent', L('Subtopic min % (0 = none)', '% mínimo por subtema (0 = ninguno)')),
          _field(t, 'timerminutes', L('Timer, minutes (0 = none)', 'Tiempo en minutos (0 = sin tiempo)')),
          _field(t, 'retakewaithours', L('Wait between attempts, hours', 'Espera entre intentos, horas')),
          _field(t, 'maxattempts', L('Max attempts (0 = unlimited)', 'Máximo de intentos (0 = sin límite)')),
          SizedBox(
            width: 220,
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(L('Learn must be complete', 'Aprender debe estar completo'), style: AdminTokens.tableHead),
              const SizedBox(height: 4),
              Select<bool>(
                value: d.requireLearnComplete,
                fill: true,
                items: [(true, L('Yes', 'Sí')), (false, L('No', 'No'))],
                onChanged: (v) => setState(() => _draft = d.copyWith(requireLearnComplete: v ?? true)),
              ),
            ]),
          ),
          SizedBox(
            width: 220,
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(L('Status', 'Estado'), style: AdminTokens.tableHead),
              const SizedBox(height: 4),
              Select<bool>(
                key: const ValueKey('blueprint-active'),
                value: d.active,
                fill: true,
                items: [(true, L('Active', 'Activa')), (false, L('Draft (not offered)', 'Borrador (no se ofrece)'))],
                onChanged: (v) => setState(() => _draft = d.copyWith(active: v ?? false)),
              ),
            ]),
          ),
        ]),
        if (sections.isNotEmpty) ...[
          const SizedBox(height: 12),
          Text(L('Subtopics covered', 'Subtemas cubiertos'), style: AdminTokens.tableHead),
          const SizedBox(height: 4),
          Wrap(spacing: 8, runSpacing: 8, children: [
            for (final s in sections)
              FilterChip(
                key: Key('blueprint-section-${s.id}'),
                label: Text('${s.title} · ${d.strategy == 'reserved' ? s.reserved : s.sequence}'),
                selected: !d.excludedSections.contains(s.id),
                onSelected: (on) => setState(() => _draft = d.copyWith(
                    excludedSections: on ? [for (final x in d.excludedSections) if (x != s.id) x] : [...d.excludedSections, s.id])),
              ),
          ]),
        ],
        const SizedBox(height: 12),
        Row(children: [
          TextButton(key: const ValueKey('blueprint-save'), onPressed: _saving ? null : _save, child: Text(_saving ? L('Saving…', 'Guardando…') : L('Save', 'Guardar'))),
          const SizedBox(width: 12),
          if (_saveNote != null) Text(_saveNote!, style: AdminTokens.muted),
          if (_saveError != null) Flexible(child: Text(_saveError!, key: const ValueKey('blueprint-error'), style: TextStyle(color: t.redText, fontSize: 12.5))),
        ]),
      ],
    );
  }

  Widget _poolPanel(TestuTokens t, BlueprintPool? p) {
    if (p == null) return const SizedBox.shrink();
    return Container(
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
      decoration: BoxDecoration(color: t.card2, border: Border.all(color: t.line), borderRadius: BorderRadius.circular(10)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(L('POOL', 'BANCO'), style: AdminTokens.eyebrow),
        const SizedBox(height: 6),
        Text(poolLine(p), key: const ValueKey('pool-line'), style: p.sufficient ? AdminTokens.body : AdminTokens.body.copyWith(color: t.redText)),
        const SizedBox(height: 6),
        Text(
          '${L('By difficulty', 'Por dificultad')}: ${p.byDifficulty['beginner'] ?? 0} ${L('beginner', 'principiante')} · ${p.byDifficulty['competent'] ?? 0} ${L('competent', 'competente')} · ${p.byDifficulty['expert'] ?? 0} ${L('expert', 'experto')}',
          style: AdminTokens.muted,
        ),
        for (final s in p.bySection)
          Text(
            '${s.position}. ${s.title} — ${s.sequence} ${L('in sequence', 'en secuencia')} · ${s.reserved} ${L('reserved', 'reservadas')}${s.covered ? '' : ' · ${L('excluded', 'excluido')}'}',
            style: AdminTokens.muted,
          ),
      ]),
    );
  }
}
```

If `TextButton` clashes with the console's button style, use the same button widget `admin_profiles.dart` uses for Save (read its Save row and copy the widget), keeping the `ValueKey('blueprint-save')`.

- [ ] **Step 4: Shell section and route**

In `admin_shell.dart`, after the exact block

```dart
    if (me.can('training_manage') || me.can('training_view'))
      AdminSection('progression', L('Progression', 'Progresión')),
```

insert

```dart
    // Evaluation blueprints per topic: same permissions as progression.
    if (me.can('training_manage') || me.can('training_view'))
      AdminSection('evaluations', L('Evaluations', 'Evaluaciones')),
```

After the exact line `    'progression' => AdminProgression(api: widget.api, me: widget.me),` insert

```dart
    'evaluations' => AdminEvaluations(api: widget.api, me: widget.me),
```

and after the exact line `import 'admin_progression.dart';` insert `import 'admin_evaluations.dart';`.

- [ ] **Step 5: Profile editor column**

In `admin_profiles.dart` `_header`, after the exact line

```dart
          SizedBox(width: 120, child: Text(L('After finish', 'Al terminar'), style: AdminTokens.tableHead)),
```

insert

```dart
          SizedBox(width: 130, child: Text(L('Evaluation', 'Evaluación'), style: AdminTokens.tableHead)),
```

In `_row`, after the exact block ending

```dart
            : ro(r.afterFinish == 'remove' ? L('Remove', 'Quitar') : L('Keep', 'Mantener'))),
```

insert

```dart
        cell(130, canEdit
            ? Select<bool>(
                key: Key('profile-eval-${r.topic}'),
                value: r.evaluationRequired,
                fill: true,
                items: [(false, L('Not required', 'No requerida')), (true, L('Required', 'Requerida'))],
                describe: (v) => v == true
                    ? L('Finished only after passing the topic evaluation', 'Termina solo al aprobar la evaluación del tema')
                    : L('Mastery and completion alone', 'Solo dominio y completitud'),
                onChanged: (v) => _set(i, r.copyWith(evaluationRequired: v ?? false)),
              )
            : ro(r.evaluationRequired ? L('Required', 'Requerida') : L('Not required', 'No requerida'))),
```

- [ ] **Step 6: Tests**

In `test/admin_profiles_test.dart`, in `_pump` replace `  tester.view.physicalSize = const Size(1200, 900);` with `  tester.view.physicalSize = const Size(1400, 900);` and `SizedBox(width: 1100,` with `SizedBox(width: 1300,` (the row grew by one column). Add a test after the existing "move down, remove, add and save" test:

```dart
  testWidgets('evaluation column saves evaluationRequired', (tester) async {
    List<ProfileRow>? savedRows;
    await _pump(tester, ProfileEditor(
      profile: _pilot(),
      topics: _topics,
      canManage: true,
      onSave: (name, rows) async => savedRows = rows,
      onCancel: () {},
    ));
    expect(find.text('Not required'), findsNWidgets(2));
    await tester.tap(find.byKey(const Key('profile-eval-T1')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Required').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('profile-save')));
    await tester.pumpAndSettle();
    expect(savedRows!.first.evaluationRequired, isTrue);
    expect(savedRows![1].evaluationRequired, isFalse);
    expect(savedRows!.first.toJson()['evaluationrequired'], true);
  });
```

(Use the Save button's actual key from the existing save test if it is not `profile-save`.)

Create `test/admin_evaluations_test.dart` modelled on `test/admin_progression_test.dart` (same imports, the same `_Http extends FakeEmeHttp` pattern with `requests`, `postBody`, `postStatus`):

```dart
Map<String, dynamic> _topic(String id, {int version = 1, bool active = true, String? reason, bool sufficient = true, String? shortfall}) => {
      'id': id,
      'title': 'Tema $id',
      'version': version,
      'active': active,
      'strategy': 'random',
      'maxquestions': 10,
      'minpersubtopic': 2,
      'excludedsections': [],
      'difficultymix': 'balanced',
      'passpercent': 70,
      'subtopicminpercent': 50,
      'timerminutes': 25,
      'retakewaithours': 24,
      'maxattempts': 3,
      'requirelearncomplete': true,
      'reason': reason,
      'pool': {
        'size': 30, 'sufficient': sufficient, 'shortfall': shortfall,
        'bysection': [
          {'id': '${id}s1', 'title': 'Uno', 'position': 1, 'covered': true, 'sequence': 15, 'reserved': 2, 'inpool': 15},
          {'id': '${id}s2', 'title': 'Dos', 'position': 2, 'covered': true, 'sequence': 15, 'reserved': 0, 'inpool': 15},
        ],
        'bydifficulty': {'beginner': 10, 'competent': 10, 'expert': 10},
      },
      'stats': {'attempts': 4, 'passed': 3, 'inprogress': 1},
    };
```

Tests:
1. `renders the table with status, pool warning and stats` — topics: `t1` unconfigured (`version: 0, active: false, reason: 'not_configured'`), `t2` active, `t3` active with `sufficient: false, shortfall: 'pool_below_max'`; expect `find.text('Not configured')`, `find.text('Active')` twice, `find.byKey(const Key('pool-warning-t3'))` one, `find.text('4 · 3 passed')` (English locale).
2. `editor posts every field with expectedversion` — tap row `t2`, pump; expect `ValueKey('blueprint-editor')`; enter `12` in `blueprint-maxquestions`, choose Draft in `blueprint-active`, tap `blueprint-save`; assert the fake's last POST form contains `topicid=t2`, `expectedversion=1`, `maxquestions=12`, `active=false`, `strategy=random`, `excludedsections=[]`, `requirelearncomplete=true`.
3. `pool_insufficient keeps the draft and shows the server's line` — fake POST throws `EmeHttpException` 409 body `{'ok': false, 'error': 'pool_insufficient', 'pool': {...}}`; after Save, `ValueKey('blueprint-error')` shows the pool text and `blueprint-maxquestions` still holds the typed value.
4. `read-only without canmanage` — `canManage: false` → `ValueKey('blueprint-readonly')` present, no editor.

Run: `cd app-genailabs && flutter test test/admin_evaluations_test.dart test/admin_profiles_test.dart test/admin_shell_test.dart test/admin_nav_test.dart`. Expected: all pass (the shell/nav tests confirm the new section does not break routing).

- [ ] **Step 7: Commit**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs && git add lib/admin/admin_models.dart lib/admin/admin_api.dart lib/admin/admin_evaluations.dart lib/admin/admin_shell.dart lib/admin/admin_profiles.dart test/admin_evaluations_test.dart test/admin_profiles_test.dart && git commit -m "feat(admin): Evaluations section with blueprint editor and pool report; evaluation column on job profiles"
```

---

### Task 6: Learner app — models, topic-home card, evaluation session, result screen

**Files:**
- Modify: `app-genailabs/lib/testu/testu_learn.dart`
- Modify: `app-genailabs/lib/testu/testu_question_source.dart`
- Modify: `app-genailabs/lib/testu/testu_live.dart`
- Modify: `app-genailabs/lib/testu/testu_session_engine.dart`
- Modify: `app-genailabs/lib/testu/testu_session.dart`
- Create: `app-genailabs/lib/testu/testu_evaluation_result.dart`
- Modify: `app-genailabs/lib/testu/testu_topics.dart`
- Test: `app-genailabs/test/testu_evaluation_test.dart` (new), `test/testu_session_evaluation_test.dart` (new), `test/testu_session_engine_test.dart` (+1)

**Interfaces:**
- Consumes: `state.json` `evaluation` block, `startevaluation.json`, `submitevaluation.json`, `answer.json` `deferred` (Task 3); `TopicState`, `NextResult`, `LearnError`, `_call`, `_get`, `answerFields`, `postAnswer`, `TestuQuestionSource`, `EmeQuestionSource`, `SessionController`, `TestuSessionScreen`, `showTestuSession`, `TestuCard`, `TestuAct`, `TestuButton`, `TestuPill`, `TestuEyebrow`, `TestuChip`, `testuProgressChanged`.
- Produces: `EvaluationState`, `EvaluationInProgress`, `EvaluationResult`, `EvaluationSubtopic`, `TopicState.evaluation`, `NextResult.expiresAt`, `NextResult.timerMinutes`, `postStartEvaluation()`, `postSubmitEvaluation()`; `TestuQuestionSource.expiresAt`, `submitEvaluation()`; `SessionController.deferVerdict`; pure `evaluationStatusLine(EvaluationState)`, `evaluationIntroLine(int total, int timerMinutes)`, `evaluationResultLine(EvaluationResult, String topic)`; `TestuEvaluationCard`, `TestuEvaluationResultScreen`; `showTestuSession(mode: 'evaluation')`.

Never run `dart format`. Tutor name = `client.tutor` (never hardcoded).

- [ ] **Step 1: Models and API (`testu_learn.dart`)**

In `TopicState.fromJson`, after the exact line `        finished = j['finished'] == null ? null : _bool(j['finished']),` insert

```dart
        evaluation = j['evaluation'] is Map<String, dynamic> ? EvaluationState.fromJson(j['evaluation'] as Map<String, dynamic>) : null,
```

and after the exact line `  final bool requiresPrevious, locked;` (in `TopicState`) insert

```dart

  /// Evaluation Mode (spec 2026-09-16-evaluation-mode): the server's status
  /// on this topic; null from a server that predates it.
  final EvaluationState? evaluation;
```

In `NextResult.fromJson`, after the exact line `        sessionId = _str(j['sessionid']),` insert

```dart
        expiresAt = _str(j['expiresat']),
        timerMinutes = _int(j['timerminutes']),
```

and after the exact line `  final String? sessionId;` (in `NextResult`) insert

```dart

  /// Evaluation attempts: when the server closes the attempt (ISO instant)
  /// and the blueprint's timer (0 = none). Null / 0 for other modes.
  final String? expiresAt;
  final int timerMinutes;
```

Append at the end of the file:

```dart
/// state.json / evaluation.json: the server's evaluation status on a topic.
/// status: not_available | in_progress | passed | locked | waiting |
/// exhausted | available. The app renders it; it never decides eligibility.
class EvaluationState {
  EvaluationState.fromJson(Map<String, dynamic> j)
      : status = '${j['status'] ?? 'not_available'}',
        reason = _str(j['reason']),
        canStart = _bool(j['canstart']),
        required = _bool(j['required']),
        passed = _bool(j['passed']),
        passedAt = _str(j['passedat']),
        scorePercent = j['scorepercent'] == null ? null : _int(j['scorepercent']),
        attempts = _int(j['attempts']),
        attemptsLeft = j['attemptsleft'] == null ? null : _int(j['attemptsleft']),
        nextAllowedAt = _str(j['nextallowedat']),
        inProgress = j['inprogress'] is Map<String, dynamic>
            ? EvaluationInProgress.fromJson(j['inprogress'] as Map<String, dynamic>)
            : null;

  final String status;
  final String? reason, passedAt, nextAllowedAt;
  final bool canStart, required, passed;
  final int? scorePercent, attemptsLeft;
  final int attempts;
  final EvaluationInProgress? inProgress;
}

class EvaluationInProgress {
  EvaluationInProgress.fromJson(Map<String, dynamic> j)
      : attemptId = '${j['attemptid'] ?? ''}',
        expiresAt = _str(j['expiresat']),
        total = _int(j['total']),
        answered = _int(j['answered']);
  final String attemptId;
  final String? expiresAt;
  final int total, answered;
}

class EvaluationSubtopic {
  EvaluationSubtopic.fromJson(Map<String, dynamic> j)
      : id = '${j['id'] ?? ''}',
        title = '${j['title'] ?? j['id'] ?? ''}',
        questions = _int(j['questions']),
        correct = _int(j['correct']),
        percent = _int(j['percent']),
        met = _bool(j['met']);
  final String id, title;
  final int questions, correct, percent;
  final bool met;
}

/// submitevaluation.json: the scored attempt.
class EvaluationResult {
  EvaluationResult.fromJson(Map<String, dynamic> j)
      : attemptId = '${j['attemptid'] ?? ''}',
        scorePercent = _int(j['scorepercent']),
        correct = _int(j['correct']),
        answered = _int(j['answered']),
        total = _int(j['total']),
        passed = _bool(j['passed']),
        failedRule = _str(j['failedrule']),
        passPercent = _int(j['passpercent']),
        subtopicMinPercent = _int(j['subtopicminpercent']),
        subtopics = [
          for (final s in (j['subtopics'] as List? ?? const []))
            if (s is Map<String, dynamic>) EvaluationSubtopic.fromJson(s),
        ],
        weakest = [for (final w in (j['weakest'] as List? ?? const [])) '$w'],
        evaluationStatus = _str(j['evaluationstatus']),
        nextAllowedAt = _str(j['nextallowedat']),
        attemptsLeft = j['attemptsleft'] == null ? null : _int(j['attemptsleft']),
        duplicate = _bool(j['duplicate']);

  final String attemptId;
  final int scorePercent, correct, answered, total, passPercent, subtopicMinPercent;
  final bool passed, duplicate;
  final String? failedRule, evaluationStatus, nextAllowedAt;
  final int? attemptsLeft;
  final List<EvaluationSubtopic> subtopics;
  final List<String> weakest;
}

/// startevaluation.json: resumes or creates the learner's attempt; next.json
/// shape with `expiresat`. 409 evaluation_not_available → [LearnError].
Future<NextResult> postStartEvaluation(EmeHttp http, {required String topicId}) async =>
    NextResult.fromJson(await _call(() => http.postForm('$_base/startevaluation.json', [MapEntry('topicid', topicId)])));

/// submitevaluation.json: finalizes the attempt (idempotent) and returns the result.
Future<EvaluationResult> postSubmitEvaluation(EmeHttp http, {required String sessionId}) async =>
    EvaluationResult.fromJson(await _call(() => http.postForm('$_base/submitevaluation.json', [MapEntry('sessionid', sessionId)])));
```

- [ ] **Step 2: Source seams**

In `testu_question_source.dart`, replace the exact line `import 'testu_learn.dart' show LearnBlocked;` with `import 'testu_learn.dart' show EvaluationResult, LearnBlocked;`, and in `abstract class TestuQuestionSource` after the exact block

```dart
  /// Subtopic titles of [scopeTopicId] (id → title), for blocked copy.
  Map<String, String> get sectionTitles => const {};
```

insert

```dart

  /// Evaluation: when the server closes the attempt (ISO instant); null in
  /// every other mode.
  String? get expiresAt => null;

  /// Evaluation: the blueprint's timer in minutes (0 = none).
  int get timerMinutes => 0;

  /// Evaluation: submits the attempt and returns the server's result.
  /// Throws in every other mode.
  Future<EvaluationResult> submitEvaluation() => throw StateError('not an evaluation');
```

- [ ] **Step 3: Live source in evaluation mode (`testu_live.dart`)**

In `EmeQuestionSource`:
- after the exact line `  String? _sessionId;` insert `  String? _expiresAt;` and `  int _timerMinutes = 0;`.
- after the exact block

```dart
  @override
  Map<String, String> get sectionTitles => _sectionTitles;
```

insert

```dart

  bool get _evaluation => mode == 'evaluation';

  @override
  String? get expiresAt => _expiresAt;

  @override
  int get timerMinutes => _timerMinutes;

  @override
  Future<EvaluationResult> submitEvaluation() {
    final sid = _sessionId;
    if (!_evaluation || sid == null) throw StateError('not an evaluation');
    return postSubmitEvaluation(_http, sessionId: sid);
  }
```

- replace the exact block

```dart
  ({String type, String id})? get _scope =>
      learnScope(mode, _topicId, sectionId);
```

with

```dart
  ({String type, String id})? get _scope => _evaluation
      ? (_topicId == null ? null : (type: 'topic', id: _topicId!))
      : learnScope(mode, _topicId, sectionId);
```

- replace the exact block

```dart
    final next = await fetchNext(_http,
        mode: mode, topicId: _topicId, sectionId: daily ? null : sectionId,
        size: size);
```

with

```dart
    final next = _evaluation
        ? await postStartEvaluation(_http, topicId: _topicId!)
        : await fetchNext(_http,
            mode: mode, topicId: _topicId, sectionId: daily ? null : sectionId,
            size: size);
```

- after the exact line `    _sessionId = next.sessionId;` insert

```dart
    _expiresAt = next.expiresAt;
    _timerMinutes = next.timerMinutes;
```

- replace the exact block

```dart
            why: mode == 'learn' ? null : reasonLine(p.$1.reason),
            intro: i == 0 && daily
                ? dailyChallengeLine(next.newCount, next.reinforcementCount)
                : null),
```

with

```dart
            why: mode == 'learn' || _evaluation ? null : reasonLine(p.$1.reason),
            intro: i == 0 && daily
                ? dailyChallengeLine(next.newCount, next.reinforcementCount)
                : i == 0 && _evaluation
                    ? evaluationIntroLine(next.total, next.timerMinutes)
                    : null),
```

- in `reportAttempt`, replace the exact line `      hintLevel: assisted ? 1 : 0, // the one hint the app has = level 1` with `      hintLevel: assisted && !_evaluation ? 1 : 0, // the one hint the app has = level 1; none in an evaluation`, and replace the exact block

```dart
      final r = await postAnswer(_http, fields, user: user);
      _tutorFeedback(q, questionId, chosen, confidence, r.answerId);
```

with

```dart
      final r = await postAnswer(_http, fields, user: user);
      // Evaluation is procedural-only: no tutor feedback during an attempt.
      if (!_evaluation) _tutorFeedback(q, questionId, chosen, confidence, r.answerId);
```

- append next to `dailyChallengeLine` (find `String dailyChallengeLine(` and add after that function):

```dart
/// The tutor's procedural opener of an evaluation attempt: how it works,
/// nothing about content (spec 2026-09-16-evaluation-mode).
String evaluationIntroLine(int total, int timerMinutes) {
  final n = total == 1 ? L('1 question', '1 pregunta') : L('$total questions', '$total preguntas');
  final time = timerMinutes > 0
      ? L(' and $timerMinutes minutes', ' y $timerMinutes minutos')
      : '';
  return L(
      'This is the evaluation of this topic: $n$time. No hints and no explanations — you see your result at the end. You can leave and come back until the time runs out.',
      'Esta es la evaluación de este tema: $n$time. Sin pistas ni explicaciones: verás tu resultado al terminar. Puedes salir y volver hasta que se acabe el tiempo.');
}
```

- [ ] **Step 4: Engine flag (`testu_session_engine.dart`)**

Replace the exact block

```dart
  SessionController({
    required this.questions,
    required this._scheduler,
    required this._confAcked,
  });
```

with

```dart
  SessionController({
    required this.questions,
    required this._scheduler,
    required this._confAcked,
    this.deferVerdict = false,
  });

  /// Evaluation: the verdict is withheld until the attempt is submitted.
  /// The transcript still gets a [Verdict] entry (the adapter reports the
  /// attempt off it) but the pause before the offer is short and the
  /// haptic never tells right from wrong.
  final bool deferVerdict;
```

Replace the exact block

```dart
    _phase = _Phase.verdict;
    _add(Verdict(a));
    _scheduler.after(_verdictToOffer, () {
```

with

```dart
    _phase = _Phase.verdict;
    _add(Verdict(a));
    _scheduler.after(deferVerdict ? _deferredToOffer : _verdictToOffer, () {
```

replace the exact line `    return a.correct ? HapticIntent.medium : HapticIntent.heavy;` with

```dart
    if (deferVerdict) return HapticIntent.medium; // same buzz either way: no leak
    return a.correct ? HapticIntent.medium : HapticIntent.heavy;
```

and after the exact line `  static const _verdictToOffer = Duration(milliseconds: 2100);` insert `  static const _deferredToOffer = Duration(milliseconds: 300);`.

Test, in `test/testu_session_engine_test.dart` (append inside `main`):

```dart
  test('deferVerdict: wrong answers buzz like right ones and the offer follows at once', () {
    final sched = FakeScheduler();
    final c = SessionController(questions: () => qs, scheduler: sched, confAcked: true, deferVerdict: true);
    c.start();
    sched.flush();
    expect(c.submit(chosen: 0, confidence: 3), HapticIntent.medium); // wrong (okIdx 1)
    expect(c.transcript.last, isA<Verdict>());
    sched.flush();
    expect(c.transcript.last, isA<ContinueOffer>());
  });
```

- [ ] **Step 5: Session screen evaluation variant (`testu_session.dart`)**

Add the import `import 'testu_evaluation_result.dart';` next to the other `testu_*` imports.

`_modeLabel`: after the exact line `      'dailychallenge' => L('DAILY CHALLENGE', 'RETO DIARIO'),` insert `      'evaluation' => L('EVALUATION', 'EVALUACIÓN'),`.

Doc comment of `showTestuSession` / `TestuSessionScreen.mode`: extend "learn | improve | dailychallenge" to "learn | improve | dailychallenge | evaluation" (the two exact comment lines that say `learn | improve | dailychallenge`).

State fields: after the exact line `  bool _debriefing = false;` insert

```dart

  /// Evaluation: the server's deadline and the once-a-second repaint of the
  /// countdown; reaching it submits what was answered.
  bool get _evaluation => _source.mode == 'evaluation';
  DateTime? _deadline;
  Timer? _tick;
```

Controller: replace the exact line `      confAcked: _confAcked || widget.guided,` with

```dart
      confAcked: _confAcked || widget.guided,
      deferVerdict: widget.mode == 'evaluation' || widget.source?.mode == 'evaluation',
```

In `_boot`, after the exact line `    if (qs.isNotEmpty) _controller.start();` insert

```dart
    if (qs.isNotEmpty && _evaluation) _armDeadline();
```

and add these methods right after `_boot` (before `  void _retry() {`):

```dart
  void _armDeadline() {
    _deadline = DateTime.tryParse(_source.expiresAt ?? '');
    if (_deadline == null) return;
    _tick?.cancel();
    _tick = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      setState(() {});
      if (!_deadline!.isAfter(DateTime.now())) _timeUp();
    });
  }

  /// mm:ss left, clamped at 00:00.
  String get _countdown {
    final left = _deadline!.difference(DateTime.now());
    final s = left.isNegative ? 0 : left.inSeconds;
    return '${(s ~/ 60).toString().padLeft(2, '0')}:${(s % 60).toString().padLeft(2, '0')}';
  }

  void _timeUp() {
    _tick?.cancel();
    _toResult();
  }

  /// Evaluation: hands over to the result screen (which submits). Once.
  void _toResult() {
    if (_debriefing) return;
    _debriefing = true;
    _tick?.cancel();
    _whenSaved(() => Navigator.of(context).pushReplacement(MaterialPageRoute(
        builder: (_) => TestuEvaluationResultScreen(
            source: _source,
            topic: _source.topic,
            topicId: _source.scopeTopicId,
            sectionTitles: _source.sectionTitles))));
  }
```

In `dispose`, after the exact line `    _sullyTimeout?.cancel();` insert `    _tick?.cancel();`.

In `_onEngine`, replace the exact block

```dart
    if (outcome != null) {
      // The debrief reads mastery from the server: only once it has them.
      if (_debriefing) return;
```

with

```dart
    if (outcome != null) {
      if (_evaluation) {
        _toResult();
        return;
      }
      // The debrief reads mastery from the server: only once it has them.
      if (_debriefing) return;
```

In `_entryWidget`, replace the exact block

```dart
      Verdict v => Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _verdictBubble(qs[v.attempt.qi], v.attempt),
            if (_coach(v.attempt.qi)) _coachAfterVerdict(),
          ]),
```

with

```dart
      Verdict v => _evaluation
          ? _recordedBubble()
          : Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _verdictBubble(qs[v.attempt.qi], v.attempt),
                if (_coach(v.attempt.qi)) _coachAfterVerdict(),
              ]),
```

and add next to `_verdictBubble`:

```dart
  /// Evaluation: the answer is stored, nothing about it is said.
  Widget _recordedBubble() => _SullyBubble(
        key: const ValueKey('evaluation-recorded'),
        delay: 200,
        onGrew: _scrollDown,
        spans: [TextSpan(text: L('Answer recorded.', 'Respuesta registrada.'))],
      );
```

Question card: in `_questionCard`, after the exact line `        guide: guide,` insert `        reveal: !_evaluation,`. In `_QuestionCard` add a constructor parameter `    this.reveal = true,` (after `required this.onHint,` in the constructor list — read the class) and the field `  /// False in an evaluation: options never colour and no hint is offered.\n  final bool reveal;`. Replace the exact lines

```dart
                      correct: _locked && i == q.okIdx,
                      wrong: _locked && i == _sel && i != q.okIdx,
```

with

```dart
                      correct: widget.reveal && _locked && i == q.okIdx,
                      wrong: widget.reveal && _locked && i == _sel && i != q.okIdx,
```

and the exact line `                if (!_locked && (q.hint != null || canAskSully(q)))` with `                if (!_locked && widget.reveal && (q.hint != null || canAskSully(q)))`.

Dock: in `_dock`, replace the exact block

```dart
        Expanded(
          // House composer — same module as thread replies and the tutor
          // ask bar.
          child: TestuComposer(
            controller: _input,
            hint: L('Ask ${client.tutor} anything…',
                'Pregunta a ${client.tutor} lo que quieras…'),
            onSend: (_) => _sendTyped(),
          ),
        ),
```

with

```dart
        if (_evaluation)
          // Procedural-only: no tutor chat during an attempt.
          const Spacer()
        else
          Expanded(
            // House composer — same module as thread replies and the tutor
            // ask bar.
            child: TestuComposer(
              controller: _input,
              hint: L('Ask ${client.tutor} anything…',
                  'Pregunta a ${client.tutor} lo que quieras…'),
              onSend: (_) => _sendTyped(),
            ),
          ),
```

and the exact block

```dart
                _saving
                    ? L('Saving…', 'Guardando…')
                    : L('Continue →', 'Continuar →'),
```

with

```dart
                _saving
                    ? L('Saving…', 'Guardando…')
                    : _evaluation && last is ContinueOffer && last.last
                        ? L('Submit →', 'Entregar →')
                        : L('Continue →', 'Continuar →'),
```

Header countdown: replace the exact block

```dart
                      TestuIconButton(
                        TestuGlyph.close,
                        onTap: () {
```

with

```dart
                      if (_evaluation && _deadline != null && !_loading && !_error)
                        Padding(
                          padding: const EdgeInsets.only(right: 10),
                          child: Text(_countdown,
                              key: const ValueKey('evaluation-countdown'),
                              style: kLabel.copyWith(fontFeatures: const [FontFeature.tabularFigures()])),
                        ),
                      TestuIconButton(
                        TestuGlyph.close,
                        onTap: () {
```

Stop challenge: replace the exact line `  Widget _stopChallengeBubble(int remaining) {` with

```dart
  Widget _stopChallengeBubble(int remaining) {
    if (_evaluation) return _evaluationStopBubble(remaining);
```

and add after that method:

```dart
  /// Evaluation: leaving keeps the attempt open until the deadline; the
  /// learner can also submit what is answered so far.
  Widget _evaluationStopBubble(int remaining) {
    final until = _deadline == null
        ? ''
        : L(' until ${_deadline!.toLocal().hour.toString().padLeft(2, '0')}:${_deadline!.toLocal().minute.toString().padLeft(2, '0')}',
            ' hasta las ${_deadline!.toLocal().hour.toString().padLeft(2, '0')}:${_deadline!.toLocal().minute.toString().padLeft(2, '0')}');
    return _SullyBubble(
      delay: 600,
      onGrew: _scrollDown,
      spans: [
        TextSpan(
            text: L(
                'You have $remaining left. Your attempt stays open$until: you can leave and come back, or submit now with what you have answered. Unanswered questions count as wrong.',
                'Te quedan $remaining. Tu intento sigue abierto$until: puedes salir y volver, o entregar ahora con lo que respondiste. Las preguntas sin responder cuentan como incorrectas.')),
      ],
      extra: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SizedBox(height: 12),
          _ChoiceChips(chips: [
            (L('Keep going', 'Seguir'), true, () => _whenSaved(_controller.continueSession)),
            (L('Leave, come back later', 'Salir y volver luego'), false, () => Navigator.of(context).pop()),
            (L('Submit now', 'Entregar ahora'), false, _toResult),
          ]),
        ],
      ),
    );
  }
```

(Match `_ChoiceChips`'s record shape to the existing "Keep going" / "Stop anyway" chips two lines below in the file.)

`_boot` error copy: after the `topic_locked` branch add

```dart
      } else if (e is LearnError && e.error == 'evaluation_not_available') {
        why = L('This evaluation is not available right now. Check the topic page for when you can take it.',
            'Esta evaluación no está disponible ahora. Revisa la página del tema para saber cuándo puedes hacerla.');
```

`_staleSession` copy: the same widget serves `session_expired` / `attempt_closed` in this mode; when `_evaluation`, use the text "Time is up for this attempt: that answer wasn't saved. Your result is on the topic page." / "Se acabó el tiempo de este intento: esa respuesta no se guardó. Tu resultado está en la página del tema." and the single chip "Back" (`Navigator.pop`), instead of Start again + Leave.

- [ ] **Step 6: Result screen**

Create `app-genailabs/lib/testu/testu_evaluation_result.dart`:

```dart
import 'package:flutter/material.dart';

import 'testu_client.dart';
import 'testu_i18n.dart';
import 'testu_learn.dart';
import 'testu_live.dart';
import 'testu_question_source.dart';
import 'testu_session.dart';
import 'testu_theme.dart';
import 'testu_topics.dart' show testuProgressChanged;
import 'testu_widgets.dart';

/// The tutor's one line on a scored attempt: the verdict and, when failed,
/// the weakest subtopics to work on. Pure: tests read it directly.
String evaluationResultLine(EvaluationResult r, String topic) {
  if (r.passed) {
    return L('You passed «$topic» with ${r.scorePercent} %. Well done — keep it fresh with Improve.',
        'Aprobaste «$topic» con ${r.scorePercent} %. Bien hecho: mantenlo fresco con Mejorar.');
  }
  final weak = [
    for (final id in r.weakest)
      ...r.subtopics.where((s) => s.id == id).map((s) => '«${s.title}»'),
  ];
  final rule = r.failedRule == 'subtopic'
      ? L('a subtopic stayed under ${r.subtopicMinPercent} %', 'un subtema quedó bajo ${r.subtopicMinPercent} %')
      : L('${r.passPercent} % needed', 'se necesita ${r.passPercent} %');
  final where = weak.isEmpty
      ? ''
      : L(' Your weakest: ${weak.join(', ')}. Let’s work on that first.',
          ' Lo más flojo: ${weak.join(', ')}. Empecemos por ahí.');
  return L('Not this time: ${r.scorePercent} % ($rule).$where',
      'Esta vez no: ${r.scorePercent} % ($rule).$where');
}

/// Replaces the debrief after an evaluation attempt: submits, then shows
/// score, pass/fail and per-subtopic results. No per-question review
/// (decision 2026-09-16).
class TestuEvaluationResultScreen extends StatefulWidget {
  const TestuEvaluationResultScreen({
    super.key,
    required this.source,
    required this.topic,
    this.topicId,
    this.sectionTitles = const {},
  });

  final TestuQuestionSource source;
  final String topic;
  final String? topicId;
  final Map<String, String> sectionTitles;

  @override
  State<TestuEvaluationResultScreen> createState() => _TestuEvaluationResultScreenState();
}

class _TestuEvaluationResultScreenState extends State<TestuEvaluationResultScreen> {
  late Future<(EvaluationResult, TopicState?)> _future = _load();

  Future<(EvaluationResult, TopicState?)> _load() async {
    final r = await widget.source.submitEvaluation();
    testuProgressChanged.value++;
    TopicState? st;
    if (testuLive && widget.topicId != null) {
      try {
        st = (await fetchLearnState(DioEmeHttp(), topicId: widget.topicId)).firstOrNull;
      } catch (_) {}
    }
    return (r, st);
  }

  @override
  Widget build(BuildContext context) {
    final t = TestuTokens.of(context);
    return Scaffold(
      backgroundColor: t.bg,
      body: SafeArea(
        child: FutureBuilder<(EvaluationResult, TopicState?)>(
          future: _future,
          builder: (context, snap) {
            if (snap.hasError) return _error(context);
            if (!snap.hasData) {
              return Center(child: Text(L('Scoring your evaluation…', 'Calificando tu evaluación…'), style: TextStyle(color: t.mut)));
            }
            final (r, st) = snap.data!;
            return _body(context, r, st);
          },
        ),
      ),
    );
  }

  Widget _error(BuildContext context) => Padding(
        padding: const EdgeInsets.all(18),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(L('Your answers are saved, but the result could not be fetched.', 'Tus respuestas están guardadas, pero no se pudo obtener el resultado.'),
              key: const ValueKey('evaluation-result-error')),
          const SizedBox(height: 12),
          Wrap(spacing: 8, children: [
            TestuChip(L('Try again', 'Reintentar'), primary: true, onTap: () => setState(() => _future = _load())),
            TestuChip(L('Back', 'Volver'), onTap: () => Navigator.of(context).pop()),
          ]),
        ]),
      );

  Widget _body(BuildContext context, EvaluationResult r, TopicState? st) {
    final t = TestuTokens.of(context);
    final weakId = r.weakest.firstOrNull;
    final weakTitle = r.subtopics.where((s) => s.id == weakId).map((s) => s.title).firstOrNull ?? widget.sectionTitles[weakId];
    final weakSection = st?.sections.where((s) => s.id == weakId).firstOrNull;
    final improve = weakSection?.improveAvailable ?? false;
    return ListView(
      padding: const EdgeInsets.fromLTRB(18, 8, 18, 30),
      children: [
        Row(children: [
          Expanded(child: TestuEyebrow('${L('EVALUATION', 'EVALUACIÓN')} · ${widget.topic.toUpperCase()}', fontSize: 10.5, letterSpacing: 1.47)),
          TestuIconButton(TestuGlyph.close, onTap: () => Navigator.of(context).pop()),
        ]),
        const SizedBox(height: 18),
        Text(r.passed ? L('You passed', 'Aprobaste') : L('Not this time', 'Esta vez no'),
            key: const ValueKey('evaluation-headline'),
            style: TextStyle(fontFamily: 'Sora', fontWeight: FontWeight.w700, fontSize: 24, color: r.passed ? t.greenText : t.ink)),
        const SizedBox(height: 6),
        Text(
          '${r.scorePercent} % · ${r.correct} ${L('of', 'de')} ${r.total} · ${L('${r.passPercent} % to pass', '${r.passPercent} % para aprobar')}',
          key: const ValueKey('evaluation-score'),
          style: TextStyle(fontFamily: 'Geist', fontSize: 13, color: t.mut),
        ),
        const SizedBox(height: 18),
        TestuCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            TestuEyebrow.kicker(L('BY SUBTOPIC', 'POR SUBTEMA')),
            const SizedBox(height: 8),
            for (final s in r.subtopics)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 4),
                child: Row(children: [
                  Expanded(child: Text(s.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontFamily: 'Geist', fontSize: 13, color: t.ink))),
                  Text('${s.percent} %', style: TextStyle(fontFamily: 'GeistMono', fontSize: 12.5, color: s.met ? t.greenText : t.redText)),
                  const SizedBox(width: 8),
                  Text(s.met ? '✓' : '!', style: TextStyle(fontSize: 12.5, color: s.met ? t.greenText : t.redText)),
                ]),
              ),
          ]),
        ),
        const SizedBox(height: 14),
        Text(evaluationResultLine(r, widget.topic), key: const ValueKey('evaluation-line'), style: TextStyle(fontFamily: 'Geist', fontSize: 13.5, height: 1.55, color: t.inkSoft)),
        if (!r.passed && r.nextAllowedAt != null) ...[
          const SizedBox(height: 8),
          Text(L('You can try again from ${testuWhen(r.nextAllowedAt!)}.', 'Puedes intentarlo de nuevo desde ${testuWhen(r.nextAllowedAt!)}.'), style: TextStyle(fontSize: 12.5, color: t.mut)),
        ],
        if (!r.passed && r.attemptsLeft != null) ...[
          const SizedBox(height: 4),
          Text(r.attemptsLeft == 0 ? L('No attempts left.', 'No quedan intentos.') : L('${r.attemptsLeft} attempts left.', 'Te quedan ${r.attemptsLeft} intentos.'), style: TextStyle(fontSize: 12.5, color: t.mut)),
        ],
        const SizedBox(height: 22),
        if (!r.passed && weakId != null && widget.topicId != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: TestuButton(
              improve ? L('IMPROVE «${weakTitle ?? ''}»', 'MEJORAR «${weakTitle ?? ''}»') : L('LEARN «${weakTitle ?? ''}»', 'APRENDER «${weakTitle ?? ''}»'),
              variant: TestuButtonVariant.primary,
              onTap: () {
                Navigator.of(context).pop();
                showTestuSession(context, topicId: widget.topicId, sectionId: weakId, mode: improve ? 'improve' : 'learn');
              },
            ),
          ),
        TestuButton(L('BACK TO TOPIC', 'VOLVER AL TEMA'), variant: r.passed ? TestuButtonVariant.primary : TestuButtonVariant.quiet, onTap: () => Navigator.of(context).pop()),
      ],
    );
  }
}

/// A short local date-time for the learner ("17/9 09:00"). Shared with the topic card.
String testuWhen(String iso) {
  final d = DateTime.tryParse(iso)?.toLocal();
  if (d == null) return iso;
  return '${d.day}/${d.month} ${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
}
```

Check the names `TestuIconButton`, `TestuGlyph.close`, `TestuEyebrow.kicker`, `TestuButtonVariant.quiet`, `DioEmeHttp`, `testuLive` against their definitions (all used elsewhere in `testu_session.dart` / `testu_topics.dart`; adjust imports to where they live). `testuWhen` is public because the topic card (Step 7) uses it too.

- [ ] **Step 7: Topic-home card (`testu_topics.dart`)**

Add `import 'testu_evaluation_result.dart' show testuWhen;`. Replace the exact block

```dart
  void _open({String? sectionId, bool improve = false}) {
    showTestuSession(context,
        topicId: widget.topicId ?? _live?.state.id,
        sectionId: sectionId,
        mode: improve ? 'improve' : 'learn');
  }
```

with

```dart
  void _open({String? sectionId, bool improve = false, bool evaluation = false}) {
    showTestuSession(context,
        topicId: widget.topicId ?? _live?.state.id,
        sectionId: sectionId,
        mode: evaluation ? 'evaluation' : improve ? 'improve' : 'learn');
  }
```

Before the exact comment line `        // Live: Learn while it has questions left; Improve once the engine` insert

```dart
        if (st?.evaluation case final ev? when ev.status != 'not_available')
          Padding(
            padding: const EdgeInsets.fromLTRB(18, 16, 18, 0),
            child: TestuEvaluationCard(ev: ev, onOpen: () => _open(evaluation: true)),
          ),
```

Append at the end of the file:

```dart
/// One status line for the topic's evaluation, from the server's status.
/// Pure: tests read it directly.
String evaluationStatusLine(EvaluationState ev) {
  final last = ev.scorePercent == null ? '' : L(' · last ${ev.scorePercent} %', ' · última ${ev.scorePercent} %');
  return switch (ev.status) {
    'in_progress' => L('In progress · ${ev.inProgress?.answered ?? 0} of ${ev.inProgress?.total ?? 0} answered',
        'En curso · ${ev.inProgress?.answered ?? 0} de ${ev.inProgress?.total ?? 0} respondidas'),
    'passed' => L('Passed · ${ev.scorePercent ?? 0} %${ev.passedAt == null ? '' : ' on ${testuWhen(ev.passedAt!)}'}',
        'Aprobada · ${ev.scorePercent ?? 0} %${ev.passedAt == null ? '' : ' el ${testuWhen(ev.passedAt!)}'}'),
    'locked' => ev.reason == 'learn_incomplete'
        ? L('Complete Learn Mode first', 'Completa primero el Modo Aprender')
        : L('Unlocks with the topic', 'Se desbloquea con el tema'),
    'waiting' => L('Not passed$last · try again from ${ev.nextAllowedAt == null ? '' : testuWhen(ev.nextAllowedAt!)}',
        'No aprobada$last · inténtalo de nuevo desde ${ev.nextAllowedAt == null ? '' : testuWhen(ev.nextAllowedAt!)}'),
    'exhausted' => L('No attempts left$last', 'No quedan intentos$last'),
    _ => ev.attempts == 0
        ? L('Ready when you are', 'Lista cuando quieras')
        : L('Not passed$last · you can try again', 'No aprobada$last · puedes intentarlo de nuevo'),
  };
}

/// Topic home: the evaluation's state and its CTA. Renders the server's
/// decision only (canStart / in_progress decide the button).
class TestuEvaluationCard extends StatelessWidget {
  const TestuEvaluationCard({super.key, required this.ev, required this.onOpen});
  final EvaluationState ev;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final t = TestuTokens.of(context);
    final resume = ev.status == 'in_progress';
    final open = resume || ev.canStart;
    return TestuCard(
      key: const ValueKey('evaluation-card'),
      accent: ev.passed ? t.green : ev.required && !ev.passed ? t.amber : null,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          TestuEyebrow.kicker(L('TOPIC EVALUATION', 'EVALUACIÓN DEL TEMA')),
          if (ev.required) ...[
            const SizedBox(width: 8),
            TestuPill(L('Required for your role', 'Requerida para tu rol')),
          ],
        ]),
        const SizedBox(height: 6),
        Text(evaluationStatusLine(ev), key: const ValueKey('evaluation-status'), style: TextStyle(fontFamily: 'Geist', fontSize: 13, color: t.inkSoft)),
        if (open) ...[
          const SizedBox(height: 10),
          TestuAct(resume ? L('Resume the evaluation', 'Continuar la evaluación') : L('Take the evaluation', 'Hacer la evaluación'),
              primary: true, onTap: onOpen),
        ],
      ]),
    );
  }
}
```

(`TestuCard` takes `accent` in `testu_shell.dart`'s certification card; `TestuPill` default constructor and `TestuAct(primary:)` are used the same way there. If `TestuCard.accent` requires a non-null Color, pass `t.line` for the neutral case.)

- [ ] **Step 8: Tests**

`test/testu_evaluation_test.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:genai_labs/testu/testu_evaluation_result.dart';
import 'package:genai_labs/testu/testu_learn.dart';
import 'package:genai_labs/testu/testu_theme.dart';
import 'package:genai_labs/testu/testu_topics.dart';

EvaluationState _ev(Map<String, dynamic> j) => EvaluationState.fromJson(j);

void main() {
  test('TopicState parses the evaluation block', () {
    final s = TopicState.fromJson({'id': 'T1', 'questions': 10, 'answered': 0, 'sections': [],
      'evaluation': {'status': 'available', 'canstart': true, 'required': true, 'attempts': 1, 'scorepercent': 62, 'attemptsleft': 2}});
    expect(s.evaluation!.status, 'available');
    expect(s.evaluation!.canStart, isTrue);
    expect(s.evaluation!.required, isTrue);
    expect(s.evaluation!.scorePercent, 62);
    expect(s.evaluation!.attemptsLeft, 2);
    expect(TopicState.fromJson({'id': 'T2', 'questions': 1, 'answered': 0, 'sections': []}).evaluation, isNull);
  });

  test('status line per server status', () {
    expect(evaluationStatusLine(_ev({'status': 'available', 'attempts': 0})), 'Ready when you are');
    expect(evaluationStatusLine(_ev({'status': 'available', 'attempts': 1, 'scorepercent': 62})), contains('62 %'));
    expect(evaluationStatusLine(_ev({'status': 'in_progress', 'inprogress': {'attemptid': 'a', 'total': 20, 'answered': 12}})), 'In progress · 12 of 20 answered');
    expect(evaluationStatusLine(_ev({'status': 'passed', 'passed': true, 'scorepercent': 84})), startsWith('Passed · 84 %'));
    expect(evaluationStatusLine(_ev({'status': 'locked', 'reason': 'learn_incomplete'})), 'Complete Learn Mode first');
    expect(evaluationStatusLine(_ev({'status': 'waiting', 'scorepercent': 40, 'nextallowedat': '2026-09-18T09:00:00.000Z'})), contains('try again from'));
    expect(evaluationStatusLine(_ev({'status': 'exhausted', 'scorepercent': 40})), 'No attempts left · last 40 %');
  });

  test('result line names the weakest subtopics when failed', () {
    final r = EvaluationResult.fromJson({'scorepercent': 62, 'passed': false, 'failedrule': 'overall', 'passpercent': 70,
      'subtopics': [{'id': 's1', 'title': 'Arrival', 'percent': 40, 'met': true}, {'id': 's2', 'title': 'FOD', 'percent': 80, 'met': true}], 'weakest': ['s1']});
    expect(evaluationResultLine(r, 'Ramp'), 'Not this time: 62 % (70 % needed). Your weakest: «Arrival». Let’s work on that first.');
    final p = EvaluationResult.fromJson({'scorepercent': 90, 'passed': true, 'passpercent': 70, 'subtopics': [], 'weakest': []});
    expect(evaluationResultLine(p, 'Ramp'), startsWith('You passed «Ramp» with 90 %'));
  });

  testWidgets('card shows the CTA only when the server allows a start or a resume', (tester) async {
    Widget app(EvaluationState ev) => MaterialApp(theme: testuTheme(), home: Scaffold(body: TestuEvaluationCard(ev: ev, onOpen: () {})));
    await tester.pumpWidget(app(_ev({'status': 'available', 'canstart': true, 'required': true})));
    expect(find.text('Take the evaluation'), findsOneWidget);
    expect(find.text('Required for your role'), findsOneWidget);
    await tester.pumpWidget(app(_ev({'status': 'in_progress', 'inprogress': {'attemptid': 'a', 'total': 5, 'answered': 2}})));
    expect(find.text('Resume the evaluation'), findsOneWidget);
    await tester.pumpWidget(app(_ev({'status': 'locked', 'reason': 'learn_incomplete'})));
    expect(find.text('Take the evaluation'), findsNothing);
    expect(find.text('Complete Learn Mode first'), findsOneWidget);
  });
}
```

`test/testu_session_evaluation_test.dart` (harness copied from `test/testu_session_save_test.dart`: `SharedPreferences.setMockInitialValues({'testu.confAcked': true})`, a 400×900 surface, `TestuSessionScreen(source: source)`, the `tap` helper, the six 1-second pumps after pumping the widget):

```dart
/// One bundled question served as an evaluation attempt.
class _EvalSource extends LocalQuestionSource {
  bool submitted = false;

  @override
  String get mode => 'evaluation';

  @override
  String? get expiresAt => DateTime.now().toUtc().add(const Duration(minutes: 25)).toIso8601String();

  @override
  int get timerMinutes => 25;

  @override
  Future<List<TestuQ>> load() async => [(await super.load()).first];

  @override
  Future<EvaluationResult> submitEvaluation() async {
    submitted = true;
    return EvaluationResult.fromJson({'scorepercent': 100, 'correct': 1, 'total': 1, 'passed': true, 'passpercent': 70,
      'subtopics': [{'id': 's1', 'title': 'Arrival', 'questions': 1, 'correct': 1, 'percent': 100, 'met': true}], 'weakest': []});
  }
}
```

Tests:
1. `evaluation session hides verdict and hint, shows countdown, submits at the end`: pump; expect `find.text('EVALUATION')` (eyebrow contains it: use `find.textContaining('EVALUATION')`), `find.byKey(const ValueKey('evaluation-countdown'))` one; no hint affordance (`find.textContaining('hint')` findsNothing before answering); answer the wrong option (`tap(find.textContaining(<a wrong option's text from the bundled first question>))`, then `tap(find.text('Certain'))`), pump 1 s; expect `find.byKey(const ValueKey('evaluation-recorded'))` one and `find.textContaining('Correct')` / `find.textContaining('Not quite')` findsNothing; expect the dock pill reads `Submit →`; tap it, pump 2 s; expect `source.submitted` true and `find.byKey(const ValueKey('evaluation-headline'))` with text `You passed`.
2. `stop challenge offers leave and submit now`: pump, answer, tap the header close (`TestuGlyph.close` icon button: find by type `TestuIconButton` first), pump 1 s; expect `find.text('Leave, come back later')` and `find.text('Submit now')`; tap `Submit now`, pump 2 s; expect the result headline.

Run:

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs && flutter test test/testu_evaluation_test.dart test/testu_session_evaluation_test.dart test/testu_session_engine_test.dart test/testu_topics_test.dart test/testu_session_save_test.dart test/testu_session_counter_test.dart test/testu_learn_test.dart
```

Expected: all pass. Then `flutter analyze lib/testu lib/admin` → no errors (warnings that pre-exist are not yours).

- [ ] **Step 9: Commit**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs && git add lib/testu/testu_learn.dart lib/testu/testu_question_source.dart lib/testu/testu_live.dart lib/testu/testu_session_engine.dart lib/testu/testu_session.dart lib/testu/testu_evaluation_result.dart lib/testu/testu_topics.dart test/testu_evaluation_test.dart test/testu_session_evaluation_test.dart test/testu_session_engine_test.dart && git commit -m "feat(learn): Evaluation Mode — topic card, timed no-teaching session, result screen"
```

---

### Task 7: Build web bundles, end-to-end check, ship

**Files:**
- Modify: `plugins/testu/html/admin/*`, `plugins/testu/html/learn/*` (built bundles) and their `eme-server-minsur/webapp/site/mediadb/{admin,learn}` copies.
- Mirror: `eme-server-minsur/webapp/site/mediadb/services/testu/learn/{evaluation,startevaluation,submitevaluation,evaluationblueprint}.{xconf,json}` (copies of the plugin files).

- [ ] **Step 1: Mirror the endpoint files to the webapp**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && for f in evaluation startevaluation submitevaluation evaluationblueprint; do cp plugins/testu/html/services/testu/learn/$f.xconf plugins/testu/html/services/testu/learn/$f.json webapp/site/mediadb/services/testu/learn/; done && git status --short webapp/site/mediadb/services/testu/learn
```

- [ ] **Step 2: Build bundles from a clean worktree**

Per memory `local-eme-server-ops`: create a detached worktree of `app-genailabs` at HEAD as a sibling directory (`/Users/DSANJORGE/Code/EMEGenAILabs/.app-genailabs-wt`, so `../eme-app-package` resolves), run the same build script the job-profiles Task 8 used (`build_bundles.sh` in the previous session's scratchpad; if gone, the two commands are `flutter build web --release --target lib/admin_main.dart --base-href /admin/ -o build/admin` and `flutter build web --release --target lib/main.dart --base-href /learn/ -o build/learn` — confirm the targets and base-hrefs against `plugins/testu/html/admin/index.html` and `html/learn/index.html` before running), then copy the outputs to `plugins/testu/html/{admin,learn}` and `webapp/site/mediadb/{admin,learn}`. Remove the worktree afterwards (`git worktree remove`).

- [ ] **Step 3: Restart and end-to-end in the built-in browser**

Notify peers, restart Tomcat, wait for startup. Then, signed in as the local admin in the built-in browser (never sign the user out of their own sessions):
1. Admin console → Evaluaciones: pick a topic with questions, set Max 5, Pass 60, Timer 2, Active → Save → `Saved as v1`. Set Max 500 → Save → the pool line in red and the draft kept.
2. Personas → a profile → Evaluation column → Required → Save.
3. Learner app (`/learn/`) as a test learner assigned that profile (via `setprofiles.json` as in the job-profiles e2e, reverted afterwards): topic home shows the Evaluation card with "Required for your role" and "Take the evaluation"; start it: eyebrow EVALUATION, countdown, answer two questions (no colouring, "Answer recorded."), ✕ → "Leave, come back later"; reopen → "Resume the evaluation" → finish → result screen with score and subtopics; topic card now "Passed · N %".
4. Screenshot the result screen and the Evaluaciones table for the report.
5. Revert: unassign the test learner's profile, set the profile row back to Not required, save the blueprint as Draft (or delete the `evaluationblueprint` rows and the learner's `evaluationattempt` / `tutoranswer` rows through `services/lists/data/...` as the check scripts do).

- [ ] **Step 4: Full check run**

From the server root, with the env sourced: `plugins/testu/tools/check_evaluation.sh`, `check_profiles.sh`, `check_learning.sh` → all PASS. `bin/sync-testu.sh --check` → clean.

- [ ] **Step 5: Commit bundles and mirrors**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/testu && git add html/admin html/learn && git commit -m "build: admin + learn bundles with Evaluation Mode"
```

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && git add webapp/site/mediadb/admin webapp/site/mediadb/learn webapp/site/mediadb/services/testu/learn/evaluation.xconf webapp/site/mediadb/services/testu/learn/evaluation.json webapp/site/mediadb/services/testu/learn/startevaluation.xconf webapp/site/mediadb/services/testu/learn/startevaluation.json webapp/site/mediadb/services/testu/learn/submitevaluation.xconf webapp/site/mediadb/services/testu/learn/submitevaluation.json webapp/site/mediadb/services/testu/learn/evaluationblueprint.xconf webapp/site/mediadb/services/testu/learn/evaluationblueprint.json && git commit -m "build: Evaluation Mode bundles and endpoint mirrors"
```

Do not push; deploy is a separate step (`/deploy-minsur`), and the user decides when.

---

## Self-review

**Spec coverage** (spec section → task): Data (tables, lists, `evaluationrequired`, audit names, permissions) → 1, 3, 4. Engine terms (blueprint/active/invalid reason, pool by strategy, sufficiency + activation refusal, status precedence, lazy + event expiry, selection steps 1–5 incl. rank and exposure, scoring incl. `failedrule` and `weakest`, Finished conjunct, `meetsrequirement` unchanged) → 2, 3. Tutor procedural guard → 4 (conditional on the peer's dirty file; parked otherwise). Learner endpoints and `answer.json`/`exposure.json` rules, `state.json` block → 3. Admin endpoint incl. unconfigured topics with pool, versions, `pool_insufficient`, `version_conflict`, audit; Personas rows; `person.json` fields → 4. Admin console (rail section, table columns and warning glyph, editor fields incl. subtopics covered, pool panel, history, error handling, profile column) → 5. Learner app (models, topic card states and CTA, session variant: eyebrow, intro, no hint/chat/verdict/rate, countdown + auto-submit, "Question N of T" unchanged, Submit at the end, stop chips, result screen with weakest CTA, error copy) → 6. Tests (pure, server, Flutter) → 2, 3, 4, 5, 6. Rollout (data mirrors, no migration, bundles) → 1, 7. Deliberate deviations recorded as rulings: attempt ids `<user>_<topic>_a<n>` and the `answers` map on the row (Task 3); no `content_unavailable` at start (pool excludes unrenderable questions); client clock offset skipped (server enforces the deadline; the countdown is a display); `person.json` fields added but no console rendering of them (the person page does not render `risk` today).

**Placeholder scan**: no TBD/TODO; every code step carries its code; the two "if the name differs, read X" notes point at concrete files.

**Type consistency**: `Blueprint.mix` ↔ JSON `difficultymix` (toJson/blueprintOf/endpoint all use `difficultymix`); `EvalAttempt.toResultJson()` keys match `EvaluationResult.fromJson` and the check script; `evaluationStatus` keys match `EvaluationState.fromJson`; `evaluationItems` returns `expiresat` read by `NextResult.expiresAt`; `startEvaluation(Topic, Learner, Content, Date)` is called with four arguments in Task 3's module code; `ProfileRow.evaluationRequired` ↔ `evaluationrequired` in `toJson`, `saveprofile.json` parsing and `rowOf`.
