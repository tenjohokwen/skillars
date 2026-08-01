# Skill Load Units (SLU)

## 1. What SLU Is

**Skill Load Units (SLU)** are Skillars' internal, normalized metric for a player's
**developmental exposure** to a specific skill. They answer the question *"how much
targeted work has this player actually done on skill X?"* — automatically, without a
coach ever having to count reps or log minutes by hand.

SLU is deliberately **not**:

- A measure of player **ability** or talent (that's the separate Skills Radar / Big
  Test system — a 1–100 coach-entered score).
- A measure of physical fatigue, effort, or exertion. SLU is **not** related to
  sports-science concepts like RPE (rate of perceived exertion) or physical training
  load — those don't exist anywhere in this codebase. SLU is a Skillars-original,
  drill-content-based metric, not a fitness/conditioning one.

> "SLUs represent estimated developmental exposure for a specific skill... The SLU
> formula is internal and not exposed to users."
> — `requirements/skillars/sessionBuilder.md`, FR-DEV-002 (PRD)

SLU is the foundation of Epic 5, "Player Development Intelligence": weekly exposure
dashboards, neglected-skill detection, coach-defined targets, cross-coach contribution
breakdowns, and correlation with Skills Radar trends all consume SLU data.

### A note on terminology: "session" is ambiguous — read carefully

Product docs (`requirements/skillars/sessionBuilder.md`, the PRD's `FR-DEV-*` /
`FR-BKG-*` requirements) use the word **"session"** to mean the real-world, coach-player
training appointment — e.g. "session completion behavior," "upon session completion."
This is everyday coaching language ("run a session," "the session went well"), and
these documents never distinguish it from anything else.

The **codebase**, however, has two genuinely separate entities that both get called
"session" in casual conversation:

- **`Booking`** (`platform.booking`) — the scheduling/appointment entity. Its
  `COMPLETED` status (reached via wrap-up + confirmation) is what the PRD's "session
  completion" language is actually describing, and it's what triggers SLU (see §4).
- **`Session`** (`platform.session`, the "session plan") — the drill/block plan a
  coach builds in Session Builder. It has its own `status` column (`DRAFT` / `SAVED` /
  `COMPLETED` at the DB schema level) that is **easy to confuse with "session
  completion" but is not what the PRD means, and is not what triggers SLU.**

In fact, **`Session.status` can never reach `"COMPLETED"` anywhere in the current
code** — the only endpoint that updates it (`SessionBuilderResource` →
`SessionPlanService.updateSession`) validates the incoming status against
`@Pattern(regexp = "DRAFT|SAVED")`, so `COMPLETED` is rejected before it ever reaches
the service. A plan is created `DRAFT`, a coach can move it to `SAVED`, and it simply
stays `SAVED` — that's the state SLU calculation expects to find. `COMPLETED` is a
schema-level reserved value (`V43__session_plans.sql` check constraint) with no live
code path, likely a leftover from an earlier design.

(The internal architecture doc even shows this collision causing a real bug in the
design itself: one section correctly ties SLU to `BookingCompletedEvent` from
`platform.booking`; another describes a `platform.session`-published
`SessionCompletedEvent` that **does not exist anywhere in the code** — an artifact of
the same "session" ambiguity, since superseded by the actually-implemented
`Booking`-driven trigger described in §4.)

**Bottom line:** whenever you see "session completion" in a requirements doc, read it
as *"the booking/appointment finished"* — never as *"the `Session` entity's `status`
became `COMPLETED`,"* because that can't happen.

## 2. Why It Exists

Coaches running sessions have no time or appetite for administrative tracking. Instead
of asking a coach to log "20 reps of first touch drills" after a session, Skillars
infers that exposure automatically from the **drill metadata already attached to the
session plan** (repetition density, skill weighting, intensity, pressure, and match
realism) combined with **how long each drill actually ran**. The result is a
consistent, comparable, auto-generated number per skill per session — with zero manual
input from the coach.

## 3. The Formula

Implemented in
[`SluFormula.java`](../src/main/java/com/softropic/skillars/platform/development/service/SluFormula.java),
computed **per skill, per drill**:

```
slu = repDensity × weight × (intensity × intensityScale)
                            × (pressureLevel × pressureScale)
                            × (matchRealism × matchRealismScale)
                            × durationMinutes
```

| Term | Source | Meaning |
|---|---|---|
| `repDensity` | `Drill.metadata.repDensity` | how repetition-dense the drill is |
| `weight` | `Drill.metadata.skillWeighting[skillCode]` | how strongly this drill trains this particular skill (a drill can weight multiple skills at once) |
| `intensity` | `Drill.metadata.intensity` | physical/technical intensity of the drill |
| `pressureLevel` | `Drill.metadata.pressureLevel` | defensive/opponent pressure simulated |
| `matchRealism` | `Drill.metadata.matchRealism` | how closely the drill resembles match conditions |
| `durationMinutes` | allocated time for the drill within its session block | `blockDuration ÷ drillsInBlock` (see §4) |
| `intensityScale`, `pressureScale`, `matchRealismScale` | `ConfigService` keys `slu.intensity.scale`, `slu.pressure.scale`, `slu.matchRealism.scale` | tunable global scaling factors (seeded to `0.10` each), adjustable via the admin config panel without a code change |

Only skills with a positive computed SLU are recorded — zero or negative contributions
are dropped. Values are stored as `NUMERIC(10,4)` (`BigDecimal`, `HALF_UP` rounding to
4 decimal places).

Because a drill's `skillWeighting` map can contain several skill codes, **one drill
produces SLU contributions for multiple skills simultaneously** — e.g. a 1v1 dribbling
drill under pressure might contribute to `DRI` (Dribbling), `1V1` (One vs One), and
`PHY` (Physicality) all at once, each with its own weight.

## 4. When and How SLU Is Calculated

### Short answer

SLU is calculated when the **booking (the appointment) is marked completed** — not
when the coach finishes building the session plan, and not at any point during the
actual training session itself. Concretely, that's one of three moments:

1. **Right after the coach submits the wrap-up form** at pickup, with the parent
   present (`mode=LIVE`) — calculated synchronously, moments after the appointment ends.
2. **When the parent later confirms** an async wrap-up (`mode=QUICK`) — could be
   minutes or hours after the session actually happened, whenever the parent clicks
   the confirmation link.
3. **Automatically by a background job**, if the parent never confirms — a cron job
   auto-confirms the booking after a timeout, and SLU fires then, with no human
   involved at that moment.

So it's tied to **when the booking's paperwork gets closed out**, not to when the
players actually leave the field.

For SLU to actually be produced (not skipped) at that moment, two things must already
be true:

- The player was marked **attended** (not a no-show) in the wrap-up.
- A **session plan** (built earlier in Session Builder) exists for that booking and
  was **saved** with at least one block containing drills. If the coach used Quick
  Complete without ever building a plan in Session Builder, there's nothing to
  calculate SLU from, so it's silently skipped — no error, just no SLU for that
  booking.

Real trigger, in one sentence: **coach/parent (or the system, on timeout) closes out
the booking → if a saved session plan exists and the player attended → SLU is computed
then, from that plan's drills.**

### Full detail

SLU calculation is triggered entirely by **booking completion**, not by anything in the
session-plan (Session Builder) lifecycle. These are two distinct, loosely-coupled
entities/lifecycles in this codebase:

- **Booking** (`platform.booking`) — the scheduling/business transaction: payment,
  attendance, wrap-up ratings, credit deduction, parent confirmation. Owns the state
  machine and the terminal `COMPLETED` status.
- **Session** (`platform.session`) — the optional drill/block plan a coach may build in
  Session Builder, with its own independent `DRAFT` / `SAVED` / `COMPLETED` status. A
  booking can be completed with **no session plan ever created**.

`SluCalculationService` reacts only to the booking side, then does a best-effort lookup
of a session plan by `bookingId` — it does not require or wait for the session plan's
own status to reach any particular value beyond "has committed block content."

### What fires `BookingCompletedEvent`

Defined in `platform.booking.contract.BookingCompletedEvent`, published from
`BookingCompletionService` / `QuickCompleteTimeoutService` (`platform.booking`) in
three places — none of which touch the `Session` repository at all:

1. **`submitWrapUp()` (mode `LIVE`)** — coach submits the wrap-up form with the parent
   present at pickup (`POST /api/bookings/{id}/complete`). Fires synchronously in the
   same transaction as the booking's `COMPLETED` transition.
2. **`confirmCompletion()`** — for the async ("QUICK") wrap-up path: coach submits
   wrap-up with `mode="QUICK"` → booking sits in `COMPLETED_PENDING_CONFIRMATION` and
   the parent is emailed a confirmation link → parent confirms
   (`PUT /api/bookings/{id}/confirm-completion`) → event fires.
3. **`QuickCompleteTimeoutService.processExpiredQuickCompletes()`** — a
   `@Scheduled(fixedDelay = 5m)` job. If the parent never confirms within
   `booking.quick_complete_timeout_hours`, the system **auto-confirms** the booking
   (`ActorRole.SYSTEM`, no human action in the moment) and fires the event itself.

No-shows (`NO_SHOW_PLAYER` / `NO_SHOW_COACH`) never reach `COMPLETED` and never fire
this event at all — see the `playerAttended` guard below for the closely related but
distinct case where the booking *does* complete but the player didn't attend.

### The calculation pipeline

1. `SluCalculationService.onBookingCompleted()`
   (`platform.development.service.SluCalculationService`) listens via
   `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async` — so SLU calculation
   never blocks or risks the booking-completion transaction itself.
2. **Guards before calculating anything:**
   - `!event.isPlayerAttended()` → skipped (SLU is only earned for sessions the player
     actually attended; the booking can still be `COMPLETED` with attendance `false`).
   - No `Session` row found for `event.getBookingId()` → skipped. This is the normal,
     expected case for a Quick Complete that never touched Session Builder — the code
     comment names this explicitly: *"Quick Complete or no session builder usage."*
   - Session status isn't `SAVED` or `COMPLETED` → skipped (a session plan left in
     `DRAFT` has no meaningfully committed block content). Note this is **not** the
     same "completed" as the booking's — a `SAVED` session plan is enough.
   - Session has no blocks, or blocks have no drill assignments → skipped.
   - SLU rows already exist for this session → skipped (idempotency guard against
     duplicate event delivery — relevant because the timeout cron and manual
     confirmation are two different paths that could theoretically race).
4. For each session block, drill time is split evenly across the drills in that block:
   `allocatedPerDrill = max(1, round(blockDurationMinutes / drillsInBlock))`.
5. `SluFormula.calculate()` runs per (block, drill) pair — **the same drill appearing
   in multiple blocks contributes independently each time**, using that block's own
   allocated duration.
6. Per-skill totals are summed across all blocks/drills in the session.
7. One immutable row per skill (with positive SLU) is written to
   `development.player_skill_stats`.
8. The player's `development.player_slu_weekly_snapshot` (keyed by ISO year/week) is
   upserted via `SnapshotBatchWriter`, so dashboard reads stay sub-second without
   re-aggregating raw stat rows.

Important module-boundary point: **the trigger for SLU calculation lives entirely in
`platform.booking`, and neither `platform.booking` nor `platform.session` compute SLU
themselves.** `platform.booking` only knows about the booking/wrap-up lifecycle and has
no knowledge of drills, blocks, or the SLU formula. `platform.session` owns the session
plan but never publishes any completion event — it's purely a passive lookup target for
`SluCalculationService`. All SLU logic lives in `platform.development`, which is the
only module that reaches across both.

### Frontend preview (estimate only)

While a coach is *building* a session (before it's ever run), the UI shows a **live
estimate** so coaches get a sense of session balance — but this is a simplified,
client-side approximation, not the authoritative formula:

```js
// simplified — see DrillCard.vue / DrillDetailPanel.vue
estimate = round(repDensity * sum(skillWeighting values) / 100)
```

This estimate ignores intensity/pressure/matchRealism scaling and the real per-block
duration split. It exists purely to give a coach a rough sense of "how much load does
this block carry" while composing a session — the real, backend-computed SLU is only
written once the session is actually completed. Components:
`DrillCard.vue`, `DrillDetailPanel.vue`, `SessionBlockView.vue` (running per-block
subtotal, `blockSlu` in `stores/sessionBuilder.store.js`).

## 5. Data Model

Schema `development`, introduced in
[`V46__development_module_init.sql`](../src/main/resources/db/migration/V46__development_module_init.sql)
and extended in
[`V48__development_exposure_dashboard.sql`](../src/main/resources/db/migration/V48__development_exposure_dashboard.sql).

### `development.skill_definitions`
The skill taxonomy — 15 seeded, extensible codes (e.g. `PAC` Pace, `SHO` Shooting,
`PAS` Passing, `DRI` Dribbling, `PHY` Physicality, `DEF` Defending, `WEF` Weak Foot,
`F1T` First Touch, `FIN` Finishing, `1V1` One vs One, `HED` Heading, `CRO` Crossing,
`IBS` In Behind Runs, `OBS` Off-Ball Scanning, `FKI` Free Kick Instep). New codes can
be added without a schema change.

### `development.player_skill_stats` — **immutable, append-only**
```sql
id              UUID          PRIMARY KEY
player_id       BIGINT        NOT NULL   -- TSID Long, not UUID
session_id      UUID                     -- nullable
coach_id        UUID          NOT NULL
skill_code      VARCHAR(10)   NOT NULL REFERENCES skill_definitions(code)
slu_value       NUMERIC(10,4) NOT NULL
calculated_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
```
Values are **baked in at booking-completion time and never updated or deleted** — this
is enforced by convention (no UPDATE/DELETE code paths in `SluRepository`) so historical
development data stays tamper-proof, even if config scale factors change later. A
partial unique index (added in `V47`) prevents duplicate SLU rows per session/skill.

### `development.player_slu_weekly_snapshot`
Pre-aggregated per player/skill/ISO-week totals, maintained incrementally by
`SnapshotBatchWriter` on every write — exists purely so the exposure dashboard can
answer "SLU by week for the last N weeks" without scanning raw stat rows.

### `development.player_slu_targets`
Coach-defined weekly SLU targets, keyed by `(coach_id, player_id, skill_code)`. Multiple
coaches can set independent targets for the same player/skill; evaluation always uses
the **highest** target across coaches.

### `development.neglected_skill_flags`
Open/resolved flags for skills falling below target (see §7).

### Config keys (`main.platform_config`)
| Key | Default | Purpose |
|---|---|---|
| `slu.intensity.scale` | `0.10` | intensity multiplier scale |
| `slu.pressure.scale` | `0.10` | pressure multiplier scale |
| `slu.matchRealism.scale` | `0.10` | match realism multiplier scale |
| `slu.neglected.threshold` | `0.30` | neglected-skill deficit threshold (see §7) |

## 6. API Surface

All endpoints live in `platform.development.api`:

| Endpoint | Purpose |
|---|---|
| `GET /api/development/players/{playerId}/exposure?weeks=8` | Weekly SLU exposure per skill (1–52 week window), backed by the snapshot table |
| `GET /api/development/players/{playerId}/narrative` | Narrative-text summary of a player's development (uses SLU trend + correlation insights) |
| `GET /api/development/players/{playerId}/slu/coach-contributions?days=30` | Per-coach breakdown of SLU contributed to a player, for multi-coach players |
| `GET /api/development/players/{playerId}/targets` | Coach's own weekly SLU targets for a player |
| `PUT /api/development/players/{playerId}/targets` | Set/update weekly SLU targets |

Access is guarded by `@PreAuthorize` — coaches can access any assigned player;
players/parents can only access their own data (`@playerOwnershipGuard`).

## 7. Downstream Consumers

- **`SluDashboardService`** — weekly exposure aggregation and narrative summaries for
  the coach/parent dashboards.
- **`SluContributionService`** — per-coach SLU contribution breakdown (for players
  training with multiple coaches).
- **`SluTargetService`** — coach-defined weekly targets per skill.
- **`NeglectedSkillDetectionService` / `NeglectedSkillProcessor`** — a weekly scheduled
  job (`@Scheduled(cron = "... MON")`, ShedLock-guarded) that evaluates the
  **previous** completed ISO week: if `actual SLU < target × (1 − threshold)`
  (threshold defaults to `0.30`, i.e. actual is more than 30% below target), a
  `neglected_skill_flags` row is opened; it's closed once the deficit resolves.
- **`DevelopmentCorrelationService`** — correlates SLU exposure trends against Skills
  Radar ability-score changes over time (e.g. flags "high SLU, improving radar score"
  vs. "low SLU, stagnant score" patterns) to surface coaching insights.
- **`ReportGenerationService` / `TimelineQueryService`** — PDF performance reports and
  the unified player timeline both reference SLU history alongside Skills Radar
  assessments and session events.

## 8. Frontend

- `src/frontend/src/api/development.api.js` — client wrappers for the exposure,
  narrative, targets, and coach-contributions endpoints.
- Dashboard components: `SluNarrativeSummary.vue`, `SluTargetEditor.vue`,
  `SkillExposureBarChart.vue`, `SkillExposureTrendChart.vue`,
  `DevelopmentCorrelationPanel.vue`.
- Session Builder preview (estimate only, see §4): `DrillCard.vue`,
  `DrillDetailPanel.vue`, `SessionBlockView.vue`,
  `stores/sessionBuilder.store.js` (`blockSlu`).

## 9. Key Invariants (do not break these)

- **SLU calculation logic belongs to `platform.development`, never `platform.booking`
  or `platform.session`.** `BookingCompletedEvent` is published from `platform.booking`
  and carries no drill/SLU knowledge; `platform.session` never publishes anything and
  is purely a passive lookup target. Only `platform.development` knows the formula.
- **`player_skill_stats` rows are immutable.** Never add UPDATE/DELETE paths to
  `SluRepository` — recalculating retroactively would corrupt historical development
  records that other features (neglected-skill flags, correlation insights, PDF
  reports) treat as a permanent record of what actually happened.
- **SLU is triggered by booking completion, not session-plan completion**, and is only
  earned when the booking's `playerAttended` flag is true **and** a session plan exists
  in `SAVED` or `COMPLETED` status with at least one block containing drills. A booking
  can complete (and fire the event) with no session plan at all — e.g. Quick Complete
  without touching Session Builder, or a plan left in `DRAFT` — in which case SLU is
  silently skipped, not blocked or errored.
- **The SLU formula itself is internal and intentionally not exposed to end users** —
  coaches/players see exposure trends and narratives, not the raw formula or scale
  factors.
- **Idempotency:** `onBookingCompleted` checks for existing SLU rows for the session
  before writing, to tolerate duplicate event delivery.

## 10. Origin

First introduced in Story 5.1 ("SLU Engine & Skill Taxonomy") as the foundation of
Epic 5 ("Player Development Intelligence"). Subsequent Epic 5 stories (Skills Radar
entry/display, development correlation, PDF/timeline reporting, parent development
portal) all build on the SLU data written here. Full spec:
`requirements/skillars/sessionBuilder.md` §2.1, and PRD requirements FR-DEV-002
through FR-DEV-006 in `_bmad-output/planning-artifacts/prds/`.
