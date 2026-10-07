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
  - `at_risk` — `Forecast.of(history, requiredmin / 100.0)` ETA after `daysleft`, or status `notonpace`; with `insufficient` history (< 7 days): sessions needed at 4 points each > `daysleft` / 2. History = the topic's mastery percent per local day over the last 28 days, replayed from the learner's attempts (pure, no storage read).
  - `on_track` — otherwise with a deadline.
  - `pace` — not met, no deadline.

Goal topic (decided 2026-10-05): candidates = required topics of the assignment that are not locked (`requiresprevious`) and not `done`. Order: overdue (earliest deadline), ready, at_risk / on_track (earliest deadline), pace (the order `recommend` uses: below required level first, then lowest %, ties by assignment position). None → `no_goal`. Subtopic: `recommend` restricted to the goal topic, unchanged rules (first unanswered unlocked question in sequence → learn on its section; learn complete → improve on the weakest section); ready → no session, the action is book/schedule. With no deadlines anywhere the goal equals today's Continue pick, so behaviour is unchanged. `othergoals` = count of remaining non-done required topics with a deadline.

Week plan: `sessionsneeded` this ISO week (learner zone) = clamp(ceil(gap / expected gain per session) spread over remaining weeks to deadline, 1, 5); expected gain per session = 4 points (ponytail: flat constant; org median from mastery history once pilots have 6+ weeks of data). `sessionsdone` = distinct days this week with ≥ 1 learn/improve/dailychallenge answer on the goal topic. `pace` goals: 3 sessions/week.

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
- Today (`testu_shell.dart`), decided 2026-10-05: Daily Challenge stays first. `_MissionCard` takes the place of `_ContinueHero` and the live certification card (`_certCard`) when status ≠ `no_goal`; with `no_goal` the Continue hero renders as today. Card content:
  - Header "Tu objetivo: <Nivel> en <Tema> · antes del <fecha>" (no date for `pace`).
  - Status label: Listo para evaluar / En camino / En riesgo — N sesiones esta semana / Vencido / A tu ritmo.
  - Bar "<Banda> · NN% → <Requerido>"; week dots "Esta semana: X de N sesiones". No streaks, no confetti.
  - Primary button = first non-remind action; text button "Más tarde" → 2h / esta noche / mañana sheet → `remind.json`.
  - "+N objetivos más" → Topics tab.
  - Certification goals render in the same card (eyebrow Certificación, action Programar renovación). The Certifications tab is unchanged; a certification that is not the goal still shows there and in Topics.
- Topics (`testu_topics.dart`): rows with a deadline show "Vence <fecha>", subtle tag for at_risk/overdue; topic home required line adds "· antes del <fecha>".
- Notifications: type `mission` routes to Today (`testu_notifications.dart`).
- Spanish copy in the app; tutor name from org config.

## Admin console (`app-genailabs/lib/admin/`)

- Overview (`admin_overview.dart`): "Sugerencias de <tutor>" card at the top, from `coach.json`. Each suggestion: sentence ("4 personas de Planta en riesgo en Bloqueo · vence 30 oct"), buttons Enviar recordatorio / Cambiar fecha (date picker, applies to the listed users, deselectable) / Ver personas (people list filtered) / Descartar. Result toast reports `skipped`.
- Profile editor (`admin_profiles.dart`, console Personas → Perfiles de puesto): new optional "Plazo (días)" column per row → `withindays`, beside the existing required level / validity columns; blank (default) = no deadline. Not on the Certificaciones → Planes screen: that screen configures the evaluation itself, the plazo belongs to the role requirement.
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

## Rollout (as built)

- Field XML, one per table:
  - `catalog` new tables (`plugins/catalog/html/data/fields/`): `learnertarget` (`user`, `entitytopic`, `duedate`, `source`, `createdby`, `createdon`) and `coachdismissal` (`user`, `suggestionkey` — renamed from `key`, `indextype="not_analyzed"`; `until`).
  - `topicrequirement`: new `withindays` field (existing table).
  - `learnernotification`: new `remindat`, `pushedat`, `status` fields (`status` is `indextype="not_analyzed"`, existing table).
  - `user.lastmissionstatus` is declared in the **site** webapp (`webapp/WEB-INF/data/system/fields/user.xml`), not a catalog field XML — users are XML-backed (system catalog), so no reindex applies to it.
- List entry: `learnernotificationtype` gets `mission` ("Objetivo", `plugins/catalog/html/data/lists/learnernotificationtype.xml`). List XML loads only at table creation, so on an existing server add the row by hand (or via the list editor); without it the notification type label is blank (cosmetic).
- Per server, after the field XML deploys: a datamanager restore (`searcher.resetMappings`, via `/site/find/views/settings/lists/datamanager/list/restore.html?searchtype=<table>`) then a reindex for `topicrequirement` and `learnernotification` — a plain reindex does not push a changed mapping onto an existing table (CLAUDE.md §6). `learnertarget` and `coachdismissal` are brand-new tables: they get their mapping on first use, so no restore/reindex is needed for them.
- No new permissions. Coach reads use the Overview tab permissions (`coach.xconf`, any of `resumen_/actividad_/dominio_/prevision_admin`) plus `loadScope`; coach writes (`nudge`, `setdue`) need `training_manage` (`canManageProgression`, no per-topic check); `dismiss` (a per-manager view preference) needs only the coach read permission. The console hides the write buttons without `training_manage`, so team managers see suggestions read-only until a server grants it (final review I1). Nudges are gated by the existing Daily Challenge email opt-in (`testu_dailychallengeemail` / `testu_dailychallengeemail_only` + `EMAIL_PERMISSION.mayReceive`), not a new permission.
- `bin/sync-testu.sh` MAP: already covers `html/services/testu/learn` (directory entry — `mission.xconf/json`, `remind.xconf/json`, `missionnudges.xconf/json` included), `html/services/testu/analytics/{coach,coachaction}.{xconf,json}`, and the `html/ai/default/calls/{analytics_ask,chat_tutor_usercomment}.json` templates that this feature modified; `--check` confirmed no drift and no missing entries (2026-10-06).
- The `ai/default/calls` templates (`analytics_ask.json`, `chat_tutor_usercomment.json`) must be deployed along with the rest — they are plain data files, not code, so a server misses the new coach-action/offer prompt rules until `bin/sync-testu.sh` runs there.
- Finder main commits (`dc75ed19c`, `1c95143e7`, `bfb084602` — offered-id filtered `[[do]]` action lines) must be pushed and deployed along with the rest; without them the app shows no chat action buttons (degrades gracefully).
- Rebuild and commit learner + console web bundles (deferred to task 15b).

- Weekly objective (amendment 2026-10-06):
  - Field XML: `learnernotification.kind` and `usageevent.notification` (existing tables; catalog plugin `html/data/fields/` and the site mirror `webapp/WEB-INF/data/site/catalog/fields/`, kept identical). Per server, right after the deploy restart and before the first 15-minute sweep: datamanager restore of `learnernotification` and `usageevent` (a restart alone does not add fields to an existing mapping). The restore deletes the two site mirror XML files from the working tree: `git checkout -- webapp/WEB-INF/data/site/catalog/fields/{learnernotification,usageevent}.xml` afterwards. Rows survive (no reindex needed for added fields). New table `missionpush` gets its mapping on first use.
  - No new list values, permissions, endpoints or events: `remind.json when=cancel`, the 18:00 unfinished push (inside the existing 15-minute `missionNudges`) and `engagement.json missionpushes` ride on existing files; `bin/sync-testu.sh --check` clean.
  - `push_open` taps need the new app build; older apps send none (tapped stays 0). `missionpush` rows are written even when FCM is off, so "sent" = issued by the server. On staging, open Actividad once with no `missionpush` rows yet to confirm `engagement.json` loads.
  - Learner and console web bundles: rebuilt in site `3977cc31` (from app `b322add`, which includes this feature).

## Amendment 2026-10-06: weekly objective

Approved by Diego from the clickable prototype (artifact "Objetivo de la semana", v1) on 2026-10-06. Supersedes the card bullets under *Learner app* and the `sessionsdone` rule under *Planner → Week plan* where they differ.

### Card ("Tu objetivo de la semana")

- Eyebrow `TU OBJETIVO DE LA SEMANA` (certification goals keep `CERTIFICACIÓN`), status pill on the right. Title is only the goal: `<Nivel> en <Tema>` (`pace`: `Practicar <Tema>`); the word "objetivo" is never repeated in the title. Line under it: `Antes del <d MMM> · quedan N días` / `Venció el <d MMM>` / `Sin fecha límite`.
- Layout follows `_ContinueHero`: the goal topic's cover (`_liveCoverUrl`, looked up from the topics list the app already loads; brand block when none), a level bar from the current percent with a marker at the required minimum (`<Banda> · NN%` … `<Requerido> · MM%`), one dot per needed session this week (filled = counted, ring = today in progress), then the actions.
- Pills: En riesgo (amber) · Vas a tiempo (green) · Plazo vencido (red) · Listo para evaluar (green) · A tu ritmo (neutral) · Semana cumplida (green, see below).
- **Week done**: when `sessionsdone >= sessionsneeded` and status is not `ready`/`overdue`, the card goes quiet until Monday: pill Semana cumplida, line `N de N · nos vemos el lunes`, single quiet button "Practicar igual" (the `start_session` action). Practice still counts toward the level.
- **Reminder set**: when `mission.json` has a pending `reminder`, the card collapses to one line (cover thumb, "Te lo recuerdo: <cuándo>", goal + date) with **Deshacer** (cancels it) and **Ver** (expands for this view only). It comes back in full when the reminder fires (the row is then pushed, no longer pending).
- **Unfinished session**: when `week.todayanswers` is between 1 and 4 and today is not yet counted, the card shows "Sesión de hoy a medias: N de 5 preguntas. Con 5 ya cuenta para la semana." and the primary button reads "Retomar sesión" (same `start_session` action).

### Planner

- `DAY_MIN_ANSWERS = 5`: a day counts toward `sessionsdone` only when it has ≥ 5 learning-mode answers (learn / improve / dailychallenge) on the goal topic's questions. Weeks stay Monday–Sunday in the learner zone.
- `week` gains `todayanswers` (learning-mode answers on the goal topic today, learner zone) and `todaycounted` (todayanswers ≥ 5).

### Endpoints

- `mission.json` adds `reminder: {remindat, topic}` when the learner has a pending mission reminder (`pushedat` null, `remindat` in the future), else null.
- `remind.json` accepts `when=cancel`: deletes the pending reminder if not yet pushed (200 `{ok, cancelled}`); `postRemind` returns `remindat` so the app can show the time at once.

### Nudges

- **Unfinished-session push**, decided 2026-10-06: at 18:00 learner zone (first sweep at or after 18:00, still inside quiet hours), once per learner per day, when the goal's `todayanswers` is 1–4, no mission reminder is pending, and `mayReceive`. Text: "Te faltan N preguntas para que hoy cuente en tu objetivo de <Tema>." Row id `<uid>_mission_unfinished_<yyyyMMdd>`.
- Every mission push carries a `kind`: `remind` · `unfinished` · `ready` · `at_risk` · `overdue` · `coach` (manager nudge). `kind` is a new `learnernotification` field and is added to the push `data` payload.

### Push effectiveness (new)

- Each mission push also writes one row to a new table `missionpush` (`user`, `kind`, `entitytopic`, `sentat`, `notification`), id `<notification id>_<yyyyMMddHHmm>`, so history survives the reused notification rows.
- The app records a tap: a new usage type `push_open` (`campaign` = notification type, `source` = kind, `entitytopic`, plus the notification id) sent when a push is opened (`testu_push.dart` `_openPush`).
- Analytics (`engagement.json`, new `missionpushes`): per kind over the selected period — `sent`, `tapped` (a `push_open` for that user and kind within 24 h after `sentat`), `practised` (≥ 1 learning answer on that topic within 24 h after `sentat`), `counted` (≥ 5 such answers on one local day within that window); rates over `sent`. Counts only, no names, no chat content.
- Console, Actividad: card "Recordatorios del objetivo" next to "Where the Daily Challenge is opened": one row per kind, Sent · Tapped % · Practised within 24 h % · Day counted %.

### Weekly summary email (existing Friday 18:00 emails, no new email)

- Learner: a section `TU OBJETIVO DE LA SEMANA` after "Tu progreso" when the learner has a goal: cover-less row with goal, `X de N sesiones esta semana`, `<Banda> · NN%`, status and deadline, plus one plain suggestion line. Absent for `no_goal`.
- Manager (admin summary): a section `OBJETIVOS CON PLAZO` within the manager's scope: per topic, counts of at risk / overdue / ready not booked (same data as `coach.json`), and a line pointing to the Coach card. Counts only; names stay in the console. Absent when there is nothing to report.
- Same switches, allowlist and once-per-Friday rule as today.
