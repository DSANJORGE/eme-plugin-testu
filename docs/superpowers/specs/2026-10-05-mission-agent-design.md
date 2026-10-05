# Mission agent (goal-driven learner plan, chat action cards, manager coach)

Date: 2026-10-05. Sub-project 4 (1 = Job profiles, 2 = Evaluation Mode, 3 = Certifications).
Builds on `2026-09-14-learning-engine.md` (`recommend`, required level), `2026-09-16-job-profiles-design.md` (`topicrequirement`, `setprofiles.json`), `2026-09-16-evaluation-mode-design.md` (`evaluationStatus`, `canstart`), `2026-09-23-certifications-design.md` (expiry, renewal window, `scheduledfor`).

Goal: make the tutor an agent, not a chatbot. The app knows each learner's goal (required level per role–topic, by a date), tracks the gap, plans the week, and proposes concrete one-tap actions — on the Today screen, under tutor replies, and in nudges. Managers get the same planner over their teams as approve-to-act suggestions. Chat stays one input, not the product.

Principles:

- **Server decides, LLM words.** A pure planner computes goal, status, week plan and the set of valid actions. The LLM may only choose among actions the server offered; it never invents actions, labels or eligibility.
- **Agent proposes, human taps.** Nothing that commits a learner (booking, reminder time) or a team (nudge, due date) happens without a tap. Plans and nudge timing adjust silently.
- **No new selection logic.** Sessions are `next.json` calls as today; the planner reuses `recommend`, `topicState`, `evaluationStatus`, certification status and `Forecast`.
- Product-first rule applies: one generic model, orgs configure `withindays`. With no `withindays`, no manual targets and no certifications, every learner gets `no_goal` and the app looks exactly as today.

## Data

- `topicrequirement` (job profile row) new field `withindays` (`datatype="long"`, nullable — never `number`, blank must stay blank). Meaning: complete within N days of the topic being assigned to the learner.
- New table `learnertarget`: one row per learner × topic. Fields `user` (`type="list" listid="user"`), `entitytopic`, `duedate` (day value, midnight-UTC via `LearningEngine.parseYmd`, compared with `endOfDay(day, zone)` like certification days), `source` (`profile` | `manual`), `createdby`, `createdon`. Row id `<user>_<topic>`.
  - `setprofiles.json`: for each topic of the learner's assignment whose merged row has `withindays` and that has no `learnertarget` row, write one with `duedate` = today (learner zone) + `withindays`, `source = profile`. Merged `withindays` across profiles = smallest.
  - `saveprofile.json`: when a row's `withindays` goes from blank to a value, backfill rows for current members lacking one (today + N). Changing an existing value never moves written dates (a learner-visible deadline does not shift silently); managers move dates via `setdue`.
  - Manual rows (`source = manual`) are never overwritten by profile writes.
  - Removed or unassigned topics: rows stay (evidence) but are ignored by the planner.
  - Audit: `learnertarget.set` (target `user`, before/after `duedate`, `source`).
- User new field `lastmissionstatus` (text, `<topic>:<status>`) — last status shown proactively, so each change is announced once.
- `learnernotification` new fields `remindat` (instant, nullable) and type `mission` (payload `topic`, optional `action`).
- Coach dismissals: table `coachdismissal` (`user` = manager, `key` = suggestion key, `until` instant).

## Planner (`MissionPlanner`, pure, beside `LearningEngine`)

Inputs per learner: required topics (assignment order, removed excluded), their `topicState`, `tutormastery` history, `learnertarget` rows, certification rows, org zone/learner zone, `now`.

Effective deadline per topic = earliest of: `learnertarget.duedate`; certification expiry (`expiryOf`) when the topic has a certification row; outside the renewal window the topic is `done`, so the far expiry never drives a plan. None → no deadline.

Per topic:

- `gap` = required band minimum − current mastery percent (≤ 0 when met); `daysleft` to deadline (null without one).
- `status`:
  - `ready` — `meetsrequirement` AND `evaluation.canstart` (or certification renewal schedulable).
  - `done` — meets requirement and nothing to book (passed / certified outside window). Not a goal.
  - `overdue` — deadline passed and not `done`.
  - `at_risk` — `Forecast.of(history, requiredmin)` ETA after deadline, or status `notonpace`, or fewer than 2 history points and `daysleft` < sessions needed.
  - `on_track` — otherwise with a deadline.
  - `pace` — not met, no deadline.

Goal = first of: overdue (earliest deadline), ready, at_risk / on_track (earliest deadline), pace (`recommend` order). None → `no_goal`. `othergoals` = count of remaining non-done required topics with a deadline.

Week plan: `sessionsneeded` this ISO week (learner zone) = clamp(ceil(gap / expected gain per session) spread over remaining weeks to deadline, 1, 5); expected gain per session = org median from `tutormastery` deltas, default 4 points (ponytail: flat default; per-learner rate when history allows). `sessionsdone` = distinct days this week with ≥ 1 learn/improve/dailychallenge answer on the goal topic. `pace` goals: 3 sessions/week.

Actions (`actions[]`, each `{id, type, topic, params, labelkey}`), only when currently valid:

- `start_session` — mode + section exactly as `recommend` returns for the goal topic (`learn` or `improve`); not when locked.
- `book_evaluation` — `evaluation.canstart`.
- `schedule_certification` — certification `renewal_due` or `expired` and not yet scheduled.
- `remind_later` — always, params `options: [2h, tonight, tomorrow]` (tonight = 19:00, tomorrow = 09:00, learner zone).

The planner never writes. It is called by `mission.json`, by the tutor action provider, by the nudge job and by the coach.

## Endpoints

Learner (`services/testu/learn/`, `TestULearningModule`):

- `mission.json` (GET): `{ok, status, goal: {topic, topictitle, requiredlevel, band, masterypercent, gap, deadline, deadlinesource, daysleft}, week: {sessionsneeded, sessionsdone}, othergoals, actions, announce, now}`. `announce` = `{status, topic}` when `lastmissionstatus` differs from the current goal status, else null; the app calls `mission.json?ack=1` after showing it, which writes `lastmissionstatus`.
- `remind.json` (POST): `topic`, `when` (`2h` | `tonight` | `tomorrow`). Writes a `learnernotification` type `mission`, `remindat` set, unread, not pushed. Replaces an existing pending mission reminder for the same learner. 400 on unknown `when`.
- Action execution uses existing endpoints, which already re-validate: `next.json` (start session), the evaluation start flow (`evaluation.json` → `startevaluation.json`), `schedulecertification.json`.

Manager (`services/testu/analytics/`, `TestUAnalyticsModule`, permission `training_manage` for writes, same scoping as `overview.json` for reads):

- `coach.json` (GET, optional `team`): suggestions over the manager's visible learners, grouped by kind × topic: `[{key, kind, topic, topictitle, deadline, count, users: [{id, name, status, masterypercent, daysleft}]}]`. Kinds: `at_risk`, `ready_not_booked` (status ready, no `scheduledfor`/attempt), `overdue`, `cert_expiring` (expiry within 14 days, not scheduled). Excludes active dismissals. Uses mastery/readiness only — never chat content.
- `coachaction.json` (POST): `action` = `nudge` (`topic`, `users[]`) | `setdue` (`topic`, `users[]`, `duedate`) | `dismiss` (`key`). `nudge` writes a `mission` notification per user and pushes, skipping users nudged on the same topic within 3 days (returns `skipped`). `setdue` upserts `learnertarget` with `source = manual`. `dismiss` writes `coachdismissal` until now + 7 days. All audited (`coach.nudge`, `learnertarget.set`, `coach.dismiss`). 403 without `training_manage`; 403 on any user outside the manager's scope.
- `ask.json`: current `coach.json` suggestions (counts and topic names only) are added to `facts`, so Ask-IRIS answers "¿qué hago esta semana?" from the same data.

## Nudges

- Existing 15-minute `certificationreminders` event also runs `missionNudges` (no new event file):
  - Delivers `mission` notifications whose `remindat` ≤ now and not pushed (push + bell).
  - Status-change push: when a learner's goal status moves to `ready`, `at_risk` or `overdue` vs the last pushed one (stored on the notification row), one push. `at_risk` at most once per 7 days per topic.
  - Gated by existing `mayReceive`. Quiet hours: pushes only between 08:00 and 20:00 learner zone; otherwise deferred to 08:00.
- Daily Challenge email (09:00, existing) gets one mission line + button when goal status is not `no_goal`/`pace`. No new email.
- Daily Challenge `inputs.flags.deadlines` stays false: dc-v0 selection is unchanged (the spec reserves deadline-aware selection for a new algorithm version).

## Tutor chat action cards

Transport (no change to `eme-app-package`):

- Testu registers a bean `testuMissionActions` exposing `actionsFor(user, topic?)` → planner actions. `AdaptiveTutorialUserCommentSkill` (finder) looks it up by name; absent bean ⇒ behaviour exactly as today. No compile-time finder → testu dependency.
- The skill lists offered actions in the prompt as `a1: book_evaluation · Fatiga`, etc. (start_session, book_evaluation, schedule_certification, remind_later; not open_source — the source block already does that). `chat_tutor_usercomment.json` schema gains `actions` (array of ids, max 2).
- The skill drops ids not offered, then appends one line per action to `message`: `[[do <type> topic=<id> mode=<m> section=<id>]]`. Server-written, so stored history carries the buttons.
- Must land on finder **main** (deploy takes main), not `llm-routing`.

App:

- `splitCite` (`testu_live.dart`) parses `[[do …]]` lines into `Cite.actions` and strips them from text.
- `SullyMessage` renders actions as buttons under the bubble, styled like `TestuSourceBlock`. Labels are app strings per type + topic title, tutor name from org config. Never LLM text.
- Tap executes via the endpoints above. A rejection (409/403, e.g. stale button in history) shows "Ya no disponible" and refreshes `mission.json`.
- Proactive turn: opening the tutor tab with a non-null `announce` inserts one local tutor message from a fixed template (no LLM call) with the matching action buttons, then acks.

## Learner app (`app-genailabs/lib/testu/`)

- `testu_client.dart`: `MissionState` model + `mission()`, `remind()`.
- Today (`testu_shell.dart`): `_MissionCard` first in the list when status ≠ `no_goal`:
  - Header "Tu objetivo: <Nivel> en <Tema> · antes del <fecha>" (no date for `pace`).
  - Status label: Listo para evaluar / En camino / En riesgo — N sesiones esta semana / Vencido / A tu ritmo.
  - Bar "<Banda> · NN% → <Requerido>"; week dots "Esta semana: X de N sesiones". No streaks, no confetti.
  - Primary button = first non-remind action; text button "Más tarde" → 2h / esta noche / mañana sheet → `remind.json`.
  - "+N objetivos más" → Topics tab.
  - When the goal comes from a certification, `_MissionCard` replaces `_CertificationCard` on Today (Certifications tab unchanged).
- Topics (`testu_topics.dart`): rows with a deadline show "Vence <fecha>", subtle tag for at_risk/overdue; topic home required line adds "· antes del <fecha>".
- Notifications: type `mission` routes to Today (`testu_notifications.dart`).
- Spanish copy in the app; tutor name from org config.

## Admin console (`app-genailabs/lib/admin/`)

- Overview (`admin_overview.dart`): "Sugerencias de <tutor>" card at the top, from `coach.json`. Each suggestion: sentence ("4 personas de Planta en riesgo en Bloqueo · vence 30 oct"), buttons Enviar recordatorio / Cambiar fecha (date picker, applies to the listed users, deselectable) / Ver personas (people list filtered) / Descartar. Result toast reports `skipped`.
- Profile editor (`admin_profiles.dart`): "Plazo (días)" column per row → `withindays`.
- Person page: per-topic deadline + source, editable (→ `setdue`).
- Ask-IRIS (`admin_iris.dart`): answers may carry `[[do nudge …]]` / `[[do setdue …]]`, rendered with the same buttons as the Overview card (confirmation step before executing).

## Privacy

Coach suggestions and nudges use mastery, readiness and deadlines — data managers already see per person. Chat content is never an input to the planner or the coach. Tutor chats stay anonymous (existing promise); question-derived insights remain aggregate with k ≥ 3 and are out of scope here.

## Testing

`tools/check_mission.sh` (local, cleans its rows), asserting:

- `no_goal` with no targets/certifications; `pace` without deadline.
- `withindays` on `setprofiles` writes a `profile` row; changing `withindays` does not move it; manual row survives profile writes.
- Certification expiry earlier than target becomes the deadline.
- `ready` only when `canstart`; `book_evaluation` absent otherwise.
- `at_risk` / `overdue` transitions with seeded mastery history; `announce` once, cleared by `ack=1`.
- `remind.json` replaces the pending reminder; nudge job delivers at `remindat`.
- `coach.json` scoping (manager without the team sees nothing); `nudge` 3-day skip; `dismiss` hides for 7 days; audit rows written.
- Skill: offered-ids filter (an invented id is dropped), absent bean ⇒ no `[[do]]` lines.

Unit tests for `MissionPlanner` status/goal ordering with fixed `now` and zone (Lima, UTC-5 day boundaries).

## Out of scope

Calendar export; agent auto-booking; LLM tool-calling agent; content/gap agent and manual-generation (slice B); deadline-aware Daily Challenge selection; per-learner session-gain model; desktop-specific layouts.

## Rollout

- Fields: `topicrequirement.withindays`, new tables `learnertarget`, `coachdismissal`, user `lastmissionstatus`, `learnernotification.remindat` → field XML + reindex where the table exists (see CLAUDE.md §6).
- No new permissions (`training_manage` reused).
- Finder skill change merged to finder main before deploy; without it the app simply shows no chat action buttons.
- Rebuild and commit learner + console web bundles.
