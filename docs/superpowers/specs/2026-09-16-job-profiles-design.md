# Job profiles (ordered role–topic assignment)

Date: 2026-09-16. Sub-project 1 of 3 (2 = Evaluation Mode, 3 = Certificates; each gets its own spec).
Source of truth: Notion Project Hub → 02 Product Requirements ("Role requirements", "Requirement conflicts", "Topics"), 04 Admin Platform ("Role–topic requirement model"), 05 Data/Mastery ("Mastery bands and role requirements"). Builds on `2026-09-14-learning-engine.md` (required level, subtopic progression, Daily Challenge).

Goal: an administrator defines job profiles (the PRD's "Role"; the app's Personas tab calls the permission role "role", so the learner-facing and admin-facing name for this entity is **Job profile / Perfil de puesto**). A profile is an ordered list of topics, each with a required mastery level, mandatory/optional, an optional gate on the previous topic, and what happens after the learner finishes it (keep or remove). People get one primary profile plus any number of extra profiles. The engine orders, gates, and retires topics accordingly; the app renders.

Product-first rule applies: one generic model; organizations configure profiles. No pilot-specific behaviour. With no profile data the engine behaves exactly as today, so the live Minsur build is unaffected until someone assigns a profile.

## Data

Existing (learning engine v1), kept and extended:

- List `jobrole` = the Job profile entity. Fields: `id`, `name` (already there). Admin label "Job profile" / "Perfil de puesto". Not deletable while any user references it.
- Table `topicrequirement` = one row per profile × topic. Existing: `jobrole`, `entitytopic`, `requiredlevel` (beginner | competent | expert; blank allowed = no level required). New fields:
  - `position` (number, 1-based, unique within a profile; the admin saves a full ordered list, the server renumbers 1..n).
  - `mandatory` (boolean, default true). Optional topics are shown to the learner as optional and enter Daily Challenge only under the existing enrichment setting, exactly as non-required topics do today.
  - `requiresprevious` (boolean, default false). Ignored on position 1.
  - `afterfinish` (list `afterfinish`: `keep` | `remove`, default keep).
  - Row id = `<jobrole>_<entitytopic>`; create/replace under the write lock.
- User (`system/fields/user.xml`): existing `jobrole` (multi) = all of the user's profiles. New `primaryjobrole` (single, list `jobrole`). Invariant enforced on write: `primaryjobrole` ∈ `jobrole`; a user with profiles must have a primary; clearing the last profile clears the primary.
- Audit (`auditevent`, existing `audit()` helper): `jobprofile.save` (target `jobrole`, before/after = name + rows), `jobprofile.delete`, `jobprofile.assign` (target `user`, before/after = primary + list). Append-only.

Evaluation and certificate settings (question count, pass %, periodicity) will be added to the same `topicrequirement` row by sub-projects 2 and 3. Nothing for them is built or reserved now.

## Engine rules (`LearningEngine`)

Terms:

- *Profiles of learner u* = `primaryjobrole` first, then the rest of `jobrole` sorted by profile name (ties by id).
- *Assignment* of u = the ordered union of the rows of u's profiles: primary rows by position, then each extra profile's rows by position. A topic appearing in several profiles keeps its first occurrence for order, `requiresprevious` and the "previous topic"; the merged attributes are: `requiredlevel` = strictest (expert > competent > beginner > blank), `mandatory` = true if any, `afterfinish` = keep if any keep. Rows whose topic the learner cannot see (entity security, existing `visibleTopics`) or that has no eligible questions are skipped silently.
- *Finished(topic)* = topic learn complete (every sequence question learning-answered) AND, when a required level is set, band ≥ required level (expert also needs expert evidence, same test as `meetsrequirement`). With no required level, learn complete alone.
- *Started(topic)* = u has any learn or dailychallenge answer or exposure on a question of the topic.
- *Locked(topic)* = its row has `requiresprevious` AND the previous row of the same profile (the row at position − 1 in the profile the occurrence came from) is not Finished AND the topic is not Started. Started topics never relock, so no unlock rows are written (contrast: subtopics record `subtopicunlock`; topics do not need it). Lock reason: `previous_topic_incomplete`, with `previoustopic` = that topic id.
- *Removed(topic)* = `afterfinish = remove` AND Finished. Removed topics are excluded from the learner's topic list, from Daily Challenge (new and reinforcement), from Improve, and from the required-topic gap in analytics. Evidence (`tutoranswer`, `tutorexposure`, `tutormastery`) is untouched. Removal is computed from current state: mastery v1 has no time decay, so once Finished a topic stays Finished unless a later attempt lowers the band (ponytail: recompute; if decay is ever added, persist a `topicfinished` row instead).

Behaviour:

- **Required topics** (Daily Challenge, analytics) = the mandatory, non-removed topics of the assignment. The existing fallback stays: a learner with no profile rows at all gets every learner-visible topic with eligible questions as required, catalog order, `inputs.topicsreason = assignment_data_unavailable`.
- **Order**: `state.json` lists topics in assignment order, then non-assigned visible topics in catalog order (a learner may still see topics nobody assigned, as today). Daily Challenge new-question fill takes unanswered questions in assignment order instead of catalog order; locked topics are excluded from the new pool and from reinforcement selection (their questions cannot be Started, so this only matters for a topic locked after a profile edit while un-started: nothing to reinforce anyway).
- **Locks**: `next.json` learn or improve with a locked topic (or a section of it) → 409 `topic_locked` `{topic, previoustopic}`; `answer.json` / `exposure.json` in learn or improve on a locked topic → 409 `topic_locked`. Improve scope inside a locked topic is impossible in practice (not Started ⇒ not learn complete) but the check is explicit. Reads never persist anything.
- **Existing rules unchanged**: subtopic progression inside a topic, Improve unlock, mastery, bands, sessions, strictest required level.

`state.json` per topic adds: `position` (null when not assigned), `profile` (id of the profile the occurrence came from, null when not assigned), `mandatory`, `requiresprevious`, `previoustopic`, `locked`, `lockreason`, `afterfinish`, `finished`. Removed topics are omitted from `topics`; their ids are listed in a top-level `removedtopics` array so the app can, if it ever wants to, show "Completed: Onboarding". Top-level `profiles` = `[{id, name, primary}]` for the learner (the app explains "Required: Competent · from your Pilot profile").

`person.json` (analytics) `risk.requiredtopics` uses the same assignment: profile order, removed topics excluded, plus `profile` and `position` per row.

## Endpoints (`services/testu/personas/`, new Java module `TestUProfileModule extends TestUBaseModule`, registered in plugin.xml, wired by `.xconf`)

All return `{ok, ...}` or `{error}` in the existing style. Learners (no `personas_view`) get 403 on every one.

- `profiles.json` (GET, personas_view): `{profiles: [{id, name, members, rows: [{topic, topictitle, position, requiredlevel, mandatory, requiresprevious, afterfinish, questions}]}], topics: [{id, title, questions}]}`. `questions` = eligible sequence questions of the topic (0 lets the console warn "no questions yet"). `members` = users whose `jobrole` contains the profile.
- `saveprofile.json` (POST, personas_manage): `id` (blank = create; server id = slug of name, unique), `name`, `rows` (JSON array in assignment order; each `{topic, requiredlevel, mandatory, requiresprevious, afterfinish}`). Validation: name non-blank and unique (case-insensitive); every topic exists, no duplicates; `requiredlevel` blank or a `masterylevel` id; `afterfinish` in list; `requiresprevious` false on the first row. Replaces the profile's row set (delete rows not in the list, upsert the rest, renumber). Audited. Returns the saved profile as `profiles.json` would.
- `deleteprofile.json` (POST, personas_manage): `id`. 409 `profile_in_use` `{members}` when any user references it; otherwise deletes the rows and the list entry. Audited.
- `setprofiles.json` (POST, personas_manage): `user`, `primary`, `extras` (JSON array). Validation: all ids exist, `primary` not in `extras`, `primary` blank only when `extras` empty. Writes `primaryjobrole` and `jobrole` = [primary, …extras]. Audited. Also called by the person editor when changing the roster.
- `users.json`: each user adds `primaryjobrole` and `jobroles` (ids). `me.json` adds the same for the signed-in user.
- `importusers.json`: optional columns `primaryjobrole` and `jobroles` (profile **names**, `jobroles` separated by `|`). Unknown name → row error like an unknown team today. Existing rows without the columns are untouched.

Scope: managers with `personas_view` see profiles read-only; `personas_manage` edits. Same verbs the People screen already uses.

## Admin console (`app-genailabs/lib/admin/`)

Personas screen (`admin_people.dart`):

- The rail gets a second section, **Job profiles / Perfiles**, under the teams: one item per profile with its headcount, then "+ New profile" (personas_manage only). Selecting a profile filters the roster to its members (primary or extra) and shows the profile editor above the table, the same slot the team head uses.
- Profile editor (new `admin_profiles.dart`): name field; topic table with drag-to-reorder handle, position, topic, required level (dropdown: none / Beginner / Competent / Expert), Mandatory (switch), Requires previous (switch, disabled on row 1), After finish (Keep / Remove); "Add topic" picker listing topics not yet in the profile (with question counts; 0 shows a warning icon); remove row; Save / Cancel; Delete (disabled with a tooltip while members > 0). Save posts the full ordered list; on success the rail headcounts and roster refresh. Read-only rendering without personas_manage.
- Person add/edit sheet: "Primary profile" dropdown and "Extra profiles" multi-select; saved through `setprofiles.json` after the user save.
- Roster: new column Profile = primary name, "+n" when extras exist; the search box matches profile names.
- Models (`admin_models.dart`): `AdminProfile`, `ProfileRow`; `AdminUser` gains `primaryProfile`, `profiles`. API (`admin_api.dart`): `profiles()`, `saveProfile()`, `deleteProfile()`, `setProfiles()`.

Nothing else in the console changes in this sub-project (analytics person page already shows required topics; it now receives them in profile order).

## Learner app (`app-genailabs/lib/testu/`)

- `testu_client.dart` `ScopeState`: add `position`, `profile`, `mandatory`, `requiresPrevious`, `previousTopic`, `locked`, `lockReason`, `afterFinish`, `finished`; state gains `profiles` and `removedTopics`.
- Topics screen (`testu_topics.dart`): rows in server order (no client sorting). Assigned mandatory topics first with position numbers; the header line reads "<primary profile name> · N required for your role" (plus "· M optional" when any). Optional topics follow under an "Optional / Opcional" label with an Optional pill. Locked rows are dimmed with a lock pill and "Finish <previous topic> to unlock / Termina <tema> para desbloquear"; tapping opens a short sheet with the same message and no session. Removed topics are not listed. Non-assigned visible topics come last, unnumbered, as today.
- Topic home: required level line becomes "Required: Competent · from your Pilot profile" when `profile` is set.
- Today / continue: unchanged code paths; they already follow the server's `nextquestionid` and topic order.
- Tutor name stays org-configured (never hardcoded).

## Tests

- `tools/LearningEngineCheck.java` (pure, fixed inputs): assignment order (primary first, extras by name); shared topic merge (strictest level, mandatory if any, keep beats remove, first occurrence for order/gate); Finished with and without required level, expert evidence; Locked only with `requiresprevious`, only on the same profile's previous row, never on position 1; Started never relocks; Removed excluded from required topics, DC pools and state; DC new-fill in assignment order; no-profile fallback identical to today (same output as the existing fixtures); rows on invisible or empty topics skipped.
- `tools/check_learning.sh` (server): learner 403 on all four endpoints; `saveprofile` create, rename, reorder (renumbered), remove a row, validation errors (duplicate topic, bad level, requiresprevious on row 1, duplicate name); `deleteprofile` 409 with members then success after unassign; `setprofiles` invariants; `users.json` / `me.json` fields; import with the two columns and an unknown name; `state.json` order, lock fields, `removedtopics`, `profiles`; 409 `topic_locked` on next/answer/exposure; audit rows written.
- Flutter (`app-genailabs/test/`): one widget test for the Topics screen: order, numbers, optional group, locked row text; one for the profile editor: reorder + save payload order.
- `tools/validate_content.py` unchanged.

## Rollout

- Data files: `plugins/testu/data/lists/afterfinish.xml`, `fields/topicrequirement.xml`, `system/fields/user.xml` (and their `webapp/WEB-INF/data` copies as the deploy expects).
- No migration: existing `topicrequirement` rows (none in production) read `position` blank → treated as catalog order, `mandatory` true, `afterfinish` keep.
- Web bundles rebuilt and committed per the no-CI rule.
- Out of scope (later sub-projects or later): per-user overrides with reason/approver/expiry, evaluation blueprints, certificates and periodicity, competencies, time-based decay, topic status/published flag.
