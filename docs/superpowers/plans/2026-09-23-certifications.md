# Certifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A topic evaluation becomes a renewable certification when a job profile gives it a validity; the server computes status, reminders and admin overrides; the learner app shows a Certificaciones tab and a Today card; the admin console gets a Certificaciones section with a compliance table.

**Architecture:** Pure rules in `LearningEngine` (status precedence, merge, pass-mark override, reminder stages) over one persisted `certification` row per learner × topic; endpoints in `TestULearningModule` / `TestUProfileModule` / `TestUAnalyticsModule`; the two Flutter apps read the blocks the server emits and never derive rules. Every task ends green on its own check (`LearningEngineCheck.java`, `check_certification.sh`, `flutter test`).

**Tech Stack:** Java 17 (eMe modules, `org.json.simple`), Elasticsearch searchers via `MediaArchive`, Groovy event shims, Flutter 3 (hand-formatted Dart, `FakeEmeHttp` tests).

**Spec:** `plugins/testu/docs/superpowers/specs/2026-09-23-certifications-design.md` — binding; read it first. This plan argues from it.

## Global Constraints

- Three git repos: `eme-server-minsur` (site, at `/Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur`), `plugins/testu` (own repo inside it), `app-genailabs` (`/Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs`). Commit in the repo that owns the file. Never push; Diego pushes.
- Never run `dart format`; files are hand-formatted. Never run `javac` by hand for the server: `bin/compile.sh` from the server root compiles into `build/`.
- Local Tomcat (:8080) is shared with other sessions: before restarting, announce via SendMessage to peers (see memory `local-eme-server-ops`); after `bin/compile.sh` a restart is needed for Java changes. XML under `data/` reaches `webapp/WEB-INF/data` only through `bin/sync-testu.sh`.
- Wire keys are lowercase without separators (`validitymonths`, `renewalwindowdays`, `passedat`). Dates on the wire: `YYYY-MM-DD` for day values, ISO instant via `LearningEngine.iso(Date)` for instants; every reply that carries a day value also carries `now`.
- Product copy stays Spanish; UI strings go through `L(en, es)`; tests assert the English side (`flutter_test_config.dart` pins `en`).
- `user` fields in new tables are `type="list" listid="user"` (shared index mapping trap).
- With no `validitymonths` anywhere, behaviour must equal today's: every existing check (`check_evaluation.sh`, `check_profiles.sh`, `check_learning.sh`, `flutter test`) stays green.
- Permissions: learner endpoints `<user/>`; admin reads `training_view`, writes `training_manage`, plus `canManageEvaluations(inReq, archive, topicid)` per topic.
- Timezone: `LearningEngine.orgZone()` is the single source; day boundaries are end of day in that zone.

---

## File map

Server (`plugins/testu`):
- `data/fields/certification.xml` — new table.
- `data/fields/topicrequirement.xml` — +`validitymonths`, `passpercent`, `renewalwindowdays`.
- `data/fields/evaluationattempt.xml` — +`passpercent`.
- `data/lists/learnernotificationtype.xml` — +`certification`.
- `code/tech/genailabs/tutor/LearningEngine.java` — `CertRow`, rule fields on `Topic`/`ProfileRow`, date helpers, `certificationStatus`, `effectivePassPercent`, `dueStages`, cycle-aware `evaluationStatus`, `finished`, `finalizeEvaluation`, `recordCertificationPass`, `loadLearner` certification rows.
- `code/tech/genailabs/tutor/TestULearningModule.java` — `submitEvaluation` hook, `state` top-level `certifications`, `notAvailable` reason, `scheduleCertification`, `certifications`, `extendCertification`, `manualCertification`, `certificationReminders(MediaArchive)`.
- `code/tech/genailabs/tutor/TestUProfileModule.java` — three row fields in `profileJson` / `saveProfile`.
- `code/tech/genailabs/tutor/TestUAnalyticsModule.java` — `loadPerson` certification block.
- `html/services/testu/learn/{schedulecertification,certifications,extendcertification,manualcertification}.{xconf,json}` — new endpoints.
- `catalog/events/testu/certificationreminders.xconf`, `catalog/events/scripts/testu/certificationreminders.groovy` — 15-minute event.
- `tools/LearningEngineCheck.java` — `certificationChecks()`.
- `tools/check_certification.sh` — server check.
- `eme-server-minsur/bin/sync-testu.sh` — MAP entries (site repo).

Admin app (`app-genailabs/lib/admin/`):
- `admin_models.dart` — `ProfileRow` fields, `CertificationRow`, `PersonTopic.certification*`.
- `admin_api.dart` — `certifications`, `extendCertification`, `manualCertification`.
- `admin_certifications.dart` — new section (Planes | Personas).
- `admin_evaluations.dart` — unchanged, embedded as the Planes tab.
- `admin_profiles.dart` — three numeric columns.
- `admin_person.dart` — certification pill + actions.
- `admin_shell.dart` — section rename + route alias.
- `test/admin_certifications_test.dart`, `test/admin_profiles_test.dart`, `test/admin_shell_test.dart`, `test/admin_api_test.dart`.

Learner app (`app-genailabs/lib/testu/`):
- `testu_learn.dart` — `CertificationState`, `TopicState.certification`, `postScheduleCertification`.
- `testu_certifications.dart` — new tab (pure `certificationRows` + `TestuCertificationsBody`).
- `testu_shell.dart` — 5th tab, live Today card.
- `testu_route.dart` — `certificaciones` path.
- `testu_schedule_sheet.dart` — `showTestuCertScheduleSheet` over the existing calendar.
- `testu_dashboard.dart` — live Certificados line.
- `testu_dates.dart` — `testuDay`, `testuInDays` helpers (lifted from `_today()`).
- `test/testu_certifications_test.dart`, `test/testu_evaluation_test.dart`.

---

### Task 1: Data files and sync map

**Files:**
- Create: `plugins/testu/data/fields/certification.xml`
- Modify: `plugins/testu/data/fields/topicrequirement.xml` (append 3 properties before `</properties>`)
- Modify: `plugins/testu/data/fields/evaluationattempt.xml` (append `passpercent` before `</properties>`)
- Modify: `plugins/testu/data/lists/learnernotificationtype.xml` (append one row)
- Modify: `eme-server-minsur/bin/sync-testu.sh:20-30` (MAP)

**Interfaces:**
- Produces: table `certification` with fields `id, user, entitytopic, passedat, attemptid, scorepercent, validuntil, extendeduntil, extendreason, extendedby, manual, manualby, manualreason, scheduledfor, reminderssent, datemodified`; `topicrequirement.validitymonths|passpercent|renewalwindowdays`; `evaluationattempt.passpercent`; list value `certification`.

- [ ] **Step 1: Write `certification.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Certifications (spec 2026-09-23): one row per learner x topic, id <user>_<topicid>. passedat starts the current cycle
     (from an evaluation pass or a manual certification); validuntil is informational, the engine recomputes expiry from
     passedat + the merged validity; extendeduntil is an admin extension; scheduledfor the learner's chosen day;
     reminderssent the stage ids already sent this cycle. -->
<properties beanname="dataSearcher">
  <property id="id" index="true" stored="true" editable="false" keyword="true"><name><language id="en">ID</language></name></property>
  <property id="user" index="true" stored="true" editable="false" type="list" listid="user"><name><language id="en">User</language><language id="es">Usuario</language></name></property>
  <property id="entitytopic" index="true" stored="true" editable="false" type="list" listid="entitytopic"><name><language id="en">Topic</language><language id="es">Tema</language></name></property>
  <property id="passedat" index="true" stored="true" editable="false" type="date"><name><language id="en">Passed</language><language id="es">Aprobada</language></name></property>
  <property id="attemptid" index="true" stored="true" editable="false" keyword="true"><name><language id="en">Attempt</language><language id="es">Intento</language></name></property>
  <property id="scorepercent" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Score %</language><language id="es">Puntaje %</language></name></property>
  <property id="validuntil" index="true" stored="true" editable="false" type="date"><name><language id="en">Valid until</language><language id="es">Vigente hasta</language></name></property>
  <property id="extendeduntil" index="true" stored="true" editable="false" type="date"><name><language id="en">Extended until</language><language id="es">Extendida hasta</language></name></property>
  <property id="extendreason" index="true" stored="true" editable="false" type="text"><name><language id="en">Extension reason</language><language id="es">Motivo de la extensión</language></name></property>
  <property id="extendedby" index="true" stored="true" editable="false" type="list" listid="user"><name><language id="en">Extended by</language><language id="es">Extendida por</language></name></property>
  <property id="manual" index="true" stored="true" editable="false" type="boolean" datatype="boolean"><name><language id="en">Manual</language><language id="es">Manual</language></name></property>
  <property id="manualby" index="true" stored="true" editable="false" type="list" listid="user"><name><language id="en">Certified by</language><language id="es">Certificada por</language></name></property>
  <property id="manualreason" index="true" stored="true" editable="false" type="text"><name><language id="en">Manual reason</language><language id="es">Motivo</language></name></property>
  <property id="scheduledfor" index="true" stored="true" editable="false" type="date"><name><language id="en">Scheduled for</language><language id="es">Programada para</language></name></property>
  <property id="reminderssent" index="false" stored="true" editable="false" type="text"><name><language id="en">Reminders sent</language><language id="es">Recordatorios enviados</language></name></property>
  <property id="datemodified" index="true" stored="true" editable="false" type="date"><name><language id="en">Modified</language><language id="es">Modificada</language></name></property>
</properties>
```

- [ ] **Step 2: Append to `topicrequirement.xml`** (before `</properties>`; also fix the es label of `evaluationrequired` to `Evaluación requerida`)

```xml
  <!-- Certifications (spec 2026-09-23): validitymonths blank = not a certification for this profile; 0 = never expires. -->
  <property id="validitymonths" index="true" stored="true" editable="true" type="number" datatype="number"><name><language id="en">Validity (months)</language><language id="es">Validez (meses)</language></name></property>
  <property id="passpercent" index="true" stored="true" editable="true" type="number" datatype="number"><name><language id="en">Pass %</language><language id="es">Aprobar %</language></name></property>
  <property id="renewalwindowdays" index="true" stored="true" editable="true" type="number" datatype="number"><name><language id="en">Renewal window (days)</language><language id="es">Ventana de renovación (días)</language></name></property>
```

- [ ] **Step 3: Append to `evaluationattempt.xml`** (before `</properties>`)

```xml
  <property id="passpercent" index="true" stored="true" editable="false" type="number" datatype="number"><name><language id="en">Pass % used</language><language id="es">Aprobar % usado</language></name></property>
```

- [ ] **Step 4: Append to `learnernotificationtype.xml`** (same element shape as the existing `reminder` row)

```xml
  <learnernotificationtype id="certification"><name><![CDATA[Certificación]]></name></learnernotificationtype>
```
(Copy the exact element name used by the existing rows in that file — open it and match; the id is `certification`.)

- [ ] **Step 5: Add to `bin/sync-testu.sh` MAP** (inside the `MAP=(` array, next to the other `data/fields` lines; also add the two event files next to the existing `computemastery` pair)

```sh
	"data/fields/certification.xml             $F/fields/certification.xml"
	"catalog/events/testu/certificationreminders.xconf            webapp/site/catalog/events/testu/certificationreminders.xconf"
	"catalog/events/scripts/testu/certificationreminders.groovy   webapp/site/catalog/events/scripts/testu/certificationreminders.groovy"
```

- [ ] **Step 6: Validate and sync**

Run: `cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && xmllint --noout plugins/testu/data/fields/certification.xml plugins/testu/data/fields/topicrequirement.xml plugins/testu/data/fields/evaluationattempt.xml plugins/testu/data/lists/learnernotificationtype.xml && bin/sync-testu.sh`
Expected: no xmllint output; sync reports copied files (the event pair will report missing sources until Task 9 — acceptable, or add them in Task 9; if the script aborts on a missing source, add those two MAP lines in Task 9 instead).

- [ ] **Step 7: Commit** (plugins/testu, then site repo for the sync script and copies)

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/testu && git add data && git commit -m "feat(certifications): certification table, profile validity fields, attempt passpercent"
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && git add bin/sync-testu.sh webapp/WEB-INF/data/site/catalog && git commit -m "chore(testu): sync certification data files"
```

---

### Task 2: Engine model + date helpers + merge

**Files:**
- Modify: `plugins/testu/code/tech/genailabs/tutor/LearningEngine.java` — `Topic` (L75-93), `Learner` (L464-478), `ProfileRow` (L768-774), `rowOf` (L782-795), `applyProfiles` merge (L921-947), `loadLearner` (L549), date helpers near `iso` (L3543), `intOr` (L3813)
- Test: `plugins/testu/tools/LearningEngineCheck.java` — new `certificationChecks()` called from `main`

**Interfaces:**
- Produces:
  - `LearningEngine.CertRow { String user, topicid, attemptid, extendreason, extendedby, manualby, manualreason; Date passedat, validuntil, extendeduntil, scheduledfor; Integer scorepercent; boolean manual; List<String> reminderssent; JSONObject toJson(ZoneId) }`
  - `Topic.validitymonths (Integer)`, `Topic.certpasspercent (Integer)`, `Topic.renewalwindowdays (int = 30)`, `Topic.certification()`
  - `ProfileRow.validitymonths, passpercent, renewalwindowdays (Integer)`
  - `Learner.certifications: Map<String, CertRow>` keyed by topic id
  - `static String ymd(Date, ZoneId)`, `static Date parseYmd(String)` (null when invalid), `static Date endOfDay(Date day, ZoneId)`, `static Date plusMonths(Date, int, ZoneId)`, `static Integer intOrNull(Object)`
  - `static CertRow certRowOf(Data)`; `CertRow loadCertification(String user, String topicid)`; `void saveCertification(CertRow)`

- [ ] **Step 1: Write the failing check** — append to `LearningEngineCheck.java` a new section and call it from `main` right after `evaluationChecks();`:

```java
	static void certificationChecks()
	{
		ZoneId lima = ZoneId.of("America/Lima");
		Date d = LearningEngine.parseYmd("2026-11-03");
		ok("cert ymd round trip", "2026-11-03".equals(LearningEngine.ymd(d, lima)), LearningEngine.ymd(d, lima));
		ok("cert parseYmd invalid -> null", LearningEngine.parseYmd("3/11/2026") == null && LearningEngine.parseYmd(null) == null, "");
		Date eod = LearningEngine.endOfDay(d, lima);
		ok("cert endOfDay is 23:59:59 Lima", "2026-11-03".equals(LearningEngine.ymd(eod, lima)) && eod.after(d), eod);
		ok("cert plusMonths 6", "2027-05-03".equals(LearningEngine.ymd(LearningEngine.plusMonths(d, 6, lima), lima)), "");
		// merge: shortest validity, highest pass %, longest window; 0 = never expires counts as longest
		LearningEngine.Content c = new LearningEngine.Content();
		LearningEngine.Topic t = new LearningEngine.Topic(); t.id = "t1"; t.title = "T1"; t.questions.add(q("q1", "s1", 1)); c.topics.put("t1", t);
		LearningEngine.ProfileRow a = row("pa", "t1", 1, null, true, false, "keep"); a.validitymonths = 6; a.passpercent = 95; a.renewalwindowdays = 30;
		LearningEngine.ProfileRow b = row("pb", "t1", 1, null, true, false, "keep"); b.validitymonths = 3; b.passpercent = 80; b.renewalwindowdays = 45;
		LearningEngine.ProfileRow z = row("pz", "t1", 1, null, true, false, "keep"); z.validitymonths = 0;
		LearningEngine.applyProfiles(c, withProfiles(List.of(), "pa", "pa", "pb", "pz"), profiles(a, b, z));
		ok("cert merge shortest validity 3", Integer.valueOf(3).equals(t.validitymonths), t.validitymonths);
		ok("cert merge highest pass 95", Integer.valueOf(95).equals(t.certpasspercent), t.certpasspercent);
		ok("cert merge longest window 45", t.renewalwindowdays == 45, t.renewalwindowdays);
		ok("cert merge certification() true", t.certification(), "");
		LearningEngine.Content c2 = new LearningEngine.Content();
		LearningEngine.Topic t2 = new LearningEngine.Topic(); t2.id = "t1"; t2.title = "T1"; t2.questions.add(q("q1", "s1", 1)); c2.topics.put("t1", t2);
		LearningEngine.applyProfiles(c2, withProfiles(List.of(), "pn", "pn"), profiles(row("pn", "t1", 1, null, true, false, "keep")));
		ok("cert no validity -> not a certification, window default 30", !t2.certification() && t2.renewalwindowdays == 30 && t2.certpasspercent == null, "");
	}
```
(`q(...)`, `row(...)`, `withProfiles(...)`, `profiles(...)` are the existing fixture helpers in that file — L171-197; check `q`'s real name/signature in the file (the question builder used by `evalTopic()`) and use that.)

- [ ] **Step 2: Run it to verify it fails**

Run: `cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && bin/compile.sh >/dev/null && CP="build:$(find plugins/system/lib plugins/finder/lib plugins/community/lib tomcat/lib -name '*.jar' | tr '\n' ':')" && java -cp "$CP" plugins/testu/tools/LearningEngineCheck.java 2>&1 | tail -5`
Expected: compile error (`parseYmd`, `validitymonths` undefined).

- [ ] **Step 3: Implement in `LearningEngine.java`**

In `Topic` (after L91 `public boolean evaluationrequired;`):
```java
		// Certifications (spec 2026-09-23), merged by applyProfiles: validitymonths null = not a certification for this learner.
		public Integer validitymonths, certpasspercent;
		public int renewalwindowdays = 30;
		public boolean certification() { return validitymonths != null; }
```
In `Learner` (after L477):
```java
		public Map<String, CertRow> certifications = new HashMap<>(); // by topic id (loadLearner)
```
In `ProfileRow` (L768-774): add `public Integer validitymonths, passpercent, renewalwindowdays;`
In `rowOf(Data d)` (after L793):
```java
		r.validitymonths = intOrNull(d.get("validitymonths"));
		r.passpercent = intOrNull(d.get("passpercent"));
		r.renewalwindowdays = intOrNull(d.get("renewalwindowdays"));
```
In `applyProfiles` merge branch (after L928 `t.evaluationrequired |= r.evaluationrequired;`):
```java
				mergeCertification(t, r);
```
and in the first-occurrence branch (after `t.evaluationrequired = r.evaluationrequired;`): `mergeCertification(t, r);`. Add the helper next to `applyProfiles`:
```java
	/** Certification rule merge: shortest validity (0 = never expires counts as longest), highest pass %, longest window. */
	static void mergeCertification(Topic t, ProfileRow r)
	{
		if (r.validitymonths == null) { return; }
		if (t.validitymonths == null || (r.validitymonths != 0 && (t.validitymonths == 0 || r.validitymonths < t.validitymonths)))
		{
			t.validitymonths = r.validitymonths;
		}
		if (r.passpercent != null && (t.certpasspercent == null || r.passpercent > t.certpasspercent)) { t.certpasspercent = r.passpercent; }
		int w = r.renewalwindowdays == null ? 30 : r.renewalwindowdays;
		if (w > t.renewalwindowdays) { t.renewalwindowdays = w; }
	}
```
Note: `t.renewalwindowdays` starts at 30; a profile asking for 10 keeps 30 (longest wins). Document this in the javadoc.

New class next to `EvalAttempt` (L1134):
```java
	/** One certification row (table certification): the learner's current cycle on a topic. */
	public static class CertRow
	{
		public String user, topicid, attemptid, extendreason, extendedby, manualby, manualreason;
		public Date passedat, validuntil, extendeduntil, scheduledfor;
		public Integer scorepercent;
		public boolean manual;
		public List<String> reminderssent = new ArrayList<>();
		public String id() { return user + "_" + topicid; }
	}
	public static CertRow certRowOf(Data d)
	{
		CertRow r = new CertRow();
		r.user = d.get("user"); r.topicid = d.get("entitytopic"); r.attemptid = d.get("attemptid");
		r.extendreason = d.get("extendreason"); r.extendedby = d.get("extendedby"); r.manualby = d.get("manualby"); r.manualreason = d.get("manualreason");
		r.passedat = dateOf(d.getValue("passedat")); r.validuntil = dateOf(d.getValue("validuntil"));
		r.extendeduntil = dateOf(d.getValue("extendeduntil")); r.scheduledfor = dateOf(d.getValue("scheduledfor"));
		r.scorepercent = intOrNull(d.get("scorepercent"));
		r.manual = "true".equals(String.valueOf(d.get("manual")));
		Object sent = d.get("reminderssent");
		if (sent != null && !String.valueOf(sent).isBlank())
		{
			for (Object o : (JSONArray) JSONValue.parse(String.valueOf(sent))) { r.reminderssent.add(String.valueOf(o)); }
		}
		return r;
	}
	private static Date dateOf(Object v) { return v == null ? null : DateStorageUtil.getStorageUtil().parseFromObject(v); }
```
(Use the same `DateStorageUtil` import already used at L507.)

Instance methods (near `saveEvalAttempt`):
```java
	public CertRow loadCertification(String inUser, String inTopicid)
	{
		Data d = (Data) fieldArchive.getSearcher("certification").searchById(inUser + "_" + inTopicid);
		return d == null ? null : certRowOf(d);
	}
	public void saveCertification(CertRow r)
	{
		Searcher s = fieldArchive.getSearcher("certification");
		Data d = (Data) s.searchById(r.id());
		if (d == null) { d = s.createNewData(); d.setId(r.id()); }
		d.setValue("user", r.user); d.setValue("entitytopic", r.topicid); d.setValue("attemptid", r.attemptid);
		d.setValue("passedat", r.passedat); d.setValue("validuntil", r.validuntil); d.setValue("extendeduntil", r.extendeduntil);
		d.setValue("extendreason", r.extendreason); d.setValue("extendedby", r.extendedby);
		d.setValue("manual", r.manual); d.setValue("manualby", r.manualby); d.setValue("manualreason", r.manualreason);
		d.setValue("scheduledfor", r.scheduledfor); d.setValue("scorepercent", r.scorepercent);
		JSONArray sent = new JSONArray(); sent.addAll(r.reminderssent); d.setValue("reminderssent", sent.toJSONString());
		d.setValue("datemodified", new Date());
		s.saveData(d, null);
	}
```
In `loadLearner(String, Collection, String)` (L549), after the evaluationattempt load:
```java
		for (Object o : fieldArchive.query("certification").exact("user", inUserid).search())
		{
			CertRow r = certRowOf((Data) o);
			l.certifications.put(r.topicid, r);
		}
```
Date helpers next to `iso` (L3543):
```java
	private static final java.time.format.DateTimeFormatter YMD = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
	public static String ymd(Date d, ZoneId z) { return d == null ? null : d.toInstant().atZone(z).toLocalDate().format(YMD); }
	/** "YYYY-MM-DD" at start of day UTC (a day value; compare with endOfDay). null when blank or invalid. */
	public static Date parseYmd(String s)
	{
		if (s == null || s.isBlank()) { return null; }
		try { return Date.from(java.time.LocalDate.parse(s.trim(), YMD).atStartOfDay(ZoneId.of("UTC")).toInstant()); }
		catch (java.time.format.DateTimeParseException e) { return null; }
	}
	public static Date endOfDay(Date day, ZoneId z)
	{
		java.time.LocalDate ld = day.toInstant().atZone(ZoneId.of("UTC")).toLocalDate();
		return Date.from(ld.atTime(23, 59, 59).atZone(z).toInstant());
	}
	public static Date plusMonths(Date d, int months, ZoneId z)
	{
		return Date.from(d.toInstant().atZone(z).toLocalDate().plusMonths(months).atStartOfDay(ZoneId.of("UTC")).toInstant());
	}
	public static Integer intOrNull(Object v)
	{
		if (v == null || String.valueOf(v).isBlank()) { return null; }
		try { return (int) Math.round(Double.parseDouble(String.valueOf(v))); } catch (NumberFormatException e) { return null; }
	}
```
Convention: **day values** (`passedat` from manual, `scheduledfor`, `extendeduntil`) are stored as `parseYmd` dates (midnight UTC of that calendar day); instants (`passedat` from a pass) are real instants. `ymd(date, zone)` renders both; `endOfDay(day, zone)` converts a day value to its inclusive end.

- [ ] **Step 4: Run the check to verify it passes**

Same command as Step 2. Expected: all `cert …` lines `ok:`; the existing sections unchanged; exit 0.

- [ ] **Step 5: Commit**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/testu && git add code/tech/genailabs/tutor/LearningEngine.java tools/LearningEngineCheck.java && git commit -m "feat(certifications): engine model, profile-row merge and day helpers"
```

---

### Task 3: Certification status, canstart, attempts per cycle, Finished, pass-mark override

**Files:**
- Modify: `LearningEngine.java` — `evaluationStatus` (L1549-1623), `finished` (L842), `scoreEvaluation` (L1430, passpercent at L1451), `finalizeEvaluation` (L1926-1970), `EvalAttempt` (+`passpercent`), `saveEvalAttempt`/`evalAttemptOf` (persist/read `passpercent`), `topicState` (L3453-3482)
- Test: `LearningEngineCheck.java` `certificationChecks()`

**Interfaces:**
- Produces:
  - `static JSONObject certificationStatus(Topic t, Learner l, Date now, ZoneId z)` → `{status, reason, passedat, validuntil, expiry, windowopens, renewalwindowdays, scheduledfor, passpercent, validitymonths, extended}` or null when `!t.certification()`
  - `static Date expiryOf(CertRow r, Topic t, ZoneId z)` (null = never)
  - `static String certStatus(Topic t, CertRow r, Date now, ZoneId z)` → `not_certified|expired|renewal_due|certified`
  - `static int effectivePassPercent(Topic t, Blueprint b)`
  - `static JSONObject evaluationStatus(Topic t, Learner l, Date now, ZoneId z)` (old 3-arg overload delegates with `ZoneId.of("UTC")` and stays for non-certification callers)
  - `EvalAttempt.passpercent (int)`; `finalizeEvaluation` scores with `effectivePassPercent`
  - `CertRow recordCertificationPass(Topic t, EvalAttempt a, ZoneId z)` (instance; writes the row; returns it)

- [ ] **Step 1: Extend the check** — append to `certificationChecks()`:

```java
		// status precedence with day boundaries
		LearningEngine.Topic ct = new LearningEngine.Topic(); ct.id = "t1"; ct.title = "T1"; ct.validitymonths = 6; ct.renewalwindowdays = 30;
		ct.blueprint = bp("random", 9, 0, "proportional", 70, 0, 0, 0, 0, false);
		LearningEngine.CertRow r = new LearningEngine.CertRow(); r.user = "u"; r.topicid = "t1";
		Date now = LearningEngine.parseYmd("2026-09-23");
		ok("cert status no row -> not_certified/never", "not_certified".equals(LearningEngine.certStatus(ct, null, now, lima)), "");
		r.passedat = LearningEngine.parseYmd("2026-05-01"); // expiry 2026-11-01, window from 2026-10-02
		ok("cert status certified", "certified".equals(LearningEngine.certStatus(ct, r, now, lima)), "");
		ok("cert status renewal_due inside window", "renewal_due".equals(LearningEngine.certStatus(ct, r, LearningEngine.parseYmd("2026-10-15"), lima)), "");
		ok("cert status still due on expiry day", "renewal_due".equals(LearningEngine.certStatus(ct, r, LearningEngine.endOfDay(LearningEngine.parseYmd("2026-11-01"), lima), lima)), "");
		ok("cert status expired next day", "expired".equals(LearningEngine.certStatus(ct, r, LearningEngine.parseYmd("2026-11-03"), lima)), "");
		r.extendeduntil = LearningEngine.parseYmd("2026-12-15");
		ok("cert extension moves expiry", "certified".equals(LearningEngine.certStatus(ct, r, LearningEngine.parseYmd("2026-11-03"), lima)), "");
		r.extendeduntil = null;
		LearningEngine.Topic never = new LearningEngine.Topic(); never.id = "t1"; never.validitymonths = 0; never.blueprint = ct.blueprint;
		ok("cert validity 0 never expires", "certified".equals(LearningEngine.certStatus(never, r, LearningEngine.parseYmd("2030-01-01"), lima)), "");
		LearningEngine.Topic noplan = new LearningEngine.Topic(); noplan.id = "t1"; noplan.validitymonths = 6;
		JSONObject np = LearningEngine.certificationStatus(noplan, learner(), now, lima);
		ok("cert plan_inactive reason", "not_certified".equals(np.get("status")) && "plan_inactive".equals(np.get("reason")), np);
		// canstart per status + attempts per cycle
		LearningEngine.Learner cl = learner(); cl.certifications.put("t1", r);
		JSONObject es = LearningEngine.evaluationStatus(ct, cl, now, lima);
		ok("cert certified -> evaluation not available valid_until", "not_available".equals(es.get("status")) && "valid_until".equals(es.get("reason")) && !Boolean.TRUE.equals(es.get("canstart")), es);
		JSONObject es2 = LearningEngine.evaluationStatus(ct, cl, LearningEngine.parseYmd("2026-10-15"), lima);
		ok("cert renewal_due -> canstart", Boolean.TRUE.equals(es2.get("canstart")), es2);
		LearningEngine.EvalAttempt old = new LearningEngine.EvalAttempt(); old.id = "u_t1_a1"; old.user = "u"; old.topicid = "t1"; old.status = "submitted"; old.passed = true; old.number = 1;
		old.submitted = LearningEngine.parseYmd("2026-05-01"); old.created = old.submitted;
		cl.evaluations.add(old);
		JSONObject es3 = LearningEngine.evaluationStatus(ct, cl, LearningEngine.parseYmd("2026-10-15"), lima);
		ok("cert attempts per cycle exclude the pass that started it", Integer.valueOf(0).equals(es3.get("attempts")), es3.get("attempts"));
		// Finished: due still finished, expired not
		ok("cert Finished when renewal_due", LearningEngine.certFinishedConjunct(ct, cl, LearningEngine.parseYmd("2026-10-15"), lima), "");
		ok("cert not Finished when expired", !LearningEngine.certFinishedConjunct(ct, cl, LearningEngine.parseYmd("2026-11-03"), lima), "");
		// pass mark override
		ct.certpasspercent = 95;
		ok("cert effective pass % = profile override", LearningEngine.effectivePassPercent(ct, ct.blueprint) == 95, "");
		ct.certpasspercent = null;
		ok("cert effective pass % = plan", LearningEngine.effectivePassPercent(ct, ct.blueprint) == 70, "");
```

- [ ] **Step 2: Run — expect compile failure** (`certStatus` etc. undefined). Same command as Task 2 Step 2.

- [ ] **Step 3: Implement**

Pure helpers (place after `evaluationPassed`, ~L1523):
```java
	/** Expiry of the learner's current cycle: max(passedat + validity, extendeduntil); null = never (validity 0, no extension). */
	public static Date expiryOf(CertRow r, Topic t, ZoneId z)
	{
		if (r == null || r.passedat == null || t.validitymonths == null) { return null; }
		Date base = t.validitymonths == 0 ? null : endOfDay(plusMonths(r.passedat, t.validitymonths, z), z);
		Date ext = r.extendeduntil == null ? null : endOfDay(r.extendeduntil, z);
		if (base == null) { return ext; }
		return ext != null && ext.after(base) ? ext : base;
	}
	public static String certStatus(Topic t, CertRow r, Date now, ZoneId z)
	{
		if (r == null || r.passedat == null) { return "not_certified"; }
		Date expiry = expiryOf(r, t, z);
		if (expiry == null) { return "certified"; }
		if (now.after(expiry)) { return "expired"; }
		long windowStart = expiry.getTime() - t.renewalwindowdays * 86400000L;
		return now.getTime() >= windowStart ? "renewal_due" : "certified";
	}
	/** null when the topic is not a certification for this learner. */
	public static JSONObject certificationStatus(Topic t, Learner l, Date now, ZoneId z)
	{
		if (!t.certification()) { return null; }
		CertRow r = l.certifications.get(t.id);
		JSONObject o = new JSONObject();
		String status = certStatus(t, r, now, z);
		boolean planinactive = t.blueprint == null || !t.blueprint.usable();
		o.put("status", status);
		o.put("reason", "not_certified".equals(status) ? (planinactive ? "plan_inactive" : "never") : null);
		Date expiry = expiryOf(r, t, z);
		o.put("passedat", r == null ? null : iso(r.passedat));
		o.put("validuntil", r == null || r.passedat == null || t.validitymonths == 0 ? null : ymd(plusMonths(r.passedat, t.validitymonths, z), z));
		o.put("expiry", expiry == null ? null : ymd(expiry, z));
		o.put("windowopens", expiry == null ? null : ymd(new Date(expiry.getTime() - t.renewalwindowdays * 86400000L), z));
		o.put("renewalwindowdays", t.renewalwindowdays);
		o.put("scheduledfor", r == null ? null : ymd(r.scheduledfor, z));
		o.put("passpercent", effectivePassPercent(t, t.blueprint));
		o.put("validitymonths", t.validitymonths);
		o.put("extended", r != null && r.extendeduntil != null);
		o.put("manual", r != null && r.manual);
		return o;
	}
	public static int effectivePassPercent(Topic t, Blueprint b)
	{
		if (t != null && t.certpasspercent != null) { return t.certpasspercent; }
		return b == null ? 70 : b.passpercent;
	}
	/** The evaluation conjunct of Finished for a certification topic: certified or due (expired is not finished). */
	public static boolean certFinishedConjunct(Topic t, Learner l, Date now, ZoneId z)
	{
		String s = certStatus(t, l.certifications.get(t.id), now, z);
		return "certified".equals(s) || "renewal_due".equals(s);
	}
```
`finished(Topic t, Learner l)` (L842): replace the first `if` with
```java
		if (t.certification() ? !certFinishedConjunct(t, l, new Date(), t.zone == null ? ZoneId.of("UTC") : t.zone) : (t.evaluationrequired && !evaluationPassed(l, t.id))) { return false; }
```
and add `public ZoneId zone;` to `Topic`, set in the instance `applyProfiles(Content, Learner)` (L874) for every topic from `(ZoneId) orgZone()[0]` before delegating to the static one (pure callers/checks leave it null → UTC). Ponytail: `// ponytail: zone rides on Topic so the pure finished() keeps its signature`.

`evaluationStatus`: rename the existing method to `evaluationStatus(Topic t, Learner l, Date inNow, ZoneId z)`; add `public static JSONObject evaluationStatus(Topic t, Learner l, Date inNow) { return evaluationStatus(t, l, inNow, ZoneId.of("UTC")); }`. Inside:
- the finalized-attempt counter loop (L1554-1561): when `t.certification()` and the learner's `CertRow` has `passedat`, skip attempts whose `submitted` is not after `passedat`.
- before the `passed` branch (L1586): 
```java
		if (t.certification())
		{
			String cs = certStatus(t, l.certifications.get(t.id), inNow, z);
			if ("certified".equals(cs))
			{
				status = "not_available"; reason = "valid_until";
				o.put("validuntil", ymd(expiryOf(l.certifications.get(t.id), t, z), z));
				// fall through to the common tail (canstart false) — structure per the existing method
			}
			skipPassedBranch = true; // renewal_due / expired / not_certified continue to locked/waiting/exhausted/available
		}
```
Adapt to the method's actual control flow (it sets `status` progressively; make the `passed` terminal branch conditional on `!t.certification()`, and add the `certified` branch above it). Keep `o.put("passed", …)`/`passedat` as they are (latest finalized attempt), they remain informative.

`EvalAttempt`: add `public int passpercent;`. `evalAttemptOf`/`saveEvalAttempt`: read/write `passpercent` (`intOr(d.get("passpercent"), 0)` / `d.setValue("passpercent", a.passpercent)`).
`scoreEvaluation(EvalAttempt a, Blueprint b, Map titles)`: add overload `scoreEvaluation(a, b, titles, int passpercent)` where L1451 uses the parameter; the 3-arg one delegates with `b.passpercent`.
`finalizeEvaluation` (L1926): after the blueprint is resolved (L1941-1943), compute `Topic t = c.topics.get(a.topicid); int pass = effectivePassPercent(t, b); a.passpercent = pass;` and call the 4-arg `scoreEvaluation`; L1961 `a.inputs.put("passpercent", pass)`.

`recordCertificationPass` (instance, next to `finalizeEvaluation`):
```java
	/** After a passed attempt on a certification topic: start a new cycle. Caller holds WRITE_LOCK. */
	public CertRow recordCertificationPass(Topic t, EvalAttempt a, ZoneId z)
	{
		CertRow r = loadCertification(a.user, t.id);
		if (r == null) { r = new CertRow(); r.user = a.user; r.topicid = t.id; }
		r.passedat = a.submitted; r.attemptid = a.id; r.scorepercent = a.scorepercent; r.manual = false; r.manualby = null; r.manualreason = null;
		r.validuntil = t.validitymonths == 0 ? null : plusMonths(a.submitted, t.validitymonths, z);
		r.extendeduntil = null; r.extendreason = null; r.extendedby = null; r.scheduledfor = null; r.reminderssent = new ArrayList<>();
		saveCertification(r);
		return r;
	}
```
`topicState(Topic, Learner, Map)` (L3453): use the 4-arg `evaluationStatus` with `(ZoneId) orgZone()[0]` and add `o.put("certification", certificationStatus(t, l, new Date(), zone));`.

- [ ] **Step 4: Run the check** — expected all `cert …` ok, `evaluationChecks` unchanged (they use the 3-arg overload and non-certification topics).

- [ ] **Step 5: Commit**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/testu && git add code/tech/genailabs/tutor/LearningEngine.java tools/LearningEngineCheck.java && git commit -m "feat(certifications): status precedence, cycle-aware canstart, Finished and pass-mark override"
```

---

### Task 4: `submitevaluation` pass hook, `state.json` certifications, `startevaluation` 409 reason

**Files:**
- Modify: `TestULearningModule.java` — `submitEvaluation` (L499-543), `state` (L20-54), `notAvailable` (L405-425)
- Test: `tools/check_certification.sh` (created here, grown in later tasks)

**Interfaces:**
- Produces: `submitevaluation.json` reply gains `certification` (block) and `passpercent` (used); `state.json` gains top-level `certifications: [{topic, title, status, expiry, scheduledfor, canstart}]`; 409 `evaluation_not_available` carries `reason: valid_until, validuntil` when certified.

- [ ] **Step 1: Write the failing server check** — create `plugins/testu/tools/check_certification.sh`, modelled on `check_evaluation.sh` (copy its header L1-130: guards, `session`, `call`, `ok`, `must`, `login`, `es_ids`, `delete_rows`, `refresh`, `put_row`, `es_doc`, `usersave`, `make_user`, `state`, `ev`, `start`, `submit`, `answer`, `audits`). Then the scenario:

```python
T = "ciberseguridad"                      # a topic with sequence questions on the local server
USER = "cert.check@testu.local"
ROLE, ROW = "ccheck-role", "ccheck-role_" + T
BP_EXTRA = [("jobrole", ROLE), ("topicrequirement", ROW), ("certification", USER + "_" + T)]

def cleanup():
    for t, i in BP_EXTRA: delete_rows(t, [i])
    delete_rows("evaluationblueprint", BP)
    wipe_user_rows(USER)
    refresh()

try:
    make_user(USER, PASSWORD, role="users")
    put_row("jobrole", ROLE, {"id": ROLE, "name": "Cert check"})
    put_row("topicrequirement", ROW, {"id": ROW, "jobrole": ROLE, "entitytopic": T, "position": "1", "mandatory": "true",
            "afterfinish": "keep", "validitymonths": "6", "passpercent": "50", "renewalwindowdays": "30"})
    must("assign profile", call(admin, "POST", "/services/testu/personas/setprofiles.json", form={"user": USER, "primary": ROLE, "extras": "[]"}))
    blueprint(active="true", strategy="random", maxquestions="4", passpercent="90", requirelearncomplete="false", timerminutes="0", retakewaithours="0", maxattempts="0")
    refresh()
    me = login(USER, PASSWORD)
    st = state(T)
    c = st["certification"]
    ok("state: certification block not_certified", c and c["status"] == "not_certified" and c["passpercent"] == 50 and c["validitymonths"] == 6, c)
    ok("state: top-level certifications lists the topic", any(x["topic"] == T and x["status"] == "not_certified" for x in state_all()["certifications"]), "")
    s = start(); sid = s["sessionid"]
    # answer half right: passes at 50 (profile), would fail at 90 (plan)
    items = s["items"]
    for i, it in enumerate(items):
        answer(sid, it["questionid"], correct_option(it) if i % 2 == 0 else wrong_option(it))
    r = submit(sid)
    ok("submit: passpercent used = 50 and passed", r["passpercent"] == 50 and r["passed"] is True, r)
    ok("submit: certification block certified with expiry ~6 months", r["certification"]["status"] == "certified" and r["certification"]["expiry"] > today_ymd(), r["certification"])
    row = es_doc("certification", USER + "_" + T)
    ok("row written: passedat/attemptid/validuntil", row and row.get("attemptid") == sid and row.get("validuntil"), row)
    ok("audit certification.pass", audits("certification.pass", USER + "_" + T), "")
    e = ev()
    ok("evaluation: certified -> not_available valid_until", e["status"] == "not_available" and e["reason"] == "valid_until" and e.get("validuntil"), e)
    st2, body = call(me, "POST", "/services/testu/learn/startevaluation.json", form={"topicid": T})
    ok("start: 409 valid_until", st2 == 409 and body.get("reason") == "valid_until", body)
finally:
    cleanup()
print("certification checks: " + ("PASS" if not FAILS else f"{len(FAILS)} FAILED")); sys.exit(1 if FAILS else 0)
```
Helpers to add in the script: `state_all()` = GET `state.json` whole reply; `today_ymd()` = server `now` (from the state reply) sliced to 10 chars; `correct_option(it)` / `wrong_option(it)`: read the question via ES `entityquestion` doc (`es_doc("entityquestion", it["questionid"])`) and return its `correctoption` / any other option key — look at how `check_evaluation.sh` answers correctly (it has a helper around L151-190; reuse it verbatim).

- [ ] **Step 2: Run it — expect the first FAILs** (`certification` block missing): `cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && set -a; . ~/.eme-local.env; set +a; plugins/testu/tools/check_certification.sh` (needs Tomcat with the Task 1-3 build; compile + restart first, announcing to peers).

- [ ] **Step 3: Implement**

`submitEvaluation` (after L520 `a = engine.finalizeEvaluation(...)`, inside `!duplicate` handling): 
```java
		ZoneId zone = (ZoneId) engine.orgZone()[0];
		if (!duplicate && a.passed && topic.certification())
		{
			LearningEngine.CertRow row;
			synchronized (LearningEngine.WRITE_LOCK) { row = engine.recordCertificationPass(topic, a, zone); }
			learner.certifications.put(topic.id, row);
			audit(inReq, getMediaArchive(inReq), "certification.pass", "certification", row.id(), null, LearningEngine.certificationStatus(topic, learner, new Date(), zone));
		}
```
Place it **before** `topic.finished = LearningEngine.finished(topic, learner)` (L526) so `finished` sees the new row. Reply: `resp.put("certification", LearningEngine.certificationStatus(topic, learner, new Date(), zone));` (already emits `passpercent` — verify it is `a.passpercent`, not the blueprint's; change L529-ish to `a.passpercent`).

`state` (L45-53): after `profiles`,
```java
		JSONArray certs = new JSONArray();
		Date now = new Date(); ZoneId zone = (ZoneId) engine.orgZone()[0];
		for (LearningEngine.Topic t : content.topics.values())
		{
			JSONObject c = LearningEngine.certificationStatus(t, learner, now, zone);
			if (c == null) { continue; }
			JSONObject row = new JSONObject();
			row.put("topic", t.id); row.put("title", t.title); row.put("status", c.get("status")); row.put("expiry", c.get("expiry"));
			row.put("scheduledfor", c.get("scheduledfor"));
			row.put("canstart", Boolean.TRUE.equals(LearningEngine.evaluationStatus(t, learner, now, zone).get("canstart")));
			certs.add(row);
		}
		certs.sort(java.util.Comparator.comparing((Object o) -> certRank(((JSONObject) o).get("status"))).thenComparing(o -> String.valueOf(((JSONObject) o).get("expiry"))));
		resp.put("certifications", certs);
		resp.put("now", LearningEngine.iso(now));
```
with `private static int certRank(Object s) { return switch (String.valueOf(s)) { case "expired" -> 0; case "renewal_due" -> 1; case "not_certified" -> 2; default -> 3; }; }`.

`notAvailable` (L405): also copy `validuntil` from the status object when present.

- [ ] **Step 4: compile, restart (announce), run the check** — expected: all lines `ok:`.

- [ ] **Step 5: Commit**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/testu && git add code/tech/genailabs/tutor/TestULearningModule.java tools/check_certification.sh && git commit -m "feat(certifications): pass starts a cycle; state.json certifications; valid_until refusal"
```

---

### Task 5: `schedulecertification.json`

**Files:**
- Create: `plugins/testu/html/services/testu/learn/schedulecertification.xconf`, `.json`
- Modify: `TestULearningModule.java` (new method), `check_certification.sh`

**Interfaces:**
- Produces: `POST schedulecertification.json topicid, date` → `{ok, certification, now}`; errors `not_certification_topic | bad_date | date_past | date_after_expiry | unknown_topic`; audit `certification.schedule`.

- [ ] **Step 1: Extend the check** (before `finally`; the learner is `certified` at this point, so first force the window open):

```python
    put_row("certification", USER + "_" + T, {"passedat": iso(NOW - datetime.timedelta(days=170))}); refresh()   # expiry in ~10 days
    st3 = state(T)["certification"]
    ok("forced: renewal_due", st3["status"] == "renewal_due", st3)
    bad = call(me, "POST", "/services/testu/learn/schedulecertification.json", form={"topicid": T, "date": "2020-01-01"})
    ok("schedule: date_past", bad[0] == 400 and bad[1]["error"] == "date_past", bad)
    late = call(me, "POST", "/services/testu/learn/schedulecertification.json", form={"topicid": T, "date": "2099-01-01"})
    ok("schedule: date_after_expiry", late[0] == 400 and late[1]["error"] == "date_after_expiry", late)
    day = (NOW + datetime.timedelta(days=3)).strftime("%Y-%m-%d")
    good = must("schedule", call(me, "POST", "/services/testu/learn/schedulecertification.json", form={"topicid": T, "date": day}))
    ok("schedule: stored", good["certification"]["scheduledfor"] == day, good)
    ok("audit certification.schedule", audits("certification.schedule", USER + "_" + T), "")
    clr = must("schedule clear", call(me, "POST", "/services/testu/learn/schedulecertification.json", form={"topicid": T, "date": ""}))
    ok("schedule: cleared", clr["certification"]["scheduledfor"] is None, clr)
```
(`iso(...)` and `NOW` exist in `check_evaluation.sh`; copy them.)

- [ ] **Step 2: Run — expect 404 on the endpoint.**

- [ ] **Step 3: Implement**

xconf (same shape as `startevaluation.xconf`):
```xml
<page>
  <path-action name="TestULearningModule.scheduleCertification"/>
  <permission name="view"><user/></permission>
</page>
```
json: `$json`

Method:
```java
	/** POST topicid, date (YYYY-MM-DD, blank = clear): the learner's commitment for the renewal (spec 2026-09-23). */
	public void scheduleCertification(WebPageRequest inReq)
	{
		User user = requireUser(inReq);
		if (user == null) { return; }
		Object[] loaded = load(inReq, user);
		LearningEngine engine = (LearningEngine) loaded[0]; LearningEngine.Content content = (LearningEngine.Content) loaded[1]; LearningEngine.Learner learner = (LearningEngine.Learner) loaded[2];
		LearningEngine.Topic topic = content.topics.get(param(inReq, "topicid"));
		if (topic == null) { fail(inReq, 404, "unknown_topic"); return; }
		if (!topic.certification()) { fail(inReq, 400, "not_certification_topic"); return; }
		ZoneId zone = (ZoneId) engine.orgZone()[0];
		Date now = new Date();
		String raw = param(inReq, "date");
		Date day = null;
		if (raw != null)
		{
			day = LearningEngine.parseYmd(raw);
			if (day == null) { fail(inReq, 400, "bad_date"); return; }
			if (LearningEngine.endOfDay(day, zone).before(now)) { fail(inReq, 400, "date_past"); return; }
			Date expiry = LearningEngine.expiryOf(learner.certifications.get(topic.id), topic, zone);
			if (expiry != null && day.after(expiry)) { fail(inReq, 400, "date_after_expiry"); return; }
		}
		LearningEngine.CertRow row = learner.certifications.get(topic.id);
		if (row == null) { row = new LearningEngine.CertRow(); row.user = user.getId(); row.topicid = topic.id; }
		JSONObject before = LearningEngine.certificationStatus(topic, learner, now, zone);
		row.scheduledfor = day;
		synchronized (LearningEngine.WRITE_LOCK) { engine.saveCertification(row); }
		learner.certifications.put(topic.id, row);
		JSONObject after = LearningEngine.certificationStatus(topic, learner, now, zone);
		audit(inReq, getMediaArchive(inReq), "certification.schedule", "certification", row.id(), before, after);
		JSONObject resp = new JSONObject(); resp.put("ok", Boolean.TRUE); resp.put("certification", after); resp.put("now", LearningEngine.iso(now));
		reply(inReq, resp);
	}
```
Add `learn/schedulecertification` to the deployed `webapp/site/mediadb/services/testu/learn/` via `bin/sync-testu.sh` (the `html/services/testu/learn` dir is already in MAP).

- [ ] **Step 4: compile, restart, run the check** — all ok.

- [ ] **Step 5: Commit** (plugins/testu: code + xconf/json + check; site: synced copies)

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/testu && git add code html/services/testu/learn/schedulecertification.xconf html/services/testu/learn/schedulecertification.json tools/check_certification.sh && git commit -m "feat(certifications): schedulecertification.json"
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && git add webapp/site/mediadb/services/testu/learn && git commit -m "chore(testu): sync schedulecertification endpoint"
```

---

### Task 6: Admin endpoints — `certifications.json`, `extendcertification.json`, `manualcertification.json`

**Files:**
- Create: `plugins/testu/html/services/testu/learn/{certifications,extendcertification,manualcertification}.{xconf,json}`
- Modify: `TestULearningModule.java`; `check_certification.sh`

**Interfaces:**
- Produces: GET `certifications.json?status&topicid&profile&team` → `{ok, canmanage, counts:{certified, renewal_due, expired, not_certified}, rows:[{user, name, team, topic, topictitle, status, reason, passedat, scorepercent, validuntil, extendeduntil, expiry, manual, scheduledfor, attempts}], now}`; POST `extendcertification.json user, topicid, until, reason` and `manualcertification.json user, topicid, passedat, reason` → `{ok, row}`.

- [ ] **Step 1: Extend the check** (admin half; before `finally`):

```python
    lst = must("certifications list", call(admin, "GET", f"/services/testu/learn/certifications.json?topicid={T}"))
    mine = [r for r in lst["rows"] if r["user"] == USER]
    ok("admin list: my row renewal_due with counts", mine and mine[0]["status"] == "renewal_due" and lst["counts"]["renewal_due"] >= 1, lst.get("counts"))
    st4, b4 = call(me, "GET", f"/services/testu/learn/certifications.json")
    ok("admin list: learner without training_view -> 403", st4 == 403, b4)
    until = (NOW + datetime.timedelta(days=40)).strftime("%Y-%m-%d")
    e1 = call(admin, "POST", "/services/testu/learn/extendcertification.json", form={"user": USER, "topicid": T, "until": until, "reason": ""})
    ok("extend: missing_reason", e1[0] == 400 and e1[1]["error"] == "missing_reason", e1)
    e2 = call(admin, "POST", "/services/testu/learn/extendcertification.json", form={"user": USER, "topicid": T, "until": "2020-01-01", "reason": "x"})
    ok("extend: date_not_later", e2[0] == 400 and e2[1]["error"] == "date_not_later", e2)
    e3 = must("extend", call(admin, "POST", "/services/testu/learn/extendcertification.json", form={"user": USER, "topicid": T, "until": until, "reason": "Annual leave"}))
    ok("extend: expiry moved, status certified", e3["row"]["expiry"] == until and e3["row"]["status"] == "certified" and e3["row"]["extendeduntil"] == until, e3["row"])
    ok("audit certification.extend", audits("certification.extend", USER + "_" + T), "")
    m1 = call(admin, "POST", "/services/testu/learn/manualcertification.json", form={"user": USER, "topicid": T, "passedat": "2099-01-01", "reason": "x"})
    ok("manual: date_future", m1[0] == 400 and m1[1]["error"] == "date_future", m1)
    m2 = must("manual", call(admin, "POST", "/services/testu/learn/manualcertification.json", form={"user": USER, "topicid": T, "passedat": today_ymd(), "reason": "External course"}))
    ok("manual: fresh cycle, manual true, extension cleared", m2["row"]["manual"] is True and m2["row"]["extendeduntil"] is None and m2["row"]["status"] == "certified", m2["row"])
    ok("audit certification.manual", audits("certification.manual", USER + "_" + T), "")
```

- [ ] **Step 2: Run — expect 404s.**

- [ ] **Step 3: Implement**

Three xconfs identical to `evaluationblueprint.xconf` with actions `TestULearningModule.certifications`, `.extendCertification`, `.manualCertification`; three `$json` files.

Shared admin preamble (private):
```java
	/** training_view (reads) / training_manage (writes) + per-topic manageevaluations. null = already failed. */
	private LearningEngine.Topic certAdminTopic(WebPageRequest inReq, boolean inWrite, LearningEngine.Content inContent, String inTopicid)
	{
		org.openedit.profile.UserProfile profile = inReq.getUserProfile();
		boolean manage = canManageProgression(inReq);
		if (inWrite ? !manage : !(manage || (profile != null && profile.hasPermission("training_view")))) { fail(inReq, 403, "forbidden"); return null; }
		LearningEngine.Topic t = inContent.topics.get(inTopicid);
		if (t == null) { fail(inReq, 404, "unknown_topic"); return null; }
		if (!canManageEvaluations(inReq, getMediaArchive(inReq), t.id)) { fail(inReq, 403, "forbidden"); return null; }
		return t;
	}
```
`certifications(WebPageRequest)`:
```java
	public void certifications(WebPageRequest inReq)
	{
		User user = requireUser(inReq);
		if (user == null) { return; }
		org.openedit.profile.UserProfile profile = inReq.getUserProfile();
		boolean manage = canManageProgression(inReq);
		if (!manage && (profile == null || !profile.hasPermission("training_view"))) { fail(inReq, 403, "forbidden"); return; }
		MediaArchive archive = getMediaArchive(inReq);
		LearningEngine engine = new LearningEngine(archive);
		ZoneId zone = (ZoneId) engine.orgZone()[0];
		Date now = new Date();
		String fstatus = param(inReq, "status"), ftopic = param(inReq, "topicid"), fprofile = param(inReq, "profile"), fteam = param(inReq, "team");
		JSONArray rows = new JSONArray();
		Map<String, Integer> counts = new java.util.TreeMap<>(Map.of("certified", 0, "renewal_due", 0, "expired", 0, "not_certified", 0));
		for (Object o : archive.query("user").all().search())
		{
			Data u = (Data) o;
			if ("agent".equals(u.get("role")) || !"true".equals(String.valueOf(u.get("enabled")))) { continue; }
			if (fteam != null && !fteam.equals(u.get("team"))) { continue; }
			Collection<String> roles = LearningEngine.jobrolesOf(u);
			if (fprofile != null && !roles.contains(fprofile)) { continue; }
			if (roles.isEmpty()) { continue; }
			LearningEngine.Content content = engine.loadContent();
			LearningEngine.Learner l = engine.loadLearner(u.getId(), roles, LearningEngine.primaryJobroleOf(u));
			engine.applyProfiles(content, l);
			for (LearningEngine.Topic t : content.topics.values())
			{
				if (!t.certification() || (ftopic != null && !ftopic.equals(t.id))) { continue; }
				if (!canManageEvaluations(inReq, archive, t.id)) { continue; }
				JSONObject c = LearningEngine.certificationStatus(t, l, now, zone);
				if (fstatus != null && !fstatus.equals(c.get("status"))) { continue; }
				counts.merge(String.valueOf(c.get("status")), 1, Integer::sum);
				rows.add(certRow(u, t, l, c, now, zone));
			}
		}
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE); resp.put("canmanage", manage); resp.put("counts", new JSONObject(counts)); resp.put("rows", rows); resp.put("now", LearningEngine.iso(now));
		reply(inReq, resp);
	}
	private static JSONObject certRow(Data u, LearningEngine.Topic t, LearningEngine.Learner l, JSONObject c, Date now, ZoneId zone)
	{
		JSONObject r = new JSONObject(c);
		LearningEngine.CertRow row = l.certifications.get(t.id);
		r.put("user", u.getId()); r.put("name", TestUAnalyticsModule.formatUserName(u)); r.put("team", u.get("team"));
		r.put("topic", t.id); r.put("topictitle", t.title);
		r.put("scorepercent", row == null ? null : row.scorepercent);
		r.put("extendeduntil", row == null ? null : LearningEngine.ymd(row.extendeduntil, zone));
		r.put("extendreason", row == null ? null : row.extendreason);
		r.put("attempts", LearningEngine.evaluationStatus(t, l, now, zone).get("attempts"));
		return r;
	}
```
(`formatUserName` is `private static` in `TestUAnalyticsModule` L2499 — make it package-private `static`.) `// ponytail: loadContent per user; cache the Content once and clone topics if the roster grows past a few hundred`. Actually `applyProfiles` mutates `Topic` fields, so per-user `loadContent()` is required for correctness; keep it, note the ceiling.

`extendCertification`:
```java
	public void extendCertification(WebPageRequest inReq)
	{
		User admin = requireUser(inReq);
		if (admin == null) { return; }
		MediaArchive archive = getMediaArchive(inReq);
		LearningEngine engine = new LearningEngine(archive);
		String userid = param(inReq, "user"), topicid = param(inReq, "topicid"), untilRaw = param(inReq, "until"), reason = param(inReq, "reason");
		Data u = userid == null ? null : (Data) archive.query("user").exact("id", userid).searchOne();
		if (u == null) { fail(inReq, 404, "unknown_user"); return; }
		LearningEngine.Content content = engine.loadContent();
		LearningEngine.Learner l = engine.loadLearner(u.getId(), LearningEngine.jobrolesOf(u), LearningEngine.primaryJobroleOf(u));
		engine.applyProfiles(content, l);
		LearningEngine.Topic t = certAdminTopic(inReq, true, content, topicid);
		if (t == null) { return; }
		if (!t.certification()) { fail(inReq, 400, "not_certification_topic"); return; }
		if (reason == null) { fail(inReq, 400, "missing_reason"); return; }
		LearningEngine.CertRow row = l.certifications.get(t.id);
		if (row == null || row.passedat == null) { fail(inReq, 400, "not_certified_yet"); return; }
		ZoneId zone = (ZoneId) engine.orgZone()[0]; Date now = new Date();
		Date until = LearningEngine.parseYmd(untilRaw);
		if (until == null) { fail(inReq, 400, "bad_date"); return; }
		Date current = LearningEngine.expiryOf(row, t, zone);
		if (current != null && !LearningEngine.endOfDay(until, zone).after(current)) { fail(inReq, 400, "date_not_later"); return; }
		if (until.after(LearningEngine.plusMonths(now, 12, zone))) { fail(inReq, 400, "date_too_far"); return; }
		JSONObject before = LearningEngine.certificationStatus(t, l, now, zone);
		row.extendeduntil = until; row.extendreason = reason; row.extendedby = admin.getId();
		row.reminderssent.removeIf(s -> s.equals("7d") || s.equals("1d") || s.equals("expired"));
		synchronized (LearningEngine.WRITE_LOCK) { engine.saveCertification(row); }
		JSONObject after = LearningEngine.certificationStatus(t, l, now, zone);
		audit(inReq, archive, "certification.extend", "certification", row.id(), before, after);
		JSONObject resp = new JSONObject(); resp.put("ok", Boolean.TRUE); resp.put("row", certRow(u, t, l, after, now, zone)); resp.put("now", LearningEngine.iso(now));
		reply(inReq, resp);
	}
```
`manualCertification`: same preamble; `passedat = parseYmd(param "passedat")` → `bad_date`; `endOfDay(passedat, zone).after(now)` → `date_future`; `reason == null` → `missing_reason`; then
```java
		if (row == null) { row = new LearningEngine.CertRow(); row.user = u.getId(); row.topicid = t.id; }
		row.passedat = passedat; row.attemptid = null; row.scorepercent = null; row.manual = true; row.manualby = admin.getId(); row.manualreason = reason;
		row.validuntil = t.validitymonths == 0 ? null : LearningEngine.plusMonths(passedat, t.validitymonths, zone);
		row.extendeduntil = null; row.extendreason = null; row.extendedby = null; row.scheduledfor = null;
		row.reminderssent = LearningEngine.stagesAlreadyPast(row, t, now, zone); // Task 8; until then: new ArrayList<>()
```
audit `certification.manual`, reply `{ok, row}`.

Check how the user searcher is queried elsewhere in the module (e.g. `freshUser`, L84 of `TestUBaseModule`) and use the same call for the user lookup instead of `query("user").exact("id", …)` if that pattern differs.

- [ ] **Step 4: compile, restart, run check** — all ok.

- [ ] **Step 5: Commit** (plugins/testu + site synced endpoints), message `feat(certifications): admin compliance list, extend and manual certification`.

---

### Task 7: Profiles and person endpoints carry the rule

**Files:**
- Modify: `TestUProfileModule.java` — `profileJson` (L369-399), `saveProfile` (L69-127 parse, L160-190 write)
- Modify: `TestUAnalyticsModule.java` — `loadPerson` (L1664-1710)
- Modify: `check_certification.sh`; run `check_profiles.sh` for regression

**Interfaces:**
- Produces: `profiles.json` rows `validitymonths, passpercent, renewalwindowdays` (null when blank) + derived `evaluationrequired`; `saveprofile.json` accepts them (400 `bad_validitymonths | bad_passpercent | bad_renewalwindowdays`); `person.json` required-topic rows `certification` block; `evaluationmet`/`requiredgaps` per spec.

- [ ] **Step 1: Extend the check**:

```python
    p = must("profiles", call(admin, "GET", "/services/testu/personas/profiles.json"))
    prow = [r for pr in p["profiles"] if pr["id"] == ROLE for r in pr["rows"] if r["topic"] == T][0]
    ok("profiles.json: rule fields", prow["validitymonths"] == 6 and prow["passpercent"] == 50 and prow["renewalwindowdays"] == 30 and prow["evaluationrequired"] is True, prow)
    badp = call(admin, "POST", "/services/testu/personas/saveprofile.json", form={"id": ROLE, "name": "Cert check", "rows": json.dumps([{"topic": T, "validitymonths": "200"}])})
    ok("saveprofile: bad_validitymonths", badp[0] == 400 and badp[1]["error"] == "bad_validitymonths", badp)
    okp = must("saveprofile", call(admin, "POST", "/services/testu/personas/saveprofile.json", form={"id": ROLE, "name": "Cert check", "rows": json.dumps([{"topic": T, "mandatory": True, "afterfinish": "keep", "validitymonths": 12, "passpercent": "", "renewalwindowdays": 15}])}))
    r2 = okp["profile"]["rows"][0]
    ok("saveprofile: stored 12 / blank / 15", r2["validitymonths"] == 12 and r2["passpercent"] is None and r2["renewalwindowdays"] == 15, r2)
    per = must("person", call(admin, "GET", f"/services/testu/analytics/person.json?userid={USER}"))
    rt = [x for x in per["risk"]["requiredtopics"] if x["id"] == T][0]
    ok("person.json: certification block + evaluationmet", rt["certification"]["status"] in ("certified", "renewal_due") and rt["evaluationmet"] is True, rt)
```
(Check the real `person.json` query parameter name in `check_profiles.sh`/`admin_api.dart:111` — `userid` or `id` — and the top-level key path `risk.requiredtopics`.)

- [ ] **Step 2: Run — expect FAILs on the new fields.**

- [ ] **Step 3: Implement**

`profileJson` (L389 area): 
```java
			row.put("validitymonths", LearningEngine.intOrNull(d.get("validitymonths")));
			row.put("passpercent", LearningEngine.intOrNull(d.get("passpercent")));
			row.put("renewalwindowdays", LearningEngine.intOrNull(d.get("renewalwindowdays")));
			row.put("evaluationrequired", LearningEngine.intOrNull(d.get("validitymonths")) != null || "true".equals(String.valueOf(d.get("evaluationrequired"))));
```
`saveProfile` parse (after L108): 
```java
			r.validitymonths = LearningEngine.intOrNull(m.get("validitymonths"));
			if (r.validitymonths != null && (r.validitymonths < 0 || r.validitymonths > 120)) { fail(inReq, 400, "bad_validitymonths"); return; }
			r.passpercent = LearningEngine.intOrNull(m.get("passpercent"));
			if (r.passpercent != null && (r.passpercent < 1 || r.passpercent > 100)) { fail(inReq, 400, "bad_passpercent"); return; }
			r.renewalwindowdays = LearningEngine.intOrNull(m.get("renewalwindowdays"));
			if (r.renewalwindowdays != null && (r.renewalwindowdays < 0 || r.renewalwindowdays > 365)) { fail(inReq, 400, "bad_renewalwindowdays"); return; }
```
write (L172-179): `d.setValue("validitymonths", r.validitymonths); d.setValue("passpercent", r.passpercent); d.setValue("renewalwindowdays", r.renewalwindowdays);` and keep writing `evaluationrequired` as `r.validitymonths != null || r.evaluationrequired` (compat for one release).

`loadPerson` (L1687-1701): after the evaluation block,
```java
			JSONObject cert = (JSONObject) st.get("certification");
			ro.put("certification", cert);
			boolean evmet = cert != null
				? ("certified".equals(cert.get("status")) || "renewal_due".equals(cert.get("status")))
				: (!t.evaluationrequired || (evfull != null && Boolean.TRUE.equals(evfull.get("passed"))));
```
(replace the existing `evmet` line; `requiredgaps` logic at L1701 stays).

- [ ] **Step 4: compile, restart, run `check_certification.sh` and `check_profiles.sh`** — both PASS.

- [ ] **Step 5: Commit** — `feat(certifications): profile rows carry validity/pass/window; person.json certification block`.

---

### Task 8: Reminder stages + 15-minute event

**Files:**
- Modify: `LearningEngine.java` — `dueStages`, `stagesAlreadyPast`
- Modify: `TestULearningModule.java` — `certificationReminders(MediaArchive)`
- Create: `plugins/testu/catalog/events/testu/certificationreminders.xconf`, `plugins/testu/catalog/events/scripts/testu/certificationreminders.groovy`
- Modify: `LearningEngineCheck.java`, `check_certification.sh`, `bin/sync-testu.sh` (if Task 1 deferred the two MAP lines)

**Interfaces:**
- Produces: `static List<String> dueStages(CertRow r, Topic t, Date now, ZoneId z)` → subset of `window_open, 7d, 1d, expired, scheduled_day` not yet in `r.reminderssent`; `static List<String> stagesAlreadyPast(CertRow, Topic, Date, ZoneId)` (all due stages except `expired`, for a freshly created manual row); `int certificationReminders(MediaArchive)` returns notifications written.

- [ ] **Step 1: Pure check** — append to `certificationChecks()`:

```java
		LearningEngine.CertRow rr = new LearningEngine.CertRow(); rr.user = "u"; rr.topicid = "t1"; rr.passedat = LearningEngine.parseYmd("2026-05-01"); // expiry 2026-11-01
		ok("stages: none before window", LearningEngine.dueStages(rr, ct, LearningEngine.parseYmd("2026-09-01"), lima).isEmpty(), "");
		ok("stages: window_open at window start", LearningEngine.dueStages(rr, ct, LearningEngine.parseYmd("2026-10-05"), lima).equals(List.of("window_open")), LearningEngine.dueStages(rr, ct, LearningEngine.parseYmd("2026-10-05"), lima));
		rr.reminderssent.add("window_open");
		ok("stages: 7d", LearningEngine.dueStages(rr, ct, LearningEngine.parseYmd("2026-10-26"), lima).equals(List.of("7d")), "");
		rr.reminderssent.add("7d");
		ok("stages: 1d and expired together when first run is late", LearningEngine.dueStages(rr, ct, LearningEngine.parseYmd("2026-11-03"), lima).equals(List.of("1d", "expired")), "");
		rr.scheduledfor = LearningEngine.parseYmd("2026-10-20");
		ok("stages: scheduled_day on the day", LearningEngine.dueStages(rr, ct, LearningEngine.parseYmd("2026-10-20"), lima).contains("scheduled_day"), "");
		ok("stages: none when validity 0", LearningEngine.dueStages(rr, never, LearningEngine.parseYmd("2030-01-01"), lima).isEmpty(), "");
		ok("stagesAlreadyPast excludes expired", !LearningEngine.stagesAlreadyPast(new LearningEngine.CertRow() {{ passedat = LearningEngine.parseYmd("2026-04-20"); }}, ct, LearningEngine.parseYmd("2026-10-26"), lima).contains("expired"), "");
```

- [ ] **Step 2: Run — compile failure.**

- [ ] **Step 3: Implement pure part**

```java
	/** Reminder stages due now and not yet sent this cycle, in send order. No expiry (validity 0) = nothing. */
	public static List<String> dueStages(CertRow r, Topic t, Date now, ZoneId z)
	{
		List<String> due = new ArrayList<>();
		if (r == null || r.passedat == null) { return due; }
		Date expiry = expiryOf(r, t, z);
		if (expiry != null)
		{
			long ms = expiry.getTime();
			if (now.getTime() >= ms - t.renewalwindowdays * 86400000L) { due.add("window_open"); }
			if (now.getTime() >= ms - 7 * 86400000L) { due.add("7d"); }
			if (now.getTime() >= ms - 1 * 86400000L) { due.add("1d"); }
			if (now.after(expiry)) { due.add("expired"); }
		}
		if (r.scheduledfor != null && !now.before(r.scheduledfor) && !now.after(endOfDay(r.scheduledfor, z))) { due.add("scheduled_day"); }
		due.removeAll(r.reminderssent);
		return due;
	}
	/** For a row created mid-cycle (manual certification): mark past stages as sent without sending, except expired. */
	public static List<String> stagesAlreadyPast(CertRow r, Topic t, Date now, ZoneId z)
	{
		List<String> past = dueStages(r, t, now, z);
		past.remove("expired");
		return past;
	}
```

- [ ] **Step 4: Run — pure checks ok.**

- [ ] **Step 5: Server side** — `TestULearningModule.certificationReminders(MediaArchive archive)` modelled on `dailyReminder` (L1329-1387):

```java
	/** 15-minute event: certification reminders (spec 2026-09-23). One learnernotification per stage per cycle; expired also audits. */
	public int certificationReminders(MediaArchive archive)
	{
		LearningEngine engine = new LearningEngine(archive);
		ZoneId zone = (ZoneId) engine.orgZone()[0];
		Date now = new Date();
		TestUSocialModule social = (TestUSocialModule) getModuleManager().getBean("TestUSocialModule");
		Searcher ns = archive.getSearcher("learnernotification");
		int sent = 0;
		Map<String, Data> users = new HashMap<>();
		for (Object o : archive.query("user").all().search()) { Data u = (Data) o; users.put(u.getId(), u); }
		for (Object o : archive.query("certification").all().search())
		{
			LearningEngine.CertRow r = LearningEngine.certRowOf((Data) o);
			Data u = users.get(r.user);
			if (u == null || !"true".equals(String.valueOf(u.get("enabled")))) { continue; }
			LearningEngine.Content content = engine.loadContent();
			LearningEngine.Learner l = engine.loadLearner(r.user, LearningEngine.jobrolesOf(u), LearningEngine.primaryJobroleOf(u));
			engine.applyProfiles(content, l);
			LearningEngine.Topic t = content.topics.get(r.topicid);
			if (t == null || !t.certification() || t.blueprint == null || !t.blueprint.usable()) { continue; }
			List<String> due = LearningEngine.dueStages(r, t, now, zone);
			if (due.isEmpty()) { continue; }
			synchronized (LearningEngine.WRITE_LOCK)
			{
				for (String stage : due)
				{
					Data n = ns.createNewData();
					n.setId(r.id() + "_" + stage + "_" + LearningEngine.ymd(r.passedat, zone));
					n.setValue("user", r.user); n.setValue("actor", "tutor"); n.setValue("actorname", tutorName(archive));
					n.setValue("type", "certification"); n.setValue("datecreated", now); n.setValue("read", false);
					n.setValue("entitytopic", t.id);
					n.setValue("text", certificationText(stage, t.title, LearningEngine.ymd(LearningEngine.expiryOf(r, t, zone), zone)));
					ns.saveData(n, null);
					social.push(archive, n);
					if ("expired".equals(stage)) { auditSystem(archive, "certification.expire", "certification", r.id(), null, LearningEngine.certificationStatus(t, l, now, zone)); }
					sent++;
				}
				r.reminderssent.addAll(due);
				engine.saveCertification(r);
			}
			notifyUser(r.user, "notifications", null);
		}
		return sent;
	}
	static String certificationText(String stage, String topic, String expiry)
	{
		return switch (stage)
		{
			case "window_open" -> "Ya puedes renovar tu certificación de " + topic + " (vence el " + expiry + ").";
			case "7d" -> "Tu certificación de " + topic + " vence en 7 días. Rinde la evaluación esta semana.";
			case "1d" -> "Tu certificación de " + topic + " vence mañana.";
			case "expired" -> "Tu certificación de " + topic + " venció. Rinde la evaluación para renovarla.";
			default -> "Hoy es el día que elegiste para renovar tu certificación de " + topic + ".";
		};
	}
```
`tutorName(archive)` = however `dailyReminder` resolves `actorname` (L1362-1375) — reuse that code. `auditSystem(...)`: the existing `audit(inReq, …)` needs a request; add to `TestUBaseModule` an overload `audit(MediaArchive, String actor, String action, String targettype, String targetid, Object before, Object after)` and make the request one delegate to it (actor `"system"` here). Apply `stagesAlreadyPast` in Task 6's `manualCertification` now.

Event xconf (copy `dailyreminder.xconf`, period `15m`, delay `4m`, eventname `TestU: certification reminders`) and groovy:
```groovy
import org.entermediadb.asset.MediaArchive

// Thin: the rule lives in tech.genailabs.tutor.TestULearningModule.certificationReminders.
MediaArchive archive = context.getPageValue("mediaarchive")
int sent = archive.getModuleManager().getBean("TestULearningModule").certificationReminders(archive)
if (sent > 0) log.info("testu certificationreminders: " + sent + " sent")
```

Server check (append before `finally`; the manual certification from Task 6 is `certified` today, so force the expiry):
```python
    put_row("certification", USER + "_" + T, {"passedat": iso(NOW - datetime.timedelta(days=200)), "reminderssent": "[]"}); refresh()  # expired ~20 days ago
    must("run reminders", call(admin, "GET", "/services/testu/learn/certificationreminders.json"))   # see note
    refresh()
    notes = es_ids("learnernotification", {"bool": {"must": [{"term": {"user": USER}}, {"term": {"type": "certification"}}]}})
    ok("reminders: window_open,7d,1d,expired written once", len(notes) == 4, notes)
    must("run reminders again", call(admin, "GET", "/services/testu/learn/certificationreminders.json")); refresh()
    ok("reminders: idempotent", len(es_ids("learnernotification", {"bool": {"must": [{"term": {"user": USER}}, {"term": {"type": "certification"}}]}})) == 4, "")
    ok("audit certification.expire", audits("certification.expire", USER + "_" + T), "")
    BP_EXTRA += [("learnernotification", i) for i in notes]
```
Note: to run the event on demand from the check, add a tiny admin endpoint `learn/certificationreminders.xconf` → `TestULearningModule.runCertificationReminders` (training_manage; calls `certificationReminders(archive)` and replies `{ok, sent}`), mirroring how `dailychallengeemail` is exercised from its check (see `html/services/testu/learn/dailychallengeemail.xconf`). Keep it: it is also how an admin will kick reminders after a bulk import.

- [ ] **Step 6: sync, compile, restart, run both checks** — PASS.

- [ ] **Step 7: Commit** — plugins/testu `feat(certifications): reminder stages and 15-minute event`; site `chore(testu): sync certification reminders event`.

---

### Task 9: Spec amendment — tutor context line deferred

**Files:**
- Modify: `plugins/testu/docs/superpowers/specs/2026-09-23-certifications-design.md` (Learner app § "Tutor prompt" bullet; Out of scope)

The learner context (`$chathistory`) is built by eMe core (eme-lib), not by this repo; there is no `TestUTutorModule`. The certification line therefore moves to Out of scope for v1 with the note "needs a hook in eMe core's chat history builder or a client-sent context field; the Today card and Certificaciones tab carry the message". 

- [ ] **Step 1: Edit the two paragraphs** as described (replace the "Tutor prompt" bullet with one line pointing to Out of scope; add the item to Out of scope).
- [ ] **Step 2: Commit** — `spec(certifications): tutor context line deferred (chathistory built in eMe core)`.

---

### Task 10: Admin models + API

**Files:**
- Modify: `app-genailabs/lib/admin/admin_models.dart` — `ProfileRow` (L279-346), `PersonTopic` (L1126-1196), new `CertificationRow`
- Modify: `app-genailabs/lib/admin/admin_api.dart` — new methods after `saveEvaluationBlueprint` (L265)
- Test: `app-genailabs/test/admin_api_test.dart`

**Interfaces:**
- Produces:
  - `ProfileRow.validityMonths (int?)`, `passPercent (int?)`, `renewalWindowDays (int?)`; `toJson` emits `validitymonths|passpercent|renewalwindowdays` (null → `''`).
  - `class CertificationRow { user, name, team, topic, topicTitle, status, reason, passedAt, scorePercent, validUntil, extendedUntil, extendReason, expiry, manual, scheduledFor, attempts }` + `fromJson`.
  - `PersonTopic.certification: CertificationRow?` (from `j['certification']`, user/name blank).
  - `AdminApi.certifications({status, topicId, profile, team})` → `({bool canManage, Map<String,int> counts, List<CertificationRow> rows})`; `extendCertification({user, topicId, until, reason})` → `CertificationRow`; `manualCertification({user, topicId, passedAt, reason})` → `CertificationRow`; `static const _certPath = 'services/testu/learn/certifications.json'`.

- [ ] **Step 1: Failing tests** — append to `test/admin_api_test.dart` (inside the file's existing `group`s or a new `group('certifications')`):

```dart
  group('certifications', () {
    test('certifications() parses counts and rows with filters', () async {
      final http = FakeEmeHttp()
        ..canned['services/testu/learn/certifications.json'] = {
          'ok': true, 'canmanage': true,
          'counts': {'certified': 1, 'renewal_due': 2, 'expired': 0, 'not_certified': 3},
          'rows': [
            {'user': 'a@x', 'name': 'Ana', 'team': 't1', 'topic': 'cs', 'topictitle': 'Ciberseguridad', 'status': 'renewal_due',
             'expiry': '2026-11-01', 'scheduledfor': '2026-10-20', 'scorepercent': 84, 'manual': false, 'attempts': 1, 'extendeduntil': null},
          ],
        };
      final r = await AdminApi(http: http).certifications(status: 'renewal_due', team: 't1');
      expect(r.canManage, isTrue);
      expect(r.counts['renewal_due'], 2);
      expect(r.rows.single.name, 'Ana');
      expect(r.rows.single.expiry, '2026-11-01');
      expect(http.requests.last.query, {'status': 'renewal_due', 'team': 't1'});
    });
    test('extendCertification posts and returns the row', () async {
      final http = FakeEmeHttp()
        ..canned['services/testu/learn/extendcertification.json'] = {'ok': true, 'row': {'user': 'a@x', 'topic': 'cs', 'status': 'certified', 'expiry': '2026-12-15', 'extendeduntil': '2026-12-15'}};
      final row = await AdminApi(http: http).extendCertification(user: 'a@x', topicId: 'cs', until: '2026-12-15', reason: 'Leave');
      expect(row.extendedUntil, '2026-12-15');
      expect(http.posted.last.fields, {'user': 'a@x', 'topicid': 'cs', 'until': '2026-12-15', 'reason': 'Leave'});
    });
    test('ProfileRow round-trips validity fields; blank posts empty', () {
      final r = ProfileRow.fromJson({'topic': 'cs', 'topictitle': 'C', 'position': 1, 'questions': 3, 'validitymonths': 6, 'passpercent': null, 'renewalwindowdays': 30});
      expect(r.validityMonths, 6);
      expect(r.passPercent, isNull);
      expect(r.toJson()['validitymonths'], 6);
      expect(r.toJson()['passpercent'], '');
    });
  });
```
(Match `http.requests` / `http.posted` record field names to `FakeEmeHttp` in `eme-app-package/lib/testing/fake_eme_http.dart:13-55`.)

- [ ] **Step 2: Run** `cd /Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs && flutter test test/admin_api_test.dart` — expect compile errors.

- [ ] **Step 3: Implement**

`ProfileRow`: add `final int? validityMonths, passPercent, renewalWindowDays;` to ctor (named, default null), fields, `fromJson` (`validityMonths: (j['validitymonths'] as num?)?.toInt()` etc.), `copyWith` (use the existing `_unset` sentinel pattern for the three nullable ints), `toJson` add:
```dart
      'validitymonths': validityMonths ?? '',
      'passpercent': passPercent ?? '',
      'renewalwindowdays': renewalWindowDays ?? '',
```
`evaluationRequired` stays readable (`fromJson`) and is no longer written (`toJson` drops it).

`CertificationRow` (after `BlueprintVersion`):
```dart
/// One learner × topic on the Certificaciones › Personas table (certifications.json) and on the person page.
class CertificationRow {
  const CertificationRow({
    required this.user, required this.name, required this.team, required this.topic, required this.topicTitle,
    required this.status, this.reason, this.passedAt, this.scorePercent, this.validUntil, this.extendedUntil,
    this.extendReason, this.expiry, this.manual = false, this.scheduledFor, this.attempts = 0,
  });
  final String user, name, team, topic, topicTitle, status;
  final String? reason, passedAt, validUntil, extendedUntil, extendReason, expiry, scheduledFor;
  final int? scorePercent;
  final bool manual;
  final int attempts;
  factory CertificationRow.fromJson(Map j) => CertificationRow(
    user: '${j['user'] ?? ''}', name: '${j['name'] ?? ''}', team: '${j['team'] ?? ''}',
    topic: '${j['topic'] ?? ''}', topicTitle: '${j['topictitle'] ?? ''}', status: '${j['status'] ?? 'not_certified'}',
    reason: j['reason'] as String?, passedAt: j['passedat'] as String?, scorePercent: (j['scorepercent'] as num?)?.toInt(),
    validUntil: j['validuntil'] as String?, extendedUntil: j['extendeduntil'] as String?, extendReason: j['extendreason'] as String?,
    expiry: j['expiry'] as String?, manual: j['manual'] == true, scheduledFor: j['scheduledfor'] as String?,
    attempts: (j['attempts'] as num?)?.toInt() ?? 0,
  );
}
```
`PersonTopic`: field `final CertificationRow? certification;`, set in `withEvaluation` (rename nothing; read `j['certification'] is Map ? CertificationRow.fromJson({...j['certification'], 'topic': id, 'topictitle': name}) : null`).

`AdminApi`:
```dart
  static const _certPath = 'services/testu/learn/certifications.json';

  Future<({bool canManage, Map<String, int> counts, List<CertificationRow> rows})>
  certifications({String? status, String? topicId, String? profile, String? team}) async {
    final j = await _http.getJson(_certPath, query: {'status': ?status, 'topicid': ?topicId, 'profile': ?profile, 'team': ?team});
    return (
      canManage: j['canmanage'] == true,
      counts: {for (final e in (j['counts'] as Map? ?? const {}).entries) '${e.key}': (e.value as num).toInt()},
      rows: [for (final r in j['rows'] as List? ?? const []) CertificationRow.fromJson(r)],
    );
  }

  Future<CertificationRow> extendCertification({required String user, required String topicId, required String until, required String reason}) =>
      _certMutation('services/testu/learn/extendcertification.json', {'user': user, 'topicid': topicId, 'until': until, 'reason': reason});

  Future<CertificationRow> manualCertification({required String user, required String topicId, required String passedAt, required String reason}) =>
      _certMutation('services/testu/learn/manualcertification.json', {'user': user, 'topicid': topicId, 'passedat': passedAt, 'reason': reason});

  Future<CertificationRow> _certMutation(String path, Map<String, String> fields) async {
    try {
      final j = await _http.postForm(path, fields.entries);
      if (j['ok'] != true) throw Exception('${j['error'] ?? 'error'}');
      return CertificationRow.fromJson(j['row'] as Map? ?? const {});
    } on EmeHttpException catch (e) {
      final b = e.body;
      if (b is Map && b['error'] != null) throw Exception('${b['error']}');
      rethrow;
    }
  }
```

- [ ] **Step 4: Run** the test file and `flutter analyze lib/admin/admin_api.dart lib/admin/admin_models.dart` — green; also run `flutter test test/admin_profiles_test.dart` (the `evaluationrequired` assertion at L110-132 will now fail — update it in Task 12; for now expect that one red and note it).

- [ ] **Step 5: Commit** — `feat(admin): certification models and API`.

---

### Task 11: Admin Certificaciones section (Planes | Personas)

**Files:**
- Create: `app-genailabs/lib/admin/admin_certifications.dart`
- Modify: `admin_shell.dart` — `sectionsFor` (L63-65), `routeFromUri` (L76-82), `_page` (L286), `_label` (L241-245)
- Test: `test/admin_certifications_test.dart`, `test/admin_shell_test.dart`

**Interfaces:**
- Consumes: `AdminApi.certifications/extendCertification/manualCertification`, `AdminEvaluations`, `Segmented<T>`, `AdminTable`, `StatRow/StatBlock`, `Select<T>`, `showConsoleForm`, `TestuPill`, `ConsoleAct`, `showToast`.
- Produces: `class AdminCertifications extends StatefulWidget { const AdminCertifications({super.key, required this.api, required this.me}); }` with `enum CertTab { plans, people }`; pure `String certStatusLabel(CertificationRow r, String today)`; section id `certifications`, `#/evaluations` → `certifications`.

- [ ] **Step 1: Failing tests**

`test/admin_shell_test.dart` — add to the pure `sectionsFor` tests:
```dart
  test('training_view shows Certifications, not Evaluations', () {
    final ids = sectionsFor(_me({'training_view'})).map((s) => s.id).toList();
    expect(ids, contains('certifications'));
    expect(ids, isNot(contains('evaluations')));
  });
  test('#/evaluations aliases to certifications', () {
    expect(routeFromUri(Uri.parse('http://x/#/evaluations')).section, 'certifications');
  });
```
`test/admin_certifications_test.dart`:
```dart
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:eme_app_package/testing/fake_eme_http.dart';
import 'package:app_genailabs/admin/admin_api.dart';
import 'package:app_genailabs/admin/admin_certifications.dart';
import 'package:app_genailabs/admin/admin_models.dart';
import 'package:app_genailabs/testu/testu_theme.dart';

Map<String, Object?> _row(String user, String status, {String? expiry, String? scheduled}) => {
  'user': user, 'name': user.split('@').first, 'team': 't1', 'topic': 'cs', 'topictitle': 'Ciberseguridad', 'status': status,
  'expiry': expiry, 'scheduledfor': scheduled, 'scorepercent': 80, 'manual': false, 'attempts': 1,
};

class _Http extends FakeEmeHttp {
  _Http() {
    canned['services/testu/learn/evaluationblueprint.json'] = {'ok': true, 'canmanage': true, 'topics': []};
    canned['services/testu/learn/certifications.json'] = {
      'ok': true, 'canmanage': true, 'now': '2026-10-20T12:00:00Z',
      'counts': {'certified': 1, 'renewal_due': 1, 'expired': 1, 'not_certified': 0},
      'rows': [_row('ana@x', 'renewal_due', expiry: '2026-11-01', scheduled: '2026-10-25'), _row('bo@x', 'expired', expiry: '2026-10-01'), _row('cy@x', 'certified', expiry: '2027-03-01')],
    };
    canned['services/testu/learn/extendcertification.json'] = {'ok': true, 'row': _row('bo@x', 'certified', expiry: '2026-12-01')};
  }
}

Future<_Http> _pump(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1440, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final http = _Http();
  await tester.pumpWidget(MaterialApp(theme: testuTheme(), home: Scaffold(body: SingleChildScrollView(
    child: AdminCertifications(api: AdminApi(http: http), me: AdminMe('u', 'u@x', 'U', 'orgadmin', const {'training_manage'}, const []))))));
  await tester.pumpAndSettle();
  return http;
}

void main() {
  test('certStatusLabel words the status against today', () {
    expect(certStatusLabel(CertificationRow.fromJson(_row('a', 'renewal_due', expiry: '2026-11-01')), '2026-10-20'), 'Due in 12 d');
    expect(certStatusLabel(CertificationRow.fromJson(_row('a', 'expired', expiry: '2026-10-01')), '2026-10-20'), 'Expired 19 d ago');
    expect(certStatusLabel(CertificationRow.fromJson(_row('a', 'certified', expiry: '2027-03-01')), '2026-10-20'), 'Certified');
    expect(certStatusLabel(CertificationRow.fromJson(_row('a', 'not_certified')), '2026-10-20'), 'Not certified');
  });
  testWidgets('People tab lists rows with counts and status pills', (tester) async {
    await _pump(tester);
    await tester.tap(find.text('People'));
    await tester.pumpAndSettle();
    expect(find.text('ana'), findsOneWidget);
    expect(find.text('Due in 12 d'), findsOneWidget);
    expect(find.text('Expired 19 d ago'), findsOneWidget);
  });
  testWidgets('Extend posts user, topic, until and reason', (tester) async {
    final http = await _pump(tester);
    await tester.tap(find.text('People'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('cert-extend-bo@x-cs')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('cert-until')), '2026-12-01');
    await tester.enterText(find.byKey(const Key('cert-reason')), 'Leave');
    await tester.tap(find.byKey(const Key('cert-confirm')));
    await tester.pumpAndSettle();
    expect(http.posted.last.fields['until'], '2026-12-01');
    expect(http.posted.last.fields['reason'], 'Leave');
  });
}
```

- [ ] **Step 2: Run** `flutter test test/admin_shell_test.dart test/admin_certifications_test.dart` — compile errors.

- [ ] **Step 3: Implement**

`admin_shell.dart`: rename the section at L63-65 to `AdminSection('certifications', L('Certifications', 'Certificaciones'))`; in `routeFromUri` map `segments.last == 'evaluations' ? 'certifications' : segments.last`; `_page`: `'certifications' => AdminCertifications(api: widget.api, me: widget.me),` (remove the `evaluations` line); `_label` map entry accordingly.

`admin_certifications.dart`:
```dart
import 'package:flutter/material.dart';
import 'admin_api.dart';
import 'admin_evaluations.dart';
import 'admin_models.dart';
import 'admin_theme.dart';
import 'admin_ui.dart';
import '../testu/testu_i18n.dart';
import '../testu/testu_theme.dart';
import '../testu/testu_widgets.dart';

/// Certificaciones: Planes (the per-topic evaluation blueprint, unchanged AdminEvaluations) and Personas (compliance per
/// learner × certification topic from learn/certifications.json). Statuses and dates come from the server; this screen words them.
enum CertTab { plans, people }

/// Pure: the status pill text. `today` is the server's now as YYYY-MM-DD.
String certStatusLabel(CertificationRow r, String today) {
  int days(String a, String b) => DateTime.parse(b).difference(DateTime.parse(a)).inDays;
  return switch (r.status) {
    'renewal_due' => L('Due in ${days(today, r.expiry!)} d', 'Vence en ${days(today, r.expiry!)} d'),
    'expired' => L('Expired ${days(r.expiry!, today)} d ago', 'Vencida hace ${days(r.expiry!, today)} d'),
    'certified' => L('Certified', 'Vigente'),
    _ => L('Not certified', 'Sin certificar'),
  };
}

class AdminCertifications extends StatefulWidget {
  const AdminCertifications({super.key, required this.api, required this.me});
  final AdminApi api;
  final AdminMe me;
  @override
  State<AdminCertifications> createState() => _AdminCertificationsState();
}

class _AdminCertificationsState extends State<AdminCertifications> {
  CertTab _tab = CertTab.plans;
  ({bool canManage, Map<String, int> counts, List<CertificationRow> rows})? _data;
  Object? _error;
  String? _status, _topic, _team, _today;

  Future<void> _load() async {
    try {
      final r = await widget.api.certifications(status: _status, topicId: _topic, team: _team);
      if (!mounted) return;
      setState(() { _data = r; _error = null; _today = DateTime.now().toIso8601String().substring(0, 10); });
    } catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = TestuTokens.of(context);
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Segmented<CertTab>(
        value: _tab,
        items: [(CertTab.plans, L('Plans', 'Planes')), (CertTab.people, L('People', 'Personas'))],
        onChanged: (v) { setState(() => _tab = v); if (v == CertTab.people && _data == null) _load(); },
      ),
      const SizedBox(height: 16),
      if (_tab == CertTab.plans) AdminEvaluations(api: widget.api, me: widget.me) else _people(t),
    ]);
  }

  Widget _people(TestuTokens t) {
    if (_error != null) return ConsolePanelError(text: errText(_error!), onRetry: _load);
    final d = _data;
    if (d == null) return const Skeleton(lines: 6, height: 22);
    final c = d.counts;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      StatRow([
        StatBlock(L('Certified', 'Vigentes'), '${c['certified'] ?? 0}'),
        StatBlock(L('Due', 'Por vencer'), '${c['renewal_due'] ?? 0}'),
        StatBlock(L('Expired', 'Vencidas'), '${c['expired'] ?? 0}'),
        StatBlock(L('Not certified', 'Sin certificar'), '${c['not_certified'] ?? 0}'),
      ]),
      const SizedBox(height: 12),
      Wrap(spacing: 10, children: [
        SizedBox(width: 200, child: Select<String>(
          value: _status, hint: L('All statuses', 'Todos los estados'),
          items: [(null, L('All statuses', 'Todos los estados')), ('renewal_due', L('Due', 'Por vencer')), ('expired', L('Expired', 'Vencidas')), ('certified', L('Certified', 'Vigentes')), ('not_certified', L('Not certified', 'Sin certificar'))],
          onChanged: (v) { setState(() => _status = v); _load(); })),
      ]),
      const SizedBox(height: 12),
      AdminTable<CertificationRow>(
        rows: d.rows,
        emptyText: L('Nobody has a certification topic yet.', 'Nadie tiene todavía un tema con certificación.'),
        columns: [
          AdminColumn(L('Person', 'Persona'), (x) => Text(x.name, maxLines: 1, overflow: TextOverflow.ellipsis), sortKey: (x) => x.name, flex: 2),
          AdminColumn(L('Team', 'Equipo'), (x) => Text(x.team), sortKey: (x) => x.team, width: 120),
          AdminColumn(L('Topic', 'Tema'), (x) => Text(x.topicTitle, maxLines: 1, overflow: TextOverflow.ellipsis), sortKey: (x) => x.topicTitle, flex: 2),
          AdminColumn(L('Status', 'Estado'), (x) => _pill(t, x), sortKey: (x) => x.status, width: 170),
          AdminColumn(L('Score', 'Puntaje'), (x) => Text(x.scorePercent == null ? '—' : '${x.scorePercent} %'), width: 80, numeric: true),
          AdminColumn(L('Expires', 'Vence'), (x) => Tooltip(message: x.extendReason ?? '', child: Text(x.expiry == null ? '—' : x.extendedUntil != null ? '${x.expiry} ↗' : x.expiry!)), sortKey: (x) => x.expiry ?? '', width: 120),
          AdminColumn(L('Scheduled', 'Programada'), (x) => Text(x.scheduledFor ?? '—'), width: 110),
          AdminColumn(L('Attempts', 'Intentos'), (x) => Text('${x.attempts}'), width: 80, numeric: true),
          if (d.canManage) AdminColumn('', (x) => Row(mainAxisSize: MainAxisSize.min, children: [
            ConsoleAct(L('Extend…', 'Extender…'), key: Key('cert-extend-${x.user}-${x.topic}'), onTap: x.passedAt == null && !x.manual && x.status == 'not_certified' ? null : () => _extend(x)),
            const SizedBox(width: 6),
            ConsoleAct(L('Certify…', 'Certificar…'), key: Key('cert-manual-${x.user}-${x.topic}'), onTap: () => _manual(x)),
          ]), width: 220),
        ],
      ),
    ]);
  }

  Widget _pill(TestuTokens t, CertificationRow x) {
    final label = certStatusLabel(x, _today ?? '');
    return switch (x.status) {
      'certified' => TestuPill(label, color: t.greenText, borderColor: t.greenBorder),
      'renewal_due' => TestuPill(label, color: t.amber, borderColor: t.amberBorder),
      'expired' => TestuPill(label, color: t.redText, borderColor: t.redBorder),
      _ => TestuPill(x.reason == 'plan_inactive' ? '$label · ${L('no active plan', 'sin plan activo')}' : label, color: t.mut, borderColor: t.line2),
    };
  }

  Future<void> _extend(CertificationRow x) => _dateReasonForm(
    title: L('Extend certification', 'Extender certificación'), dateLabel: L('Until (YYYY-MM-DD)', 'Hasta (AAAA-MM-DD)'),
    submit: (date, reason) => widget.api.extendCertification(user: x.user, topicId: x.topic, until: date, reason: reason));

  Future<void> _manual(CertificationRow x) => _dateReasonForm(
    title: L('Mark as certified', 'Certificar manualmente'), dateLabel: L('Passed on (YYYY-MM-DD)', 'Aprobada el (AAAA-MM-DD)'),
    submit: (date, reason) => widget.api.manualCertification(user: x.user, topicId: x.topic, passedAt: date, reason: reason));

  Future<void> _dateReasonForm({required String title, required String dateLabel, required Future<CertificationRow> Function(String date, String reason) submit}) async {
    final t = TestuTokens.of(context);
    final date = TextEditingController(), reason = TextEditingController();
    await showConsoleForm(context,
      title: title,
      fields: (dctx, setD) => [
        Text(dateLabel, style: AdminTokens.tableHead),
        TextField(key: const Key('cert-until'), controller: date, decoration: consoleField(t, hint: '2026-12-31')),
        const SizedBox(height: 10),
        Text(L('Reason', 'Motivo'), style: AdminTokens.tableHead),
        TextField(key: const Key('cert-reason'), controller: reason, decoration: consoleField(t, hint: L('Annual leave until…', 'Vacaciones hasta…'))),
      ],
      primary: (dctx, setD) => ConsoleAct(L('Save', 'Guardar'), key: const Key('cert-confirm'), primary: true, onTap: () async {
        Navigator.pop(dctx);
        try {
          await submit(date.text.trim(), reason.text.trim());
          await _load();
        } catch (e) {
          if (mounted) showToast(context, _errorLine(errText(e)), error: true);
        }
      }),
    );
  }

  String _errorLine(String code) => switch (code) {
    'missing_reason' => L('A reason is required.', 'Hace falta un motivo.'),
    'bad_date' => L('Use YYYY-MM-DD.', 'Usa AAAA-MM-DD.'),
    'date_not_later' => L('The date must be after the current expiry.', 'La fecha debe ser posterior al vencimiento actual.'),
    'date_too_far' => L('At most 12 months from today.', 'Como máximo 12 meses desde hoy.'),
    'date_future' => L('The date cannot be in the future.', 'La fecha no puede ser futura.'),
    'not_certified_yet' => L('Not certified yet: mark as certified instead.', 'Todavía sin certificar: usa Certificar.'),
    _ => L('Could not save: $code', 'No se pudo guardar: $code'),
  };
}
```
Check the real `Select` item type (`List<(T?, String)>`), `StatBlock` ctor, `Skeleton` args and `showConsoleForm` signature against `admin_ui.dart` while writing. `_today` from the server: parse `now` — expose it from `AdminApi.certifications` as a 4th record field `now` (add to the API record in Task 10 if not already: `now: '${j['now'] ?? ''}'`) and use `now.substring(0,10)` instead of `DateTime.now()`.

- [ ] **Step 4: Run** both test files + `flutter analyze lib/admin` — green. Then `flutter test` (whole suite) to catch the rail-label golden/rail tests that mention `EVALUATIONS` — update to `CERTIFICATIONS` where asserted.

- [ ] **Step 5: Commit** — `feat(admin): Certificaciones section with Planes and Personas`.

---

### Task 12: Profile editor validity columns + person pill

**Files:**
- Modify: `admin_profiles.dart` — `_fixedColsW` (L174), `_header` (L202-214), `_row` evaluation cell (L285-296), state controllers
- Modify: `admin_person.dart` — `_TopicRow` (L396-401), `_EvaluationPill` (L531-557)
- Test: `test/admin_profiles_test.dart` (L110-132), `test/admin_person_test.dart` (evaluation-pill tests L239-278)

**Interfaces:**
- Consumes: `ProfileRow.validityMonths/passPercent/renewalWindowDays`, `PersonTopic.certification`.

- [ ] **Step 1: Rewrite the failing profile test** (replace the `profile-eval` test at L110-132):

```dart
  testWidgets('validity, pass % and window are saved on the row', (tester) async {
    List<ProfileRow>? savedRows;
    await _pump(tester, profile: _pilot(), onSave: (name, rows) async => savedRows = rows);
    await tester.enterText(find.byKey(const Key('profile-validity-T1')), '6');
    await tester.enterText(find.byKey(const Key('profile-pass-T1')), '95');
    await tester.enterText(find.byKey(const Key('profile-window-T1')), '45');
    await tester.tap(find.byKey(const Key('profile-save')));
    await tester.pumpAndSettle();
    final r = savedRows!.first;
    expect((r.validityMonths, r.passPercent, r.renewalWindowDays), (6, 95, 45));
    expect(r.toJson()['validitymonths'], 6);
  });
```
(Adapt `_pump`'s parameters to the file's real helper signature.) Person test: where a row has `'evaluationrequired': true, 'evaluation': {...}` add `'certification': {'status': 'renewal_due', 'expiry': '2026-11-01'}` to one fixture row and assert `find.text('Due')`-style copy: `expect(find.textContaining('Due'), findsOneWidget);`.

- [ ] **Step 2: Run** both — red.

- [ ] **Step 3: Implement**

`admin_profiles.dart`: replace the 130 px evaluation column with three 90 px numeric columns (`_fixedColsW` = `30 + 130 + 120 + 130 + 120 + 90 + 90 + 90 + 96`). Controllers: `final _num = <String, TextEditingController>{};` with `TextEditingController _ctl(String key, int? v) => _num.putIfAbsent(key, () => TextEditingController(text: v?.toString() ?? ''));`, disposed in `dispose`. Cell:
```dart
        cell(90, canEdit
            ? TextField(key: Key('profile-validity-${r.topic}'), controller: _ctl('v-${r.topic}', r.validityMonths), keyboardType: TextInputType.number,
                decoration: consoleField(t, hint: '—'), onChanged: (s) => _set(i, r.copyWith(validityMonths: int.tryParse(s))))
            : ro(r.validityMonths?.toString() ?? '—')),
```
same for `profile-pass-` (`passPercent`) and `profile-window-` (`renewalWindowDays`). Header labels: `L('Validity (mo)', 'Validez (m)')`, `L('Pass %', 'Aprobar %')`, `L('Window (d)', 'Ventana (d)')`. Under the table, when any row has `evaluationRequired && validityMonths == null`, show `Text(L('Required evaluation without validity: set a validity to make it a certification.', 'Evaluación requerida sin validez: pon una validez para convertirla en certificación.'), style: AdminTokens.footnote)`.

`admin_person.dart`: in `_TopicRow`, after the evaluation pill:
```dart
            if (topic.certification case final c?) ...[
              const SizedBox(width: 6),
              _CertPill(c),
            ],
```
```dart
class _CertPill extends StatelessWidget {
  const _CertPill(this.c);
  final CertificationRow c;
  @override
  Widget build(BuildContext context) {
    const t = TestuTokens.instance;
    final label = switch (c.status) {
      'certified' => L('Certified · ${c.expiry ?? '∞'}', 'Vigente · ${c.expiry ?? '∞'}'),
      'renewal_due' => L('Due ${c.expiry}', 'Vence ${c.expiry}'),
      'expired' => L('Expired ${c.expiry}', 'Vencida ${c.expiry}'),
      _ => L('Not certified', 'Sin certificar'),
    };
    final (color, border) = switch (c.status) {
      'certified' => (t.greenText, t.greenBorder),
      'renewal_due' => (t.amber, t.amberBorder),
      'expired' => (t.redText, t.redBorder),
      _ => (t.mut, t.line2),
    };
    return TestuPill(label, key: const ValueKey('person-certification-pill'), color: color, borderColor: border);
  }
}
```
(The extend/certify actions on the person page are deferred to the Personas table — one place to act; `// ponytail: actions live on the Certificaciones table; add here if admins ask`.)

- [ ] **Step 4: Run** `flutter test test/admin_profiles_test.dart test/admin_person_test.dart` + analyze — green.

- [ ] **Step 5: Commit** — `feat(admin): profile validity columns and person certification pill`.

---

### Task 13: Learner state model + schedule call + day helpers

**Files:**
- Modify: `app-genailabs/lib/testu/testu_learn.dart` — `TopicState` (L113-156), new `CertificationState` after `EvaluationInProgress` (L686), `postScheduleCertification` after L739
- Create: `app-genailabs/lib/testu/testu_dates.dart` (lift `_today()`'s arrays from `testu_shell.dart:1169-1221`)
- Modify: `testu_shell.dart` — `_today()` uses the new helper
- Test: `test/testu_evaluation_test.dart` (parse test), new `test/testu_dates_test.dart`

**Interfaces:**
- Produces:
  - `class CertificationState { status, reason, passedAt, validUntil, expiry, windowOpens, renewalWindowDays, scheduledFor, passPercent, validityMonths, extended, manual }` + `fromJson`; `TopicState.certification: CertificationState?`.
  - `Future<CertificationState> postScheduleCertification(EmeHttp http, {required String topicId, required String? date})`.
  - `testu_dates.dart`: `String testuDay(String ymd)` → `L('November 3', '3 de noviembre')`; `String testuWeekday(String ymd)` → `L('Thursday', 'jueves')`; `int testuDaysUntil(String ymd, {DateTime? now})`; `String testuInDays(int n)` → `L('in 12 days', 'en 12 días')` / `L('today', 'hoy')` / `L('3 days ago', 'hace 3 días')`.

- [ ] **Step 1: Failing tests**

`test/testu_evaluation_test.dart` (next to the `evaluation` parse test at L44-56):
```dart
  test('TopicState parses the certification block', () {
    final t = TopicState.fromJson({'id': 't', 'questions': 1, 'answered': 0, 'sections': [],
      'certification': {'status': 'renewal_due', 'expiry': '2026-11-01', 'windowopens': '2026-10-02', 'renewalwindowdays': 30,
        'scheduledfor': '2026-10-20', 'passpercent': 95, 'validitymonths': 6, 'extended': false, 'manual': false}});
    expect(t.certification!.status, 'renewal_due');
    expect(t.certification!.expiry, '2026-11-01');
    expect(t.certification!.scheduledFor, '2026-10-20');
    expect(t.certification!.passPercent, 95);
    expect(TopicState.fromJson({'id': 't', 'questions': 1, 'answered': 0, 'sections': []}).certification, isNull);
  });
  test('postScheduleCertification posts topicid and date', () async {
    final http = FakeEmeHttp()..canned['services/testu/learn/schedulecertification.json'] = {'ok': true, 'certification': {'status': 'renewal_due', 'scheduledfor': '2026-10-20'}};
    final c = await postScheduleCertification(http, topicId: 't', date: '2026-10-20');
    expect(c.scheduledFor, '2026-10-20');
    expect(http.posted.last.fields, {'topicid': 't', 'date': '2026-10-20'});
  });
```
`test/testu_dates_test.dart`:
```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:app_genailabs/testu/testu_dates.dart';

void main() {
  test('testuDay / testuWeekday / testuInDays (en)', () {
    expect(testuDay('2026-11-03'), 'November 3');
    expect(testuWeekday('2026-10-22'), 'Thursday');
    expect(testuDaysUntil('2026-11-03', now: DateTime(2026, 10, 22)), 12);
    expect(testuInDays(12), 'in 12 days');
    expect(testuInDays(0), 'today');
    expect(testuInDays(-3), '3 days ago');
  });
}
```

- [ ] **Step 2: Run** — compile errors.

- [ ] **Step 3: Implement**

`testu_learn.dart`, in `TopicState.fromJson` initializer list (next to L134):
```dart
        certification = j['certification'] is Map<String, dynamic> ? CertificationState.fromJson(j['certification'] as Map<String, dynamic>) : null,
```
field: `/// Certifications (spec 2026-09-23); null = not a certification for this learner or an older server.` `final CertificationState? certification;`

```dart
/// The learner's certification on a topic: not_certified | expired | renewal_due | certified (server-decided).
class CertificationState {
  CertificationState.fromJson(Map<String, dynamic> j)
      : status = _str(j['status']) ?? 'not_certified',
        reason = _str(j['reason']),
        passedAt = _str(j['passedat']),
        validUntil = _str(j['validuntil']),
        expiry = _str(j['expiry']),
        windowOpens = _str(j['windowopens']),
        renewalWindowDays = _int(j['renewalwindowdays']),
        scheduledFor = _str(j['scheduledfor']),
        passPercent = _int(j['passpercent']),
        validityMonths = _int(j['validitymonths']),
        extended = _bool(j['extended']),
        manual = _bool(j['manual']);
  final String status;
  final String? reason, passedAt, validUntil, expiry, windowOpens, scheduledFor;
  final int renewalWindowDays, passPercent, validityMonths;
  final bool extended, manual;
  bool get due => status == 'renewal_due' || status == 'expired';
}

Future<CertificationState> postScheduleCertification(EmeHttp http, {required String topicId, required String? date}) async =>
    CertificationState.fromJson((await _call(() => http.postForm('$_base/schedulecertification.json', [MapEntry('topicid', topicId), MapEntry('date', date ?? '')])))['certification'] as Map<String, dynamic>);
```
(`_int` returns 0 for null per the existing coercer — fine for these three.)

`testu_dates.dart`:
```dart
import 'testu_i18n.dart';

const _daysEn = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
const _daysEs = ['lunes', 'martes', 'miércoles', 'jueves', 'viernes', 'sábado', 'domingo'];
const _monthsEn = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const _monthsEs = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre'];

/// "November 3" / "3 de noviembre" from YYYY-MM-DD; the input when unparseable.
String testuDay(String ymd) {
  final d = DateTime.tryParse(ymd);
  if (d == null) return ymd;
  return L('${_monthsEn[d.month - 1]} ${d.day}', '${d.day} de ${_monthsEs[d.month - 1]}');
}
String testuWeekday(String ymd) {
  final d = DateTime.tryParse(ymd);
  return d == null ? ymd : L(_daysEn[d.weekday - 1], _daysEs[d.weekday - 1]);
}
/// Whole days from today's date to ymd (negative = past). Day granularity, local clock.
int testuDaysUntil(String ymd, {DateTime? now}) {
  final d = DateTime.tryParse(ymd);
  final n = now ?? DateTime.now();
  return d == null ? 0 : DateTime(d.year, d.month, d.day).difference(DateTime(n.year, n.month, n.day)).inDays;
}
String testuInDays(int n) => n == 0
    ? L('today', 'hoy')
    : n > 0 ? L('in $n days', 'en $n días') : L('${-n} days ago', 'hace ${-n} días');
```
`testu_shell.dart` `_today()`: replace its private arrays with the ones above (export `testuWeekdayArrays` only if needed; simplest: make `_today()` build from `testuWeekday`/`testuDay` of today's ymd — check its exact output format `'Friday, November 3'` / `'viernes, 3 de noviembre'` and keep it byte-identical because a golden may assert it).

- [ ] **Step 4: Run** the three test files + `flutter analyze lib/testu` — green; full `flutter test` still green.

- [ ] **Step 5: Commit** — `feat(testu): certification state, schedule call, day helpers`.

---

### Task 14: Certificaciones tab

**Files:**
- Create: `app-genailabs/lib/testu/testu_certifications.dart`
- Modify: `testu_shell.dart` — `testuTabLabels()` (L26-31), `IndexedStack` children (L230-241)
- Modify: `testu_route.dart` — `paths` (L19)
- Modify: `testu_schedule_sheet.dart` — `showTestuCertScheduleSheet`
- Test: `test/testu_certifications_test.dart`

**Interfaces:**
- Consumes: `loadAllTopicProgress()`, `TopicProgress.state.certification`, `showTestuSession(context, topicId:, mode: 'evaluation')`, `testuProgressChanged`, `TestuCard/TestuEyebrow/TestuAct/TestuPill/TestuGrabber/showTestuSheet`, `testu_dates.dart`.
- Produces:
  - `typedef CertRow = ({String topic, String title, CertificationState cert});`
  - pure `({List<CertRow> due, List<CertRow> valid, List<CertRow> pending}) certificationRows(List<TopicProgress> live)` — `due` = `renewal_due` sorted by expiry asc; `valid` = `certified`; `pending` = `expired` (by expiry asc) then `not_certified`.
  - `class TestuCertificationsScreen extends StatefulWidget` (loads, groups, renders `TestuCertificationsBody`).
  - `class TestuCertificationsBody extends StatelessWidget { const TestuCertificationsBody({required this.rows, required this.canStart, required this.onTake, required this.onSchedule}); }` where `canStart(CertRow)` is a `bool Function(CertRow)` from `TopicProgress.state.evaluation?.canStart`.
  - `Future<void> showTestuCertScheduleSheet(BuildContext, {required String topicId, required String? expiry, required ValueChanged<String?> onScheduled})`.
  - Tab: labels gain `'CERTIFICATIONS'/'CERTIFICACIONES'` at index 4 **only when** `TestuShell.hasCertifications.value` (a `ValueNotifier<bool>` set by the Today/Topics loaders when any `TopicState.certification != null`); `LearnerRoute.paths` gains `'certificaciones'` at index 4.

- [ ] **Step 1: Failing test** `test/testu_certifications_test.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:app_genailabs/testu/testu_certifications.dart';
import 'package:app_genailabs/testu/testu_learn.dart';
import 'package:app_genailabs/testu/testu_live.dart';
import 'package:app_genailabs/testu/testu_theme.dart';

TopicProgress _p(String id, String title, Map<String, Object?> cert, {bool canStart = false}) => TopicProgress(
  TopicState.fromJson({'id': id, 'title': title, 'questions': 10, 'answered': 0, 'sections': [], 'certification': cert,
    'evaluation': {'status': canStart ? 'available' : 'not_available', 'canstart': canStart}}), null);

final _live = [
  _p('a', 'Alpha', {'status': 'certified', 'expiry': '2027-03-01'}),
  _p('b', 'Beta', {'status': 'renewal_due', 'expiry': '2026-11-10'}, canStart: true),
  _p('c', 'Gamma', {'status': 'renewal_due', 'expiry': '2026-11-01', 'scheduledfor': '2026-10-25'}, canStart: true),
  _p('d', 'Delta', {'status': 'expired', 'expiry': '2026-09-01'}, canStart: true),
  _p('e', 'Eps', {'status': 'not_certified'}),
  TopicProgress(TopicState.fromJson({'id': 'z', 'title': 'Zed', 'questions': 1, 'answered': 0, 'sections': []}), null),
];

void main() {
  test('certificationRows groups and orders', () {
    final g = certificationRows(_live);
    expect(g.due.map((r) => r.topic), ['c', 'b']);
    expect(g.valid.map((r) => r.topic), ['a']);
    expect(g.pending.map((r) => r.topic), ['d', 'e']);
  });
  testWidgets('body shows groups, status lines and CTAs', (tester) async {
    tester.view.physicalSize = const Size(400, 1400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final taken = <String>[], scheduled = <String>[];
    await tester.pumpWidget(MaterialApp(theme: testuTheme(), home: Scaffold(body: TestuCertificationsBody(
      rows: certificationRows(_live), canStart: (r) => r.topic != 'a' && r.topic != 'e', onTake: (r) => taken.add(r.topic), onSchedule: (r) => scheduled.add(r.topic)))));
    expect(find.text('Due soon'), findsOneWidget);
    expect(find.text('Valid'), findsOneWidget);
    expect(find.text('Pending'), findsOneWidget);
    expect(find.textContaining('Scheduled'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('cert-take-b')));
    await tester.tap(find.byKey(const ValueKey('cert-schedule-b')));
    expect(taken, ['b']);
    expect(scheduled, ['b']);
    expect(find.byKey(const ValueKey('cert-take-a')), findsNothing);
  });
}
```

- [ ] **Step 2: Run** — compile errors.

- [ ] **Step 3: Implement**

`testu_certifications.dart`:
```dart
import 'package:flutter/material.dart';
import 'testu_dates.dart';
import 'testu_i18n.dart';
import 'testu_learn.dart';
import 'testu_live.dart';
import 'testu_schedule_sheet.dart';
import 'testu_session.dart';
import 'testu_shell.dart' show TestuShell;
import 'testu_theme.dart';
import 'testu_widgets.dart';

/// Certificaciones tab (spec 2026-09-23): the learner's certification topics grouped by what needs doing. Status, expiry and
/// whether an evaluation may start all come from state.json; this file only groups, words and routes taps.
typedef CertRow = ({String topic, String title, CertificationState cert});

({List<CertRow> due, List<CertRow> valid, List<CertRow> pending}) certificationRows(List<TopicProgress> live) {
  final rows = [for (final p in live) if (p.state.certification case final c?) (topic: p.state.id, title: p.state.title, cert: c)];
  int byExpiry(CertRow a, CertRow b) => (a.cert.expiry ?? '').compareTo(b.cert.expiry ?? '');
  return (
    due: rows.where((r) => r.cert.status == 'renewal_due').toList()..sort(byExpiry),
    valid: rows.where((r) => r.cert.status == 'certified').toList(),
    pending: [...rows.where((r) => r.cert.status == 'expired').toList()..sort(byExpiry), ...rows.where((r) => r.cert.status == 'not_certified')],
  );
}

/// Pure: the second line of a row.
String certificationLine(CertificationState c) {
  final exp = c.expiry;
  return switch (c.status) {
    'renewal_due' => L('Expires ${testuDay(exp!)} · ${testuInDays(testuDaysUntil(exp))}', 'Vence el ${testuDay(exp!)} · ${testuInDays(testuDaysUntil(exp))}'),
    'certified' => exp == null ? L('Valid · no expiry', 'Vigente · sin vencimiento') : L('Valid until ${testuDay(exp)}', 'Vigente hasta el ${testuDay(exp)}'),
    'expired' => L('Expired ${testuInDays(testuDaysUntil(exp!))}', 'Vencida ${testuInDays(testuDaysUntil(exp!))}'),
    _ => c.reason == 'plan_inactive' ? L('Not certified · evaluation not available yet', 'Sin certificar · evaluación aún no disponible') : L('Not certified', 'Sin certificar'),
  };
}

class TestuCertificationsScreen extends StatefulWidget {
  const TestuCertificationsScreen({super.key});
  @override
  State<TestuCertificationsScreen> createState() => _TestuCertificationsScreenState();
}

class _TestuCertificationsScreenState extends State<TestuCertificationsScreen> {
  late Future<List<TopicProgress>> _future = loadAllTopicProgress();
  void _reload() => setState(() => _future = loadAllTopicProgress());
  @override
  void initState() { super.initState(); testuProgressChanged.addListener(_reload); }
  @override
  void dispose() { testuProgressChanged.removeListener(_reload); super.dispose(); }

  Future<void> _take(CertRow r) async {
    await showTestuSession(context, topicId: r.topic, mode: 'evaluation');
    _reload();
  }
  Future<void> _schedule(CertRow r) => showTestuCertScheduleSheet(context, topicId: r.topic, expiry: r.cert.expiry, onScheduled: (_) => _reload());

  @override
  Widget build(BuildContext context) => FutureBuilder<List<TopicProgress>>(
    future: _future,
    builder: (context, snap) {
      if (snap.hasError) return TestuErrorState(onRetry: _reload); // use the same error/retry widget testu_topics.dart uses at its FutureBuilder (~L252)
      final live = snap.data;
      if (live == null) return const Center(child: CircularProgressIndicator());
      final canStart = {for (final p in live) p.state.id: p.state.evaluation?.canStart ?? false};
      return TestuCertificationsBody(rows: certificationRows(live), canStart: (r) => canStart[r.topic] ?? false, onTake: _take, onSchedule: _schedule);
    });
}

class TestuCertificationsBody extends StatelessWidget {
  const TestuCertificationsBody({super.key, required this.rows, required this.canStart, required this.onTake, required this.onSchedule});
  final ({List<CertRow> due, List<CertRow> valid, List<CertRow> pending}) rows;
  final bool Function(CertRow) canStart;
  final void Function(CertRow) onTake, onSchedule;

  @override
  Widget build(BuildContext context) {
    final t = TestuTokens.of(context);
    Widget group(String title, List<CertRow> list, Color color) => list.isEmpty ? const SizedBox.shrink() : Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      TestuEyebrow(title, color: color),
      const SizedBox(height: 8),
      for (final r in list) ...[_row(t, r), const SizedBox(height: 10)],
      const SizedBox(height: 8),
    ]);
    return ListView(padding: EdgeInsets.fromLTRB(16, 16, 16, testuBottomPad(context)), children: [
      group(L('Due soon', 'Por vencer'), rows.due, t.amber),
      group(L('Valid', 'Vigentes'), rows.valid, t.greenText),
      group(L('Pending', 'Pendientes'), rows.pending, t.mut),
      if (rows.due.isEmpty && rows.valid.isEmpty && rows.pending.isEmpty)
        Text(L('No certifications for your profile yet.', 'Todavía no hay certificaciones para tu perfil.'), style: TextStyle(color: t.mut)),
    ]);
  }

  Widget _row(TestuTokens t, CertRow r) {
    final c = r.cert;
    final accent = switch (c.status) { 'renewal_due' => t.amber, 'expired' => t.red, 'certified' => t.green, _ => null };
    return TestuCard(accent: accent, child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(r.title, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
      const SizedBox(height: 4),
      Text(certificationLine(c), style: TextStyle(color: t.mut, fontSize: 12)),
      if (c.scheduledFor != null) ...[const SizedBox(height: 4), Text(L('Scheduled: ${testuWeekday(c.scheduledFor!)} ${testuDay(c.scheduledFor!)}', 'Programada: ${testuWeekday(c.scheduledFor!)} ${testuDay(c.scheduledFor!)}'), style: TextStyle(color: t.greenText, fontSize: 12))],
      if (canStart(r) || (c.due && c.expiry != null)) ...[
        const SizedBox(height: 12),
        Row(children: [
          if (canStart(r)) TestuAct(L('Take it now', 'Rendir ahora'), key: ValueKey('cert-take-${r.topic}'), primary: true, onTap: () => onTake(r)),
          if (canStart(r) && c.due && c.expiry != null) const SizedBox(width: 8),
          if (c.due && c.expiry != null) TestuAct(c.scheduledFor == null ? L('Schedule', 'Programar') : L('Change day', 'Cambiar día'), key: ValueKey('cert-schedule-${r.topic}'), onTap: () => onSchedule(r)),
        ]),
      ],
    ]));
  }
}
```
(Check `TestuAct`'s exact parameters in `testu_widgets.dart:282`; check the error widget used at `testu_topics.dart:252`.)

`testu_schedule_sheet.dart`: add
```dart
/// Live: pick a day up to the certification expiry (14-day grid from today, capped at expiry) and post it.
Future<void> showTestuCertScheduleSheet(BuildContext context, {required String topicId, required String? expiry, required ValueChanged<String?> onScheduled}) =>
    showTestuSheet<void>(context, builder: (_) => _CertScheduleBody(topicId: topicId, expiry: expiry, onScheduled: onScheduled));
```
`_CertScheduleBody`: reuse `_Calendar` (generalise it to take `List<DateTime> days` allowed = today..min(today+13, expiry) and `onPick(DateTime)`); on pick → `postScheduleCertification(DioEmeHttp(), topicId: topicId, date: ymd)` → `onScheduled(ymd)` → `_SuccessView` (existing). Errors → `showToast`-equivalent used in the learner app (see `testu_widgets.dart` for the learner toast). `ymd` here = `'${d.year}-${d.month.toString().padLeft(2,'0')}-${d.day.toString().padLeft(2,'0')}'`.

Tab wiring (`testu_shell.dart`): `static final hasCertifications = ValueNotifier<bool>(false);` on `TestuShell`; `testuTabLabels()` appends the label when `TestuShell.hasCertifications.value`; the `IndexedStack` children append `const TestuCertificationsScreen()` under the same condition; rebuild the shell on `hasCertifications` changes (`ValueListenableBuilder` around the scaffold or `addListener(setState)`). Set the flag in `loadAllTopicProgress()` (`testu_live.dart:968`): `TestuShell.hasCertifications.value = states.any((s) => s.certification != null);` — import cycle check: `testu_live.dart` must not import `testu_shell.dart`; put the notifier in `testu_live.dart` instead as `final testuHasCertifications = ValueNotifier<bool>(false);` and read it from the shell. `testu_route.dart:19` `paths` gains `'certificaciones'`.

- [ ] **Step 4: Run** `flutter test test/testu_certifications_test.dart` + full `flutter test` + analyze — green (fix any `testuTabLabels()` length assertions in existing shell tests: they must pass with the flag false).

- [ ] **Step 5: Commit** — `feat(testu): Certificaciones tab`.

---

### Task 15: Today card + Dashboard line (live)

**Files:**
- Modify: `testu_shell.dart` — `_cards` (L353-366), `_CertificationCard` (L574-623) → live variant
- Modify: `testu_dashboard.dart` — `_ReadinessCard` live branch (L412-419)
- Test: `test/testu_certifications_test.dart` (add), existing dashboard test if any

**Interfaces:**
- Consumes: `certificationRows`, `certificationLine`, `showTestuCertScheduleSheet`, `showTestuSession`.
- Produces: `class TestuLiveCertificationCard extends StatelessWidget { const TestuLiveCertificationCard({required this.row, required this.canStart, required this.onTake, required this.onSchedule}); }` (exported from `testu_certifications.dart`); pure `CertRow? mostUrgentCertification(List<TopicProgress>)` (first of `expired` by expiry, else first `renewal_due`, else null); pure `String certificatesSummary(List<TopicProgress>)` → `'N valid · M due'`.

- [ ] **Step 1: Failing tests** (append to `testu_certifications_test.dart`):
```dart
  test('mostUrgentCertification picks expired first, then soonest due', () {
    expect(mostUrgentCertification(_live)!.topic, 'd');
    expect(mostUrgentCertification(_live.where((p) => p.state.id != 'd').toList())!.topic, 'c');
    expect(mostUrgentCertification([_live.first]), isNull);
  });
  test('certificatesSummary counts valid and due/expired', () {
    expect(certificatesSummary(_live), '1 valid · 3 due');
  });
  testWidgets('Today card words the urgent certification and offers schedule', (tester) async {
    final urgent = mostUrgentCertification(_live)!;
    var scheduled = 0;
    await tester.pumpWidget(MaterialApp(theme: testuTheme(), home: Scaffold(body: TestuLiveCertificationCard(row: urgent, canStart: true, onTake: () {}, onSchedule: () => scheduled++))));
    expect(find.textContaining('Delta'), findsOneWidget);
    expect(find.textContaining('expired'), findsOneWidget);
    await tester.tap(find.text('Schedule'));
    expect(scheduled, 1);
  });
```

- [ ] **Step 2: Run — red.**

- [ ] **Step 3: Implement**

In `testu_certifications.dart`:
```dart
CertRow? mostUrgentCertification(List<TopicProgress> live) {
  final g = certificationRows(live);
  final expired = g.pending.where((r) => r.cert.status == 'expired').toList();
  return expired.isNotEmpty ? expired.first : g.due.isNotEmpty ? g.due.first : null;
}
String certificatesSummary(List<TopicProgress> live) {
  final g = certificationRows(live);
  final due = g.due.length + g.pending.where((r) => r.cert.status == 'expired').length;
  return L('${g.valid.length} valid · $due due', '${g.valid.length} vigentes · $due por vencer');
}

class TestuLiveCertificationCard extends StatelessWidget {
  const TestuLiveCertificationCard({super.key, required this.row, required this.canStart, required this.onTake, required this.onSchedule});
  final CertRow row; final bool canStart; final VoidCallback onTake, onSchedule;
  @override
  Widget build(BuildContext context) {
    final t = TestuTokens.of(context);
    final c = row.cert;
    final title = c.status == 'expired'
        ? L('Your ${row.title} certificate expired ${testuInDays(testuDaysUntil(c.expiry!))}', 'Tu certificado de ${row.title} venció ${testuInDays(testuDaysUntil(c.expiry!))}')
        : L('Your ${row.title} certificate expires ${testuInDays(testuDaysUntil(c.expiry!))}', 'Tu certificado de ${row.title} vence ${testuInDays(testuDaysUntil(c.expiry!))}');
    return TestuCard(accent: t.amber, child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      TestuEyebrow(L('CERTIFICATION', 'CERTIFICACIÓN'), color: t.amber),
      const SizedBox(height: 7),
      Text(title, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
      const SizedBox(height: 4),
      Text(L('${client.tutor} says: take the renewal evaluation before it expires to keep your status.', '${client.tutor} dice: rinde la evaluación de renovación antes de que venza para mantener tu estado.'), style: TextStyle(color: t.mut, fontSize: 12)),
      const SizedBox(height: 12),
      Row(children: [
        if (canStart) TestuAct(L('Take it now', 'Rendir ahora'), primary: true, onTap: onTake),
        if (canStart) const SizedBox(width: 8),
        TestuAct(c.scheduledFor == null ? L('Schedule', 'Programar') : '✓ ${L('Scheduled', 'Programada')} · ${testuWeekday(c.scheduledFor!)}', onTap: onSchedule),
      ]),
    ]));
  }
}
```
(`client` from `testu_client.dart`; match `_CardTitle`/`_CardBody` styles from the prototype card if you prefer, but those are private to `testu_shell.dart` — plain `Text` with the same sizes is fine.)

`testu_shell.dart` `_TestuTodayScreenState`: add `late Future<List<TopicProgress>> _progress = loadAllTopicProgress();`, reload on `testuProgressChanged`; in `_cards`' live branch, before `_LiveDailyChallengeCard`, a `FutureBuilder` that renders `TestuLiveCertificationCard` when `mostUrgentCertification(data)` is non-null (`onTake: () => showTestuSession(context, topicId: r.topic, mode: 'evaluation')`, `onSchedule: () => showTestuCertScheduleSheet(...)`), `SizedBox.shrink()` otherwise; keep the prototype card in the `!testuLive` branch untouched.

`testu_dashboard.dart` live branch (L412): replace the comment with
```dart
        _CompLine(L('Certificates', 'Certificados'), certificatesSummary(live), certificationRows(live).due.isEmpty && !live.any((p) => p.state.certification?.status == 'expired') ? t.greenText : t.amber),
```
only when `live.any((p) => p.state.certification != null)`; keep `last: true` on Retention.

- [ ] **Step 4: Run** tests + analyze + full suite — green.

- [ ] **Step 5: Commit** — `feat(testu): live certification card on Today and dashboard line`.

---

### Task 16: Bundles, end-to-end check, docs

**Files:**
- Run: `app-genailabs/build_admin.sh`, `build_learn.sh` (from a clean worktree per memory `no-ci-committed-bundles` / `console-shots-goldens` — sibling `../eme-app-package` must resolve)
- Modify: `plugins/testu/tools/check_certification.sh` header comment (what it writes/cleans), `eme-server-minsur/plugins/testu/README` or the tools index if one lists check scripts (grep `check_evaluation.sh` in `plugins/testu/docs` and `tools/README*`)

- [ ] **Step 1: Server regression** — `bin/compile.sh`; announce; restart; run `check_learning.sh`, `check_evaluation.sh`, `check_profiles.sh`, `check_certification.sh` — all PASS.
- [ ] **Step 2: Flutter regression** — `flutter test` (full) and `flutter analyze` — clean.
- [ ] **Step 3: Build bundles** from a clean worktree of `app-genailabs` at the merge commit; `build_admin.sh` and `build_learn.sh`; commit the two bundle dirs in `eme-server-minsur` (`feat(webapp): admin + learn bundles with certifications`).
- [ ] **Step 4: Manual pass in the browser pane** (local Tomcat): as admin → Certificaciones › Personas shows the roster; set a validity on a profile in Personas › Perfiles; as a learner in that profile (OTP login per memory `local-learner-login-otp`) → Certificaciones tab appears, Today card after forcing `passedat` back with `put_row`, schedule a day, take the evaluation. Screenshot each to the scratchpad and send with SendUserFile.
- [ ] **Step 5: Memory** — update `testu-evaluation-mode.md` (`passed` no longer terminal for certification topics; `evaluationStatus` takes a `ZoneId`) and add a `testu-certifications` memory (row conventions: day values midnight UTC; `reload.json` after seeding rows; check script). Update `MEMORY.md`.
- [ ] **Step 6: Commit** docs/memory-related repo files; report to Diego: what shipped, what is not pushed, the deploy notes from the spec (rows on existing tables need `PUT` + `reload.json`; `permissionentityassigned` rows).

---

## Self-review

**Spec coverage**
- Data (table, 3 profile fields, attempt `passpercent`, list value, audits): Tasks 1, 2, 3, 4, 5, 6, 8 ✓
- Engine (expiry/window/status/day boundary, canstart, per-cycle attempts, pass mark, on-pass, Finished, profile changes on read, reminders, analytics): Tasks 2, 3, 4, 7, 8 ✓
- Endpoints (state, evaluation/start/submit, schedule, certifications, extend, manual, profiles, person): Tasks 4-7 ✓ (+ `runCertificationReminders` helper endpoint added in Task 8)
- Admin console (rail rename + redirect, Planes/Personas, filters — status/topic/team done; profile filter is accepted by the API but not exposed as a dropdown: add `profile` `Select` in Task 11 if the roster warrants; person page pill; profile editor): Tasks 10-12 ✓ (person-page actions deferred to the table — noted with a ponytail comment; spec says "same two actions": acceptable deviation, flagged for Diego in Task 16's report)
- Learner app (state model, tab hidden when empty, groups, CTAs, schedule sheet, Today card, dashboard, tutor line): Tasks 13-15 ✓; tutor line → Task 9 spec amendment
- Errors/edge cases (plan_inactive, recompute on read, extension rules, manual with in-progress attempt, validity 0, timezone): Tasks 3, 5, 6, 8 ✓
- Testing (LearningEngineCheck, check_certification.sh, Flutter tests): every task ✓
- Migration/rollout: Task 16 report ✓

**Type consistency**: `certStatus/certificationStatus/expiryOf/effectivePassPercent/dueStages/stagesAlreadyPast/certFinishedConjunct` (Tasks 3, 8) used by Tasks 4-8 with the same signatures; `CertificationRow`/`CertificationState` names distinct (admin vs learner); `postScheduleCertification` (Task 13) used in Task 14; `certificationRows/mostUrgentCertification/certificatesSummary/certificationLine` (Tasks 14-15) consistent.

**Placeholders**: none; every step has its code or the exact existing code to copy from (with file:line).
