# Job Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Admins define ordered job profiles (topics with required level, mandatory, gate-on-previous, keep/remove after finish), assign them to people, and the learning engine orders, locks and retires topics accordingly; learners see the result on Topics.

**Architecture:** Server-owned. The engine (`LearningEngine`) gains a pure `applyProfiles` step that reorders the learner's `Content`, marks locks/finished, and strips removed topics; every read/write endpoint runs it right after `loadLearner`. A new `TestUProfileModule` exposes CRUD under `services/testu/personas/`. The admin console's Personas screen gets a profile rail + editor; the learner Topics screen renders server order and lock state.

**Tech Stack:** Java (EME/EnterMedia modules, json-simple), EME data XML (fields/lists), Groovy CSV importer, Flutter/Dart (admin console + learner app), shell/Python check scripts.

**Spec:** `plugins/testu/docs/superpowers/specs/2026-09-16-job-profiles-design.md` (read it first; it holds the rules this plan implements).

## Global Constraints

- Two repos: server plugin = `eme-server-minsur/plugins/testu` (its own git repo, run git commands inside it); app = `app-genailabs`. Peers may be editing the same working trees: make exact-string edits, never reformat whole files, never `dart format` in app-genailabs, tell the user before restarting Tomcat.
- Product-first: one generic model, no pilot/client-specific logic. With no profile rows the behaviour is byte-for-byte today's (`assignment_data_unavailable` fallback).
- Server owns every decision (order, locked, finished, removed). Flutter renders `state.json`; it never sorts topics or computes locks.
- Names: entity = **Job profile / Perfil de puesto**; list `jobrole`; table `topicrequirement`; user fields `jobrole` (multi) + `primaryjobrole` (single). Lock reason code `previous_topic_incomplete`; error code `topic_locked`; `afterfinish` ∈ {`keep`, `remove`}.
- Endpoints return `{ok:true,...}` or `{ok:false,error:"code"}` via `reply()` / `fail()`; permissions live in the `.xconf` (`personas_view` reads, `personas_manage` writes).
- No manual `javac`: the running eMe compiles on save/restart. Java style: tabs, Allman braces, `inReq`/`inX` parameter prefix (match neighbours).
- Web bundles are committed (no CI): after app tasks run `build_admin.sh` / `build_learn.sh` and commit both copies.
- Add `.superpowers/` to `app-genailabs/.gitignore` (Task 5 does it).
- Data XML lives in `plugins/testu/data/...` and is mirrored to `eme-server-minsur/webapp/WEB-INF/data/site/catalog/...` (`fields/`, `lists/`) and `webapp/WEB-INF/data/system/fields/user.xml`; copy both.

---

## File map

Server (`eme-server-minsur/plugins/testu`):
- Modify `data/fields/topicrequirement.xml` — +position, mandatory, requiresprevious, afterfinish.
- Create `data/lists/afterfinish.xml` — keep | remove.
- Modify `data/system/fields/user.xml` — +primaryjobrole.
- Modify `code/tech/genailabs/tutor/LearningEngine.java` — ProfileRow/Profiles, `loadProfiles`, `applyProfiles`, Topic/Content/Learner fields, `topicState` fields, Daily Challenge required/locked, `resolve` topic_locked.
- Modify `code/tech/genailabs/tutor/TestULearningModule.java` — call `applyProfiles`; `state.json` extras; `next.json` 409.
- Modify `code/tech/genailabs/tutor/TestUAnalyticsModule.java` — person.json required topics in profile order.
- Create `code/tech/genailabs/tutor/TestUProfileModule.java` — profiles/saveprofile/deleteprofile/setprofiles.
- Modify `code/tech/genailabs/tutor/TestUUserModule.java` — users.json + me.json profile fields.
- Modify `html/src/plugin.xml` — bean `TestUProfileModule`.
- Create `html/services/testu/personas/{profiles,saveprofile,deleteprofile,setprofiles}.{xconf,json}`.
- Modify `html/services/testu/personas/import/scripts/ImportUsers.groovy` — `primaryjobrole`, `jobrole` columns.
- Modify `tools/LearningEngineCheck.java` — pure profile checks.
- Create `tools/check_profiles.sh` — server checks (local eMe).

App (`app-genailabs`):
- Modify `lib/admin/admin_models.dart` — `AdminProfile`, `ProfileRow`, `AdminTopicRef`, `AdminUser.primaryProfile/profiles`.
- Modify `lib/admin/admin_api.dart` — `profiles()`, `saveProfile()`, `deleteProfile()`, `setProfiles()`.
- Create `lib/admin/admin_profiles.dart` — `ProfileEditor` widget.
- Modify `lib/admin/admin_people.dart` — rail section, head slot, Profile column, profiles sheet.
- Modify `lib/testu/testu_learn.dart` — `TopicState` profile fields.
- Modify `lib/testu/testu_topics.dart` — order/lock/optional rendering, topic home line.
- Tests: `test/admin_api_test.dart` (+cases), `test/admin_profiles_test.dart` (new), `test/admin_people_test.dart` (+cases), `test/testu_topics_test.dart` (new).

---

### Task 1: Data definitions

**Files:**
- Modify: `plugins/testu/data/fields/topicrequirement.xml`
- Create: `plugins/testu/data/lists/afterfinish.xml`
- Modify: `plugins/testu/data/system/fields/user.xml`
- Mirror: `eme-server-minsur/webapp/WEB-INF/data/site/catalog/fields/topicrequirement.xml`, `.../lists/afterfinish.xml`, `eme-server-minsur/webapp/WEB-INF/data/system/fields/user.xml`

**Interfaces:**
- Produces: `topicrequirement` fields `position` (number), `mandatory` (boolean), `requiresprevious` (boolean), `afterfinish` (list afterfinish); `user.primaryjobrole` (list jobrole, single).

- [ ] **Step 1: Extend `topicrequirement.xml`**

Replace the file with:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Learning engine v1: required band per job role x topic; strictest wins across a user's job roles.
     Job profiles (spec 2026-09-16): one row per profile x topic, id <jobrole>_<entitytopic>. position = order in the profile (1-based),
     mandatory (default true), requiresprevious = locked until the previous row of the same profile is finished, afterfinish keep|remove. -->
<properties beanname="dataSearcher">
  <property id="id" index="true" stored="true" editable="false" keyword="true"><name><language id="en">ID</language></name></property>
  <property id="jobrole" index="true" stored="true" editable="true" type="list" listid="jobrole"><name><language id="en">Job profile</language><language id="es">Perfil de puesto</language></name></property>
  <property id="entitytopic" index="true" stored="true" editable="true" type="list" listid="entitytopic"><name><language id="en">Topic</language><language id="es">Tema</language></name></property>
  <property id="requiredlevel" index="true" stored="true" editable="true" type="list" listid="masterylevel"><name><language id="en">Required level</language><language id="es">Nivel requerido</language></name></property>
  <property id="position" index="true" stored="true" editable="true" type="number" datatype="number"><name><language id="en">Position</language><language id="es">Posición</language></name></property>
  <property id="mandatory" index="true" stored="true" editable="true" type="boolean" datatype="boolean"><name><language id="en">Mandatory</language><language id="es">Obligatorio</language></name></property>
  <property id="requiresprevious" index="true" stored="true" editable="true" type="boolean" datatype="boolean"><name><language id="en">Requires previous topic</language><language id="es">Requiere el tema anterior</language></name></property>
  <property id="afterfinish" index="true" stored="true" editable="true" type="list" listid="afterfinish"><name><language id="en">After finish</language><language id="es">Al terminar</language></name></property>
</properties>
```

- [ ] **Step 2: Create `afterfinish.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<afterfinishs>
  <afterfinish id="keep"><name><![CDATA[Mantener]]></name></afterfinish>
  <afterfinish id="remove"><name><![CDATA[Quitar]]></name></afterfinish>
</afterfinishs>
```

- [ ] **Step 3: Add `primaryjobrole` to `data/system/fields/user.xml`**

After the `jobrole` property line add:

```xml
  <!-- Job profiles: the primary profile orders the learner's topics; must be one of jobrole. -->
  <property id="primaryjobrole" index="true" stored="true" editable="true" type="list" viewtype="list" datatype="list" listid="jobrole" indextype="not_analyzed">Primary job profile</property>
```

- [ ] **Step 4: Mirror the three files into the webapp**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur
cp plugins/testu/data/fields/topicrequirement.xml webapp/WEB-INF/data/site/catalog/fields/topicrequirement.xml
cp plugins/testu/data/lists/afterfinish.xml webapp/WEB-INF/data/site/catalog/lists/afterfinish.xml
cp plugins/testu/data/system/fields/user.xml webapp/WEB-INF/data/system/fields/user.xml
```

- [ ] **Step 5: Verify eMe picks the fields up**

Tell the user you are about to restart Tomcat (peers share it), then restart via the project's usual way (`bin/restart.sh` if present, else the IDE launcher). Then:

```bash
curl -s -u "$EME_USER:$EME_PASSWORD" "http://localhost:8080/site/mediadb/services/lists/data/afterfinish.json" | head -c 400
```

Expected: JSON with `keep` and `remove` rows. Also `plugins/testu/tools/check_fields.sh` (if it lists topicrequirement) still passes.

- [ ] **Step 6: Commit (plugin repo)**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/testu
git add data/fields/topicrequirement.xml data/lists/afterfinish.xml data/system/fields/user.xml
git commit -m "job profiles: topicrequirement order/mandatory/gate/afterfinish, user.primaryjobrole, afterfinish list"
```

---

### Task 2: Engine — pure assignment (`applyProfiles`) with checks

**Files:**
- Modify: `plugins/testu/code/tech/genailabs/tutor/LearningEngine.java` (Topic class ~L66, Content ~L77, Learner ~L425, after `requiredLevel` ~L690)
- Test: `plugins/testu/tools/LearningEngineCheck.java`

**Interfaces:**
- Produces:
  - `LearningEngine.ProfileRow { String jobrole, topicid, requiredlevel, afterfinish; int position; boolean mandatory, requiresprevious }`
  - `LearningEngine.Profiles { List<ProfileRow> rows; Map<String,String> names }`
  - `Learner.primaryjobrole` (String)
  - `Topic`: `Integer position; String profile, profilename, previoustopic, afterfinish, assignedlevel; boolean mandatory, requiresprevious, locked, finished, removed`
  - `Content`: `List<String> removedtopics; List<JSONObject> profiles`
  - `static void applyProfiles(Content c, Learner l, Profiles p)` (pure), `Profiles loadProfiles(Collection<String> jobroles)` (DB), `void applyProfiles(Content c, Learner l)` (loads + applies), `static ProfileRow rowOf(Data d)`, `static List<String> profileOrder(Learner l, Map<String,String> names)`, `static boolean finished(Topic t, Learner l)`, `static boolean started(Topic t, Learner l)`, `static String primaryJobroleOf(Data user)`, `Learner loadLearner(String userid, Collection<String> jobroles, String primary)`.

- [ ] **Step 1: Write the failing pure checks**

In `tools/LearningEngineCheck.java`, add at the end of `main` (before the final failure count / exit — find `failures` usage at the bottom of `main`):

```java
		profileChecks();
```

and add these methods after `main`:

```java
	// ---- job profiles (spec 2026-09-16): pure assignment on a 4-topic fixture

	static LearningEngine.Profiles profiles(LearningEngine.ProfileRow... rows)
	{
		LearningEngine.Profiles p = new LearningEngine.Profiles();
		p.names.put("pilot", "Pilot");
		p.names.put("safety", "Safety lead");
		p.names.put("alpha", "Alpha");
		for (LearningEngine.ProfileRow r : rows)
		{
			p.rows.add(r);
		}
		return p;
	}

	static LearningEngine.ProfileRow row(String profile, String topic, int pos, String level, boolean mandatory, boolean reqPrev, String after)
	{
		LearningEngine.ProfileRow r = new LearningEngine.ProfileRow();
		r.jobrole = profile;
		r.topicid = topic;
		r.position = pos;
		r.requiredlevel = level;
		r.mandatory = mandatory;
		r.requiresprevious = reqPrev;
		r.afterfinish = after;
		return r;
	}

	static Learner withProfiles(List<Attempt> at, String primary, String... all)
	{
		Learner l = learner(at);
		l.primaryjobrole = primary;
		l.jobroles = new ArrayList<>(List.of(all));
		return l;
	}

	static List<String> order(Content c)
	{
		return new ArrayList<>(c.topics.keySet());
	}

	static List<Attempt> allCorrect(Content c, String topic)
	{
		List<Attempt> at = new ArrayList<>();
		for (Question q : c.topics.get(topic).questions)
		{
			at.add(attempt(q.id, "learn", true, "confident", 0, 10 + q.position));
		}
		return at;
	}

	static void profileChecks()
	{
		// pilot: t1 (remove after finish), t2 requires previous, t3, t4 optional; safety: t3 expert, t2 keep
		LearningEngine.Profiles p = profiles(
			row("pilot", "t1", 1, "competent", true, false, "remove"),
			row("pilot", "t2", 2, "competent", true, true, "keep"),
			row("pilot", "t3", 3, null, true, false, "keep"),
			row("pilot", "t4", 4, null, false, false, "keep"),
			row("safety", "t3", 1, "expert", true, false, "keep"),
			row("safety", "t2", 2, "beginner", false, true, "remove"));

		Content c = content(4, 3);
		Learner l = withProfiles(new ArrayList<>(), "pilot", "pilot", "safety");
		LearningEngine.applyProfiles(c, l, p);
		ok("profiles: assignment order = primary rows then extras", order(c).equals(List.of("t1", "t2", "t3", "t4")), order(c));
		ok("profiles: positions renumbered 1..n", c.topics.get("t1").position == 1 && c.topics.get("t4").position == 4, c.topics.get("t4").position);
		ok("profiles: shared topic keeps first occurrence's profile", "pilot".equals(c.topics.get("t3").profile) && "Pilot".equals(c.topics.get("t3").profilename), c.topics.get("t3").profile);
		ok("profiles: strictest level across profiles", "expert".equals(c.topics.get("t3").assignedlevel) && "competent".equals(c.topics.get("t2").assignedlevel), c.topics.get("t3").assignedlevel);
		ok("profiles: mandatory if any, keep beats remove", c.topics.get("t2").mandatory && "keep".equals(c.topics.get("t2").afterfinish), c.topics.get("t2").afterfinish);
		ok("profiles: optional topic stays optional", !c.topics.get("t4").mandatory, c.topics.get("t4").mandatory);
		ok("profiles: t2 locked behind unfinished t1 (previous_topic_incomplete)", c.topics.get("t2").locked && "t1".equals(c.topics.get("t2").previoustopic), c.topics.get("t2").previoustopic);
		ok("profiles: t1 not locked (no gate), t3 not locked", !c.topics.get("t1").locked && !c.topics.get("t3").locked, "");
		ok("profiles: nothing finished, nothing removed", !c.topics.get("t1").finished && c.removedtopics.isEmpty(), c.removedtopics);
		ok("profiles: profiles json primary first", c.profiles.size() == 2 && "pilot".equals(c.profiles.get(0).get("id")) && Boolean.TRUE.equals(c.profiles.get(0).get("primary")), c.profiles);

		// extras order by name: alpha (name "Alpha") before safety; primary still first
		Content c2 = content(4, 3);
		LearningEngine.Profiles p2 = profiles(row("safety", "t2", 1, null, true, false, "keep"), row("alpha", "t3", 1, null, true, false, "keep"), row("pilot", "t1", 1, null, true, false, "keep"));
		LearningEngine.applyProfiles(c2, withProfiles(new ArrayList<>(), "pilot", "safety", "alpha", "pilot"), p2);
		ok("profiles: extras sorted by name after primary", order(c2).subList(0, 3).equals(List.of("t1", "t3", "t2")), order(c2));

		// finished = learn complete + level; t1 all correct confident -> competent (100%) -> finished -> removed; t2 unlocks
		Content c3 = content(4, 3);
		LearningEngine.applyProfiles(c3, withProfiles(allCorrect(c, "t1"), "pilot", "pilot", "safety"), p);
		ok("profiles: finished t1 with remove is stripped and listed", !c3.topics.containsKey("t1") && c3.removedtopics.equals(List.of("t1")) && !c3.questions.containsKey("t1q1"), c3.removedtopics);
		ok("profiles: t2 unlocked once t1 finished", !c3.topics.get("t2").locked, c3.topics.get("t2").locked);
		ok("profiles: order after removal starts at t2 with position 2 kept", order(c3).get(0).equals("t2") && c3.topics.get("t2").position == 2, order(c3));

		// learn complete but below level -> not finished (t1 answered all, wrong) -> still present, t2 locked
		List<Attempt> wrong = new ArrayList<>();
		for (Question q : content(4, 3).topics.get("t1").questions)
		{
			wrong.add(attempt(q.id, "learn", false, "confident", 0, 5));
		}
		Content c4 = content(4, 3);
		LearningEngine.applyProfiles(c4, withProfiles(wrong, "pilot", "pilot", "safety"), p);
		ok("profiles: learn complete below required level is not finished", c4.topics.containsKey("t1") && !c4.topics.get("t1").finished && c4.topics.get("t2").locked, c4.topics.get("t1").finished);

		// started never relocks: one learn answer in t2 while t1 unfinished
		List<Attempt> startedT2 = new ArrayList<>();
		startedT2.add(attempt("t2q1", "learn", false, "notsure", 0, 2));
		Content c5 = content(4, 3);
		LearningEngine.applyProfiles(c5, withProfiles(startedT2, "pilot", "pilot", "safety"), p);
		ok("profiles: a started topic is never locked", !c5.topics.get("t2").locked, c5.topics.get("t2").locked);

		// an evaluation-only answer does not count as started
		List<Attempt> evalT2 = new ArrayList<>();
		evalT2.add(attempt("t2q1", "evaluation", true, "confident", 0, 2));
		Content c6 = content(4, 3);
		LearningEngine.applyProfiles(c6, withProfiles(evalT2, "pilot", "pilot", "safety"), p);
		ok("profiles: evaluation answer does not start a topic", c6.topics.get("t2").locked, c6.topics.get("t2").locked);

		// rows on unknown topics are skipped; gate uses the previous *visible* row
		Content c7 = content(2, 3);
		LearningEngine.Profiles p7 = profiles(row("pilot", "t1", 1, null, true, false, "keep"), row("pilot", "ghost", 2, null, true, false, "keep"), row("pilot", "t2", 3, null, true, true, "keep"));
		LearningEngine.applyProfiles(c7, withProfiles(new ArrayList<>(), "pilot", "pilot"), p7);
		ok("profiles: unknown topic skipped, gate falls back to previous visible row", c7.topics.get("t2").locked && "t1".equals(c7.topics.get("t2").previoustopic) && c7.topics.size() == 2, c7.topics.get("t2").previoustopic);

		// requiresprevious on the first row is ignored
		Content c8 = content(2, 3);
		LearningEngine.applyProfiles(c8, withProfiles(new ArrayList<>(), "pilot", "pilot"), profiles(row("pilot", "t1", 1, null, true, true, "keep")));
		ok("profiles: requiresprevious ignored on the first row", !c8.topics.get("t1").locked && !c8.topics.get("t1").requiresprevious, "");

		// no rows -> untouched (fallback path)
		Content c9 = content(2, 3);
		LearningEngine.applyProfiles(c9, withProfiles(new ArrayList<>(), null, "pilot"), profiles());
		ok("profiles: no rows leaves content untouched", order(c9).equals(List.of("t1", "t2")) && c9.topics.get("t1").position == null && c9.removedtopics.isEmpty(), order(c9));

		// unassigned topics follow in catalog order
		Content c10 = content(3, 3);
		LearningEngine.applyProfiles(c10, withProfiles(new ArrayList<>(), "pilot", "pilot"), profiles(row("pilot", "t3", 1, null, true, false, "keep")));
		ok("profiles: unassigned topics follow in catalog order", order(c10).equals(List.of("t3", "t1", "t2")) && c10.topics.get("t1").position == null, order(c10));

		// Daily Challenge: required = mandatory assigned; locked topic's sections excluded from the new pool
		Content c11 = content(4, 3);
		Learner l11 = withProfiles(new ArrayList<>(), "pilot", "pilot", "safety");
		LearningEngine.applyProfiles(c11, l11, p);
		Map<String, Boolean> req = LearningEngine.requiredTopics(c11, l11, null);
		ok("profiles: required = mandatory assigned (t4 optional)", Boolean.TRUE.equals(req.get("t1")) && Boolean.TRUE.equals(req.get("t3")) && !Boolean.TRUE.equals(req.get("t4")), req);
		JSONObject dc = LearningEngine.buildDailyChallenge(c11, l11, NOW, req, false, 5, 20, LearningEngine.lockedTopicSections(c11));
		boolean anyT2 = false;
		for (Object o : items(dc))
		{
			anyT2 |= String.valueOf(((JSONObject) o).get("questionid")).startsWith("t2q");
		}
		ok("profiles: locked topic never enters the Daily Challenge", !anyT2, dc);
		String first = String.valueOf(((JSONObject) items(dc).get(0)).get("questionid"));
		ok("profiles: Daily Challenge new fill starts with the first assigned topic", first.startsWith("t1q"), first);
	}
```

- [ ] **Step 2: Run the checks to verify they fail to compile**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur
CP="build:$(find plugins/system/lib plugins/finder/lib plugins/community/lib tomcat/lib -type f -name '*.jar' | tr '\n' ':')"
java -cp "$CP" plugins/testu/tools/LearningEngineCheck.java
```

Expected: compile error (`ProfileRow`, `applyProfiles`, `requiredTopics`, `lockedTopicSections` not found). Note: `build` must contain the compiled plugin classes (the running eMe writes them); if `build` is stale, restart eMe first.

- [ ] **Step 3: Add the model fields**

In `LearningEngine.java`, `Topic` class (after `public List<Question> questions = new ArrayList<>();`):

```java
		// Job profiles (spec 2026-09-16), set by applyProfiles; position null = not assigned by any profile of the learner.
		public Integer position;
		public String profile, profilename, previoustopic, afterfinish = "keep", assignedlevel;
		public boolean mandatory, requiresprevious, locked, finished, removed;
```

In `Content` (after `thresholdsreason`):

```java
		public List<String> removedtopics = new ArrayList<>(); // assigned topics finished with afterfinish=remove (stripped from topics/sections/questions)
		public List<JSONObject> profiles = new ArrayList<>(); // [{id, name, primary}] of the learner, primary first
```

In `Learner` (after `jobroles`):

```java
		public String primaryjobrole; // orders the assignment; null = none (extras alone, by name)
```

- [ ] **Step 4: Add `primaryJobroleOf` and the 3-arg `loadLearner`**

After `jobrolesOf(Data)`:

```java
	/** The user's primary job profile id, or null when unset. */
	public static String primaryJobroleOf(Data inUser)
	{
		if (inUser == null)
		{
			return null;
		}
		String v = inUser.get("primaryjobrole");
		return v == null || v.isEmpty() ? null : v;
	}
```

After the existing `loadLearner(String, Collection<String>)`:

```java
	public Learner loadLearner(String inUserid, Collection<String> inJobroles, String inPrimary)
	{
		Learner l = loadLearner(inUserid, inJobroles);
		l.primaryjobrole = inPrimary;
		return l;
	}
```

- [ ] **Step 5: Add the profile model, loader and pure `applyProfiles`**

After `requiredLevel(...)` (before the `// ---- subtopic progression` comment):

```java
	// ---------------------------------------------------------------- job profiles (spec 2026-09-16)

	/** One topicrequirement row: profile x topic. */
	public static class ProfileRow
	{
		public String jobrole, topicid, requiredlevel, afterfinish = "keep";
		public int position;
		public boolean mandatory = true, requiresprevious;
	}

	/** Every row of the learner's profiles, and profile id -> name. */
	public static class Profiles
	{
		public List<ProfileRow> rows = new ArrayList<>();
		public Map<String, String> names = new LinkedHashMap<>();
	}

	public static ProfileRow rowOf(Data d)
	{
		ProfileRow r = new ProfileRow();
		r.jobrole = d.get("jobrole");
		r.topicid = d.get("entitytopic");
		String lvl = d.get("requiredlevel");
		r.requiredlevel = lvl == null || lvl.isEmpty() ? null : lvl;
		r.position = intOr(d.get("position"), 0);
		r.mandatory = !"false".equals(String.valueOf(d.get("mandatory")));
		r.requiresprevious = "true".equals(String.valueOf(d.get("requiresprevious")));
		r.afterfinish = "remove".equals(d.get("afterfinish")) ? "remove" : "keep";
		return r;
	}

	public Profiles loadProfiles(Collection<String> inJobroles)
	{
		Profiles p = new Profiles();
		if (inJobroles == null || inJobroles.isEmpty())
		{
			return p;
		}
		for (String id : inJobroles)
		{
			Data d = fieldArchive.getCachedData("jobrole", id);
			p.names.put(id, d == null || d.getName() == null ? id : d.getName());
		}
		for (Object o : fieldArchive.query("topicrequirement").orgroup("jobrole", inJobroles).search())
		{
			p.rows.add(rowOf((Data) o));
		}
		return p;
	}

	/** Primary first, then the other profiles by name (ties by id). Ids the learner does not hold are ignored. */
	public static List<String> profileOrder(Learner l, Map<String, String> inNames)
	{
		List<String> out = new ArrayList<>();
		if (l.primaryjobrole != null && l.jobroles.contains(l.primaryjobrole))
		{
			out.add(l.primaryjobrole);
		}
		List<String> rest = new ArrayList<>();
		for (String id : l.jobroles)
		{
			if (!out.contains(id) && !rest.contains(id))
			{
				rest.add(id);
			}
		}
		rest.sort(Comparator.comparing((String id) -> inNames.getOrDefault(id, id)).thenComparing(id -> id));
		out.addAll(rest);
		return out;
	}

	/** Learn complete AND (no level, or band >= assignedlevel with expert evidence when expert). */
	public static boolean finished(Topic t, Learner l)
	{
		Mastery m = mastery(t.questions, l, t.competentmin, t.expertmin);
		if (m.questions == 0 || m.answered < m.questions)
		{
			return false;
		}
		if (t.assignedlevel == null)
		{
			return true;
		}
		return levelIndex(m.band) >= levelIndex(t.assignedlevel) && (!"expert".equals(t.assignedlevel) || m.evidence);
	}

	/** Any learn/dailychallenge answer or exposure on a question of t. */
	public static boolean started(Topic t, Learner l)
	{
		for (Question q : t.questions)
		{
			if (l.answeredInSequence.contains(q.id) || l.exposedInSequence.contains(q.id))
			{
				return true;
			}
		}
		return false;
	}

	/** loadProfiles + applyProfiles for the learner's own profiles. */
	public void applyProfiles(Content c, Learner l)
	{
		applyProfiles(c, l, loadProfiles(l.jobroles));
	}

	/**
	 * Pure. Reorders c.topics: the learner's assignment first (primary profile rows by position, then each extra profile by name), then
	 * the rest in catalog order. A topic in several profiles keeps its first occurrence for order, gate and previous topic; level =
	 * strictest, mandatory if any, keep beats remove. Rows on topics not in c or without questions are skipped (the gate then points at
	 * the previous visible row of that profile). Sets position (renumbered 1..n over the merged list), finished, locked (requiresprevious
	 * AND previous not finished AND not started), removed (afterfinish=remove AND finished; stripped from c and listed in c.removedtopics).
	 * No rows -> c untouched apart from c.profiles.
	 */
	public static void applyProfiles(Content c, Learner l, Profiles inProfiles)
	{
		List<String> order = profileOrder(l, inProfiles.names);
		c.profiles = new ArrayList<>();
		for (String id : order)
		{
			JSONObject p = new JSONObject();
			p.put("id", id);
			p.put("name", inProfiles.names.getOrDefault(id, id));
			p.put("primary", id.equals(l.primaryjobrole));
			c.profiles.add(p);
		}
		if (inProfiles.rows.isEmpty())
		{
			return;
		}
		Map<String, List<ProfileRow>> byProfile = new HashMap<>();
		for (ProfileRow r : inProfiles.rows)
		{
			byProfile.computeIfAbsent(r.jobrole, k -> new ArrayList<>()).add(r);
		}
		Map<String, Topic> assigned = new LinkedHashMap<>();
		for (String pid : order)
		{
			List<ProfileRow> rows = new ArrayList<>(byProfile.getOrDefault(pid, Collections.emptyList()));
			rows.sort(Comparator.comparingInt((ProfileRow r) -> r.position).thenComparing(r -> r.topicid));
			String prev = null; // previous visible row of this profile
			for (ProfileRow r : rows)
			{
				Topic t = c.topics.get(r.topicid);
				if (t == null || t.questions.isEmpty())
				{
					continue;
				}
				if (assigned.containsKey(t.id))
				{
					if (levelIndex(r.requiredlevel) > levelIndex(t.assignedlevel))
					{
						t.assignedlevel = r.requiredlevel;
					}
					t.mandatory |= r.mandatory;
					if ("keep".equals(r.afterfinish))
					{
						t.afterfinish = "keep";
					}
				}
				else
				{
					t.position = assigned.size() + 1;
					t.profile = pid;
					t.profilename = inProfiles.names.getOrDefault(pid, pid);
					t.assignedlevel = r.requiredlevel;
					t.mandatory = r.mandatory;
					t.afterfinish = r.afterfinish;
					t.requiresprevious = r.requiresprevious && prev != null;
					t.previoustopic = t.requiresprevious ? prev : null;
					assigned.put(t.id, t);
				}
				prev = t.id;
			}
		}
		if (assigned.isEmpty())
		{
			return;
		}
		for (Topic t : assigned.values())
		{
			t.finished = finished(t, l);
		}
		for (Topic t : assigned.values())
		{
			Topic p = t.previoustopic == null ? null : c.topics.get(t.previoustopic);
			t.locked = t.requiresprevious && p != null && !p.finished && !started(t, l);
			t.removed = "remove".equals(t.afterfinish) && t.finished;
		}
		Map<String, Topic> reordered = new LinkedHashMap<>();
		for (Topic t : assigned.values())
		{
			if (t.removed)
			{
				c.removedtopics.add(t.id);
				for (Section s : t.sections)
				{
					c.sections.remove(s.id);
				}
				for (Question q : t.questions)
				{
					c.questions.remove(q.id);
				}
			}
			else
			{
				reordered.put(t.id, t);
			}
		}
		for (Topic t : c.topics.values())
		{
			if (!assigned.containsKey(t.id))
			{
				reordered.put(t.id, t);
			}
		}
		c.topics = reordered;
	}

	/**
	 * Daily Challenge required map: assigned topics -> mandatory; no assignment for the learner -> topicrequirement by job role (v1);
	 * none at all -> every topic with questions (assignment_data_unavailable). inReason (nullable, 1 slot) receives the reason or null.
	 */
	public Map<String, Boolean> requiredTopics(Content c, Learner l, String[] inReason)
	{
		Map<String, Boolean> required = new HashMap<>();
		boolean any = false;
		for (Topic t : c.topics.values())
		{
			boolean r = t.position != null ? t.mandatory : requiredLevel(t.id, l.jobroles) != null;
			required.put(t.id, r);
			any |= r;
		}
		if (!any)
		{
			for (Topic t : c.topics.values())
			{
				required.put(t.id, !t.questions.isEmpty());
			}
			if (inReason != null)
			{
				inReason[0] = "assignment_data_unavailable";
			}
		}
		return required;
	}

	/** Section ids of locked topics: excluded from the Daily Challenge new pool like locked subtopics. */
	public static Set<String> lockedTopicSections(Content c)
	{
		Set<String> out = new HashSet<>();
		for (Topic t : c.topics.values())
		{
			if (t.locked)
			{
				for (Section s : t.sections)
				{
					out.add(s.id);
				}
			}
		}
		return out;
	}
```

`requiredTopics` is an instance method (it calls `requiredLevel`, which queries the DB) — the pure check in Step 1 calls `LearningEngine.requiredTopics(c11, l11, null)` statically. Make the check instead read the field directly: replace that line in the check with

```java
		Map<String, Boolean> req = new HashMap<>();
		for (Topic t : c11.topics.values())
		{
			req.put(t.id, t.position != null && t.mandatory);
		}
```

(Keep `requiredTopics` on the engine; it is exercised by the server checks in Task 3.)

- [ ] **Step 6: Wire `requiredTopics` into `dailyChallenge`**

In `dailyChallenge(Content c, Learner l)`, the `else` branch that builds a new set currently starts with:

```java
				Map<String, Boolean> required = new HashMap<>();
				boolean any = false;
				for (Topic t : c.topics.values())
				{
					boolean r = requiredLevel(t.id, l.jobroles) != null;
					required.put(t.id, r);
					any |= r;
				}
				if (!any)
				{
					// No role-topic assignment data for this user: every learner-visible topic with eligible questions is required.
					// (c holds only visible topics; there is no status/published field on entitytopic to filter on.)
					for (Topic t : c.topics.values())
					{
						required.put(t.id, !t.questions.isEmpty());
					}
				}
```

Replace those lines with:

```java
				String[] reason = new String[1];
				Map<String, Boolean> required = requiredTopics(c, l, reason);
```

Just below, `Set<String> locked = locked(subtopicStates(c, l));` becomes:

```java
				Set<String> locked = new HashSet<>(locked(subtopicStates(c, l)));
				locked.addAll(lockedTopicSections(c));
```

Find where `inputs.topicsreason` is set to `assignment_data_unavailable` in that method (grep `topicsreason`); make it use `reason[0]` (put `topicsreason` = `reason[0]`, null when assignment data exists). If the existing code derives it from `any`, delete that derivation.

- [ ] **Step 7: Run the pure checks**

Restart eMe (announce it) so `build/` has the new classes, then run the Step 2 command.

Expected: every `profiles:` line prints `ok:` and the exit code is 0 (all older checks still `ok:`).

- [ ] **Step 8: Commit**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/testu
git add code/tech/genailabs/tutor/LearningEngine.java tools/LearningEngineCheck.java
git commit -m "engine: job profile assignment (order, gate, finished, remove) + pure checks"
```

---

### Task 3: Engine integration — state.json, next/answer/exposure locks, analytics, server checks

**Files:**
- Modify: `plugins/testu/code/tech/genailabs/tutor/LearningEngine.java` (`topicState` ~L1947, `resolve` ~L1260)
- Modify: `plugins/testu/code/tech/genailabs/tutor/TestULearningModule.java` (`state` L20-53, `next` L55-130, `resolve` L517-531)
- Modify: `plugins/testu/code/tech/genailabs/tutor/TestUAnalyticsModule.java` (~L1390-1415)
- Create: `plugins/testu/tools/check_profiles.sh` (engine half; Task 4 appends the endpoint half)

**Interfaces:**
- Consumes: Task 2 (`applyProfiles`, Topic fields, `Content.removedtopics/profiles`, `primaryJobroleOf`, 3-arg `loadLearner`).
- Produces: `state.json` topic keys `position, profile, profilename, mandatory, requiresprevious, previoustopic, locked, lockreason, afterfinish, finished`; top-level `removedtopics`, `profiles`; `next.json`/`answer.json`/`exposure.json` → 409 `{ok:false, error:"topic_locked", topic, previoustopic}`; `person.json` `risk.requiredtopics[]` + `profile`, `position`.

- [ ] **Step 1: Write the failing server checks (engine half)**

Create `plugins/testu/tools/check_profiles.sh` (executable). It seeds two profiles by direct list writes (the endpoints come in Task 4), signs in as a fresh learner, and asserts `state.json`, `next.json`, `answer.json`. Local only, cleans up.

```sh
#!/bin/sh
# Job profiles (spec docs/superpowers/specs/2026-09-16-job-profiles-design.md): server checks against a local eMe as
# profile.check@testu.local. LOCAL ONLY. Writes and removes: jobrole pcheck-*, topicrequirement pcheck-*, the user's learning rows.
# Usage: EME_USER=... EME_PASSWORD=... plugins/testu/tools/check_profiles.sh   (from the server root)
set -eu
exec python3 - "$@" <<'PY'
import json, os, secrets, sys, urllib.error, urllib.request
from http.cookiejar import CookieJar
from urllib.parse import quote, urlencode, urlparse

B = os.environ.get("EME_BASE", "http://localhost:8080/site/mediadb")
ES = os.environ.get("ES", "http://localhost:9200/site_catalog")
for u in (B, ES):
    if urlparse(u).hostname not in ("localhost", "127.0.0.1"):
        sys.exit(f"refusing non-local host: {u}")
if not os.environ.get("EME_USER") or not os.environ.get("EME_PASSWORD"):
    sys.exit("set EME_USER and EME_PASSWORD (a local admin)")
USER, PASSWORD = "profile.check@testu.local", "Pc9-" + secrets.token_urlsafe(24)
FAILS = []


def session():
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))


def call(op, method, path, body=None, form=None, base=B):
    data, headers = None, {}
    if body is not None:
        data, headers = json.dumps(body).encode(), {"Content-Type": "application/json"}
    elif form is not None:
        data, headers = urlencode(form).encode(), {"Content-Type": "application/x-www-form-urlencoded"}
    req = urllib.request.Request(base + path, data=data, method=method, headers=headers)
    try:
        with op.open(req, timeout=300) as r:
            raw, status = r.read(), r.status
    except urllib.error.HTTPError as e:
        raw, status = e.read(), e.code
    try:
        return status, json.loads(raw)
    except ValueError:
        return status, raw


def ok(name, cond, detail=""):
    print(("ok: " if cond else "FAIL: ") + name + ("" if cond else f" -> {str(detail)[:600]}"))
    if not cond:
        FAILS.append(name)


def must(what, res):
    if res[0] // 100 != 2:
        raise SystemExit(f"{what} failed: HTTP {res[0]} {str(res[1])[:300]}")
    return res[1]


def login(user, password):
    op = session()
    st, body = call(op, "POST", "/services/authentication/login.json", body={"id": user, "password": password})
    if st != 200 or body.get("response", {}).get("status") != "ok":
        raise SystemExit(f"login {user} failed: {st} {body}")
    return op


admin = login(os.environ["EME_USER"], os.environ["EME_PASSWORD"])
es = session()


def es_ids(table, query):
    st, res = call(es, "POST", f"/{table}/_search", body={"size": 10000, "query": query, "_source": False}, base=ES)
    return [h["_id"] for h in res.get("hits", {}).get("hits", [])] if st == 200 else []


def delete_rows(table, ids):
    for i in ids:
        call(admin, "DELETE", f"/services/lists/data/{table}/{quote(i)}.json")


def refresh():
    call(es, "POST", "/_refresh", body={}, base=ES)


USER_TABLES = ("tutoranswer", "tutorexposure", "dailychallengeset", "learningsession", "tutormastery", "tutordaily", "subtopicunlock")


def wipe_user_rows():
    refresh()
    for t in USER_TABLES:
        delete_rows(t, es_ids(t, {"term": {"user": USER}}))


def usersave(field, value):
    must(f"usersave {field}", call(admin, "POST", "/services/authentication/usersave.json", form={"username": USER, "field": field, field + "value": value}))


def put_row(table, rid, body):
    body = dict(body, id=rid)
    must(f"{table} {rid}", call(admin, "PUT", f"/services/lists/data/{table}/{quote(rid)}.json", body=body))


def state(topic=None):
    return must("state", call(me, "GET", "/services/testu/learn/state.json" + (f"?topicid={topic}" if topic else "")))


def topic_of(st, tid):
    return next(t for t in st["topics"] if t["id"] == tid)


def nxt(**params):
    return call(me, "GET", "/services/testu/learn/next.json?" + urlencode(params))


P1, P2 = "pcheck-pilot", "pcheck-safety"
ROWS = []
try:
    # ---- setup: learner, two profiles
    users = must("users.json", call(admin, "GET", "/services/testu/personas/users.json"))["users"]
    if next((u for u in users if u["id"] == USER), None) is None:
        must("createuser", call(admin, "POST", "/services/testu/personas/createuser.json", form={"email": USER, "firstName": "Profile", "lastName": "Check", "role": "users"}))
    else:
        usersave("enabled", "true")
    usersave("password", PASSWORD)
    usersave("jobrole", "")
    usersave("primaryjobrole", "")
    wipe_user_rows()
    me = login(USER, PASSWORD)
    base = state()
    TOPICS = [t["id"] for t in base["topics"] if t["questions"] > 0]
    if len(TOPICS) < 3:
        sys.exit("need at least 3 learner-visible topics with questions")
    T1, T2, T3 = TOPICS[:3]
    ok("no profile: no position, no locks, no profiles", all(t.get("position") is None and not t.get("locked") for t in base["topics"]) and base.get("profiles") == [] and base.get("removedtopics") == [], base.get("profiles"))

    put_row("jobrole", P1, {"name": "Pcheck Pilot"})
    put_row("jobrole", P2, {"name": "Pcheck Safety"})
    for rid, role, tid, pos, lvl, mand, prev, after in (
        ("pcheck-r1", P1, T3, 1, "", "true", "false", "remove"),
        ("pcheck-r2", P1, T1, 2, "competent", "true", "true", "keep"),
        ("pcheck-r3", P2, T2, 1, "expert", "false", "false", "keep"),
        ("pcheck-r4", P2, T1, 2, "expert", "true", "false", "keep"),
    ):
        put_row("topicrequirement", rid, {"jobrole": role, "entitytopic": tid, "position": str(pos), "requiredlevel": lvl, "mandatory": mand, "requiresprevious": prev, "afterfinish": after})
        ROWS.append(rid)
    usersave("jobrole", f"{P1}|{P2}")
    usersave("primaryjobrole", P1)
    refresh()

    # ---- state.json
    st = state()
    ids = [t["id"] for t in st["topics"]]
    ok("state: assignment order T3, T1, T2 then the rest", ids[:3] == [T3, T1, T2], ids[:4])
    t1, t2, t3 = topic_of(st, T1), topic_of(st, T2), topic_of(st, T3)
    ok("state: positions 1..3, profile ids and names", (t3["position"], t1["position"], t2["position"]) == (1, 2, 3) and t3["profile"] == P1 and t3["profilename"] == "Pcheck Pilot" and t2["profile"] == P2, (t3, t2))
    ok("state: T1 locked behind T3 with reason and previoustopic", t1["locked"] is True and t1["lockreason"] == "previous_topic_incomplete" and t1["previoustopic"] == T3, t1)
    ok("state: strictest level on shared T1 (expert) and mandatory", t1["requiredlevel"] == "expert" and t1["mandatory"] is True, t1)
    ok("state: T2 optional, not locked, no level -> requiredlevel expert from row", t2["mandatory"] is False and t2["locked"] is False, t2)
    ok("state: afterfinish/finished present", t3["afterfinish"] == "remove" and t3["finished"] is False and t1["afterfinish"] == "keep", t3)
    ok("state: profiles primary first", [p["id"] for p in st["profiles"]] == [P1, P2] and st["profiles"][0]["primary"] is True and st["profiles"][0]["name"] == "Pcheck Pilot", st["profiles"])
    ok("state: unassigned topics have null position", all(t.get("position") is None for t in st["topics"][3:]), st["topics"][3:4])

    # ---- locks on next/answer/exposure
    stc, body = nxt(mode="learn", topicid=T1)
    ok("next: learn on a locked topic = 409 topic_locked with previoustopic", stc == 409 and body["error"] == "topic_locked" and body["topic"] == T1 and body["previoustopic"] == T3, (stc, body))
    stc, body = nxt(mode="improve", topicid=T1)
    ok("next: improve on a locked topic = 409 topic_locked", stc == 409 and body["error"] == "topic_locked", (stc, body))
    ln = must("learn T3", nxt(mode="learn", topicid=T3))
    q = ln["items"][0]
    r = call(me, "POST", "/services/testu/learn/exposure.json", form={"mode": "learn", "scopetype": "topic", "scopeid": T1, "questionid": q["questionid"], "sessionid": ln["sessionid"]})
    ok("exposure: locked topic rejected before session checks (scope mismatch or topic_locked, never 2xx)", r[0] in (409,), r)
    dc = must("dailychallenge", nxt(mode="dailychallenge"))
    ok("dailychallenge: no question of the locked topic, first new from T3", all(i["topicid"] != T1 for i in dc["items"]) and any(i["topicid"] == T3 for i in dc["items"]), [i["topicid"] for i in dc["items"]])

    # ---- finish T3 (all correct, confident) -> removed; T1 unlocks
    for i in ln["items"]:
        must("answer", call(me, "POST", "/services/testu/learn/answer.json", form={"mode": "learn", "scopetype": "topic", "scopeid": T3, "questionid": i["questionid"], "sessionid": ln["sessionid"], "answer": i.get("correctoption", "option_a"), "confidence": "confident", "attemptid": "pc" + secrets.token_hex(8)}))
    refresh()
    st2 = state()
    ok("finish: T3 removed (absent from topics, listed in removedtopics)", T3 not in [t["id"] for t in st2["topics"]] and st2["removedtopics"] == [T3], st2["removedtopics"])
    ok("finish: T1 unlocked, keeps position 2", topic_of(st2, T1)["locked"] is False and topic_of(st2, T1)["position"] == 2, topic_of(st2, T1))

    # ---- analytics person.json in profile order
    pj = must("person.json", call(admin, "GET", f"/services/testu/analytics/person.json?user={quote(USER)}"))
    req = pj["risk"]["requiredtopics"]
    ok("person: required topics in profile order, T3 excluded (removed), profile/position present", [x["id"] for x in req][:2] == [T1, T2] and req[0]["profile"] == P1 and req[0]["position"] == 2, req)
finally:
    delete_rows("topicrequirement", ROWS)
    call(admin, "POST", "/services/authentication/usersave.json", form={"username": USER, "field": "jobrole", "jobrolevalue": ""})
    call(admin, "POST", "/services/authentication/usersave.json", form={"username": USER, "field": "primaryjobrole", "primaryjobrolevalue": ""})
    delete_rows("jobrole", [P1, P2])
    wipe_user_rows()
    call(admin, "POST", "/services/testu/personas/disableuser.json", form={"userid": USER})
    print("cleanup done")

print("PASS" if not FAILS else f"FAIL ({len(FAILS)}): " + "; ".join(FAILS))
sys.exit(1 if FAILS else 0)
PY
```

Note on the answer form: check `check_learning.sh`'s `post_answer` usage to confirm the parameter names for the chosen option and confidence (`answer`/`confidence` vs. others) and the `correctoption` key on `next.json` items; adjust the two form keys in this script to match — the goal is "every question of T3 answered correctly with confidence confident".

- [ ] **Step 2: Run it to verify it fails**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur
chmod +x plugins/testu/tools/check_profiles.sh
EME_USER=... EME_PASSWORD=... plugins/testu/tools/check_profiles.sh
```

Expected: FAIL lines for `state: assignment order`, `next: learn on a locked topic`, etc.

- [ ] **Step 3: `topicState` fields**

In `LearningEngine.topicState(Topic t, Learner l, Map<String, JSONObject> inStates)`, after `o.put("expertevidence", evidence(m));` add:

```java
		o.put("position", t.position);
		o.put("profile", t.profile);
		o.put("profilename", t.profilename);
		o.put("mandatory", t.position == null ? null : Boolean.valueOf(t.mandatory));
		o.put("requiresprevious", t.position == null ? null : Boolean.valueOf(t.requiresprevious));
		o.put("previoustopic", t.previoustopic);
		o.put("locked", Boolean.valueOf(t.locked));
		o.put("lockreason", t.locked ? "previous_topic_incomplete" : null);
		o.put("afterfinish", t.position == null ? null : t.afterfinish);
		o.put("finished", t.position == null ? null : Boolean.valueOf(t.finished));
```

- [ ] **Step 4: `resolve` rejects locked topics**

In `LearningEngine.resolve(...)`, right after the `scope_mismatch` check and before the `subtopic_locked` check, add:

```java
			Topic top = c.topics.get(q.topicid);
			if (top != null && top.locked)
			{
				return Resolved.fail(409, "topic_locked");
			}
```

- [ ] **Step 5: `TestULearningModule` — apply profiles, state extras, next 409**

`state()`: replace

```java
		LearningEngine.Learner learner = engine.loadLearner(user.getId(), LearningEngine.jobrolesOf(user));
```

with

```java
		LearningEngine.Learner learner = engine.loadLearner(user.getId(), LearningEngine.jobrolesOf(user), LearningEngine.primaryJobroleOf(user));
		engine.applyProfiles(content, learner);
```

and after `resp.put("topics", topics);` add:

```java
		resp.put("removedtopics", content.removedtopics);
		resp.put("profiles", content.profiles);
```

`next()`: same two-line replacement for its `loadLearner` line (before the `dailychallenge` branch). Then after the `unknown_section` check (before `String scopetype = ...`) add:

```java
		if (topic.locked)
		{
			JSONObject err = new JSONObject();
			err.put("ok", Boolean.FALSE);
			err.put("error", "topic_locked");
			err.put("topic", topic.id);
			err.put("previoustopic", topic.previoustopic);
			if (inReq.getResponse() != null)
			{
				inReq.getResponse().setStatus(409);
			}
			reply(inReq, err);
			inReq.setCancelActions(true);
			return;
		}
```

`resolve(WebPageRequest, LearningEngine, User, boolean)`: same two-line replacement for its `loadLearner` line.

`topicid` lookup in `state()`: a removed topic requested by id now 404s (`unknown_topic`) — acceptable, it is no longer the learner's.

- [ ] **Step 6: Analytics person.json**

In `TestUAnalyticsModule` (~L1393) replace

```java
		LearningEngine.Learner learner = engine.loadLearner(uid, LearningEngine.jobrolesOf(u));
		JSONArray required = new JSONArray();
		JSONObject lowest = null;
		int gaps = 0;
		for (LearningEngine.Topic t : engine.loadContent().topics.values())
		{
```

with

```java
		LearningEngine.Learner learner = engine.loadLearner(uid, LearningEngine.jobrolesOf(u), LearningEngine.primaryJobroleOf(u));
		LearningEngine.Content pcontent = engine.loadContent();
		engine.applyProfiles(pcontent, learner);
		JSONArray required = new JSONArray();
		JSONObject lowest = null;
		int gaps = 0;
		for (LearningEngine.Topic t : pcontent.topics.values())
		{
```

and inside the loop, after `ro.put("requiredlevel", req);` add:

```java
			ro.put("profile", t.profile);
			ro.put("position", t.position);
```

Also change the `if (req == null) continue;` so optional assigned rows without a level are skipped as before but mandatory ones without a level are listed: replace it with

```java
			if (req == null && !(t.position != null && t.mandatory))
				continue;
```

(and guard the `gap` computation: `int gap = req == null ? 0 : LearningEngine.levelIndex(req) - LearningEngine.levelIndex((String) st.get("band"));`).

- [ ] **Step 7: Restart eMe (announce), run both checks**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur
EME_USER=... EME_PASSWORD=... plugins/testu/tools/check_profiles.sh
EME_USER=... EME_PASSWORD=... plugins/testu/tools/check_learning.sh
```

Expected: both end with `PASS`. `check_learning.sh` proves the no-profile fallback is unchanged (its user has no profile rows).

- [ ] **Step 8: Commit**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/testu
git add code/tech/genailabs/tutor/LearningEngine.java code/tech/genailabs/tutor/TestULearningModule.java code/tech/genailabs/tutor/TestUAnalyticsModule.java tools/check_profiles.sh
git commit -m "learn: state.json profile order/locks/removed, 409 topic_locked, person.json in profile order; check_profiles.sh"
```

---

### Task 4: Profile endpoints — `TestUProfileModule`, users/me fields, CSV import

**Files:**
- Create: `plugins/testu/code/tech/genailabs/tutor/TestUProfileModule.java`
- Modify: `plugins/testu/html/src/plugin.xml`
- Create: `plugins/testu/html/services/testu/personas/profiles.xconf`, `profiles.json`, `saveprofile.xconf`, `saveprofile.json`, `deleteprofile.xconf`, `deleteprofile.json`, `setprofiles.xconf`, `setprofiles.json`
- Modify: `plugins/testu/code/tech/genailabs/tutor/TestUUserModule.java` (`loadMe` ~L101-107, `loadUsers` ~L284-292)
- Modify: `plugins/testu/html/services/testu/personas/import/scripts/ImportUsers.groovy`
- Mirror the four `.xconf/.json` pairs and the groovy into `eme-server-minsur/webapp/site/mediadb/services/testu/personas/` (same relative paths) if that tree is not a symlink of the plugin (check with `ls -la`).
- Test: append to `plugins/testu/tools/check_profiles.sh`

**Interfaces:**
- Consumes: `LearningEngine.rowOf`, `jobrolesOf`, `primaryJobroleOf`, `loadContent()`; `TestUBaseModule.reply/fail/audit/snapshot`.
- Produces:
  - `GET profiles.json` → `{ok, profiles:[{id,name,members,rows:[{topic,topictitle,position,requiredlevel,mandatory,requiresprevious,afterfinish,questions}]}], topics:[{id,title,questions}]}`
  - `POST saveprofile.json` form `id` (blank = create), `name`, `rows` (JSON array string) → `{ok, profile:{...}}`; errors 400 `missing_name|duplicate_name|bad_rows|unknown_topic|duplicate_topic|bad_level|bad_afterfinish|first_row_gate`, 404 `unknown_profile`.
  - `POST deleteprofile.json` form `id` → `{ok}`; 404 `unknown_profile`; 409 `profile_in_use` + `members`.
  - `POST setprofiles.json` form `user`, `primary`, `extras` (JSON array string) → `{ok, primaryjobrole, jobroles}`; 404 `no user`; 400 `unknown_profile|primary_in_extras|missing_primary`.
  - `users.json` / `me.json` user objects gain `primaryjobrole` (string|null) and `jobroles` (array).
  - CSV columns `primaryjobrole`, `jobrole` (`|`-separated), each a profile id or name.

- [ ] **Step 1: Append the failing endpoint checks**

In `check_profiles.sh`, inside the `try:` block just before the `# ---- analytics person.json` section, insert:

```python
    # ---- endpoints (Task 4)
    PP = "/services/testu/personas/"
    r = call(me, "GET", PP + "profiles.json")
    ok("profiles.json: learner gets 403", r[0] == 403, r)
    for ep in ("saveprofile", "deleteprofile", "setprofiles"):
        r = call(me, "POST", PP + ep + ".json", form={"id": "x"})
        ok(f"{ep}.json: learner gets 403", r[0] == 403, r)

    def rows(*specs):
        return json.dumps([{"topic": t, "requiredlevel": lvl, "mandatory": m, "requiresprevious": rp, "afterfinish": af} for t, lvl, m, rp, af in specs])

    r = call(admin, "POST", PP + "saveprofile.json", form={"id": "", "name": "", "rows": rows()})
    ok("saveprofile: blank name = 400 missing_name", r[0] == 400 and r[1]["error"] == "missing_name", r)
    r = call(admin, "POST", PP + "saveprofile.json", form={"id": "", "name": "Pcheck New", "rows": rows((T1, "", True, True, "keep"))})
    ok("saveprofile: requiresprevious on the first row = 400 first_row_gate", r[0] == 400 and r[1]["error"] == "first_row_gate", r)
    r = call(admin, "POST", PP + "saveprofile.json", form={"id": "", "name": "Pcheck New", "rows": rows((T1, "", True, False, "keep"), (T1, "", True, False, "keep"))})
    ok("saveprofile: duplicate topic = 400 duplicate_topic", r[0] == 400 and r[1]["error"] == "duplicate_topic", r)
    r = call(admin, "POST", PP + "saveprofile.json", form={"id": "", "name": "Pcheck New", "rows": rows((T1, "guru", True, False, "keep"))})
    ok("saveprofile: bad level = 400 bad_level", r[0] == 400 and r[1]["error"] == "bad_level", r)
    r = call(admin, "POST", PP + "saveprofile.json", form={"id": "", "name": "pcheck pilot", "rows": rows()})
    ok("saveprofile: duplicate name (case-insensitive) = 400 duplicate_name", r[0] == 400 and r[1]["error"] == "duplicate_name", r)

    created = must("saveprofile create", call(admin, "POST", PP + "saveprofile.json", form={"id": "", "name": "Pcheck New", "rows": rows((T2, "competent", True, False, "keep"), (T1, "", False, True, "remove"))}))
    NEW = created["profile"]["id"]
    ROWS.extend([f"{NEW}_{T2}", f"{NEW}_{T1}"])
    ok("saveprofile: id is a slug of the name, rows renumbered 1..n with titles and question counts", NEW == "pcheck-new" and [x["position"] for x in created["profile"]["rows"]] == [1, 2] and created["profile"]["rows"][0]["topic"] == T2 and created["profile"]["rows"][0]["questions"] > 0 and created["profile"]["rows"][0]["topictitle"], created["profile"])
    lst = must("profiles.json", call(admin, "GET", PP + "profiles.json"))
    mine = next(p for p in lst["profiles"] if p["id"] == NEW)
    ok("profiles.json: lists the new profile with 0 members and every topic", mine["members"] == 0 and any(t["id"] == T1 for t in lst["topics"]), mine)
    pil = next(p for p in lst["profiles"] if p["id"] == P1)
    ok("profiles.json: seeded rows read back (position, gate, afterfinish)", [x["topic"] for x in pil["rows"]] == [T3, T1] and pil["rows"][1]["requiresprevious"] is True and pil["rows"][0]["afterfinish"] == "remove" and pil["members"] == 1, pil)

    saved = must("saveprofile reorder", call(admin, "POST", PP + "saveprofile.json", form={"id": NEW, "name": "Pcheck New 2", "rows": rows((T1, "", True, False, "keep"))}))
    refresh()
    ok("saveprofile: replaces the row set (T2 row gone), renames", [x["topic"] for x in saved["profile"]["rows"]] == [T1] and saved["profile"]["name"] == "Pcheck New 2" and not es_ids("topicrequirement", {"term": {"_id": f"{NEW}_{T2}"}}), saved["profile"])

    r = call(admin, "POST", PP + "setprofiles.json", form={"user": USER, "primary": NEW, "extras": json.dumps([NEW])})
    ok("setprofiles: primary in extras = 400 primary_in_extras", r[0] == 400 and r[1]["error"] == "primary_in_extras", r)
    r = call(admin, "POST", PP + "setprofiles.json", form={"user": USER, "primary": "", "extras": json.dumps([P2])})
    ok("setprofiles: extras without primary = 400 missing_primary", r[0] == 400 and r[1]["error"] == "missing_primary", r)
    r = call(admin, "POST", PP + "setprofiles.json", form={"user": "nobody@x", "primary": NEW, "extras": "[]"})
    ok("setprofiles: unknown user = 404", r[0] == 404, r)
    sp = must("setprofiles", call(admin, "POST", PP + "setprofiles.json", form={"user": USER, "primary": NEW, "extras": json.dumps([P1])}))
    ok("setprofiles: stores primary + list", sp["primaryjobrole"] == NEW and sorted(sp["jobroles"]) == sorted([NEW, P1]), sp)
    ulist = must("users.json", call(admin, "GET", PP + "users.json"))["users"]
    u = next(x for x in ulist if x["id"] == USER)
    ok("users.json: primaryjobrole and jobroles", u["primaryjobrole"] == NEW and sorted(u["jobroles"]) == sorted([NEW, P1]), u)
    mj = must("me.json", call(me, "GET", PP + "me.json"))
    ok("me.json: primaryjobrole and jobroles", mj["user"]["primaryjobrole"] == NEW and NEW in mj["user"]["jobroles"], mj["user"])

    r = call(admin, "POST", PP + "deleteprofile.json", form={"id": NEW})
    ok("deleteprofile: in use = 409 profile_in_use with members", r[0] == 409 and r[1]["error"] == "profile_in_use" and r[1]["members"] == 1, r)
    must("setprofiles back", call(admin, "POST", PP + "setprofiles.json", form={"user": USER, "primary": P1, "extras": json.dumps([P2])}))
    must("deleteprofile", call(admin, "POST", PP + "deleteprofile.json", form={"id": NEW}))
    refresh()
    ok("deleteprofile: rows and list entry gone", not es_ids("topicrequirement", {"term": {"jobrole": NEW}}) and call(admin, "GET", f"/services/lists/data/jobrole/{NEW}.json")[0] in (404, 200) and NEW not in [p["id"] for p in must("profiles.json", call(admin, "GET", PP + "profiles.json"))["profiles"]], NEW)
    r = call(admin, "POST", PP + "deleteprofile.json", form={"id": NEW})
    ok("deleteprofile: unknown = 404", r[0] == 404, r)
    refresh()
    aud = es_ids("auditevent", {"bool": {"must": [{"term": {"action": "jobprofile.save"}}, {"term": {"targetid": NEW}}]}})
    ok("audit: jobprofile.save rows written", len(aud) >= 2, aud)

    # CSV import with profile columns (names and ids)
    csv = f"email,firstName,lastName,primaryjobrole,jobrole\npcheck-import@testu.local,Imp,Ort,Pcheck Pilot,{P1}|Pcheck Safety\n"
    import io, mimetypes
    boundary = "----pcheck" + secrets.token_hex(6)
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"users.csv\"\r\nContent-Type: text/csv\r\n\r\n{csv}\r\n--{boundary}--\r\n").encode()
    req = urllib.request.Request(B + PP + "importusers.json", data=body, method="POST", headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        with admin.open(req, timeout=120) as rr:
            imp = (rr.status, json.loads(rr.read()))
    except urllib.error.HTTPError as e:
        imp = (e.code, e.read())
    ok("import: profile columns accepted", imp[0] == 200 and imp[1].get("imported") == 1, imp)
    refresh()
    ulist = must("users.json", call(admin, "GET", PP + "users.json"))["users"]
    iu = next((x for x in ulist if x["id"] == "pcheck-import@testu.local"), None)
    ok("import: names resolved to ids, primary set", iu is not None and iu["primaryjobrole"] == P1 and sorted(iu["jobroles"]) == sorted([P1, P2]), iu)
    csv_bad = "email,firstName,lastName,primaryjobrole\npcheck-import2@testu.local,A,B,No Such Profile\n"
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"users.csv\"\r\nContent-Type: text/csv\r\n\r\n{csv_bad}\r\n--{boundary}--\r\n").encode()
    req = urllib.request.Request(B + PP + "importusers.json", data=body, method="POST", headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        with admin.open(req, timeout=120) as rr:
            imp = (rr.status, json.loads(rr.read()))
    except urllib.error.HTTPError as e:
        imp = (e.code, e.read())
    ok("import: unknown profile name = 400", imp[0] == 400, imp)
```

And in the `finally:` block add, before `print("cleanup done")`:

```python
    call(admin, "POST", "/services/testu/personas/deleteuser.json", form={"userid": "pcheck-import@testu.local"})
    call(admin, "POST", "/services/testu/personas/deleteuser.json", form={"userid": "pcheck-import2@testu.local"})
    delete_rows("jobrole", ["pcheck-new"])
```

- [ ] **Step 2: Run to verify failure**

Run `check_profiles.sh` as in Task 3. Expected: the `profiles.json`/`saveprofile`/… checks FAIL (404s), the Task 3 checks still pass.

- [ ] **Step 3: Create `TestUProfileModule.java`**

```java
package tech.genailabs.tutor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.entermediadb.asset.MediaArchive;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.openedit.Data;
import org.openedit.WebPageRequest;
import org.openedit.data.Searcher;
import org.openedit.hittracker.HitTracker;

/**
 * services/testu/personas/{profiles,saveprofile,deleteprofile,setprofiles}.json -- job profiles (spec 2026-09-16).
 * A profile = a `jobrole` list entry + its `topicrequirement` rows (id <jobrole>_<entitytopic>). Permissions are in the .xconf
 * (personas_view reads, personas_manage writes). Every write is audited (jobprofile.save|delete|assign).
 */
public class TestUProfileModule extends TestUBaseModule
{
	static final Set<String> LEVELS = Set.of("beginner", "competent", "expert");
	static final Set<String> AFTERFINISH = Set.of("keep", "remove");
	static final Object LOCK = new Object(); // ponytail: one JVM; saves are rare and small

	public void profiles(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		LearningEngine.Content content = new LearningEngine(archive).loadContent();
		Map<String, Integer> members = memberCounts(archive);
		JSONArray profiles = new JSONArray();
		for (Data p : profileList(archive))
		{
			profiles.add(profileJson(archive, content, p, members));
		}
		JSONArray topics = new JSONArray();
		for (LearningEngine.Topic t : content.topics.values())
		{
			JSONObject o = new JSONObject();
			o.put("id", t.id);
			o.put("title", t.title);
			o.put("questions", Integer.valueOf(t.questions.size()));
			topics.add(o);
		}
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("profiles", profiles);
		resp.put("topics", topics);
		reply(inReq, resp);
	}

	public void saveProfile(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		String id = trim(inReq.getRequestParameter("id"));
		String name = trim(inReq.getRequestParameter("name"));
		if (name.isEmpty())
		{
			fail(inReq, 400, "missing_name");
			return;
		}
		Object parsed = JSONValue.parse(inReq.getRequestParameter("rows") == null ? "[]" : inReq.getRequestParameter("rows"));
		if (!(parsed instanceof List))
		{
			fail(inReq, 400, "bad_rows");
			return;
		}
		LearningEngine.Content content = new LearningEngine(archive).loadContent();
		List<LearningEngine.ProfileRow> rows = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (Object o : (List) parsed)
		{
			if (!(o instanceof Map))
			{
				fail(inReq, 400, "bad_rows");
				return;
			}
			Map m = (Map) o;
			LearningEngine.ProfileRow r = new LearningEngine.ProfileRow();
			r.topicid = trim(str(m.get("topic")));
			if (!content.topics.containsKey(r.topicid))
			{
				fail(inReq, 400, "unknown_topic");
				return;
			}
			if (!seen.add(r.topicid))
			{
				fail(inReq, 400, "duplicate_topic");
				return;
			}
			String lvl = trim(str(m.get("requiredlevel")));
			if (!lvl.isEmpty() && !LEVELS.contains(lvl))
			{
				fail(inReq, 400, "bad_level");
				return;
			}
			r.requiredlevel = lvl.isEmpty() ? null : lvl;
			r.mandatory = !"false".equals(String.valueOf(m.get("mandatory")));
			r.requiresprevious = "true".equals(String.valueOf(m.get("requiresprevious")));
			String af = trim(str(m.get("afterfinish")));
			if (af.isEmpty())
			{
				af = "keep";
			}
			if (!AFTERFINISH.contains(af))
			{
				fail(inReq, 400, "bad_afterfinish");
				return;
			}
			r.afterfinish = af;
			r.position = rows.size() + 1;
			if (r.position == 1 && r.requiresprevious)
			{
				fail(inReq, 400, "first_row_gate");
				return;
			}
			rows.add(r);
		}
		Searcher list = archive.getSearcher("jobrole");
		JSONObject before = null;
		synchronized (LOCK)
		{
			Data p = id.isEmpty() ? null : (Data) list.searchById(id);
			if (!id.isEmpty() && p == null)
			{
				fail(inReq, 404, "unknown_profile");
				return;
			}
			for (Data other : profileList(archive))
			{
				if (!other.getId().equals(id) && name.equalsIgnoreCase(other.getName()))
				{
					fail(inReq, 400, "duplicate_name");
					return;
				}
			}
			if (p == null)
			{
				id = uniqueSlug(list, name);
				p = list.createNewData();
				p.setId(id);
			}
			else
			{
				before = profileJson(archive, content, p, memberCounts(archive));
			}
			p.setName(name);
			p.setValue("name", name);
			list.saveData(p, inReq.getUser());
			Searcher req = archive.getSearcher("topicrequirement");
			Set<String> keep = new HashSet<>();
			for (LearningEngine.ProfileRow r : rows)
			{
				String rid = id + "_" + r.topicid;
				keep.add(rid);
				Data d = (Data) req.searchById(rid);
				if (d == null)
				{
					d = req.createNewData();
					d.setId(rid);
				}
				d.setValue("jobrole", id);
				d.setValue("entitytopic", r.topicid);
				d.setValue("requiredlevel", r.requiredlevel);
				d.setValue("position", String.valueOf(r.position));
				d.setValue("mandatory", r.mandatory ? "true" : "false");
				d.setValue("requiresprevious", r.requiresprevious ? "true" : "false");
				d.setValue("afterfinish", r.afterfinish);
				req.saveData(d, inReq.getUser());
			}
			for (Object o : req.query().exact("jobrole", id).search())
			{
				Data d = (Data) o;
				if (!keep.contains(d.getId()))
				{
					req.delete(d, inReq.getUser());
				}
			}
		}
		JSONObject after = profileJson(archive, content, (Data) list.searchById(id), memberCounts(archive));
		audit(inReq, archive, "jobprofile.save", "jobrole", id, before, after);
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("profile", after);
		reply(inReq, resp);
	}

	public void deleteProfile(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		String id = trim(inReq.getRequestParameter("id"));
		Searcher list = archive.getSearcher("jobrole");
		Data p = id.isEmpty() ? null : (Data) list.searchById(id);
		if (p == null)
		{
			fail(inReq, 404, "unknown_profile");
			return;
		}
		Integer members = memberCounts(archive).get(id);
		if (members != null && members > 0)
		{
			JSONObject err = new JSONObject();
			err.put("ok", Boolean.FALSE);
			err.put("error", "profile_in_use");
			err.put("members", members);
			if (inReq.getResponse() != null)
			{
				inReq.getResponse().setStatus(409);
			}
			reply(inReq, err);
			inReq.setCancelActions(true);
			return;
		}
		LearningEngine.Content content = new LearningEngine(archive).loadContent();
		JSONObject before = profileJson(archive, content, p, new HashMap<>());
		synchronized (LOCK)
		{
			Searcher req = archive.getSearcher("topicrequirement");
			for (Object o : req.query().exact("jobrole", id).search())
			{
				req.delete((Data) o, inReq.getUser());
			}
			list.delete(p, inReq.getUser());
		}
		audit(inReq, archive, "jobprofile.delete", "jobrole", id, before, null);
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		reply(inReq, resp);
	}

	public void setProfiles(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		String userid = trim(inReq.getRequestParameter("user"));
		String primary = trim(inReq.getRequestParameter("primary"));
		Object parsed = JSONValue.parse(inReq.getRequestParameter("extras") == null ? "[]" : inReq.getRequestParameter("extras"));
		if (!(parsed instanceof List))
		{
			fail(inReq, 400, "bad_extras");
			return;
		}
		List<String> extras = new ArrayList<>();
		for (Object o : (List) parsed)
		{
			String e = trim(str(o));
			if (!e.isEmpty() && !extras.contains(e))
			{
				extras.add(e);
			}
		}
		Searcher users = archive.getSearcher("user");
		Data u = (Data) users.searchById(userid);
		if (u == null)
		{
			fail(inReq, 404, "no user");
			return;
		}
		if (extras.contains(primary))
		{
			fail(inReq, 400, "primary_in_extras");
			return;
		}
		if (primary.isEmpty() && !extras.isEmpty())
		{
			fail(inReq, 400, "missing_primary");
			return;
		}
		List<String> all = new ArrayList<>();
		if (!primary.isEmpty())
		{
			all.add(primary);
		}
		all.addAll(extras);
		for (String id : all)
		{
			if (archive.getCachedData("jobrole", id) == null)
			{
				fail(inReq, 400, "unknown_profile");
				return;
			}
		}
		JSONObject before = new JSONObject();
		before.put("primaryjobrole", LearningEngine.primaryJobroleOf(u));
		before.put("jobroles", new ArrayList<>(LearningEngine.jobrolesOf(u)));
		u.setValue("primaryjobrole", primary.isEmpty() ? null : primary);
		u.setValue("jobrole", all.isEmpty() ? null : all);
		users.saveData(u, inReq.getUser());
		JSONObject after = new JSONObject();
		after.put("primaryjobrole", primary.isEmpty() ? null : primary);
		after.put("jobroles", all);
		audit(inReq, archive, "jobprofile.assign", "user", userid, before, after);
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("primaryjobrole", primary.isEmpty() ? null : primary);
		resp.put("jobroles", all);
		reply(inReq, resp);
	}

	// ---- helpers

	static String trim(String s)
	{
		return s == null ? "" : s.trim();
	}

	static String str(Object o)
	{
		return o == null ? "" : String.valueOf(o);
	}

	static List<Data> profileList(MediaArchive archive)
	{
		List<Data> out = new ArrayList<>();
		HitTracker hits = archive.query("jobrole").all().sort("nameUp").search();
		if (hits != null)
		{
			for (Object o : hits)
			{
				out.add((Data) o);
			}
		}
		return out;
	}

	/** profile id -> number of users whose jobrole contains it. */
	static Map<String, Integer> memberCounts(MediaArchive archive)
	{
		Map<String, Integer> out = new HashMap<>();
		HitTracker hits = archive.query("user").all().search();
		if (hits != null)
		{
			hits.enableBulkOperations();
			for (Object o : hits)
			{
				for (String id : LearningEngine.jobrolesOf((Data) o))
				{
					out.merge(id, 1, Integer::sum);
				}
			}
		}
		return out;
	}

	static JSONObject profileJson(MediaArchive archive, LearningEngine.Content content, Data p, Map<String, Integer> members)
	{
		List<LearningEngine.ProfileRow> rows = new ArrayList<>();
		for (Object o : archive.query("topicrequirement").exact("jobrole", p.getId()).search())
		{
			rows.add(LearningEngine.rowOf((Data) o));
		}
		rows.sort(java.util.Comparator.comparingInt((LearningEngine.ProfileRow r) -> r.position).thenComparing(r -> r.topicid));
		JSONArray arr = new JSONArray();
		int pos = 0;
		for (LearningEngine.ProfileRow r : rows)
		{
			LearningEngine.Topic t = content.topics.get(r.topicid);
			JSONObject o = new JSONObject();
			o.put("topic", r.topicid);
			o.put("topictitle", t == null ? r.topicid : t.title);
			o.put("position", Integer.valueOf(++pos));
			o.put("requiredlevel", r.requiredlevel);
			o.put("mandatory", Boolean.valueOf(r.mandatory));
			o.put("requiresprevious", Boolean.valueOf(r.requiresprevious));
			o.put("afterfinish", r.afterfinish);
			o.put("questions", Integer.valueOf(t == null ? 0 : t.questions.size()));
			arr.add(o);
		}
		JSONObject o = new JSONObject();
		o.put("id", p.getId());
		o.put("name", p.getName() == null ? p.get("name") : p.getName());
		o.put("members", members.getOrDefault(p.getId(), 0));
		o.put("rows", arr);
		return o;
	}

	/** lowercase [a-z0-9-] of the name; "-2", "-3" ... when taken. */
	static String uniqueSlug(Searcher list, String name)
	{
		String base = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
		if (base.isEmpty())
		{
			base = "profile";
		}
		String id = base;
		for (int n = 2; list.searchById(id) != null; n++)
		{
			id = base + "-" + n;
		}
		return id;
	}
}
```

If `sort("nameUp")` throws on this eMe build, use `.sort("name")` — the intent is the list alphabetical by name.

- [ ] **Step 4: Register the bean and wire the endpoints**

`html/src/plugin.xml`, after the `TestUTeamModule` bean:

```xml
	<bean id="TestUProfileModule" class="tech.genailabs.tutor.TestUProfileModule" scope="prototype">
		<property name="moduleManager">
			<ref bean="moduleManager" />
		</property>
	</bean>
```

`html/services/testu/personas/profiles.xconf`:

```xml
<page>
  <path-action name="TestUProfileModule.profiles"/>
  <permission name="view"><userprofile property="personas_view" equals="true"/></permission>
</page>
```

`saveprofile.xconf`, `deleteprofile.xconf`, `setprofiles.xconf` (one each, `TestUProfileModule.saveProfile` / `.deleteProfile` / `.setProfiles`):

```xml
<page>
  <path-action name="TestUProfileModule.saveProfile"/>
  <permission name="view"><userprofile property="personas_manage" equals="true"/></permission>
</page>
```

Each `.json` file contains exactly:

```
$json
```

Mirror the eight files into `eme-server-minsur/webapp/site/mediadb/services/testu/personas/` if that directory is a real copy (compare with how `saveteam.xconf` exists in both).

- [ ] **Step 5: `users.json` and `me.json` fields**

`TestUUserModule.loadUsers`, after `userObj.put("team", team);`:

```java
				userObj.put("primaryjobrole", LearningEngine.primaryJobroleOf(u));
				userObj.put("jobroles", new ArrayList<>(LearningEngine.jobrolesOf(u)));
```

`TestUUserModule.loadMe`, after `userObj.put("lastName", u.get("lastName"));` (`u` is a `User`, which is a `Data`):

```java
			userObj.put("primaryjobrole", LearningEngine.primaryJobroleOf(u));
			userObj.put("jobroles", new ArrayList<>(LearningEngine.jobrolesOf(u)));
```

Add `import java.util.ArrayList;` if missing.

- [ ] **Step 6: CSV import columns**

In `ImportUsers.groovy`:

```groovy
  static final ALLOWED = ["id", "email", "firstName", "lastName", "team", "primaryjobrole", "jobrole"] as Set
```

and after the `team` check, before `super.addProperties(inRow, inData)`:

```groovy
    // Job profiles: primaryjobrole (one) and jobrole (a|b|c), each an id or a name of the jobrole list; primary is added to jobrole.
    def resolve = { String v ->
      String s = (v ?: "").trim()
      if (!s) return null
      if (getMediaArchive().getCachedData("jobrole", s) != null) return s
      def hit = getMediaArchive().query("jobrole").all().search().find { it.getName()?.equalsIgnoreCase(s) }
      if (hit == null) throw new IllegalArgumentException("unknown job profile: " + s)
      return hit.getId()
    }
    String primary = resolve(inRow.get("primaryjobrole"))
    List roles = []
    for (String part : ((inRow.get("jobrole") ?: "") as String).split(/\s*[|,]\s*/)) { def r = resolve(part); if (r && !(r in roles)) roles << r }
    if (primary && !(primary in roles)) roles.add(0, primary)
```

and after `super.addProperties(inRow, inData)`:

```groovy
    inData.setValue("primaryjobrole", primary)
    inData.setValue("jobrole", roles ? roles : null)
```

- [ ] **Step 7: Restart eMe (announce), run the checks**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur
EME_USER=... EME_PASSWORD=... plugins/testu/tools/check_profiles.sh
```

Expected: `PASS`. If the CSV import checks fail on the multipart shape, compare with how `admin_api.dart importUsers` posts (`file` field) and adjust the boundary/headers, not the semantics.

- [ ] **Step 8: Commit**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/testu
git add code/tech/genailabs/tutor/TestUProfileModule.java code/tech/genailabs/tutor/TestUUserModule.java html/src/plugin.xml html/services/testu/personas/profiles.* html/services/testu/personas/saveprofile.* html/services/testu/personas/deleteprofile.* html/services/testu/personas/setprofiles.* html/services/testu/personas/import/scripts/ImportUsers.groovy tools/check_profiles.sh
git commit -m "personas: job profile endpoints (profiles/saveprofile/deleteprofile/setprofiles), users/me profile fields, CSV columns"
```

---

### Task 5: Admin models + API client

**Files:**
- Modify: `app-genailabs/lib/admin/admin_models.dart` (after `AdminTeam`, and `AdminUser`)
- Modify: `app-genailabs/lib/admin/admin_api.dart` (after `saveTeam`)
- Modify: `app-genailabs/.gitignore` (+`.superpowers/`)
- Test: `app-genailabs/test/admin_api_test.dart`

**Interfaces:**
- Produces:
  - `class ProfileRow { String topic, topicTitle; int position, questions; String? requiredLevel; bool mandatory, requiresPrevious; String afterFinish; ProfileRow copyWith(...); Map<String, Object?> toJson() }`
  - `class AdminProfile { String id, name; int members; List<ProfileRow> rows }`
  - `class AdminTopicRef { String id, title; int questions }`
  - `AdminUser.primaryProfile` (String?), `AdminUser.profiles` (List<String>)
  - `AdminApi.profiles() → Future<({List<AdminProfile> profiles, List<AdminTopicRef> topics})>`
  - `AdminApi.saveProfile({String? id, required String name, required List<ProfileRow> rows}) → Future<AdminProfile>`
  - `AdminApi.deleteProfile(String id) → Future<void>` (throws `Exception('profile_in_use')`)
  - `AdminApi.setProfiles(String userid, {String? primary, List<String> extras}) → Future<void>`

- [ ] **Step 1: Failing tests**

Append to `test/admin_api_test.dart` (it already builds an `AdminApi` over `FakeEmeHttp`; follow its existing `_api(http)`/setup helper name — read the file's first test and reuse the same construction):

```dart
  group('job profiles', () {
    const path = 'services/testu/personas/profiles.json';
    Map<String, dynamic> profilesJson() => {
      'ok': true,
      'profiles': [
        {
          'id': 'pilot',
          'name': 'Pilot',
          'members': 3,
          'rows': [
            {'topic': 'T1', 'topictitle': 'Onboarding', 'position': 1, 'requiredlevel': 'competent', 'mandatory': true, 'requiresprevious': false, 'afterfinish': 'remove', 'questions': 24},
            {'topic': 'T2', 'topictitle': 'Ops', 'position': 2, 'requiredlevel': null, 'mandatory': false, 'requiresprevious': true, 'afterfinish': 'keep', 'questions': 0},
          ],
        },
      ],
      'topics': [
        {'id': 'T1', 'title': 'Onboarding', 'questions': 24},
        {'id': 'T3', 'title': 'Safety', 'questions': 9},
      ],
    };

    test('profiles() parses profiles, rows and topics', () async {
      final http = FakeEmeHttp()..canned[path] = profilesJson();
      final r = await AdminApi(http: http).profiles();
      expect(r.profiles.single.id, 'pilot');
      expect(r.profiles.single.members, 3);
      final rows = r.profiles.single.rows;
      expect(rows.map((x) => x.topic), ['T1', 'T2']);
      expect(rows[0].requiredLevel, 'competent');
      expect(rows[0].afterFinish, 'remove');
      expect(rows[1].requiredLevel, isNull);
      expect(rows[1].mandatory, isFalse);
      expect(rows[1].requiresPrevious, isTrue);
      expect(rows[1].questions, 0);
      expect(r.topics.map((t) => t.id), ['T1', 'T3']);
    });

    test('saveProfile posts name and rows as JSON in order', () async {
      final http = FakeEmeHttp()
        ..canned['services/testu/personas/saveprofile.json'] = {
          'ok': true,
          'profile': profilesJson()['profiles'][0],
        };
      final saved = await AdminApi(http: http).saveProfile(
        id: 'pilot',
        name: 'Pilot',
        rows: [
          ProfileRow(topic: 'T2', topicTitle: 'Ops', requiredLevel: null, mandatory: true, requiresPrevious: false, afterFinish: 'keep'),
          ProfileRow(topic: 'T1', topicTitle: 'Onboarding', requiredLevel: 'expert', mandatory: true, requiresPrevious: true, afterFinish: 'remove'),
        ],
      );
      expect(saved.id, 'pilot');
      final f = http.posted.single.fields;
      expect(f['id'], 'pilot');
      expect(f['name'], 'Pilot');
      final rows = jsonDecode(f['rows']!) as List;
      expect(rows.map((r) => r['topic']), ['T2', 'T1']);
      expect(rows[1]['requiredlevel'], 'expert');
      expect(rows[1]['requiresprevious'], true);
      expect(rows[0]['afterfinish'], 'keep');
      expect(rows[0].containsKey('position'), isFalse);
    });

    test('deleteProfile surfaces profile_in_use', () async {
      final http = FakeEmeHttp()
        ..canned['services/testu/personas/deleteprofile.json'] = {'ok': false, 'error': 'profile_in_use', 'members': 2};
      expect(() => AdminApi(http: http).deleteProfile('pilot'), throwsA(predicate((e) => errText(e as Object) == 'profile_in_use')));
    });

    test('setProfiles posts user, primary and extras JSON', () async {
      final http = FakeEmeHttp()..canned['services/testu/personas/setprofiles.json'] = {'ok': true};
      await AdminApi(http: http).setProfiles('u1', primary: 'pilot', extras: ['safety']);
      final f = http.posted.single.fields;
      expect(f['user'], 'u1');
      expect(f['primary'], 'pilot');
      expect(jsonDecode(f['extras']!), ['safety']);
    });

    test('AdminUser parses primaryjobrole and jobroles', () {
      final u = AdminUser.fromJson({'id': 'u1', 'email': 'a@b', 'firstName': 'A', 'lastName': 'B', 'role': 'users', 'enabled': true, 'primaryjobrole': 'pilot', 'jobroles': ['pilot', 'safety']});
      expect(u.primaryProfile, 'pilot');
      expect(u.profiles, ['pilot', 'safety']);
      final none = AdminUser.fromJson({'id': 'u2', 'email': 'c@d', 'role': 'users'});
      expect(none.primaryProfile, isNull);
      expect(none.profiles, isEmpty);
    });
  });
```

Add `import 'dart:convert';` at the top if absent.

- [ ] **Step 2: Run, expect compile failure**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs && flutter test test/admin_api_test.dart
```

Expected: errors about `profiles`, `ProfileRow`, `primaryProfile`.

- [ ] **Step 3: Models**

In `admin_models.dart`, `AdminUser`: add constructor params `this.primaryProfile, this.profiles = const [],`, fields

```dart
  /// Job profiles (personas/users.json `primaryjobrole`, `jobroles`).
  final String? primaryProfile;
  final List<String> profiles;
```

and in `fromJson`:

```dart
      primaryProfile: (j['primaryjobrole']?.toString() ?? '').isEmpty ? null : j['primaryjobrole'].toString(),
      profiles: [for (final p in j['jobroles'] as List? ?? const []) '$p'],
```

After `AdminTeam` add:

```dart
/// One topic of a job profile (topicrequirement row), in profile order.
class ProfileRow {
  ProfileRow({
    required this.topic,
    required this.topicTitle,
    this.position = 0,
    this.questions = 0,
    this.requiredLevel,
    this.mandatory = true,
    this.requiresPrevious = false,
    this.afterFinish = 'keep',
  });
  final String topic, topicTitle;
  final int position, questions;

  /// beginner | competent | expert | null (no level required).
  final String? requiredLevel;
  final bool mandatory, requiresPrevious;

  /// keep | remove.
  final String afterFinish;

  factory ProfileRow.fromJson(Map j) => ProfileRow(
        topic: '${j['topic']}',
        topicTitle: '${j['topictitle'] ?? j['topic']}',
        position: (j['position'] as num?)?.toInt() ?? 0,
        questions: (j['questions'] as num?)?.toInt() ?? 0,
        requiredLevel: (j['requiredlevel']?.toString() ?? '').isEmpty ? null : j['requiredlevel'].toString(),
        mandatory: j['mandatory'] != false,
        requiresPrevious: j['requiresprevious'] == true,
        afterFinish: j['afterfinish'] == 'remove' ? 'remove' : 'keep',
      );

  ProfileRow copyWith({
    Object? requiredLevel = _unset,
    bool? mandatory,
    bool? requiresPrevious,
    String? afterFinish,
  }) =>
      ProfileRow(
        topic: topic,
        topicTitle: topicTitle,
        position: position,
        questions: questions,
        requiredLevel: identical(requiredLevel, _unset) ? this.requiredLevel : requiredLevel as String?,
        mandatory: mandatory ?? this.mandatory,
        requiresPrevious: requiresPrevious ?? this.requiresPrevious,
        afterFinish: afterFinish ?? this.afterFinish,
      );

  /// What saveprofile.json takes; position is the array order.
  Map<String, Object?> toJson() => {
        'topic': topic,
        'requiredlevel': requiredLevel,
        'mandatory': mandatory,
        'requiresprevious': requiresPrevious,
        'afterfinish': afterFinish,
      };
}

const _unset = Object();

/// A job profile: an ordered topic list people are assigned to.
class AdminProfile {
  AdminProfile({required this.id, required this.name, this.members = 0, this.rows = const []});
  final String id, name;
  final int members;
  final List<ProfileRow> rows;

  factory AdminProfile.fromJson(Map j) => AdminProfile(
        id: '${j['id']}',
        name: '${j['name'] ?? j['id']}',
        members: (j['members'] as num?)?.toInt() ?? 0,
        rows: [for (final r in j['rows'] as List? ?? const []) ProfileRow.fromJson(r)],
      );
}

/// A topic the profile editor can add (profiles.json `topics`).
class AdminTopicRef {
  AdminTopicRef({required this.id, required this.title, this.questions = 0});
  final String id, title;
  final int questions;
  factory AdminTopicRef.fromJson(Map j) => AdminTopicRef(
        id: '${j['id']}',
        title: '${j['title'] ?? j['id']}',
        questions: (j['questions'] as num?)?.toInt() ?? 0,
      );
}
```

- [ ] **Step 4: API**

In `admin_api.dart` after `saveTeam`:

```dart
  static const _profilesPath = 'services/testu/personas/profiles.json';

  Future<({List<AdminProfile> profiles, List<AdminTopicRef> topics})> profiles() async {
    final j = await _http.getJson(_profilesPath);
    return (
      profiles: [for (final p in j['profiles'] as List? ?? const []) AdminProfile.fromJson(p)],
      topics: [for (final t in j['topics'] as List? ?? const []) AdminTopicRef.fromJson(t)],
    );
  }

  /// Creates ([id] null) or replaces a profile's name and full row set in
  /// the given order. Refusals (400 missing_name/duplicate_name/...,
  /// 404 unknown_profile) throw `Exception(code)` for [errText].
  Future<AdminProfile> saveProfile({String? id, required String name, required List<ProfileRow> rows}) async {
    try {
      final j = await _http.postForm('services/testu/personas/saveprofile.json', [
        MapEntry('id', id ?? ''),
        MapEntry('name', name),
        MapEntry('rows', jsonEncode([for (final r in rows) r.toJson()])),
      ]);
      if (j['ok'] != true) throw Exception('${j['error'] ?? 'error'}');
      return AdminProfile.fromJson(j['profile'] as Map? ?? const {});
    } on EmeHttpException catch (e) {
      final b = e.body;
      if (b is Map && b['error'] != null) throw Exception('${b['error']}');
      rethrow;
    }
  }

  /// 409 profile_in_use throws `Exception('profile_in_use')`.
  Future<void> deleteProfile(String id) async {
    try {
      await _post('services/testu/personas/deleteprofile.json', {'id': id});
    } on EmeHttpException catch (e) {
      final b = e.body;
      if (b is Map && b['error'] != null) throw Exception('${b['error']}');
      rethrow;
    }
  }

  Future<void> setProfiles(String userid, {String? primary, List<String> extras = const []}) =>
      _post('services/testu/personas/setprofiles.json', {
        'user': userid,
        'primary': primary ?? '',
        'extras': jsonEncode(extras),
      });
```

`dart:convert` is already imported there.

- [ ] **Step 5: `.gitignore`**

Append `.superpowers/` to `app-genailabs/.gitignore`.

- [ ] **Step 6: Run tests**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs && flutter test test/admin_api_test.dart
```

Expected: all pass.

- [ ] **Step 7: Commit (app repo)**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs
git add lib/admin/admin_models.dart lib/admin/admin_api.dart test/admin_api_test.dart .gitignore
git commit -m "admin: job profile models and API (profiles/saveProfile/deleteProfile/setProfiles)"
```

---

### Task 6: Admin Personas — profile editor, rail, roster column, profiles sheet

**Files:**
- Create: `app-genailabs/lib/admin/admin_profiles.dart`
- Modify: `app-genailabs/lib/admin/admin_people.dart` (state ~L33-50, `_load` ~L70-90, `_filtered` ~L120-140, build ~L200-245, `_rail` ~L263-300, `_table` ~L549-620, `_openAdd` ~L833)
- Test: `app-genailabs/test/admin_profiles_test.dart` (new), `app-genailabs/test/admin_people_test.dart` (+2 cases)

**Interfaces:**
- Consumes: Task 5 models/API; `admin_ui.dart` `Select`, `ConsoleAct`, `consoleField`, `showConsoleForm`, `showToast`, `AdminTokens`, `TestuTokens`, `L()`.
- Produces: `class ProfileEditor extends StatefulWidget { ProfileEditor({required this.profile /* AdminProfile? */, required this.topics /* List<AdminTopicRef> */, required this.canManage, required this.onSave /* Future<void> Function(String name, List<ProfileRow> rows) */, this.onDelete /* Future<void> Function()? */, required this.onCancel}) }` with keys `Key('profile-name')`, `Key('profile-save')`, `Key('profile-add-topic')`, `Key('profile-row-<topicId>')`, `Key('profile-remove-<topicId>')`, `Key('profile-up-<topicId>')`, `Key('profile-down-<topicId>')`.

- [ ] **Step 1: Failing editor test**

Create `test/admin_profiles_test.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:genai_labs/admin/admin_models.dart';
import 'package:genai_labs/admin/admin_profiles.dart';
import 'package:genai_labs/admin/admin_ui.dart';
import 'package:genai_labs/testu/testu_theme.dart';

final _topics = [
  AdminTopicRef(id: 'T1', title: 'Onboarding', questions: 24),
  AdminTopicRef(id: 'T2', title: 'Operations', questions: 61),
  AdminTopicRef(id: 'T3', title: 'Safety', questions: 0),
];

AdminProfile _pilot() => AdminProfile(id: 'pilot', name: 'Pilot', members: 2, rows: [
      ProfileRow(topic: 'T1', topicTitle: 'Onboarding', position: 1, questions: 24, requiredLevel: 'competent', afterFinish: 'remove'),
      ProfileRow(topic: 'T2', topicTitle: 'Operations', position: 2, questions: 61, requiresPrevious: true),
    ]);

Future<void> _pump(WidgetTester tester, Widget child) => tester.pumpWidget(TestuTheme(
      child: MaterialApp(home: Scaffold(body: SizedBox(width: 1100, child: SingleChildScrollView(child: child)))),
    ));

void main() {
  testWidgets('renders rows in order with their settings', (tester) async {
    await _pump(tester, ProfileEditor(profile: _pilot(), topics: _topics, canManage: true, onSave: (_, __) async {}, onCancel: () {}));
    expect(find.text('Onboarding'), findsOneWidget);
    expect(find.text('Operations'), findsOneWidget);
    final y1 = tester.getTopLeft(find.byKey(const Key('profile-row-T1'))).dy;
    final y2 = tester.getTopLeft(find.byKey(const Key('profile-row-T2'))).dy;
    expect(y1, lessThan(y2));
  });

  testWidgets('move down, remove, add and save post the new order', (tester) async {
    String? savedName;
    List<ProfileRow>? savedRows;
    await _pump(tester, ProfileEditor(
      profile: _pilot(),
      topics: _topics,
      canManage: true,
      onSave: (name, rows) async {
        savedName = name;
        savedRows = rows;
      },
      onCancel: () {},
    ));
    await tester.tap(find.byKey(const Key('profile-down-T1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('profile-add-topic')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Safety').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('profile-remove-T2')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('profile-name')), 'Pilot v2');
    await tester.tap(find.byKey(const Key('profile-save')));
    await tester.pumpAndSettle();
    expect(savedName, 'Pilot v2');
    expect(savedRows!.map((r) => r.topic), ['T1', 'T3']);
    expect(savedRows![0].afterFinish, 'remove');
  });

  testWidgets('read-only without manage: no save, no add', (tester) async {
    await _pump(tester, ProfileEditor(profile: _pilot(), topics: _topics, canManage: false, onSave: (_, __) async {}, onCancel: () {}));
    expect(find.byKey(const Key('profile-save')), findsNothing);
    expect(find.byKey(const Key('profile-add-topic')), findsNothing);
    expect(find.text('Onboarding'), findsOneWidget);
  });
}
```

(If other admin widget tests wrap with something other than `TestuTheme`+`MaterialApp`, copy their `_pump` instead — see `test/admin_team_test.dart`.)

- [ ] **Step 2: Run, expect failure**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs && flutter test test/admin_profiles_test.dart
```

Expected: `admin_profiles.dart` not found.

- [ ] **Step 3: Create `lib/admin/admin_profiles.dart`**

```dart
import 'package:flutter/material.dart';

import '../testu/testu_i18n.dart';
import '../testu/testu_theme.dart';
import 'admin_models.dart';
import 'admin_ui.dart';

/// Editor of one job profile (spec 2026-09-16): name, the ordered topic
/// table (required level, mandatory, requires previous, after finish),
/// add/remove/move topic, Save. Order is expressed with up/down acts (keyboard
/// and screen-reader friendly, no drag). Rows are sent in table order; the
/// server renumbers positions. Read-only without [canManage].
class ProfileEditor extends StatefulWidget {
  const ProfileEditor({
    super.key,
    required this.profile,
    required this.topics,
    required this.canManage,
    required this.onSave,
    this.onDelete,
    required this.onCancel,
  });

  /// Null = a new profile.
  final AdminProfile? profile;
  final List<AdminTopicRef> topics;
  final bool canManage;
  final Future<void> Function(String name, List<ProfileRow> rows) onSave;

  /// Null hides Delete (new profile, or no permission).
  final Future<void> Function()? onDelete;
  final VoidCallback onCancel;

  @override
  State<ProfileEditor> createState() => _ProfileEditorState();
}

class _ProfileEditorState extends State<ProfileEditor> {
  late final _name = TextEditingController(text: widget.profile?.name ?? '');
  late List<ProfileRow> _rows = [...?widget.profile?.rows];
  bool _busy = false;

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  List<AdminTopicRef> get _addable =>
      [for (final t in widget.topics) if (!_rows.any((r) => r.topic == t.id)) t];

  void _set(int i, ProfileRow r) => setState(() => _rows[i] = r);

  void _move(int i, int delta) {
    final j = i + delta;
    if (j < 0 || j >= _rows.length) return;
    setState(() {
      final r = _rows.removeAt(i);
      _rows.insert(j, r);
      // A row moved to the top cannot gate on a previous one.
      _rows[0] = _rows[0].copyWith(requiresPrevious: false);
    });
  }

  Future<void> _save() async {
    final name = _name.text.trim();
    if (name.isEmpty) {
      showToast(context, L('Name the profile first.', 'Ponle nombre al perfil primero.'), error: true);
      return;
    }
    setState(() => _busy = true);
    try {
      await widget.onSave(name, _rows);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = TestuTokens.of(context);
    final canEdit = widget.canManage && !_busy;
    final members = widget.profile?.members ?? 0;
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
      margin: const EdgeInsets.only(bottom: 16),
      decoration: BoxDecoration(
        color: t.card2,
        border: Border.all(color: t.line),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Expanded(
              child: widget.canManage
                  ? TextField(
                      key: const Key('profile-name'),
                      controller: _name,
                      style: AdminTokens.table,
                      decoration: consoleField(t, hint: L('Profile name', 'Nombre del perfil')),
                    )
                  : Text(widget.profile?.name ?? '', style: AdminTokens.body.copyWith(fontWeight: FontWeight.w600)),
            ),
            const SizedBox(width: 12),
            Text(
              members == 1 ? L('1 person', '1 persona') : L('$members people', '$members personas'),
              style: AdminTokens.mono(11.5, color: t.mut),
            ),
          ]),
          const SizedBox(height: 12),
          _header(t),
          for (var i = 0; i < _rows.length; i++) _row(i, t, canEdit),
          if (_rows.isEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 10),
              child: Text(L('No topics yet. Add the first one.', 'Sin temas todavía. Añade el primero.'), style: AdminTokens.muted),
            ),
          const SizedBox(height: 10),
          Row(children: [
            if (canEdit && _addable.isNotEmpty)
              SizedBox(
                width: 260,
                child: Select<String>(
                  key: const Key('profile-add-topic'),
                  value: null,
                  hint: L('Add topic', 'Añadir tema'),
                  fill: true,
                  items: [for (final x in _addable) (x.id, x.questions == 0 ? '${x.title} · ${L('no questions', 'sin preguntas')}' : x.title)],
                  onChanged: (id) {
                    final x = _addable.where((a) => a.id == id).firstOrNull;
                    if (x == null) return;
                    setState(() => _rows.add(ProfileRow(topic: x.id, topicTitle: x.title, questions: x.questions)));
                  },
                ),
              ),
            const Spacer(),
            if (widget.canManage) ...[
              if (widget.onDelete != null)
                Tooltip(
                  message: members > 0
                      ? L('Unassign everyone before deleting.', 'Quita el perfil a todos antes de borrarlo.')
                      : '',
                  child: ConsoleAct(L('Delete', 'Eliminar'), onTap: members > 0 || _busy ? null : widget.onDelete),
                ),
              const SizedBox(width: 8),
              ConsoleAct(L('Cancel', 'Cancelar'), onTap: _busy ? null : widget.onCancel),
              const SizedBox(width: 8),
              ConsoleAct(L('Save', 'Guardar'), key: const Key('profile-save'), primary: true, onTap: _busy ? null : _save),
            ],
          ]),
        ],
      ),
    );
  }

  Widget _header(TestuTokens t) => Padding(
        padding: const EdgeInsets.only(bottom: 4),
        child: Row(children: [
          const SizedBox(width: 30),
          Expanded(flex: 3, child: Text(L('Topic', 'Tema'), style: AdminTokens.th)),
          SizedBox(width: 130, child: Text(L('Required level', 'Nivel requerido'), style: AdminTokens.th)),
          SizedBox(width: 120, child: Text(L('Mandatory', 'Obligatorio'), style: AdminTokens.th)),
          SizedBox(width: 130, child: Text(L('Requires previous', 'Requiere anterior'), style: AdminTokens.th)),
          SizedBox(width: 120, child: Text(L('After finish', 'Al terminar'), style: AdminTokens.th)),
          const SizedBox(width: 96),
        ]),
      );

  Widget _row(int i, TestuTokens t, bool canEdit) {
    final r = _rows[i];
    Widget cell(double w, Widget child) => SizedBox(width: w, child: Padding(padding: const EdgeInsets.only(right: 8), child: child));
    Widget ro(String s) => Text(s, style: TextStyle(color: t.mut), maxLines: 1, overflow: TextOverflow.ellipsis);
    return Container(
      key: Key('profile-row-${r.topic}'),
      padding: const EdgeInsets.symmetric(vertical: 4),
      decoration: BoxDecoration(border: Border(top: BorderSide(color: t.line))),
      child: Row(children: [
        SizedBox(width: 30, child: Text('${i + 1}', style: AdminTokens.mono(11.5, color: t.mut))),
        Expanded(
          flex: 3,
          child: Row(children: [
            Flexible(child: Text(r.topicTitle, maxLines: 1, overflow: TextOverflow.ellipsis)),
            if (r.questions == 0) ...[
              const SizedBox(width: 6),
              Tooltip(
                message: L('This topic has no questions yet.', 'Este tema aún no tiene preguntas.'),
                child: Text('!', style: AdminTokens.mono(11.5, color: AdminTokens.focus)),
              ),
            ],
          ]),
        ),
        cell(130, canEdit
            ? Select<String>(
                value: r.requiredLevel,
                hint: L('None', 'Ninguno'),
                fill: true,
                items: [
                  (null, L('None', 'Ninguno')),
                  ('beginner', L('Beginner', 'Principiante')),
                  ('competent', L('Competent', 'Competente')),
                  ('expert', L('Expert', 'Experto')),
                ],
                onChanged: (v) => _set(i, r.copyWith(requiredLevel: v)),
              )
            : ro(switch (r.requiredLevel) {
                'beginner' => L('Beginner', 'Principiante'),
                'competent' => L('Competent', 'Competente'),
                'expert' => L('Expert', 'Experto'),
                _ => L('None', 'Ninguno'),
              })),
        cell(120, canEdit
            ? Select<bool>(
                value: r.mandatory,
                fill: true,
                items: [(true, L('Mandatory', 'Obligatorio')), (false, L('Optional', 'Opcional'))],
                onChanged: (v) => _set(i, r.copyWith(mandatory: v ?? true)),
              )
            : ro(r.mandatory ? L('Mandatory', 'Obligatorio') : L('Optional', 'Opcional'))),
        cell(130, canEdit && i > 0
            ? Select<bool>(
                value: r.requiresPrevious,
                fill: true,
                items: [(false, L('No', 'No')), (true, L('Yes', 'Sí'))],
                onChanged: (v) => _set(i, r.copyWith(requiresPrevious: v ?? false)),
              )
            : ro(i == 0 ? '—' : (r.requiresPrevious ? L('Yes', 'Sí') : L('No', 'No')))),
        cell(120, canEdit
            ? Select<String>(
                value: r.afterFinish,
                fill: true,
                items: [('keep', L('Keep', 'Mantener')), ('remove', L('Remove', 'Quitar'))],
                describe: (v) => v == 'remove'
                    ? L('Disappears once Learn is complete and the level reached', 'Desaparece al completar Aprender y alcanzar el nivel')
                    : L('Stays for reinforcement', 'Se mantiene para reforzar'),
                onChanged: (v) => _set(i, r.copyWith(afterFinish: v ?? 'keep')),
              )
            : ro(r.afterFinish == 'remove' ? L('Remove', 'Quitar') : L('Keep', 'Mantener'))),
        SizedBox(
          width: 96,
          child: canEdit
              ? Row(mainAxisAlignment: MainAxisAlignment.end, children: [
                  IconButton(
                    key: Key('profile-up-${r.topic}'),
                    tooltip: L('Move up', 'Subir'),
                    iconSize: 16,
                    visualDensity: VisualDensity.compact,
                    onPressed: i == 0 ? null : () => _move(i, -1),
                    icon: const Icon(Icons.arrow_upward),
                  ),
                  IconButton(
                    key: Key('profile-down-${r.topic}'),
                    tooltip: L('Move down', 'Bajar'),
                    iconSize: 16,
                    visualDensity: VisualDensity.compact,
                    onPressed: i == _rows.length - 1 ? null : () => _move(i, 1),
                    icon: const Icon(Icons.arrow_downward),
                  ),
                  IconButton(
                    key: Key('profile-remove-${r.topic}'),
                    tooltip: L('Remove', 'Quitar'),
                    iconSize: 16,
                    visualDensity: VisualDensity.compact,
                    onPressed: () => setState(() {
                      _rows.removeAt(i);
                      if (_rows.isNotEmpty) _rows[0] = _rows[0].copyWith(requiresPrevious: false);
                    }),
                    icon: const Icon(Icons.close),
                  ),
                ])
              : const SizedBox.shrink(),
        ),
      ]),
    );
  }
}
```

Check `AdminTokens.th`, `AdminTokens.body`, `AdminTokens.muted`, `AdminTokens.table`, `AdminTokens.mono`, `AdminTokens.focus` exist in `admin_theme.dart`; substitute the header-cell style the roster's `AdminTable` uses if `th` does not exist. `ConsoleAct` must accept `key:` — if its constructor lacks `super.key`, add it (one-line change in `admin_ui.dart`). If `Select` does not forward `key`, wrap it in `KeyedSubtree`.

- [ ] **Step 4: Run the editor test**

```bash
flutter test test/admin_profiles_test.dart
```

Expected: 3 pass. If the `Select` menu opens as a popup that `find.text('Safety').last` cannot hit, look at how `admin_people_test.dart` picks a `Select` item (it changes a team through one) and mirror that.

- [ ] **Step 5: Failing Personas screen tests**

Append to `test/admin_people_test.dart` (reuse its `_pump` helper: it takes `users`, `teams` JSON and an `AdminMe`; add canned `profiles.json` through the returned `FakeEmeHttp` — check the helper's signature; if it cannot pre-can extra paths, add an optional `Map<String, Map<String, dynamic>> extra` parameter that is merged into `http.canned` before pumping):

```dart
  const _profilesPath = 'services/testu/personas/profiles.json';
  Map<String, dynamic> _profilesJson() => {
        'ok': true,
        'profiles': [
          {'id': 'pilot', 'name': 'Pilot', 'members': 1, 'rows': [
            {'topic': 'T1', 'topictitle': 'Onboarding', 'position': 1, 'mandatory': true, 'requiresprevious': false, 'afterfinish': 'keep', 'questions': 3},
          ]},
        ],
        'topics': [{'id': 'T1', 'title': 'Onboarding', 'questions': 3}],
      };

  testWidgets('rail lists job profiles; picking one filters the roster and opens the editor', (tester) async {
    final users = _usersJson();
    (users['users'] as List).add({'id': 'u2', 'email': 'bo@minsur.test', 'firstName': 'Bo', 'lastName': 'Li', 'role': 'users', 'enabled': true, 'primaryjobrole': 'pilot', 'jobroles': ['pilot']});
    await _pump(tester, me: _fullAccess, users: users, extra: {_profilesPath: _profilesJson()});
    expect(find.text('Pilot'), findsWidgets);
    await tester.tap(find.text('Pilot').first);
    await tester.pumpAndSettle();
    expect(find.text('Bo Li'), findsOneWidget);
    expect(find.text('Ana Quispe'), findsNothing);
    expect(find.byKey(const Key('profile-name')), findsOneWidget);
    expect(find.byKey(const Key('profile-row-T1')), findsOneWidget);
  });

  testWidgets('roster shows the Profile column with +n extras', (tester) async {
    final users = _usersJson();
    (users['users'] as List)[0]['primaryjobrole'] = 'pilot';
    (users['users'] as List)[0]['jobroles'] = ['pilot', 'safety'];
    await _pump(tester, me: _noManage, users: users, extra: {_profilesPath: _profilesJson()});
    expect(find.text('Pilot +1'), findsOneWidget);
  });
```

- [ ] **Step 6: Run, expect failure**

```bash
flutter test test/admin_people_test.dart
```

Expected: the two new tests fail (no rail entry / no column).

- [ ] **Step 7: Wire the Personas screen**

In `admin_people.dart`:

1. Imports: add `import 'admin_profiles.dart';`.
2. State: after `List<AdminTeam>? _teams;` add

```dart
  List<AdminProfile> _profiles = const [];
  List<AdminTopicRef> _profileTopics = const [];
  /// Rail pick of a profile: `p:<id>`; `p:` alone = a new profile being made.
  static const _kProfile = 'p:';
  bool get _profilePicked => _sel != null && _sel!.startsWith(_kProfile);
  String? get _profileId => _profilePicked ? _sel!.substring(_kProfile.length) : null;
  AdminProfile? get _profile => _profiles.where((p) => p.id == _profileId).firstOrNull;
  String _profileName(String? id) => _profiles.where((p) => p.id == id).firstOrNull?.name ?? '';
  String _profileLabel(AdminUser u) {
    final p = _profileName(u.primaryProfile);
    if (p.isEmpty) return '';
    final extras = u.profiles.where((x) => x != u.primaryProfile).length;
    return extras == 0 ? p : '$p +$extras';
  }
```

3. `_load`: add a fourth future `widget.api.profiles().catchError((_) => (profiles: <AdminProfile>[], topics: <AdminTopicRef>[]))` to the `Future.wait` list and, in `setState`, `final pr = r[3] as ({List<AdminProfile> profiles, List<AdminTopicRef> topics}); _profiles = pr.profiles; _profileTopics = pr.topics;`. Keep the existing vanished-team fallback and add: `if (_profilePicked && _profileId!.isNotEmpty && _profile == null) _sel = null;`.
4. `_team` getter: return null when `_profilePicked`.
5. `_filtered`: where the team pick is applied (`switch (_sel)`), add a case first: `final s when s.startsWith(_kProfile) => s == _kProfile || u.primaryProfile == _profileId || u.profiles.contains(_profileId),` (a new, unsaved profile shows everyone). Extend the search match with `|| _profileLabel(u).toLowerCase().contains(q)`.
6. Build: the `AnimatedSwitcher` child becomes

```dart
                      child: _profilePicked
                          ? KeyedSubtree(key: ValueKey('p:${_profileId}'), child: _profileHead())
                          : team == null
                              ? const SizedBox(width: double.infinity)
                              : KeyedSubtree(key: ValueKey(team.id), child: _teamHead(team, t)),
```

7. Add:

```dart
  Widget _profileHead() => ProfileEditor(
        profile: _profile,
        topics: _profileTopics,
        canManage: _canManage,
        onCancel: () => _pick(null),
        onDelete: _profile == null || !_canManage
            ? null
            : () => _mutate(() async {
                  await widget.api.deleteProfile(_profile!.id);
                  _sel = null;
                }),
        onSave: (name, rows) => _mutate(() async {
          final saved = await widget.api.saveProfile(id: _profile?.id, name: name, rows: rows);
          _sel = '$_kProfile${saved.id}';
        }),
      );
```

8. `_rail`: after the teams block and before the `if (_canOperate)` "New team" block, add

```dart
        _railRule(t),
        Padding(
          padding: const EdgeInsets.fromLTRB(9, 2, 9, 4),
          child: Text(L('Job profiles', 'Perfiles'), style: AdminTokens.th),
        ),
        if (_profiles.isEmpty)
          Padding(
            padding: const EdgeInsets.fromLTRB(9, 6, 9, 8),
            child: Text(L('No profiles yet.', 'Todavía no hay perfiles.'), style: AdminTokens.muted),
          ),
        for (final p in _profiles)
          _railItem(t, label: p.name, count: p.members, selected: _sel == '$_kProfile${p.id}', onTap: () => _pick('$_kProfile${p.id}')),
        if (_canManage)
          _railItem(t, label: L('New profile', 'Nuevo perfil'), leading: '+', onTap: () => _pick(_kProfile)),
```

9. `_table` columns: after the Role column add

```dart
          AdminColumn(
            L('Profile', 'Perfil'),
            (u) => _profileCell(u, t),
            sortKey: (u) => _profileLabel(u),
            width: 150,
          ),
```

and

```dart
  Widget _profileCell(AdminUser u, TestuTokens t) {
    final label = _profileLabel(u);
    final text = Text(label.isEmpty ? '—' : label, style: TextStyle(color: t.mut), maxLines: 1, overflow: TextOverflow.ellipsis);
    if (!_canManage) return text;
    return ConsoleInteractive(
      onTap: () => _openProfiles(u),
      radius: 6,
      semanticLabel: L('Change job profiles', 'Cambiar perfiles'),
      builder: (context, hovered) => Padding(padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2), child: text),
    );
  }

  /// Primary + extra profiles of one person, saved through setprofiles.json.
  Future<void> _openProfiles(AdminUser u) async {
    String? primary = u.primaryProfile;
    final extras = {...u.profiles}..remove(u.primaryProfile);
    await showConsoleForm(
      context,
      title: L('Job profiles', 'Perfiles de puesto'),
      fields: (dctx, setD) => [
        Text(u.name, style: AdminTokens.body.copyWith(fontWeight: FontWeight.w600)),
        const SizedBox(height: 8),
        Select<String>(
          value: _profiles.any((p) => p.id == primary) ? primary : null,
          hint: L('Primary profile', 'Perfil principal'),
          fill: true,
          items: [(null, L('None', 'Ninguno')), for (final p in _profiles) (p.id, p.name)],
          onChanged: (v) => setD(() {
            primary = v;
            extras.remove(v);
          }),
        ),
        const SizedBox(height: 8),
        Text(L('Extra profiles', 'Perfiles adicionales'), style: AdminTokens.th),
        for (final p in _profiles)
          if (p.id != primary)
            CheckboxListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
              title: Text(p.name, style: AdminTokens.table),
              value: extras.contains(p.id),
              onChanged: primary == null ? null : (v) => setD(() => v == true ? extras.add(p.id) : extras.remove(p.id)),
            ),
        if (primary == null)
          Text(L('Pick a primary profile to add extras.', 'Elige un perfil principal para añadir adicionales.'), style: AdminTokens.muted),
      ],
      primary: (dctx, _) => ConsoleAct(
        L('Save', 'Guardar'),
        primary: true,
        onTap: () {
          Navigator.pop(dctx);
          _mutate(() => widget.api.setProfiles(u.id, primary: primary, extras: primary == null ? const [] : extras.toList()));
        },
      ),
    );
  }
```

10. `_openAdd` (new person sheet): add a `String? primary;` and, after the team `Select`, a `Select<String>` over `_profiles` with hint `L('Job profile', 'Perfil de puesto')`; after `createUser` succeeds and `primary != null`, call `widget.api.setProfiles(email, primary: primary)` inside the same `_mutate` closure.

- [ ] **Step 8: Run all admin tests**

```bash
flutter test test/admin_people_test.dart test/admin_profiles_test.dart test/admin_ui_test.dart test/console_shots_test.dart
```

Expected: pass. If `console_shots_test.dart` is a golden test that now differs because of the new rail section, regenerate its goldens with `flutter test --update-goldens test/console_shots_test.dart` and eyeball the diff (rail gains "Perfiles" + "Todavía no hay perfiles.").

- [ ] **Step 9: Commit**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs
git add lib/admin/admin_profiles.dart lib/admin/admin_people.dart test/admin_profiles_test.dart test/admin_people_test.dart test/console_shots
git commit -m "admin Personas: job profiles rail, editor, Profile column and per-person profiles sheet"
```

---

### Task 7: Learner app — Topics order, locks, optional group, topic-home line

**Files:**
- Modify: `app-genailabs/lib/testu/testu_learn.dart` (`TopicState` ~L113-133)
- Modify: `app-genailabs/lib/testu/testu_topics.dart` (`_Topic` typedef ~L24, `_mapTopic` ~L290, `_topicsBody` ~L313-390, `_TopicRow` ~L396-450, required fact ~L684-698)
- Test: `app-genailabs/test/testu_topics_test.dart` (new)

**Interfaces:**
- Consumes: `state.json` keys from Task 3.
- Produces: `TopicState.position/profile/profileName/mandatory/requiresPrevious/previousTopic/locked/lockReason/afterFinish/finished`; public `typedef TopicsRow`, `List<TopicsRow> topicsRows(List<TopicProgress>)`, `class TestuTopicsBody extends StatelessWidget`.

- [ ] **Step 1: Failing tests**

Create `test/testu_topics_test.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:genai_labs/testu/testu_learn.dart';
import 'package:genai_labs/testu/testu_live.dart';
import 'package:genai_labs/testu/testu_theme.dart';
import 'package:genai_labs/testu/testu_topics.dart';

TopicProgress _p(Map<String, dynamic> j) => TopicProgress(TopicState.fromJson({'questions': 10, 'answered': 0, 'sections': [], ...j}), null);

final _live = [
  _p({'id': 'T1', 'title': 'Onboarding', 'position': 1, 'profile': 'pilot', 'profilename': 'Pilot', 'mandatory': true, 'locked': false, 'afterfinish': 'remove'}),
  _p({'id': 'T2', 'title': 'Operations', 'position': 2, 'profile': 'pilot', 'profilename': 'Pilot', 'mandatory': true, 'locked': true, 'lockreason': 'previous_topic_incomplete', 'previoustopic': 'T1'}),
  _p({'id': 'T4', 'title': 'Culture', 'position': 3, 'profile': 'pilot', 'profilename': 'Pilot', 'mandatory': false, 'locked': false}),
  _p({'id': 'T9', 'title': 'Extra', 'position': null, 'locked': false}),
];

void main() {
  test('TopicState parses profile fields', () {
    final s = _live[1].state;
    expect(s.position, 2);
    expect(s.profileName, 'Pilot');
    expect(s.locked, isTrue);
    expect(s.lockReason, 'previous_topic_incomplete');
    expect(s.previousTopic, 'T1');
    expect(s.mandatory, isTrue);
    expect(_live[3].state.position, isNull);
    expect(_live[0].state.afterFinish, 'remove');
  });

  test('topicsRows keeps server order, groups optional and unassigned, labels locks', () {
    final rows = topicsRows(_live);
    expect(rows.map((r) => r.id), ['T1', 'T2', 'T4', 'T9']);
    expect(rows[0].position, 1);
    expect(rows[1].locked, isTrue);
    expect(rows[1].sub, contains('Onboarding'));
    expect(rows[1].opens, isFalse);
    expect(rows[2].optional, isTrue);
    expect(rows[3].position, isNull);
    expect(rows[3].optional, isFalse);
  });

  testWidgets('body shows the profile header line, numbers, lock and Optional label', (tester) async {
    await tester.pumpWidget(TestuTheme(child: MaterialApp(home: TestuTopicsBody(rows: topicsRows(_live)))));
    expect(find.text('Pilot · 2 required for your role · 1 optional'), findsOneWidget);
    expect(find.text('1'), findsOneWidget);
    expect(find.text('Optional'), findsWidgets);
    expect(find.textContaining('Finish Onboarding to unlock'), findsOneWidget);
  });
}
```

(`TestuTheme` is whatever wrapper `testu_lock_test.dart` or `testu_landscape_test.dart` uses to give `TestuTokens.of(context)`; copy it. If `testuLang` defaults to Spanish in tests, set `testuLang.value = 'en'` in `setUp`.)

- [ ] **Step 2: Run, expect failure**

```bash
flutter test test/testu_topics_test.dart
```

Expected: `position`, `topicsRows`, `TestuTopicsBody` undefined.

- [ ] **Step 3: `TopicState` fields**

In `testu_learn.dart` `TopicState.fromJson` initializer list add:

```dart
        position = j['position'] == null ? null : _int(j['position']),
        profile = _str(j['profile']),
        profileName = _str(j['profilename']),
        mandatory = j['mandatory'] == null ? null : _bool(j['mandatory']),
        requiresPrevious = _bool(j['requiresprevious']),
        previousTopic = _str(j['previoustopic']),
        locked = _bool(j['locked']),
        lockReason = _str(j['lockreason']),
        afterFinish = _str(j['afterfinish']),
        finished = j['finished'] == null ? null : _bool(j['finished']),
```

and fields:

```dart
  /// Job profile assignment (spec 2026-09-16). position null = not assigned
  /// by any profile of the learner; the server decides order and locks.
  final int? position;
  final String? profile, profileName, previousTopic, lockReason, afterFinish;
  final bool? mandatory, finished;
  final bool requiresPrevious, locked;
```

- [ ] **Step 4: Topics screen**

In `testu_topics.dart`:

1. Replace the private `_Topic` typedef with a public one and a builder:

```dart
typedef TopicsRow = ({
  String img,
  String title,
  String sub,
  String pill,
  Color pillColor,
  Color pillBorder,
  bool opens,
  String? id, // live topic id; null for demo rows
  int? position, // profile order; null = not assigned
  bool locked,
  bool optional,
  String? profileName,
});
```

(then rename every `_Topic` use in the file to `TopicsRow`; demo rows get `position: null, locked: false, optional: false, profileName: null`).

2. `_mapTopic` becomes public `topicsRows`:

```dart
/// Topics rows in the server's order (assignment first, then the rest):
/// band + %, questions answered, lock state and optional flag. No sorting
/// here -- the engine already ordered them.
List<TopicsRow> topicsRows(List<TopicProgress> live) {
  final titles = {for (final p in live) p.state.id: p.title};
  return [for (final p in live) _mapTopic(p, titles)];
}

TopicsRow _mapTopic(TopicProgress p, Map<String, String> titles) {
  final s = p.state;
  final m = bandStyle(s.band);
  final prev = titles[s.previousTopic] ?? s.previousTopic ?? '';
  return (
    img: _liveCoverUrl(p.topic) ?? '',
    title: p.title,
    sub: s.locked
        ? L('Finish $prev to unlock', 'Termina $prev para desbloquear')
        : s.questions == 0
            ? L('No content yet', 'Sin contenido todavía')
            : s.improveAvailable
                ? L('Improve Mode available · ${s.answered} of ${s.questions} questions',
                    'Modo Mejorar disponible · ${s.answered} de ${s.questions} preguntas')
                : L('${s.answered} of ${s.questions} questions · ${s.sections.length} subtopics',
                    '${s.answered} de ${s.questions} preguntas · ${s.sections.length} subtemas'),
    pill: s.locked ? L('Locked', 'Bloqueado') : bandPill(s),
    pillColor: s.locked ? _t.mut : m.color,
    pillBorder: s.locked ? _t.line2 : m.border,
    opens: s.questions > 0 && !s.locked,
    id: s.id,
    position: s.position,
    locked: s.locked,
    optional: s.position != null && s.mandatory == false,
    profileName: s.profileName,
  );
}
```

(`_t` is the file's token accessor used by `_masteryStyle`; if it needs a context, compute the two lock colours inside `_TopicRow` instead and pass `locked` only.)

3. `_LiveTopicsState.build` success branch: `return _topicsBody(context, topicsRows(live));`.

4. Make the body a widget: `class TestuTopicsBody extends StatelessWidget { const TestuTopicsBody({super.key, required this.rows, this.loading = false, this.empty, this.onRetry}); ... build => _topicsBody(context, rows, loading: loading, empty: empty, onRetry: onRetry); }` and keep `_topicsBody` private underneath.

5. In `_topicsBody`, replace the prototype-only role line block with:

```dart
                if (!testuLive) ...[ /* existing CL(...) prototype line, unchanged */ ],
                if (testuLive || topics.any((x) => x.position != null)) ...[
                  if (_headerLine(topics) case final line?) ...[
                    const SizedBox(height: 4),
                    Text(line, style: TextStyle(fontFamily: 'Geist', fontSize: 11.5, color: t.mut)),
                  ],
                ],
```

with

```dart
/// "<primary profile> · N required for your role[ · M optional]"; null when
/// no topic is assigned by a profile.
String? _headerLine(List<TopicsRow> topics) {
  final assigned = topics.where((x) => x.position != null).toList();
  if (assigned.isEmpty) return null;
  final required = assigned.where((x) => !x.optional).length;
  final optional = assigned.length - required;
  final name = assigned.first.profileName ?? '';
  final parts = [
    if (name.isNotEmpty) name,
    L('$required required for your role', '$required obligatorios para tu rol'),
    if (optional > 0) L('$optional optional', '$optional opcionales'),
  ];
  return parts.join(' · ');
}
```

6. In the `ListView` children, replace the single `for (final topic in topics) _TopicRow(...)` with three groups so the "Optional" label sits before the first optional row:

```dart
                for (final topic in topics.where((x) => x.position != null && !x.optional)) _row(context, topic),
                if (topics.any((x) => x.optional))
                  Padding(
                    padding: const EdgeInsets.fromLTRB(18, 14, 18, 2),
                    child: Text(L('Optional', 'Opcional'), style: kMeta),
                  ),
                for (final topic in topics.where((x) => x.optional)) _row(context, topic),
                for (final topic in topics.where((x) => x.position == null)) _row(context, topic),
```

with

```dart
Widget _row(BuildContext context, TopicsRow topic) => _TopicRow(
      topic: topic,
      onTap: topic.locked
          ? () => _explainLock(context, topic)
          : topic.opens
              ? () => pushLearnerScreen<void>(
                  context,
                  LearnerRoute(1, topicId: topic.id),
                  TestuTopicHomeScreen(
                    topicId: topic.id,
                    title: topic.title,
                    img: topic.img,
                    pill: topic.pill,
                    pillColor: topic.pillColor,
                    pillBorder: topic.pillBorder,
                  ))
              : _nothing,
    );

void _explainLock(BuildContext context, TopicsRow topic) => showModalBottomSheet<void>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(18, 18, 18, 24),
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(topic.title, style: kRowTitle),
            const SizedBox(height: 8),
            Text(topic.sub, style: kMeta),
            const SizedBox(height: 6),
            Text(L('Your job profile sets this order.', 'Tu perfil de puesto define este orden.'), style: kMeta),
          ]),
        ),
      ),
    );
```

(If the file has a `TestuSheet`/`showTestuSheet` helper used elsewhere for bottom sheets, use it instead of `showModalBottomSheet` to keep the app's chrome.)

7. `_TopicRow`: wrap the existing `Row` children with the position badge and dim locked rows:

```dart
    return Opacity(
      opacity: topic.locked ? 0.55 : 1,
      child: TestuPressable(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 18),
          child: Row(children: [
            if (topic.position != null) ...[
              SizedBox(width: 18, child: Text('${topic.position}', style: kMeta.copyWith(fontFeatures: const [FontFeature.tabularFigures()]))),
              const SizedBox(width: 6),
            ],
            /* existing cover Container, SizedBox(13), Expanded column, SizedBox(8), chevron -- unchanged */
          ]),
        ),
      ),
    );
```

(`FontFeature` needs `import 'dart:ui' show FontFeature;`.)

8. Topic home required fact (~L684): change the `sub` when not met and a profile is known:

```dart
            sub: st.requiredLevel == null
                ? L('No requirement for your role', 'Sin requisito para tu rol')
                : st.meetsRequirement
                    ? L('You meet it', 'Lo cumples')
                    : (st.profileName ?? '').isEmpty
                        ? L('Not met yet', 'Aún no alcanzado')
                        : L('Not met yet · from your ${st.profileName} profile', 'Aún no alcanzado · por tu perfil ${st.profileName}'),
```

- [ ] **Step 5: Run the learner tests**

```bash
flutter test test/testu_topics_test.dart test/testu_learn_test.dart test/testu_question_source_test.dart test/testu_lock_test.dart
```

Expected: pass. `flutter analyze lib/testu/testu_topics.dart lib/testu/testu_learn.dart` clean.

- [ ] **Step 6: Commit**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs
git add lib/testu/testu_learn.dart lib/testu/testu_topics.dart test/testu_topics_test.dart
git commit -m "learner Topics: profile order, numbers, locks, optional group, profile line on topic home"
```

---

### Task 8: Build web bundles, end-to-end check, ship

**Files:**
- Modify (generated): `eme-server-minsur/plugins/testu/html/admin/**`, `html/learn/**`, `eme-server-minsur/webapp/site/mediadb/{admin,learn}/**`

- [ ] **Step 1: Build both bundles**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/app-genailabs && ./build_admin.sh && ./build_learn.sh
```

Expected: both finish; `eme-server-minsur/bin/sync-testu.sh --check` (if present) reports no drift.

- [ ] **Step 2: Manual end-to-end on the local server (browser)**

1. Sign in to `http://localhost:8080/site/mediadb/admin/` as an orgadmin, open Personas: the rail shows "Perfiles / Todavía no hay perfiles." and "+ Nuevo perfil".
2. Create profile "Piloto": add three topics, set row 2 "Requiere anterior = Sí", row 1 "Al terminar = Quitar", Save. Rail shows Piloto · 0.
3. Click a person's Profile cell, set Piloto as primary, Save. Rail shows Piloto · 1; picking it filters the roster.
4. Sign in to `http://localhost:8080/site/mediadb/learn/` as that person: Topics shows "Piloto · 3 obligatorios para tu rol", numbered rows, row 2 dimmed "Termina <tema 1> para desbloquear"; tapping it opens the explanation sheet and no session.
5. Run `check_profiles.sh` and `check_learning.sh` once more: `PASS`.

- [ ] **Step 3: Commit the bundles (plugin repo + server repo)**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/testu
git add html/admin html/learn
git commit -m "bundles: admin + learn with job profiles (app-genailabs $(git -C ../../../app-genailabs rev-parse --short HEAD))"
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur
git add webapp/site/mediadb/admin webapp/site/mediadb/learn webapp/WEB-INF/data/site/catalog/fields/topicrequirement.xml webapp/WEB-INF/data/site/catalog/lists/afterfinish.xml webapp/WEB-INF/data/system/fields/user.xml webapp/site/mediadb/services/testu/personas
git commit -m "job profiles: data files, personas endpoints, admin + learn bundles (plugins/testu $(git -C plugins/testu rev-parse --short HEAD))"
```

Only stage the paths above — peers may have unrelated modified files in these trees (`TestUSocialModule.java`, `tutorialprogress.xml`): leave them alone.

- [ ] **Step 4: Report**

Tell the user: what shipped, the two check scripts' PASS lines, that Minsur live is unaffected until a profile is assigned, and that deploy is a separate step (`/deploy-minsur`).

---

## Self-review

- **Spec coverage:** Data (T1), engine merge/finished/locked/removed/order/DC (T2, T3), state.json + 409s + person.json (T3), endpoints + users/me + import (T4), admin console rail/editor/column/person sheet/read-only (T5, T6), learner Topics + topic home (T7), tests pure/server/Flutter (T2–T7), rollout bundles + .gitignore (T5, T8). Deviations from the spec text, applied on purpose (spec updated in the same commit as this plan): CSV columns are `primaryjobrole` + `jobrole` (ids or names; the importer maps header names to fields, so a `jobroles` column would create a stray field); `state.json` topics also carry `profilename`; order is changed with up/down acts rather than drag; extra profiles are edited in a per-person sheet opened from the Profile cell, the Add-person sheet only takes the primary.
- **Placeholders:** none; every step carries its code or exact command.
- **Type consistency:** `ProfileRow`/`Profiles`/`applyProfiles`/`requiredTopics`/`lockedTopicSections`/`primaryJobroleOf` used identically across T2–T4; Dart `ProfileRow`/`AdminProfile`/`AdminTopicRef`/`profiles()`/`saveProfile()`/`deleteProfile()`/`setProfiles()` identical across T5–T6; `TopicsRow`/`topicsRows`/`TestuTopicsBody` across T7.
