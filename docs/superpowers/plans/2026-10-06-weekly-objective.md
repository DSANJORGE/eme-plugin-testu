# Weekly Objective Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the mission card into "Tu objetivo de la semana": a day counts only after 5 answers on the goal topic, the card shows reminder / unfinished-session / week-done states, every mission push is tagged and logged so the console can show push effectiveness, and the Friday emails carry the objective.

**Architecture:** The pure `MissionPlanner` gains the 5-answer day rule, `todayanswers`/`todaycounted`, the unfinished-push decision and the `missionpush` row id. Thin endpoint changes in `TestULearningModule` (`mission.json` `reminder`, `remind.json when=cancel`, nudge job), one shared `pushMission` helper that pushes and logs a `missionpush` row, a pure `missionPushes` counter in `TestUAnalyticsModule` wired into `engagement.json`, and two new blocks in `WeeklySummaryEmail`. The Flutter learner app re-renders the Today card from mission.json and reports push taps as a `push_open` usage event; the console adds one card.

**Tech Stack:** Java 17 (eMe modules, `org.json.simple`), Elasticsearch searchers via `MediaArchive`, field XML (catalog plugin + site mirror), Flutter 3 (hand-formatted Dart, `FakeEmeHttp` tests), python live checks.

**Spec:** `/Users/DSANJORGE/Code/EMEGenAILabs/.worktrees/testu-weekly/docs/superpowers/specs/2026-10-05-mission-agent-design.md` — the section **"Amendment 2026-10-06: weekly objective"** is binding; read the whole spec first. UI reference: the approved prototype `/private/tmp/claude-501/-Users-DSANJORGE-Code-EMEGenAILabs/09ac33b3-5de0-4ac1-a0e4-e8f8b25fc0ca/scratchpad/weekly-proto/src.html` (Spanish copy, layout). Where prototype and amendment differ, the amendment wins (see "Decisions" at the end).

## Global Constraints

- Repos and where work happens:
  - Server plugin worktree, branch `feat/weekly-objective`: `/Users/DSANJORGE/Code/EMEGenAILabs/.worktrees/testu-weekly` (= `plugins/testu`; Java in `code/tech/genailabs/tutor/`, endpoints `html/services/testu/...`, checks `tools/`).
  - App worktree, branch `feat/weekly-objective`: `/Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs/.worktrees/weekly` (`lib/testu`, `lib/admin`, `test/`).
  - Field XML: catalog plugin main checkout `/Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/catalog/html/data/fields/` and the site mirror `/Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/webapp/WEB-INF/data/site/catalog/fields/` (both on `main`, no worktree).
- Peers have uncommitted edits in the site and catalog main checkouts and share the local Tomcat: **exact-string edits only; stage files by explicit path; never `git add -A` / `git add .` / `git commit -a`.** Announce before any Tomcat restart (memory `concurrent-sessions-shared-repos`).
- Never push. Deploys run from OpenCode on the servers; we stop at commit (memory `deploys-via-opencode`).
- Every commit message ends with the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Never run `dart format`** (hand-formatted files; the formatter rewrites whole files). Match the surrounding hand formatting.
- Product copy through `L('English', 'Español')`; tests assert the English side (`flutter_test_config.dart` pins `en`). Spanish copy matches the prototype. Server email/push copy: Spanish (push) or `en ? … : …` (email), as the surrounding code does.
- Tutor name comes from client/org config (`tutorName(archive)` server-side, persona on the app); never hardcode "IRIS"/"Sully".
- Tutor chats are anonymous: analytics are counts only — no names, no chat content in any new output (engagement `missionpushes`, admin email block).
- Field datatypes: ints that must stay blank are `datatype="long"`, never `number`; instants are `type="date"` written as `Date`; day values via `LearningEngine.parseYmd`/`ymd`. A field id must be mapped the same way in every table (CLAUDE.md §6): `kind` and `notification` are new everywhere and declared identically (`keyword="true" indextype="not_analyzed"`); `sentat` stays `type="date"` like its other uses.
- Spec ambiguity resolved as written in the amendment: a day counts at `DAY_MIN_ANSWERS = 5`; "unfinished" = `todayanswers` 1..4 (out of 5), **not** "of 10" (the prototype's "4 de 10" is superseded).
- Pure server checks run with `sh /private/tmp/claude-501/-Users-DSANJORGE-Code-EMEGenAILabs/09ac33b3-5de0-4ac1-a0e4-e8f8b25fc0ca/scratchpad/wcheck.sh <CheckName ...>` (compiles this worktree into a scratch build, runs `tools/<CheckName>.java`). A compile error aborts it with javac output. Known baseline: `DailyChallengeEmailCheck` prints 5 avatar/multipart FAILs under `wcheck.sh` (it looks for `webapp/...iris.png` relative to the cwd); that check is not touched by this plan.
- Live checks (`tools/check_mission.sh`) run against the local Tomcat, which serves the **main** `plugins/testu` checkout, not this worktree. Tasks 2, 4, 5 and 6 add live assertions but do **not** run them; Task 10 runs them after the controller merges the branches.
- With no deadlines/targets/certifications behaviour stays as today (mission `no_goal` → Continue hero).

---

## File map

Server (`/Users/DSANJORGE/Code/EMEGenAILabs/.worktrees/testu-weekly`):
- `code/tech/genailabs/tutor/MissionPlanner.java` — `DAY_MIN_ANSWERS`, `UNFINISHED_HOUR`; `week()` day rule + `todayanswers`/`todaycounted`; `topicPlan` adds `requiredmin`; `unfinishedDue`, `unfinishedText`, `pushRowId`.
- `code/tech/genailabs/tutor/TestULearningModule.java` — `mission()` `reminder`; `remind()` `when=cancel`; `missionNudges` kind + unfinished push; `pushMission`.
- `code/tech/genailabs/tutor/TestUSocialModule.java` — push data payload gets `kind`.
- `code/tech/genailabs/tutor/TestUAnalyticsModule.java` — coach nudge `kind=coach` via `pushMission`; `missionPushes` (pure) + `engagement()` wiring; `cachedCoachSuggestions`, `coachSuggestionsFor`.
- `code/tech/genailabs/tutor/TestUUsageModule.java` — usage type `push_open`, extra field `notification`.
- `code/tech/genailabs/tutor/WeeklySummaryEmail.java` — learner `TU OBJETIVO DE LA SEMANA`, admin `OBJETIVOS CON PLAZO`.
- `tools/MissionPlannerCheck.java` — week, unfinished, push-id and push-effectiveness cases.
- `tools/WeeklySummaryEmailCheck.java` — objective blocks; `tools/check_learning.sh` runs it.
- `tools/check_mission.sh` — live assertions (verified in Task 10).

Field XML (`/Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur`):
- `plugins/catalog/html/data/fields/missionpush.xml` — new table.
- `plugins/catalog/html/data/fields/learnernotification.xml` + `webapp/WEB-INF/data/site/catalog/fields/learnernotification.xml` — `kind`.
- `plugins/catalog/html/data/fields/usageevent.xml` + `webapp/WEB-INF/data/site/catalog/fields/usageevent.xml` — `notification`.

App (`/Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs/.worktrees/weekly`):
- `lib/testu/testu_learn.dart` — `MissionState.todayAnswers/todayCounted/reminder`, `MissionGoal.requiredMin`, `MissionReminder`, `postRemind` returns remindat, `cancelRemind`.
- `lib/testu/testu_dates.dart` — `testuRemindWhen`.
- `lib/testu/testu_usage.dart` — `pushOpen`; `lib/testu/testu_push.dart` — calls it.
- `lib/testu/testu_live.dart` — `liveTopics()`; `lib/testu/testu_topics.dart` — `liveTopicCover`.
- `lib/testu/testu_actions.dart` — `ActionButtons.labelOf`, `onDone`.
- `lib/testu/testu_shell.dart` — the Today card redesign.
- `lib/admin/admin_engagement.dart` — `MissionPushes`, "Recordatorios del objetivo" card.
- Tests: `test/testu_mission_test.dart`, `test/testu_dates_test.dart`, `test/testu_usage_test.dart`, `test/admin_engagement_test.dart`.

---

## Phase 1 — Server

### Task 1: Planner — 5-answer day rule, today's answers, required minimum

**Files:**
- Modify: `code/tech/genailabs/tutor/MissionPlanner.java:26` (constants), `:105` (topicPlan output), `:303-320` (`week()` day loop)
- Test: `tools/MissionPlannerCheck.java` (new `weekChecks()`, called from `main` after `missionChecks();` at `:50`)

**Interfaces:**
- Produces: `MissionPlanner.DAY_MIN_ANSWERS = 5` (public static final int); mission.json `week` = `{sessionsneeded, sessionsdone, todayanswers (int), todaycounted (bool)}`; each plan/goal gains `requiredmin` (int, the required band's minimum percent). Later tasks read `week.todayanswers` (Task 4, app Task 7) and `goal.requiredmin` (app Task 7).

- [ ] **Step 1: Write the failing check.** In `tools/MissionPlannerCheck.java` add `weekChecks();` on its own line right after `missionChecks();` in `main`, and add these methods before `static JSONObject action(`:

```java
	// Amendment 2026-10-06: a day counts only from DAY_MIN_ANSWERS learning answers on the goal topic; week.todayanswers/todaycounted.
	// NOW = 19:00 Sun 4 Oct Lima, so this week runs Mon 28 Sep .. Sun 4 Oct.
	static void weekChecks()
	{
		Content c = new Content();
		Topic a = topic("a", 60, 85);
		c.topics.put("a", a);
		Learner l = learner(); target(l, "a", "2026-10-20");
		attempts(l, "a-q1", "learn", "2026-09-29T15:00:00Z", 5);       // Tue: 5 -> counts
		attempts(l, "a-q1", "improve", "2026-09-30T15:00:00Z", 4);     // Wed: 4 -> does not
		attempts(l, "a-q1", "learn", "2026-09-27T15:00:00Z", 5);       // Sun 27 Sep: last week
		attempts(l, "a-q1", "learn", "2026-10-04T20:00:00Z", 3);       // today, 15:00 Lima: 3
		attempts(l, "a-q1", "evaluation", "2026-10-04T21:00:00Z", 2);  // not a learning mode
		Map<String, JSONObject> states = new LinkedHashMap<>();
		states.put("a", state("a", "competent", false, 30, "beginner", false));
		JSONObject m = MissionPlanner.mission(c, l, states, tid -> null, NOW, LIMA);
		JSONObject w = (JSONObject) m.get("week");
		ok("week: only the 5-answer day counts", Integer.valueOf(1).equals(w.get("sessionsdone")), w);
		ok("week: todayanswers = today's learning answers on the goal topic", Integer.valueOf(3).equals(w.get("todayanswers")), w);
		ok("week: todaycounted false below 5", Boolean.FALSE.equals(w.get("todaycounted")), w);
		ok("goal carries requiredmin", Integer.valueOf(60).equals(((JSONObject) m.get("goal")).get("requiredmin")), m.get("goal"));
		attempts(l, "a-q1", "dailychallenge", "2026-10-04T22:00:00Z", 2);
		w = (JSONObject) MissionPlanner.mission(c, l, states, tid -> null, NOW, LIMA).get("week");
		ok("week: today reaches 5 -> counted", Integer.valueOf(2).equals(w.get("sessionsdone")) && Boolean.TRUE.equals(w.get("todaycounted")), w);
	}

	static void attempts(Learner l, String q, String mode, String iso, int n)
	{
		for (int i = 0; i < n; i++)
		{
			Attempt at = new Attempt(); at.questionid = q; at.mode = mode; at.at = Date.from(java.time.Instant.parse(iso).plusSeconds(60L * i));
			l.attempts.add(at);
		}
	}
```

- [ ] **Step 2: Run it to make sure it fails.**

Run: `sh /private/tmp/claude-501/-Users-DSANJORGE-Code-EMEGenAILabs/09ac33b3-5de0-4ac1-a0e4-e8f8b25fc0ca/scratchpad/wcheck.sh MissionPlannerCheck`
Expected: `FAIL: week: only the 5-answer day counts` (today's rule counts 3 days), `FAIL: week: todayanswers …`, `FAIL: goal carries requiredmin`, last line `N FAILED`.

- [ ] **Step 3: Implement.** In `MissionPlanner.java`:

Replace line 26
```java
	public static final int GAIN_PER_SESSION = 4, PACE_SESSIONS = 3, MAX_SESSIONS = 5, HISTORY_DAYS = 28;
```
with
```java
	public static final int GAIN_PER_SESSION = 4, PACE_SESSIONS = 3, MAX_SESSIONS = 5, HISTORY_DAYS = 28;
	/** Amendment 2026-10-06: a day counts toward the week from this many learning answers on the goal topic; the unfinished-session
	 *  push goes out from this learner-local hour. */
	public static final int DAY_MIN_ANSWERS = 5, UNFINISHED_HOUR = 18;
```

In `topicPlan`, replace
```java
		o.put("canstart", canstart);
		return o;
```
with
```java
		o.put("canstart", canstart);
		o.put("requiredmin", requiredMin(s)); // the card's level-bar marker (amendment 2026-10-06); gap alone is clamped at 0
		return o;
```

In `week()`, replace
```java
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
```
with
```java
		LocalDate today = now.toInstant().atZone(z).toLocalDate();
		LocalDate monday = today.with(java.time.DayOfWeek.MONDAY);
		java.util.Set<String> ids = LearningEngine.ids(t.questions);
		Map<LocalDate, Integer> perDay = new java.util.HashMap<>();
		for (Attempt a : l.attempts)
		{
			if (a.at != null && ids.contains(a.questionid) && LearningEngine.isLearningMode(a.mode))
			{
				LocalDate d = a.at.toInstant().atZone(z).toLocalDate();
				if (!d.isBefore(monday))
					perDay.merge(d, 1, Integer::sum);
			}
		}
		int counted = 0;
		for (int n : perDay.values())
			if (n >= DAY_MIN_ANSWERS)
				counted++;
		int todayAnswers = perDay.getOrDefault(today, 0);
		JSONObject w = new JSONObject();
		w.put("sessionsneeded", needed);
		w.put("sessionsdone", Math.min(counted, needed)); // amendment 2026-10-06: a day counts from DAY_MIN_ANSWERS answers
		w.put("todayanswers", todayAnswers);
		w.put("todaycounted", todayAnswers >= DAY_MIN_ANSWERS);
		return w;
```
(`Map` is already imported.)

- [ ] **Step 4: Run the check.**

Run: `sh /private/tmp/claude-501/-Users-DSANJORGE-Code-EMEGenAILabs/09ac33b3-5de0-4ac1-a0e4-e8f8b25fc0ca/scratchpad/wcheck.sh MissionPlannerCheck`
Expected: every line `ok: …`, last line `MissionPlannerCheck: all ok`.

- [ ] **Step 5: Commit.**

```bash
git -C /Users/DSANJORGE/Code/EMEGenAILabs/.worktrees/testu-weekly add code/tech/genailabs/tutor/MissionPlanner.java tools/MissionPlannerCheck.java
git -C /Users/DSANJORGE/Code/EMEGenAILabs/.worktrees/testu-weekly commit -m "feat(testu): weekly objective -- a day counts from 5 answers, today's answers, requiredmin" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 2: Reminder endpoints — `mission.json` reminder, `remind.json when=cancel`

**Files:**
- Modify: `code/tech/genailabs/tutor/TestULearningModule.java:548-576` (`mission`), `:583-622` (`remind`)
- Modify: `tools/check_mission.sh:335-345` (remind section; live, verified in Task 10)

**Interfaces:**
- Produces: mission.json `reminder` = `{remindat: ISO instant, topic}` when `<uid>_mission_remind` exists with `pushedat` null and `remindat` after now, else `null`. `remind.json` POST `when=cancel` (no topic needed) → `200 {ok: true, cancelled: bool}`; any other `when` unchanged (`{ok, remindat}`). App Task 7 reads `reminder`; app Task 7 calls `when=cancel`.

- [ ] **Step 1: Add the live assertions** (they fail against today's server; verified in Task 10). In `tools/check_mission.sh`, replace
```python
    s, r = call(me, "POST", "/services/testu/learn/remind.json", form={"topic": TOPIC, "when": "2h"})
    refresh()
```
with
```python
    s, r = call(me, "POST", "/services/testu/learn/remind.json", form={"topic": TOPIC, "when": "2h"})
    ok("remind returns remindat", s == 200 and r.get("remindat"), r)
    refresh()
```
and replace
```python
    ok("unknown when -> 400", s == 400, r)
```
with
```python
    ok("unknown when -> 400", s == 400, r)
    # amendment 2026-10-06: the pending reminder shows on mission.json and can be undone until it is pushed.
    s, m = call(me, "GET", "/services/testu/learn/mission.json")
    ok("mission.json exposes the pending reminder", m.get("reminder") and m["reminder"]["topic"] == TOPIC and m["reminder"]["remindat"], m.get("reminder"))
    s, r = call(me, "POST", "/services/testu/learn/remind.json", form={"when": "cancel"})
    ok("when=cancel deletes the pending reminder", s == 200 and r.get("ok") and r.get("cancelled") is True, r)
    ok("cancelled reminder row gone", es_doc("learnernotification", f"{UID}_mission_remind") is None)
    s, m = call(me, "GET", "/services/testu/learn/mission.json")
    ok("no reminder after cancel", m.get("reminder") is None, m.get("reminder"))
    s, r = call(me, "POST", "/services/testu/learn/remind.json", form={"when": "cancel"})
    ok("cancel with nothing pending: 200, cancelled false", s == 200 and r.get("cancelled") is False, r)
```

- [ ] **Step 2: Implement `reminder` in `mission()`.** Replace
```java
		m.put("announce", announce);
		m.put("ok", Boolean.TRUE);
		m.put("now", LearningEngine.iso(now));
		reply(inReq, m);
```
with
```java
		m.put("announce", announce);
		// amendment 2026-10-06: the pending "remind me later" (not pushed yet, still ahead), so the card can collapse to one line.
		Data rem = (Data) archive.getSearcher("learnernotification").searchById(user.getId() + "_mission_remind");
		Date remAt = rem == null || rem.get("pushedat") != null ? null : (Date) rem.getValue("remindat");
		JSONObject reminder = null;
		if (remAt != null && remAt.after(now))
		{
			reminder = new JSONObject();
			reminder.put("remindat", LearningEngine.iso(remAt));
			reminder.put("topic", rem.get("entitytopic"));
		}
		m.put("reminder", reminder);
		m.put("ok", Boolean.TRUE);
		m.put("now", LearningEngine.iso(now));
		reply(inReq, m);
```

- [ ] **Step 3: Implement `when=cancel` in `remind()`.** Replace the doc comment line
```java
	/** services/testu/learn/remind.json (POST topic, when -- 2h|tonight|tomorrow) -- "remind me later" for the mission card.
	 *  One pending reminder per learner: a new one replaces it. Delivery (task 6) fills text and pushedat. */
```
with
```java
	/** services/testu/learn/remind.json (POST topic, when -- 2h|tonight|tomorrow) -- "remind me later" for the mission card.
	 *  One pending reminder per learner: a new one replaces it. Delivery (task 6) fills text and pushedat. when=cancel (no topic)
	 *  deletes the pending one unless it was already pushed: {ok, cancelled} (amendment 2026-10-06, the card's Deshacer). */
```
and replace
```java
		MediaArchive archive = getMediaArchive(inReq);
		String topic = param(inReq, "topic");
		Date at = MissionPlanner.remindAt(param(inReq, "when"), new Date(), learnerZone(archive, user.getId()));
```
with
```java
		MediaArchive archive = getMediaArchive(inReq);
		if ("cancel".equals(param(inReq, "when")))
		{
			boolean cancelled = false;
			Searcher cs = archive.getSearcher("learnernotification");
			synchronized (LearningEngine.WRITE_LOCK)
			{
				Data n = (Data) cs.searchById(user.getId() + "_mission_remind");
				if (n != null && n.get("pushedat") == null)
				{
					cs.delete(n, null);
					cancelled = true;
				}
			}
			JSONObject resp = new JSONObject();
			resp.put("ok", Boolean.TRUE);
			resp.put("cancelled", cancelled);
			reply(inReq, resp);
			return;
		}
		String topic = param(inReq, "topic");
		Date at = MissionPlanner.remindAt(param(inReq, "when"), new Date(), learnerZone(archive, user.getId()));
```

- [ ] **Step 4: Compile and run the pure checks** (nothing pure changed; this proves the module compiles).

Run: `sh /private/tmp/claude-501/-Users-DSANJORGE-Code-EMEGenAILabs/09ac33b3-5de0-4ac1-a0e4-e8f8b25fc0ca/scratchpad/wcheck.sh MissionPlannerCheck`
Expected: no javac error, last line `MissionPlannerCheck: all ok`. Also `python3 -c "import ast,sys; src=open('/Users/DSANJORGE/Code/EMEGenAILabs/.worktrees/testu-weekly/tools/check_mission.sh').read(); ast.parse(src.split(\"<<'PY'\n\",1)[1].rsplit('\nPY',1)[0])"` → no output (the python heredoc parses).

- [ ] **Step 5: Commit.**

```bash
git -C /Users/DSANJORGE/Code/EMEGenAILabs/.worktrees/testu-weekly add code/tech/genailabs/tutor/TestULearningModule.java tools/check_mission.sh
git -C /Users/DSANJORGE/Code/EMEGenAILabs/.worktrees/testu-weekly commit -m "feat(testu): mission.json pending reminder, remind.json when=cancel" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 3: Field XML — `learnernotification.kind`, `usageevent.notification`, table `missionpush`

**Files:**
- Create: `/Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/catalog/html/data/fields/missionpush.xml`
- Modify (append before `</properties>`): `plugins/catalog/html/data/fields/learnernotification.xml`, `webapp/WEB-INF/data/site/catalog/fields/learnernotification.xml`, `plugins/catalog/html/data/fields/usageevent.xml`, `webapp/WEB-INF/data/site/catalog/fields/usageevent.xml` (all under `/Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur`; the plugin and site copies are identical today and must stay identical)

**Interfaces:**
- Produces: `learnernotification.kind` (remind | unfinished | ready | at_risk | overdue | coach); `usageevent.notification` (opened notification id); table `missionpush {id, user, kind, entitytopic, sentat, notification}`, id `<notification id>_<yyyyMMddHHmm>`. Tasks 4 and 5 write/read them.

- [ ] **Step 1: Confirm nothing else declares these ids** (CLAUDE.md §6).

Run: `cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && grep -rl 'property id="kind"\|property id="notification"' plugins/*/html/data/fields webapp/WEB-INF/data/*/catalog/fields webapp/WEB-INF/data/system/fields 2>/dev/null`
Expected: no output. (If anything prints, stop and match its definition exactly instead.)

- [ ] **Step 2: Create `plugins/catalog/html/data/fields/missionpush.xml`.**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Weekly objective (spec amendment 2026-10-06): one row per mission push, id <notification id>_<yyyyMMddHHmm> (UTC), so push
     history survives the reused learnernotification rows. kind = remind | unfinished | ready | at_risk | overdue | coach. Written by
     TestULearningModule.pushMission, read by TestUAnalyticsModule.engagement (missionpushes). Counts only, never shown by name. -->
<properties beanname="dataSearcher">
  <property id="id" index="true" stored="true" editable="false" keyword="true"><name><language id="en">ID</language></name></property>
  <property id="user" index="true" stored="true" editable="false" type="list" listid="user"><name><language id="en">User</language><language id="es">Usuario</language></name></property>
  <property id="kind" index="true" stored="true" editable="false" keyword="true" indextype="not_analyzed"><name><language id="en">Push kind</language><language id="es">Tipo de aviso</language></name></property>
  <property id="entitytopic" index="true" stored="true" editable="false" type="list" listid="entitytopic"><name><language id="en">Topic</language><language id="es">Tema</language></name></property>
  <property id="sentat" index="true" stored="true" editable="false" type="date"><name><language id="en">Sent at</language><language id="es">Enviado</language></name></property>
  <property id="notification" index="true" stored="true" editable="false" keyword="true" indextype="not_analyzed"><name><language id="en">Notification</language><language id="es">Notificación</language></name></property>
</properties>
```

- [ ] **Step 3: Append `kind` to both `learnernotification.xml` copies.** In each file replace the last line `</properties>` with:

```xml
  <!-- Weekly objective (amendment 2026-10-06): which mission push this row was (remind | unfinished | ready | at_risk | overdue | coach); also sent in the push data. -->
  <property id="kind" index="true" stored="true" editable="false" keyword="true" indextype="not_analyzed"><name><language id="en">Push kind</language><language id="es">Tipo de aviso</language></name></property>
</properties>
```

- [ ] **Step 4: Append `notification` to both `usageevent.xml` copies.** In each file replace the last line `</properties>` with:

```xml
  <!-- Weekly objective (amendment 2026-10-06): a push_open event carries the opened notification's id. -->
  <property id="notification" index="true" stored="true" editable="false" keyword="true" indextype="not_analyzed"><name><language id="en">Notification</language><language id="es">Notificación</language></name></property>
</properties>
```

- [ ] **Step 5: Verify.**

Run:
```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur
xmllint --noout plugins/catalog/html/data/fields/missionpush.xml plugins/catalog/html/data/fields/learnernotification.xml webapp/WEB-INF/data/site/catalog/fields/learnernotification.xml plugins/catalog/html/data/fields/usageevent.xml webapp/WEB-INF/data/site/catalog/fields/usageevent.xml
diff plugins/catalog/html/data/fields/learnernotification.xml webapp/WEB-INF/data/site/catalog/fields/learnernotification.xml && diff plugins/catalog/html/data/fields/usageevent.xml webapp/WEB-INF/data/site/catalog/fields/usageevent.xml && echo mirrors-identical
```
Expected: no xmllint output, then `mirrors-identical`. (No restart here: the mapping is applied in Task 10.)

- [ ] **Step 6: Commit, each repo by explicit path.**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur
git -C plugins/catalog add html/data/fields/missionpush.xml html/data/fields/learnernotification.xml html/data/fields/usageevent.xml
git -C plugins/catalog commit -m "feat(testu): weekly objective fields (missionpush table, learnernotification.kind, usageevent.notification)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git add webapp/WEB-INF/data/site/catalog/fields/learnernotification.xml webapp/WEB-INF/data/site/catalog/fields/usageevent.xml
git commit -m "chore(testu): mirror weekly objective fields (learnernotification.kind, usageevent.notification)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
Before each commit run `git status --short` / `git -C plugins/catalog status --short` and confirm only these paths are staged (`A`/`M` in the first column).

### Task 4: Nudge kinds, unfinished-session push, `missionpush` log

**Files:**
- Modify: `code/tech/genailabs/tutor/MissionPlanner.java` (after `remindAt`, end of file `:461-472`) — pure helpers
- Modify: `code/tech/genailabs/tutor/TestULearningModule.java:2062-2135` (`missionNudges`), new `pushMission` after `missionText` (`:2138-2149`)
- Modify: `code/tech/genailabs/tutor/TestUSocialModule.java:1541` (push data keys)
- Modify: `code/tech/genailabs/tutor/TestUAnalyticsModule.java:2669` and `:2680` (`coachNudge`)
- Test: `tools/MissionPlannerCheck.java` (new `unfinishedChecks()`); `tools/check_mission.sh` (nudges + coach sections, live, verified in Task 10)

**Interfaces:**
- Consumes: Task 1 `week.todayanswers`, `MissionPlanner.DAY_MIN_ANSWERS`, `UNFINISHED_HOUR`; Task 3 fields.
- Produces:
  - `public static boolean MissionPlanner.unfinishedDue(int todayAnswers, boolean reminderPending, int hour, boolean honorClock)`
  - `public static String MissionPlanner.unfinishedText(int todayAnswers, String topicTitle)`
  - `public static String MissionPlanner.pushRowId(String notificationId, Date sentAt)` → `<id>_<yyyyMMddHHmm>` UTC
  - `void TestULearningModule.pushMission(MediaArchive archive, TestUSocialModule social, Data n, Date now)` (package-visible): FCM push + one `missionpush` row from `n`'s `user`, `kind`, `entitytopic`.
  - Every mission `learnernotification` row now has `kind`; FCM `data.kind` set (empty for non-mission rows). Row id `<uid>_mission_unfinished_<yyyyMMdd>` (learner-zone day).

- [ ] **Step 1: Write the failing check.** In `tools/MissionPlannerCheck.java` add `unfinishedChecks();` after `weekChecks();` in `main`, and the method next to `weekChecks`:

```java
	// Amendment 2026-10-06: the 18:00 unfinished-session push and the missionpush row id.
	static void unfinishedChecks()
	{
		ok("unfinished: 3 answers, no reminder, 18:00 -> due", MissionPlanner.unfinishedDue(3, false, 18, true), "");
		ok("unfinished: before 18:00 the sweep waits", !MissionPlanner.unfinishedDue(3, false, 17, true), "");
		ok("unfinished: the on-demand run ignores the clock", MissionPlanner.unfinishedDue(3, false, 9, false), "");
		ok("unfinished: 0 answers is not a session", !MissionPlanner.unfinishedDue(0, false, 18, true), "");
		ok("unfinished: 5 answers already counted", !MissionPlanner.unfinishedDue(5, false, 18, true), "");
		ok("unfinished: a pending reminder wins", !MissionPlanner.unfinishedDue(3, true, 18, true), "");
		ok("unfinished text, plural", "Te faltan 2 preguntas para que hoy cuente en tu objetivo de Fatiga.".equals(MissionPlanner.unfinishedText(3, "Fatiga")), MissionPlanner.unfinishedText(3, "Fatiga"));
		ok("unfinished text, singular", "Te falta 1 pregunta para que hoy cuente en tu objetivo de Fatiga.".equals(MissionPlanner.unfinishedText(4, "Fatiga")), MissionPlanner.unfinishedText(4, "Fatiga"));
		ok("missionpush id = <notification>_<yyyyMMddHHmm> UTC", "u_mission_remind_202610050000".equals(MissionPlanner.pushRowId("u_mission_remind", NOW)), MissionPlanner.pushRowId("u_mission_remind", NOW));
	}
```

- [ ] **Step 2: Run it to make sure it fails.**

Run: `sh /private/tmp/claude-501/-Users-DSANJORGE-Code-EMEGenAILabs/09ac33b3-5de0-4ac1-a0e4-e8f8b25fc0ca/scratchpad/wcheck.sh MissionPlannerCheck`
Expected: compile error `cannot find symbol … unfinishedDue` (the check does not compile yet).

- [ ] **Step 3: Pure helpers.** In `MissionPlanner.java`, replace the final
```java
			default -> null;
		};
	}
}
```
with
```java
			default -> null;
		};
	}

	/** The unfinished-session push (amendment 2026-10-06): today 1..DAY_MIN_ANSWERS-1 answers on the goal topic, no pending
	 *  reminder, and -- for the automatic sweep (honorClock) -- the learner's clock at or past UNFINISHED_HOUR (quiet hours stop it
	 *  at 20:00). The admin's on-demand run ignores the clock, like it ignores quiet hours. Pure. */
	public static boolean unfinishedDue(int todayAnswers, boolean reminderPending, int hour, boolean honorClock)
	{
		return todayAnswers > 0 && todayAnswers < DAY_MIN_ANSWERS && !reminderPending && (!honorClock || hour >= UNFINISHED_HOUR);
	}

	/** "Te faltan N preguntas para que hoy cuente en tu objetivo de <Tema>." (Spanish, like missionText). Pure. */
	public static String unfinishedText(int todayAnswers, String topicTitle)
	{
		int left = DAY_MIN_ANSWERS - todayAnswers;
		return (left == 1 ? "Te falta 1 pregunta" : "Te faltan " + left + " preguntas") + " para que hoy cuente en tu objetivo de " + topicTitle + ".";
	}

	/** missionpush row id: <notification id>_<yyyyMMddHHmm> in UTC -- one row per push even when the notification row is reused. Pure. */
	public static String pushRowId(String notificationId, Date sentAt)
	{
		return notificationId + "_" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(java.time.ZoneOffset.UTC).format(sentAt.toInstant());
	}
}
```

- [ ] **Step 4: Run the pure check.**

Run: `sh /private/tmp/claude-501/-Users-DSANJORGE-Code-EMEGenAILabs/09ac33b3-5de0-4ac1-a0e4-e8f8b25fc0ca/scratchpad/wcheck.sh MissionPlannerCheck`
Expected: last line `MissionPlannerCheck: all ok`.

- [ ] **Step 5: `pushMission` helper.** In `TestULearningModule.java`, right after the closing `}` of `static String missionText(String status, JSONObject goal)` (the line after `default -> "Una sesión corta hoy para seguir avanzando.";` and `};`), add:

```java

	/** Every mission push (amendment 2026-10-06): the FCM push of the saved row n, plus one missionpush row (user, kind,
	 *  entitytopic, sentat, notification) so push history survives the reused notification rows. The row is written even when FCM
	 *  is off or the learner has no device: sent = issued by the server (the bell has it), not confirmed by a phone. Package-visible:
	 *  the coach nudge (TestUAnalyticsModule) goes through it too. */
	void pushMission(MediaArchive archive, TestUSocialModule social, Data n, Date now)
	{
		social.push(archive, n);
		Searcher ps = archive.getSearcher("missionpush");
		Data r = ps.createNewData();
		r.setId(MissionPlanner.pushRowId(n.getId(), now));
		r.setValue("user", n.get("user"));
		r.setValue("kind", n.get("kind"));
		r.setValue("entitytopic", n.get("entitytopic"));
		r.setValue("sentat", now);
		r.setValue("notification", n.getId());
		ps.saveData(r, null);
	}
```

- [ ] **Step 6: Kinds and the unfinished push in `missionNudges`.**

Replace
```java
				int hour = now.toInstant().atZone(learnerZone(archive, u.getId())).getHour();
```
with
```java
				ZoneId zone = learnerZone(archive, u.getId());
				int hour = now.toInstant().atZone(zone).getHour();
```

Replace
```java
						remind.setValue("text", missionText(goal == null ? "pace" : "remind", goal));
						remind.setValue("pushedat", now);
						remind.setValue("datecreated", now);
						ns.saveData(remind, null);
						social.push(archive, remind);
						sent++;
					}
```
with
```java
						remind.setValue("text", missionText(goal == null ? "pace" : "remind", goal));
						remind.setValue("kind", "remind");
						remind.setValue("pushedat", now);
						remind.setValue("datecreated", now);
						ns.saveData(remind, null);
						pushMission(archive, social, remind, now);
						sent++;
					}
					// amendment 2026-10-06: today's session started but not counted yet -> one push per learner per day from 18:00.
					JSONObject week = (JSONObject) m.get("week");
					int todayAnswers = week == null ? 0 : LearningEngine.intOr(week.get("todayanswers"), 0);
					boolean reminderPending = remind != null && remind.get("pushedat") == null;
					if (goal != null && MissionPlanner.unfinishedDue(todayAnswers, reminderPending, hour, inHonorQuietHours))
					{
						String unfinishedId = u.getId() + "_mission_unfinished_" + LearningEngine.ymd(now, zone).replace("-", "");
						if (ns.searchById(unfinishedId) == null)
						{
							Data n = ns.createNewData();
							n.setId(unfinishedId);
							n.setValue("user", u.getId()); n.setValue("actor", "tutor"); n.setValue("actorname", tutorName(archive));
							n.setValue("type", "mission"); n.setValue("datecreated", now); n.setValue("read", false);
							n.setValue("entitytopic", goal.get("topic")); n.setValue("kind", "unfinished"); n.setValue("pushedat", now);
							n.setValue("text", MissionPlanner.unfinishedText(todayAnswers, String.valueOf(goal.get("topictitle"))));
							ns.saveData(n, null);
							pushMission(archive, social, n, now);
							sent++;
						}
					}
```

Replace
```java
							n.setValue("text", missionText(status, goal));
							ns.saveData(n, null);
							social.push(archive, n);
```
with
```java
							n.setValue("text", missionText(status, goal));
							n.setValue("kind", status); // ready | at_risk | overdue
							ns.saveData(n, null);
							pushMission(archive, social, n, now);
```

- [ ] **Step 7: `kind` in the push payload.** In `TestUSocialModule.java` replace
```java
		for (String k : new String[] { "type", "channel", "messageid", "entitytopic", "entityquestion", "entitytutorial", "actorname", "text" })
```
with
```java
		for (String k : new String[] { "type", "kind", "channel", "messageid", "entitytopic", "entityquestion", "entitytutorial", "actorname", "text" })
```
(and in the javadoc above `push`, after "The data payload is the row itself", nothing else changes: `kind` is part of the row.)

- [ ] **Step 8: Coach nudge.** In `TestUAnalyticsModule.java` (`coachNudge`) replace
```java
					n.setValue("status", textStatus);
```
with
```java
					n.setValue("status", textStatus);
					n.setValue("kind", "coach");
```
and replace
```java
			social.push(archive, n);
			done++;
```
with
```java
			learningModule.pushMission(archive, social, n, now);
			done++;
```

- [ ] **Step 9: Live assertions** (verified in Task 10). In `tools/check_mission.sh`:

In `cleanup()`, replace
```python
    delete_rows("learnernotification", es_ids("learnernotification", {"term": {"user": UID}}))
    usersave("lastmissionstatus", "", UID)
```
with
```python
    delete_rows("learnernotification", es_ids("learnernotification", {"term": {"user": UID}}))
    delete_rows("missionpush", es_ids("missionpush", {"term": {"user": UID}}))
    delete_rows("missionpush", es_ids("missionpush", {"term": {"user": UID2}}))
    usersave("lastmissionstatus", "", UID)
```

Replace
```python
    ok("status push not repeated on a second run", r2.get("sent", 0) == 0, r2)
```
with
```python
    ok("status push not repeated on a second run", r2.get("sent", 0) == 0, r2)
    ok("delivered reminder carries kind=remind", es_doc("learnernotification", f"{UID}_mission_remind").get("kind") == "remind")
    ok("delivered reminder logged in missionpush", any(i.startswith(f"{UID}_mission_remind_") for i in es_ids("missionpush", {"term": {"user": UID}})))
    # amendment 2026-10-06: 2 learning answers today on the goal topic -> week.todayanswers 2; the on-demand sweep (no clock gate)
    # pushes "Te faltan 3 preguntas…" once, and not again the same day.
    for i in range(2):
        put_row("tutoranswer", f"mcheck-a-{i}", {"user": UID, "entityquestion": item0["questionid"], "iscorrect": "true", "answerconfidence": "confident",
                "mode": "learn", "hintlevel": "0", "datecreated": iso(datetime.datetime.utcnow()), "componentsection": item0["sectionid"]})
    refresh()
    s, m = call(me, "GET", "/services/testu/learn/mission.json")
    ok("week.todayanswers counts today's learning answers", m["week"]["todayanswers"] == 2 and m["week"]["todaycounted"] is False, m.get("week"))
    call(admin, "GET", "/services/testu/learn/missionnudges.json")
    refresh()
    unf = [i for i in es_ids("learnernotification", {"term": {"user": UID}}) if "_mission_unfinished_" in i]
    ok("unfinished push: one row today", len(unf) == 1, unf)
    urow = es_doc("learnernotification", unf[0]) if unf else {}
    ok("unfinished row: kind + text", urow.get("kind") == "unfinished" and "Te faltan 3 preguntas" in (urow.get("text") or ""), urow)
    ok("unfinished push logged in missionpush", any(i.startswith(f"{UID}_mission_unfinished_") for i in es_ids("missionpush", {"term": {"user": UID}})))
    s, r3 = call(admin, "GET", "/services/testu/learn/missionnudges.json")
    refresh()
    ok("unfinished push not repeated the same day", len([i for i in es_ids("learnernotification", {"term": {"user": UID}}) if "_mission_unfinished_" in i]) == 1 and r3.get("sent", 0) == 0, r3)
```

Replace
```python
    ok("nudge sent", r.get("done") == 1, r)
```
with
```python
    ok("nudge sent", r.get("done") == 1, r)
    refresh()
    ok("coach nudge carries kind=coach", (es_doc("learnernotification", f"{UID2}_coach_{TOPIC}") or {}).get("kind") == "coach")
    ok("coach nudge logged in missionpush", any(i.startswith(f"{UID2}_coach_{TOPIC}_") for i in es_ids("missionpush", {"term": {"user": UID2}})))
```

- [ ] **Step 10: Compile + pure checks, python parse.**

Run: `sh /private/tmp/claude-501/-Users-DSANJORGE-Code-EMEGenAILabs/09ac33b3-5de0-4ac1-a0e4-e8f8b25fc0ca/scratchpad/wcheck.sh MissionPlannerCheck`
Expected: no javac error, `MissionPlannerCheck: all ok`.
Run: `python3 -c "import ast; src=open('/Users/DSANJORGE/Code/EMEGenAILabs/.worktrees/testu-weekly/tools/check_mission.sh').read(); ast.parse(src.split(\"<<'PY'\n\",1)[1].rsplit('\nPY',1)[0])"`
Expected: no output.

- [ ] **Step 11: Commit.**

```bash
git -C /Users/DSANJORGE/Code/EMEGenAILabs/.worktrees/testu-weekly add code/tech/genailabs/tutor/MissionPlanner.java code/tech/genailabs/tutor/TestULearningModule.java code/tech/genailabs/tutor/TestUSocialModule.java code/tech/genailabs/tutor/TestUAnalyticsModule.java tools/MissionPlannerCheck.java tools/check_mission.sh
git -C /Users/DSANJORGE/Code/EMEGenAILabs/.worktrees/testu-weekly commit -m "feat(testu): mission push kinds, 18:00 unfinished-session push, missionpush log" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 5: Push effectiveness — `push_open` usage type, `engagement.json` `missionpushes`

**Files:**
- Modify: `code/tech/genailabs/tutor/TestUUsageModule.java:21-22`
- Modify: `code/tech/genailabs/tutor/TestUAnalyticsModule.java` — `engagement()` (`:993` lists, `:1009-1022` usageevent loop, `:1063-1065` tutoranswer loop, `:1207` resp), new `PUSH_KINDS` + `missionPushes` after `countsJson` (`:1250-1263`)
- Test: `tools/MissionPlannerCheck.java` (new `missionPushChecks()`); `tools/check_mission.sh` (live, verified in Task 10)

**Interfaces:**
- Consumes: Task 4 `missionpush` rows; `MissionPlanner.DAY_MIN_ANSWERS`; `TestUAnalyticsModule.DoneRow(user, type, source, topic, at, complete)` (existing, public).
- Produces:
  - usage event `{type: "push_open", campaign: <notification type>, source: <kind>, entitytopic, notification: <id>}` accepted by `services/testu/usage/track.json`.
  - `public static JSONObject TestUAnalyticsModule.missionPushes(List<DoneRow> inSent, List<DoneRow> inOpens, List<DoneRow> inAnswers, ZoneId inZone)`.
  - engagement.json `missionpushes` = `{remind|unfinished|ready|at_risk|overdue|coach: {sent, tapped, practised, counted, tappedrate, practisedrate, countedrate}}` (rates `null` when `sent` = 0, else 3 decimals). App Task 9 reads it.

- [ ] **Step 1: Write the failing check.** In `tools/MissionPlannerCheck.java` add `import tech.genailabs.tutor.TestUAnalyticsModule;` after `import tech.genailabs.tutor.MissionPlanner;`, add `missionPushChecks();` after `unfinishedChecks();` in `main`, and:

```java
	// Amendment 2026-10-06: push effectiveness per kind (engagement.json missionpushes), Lima day 2026-09-21.
	static void missionPushChecks()
	{
		List<TestUAnalyticsModule.DoneRow> sent = new ArrayList<>(), opens = new ArrayList<>(), answers = new ArrayList<>();
		sent.add(row("a", "remind", "t1", "2026-09-21T14:00:00Z"));
		sent.add(row("b", "remind", "t1", "2026-09-21T14:00:00Z"));
		sent.add(row("a", "unfinished", "t1", "2026-09-21T23:00:00Z"));
		sent.add(row("c", "coach", "t2", "2026-09-21T15:00:00Z"));
		opens.add(row("a", "remind", "t1", "2026-09-21T14:10:00Z"));
		opens.add(row("b", "unfinished", "t1", "2026-09-21T14:10:00Z")); // another kind's tap: not this push
		opens.add(row("c", "coach", "t2", "2026-09-23T15:00:00Z"));      // after 24 h
		for (int i = 0; i < 5; i++)
			answers.add(row("a", null, "t1", "2026-09-21T15:0" + i + ":00Z"));
		for (int i = 0; i < 2; i++)
			answers.add(row("a", null, "t1", "2026-09-22T01:0" + i + ":00Z")); // 20:0x Lima, still the 21st
		answers.add(row("b", null, "t2", "2026-09-21T15:00:00Z")); // another topic
		JSONObject p = TestUAnalyticsModule.missionPushes(sent, opens, answers, LIMA);
		ok("pushes: remind", "sent=2 tapped=1 practised=1 counted=1".equals(pushOf(p, "remind")), pushOf(p, "remind"));
		ok("pushes: unfinished counts only answers inside its own 24 h", "sent=1 tapped=0 practised=1 counted=0".equals(pushOf(p, "unfinished")), pushOf(p, "unfinished"));
		ok("pushes: coach, a tap after 24 h is ignored", "sent=1 tapped=0 practised=0 counted=0".equals(pushOf(p, "coach")), pushOf(p, "coach"));
		ok("pushes: remind tapped rate 0.5", Double.valueOf(0.5).equals(((JSONObject) p.get("remind")).get("tappedrate")), p.get("remind"));
		ok("pushes: nothing sent -> rates null", ((JSONObject) p.get("ready")).get("tappedrate") == null, p.get("ready"));
	}

	static TestUAnalyticsModule.DoneRow row(String user, String kind, String topic, String iso)
	{
		return new TestUAnalyticsModule.DoneRow(user, kind, null, topic, Date.from(java.time.Instant.parse(iso)), false);
	}

	static String pushOf(JSONObject p, String kind)
	{
		JSONObject k = (JSONObject) p.get(kind);
		return "sent=" + k.get("sent") + " tapped=" + k.get("tapped") + " practised=" + k.get("practised") + " counted=" + k.get("counted");
	}
```

- [ ] **Step 2: Run it to make sure it fails.**

Run: `sh /private/tmp/claude-501/-Users-DSANJORGE-Code-EMEGenAILabs/09ac33b3-5de0-4ac1-a0e4-e8f8b25fc0ca/scratchpad/wcheck.sh MissionPlannerCheck`
Expected: compile error `cannot find symbol … missionPushes`.

- [ ] **Step 3: Pure counter.** In `TestUAnalyticsModule.java`, directly before `	/** A dailydone_* usage event (type, source = email|app, topic = the recommended one) or a learn/improve session (type = mode,` add:

```java
	/** Mission push kinds, in display order (amendment 2026-10-06). */
	static final List<String> PUSH_KINDS = List.of("remind", "unfinished", "ready", "at_risk", "overdue", "coach");

	/**
	 * Mission push effectiveness per kind (engagement.json missionpushes, amendment 2026-10-06): sent = missionpush rows; within 24 h
	 * after each: tapped = a push_open of the same user and kind; practised = at least one learning answer on the push's topic;
	 * counted = MissionPlanner.DAY_MIN_ANSWERS or more of those answers on one inZone day. Rates over sent, null with none sent.
	 * inSent/inOpens: type = kind; inAnswers: learning-mode answers with their topic. Pure; counts only, no names.
	 * ponytail: O(sent x events), fine at pilot volume; index opens/answers by user if engagement.json gets slow.
	 */
	public static JSONObject missionPushes(List<DoneRow> inSent, List<DoneRow> inOpens, List<DoneRow> inAnswers, java.time.ZoneId inZone)
	{
		Map<String, int[]> counts = new LinkedHashMap<>();
		for (String k : PUSH_KINDS)
			counts.put(k, new int[4]);
		for (DoneRow s : inSent)
		{
			int[] c = counts.get(s.type);
			if (c == null)
				continue;
			long from = s.at.getTime(), until = from + 86400000L;
			boolean tapped = false;
			for (DoneRow o : inOpens)
				tapped |= s.user.equals(o.user) && s.type.equals(o.type) && o.at.getTime() >= from && o.at.getTime() <= until;
			Map<java.time.LocalDate, Integer> perDay = new HashMap<>();
			for (DoneRow a : inAnswers)
				if (s.user.equals(a.user) && s.topic != null && s.topic.equals(a.topic) && a.at.getTime() >= from && a.at.getTime() <= until)
					perDay.merge(a.at.toInstant().atZone(inZone).toLocalDate(), 1, Integer::sum);
			c[0]++;
			c[1] += tapped ? 1 : 0;
			c[2] += perDay.isEmpty() ? 0 : 1;
			c[3] += perDay.values().stream().anyMatch(n -> n >= MissionPlanner.DAY_MIN_ANSWERS) ? 1 : 0;
		}
		String[] names = {"sent", "tapped", "practised", "counted"};
		JSONObject out = new JSONObject();
		for (Map.Entry<String, int[]> e : counts.entrySet())
		{
			int[] c = e.getValue();
			JSONObject k = new JSONObject();
			for (int i = 0; i < 4; i++)
				k.put(names[i], c[i]);
			for (int i = 1; i < 4; i++)
				k.put(names[i] + "rate", c[0] == 0 ? null : Math.round(1000.0 * c[i] / c[0]) / 1000.0);
			out.put(e.getKey(), k);
		}
		return out;
	}

```

- [ ] **Step 4: Run the pure check.**

Run: `sh /private/tmp/claude-501/-Users-DSANJORGE-Code-EMEGenAILabs/09ac33b3-5de0-4ac1-a0e4-e8f8b25fc0ca/scratchpad/wcheck.sh MissionPlannerCheck`
Expected: `MissionPlannerCheck: all ok`.

- [ ] **Step 5: Wire it into `engagement()`.**

Replace
```java
		List<DoneRow> dcOpens = new ArrayList<>(); // session_start of a Daily Challenge: type = platform, source = entry
```
with
```java
		List<DoneRow> dcOpens = new ArrayList<>(); // session_start of a Daily Challenge: type = platform, source = entry
		List<DoneRow> pushOpens = new ArrayList<>(); // push_open: type = mission push kind (the event's source), source = campaign
		List<DoneRow> learnAnswers = new ArrayList<>(); // learning-mode answers with their topic, for missionpushes
```

Replace
```java
			if ("session_start".equals(type))
			{
				if ("dailychallenge".equals(e.get("mode")))
```
with
```java
			if ("push_open".equals(type))
			{
				pushOpens.add(new DoneRow(u, e.get("source"), e.get("campaign"), e.get("entitytopic"), d, false));
				continue;
			}
			if ("session_start".equals(type))
			{
				if ("dailychallenge".equals(e.get("mode")))
```

Replace
```java
			if (!topicFilter.isEmpty() && !perSection.containsKey(x.get("componentsection")))
				continue;
			String mode = x.get("mode") == null ? "other" : x.get("mode");
			modeAnswers.merge(mode, 1, Integer::sum);
```
with
```java
			if (!topicFilter.isEmpty() && !perSection.containsKey(x.get("componentsection")))
				continue;
			Map<String, Object> sec = perSection.get(x.get("componentsection")); // topic of the answer (tutoranswer has no entitytopic)
			if (sec != null && LearningEngine.isLearningMode(x.get("mode")))
				learnAnswers.add(new DoneRow(u, null, null, (String) sec.get("topic"), d, false));
			String mode = x.get("mode") == null ? "other" : x.get("mode");
			modeAnswers.merge(mode, 1, Integer::sum);
```
(This exact block appears once in `engagement()`; the similar loop at `:1406` is in another method and has no `modeAnswers` line.)

Replace
```java
		resp.put("dailyopens", dailyOpens(dcOpens, dcComplete, orgzone));
		return resp;
```
with
```java
		resp.put("dailyopens", dailyOpens(dcOpens, dcComplete, orgzone));
		// Mission pushes of the period (amendment 2026-10-06), topic-filtered like the answers. ponytail: taps/answers after `to`
		// are not loaded, so a push in the period's last 24 h can under-count; widen the event queries if that ever matters.
		List<DoneRow> pushesSent = new ArrayList<>();
		HitTracker mh = archive.query("missionpush").after("sentat", from).search();
		if (mh != null)
		{
			mh.enableBulkOperations();
			for (Object o : mh)
			{
				Data r = (Data) o;
				Date d = dates.parseFromObject(r.getValue("sentat"));
				if (d != null && d.before(to) && users.contains(r.get("user")) && (topicFilter.isEmpty() || topicFilter.equals(r.get("entitytopic"))))
					pushesSent.add(new DoneRow(r.get("user"), r.get("kind"), null, r.get("entitytopic"), d, false));
			}
		}
		resp.put("missionpushes", missionPushes(pushesSent, pushOpens, learnAnswers, orgzone));
		return resp;
```

- [ ] **Step 6: Accept `push_open`.** In `TestUUsageModule.java` replace
```java
	private static final Set<String> USAGE_TYPES = new HashSet<>(Arrays.asList("open", "resume", "pause", "iris_rate", "source_open", "session_leave", "dailydone_shown", "dailydone_click", "dailydone_dismiss", "session_start"));
	private static final String[] EXTRA_FIELDS = new String[] {"channel", "componentsection", "entityquestion", "rating", "source", "platform", "appversion", "entitytopic", "mode", "campaign"};
```
with
```java
	private static final Set<String> USAGE_TYPES = new HashSet<>(Arrays.asList("open", "resume", "pause", "iris_rate", "source_open", "session_leave", "dailydone_shown", "dailydone_click", "dailydone_dismiss", "session_start", "push_open"));
	private static final String[] EXTRA_FIELDS = new String[] {"channel", "componentsection", "entityquestion", "rating", "source", "platform", "appversion", "entitytopic", "mode", "campaign", "notification"};
```

- [ ] **Step 7: Live assertions** (verified in Task 10). In `tools/check_mission.sh`, in `cleanup()` after the two `missionpush` lines added in Task 4 add:
```python
    delete_rows("usageevent", es_ids("usageevent", {"term": {"user": UID}}))
```
and directly after the statement `ok("unfinished push not repeated the same day", …)` (added in Task 4, the last line of the nudges block before `# --- coach`) add:
```python
    # amendment 2026-10-06: a push tap is a push_open usage event; engagement.json counts sent/tapped per kind.
    ev = [{"type": "push_open", "at": datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ"), "sessionid": "mcheck",
           "campaign": "mission", "source": "remind", "entitytopic": TOPIC, "notification": f"{UID}_mission_remind"}]
    s, r = call(me, "POST", "/services/testu/usage/track.json", form={"events": json.dumps(ev)})
    ok("push_open accepted", s == 200 and r.get("ok"), r)
    refresh()
    s, e = call(admin, "GET", "/services/testu/analytics/engagement.json")
    mp = ((e.get("missionpushes") or {}) if s == 200 else {}).get("remind") or {}
    ok("engagement.json missionpushes: remind sent and tapped", mp.get("sent", 0) >= 1 and mp.get("tapped", 0) >= 1, e.get("missionpushes") if s == 200 else e)
```

- [ ] **Step 8: Compile + checks + python parse.**

Run: `sh /private/tmp/claude-501/-Users-DSANJORGE-Code-EMEGenAILabs/09ac33b3-5de0-4ac1-a0e4-e8f8b25fc0ca/scratchpad/wcheck.sh MissionPlannerCheck`
Expected: `MissionPlannerCheck: all ok`.
Run: `python3 -c "import ast; src=open('/Users/DSANJORGE/Code/EMEGenAILabs/.worktrees/testu-weekly/tools/check_mission.sh').read(); ast.parse(src.split(\"<<'PY'\n\",1)[1].rsplit('\nPY',1)[0])"`
Expected: no output.

- [ ] **Step 9: Commit.**

```bash
git -C /Users/DSANJORGE/Code/EMEGenAILabs/.worktrees/testu-weekly add code/tech/genailabs/tutor/TestUAnalyticsModule.java code/tech/genailabs/tutor/TestUUsageModule.java tools/MissionPlannerCheck.java tools/check_mission.sh
git -C /Users/DSANJORGE/Code/EMEGenAILabs/.worktrees/testu-weekly commit -m "feat(testu): push_open usage event and engagement.json missionpushes (sent/tapped/practised/counted per kind)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 6: Friday summary emails — learner objective block, admin deadline counts

**Files:**
- Modify: `code/tech/genailabs/tutor/WeeklySummaryEmail.java` — `LearnerWeek` (`:233-244`), `learnerWeek()` end (`:383-386`), `learnerContent` (`:467`), `AdminWeek` (`:516`), `adminWeek()` end (`:647-651`), `adminContent` (`:700`), new static helpers next to `band()` (`:908`)
- Modify: `code/tech/genailabs/tutor/TestUAnalyticsModule.java:2840-2860` (`coachSuggestions` cache block) + new `cachedCoachSuggestions`, `coachSuggestionsFor`
- Test: `tools/WeeklySummaryEmailCheck.java`; `tools/check_learning.sh:16` (runs it); `tools/check_mission.sh` (live, verified in Task 10)

**Interfaces:**
- Consumes: `TestULearningModule.missionOf(archive, u, now)` (package-visible, `[0]` = mission JSON with `goal`, `week`, `status`); Task 1 week fields.
- Produces:
  - `LearnerWeek.goalStatus, goalTopic, goalLevel, goalDeadline, goalBand : String` (goalTopic null = no block), `goalPercent, goalDone, goalNeeded : int`.
  - `AdminWeek.deadlines : List<String[]>` = `{topic title, at risk, overdue, ready not booked}` (counts as strings).
  - `public static List<String[]> WeeklySummaryEmail.deadlineRows(List<Map<String, Object>> inSuggestions)`.
  - `List<Map<String, Object>> TestUAnalyticsModule.coachSuggestionsFor(MediaArchive archive, Set<String> scope)` (package-visible; scope null = whole org; no team filter, no dismissals, shares the coach TTL cache).

- [ ] **Step 1: Write the failing checks.** In `tools/WeeklySummaryEmailCheck.java`, after
```java
		ok("english subject", "Diego, here’s your week in TestU".equals(en[0]), en[0]);
```
add
```java

		// Weekly objective (amendment 2026-10-06): learner block after the progress section; absent without a goal.
		busy.goalStatus = "at_risk"; busy.goalTopic = "Ciberseguridad"; busy.goalLevel = "competent"; busy.goalDeadline = "2026-10-11";
		busy.goalBand = "beginner"; busy.goalPercent = 4; busy.goalDone = 1; busy.goalNeeded = 5;
		String[] g = WeeklySummaryEmail.learnerContent(false, busy, "IRIS", null, "L");
		ok("goal section", g[1].contains("TU OBJETIVO DE LA SEMANA"), "");
		ok("goal title + deadline", g[1].contains("Competente en Ciberseguridad · antes del 11 de octubre"), "");
		ok("goal week + level", g[1].contains("1 de 5 sesiones esta semana · Principiante · 4%"), "");
		ok("goal status + suggestion", g[1].contains("En riesgo · Una sesión el sábado y otra el domingo te acercan a tu meta."), "");
		ok("goal block after the progress section", g[1].indexOf("TU OBJETIVO DE LA SEMANA") > g[1].indexOf("TU AVANCE"), "");
		ok("no goal, no block", !q[1].contains("TU OBJETIVO DE LA SEMANA"), "");
		busy.goalDone = 5;
		ok("week done line", WeeklySummaryEmail.learnerContent(false, busy, "IRIS", null, "L")[1].contains("Semana cumplida. Lo que practiques de más suma para tu nivel."), "");
		busy.goalStatus = "pace"; busy.goalDeadline = null; busy.goalDone = 1; busy.goalNeeded = 3;
		ok("pace goal title", WeeklySummaryEmail.learnerContent(false, busy, "IRIS", null, "L")[1].contains("Practicar Ciberseguridad"), "");
		busy.goalTopic = null;
```
After
```java
		ok("team scope foot", c[1].contains("administras Oficina Lima"), "");
```
add
```java

		// Admin: OBJETIVOS CON PLAZO from the Coach card's suggestions (counts per topic, cert_expiring left out).
		List<java.util.Map<String, Object>> sugg = List.of(sugg("at_risk", "Ciberseguridad", 3), sugg("overdue", "Ciberseguridad", 1),
			sugg("ready_not_booked", "Derechos Humanos", 2), sugg("cert_expiring", "Fatiga", 4));
		a.deadlines = WeeklySummaryEmail.deadlineRows(sugg);
		ok("deadline rows: one per topic, cert_expiring left out", a.deadlines.size() == 2 && "Ciberseguridad".equals(a.deadlines.get(0)[0]), a.deadlines.size());
		String[] ad2 = WeeklySummaryEmail.adminContent(false, a, "IRIS", null, "L");
		ok("admin goals section", ad2[1].contains("OBJETIVOS CON PLAZO") && ad2[1].contains("Ciberseguridad · 3 en riesgo · 1 con plazo vencido")
			&& ad2[1].contains("Derechos Humanos · 2 listos para evaluar"), "");
		ok("admin goals: Coach pointer", ad2[1].contains("desde la tarjeta Coach de la consola"), "");
		ok("no deadlines, no section", !c[1].contains("OBJETIVOS CON PLAZO"), "");
```
and add a helper next to `move(...)`:
```java
	static java.util.Map<String, Object> sugg(String inKind, String inTopic, int inCount)
	{
		java.util.Map<String, Object> m = new java.util.HashMap<>();
		m.put("kind", inKind); m.put("topictitle", inTopic); m.put("count", inCount);
		return m;
	}
```
In `tools/check_learning.sh` replace
```sh
java -cp "$CP" "$ROOT/plugins/testu/tools/DailyChallengeEmailCheck.java"
```
with
```sh
java -cp "$CP" "$ROOT/plugins/testu/tools/DailyChallengeEmailCheck.java"
java -cp "$CP" "$ROOT/plugins/testu/tools/WeeklySummaryEmailCheck.java"
```

- [ ] **Step 2: Run it to make sure it fails.**

Run: `sh /private/tmp/claude-501/-Users-DSANJORGE-Code-EMEGenAILabs/09ac33b3-5de0-4ac1-a0e4-e8f8b25fc0ca/scratchpad/wcheck.sh WeeklySummaryEmailCheck`
Expected: compile error `cannot find symbol … goalStatus` / `deadlineRows`.

- [ ] **Step 3: Model fields.** In `WeeklySummaryEmail.java` replace
```java
		public String[] focus; // {mode learn|improve, subtopic or null, topic}
	}
```
with
```java
		public String[] focus; // {mode learn|improve, subtopic or null, topic}
		// Weekly objective (amendment 2026-10-06), from mission.json's goal/week; goalTopic null = no goal, no block.
		public String goalStatus, goalTopic, goalLevel, goalDeadline, goalBand;
		public int goalPercent, goalDone, goalNeeded;
	}
```
and replace
```java
		public List<String[]> gaps = new ArrayList<>(); // {subtopic, topic, beginners, people}
```
with
```java
		public List<String[]> gaps = new ArrayList<>(); // {subtopic, topic, beginners, people}
		public List<String[]> deadlines = new ArrayList<>(); // {topic, at risk, overdue, ready not booked} (amendment 2026-10-06)
```

- [ ] **Step 4: Learner block.** In `learnerContent` replace
```java
		List<String[]> next = new ArrayList<>(); // {icon, text}
```
with
```java
		if (w.goalTopic != null)
		{
			in.append(section(en ? "YOUR GOAL THIS WEEK" : "TU OBJETIVO DE LA SEMANA"));
			String title = "pace".equals(w.goalStatus) || w.goalLevel == null ? (en ? "Practise " : "Practicar ") + w.goalTopic
					: band(en, w.goalLevel) + (en ? " in " : " en ") + w.goalTopic;
			if (w.goalDeadline != null)
			{
				title += (en ? " · by " : " · antes del ") + date(en, w.goalDeadline);
			}
			in.append(iconRow("🎯", title));
			String sessions = w.goalNeeded == 1 ? (en ? " session" : " sesión") : (en ? " sessions" : " sesiones");
			in.append(p(w.goalDone + (en ? " of " : " de ") + w.goalNeeded + sessions + (en ? " this week · " : " esta semana · ")
					+ (w.goalBand == null ? (en ? "Not started" : "Sin empezar") : band(en, w.goalBand)) + " · " + w.goalPercent + "%"));
			in.append(p(goalAdvice(en, w)));
		}
		List<String[]> next = new ArrayList<>(); // {icon, text}
```
and add next to `static String band(` (before it):
```java
	/** The objective block's status plus one plain suggestion line (amendment 2026-10-06). Pure. */
	static String goalAdvice(boolean en, LearnerWeek w)
	{
		if ("ready".equals(w.goalStatus))
		{
			return en ? "Ready for the evaluation · it’s open in the app." : "Listo para evaluar · la evaluación está abierta en la app.";
		}
		if (w.goalNeeded > 0 && w.goalDone >= w.goalNeeded && !"overdue".equals(w.goalStatus))
		{
			return en ? "Week done. Extra practice still counts toward your level." : "Semana cumplida. Lo que practiques de más suma para tu nivel.";
		}
		String status = switch (String.valueOf(w.goalStatus))
		{
			case "at_risk" -> en ? "At risk" : "En riesgo";
			case "on_track" -> en ? "On track" : "Vas a tiempo";
			case "overdue" -> en ? "Deadline passed" : "Plazo vencido";
			default -> en ? "At your pace" : "A tu ritmo";
		};
		String tip = w.goalNeeded - w.goalDone == 1 ? (en ? "One session this weekend completes your week." : "Una sesión este fin de semana completa tu semana.")
				: (en ? "A session on Saturday and another on Sunday get you closer." : "Una sesión el sábado y otra el domingo te acercan a tu meta.");
		return status + " · " + tip;
	}

	/** {topic, at risk, overdue, ready not booked} per topic from the Coach card's suggestions, in their order. cert_expiring is
	 *  left out: the NEED ATTENTION certifications count already covers it. Counts only. Pure. */
	public static List<String[]> deadlineRows(List<Map<String, Object>> inSuggestions)
	{
		Map<String, int[]> byTopic = new java.util.LinkedHashMap<>();
		for (Map<String, Object> s : inSuggestions)
		{
			int i = switch (String.valueOf(s.get("kind")))
			{
				case "at_risk" -> 0;
				case "overdue" -> 1;
				case "ready_not_booked" -> 2;
				default -> -1;
			};
			if (i >= 0)
			{
				byTopic.computeIfAbsent(String.valueOf(s.get("topictitle")), k -> new int[3])[i] += num(s.get("count"));
			}
		}
		List<String[]> out = new ArrayList<>();
		for (Map.Entry<String, int[]> e : byTopic.entrySet())
		{
			out.add(new String[] {e.getKey(), String.valueOf(e.getValue()[0]), String.valueOf(e.getValue()[1]), String.valueOf(e.getValue()[2])});
		}
		return out;
	}

	/** "Ciberseguridad · 3 en riesgo · 1 con plazo vencido" (zero counts left out). Pure. */
	static String deadlineCounts(boolean en, String[] inRow)
	{
		List<String> parts = new ArrayList<>();
		if (num(inRow[1]) > 0)
			parts.add(inRow[1] + (en ? " at risk" : " en riesgo"));
		if (num(inRow[2]) > 0)
			parts.add(inRow[2] + (en ? " overdue" : " con plazo vencido"));
		if (num(inRow[3]) > 0)
			parts.add(inRow[3] + (en ? " ready, not booked" : num(inRow[3]) == 1 ? " listo para evaluar" : " listos para evaluar"));
		return inRow[0] + " · " + String.join(" · ", parts);
	}

```

- [ ] **Step 5: Admin block.** In `adminContent` replace
```java
		if (!w.teams.isEmpty())
		{
			in.append(section(en ? "ACTIVE BY TEAM" : "ACTIVAS POR EQUIPO"));
```
with
```java
		if (!w.deadlines.isEmpty())
		{
			in.append(section(en ? "GOALS WITH A DEADLINE" : "OBJETIVOS CON PLAZO"));
			for (String[] d : w.deadlines)
			{
				in.append(iconRow("🎯", deadlineCounts(en, d)));
			}
			in.append(p(en ? "Send a reminder or change a date from the Coach card in the console." : "Envía un recordatorio o cambia una fecha desde la tarjeta Coach de la consola."));
		}
		if (!w.teams.isEmpty())
		{
			in.append(section(en ? "ACTIVE BY TEAM" : "ACTIVAS POR EQUIPO"));
```

- [ ] **Step 6: Run the pure check.**

Run: `sh /private/tmp/claude-501/-Users-DSANJORGE-Code-EMEGenAILabs/09ac33b3-5de0-4ac1-a0e4-e8f8b25fc0ca/scratchpad/wcheck.sh WeeklySummaryEmailCheck`
Expected: last line `ALL OK` (no `FAIL` lines).

- [ ] **Step 7: Fill the learner fields.** In `learnerWeek()` replace
```java
			w.focus = new String[] {(String) rec.get("mode"), (String) rec.get("sectiontitle"), (String) rec.get("topictitle")};
		}
		return w;
```
with
```java
			w.focus = new String[] {(String) rec.get("mode"), (String) rec.get("sectiontitle"), (String) rec.get("topictitle")};
		}
		// Weekly objective (amendment 2026-10-06): the same mission.json the app shows (missionOf, the one server-side mission path).
		try
		{
			org.json.simple.JSONObject m = (org.json.simple.JSONObject) module.missionOf(archive, u, new Date())[0];
			org.json.simple.JSONObject g = (org.json.simple.JSONObject) m.get("goal");
			org.json.simple.JSONObject wk = (org.json.simple.JSONObject) m.get("week");
			if (g != null && wk != null)
			{
				w.goalStatus = String.valueOf(m.get("status"));
				w.goalTopic = (String) g.get("topictitle");
				w.goalLevel = (String) g.get("requiredlevel");
				w.goalDeadline = (String) g.get("deadline");
				w.goalBand = (String) g.get("band");
				w.goalPercent = LearningEngine.intOr(g.get("masterypercent"), 0);
				w.goalDone = LearningEngine.intOr(wk.get("sessionsdone"), 0);
				w.goalNeeded = LearningEngine.intOr(wk.get("sessionsneeded"), 0);
			}
		}
		catch (Exception e)
		{
			log.error("testu weeklysummaryemail: objective for " + uid, e); // the summary must still go out
		}
		return w;
```

- [ ] **Step 8: Coach suggestions without a request.** In `TestUAnalyticsModule.java` (`coachSuggestions(WebPageRequest, MediaArchive)`), replace
```java
		String cacheKey = archive.getCatalogId() + "|" + (scope == null ? "*" : new java.util.TreeSet<>(scope)) + "|" + teamKey;
		Object[] hit = COACH_CACHE.get(cacheKey);
		List<Map<String, Object>> all;
		if (hit != null && System.currentTimeMillis() - (Long) hit[0] < COACH_TTL_MS)
		{
			all = (List<Map<String, Object>>) hit[1];
		}
		else
		{
			long started = System.currentTimeMillis();
			all = computeCoachSuggestions(archive, scope, teamFilter, teamKey);
			log.info("coach suggestions computed in " + (System.currentTimeMillis() - started) + " ms (cache miss)");
			COACH_CACHE.put(cacheKey, new Object[] {System.currentTimeMillis(), all});
		}
		List<Map<String, Object>> out = new ArrayList<>();
```
with
```java
		List<Map<String, Object>> all = cachedCoachSuggestions(archive, scope, teamFilter, teamKey);
		List<Map<String, Object>> out = new ArrayList<>();
```
and add, right after the `COACH_CACHE` declaration line `	static final Map<String, Object[]> COACH_CACHE = new ConcurrentHashMap<>();`:
```java

	/** computeCoachSuggestions behind the per-JVM TTL cache (see the ponytail note in coachSuggestions). */
	private List<Map<String, Object>> cachedCoachSuggestions(MediaArchive archive, Set<String> scope, String teamFilter, String teamKey)
	{
		String cacheKey = archive.getCatalogId() + "|" + (scope == null ? "*" : new java.util.TreeSet<>(scope)) + "|" + teamKey;
		Object[] hit = COACH_CACHE.get(cacheKey);
		if (hit != null && System.currentTimeMillis() - (Long) hit[0] < COACH_TTL_MS)
		{
			return (List<Map<String, Object>>) hit[1];
		}
		long started = System.currentTimeMillis();
		List<Map<String, Object>> all = computeCoachSuggestions(archive, scope, teamFilter, teamKey);
		log.info("coach suggestions computed in " + (System.currentTimeMillis() - started) + " ms (cache miss)");
		COACH_CACHE.put(cacheKey, new Object[] {System.currentTimeMillis(), all});
		return all;
	}

	/** The Coach card's suggestions for a team scope (null = the whole org), with no team filter and no per-manager dismissals:
	 *  the Friday admin email's OBJETIVOS CON PLAZO counts (amendment 2026-10-06). Callers read counts and topic titles only. */
	List<Map<String, Object>> coachSuggestionsFor(MediaArchive archive, Set<String> scope)
	{
		return cachedCoachSuggestions(archive, scope, "", "all");
	}
```

- [ ] **Step 9: Fill the admin rows.** In `WeeklySummaryEmail.adminWeek()` replace
```java
				w.gaps.add(new String[] {String.valueOf(g.get("name")), String.valueOf(g.get("topic")), String.valueOf(g.get("beginners")), String.valueOf(g.get("people"))});
			}
		}
		return w;
```
with
```java
				w.gaps.add(new String[] {String.valueOf(g.get("name")), String.valueOf(g.get("topic")), String.valueOf(g.get("beginners")), String.valueOf(g.get("people"))});
			}
		}
		// Goals with a deadline (amendment 2026-10-06): the Coach card's own suggestions over this scope, counts only.
		try
		{
			TestUAnalyticsModule analyticsModule = (TestUAnalyticsModule) module.getModuleManager().getBean("TestUAnalyticsModule");
			w.deadlines = deadlineRows(analyticsModule.coachSuggestionsFor(archive, scope));
		}
		catch (Exception e)
		{
			log.error("testu weeklysummaryemail: goal counts", e); // the summary must still go out
		}
		return w;
```

- [ ] **Step 10: Live assertions** (verified in Task 10). In `tools/check_mission.sh` replace
```python
    ok("overdue suggestion lists the learner", sug and any(u["id"] == UID2 for u in sug["users"]), c)
```
with
```python
    ok("overdue suggestion lists the learner", sug and any(u["id"] == UID2 for u in sug["users"]), c)
    # amendment 2026-10-06: the Friday summaries carry the objective (learner) and the deadline counts (admin).
    s, wk = call(admin, "GET", f"/services/testu/learn/weeklysummaryemail.json?user={quote(UID)}&as=learner")
    h = (wk.get("html") or "") if s == 200 else ""
    ok("weekly summary: learner objective block", "TU OBJETIVO DE LA SEMANA" in h or "YOUR GOAL THIS WEEK" in h, str(wk)[:300])
    s, wk = call(admin, "GET", "/services/testu/learn/weeklysummaryemail.json?as=admin")
    h = (wk.get("html") or "") if s == 200 else ""
    ok("weekly summary: admin deadline counts", "OBJETIVOS CON PLAZO" in h or "GOALS WITH A DEADLINE" in h, str(wk)[:300])
```

- [ ] **Step 11: Compile + checks + python parse.**

Run: `sh /private/tmp/claude-501/-Users-DSANJORGE-Code-EMEGenAILabs/09ac33b3-5de0-4ac1-a0e4-e8f8b25fc0ca/scratchpad/wcheck.sh MissionPlannerCheck WeeklySummaryEmailCheck`
Expected: `MissionPlannerCheck: all ok`, then `ALL OK`.
Run: `python3 -c "import ast; src=open('/Users/DSANJORGE/Code/EMEGenAILabs/.worktrees/testu-weekly/tools/check_mission.sh').read(); ast.parse(src.split(\"<<'PY'\n\",1)[1].rsplit('\nPY',1)[0])"`
Expected: no output.

- [ ] **Step 12: Commit.**

```bash
git -C /Users/DSANJORGE/Code/EMEGenAILabs/.worktrees/testu-weekly add code/tech/genailabs/tutor/WeeklySummaryEmail.java code/tech/genailabs/tutor/TestUAnalyticsModule.java tools/WeeklySummaryEmailCheck.java tools/check_learning.sh tools/check_mission.sh
git -C /Users/DSANJORGE/Code/EMEGenAILabs/.worktrees/testu-weekly commit -m "feat(testu): Friday summaries -- learner weekly objective, admin goals with a deadline" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Phase 2 — Apps

All commands in this phase run from `/Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs/.worktrees/weekly`. Never `dart format`.

### Task 7: App models, reminder calls, push-tap event

**Files:**
- Modify: `lib/testu/testu_learn.dart:797-826` (`MissionGoal`), `:827-855` (`MissionState`), `:862-865` (`postRemind`)
- Modify: `lib/testu/testu_dates.dart` (append `testuRemindWhen`)
- Modify: `lib/testu/testu_usage.dart` (after `dailyDone`, `:157-172`), `lib/testu/testu_push.dart:76-83` (`_openPush`)
- Test: `test/testu_mission_test.dart`, `test/testu_dates_test.dart`, `test/testu_usage_test.dart`

**Interfaces:**
- Consumes: Tasks 1–2 wire shape (`week.todayanswers`, `week.todaycounted`, `goal.requiredmin`, `reminder{remindat, topic}`, `remind.json` `remindat`/`cancelled`); Task 4 push `data.kind`; Task 5 usage type `push_open`.
- Produces:
  - `MissionState.todayAnswers : int`, `MissionState.todayCounted : bool`, `MissionState.reminder : MissionReminder?`; `MissionGoal.requiredMin : int`.
  - `class MissionReminder { final String remindAt, topic; }` (remindAt = server ISO string).
  - `Future<String?> postRemind(EmeHttp http, {required String topic, required String when})` (returns `remindat`).
  - `Future<bool> cancelRemind(EmeHttp http)`.
  - `String testuRemindWhen(String iso, {DateTime? now})` → "tonight at 19:00" / "esta noche a las 19:00", "today at …", "tomorrow at …", "9 Oct at …"; '' when unparseable.
  - `Future<void> TestuUsage.pushOpen(Map<String, dynamic> data)`.

- [ ] **Step 1: Write the failing tests.**

In `test/testu_mission_test.dart`, after the test `'MissionState no_goal has no goal and no actions'` add:
```dart
  test('MissionState parses the weekly fields and a pending reminder', () {
    final m = MissionState.fromJson({
      'status': 'at_risk',
      'goal': {'topic': 'loto', 'topictitle': 'LOTO', 'status': 'at_risk', 'requiredlevel': 'competent', 'requiredmin': 60, 'masterypercent': 52},
      'week': {'sessionsneeded': 3, 'sessionsdone': 1, 'todayanswers': 4, 'todaycounted': false},
      'reminder': {'remindat': '2026-10-06T19:00:00-05:00', 'topic': 'loto'},
      'actions': [],
    });
    expect(m.todayAnswers, 4);
    expect(m.todayCounted, isFalse);
    expect(m.goal!.requiredMin, 60);
    expect(m.reminder?.remindAt, '2026-10-06T19:00:00-05:00');
    expect(m.reminder?.topic, 'loto');
    expect(MissionState.fromJson({'status': 'no_goal'}).reminder, isNull);
  });

  test('postRemind returns remindat; cancelRemind posts when=cancel', () async {
    final http = FakeEmeHttp()
      ..canned['services/testu/learn/remind.json'] = {'ok': true, 'remindat': '2026-10-06T19:00:00-05:00', 'cancelled': true};
    expect(await postRemind(http, topic: 'loto', when: 'tonight'), '2026-10-06T19:00:00-05:00');
    expect(await cancelRemind(http), isTrue);
    expect(http.posted.last.fields, {'when': 'cancel'});
  });
```
In `test/testu_dates_test.dart`, before the final `}` of `main` add:
```dart

  test('testuRemindWhen: tonight, today, tomorrow, a later day', () {
    final now = DateTime(2026, 10, 6, 10, 0);
    expect(testuRemindWhen(DateTime(2026, 10, 6, 19, 0).toIso8601String(), now: now), 'tonight at 19:00');
    expect(testuRemindWhen(DateTime(2026, 10, 6, 12, 5).toIso8601String(), now: now), 'today at 12:05');
    expect(testuRemindWhen(DateTime(2026, 10, 7, 9, 0).toIso8601String(), now: now), 'tomorrow at 09:00');
    expect(testuRemindWhen(DateTime(2026, 10, 9, 9, 0).toIso8601String(), now: now), '9 Oct at 09:00');
    expect(testuRemindWhen('nope', now: now), '');
  });
```
In `test/testu_usage_test.dart`, after the test `'sourceOpen and sessionLeave queue their fields'` add:
```dart
  test('pushOpen queues the push payload as a push_open event', () async {
    final u = TestuUsage(
        http: FakeEmeHttp(), platform: 'test', appVersion: '1.1.1+6');
    await u.pushOpen({'type': 'mission', 'kind': 'unfinished', 'entitytopic': 'loto', 'id': 'u_mission_unfinished_20261006'});
    final q = (await SharedPreferences.getInstance())
        .getString('testu_usage_queue')!;
    expect(q, contains('"type":"push_open"'));
    expect(q, contains('"campaign":"mission"'));
    expect(q, contains('"source":"unfinished"'));
    expect(q, contains('"entitytopic":"loto"'));
    expect(q, contains('"notification":"u_mission_unfinished_20261006"'));
  });
```

- [ ] **Step 2: Run them to make sure they fail.**

Run: `flutter test test/testu_mission_test.dart test/testu_dates_test.dart test/testu_usage_test.dart`
Expected: compilation errors (`todayAnswers`, `cancelRemind`, `testuRemindWhen`, `pushOpen` not defined).

- [ ] **Step 3: Models.** In `lib/testu/testu_learn.dart`:

Replace
```dart
        deadlineSource = _str(j['deadlinesource']),
        daysLeft = j['daysleft'] == null ? null : _int(j['daysleft']);

  final String topic, topicTitle, status;
  final String? requiredLevel, band, deadline, deadlineSource;
  final int masteryPercent, gap;
  final int? daysLeft;
}
```
with
```dart
        deadlineSource = _str(j['deadlinesource']),
        daysLeft = j['daysleft'] == null ? null : _int(j['daysleft']),
        requiredMin = _int(j['requiredmin']);

  final String topic, topicTitle, status;
  final String? requiredLevel, band, deadline, deadlineSource;
  final int masteryPercent, gap;
  final int? daysLeft;

  /// The required band's minimum percent: the level bar's marker (0 = unknown, no marker).
  final int requiredMin;
}

/// mission.json's `reminder`: the learner's pending "remind me later" (not
/// pushed yet, still ahead). The Today card collapses to one line while it
/// is set.
class MissionReminder {
  MissionReminder.fromJson(Map<String, dynamic> j)
      : remindAt = _str(j['remindat']) ?? '',
        topic = _str(j['topic']) ?? '';

  /// ISO instant, as the server wrote it.
  final String remindAt, topic;
}
```

Replace
```dart
        sessionsDone = _int((j['week'] as Map<String, dynamic>?)?['sessionsdone']),
        otherGoals = _int(j['othergoals']),
```
with
```dart
        sessionsDone = _int((j['week'] as Map<String, dynamic>?)?['sessionsdone']),
        todayAnswers = _int((j['week'] as Map<String, dynamic>?)?['todayanswers']),
        todayCounted = (j['week'] as Map<String, dynamic>?)?['todaycounted'] == true,
        reminder = j['reminder'] == null ? null : MissionReminder.fromJson(j['reminder'] as Map<String, dynamic>),
        otherGoals = _int(j['othergoals']),
```
and replace
```dart
  final String status;
  final MissionGoal? goal;
  final int sessionsNeeded, sessionsDone, otherGoals;
```
with
```dart
  final String status;
  final MissionGoal? goal;
  final int sessionsNeeded, sessionsDone, otherGoals;

  /// Learning answers on the goal topic today; the day counts from 5
  /// (`MissionPlanner.DAY_MIN_ANSWERS`, [todayCounted]).
  final int todayAnswers;
  final bool todayCounted;
  final MissionReminder? reminder;
```

Replace
```dart
/// remind.json: snoozes the current goal's reminder (`when`: one of
/// `MissionPlanner.REMIND_WHEN`, e.g. `2h`, `tonight`, `tomorrow`).
Future<void> postRemind(EmeHttp http, {required String topic, required String when}) =>
    _call(() => http.postForm('$_base/remind.json', [MapEntry('topic', topic), MapEntry('when', when)]));
```
with
```dart
/// remind.json: snoozes the current goal's reminder (`when`: one of
/// `MissionPlanner.REMIND_WHEN`, e.g. `2h`, `tonight`, `tomorrow`).
/// Returns the server's `remindat` (ISO), so the time can show at once.
Future<String?> postRemind(EmeHttp http, {required String topic, required String when}) async =>
    _str((await _call(() => http.postForm('$_base/remind.json', [MapEntry('topic', topic), MapEntry('when', when)])))['remindat']);

/// remind.json `when=cancel`: drops the pending reminder unless it already
/// went out. True when one was cancelled.
Future<bool> cancelRemind(EmeHttp http) async =>
    (await _call(() => http.postForm('$_base/remind.json', const [MapEntry('when', 'cancel')])))['cancelled'] == true;
```

- [ ] **Step 4: `testuRemindWhen`.** Append to `lib/testu/testu_dates.dart`:
```dart

/// When a mission reminder fires, for the Today card's collapsed line:
/// "tonight at 19:00" / "esta noche a las 19:00" (today from 18:00),
/// "today at 12:05", "tomorrow at 09:00", else "9 Oct at 09:00". Local
/// clock; '' when [iso] is unparseable.
String testuRemindWhen(String iso, {DateTime? now}) {
  final a = DateTime.tryParse(iso)?.toLocal();
  if (a == null) return '';
  final n = now ?? DateTime.now();
  final hm = '${a.hour.toString().padLeft(2, '0')}:${a.minute.toString().padLeft(2, '0')}';
  final days = DateTime.utc(a.year, a.month, a.day).difference(DateTime.utc(n.year, n.month, n.day)).inDays;
  if (days == 0) {
    return a.hour >= 18 ? L('tonight at $hm', 'esta noche a las $hm') : L('today at $hm', 'hoy a las $hm');
  }
  if (days == 1) return L('tomorrow at $hm', 'mañana a las $hm');
  return L('${a.day} ${_monthsEnShort[a.month - 1]} at $hm', '${a.day} ${_monthsEsShort[a.month - 1]} a las $hm');
}
```

- [ ] **Step 5: `pushOpen` + the tap.** In `lib/testu/testu_usage.dart`, directly before the doc comment `  /// Called from [TestuAuth.signOut] while the cookie is still good: ship` add:
```dart
  /// A push notification opened (spec amendment 2026-10-06): [data] is the
  /// FCM payload — `type` (bell type) → campaign, `kind` (mission push
  /// kind) → source, `entitytopic`, `id` → notification. The console counts
  /// taps per kind within 24 h of each push.
  Future<void> pushOpen(Map<String, dynamic> data) => _run(() async {
        await _enqueue({
          'type': 'push_open',
          'campaign': '${data['type'] ?? ''}',
          'source': '${data['kind'] ?? ''}',
          'entitytopic': '${data['entitytopic'] ?? ''}',
          'notification': '${data['id'] ?? ''}',
        });
        await _flush();
      });

```
In `lib/testu/testu_push.dart` replace
```dart
  setTestuEntry((src: 'push', campaign: '${m.data['type'] ?? ''}'.isEmpty ? null : '${m.data['type']}'));
```
with
```dart
  setTestuEntry((src: 'push', campaign: '${m.data['type'] ?? ''}'.isEmpty ? null : '${m.data['type']}'));
  unawaited(testuUsage.pushOpen(m.data));
```
(`dart:async` and `testu_usage.dart` are already imported there.)

- [ ] **Step 6: Run the tests.**

Run: `flutter test test/testu_mission_test.dart test/testu_dates_test.dart test/testu_usage_test.dart`
Expected: `All tests passed!`
Run: `flutter analyze lib/testu/testu_learn.dart lib/testu/testu_dates.dart lib/testu/testu_usage.dart lib/testu/testu_push.dart lib/testu/testu_actions.dart`
Expected: `No issues found!` (the existing `await postRemind(...)` in `testu_actions.dart` ignores the new return value; that is fine).

- [ ] **Step 7: Commit.**

```bash
git -C /Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs/.worktrees/weekly add lib/testu/testu_learn.dart lib/testu/testu_dates.dart lib/testu/testu_usage.dart lib/testu/testu_push.dart test/testu_mission_test.dart test/testu_dates_test.dart test/testu_usage_test.dart
git -C /Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs/.worktrees/weekly commit -m "feat(testu): weekly objective models, reminder undo, push_open usage event" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 8: Today card — "Tu objetivo de la semana"

**Files:**
- Modify: `lib/testu/testu_live.dart:696` (public `liveTopics()` after `_fetchTopics`)
- Modify: `lib/testu/testu_topics.dart:316-319` (`liveTopicCover` after `_liveCoverUrl`)
- Modify: `lib/testu/testu_actions.dart:199-245` (`ActionButtons.labelOf`, `onDone`)
- Modify: `lib/testu/testu_shell.dart:851-1010` (`_MissionCardState.build`, helpers `:899-935`, `_MissionCardBody` `:937-1010`)
- Test: `test/testu_mission_test.dart` (helper `missionJson` `:193-205`, at_risk test `:209-241`, pace test `:286-307`, new tests)

**Interfaces:**
- Consumes: Task 7 `MissionState.todayAnswers/todayCounted/reminder`, `MissionGoal.requiredMin`, `cancelRemind`, `testuRemindWhen`; existing `actionLabel`, `runMissionAction`, `ActionButtons`, `TestuCard`, `TestuCover`, `testuImage`, `TestuPill.*`, `TestuEyebrow`, `TestuChip`, `TestuAct`, `bandStyle`, `testuShortDay`.
- Produces: `Future<List<Topic>> liveTopics()`; `Future<String?> liveTopicCover(String topicId)`; `ActionButtons({…, String Function(MissionAction a)? labelOf, VoidCallback? onDone})`; `const kMissionDayMinAnswers = 5`; `bool missionWeekDone(MissionState m)`; `bool missionUnfinished(MissionState m)`; `String missionWhenLine(MissionGoal g)`. Widget keys: `Key('mission-card')` (both layouts), `ValueKey('week-dot-done' | 'week-dot-today' | 'week-dot')`.

Card rules (spec amendment + prototype):
- Eyebrow `YOUR GOAL THIS WEEK` / `TU OBJETIVO DE LA SEMANA` (orange); certification goals keep `CERTIFICATION` / `CERTIFICACIÓN` (gold). Pill right.
- Title: `<Level> in <Topic>` / `<Nivel> en <Tema>`; pace `Practise <Topic>` / `Practicar <Tema>`; certification keeps `Renew your <Topic> certification`.
- When line: `By 11 Oct · 5 days left` / `Antes del 11 oct · quedan 5 días`; ≥ 14 days `· N weeks left` / `· quedan N semanas`; `Was due 11 Oct` / `Venció el 11 oct`; `No deadline` / `Sin fecha límite`.
- Cover: goal topic cover from the topics list (brand block when none). Layout: under 520 px wide the cover is a 120 px banner on top (fade down); wider, a 132 px column on the left (fade right).
- Level bar with a marker at `requiredMin`; legend `<Band> · NN%` … `<Required> · MM%`.
- Week dots: one per needed session; filled = counted; ring = today in progress.
- Pills: At risk (amber) · On track (green) · Deadline passed (red) · Ready to take the evaluation (green) · At your pace (gray) · Week done (green).
- Week done (`sessionsdone >= sessionsneeded`, status not ready/overdue): pill Week done, line `N of N · see you Monday`, single quiet `Practise anyway` / `Practicar igual` (the `start_session` action).
- Reminder pending: one line (40 px cover thumb, `Reminder: <when>` / `Te lo recuerdo: <cuándo>`, goal + when line) with `Undo`/`Deshacer` (→ `cancelRemind`, refetch) and `View`/`Ver` (expands for this view only).
- Unfinished (`todayAnswers` 1..4, not counted, week not done): box `Today's session is half done: N of 5 questions. With 5 it counts for the week.` / `Sesión de hoy a medias: N de 5 preguntas. Con 5 ya cuenta para la semana.`; the `start_session` button reads `Resume session` / `Retomar sesión`.
- Overdue adds `No pressure: a short session today and we pick the pace back up.` / `Sin presión: una sesión corta hoy y retomamos el ritmo.` (prototype).
- Ready: the server's own actions (`Take the evaluation`, `Remind me later`); week line `Level reached · the evaluation is open` / `Nivel alcanzado · la evaluación está abierta`.

- [ ] **Step 1: Write the failing tests.** In `test/testu_mission_test.dart`:

Replace the helper
```dart
  Map<String, dynamic> missionJson({
    required String status,
    Map<String, dynamic>? goal,
    int sessionsNeeded = 0,
    int sessionsDone = 0,
    int otherGoals = 0,
    List<Map<String, dynamic>> actions = const [],
  }) => {
        'status': status,
        'goal': goal,
        'week': {'sessionsneeded': sessionsNeeded, 'sessionsdone': sessionsDone},
        'othergoals': otherGoals,
        'actions': actions,
      };
```
with
```dart
  Map<String, dynamic> missionJson({
    required String status,
    Map<String, dynamic>? goal,
    int sessionsNeeded = 0,
    int sessionsDone = 0,
    int todayAnswers = 0,
    int otherGoals = 0,
    List<Map<String, dynamic>> actions = const [],
    Map<String, dynamic>? reminder,
  }) => {
        'status': status,
        'goal': goal,
        'week': {'sessionsneeded': sessionsNeeded, 'sessionsdone': sessionsDone, 'todayanswers': todayAnswers, 'todaycounted': todayAnswers >= 5},
        'othergoals': otherGoals,
        'actions': actions,
        'reminder': reminder,
      };

  Map<String, dynamic> fatiga(String status) => {
        'topic': 'fatiga',
        'topictitle': 'Fatiga',
        'status': status,
        'requiredlevel': 'competent',
        'requiredmin': 60,
        'band': 'beginner',
        'masterypercent': 48,
        'gap': 12,
        'deadline': '2026-11-14',
        'daysleft': 40,
      };
```
In the at_risk test, replace
```dart
          'masterypercent': 52,
          'gap': 8,
          'deadline': '2026-10-09',
          'deadlinesource': 'profile',
          'daysleft': 4,
        },
        sessionsNeeded: 3,
        sessionsDone: 1,
        otherGoals: 1,
```
with
```dart
          'masterypercent': 52,
          'gap': 8,
          'requiredmin': 60,
          'deadline': '2026-10-09',
          'deadlinesource': 'profile',
          'daysleft': 4,
        },
        sessionsNeeded: 3,
        sessionsDone: 1,
        otherGoals: 1,
```
and replace
```dart
    expect(find.text('At risk — 3 sessions this week'), findsOneWidget);
    expect(find.text('Beginner · 52% → Competent'), findsOneWidget);
    expect(find.text('This week: 1 of 3 sessions'), findsOneWidget);
    expect(find.text('Start 6-min session'), findsOneWidget);
  });
```
with
```dart
    expect(find.text('YOUR GOAL THIS WEEK'), findsOneWidget);
    expect(find.text('At risk'), findsOneWidget);
    expect(find.text('Competent in LOTO'), findsOneWidget);
    expect(find.text('By 9 Oct · 4 days left'), findsOneWidget);
    expect(find.text('Beginner · 52%'), findsOneWidget);
    expect(find.text('Competent · 60%'), findsOneWidget);
    expect(find.text('1 of 3 sessions this week'), findsOneWidget);
    expect(find.byKey(const ValueKey('week-dot-done')), findsOneWidget);
    expect(find.text('Start 6-min session'), findsOneWidget);
  });
```
In the pace test replace
```dart
    expect(find.byKey(const Key('mission-card')), findsOneWidget);
    expect(find.text('Start 6-min session'), findsOneWidget);
  });

  testWidgets(
      'Today: a certification-driven goal is labelled as a certification even without schedule_certification',
```
with
```dart
    expect(find.byKey(const Key('mission-card')), findsOneWidget);
    expect(find.text('Start 6-min session'), findsOneWidget);
    expect(find.text('Practise Fatiga'), findsOneWidget);
    expect(find.text('No deadline'), findsOneWidget);
    expect(find.text('At your pace'), findsOneWidget);
  });

  testWidgets('Today: a full week goes quiet with a single "Practise anyway"',
      (tester) async {
    final http = FakeEmeHttp()
      ..canned[missionPath] = missionJson(
        status: 'on_track',
        goal: fatiga('on_track'),
        sessionsNeeded: 2,
        sessionsDone: 2,
        actions: [
          {'type': 'start_session', 'topic': 'fatiga'},
          {'type': 'remind_later', 'topic': 'fatiga'},
        ],
      );
    await _pump(tester, TestuTodayScreen(http: http));
    await tester.pumpAndSettle();
    expect(find.text('Week done'), findsOneWidget);
    expect(find.text('2 of 2 · see you Monday'), findsOneWidget);
    expect(find.text('Practise anyway'), findsOneWidget);
    expect(find.text('Remind me later'), findsNothing);
  });

  testWidgets('Today: an unfinished session shows "N of 5" and Resume session',
      (tester) async {
    final http = FakeEmeHttp()
      ..canned[missionPath] = missionJson(
        status: 'on_track',
        goal: fatiga('on_track'),
        sessionsNeeded: 3,
        sessionsDone: 1,
        todayAnswers: 3,
        actions: [
          {'type': 'start_session', 'topic': 'fatiga'},
          {'type': 'remind_later', 'topic': 'fatiga'},
        ],
      );
    await _pump(tester, TestuTodayScreen(http: http));
    await tester.pumpAndSettle();
    expect(
        find.text("Today's session is half done: 3 of 5 questions. With 5 it counts for the week."),
        findsOneWidget);
    expect(find.text('Resume session'), findsOneWidget);
    expect(find.text('Start 6-min session'), findsNothing);
    expect(find.byKey(const ValueKey('week-dot-today')), findsOneWidget);
  });

  testWidgets('Today: a pending reminder collapses the card; View expands it',
      (tester) async {
    final http = FakeEmeHttp()
      ..canned[missionPath] = missionJson(
        status: 'on_track',
        goal: fatiga('on_track'),
        sessionsNeeded: 2,
        sessionsDone: 1,
        actions: [
          {'type': 'start_session', 'topic': 'fatiga'},
        ],
        reminder: {'remindat': '2026-10-06T19:00:00-05:00', 'topic': 'fatiga'},
      );
    await _pump(tester, TestuTodayScreen(http: http));
    await tester.pumpAndSettle();
    expect(find.textContaining('Reminder: '), findsOneWidget);
    expect(find.text('Start 6-min session'), findsNothing);
    await tester.tap(find.text('View'));
    await tester.pumpAndSettle();
    expect(find.text('Start 6-min session'), findsOneWidget);
  });

  testWidgets('Today: Undo cancels the pending reminder', (tester) async {
    final http = FakeEmeHttp()
      ..canned[missionPath] = missionJson(
        status: 'on_track',
        goal: fatiga('on_track'),
        sessionsNeeded: 2,
        sessionsDone: 1,
        actions: [
          {'type': 'start_session', 'topic': 'fatiga'},
        ],
        reminder: {'remindat': '2026-10-06T19:00:00-05:00', 'topic': 'fatiga'},
      )
      ..canned['services/testu/learn/remind.json'] = {'ok': true, 'cancelled': true};
    await _pump(tester, TestuTodayScreen(http: http));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Undo'));
    await tester.pumpAndSettle();
    final cancel = http.posted.singleWhere((p) => p.path.endsWith('remind.json'));
    expect(cancel.fields, {'when': 'cancel'});
  });

  testWidgets(
      'Today: a certification-driven goal is labelled as a certification even without schedule_certification',
```

- [ ] **Step 2: Run them to make sure they fail.**

Run: `flutter test test/testu_mission_test.dart`
Expected: FAIL — e.g. `Expected: exactly one matching candidate … 'YOUR GOAL THIS WEEK'`, `'Week done'`, `'Resume session'`, `'Reminder: '`.

- [ ] **Step 3: Cover lookup.** In `lib/testu/testu_live.dart`, right after the closing `}` of `Future<List<Topic>> _fetchTopics() {…}` add:
```dart

/// [_fetchTopics] for other screens: the Today mission card's cover lookup.
Future<List<Topic>> liveTopics() => _fetchTopics();
```
In `lib/testu/testu_topics.dart`, right after `_liveCoverUrl` (the statement ending `liveAssetUrl(t.thumbnail.replaceFirst('image200x200', 'image3000x3000'));`) add:
```dart

/// The big cover of [topicId] from the topics list (the lookup the Topics
/// tab and the Today hero use). Null when it has no picture or the list
/// cannot load — the mission card then shows the brand block.
Future<String?> liveTopicCover(String topicId) async {
  try {
    return _liveCoverUrl((await liveTopics()).where((t) => t.id == topicId).firstOrNull);
  } catch (_) {
    return null;
  }
}
```

- [ ] **Step 4: `ActionButtons.labelOf` / `onDone`.** In `lib/testu/testu_actions.dart` replace
```dart
  const ActionButtons(
      {super.key,
      required this.actions,
      required this.topicTitles,
      this.http});
```
with
```dart
  const ActionButtons(
      {super.key,
      required this.actions,
      required this.topicTitles,
      this.http,
      this.labelOf,
      this.onDone});
```
replace
```dart
  /// Test seam for the network calls [runMissionAction] makes; production
  /// callers omit it (defaults to [DioEmeHttp] at each call site).
  final EmeHttp? http;

  @override
  Widget build(BuildContext context) {
    final known = [
```
with
```dart
  /// Test seam for the network calls [runMissionAction] makes; production
  /// callers omit it (defaults to [DioEmeHttp] at each call site).
  final EmeHttp? http;

  /// The Today card's own wording for an action (e.g. "Resume session");
  /// null = [actionLabel].
  final String Function(MissionAction a)? labelOf;

  /// After an action went through (a reminder time picked, a session
  /// ended): the Today card refetches mission.json.
  final VoidCallback? onDone;

  @override
  Widget build(BuildContext context) {
    final known = [
```
and replace
```dart
          TestuChip(
            actionLabel(known[i], topicTitles[known[i].topic] ?? ''),
            primary: i == 0,
            onTap: () => runMissionAction(context, known[i],
                topicTitle: topicTitles[known[i].topic] ?? '', http: http),
          ),
```
with
```dart
          TestuChip(
            labelOf?.call(known[i]) ??
                actionLabel(known[i], topicTitles[known[i].topic] ?? ''),
            primary: i == 0,
            onTap: () async {
              if (await runMissionAction(context, known[i],
                  topicTitle: topicTitles[known[i].topic] ?? '', http: http)) {
                onDone?.call();
              }
            },
          ),
```

- [ ] **Step 5: The card.** In `lib/testu/testu_shell.dart`:

In `_MissionCardState.build` replace
```dart
        return Column(
          children: [
            _MissionCardBody(mission: m, http: widget.http),
            const SizedBox(height: 12),
          ],
        );
```
with
```dart
        return Column(
          children: [
            _MissionCardBody(
              // A new goal or reminder starts the body fresh (cover lookup,
              // the reminder line's "View").
              key: ValueKey('${m.goal!.topic}|${m.reminder?.remindAt}'),
              mission: m,
              http: widget.http,
              onChanged: _reload,
            ),
            const SizedBox(height: 12),
          ],
        );
```

Replace everything from the line
```dart
/// `at_risk`'s "N sessions this week" and the week-progress line both read
```
through the closing `}` of `class _MissionCardBody` (the line before `class _ContinueHero extends StatefulWidget {`) with:

```dart
/// `MissionPlanner.DAY_MIN_ANSWERS`: a day counts for the week from this many
/// answers on the goal topic (spec amendment 2026-10-06).
const kMissionDayMinAnswers = 5;

/// Week done: the dots are full and nothing urgent is pending — the card
/// goes quiet until Monday.
bool missionWeekDone(MissionState m) =>
    m.sessionsNeeded > 0 &&
    m.sessionsDone >= m.sessionsNeeded &&
    m.status != 'ready' &&
    m.status != 'overdue';

/// Today's session started but does not count yet (1–4 answers of 5).
bool missionUnfinished(MissionState m) =>
    m.todayAnswers > 0 &&
    !m.todayCounted &&
    m.todayAnswers < kMissionDayMinAnswers;

TestuPill _missionPill(MissionState m) {
  if (missionWeekDone(m)) return TestuPill.green(L('Week done', 'Semana cumplida'));
  return switch (m.status) {
    'at_risk' => TestuPill.amber(L('At risk', 'En riesgo')),
    'on_track' => TestuPill.green(L('On track', 'Vas a tiempo')),
    'overdue' => TestuPill.red(L('Deadline passed', 'Plazo vencido')),
    'ready' => TestuPill.green(L('Ready to take the evaluation', 'Listo para evaluar')),
    _ => TestuPill.gray(L('At your pace', 'A tu ritmo')),
  };
}

/// Only the goal — "objetivo" is the eyebrow's word, never the title's.
String _missionTitle(MissionGoal g, {required bool cert}) {
  if (cert) {
    return L('Renew your ${g.topicTitle} certification',
        'Renueva tu certificación de ${g.topicTitle}');
  }
  if (g.status == 'pace' || g.requiredLevel == null) {
    return L('Practise ${g.topicTitle}', 'Practicar ${g.topicTitle}');
  }
  final level = bandStyle(g.requiredLevel).label;
  return L('$level in ${g.topicTitle}', '$level en ${g.topicTitle}');
}

/// "By 11 Oct · 5 days left" / "Was due 11 Oct" / "No deadline".
String missionWhenLine(MissionGoal g) {
  final day = g.deadline == null ? '' : testuShortDay(g.deadline!);
  if (day.isEmpty) return L('No deadline', 'Sin fecha límite');
  final left = g.daysLeft;
  if (g.status == 'overdue' || (left != null && left < 0)) {
    return L('Was due $day', 'Venció el $day');
  }
  final rest = left == null
      ? ''
      : left == 0
          ? L(' · due today', ' · vence hoy')
          : left == 1
              ? L(' · 1 day left', ' · queda 1 día')
              : left < 14
                  ? L(' · $left days left', ' · quedan $left días')
                  : L(' · ${left ~/ 7} weeks left', ' · quedan ${left ~/ 7} semanas');
  return L('By $day', 'Antes del $day') + rest;
}

String _missionWeekLabel(MissionState m) {
  final n = m.sessionsNeeded;
  if (missionWeekDone(m)) {
    return L('$n of $n · see you Monday', '$n de $n · nos vemos el lunes');
  }
  if (m.status == 'ready') {
    return L('Level reached · the evaluation is open',
        'Nivel alcanzado · la evaluación está abierta');
  }
  final s = n == 1 ? L('session', 'sesión') : L('sessions', 'sesiones');
  return L('${m.sessionsDone} of $n $s this week',
      '${m.sessionsDone} de $n $s esta semana');
}

/// "Tu objetivo de la semana" (spec amendment 2026-10-06, approved
/// prototype v1): the goal topic's cover, level bar with the required
/// marker, one dot per session this week, then the actions. A pending
/// reminder collapses it to one line; a full week makes it quiet.
class _MissionCardBody extends StatefulWidget {
  const _MissionCardBody(
      {super.key, required this.mission, this.http, required this.onChanged});

  final MissionState mission;
  final EmeHttp? http;

  /// Refetches mission.json (a reminder set or undone changes the card).
  final VoidCallback onChanged;

  @override
  State<_MissionCardBody> createState() => _MissionCardBodyState();
}

class _MissionCardBodyState extends State<_MissionCardBody> {
  // "View" opens the full card over a pending reminder, for this view only.
  bool _expanded = false;

  late final Future<String?> _coverUrl =
      liveTopicCover(widget.mission.goal!.topic);

  MissionState get m => widget.mission;

  // A certification renewal reads as a mission goal like any other (spec:
  // "Certification goals render in the same card"); the tell is where the
  // deadline came from.
  bool get _isCert => m.goal?.deadlineSource == 'certification';

  Widget _cover() => FutureBuilder<String?>(
        future: _coverUrl,
        builder: (context, snap) => TestuCover(
          image: snap.data == null ? null : testuImage(snap.data!),
          title: m.goal!.topicTitle,
        ),
      );

  Widget _fadedCover(TestuTokens t, {required bool vertical}) => Stack(
        fit: StackFit.expand,
        children: [
          _cover(),
          DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: vertical ? Alignment.topCenter : Alignment.centerLeft,
                end: vertical ? Alignment.bottomCenter : Alignment.centerRight,
                stops: vertical ? const [0.45, 1.0] : const [0.55, 1.0],
                colors: [t.card.withValues(alpha: 0), t.card],
              ),
            ),
          ),
        ],
      );

  Future<void> _undo() async {
    try {
      await cancelRemind(widget.http ?? DioEmeHttp());
    } catch (e) {
      debugPrint('TestU: cancel reminder ($e)');
    }
    widget.onChanged();
  }

  @override
  Widget build(BuildContext context) {
    final r = m.reminder;
    if (r != null && !_expanded) return _collapsed(context, r);
    return _full(context);
  }

  Widget _collapsed(BuildContext context, MissionReminder r) {
    final t = TestuTokens.of(context);
    final g = m.goal!;
    final when = testuRemindWhen(r.remindAt);
    final link = TextStyle(
      fontFamily: 'Geist',
      fontWeight: FontWeight.w500,
      fontSize: 12.5,
      color: t.inkDim,
      decoration: TextDecoration.underline,
    );
    return TestuCard(
      key: const Key('mission-card'),
      padding: const EdgeInsets.fromLTRB(14, 12, 10, 12),
      child: Row(
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(9),
            child: SizedBox(width: 40, height: 40, child: _cover()),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  L('Reminder: $when', 'Te lo recuerdo: $when'),
                  style: TextStyle(
                    fontFamily: 'Geist',
                    fontWeight: FontWeight.w600,
                    fontSize: 13,
                    color: t.ink,
                  ),
                ),
                Text(
                  '${_missionTitle(g, cert: _isCert)} · ${missionWhenLine(g)}',
                  style: TextStyle(fontFamily: 'Geist', fontSize: 12, color: t.mut),
                ),
              ],
            ),
          ),
          TestuPressable(
            onTap: _undo,
            child: Padding(
              padding: const EdgeInsets.all(6),
              child: Text(L('Undo', 'Deshacer'), style: link),
            ),
          ),
          TestuPressable(
            onTap: () => setState(() => _expanded = true),
            child: Padding(
              padding: const EdgeInsets.all(6),
              child: Text(L('View', 'Ver'), style: link),
            ),
          ),
        ],
      ),
    );
  }

  Widget _full(BuildContext context) {
    final t = TestuTokens.of(context);
    final g = m.goal!;
    final done = missionWeekDone(m);
    final unfinished = !done && missionUnfinished(m);
    final start =
        m.actions.where((a) => a.type == 'start_session').firstOrNull;
    final body = Padding(
      padding: const EdgeInsets.fromLTRB(14, 16, 16, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Flexible(
                child: TestuEyebrow(
                  _isCert
                      ? L('CERTIFICATION', 'CERTIFICACIÓN')
                      : L('YOUR GOAL THIS WEEK', 'TU OBJETIVO DE LA SEMANA'),
                  color: _isCert ? t.gold : t.orange,
                ),
              ),
              const SizedBox(width: 10),
              _missionPill(m),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            _missionTitle(g, cert: _isCert),
            style: TextStyle(
              fontFamily: 'Geist',
              fontWeight: FontWeight.w700,
              fontSize: 17,
              height: 1.25,
              color: t.ink,
            ),
          ),
          const SizedBox(height: 4),
          _CardBody(missionWhenLine(g)),
          if (g.requiredLevel != null && g.requiredMin > 0) ...[
            const SizedBox(height: 10),
            _LevelBar(goal: g),
          ],
          const SizedBox(height: 10),
          Row(
            children: [
              _WeekDots(
                  needed: m.sessionsNeeded,
                  done: m.sessionsDone,
                  today: unfinished),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  _missionWeekLabel(m),
                  style: TextStyle(
                      fontFamily: 'Geist', fontSize: 12.5, color: t.inkDim),
                ),
              ),
            ],
          ),
          if (unfinished) ...[
            const SizedBox(height: 10),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
              decoration: BoxDecoration(
                color: t.orange.withValues(alpha: 0.10),
                border: Border.all(color: t.orange.withValues(alpha: 0.35)),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Text(
                L("Today's session is half done: ${m.todayAnswers} of $kMissionDayMinAnswers questions. With $kMissionDayMinAnswers it counts for the week.",
                    'Sesión de hoy a medias: ${m.todayAnswers} de $kMissionDayMinAnswers preguntas. Con $kMissionDayMinAnswers ya cuenta para la semana.'),
                style: TextStyle(
                    fontFamily: 'Geist',
                    fontSize: 12.5,
                    height: 1.45,
                    color: t.inkDim),
              ),
            ),
          ],
          if (m.status == 'overdue') ...[
            const SizedBox(height: 8),
            _CardBody(L(
                'No pressure: a short session today and we pick the pace back up.',
                'Sin presión: una sesión corta hoy y retomamos el ritmo.')),
          ],
          const SizedBox(height: 12),
          if (done && start != null)
            TestuChip(
              L('Practise anyway', 'Practicar igual'),
              onTap: () => runMissionAction(context, start,
                  topicTitle: '', http: widget.http),
            )
          else if (!done)
            ActionButtons(
              actions: m.actions,
              topicTitles: const {},
              http: widget.http,
              labelOf: unfinished
                  ? (a) => a.type == 'start_session'
                      ? L('Resume session', 'Retomar sesión')
                      : actionLabel(a, '')
                  : null,
              onDone: widget.onChanged,
            ),
          if (m.otherGoals > 0) ...[
            const SizedBox(height: 10),
            TestuAct(
              L('+${m.otherGoals} more goals', '+${m.otherGoals} objetivos más'),
              onTap: () => TestuShell.tabRequest.value = 1,
            ),
          ],
        ],
      ),
    );
    return TestuCard(
      key: const Key('mission-card'),
      padding: EdgeInsets.zero,
      child: LayoutBuilder(
        builder: (context, box) => box.maxWidth < 520
            ? Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  SizedBox(height: 120, child: _fadedCover(t, vertical: true)),
                  body,
                ],
              )
            : Stack(
                children: [
                  Positioned(
                    left: 0,
                    top: 0,
                    bottom: 0,
                    width: 132,
                    child: _fadedCover(t, vertical: false),
                  ),
                  Padding(padding: const EdgeInsets.only(left: 132), child: body),
                ],
              ),
      ),
    );
  }
}

/// Current percent with a marker at the required minimum:
/// "<Band> · NN%" … "<Required> · MM%".
class _LevelBar extends StatelessWidget {
  const _LevelBar({required this.goal});

  final MissionGoal goal;

  @override
  Widget build(BuildContext context) {
    final t = TestuTokens.of(context);
    final pct = goal.masteryPercent.clamp(0, 100) / 100;
    final req = goal.requiredMin.clamp(0, 100) / 100;
    final legend = TextStyle(fontFamily: 'GeistMono', fontSize: 11, color: t.mut);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SizedBox(
          height: 14,
          child: LayoutBuilder(
            builder: (context, box) => Stack(
              children: [
                Positioned(
                  left: 0,
                  right: 0,
                  top: 4,
                  height: 6,
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                        color: t.line2, borderRadius: BorderRadius.circular(99)),
                  ),
                ),
                Positioned(
                  left: 0,
                  top: 4,
                  height: 6,
                  width: box.maxWidth * pct,
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                        color: t.orange, borderRadius: BorderRadius.circular(99)),
                  ),
                ),
                Positioned(
                  left: (box.maxWidth * req - 1).clamp(0.0, box.maxWidth - 2),
                  top: 0,
                  width: 2,
                  height: 14,
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                        color: t.inkDim, borderRadius: BorderRadius.circular(2)),
                  ),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 5),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text('${bandStyle(goal.band).label} · ${goal.masteryPercent}%',
                style: legend),
            Text('${bandStyle(goal.requiredLevel).label} · ${goal.requiredMin}%',
                style: legend),
          ],
        ),
      ],
    );
  }
}

/// One dot per needed session this week: filled = counted, ring = today in
/// progress (not counted yet).
class _WeekDots extends StatelessWidget {
  const _WeekDots(
      {required this.needed, required this.done, required this.today});

  final int needed, done;
  final bool today;

  @override
  Widget build(BuildContext context) {
    final t = TestuTokens.of(context);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (var i = 0; i < needed; i++) ...[
          if (i > 0) const SizedBox(width: 6),
          Container(
            key: ValueKey(i < done
                ? 'week-dot-done'
                : i == done && today
                    ? 'week-dot-today'
                    : 'week-dot'),
            width: 14,
            height: 14,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: i < done ? t.orange : null,
              border: Border.all(
                  color: i < done || (i == done && today) ? t.orange : t.line2,
                  width: 1.5),
            ),
          ),
        ],
      ],
    );
  }
}
```

- [ ] **Step 6: Run the tests.**

Run: `flutter test test/testu_mission_test.dart`
Expected: `All tests passed!` (if `find.text('At risk')` matches more than one widget on Today, narrow it with `find.descendant(of: find.byKey(const Key('mission-card')), matching: find.text('At risk'))`; same for any other duplicate.)
Run: `flutter analyze lib/testu/testu_shell.dart lib/testu/testu_actions.dart lib/testu/testu_topics.dart lib/testu/testu_live.dart`
Expected: `No issues found!`
Run: `flutter test test/testu_cite_test.dart test/testu_topics_test.dart test/testu_tabs_test.dart`
Expected: `All tests passed!` (ActionButtons/Today callers unchanged in behaviour).

- [ ] **Step 7: Commit.** (The widget tests run at the default 800 px surface, i.e. the wide cover-left layout; the 390 px phone layout is eyeballed in Task 10 Step 4.)

```bash
git -C /Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs/.worktrees/weekly add lib/testu/testu_shell.dart lib/testu/testu_actions.dart lib/testu/testu_topics.dart lib/testu/testu_live.dart test/testu_mission_test.dart
git -C /Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs/.worktrees/weekly commit -m "feat(testu): Today card 'Tu objetivo de la semana' (cover, level marker, week dots, reminder/unfinished/week-done states)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 9: Console card "Recordatorios del objetivo"

**Files:**
- Modify: `lib/admin/admin_engagement.dart` — new `MissionPushes` class after `DailyOpens` (`:59-70`), `Engagement` parse + field (`:74-136`), build children (`:222-224`), new `_missionPushes` after `_dailyOpens` (`:244-290`)
- Test: `test/admin_engagement_test.dart`

**Interfaces:**
- Consumes: Task 5 engagement.json `missionpushes.<kind>.{sent, tappedrate, practisedrate, countedrate}`.
- Produces: `class MissionPushes { final int sent; final double? tappedRate, practisedRate, countedRate; }`; `Engagement.missionPushes : Map<String, MissionPushes>` keyed in display order `remind, unfinished, ready, at_risk, overdue, coach`; widget keys `ValueKey('missionpush-<kind>')`.

- [ ] **Step 1: Write the failing tests.** In `test/admin_engagement_test.dart`, before `  testWidgets('the Daily Challenge "done" funnel is empty when nobody saw it', (tester) async {` add:
```dart
  testWidgets('Goal reminders: one row per kind sent, with tapped / practised / counted rates', (tester) async {
    await _pump(tester, {
      ..._json(),
      'missionpushes': {
        'remind': {'sent': 10, 'tapped': 4, 'practised': 6, 'counted': 3, 'tappedrate': 0.4, 'practisedrate': 0.6, 'countedrate': 0.3},
        'unfinished': {'sent': 5, 'tapped': 1, 'practised': 2, 'counted': 2, 'tappedrate': 0.2, 'practisedrate': 0.4, 'countedrate': 0.4},
        'coach': {'sent': 0, 'tapped': 0, 'practised': 0, 'counted': 0, 'tappedrate': null, 'practisedrate': null, 'countedrate': null},
      },
    });
    await tester.scrollUntilVisible(find.text('GOAL REMINDERS'), 200);
    expect(find.text('GOAL REMINDERS'), findsOneWidget);
    expect(find.text('10 sent · Tapped 40 % · Practised within 24 h 60 % · Day counted 30 %'), findsOneWidget);
    expect(find.text('Unfinished session (18:00)'), findsWidgets);
    expect(find.byKey(const ValueKey('missionpush-coach')), findsNothing);
  });

  testWidgets('Goal reminders: none sent, or an older server without the field', (tester) async {
    await _pump(tester, _json());
    await tester.scrollUntilVisible(find.text('No goal reminders were sent in this period.'), 200);
    expect(find.text('No goal reminders were sent in this period.'), findsOneWidget);
  });

```

- [ ] **Step 2: Run them to make sure they fail.**

Run: `flutter test test/admin_engagement_test.dart`
Expected: FAIL — `Bad state: … 'GOAL REMINDERS'` not found (scrollUntilVisible gives up).

- [ ] **Step 3: Model.** In `lib/admin/admin_engagement.dart`, after the closing `}` of `class DailyOpens` add:
```dart

/// One kind of goal push (engagement.json `missionpushes.<kind>`), counted by
/// the server: sent, and within 24 h of each push the share tapped,
/// practised (at least one answer on its topic) and day counted (5 answers
/// that day). Rates are over sent, null with none sent. Counts only.
class MissionPushes {
  MissionPushes(Map? j)
      : sent = DailyDoneFunnel._n(j, 'sent'),
        tappedRate = (j?['tappedrate'] as num?)?.toDouble(),
        practisedRate = (j?['practisedrate'] as num?)?.toDouble(),
        countedRate = (j?['countedrate'] as num?)?.toDouble();

  final int sent;
  final double? tappedRate, practisedRate, countedRate;
}
```
Replace
```dart
        opensByPlatform = {
          for (final p in const ['web', 'ios', 'android'])
            p: DailyOpens(((j['dailyopens'] as Map?)?['platforms'] as Map?)?[p] as Map?),
        };
```
with
```dart
        opensByPlatform = {
          for (final p in const ['web', 'ios', 'android'])
            p: DailyOpens(((j['dailyopens'] as Map?)?['platforms'] as Map?)?[p] as Map?),
        },
        missionPushes = {
          for (final k in const ['remind', 'unfinished', 'ready', 'at_risk', 'overdue', 'coach'])
            k: MissionPushes((j['missionpushes'] as Map?)?[k] as Map?),
        };
```
and replace
```dart
  /// Daily Challenge opens and completions per entry channel and platform,
  /// in display order.
  final Map<String, DailyOpens> opensByChannel, opensByPlatform;
}
```
with
```dart
  /// Daily Challenge opens and completions per entry channel and platform,
  /// in display order.
  final Map<String, DailyOpens> opensByChannel, opensByPlatform;

  /// Goal pushes per kind, in display order (spec amendment 2026-10-06).
  final Map<String, MissionPushes> missionPushes;
}
```

- [ ] **Step 4: Card.** Replace
```dart
        _dailyOpens(d),
        const SizedBox(height: 16),
        _dailyDone(d),
```
with
```dart
        _dailyOpens(d),
        const SizedBox(height: 16),
        _missionPushes(d),
        const SizedBox(height: 16),
        _dailyDone(d),
```
and, directly before `  // -------------------------------------------------- daily challenge done`, add:
```dart
  // ------------------------------------------------------ goal reminders

  static String pushKindLabel(String id) => switch (id) {
        'remind' => L('Reminder they asked for', 'Recordatorio que pidieron'),
        'unfinished' => L('Unfinished session (18:00)', 'Sesión a medias (18:00)'),
        'ready' => L('Ready for the evaluation', 'Listo para evaluar'),
        'at_risk' => L('At risk', 'En riesgo'),
        'overdue' => L('Deadline passed', 'Plazo vencido'),
        _ => L('Manager reminder', 'Recordatorio del responsable'),
      };

  /// Goal pushes per kind: how many went out, then tapped / practised / day
  /// counted within 24 h — the server's rates (engagement.json missionpushes).
  Widget _missionPushes(Engagement d) {
    final rows = [for (final e in d.missionPushes.entries) if (e.value.sent > 0) e];
    final max = rows.fold(0, (m, e) => e.value.sent > m ? e.value.sent : m);
    String pct(double? r) => r == null ? '–' : '${(100 * r).round()} %';
    return ChartCard(
      eyebrow: L('Goal reminders', 'Recordatorios del objetivo'),
      helpId: null,
      height: null,
      footnote: L(
          'Pushes about each person’s weekly goal. Within 24 h of each push: tapped = opened from the notification; '
              'practised = at least one answer on its topic; day counted = that day reached 5 answers on the topic. Counts only, no names.',
          'Notificaciones sobre el objetivo semanal de cada persona. En las 24 h siguientes a cada una: tocadas = abiertas desde la '
              'notificación; practicaron = al menos una respuesta en su tema; día contado = ese día llegó a 5 respuestas en el tema. '
              'Solo conteos, sin nombres.'),
      child: rows.isEmpty
          ? Text(L('No goal reminders were sent in this period.', 'No se enviaron recordatorios del objetivo en este periodo.'),
              style: AdminTokens.muted)
          : Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (final e in rows) ...[
                  BarRow(
                    label: pushKindLabel(e.key),
                    value: e.value.sent,
                    max: max,
                    tooltip: L('${pushKindLabel(e.key)} · ${e.value.sent} sent', '${pushKindLabel(e.key)} · ${e.value.sent} enviadas'),
                  ),
                  Padding(
                    padding: const EdgeInsets.only(bottom: 10),
                    child: Text(
                      L('${e.value.sent} sent · Tapped ${pct(e.value.tappedRate)} · Practised within 24 h ${pct(e.value.practisedRate)} · Day counted ${pct(e.value.countedRate)}',
                          '${e.value.sent} enviadas · Tocadas ${pct(e.value.tappedRate)} · Practicaron en 24 h ${pct(e.value.practisedRate)} · Día contado ${pct(e.value.countedRate)}'),
                      key: ValueKey('missionpush-${e.key}'),
                      style: AdminTokens.muted,
                    ),
                  ),
                ],
              ],
            ),
    );
  }

```

- [ ] **Step 5: Run the tests.**

Run: `flutter test test/admin_engagement_test.dart`
Expected: `All tests passed!`
Run: `flutter analyze lib/admin/admin_engagement.dart`
Expected: `No issues found!`

- [ ] **Step 6: Commit.**

```bash
git -C /Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs/.worktrees/weekly add lib/admin/admin_engagement.dart test/admin_engagement_test.dart
git -C /Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs/.worktrees/weekly commit -m "feat(admin): Actividad card 'Recordatorios del objetivo' (goal push effectiveness)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Phase 3 — Integration (controller-run, AFTER the merges)

### Task 10: Live verification, bundles, rollout notes

Precondition: the controller has merged `feat/weekly-objective` into `main` in the testu plugin repo (`/Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/testu`) and in `app-genailabs`; Task 3's commits are on catalog/site `main`. Do not start before that.

**Files:**
- Modify: `/Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/testu/docs/superpowers/specs/2026-10-05-mission-agent-design.md` (§Rollout)
- Regenerate: `/Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/webapp/site/learn/`, `/Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/webapp/site/admin/`

- [ ] **Step 1: Full pure + app suites on the merged mains.**

Run (server root `/Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur`): `bin/compile.sh` then the three pure checks the first lines of `plugins/testu/tools/check_learning.sh` run plus the new one:
```bash
CP="build:$(find plugins/system/lib plugins/finder/lib plugins/community/lib tomcat/lib -type f -name '*.jar' | tr '\n' ':')"
for c in LearningEngineCheck MissionPlannerCheck DailyChallengeEmailCheck WeeklySummaryEmailCheck; do java -cp "$CP" plugins/testu/tools/$c.java | tail -1; done
```
Expected: `… all ok` / `all daily challenge email checks passed` / `ALL OK` (from the server root the avatar checks pass).
Run (app main): `cd /Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs && flutter test`
Expected: `All tests passed!`

- [ ] **Step 2: Apply the mapping locally.** Announce in the peers' channel that Tomcat restarts. `bin/sync-testu.sh --check` (expect `testu runtime files in sync`; run `bin/sync-testu.sh` if it lists drift), restart Tomcat the documented way (memory `local-tomcat-restart`). Then, signed in as the local admin, open `/site/find/views/settings/lists/datamanager/list/restore.html?searchtype=learnernotification` and `…?searchtype=usageevent`, then reindex both tables (CLAUDE.md §6). `missionpush` is new: nothing to do. Confirm: `curl -s 'localhost:9200/site_catalog/_mapping/field/kind,notification?pretty' | grep -E '"(learnernotification|usageevent|missionpush|kind|notification)"'` shows `kind` under `learnernotification` and `notification` under `usageevent` (`missionpush` appears after its first row).

- [ ] **Step 3: Live checks.** From the server root with `~/.eme-local.env` loaded (memory `local-eme-server-ops`):
```bash
set -a; . ~/.eme-local.env; set +a
plugins/testu/tools/check_mission.sh
plugins/testu/tools/check_learning.sh
```
Expected: both end `all ok` (the tutor-chat `[[do …]]` assertions in `check_mission.sh` depend on the LLM and are known-flaky; everything added by Tasks 2/4/5/6 must be `ok:`). On a failure, fix on a branch in the testu worktree and repeat Steps 1–3.

- [ ] **Step 4: Bundles.** Check peers first: `git -C /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur status --short webapp/site/learn webapp/site/admin`. If `main.dart.js` is already modified by someone else's uncommitted build, stop and ask the controller before overwriting. Otherwise, from the app main checkout (or a clean sibling worktree on merged `main`, memory `local-eme-server-ops`): `./build_learn.sh && ./build_admin.sh`. Open `http://localhost:8080/site/learn/` at 390 px and desktop width, sign in as `demo.N@testu.local` (memory `local-learner-login-otp`), and look at the Today card in each state you can reach (on_track, unfinished after 1–4 answers, reminder set → collapsed → Undo). Then commit **only** the bundle paths:
```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur
git add webapp/site/learn webapp/site/admin
git status --short   # only webapp/site/learn/... and webapp/site/admin/... staged
git commit -m "build: learner + console bundles (weekly objective)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 5: Rollout notes.** In `plugins/testu/docs/superpowers/specs/2026-10-05-mission-agent-design.md`, append to the end of `## Rollout (as built)` (before `## Amendment 2026-10-06: weekly objective`):
```markdown
- Weekly objective (amendment 2026-10-06):
  - Field XML: `learnernotification.kind` and `usageevent.notification` (existing tables; catalog plugin `html/data/fields/` and the site mirror `webapp/WEB-INF/data/site/catalog/fields/`, kept identical). Per server after deploy: datamanager restore then reindex of `learnernotification` and `usageevent`. New table `missionpush` (`plugins/catalog/html/data/fields/missionpush.xml`) gets its mapping on first use: nothing to do.
  - No new list values, permissions, endpoints or events: `remind.json when=cancel`, the 18:00 unfinished push (inside the existing 15-minute `missionNudges`) and `engagement.json missionpushes` ride on existing files; `bin/sync-testu.sh --check` clean.
  - `push_open` taps need the new app build; older apps send none (tapped stays 0). `missionpush` rows are written even when FCM is off, so "sent" = issued by the server.
  - Learner and console web bundles rebuilt.
```
Commit in the testu repo:
```bash
git -C /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/testu add docs/superpowers/specs/2026-10-05-mission-agent-design.md
git -C /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/testu commit -m "docs(testu): weekly objective rollout notes" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 6: Hand-off.** Report the push list to Diego (testu, catalog, site incl. bundles, app) — do not push; Notion only where it already tracks this feature (memory `feedback-keep-notion-updated`).

---

## Decisions (where spec/prototype were silent or disagreed)

- Unfinished = 1–4 of `DAY_MIN_ANSWERS` 5 (amendment), not the prototype's "of 10".
- The admin's on-demand `missionnudges.json` ignores the 18:00 clock for the unfinished push (as it already ignores quiet hours); the 15-minute sweep honours it. This is what makes the live check deterministic.
- `missionpush` rows are written for every server-issued mission push, even with FCM off/no device (sent = issued, not delivered).
- Push copy singular form: "Te falta 1 pregunta …" when one answer is missing.
- `push_open` keeps the opened notification id in a new `usageevent.notification` field (spec "plus the notification id"); analytics match taps by user + kind + 24 h, not by id.
- Push-effectiveness answers get their topic from `perSection` (section → topic) because `tutoranswer` has no topic; with a console topic filter only that topic's pushes count; day boundaries in the org zone; taps/answers after the period end are not loaded (ponytail ceiling noted in code).
- The push-effectiveness pure check lives in `MissionPlannerCheck` (already wired and clean under `wcheck.sh`), not a new file.
- `goal.requiredmin` added to the planner output (the level-bar marker needs it; `gap` is clamped at 0).
- Ready card: the server's own actions (Take the evaluation + Remind me later); the prototype's "Practicar antes" is not offered because the planner sends no `start_session` for `ready`.
- Manager email counts at risk / overdue / ready-not-booked only (spec); the prototype's "a tiempo" count is not in `coach.json`, so it is left out; `cert_expiring` stays in the certifications count.
- Overdue card keeps the prototype's "Sin presión…" line.
- Unfinished push and banner never contradict the card (final review I1/I2/m3): the server sends the unfinished push only while the week is open (goal not `ready`, `sessionsdone < sessionsneeded`) and no reminder is pending or was pushed today (learner zone); the app shows the unfinished banner only when the week isn't done and the card has its `start_session` action. At most one mission push per learner per sweep: a status push after a reminder/unfinished push waits for the next sweep.
