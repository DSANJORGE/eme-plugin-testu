# Certifications (renewable topic evaluations)

Date: 2026-09-23. Sub-project 3 of 3 (1 = Job profiles, 2 = Evaluation Mode, both done).
Source of truth: Notion Project Hub → 02 Product Requirements ("Completion vs mastery vs certification"), 04 Admin Platform ("Certifications: evaluation blueprints, forms, results"). Builds on `2026-09-16-job-profiles-design.md` (`topicrequirement`, merge rules, Finished) and `2026-09-16-evaluation-mode-design.md` (blueprints, attempts, `passed` terminal in v1).

Goal: a topic's evaluation becomes a **certification** when a job profile gives it a validity period. Each profile decides how often its people must pass (validity), how well (pass %, overriding the plan's) and how early they may renew (window). The learner sees their certifications, is reminded before expiry and can commit to a day. Administrators see compliance per person and can extend a certificate or mark someone certified, with a reason. The server decides; the apps render.

Decisions taken with the user (2026-09-23): **one topic = one certification** (no bundles); **profile row overrides** the plan's pass % and owns cadence; **renewal window** before expiry, a failed renewal never cancels a valid certificate; **reminders + a scheduled day** in v1, tutor/calendar negotiation later on the same field; admin actions are **extend** and **mark certified**, audited; no revoke.

Product-first rule: one generic model. With no `validitymonths` anywhere, engine, endpoints and apps behave exactly as today.

## Data

`topicrequirement` (profile × topic) gains:

- `validitymonths` (number/long, 0–120, blank = the topic is not a certification for this profile; 0 = certified once, never expires).
- `passpercent` (number/long, 1–100, blank = the plan's `passpercent`).
- `renewalwindowdays` (number/long, 0–365, default 30).
- `evaluationrequired` is superseded: it reads as `validitymonths != blank` for one release (the app stops writing it), then is dropped.
(All numeric fields use datatype `long` because eMe's `number` persists a blank as 0; for these fields, blank means "inherit" or "not set", not "zero".)

Merge across a learner's profiles (same shape as `requiredlevel`): certification required iff any row has `validitymonths`; `validitymonths` = shortest non-blank (0 counts as longest); `passpercent` = highest set, else the plan's; `renewalwindowdays` = longest.

New table `certification` (`data/fields/certification.xml`), one row per learner × topic, id `<user>_<topicid>`. `user` is `type="list" listid="user"` (shared index trap, see evaluation-mode memory).

- `user`, `entitytopic`.
- `passedat` (date), `attemptid`, `scorepercent` (long, blank for manual certifications) — the pass that started the current cycle.
- `validuntil` (date) = `passedat` + merged `validitymonths` at the time of the pass; blank when `validitymonths` = 0.
- `extendeduntil` (date), `extendreason`, `extendedby` — admin extension of the current cycle.
- `manual` (boolean), `manualby`, `manualreason` — certified without an attempt (`passedat` = the date given).
- `scheduledfor` (date) — the learner's commitment for the renewal.
- `reminderssent` (JSON array of stage ids sent in the current cycle).
- `datemodified`.

Audit (`auditevent`, existing helper): `certification.pass` (after = row), `certification.extend`, `certification.manual`, `certification.schedule` (target `certification`), `certification.expire` (written once when a row first crosses its expiry).

## Engine rules (`LearningEngine`, pure unless stated)

Terms, for learner u and a certification topic t (merged rule r):

- *Expiry* = max(`passedat` + merged `validitymonths`, `extendeduntil`), recomputed on every read so profile changes apply; the stored `validuntil` is informational. No expiry when validity is 0 and there is no extension.
- *Window open* = expiry set AND now ≥ expiry − `renewalwindowdays`.
- *Status*, precedence: `not_certified` (no row, or no `passedat`) → `expired` (now > expiry) → `renewal_due` (window open) → `certified`. `manual` rows are a pass dated `passedat`. Reason codes on `not_certified`: `never` | `plan_inactive` (no active blueprint).
- *Day boundary*: expiry and window are compared at end of day in the organization timezone (`testu_timezone` catalog setting), so "expires 2026-11-03" is valid all of that day.

Behaviour:

- **canstart** (replaces "`passed` is terminal"): for a certification topic, an evaluation may start when status ∈ {`not_certified`, `renewal_due`, `expired`}; `certified` reports `evaluation_not_available {status: certified, reason: valid_until, validuntil}`. Retake wait and `maxattempts` still apply; attempts are counted **per cycle** (finalized attempts with `submitted` > `passedat`, or all when no pass). Non-certification topics keep today's rule unchanged.
- **Pass mark**: `scoreEvaluation` takes the effective pass % = merged `passpercent` or the blueprint's. The attempt row stores the value used (`passpercent` on `evaluationattempt`, new field) so results stay explainable after a profile change.
- **On pass** (`submitevaluation`, under the write lock): upsert the `certification` row: `passedat = submitted`, `attemptid`, `scorepercent`, `validuntil` = `passedat` + validity (blank for 0), clear `extendeduntil`, `extendreason`, `scheduledfor`, `reminderssent`; keep `manual = false`. Audited `certification.pass`. A failed attempt leaves the row untouched.
- **Finished(topic)**: the evaluation conjunct becomes "status ∈ {`certified`, `renewal_due`}" for certification topics (due but valid still counts; `expired` does not). Non-certification topics with a blueprint: unchanged (latest finalized attempt passed).
- **Profile changes** are applied on read: a shorter validity can move a row straight to `renewal_due` or `expired`; a profile removed from the learner leaves the row in place (history) and the topic stops being a certification for them. Multiple profiles: the merged rule, one expiry.
- **Reminders** (server, the existing 15-minute `computemastery` event, under the write lock, idempotent): for every learner × certification topic with an expiry, stages `window_open` (first run with the window open), `7d`, `1d`, `expired` (first run past expiry, also writes `certification.expire`), and `scheduled_day` (first run on `scheduledfor`). Stage thresholds are evaluated on calendar days in the organization timezone (window start day, 7 days before, 1 day before, the day after expiry, the scheduled day), not raw millisecond offsets. Each stage is sent once per cycle (append to `reminderssent`) through `learnernotification` + the existing email/push path (`learnernotificationtype` gains `certification`). No reminders while `plan_inactive`. Stages already passed when a row is created (e.g. a manual certification entered 5 days before its expiry) are marked sent without sending, except `expired`.
- **Analytics**: `person.json` required-topic rows add the certification block; `requiredgaps` counts `expired` and `not_certified` certification topics (not `renewal_due`); `evaluationmet` = status ∈ {`certified`, `renewal_due`} for certification topics.

## Endpoints (`services/testu/`, `TestULearningModule` / `TestUUserModule` patterns)

Learner (signed-in user; 401 as today):

- `state.json`: per topic `certification: {status, reason, passedat, validuntil, expiry, windowopens, renewalwindowdays, scheduledfor, passpercent, validitymonths, extended: bool} | null` (null = not a certification for this learner); top level `certifications: [{topic, title, status, expiry, scheduledfor, canstart}]` ordered `expired`, `renewal_due` (soonest expiry first), `not_certified`, `certified`.
- `evaluation.json`, `startevaluation.json`: unchanged shapes; `canstart` / 409 `evaluation_not_available` follow the rules above.
- `submitevaluation.json`: adds `certification` (block above) and `passpercent` (the value used).
- New `schedulecertification.json` (POST `topicid`, `date` = `YYYY-MM-DD` or blank to clear) → `{ok, certification}`. 400 `not_certification_topic` | `bad_date` | `date_past` | `date_after_expiry` (while not yet expired; an expired certification may schedule any day from today on); 404 `unknown_topic`. Audited `certification.schedule`.

Admin (reads `training_view`, writes `training_manage`; every topic also passes the per-topic `manageevaluations` entity permission, so a trainer sees only their topics):

- `learn/certifications.json` (GET; optional `status`, `topicid`, `profile`, `team`) → `{ok, canmanage, counts: {certified, renewal_due, expired, not_certified}, rows: [{user, name, team, topic, topictitle, status, reason, passedat, scorepercent, validuntil, extendeduntil, expiry, manual, scheduledfor, attempts}]}`. One row per learner × certification topic of the learner's merged assignment. Counts are over the filtered rows.
- `learn/extendcertification.json` (POST `user`, `topicid`, `until` = `YYYY-MM-DD`, `reason`) → `{ok, row}`. Requires a row with `passedat`; `until` > current expiry and ≤ today + 12 months; `reason` non-blank. Writes `extendeduntil`, `extendreason`, `extendedby`; clears the `7d` / `1d` / `expired` stages from `reminderssent` so they fire again for the new date. 400 `not_certified_yet` | `bad_date` | `date_not_later` | `date_too_far` | `missing_reason`; 404 `unknown_user` | `unknown_topic`. Audited `certification.extend`.
- `learn/manualcertification.json` (POST `user`, `topicid`, `passedat` = `YYYY-MM-DD` ≤ today, `reason`) → `{ok, row}`. Upserts the row as a pass on that date with `manual = true`, `manualby`, `manualreason`, `attemptid` blank, `scorepercent` blank; same clears as a pass. 400 `bad_date` | `date_future` | `missing_reason` | `not_certification_topic`; 404s as above. Audited `certification.manual`.
- `profiles.json` / `saveprofile.json`: rows carry `validitymonths`, `passpercent`, `renewalwindowdays` (validation: ranges above; `passpercent` blank or 1–100). `evaluationrequired` is still returned (derived) and ignored on save.
- `person.json`: each required-topic row adds `certification` (the learner block).

## Admin console (`app-genailabs/lib/admin/`)

- Rail: **Evaluaciones → Certificaciones** (`#/certifications`; `#/evaluations` redirects). Two tabs:
  - **Planes**: today's `admin_evaluations.dart` table and blueprint editor, unchanged.
  - **Personas**: the compliance table from `certifications.json`. Header counts (certified / due / expired / not certified). Filters status, topic, profile, team. Columns: person, team, topic, status pill (`Vigente` · `Vence en N d` · `Vencida hace N d` · `Sin certificar`), last score, expiry (with an "extended" glyph and the reason on hover), scheduled day, attempts this cycle. Row actions (only with `training_manage`): **Extender…** (date + reason sheet) and **Certificar manualmente…** (date + reason sheet). Rows for `plan_inactive` topics show a warning glyph linking to the plan.
- Profile editor (`admin_profiles.dart`): the "Evaluation: Required" column becomes **Validez** (months, blank = no certification), **Aprobar %** (blank = plan's), **Ventana** (days, default 30). Rows with the legacy `evaluationrequired = true` and blank validity show a hint "Required evaluation without validity: set a validity to make it a certification".
- Person page (`admin_person.dart`): required-topic rows show the certification pill (no actions in v1; Extend and Mark-certified live only on the Certificaciones › Personas table).
- Models/API (`admin_models.dart`, `admin_api.dart`): `CertificationRow`, `ProfileRow.validityMonths / passPercent / renewalWindowDays`; `certifications(filters)`, `extendCertification(...)`, `manualCertification(...)`.

## Learner app (`app-genailabs/lib/testu/`)

- `testu_client.dart`: `TopicState.certification` (`CertificationState`: status, reason, passedAt, validUntil, expiry, windowOpens, scheduledFor, passPercent, validityMonths, extended), state-level `certifications`; `scheduleCertification(topicId, date)`.
- New tab **Certificaciones** (appended after Dashboard, index 4), shown only when `certifications` is non-empty, so learners without certification topics see no change. Groups: **Por vencer** (`renewal_due`, soonest first), **Vigentes** (`certified`), **Pendientes** (`expired`, then `not_certified`). Row: topic, status line ("Vence el 3 nov · en 12 días", "Vigente hasta …", "Vencida hace 3 días", "Sin certificar"), CTA **Rendir ahora** when `canstart`, else **Programar** (date picker → `schedulecertification.json`; shows "Programada: jueves 30") or nothing when `certified`. Tapping a row opens the topic's existing evaluation sheet.
- Today: an amber card for the most urgent `expired` / `renewal_due` certification (prototype card): title "El certificado de <topic> vence en N días" / "venció hace N días", CTA as above. Absent when none.
- Dashboard: the stubbed "Certificados" line becomes live: "N vigentes · M por vencer" (amber when M > 0 or any expired).
- Copy stays Spanish for product text, both languages through `L()` as today.

## Errors and edge cases

- `validitymonths` set but no active blueprint: `not_certified` / `plan_inactive`; admin table warns; no reminders; `canstart` false with 409 `evaluation_not_available {reason: plan_inactive}`.
- A learner certified under profile A, then moved to profile B with a longer validity: expiry is recomputed from `passedat` with the new merged rule on read (`validuntil` on the row is informational; the engine recomputes from `passedat` + merged validity). Extensions are absolute dates and survive.
- Extension shorter than or equal to the current expiry: 400 `date_not_later`.
- Manual certification for a learner with an in-progress attempt: allowed; the attempt continues and a later pass simply restarts the cycle.
- Validity 0 (never expires): no window, no reminders, `renewal_due` never; `canstart` false once certified.
- Timezone: `testu_timezone`; all dates in replies are `YYYY-MM-DD` plus `now` for the app's clock offset.

## Testing

- `tools/LearningEngineCheck.java` (pure, fixed inputs): status precedence with day boundaries; merge of validity / pass % / window across profiles; attempts per cycle; `canstart` per status; Finished with `renewal_due` and `expired`; reminder stage selection and dedup; recompute of expiry after a profile change; validity 0.
- `tools/check_certification.sh` (server, local only, admin creds from the environment as the other check scripts): create a profile with validity, assign a test user, start and pass an evaluation with the override pass %, assert the row and `state.json`; schedule a day; extend; manual certification; force `passedat` back via the data API to cross window and expiry and assert statuses, reminders (`learnernotification` rows) and the `certification.expire` audit; clean up its rows.
- Flutter (`app-genailabs/test/`): one widget test for the learner tab grouping and CTAs; one for the admin Personas table action payloads.

## Migration and rollout

- New fields blank, new table empty: no migration. Live Minsur is unchanged until a profile row gets a validity.
- `permissionentityassigned` rows for `manageevaluations` (per-topic trainer scope) must exist on the target server before trainers use the Personas tab; this includes an explicit grant for administrators (rows do not auto-load on an existing table; the local DB needed an explicit administrators grant for topics to appear).
- Data files: `plugins/testu/data/fields/certification.xml`, `topicrequirement.xml`, `evaluationattempt.xml` (`passpercent`), `lists/learnernotificationtype.xml`, and their `webapp/WEB-INF/data` copies as the deploy expects.

## Out of scope (v1)

Calendar integration and tutor-negotiated scheduling (later, on `scheduledfor`); multi-topic certifications; hand-picked "common form" evaluations; revoking a certificate; manager escalation; certificate PDFs; per-learner overrides of validity or pass %; tutor context line (the learner context is built by eMe core's chat history builder — needs a hook there or a client-sent context field; the Today card and Certificaciones tab carry the message).
