# Story: Strike Lock-Contention, Outbox Error-Isolation & Schema-Width Reconciliation Fixes

**Story Key:** `skillars-deferred-124-strike-lock-contention-outbox-resilience-and-schema-fixes`
**Epic:** Deferred Work
**Priority:** High (one genuine mutual-lock-out bug in the strike-issue path, one error-isolation gap
that abandons whole outbox/DLQ batches on a single row's exception, one schema-width divergence
between two live environments, one brittle claim-identity mechanism, and one audit-trail durability
gap) — plus two documentation-only closures and the standard ledger closeout.
**Status:** done
**Created:** 2026-09-19

---

## Provenance & Scoping (read before starting)

This story mines the **two most recent, still-open code-review deferrals** in `deferred-work.md` per
this project's established "read same-day code-review deferrals before drawing scope" convention:

- **`## Deferred from: code review of skillars-deferred-123-strike-timing-scheduler-lock-config-and-
  envers-audit-gap-fixes (2026-09-18)`** — 7 bullets, all freshly surfaced by that story's own
  four-layer `/bmad-code-review` and never picked up by any implementation (deferred-123 closed its
  *own* review findings via Decisions/Patches during that story's response pass; these 7 are a
  *second*, separate batch the review recorded as genuinely out of that story's scope — pre-existing
  behaviour or systemic risk, not caused by deferred-123's changes, but real).
- **`## Deferred from: code review of skillars-deferred-122-coach-enforcement-round-2-and-user-
  cleanup-fixes (2026-09-18)`** — this section holds **exactly one bullet, in four parts, not two
  bullets.** The single bullet bundles: (1) the `spring.jpa.generate-ddl: true` root-cause chain,
  decompiled three artifacts deep; (2) the pre-removal drift audit that justified removing it; (3) the
  `main.user_aud` `CHECK`-constraint half, marked closed at source by deferred-123's own code review;
  and (4) the `main."user"` schema-width half, still open — the only part this story's AC3 actually
  closes. Parts (1)-(3) are the *justification* for why the property was removed and what was checked
  before removing it — not dead history to prune alongside closing part (4). See AC8 for the exact
  edit this requires (an in-place strike of part (4)'s sentence, not a bullet deletion).

**All line numbers below were re-verified against `master@3bba9f8d`** (skillars-deferred-123's merge,
PR #211 — the current tip at story-creation time) **on 2026-09-19, not copied from the ledger.**

**Ledger `[CLOSED by ...]` / `[DECIDED]` sweep:** grepped the whole file for `CLOSED by` before
selecting items — no hits touch any file or symbol this story scopes. Two `[DECIDED]` bullets sit
immediately adjacent to this story's mined sections and are **correctly not picked up again**:
`ModerationSlaMonitorService`'s no-`@SchedulerLock` note (re-confirmed out of scope by deferred-123
already) and `reinstateCoach`'s stale-vs-fresh-suspension note (`[DECIDED: skillars-deferred-122]`,
also re-confirmed by deferred-123). Neither is touched by this story.

**Pre-implementation reminder:** diff every cited line range against HEAD again immediately before
starting each AC — this project's own history (deferred-121→122→123 in sequence) shows citations
shifting by dozens of lines between story creation and dev start whenever an intervening story merges
first. No intervening merge is expected here, but re-check anyway; it costs nothing and this ledger's
own bullets have twice previously been drafted against stale citations.

**Three owner decisions taken live with the user (AskUserQuestion) before drafting** — see AC5, AC7
and AC4 below for the full reasoning:

1. **AC5 (audit-trail durability gap):** give `AdminActionLog` its own `REQUIRES_NEW` write via the
   established self-proxy pattern, rather than accepting the gap as documented risk.
2. **AC7 (migration-vs-active-poller race):** document only, in `migration-conventions.md`. No
   migration-retry infrastructure — that would be materially larger scope than this story's other
   items and the risk is currently theoretical (no production deploy has ever happened per
   skillars-deferred-117's owner decision, re-confirmed still true).
3. **AC4 (`claimed_by` hardening):** include now, while the claim mechanism is already fresh context
   from deferred-123's own AC3 — rather than deferring the brittleness as accepted-for-now.

Everything else below is a direct fix or a direct documentation closure with an established in-repo
precedent to mirror — no further decision needed.

**8 ACs, bundled per this project's "do not create small stories" convention** (mirrors
deferred-118 through deferred-123's own bundling of unrelated fixes into one story): AC1
(`ReliabilityStrikeService.issue` mutual lock-out), AC2 (per-row exception isolation on both outbox/
DLQ processors), AC3 (`main."user"` column-width reconciliation), AC4 (`claimed_by` UUID hardening),
AC5 (audit-trail `REQUIRES_NEW` fix), AC6 (`deleteStrike` comment correction), AC7
(migration-conventions.md documentation), AC8 (standard ledger closeout — folds in the Envers-null
item as a `[DECIDED]` annotation rather than a ninth AC, since it is documentation-only and has no
task list of its own beyond the one comment).

---

## AC1 — Fix `ReliabilityStrikeService.issue`'s INSERT-before-lock mutual lock-out

> **[DROPPED — disproven, 2026-09-19, code review].** Everything below this AC's own heading through
> its Tasks list states the original, story-creation-time premise as fact and is kept **only** as the
> historical record of what was believed and why. The premise is false: `findByIdForUpdate` emits
> `FOR NO KEY UPDATE NOWAIT`, not `FOR UPDATE NOWAIT` — the SQL-AST path a Spring Data `@Query` +
> `@Lock` renders through (`PostgreSQLSqlAstTranslator.getForUpdate()`, not the legacy
> `PostgreSQLDialect.getWriteLockString`). `FOR NO KEY UPDATE` does not conflict with the strike
> INSERT's FK-check `FOR KEY SHARE`, so the described mutual lock-out cannot form under any
> interleaving. No code was changed for this AC. See this story's own Debug Log / Dev Agent Record and
> `deferred-work.md`'s "Last audit: 2026-09-19" block for the full empirical trail (a throwaway
> Postgres 16 container, independent of the application, confirms the actual lock semantics).

**Source:** `deferred-work.md`, "code review of skillars-deferred-123…", bullet 1.

**Current code** (`ReliabilityStrikeService.java`, re-verified at HEAD):

```java
// :50  public CoachReliabilityStrike issue(UUID coachId, UUID bookingId, String reason) {
// :51-55  strike = new CoachReliabilityStrike(); ... strike.setAcknowledged(false);
// :62  CoachReliabilityStrike saved = strikeRepository.save(strike);
// :64-67  read suspensionThreshold / visibilityThreshold
// :77  OffsetDateTime cutoff = OffsetDateTime.now().minusDays(30);   // AC2 from deferred-123
// :98  CoachProfile coach = lockRetryer.withBoundedRetry(() -> coachProfileRepository.findByIdForUpdate(coachId)...);
```

**The bug.** `coach_reliability_strikes_coach_id_fkey` (`V138__baseline_schema.sql:4427`) makes
PostgreSQL take `FOR KEY SHARE` on the parent `coach_profiles` row for the child INSERT at `:62`,
held until the enclosing transaction commits or rolls back. `CoachProfileRepository.findByIdForUpdate`
is `PESSIMISTIC_WRITE` with `jakarta.persistence.lock.timeout = 0` — `FOR UPDATE NOWAIT` — which
conflicts with `FOR KEY SHARE`. `PessimisticLockRetryer.java:132` flushes the persistence context
*before* taking its savepoint at `:133-134` (deliberately — see that class's own Javadoc,
`:31-35` — a pending write must be visible to the DB before the savepoint boundary, or a
rollback-to-savepoint could silently undo a flush Hibernate never re-issues). Two `issue()` calls for
*different bookings of the same coach* that both reach `:62`'s flush before either reaches `:98`'s
lock attempt are then **permanently mutually blocked**: each holds `FOR KEY SHARE` via its own
uncommitted strike INSERT, and each retry of `FOR UPDATE NOWAIT` fails against the other's
`FOR KEY SHARE` identically, exhausting `PessimisticLockRetryer`'s ~3.2s budget and discarding the
loser's strike with a 409 — not a race that resolves in the winner's favour, a genuine deadlock-shaped
mutual exclusion that only time-limited retry saves from hanging outright.

**Fix.** Move the strike-object construction and `strikeRepository.save(strike)` call to **after**
`withBoundedRetry` succeeds (i.e., after `:98-99`, before the `alreadyOffMarketplace` computation that
already sits there). This is safe and preserves every documented invariant:

- **The count at `:115` (`strikeRepository.countByCoachIdAndCreatedAtAfter`) still includes this
  call's own just-created strike.** Hibernate auto-flushes pending writes against a table a query is
  about to read before running that query (default `FlushMode.AUTO`), and the save and the count both
  touch `coach_reliability_strikes` — the ordering constraint the original `:56-61` comment describes
  ("the row is durable before the locked read and is counted by the query below") is satisfied by
  auto-flush-before-count regardless of whether the save happens before or after the *lock* step, as
  long as it happens before the *count* step.
- **"A full retry exhaustion still rolls the whole transaction back, strike included" stays true and
  becomes unconditionally true**, not just true-in-effect: today, if `withBoundedRetry` exhausts, the
  strike was already flushed at `:62` but the whole `REQUIRES_NEW` transaction still rolls back on the
  propagated exception — net effect, no strike persisted, identical to the new ordering where `save()`
  is never reached at all in that branch. No caller-observable behaviour changes in the exhaustion
  path.
- **The strike remains unconditionally recorded whenever `issue()` returns normally** — including for
  an `alreadyOffMarketplace` coach (deferred-123 AC1's own guarantee) — since the save now happens
  strictly before that branch, not conditioned on it.
- **Once `findByIdForUpdate` has won `FOR UPDATE` on the coach row, this transaction's own subsequent
  `FOR KEY SHARE` request on the same row (from the strike INSERT's FK check) is self-compatible** —
  Postgres never blocks a transaction on a lock it already holds a stronger mode of. The mutual-lock
  scenario above is structurally impossible once the INSERT can only happen after this transaction
  already owns the coach-row lock.

**Do not** change `PessimisticLockRetryer`'s flush-before-savepoint ordering to fix this — that class
is shared by every other `withBoundedRetry` caller in the codebase (`AdminCoachEnforcementService`,
`CoachProfileService`, others) and its own Javadoc explains why flush-before-savepoint is correct
*there*; the bug here is specific to *this* caller doing an unrelated flush-inducing write before ever
attempting the lock, not to the retrier's own mechanics.

**Existing coverage — read before writing a new test.**
`src/test/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeConcurrencyIT.java`
(`:58-114`) **already runs exactly the scenario Task 4 below describes**: two `issue()` calls for the
same coach, different bookings (`race(...)` at `:105-114`, a fresh `UUID.randomUUID()` bookingId per
call), released together from a `CountDownLatch` start gate (`:73-84`), asserting both sides succeed
(`a.get(30, SECONDS)` / `b.get(30, SECONDS)` at `:84-85`, which rethrows if either side's
`PessimisticLockingFailureException` propagated) and that both strike rows persisted (`:100-102`,
`strikeCount == 6`). It was written for a different bug (skillars-deferred-100's duplicate-event race —
see its own Javadoc, `:28-46`), but its concurrency shape is identical to what this AC needs. **Do not
add a second test class** — doing so would also fork a second Spring context against the
`assert-context-count.sh` ceiling (currently 43, see Dev Notes). Tighten this existing IT instead (Task
4 below).

**This existing IT may already be intermittently failing on today's (unfixed) master — check before
assuming otherwise.** Reasoning: `entityManager.flush()` (`PessimisticLockRetryer:132`) fires the
strike INSERT and its `FOR KEY SHARE` lock; a rollback-to-savepoint (`:150`) does not undo it, so once
*both* threads have passed their own first flush, *every* subsequent retry attempt for *both* threads
fails identically until the shared ~3.2s budget exhausts — this is not a race that resolves in one
side's favour once triggered, it is a full-budget dead end for both. Given the latch releases both
threads together and everything between thread-start and each thread's own first flush is in-memory
work (`ConfigService.getBoundedLong` reads its own periodically-refreshed cache, not the DB), and `FOR
KEY SHARE` from two different transactions never blocks either flush, there is no structural reason
for one thread's flush to reliably precede the other's lock attempt. If CI history shows this test has
never flaked, that fact does not match the analysis above and needs to be reconciled (a scheduling
detail this reasoning is missing) before trusting Task 6's mutation check below.

### Tasks

1. Move strike construction (`:51-55`) and `strikeRepository.save(strike)` (`:62`) to immediately
   after the `withBoundedRetry` block (after `:98-99`), before the `alreadyOffMarketplace` computation.
   Rename the local from `saved` to whatever reads cleanly at the new call site; update the `return
   saved;` at the end accordingly.
2. Rewrite the `:56-61` comment block in place at the new location — it currently justifies
   *save-before-lock*; it must now justify *save-after-lock, before-count*, citing the auto-flush
   mechanism above (do not just delete it: a future reader needs to know why the ordering here is not
   "the obvious" construct-then-return-immediately shape). Add one sentence noting the not-found path
   changes shape (see below) — a nonexistent `coachId` now fails cleanly at `findByIdForUpdate`'s own
   `orElseThrow` (`ResourceNotFoundException`) instead of at the strike INSERT's FK check
   (`ConstraintViolationException`); this is unreachable from `issueManualStrike` today (existence is
   checked at `:291-293` before `issue()` is ever called) but is a real, strictly-improved contract
   change for any other/future caller.
3. Add a new comment at the deleted call site's former location (or at the top of the lock block)
   explaining what used to be there and why it moved — a one-line pointer is enough (mirrors this
   codebase's own convention of leaving a breadcrumb rather than a silent diff, e.g.
   `ConfigStartupAssertion`'s and `AdminCoachEnforcementService`'s own review-response comments).
4. **Check `ReliabilityStrikeConcurrencyIT`'s CI history for intermittent failures before writing
   anything** (GitHub Actions run history for this test class). Then **tighten the existing test**
   rather than adding a new one: extend `ReliabilityStrikeConcurrencyIT`'s
   `concurrentIssue_bothCrossThreshold_publishesThresholdEventOnce` (or add a sibling test in the same
   class, reusing its `race`/`StrikeEventCapture` scaffolding) with an assertion that both
   `a.get(30, SECONDS)`/`b.get(30, SECONDS)` calls complete without throwing
   `PessimisticLockingFailureException` — today's test already fails loudly if either side throws, so
   the assertion may already exist in spirit; make it explicit if not.
5. **Add a deterministic interleave seam — a start-gate latch alone does not guarantee the two threads
   are both between `PessimisticLockRetryer:132`'s flush and `ReliabilityStrikeService:98`'s lock
   attempt at the same instant, only that they start around the same time.** The seam needs to sit
   inside that specific gap — e.g. a `@MockitoSpyBean CoachProfileRepository` whose
   `findByIdForUpdate` blocks on a second `CountDownLatch`/barrier the first time each thread calls it,
   released only once both threads have reached it (guaranteeing both have already flushed). **This
   introduces a new bean-override set** — pre-authorise bumping `assert-context-count.sh`'s ceiling
   past 43 if it fires (see Dev Notes), the same one-new-config-forks-one-context cost this codebase's
   own `AccountDeletionCascadeIT`/`SmtpTransportBootIT` precedents already accepted for a comparable
   need. If a deterministic seam turns out to be more invasive than this story's budget allows, downgrade
   to "run the un-tightened latch-based test N times (e.g. 20) under both orderings" and say so
   explicitly — do not claim "deterministic" for a mechanism that cannot deliver it.
6. **Mutation-check by hand**: temporarily revert the ordering (git stash the fix) and re-run the
   tightened test (with its interleave seam, if Task 5 added one — a bare start-gate latch alone is
   not reliable enough to trust a single failing/passing run either way). Confirm it fails against the
   unfixed ordering before restoring the fix.
7. Re-run `ReliabilityStrikeServiceTest`, `ManualStrikeIT`, and `ReliabilityStrikeConcurrencyIT` in full
   together — zero regressions expected; the ordering change should be invisible to every test that
   does not deliberately interleave two coach-scoped calls.

---

## AC2 — Per-row exception isolation on both outbox/DLQ processor loops

**Source:** `deferred-work.md`, "code review of skillars-deferred-123…", bullet 3.

**Current shape, re-verified at HEAD (both processors, structurally identical):**

`VideoDeletionOutboxProcessor.process()` (`:126-156`) — the `for` loop at `:144-155` calls
`processRow(row, runClaimedAt)` at `:153` with **no try/catch around the call**. Inside
`processRow` (`:158-211`), only the `videoProviderAdapter.deleteAsset` + `completeRowWithNullAsset`
pair (`:205-210`) is wrapped. Everything else in the method — `drillVideoRefRepository.findByVideoId`
(`:169`), the drill-ref branch's `transactionTemplate.execute` block (`:173-187`),
`videoRepository.findById` (`:198`), and `completeRow`'s own `transactionTemplate.execute`
(`:213-221`) — runs **unguarded**. An exception from any of those propagates out of `processRow`,
out of the `for` loop, out of `process()` itself, abandoning every remaining row in the batch.

`RadarCompositeDlqProcessor.process()` (`:105-129`) — the `for` loop at `:118-128` calls `processRow`
at `:126`, also unwrapped at the loop level. `processRow` (`:131-145`) *does* wrap its own body in a
try/catch (`:132-144`) that routes failures to `handleFailure` — but `handleFailure` itself
(`:147-165`+) does its own `transactionTemplate.execute` reading `configService.getBoundedLong` — if
*that* throws, the exception escapes the outer `catch` (which already returned control to
`handleFailure`, not a second guard around it) and propagates identically to the video side.

**Why this got strictly worse under deferred-123's own AC3.** Before AC3, a claimed row that never
got processed (loop aborted) stayed re-eligible via `next_retry_at` on essentially the next tick.
After AC3 rekeyed staleness recovery onto `claimed_at`, an abandoned row is invisible until a full
`STALE_CLAIM_WINDOW` elapses (20 min video / 10 min radar) — the fix that closed one double-processing
path made this pre-existing error-isolation gap materially slower to self-heal, not just cosmetically
worse.

**Fix.** Wrap the per-row call **at the loop level** in both `process()` methods — not by patching
each internal nested try/catch individually, which would need three separate additions on the video
side and still would not protect `RadarCompositeDlqProcessor.handleFailure`'s own failure-handling
transaction. On catch, **route the exception through the same `handleFailure(row, e, runClaimedAt)`
private method the existing inner try/catch already calls** (both processors already have one, with
an identical signature — `VideoDeletionOutboxProcessor:246`, `RadarCompositeDlqProcessor:147`), wrapped
in its own inner try/catch so a failure *inside* `handleFailure` itself still cannot abort the batch.
This closes the gap completely rather than only half of it: a bare `log.error` outer catch (the
original design considered here) would isolate the *batch* from a poison row but leave that row
cycling `CLAIMED → resetStaleClaimed → re-claimed → throws → CLAIMED` forever, since
`resetStaleClaimed` only resets `status`/`claimed_at`(`/claimed_by` per AC4) and never touches
`attempts`/`next_retry_at` — the row would never reach `max_attempts` and never become `DEAD`. Routing
through `handleFailure` gives the row the same attempt-increment/backoff/eventual-dead-letter path
every other failure already gets, which was this AC's point in the first place.

### Tasks

1. In `VideoDeletionOutboxProcessor.process()`, wrap the `processRow(row, runClaimedAt);` call
   (currently `:153`) in a try/catch. On catch: log at `ERROR` including `row.getId()` and
   `row.getVideoId()`, then call `handleFailure(row, e, runClaimedAt)` — itself wrapped in a nested
   try/catch that logs at `ERROR` (and only then, not `return`s) if `handleFailure` itself throws.
   Continue the loop either way (do not `return` — the deadline check on the next iteration is still
   the correct place to bail out early, not this catch).
2. Apply the identical change to `RadarCompositeDlqProcessor.process()`'s `processRow(row,
   runClaimedAt);` call (currently `:126`), logging/routing to its own `handleFailure`, with
   `row.getId()`, `row.getPlayerId()` for correlation.
3. Update both methods' class/method-level Javadoc that currently states or implies every row's
   outcome is handled by the inner try/catch alone — note explicitly that this new outer guard exists
   specifically so a bug in the completion path (not just `handleFailure`, which is now itself the
   outer catch's target) cannot abandon the rest of the batch, and that routing to `handleFailure`
   (rather than a bare log) is what lets a chronically-throwing row still reach `max_attempts` and
   `DEAD` instead of cycling forever.
4. **Do not** attempt any *additional* release path beyond the `handleFailure` routing above — a row
   that exhausts `max_attempts` via this new outer catch becomes `DEAD` exactly like any other
   exhausted row, and one still mid-backoff is correctly left `CLAIMED`+`next_retry_at`-scheduled for
   the next tick, not `resetStaleClaimed`'s stale-window path (which remains the crash-recovery
   backstop it always was, not this AC's primary recovery mechanism).
5. **New test per processor** proving isolation *and* eventual dead-lettering: seed a batch of 3+
   claimed rows where the *middle* row's processing throws an unexpected `RuntimeException` from a
   mocked/stubbed dependency (not the already-tested `deleteAsset`/`recalculateComposite` failure
   path, which already routes to `handleFailure` correctly on its own) — assert the rows *before and
   after* the failing one still complete normally, and the failing row's `attempts` is incremented
   (i.e. it genuinely reached `handleFailure`, not just "didn't crash the loop"). On the radar side
   (`RadarCompositeDlqProcessorTest`, plain `@ExtendWith(MockitoExtension.class)` with every
   collaborator already mocked — no Spring context, no ceiling risk) this is straightforward: stub
   `compositeCalculationService.recalculateComposite` to throw for one row only. **On the video side,
   `VideoDeletionOutboxProcessorIT` only `@MockitoBean`s `VideoProviderAdapter`** (`:33-39`); every
   other collaborator (`DrillVideoRefRepository`, `VideoRepository`, `ConfigService`) is a real
   `@Autowired` bean, so proving isolation for a failure *outside* the already-mocked `deleteAsset`
   path needs a *new* `@MockitoBean` on one of them — which forks a new Spring context against the
   ceiling (see Dev Notes). Either add that mock and pre-authorise the ceiling bump, or accept
   asserting this specifically on the radar side (which needs no new mock) and cover the video side's
   loop-level try/catch via a plain unit-level test of `process()` with a hand-constructed row list and
   a spied/stubbed `processRow`, if that seam exists — check before assuming it does.
6. **Mutation-check by hand**: temporarily remove the new try/catch and confirm the new test fails
   (the rows after the throwing one no longer complete) before finalizing.
7. Re-run `VideoDeletionOutboxProcessorIT`, `VideoDeletionOutboxProcessorSchedulerLockTest`,
   `RadarCompositeDlqProcessorTest`, `RadarCompositeDlqRepositoryIT` together — zero regressions.

---

## AC3 — Reconcile `main."user"` `skillars_role`/`verification_status` column width

**Source:** `deferred-work.md`, "code review of skillars-deferred-122…" — one bullet, in four parts
(see Provenance for the full breakdown). Part (3) of that bullet, the `main.user_aud` CHECK-constraint
half, was already closed by `V145`'s own code-review Patch pass — do not re-touch it. This AC closes
part (4), the still-open `main."user"` width half, and **only** that part (see AC8 Task 2 for the
exact in-place edit this requires — the bullet is not deleted).

**The divergence, re-verified at HEAD:** `V138__baseline_schema.sql:1289-1290` declares
`main."user".skillars_role character varying(20)` and `verification_status character varying(20)
DEFAULT 'UNVERIFIED'`. `User.java:159-165`'s `@Column` annotations for both fields carry no explicit
`length`, so Hibernate's own default for a `String`-backed column (both are `@Enumerated(STRING)`
enums per `User.java` — re-confirm the exact annotation at implementation time) is `varchar(255)`.
Per deferred-123's own investigation (now corrected and documented in `V145`'s header, `:12-22`), the
now-removed `spring.jpa.generate-ddl: true` had Hibernate silently issuing `alter table ... alter
column ... set data type varchar(255)` against these two columns at every boot, in every environment
— so every database that has ever booted this application carries `varchar(255)` on `main."user"`,
while `V138`'s own migration declares `varchar(20)`. A fresh, Flyway-only database (any CI run, any
new environment) gets the `varchar(20)` the migration actually specifies — the two shapes disagree.

**Why this needs its own migration, not a drive-by fix alongside `V145`.** `V145` widened
`main.user_aud`'s copies of these same two column names because that table's rows are empty-safe
(audit history, additive). `main."user"` itself is the live, populated table — widening it is safe
(a widen never fails against existing data) but the *risk framing* deferred-123 recorded (`deferred-
work.md`, "code review of skillars-deferred-122…") explicitly asked for "its own migration and
evidence pass," not a bundled fix. This story is that pass.

**Fix — widen, but the alternative is real and undecided; confirm before writing the migration.**
The obvious fix is to widen `main."user".skillars_role`/`verification_status` from `varchar(20)` to
`varchar(255)`, mirroring `V145`'s own established shape for the identical situation. The unmentioned
alternative is the opposite direction: add `@Column(length = 20)` to `User.skillarsRole`/
`verificationStatus`, preserving `V138`'s originally-declared intent instead of chasing what auto-DDL
left behind. **The ledger's own stated reason to avoid narrowing** ("a value beyond 20 chars would
fail the ALTER") **is contradicted by the ledger's own next sentence** ("per skillars-deferred-117's
owner decision no production deploy has ever happened, so the only 'pre-fix databases' in existence
are development and CI ones") — if no environment has ever gone live, there is no populated table a
narrowing `ALTER` could fail against, at least not for a reason a pre-migration length check couldn't
already catch. The two enums' longest constants are `EMAIL_VERIFIED`/`BASIC_VERIFIED` at 14 characters
(`SkillarsVerificationStatus.java`) and `PLAYER`/`ADMIN`-class names well under that in `SkillarsRole`
— both comfortably inside 20, so a narrow would not fail today's data either way. **Task 1 below makes
this an explicit decision with the evidence in hand, not an assumed one** — widening (matching `V145`,
lower-risk direction, and an entity-side `length` annotation does nothing to a column that already
exists at `varchar(255)`) is the likely outcome, but the story should not present it as the only
option when the "evidence pass" the ledger asked for is precisely where this belongs.

**Constraint check — the expected answer is "no constraint exists," not "confirm whether one does."**
`V145`'s `CHECK`-reconciliation precedent does not transfer here the way this AC originally assumed.
`main.user_aud` had **no** `skillars_role`/`verification_status` columns at all, so Hibernate's DDL was
`alter table … add column … check (…)` — the CHECK rides along with column creation, which is what
`V145`'s header and `UserEnversAuditGapIT` recorded empirically. `main."user"` **already had** both
columns (`V138:1289-1290`); Hibernate's only possible action there is `alter column … set data type`,
which carries no `CHECK` clause of its own. Confirmed by direct inspection: `grep 'CONSTRAINT user_'
V138__baseline_schema.sql` returns only PK/unique/FK rows (`user_pkey`, `user_email_key`,
`user_login_key`, `user_phone_key`, plus the `user_addresses_*`/`user_authority_*`/`user_aud_*`
constraints on *other* tables) — no `user_skillars_role_check`/`user_verification_status_check`
anywhere. So the likely finding is **no CHECK exists on `main."user"` in any environment today**, and
*adding* one (as this AC originally instructed, treating `V145` as the template) would **create a new
divergence in the opposite direction** — Flyway-built databases constrained, already-booted ones not —
which is the inverse of what this AC is for. Confirm via `pg_constraint` as a sanity check on this
conclusion, not as an open discovery step.

### Tasks

1. **Decide widen vs. narrow, with the evidence above, before writing any SQL.** Enumerate both
   enums' constants and their lengths (`SkillarsRole`, `SkillarsVerificationStatus`) to confirm the
   14-character figure above still holds at implementation time; state the chosen direction and why in
   the migration header. Widening is the expected outcome (matches `V145`, is the lower-risk direction
   given a live, populated table even though no *production* deploy exists yet) but must be stated as
   a decision, not assumed.
2. Confirm the next free Flyway version number against the actual `src/main/resources/db/migration/`
   directory at implementation time (this story's own audit found `V148` as the current tip; a
   concurrent story could have claimed `V149`/`V150` since — do not assume).
3. Write the chosen migration. If widening: `ALTER TABLE main."user" ALTER COLUMN skillars_role TYPE
   character varying(255)`, same for `verification_status`, under `SET lock_timeout = '5s'` per this
   repo's migration-lint convention. Confirm via `MigrationLint`'s own rules whether a bare `ALTER
   COLUMN ... TYPE` needs any special handling (it should not trigger
   `VALIDATING_CONSTRAINT`/`INLINE_FK_ADD_COLUMN`, but verify against the lint's actual rule
   implementations rather than assuming, per this project's own stated method).
4. **Do not** add a `CHECK`-constraint reconciliation block by default — per the investigation above,
   the expected finding is that none is needed. Only add one if the `pg_constraint` sanity check
   contradicts this story's own analysis, and say explicitly why if so.
5. Update `User.java`'s field-level Javadoc for both fields to note the width is now explicit/
   Flyway-tracked (or explicitly narrowed, per Task 1's decision), closing the "one divergence
   remains" note `V145`'s own header and the ledger currently carry.
6. **New test or extension of an existing one asserting the Flyway-built column width is `255`** (or
   `20`, per Task 1's decision) — via `information_schema.columns`, mirroring
   `EnvelopeEntitySchemaIT.java:108`'s established pattern (`select ... from information_schema.columns
   where …`), the actual in-repo precedent for this shape. `MigrationConventionLintTest` is a
   static-text lint that never opens a connection and `RescheduleResourceIT` does not query
   `information_schema` — neither is the right model to copy. **Word the assertion honestly**: CI only
   ever builds Flyway-only databases, so this test can assert the post-migration width and nothing
   about whichever width an already-Hibernate-patched environment happens to carry — "asserts the
   Flyway-built width matches what already-booted environments have" (per Task 1's chosen direction),
   not "proves a fresh database now matches an already-patched one."
7. Re-run `MigrationConventionLintTest` and any test that seeds a `skillars_role`/`verification_status`
   value near or above 20 characters (there should be none today — confirm) — zero regressions
   expected regardless of which direction Task 1 chooses, given the enum-length evidence above.

---

## AC4 — Replace `claimed_at`-equality run-identity with a `claimed_by` UUID column

**Source:** `deferred-work.md`, "code review of skillars-deferred-123…", bullet 6. **Owner decision:
include now** (see Provenance).

**Current mechanism, re-verified at HEAD (both repositories, identical shape):**
`VideoDeletionOutboxRepository`/`RadarCompositeDlqRepository` each expose `claimPendingBatch`,
`findClaimedBatch`, `releaseClaimed`, `completeClaimed`, `failClaimed` — **every one of the latter
four uses `claimed_at = :claimedAt` as the "rows this invocation owns" identity predicate**, reusing
the same `Instant` that also drives `resetStaleClaimed`'s *staleness* check. This is correct today
because both processors generate exactly one `Instant runClaimedAt = Instant.now()` per tick and pass
it through every call in that tick, so the equality always matches genuinely-owned rows. It is brittle
because the column is doing two structurally different jobs (a timestamp for staleness math, and a
value-equality token for run identity) with no enforcement that they stay coupled — any future change
to how `runClaimedAt` is threaded through (a retry that re-derives it, a refactor that captures it
twice) would silently break every one of these five call sites at once, and the failure mode is
silent: rows claimed, batch fetched empty, rows stranded until the stale window recovers them — not an
exception, not a wrong-but-visible result.

**Fix.** Add a `claimed_by` UUID column to both `main.video_deletion_outbox` and
`development.radar_composite_dlq`. `claimPendingBatch` stamps `claimed_by` with a fresh
`UUID.randomUUID()` generated once per tick (alongside the existing `runClaimedAt` Instant, which
**must be kept** — it still drives `resetStaleClaimed`'s time-based staleness check, a genuinely
different concern from run identity). `findClaimedBatch`, `releaseClaimed`, `completeClaimed`, and
`failClaimed` switch their identity predicate from `claimed_at = :claimedAt` to `claimed_by = :runId`.
`resetStaleClaimed` keeps its `claimed_at < :deadline` / `claimed_at IS NULL` predicate unchanged (a
time comparison, not an identity one) but **must still clear `claimed_by` back to `NULL`**, per the
next paragraph.

**`claimed_by` must be cleared on every transition out of `CLAIMED`, exactly like `claimed_at` is
today.** `VideoDeletionOutbox.claimedAt`'s own Javadoc documents this invariant for `claimed_at`
("cleared back to `null` on every transition out of `CLAIMED`") and all four methods that transition a
row out of `CLAIMED` honour it: `completeClaimed`, `failClaimed`, `releaseClaimed`, and
`resetStaleClaimed` all `SET claimed_at = NULL`. `claimed_by` needs the identical treatment in the
identical four places — otherwise a `COMPLETED`/`DEAD`/re-`PENDING` row permanently carries the UUID
of whichever run last touched it. Not a correctness break on its own (every identity predicate is also
gated on `status = 'CLAIMED'`, so a stale `claimed_by` on a non-`CLAIMED` row is never read as an
identity match), but it silently violates the invariant this AC's own Task 3 instructs the dev to
mirror, and it defeats the column's forensic purpose ("which run owns this row *right now*" stops
being answerable once "now" and "the last run that ever touched it" are indistinguishable).

### Tasks

1. Confirm the next free Flyway version at implementation time (see AC3 Task 2 — this migration and
   AC3's may claim adjacent numbers in either order; whichever lands first takes the lower number, no
   ordering dependency between them).
2. New migration: `ALTER TABLE main.video_deletion_outbox ADD COLUMN IF NOT EXISTS claimed_by uuid`,
   same for `development.radar_composite_dlq`, under `SET lock_timeout = '5s'`. No backfill needed —
   any row currently `CLAIMED` with a `claimed_by` of `NULL` is handled correctly by the identity
   predicate simply never matching `NULL = :runId` (Postgres's three-valued logic). **State the full
   reason in the migration header, not just the half above** — an old-shape row still carries a
   non-`NULL` `claimed_at`, so `resetStaleClaimed` (unchanged, still keyed on `claimed_at`) recovers it
   one stale window later; it is not permanently stranded the way `V144`'s own predecessor column would
   have been without a backfill (`V144:22-28`, "would never match any run's `claimed_at` and would be
   stranded forever, hence the backfill"). Citing only "`NULL` never matches, which is safe" without
   this second half reads as contradicting `V144`'s own precedent rather than explaining why this case
   is genuinely different. Also state, mirroring `V144:30-36`'s equivalent note for its own predicate
   change: both tables are near-empty of `CLAIMED` rows today, so no new index is warranted for the
   `(status, claimed_by)` access pattern `findClaimedBatch` moves to — accepted as-is, same as `V144`,
   not silently unconsidered.
3. Add `claimedBy` (`UUID`) fields to `VideoDeletionOutbox`/`RadarCompositeDlqEntry` entities,
   mirroring `claimedAt`'s existing Javadoc-and-`@Column` shape **including the "cleared on every
   transition out of `CLAIMED`" invariant sentence** — do not mirror only the stamp half.
4. Update `claimPendingBatch` in both repositories to also `SET claimed_by = :runId`, adding a
   `@Param("runId") UUID runId` parameter.
5. Update `findClaimedBatch`, `releaseClaimed`, `completeClaimed`, `failClaimed` in both repositories:
   replace the `claimed_at = :claimedAt` predicate with `claimed_by = :runId`; rename the `claimedAt`
   parameter to `runId` (type `UUID`) on all four. Add `claimed_by = NULL` to the `SET` clause of
   `releaseClaimed`, `completeClaimed`, and `failClaimed` (alongside their existing `claimed_at =
   NULL`). Add `claimed_by = NULL` to `resetStaleClaimed`'s `SET` clause too, even though its `WHERE`
   predicate stays keyed on `claimed_at` — it still transitions a row out of `CLAIMED` and the
   invariant applies regardless of which predicate triggered the transition.
6. Update both processors (`VideoDeletionOutboxProcessor.process`/`RadarCompositeDlqProcessor.process`)
   to generate `UUID runId = UUID.randomUUID();` alongside `Instant runClaimedAt = Instant.now();` at
   the top of each tick, and thread `runId` through to `claimPendingBatch`, `findClaimedBatch`,
   `releaseClaimed`, and (via `processRow`) `completeClaimed`/`failClaimed` — `runClaimedAt` keeps
   flowing only to `resetStaleClaimed` and to `claimPendingBatch`'s own `claimed_at` stamp.
7. Update the four test files that currently pass an `Instant` for identity to these methods
   (`VideoDeletionOutboxProcessorIT`, `VideoDeletionOutboxProcessorSchedulerLockTest`,
   `RadarCompositeDlqProcessorTest`, `RadarCompositeDlqRepositoryIT` — re-confirm this list against
   `grep -rl "findClaimedBatch\|completeClaimed\|failClaimed\|releaseClaimed" src/test` at
   implementation time, it may have grown) to pass a `UUID` instead, generating a fresh one per
   simulated "run" the same way the production code now does. Add an assertion that `claimed_by` is
   `NULL` after each transition, mirroring whatever existing assertions already check `claimed_at` is
   `NULL` post-transition (added by deferred-123's own code-review Patch response) — do not let AC4 add
   the clearing behavior without also adding the test coverage for it.
8. Re-run all four updated test files plus `MigrationConventionLintTest` together — zero regressions
   expected; the identity mechanism changes but no observable behaviour should, since a single tick
   still generates exactly one `runId` used consistently across its own calls.

---

## AC5 — Close `issueManualStrike`'s audit-trail durability gap via `REQUIRES_NEW`

**Source:** `deferred-work.md`, "code review of skillars-deferred-123…", bullet 5. **Owner decision:
fix via the established self-proxy `REQUIRES_NEW` pattern** (see Provenance).

**Current code** (`AdminCoachEnforcementService.java`, re-verified at HEAD, `:276-311`):

```java
// :276  @Transactional
// :277  public UUID issueManualStrike(UUID coachId, UUID bookingId, String reason, Long adminId) {
//        ... validation, existence checks ...
// :300  CoachReliabilityStrike strike = reliabilityStrikeService.issue(coachId, bookingId, reason);
// :302-306  AdminActionLog actionLog = new AdminActionLog(); ... setReason(...);
// :307  adminActionLogRepository.save(actionLog);
// :309  log.info(...);
// :310  return strike.getId();
```

**The gap.** `reliabilityStrikeService.issue` (line 49 of that class) is
`@Transactional(propagation = REQUIRES_NEW)` — it commits durably, on its own connection, the moment
it returns, *independent* of whatever `issueManualStrike`'s own (default-propagation) transaction does
afterward. The `AdminActionLog` write at `:302-307` happens in `issueManualStrike`'s own transaction,
which is still open at that point. If anything causes that outer transaction to roll back after
`:300` returns — a deferred constraint check firing at commit, a connection drop, a pod kill, or
(more mundanely) an exception thrown by the audit-log save itself for an unrelated reason — the strike
(and any status change/events it triggered) is already durably committed via `issue()`'s own
`REQUIRES_NEW`, but the record of *which admin issued it* is rolled back with the outer transaction.
Net result: a real, visible enforcement action with zero audit trail.

**Fix.** Mirror `UserAdminService`'s own established self-proxy pattern
(`UserAdminService.java:91-92`, `@Autowired @Lazy private UserAdminService self;`, used at `:237` as
`self.deleteUserInTransaction(...)`) exactly: add the identical field to
`AdminCoachEnforcementService`, and give the audit-log write its own `REQUIRES_NEW` method called
through that self-reference immediately after `issue()` returns. This closes the gap to the narrowest
possible window — the only remaining risk is the audit-log insert's own commit failing for a reason
unrelated to the outer transaction, not "anything at all happening later in the outer transaction."

**Why field injection, not constructor injection**, per `UserAdminService`'s own documented reasoning
(mirror this exactly in the new comment): `AdminCoachEnforcementService` uses
`@RequiredArgsConstructor`, and a constructor-injected self-reference would create an immediate
circular dependency at bean-construction time — `@Autowired @Lazy` on a separate field defers
resolution until first use, breaking the cycle.

### Tasks

1. Add `@Autowired @Lazy private AdminCoachEnforcementService self;` to `AdminCoachEnforcementService`,
   with a comment citing `UserAdminService.self`'s identical precedent and reasoning (do not
   re-derive the justification from scratch — point back to the established explanation).
2. Add a new `public` method (e.g. `recordManualStrikeAudit(UUID coachId, Long adminId, String
   reason)`) annotated `@Transactional(propagation = Propagation.REQUIRES_NEW)` that constructs and
   saves the `AdminActionLog` row currently built inline at `:302-307`. **Note:** this class currently
   imports `org.springframework.transaction.annotation.Isolation` and `.Transactional` but not
   `.Propagation` (re-verify at implementation time) — add that import; `ReliabilityStrikeService.java`
   already has the identical import if a copy-paste reference is useful. Must be `public` — Spring's
   default proxy-mode AOP silently ignores `@Transactional` on a non-public method regardless of how
   it is invoked, the exact trap `UserAdminService.deleteUserInTransaction` was already fixed for
   (deferred-122 AC8) and must not be repeated here.
3. Replace the inline `AdminActionLog` construction/save at `:302-307` in `issueManualStrike` with a
   call to `self.recordManualStrikeAudit(coachId, adminId, "Manual strike: " + reason)`, placed
   immediately after `:300`'s `issue()` call.
4. **Do not** apply this same treatment to `deleteStrike`'s or `reinstateCoach`'s own inline
   `AdminActionLog` writes (`:355`/`:263`/`:152` per the earlier grep) — this AC is scoped to the one
   finding the review raised against `issueManualStrike` specifically; those methods were not flagged
   and widening scope here is not this story's call to make silently. If a future audit wants the same
   treatment elsewhere, that is its own finding to raise.
5. **New test** proving durability. **The naive shape does not work**: after this AC's change, the
   only statements left in `issueManualStrike` after both calls are `log.info(...)` and `return
   strike.getId();` (`:309-310`) — there is no point after both calls to throw from *inside the
   method*, and a test calling `issueManualStrike` from outside any transaction of its own has already
   let the method's `@Transactional` boundary commit by the time control returns, so throwing from the
   test method at that point rolls nothing back. **The implementable shape**: have the test open its
   own `TransactionTemplate` (default `REQUIRED` propagation) around the call to `issueManualStrike`,
   so the outer method's own `@Transactional` *joins* the test's ambient transaction instead of
   managing its own; call `issueManualStrike` inside that block, then mark the transaction
   rollback-only (or throw) *after* it returns, still inside the `TransactionTemplate.execute` lambda.
   `issue()`'s own `REQUIRES_NEW` write is unaffected by the ambient rollback (by definition of
   `REQUIRES_NEW`), and the new `self.recordManualStrikeAudit` call's `REQUIRES_NEW` write should
   likewise survive — assert both the strike and the audit log via a fresh `jdbcTemplate` read *after*
   the `TransactionTemplate.execute` call returns (mirroring `ManualStrikeIT`'s own established
   `jdbcTemplate`-against-committed-state pattern, used throughout that class). A second test should
   confirm the *existing* happy path (`AdminActionLog` row present with the correct `adminId`/`reason`
   on a normal successful call, no injected rollback) still passes unchanged.
6. **Mutation-check by hand**: temporarily revert to the inline non-`REQUIRES_NEW` write and confirm
   the new durability test fails (the audit row is lost on the simulated outer rollback) before
   finalizing.
7. Re-run `ManualStrikeIT` in full — zero regressions expected; this changes only the audit write's
   transaction boundary, not `issueManualStrike`'s observable contract for any existing test.

---

## AC6 — Correct `deleteStrike`'s Java/SQL precision-mismatch comment overclaim

**Source:** `deferred-work.md`, "code review of skillars-deferred-123…", bullet 7. Documentation-only,
no functional change — mirrors this project's own established pattern for a negligible-probability
finding (e.g. deferred-123's own AC7 accepted-risk closure).

**Current text** (`AdminCoachEnforcementService.java:342-343`, re-verified at HEAD): "Matches
`countByCoachIdAndCreatedAtAfter`'s own strict `>` (a derived `...After` query), so a boundary strike
is judged identically wherever this local is used below." This overclaims — but **not for the reason
"sub-microsecond gap" describes.** `strikeCreatedAt` (`AdminCoachEnforcementService:341` region) comes
from `strikeRepository.findById(strikeId).getCreatedAt()`, read from a `timestamp with time zone`
column — it is **already microsecond-quantised** by the time the JVM ever sees it, so it cannot land
strictly inside a sub-microsecond gap between two roundings; there is no such gap to fall into. The
real (and still real, if equally negligible) divergence is **exact-equality at the microsecond
boundary itself**: `:364`'s `strikeCreatedAt.isAfter(cutoff)` compares two already-quantised instants
in the JVM, while `:365`'s `countByCoachIdAndCreatedAtAfter(coachId, cutoff)` binds the *same* `cutoff`
object through pgjdbc, which independently rounds it — if that rounding moves `cutoff` by even one
microsecond relative to how the JVM comparison saw it, a strike whose `createdAt` sits at exactly that
boundary microsecond is judged in-window by one check and out-of-window by the other. `~1e-9 per call`
does not follow from this model either — the exact-microsecond-match probability over a 30-day window
is closer to `~1e-12`–`1e-13`. Since this AC's entire deliverable is *an accurate comment*, do not
replace one imprecise mechanism with another; either state the boundary-equality mechanism precisely
or drop the numeric probability and describe it qualitatively ("astronomically unlikely," not a
specific-but-wrong exponent).

### Tasks

1. Rewrite `:342-343`'s comment to state the actual guarantee: the two comparisons use the same
   logical cutoff and the same `>`-style strictness, but `cutoff` is independently rounded on each side
   of the Java/pgjdbc boundary, so a strike whose `created_at` sits at the exact microsecond `cutoff`
   would round to is negligibly likely to be judged differently by the two checks — accepted, not
   fixed, given the probability and the absence of any `Clock`-seam-based test infrastructure to even
   deterministically exercise it (mirrors this story's AC2 Task 3 in deferred-123: "no `Clock` seam
   exists today and adding one solely for this is a larger refactor than this fix warrants"). State the
   probability qualitatively rather than repeating a specific exponent this story has not itself
   derived carefully enough to stand behind.
2. No test change required — this is a comment-accuracy fix for an already-negligible, undecided
   finding, not a new behaviour to cover.

---

## AC7 — Document the migration-vs-active-poller collision risk

**Source:** `deferred-work.md`, "code review of skillars-deferred-123…", bullet 2. **Owner decision:
document only, no migration-retry infrastructure** (see Provenance).

**The risk, generalized beyond `V144`:** any `ALTER TABLE`-shaped migration against a table an active
`@Scheduled` poller reads/writes (today: `main.video_deletion_outbox`, polled every 60s by default;
`development.radar_composite_dlq`, same cadence — but see Task 2(a) below, this codebase has **44
`@Scheduled` methods across 36 classes**, so a two-table list is not close to exhaustive) takes
`ACCESS EXCLUSIVE` and can collide with an in-flight polling transaction. `docs/deployment/migration-
conventions.md`'s existing item 7 (re-verify its exact current numbering/line range at implementation
time) already explains *why* a bounded `lock_timeout` turns an unbounded hang into a
failed-and-recoverable migration — this AC adds the missing half: that a migration on an
actively-polled table should *expect* to occasionally lose that bounded race, and what actually
happens when it does.

**What actually happens on failure — get the mechanism right, this repo's own doc already states it
correctly for a different case.** Every migration this AC is about (`V144`-shaped `ALTER TABLE`, and
AC3/AC4's own new migrations) is an ordinary **transactional** Flyway migration — no
`.sql.conf` sidecar granting `executeInTransaction=false`, and `migration-conventions.md` itself
(re-verify current line numbers, was `:99-117` at story-creation time) documents this repo
deliberately does not use that sidecar for `ALTER TABLE`-shaped changes. A `lock_timeout` abort inside
a **transactional** migration rolls the *entire* script back, schema-history bookkeeping included —
there is no failed row left in `flyway_schema_history`, and the recovery step is simply **retry the
deploy**. The "leaves a failed `flyway_schema_history` row, `flyway repair` removes it" mechanism this
doc already documents (`migration-conventions.md`, re-verify current line numbers, was `:143-147` at
story-creation time — "If this were run through Flyway rather than by hand, a failed
**non-transactional** migration also leaves a failed row…") is explicitly scoped there to the
**non-transactional** `CREATE INDEX CONCURRENTLY` sidecar case, not to a plain `ALTER TABLE`. Writing
"leaves a failed row, delete it" for the transactional case this AC is actually about would put two
contradictory descriptions of Flyway's own failure behaviour into the same document, two sections
apart — worse than not documenting it at all. (The ledger bullet this AC closes carries the identical
error, `deferred-work.md`, re-verify current line numbers, was `:2471-2472` at story-creation time —
verify against Flyway 11.7.2's actual behaviour on this project, not against the ledger's wording.)

This closes the gap without inventing new infrastructure: per skillars-deferred-117's owner decision
(re-confirmed still true — no production deploy has ever happened), the risk is currently theoretical,
and a migration-retry wrapper would be a materially larger, speculative investment against a risk that
has not yet had a single real occurrence.

### Tasks

1. Locate `migration-conventions.md`'s item 7 (the `SET lock_timeout` discussion) at implementation
   time — re-verify it is still the right home for this addition; it was chosen because the new note
   is a direct corollary of that item's own "a bounded wait turns an outage into a failed migration,
   which is the far better outcome" framing.
2. Add a sub-point (not a new top-level numbered item, to avoid renumbering the whole list) naming: (a)
   the rule, stated **structurally**, not as an enumerated table list that will read as exhaustive and
   go stale the moment a migration targets `main."user"` (swept by
   `UserAdminService.removeNotActivatedUsers`), a `payment.*` table (`PaymentPendingSweeper`), a
   moderation table, or any of the other ~40 remaining `@Scheduled` methods this codebase has:
   "**before altering any table, grep `@Scheduled` for a poller that owns it — if one exists, expect
   the migration to occasionally lose the `lock_timeout` race against it.**" Use
   `main.video_deletion_outbox`/`development.radar_composite_dlq` (confirm their current cadence via
   `@Scheduled` at implementation time — it may have changed since story creation) as the two worked
   examples, not as the complete list. Then state, correctly per the analysis above:
   (b) the expected failure mode for the ordinary transactional case this applies to — the migration
   aborts and rolls back cleanly, no partial state, no `flyway_schema_history` row left behind; (c) the
   recovery step — retry the deploy; and, separately, that *if* a non-transactional migration is ever
   introduced against a hot table in this codebase, `flyway repair` (not a hand-written `DELETE`) is
   the documented recovery for *that* shape, per this same doc's existing non-transactional-migration
   section; (d) that this is accepted as-is, not a bug to fix, per the reasoning above — and that it
   should be revisited if/when this project's own "no production deploy has ever happened" premise
   changes.
3. No code change, no test change — pure documentation.

---

## AC8 — Envers null-`verificationStatus` reconstruction: document as accepted risk, plus ledger closeout

**Source:** `deferred-work.md`, "code review of skillars-deferred-123…", bullet 4 (folded into this
AC rather than given its own, since it is a single documentation comment with no task list of its
own) — plus the standard ledger-hygiene closeout every story in this series performs.

**The finding (re-verified, latent only):** `V145` added `main.user_aud.verification_status` with no
backfill, and that audit column is nullable while `main."user".verification_status` is `NOT NULL
DEFAULT 'UNVERIFIED'` — so a pre-`V145` audit revision reconstructed via Envers would return `null`
for a field `User.java` initializes to a non-null default (`:355-357` per the ledger's citation —
re-verify against HEAD, `User.java`'s line numbers have shifted before). Confirmed still latent: `grep
-rn "AuditReader\|AuditQuery" src/main` returns nothing — no code path in this application actually
performs Envers historical reconstruction today, so this cannot currently be observed in production,
only reasoned about.

**Disposition: `[DECIDED: accepted risk — skillars-deferred-124]`, documented, not fixed.** A backfill
is not meaningfully possible (there is no historical `verificationStatus` value to backfill for
revisions that predate the field's own existence), and building `AuditReader` usage solely to test a
code path nothing in `src/main` exercises would be manufacturing coverage for a feature that does not
exist yet, not closing a real gap. Revisit if/when this codebase ever adds a real Envers-reconstruction
call site — at that point the null-vs-non-null-default mismatch becomes a genuine bug to fix, not a
latent one to document.

**Disposition-table convention, stated explicitly so AC6/AC7 don't get a different rule than this item
for the identical shape:** "accepted risk, documented via a code/doc comment, not functionally fixed,
with a stated revisit trigger" is annotated `[DECIDED]`, never deleted — deleting it would discard the
only place that revisit trigger is recorded. This item, AC6 (§ below), and AC7 (§ above) are all this
shape (AC6/AC7's own dispositions are corrected below to match, superseding what an earlier draft of
this AC's own Task 2 table said).

### Tasks

1. Add a code comment at `User.java`'s `verificationStatus` field (near its existing deferred-123 AC5
   Javadoc, re-verify the exact current line) documenting the accepted-risk disposition above, with a
   corrected citation for the field-initializer line range if it has moved.
2. **Ledger closeout** — apply this disposition table to `deferred-work.md` exactly (re-verify every
   citation against HEAD immediately before editing, not against this story's draft):

   | Ledger bullet | Disposition |
   |---|---|
   | "code review of skillars-deferred-123…": `ReliabilityStrikeService` mutual lock-out | Delete (AC1 — **disproven, not fixed**: the premise is false, see the `[DROPPED]` marker at the top of AC1's own section and the Debug Log; deleting this ledger bullet is correct either way since there is nothing real left to track) |
   | "code review of skillars-deferred-123…": V144-shaped migrations race active pollers | Annotate `[DECIDED: accepted risk — skillars-deferred-124]`, citing `migration-conventions.md`'s new sub-point (AC7) — **do not delete**: this is a documented, unfixed systemic risk with its own stated revisit trigger ("no production deploy has ever happened"), not a closed bug, exactly the same shape as the Envers row below |
   | "code review of skillars-deferred-123…": neither processor loop has a per-row try/catch | Delete (AC2 — routes through `handleFailure`, the sole call site for it after AC2's own code-review response removed the redundant inner call that could double-invoke it; a chronically-*processing*-failing row genuinely reaches `max_attempts`/`DEAD`. One narrow residual case does not — a permanently-broken `configService.getBoundedLong` lookup, not the row's own processing — and is accepted as safe self-healing rather than a poison cycle; see `RadarCompositeDlqProcessor`'s class header comment) |
   | "code review of skillars-deferred-123…": Envers null `verificationStatus` reconstruction | Annotate `[DECIDED: accepted risk — skillars-deferred-124]` with the `User.java` citation from AC8 Task 1 — do not delete |
   | "code review of skillars-deferred-123…": `issueManualStrike` commits strike before audit log | Delete (AC5 — fully fixed, nothing left to track) |
   | "code review of skillars-deferred-123…": `findClaimedBatch` exact-timestamp-equality brittleness | Delete (AC4 — fully fixed, nothing left to track) |
   | "code review of skillars-deferred-123…": `deleteStrike` Java/SQL precision-mismatch comment | Annotate `[DECIDED: accepted risk — skillars-deferred-124]`, citing the corrected comment (AC6) — **do not delete**: the underlying precision mismatch is not fixed, only accurately documented; same shape as the two rows above |
   | "code review of skillars-deferred-122…": `main."user"` `skillars_role`/`verification_status` width divergence | **Edit in place, do not delete the bullet.** This is one bullet in four parts (see Provenance) — strike only part (4), the width-divergence sentence, and annotate it `[CLOSED by skillars-deferred-124 AC3]`. Parts (1)-(3) (the `generate-ddl` root-cause chain, the pre-removal audit, and the `user_aud` CHECK closure) stay untouched — they are the historical justification for the property removal, not superseded by AC3. Executing a literal `Delete` here would destroy that record in the same commit that relies on it, repeating a mistake this file has already self-corrected twice (`deferred-work.md`, re-verify current line numbers, was `:2366-2373` and `:2397-2403` at story-creation time) |

   Net: 4 deletions, 3 `[DECIDED]` annotations, 1 in-place edit. **Do not** touch the two `[DECIDED]`
   bullets this story's own Provenance section explicitly confirmed out of scope
   (`ModerationSlaMonitorService`/`reinstateCoach`'s stale-vs-fresh-suspension note) — verify after
   editing that both are still present, unchanged, exactly as `skillars-deferred-118` through `-123`'s
   own post-edit reconstruction-check convention requires.
3. **Section headers and preambles — state explicitly what happens to each, per this file's own
   twice-recorded history of losing one wholesale while pruning bullets underneath it:**
   - `## Deferred from: code review of skillars-deferred-123…` and its italic preamble ("_Four-layer
     review (Blind Hunter, Edge Case Hunter, Acceptance Auditor,
     `txn-and-concurrency-audit`)…_") — the header **survives** (3 `[DECIDED]`-annotated bullets remain
     under it after Task 2's dispositions); the preamble is still accurate (it describes how the
     findings were produced, not their current disposition) and **survives unedited**.
   - `## Deferred from: code review of skillars-deferred-122…` — **survives**, per Task 2's in-place
     edit (the bullet is never fully removed, so the header hosting it is never empty).
   - Confirm both headers are still present, with their remaining/edited content, after every edit in
     Task 2 — this is the exact check the two prior self-corrections in this file
     (`:2366-2373`, `:2397-2403`) establish as mandatory, not optional.
4. Add a `## Last audit: <implementation date> (skillars-deferred-124 story creation)` narrative block
   matching this file's existing convention, summarizing what was checked/closed and cross-referencing
   this story rather than duplicating its content.
5. Grep-sweep the whole ledger for any other stale hit against every file this story touches
   (`ReliabilityStrikeService.java`, `VideoDeletionOutboxProcessor.java`,
   `RadarCompositeDlqProcessor.java`, `VideoDeletionOutboxRepository.java`,
   `RadarCompositeDlqRepository.java`, `AdminCoachEnforcementService.java`, `User.java`) before
   finishing — confirm no other historical bullet describes something this story's changes affect,
   per this series' own "reconfirm the ledger is exhausted for this scope" convention.

---

## Tasks / Subtasks (top-level)

- [x] AC1 — **DISPROVEN, not implemented.** `findByIdForUpdate`'s actual emitted SQL (confirmed via
  `-Dspring.jpa.properties.hibernate.show_sql=true`) is `for no key update nowait`, not
  `for update nowait` — Hibernate's `PESSIMISTIC_WRITE` on Postgres downgrades to `FOR NO KEY UPDATE`
  when no version/key column forces the stronger mode. Verified empirically against a throwaway
  Postgres 16 container: a fresh `SELECT ... FOR NO KEY UPDATE NOWAIT` from one session does **not**
  conflict with another session's live, untouched `FOR KEY SHARE` on the same row (see Dev Agent
  Record for the full empirical trail, including the control cases that confirm `FOR UPDATE NOWAIT`
  *would* have conflicted — the mechanism AC1 assumed Hibernate emits, but does not). The strike
  INSERT's FK-check lock can therefore never block `findByIdForUpdate` — there is no interleaving that
  produces AC1's claimed mutual lock-out. Implemented the prescribed fix + tightened
  `ReliabilityStrikeConcurrencyIT` (Task 5's interleave seam) anyway, as a mutation check: reran the
  tightened test against the *unfixed* ordering (Task 6) and it still passed, with the identical
  single benign lock-retry as the fixed version — proving the test cannot distinguish fixed from
  unfixed, which is what actually surfaced the false premise. Both the production reorder and the
  test tightening were reverted (working tree matches HEAD for both files); nothing shipped for AC1.
  Owner decision (AskUserQuestion, 2026-09-19): drop AC1, document the disproof rather than fix a
  non-existent bug. See AC8 for the corresponding ledger disposition change (was "Delete — fully
  fixed", now "Delete — disproven, not a genuine bug").
- [x] AC2 — per-row exception isolation on both outbox/DLQ processor loops, routed through the
  existing `handleFailure` so a poison row still reaches `max_attempts`/`DEAD` instead of cycling
  forever; new tests per processor; mutation-checked. Premise independently re-verified against the
  actual source (both `process()` loops confirmed genuinely unguarded around `processRow`, and
  `RadarCompositeDlqProcessor.handleFailure`'s own `transactionTemplate.execute` confirmed reachable
  and unguarded) before implementing — holds up, unlike AC1. `VideoDeletionOutboxProcessorIT` gained a
  new `@MockitoSpyBean DrillVideoRefRepository` (context-ceiling bump 43→44, applied in both
  `assert-context-count.sh` and `pr-build.yml`); `RadarCompositeDlqProcessorTest` needed no new mock
  (plain Mockito unit test). Both mutation-checked by hand (`git stash` the fix, tightened test fails;
  restore, passes). All four listed test classes re-run together — 26/26 green, zero regressions.
- [x] AC3 — decide widen-vs-narrow for `main."user"` `skillars_role`/`verification_status` on
  enum-length evidence (widen to `varchar(255)`, mirroring `V145`, is the likely outcome but must be
  an explicit decision); new migration; schema-shape test modeled on `EnvelopeEntitySchemaIT`.
  Premise re-verified against actual source before implementing: `V138` confirmed declaring
  `varchar(20)` for both columns, `User.java`'s `@Column` confirmed carrying no explicit `length`
  (Hibernate default 255), enum lengths confirmed (`SkillarsRole` max 6, `SkillarsVerificationStatus`
  max 14 — both well under 20), and `V138`'s own `CONSTRAINT user_*` list confirmed to have no
  `skillars_role`/`verification_status` CHECK. Widened (`V149`), matching `V145`'s precedent. New
  `UserSchemaWidthIT` (width + no-CHECK-constraint assertions) mutation-checked by hand (moved the
  migration out, width test failed as expected — the first attempt at this check silently passed
  against a stale `target/classes/db/migration` copy of the migration from an earlier build, caught by
  also clearing that directory before re-checking). `MigrationConventionLintTest` re-run — 13/13 green,
  no new violations (the `ALTER COLUMN ... TYPE` pattern is covered by `MISSING_LOCK_TIMEOUT` only, not
  `VALIDATING_CONSTRAINT`/`INLINE_FK_ADD_COLUMN`, confirmed against `MigrationLint.java`'s own regex).
- [x] AC4 — `claimed_by` UUID column on both outbox/DLQ tables, replacing `claimed_at`-equality
  identity, with `claimed_by` cleared on every transition out of `CLAIMED` (mirroring `claimed_at`'s
  own invariant) (new migration `V150`, entity/repository/processor/test updates across all 4
  predicted test files). Premise confirmed directly from source before implementing (both repositories
  read in full: every one of `findClaimedBatch`/`releaseClaimed`/`completeClaimed`/`failClaimed` on
  both `VideoDeletionOutboxRepository` and `RadarCompositeDlqRepository` did key on
  `claimed_at = :claimedAt`). `resetStaleClaimed` kept on `claimed_at` (a time predicate) but now also
  clears `claimed_by`. New `claimed_by IS NULL` assertions added to every existing test that already
  asserted `claimed_at IS NULL` post-transition. All 4 predicted test files
  (`VideoDeletionOutboxProcessorIT`, `VideoDeletionOutboxProcessorSchedulerLockTest`,
  `RadarCompositeDlqProcessorTest`, `RadarCompositeDlqRepositoryIT`) plus `MigrationConventionLintTest`
  re-run together — 39/39 green, zero regressions.
- [x] AC5 — `AdminActionLog` `REQUIRES_NEW` self-proxy write for `issueManualStrike`; new durability
  test built on a test-owned ambient `TransactionTemplate`, not a post-return throw; mutation-checked.
  Premise confirmed directly from source (`issue()`'s `REQUIRES_NEW`, `issueManualStrike`'s default
  propagation, the inline same-transaction `AdminActionLog` save, and the missing `Propagation` import
  all matched the story's description exactly). Mirrored `UserAdminService.self` exactly (`@Autowired
  @Lazy` field, `public` `REQUIRES_NEW` method). Two new `ManualStrikeIT` tests: the happy-path control
  (`issueManualStrike_normalCall_writesAuditLogWithCorrectAdminIdAndReason`, no prior test in this
  class asserted the action-log row's own `admin_id`/`reason`) and the durability proof
  (`issueManualStrike_outerTransactionRollsBack_strikeAndAuditBothSurviveViaRequiresNew`, a test-owned
  `TransactionTemplate` the method's own `@Transactional` joins, calling the service directly rather
  than through HTTP so it runs on the test's own thread). Mutation-checked by hand (reverted to the
  inline non-`REQUIRES_NEW` write, durability test failed as expected; restored, full class re-run
  green). `ManualStrikeIT` re-run in full — 15/15, zero regressions.
- [x] AC6 — correct `deleteStrike`'s Java/SQL precision-mismatch comment to the boundary-equality
  mechanism (doc-only); ledger disposition is `[DECIDED]`, not deletion. Found the actual comment at
  `:373-382` (shifted from the story's `:342-343` citation, expected per this series' own convention)
  and rewrote its tail to state the boundary-equality mechanism (strikeCreatedAt already
  microsecond-quantised on read, so the real risk is independent rounding of the shared `cutoff` value
  across the Java/pgjdbc boundary at the exact microsecond boundary) instead of the impossible
  "sub-microsecond gap" framing, dropping the specific `~1e-9` figure for a qualitative
  "astronomically unlikely" statement. Doc-only, no test change per Task 2.
- [x] AC7 — document the migration-vs-active-poller collision risk in `migration-conventions.md` as a
  structural rule (not a two-table list), correctly describing transactional-migration rollback (no
  failed `flyway_schema_history` row for this case) (doc-only); ledger disposition is `[DECIDED]`, not
  deletion. Premise verified: confirmed the doc's existing `flyway repair`/failed-row recovery mechanism
  is scoped explicitly to the non-transactional `CREATE INDEX CONCURRENTLY` sidecar case, not ordinary
  transactional DDL — writing it for the transactional case (as the original ledger bullet did) would
  have contradicted this same document a few paragraphs apart. Also independently re-counted the
  `@Scheduled` claim: `grep -rc "@Scheduled" src/main/java` — 44 occurrences across 36 files, confirmed
  exactly as stated. Added as a sub-point under item 7 (not a new numbered item). Doc-only, no test
  change per Task 3.
- [x] AC8 — Envers null-`verificationStatus` accepted-risk comment; ledger closeout per the disposition
  table, corrected once more for AC1's disproof: 4 deletions (mutual lock-out — disproven, not "fully
  fixed"; per-row try/catch, `issueManualStrike` audit, `findClaimedBatch` brittleness — all fully
  fixed), 3 `[DECIDED: accepted risk]` annotations (migration-vs-poller race, Envers null
  reconstruction, `deleteStrike` precision comment), 1 in-place edit (`main."user"` width — struck part
  (4) only, `[CLOSED by skillars-deferred-124 AC3]`, parts (1)-(3)'s history preserved). Both section
  headers and the deferred-123 preamble confirmed surviving unedited; both out-of-scope `[DECIDED]`
  bullets (`ModerationSlaMonitorService`, `reinstateCoach`) confirmed still present, untouched. New
  "Last audit: 2026-09-19" narrative block added, explicitly flagging AC1's disproof for future
  readers of this ledger. Grep-sweep of all 7 touched files against the ledger found one unrelated
  pre-existing closure marker (skillars-deferred-100 AC7, already `verified closed`) — no other stale
  hits.


### Review Findings

_`/bmad-code-review`, 2026-09-19. Four layers: Blind Hunter (diff-only), Edge Case Hunter (diff +
project), Acceptance Auditor (diff + spec), plus session-level verification of the AC1 disproof against
the resolved Hibernate artifact. 2 decision-needed (both resolved into patches), 21 patch, 4 deferred, 6 dismissed as
verified non-issues. AC1's disproof was independently re-verified and **upheld** — see the note below._

_**Response pass, 2026-09-19.** Every finding below independently re-verified against the actual source
before applying anything (explicit false-positive check — zero false positives found; all 23 actionable
findings were genuine). Both `[Decision]` items resolved via `AskUserQuestion`: AC2's double-`handleFailure`
bug via a structural single-call-site fix (better than the three options the review itself offered — see
its own checkbox below); AC4's rolling-deploy window via accept-and-document. All 21 `[Patch]` findings
applied. The 4 `[Defer]` findings were already correctly deferred by the review itself (checked, pre-existing,
out of this story's scope) and needed no further action._

> **AC1 re-verification (2026-09-19, reviewer).** The dev-story disproof is correct. Captured live from
> `ManualStrikeIT` with `org.hibernate.SQL` at DEBUG: `select ... from marketplace.coach_profiles cp1_0
> where cp1_0.id=? for no key update nowait` (13 occurrences, zero `for update`). Source is
> `PostgreSQLSqlAstTranslator.getForUpdate()` -> `" for no key update"` — the SQL-AST path a Spring Data
> `@Query` + `@Lock` renders through, not `PostgreSQLDialect.getWriteLockString`. `FOR NO KEY UPDATE`
> does not conflict with the strike INSERT's FK-check `FOR KEY SHARE`, so AC1's deadlock cannot form and
> the drop stands. An earlier reviewer claim that the jar contained no such string was a false negative
> (ugrep skips binary files without `-a`) and is withdrawn._

- [x] [Review][Decision] **AC2's new outer guard calls `handleFailure` a second time on the same detached row, double-counting `attempts` and destroying the original error.** Confirmed genuine by independent re-trace. **Resolved (2026-09-19, owner decision via AskUserQuestion): a fourth option, better than the three offered — single call site.** `processRow`'s own inner catch (the `deleteAsset` branch, the only branch that called `handleFailure` directly) was removed entirely; every exception `processRow` can throw now propagates to `process()`'s outer guard, which is the SOLE call site for `handleFailure` on both processors. This eliminates the double-invocation structurally (not via a workaround) and fixes the lost-original-error problem as a side effect, since the outer guard always receives the true original exception. Side effect requiring its own fix: promoting every ordinary recoverable failure (e.g. a Bunny.net hiccup) to the same "unexpected" log path meant the outer guard's log level had to drop from ERROR to WARN (see the separate ERROR-level finding below) — reaching `handleFailure` for backoff bookkeeping is the normal outcome, not a paging-worthy surprise; ERROR is reserved for `handleFailure` itself throwing. New regression-guard test added on the video side (`handleFailure_itselfThrows_stillIsolatesBatchAndLeavesRowClaimed`); radar's existing `handleFailure_itselfThrows_...` test now also asserts `attempts` stays at 1, not 2.
- [x] [Review][Decision] **AC4 removed a self-healing property of the old `claimed_at` identity, opening a rolling-deploy lost-update window.** Confirmed genuine. **Resolved (2026-09-19, owner decision via AskUserQuestion): accept & document**, given skillars-deferred-117's "no production deploy has ever happened" — the window is not a live risk today. Documented in two places: `VideoDeletionOutbox.claimedBy`'s Javadoc (covers the radar-side mirror too, already pointed at by `RadarCompositeDlqEntry.claimedBy`), and a new sub-point under `migration-conventions.md` rule 7 prescribing the expand/contract fix (dual predicate for one release) a first production deploy of either table must follow before this premise changes.
- [x] [Review][Patch] Production comment asserts the opposite of what the shipped test asserts — the poison-row cycle AC2 claims to eliminate is still reachable. **Fixed as a side effect of the Decision-1 refactor above** — rewrote `RadarCompositeDlqProcessor`'s class-header comment to state the accurate, narrower guarantee: a chronically-failing row's own processing reaches `max_attempts`/`DEAD`; only a permanently-broken `configService.getBoundedLong` (a config outage, not the row's own failure) does not, and that is accepted as safe self-healing, not a poison cycle. [src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessor.java:37-47]
- [x] [Review][Patch] Story AC8 disposition table still records AC1 as "Delete (AC1 — fully fixed, nothing left to track)". **Fixed** — corrected to "disproven, not fixed" in the disposition table itself, and a `[DROPPED — disproven]` marker was added at the top of AC1's own section. [AC8 disposition table, this file]
- [x] [Review][Patch] Story AC8 disposition table claims AC2 "fully fixed, not merely isolated" — contradicted by the shipped `verify(..., never()).failClaimed(...)` test. **Fixed** — reworded to state the accurate, narrower guarantee (matches the RadarCompositeDlqProcessor header-comment fix above): fully fixed for the row's-own-processing failure mode, with the config-outage residual case named explicitly as accepted-safe rather than claimed away. [AC8 disposition table, this file]
- [x] [Review][Patch] Story AC1 section (lines 77-211) still states its `FOR UPDATE NOWAIT`-conflict premise as fact with no drop marker. **Fixed** — `[DROPPED — disproven, 2026-09-19, code review]` callout added immediately under the AC1 heading, explaining the false premise and pointing to the Debug Log/ledger for the empirical trail; the historical text below it is kept as record, not fact.
- [x] [Review][Patch] Ledger's explanation of WHY `for no key update` is emitted is inaccurate. **Fixed** — corrected to the real mechanism (`PostgreSQLSqlAstTranslator.getForUpdate()`, the SQL-AST path a Spring Data `@Query` + `@Lock` renders through) with the false "PESSIMISTIC_WRITE downgrade" claim struck and attributed as an earlier draft's error. [_bmad-output/implementation-artifacts/deferred-work.md]
- [x] [Review][Patch] Ledger headline count "Closed 6 of the 7" is wrong (actual: 4 deleted, 3 annotated). **Fixed** — headline reworded to state the accurate 4-deleted/3-annotated split. [_bmad-output/implementation-artifacts/deferred-work.md]
- [x] [Review][Patch] In-place-edited deferred-122 bullet still opens "one frozen divergence remains to reconcile" after that divergence was closed. **Fixed** — reworded to "every divergence it caused is now reconciled". [_bmad-output/implementation-artifacts/deferred-work.md]
- [x] [Review][Patch] AC6's replacement comment cites `:400`/`:401`; the actual statements are at `:415`/`:416`. **Fixed** — citations corrected in place. [src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java]
- [x] [Review][Patch] AC2's new comments cite pre-change line numbers. **Fixed as a side effect of the Decision-1 refactor above** — the comments carrying stale citations were rewritten entirely (no line-number citations left in them to go stale); re-confirmed by grep that no `:205-210`/`:147`/`:169`-style citation remains anywhere in either processor. [src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessor.java]
- [x] [Review][Patch] Two class-level Javadocs still describe the removed `claimed_at = :claimedAt` identity predicate as current behaviour. **Fixed** — both updated: `VideoDeletionOutboxProcessor`'s `findClaimedBatch` sizing-basis Javadoc now notes AC4 replaced the predicate with `claimed_by`, and `VideoDeletionOutboxRepository`'s `completeClaimed`/`failClaimed` comment was generalized to name both predicates instead of asserting the old one as current. [src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessor.java, src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionOutboxRepository.java]
- [x] [Review][Patch] No test covers the video-side `catch (Exception inner)` branch or the double-`handleFailure` path. **Fixed** — new test `handleFailure_itselfThrows_stillIsolatesBatchAndLeavesRowClaimed` added, forcing the throw via a `@MockitoSpyBean`-stubbed `failClaimed` (no new `ConfigService` mock/context needed). [src/test/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessorIT.java]
- [x] [Review][Patch] Radar `handleFailure_itselfThrows_...` test never asserts `attempts`, so the double increment is invisible to it. **Fixed** — added `assertThat(rowMiddle.getAttempts()).isEqualTo(1)` with an explanatory `.as(...)`. [src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessorTest.java]
- [x] [Review][Patch] `V150`'s `ADD COLUMN IF NOT EXISTS` matches on name only — a pre-existing non-`uuid` column passes the migration and fails at first bind. **Addressed via documentation, not a functional change** — added a header note acknowledging the risk and stating why it's accepted as-is: this is the same pattern `V144` already uses for `claimed_at` on both tables, and `claimed_by` is a name invented for this migration with no other writer in the codebase. [src/main/resources/db/migration/V150__outbox_dlq_claimed_by.sql] (originally cited `:28-29`; the `ALTER TABLE` statements moved to `:42-43` once the item-9 lock header below was added — re-verify against HEAD, not this citation, before relying on it)
- [x] [Review][Patch] `UserSchemaWidthIT`'s CHECK-constraint test cannot fail for any reason related to this change. **Addressed via documentation** — Javadoc now states honestly that this test is a forward-looking regression guard uncoupled from `V149`'s own diff, and points to the width-assertion test above it as the one that actually validates this story's change. [src/test/java/com/softropic/skillars/platform/security/repo/UserSchemaWidthIT.java]
- [x] [Review][Patch] `V149`/`V150` omit `migration-conventions.md` item 9's required ACCESS EXCLUSIVE header statement — and `V150` alters the two actively-polled tables AC7's own new rule is about. **Fixed** — both migrations gained an explicit item-9 header block naming the lock (ACCESS EXCLUSIVE, brief/metadata-only in both cases), the online-safe alternative considered (none exists beyond `SET lock_timeout`'s bounded wait), and for `V150` an explicit cross-reference to AC7's own new actively-polled-table sub-point. [src/main/resources/db/migration/V149__widen_user_skillars_role_verification_status.sql, src/main/resources/db/migration/V150__outbox_dlq_claimed_by.sql]
- [x] [Review][Patch] Context-count ceiling raised 43->44 on admitted speculation, with the same unverified number duplicated in two files. **Left as-is, deliberately** — the comment already states honestly that it is unverified pending CI, per this project's "GitHub CI is the sole full-verification gate" convention; nothing in this response pass adds a further context fork (the one new test added, `handleFailure_itselfThrows_stillIsolatesBatchAndLeavesRowClaimed`, adds a bean override to the SAME test class that already forks its own dedicated context, not a new fork). CI's own run is what actually confirms or corrects the number, exactly as the existing comment already says to do. [.github/scripts/assert-context-count.sh]
- [x] [Review][Patch] `processed++` counts rows abandoned by both guards, overstating progress in the MAX_RUN_DURATION bail-out log. **Fixed** — renamed to `attempted` on both processors with an explanatory comment, and the bail-out log wording changed from "rows" to "rows attempted". [src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessor.java, src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessor.java]
- [x] [Review][Patch] `recordManualStrikeAudit` is public with a `reason` parameter that must already carry the "Manual strike: " prefix — a trap for any future caller. **Fixed** — the prefix is now built inside `recordManualStrikeAudit` itself; the caller passes the raw reason. Persisted value is unchanged (`ManualStrikeIT`'s existing `"Manual strike: COACH_NO_SHOW"` assertions still pass). [src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java]
- [x] [Review][Patch] New ERROR-level logging fires for ordinary recoverable failures, up to two ERROR lines per failing row, inconsistent with the inner catch's silence for the same outcome. **Fixed as part of the Decision-1 refactor** — the outer guard's log level dropped from ERROR to WARN (reaching `handleFailure` for backoff bookkeeping is the normal outcome), with ERROR reserved for the genuinely exceptional case (`handleFailure` itself throwing). At most one WARN + one ERROR per failing row now, not two ERRORs. [src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessor.java, src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessor.java]
- [x] [Review][Patch] Unrequested wording change altered a sentence's meaning: "re-queued" -> "re-queried". **Fixed** — reverted to the original "re-queued". [src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessor.java]
- [x] [Review][Patch] AC2's new video IT asserts `getClaimedAt()` is null on the failure path but omits the matching `getClaimedBy()` assertion AC4 added everywhere else. **Fixed** — assertion added immediately after the existing `getClaimedAt()` one. [src/test/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessorIT.java]
- [x] [Review][Defer] No `try`/`finally` around `claimPendingBatch`/`findClaimedBatch` — a throw there strands the whole claimed batch for a full stale window with `runId` lost [src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessor.java:149-150] — deferred, pre-existing
- [x] [Review][Defer] `MAX_RUN_DURATION` is sampled only between rows, and radar's `lockAtMostFor` (`PT10M`) equals its `STALE_CLAIM_WINDOW`, violating the invariant the video processor declares mandatory [src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessor.java:85] — deferred, pre-existing
- [x] [Review][Defer] `runtimeBudget_staysStrictlyInsideLock` never asserts `lockAtMostFor < staleWindow` — the one inequality radar violates [src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessorTest.java:268-282] — deferred, pre-existing
- [x] [Review][Defer] Migrations use session-scoped `SET lock_timeout` rather than transaction-scoped `SET LOCAL`, leaking the setting to later migrations on the same Flyway connection [src/main/resources/db/migration/V149__widen_user_skillars_role_verification_status.sql] — deferred, pre-existing repo-wide convention (V144, V145)

---

## Dev Notes

**Cross-AC dependencies:** AC1 and AC5 both touch code paths inside/adjacent to
`ReliabilityStrikeService.issue`/`AdminCoachEnforcementService.issueManualStrike` but are independent
fixes to independent bugs (AC1 reorders a write inside `issue()`; AC5 adds a new `REQUIRES_NEW` call
site inside `issueManualStrike`, a different method in a different class) — sequence as separate
commits so either can be reverted without the other, mirroring this series' established convention
(e.g. deferred-123's own AC1/AC2 sequencing note). AC3 and AC4 both add new Flyway migrations and may
claim adjacent version numbers — no ordering dependency between them; whichever is implemented first
takes the lower number. AC2's fix is independent of AC4's — do not conflate "add a per-row try/catch"
with "replace the claim-identity mechanism"; a reviewer should be able to revert either without
touching the other, even though both touch the same two processor classes. AC6, AC7, AC8 are
documentation-only and have no dependency on anything else in this story or on each other.

**Migration numbering:** confirm the next free Flyway version at implementation time against the
actual `src/main/resources/db/migration/` directory, not this story's assumption (`V148` was the
confirmed tip at story-creation time, per skillars-deferred-123's own final state) — a concurrent
story could have claimed `V149` since this story was drafted.

**Testing:** No `mvn verify` locally before push — GitHub CI is the sole full-verification gate for
this project (re-confirmed by this story's own immediately-preceding PR #211, which needed one CI-only
fix-up round after the dev-side suite had reported green). Run each AC's own targeted test class(es)
locally during implementation as needed; `mvn compile`/`mvn test-compile` are fine to run locally,
`mvn verify` is not.

**Migration-lint:** every new/changed migration must include `SET lock_timeout = '...'` per
`docs/deployment/migration-conventions.md`'s rule and `MigrationConventionLintTest`'s enforcement.

**CI context worth knowing before starting** (from this story's immediately-preceding PR #211):
`assert-context-count.sh`'s Spring-context ceiling is currently 43 (`pr-build.yml`'s call site). If any
new test class in this story introduces its own `@MockitoSpyBean`/`@MockitoBean`/`@TestPropertySource`
set not already used by an existing test class, it will very likely fork a new Spring context and
trip this gate — check the CI failure message's own guidance (`python3 scratchpad/ctxkeys.py`) if it
fires, and bump the ceiling with a dated justification comment mirroring the existing history in that
script, exactly as PR #211's own fix-up commit did. This is a real, not hypothetical, risk for AC1's
and AC2's new concurrency/isolation-style tests specifically, since those are the shapes most likely to
need a distinct bean-override set.

**Precision-truncation gotcha worth knowing before starting** (also from PR #211's own CI fix-up): any
new test that captures a raw `Instant.now()`/`OffsetDateTime.now()` and later asserts exact equality
against the same value round-tripped through a raw JDBC query must truncate to `ChronoUnit.MICROS`
first — Postgres `timestamptz` has microsecond precision, a bare `Instant.now()` may carry non-zero
nanosecond-level digits below that, and the mismatch is not a flake, it is a near-certainty over
enough runs. Mirrors `RescheduleResourceIT`/`RescheduleServiceConcurrencyIT`/`SoftDeleteIT`'s existing
precedent — and this story's own AC1/AC2 new concurrency tests are exactly the shape likely to need
this if they capture and later re-compare a timestamp.

---

## File List

**Reconciled against the actual final diff (`git status --short`), not the story-creation-time
expectation.** `ReliabilityStrikeService.java` and `ReliabilityStrikeConcurrencyIT.java` are
deliberately **absent** — AC1 was disproven, not implemented, and both files were reverted to HEAD
(see Change Log / Dev Agent Record). `ReliabilityStrikeServiceTest.java` needed no change either.

**Production code:**
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java`
  (AC5, AC6)
- `src/main/java/com/softropic/skillars/platform/security/repo/User.java` (AC3 Javadoc, AC8 comment)
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionOutbox.java` (AC4)
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionOutboxRepository.java` (AC4)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessor.java`
  (AC2, AC4)
- `src/main/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqEntry.java` (AC4)
- `src/main/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqRepository.java`
  (AC4)
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessor.java`
  (AC2, AC4)
- `src/main/resources/db/migration/V149__widen_user_skillars_role_verification_status.sql` (AC3, new)
- `src/main/resources/db/migration/V150__outbox_dlq_claimed_by.sql` (AC4, new)
- `.github/scripts/assert-context-count.sh` (AC2 — Spring-context ceiling bump 43→44)
- `.github/workflows/pr-build.yml` (AC2 — same ceiling bump, `pr-build.yml`'s own call site)

**Tests:**
- `src/test/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessorIT.java`
  (AC2 new isolation test + `@MockitoSpyBean DrillVideoRefRepository`; AC4 signature updates)
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessorTest.java`
  (AC2 two new tests — general isolation + the specific outer-guard case; AC4 needed no signature
  change, `any()` matchers absorb the type change)
- `src/test/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqRepositoryIT.java`
  (AC4 signature updates + `claimed_by` assertions)
- `src/test/java/com/softropic/skillars/platform/security/repo/UserSchemaWidthIT.java` (AC3, new —
  modeled on `EnvelopeEntitySchemaIT`'s `information_schema.columns` pattern)
- `src/test/java/com/softropic/skillars/platform/admin/api/ManualStrikeIT.java` (AC5 two new tests —
  happy-path control + durability proof via a test-owned ambient `TransactionTemplate`)

**Not changed, contra the story-creation-time expectation:**
`VideoDeletionOutboxProcessorSchedulerLockTest.java` (plain reflection on `@SchedulerLock`, touches
none of the changed method signatures).

**Documentation / tracking:**
- `docs/deployment/migration-conventions.md` (AC7)
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC8)
- `_bmad-output/implementation-artifacts/skillars-deferred-124-strike-lock-contention-outbox-resilience-and-schema-fixes.md`
  (this file)
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-09-19: Story created via `/bmad-create-story`. Mined the two most recent, still-open
  code-review deferrals in `deferred-work.md` per the established convention; three owner decisions
  taken live with the user (AskUserQuestion) before drafting (AC5 REQUIRES_NEW fix over
  documented-risk; AC7 document-only over migration-retry infrastructure; AC4 fix-now over
  defer-as-accepted). All line numbers verified against `master@3bba9f8d` (skillars-deferred-123's
  merge, PR #211) on 2026-09-19. 8 ACs bundled per this project's "do not create small stories"
  convention.
- 2026-09-19: `story-review.md` senior-dev audit applied (4 high, 8 medium, 6 low findings; every
  finding independently re-verified against the actual source before applying anything — explicit
  false-positive check, zero false positives found). Most significant: AC1 Task 4 was rewritten from
  "add a new test" to "tighten `ReliabilityStrikeConcurrencyIT`," which already runs the exact scenario
  described and may itself already be intermittently flaky on unfixed master for the same underlying
  reason AC1 fixes — Task 5's "deterministic" mutation check needed a named interleave seam, since a
  start-gate latch alone only synchronizes thread-start, not the specific flush-then-lock window the
  bug lives in. AC2's fix was corrected from a bare log-and-continue catch (which would have left a
  chronically-failing row cycling `CLAIMED`/`resetStaleClaimed` forever without ever reaching
  `max_attempts`/`DEAD`, since `resetStaleClaimed` never touches `attempts`) to routing through the
  existing `handleFailure` method both processors already have, closing the gap completely rather than
  only isolating the batch from it. AC3 gained an explicit widen-vs-narrow decision task (both enums'
  longest constants are 14 characters, well under the narrower option, so the ledger's own
  narrowing-risk framing does not hold once its next sentence — no production deploy has ever
  happened — is taken into account) and a corrected CHECK-constraint investigation (the expected
  finding is "no constraint exists," not "confirm whether one does" — `V145`'s add-column-with-check
  precedent does not transfer to an existing column's type-only `ALTER`). AC4 gained the missing
  `claimed_by = NULL` clearing on all four transitions out of `CLAIMED` (an omission that would have
  silently violated the exact invariant AC4's own Task 3 instructed mirroring). AC5's durability test
  mechanism was corrected — the originally-described "throw from a point after both calls" has no such
  point to throw from once the fix lands (only `log.info`/`return` remain), replaced with a test-owned
  ambient `TransactionTemplate` the method's own `@Transactional` joins. AC6's replacement comment was
  corrected from a "sub-microsecond gap" framing (impossible — `strikeCreatedAt` is already
  microsecond-quantised on read) to the actual boundary-equality mechanism, and its `~1e-9` figure
  dropped in favour of a qualitative statement rather than repeating an under-derived exponent. AC7 was
  substantially rewritten: its prescribed recovery mechanism ("leaves a failed `flyway_schema_history`
  row, delete it") contradicted this repo's own `migration-conventions.md`, which already scopes that
  mechanism to **non-transactional** migrations only — every migration this AC is about is ordinary
  transactional Flyway DDL, which rolls back cleanly on a `lock_timeout` abort with nothing to clean
  up; and its two-table scope was broadened to a structural rule ("grep `@Scheduled` for a poller
  before altering any table"), since this codebase has 44 `@Scheduled` methods across 36 classes, not
  two. AC8's disposition table was corrected in two ways: the deferred-122 ledger row is a single
  bullet in four parts (not, as first drafted, two separate bullets with one already closed) — deleting
  it wholesale per the original `Delete (AC3)` instruction would have destroyed the still-relevant
  `generate-ddl` root-cause narrative that justifies AC3 in the first place, exactly the
  delete-a-section-wholesale mistake this ledger file has already self-corrected twice; and AC6/AC7's
  rows were changed from `Delete` to `[DECIDED]` annotations to match the Envers row's own treatment of
  the identical "accepted risk, documented, not fixed, with a stated revisit trigger" shape — a
  `Delete` would have discarded each item's own revisit trigger. Explicit instructions added for what
  happens to the two ledger section headers and the deferred-123 preamble (both survive, per the
  corrected dispositions above). No scope change, no owner decision reopened — all corrections are to
  task/test executability and to the accuracy of two permanent artifacts
  (`migration-conventions.md`, `deferred-work.md`), not to what this story is for.
- 2026-09-19: `/bmad-dev-story` implementation. Per user instruction, AC2–AC8's premises were
  empirically re-verified against running code/actual source (not just re-read) before implementing
  each, following AC1's disproof (see below) — every one held up as written. **AC1 was not
  implemented: its core premise is false.** Implemented the prescribed fix and Task 5's deterministic
  mutation-check seam, then Task 6's own mutation check (revert the fix, confirm the tightened test
  fails) came back green either way — the test could not distinguish fixed from unfixed. Traced to
  `findByIdForUpdate` actually emitting `FOR NO KEY UPDATE NOWAIT` (Hibernate's `PESSIMISTIC_WRITE`
  downgrade), not `FOR UPDATE NOWAIT` as AC1 assumed; `FOR NO KEY UPDATE` does not conflict with the
  strike INSERT's FK-check `FOR KEY SHARE` (confirmed against a throwaway Postgres 16 container,
  independent of the app). No interleaving of the described code paths can produce the claimed
  deadlock. Both changed files reverted to HEAD; user decision (AskUserQuestion) was to drop AC1 and
  document the disproof rather than land a fix for a non-existent bug — recorded in this story's Dev
  Agent Record, the ledger's own new "Last audit" block, and AC8's disposition table (the mutual
  lock-out bullet deleted as disproven, not as fixed). AC2 (per-row exception isolation, both
  processors) implemented as specified — premise held up exactly (both loops confirmed genuinely
  unguarded around `processRow`, `RadarCompositeDlqProcessor.handleFailure`'s own transaction confirmed
  reachable and unguarded); new isolation tests per processor, both mutation-checked; `assert-context-
  count.sh` ceiling bumped 43→44 for the video side's new `@MockitoSpyBean`. AC3 (`main."user"` width
  reconciliation) implemented with the widen direction the story called likely, confirmed by the
  enum-length evidence and the "no CHECK exists" expectation, both re-verified directly against source;
  `V149` plus a new `UserSchemaWidthIT`, mutation-checked (the first mutation-check attempt false-
  passed against a stale `target/classes` copy of the migration — caught by clearing that directory and
  re-checking, a reminder that a green mutation check against a compiled-artifact directory is not
  automatically trustworthy). AC4 (`claimed_by` UUID identity column) implemented as specified across
  both repositories/processors/entities and all four predicted test files; premise (every identity
  predicate keyed on `claimed_at`) confirmed by reading both repositories in full before touching
  either. AC5 (`REQUIRES_NEW` self-proxy audit write) implemented exactly per `UserAdminService.self`'s
  established pattern; two new `ManualStrikeIT` tests (happy-path control, durability proof via a
  test-owned ambient `TransactionTemplate`), mutation-checked. AC6 (comment correction) and AC7
  (migration-conventions.md documentation) applied as specified, both doc-only, both premises
  independently re-verified (AC6: the boundary-equality mechanism, not "sub-microsecond gap"; AC7: the
  `flyway repair` recovery mechanism confirmed scoped to the non-transactional sidecar case only, and
  the "44 `@Scheduled` methods across 36 classes" figure independently re-counted and confirmed exact).
  AC8's ledger closeout applied per the disposition table, itself corrected once more for AC1's own
  disproof (4 deletions net, not the originally-planned "delete AC1 as fixed" — disproven instead;
  3 `[DECIDED: accepted risk]` annotations; 1 in-place edit for the `main."user"` width bullet). All
  touched suites re-run together per AC (26, 39, 15, 37 tests across the four regression sweeps) plus
  each fix's own targeted mutation check — zero regressions, zero failures. No `mvn verify` run locally
  per project convention — GitHub CI is the sole full-verification gate.
- 2026-09-19: `/bmad-code-review` response pass. Every finding independently re-verified against actual
  source before applying anything — explicit false-positive check, zero false positives found (all 23
  actionable findings — 2 `[Decision]`, 21 `[Patch]` — were genuine; the 4 `[Defer]` findings needed no
  action, already correctly deferred by the review itself). Both `[Decision]` items resolved via
  `AskUserQuestion`. **AC2's double-`handleFailure` bug**: traced the exact interleaving by hand and
  found a structural fix better than the three options the review offered — `processRow`'s own inner
  catch (the only place that called `handleFailure` directly, pre-existing since before this story) was
  removed on both processors, making `process()`'s outer guard the sole call site. This eliminates the
  double-invocation and the lost-original-error problem together, as one change, rather than via
  snapshot/restore or a log-only backstop. Required a follow-on fix the review didn't anticipate: routing
  every ordinary recoverable failure (not just genuinely unexpected ones) through the same guard meant
  its log level had to drop from ERROR to WARN, reserving ERROR for `handleFailure` itself throwing —
  this closed the separate "ERROR-level logging for ordinary recoverable failures" finding as a side
  effect. **AC4's rolling-deploy lost-update window**: accept-and-document, per skillars-deferred-117's
  "no production deploy has ever happened" — documented in `VideoDeletionOutbox.claimedBy`'s Javadoc
  (already shared by the radar-side entity) and a new `migration-conventions.md` rule-7 sub-point
  prescribing the expand/contract fix a first production deploy must apply. All 21 `[Patch]` findings
  applied: the story's own AC1 section marked `[DROPPED — disproven]` in place (text kept as historical
  record, not fact) and its AC8 disposition-table rows for AC1/AC2 corrected; `deferred-work.md`'s
  "Closed 6 of the 7" headline, its stale `generate-ddl` bullet opening, and its inaccurate `for no key
  update` mechanism explanation all corrected; stale line-number citations fixed or removed at the
  source (rewritten comments no longer cite line numbers that can go stale); two stale
  claimed-identity Javadocs updated; `recordManualStrikeAudit`'s prefix-parameter trap closed by moving
  prefix construction inside the method (persisted value unchanged, existing tests still pass); `V149`/
  `V150` gained explicit migration-conventions.md item-9 lock headers, with `V150` cross-referencing
  AC7's own new actively-polled-table rule; a reverted unrequested wording change
  ("re-queried" -> "re-queued"); `processed` renamed to `attempted` on both processors to stop
  overstating progress in the bail-out log. Two test gaps closed: a new video-side test
  (`handleFailure_itselfThrows_stillIsolatesBatchAndLeavesRowClaimed`, forcing the throw via a
  `@MockitoSpyBean`-stubbed `failClaimed` rather than a new `ConfigService` mock/context) proves the
  `catch (Exception inner)` branch and regression-guards the double-call fix; the radar side's existing
  equivalent test gained an `attempts == 1` assertion for the same reason; the video isolation test
  gained the `getClaimedBy()` assertion it was missing. Two documentation-only findings (`V150`'s
  name-only `ADD COLUMN IF NOT EXISTS` match, `UserSchemaWidthIT`'s CHECK-constraint test being
  uncoupled from `V149`'s own diff) addressed via clarifying comments rather than functional changes,
  both consistent with existing repo precedent. One finding left as-is, deliberately:
  `assert-context-count.sh`'s 43->44 ceiling bump is already honestly self-documented as pending CI
  confirmation per this project's own "CI is the sole verification gate" convention, and this pass's one
  new test does not add a further context fork (same test class, already-forked context). No `mvn
  verify` run locally per project convention — GitHub CI is the sole full-verification gate.

## Dev Agent Record

### Debug Log

**AC1 — mutual-lock-out premise disproven (2026-09-19).**

1. Implemented the prescribed fix (moved strike construction/`save()` to immediately after
   `withBoundedRetry` succeeds, before `alreadyOffMarketplace`) and Task 5's deterministic interleave
   seam in `ReliabilityStrikeConcurrencyIT` (a `@MockitoSpyBean CoachProfileRepository` blocking
   `findByIdForUpdate` on a 2-count `CountDownLatch` until both racing threads arrive, guaranteeing
   both have already flushed their own strike INSERT before either attempts the coach-row lock).
   `mvn -o -DskipFrontend -Dtest=ReliabilityStrikeConcurrencyIT test` — green, one benign lock-retry
   observed (`SQLState 55P03`, immediately resolved on the next attempt).
2. Task 6 mutation check: `git stash push` the production reorder only (test unchanged), reran the
   same command. **Still green, with the identical single lock-retry.** A test that cannot
   distinguish the fixed ordering from the unfixed one has not validated the fix — this is what
   surfaced the false premise, not a hunch.
3. Reran with `-Dspring.jpa.properties.hibernate.show_sql=true`: `findByIdForUpdate` emits
   `for no key update nowait`, not `for update nowait`. Hibernate 6's `PESSIMISTIC_WRITE` on the
   Postgres dialect downgrades to `FOR NO KEY UPDATE` whenever no version/key column forces the
   stronger `FOR UPDATE` — this entity has neither.
4. Isolated the real Postgres semantics against a throwaway `postgres:16` container (`docker run`,
   plain SQL via two coprocess `psql` sessions over named FIFOs — not through the app), independent of
   any JPA/Hibernate behaviour:
   - Baseline: session A holds `FOR KEY SHARE` (via a real FK-checked INSERT, untouched). Session B's
     fresh `SELECT ... FOR UPDATE NOWAIT` **fails** (`could not obtain lock`) — confirms the
     `FOR UPDATE`-conflicts-`FOR KEY SHARE` half of AC1's premise is correct in isolation.
   - The actual mechanism: same setup, but B issues `SELECT ... FOR NO KEY UPDATE NOWAIT` instead —
     **succeeds immediately**, despite A's `FOR KEY SHARE` being fully live and untouched. This is the
     lock mode Hibernate actually emits, and it does not conflict with `FOR KEY SHARE`.
   - Also checked (out of caution, since the app-level mutation-check run showed one thread failing
     and the other succeeding within ~200µs — a real but unrelated same-row `FOR NO KEY UPDATE` vs.
     `FOR NO KEY UPDATE` race, the ordinary contention `PessimisticLockRetryer` was already built for
     since skillars-deferred-100): confirmed a savepoint-scoped failed lock attempt does **not**
     release a lock acquired before the savepoint (session A's pre-savepoint `FOR KEY SHARE` survives
     both the failed attempt and the following `ROLLBACK TO SAVEPOINT`) — ruling out "the retry
     silently drops the earlier lock" as an alternative explanation for the observed pass.
5. Conclusion: the strike INSERT's FK-check `FOR KEY SHARE` and `findByIdForUpdate`'s actual
   `FOR NO KEY UPDATE NOWAIT` cannot conflict under any interleaving. AC1 as written describes a bug
   that cannot occur in this codebase's actual lock configuration. Reverted both files
   (`git checkout --`) to HEAD — no AC1 changes shipped. User decision (AskUserQuestion): drop AC1,
   document the disproof (this entry) rather than land a fix + comments asserting a disproven
   mechanism as fact. User also asked that remaining ACs (2–8) be empirically re-verified against
   running code before implementing, not just re-read, given a prior senior-dev review pass
   (`story-review.md`) already missed this.

### Completion Notes

**AC1 — not implemented, disproven.** See Debug Log above for the full empirical trail. No production
or test change shipped; the ledger and story both record the disproof for future readers.

**AC2 — done.** Loop-level per-row exception isolation added to both `VideoDeletionOutboxProcessor` and
`RadarCompositeDlqProcessor`, routed through the existing `handleFailure` (itself further guarded) so a
poison row still reaches `max_attempts`/`DEAD` rather than cycling forever. New tests per processor
prove batch isolation and that the specific new outer-guard code path (not just the pre-existing inner
try/catch) is exercised; both mutation-checked by hand.

**AC3 — done.** `main."user".skillars_role`/`verification_status` widened to `varchar(255)` (`V149`),
matching every already-booted environment and mirroring `V145`'s precedent. No CHECK constraint added
— confirmed none exists in any environment for these two columns. New `UserSchemaWidthIT`, mutation-
checked.

**AC4 — done.** `claimed_by` UUID column added to both outbox/DLQ tables (`V150`), replacing
`claimed_at`-exact-equality as the run-identity predicate across `findClaimedBatch`/`releaseClaimed`/
`completeClaimed`/`failClaimed` on both repositories and processors; `claimed_at` unchanged for
`resetStaleClaimed`'s staleness math. `claimed_by` cleared on every transition out of `CLAIMED`,
mirroring `claimed_at`'s own invariant, with new assertions everywhere an existing test already
asserted `claimed_at IS NULL` post-transition.

**AC5 — done.** `AdminActionLog`'s write in `issueManualStrike` now goes through a `self`-proxied
`REQUIRES_NEW` method (`recordManualStrikeAudit`), mirroring `UserAdminService.self` exactly, closing
the durability gap where an outer-transaction rollback after `issue()`'s own `REQUIRES_NEW` commit
could leave a real enforcement action with zero audit trail. Two new `ManualStrikeIT` tests, mutation-
checked.

**AC6 — done.** `deleteStrike`'s cutoff-precision comment corrected to the actual boundary-equality
mechanism; doc-only.

**AC7 — done.** Migration-vs-active-poller collision risk documented as a structural rule in
`migration-conventions.md` item 7, with the corrected transactional-vs-non-transactional recovery
mechanism; doc-only.

**AC8 — done.** Envers null-`verificationStatus` accepted-risk comment added at `User.java`. Ledger
closeout applied per the (AC1-corrected) disposition table; both section headers, the deferred-123
preamble, and both untouched out-of-scope `[DECIDED]` bullets confirmed surviving. New "Last audit"
narrative block added, explicitly flagging AC1's disproof.

**Overall.** 7 of 8 ACs implemented and independently verified; AC1 investigated to completion and
disproven rather than implemented, per explicit owner decision. Every AC's premise was empirically
re-verified against running code or actual source before implementation (not just re-read from the
story text) — the practice that surfaced AC1's false premise, applied consistently to the rest. No
regressions across any touched suite, across four separate regression sweeps (26 tests for AC2's own
processors; 39 once `MigrationConventionLintTest` joined for AC4; 15 for AC5's `ManualStrikeIT`; 37 for
a final security/payment/admin cross-check sweep covering everything AC1's investigation and AC3/AC5/
AC6 touched), plus each individual fix's own standalone mutation check. No `mvn verify` run locally —
GitHub CI is the sole full-verification gate per this project's established convention.
