# Pre-Implementation Story Review — `skillars-12-3-session-prep-card-and-timeline-access-clock-fix`

**Audit date:** 2026-10-15
**Real HEAD at audit time:** `108d9d9f` — `build(deps-dev): bump com.google.guava:guava-testlib (#273)`
**Story status at audit time:** `ready-for-dev` (confirmed from `sprint-status.yaml`'s `development_status` map, line 365 — not from the header comment prose)

> **Every citation below was verified against the working tree at `108d9d9f`.** No SHA, line count, or "as of drafting" claim written inside the story was trusted. The story's own lineage references an earlier review run against HEAD `9e3ae6a1` (story 12-2's pass); that SHA is **not** what anything here was checked against.
>
> Method: four parallel read-only verification layers (citations / ledger-and-precedent attributions / mechanistic claims / corner cases), followed by a mandatory adversarial re-verification pass in which every surviving finding's evidence was re-opened and re-read with the goal of refuting it. Section 6 records what that pass killed.

---

## 1. Citation verification

| Citation | Verdict | Real location | Evidence |
|---|---|---|---|
| `V138__baseline_schema.sql:420-428` — `player_timeline_events` table | **MATCH** | 420-428 exactly | `player_id bigint NOT NULL`, `occurred_at timestamptz NOT NULL`, `metadata jsonb`; **no `coach_id`** |
| `V138__baseline_schema.sql:3334` — existing index | **MATCH** | 3334 | `idx_player_timeline_events_player_id ... USING btree (player_id, occurred_at DESC)`. Grep of all migrations: this is the only index on the table |
| `TimelineQueryService.java:43-52` — `isCoach` branch | **MATCH** | 43-52 exactly | Verified firsthand |
| `TimelineQueryService.java:37-41` — `expiryDays` config read | **DRIFTED** (undershoot) | real statement **33-41** | L33 `long expiryDays;` and L34 `try {` fall outside the cited range |
| `TimelineQueryService.java:70-84` — `redactRecapIfNotAuthoringCoach` | **MATCH** | 70-84 exactly, name spelled identically | Verified firsthand |
| `TimelineQueryService.java:37-52` — "the exact region Task 2.2 changes" | **DRIFTED** | real blast radius **L9, L28-30, L37-52** | Lombok `@RequiredArgsConstructor` means the new resolver dependency is declared at **28-30**, outside the cited range; the `SluRepository` import at L9 may go unused |
| `ConfigBounds.java:148-151` — 3-arg-vs-4-arg Javadoc | **DRIFTED** | real Javadoc at **59-70** | 148-151 is only the `BoundedKey` declaration + a one-line Javadoc about `0/neg` semantics — no arity content. Verified firsthand |
| `SluRepository.java:48-56` — `findLastSessionDate` | **MATCH** | 48-56 | Native `@Query`, `SELECT MAX(calculated_at) ... WHERE player_id=:playerId AND coach_id=:coachId`, returns bare nullable `Instant`, params `(Long, UUID)` |
| `BookingCompletionService.java:228-232` — `verifyCoachOwnership` | **MATCH** | 228-232 exactly; is `private` | Throws `OperationNotAllowedException(..., SecurityError.MISSING_RIGHTS)`. Takes a `CoachProfile`, **not** a `UUID` |
| `Session.java:37-38` — `coachId` | **MATCH** | 37-38 exactly | `@Column(name="coach_id", nullable=false) private UUID coachId;` |
| `CoachCommandCenterPage.vue:90-203` / `:102-153` | **MATCH** (both ranges) | as cited | Caveat: 102-153 holds **five** buttons, not the three named (incl. a `COMPLETED`-gated "Repeat Next Week") |
| `router/routes.js:181-185` — session-builder route | **DRIFTED** (undershoot) | route object spans **180-185** | `name` is exactly `coach-session-builder` — the story's guess is **correct**. But it is **not** the only `:bookingId` route (also `:317`, `:335` receipt routes) |
| `i18n/en-US/index.js:239-255` — `coach.myPlayers*` | **MATCH** | `coach:` opens 239, closes 255 | `myPlayersNeglectedSkillCount: '{count} neglected skill \| {count} neglected skills'` — verbatim as claimed |
| `V159__booking_session_completion_data_recap.sql` | **MATCH** | is the highest migration on disk | **V160 is genuinely free.** Both borrowed idioms (`SET LOCAL lock_timeout`, `ADD COLUMN IF NOT EXISTS`) present |
| `PlayerTimelinePanel.vue` at `pages/coach/` | **DRIFTED** | real path `src/frontend/src/components/development/PlayerTimelinePanel.vue` | Substantive claims about it (no `coachId` prop, `store.fetchTimeline`) are correct |
| `vitest.config.mjs` at `src/frontend/src/` | **DRIFTED** | real path `src/frontend/vitest.config.mjs` | `globals: false` confirmed |
| All other named files | **EXIST** | as cited | `SessionPrepCard.vue`, `SessionPrepResource`, `CoachPlayerLastSessionResolver`, `SessionFocusThreadCalculator`, `SessionPrepService`, `SessionPrepResponse` correctly do **not** exist yet |

---

## 2. Ledger & precedent attribution

| Claim | Sources checked | Verdict |
|---|---|---|
| `deferred-work.md` heading "code review of skillars-12-2…" + SLU-clock-proxy bullet | doc / code comments / git | **SUPPORTED** — paraphrase faithful on every load-bearing point |
| Ledger's "natural home is Priority 4's…" quote | doc / git | **SUPPORTED** — verbatim |
| "coach UI CRUD audit" orphaned `SessionBuilderPage.vue` | doc / **independent frontend grep** / git | **SUPPORTED** — and the orphan is still real today: route name appears only in `routes.js` |
| "this session actually happened" as the ledger's own wording | doc (0 hits) / code comments (says *"meaningfully committed block content"*) / git (0 hits all history) | **UNSUPPORTED** — reviewer's paraphrase promoted to a quote. See M6 |
| Both source docs annotated as covered | doc / `git diff` | **SUPPORTED** — 1 annotation in `deferred-work.md`, 3 in the brainstorm |
| Pattern #9 verbatim quote | doc / git | **MISATTRIBUTED** — two-source splice. See M6 |
| Pattern #9 under `### Role Playing` | doc | **SUPPORTED** (`#Role-Playing` is the slug form) |
| Priority 4 / Fast-Follow out-of-scope framing | doc | **SUPPORTED** |
| Player/parent flag "permanently deferred" | doc | **PARTIALLY_SUPPORTED** — brainstorm says *"kept on the list"*, *"revisit once homework/messaging-adjacent stories are scheduled"* |
| 12-1 and 12-2 are `done` | `development_status` map | **SUPPORTED** — both `done` (the "review" wording is a superseded prior note) |
| 12-1's `lastActivityDate` merge-once precedent | doc / shipped code | **SUPPORTED** — confirmed in both |
| 12-1 "sidestepped the gap by calling the repo directly" | doc | **PARTIALLY_SUPPORTED** — flag-and-defer is exact; the *sidestep rationale* is 12-3's own inference. 12-1's stated reason is reuse + the player-level data boundary |
| `RosterAttentionCalculator(+Test)` precedent | code | **PARTIALLY_SUPPORTED** — real precedent is JUnit 5 + AssertJ, *"No Spring, no mocks"*. Both halves of the story's contradictory guidance are partly wrong. See L-group |
| `CoachRosterResourceIT` class shape | code | **SUPPORTED** — `@Sql({SecurityIT.SEC_DATA_SQL_PATH})` + `extends AbstractIntegrationTest`; the rest is inherited |
| `getCoachIdByUserId` precedent | code | **PARTIALLY_SUPPORTED** — method/types exact (`UUID getCoachIdByUserId(Long)`); the *layering* is inverted. See M4 |
| "prefer events for cross-module communication" + read-only carve-out | 12-2 / `project-context.md:123` | **PARTIALLY_SUPPORTED** — rule wording exact; the carve-out is 12-3's inference. The `CoachRosterService`→`BookingRepository` precedent is real and does carry the argument |
| `TimelineQueryService` has never had a unit test | 12-2 / test-tree search | **SUPPORTED** — only `PlayerTimelineResourceIT` references it |
| 12-2's D1 in "Pre-Implementation Review Resolution" + "both point here" | doc | **PARTIALLY MISATTRIBUTED** — D1 is real and the Review Findings "Deferred" half is **correct**; D1 is *not* in "Pre-Implementation Review Resolution" (line 97, a different review's M/H/L findings), and neither section forward-references 12-3 |
| `project-context.md` five rules | doc | **SUPPORTED** — 5/5 |
| "migration count moves quickly" | git | **PARTIALLY_SUPPORTED** — 1 migration-touching commit in the last 20. The "verify V160 is free" hedge is still good practice |

**Lineage note:** 12-2's own D1 (line 70) cites `SluCalculationService.java:52-62` and lists **three** early returns, omitting the `blocks` gate. The undercount originates there, was inherited by the ledger, and again by this story (see M6).

---

## 3. Mechanistic claims

**The story's core premise is sound.** This was checked hardest, and it holds:

- `TimelineQueryService.java:46` reads **only** `sluRepository.findLastSessionDate`. `getTimeline` calls exactly three collaborators; the timeline repository *is* consulted at `:55` but strictly **after** the gate and un-scoped by coach — an easy conflation that does not occur here. **CONFIRMED**
- `TimelineQueryService.java:47` — `lastSession == null ||` — **null is explicitly treated as expired**, first in the disjunction. `accessExpired` is the real field name (`TimelineResponse.java:5-9`), and `PlayerTimelineResourceIT:257-266` already pins this behavior. **CONFIRMED** — had null meant "no check applies", the whole story would collapse. It doesn't.
- `SluCalculationService.java:57-62` — the gate keys off **absence of a `session.sessions` row for the booking id**, matching its own log message *"Quick Complete or no session builder usage"*. **CONFIRMED**

**Near-miss hazard explicitly cleared:** there are **two** different `onBookingCompleted(BookingCompletedEvent)` methods in `platform.development.service`, with identical `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("taskExecutor")` annotations — `SluCalculationService:50` (six early returns) and `TimelineEventListener:44` (**zero** gates, writes unconditionally). Each was read in full and separately. Attributing one's gating to the other would invert the story's thesis; it also turns out to be the root of finding H3.

**Entity/repository mechanics — all proposed derived queries parse:**

| Claim | Verdict |
|---|---|
| `PlayerTimelineEvent.playerId` is `Long`, `occurredAt` is `Instant`, `eventType` is the enum, `metadata` is a `jsonb`-mapped `Map<String,Object>`, no `coach_id` today | **CONFIRMED** (verified firsthand) |
| `findTopByPlayerIdAndCoachIdAndEventTypeOrderByOccurredAtDesc(Long, UUID, PlayerTimelineEventType)` parses | **CONFIRMED** |
| `Session.status` is a **`String`** → `List.of("SAVED","COMPLETED")` is the correct param type (no enum trap) | **CONFIRMED** |
| `Session.developmentFocus` is `List<String>`, non-null jsonb → Task 3.1's `List<List<String>>` is achievable | **CONFIRMED** |
| `findTop10ByPlayerIdAndCoachIdAndStatusInOrderByCreatedAtDesc` parses; `findTop5ByPlayerIdOrderByCreatedAtDesc` is a direct in-interface precedent | **CONFIRMED** |
| `NeglectedSkillFlagRepository.findByPlayerIdAndResolvedAtIsNull(Long)` + `.size()` is exactly 12-1's idiom (no `countBy*` exists) | **CONFIRMED** |
| **ID space:** `Booking.playerId` is a `Long` `PlayerProfile` PK, and all four downstream repositories key off that same space and type | **CONFIRMED — the known trap does not bite** |
| `SecurityConstants.HAS_COACH_ROLE` = `"hasRole('ROLE_COACH')"` | **CONFIRMED** |
| `/neglected-skills` + `/slu/coach-contributions` have no relationship check | **CONFIRMED** against two convincing near-misses — `SluTargetService:27`/`:36` *do* call the guard while `getNeglectedSkills:53` does not; `SluDashboardService` guards 3 of 4 sibling endpoints. Verified method-by-method, not at class granularity |
| `writeTimelineEvent` is **private** | **REFUTED** → H1 |
| `Session.developmentFocus` is "only ever set via `SessionPlanService`" | **REFUTED** — `SessionTemplateService:158` also writes it. Consequence-free (stamps `DRAFT`, excluded by Task 3.2's filter) |
| "the one key in this codebase using the 3-arg pattern" | **PARTIALLY_TRUE** — 10+ 3-arg call sites repo-wide; `NeglectedSkillDetectionService:73-85` also wraps one in a manual catch. The true claim is scoped to `HAS_CODE_DEFAULT`'s 18 keys, per `ConfigBounds.java:59-70` |

**Transaction/async boundary (traced, not assumed).** `submitWrapUp`'s transaction commits the `session_completion_data` row and the state transition — **and nothing else**. *After* commit, two `AFTER_COMMIT` listeners dispatch onto `taskExecutor` (4 core / 16 max, `CallerRunsPolicy`). `writeTimelineEvent` then opens a **separate `REQUIRES_NEW`** transaction and commits the single INSERT alone; failures are caught and logged at `:65-67` with no rethrow, so a timeline-write failure **cannot** roll back booking completion. The SLU write and the timeline write are **not** in the same transaction as each other and have **no ordering guarantee**. Consequence for this story: **no new hazard for the `coach_id` write** (it lands in the same single INSERT), but the silent-loss path is what makes M2 reachable.

---

## 4. Corner cases, false assumptions, missed flows

### HIGH

**H1 — `writeTimelineEvent` is `public` with a cross-class caller; widening it breaks compilation in two files no task names.**
Task 1.3 says *"locate every call site of the **private** `writeTimelineEvent(...)` helper (`TimelineEventListener.java`, grep …)"* — wrong modifier, and the grep is scoped to the wrong file. Reality (`TimelineEventListener.java:87`): `public`, `@Transactional(REQUIRES_NEW)`, invoked via a `@Lazy self` proxy (it *must* be public). Three production call sites across two classes — `TimelineEventListener:58` (SESSION_COMPLETED), `:74` (RADAR_ASSESSMENT), and **`ReportGenerationService.java:216`** (PERFORMANCE_REPORT). Adding a 6th parameter is a **compile error** in `ReportGenerationService.java` plus **four Mockito arity compile errors** in `ReportGenerationServiceTest.java:216, 266-269, 285, 294`. Neither file appears in the story's Project Structure Notes or File List.
*Also settles Task 1.3's open question:* `RadarEntrySubmittedEvent` has no coach field and `ReportGeneratedEvent` carries `coachName` (not an id) — so `null` is correct at both, exactly as AC2 permits.
**Fix:** correct the modifier and call-site list; either pass `null` at `ReportGenerationService:216` and update the 4 verifications to six `any()`s, or add a 5-arg overload delegating with `null` so no existing caller changes.

**H2 — The V160 `CREATE INDEX` as specified fails the build.**
`MigrationLint.Rule.BLOCKING_INDEX` (`MigrationLint.java:787-790`) rejects a `CREATE INDEX` without `CONCURRENTLY` and without a `-- migration-lint: allow-blocking-index <reason>` opt-out **immediately above the statement** (an opt-out elsewhere in the file explicitly no longer covers it). The grandfather baseline is V139 and *"every migration from V140 onward is bound by the full rule set with no grandfather exceptions"* — V160 qualifies. Task 1.1's index statement carries no marker. `CONCURRENTLY` is not an escape either: no migration in the repo uses it, and the conventions doc records that it permanently self-deadlocks under this project's Flyway. The gate is `MigrationConventionLintTest` — a plain unit test, so it fails fast in the `test` phase and blocks every downstream job.
**Sharpening this audit added:** no post-baseline migration creates an index at all, so V160 would be the **first** — there is zero in-repo example to copy and the rule has never been exercised by a real migration.
**Fix:** Task 1.1 must specify the statement-scoped marker with a reason including an estimated row count and build time, per the conventions doc's rule 4.

**H3 — The new signal grants 90 days of full-timeline access for a session the player did not attend.**
The two `BookingCompletedEvent` consumers diverge precisely on attendance: `SluCalculationService.java:52-55` returns early on `!event.isPlayerAttended()` (no SLU row → no clock reset today), while `TimelineEventListener.onBookingCompleted` writes the `SESSION_COMPLETED` row **unconditionally** — grep for `attend` in that file returns **zero** hits. The non-attended wrap-up is a live, intentional path (`BookingCompletionService.java:138`; `WrapUpSequence.vue` exposes a switchable `playerAttended` toggle). Story 12-2's own D3 states the SLU skip is the *only* thing distinguishing the two cases — and the SLU row *is* the access clock.
*Failure scenario:* coach C's single booking with player P is wrapped up with `playerAttended = false`. Today → `accessExpired=true` forever. After this story → `accessExpired=false`, and C reads P's **entire** development timeline (radar assessments, performance reports, other coaches' session events) for 90 days on the strength of a session that never happened. `deferred-work.md:3794` specifically warns that changing this policy *"widens data access for currently-expired coaches and deserves its own ACs and tests."*
**Fix:** make it a decision, not a side effect. Either scope the new signal to attended sessions (requires attendance in the row — the table has no such column today), or accept it explicitly in an AC with an IT asserting the behavior.

### MEDIUM

**M1 — Task 2.3's seeding instruction produces a test that cannot pass.**
`insertSessionCompletedEventWithRecap` (`PlayerTimelineResourceIT:584-595`) inserts `(id, player_id, event_type, reference_module, occurred_at, metadata)` — **no `coach_id`**. Task 1.4's new query filters on that **column**. The V160 backfill runs once at Flyway time against an empty table, and `DatabaseResetTestExecutionListener` truncates before every test method, so no test row ever receives a backfilled value. Following Task 2.3's `jdbcTemplate` branch verbatim, the headline AC1/AC8 test asserts `accessExpired=false` and gets `true` — and the dev plausibly concludes the production merge is broken and debugs the wrong half. Secondary: *"directly via `jdbcTemplate` matching the existing helper's pattern"* points at the half that **doesn't commit** — all three existing callers wrap it in `transactionTemplate.execute(...)` (e.g. `:159`), which is what makes it commit under hikari `auto-commit=false`.
**Fix:** state that the fallback requires a widened/new helper setting `coach_id`. Worth also recording the silver lining: because the existing helper leaves `coach_id` NULL, none of 12-2's three redaction tests are perturbed.

**M2 — AC3 can label a recap with a date it did not come from.**
AC3 ties `lastWhatWeDid`/`lastWhatsNext` to *"that same most-recent own-authored `SESSION_COMPLETED` event"*, but Task 2.1's resolver returns `max(SLU, timeline)` — which **may be the SLU value** — and Task 3.4 assembles the two sources with no reconciliation rule. Divergence is reachable via two paths: the async swallow at `TimelineEventListener:65-67` (12-2 accepted this as "silent async-write loss"), and the story's **own** "Known residual risk" window, where a `coach_id IS NULL` row is invisible to Task 1.4's query. That paragraph reasons only about the *clock* (*"a row with neither simply doesn't move the clock"*) and never follows the consequence through to the recap.
*Failure scenario:* Jan 10 Quick Complete with recap R1 (timeline row, no SLU). Feb 10 builder session, timeline write silently lost, SLU row commits. Resolver → Feb 10; recap query → the Jan 10 row. Card renders **"Last session: 10 Feb — What we did: R1"**.
**Fix:** add `lastRecapSessionDate` to `SessionPrepResponse` and render the recap under *that*, or null the recap fields unless the event's `occurredAt` equals the merged date (falling back to AC5's "no recap yet"). State the rule in AC3.

**M3 — The backfill is an unbounded full-table `UPDATE` held open across a blocking index build.**
`docs/deployment/migration-conventions.md` requires backfills to be *chunked*, *"never one unbounded full-table write"*, and its rule 6 note is explicit that the lint *"does not decide whether a present `WHERE` actually bounds anything — **review still owns that half.**"* The story's `WHERE coach_id IS NULL AND metadata ? 'coachId'` matches every existing `SESSION_COMPLETED` row by design, inside a single Flyway transaction, followed by a blocking `CREATE INDEX` on the same table under `lock_timeout = '5s'`. `deferred-work.md:3798` separately records that this table's real cardinality is **unknown**.
**Fix:** add the `allow-full-table-dml` marker with a measured row count and explicit sign-off, or split the index into its own migration so the UPDATE's row locks aren't held while a `SHARE` lock is acquired.

**M4 — The coachId-resolution layering is inverted, the tasks contradict each other, and Task 3.6 asks for an unassertable assertion.**
Both cited precedents (`CoachBrandingResource`, `CoachRosterResource:33-35`) resolve `UUID coachId` **in the resource** and hand the service a `UUID`. Task 3.4 has `SessionPrepService.getPrep(UUID bookingId, Long coachUserId)` resolve it **inside the service**; Task 3.5 then says the resource resolves `coachUserId` *"the same way `CoachRosterResource` does"* — which never passes a `coachUserId` anywhere. Separately, Task 3.6 requires asserting a different coach's booking *"must not leak the player's name"* — but `SessionPrepResponse` has **no name field**, for any caller; a dev either invents one (creating a real leak surface AC3 doesn't list) or writes a vacuous assertion. And the two negative cases produce **different** statuses that no AC states: unknown booking → **404** (`ResourceNotFoundException` → `ApiAdvice:611-617`), wrong coach → **403** (`OperationNotAllowedException` → `ApiAdvice:329-333`).
**Fix:** pick one layering and make 3.4/3.5 agree (resolving in the resource matches both precedents and avoids a cross-module repository dependency). Replace the "player's name" wording with the real assertions, and note that the 403/404 split reveals *booking existence* — not player data — and is the deliberate, precedented behavior. Also note for AC5 that the card's player name must come from the parent's prop.

**M5 — `maxCodes` has no specified value or source anywhere in the story.**
Task 3.1 takes it as a parameter, Task 3.7 tests that it caps, Task 3.4's assembly never mentions it, and AC3 says only *"a list, possibly empty"*. No AC, task, or Dev Note names a number, constant, or config key — unlike every other tunable in this area. A dev picking a `ConfigService` key would need a `ConfigBounds` registration + `HAS_CODE_DEFAULT` entry + Flyway seed, which was a blocking finding in story 12-1.
**Fix:** name it — a private `static final int MAX_FOCUS_CODES = N`, explicitly *not* a config key.

**M6 — Two load-bearing attributions are inaccurate in ways that mislead on scope.**
(a) The "Pattern #9" block quote is a **two-source splice**: Pattern #9 ends at `_Novelty:_`; the second sentence (*"Reuse the same single-player endpoints…"*) is Priority 3's Action Planning `_Next steps:_` item 2, ~110 lines away in a different top-level section. Pattern #9 also never mentions neglected skills (that is Next Steps item 1). A dev treating the quoted block as the authoritative mandate gets a scope boundary matching neither source. This is the same defect class 12-2's own review already caught and corrected once.
(b) *"this session actually happened"* appears **nowhere** — not in the ledger, not in `SluCalculationService`'s comments (which say *"meaningfully committed block content"*), not in all of git history. Worse, Task 3.2 instructs mirroring *"`SluCalculationService.onBookingCompleted`'s own definition"*, but that method has **six** independent early returns (`:52` attendance, `:57` no `Session` row, `:66` status, `:73` empty blocks, `:111` no drills, `:170` empty stats). The status filter mirrors **one** of six. A dev taking "mirror" literally either adds gates the ACs don't want or burns time on the mismatch.
**Fix:** attribute the spliced sentence to Action Planning; drop the fabricated quote and state plainly that Task 3.2 intentionally mirrors only the *status* clause, because the focus thread needs `developmentFocus`, not drills. Also note AC1's claim that zero SLU rows means "Quick Complete or builder-less" is **narrower than reality** — a DRAFT or drill-less builder session also yields none, which *strengthens* the case for the fix.

### LOW

- **L1** — `Session.createdAt` is **plan-creation** time (`@PrePersist`, set when the builder is first opened), not session time; `Session` carries no conducted/scheduled date at all. Ten sessions bulk-planned for March all stamp ≈1 Feb and would outrank a session actually conducted 15 Jan, so `OrderByCreatedAtDesc` can return unconducted plans. The story's "no join needed" is true for the `coachId` filter, not the time semantic.
- **L2** — Task 3.1's tie-break is under-determined *within* a session: all codes in one `developmentFocus` list share the same index, so equal-count codes from the same session have no defined order → a non-deterministic unit test. Add a final deterministic tiebreak.
- **L3** — AC1's "if any other existing event type already stores a `coachId` key the same way" is **false**: `PERFORMANCE_REPORT` writes `coachName`, `RADAR_ASSESSMENT` writes only `skillCodes`. The backfill's broader scope buys nothing; say it is defensive only.
- **L4** — `SessionPrepCard.vue` is placed in `components/booking/` but told to use the `coach.*` namespace, while its cited sibling `WrapUpSequence.vue` uses `booking.*` 23× and `coach.*` zero times (as does the whole button region it joins). Defensible, but state *why* it departs so the next reader doesn't "fix" it.
- **L5** — Task 5.2's "extend" is available (`CoachCommandCenterPageSpec.js` exists, 298 lines) but the spec mounts `shallow: true` with `coachSchedule: null` and drives handlers via `wrapper.vm` — so **no booking cards render at all**. Asserting a rendered, status-gated button needs a different mount mode than the file's single helper provides.
- **L6** — AC1's fix amplifies an open ledger defect: `PlayerTimelineRepository.findByPlayerIdOrderByOccurredAtDesc` takes no `Pageable` (`deferred-work.md:3798`), and AC1's whole purpose is to let many more coaches past the gate that currently short-circuits them with `List.of()`. Latency/payload only — worth a residual-risk line so it isn't later re-triaged as a 12-3 regression.
- **L7** — Testing Standards says *"Mockito + AssertJ … matching `RosterAttentionCalculatorTest`'s precedent"* while Task 3.7 says *"Mockito/AssertJ not needed"*. The real precedent is JUnit 5 + AssertJ with an explicit *"No Spring, no mocks"* Javadoc — so **both** are partly wrong. (12-1 made the same Mockito error; inherited, not invented.)
- **L8** — Task 1.2: every other mapped column on `PlayerTimelineEvent` declares `updatable = false`; adding `coachId` without it makes the new column the lone mutable one.
- **L9** — Task 3.4 needs a **null guard** on `metadata` (nullable; `insertTimelineEvent` seeds rows with none, and `TimelineQueryService:66-69` documents this) and an `Object`→`String` narrowing. Neither is mentioned.
- **L10** — After Task 1 the row carries **two** authoring-coach identities: the `coach_id` column (new query) and the `metadata.coachId` **String** (still used by `redactRecapIfNotAuthoringCoach:76`). Task 3.4 reads metadata off a column-filtered result — reintroducing one layer down the "two computations can silently disagree" risk the story's own resolver Dev Note argues against.
- **L11** — `ReportGenerationService.java:146` is a **third** consumer of the same SLU-only clock, hard-gating with `AccessDeniedException("Coach has no session history with this player")`. The story fixes one of three, so a Quick-Complete-only coach will be able to read their timeline and open the Prep Card while still being refused report generation. Out of AC scope; worth a deferred-work line.
- **L12** — Line-range and fact corrections: config read is 33-41 not 37-41; `routes.js` route object is 180-185 not 181-185; `CoachCommandCenterPage.vue:102-153` holds five buttons not three; `verifyCoachOwnership` has **six** call sites not "exactly two" and takes a `CoachProfile` not a `UUID` (the duplication argument still stands); `coach/session-builder/:bookingId` is not the only `:bookingId` route; Task 4.1's snippet is missing `export const`; `WrapUpSequence.vue` has a fourth prop (`isLiveMode`); the `mvn verify` rule's real source is `docs/validation-strategy.md` (uncited).
- **L13** — Task 4.3's insertion into `CoachCommandCenterPage.vue`'s card block will stale line citations in two open ledger items (`deferred-work.md:3796`, `:843`) and 12-2's D3. Cosmetic.

---

## 5. What the story got right (independently verified, not taken on its word)

Listed so real coverage is distinguishable from silence:

- **The core premise** — null *is* treated as expired; `accessExpired` *is* the real field; the SLU gate *is* real and does skip Quick Complete.
- **V160 is free**; `V159` is the highest on disk.
- **`metadata` is `jsonb`**, so `?` is valid; the sole writer of a top-level `coachId` always writes a UUID string (never empty, never nested), so `(metadata->>'coachId')::uuid` **cannot fail on real data**; NULL-metadata rows are skipped safely.
- `SET LOCAL lock_timeout` **does** apply to later statements (one transaction per migration; V159 is the exact precedent).
- The proposed index **fits** the query and is **not redundant** with the existing `(player_id, occurred_at DESC)`.
- Both IT helpers use **explicit column lists**, so a new nullable column breaks no existing seed.
- **No type mismatch in the merge** — both signals are `Instant`, and both are `Instant.now()` at AFTER_COMMIT processing of the same event, so they share a semantic and differ by milliseconds. The "later of the two" merge does not systematically prefer the wrong one.
- **The null-safe max is strictly non-regressive** — it returns null only when *both* inputs are null, a strict subset of today's null case, so no coach who passes today can fail after.
- **AC1's "existing tests stay green unmodified" is TRUE** — each fixture was checked individually; the two `accessExpired=true` tests seed no timeline row at all, and per-method truncation rules out cross-test pollution.
- **No reassigned/substituted-coach leak** — `Booking.coach_id` is `updatable = false`, so a booking's coach can never change.
- **All four repositories share one ID space** (`Long` `PlayerProfile` PK) — the known `Booking.playerId` trap does **not** bite here.
- **AC4's status-agnostic design cannot NPE** — `player_id` is `bigint NOT NULL`; there are no group/open bookings.
- **`openNeglectedSkillCount` is no new privacy leak** — `NeglectedSkillFlag` has no `coach_id` column at all, and 12-1 already returns the identical count to a coach.
- **The route name guess `coach-session-builder` is exact**, the route's coach guard won't bounce, and `SessionBuilderPage.vue` tolerates an arbitrary `bookingId` (404 → `initForBooking`).
- **`booking.playerId` and `booking.playerName` ARE in scope** for Task 4.2's props (`ScheduleBookingItem` carries both); the id field is `booking.bookingId`.
- **The `q-dialog` child DOES remount per open** — so `onMounted` refires and there is no stale-`bookingId` bug (see §6).
- **Locale files are genuinely in sync** (one unrelated key missing from two locales); the pluralization shape is verbatim correct. Caveat: **no CI gate on locale parity exists** — Task 4.4's sync requirement is reviewer-enforced only.
- **AC8's "no existing test anywhere today" holds.**
- The unguarded-endpoint claim, the `CoachRosterResourceIT` shape, `HAS_COACH_ROLE`, `project-context.md`'s five rules, and the orphaned-route ledger item all check out.

---

## 6. What did NOT survive adversarial re-verification

Recorded because a report with uniformly high confidence and no calibration differences is itself evidence this pass didn't happen.

1. **"The `mvn verify` 'sole' claim regresses an already-corrected attribution"** — *downgraded to a missing citation (L12).* The story qualifies it with *"for routine story work"*, which matches `docs/validation-strategy.md:20` (*"not part of the normal `/bmad-dev-story` workflow"*) and `:38` (*"GitHub CI is the authoritative environment"*). Substantively accurate; only the citation is absent.
2. **The `Booking.playerId` ID-space trap** — *killed.* All four downstream repositories were checked individually; every one takes the same `Long` `PlayerProfile` PK. A plausible, high-prior failure mode in this codebase that simply does not apply here.
3. **`q-dialog` reusing a mounted child with a stale `bookingId`** — *killed.* Traced into Quasar's `use-portal.js` / `QDialog.js`: the slot subtree is destroyed on hide and recreated on show, so `onMounted` refires. Only a narrow caveat survives (re-opening mid-close-transition reuses the instance), not the predicted bug.
4. **"Existing `PlayerTimelineResourceIT` tests will break"** — *killed.* Every fixture was opened; the two flip candidates seed no timeline event whatsoever. AC1's claim is correct — though the protection comes partly from the seed helper's `coach_id` blind spot, which is precisely what makes M1 a defect.
5. **"A non-UUID / nested / empty `coachId` could abort the backfill cast"** — *killed.* The sole writer always emits `event.getCoachId().toString()`; no migration INSERTs into the table.
6. **"NULL `metadata` could break `?`"** — *killed.* `NULL ? 'coachId'` yields NULL → row skipped; `JsonType` never writes `'null'::jsonb` or a scalar.
7. **Reassigned-coach leak through the new signal** — *killed* by `updatable = false` on `Booking.coach_id`.
8. **`openNeglectedSkillCount` as a cross-coach privacy leak** — *killed*; the flag has no coach dimension and 12-1 set the precedent.
9. **"Every other cited line range is accurate"** (asserted by the corner-case layer) — *refuted.* Two other layers independently quoted `ConfigBounds.java:148-151`'s actual content, and my own read confirms it is the `BoundedKey` declaration with no arity Javadoc (real support at 59-70). A blanket accuracy claim was wrong where a specific one was right.
10. **"The `ConfigBounds` citation has the wrong directory (`infrastructure/` vs `config/`)"** — *dropped.* The story cites only a filename, no path; the "wrong path" was the verifying layer's own inference from its search. Only the line-number and scope halves of that finding stand.
11. **`Session.status` enum-parameter trap** — *killed.* `status` is a `String`, so `List<String>` is correct.
12. **"V160 may be taken"** — *killed*; free, with the story's own hedge being reasonable practice regardless.

---

## 7. Recommendation

**Status: do not start Task 1 as written. H1, H2, and M1 are each independently sufficient to produce a red build or a failing headline test on the first attempt; H3 is a product decision that should be made before code, not after.** None of this requires a redesign — the story's architecture is sound and its core premise was verified correct. This is a patch pass.

Confidence, per claim rather than as a blanket score:

| Finding | Confidence | Basis |
|---|---|---|
| H1 (`writeTimelineEvent` public + cross-class caller + 4 arity-pinned verifications) | **Highest** | Re-verified firsthand by direct grep and file read, independent of the reporting layer |
| H2 (V160 `CREATE INDEX` fails `MigrationConventionLintTest`) | **Highest** | Lint rule, grandfather baseline, and test-class wiring all read firsthand; additionally established that no post-baseline migration creates an index, so there is no precedent to copy |
| H3 (attendance asymmetry widens data access) | **Highest** | Verified firsthand — zero `attend` hits in `TimelineEventListener`, against `SluCalculationService:52-55` |
| M1 (Task 2.3 seeding cannot pass) | **Highest** | Helper's column list and the callers' `transactionTemplate` wrapper both read firsthand |
| M6 (Pattern #9 splice; fabricated "actually happened" quote; 6-gate undercount) | **High** | Both documents and the full method body read firsthand; quote absence confirmed across doc, code comments, and all git history |
| M3 (unbounded backfill), M4 (layering + unassertable assertion + 403/404), M5 (`maxCodes`) | **High** | Single-layer evidence, but each quotes a convention doc, a record definition, or an exhaustive absence that I spot-checked |
| M2 (recap/date mismatch) | **Medium-high** | The mechanism is confirmed (async swallow + the story's own `coach_id IS NULL` window); the *frequency* is low and I did not attempt to quantify it |
| L1–L13 | **Medium** | Reported by one layer with file:line evidence; I re-checked L11, L7's precedent, and the L12 line ranges myself, not the rest |

**Independently re-checked by me, not accepted on a layer's word:** H1, H2, H3, M1, M6, the `ConfigBounds` contradiction, the third SLU-clock consumer (L11), the `developmentFocus` second writer, the `SluTargetService` guard near-miss, `TimelineQueryService`'s and `PlayerTimelineEvent`'s full contents, and V160's availability.

**Not independently re-checked:** L1–L6, L8–L10, L13, the locale-parity key diff, the Quasar portal trace, and the per-fixture `PlayerTimelineResourceIT` enumeration. These rest on a single layer's cited evidence and should be treated as credible but unconfirmed.

**Suggested order:** resolve H3 as a product decision first (it may add a column to the same V160 migration, so it should land before Task 1.1 is written); then patch H1, H2, M1 into the task list; then M2–M6; then the L-group as editorial corrections. Status can stay `ready-for-dev` once the task list is patched.
