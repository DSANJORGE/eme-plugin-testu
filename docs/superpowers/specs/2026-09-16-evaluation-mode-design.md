# Evaluation Mode (topic evaluations v1)

Date: 2026-09-16. Sub-project 2 of 3 (1 = Job profiles, done; 3 = Certificates, later).
Source of truth: Notion Project Hub → 02 Product Requirements ("Evaluation Mode", "Mode-specific rules", "Confidence model", "Hints", "Completion vs mastery vs certification"), 04 Admin Platform ("Certifications: evaluation blueprints, forms, results"). Builds on `2026-09-14-learning-engine.md` (modes, sessions, mastery, `evaluationreserved`) and `2026-09-16-job-profiles-design.md` (assignment, Finished, `topicrequirement`).

Goal: an administrator activates a per-topic evaluation blueprint (max questions, coverage, difficulty mix, strategy, pass rule, timer, retake rules). An eligible learner takes the evaluation in the app: a server-issued attempt, no teaching, delayed feedback, a timer, then score / pass / fail per subtopic and a remediation path. A job profile row may require the pass for the topic to count as Finished. The server selects, scores and decides; the app renders.

Decisions taken with the user (2026-09-16): strategies **random per user** and **reserved questions** (common form and weak-area focus arrive with Certificates); the pass **feeds job profiles** through the profile row; a **full-evaluation timer**; results show **score, pass/fail and per-subtopic** only (no per-question review inside the evaluation).

Product-first rule: one generic model; organizations configure blueprints. With no active blueprint the engine, endpoints and app behave exactly as today, so the live Minsur build is unaffected until someone activates one.

## Data

New table `evaluationblueprint` (`data/fields/evaluationblueprint.xml`), append-only versions like `subtopicpolicy`. Id `<topicid>_v<n>`; the highest version is current.

- `entitytopic`, `blueprintversion` (number), `user`, `datecreated`.
- `active` (boolean). Inactive = evaluation not offered. The first save of a topic may be inactive (draft).
- `strategy` (list `evaluationstrategy`: `random` | `reserved`).
- `maxquestions` (number, 1–100).
- `minpersubtopic` (number, ≥ 0; 0 = no per-subtopic minimum).
- `excludedsections` (JSON array of section ids not covered; empty = every subtopic).
- `difficultymix` (list `difficultymix`: `proportional` | `balanced`).
- `passpercent` (number, 1–100).
- `subtopicminpercent` (number, 0–100; 0 = no per-subtopic pass rule).
- `timerminutes` (number, 0–480; 0 = no timer).
- `retakewaithours` (number, ≥ 0).
- `maxattempts` (number, ≥ 0; 0 = unlimited).
- `requirelearncomplete` (boolean, default true): the learner must have topic Learn complete to start.

New table `evaluationattempt` (`data/fields/evaluationattempt.xml`). Id `<user>_<topic>_a<n>`.

- `user`, `entitytopic`, `blueprintversion`, `strategy`, `attemptnumber` (1-based per user × topic), `questionlist` (JSON, ordered question ids with section and difficulty as resolved by the server), `total`.
- `status` (keyword: `inprogress` | `submitted` | `expired`), `datecreated` (start), `expiresat` (start + `timerminutes`; with no timer, start + 24 h: an attempt is always one sitting in the sense of one bounded window), `submitted` (date), `finalizedby` (`learner` | `timer`).
- Result: `answered`, `correct`, `scorepercent`, `passed` (boolean), `subtopicresults` (JSON: `[{section, questions, correct, percent, met}]`).
- Exposure risk: `exposed` (selected questions the learner had answered or been shown in learn / dailychallenge / improve before the attempt), `reused` (selected questions already used in the learner's earlier evaluation attempts of the topic), `exposurerisk` = `exposed + reused > 0`.
- `inputs` (JSON: pool sizes by section and difficulty, targets, seed, and every relaxation used: `minpersubtopic_short`, `mix_short`, `reused`).
- Create-only except the finalization fields, written once under the engine write lock.

Answers: existing `tutoranswer` rows with `mode = evaluation`, `scopetype = topic`, `scopeid = <topic>`, `learningsession = <attempt id>`, `hintlevel = 0`. Written only by `answer.json` (below). Evaluation answers keep their existing roles: mastery evidence (latest attempt in any mode), never a learning answer (no Learn completion, Improve unlock, subtopic unlock, Daily Challenge completion, cooldown or remediation).

`topicrequirement` (job profiles) gains `evaluationrequired` (boolean, default false). Merge across profiles: true if any.

Lists: `data/lists/evaluationstrategy.xml` (random "Aleatoria por persona", reserved "Preguntas reservadas"), `data/lists/difficultymix.xml` (proportional "Proporcional al banco", balanced "Equilibrada").

Audit (`auditevent`): `evaluationblueprint.change` (target `entitytopic`; the version rows hold before/after), `evaluation.start`, `evaluation.submit` (target `evaluationattempt`, after = result). `jobprofile.save` already covers the new row field.

Permissions: blueprints read `training_view` or `training_manage`, write `training_manage` (same as subtopic progression). Learners: 403 on the blueprint endpoint; the learner endpoints need only a signed-in user.

## Engine rules (`LearningEngine`)

Terms:

- *Blueprint(topic)* = the highest `evaluationblueprint` version; *active* when it exists and `active = true` and the topic is learner-visible. Invalid stored values (out of range, unknown strategy or mix) make the version **inactive** with `blueprintreason` = `invalid_<field>`; the endpoint refuses such values on save, so this only guards edited data.
- *Pool(topic, strategy)* = `random`: the topic's sequence questions (non-reserved) that pass `contentProblem`; `reserved`: the topic's `evaluationreserved = true` questions (all its subtopics) that pass `contentProblem`. Both minus questions whose section is in `excludedsections`. Reserved questions stay excluded from every other mode (existing rule).
- *Pool sufficient* = `|pool| ≥ maxquestions` AND, when `minpersubtopic > 0`, every covered section with at least one pool question has ≥ `minpersubtopic` of them AND `Σ min(minpersubtopic, section pool) ≤ maxquestions`. Insufficient pools are reported on every read (`poolwarning` with the numbers) and refused on activation (409 `pool_insufficient`), never silently trimmed. Content edits after activation can still shrink a pool: selection then fills what it can and records the shortfall in `inputs` (see Selection); the console shows the warning on the blueprint list.
- *Evaluation status* of learner u on topic t (pure, from the blueprint, u's attempts of t and u's learning state):
  - `not_available` — no active blueprint (`reason` = `no_blueprint` | `inactive` | `invalid_<field>`).
  - `in_progress` — an attempt with `status = inprogress` and `expiresat` in the future (resumable).
  - `passed` — the latest finalized attempt passed. Terminal in v1: no further attempts (renewal cycles are the Certificates sub-project). `passedat` = its `submitted`.
  - `locked` — the topic is Locked by a job profile gate (`topic_locked`, existing rule) or `requirelearncomplete` and topic Learn is not complete (`learn_incomplete`).
  - `waiting` — the latest finalized attempt failed and now < its `submitted + retakewaithours` (`nextallowedat`).
  - `exhausted` — `maxattempts > 0` and finalized attempts ≥ `maxattempts`.
  - `available` — otherwise (`canstart = true`); `attemptsleft` = `maxattempts − finalized` or null when unlimited. A failed latest attempt with a retake allowed reports `available` with `lastresult` so the app can say "Not passed · 62 % · try again".
  Precedence in that order. Expired in-progress attempts are finalized (scored with what they have, `finalizedby = timer`) lazily by the first read or write that meets them and by the 15-minute `computemastery` event, under the write lock, idempotently.

Selection (`LearningEngine.buildEvaluation`, pure: pool, blueprint, learner history, seed → ordered list + inputs). The seed is the attempt id, so a stored attempt can be reproduced and the pure checks are deterministic.

1. n = min(`maxquestions`, |pool|). Each pool question gets a rank: 0 = never used in the learner's earlier evaluation attempts of the topic, 1 = used before. Retakes therefore avoid earlier questions "where the pool allows" (PRD); reuse is counted in `reused`.
2. Coverage first: for each covered section with pool questions, in content order, take `min(minpersubtopic, section pool)` questions: lowest rank first, ties by the seeded shuffle. A section short of `minpersubtopic` records `minpersubtopic_short`.
3. Difficulty mix for the remaining slots. `proportional`: seeded shuffle of the remaining pool, lowest rank first. `balanced`: target = remaining ÷ 3 per difficulty (beginner, competent, expert; remainder to the harder levels first); round-robin over the three buckets, lowest rank first within a bucket, skipping empty buckets and recording `mix_short` when a bucket runs out.
4. Order of presentation: by section content order, then by the pick order inside the section (learners see a coherent flow; comparability comes from the blueprint, not the order).
5. `exposed` = selected questions with any learn / dailychallenge / improve answer or exposure by the learner before the attempt (0 by construction under `reserved` unless a reserved flag was added after the learner saw the question). Stored, never shown to the learner; the console shows it per attempt.

Scoring (pure): strict, one point per question, unanswered = wrong. `scorepercent = round(100 × correct / total)` (half up). Per section: same over the section's selected questions. `passed` = `scorepercent ≥ passpercent` AND, when `subtopicminpercent > 0`, every section with ≥ 1 selected question has `percent ≥ subtopicminpercent` (`met` per section; the rule that failed is reported: `failedrule = overall | subtopic`). `failedrule` is `overall` when both rules fail. Confidence is stored and never affects the score. Hints do not exist in this mode (`hintlevel` must be 0). Multiple-correct questions do not exist in the bank; if they arrive, strict = the exact option set (out of scope now).

Job profiles: `Finished(topic)` (job-profiles spec) gains a third conjunct: when the learner's merged row has `evaluationrequired`, the evaluation status must be `passed`. Consequences follow the existing rules: a `requiresprevious` gate on the next topic waits for the pass, `afterfinish = remove` waits for the pass, and `state.json`'s `finished` reflects it. `meetsrequirement` stays mastery-only (certification and mastery are separate, PRD). Analytics adds `evaluationmet` (see Endpoints) and counts an unmet required evaluation in `requiredgaps`.

Tutor: `AdaptiveTutorialAnswerSkill` (chat feedback after an answer) returns one fixed procedural line and no explanation, hint, source or citation when the stored answer's mode is `evaluation` (server guard; the app never opens the chat during an attempt). Social comments are not shown during an attempt (app).

Learn / Improve / Daily Challenge: unchanged. Evaluation answers never advance the learning sequence (existing rule, kept: a non-reserved question first seen in the evaluation is still served later by Learn or Daily Challenge with teaching).

## Endpoints

Learner (`services/testu/learn/`, `TestULearningModule`, signed-in user; `{ok:true, ...}` / `{ok:false, error}`; 401 when not signed in):

- `evaluation.json?topicid=` (GET) → `{ok, topic, blueprint: {version, strategy, maxquestions, minpersubtopic, difficultymix, passpercent, subtopicminpercent, timerminutes, retakewaithours, maxattempts, requirelearncomplete} | null, status, reason, canstart, required, attemptsleft, nextallowedat, passedat, lastresult: {attemptid, scorepercent, passed, submitted, subtopics} | null, attempt: {attemptid, expiresat, total, answered} | null, attempts: [{attemptid, number, datecreated, submitted, status, scorepercent, passed}]}`. Read-only except lazy finalization of an expired attempt. 404 `unknown_topic` (also when not learner-visible).
- `startevaluation.json` (POST `topicid`) → creates the attempt under the write lock (a resumable in-progress attempt is returned instead of creating a second one) and replies in `next.json` shape: `{ok, mode: "evaluation", sessionid: <attempt id>, total, expiresat, timerminutes, items: [{questionid, componentid, sectionid, tutorialid, topicid, position, done}]}` (`done` = answered in this attempt, for resume). Before storing, every item passes the servable-content check (409 `content_unavailable` + `questionflag`, as for sessions). 409 `evaluation_not_available` `{status, reason, nextallowedat}` when `canstart` is false. Audited `evaluation.start`.
- `answer.json`: `mode = evaluation` accepted. Validation (`resolve`): scope must be `topic` + the question's topic; `sessionid` = an `evaluationattempt` of this learner (`unknown_session` | `session_mismatch`), `inprogress` (`attempt_closed` when submitted/expired), not past `expiresat` (`session_expired`, and the attempt is finalized), the question in its `questionlist` (`not_in_session`), not yet answered in this attempt (`already_answered`), `hintlevel` = 0 (`hints_not_allowed`). Any order. The response omits `iscorrect` and returns `deferred: true` (correctness is not sent to the client during an attempt). Idempotent by `attemptid` as today. Legacy rows without mode are unaffected.
- `exposure.json`: `mode = evaluation`, membership in the attempt only.
- `submitevaluation.json` (POST `sessionid`) → finalizes: `{ok, attemptid, status: "submitted", scorepercent, correct, answered, total, passed, failedrule, passpercent, subtopicminpercent, subtopics: [{id, title, questions, correct, percent, met}], weakest: [section ids, lowest percent first, max 3], evaluationstatus, nextallowedat, attemptsleft, finished}` (`finished` = the topic's job-profile Finished after this result). Already finalized → the same result with `duplicate: true`. Not this learner's → 404 `unknown_session`. Audited `evaluation.submit`; `notifyUser(user, "progress")` as other progress changes do.
- `state.json` per topic adds `evaluation: {status, reason, canstart, required, passed, passedat, scorepercent (latest finalized), attempts, attemptsleft, nextallowedat, inprogress: {attemptid, expiresat, answered, total} | null}`; `finished` now includes the evaluation conjunct.

Admin (`services/testu/learn/evaluationblueprint.json`, `TestULearningModule`; read `training_view` | `training_manage`, write `training_manage`; learners 403 `forbidden`):

- GET → `{ok, canmanage, topics: [{id, title, version, active, strategy, maxquestions, minpersubtopic, difficultymix, passpercent, subtopicminpercent, timerminutes, retakewaithours, maxattempts, requirelearncomplete, excludedsections, reason, pool: {size, sufficient, bysection: [{id, title, position, sequence, reserved}], bydifficulty: {beginner, competent, expert}}, stats: {attempts, passed, inprogress}}]}`. Topics without a blueprint appear with `version: 0`, `active: false`, `reason: "not_configured"` and their pool numbers, so the console can warn before the first save.
- GET `?topicid=` → `{ok, canmanage, topic, versions: [{version, active, …fields, user, datecreated}]}` newest first.
- POST `topicid, expectedversion, active, strategy, maxquestions, minpersubtopic, excludedsections (JSON), difficultymix, passpercent, subtopicminpercent, timerminutes, retakewaithours, maxattempts, requirelearncomplete` → `{ok, unchanged, topic}` (identical to the current version → `unchanged: true`, no new version). Errors: 400 `missing_topicid` | `missing_expectedversion` | `bad_expectedversion` | `bad_<field>` (with the allowed range in `detail`); 404 `unknown_topic` | `unknown_section`; 409 `version_conflict` `{currentversion}` | `pool_insufficient` `{pool}` (only when `active = true`). Create-only versions under the write lock with realtime get-before-save, like `subtopicpolicy`. Audited.
- An in-progress attempt keeps the version it started with; a new version applies from the next `startevaluation`.

Personas (`profiles.json` / `saveprofile.json`): rows gain `evaluationrequired`; validation boolean; `topicrequirement` field added; audited through the existing `jobprofile.save`.

Analytics (`person.json`): each `risk.requiredtopics` row adds `evaluationrequired`, `evaluation: {status, scorepercent, passedat, attempts}`, `evaluationmet` (true when not required, or required and passed). `requiredgaps` counts rows with `meetsrequirement = false` OR `evaluationmet = false`. Readiness statuses are still not computed.

## Admin console (`app-genailabs/lib/admin/`)

New rail section **Evaluations / Evaluaciones** (visible with `training_view` or `training_manage`, next to Progression), new file `admin_evaluations.dart` following `admin_progression.dart`:

- Topic table: topic, status (Active / Draft / Not configured), strategy, questions (max · min per subtopic), pass rule (`70 %` or `70 % · 50 % per subtopic`), timer, attempts / passed, and a warning glyph with the pool numbers when `pool.sufficient` is false.
- Editor (personas with `training_manage`; read-only otherwise): Strategy (Select with the PRD's one-line "achieves / trade-off" per option), Max questions, Min per subtopic, Subtopics covered (checkbox list; unchecking = excluded), Difficulty mix (Select), Pass % , Subtopic min %, Timer (minutes, 0 = none), Wait between attempts (hours), Max attempts (0 = unlimited), Learn must be complete (Select<bool>), Active (Select<bool>). Pool panel beside it: size by section and difficulty, "sufficient for this blueprint" or the shortfall in words. Save posts `expectedversion`; `version_conflict` reloads and asks to save again; `pool_insufficient` shows the numbers and keeps the draft (the user may save it inactive). Version history table like Progression.
- Profile editor (`admin_profiles.dart`): new column **Evaluation** (Select<bool>: Not required / Required), saved with the row. Person page (`admin_person.dart`) required-topic rows show the evaluation status pill (Passed 84 % · Not passed 62 % · Not taken · Required).
- Models (`admin_models.dart`): `EvaluationBlueprint`, `BlueprintVersion`, `EvaluationPool`; `ProfileRow.evaluationRequired`. API (`admin_api.dart`): `evaluationBlueprints()`, `evaluationBlueprint(topicId)`, `saveEvaluationBlueprint(...)`.

## Learner app (`app-genailabs/lib/testu/`)

- `testu_client.dart`: `TopicState.evaluation` (`EvaluationState`: status, reason, canStart, required, passed, passedAt, scorePercent, attempts, attemptsLeft, nextAllowedAt, inProgress {attemptId, expiresAt, answered, total}); client methods `evaluation(topicId)`, `startEvaluation(topicId)`, `submitEvaluation(sessionId)`.
- Topic home (`testu_topics.dart`): an **Evaluation** card under the Learn / Improve rows when `evaluation.status != not_available`: title "Topic evaluation / Evaluación del tema", one status line (Available · 20 questions · 25 min; Required for your role; In progress · 12 of 20 · ends 14:32; Not passed · 62 % · you can try again on Sep 18 09:00; Passed · 84 % on Sep 16; No attempts left; Complete Learn first) and the CTA "Take the evaluation / Hacer la evaluación" or "Resume / Continuar" when `canStart` or in progress. Nothing when `not_available`.
- Session (`testu_session.dart`, `testu_live.dart` `EmeQuestionSource`, `testu_session_engine.dart`): `mode = evaluation`. Differences from a learning session, all driven by the mode: eyebrow "EVALUATION / EVALUACIÓN"; a procedural intro from the tutor ("20 questions, 25 minutes, no hints or explanations; you see your result at the end; you can leave and come back until the time runs out"); no hint button, no tutor chat, no social comments, no "Rate my feedback"; after the confidence pick the engine advances without a verdict (options never colour, no explanation bubble; the engine's `judge` still records the local attempt for the outcome but the screen does not render `Verdict` entries); a countdown in the header from the server's `expiresat` (client clock offset taken from the response time; reaching zero shows "Time is up" and submits); "Question N of T"; answers are awaited as in other modes; the ✕ / back flow says the attempt stays open until `expiresat` and offers Leave or Submit now. The last question's Continue (or Submit now) calls `submitEvaluation` and opens the result screen.
- Result screen (new `testu_evaluation_result.dart`, replaces the debrief in this mode): pass / not passed headline with the score and the pass rule, per-subtopic rows (percent, met glyph), the tutor's line — passed: "You passed «Topic» with 84 %"; failed: "Not this time: 62 % (70 % needed). Your weakest subtopics were «A» and «B». Let's work on them." — then CTAs: failed → "Improve «A» / Mejorar «A»" (Improve on the weakest subtopic when its Improve is available, else Learn there) and "Back to topic"; passed → "Back to topic". Shows `nextallowedat` / attempts left when relevant. No per-question review (decision above).
- `topic_locked`, `content_unavailable`, `session_expired` and `attempt_closed` errors: same handling as today's sessions (Start again / Back), with "Time is up" wording for `session_expired` in this mode.
- Today: no new card in v1 (evaluation-due notifications and the prototype certification card belong to Certificates).
- Tutor name stays org-configured.

## Tests

- `tools/LearningEngineCheck.java` (pure): pool by strategy (reserved only under `reserved`, excluded sections dropped, content problems dropped); sufficiency rule and the three shortfall cases; selection: n = min(max, pool), min per subtopic taken first, balanced mix targets and `mix_short`, proportional fill, rank 0 before rank 1 (retake avoids earlier questions) and `reused` count, seeded determinism (same seed same list, different seed different order), presentation order by section; exposure count across modes; scoring: rounding, unanswered = wrong, overall rule, subtopic rule, `failedrule`; status precedence (not_available, in_progress, passed terminal, locked by gate / learn incomplete, waiting with `nextallowedat`, exhausted, available with `attemptsleft`); expiry finalization idempotent; `resolve` for evaluation (scope, session ownership, closed, expired, membership, already answered, hints); Finished with `evaluationrequired` (gate and remove wait for the pass); invalid stored blueprint → inactive with reason.
- `tools/check_learning.sh` (server): learner 403 on the blueprint endpoint; every save rejection incl. `pool_insufficient` on activate and allowed inactive; version n+1, unchanged, `version_conflict`; `evaluation.json` statuses through a full cycle (not_available → available → start → in_progress with resume returning the same attempt → answers stored with mode evaluation, `learningsession`, `deferred` and no `iscorrect` → `already_answered`, `hints_not_allowed`, wrong-topic scope → submit result with subtopics → duplicate submit → waiting/available per `retakewaithours` → retake avoids earlier questions → exhausted at `maxattempts`); timer: a 1-minute blueprint expires (checked with a backdated `expiresat`) → `session_expired`, finalized by timer, scored with the answered subset; evaluation answers do not advance Learn or unlock subtopics (existing checks extended); `state.json` `evaluation` block and `finished` with `evaluationrequired`; profile row round-trip; `person.json` fields; audit rows; tutor skill procedural reply for an evaluation answer.
- Flutter (`app-genailabs/test/`): session in evaluation mode (no verdict colouring, no hint, countdown text, submit at the end → result screen), result screen copy (passed / failed with weakest and CTAs), blueprint editor save payload, topic-home evaluation card states.
- `tools/validate_content.py`: reports topics whose active blueprint pool is insufficient (read-only warning).

## Rollout

- Data files: `plugins/testu/data/fields/evaluationblueprint.xml`, `evaluationattempt.xml`, `topicrequirement.xml` (+ `evaluationrequired`), `data/lists/evaluationstrategy.xml`, `difficultymix.xml`; `tutoranswer.mode` accepts `evaluation`; the `webapp/WEB-INF/data` copies via `bin/sync-testu.sh`.
- No migration. No blueprint rows in production → `evaluation.status = not_available` everywhere, `finished` unchanged, no UI shown.
- Old app bundles ignore the new `state.json` block; they never send `mode = evaluation`.
- Web bundles rebuilt from a clean worktree and committed (no-CI rule).

## Known limitations (tracked)

- The client already holds each question's correct option through `tutorial.json` (all modes). The evaluation hides verdicts and the server withholds `iscorrect`, but a determined learner can read the bundle's network data. Hardening = serving question content without the answer key from `next.json`, a platform change already noted in the learning-engine spec.
- Single application node: attempt creation and finalization use the JVM write lock, as every create-only write does today.
- The 24-hour window for untimed attempts is a fixed engine constant (ponytail: make it a catalog setting if an organization asks).

## Out of scope (later sub-projects)

Common-form and weak-area strategies, form versions and approval workflows, per-question review release policy, per-question timers, pause rules and accommodations, renewal periodicity and certificates, evaluation notifications (due / failed), manager notification on failure, required remediation before a retake (the retake rules here are the waiting period and max attempts), role-specific pass thresholds, multiple-correct scoring, readiness statuses.
