# Mission Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The tutor becomes a goal-driven agent: a server planner turns required level + deadline into a status, a week plan and valid one-tap actions, surfaced on Today, under tutor replies, in nudges, and as approve-to-act suggestions for managers.

**Architecture:** Pure `MissionPlanner` (beside `LearningEngine`) computes everything from data the engine already loads plus one new table (`learnertarget`). Thin endpoints in `TestULearningModule` (`mission.json`, `remind.json`) and `TestUAnalyticsModule` (`coach.json`, `coachaction.json`). The finder tutor skill appends server-validated `[[do …]]` lines; the Flutter apps parse and render them and call existing endpoints. Nothing executes without a tap.

**Tech Stack:** Java 17 (eMe modules, `org.json.simple`), Elasticsearch searchers via `MediaArchive`, Groovy event shims, Flutter 3 (hand-formatted Dart, `FakeEmeHttp` tests), one HTML artifact for the prototype.

**Spec:** `plugins/testu/docs/superpowers/specs/2026-10-05-mission-agent-design.md` — binding; read it first.

## Global Constraints

- **Phase 0 gate: no production code (Tasks 1+) until Diego approves the prototype (Task 0).** UI tasks (8–14) are re-checked against the approved prototype before execution; if the prototype changed copy or layout, the prototype wins and the task is edited first.
- Repos: `eme-server-minsur` (site), `plugins/testu` (own repo), `plugins/catalog` (fork; field XML lives here: `plugins/catalog/html/data/fields/`), `plugins/finder` (fork; must land on **main**), `app-genailabs`. Commit in the repo that owns the file. Never push; Diego pushes.
- Other sessions share these repos and local Tomcat: exact-string edits only; announce before restarting Tomcat (memory `local-eme-server-ops`). `bin/compile.sh` from the server root compiles Java; `bin/sync-testu.sh` copies `plugins/testu/html` + events into `webapp/`.
- Never run `dart format` (hand-formatted files).
- Wire keys lowercase without separators. Day values `YYYY-MM-DD` via `LearningEngine.parseYmd`/`ymd`, compared with `endOfDay(day, zone)`; instants via `LearningEngine.iso`. Replies with day values carry `now`.
- Nullable numbers: `datatype="long"` (never `number`). User fields: `type="list" listid="user"`.
- Product copy Spanish through `L(en, es)`; tests assert English (`flutter_test_config.dart` pins `en`). Tutor name from org config, never hardcoded.
- Learner endpoints `<user/>`; coach reads: same permission block as `overview.xconf` + `TestUTeamModule.loadScope`; coach writes: `canManageProgression(inReq)` (training_manage).
- Chat content is never an input to planner or coach.
- With no `withindays`, no `learnertarget` rows and no certifications, behaviour equals today's: `check_learning.sh`, `check_profiles.sh`, `check_evaluation.sh`, `check_certification.sh`, `flutter test` stay green.

---

## File map

Prototype:
- `/private/tmp/.../scratchpad/mission-proto.html` → published Artifact (throwaway).

Server (`plugins/catalog/html/data/`):
- `fields/learnertarget.xml`, `fields/coachdismissal.xml` — new tables.
- `fields/topicrequirement.xml` — +`withindays`.
- `fields/learnernotification.xml` — +`remindat`, `pushedat`, `status`.
- `fields/user.xml` — +`lastmissionstatus`.
- `lists/learnernotificationtype.xml` — +`mission`.

Server (`plugins/testu`):
- `code/tech/genailabs/tutor/MissionPlanner.java` — new, pure.
- `code/tech/genailabs/tutor/LearningEngine.java` — `ProfileRow.withindays`, `Topic.withindays`, `Learner.targets`, `loadLearner` reads `learnertarget`, `TargetRow`, `saveTarget`.
- `code/tech/genailabs/tutor/TestUProfileModule.java` — `withindays` in profile JSON/save, target writes in `setProfiles`/`saveProfile`.
- `code/tech/genailabs/tutor/TestULearningModule.java` — `mission`, `remind`, `missionNudges`, `MissionActions` bean method, Daily Challenge email line.
- `code/tech/genailabs/tutor/TestUAnalyticsModule.java` — `coach`, `coachAction`, facts in `askAnalytics`.
- `html/services/testu/learn/{mission,remind}.{xconf,json}`, `html/services/testu/analytics/{coach,coachaction}.{xconf,json}`.
- `catalog/events/scripts/testu/certificationreminders.groovy` — also runs `missionNudges`.
- `html/ai/default/calls/chat_tutor_usercomment.json` — `actions` + `$offeredactions`.
- `tools/MissionPlannerCheck.java`, `tools/check_mission.sh`; `tools/check_learning.sh` runs the new pure check.

Finder (`plugins/finder`):
- `code/org/entermediadb/ai/skills/AdaptiveTutorialUserCommentSkill.java` — offered actions in, `[[do]]` lines out.

Learner app (`app-genailabs/lib/testu/`):
- `testu_learn.dart` — `MissionState`, `MissionAction`, `fetchMission`, `postRemind`, `ackMission`.
- `testu_live.dart` — `Cite.actions`, `_doRe` in `splitCite`.
- `testu_actions.dart` — new: `runMissionAction` + `ActionButtons` widget (shared by chat and Today).
- `testu_sully.dart` — `SullyMessage.actions`.
- `testu_tutor.dart` — proactive announce turn.
- `testu_shell.dart` — `_MissionCard`, replaces `_certCard` when the goal is a certification.
- `testu_topics.dart`, `testu_notifications.dart`.

Admin app (`app-genailabs/lib/admin/`):
- `admin_models.dart`, `admin_api.dart` — `CoachSuggestion`, `coach()`, `coachAction()`, `ProfileRow.withinDays`, `PersonTopic.dueDate`.
- `admin_coach.dart` — new card; `admin_overview.dart` mounts it.
- `admin_profiles.dart`, `admin_person.dart`, `admin_iris.dart`.

Tests: `test/testu_cite_test.dart`, `test/testu_mission_test.dart`, `test/admin_coach_test.dart`, `test/admin_profiles_test.dart`.

---

## Phase 0 — Prototype (gate)

### Task 0: Clickable prototype of every new surface

**Files:**
- Create: scratchpad `mission-proto.html` (single file, inline CSS/JS, fake data, no server).

**Interfaces:**
- Produces: an Artifact URL Diego reviews; a list of copy/layout decisions to fold back into Tasks 8–14.

- [ ] **Step 1: Load design guidance.** Invoke `artifact-design` (and `PRODUCT.md` brand: corporate-premium, calm, MasterClass restraint, no Duolingo energy, explainable labels, tutor present on every screen).

- [ ] **Step 2: Build the page.** Two device frames side by side (phone 390px learner, desktop console), stacking at phone width. A scenario switcher at the top drives both:
  - Scenarios: `pace` (no deadline), `on_track`, `at_risk`, `ready`, `overdue`, `cert renewal` (goal from certification), `no_goal`.
  - **Learner · Today:** `_MissionCard` per spec §Learner app (header, status label, band bar "Principiante · 52% → Competente", week dots "1 de 3 sesiones", primary action button, "Más tarde" → sheet 2h / esta noche / mañana, "+2 objetivos más"). Below it the existing Daily Challenge and Continue cards as grey placeholders.
  - **Learner · Tutor tab:** proactive announce bubble (fixed template, e.g. "Ya estás listo para la evaluación de Fatiga.") with action buttons; then one normal Q&A turn: learner question, tutor reply with citation source block, follow-up chips, and ≤ 2 action buttons ("Empezar sesión de 6 min", "Recordármelo más tarde"). A stale button demo: tapping "Reservar evaluación" in an old bubble shows "Ya no disponible".
  - **Learner · Topics:** rows with "Vence 14 nov", subtle at-risk/overdue tag; topic header "Requerido: Competente · antes del 14 nov · de tu perfil Operador".
  - **Learner · Push/bell:** three notification samples (ready, at_risk, reminder) as lock-screen cards.
  - **Console · Overview:** "Sugerencias de IRIS" card with the four kinds; each with Enviar recordatorio (toast "Enviado a 3 · 1 omitido (recordado hace 2 días)"), Cambiar fecha (date picker + deselectable people list), Ver personas, Descartar.
  - **Console · Profile editor:** "Plazo (días)" column.
  - **Console · Ask-IRIS:** "¿Qué hago esta semana?" → answer citing the suggestions with the same buttons and a confirm step.
  - Tutor name shown as "IRIS" with a note "configurable por organización".

- [ ] **Step 3: Self-check.** Open in the browser pane at 390px and desktop width, light and dark; every scenario renders; no horizontal scroll.

- [ ] **Step 4: Publish and hand off.** Publish via the Artifact tool (icon `compass`). Send Diego the link with the three decisions to look at: card density on Today, number of chat buttons (≤ 2), coach wording.

- [ ] **Step 5: GATE.** Stop. Record Diego's changes as edits to Tasks 8–14 (and the spec if behaviour changes) before starting Task 1. Do not proceed without an explicit approval.

---

## Phase 1 — Server

### Task 1: Data fields, list value, sync

**Files:**
- Create: `plugins/catalog/html/data/fields/learnertarget.xml`, `plugins/catalog/html/data/fields/coachdismissal.xml`
- Modify: `plugins/catalog/html/data/fields/topicrequirement.xml`, `learnernotification.xml`, `user.xml` (append before `</properties>`)
- Modify: `plugins/catalog/html/data/lists/learnernotificationtype.xml` (one row)

**Interfaces:**
- Produces: tables `learnertarget {id, user, entitytopic, duedate, source, createdby, createdon}`, `coachdismissal {id, user, key, until}`; `topicrequirement.withindays`; `learnernotification.remindat|pushedat|status`; `user.lastmissionstatus`; notification type `mission`.

- [ ] **Step 1: `learnertarget.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Mission agent (spec 2026-10-05): one row per learner x topic, id <user>_<topicid>. duedate is a day value (midnight UTC,
     LearningEngine.parseYmd). source = profile (written from topicrequirement.withindays) | manual (a manager's setdue). -->
<properties beanname="dataSearcher">
  <property id="id" index="true" stored="true" editable="false" keyword="true"><name><language id="en">ID</language></name></property>
  <property id="user" index="true" stored="true" editable="false" type="list" listid="user"><name><language id="en">User</language><language id="es">Usuario</language></name></property>
  <property id="entitytopic" index="true" stored="true" editable="false" type="list" listid="entitytopic"><name><language id="en">Topic</language><language id="es">Tema</language></name></property>
  <property id="duedate" index="true" stored="true" editable="false" type="date"><name><language id="en">Due</language><language id="es">Vence</language></name></property>
  <property id="source" index="true" stored="true" editable="false" keyword="true"><name><language id="en">Source</language><language id="es">Origen</language></name></property>
  <property id="createdby" index="true" stored="true" editable="false" type="list" listid="user"><name><language id="en">Set by</language><language id="es">Definido por</language></name></property>
  <property id="createdon" index="true" stored="true" editable="false" type="date"><name><language id="en">Set on</language><language id="es">Definido el</language></name></property>
</properties>
```

- [ ] **Step 2: `coachdismissal.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Mission agent coach (spec 2026-10-05): a manager hid a suggestion until `until`. id <user>_<key>. -->
<properties beanname="dataSearcher">
  <property id="id" index="true" stored="true" editable="false" keyword="true"><name><language id="en">ID</language></name></property>
  <property id="user" index="true" stored="true" editable="false" type="list" listid="user"><name><language id="en">Manager</language><language id="es">Responsable</language></name></property>
  <property id="key" index="true" stored="true" editable="false" keyword="true"><name><language id="en">Suggestion</language><language id="es">Sugerencia</language></name></property>
  <property id="until" index="true" stored="true" editable="false" type="date"><name><language id="en">Hidden until</language><language id="es">Oculta hasta</language></name></property>
</properties>
```

- [ ] **Step 3: Append fields**

`topicrequirement.xml`:
```xml
  <!-- Mission agent (spec 2026-10-05): complete within N days of assignment; blank = no deadline. -->
  <property id="withindays" index="true" stored="true" editable="true" type="number" datatype="long"><name><language id="en">Deadline (days)</language><language id="es">Plazo (días)</language></name></property>
```
`learnernotification.xml`:
```xml
  <!-- Mission agent: remindat = deliver at (null = immediate); pushedat = when the push went out; status = goal status a status-change push announced. -->
  <property id="remindat" index="true" stored="true" editable="false" type="date"><name><language id="en">Remind at</language><language id="es">Recordar a las</language></name></property>
  <property id="pushedat" index="true" stored="true" editable="false" type="date"><name><language id="en">Pushed</language><language id="es">Enviada</language></name></property>
  <property id="status" index="true" stored="true" editable="false" keyword="true"><name><language id="en">Goal status</language><language id="es">Estado del objetivo</language></name></property>
```
`user.xml`:
```xml
  <property id="lastmissionstatus" index="false" stored="true" editable="false" keyword="true"><name><language id="en">Last mission status shown</language><language id="es">Último estado de misión mostrado</language></name></property>
```
`learnernotificationtype.xml`: add `<property id="mission">Objetivo</property>` next to the `certification` row (match that row's exact markup).

- [ ] **Step 4: Verify locally.** Copy the catalog files the way `check_fields.sh` expects, restart (announce to peers first), then `plugins/testu/tools/check_fields.sh`. Expected: no field errors. For tables that already exist (`topicrequirement`, `learnernotification`, `user`), reindex per `eme-server-minsur/CLAUDE.md` §6.

- [ ] **Step 5: Commit** (catalog repo)

```bash
git -C plugins/catalog add html/data/fields/learnertarget.xml html/data/fields/coachdismissal.xml html/data/fields/topicrequirement.xml html/data/fields/learnernotification.xml html/data/fields/user.xml html/data/lists/learnernotificationtype.xml
git -C plugins/catalog commit -m "feat(testu): mission agent fields (learnertarget, coachdismissal, withindays, reminders)"
```

### Task 2: Engine plumbing for targets

**Files:**
- Modify: `plugins/testu/code/tech/genailabs/tutor/LearningEngine.java` (`ProfileRow` :784, `rowOf` :799, `Topic` :~77, `Learner` :474, `loadLearner` :565, `applyProfiles` merge)
- Test: `plugins/testu/tools/LearningEngineCheck.java` (new `targetChecks()` called from `main`)

**Interfaces:**
- Produces:
  - `ProfileRow.withindays : Integer`; `Topic.withindays : Integer` (merged = smallest non-null across the learner's rows).
  - `public static class TargetRow { String user, topicid, source, createdby; Date duedate, createdon; String id(); }`
  - `public static TargetRow targetRowOf(Data d)`
  - `Learner.targets : Map<String, TargetRow>` (by topic id), filled by `loadLearner`.
  - `public void saveTarget(TargetRow r)` (caller holds `WRITE_LOCK`).

- [ ] **Step 1: Failing check** — append to `LearningEngineCheck`:

```java
	static void targetChecks()
	{
		Topic t = new Topic();
		LearningEngine.ProfileRow a = new LearningEngine.ProfileRow(); a.withindays = 30;
		LearningEngine.ProfileRow b = new LearningEngine.ProfileRow(); b.withindays = 14;
		LearningEngine.ProfileRow c = new LearningEngine.ProfileRow();
		LearningEngine.mergeWithindays(t, a); LearningEngine.mergeWithindays(t, c); LearningEngine.mergeWithindays(t, b);
		ok("withindays merges to the smallest", Integer.valueOf(14).equals(t.withindays), t.withindays);
		Topic none = new Topic();
		LearningEngine.mergeWithindays(none, c);
		ok("withindays stays null without a value", none.withindays == null, none.withindays);
	}
```
and call `targetChecks();` in `main` next to `certificationChecks();`.

- [ ] **Step 2: Run** `plugins/testu/tools/check_learning.sh` → FAIL (`mergeWithindays` / `withindays` undefined).

- [ ] **Step 3: Implement**

```java
	// ProfileRow: add
	public Integer withindays;
	// rowOf: add
	r.withindays = intOrNull(d.get("withindays"));
	// Topic: add
	public Integer withindays; // mission agent: smallest withindays across the learner's rows; null = no profile deadline

	static void mergeWithindays(Topic t, ProfileRow r)
	{
		if (r.withindays != null && (t.withindays == null || r.withindays < t.withindays))
		{
			t.withindays = r.withindays;
		}
	}

	/** One learnertarget row (mission agent): the learner's deadline on a topic. */
	public static class TargetRow
	{
		public String user, topicid, source, createdby;
		public Date duedate, createdon;
		public String id()
		{
			return user + "_" + topicid;
		}
	}

	public static TargetRow targetRowOf(Data d)
	{
		TargetRow r = new TargetRow();
		r.user = d.get("user"); r.topicid = d.get("entitytopic"); r.source = d.get("source"); r.createdby = d.get("createdby");
		r.duedate = dateOf(d.getValue("duedate")); r.createdon = dateOf(d.getValue("createdon"));
		return r;
	}

	/** Caller holds WRITE_LOCK. */
	public void saveTarget(TargetRow r)
	{
		Searcher s = fieldArchive.getSearcher("learnertarget");
		Data d = (Data) s.searchById(r.id());
		if (d == null)
		{
			d = s.createNewData();
			d.setId(r.id());
		}
		d.setValue("user", r.user); d.setValue("entitytopic", r.topicid); d.setValue("duedate", r.duedate);
		d.setValue("source", r.source); d.setValue("createdby", r.createdby); d.setValue("createdon", r.createdon);
		s.saveData(d, null);
	}
```
In `Learner` add `public Map<String, TargetRow> targets = new HashMap<>(); // by topic id (loadLearner)`.
In `loadLearner(String, Collection, String)`, next to where `certifications` are loaded, add:

```java
		for (Object o : fieldArchive.query("learnertarget").exact("user", inUserid).search())
		{
			TargetRow r = targetRowOf((Data) o);
			l.targets.put(r.topicid, r);
		}
```
In `applyProfiles`, wherever `mergeCertification(t, r)` is called, also call `mergeWithindays(t, r);`.

- [ ] **Step 4: Run** `check_learning.sh` → all `ok`, including existing checks.

- [ ] **Step 5: Commit** (testu repo)

```bash
git -C plugins/testu add code/tech/genailabs/tutor/LearningEngine.java tools/LearningEngineCheck.java
git -C plugins/testu commit -m "feat(testu): learnertarget rows and withindays merge in the engine"
```

### Task 3: MissionPlanner (pure)

**Files:**
- Create: `plugins/testu/code/tech/genailabs/tutor/MissionPlanner.java`
- Create: `plugins/testu/tools/MissionPlannerCheck.java`
- Modify: `plugins/testu/tools/check_learning.sh` (run the new check after `LearningEngineCheck.java`, same `java -cp "$CP"` line)

**Interfaces:**
- Consumes: `LearningEngine` statics (`mastery`, `percent`, `levelIndex`, `requiredLevel` via topicState JSON, `evaluationStatus`, `certStatus`, `expiryOf`, `recommend`, `learner`, `ymd`, `endOfDay`), `Forecast.of`, `Learner.targets` (Task 2).
- Produces:

```java
public final class MissionPlanner
{
	public static final int GAIN_PER_SESSION = 4, PACE_SESSIONS = 3, MAX_SESSIONS = 5, HISTORY_DAYS = 28;
	public static final List<String> REMIND_WHEN = List.of("2h", "tonight", "tomorrow");
	/** Per-topic plan. status: ready | done | overdue | at_risk | on_track | pace. */
	public static JSONObject topicPlan(Topic t, JSONObject inState, Learner l, Date now, ZoneId z)
	/** The learner's mission: {status, goal, week, othergoals, actions, plans}. status adds no_goal. */
	public static JSONObject mission(Content c, Learner l, Map<String, JSONObject> inTopicStates, JSONObject inRecommend, Date now, ZoneId z)
	/** Effective deadline (end of day) or null. */
	public static Date deadline(Topic t, Learner l, ZoneId z)
	/** Mastery percent per local day, oldest first, last = today; replayed from attempts. */
	public static List<Double> history(Topic t, Learner l, Date now, ZoneId z, int days)
	/** Instant for remind.json's `when`, or null when unknown. */
	public static Date remindAt(String when, Date now, ZoneId z)
}
```
`inTopicStates` = `topicState(...)` JSON per topic id (the caller already builds them for `state.json`), so `requiredlevel`, `meetsrequirement`, `masterypercent`, `band`, `competentmin`, `expertmin`, `evaluation.canstart`, `locked` come from one place. `inRecommend` = `LearningEngine.recommend(...)` result restricted to the goal topic (the caller passes a `Content` view holding only that topic).

- [ ] **Step 1: Failing check** `tools/MissionPlannerCheck.java` — self-contained `main` with `ok(...)` like `LearningEngineCheck`; builds JSON topic states by hand (no engine instance needed):

```java
import java.time.ZoneId;
import java.util.*;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import tech.genailabs.tutor.LearningEngine;
import tech.genailabs.tutor.LearningEngine.*;
import tech.genailabs.tutor.MissionPlanner;

/** Pure checks of MissionPlanner (spec 2026-10-05). Run by tools/check_learning.sh. */
public class MissionPlannerCheck
{
	static int failures = 0;
	static final ZoneId LIMA = ZoneId.of("America/Lima");
	static final Date NOW = LearningEngine.parseYmd("2026-10-05"); // midnight UTC = 19:00 Oct 4 Lima

	public static void main(String[] args)
	{
		Topic t = topic("fatiga", 60, 85);
		// no deadline, below required -> pace
		JSONObject st = state("fatiga", "competent", false, 40, "beginner", false);
		ok("pace without deadline", "pace".equals(MissionPlanner.topicPlan(t, st, learner(), NOW, LIMA).get("status")), st);
		// meets requirement + canstart -> ready, even with a deadline
		Learner l = learner(); target(l, "fatiga", "2026-11-14");
		st = state("fatiga", "competent", true, 70, "competent", true);
		ok("ready when met and canstart", "ready".equals(MissionPlanner.topicPlan(t, st, l, NOW, LIMA).get("status")), st);
		// met, cannot start (passed) -> done
		st = state("fatiga", "competent", true, 70, "competent", false);
		ok("done when met and nothing to book", "done".equals(MissionPlanner.topicPlan(t, st, l, NOW, LIMA).get("status")), st);
		// deadline passed -> overdue
		Learner late = learner(); target(late, "fatiga", "2026-10-01");
		st = state("fatiga", "competent", false, 40, "beginner", false);
		ok("overdue after the deadline", "overdue".equals(MissionPlanner.topicPlan(t, st, late, NOW, LIMA).get("status")), st);
		// insufficient history: gap 20 pts = 5 sessions > 4 days / 2 -> at_risk
		Learner soon = learner(); target(soon, "fatiga", "2026-10-09");
		ok("at_risk when sessions needed exceed half the days left", "at_risk".equals(MissionPlanner.topicPlan(t, st, soon, NOW, LIMA).get("status")), MissionPlanner.topicPlan(t, st, soon, NOW, LIMA));
		// far deadline, small gap -> on_track
		ok("on_track with room", "on_track".equals(MissionPlanner.topicPlan(t, st, l, NOW, LIMA).get("status")), MissionPlanner.topicPlan(t, st, l, NOW, LIMA));
		// certification expiry sooner than target wins
		Topic ct = topic("fatiga", 60, 85); ct.validitymonths = 12;
		Learner cl = learner(); target(cl, "fatiga", "2026-12-31");
		CertRow cr = new CertRow(); cr.user = "u"; cr.topicid = "fatiga"; cr.passedat = LearningEngine.parseYmd("2025-10-20");
		cl.certifications.put("fatiga", cr);
		ok("certification expiry wins when sooner", "2026-10-20".equals(LearningEngine.ymd(MissionPlanner.deadline(ct, cl, LIMA), LIMA)), MissionPlanner.deadline(ct, cl, LIMA));
		// remind_later instants
		ok("tomorrow = 09:00 Lima", "2026-10-05T14:00:00Z".equals(MissionPlanner.remindAt("tomorrow", NOW, LIMA).toInstant().toString()), MissionPlanner.remindAt("tomorrow", NOW, LIMA));
		ok("unknown when -> null", MissionPlanner.remindAt("later", NOW, LIMA) == null, "");
		missionChecks();
		if (failures > 0) { System.out.println(failures + " FAILED"); System.exit(1); }
		System.out.println("MissionPlannerCheck: all ok");
	}

	static void missionChecks()
	{
		Content c = new Content();
		Topic a = topic("a", 60, 85), b = topic("b", 60, 85);
		c.topics.put("a", a); c.topics.put("b", b);
		Learner l = learner(); target(l, "b", "2026-10-20");
		Map<String, JSONObject> states = new LinkedHashMap<>();
		states.put("a", state("a", "competent", false, 30, "beginner", false));
		states.put("b", state("b", "competent", false, 50, "beginner", false));
		JSONObject rec = new JSONObject(); rec.put("mode", "learn"); rec.put("topicid", "b"); rec.put("sectionid", "b1");
		JSONObject m = MissionPlanner.mission(c, l, states, rec, NOW, LIMA);
		ok("goal = topic with the earliest deadline over a lower pace topic", "b".equals(((JSONObject) m.get("goal")).get("topic")), m);
		ok("actions: start_session first, remind_later last, no book_evaluation", types(m).equals(List.of("start_session", "remind_later")), m);
		Map<String, JSONObject> empty = new LinkedHashMap<>();
		ok("no required topics -> no_goal, no actions", "no_goal".equals(MissionPlanner.mission(new Content(), learner(), empty, null, NOW, LIMA).get("status")), "");
		states.put("b", state("b", "competent", true, 70, "competent", true));
		m = MissionPlanner.mission(c, l, states, rec, NOW, LIMA);
		ok("ready goal offers book_evaluation first", "book_evaluation".equals(types(m).get(0)), m);
	}

	static List<String> types(JSONObject m)
	{
		List<String> out = new ArrayList<>();
		for (Object o : (JSONArray) m.get("actions")) { out.add(String.valueOf(((JSONObject) o).get("type"))); }
		return out;
	}

	static Topic topic(String id, int comp, int exp) { Topic t = new Topic(); t.id = id; t.title = id; t.competentmin = comp; t.expertmin = exp; return t; }
	static Learner learner() { Learner l = new Learner(); l.userid = "u"; return l; }
	static void target(Learner l, String topic, String ymd) { TargetRow r = new TargetRow(); r.user = l.userid; r.topicid = topic; r.duedate = LearningEngine.parseYmd(ymd); r.source = "profile"; l.targets.put(topic, r); }
	static JSONObject state(String id, String required, boolean meets, int pct, String band, boolean canstart)
	{
		JSONObject s = new JSONObject(); s.put("id", id); s.put("title", id); s.put("requiredlevel", required); s.put("meetsrequirement", meets);
		s.put("masterypercent", pct); s.put("band", band); s.put("competentmin", 60); s.put("expertmin", 85); s.put("locked", false);
		JSONObject e = new JSONObject(); e.put("canstart", canstart); s.put("evaluation", e);
		return s;
	}
	static void ok(String name, boolean cond, Object detail)
	{
		System.out.println((cond ? "ok: " : "FAIL: ") + name + (cond ? "" : " -> " + detail));
		if (!cond) failures++;
	}
}
```

- [ ] **Step 2: Run** `check_learning.sh` → FAIL (class `MissionPlanner` missing).

- [ ] **Step 3: Implement `MissionPlanner.java`**

```java
package tech.genailabs.tutor;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import tech.genailabs.tutor.LearningEngine.Attempt;
import tech.genailabs.tutor.LearningEngine.Content;
import tech.genailabs.tutor.LearningEngine.Learner;
import tech.genailabs.tutor.LearningEngine.Topic;

/**
 * Mission agent (spec 2026-10-05): goal, status, week plan and the actions valid right now, per learner. Pure: callers load
 * Content/Learner and the topicState JSON; nothing here reads or writes storage.
 */
public final class MissionPlanner
{
	public static final int GAIN_PER_SESSION = 4, PACE_SESSIONS = 3, MAX_SESSIONS = 5, HISTORY_DAYS = 28;
	public static final List<String> REMIND_WHEN = List.of("2h", "tonight", "tomorrow");

	private MissionPlanner()
	{
	}

	public static Date deadline(Topic t, Learner l, ZoneId z)
	{
		Date best = null;
		LearningEngine.TargetRow tr = l.targets.get(t.id);
		if (tr != null && tr.duedate != null)
		{
			best = LearningEngine.endOfDay(tr.duedate, z);
		}
		Date expiry = LearningEngine.expiryOf(l.certifications.get(t.id), t, z);
		if (expiry != null && (best == null || expiry.before(best)))
		{
			best = expiry;
		}
		return best;
	}

	static int requiredMin(JSONObject s)
	{
		String req = (String) s.get("requiredlevel");
		if ("expert".equals(req))
		{
			return LearningEngine.intOr(s.get("expertmin"), 85);
		}
		return "competent".equals(req) ? LearningEngine.intOr(s.get("competentmin"), 60) : 0;
	}

	public static JSONObject topicPlan(Topic t, JSONObject s, Learner l, Date now, ZoneId z)
	{
		boolean meets = Boolean.TRUE.equals(s.get("meetsrequirement"));
		JSONObject eval = (JSONObject) s.get("evaluation");
		boolean canstart = eval != null && Boolean.TRUE.equals(eval.get("canstart"));
		int pct = LearningEngine.intOr(s.get("masterypercent"), 0), gap = Math.max(0, requiredMin(s) - pct);
		Date dl = deadline(t, l, z);
		Long daysleft = dl == null ? null : ChronoUnit.DAYS.between(now.toInstant().atZone(z).toLocalDate(), dl.toInstant().atZone(z).toLocalDate());
		String status;
		if (meets && canstart)
			status = "ready";
		else if (meets)
			status = "done";
		else if (dl != null && now.after(dl))
			status = "overdue";
		else if (dl == null)
			status = "pace";
		else
			status = atRisk(t, l, s, gap, daysleft, now, z) ? "at_risk" : "on_track";
		JSONObject o = new JSONObject();
		o.put("topic", t.id);
		o.put("topictitle", t.title);
		o.put("status", status);
		o.put("requiredlevel", s.get("requiredlevel"));
		o.put("band", s.get("band"));
		o.put("masterypercent", pct);
		o.put("gap", gap);
		o.put("deadline", dl == null ? null : LearningEngine.ymd(dl, z));
		o.put("deadlinesource", dl == null ? null : deadlineSource(t, l, z, dl));
		o.put("daysleft", daysleft);
		o.put("canstart", canstart);
		return o;
	}

	static String deadlineSource(Topic t, Learner l, ZoneId z, Date dl)
	{
		Date expiry = LearningEngine.expiryOf(l.certifications.get(t.id), t, z);
		if (expiry != null && expiry.equals(dl))
		{
			return "certification";
		}
		LearningEngine.TargetRow tr = l.targets.get(t.id);
		return tr == null ? null : tr.source;
	}

	static boolean atRisk(Topic t, Learner l, JSONObject s, int gap, long daysleft, Date now, ZoneId z)
	{
		java.util.Map<String, Object> f = Forecast.of(history(t, l, now, z, HISTORY_DAYS), requiredMin(s) / 100.0);
		String fs = String.valueOf(f.get("status"));
		if ("insufficient".equals(fs))
		{
			int sessions = (gap + GAIN_PER_SESSION - 1) / GAIN_PER_SESSION;
			return sessions > daysleft / 2.0; // ponytail: one session every other day is the most we plan for
		}
		if ("notonpace".equals(fs))
		{
			return true;
		}
		Object eta = f.get("eta");
		return !"reached".equals(fs) && (eta == null || ((Number) eta).longValue() > daysleft);
	}

	/** ponytail: replays up to HISTORY_DAYS learner() builds per topic; fine at pilot sizes, cache per request if it shows in profiles. */
	public static List<Double> history(Topic t, Learner l, Date now, ZoneId z, int days)
	{
		List<Double> out = new ArrayList<>();
		LocalDate today = now.toInstant().atZone(z).toLocalDate();
		LocalDate first = null;
		for (Attempt a : l.attempts)
		{
			if (a.at != null)
			{
				first = a.at.toInstant().atZone(z).toLocalDate();
				break;
			}
		}
		if (first == null)
		{
			return out;
		}
		LocalDate start = today.minusDays(days - 1);
		if (first.isAfter(start))
		{
			start = first;
		}
		for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1))
		{
			Date cut = Date.from(d.plusDays(1).atStartOfDay(z).toInstant());
			List<Attempt> upto = new ArrayList<>();
			for (Attempt a : l.attempts)
			{
				if (a.at != null && a.at.before(cut))
					upto.add(a);
			}
			out.add(LearningEngine.percent(t.questions, LearningEngine.learner(l.userid, upto)) / 100.0);
		}
		return out;
	}

	public static JSONObject mission(Content c, Learner l, Map<String, JSONObject> inStates, JSONObject inRecommend, Date now, ZoneId z)
	{
		List<JSONObject> plans = new ArrayList<>();
		for (Topic t : c.topics.values())
		{
			JSONObject s = inStates.get(t.id);
			if (s == null || s.get("requiredlevel") == null || Boolean.TRUE.equals(s.get("locked")))
			{
				continue;
			}
			plans.add(topicPlan(t, s, l, now, z));
		}
		JSONObject goal = pick(plans);
		JSONObject o = new JSONObject();
		o.put("status", goal == null ? "no_goal" : goal.get("status"));
		o.put("goal", goal);
		o.put("week", goal == null ? null : week(goal, c.topics.get(goal.get("topic")), l, now, z));
		int others = 0;
		for (JSONObject p : plans)
		{
			if (p != goal && p.get("deadline") != null && !"done".equals(p.get("status")))
				others++;
		}
		o.put("othergoals", others);
		o.put("actions", goal == null ? new JSONArray() : actions(goal, c.topics.get(goal.get("topic")), l, inRecommend, now, z));
		JSONArray all = new JSONArray();
		all.addAll(plans);
		o.put("plans", all); // every required topic's plan: the Topics tab shows per-row deadlines
		return o;
	}

	static int rank(String status)
	{
		return switch (status)
		{
			case "overdue" -> 0;
			case "ready" -> 1;
			case "at_risk", "on_track" -> 2;
			case "pace" -> 3;
			default -> 9;
		};
	}

	/** Lowest rank; ties by earliest deadline; then learner order (stable). */
	static JSONObject pick(List<JSONObject> plans)
	{
		JSONObject best = null;
		for (JSONObject p : plans)
		{
			int r = rank(String.valueOf(p.get("status")));
			if (r == 9)
				continue;
			if (best == null)
			{
				best = p;
				continue;
			}
			int br = rank(String.valueOf(best.get("status")));
			String d = (String) p.get("deadline"), bd = (String) best.get("deadline");
			if (r < br || (r == br && d != null && (bd == null || d.compareTo(bd) < 0)))
				best = p;
		}
		return best;
	}

	static JSONObject week(JSONObject goal, Topic t, Learner l, Date now, ZoneId z)
	{
		int needed;
		Object dl = goal.get("daysleft");
		int gap = LearningEngine.intOr(goal.get("gap"), 0);
		if (dl == null)
			needed = PACE_SESSIONS;
		else
		{
			long weeks = Math.max(1, (((Number) dl).longValue() + 6) / 7);
			needed = (int) Math.max(1, Math.min(MAX_SESSIONS, Math.ceil(Math.ceil(gap / (double) GAIN_PER_SESSION) / weeks)));
		}
		LocalDate monday = now.toInstant().atZone(z).toLocalDate().with(java.time.DayOfWeek.MONDAY);
		java.util.Set<String> ids = LearningEngine.ids(t.questions);
		java.util.Set<LocalDate> days = new java.util.HashSet<>();
		for (Attempt a : l.attempts)
		{
			if (a.at != null && ids.contains(a.questionid) && LearningEngine.isLearningMode(a.mode))
			{
				LocalDate d = a.at.toInstant().atZone(z).toLocalDate();
				if (!d.isBefore(monday))
					days.add(d);
			}
		}
		JSONObject w = new JSONObject();
		w.put("sessionsneeded", needed);
		w.put("sessionsdone", Math.min(days.size(), needed));
		return w;
	}

	static JSONArray actions(JSONObject goal, Topic t, Learner l, JSONObject rec, Date now, ZoneId z)
	{
		JSONArray out = new JSONArray();
		String topic = (String) goal.get("topic");
		if ("ready".equals(goal.get("status")))
		{
			String cs = LearningEngine.certStatus(t, l.certifications.get(topic), now, z);
			LearningEngine.CertRow cr = l.certifications.get(topic);
			boolean renewal = ("renewal_due".equals(cs) || "expired".equals(cs)) && (cr == null || cr.scheduledfor == null);
			out.add(action(renewal ? "schedule_certification" : "book_evaluation", topic, null, null));
		}
		if (rec != null && topic.equals(rec.get("topicid")) && !"ready".equals(goal.get("status")))
		{
			out.add(action("start_session", topic, (String) rec.get("mode"), (String) rec.get("sectionid")));
		}
		out.add(action("remind_later", topic, null, null));
		for (int i = 0; i < out.size(); i++)
		{
			((JSONObject) out.get(i)).put("id", "a" + (i + 1));
		}
		return out;
	}

	static JSONObject action(String type, String topic, String mode, String section)
	{
		JSONObject a = new JSONObject();
		a.put("type", type);
		a.put("topic", topic);
		if (mode != null)
			a.put("mode", mode);
		if (section != null)
			a.put("section", section);
		return a;
	}

	public static Date remindAt(String when, Date now, ZoneId z)
	{
		ZonedDateTime n = now.toInstant().atZone(z);
		return switch (when == null ? "" : when)
		{
			case "2h" -> Date.from(n.plusHours(2).toInstant());
			case "tonight" -> Date.from((n.getHour() >= 19 ? n.plusDays(1) : n).withHour(19).withMinute(0).withSecond(0).withNano(0).toInstant());
			case "tomorrow" -> Date.from(n.plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0).toInstant());
			default -> null;
		};
	}
}
```
Note: `Attempt.mode` and `LearningEngine.ids` exist (`Attempt` :465, `ids` :4160); `percent`/`learner` are public static. `Topic.certification()` is not needed here because `expiryOf` returns null without `validitymonths`.

- [ ] **Step 4: Run** `check_learning.sh` → `MissionPlannerCheck: all ok` plus existing output. If "tomorrow" fails, note NOW is 19:00 Oct 4 in Lima, so tomorrow 09:00 Lima = Oct 5 14:00Z (expected value above).

- [ ] **Step 5: Commit**

```bash
git -C plugins/testu add code/tech/genailabs/tutor/MissionPlanner.java tools/MissionPlannerCheck.java tools/check_learning.sh
git -C plugins/testu commit -m "feat(testu): MissionPlanner -- goal, status, week plan and valid actions"
```

### Task 4: Target writes from job profiles

**Files:**
- Modify: `plugins/testu/code/tech/genailabs/tutor/TestUProfileModule.java` (`profileJson` row fields, `saveProfile` parse/validate, `setProfiles` :275)
- Test: `plugins/testu/tools/check_mission.sh` (create; header copied verbatim from `check_certification.sh` lines 1–~120: guards, session, call, ok, must, login, es_ids, delete_rows, refresh, put_row, es_doc, usersave, make_user, audits, wipe_user_rows)

**Interfaces:**
- Consumes: `LearningEngine.TargetRow`, `saveTarget`, `parseYmd` (Task 2).
- Produces: `void writeProfileTargets(MediaArchive archive, String userid, Collection<String> jobroles, String actor)` (instance method: it uses the base `audit(archive, actor, …)` overload) in `TestUProfileModule` — for each merged row with `withindays` and no existing target, writes `source=profile`, `duedate` = today (org zone) + withindays, audited `learnertarget.set`.

- [ ] **Step 1: Failing server check** — in `check_mission.sh` after the copied header, user `mission.check@testu.local`, topic = first topic with questions (as `check_certification.sh` picks it):

```python
# --- profile targets
admin = login(os.environ["EME_USER"], os.environ["EME_PASSWORD"])
wipe_user_rows(UID, ["learnertarget"])
s, r = call(admin, "POST", "/services/testu/personas/saveprofile.json", form={"id": "", "name": "Mission check", "rows": json.dumps([{"topic": TOPIC, "requiredlevel": "competent", "mandatory": True, "requiresprevious": False, "afterfinish": "keep", "withindays": 30}])})
must("saveprofile with withindays", s == 200 and r["profile"]["rows"][0]["withindays"] == 30, r)
PROFILE = r["profile"]["id"]
s, r = call(admin, "POST", "/services/testu/personas/setprofiles.json", form={"user": UID, "primary": PROFILE, "extras": "[]"})
refresh("learnertarget")
row = es_doc("learnertarget", f"{UID}_{TOPIC}")
ok("setprofiles writes a profile target", row and row.get("source") == "profile", row)
due1 = row and row.get("duedate")
call(admin, "POST", "/services/testu/personas/saveprofile.json", form={"id": PROFILE, "name": "Mission check", "rows": json.dumps([{"topic": TOPIC, "requiredlevel": "competent", "mandatory": True, "requiresprevious": False, "afterfinish": "keep", "withindays": 5}])})
refresh("learnertarget")
ok("changing withindays does not move a written date", es_doc("learnertarget", f"{UID}_{TOPIC}").get("duedate") == due1)
ok("learnertarget.set audited", any(a.get("action") == "learnertarget.set" for a in audits(UID)))
```

- [ ] **Step 2: Run** `EME_USER=… EME_PASSWORD=… plugins/testu/tools/check_mission.sh` → FAIL (`withindays` missing from profile JSON).

- [ ] **Step 3: Implement**
  - `profileJson` row: `row.put("withindays", r.withindays);`
  - `saveProfile` row parse: `Integer within = intOrNull(o.get("withindays"));` validated with the existing bad-number helper (range 1..3650, blank allowed → error `bad_withindays`); `d.setValue("withindays", within);`.
  - After the row upsert in `saveProfile`, when any row's `withindays` went from null to a value (compare with the rows read before the write), call `writeProfileTargets` for each member (`members` already computed for `profiles.json`).
  - In `setProfiles`, after `users.saveData(u, …)`: `writeProfileTargets(archive, userid, all, inReq.getUser().getId());`

```java
	void writeProfileTargets(MediaArchive archive, String userid, java.util.Collection<String> jobroles, String actor)
	{
		LearningEngine engine = new LearningEngine(archive);
		LearningEngine.Profiles p = engine.loadProfiles(jobroles);
		java.util.Map<String, Integer> within = new java.util.HashMap<>();
		for (LearningEngine.ProfileRow r : p.rows)
		{
			if (r.withindays != null && (!within.containsKey(r.topicid) || r.withindays < within.get(r.topicid)))
				within.put(r.topicid, r.withindays);
		}
		if (within.isEmpty())
			return;
		java.time.ZoneId zone = (java.time.ZoneId) engine.orgZone()[0];
		java.time.LocalDate today = java.time.LocalDate.now(zone);
		Searcher s = archive.getSearcher("learnertarget");
		synchronized (LearningEngine.WRITE_LOCK)
		{
			for (java.util.Map.Entry<String, Integer> e : within.entrySet())
			{
				if (s.searchById(userid + "_" + e.getKey()) != null)
					continue; // written dates never move; managers use setdue
				LearningEngine.TargetRow t = new LearningEngine.TargetRow();
				t.user = userid; t.topicid = e.getKey(); t.source = "profile"; t.createdby = actor; t.createdon = new Date();
				t.duedate = LearningEngine.parseYmd(today.plusDays(e.getValue()).toString());
				engine.saveTarget(t);
				JSONObject after = new JSONObject();
				after.put("duedate", t.duedate == null ? null : today.plusDays(e.getValue()).toString());
				after.put("source", "profile");
				audit(archive, actor, "learnertarget.set", "user", userid, null, after); // TestUBaseModule:96
			}
		}
	}
```

- [ ] **Step 4: Run** `bin/compile.sh`, `bin/sync-testu.sh`, restart (announce), then `check_mission.sh` → the four `ok`; `check_profiles.sh` still green.

- [ ] **Step 5: Commit**

```bash
git -C plugins/testu add code/tech/genailabs/tutor/TestUProfileModule.java tools/check_mission.sh
git -C plugins/testu commit -m "feat(testu): job profile withindays writes learner targets"
```

### Task 5: `mission.json` and `remind.json`

**Files:**
- Modify: `TestULearningModule.java` (new methods `mission`, `remind`, helper `missionFor`)
- Create: `html/services/testu/learn/mission.xconf`, `mission.json`, `remind.xconf`, `remind.json`
- Test: `tools/check_mission.sh` (append)

**Interfaces:**
- Consumes: `MissionPlanner.mission`, `remindAt` (Task 3), `load(inReq, user)` :385.
- Produces: `JSONObject missionFor(LearningEngine engine, Content content, Learner learner, Date now, ZoneId zone)` (package-visible; reused by Task 6 nudges, Task 7 coach, Task 8 bean). Endpoints per spec §Endpoints.

- [ ] **Step 1: Failing check** (append):

```python
# --- mission.json
learner = login_as(UID)  # helper from the copied header (one-time code flow used by check_certification.sh)
s, m = call(learner, "GET", "/services/testu/learn/mission.json")
must("mission.json ok", s == 200 and m.get("ok"), m)
ok("goal is the profile topic with a deadline", m["goal"] and m["goal"]["topic"] == TOPIC and m["goal"]["deadlinesource"] == "profile", m)
ok("status on_track or at_risk", m["status"] in ("on_track", "at_risk"), m)
ok("announce set on first read", m.get("announce") and m["announce"]["topic"] == TOPIC, m)
call(learner, "GET", "/services/testu/learn/mission.json?ack=1")
s, m = call(learner, "GET", "/services/testu/learn/mission.json")
ok("ack clears announce", m.get("announce") is None, m)
ok("no book_evaluation when canstart is false", all(a["type"] != "book_evaluation" for a in m["actions"]), m)
# --- remind.json
s, r = call(learner, "POST", "/services/testu/learn/remind.json", form={"topic": TOPIC, "when": "tomorrow"})
ok("remind ok", s == 200 and r.get("ok"), r)
s, r = call(learner, "POST", "/services/testu/learn/remind.json", form={"topic": TOPIC, "when": "2h"})
refresh("learnernotification")
pend = es_ids("learnernotification", {"bool": {"must": [{"term": {"user": UID}}, {"term": {"type": "mission"}}, {"exists": {"field": "remindat"}}], "must_not": [{"exists": {"field": "pushedat"}}]}})
ok("second remind replaces the first", len(pend) == 1, pend)
s, r = call(learner, "POST", "/services/testu/learn/remind.json", form={"topic": TOPIC, "when": "someday"})
ok("unknown when -> 400", s == 400, r)
```

- [ ] **Step 2: Run** → FAIL (404 on mission.json).

- [ ] **Step 3: Implement**

`mission.xconf` / `remind.xconf` (same shape as `schedulecertification.xconf`):
```xml
<page>
  <path-action name="TestULearningModule.mission"/>
  <permission name="view"><user/></permission>
</page>
```
`mission.json` / `remind.json`: copy `schedulecertification.json` verbatim (it renders the reply the module put on the request).

```java
	JSONObject missionFor(LearningEngine engine, LearningEngine.Content content, LearningEngine.Learner learner, Date now, ZoneId zone)
	{
		java.util.Map<String, JSONObject> unlocks = engine.subtopicStates(content, learner);
		java.util.Map<String, JSONObject> states = new java.util.LinkedHashMap<>();
		for (LearningEngine.Topic t : content.topics.values())
		{
			states.put(t.id, engine.topicState(t, learner, unlocks));
		}
		JSONObject first = MissionPlanner.mission(content, learner, states, null, now, zone);
		JSONObject goal = (JSONObject) first.get("goal");
		if (goal == null)
			return first;
		LearningEngine.Content only = new LearningEngine.Content();
		LearningEngine.Topic gt = content.topics.get(goal.get("topic"));
		only.topics.put(gt.id, gt);
		only.sections.putAll(content.sections);
		JSONObject rec = LearningEngine.recommend(only, learner, unlocks, id -> (String) states.get(id).get("requiredlevel"));
		return MissionPlanner.mission(content, learner, states, rec, now, zone);
	}

	public void mission(WebPageRequest inReq)
	{
		User user = requireUser(inReq);
		if (user == null)
			return;
		Object[] loaded = load(inReq, user);
		LearningEngine engine = (LearningEngine) loaded[0];
		ZoneId zone = (ZoneId) engine.orgZone()[0];
		Date now = new Date();
		JSONObject m = missionFor(engine, (LearningEngine.Content) loaded[1], (LearningEngine.Learner) loaded[2], now, zone);
		MediaArchive archive = getMediaArchive(inReq);
		Data urec = freshUser(archive, user);
		JSONObject goal = (JSONObject) m.get("goal");
		String key = goal == null ? null : goal.get("topic") + ":" + m.get("status");
		String last = urec.get("lastmissionstatus");
		boolean ack = "1".equals(param(inReq, "ack"));
		if (ack && key != null && !key.equals(last))
		{
			urec.setValue("lastmissionstatus", key);
			archive.getSearcher("user").saveData(urec, null);
			last = key;
		}
		JSONObject announce = null;
		if (key != null && !key.equals(last) && !"pace".equals(m.get("status")))
		{
			announce = new JSONObject();
			announce.put("status", m.get("status"));
			announce.put("topic", goal.get("topic"));
		}
		m.put("announce", announce);
		m.put("ok", Boolean.TRUE);
		m.put("now", LearningEngine.iso(now));
		reply(inReq, m);
	}

	public void remind(WebPageRequest inReq)
	{
		User user = requireUser(inReq);
		if (user == null)
			return;
		MediaArchive archive = getMediaArchive(inReq);
		String topic = param(inReq, "topic");
		ZoneId zone = zoneOf(userZone(archive, user.getId()), (ZoneId) new LearningEngine(archive).orgZone()[0]);
		Date at = MissionPlanner.remindAt(param(inReq, "when"), new Date(), zone);
		if (topic == null || at == null)
		{
			fail(inReq, 400, at == null ? "bad_when" : "missing_topic");
			return;
		}
		Searcher ns = archive.getSearcher("learnernotification");
		synchronized (LearningEngine.WRITE_LOCK)
		{
			String id = user.getId() + "_mission_remind";
			Data n = (Data) ns.searchById(id);
			if (n == null)
			{
				n = ns.createNewData();
				n.setId(id); // one pending reminder per learner: a new one replaces it
			}
			n.setValue("user", user.getId());
			n.setValue("actor", "tutor");
			n.setValue("actorname", tutorName(archive));
			n.setValue("type", "mission");
			n.setValue("datecreated", new Date());
			n.setValue("read", false);
			n.setValue("entitytopic", topic);
			n.setValue("remindat", at);
			n.setValue("pushedat", null);
			n.setValue("text", null); // filled at delivery from the then-current mission
			ns.saveData(n, null);
		}
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("remindat", LearningEngine.iso(at));
		reply(inReq, resp);
	}
```
`zoneOf`, `userZone`, `tutorName`, `param`, `freshUser` already exist in the module/base (see `dailyChallengeEmail` :1976). Notification list (`loadNotifications`) must hide rows whose `remindat` is in the future: add `if (n.getValue("remindat") != null && ((Date) n.getValue("remindat")).after(new Date()) && n.get("pushedat") == null) continue;` in `TestUSocialModule.loadNotifications` :483 loop.

- [ ] **Step 4: Run** compile, sync, restart (announce), `check_mission.sh` → all `ok`.

- [ ] **Step 5: Commit**

```bash
git -C plugins/testu add code/tech/genailabs/tutor/TestULearningModule.java code/tech/genailabs/tutor/TestUSocialModule.java html/services/testu/learn/mission.* html/services/testu/learn/remind.* tools/check_mission.sh
git -C plugins/testu commit -m "feat(testu): mission.json and remind.json"
```

### Task 6: Nudges

**Files:**
- Modify: `TestULearningModule.java` (`missionNudges(MediaArchive)`, `missionText`, Daily Challenge email line in `dailyChallengeEmail`'s content builder)
- Modify: `catalog/events/scripts/testu/certificationreminders.groovy` (also call `missionNudges`)
- Create: `html/services/testu/learn/missionnudges.{xconf,json}` (on-demand, `canManageProgression`, for the check)
- Test: `tools/check_mission.sh` (append)

**Interfaces:**
- Consumes: `missionFor` (Task 5), `social.push(archive, n)`, `mayReceive`.
- Produces: `public int missionNudges(MediaArchive archive)` → pushes sent.

- [ ] **Step 1: Failing check** (append):

```python
# --- nudges
put_row("learnernotification", f"{UID}_mission_remind", {"user": UID, "type": "mission", "entitytopic": TOPIC, "remindat": "2020-01-01T00:00:00Z", "read": False, "actor": "tutor"})
refresh("learnernotification")
s, r = call(admin, "GET", "/services/testu/learn/missionnudges.json")
ok("nudges ran", s == 200 and r.get("ok"), r)
refresh("learnernotification")
ok("due reminder delivered (pushedat + text set)", es_doc("learnernotification", f"{UID}_mission_remind").get("pushedat") and es_doc("learnernotification", f"{UID}_mission_remind").get("text"))
s, r2 = call(admin, "GET", "/services/testu/learn/missionnudges.json")
ok("status push not repeated on a second run", r2.get("sent", 0) == 0, r2)
```

- [ ] **Step 2: Run** → FAIL (404).

- [ ] **Step 3: Implement**

```java
	public int missionNudges(MediaArchive archive)
	{
		LearningEngine engine = new LearningEngine(archive);
		ZoneId orgzone = (ZoneId) engine.orgZone()[0];
		Date now = new Date();
		TestUSocialModule social = (TestUSocialModule) getModuleManager().getBean("TestUSocialModule");
		Searcher ns = archive.getSearcher("learnernotification");
		int sent = 0;
		for (Object o : archive.query("user").all().search())
		{
			Data u = (Data) o;
			try
			{
				if ("false".equals(String.valueOf(u.get("enabled"))) || !mayReceive(archive, u))
					continue;
				ZoneId zone = zoneOf(userZone(archive, u.getId()), orgzone);
				int hour = now.toInstant().atZone(zone).getHour();
				if (hour < 8 || hour >= 20)
					continue; // quiet hours: the next run inside the window delivers
				LearningEngine.Content content = engine.loadContent();
				LearningEngine.Learner l = engine.loadLearner(u.getId(), LearningEngine.jobrolesOf(u), LearningEngine.primaryJobroleOf(u));
				engine.applyProfiles(content, l);
				JSONObject m = missionFor(engine, content, l, now, zone);
				JSONObject goal = (JSONObject) m.get("goal");
				String status = String.valueOf(m.get("status"));
				synchronized (LearningEngine.WRITE_LOCK)
				{
					Data remind = (Data) ns.searchById(u.getId() + "_mission_remind");
					if (remind != null && remind.get("pushedat") == null && remind.getValue("remindat") != null && !((Date) remind.getValue("remindat")).after(now))
					{
						remind.setValue("text", missionText(goal == null ? "pace" : "remind", goal));
						remind.setValue("pushedat", now);
						remind.setValue("datecreated", now);
						ns.saveData(remind, null);
						social.push(archive, remind);
						sent++;
					}
					if (goal != null && Set.of("ready", "at_risk", "overdue").contains(status))
					{
						String id = u.getId() + "_mission_" + goal.get("topic") + "_" + status;
						Data prev = (Data) ns.searchById(id);
						boolean recent = prev != null && prev.getValue("pushedat") != null && ("at_risk".equals(status)
							? now.getTime() - ((Date) prev.getValue("pushedat")).getTime() < 7L * 86400000
							: true); // ready/overdue: once per status entry; the row is deleted when the status leaves (below)
						if (!recent)
						{
							Data n = prev != null ? prev : ns.createNewData();
							n.setId(id);
							n.setValue("user", u.getId()); n.setValue("actor", "tutor"); n.setValue("actorname", tutorName(archive));
							n.setValue("type", "mission"); n.setValue("datecreated", now); n.setValue("read", false);
							n.setValue("entitytopic", goal.get("topic")); n.setValue("status", status); n.setValue("pushedat", now);
							n.setValue("text", missionText(status, goal));
							ns.saveData(n, null);
							social.push(archive, n);
							sent++;
						}
					}
					// leaving ready/overdue re-arms them: drop this learner's status rows for other statuses of the goal topic
					if (goal != null)
					{
						for (String st : List.of("ready", "overdue"))
						{
							if (!st.equals(status))
							{
								Data old = (Data) ns.searchById(u.getId() + "_mission_" + goal.get("topic") + "_" + st);
								if (old != null)
									ns.delete(old, null);
							}
						}
					}
				}
			}
			catch (Exception e)
			{
				log.error("mission nudges: user " + u.getId(), e);
			}
		}
		return sent;
	}

	static String missionText(String status, JSONObject goal)
	{
		String topic = goal == null ? "" : String.valueOf(goal.get("topictitle"));
		return switch (status)
		{
			case "ready" -> "Ya estás listo para la evaluación de " + topic + ".";
			case "at_risk" -> "Vas justo con " + topic + " (vence el " + goal.get("deadline") + "). Una sesión corta hoy te pone al día.";
			case "overdue" -> "El plazo de " + topic + " venció. Retomemos con una sesión corta.";
			case "remind" -> "Te lo recuerdo: " + topic + ", una sesión de 6 minutos.";
			default -> "Una sesión corta hoy para seguir avanzando.";
		};
	}
```
(Add `import java.util.List;`.) Groovy shim: in `certificationreminders.groovy`, after the existing call, add the same bean lookup and `module.missionNudges(archive)`. `missionnudges.json` endpoint: copy `runCertificationReminders` as `runMissionNudges` calling `missionNudges`. Daily Challenge email: where `emailContent(...)` builds the body, prepend one paragraph when `missionFor(...).status` ∉ {`no_goal`, `pace`}: `missionText(status, goal)` + the existing button style linking to the app root.

- [ ] **Step 4: Run** compile, sync, restart (announce), `check_mission.sh`, `check_certification.sh` → green.

- [ ] **Step 5: Commit**

```bash
git -C plugins/testu add code/tech/genailabs/tutor/TestULearningModule.java catalog/events/scripts/testu/certificationreminders.groovy html/services/testu/learn/missionnudges.* tools/check_mission.sh
git -C plugins/testu commit -m "feat(testu): mission nudges (due reminders, status-change pushes, email line)"
```

### Task 7: Coach endpoints and Ask-IRIS facts

**Files:**
- Modify: `TestUAnalyticsModule.java` (`coach`, `coachAction`, `askAnalytics` :1738 facts)
- Create: `html/services/testu/analytics/coach.{xconf,json}`, `coachaction.{xconf,json}`
- Test: `tools/check_mission.sh` (append)

**Interfaces:**
- Consumes: `missionFor` (via `(TestULearningModule) getModuleManager().getBean("TestULearningModule")`), `scopeteams` page value (`TestUTeamModule.loadScope`), `writeTarget` logic from Task 4 (manual source).
- Produces: `coach.json` → `{ok, suggestions: [{key, kind, topic, topictitle, deadline, count, users: [{id, name, status, masterypercent, daysleft}]}], now}`; `coachaction.json` → `{ok, done, skipped}`.

- [ ] **Step 1: Failing check** (append): create a second learner in the same team with the overdue target (`put_row learnertarget duedate 2026-01-01`), then:

```python
s, c = call(admin, "GET", "/services/testu/analytics/coach.json")
must("coach ok", s == 200 and c.get("ok"), c)
sug = next((x for x in c["suggestions"] if x["kind"] == "overdue" and x["topic"] == TOPIC), None)
ok("overdue suggestion lists the learner", sug and any(u["id"] == UID2 for u in sug["users"]), c)
s, r = call(admin, "POST", "/services/testu/analytics/coachaction.json", form={"action": "nudge", "topic": TOPIC, "users": json.dumps([UID2])})
ok("nudge sent", r.get("done") == 1, r)
s, r = call(admin, "POST", "/services/testu/analytics/coachaction.json", form={"action": "nudge", "topic": TOPIC, "users": json.dumps([UID2])})
ok("second nudge within 3 days skipped", r.get("skipped") == 1, r)
s, r = call(admin, "POST", "/services/testu/analytics/coachaction.json", form={"action": "setdue", "topic": TOPIC, "users": json.dumps([UID2]), "duedate": "2030-01-01"})
refresh("learnertarget")
ok("setdue writes a manual target", es_doc("learnertarget", f"{UID2}_{TOPIC}").get("source") == "manual", r)
s, r = call(admin, "POST", "/services/testu/analytics/coachaction.json", form={"action": "dismiss", "key": sug["key"]})
s, c = call(admin, "GET", "/services/testu/analytics/coach.json")
ok("dismissed suggestion hidden", all(x["key"] != sug["key"] for x in c["suggestions"]), c)
s, r = call(learner, "POST", "/services/testu/analytics/coachaction.json", form={"action": "dismiss", "key": "x"})
ok("learner gets 403", s == 403, r)
ok("coach writes audited", {"coach.nudge", "learnertarget.set", "coach.dismiss"} <= {a.get("action") for a in audits_all()})
```

- [ ] **Step 2: Run** → FAIL (404).

- [ ] **Step 3: Implement**
  - `coach.xconf`: copy `overview.xconf` (same `loadScope` + permission block), action `TestUAnalyticsModule.coach`. `coachaction.xconf`: `loadScope` + `TestUAnalyticsModule.coachAction`, permission `<user/>` (the method checks `canManageProgression`).
  - `coach(inReq)`: for every enabled, non-internal user whose `team` is in `scopeteams` (null scope = all; same in-scope test as `analytics()` :202), build content/learner as in `missionNudges`, call `missionFor`, and bucket by kind:
    - `overdue` / `at_risk` → kind = status, per goal topic.
    - `ready` with no `scheduledfor` on the certification row and no evaluation attempt in progress → `ready_not_booked`.
    - certification `expiry` within 14 days and no `scheduledfor` → `cert_expiring` (from `state`'s certification block logic: `LearningEngine.certificationStatus`).
    - `key` = `kind + ":" + topic + ":" + (team filter or "all")`. Skip keys with a `coachdismissal` row `<manager>_<key>` whose `until` > now. Sort: overdue, cert_expiring, at_risk, ready_not_booked; then count desc.
  - `coachAction(inReq)`: 403 unless `canManageProgression(inReq)`; every user id must be in scope (403 `out of scope` otherwise).
    - `nudge`: for each user, skip if a `learnernotification` id `<user>_coach_<topic>` has `pushedat` within 3 days; else upsert it (type `mission`, text `missionText(status, goal)` from that user's mission, `pushedat` now) and `social.push`. Audit `coach.nudge` once with `{topic, users, skipped}`.
    - `setdue`: `parseYmd(duedate)` (400 `bad_date`), upsert `TargetRow` with `source=manual`, `createdby` = caller, audit `learnertarget.set` per user with before/after duedate.
    - `dismiss`: save `coachdismissal` `<caller>_<key>` with `until` = now + 7 days; audit `coach.dismiss`.
  - `askAnalytics`: after `facts` are built (:1765), add `facts.put("coach", <suggestions without users: key, kind, topictitle, deadline, count>)` from the same computation (factor the bucketing into `private List<JSONObject> coachSuggestions(WebPageRequest inReq, MediaArchive archive)` used by both).

- [ ] **Step 4: Run** compile, sync, restart (announce), `check_mission.sh`, `check_ask.sh`, `check_analytics.sh` → green.

- [ ] **Step 5: Commit**

```bash
git -C plugins/testu add code/tech/genailabs/tutor/TestUAnalyticsModule.java html/services/testu/analytics/coach.* html/services/testu/analytics/coachaction.* tools/check_mission.sh
git -C plugins/testu commit -m "feat(testu): manager coach suggestions and approve-to-act actions"
```

### Task 8: Tutor chat actions (testu bean + finder skill + template)

**Files:**
- Modify: `TestULearningModule.java` (public `List<JSONObject> actionsFor(MediaArchive archive, String userid)`)
- Modify: `plugins/testu/html/ai/default/calls/chat_tutor_usercomment.json` (schema + prompt block)
- Modify: `plugins/finder/code/org/entermediadb/ai/skills/AdaptiveTutorialUserCommentSkill.java` (before `callStructure` :238 and after the follow-up append :292)
- Test: `tools/check_mission.sh` (append), `tools/check_tutor.sh` (still green)

**Interfaces:**
- Consumes: `missionFor`.
- Produces: `actionsFor(archive, userid)` → mission `actions` minus `remind_later`'s `options` (each `{id, type, topic, mode?, section?}`). Line format appended to the message: `[[do <type> topic=<id>[ mode=<m>][ section=<id>]]]`.

- [ ] **Step 1: Failing check** (append): ask the tutor through the same chat call `check_tutor.sh` uses, as the mission learner, with a question about the goal topic; assert the stored reply message either has no `[[do` or only types from `{start_session, book_evaluation, schedule_certification, remind_later}` with `topic=` equal to the goal topic. Second assertion: the `[[do` lines appear after the `>>` follow-ups and at most 2.

- [ ] **Step 2: Run** → FAIL only if the skill emits garbage; before the change it passes vacuously, so also assert `"[[do" in reply` for this learner (the LLM is told to pick ≥ 1 when the learner asks "¿qué hago ahora?"; use that exact question).

- [ ] **Step 3: Implement**

Template, inside the prompt (next to the rules about follow-ups):
```
#if($offeredactions)
## Acciones disponibles (elige como máximo 2 ids que ayuden al alumno ahora, o ninguna):
$offeredactions
#end
```
Schema: `"actions": { "type": "array", "items": { "type": "string" } }` in `properties` (not required).

Finder skill, before `callStructure`:
```java
			// Mission agent (testu): server-validated actions the tutor may attach. Absent bean = behaviour as before.
			java.util.List<?> offered = null;
			Object provider = null;
			try { provider = getMediaArchive().getModuleManager().getBean("TestULearningModule"); } catch (Exception e) { provider = null; }
			if (provider != null && userid != null)
			{
				try
				{
					offered = (java.util.List<?>) provider.getClass().getMethod("actionsFor", org.entermediadb.asset.MediaArchive.class, String.class).invoke(provider, getMediaArchive(), userid);
				}
				catch (Exception e)
				{
					log.warn("mission actions unavailable", e);
				}
			}
			StringBuilder offer = new StringBuilder();
			java.util.Map<String, java.util.Map<?, ?>> byId = new java.util.HashMap<>();
			if (offered != null)
			{
				for (Object o : offered)
				{
					java.util.Map<?, ?> a = (java.util.Map<?, ?>) o;
					byId.put(String.valueOf(a.get("id")), a);
					offer.append(a.get("id")).append(": ").append(a.get("type")).append(" · ").append(a.get("topic")).append("\n");
				}
			}
			tutorMessageContext.putContextValue("offeredactions", offer.length() == 0 ? null : offer.toString());
```
(`userid`: the skill's existing user id for the message; find how it already reads the learner — `tutorMessageContext` user / `inProfile.getUserId()` :1044 — and use that. Reflection keeps finder free of a compile-time testu dependency.)

After the follow-up append (:292):
```java
			Object picked = structured.get("actions");
			if (!end && picked instanceof java.util.List && !byId.isEmpty())
			{
				int n = 0;
				for (Object id : (java.util.List<?>) picked)
				{
					java.util.Map<?, ?> a = byId.get(String.valueOf(id));
					if (a == null || n == 2)
						continue; // never an action the server did not offer
					message = message + "\n[[do " + a.get("type") + " topic=" + a.get("topic") + (a.get("mode") == null ? "" : " mode=" + a.get("mode")) + (a.get("section") == null ? "" : " section=" + a.get("section")) + "]]";
					n++;
				}
			}
```

testu bean method:
```java
	public java.util.List<JSONObject> actionsFor(MediaArchive archive, String userid)
	{
		Data u = (Data) archive.getSearcher("user").searchById(userid);
		if (u == null)
			return java.util.List.of();
		LearningEngine engine = new LearningEngine(archive);
		LearningEngine.Content content = engine.loadContent(); // ponytail: org-wide visibility; the IRIS tab is org-wide anyway
		LearningEngine.Learner l = engine.loadLearner(userid, LearningEngine.jobrolesOf(u), LearningEngine.primaryJobroleOf(u));
		engine.applyProfiles(content, l);
		JSONArray a = (JSONArray) missionFor(engine, content, l, new Date(), (ZoneId) engine.orgZone()[0]).get("actions");
		java.util.List<JSONObject> out = new java.util.ArrayList<>();
		for (Object o : a)
			out.add((JSONObject) o);
		return out;
	}
```

- [ ] **Step 4: Run** compile, sync, restart (announce), `check_tutor.sh`, `check_mission.sh` → green. Ensure finder is on `main` (`git -C plugins/finder branch --show-current`).

- [ ] **Step 5: Commit** (two repos)

```bash
git -C plugins/testu add code/tech/genailabs/tutor/TestULearningModule.java html/ai/default/calls/chat_tutor_usercomment.json tools/check_mission.sh
git -C plugins/testu commit -m "feat(testu): tutor may attach server-offered mission actions"
git -C plugins/finder add code/org/entermediadb/ai/skills/AdaptiveTutorialUserCommentSkill.java
git -C plugins/finder commit -m "feat(tutor): append server-validated [[do]] action lines (TestU mission agent)"
```

---

## Phase 2 — Learner app (re-check against the approved prototype first)

### Task 9: Client models and `[[do]]` parsing

**Files:**
- Modify: `app-genailabs/lib/testu/testu_learn.dart` (after `postScheduleCertification` :769)
- Modify: `app-genailabs/lib/testu/testu_live.dart` (`Cite` :1305, regexes :1352, `splitCite` :1372)
- Test: `app-genailabs/test/testu_cite_test.dart`, create `test/testu_mission_test.dart`

**Interfaces:**
- Produces:

```dart
class MissionAction {
  MissionAction.fromJson(Map<String, dynamic> j)
      : type = _str(j['type']) ?? '', topic = _str(j['topic']) ?? '', mode = _str(j['mode']), section = _str(j['section']);
  const MissionAction(this.type, this.topic, {this.mode, this.section});
  final String type, topic;
  final String? mode, section;
}
class MissionState { status, goal (MissionGoal?), sessionsNeeded, sessionsDone, otherGoals, actions, announce (String? status, String? topic) }
class MissionGoal { topic, topicTitle, status, requiredLevel, band, masteryPercent, gap, deadline, deadlineSource, daysLeft }
// MissionState also has plans : List<MissionGoal> (every required topic)
Future<MissionState> fetchMission(EmeHttp http, {bool ack = false})
Future<void> postRemind(EmeHttp http, {required String topic, required String when})
// testu_live.dart
Cite.actions : List<MissionAction>
```

- [ ] **Step 1: Failing tests**

`test/testu_cite_test.dart` add:
```dart
  test('splitCite lifts [[do]] lines into actions and strips them', () {
    final c = splitCite('Vas bien con Fatiga.\n\n>> ¿Repasamos?\n'
        '[[do start_session topic=fatiga mode=learn section=s1]]\n'
        '[[do remind_later topic=fatiga]]');
    expect(c.text, 'Vas bien con Fatiga.');
    expect(c.followups, ['¿Repasamos?']);
    expect(c.actions.map((a) => a.type), ['start_session', 'remind_later']);
    expect(c.actions.first.mode, 'learn');
    expect(c.actions.first.section, 's1');
  });

  test('splitCite ignores a malformed [[do]] line', () {
    final c = splitCite('Hola.\n[[do]]');
    expect(c.actions, isEmpty);
    expect(c.text, 'Hola.');
  });
```
`test/testu_mission_test.dart`:
```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:genai_labs/testu/testu_learn.dart';

void main() {
  test('MissionState parses a ready goal', () {
    final m = MissionState.fromJson({
      'status': 'ready',
      'goal': {'topic': 'fatiga', 'topictitle': 'Fatiga', 'requiredlevel': 'competent', 'band': 'competent', 'masterypercent': 72, 'gap': 0, 'deadline': '2026-11-14', 'deadlinesource': 'profile', 'daysleft': 40},
      'week': {'sessionsneeded': 1, 'sessionsdone': 0},
      'othergoals': 2,
      'actions': [{'id': 'a1', 'type': 'book_evaluation', 'topic': 'fatiga'}],
      'announce': {'status': 'ready', 'topic': 'fatiga'},
    });
    expect(m.goal!.topicTitle, 'Fatiga');
    expect(m.actions.single.type, 'book_evaluation');
    expect(m.announce?.status, 'ready');
    expect(m.otherGoals, 2);
  });

  test('MissionState no_goal has no goal and no actions', () {
    final m = MissionState.fromJson({'status': 'no_goal', 'goal': null, 'actions': []});
    expect(m.goal, isNull);
    expect(m.actions, isEmpty);
  });
}
```

- [ ] **Step 2: Run** `flutter test test/testu_cite_test.dart test/testu_mission_test.dart` → FAIL (undefined).

- [ ] **Step 3: Implement**

`testu_live.dart`:
```dart
// `[[do type k=v …]]` lines: server-written mission actions (finder skill, spec 2026-10-05).
final _doRe = RegExp(r'^\s*\[\[do ([a-z_]+)((?: [a-z]+=[^\s\]]+)*)\]\]\s*$', multiLine: true);
final _doAnyRe = RegExp(r'^\s*\[\[do\b[^\]]*\]\]\s*$', multiLine: true);

List<MissionAction> _actions(String reply) => [
      for (final m in _doRe.allMatches(reply))
        () {
          final kv = {for (final p in m[2]!.trim().split(' ').where((p) => p.contains('='))) p.split('=')[0]: p.split('=')[1]};
          return MissionAction(m[1]!, kv['topic'] ?? '', mode: kv['mode'], section: kv['section']);
        }(),
    ].where((a) => a.topic.isNotEmpty).toList();
```
In `splitCite`, first lines become:
```dart
  final actions = _actions(reply);
  reply = reply.replaceAll(_doAnyRe, '').trim();
  // Follow-ups come off first: to _quoteRe a `>> …` line is a quote.
```
and both `Cite(...)` returns pass `actions: actions`. `Cite` gains `this.actions = const []` and `final List<MissionAction> actions;` (import `testu_learn.dart` for `MissionAction`; if that creates an import cycle, move `MissionAction` to `testu_actions.dart` from Task 10 and import it from both).

`testu_learn.dart`: `MissionState.fromJson`, `MissionGoal.fromJson` using the file's `_str/_int/_bool` helpers; `fetchMission` = `_call(() => http.getJson('$_base/mission.json', query: ack ? {'ack': '1'} : null))`; `postRemind` = `_call(() => http.postForm('$_base/remind.json', [MapEntry('topic', topic), MapEntry('when', when)]))`.

- [ ] **Step 4: Run** the two tests + `flutter test test/testu_chat_markdown_test.dart test/testu_client_test.dart` → PASS.

- [ ] **Step 5: Commit** (app repo)

```bash
git -C ../../app-genailabs add lib/testu/testu_learn.dart lib/testu/testu_live.dart test/testu_cite_test.dart test/testu_mission_test.dart
git -C ../../app-genailabs commit -m "feat(testu): mission client models and [[do]] action parsing"
```

### Task 10: Action buttons in chat + proactive announce

**Files:**
- Create: `app-genailabs/lib/testu/testu_actions.dart`
- Modify: `lib/testu/testu_sully.dart` (`SullyMessage` :150, render under the source block), `lib/testu/testu_tutor.dart` (where live replies become `SullyMessage`, and screen init)
- Test: `test/testu_mission_test.dart` (widget tests with `FakeEmeHttp`)

**Interfaces:**
- Consumes: `MissionAction`, `fetchMission`, `postRemind`, `postScheduleCertification`, `postStartEvaluation` (existing :739), the existing session launcher used by Today's Continue hero (find its call in `_ContinueHero`; reuse it, do not duplicate).
- Produces:

```dart
/// Labels per action type (Spanish copy via L). The tutor never writes button text.
String actionLabel(MissionAction a, String topicTitle);
/// Runs [a]; on a 409/403 shows "Ya no disponible" and returns false.
Future<bool> runMissionAction(BuildContext context, MissionAction a, {required String topicTitle});
class ActionButtons extends StatelessWidget { const ActionButtons({required this.actions, required this.topicTitles}); }
/// The proactive turn's text for an announce status (fixed template, no LLM).
String announceText(String status, String topicTitle, String tutorName);
```

- [ ] **Step 1: Failing widget tests** (append to `testu_mission_test.dart`):
  - `ActionButtons` with `[start_session, remind_later]` renders "Start 6-min session" and "Remind me later"; tapping "Remind me later" opens a sheet with "In 2 hours / Tonight / Tomorrow"; choosing "Tomorrow" posts `remind.json` with `when=tomorrow` (assert on `FakeEmeHttp` recorded calls).
  - `runMissionAction` for `book_evaluation` when the fake returns 409 shows the snackbar "No longer available".
  - `announceText('ready', 'Fatiga', 'IRIS')` == `"You're ready for the Fatiga evaluation."` (English side of `L`).

- [ ] **Step 2: Run** → FAIL.

- [ ] **Step 3: Implement** `testu_actions.dart` with labels (en/es):
  - `start_session` → "Start 6-min session" / "Empezar sesión de 6 min"
  - `book_evaluation` → "Book the evaluation" / "Reservar evaluación"
  - `schedule_certification` → "Schedule renewal" / "Programar renovación" (opens `showTestuCertScheduleSheet`)
  - `remind_later` → "Remind me later" / "Recordármelo más tarde" (sheet: "En 2 horas" `2h`, "Esta noche" `tonight`, "Mañana" `tomorrow`)
  Buttons styled exactly like `TestuSourceBlock`'s button (reuse its style constants). `SullyMessage` gains `this.actions = const []` and renders `ActionButtons` below the source block and above follow-up chips. In `testu_tutor.dart`, pass `cite.actions` wherever `cite.followups` is passed; on screen init (live only) call `fetchMission()`, and when `announce != null` insert a local tutor turn `announceText(...)` with `actions` from the mission, then `fetchMission(ack: true)`.

- [ ] **Step 4: Run** `flutter test` (whole suite) → PASS.

- [ ] **Step 5: Commit**

```bash
git -C ../../app-genailabs add lib/testu/testu_actions.dart lib/testu/testu_sully.dart lib/testu/testu_tutor.dart test/testu_mission_test.dart
git -C ../../app-genailabs commit -m "feat(testu): tutor action buttons and proactive mission turn"
```

### Task 11: Mission card on Today, Topics deadlines, notification routing

**Files:**
- Modify: `lib/testu/testu_shell.dart` (`_cards` :409, new `_MissionCard` beside `_CertificationCard` :632), `lib/testu/testu_topics.dart`, `lib/testu/testu_notifications.dart` (:40 routing)
- Modify: `lib/testu/testu_learn.dart` (`MissionState.plans : List<MissionGoal>` with `status`)
- Test: `test/testu_mission_test.dart`

Note: per-topic deadlines come from `mission.json` `plans` (Task 3 emits them); parse them into `MissionState.plans` here.

**Interfaces:**
- Consumes: `MissionState` (incl. `plans`), `ActionButtons`, `runMissionAction`.
- Produces: `_MissionCard(mission)`; Topics row trailing text `deadlineLine(plan)` → "Due 14 Nov" / "Vence 14 nov".

- [ ] **Step 1: Failing widget tests:**
  - Today in live mode with a fake `mission.json` `at_risk` → finds "At risk — 3 sessions this week", "Beginner · 52% → Competent", "This week: 1 of 3 sessions", primary button "Start 6-min session".
  - `no_goal` → no `_MissionCard` (find by key `Key('mission-card')` → nothing).
  - Goal `deadlinesource: certification` → `_CertificationCard` not shown on Today.
  - Notification of type `mission` tapped → Today tab selected.
  - Topics with a plan deadline 2026-11-14 → row shows "Due 14 Nov".

- [ ] **Step 2: Run** → FAIL.

- [ ] **Step 3: Implement** per spec §Learner app and the approved prototype. Status labels (en/es): ready "Ready to take the evaluation"/"Listo para evaluar"; on_track "On track"/"En camino"; at_risk "At risk — N sessions this week"/"En riesgo — N sesiones esta semana"; overdue "Overdue"/"Vencido"; pace "At your pace"/"A tu ritmo". Band names reuse the app's existing band label helper. Card first in `_cards` live branch; replaces `_certCard` when `goal.deadlineSource == 'certification'`. Reuse `_CardTitle` / `_CardBody`.

- [ ] **Step 4: Run** `flutter test` → PASS.

- [ ] **Step 5: Commit**

```bash
git -C ../../app-genailabs add lib/testu/testu_shell.dart lib/testu/testu_topics.dart lib/testu/testu_notifications.dart lib/testu/testu_learn.dart test/testu_mission_test.dart
git -C ../../app-genailabs commit -m "feat(testu): mission card on Today, deadlines on Topics, mission notifications"
```

---

## Phase 3 — Admin console (re-check against the approved prototype first)

### Task 12: Coach card on Overview

**Files:**
- Modify: `lib/admin/admin_models.dart`, `lib/admin/admin_api.dart` (after `overview` :90)
- Create: `lib/admin/admin_coach.dart`
- Modify: `lib/admin/admin_overview.dart` (mount at the top)
- Test: create `test/admin_coach_test.dart`

**Interfaces:**
- Produces: `class CoachSuggestion { key, kind, topic, topicTitle, deadline, count, users (List<CoachUser>) }`, `class CoachUser { id, name, status, masteryPercent, daysLeft }`; `Future<List<CoachSuggestion>> coach({String? team})`; `Future<({int done, int skipped})> coachAction(String action, {String? topic, List<String>? users, String? dueDate, String? key})`; `class CoachCard extends StatefulWidget`.

- [ ] **Step 1: Failing tests:** fake `coach.json` with one `at_risk` (4 users) → card shows "4 people at risk in Bloqueo · due 30 Oct" and buttons "Send reminder", "Change date", "See people", "Dismiss"; tapping "Send reminder" posts `coachaction.json` `action=nudge` with the 4 ids and shows "Sent to 3 · 1 skipped"; "Dismiss" removes the row; empty suggestions → card hidden.

- [ ] **Step 2: Run** `flutter test test/admin_coach_test.dart` → FAIL.

- [ ] **Step 3: Implement** per spec §Admin console and prototype. "Change date" opens a dialog with the people list (checkboxes, all on) and a date picker; posts `setdue`. "See people" navigates to the People screen filtered by those ids (reuse the existing people filter route used by `admin_overview` gap links). Title "Sugerencias de <tutor>" from org config.

- [ ] **Step 4: Run** `flutter test` → PASS.

- [ ] **Step 5: Commit**

```bash
git -C ../../app-genailabs add lib/admin/admin_models.dart lib/admin/admin_api.dart lib/admin/admin_coach.dart lib/admin/admin_overview.dart test/admin_coach_test.dart
git -C ../../app-genailabs commit -m "feat(admin): coach suggestions card with approve-to-act actions"
```

### Task 13: Profile deadline column, person due date

**Files:**
- Modify: `lib/admin/admin_profiles.dart`, `lib/admin/admin_person.dart`, `lib/admin/admin_models.dart`, `lib/admin/admin_api.dart`
- Test: `test/admin_profiles_test.dart`, `test/admin_person_test.dart`

**Interfaces:**
- Produces: `ProfileRow.withinDays : int?` (round-trips through `saveProfile`); `PersonTopic.dueDate : String?`, `dueSource : String?` (from `person.json` — add `duedate`, `duesource` per required topic in `loadPerson` server-side, small change in `TestUAnalyticsModule`; include it in this task's server commit).

- [ ] **Step 1: Failing tests:** profile editor shows "Deadline (days)" column, editing to 30 posts `rows[0].withindays = 30`; blank posts null; invalid "abc" shows the existing numeric field error. Person page shows "Due 14 Nov · from profile" and an edit action posting `coachaction.json action=setdue`.

- [ ] **Step 2: Run** → FAIL.

- [ ] **Step 3: Implement** — same numeric-column pattern the certification columns (`validitymonths`) use in `admin_profiles.dart`; person page row trailing text + edit icon → date picker → `coachAction('setdue', …)`.

- [ ] **Step 4: Run** `flutter test`; `check_analytics.sh` → green.

- [ ] **Step 5: Commit** (app + testu)

```bash
git -C ../../app-genailabs add lib/admin/admin_profiles.dart lib/admin/admin_person.dart lib/admin/admin_models.dart lib/admin/admin_api.dart test/admin_profiles_test.dart test/admin_person_test.dart
git -C ../../app-genailabs commit -m "feat(admin): profile deadline days and per-person due dates"
git -C plugins/testu add code/tech/genailabs/tutor/TestUAnalyticsModule.java
git -C plugins/testu commit -m "feat(testu): person.json carries due date and source per required topic"
```

### Task 14: Ask-IRIS action buttons

**Files:**
- Modify: `plugins/testu/code/tech/genailabs/tutor/TestUAnalyticsModule.java` (`askAnalytics`: let the answer schema return `actions: [suggestion keys]`, append `[[do nudge key=<key>]]` / `[[do setdue key=<key>]]` only for keys present in `facts.coach`)
- Modify: `plugins/testu/html/ai/default/calls/` ask template (the one `askAnalytics` calls; find by `callStructure` name at :2153)
- Modify: `lib/admin/admin_iris.dart` (parse `[[do …]]` with the same regex shape as Task 9, render buttons; tap → confirm dialog → `coachAction`)
- Test: `test/admin_iris_test.dart`, `tools/check_ask.sh`

- [ ] **Step 1: Failing tests:** an Ask reply containing `[[do nudge key=at_risk:bloqueo:all]]` renders "Send reminder" and, after confirm, posts `coachaction.json` with that suggestion's users (looked up from a fake `coach.json`); text never shows the raw `[[do` line. Server: an invented key in the LLM output is dropped (`check_ask.sh` assertion with a stubbed answer if the script supports it; otherwise assert no `[[do` with unknown keys over 3 runs).

- [ ] **Step 2: Run** → FAIL.

- [ ] **Step 3: Implement** as described; buttons reuse `CoachCard`'s action widgets.

- [ ] **Step 4: Run** `flutter test`, `check_ask.sh` → green.

- [ ] **Step 5: Commit** (both repos, as Task 13).

---

## Phase 4 — Ship prep

### Task 15: Bundles, sync, docs

- [ ] **Step 1:** `bin/sync-testu.sh --check` → no drift (add any new event/script paths to `MAP` if missing; `html/services/testu/learn` and `analytics/{coach,coachaction}` — add the two analytics entries explicitly like `forecast`).
- [ ] **Step 2:** Rebuild learner and console web bundles (`app-genailabs` `build_learn.sh`, `build_admin.sh`), commit `webapp/site/{learn,admin}` in `eme-server-minsur` (memory: no CI, committed bundles).
- [ ] **Step 3:** Full regression: `check_learning.sh`, `check_profiles.sh`, `check_evaluation.sh`, `check_certification.sh`, `check_mission.sh`, `check_tutor.sh`, `check_ask.sh`, `flutter test`.
- [ ] **Step 4:** Rollout notes in the spec §Rollout confirmed: field XML + reindex list, finder main merge, no new permissions.
- [ ] **Step 5:** Update Notion Project Hub (PRD sub-project list + Dev status) per memory `feedback-keep-notion-updated`; never formula/rollup fields.
- [ ] **Step 6:** Commit sync/bundle changes in `eme-server-minsur`; report to Diego with the push list (testu, catalog, finder, app, site). Do not push.
