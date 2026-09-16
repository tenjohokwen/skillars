# Story: Subscription Scheduler Transaction-Isolation & Cluster-Lock Hardening

**Story Key:** `skillars-deferred-116-subscription-scheduler-transaction-isolation-hardening`
**Epic:** Deferred Work
**Priority:** Medium (one silent-batch-rollback resilience gap, one cluster-safety gap — same bug class as an already-fixed sibling)
**Status:** review
**Created:** 2026-09-16

---

## Provenance & Scoping (read before starting)

This story was mined from `_bmad-output/implementation-artifacts/deferred-work.md`'s
`## Deferred from: ad-hoc audit of payment module subscription schedulers (2026-09-16)` section — a
fresh finding, not a pre-existing ledger bullet, surfaced by applying `skillars-deferred-115`'s own audit
lens (transaction-boundary/TOCTOU patterns across every `@Scheduled`/batch-processing class) to the one
area deferred-115 explicitly declared out of scope: booking/payment/messaging. Line citations below were
verified against HEAD at story-creation time by reading the actual files, not trusted from the ledger
text.

**Scope was deliberately narrow.** This audit covered only `platform.payment.service`'s two subscription
schedulers — `SubscriptionService.applyPendingChanges()`/`checkPastDueGracePeriod()`, driven by
`SubscriptionChangeApplicator`/`SubscriptionGracePeriodChecker` — found deviating from this codebase's
own established "short transaction for the batch SELECT, separate transaction per row, `@SchedulerLock`
on the sweep method" pattern, which every other stateful `@Scheduled` job in booking/payment already
follows (`BookingExpiryScheduler`, `BookingReminderScheduler`, `PaymentPendingSweeper`,
`SessionPackExpiryNotifier`, `SessionPackForfeitureScheduler`). Do **not** re-audit those other
schedulers as part of this story — they already follow the pattern — and do not extend this story's
scope beyond the two named methods and their two wrapper classes.

| # | Finding | Outcome |
|---|---|---|
| 1 | `SubscriptionService.applyPendingChanges()` — one bad row rolls back the whole day's coach+player downgrade batch | **AC1 below** — direct fix, mirroring an existing sibling pattern. |
| 2 | `SubscriptionService.checkPastDueGracePeriod()` — identical shape, same risk | **AC2 below** — direct fix, same pattern. |
| 3 | Neither scheduler wrapper class carries `@SchedulerLock` | **AC3 below** — direct fix, mirroring every other stateful scheduler in this module. |

AC4 is the standard ledger-hygiene closeout every `skillars-deferred-*` story in this project performs.

---

## User Story

As a **Platform Engineer**, I want `SubscriptionService.applyPendingChanges()` and
`checkPastDueGracePeriod()` hardened with the same per-item failure isolation and cluster-wide mutual
exclusion every sibling scheduler in the booking/payment module already has, so that **one malformed or
concurrently-modified subscription row can no longer silently drop an entire day's scheduled downgrades
or grace-period cancellations for every other coach and player, and a lock-expiry double-run can no
longer double-apply a tier change**.

---

## Acceptance Criteria

### AC1: `SubscriptionService.applyPendingChanges()` — per-item transaction isolation

**Given** `applyPendingChanges()` (`SubscriptionService.java:445-477`) carries a method-level
`@Transactional` wrapping the entire body — both the `coachSubscriptionChangeRepository.findPendingForScheduler(now)`
read and the full `for` loop applying every pending coach downgrade, then the identical read+loop for
every pending player downgrade,

**And** each coach iteration calls `syncMarketplaceTier(change.getCoachId(), change.getToTier())`
(`SubscriptionService.java:455`, method body at `:631-643`), which does
`CoachSubscriptionTier.valueOf(tier)` — a `String`-keyed enum lookup with **no try/catch anywhere in the
call chain**,

**And** the source of that string, `payment.coach_subscription_changes.to_tier` /
`payment.player_subscription_changes.to_tier`, is an unconstrained `character varying(20)`
(`V138__baseline_schema.sql:1780,1844` — confirmed no `CHECK` constraint, no FK tying either column to
`CoachSubscriptionTier`'s three values),

**When** the scheduler runs (`SubscriptionChangeApplicator`, daily at `0 0 2 * * *`) and any single
pending change's `to_tier` fails `CoachSubscriptionTier.valueOf(...)` — a value that was never a valid
tier name to begin with, a future enum-rename leaving a stale row behind, or any other data-integrity
drift between the write path and this read path,

**Then today**: `IllegalArgumentException` propagates out of the `for` loop, out of `applyPendingChanges()`,
uncaught (no custom `TaskScheduler` `ErrorHandler` is configured anywhere in this codebase — confirmed by
grep — so Spring's default `LoggingErrorHandler` is the only thing that fires). The single `@Transactional`
rolls back **every** coach and player downgrade batched into that run, not just the offending row.
Recovery window is a full day (the job's own cron interval), not the sub-minute self-heal every sibling
scheduler in this module gets from its own per-item isolation.

**This is the exact same bug class `skillars-deferred-115` fixed for `VideoLifecycleScheduler`** — a
batch of independent per-entity work items wrapped in one transaction, where one item's failure takes
down every sibling item in the same run. Unlike `ModerationSlaMonitorService` in that story (which had an
existing `REQUIRES_NEW`-per-item partial mitigation the team had to weigh against), **there is no existing
mitigation here at all** — this is a direct fix, not a decision point.

**Confirmed by re-reading the actual native query** (`CoachSubscriptionChangeRepository.findPendingForScheduler`/
`PlayerSubscriptionChangeRepository.findPendingForScheduler`, both `SELECT ... WHERE applied = false AND
voided_at IS NULL AND effective_at <= :cutoff ... FOR UPDATE SKIP LOCKED`): the "leave `applied = false` on
failure and it's naturally re-selected" assumption below is correct — the query filters on exactly that
column, not a composite condition that could silently exclude it. Also note the query already carries
`FOR UPDATE SKIP LOCKED` — it must keep running inside an active transaction for that clause to do
anything; when the batch load moves into its own short transaction (item 1 below), that transaction's
commit is what releases the row locks before the per-item work begins, same mechanism as every sibling
scheduler.

**Fix, mirroring `SessionPackForfeitureScheduler`'s established pattern in this same module**
(`SessionPackForfeitureScheduler.java:33-89` — short-transaction batch load via `transactionTemplate.execute(...)`,
then a `for` loop where each iteration gets its own `transactionTemplate.execute(...)` wrapped in
`try/catch (Exception e) { log.error(...); }`):
1. Load `coachChanges/playerChanges` in a short, separate transaction (or accept the repository read's own
   default transaction if it is not nested inside a longer one — decide based on whether `applyPendingChanges()`
   keeps a method-level `@Transactional` at all after this fix; it should not, per the sibling pattern).
2. Process each coach change, and each player change, in its own transaction (`transactionTemplate.execute(...)`),
   wrapped in `try/catch (Exception e)`: `log.error` with the change's id and the exception, then continue —
   one bad row no longer aborts its sibling rows or the other entity type's loop.
3. Do not silently swallow a malformed `to_tier`: keep the change row's `applied` flag `false` on failure
   (do not mark it applied) so it is naturally retried on the next day's run once the underlying data issue
   is fixed, mirroring `SessionPackForfeitureScheduler`'s "keep re-selecting until fixed" stance for a
   genuine data-integrity failure (as opposed to its blank-email case, which *does* stamp-and-skip because
   a retry cannot change that outcome — a malformed tier string, by contrast, *can* be fixed by a data
   correction, so re-selection is the right default here).

**Not applicable — checked and ruled out:** `applyPendingChanges()` never publishes `SubscriptionExpiredEvent`
(confirmed by grep — the method's only reference to it is a comment explaining why it *doesn't*, line
474-475; the sole `eventPublisher.publishEvent(new SubscriptionExpiredEvent(...))` call in this class is
in `handleSubscriptionDeleted()`, an unrelated webhook-driven method this story does not touch). Do not
spend implementation time on event-ordering-vs-transaction-boundary concerns for this AC — there is no
event here to order.

**Verified by:**
- A test forcing `CoachSubscriptionTier.valueOf(...)` (or the direct DB-level equivalent — an
  intentionally-invalid `to_tier` row) to throw for one pending coach change in a multi-change batch that
  also includes at least one valid coach change and at least one valid player change, asserting: the valid
  coach change and the valid player change are still applied in the same run, and the failing change's
  `applied` flag remains `false` (not marked applied, not silently dropped).
- The symmetric case — one pending **player** change fails, at least one valid coach change and one valid
  player change are in the same batch — asserting the valid changes on both sides still apply. (Coach and
  player changes are two separate `for` loops over two separate lists; a naive implementation could bail
  out of the whole method on the first loop's uncaught exception before the second loop ever runs — assert
  this cannot happen once the fix lands.)
- A test where **both** a coach change and a player change fail in the same run — asserting neither loop
  aborts the other, both failing changes keep `applied = false`, and any other valid changes in the same
  batch still apply.
- Existing `SubscriptionService` test coverage (check `SubscriptionLifecycleIT.java`,
  `PastDueGracePeriodTest.java`, `TierEntitlementGatingTest.java` for any existing
  `applyPendingChanges`/`checkPastDueGracePeriod` coverage before writing new tests — none was found at
  story-creation time, so this is likely new test-class territory, not an extension) re-run green.

**Ledger:** [`src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java:445-477,631-643`,
`src/main/java/com/softropic/skillars/platform/payment/repo/CoachSubscriptionChangeRepository.java:12-21`,
`src/main/resources/db/migration/V138__baseline_schema.sql:1780,1844`]

---

### AC2: `SubscriptionService.checkPastDueGracePeriod()` — per-item transaction isolation

**Given** `checkPastDueGracePeriod()` (`SubscriptionService.java:481-503`) has the identical shape to
AC1's method: one method-level `@Transactional` wrapping the `paymentCoachSubscriptionRepository.findByStatusAndPastDueSinceBefore(...)`
read and its `for` loop (downgrading each past-due coach to the literal `SCOUT`/`CANCELLED` and calling
`syncMarketplaceTier`), then the identical read+loop for past-due players (downgrading to the literal
`ATHLETE`/`CANCELLED`, no `syncMarketplaceTier` call for players — players have no marketplace-visible
tier, confirmed by reading both loops; this asymmetry is intentional, not a gap),

**When** the scheduler runs (`SubscriptionGracePeriodChecker`, daily at `0 0 3 * * *`) and any single
past-due subscription's downgrade write fails — the `CoachSubscriptionTier.valueOf("SCOUT")` call inside
`syncMarketplaceTier` cannot itself fail (the literal is a valid enum constant); a DB-level failure
(connectivity blip, constraint violation on the `marketplace.coach_subscriptions` insert path — see the
"pre-existing, out of scope" note below) is the realistic failure source, not an optimistic-lock
exception,

**Correction to an earlier draft of this AC:** `PaymentCoachSubscription`, `PaymentPlayerSubscription`,
and marketplace `CoachSubscription` all carry **no `@Version` field** (confirmed by reading all three
entities directly) — there is no optimistic-locking mechanism on any of them, so a concurrent write
(e.g. a webhook-driven status update landing mid-sweep) does **not** throw an exception at all. It
silently last-write-wins: whichever transaction commits second overwrites the other's change with no
error, no retry, no log line pointing at the collision. **This is a real, pre-existing data-integrity
gap, but it is not new here and not something this story's restructure changes** — the race window
between one row's read and its write is the same length whether that row's write sits inside one giant
method-level transaction (today) or its own short per-item transaction (after this fix); only the
*blast radius* of an actual thrown exception changes, and a silent overwrite never throws one. Treat
this as an accepted, out-of-scope risk (same shape as the `skillars-11-1` payment-parity items already
sitting in `deferred-work.md`, filed separately) — do not add `@Version` to any of these three entities
as part of this story; that is a bigger, cross-cutting change (every write path to all three tables
would need updating) that deserves its own story if ever prioritized.

**Then today**: on a genuine write failure (not a lost-update, which is silent), identical failure mode
to AC1 — the single `@Transactional` rolls back every other past-due coach's and player's grace-period
cancellation for that entire run, not just the failing row.

**Fix:** identical restructure to AC1 — short-transaction batch load, per-item `transactionTemplate.execute(...)`
wrapped in `try/catch (Exception e) { log.error(...); }`. A failed row is naturally re-selected on
tomorrow's run (the query filters on `status = 'PAST_DUE'` and a cutoff timestamp — a row that failed to
downgrade is still `PAST_DUE` and still past the cutoff, so it stays eligible with no extra bookkeeping
needed, unlike AC1's `applied` flag). Note the one way this *could* change: if the same lost-update race
above causes a concurrent write to move the row's `status` off `PAST_DUE` or push `pastDueSince` past the
cutoff between this run and tomorrow's, the row legitimately stops being selected — that is correct
behavior (the subscriber is no longer past-due), not a bug.

**Verified by:**
- A test forcing one past-due coach's (or player's) downgrade write to throw (e.g. a repository `save`
  stub that throws) in a multi-row batch that also includes at least one succeeding coach and one
  succeeding player, asserting the succeeding rows are still downgraded/cancelled in the same run.
- A test where **both** a coach downgrade and a player downgrade fail in the same run — asserting
  neither loop aborts the other and any other valid rows in the same batch still process.
- `PastDueGracePeriodTest.java`'s existing coverage (grace-period-boundary cases) re-run green — this is
  the one existing test file directly exercising this method; read it first to match its established
  Mockito-based test shape rather than introducing a different pattern.

**Ledger:** [`src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java:481-503`]

---

### AC3: `@SchedulerLock` on both scheduler wrapper classes

**Given** `SubscriptionChangeApplicator.applyPendingChanges()` and
`SubscriptionGracePeriodChecker.checkGracePeriods()` (both trivial `@Scheduled`-annotated one-line
delegators) carry no `@SchedulerLock`, unlike every other stateful `@Scheduled` job in this module
(`BookingExpiryScheduler`, `BookingReminderScheduler`, `PaymentPendingSweeper`,
`SessionPackExpiryNotifier`, `SessionPackForfeitureScheduler` — all confirmed via grep),

**When** a run takes long enough to overlap with the next day's firing (unlikely at today's data volumes,
but not bounded by anything in the code — AC1/AC2's own restructure to per-item transactions makes a slow
run *more* likely, not less, since it trades one fast bulk operation for N small ones) — or, more
realistically, when this application runs more than one instance (multiple pods/replicas), both instances'
schedulers fire on the same cron tick with no coordination,

**Then today**: nothing prevents a coach/player's pending change from being applied twice (idempotent in
outcome for `applyPendingChanges` — reapplying the same `toTier` is a no-op — but the `change.setApplied(true)`
write would race between the two instances) and nothing prevents `checkPastDueGracePeriod`'s
cancellation-and-downgrade from double-firing.

**This AC is a correctness dependency for AC1/AC2, not an independent nice-to-have — land all three
together.** `findPendingForScheduler`'s `FOR UPDATE SKIP LOCKED` clause only claims a row for the
lifetime of the transaction that reads it. Under AC1/AC2's restructure, that transaction is now the
*short* batch-load transaction, not the whole method — its lock releases as soon as it commits, well
before the per-item write happens. Without `@SchedulerLock`, a second concurrent run (a second pod, or
this same pod's next tick if a run somehow overruns) could re-select the same not-yet-`applied` rows in
the gap between the first run's batch-load commit and its per-item writes completing. `@SchedulerLock` is
what closes that gap — implement it in the same change as AC1/AC2, not as a follow-up.

**Fix:** add `@SchedulerLock` to `SubscriptionChangeApplicator.applyPendingChanges()` and
`SubscriptionGracePeriodChecker.checkGracePeriods()`, mirroring `SessionPackForfeitureScheduler`'s
`@SchedulerLock(name = "...", lockAtMostFor = "PT15M", lockAtLeastFor = "PT2M")` shape. Size
`lockAtMostFor` the same way `skillars-deferred-115` sized `VideoLifecycleScheduler`'s — worst-case
arithmetic documented in a code comment (expected row count at today's scale × expected per-row latency,
contrasted with a worst-case per-row latency, plus margin), **not** a live production-DB query (no other
scheduler story in this codebase has done that, and this story has no DB access to do it with). There is
no config-bound batch-size ceiling on either query today, unlike `VideoLifecycleScheduler`'s — note this
as a known gap in the story but do not scope-creep into adding one unless implementation reveals it is
trivial to do consistently with the AC1/AC2 restructure. `lockAtLeastFor = "PT2M"` is carried over from
`SessionPackForfeitureScheduler` for consistency, not because a daily cron needs a 2-minute minimum on
its own merits — it is a cheap defensive floor against a pathological fast-fail-and-immediately-refire
edge case, not load-bearing for this story's correctness; say so in the code comment rather than
implying it was independently derived.

**Defense-in-depth note:** `@SchedulerLock` is what actually prevents the double-apply scenario described
above. The "reapplying the same `toTier` is a no-op" idempotency is a secondary safety net, not the
primary defense — if `@SchedulerLock` is ever removed or misconfigured later, that idempotency is what
stands between a lock regression and an actual double-write; worth one comment line noting this so a
future reader doesn't mistake the lock for optional.

**Verified by:** annotation presence + sensible `lockAtMostFor`/`lockAtLeastFor` values, confirmed via the
same pattern used for any existing `@SchedulerLock` test in this module (check
`SessionPackForfeitureScheduler`'s or `PaymentPendingSweeper`'s own test suite first for a precedent to
extend rather than inventing a new one).

**Ledger:** [`src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionChangeApplicator.java`,
`src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionGracePeriodChecker.java`,
`src/main/java/com/softropic/skillars/platform/payment/service/SessionPackForfeitureScheduler.java:32-34`
(the exact sibling pattern to mirror)]

---

### AC4: Ledger hygiene

**Given** this story closes all three findings in `deferred-work.md`'s
`## Deferred from: ad-hoc audit of payment module subscription schedulers (2026-09-16)` section,

**Then**: delete all three bullets outright per this file's own stated convention (delete on a real fix,
not a decision — none of AC1/AC2/AC3 needed an owner decision). Reconstruction check: every surviving
line in the target section must match the pre-edit content, in order, with only the three bullets and
their own intro paragraph's provenance sentence remaining accurate (the section's intro paragraph itself
may be left in place or removed with the last bullet — developer's call, consistent with how prior
stories have handled a fully-closed section).

**Verified by:** a diff of the section before/after showing only the expected deletions, no unrelated
changes; grep confirms no other section of `deferred-work.md` was touched.

**Ledger:** [`_bmad-output/implementation-artifacts/deferred-work.md` — the
`## Deferred from: ad-hoc audit of payment module subscription schedulers (2026-09-16)` section]

---

## Tasks/Subtasks

- [x] **Task 1 — AC1: `applyPendingChanges()` hardening**
  - [x] Add `TransactionTemplate` as a new constructor dependency to `SubscriptionService`
  - [x] Restructure into short-transaction batch load + per-item `transactionTemplate.execute(...)` +
        `try/catch (Exception e) { log.error(...); }`, mirroring `SessionPackForfeitureScheduler`
  - [x] Confirm a failed change's `applied` flag stays `false` (naturally re-selected next run)
  - [x] Add `@Mock TransactionTemplate transactionTemplate` to `PastDueGracePeriodTest.java` (and
        `TierEntitlementGatingTest.java` if it turns out to need it), stubbed to invoke its callback —
        verify all pre-existing tests in both files still pass unmodified otherwise
  - [x] Test(s) per AC1's "Verified by" (including the both-coach-and-player-fail case)

- [x] **Task 2 — AC2: `checkPastDueGracePeriod()` hardening**
  - [x] Identical restructure to Task 1 (same `TransactionTemplate` dependency, already added in Task 1)
  - [x] Test(s) per AC2's "Verified by" (including the both-fail case)

- [x] **Task 3 — AC3: `@SchedulerLock` on both wrapper classes**
  - [x] Add `@SchedulerLock` to `SubscriptionChangeApplicator.applyPendingChanges()` and
        `SubscriptionGracePeriodChecker.checkGracePeriods()`, sized and documented per AC3
  - [x] Test(s)/verification per AC3's "Verified by"

- [x] **Task 4 — AC4: ledger updates**
  - [x] Delete all three bullets from the target `deferred-work.md` section
  - [x] Reconstruction check

- [x] **Task 5 — Final validation**
  - [x] Run all new/modified targeted test classes together; confirm zero regressions in the payment
        module's subscription test suites (`SubscriptionLifecycleIT`, `PastDueGracePeriodTest`,
        `TierEntitlementGatingTest`, `SubscriptionResourceIT`, `PlayerSubscriptionOwnershipIT`)
  - [x] Update Verification Checklist, File List, Change Log, Dev Agent Record
  - [x] Mark story Status → review

---

## Dev Notes

- **No local `mvn verify`** per project convention — GitHub CI is the sole full-verification gate. Run
  targeted suites only (payment module: subscription lifecycle, grace period, tier entitlement).
- **Risk areas:**
  - Unlike `skillars-deferred-115`'s AC1, none of this story's ACs need an owner decision — there is no
    pre-existing mitigation to weigh against, and the fix directly mirrors an established in-module
    sibling pattern (`SessionPackForfeitureScheduler`). Proceed straight to implementation.
  - `syncMarketplaceTier`'s `ifPresentOrElse` branch (creating a new `CoachSubscription` row if none
    exists) must keep working identically inside the new per-item transaction — read the whole method
    (`SubscriptionService.java:631-643`) before touching its call sites. It is a read-then-insert, not an
    upsert (`marketplace.coach_subscriptions.coach_id` is the `@Id` itself, so a genuine race would throw
    a primary-key violation, not silently corrupt data). This method is also called from synchronous,
    non-scheduled paths (`subscribeCoach`/`handleSubscriptionDeleted`, outside `@SchedulerLock`'s
    protection) — a coach-initiated tier change landing at the exact moment the scheduler processes that
    same coach is a genuine, **pre-existing** race, unrelated to and not worsened by this story's
    restructure (the race window is identical today, inside the one big transaction). This story's
    per-item `try/catch` actually improves the failure mode here versus today (a caught, logged,
    contained failure instead of an uncaught exception rolling back the whole batch) — do not attempt to
    fix the underlying race itself (e.g. by adding `ON CONFLICT`/upsert semantics); that is a separate,
    cross-cutting change out of this story's scope.
  - Player downgrades have no `syncMarketplaceTier` equivalent (players have no marketplace-visible tier)
    — do not add one; confirm this asymmetry is intentional by reading `applyPendingChanges()`'s player
    loop and `checkPastDueGracePeriod()`'s player loop before assuming symmetry with the coach path.
  - **`PaymentCoachSubscription`/`PaymentPlayerSubscription`/marketplace `CoachSubscription` carry no
    `@Version` field** — see AC2's correction note. Do not write a test asserting
    `OptimisticLockingFailureException`/`ObjectOptimisticLockingFailureException` for either method; no
    code path can throw one. Use a plain stubbed exception (e.g. a mocked `repository.save(...)` throwing
    a generic `RuntimeException`, or a `DataIntegrityViolationException` if testing the constraint-failure
    angle) to exercise the per-item `try/catch`.
- **Testing approach:** `PastDueGracePeriodTest.java` already establishes a `@Mock`-based unit-test
  pattern with `@InjectMocks SubscriptionService service` — extend that pattern for both AC1 and AC2
  rather than introducing a container-backed IT. **Concrete mechanical gap to close first:**
  `SubscriptionService` has no `TransactionTemplate` field today (confirmed — not in its constructor
  dependency list). Adding one (needed for the `transactionTemplate.execute(...)` restructure, mirroring
  `SessionPackForfeitureScheduler`'s own `private final TransactionTemplate transactionTemplate` field)
  means `PastDueGracePeriodTest.java` (and `TierEntitlementGatingTest.java`, the other `@InjectMocks
  SubscriptionService` test, if it turns out to exercise either restructured method) needs a new `@Mock
  TransactionTemplate transactionTemplate` field, **stubbed to actually invoke its callback** —
  `when(transactionTemplate.execute(any())).thenAnswer(inv -> inv.getArgument(0, TransactionCallback.class).doInTransaction(null))`
  (or the codebase's existing equivalent stubbing idiom, if `SessionPackForfeitureScheduler`'s own test
  suite — if one exists — already established one; check before inventing a new pattern). A bare
  `@Mock TransactionTemplate` with no stub returns `null` from every `execute(...)` call **without
  invoking the callback at all** — every existing test in `PastDueGracePeriodTest.java` would then
  silently no-op (assertions on `sub.getTier()`/`sub.getStatus()` would fail because the mutation never
  ran) rather than fail with an obvious error. Confirm this stub is in place and all 8 existing tests in
  that file still pass with their original assertions before considering the restructure complete.
- **Logging:** match this module's existing structured-argument style for the new per-item `log.error`
  calls (see `SessionPackForfeitureScheduler`'s `log.error("Failed to forfeit session pack purchase {}",
  purchase.getPurchaseId(), e)` — id first as a named placeholder, exception object last so the stack
  trace prints) rather than string-concatenating the id into the message.
- **Project structure:** both touched service methods are in `platform.payment.service`; both touched
  scheduler wrapper classes are already in the same package — no new module, no infrastructure-layer
  changes, no migration.

### Project Structure Notes

- All touched files are within `platform.payment.service` — no new module.
- `@SchedulerLock` (`net.javacrumbs.shedlock.spring.annotation.SchedulerLock`) and `TransactionTemplate`
  are already project dependencies, used by multiple other schedulers in this same package — no new
  dependency needed.

---

## Files to Read Before Implementation

- `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java` (AC1/AC2 —
  read the whole file at minimum lines 440-650: `applyPendingChanges`, `checkPastDueGracePeriod`,
  `syncMarketplaceTier`, and their shared repository fields)
- `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionChangeApplicator.java` (AC3
  — whole file, 20 lines)
- `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionGracePeriodChecker.java`
  (AC3 — whole file, 20 lines)
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackForfeitureScheduler.java`
  (AC1/AC2/AC3 — the exact sibling pattern all three ACs mirror: short-transaction batch load, per-item
  transaction + try/catch, `@SchedulerLock` with a documented sizing basis)
- `src/test/java/com/softropic/skillars/platform/payment/service/PastDueGracePeriodTest.java` (AC2 — the
  one existing test file directly exercising `checkPastDueGracePeriod`; establishes the Mockito-based
  test pattern to extend)
- `src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionLifecycleIT.java` (AC1/AC2 —
  check for any existing scheduler-adjacent coverage before writing new tests from scratch)
- `src/main/java/com/softropic/skillars/platform/payment/repo/CoachSubscriptionChangeRepository.java`,
  `PlayerSubscriptionChangeRepository.java` (AC1 — confirms `findPendingForScheduler`'s exact `WHERE`
  clause, including the `FOR UPDATE SKIP LOCKED` that AC3 depends on)
- `src/main/resources/db/migration/V138__baseline_schema.sql:1770-1850` (AC1 — confirms `to_tier`'s
  unconstrained `VARCHAR(20)` shape on both `coach_subscription_changes` and `player_subscription_changes`)
- `src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachSubscriptionTier.java` (AC1 —
  the three-value enum `valueOf(...)` is called against; note its declaration-order comment before
  touching anything nearby)

---

## Verification Checklist

- [x] AC1: `applyPendingChanges()` no longer carries a method-level `@Transactional` wrapping the whole
      batch; a malformed/failing `to_tier` on one pending change no longer prevents other coach or player
      changes in the same run from applying; the failing change's `applied` flag stays `false`; both-fail
      (coach + player) case tested, not just single-loop failures
- [x] AC2: `checkPastDueGracePeriod()` restructured identically; one row's failure no longer aborts other
      coaches'/players' grace-period downgrades in the same run; both-fail case tested
- [x] AC3: both `SubscriptionChangeApplicator.applyPendingChanges()` and
      `SubscriptionGracePeriodChecker.checkGracePeriods()` carry `@SchedulerLock` with a documented
      sizing basis (arithmetic, not a production-DB query); landed in the same change as AC1/AC2
- [x] AC4: `deferred-work.md`'s `ad-hoc audit of payment module subscription schedulers` section has all
      three bullets deleted outright; reconstruction check passed
- [x] `PastDueGracePeriodTest.java`'s existing 8 tests still pass unmodified after `TransactionTemplate`
      is added as a new `SubscriptionService` constructor dependency (mock stubbed to invoke its
      callback, not left to return `null`)
- [x] No regressions in existing payment module subscription test suites

---

## References

- `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: ad-hoc audit of payment
  module subscription schedulers (2026-09-16)`
- `_bmad-output/implementation-artifacts/skillars-deferred-115-scheduler-transaction-isolation-hardening.md`
  — sibling story this audit followed on from; same bug class, same fix pattern, same project
  conventions (ledger hygiene, no local `mvn verify`)
- `_bmad-output/implementation-artifacts/story-review.md` — pre-implementation quality review processed
  2026-09-16; see Change Log for the disposition of each finding

---

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (`claude-sonnet-5`), via `/bmad-dev-story`.

### Debug Log References

None — no failures requiring a debug session. Targeted test runs (Mockito unit suites, then the real
Testcontainers-backed ITs) all passed on first correct attempt after two test-authoring corrections
caught by the unit runs themselves (documented in Completion Notes below).

### Completion Notes List

- **AC1/AC2**: Added `TransactionTemplate` as a new `SubscriptionService` constructor dependency.
  Restructured `applyPendingChanges()` and `checkPastDueGracePeriod()` from a single method-level
  `@Transactional` wrapping the whole batch into: short-transaction batch load
  (`transactionTemplate.execute(...)`) + per-item `transactionTemplate.executeWithoutResult(...)`
  wrapped in `try/catch (Exception e) { log.error(...); }`, mirroring
  `SessionPackForfeitureScheduler`'s established pattern exactly. Both methods lost their
  `@Transactional` annotation entirely, per the sibling pattern. A `null` guard defaults each batch
  load to an empty list (matches `TransactionTemplate.execute`'s nullable return contract).
- **AC1**: Confirmed via a real Testcontainers-backed IT (`SubscriptionLifecycleIT`, unmodified) that
  the restructured `applyPendingChanges()` still applies a real scheduled downgrade end-to-end.
  Confirmed via new unit tests (`SubscriptionSchedulerIsolationTest`) that a malformed `to_tier`
  (`CoachSubscriptionTier.valueOf(...)` throwing) on one coach change leaves that change's `applied`
  flag `false` while a sibling valid coach change and a sibling valid player change both still apply
  in the same run; symmetric player-side-failure case; and a both-coach-and-player-fail case proving
  neither loop aborts the other.
- **AC2**: Identical restructure. New unit tests confirm one coach's (or, in the combined test, one
  coach's and one player's) downgrade write failing does not prevent sibling coach/player downgrades
  in the same run from completing. Note: unlike AC1's `applied` flag (a separate row only stamped
  `true` after the whole per-item block succeeds), the *failing* row's in-memory tier/status fields
  are mutated before the throwing `save()` call in these tests — a real transaction rollback discards
  that uncommitted write, but a Mockito `save()` stub cannot reproduce rollback on a plain POJO, so
  these two new tests deliberately assert only on the succeeding siblings (documented inline in the
  test file) rather than making a claim the mock can't actually verify.
- **AC3**: Added `@SchedulerLock` to `SubscriptionChangeApplicator.applyPendingChanges()`
  (`lockAtMostFor = "PT30M"`, `lockAtLeastFor = "PT2M"`) and
  `SubscriptionGracePeriodChecker.checkGracePeriods()` (identical values), each with a Javadoc sizing
  comment documenting the worst-case arithmetic basis (no config-bound batch-size ceiling exists on
  either query — noted as a known, out-of-scope gap per the story's own Dev Notes — so sizing is based
  on an assumed worst-case daily volume order-of-magnitude above any plausible near-term scale, at a
  pessimistic per-row DB-round-trip cost, with no external HTTP call in either loop). New
  `SubscriptionSchedulerLockTest` (reflection/annotation check, mirroring
  `VideoLifecycleSchedulerTest`'s established precedent for this exact assertion shape) confirms both
  annotations are present with sensible values.
- **Test-authoring corrections caught by the unit test runs themselves** (both fixed before any test
  was accepted as passing): (1) `PastDueGracePeriodTest`'s new `TransactionTemplate` mock stub for
  `executeWithoutResult` needed `lenient()` — several pre-existing tests in that file have no past-due
  rows at all, so the stub goes unused in them and strict Mockito stubbing flagged it; (2) two new
  `checkPastDueGracePeriod` isolation tests originally asserted the *failing* entity's tier stayed
  unmutated after a thrown `save()` — factually wrong for a plain Mockito POJO mock (see AC2 note
  above); corrected to assert only what the restructure actually guarantees and what a mock can
  actually prove.
- **AC4**: Deleted all three bullets from `deferred-work.md`'s
  `## Deferred from: ad-hoc audit of payment module subscription schedulers (2026-09-16)` section,
  keeping the section's intro paragraph (now with a one-sentence closing note) — same treatment the
  sibling `notification + video` section received when `skillars-deferred-115` closed it. `git diff`
  confirms only those three bullets were removed; no other section touched.
- **Final validation**: `PastDueGracePeriodTest` (6), `TierEntitlementGatingTest` (10),
  `SubscriptionSchedulerIsolationTest` (5, new), `SubscriptionSchedulerLockTest` (2, new) — 23/23
  green. `SubscriptionLifecycleIT` (15), `SubscriptionResourceIT` (13), `PlayerSubscriptionOwnershipIT`
  (4) — 32/32 green against a real Testcontainers Postgres, zero regressions. No local `mvn verify`
  per project convention (GitHub CI is the sole full-verification gate).

### File List

- `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java` — AC1/AC2:
  added `TransactionTemplate` dependency; restructured `applyPendingChanges()` and
  `checkPastDueGracePeriod()` into short-transaction batch load + per-item transaction + try/catch
- `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionChangeApplicator.java` —
  AC3: added `@SchedulerLock` with sizing-basis Javadoc
- `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionGracePeriodChecker.java`
  — AC3: added `@SchedulerLock` with sizing-basis Javadoc
- `src/test/java/com/softropic/skillars/platform/payment/service/PastDueGracePeriodTest.java` — added
  `TransactionTemplate` mock + stubbing so the 6 pre-existing tests keep passing after the AC1/AC2
  dependency change
- `src/test/java/com/softropic/skillars/platform/payment/service/TierEntitlementGatingTest.java` —
  added `TransactionTemplate` mock + local stubbing in the one test exercising
  `checkPastDueGracePeriod()`
- `src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionSchedulerIsolationTest.java`
  (new) — AC1/AC2 per-item failure-isolation tests, including both-loops-fail cases
- `src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionSchedulerLockTest.java`
  (new) — AC3 `@SchedulerLock` presence/sizing reflection tests
- `_bmad-output/implementation-artifacts/deferred-work.md` — AC4: three closed bullets deleted

---

## Change Log

- 2026-09-16: Story created via `/bmad-create-story`, mined from a fresh ad-hoc audit of the payment
  module's subscription schedulers — the natural sequel to `skillars-deferred-115`, which explicitly
  scoped itself to notification/video and declared booking/payment/messaging out of scope. Found the
  identical bug class (batch processed in one transaction, no per-item isolation, no `@SchedulerLock`)
  in `SubscriptionService.applyPendingChanges()`/`checkPastDueGracePeriod()`. Unlike deferred-115's AC1,
  no owner decision is needed here — there is no pre-existing partial mitigation to weigh against, and an
  in-module sibling (`SessionPackForfeitureScheduler`) already demonstrates the exact pattern to mirror.
- 2026-09-16: Pre-implementation quality review (`story-review.md`) processed. Each of its 12 findings
  re-verified against actual code rather than accepted on the review's assertion alone. **Issue #1**
  (event publishing/transaction-boundary interaction) was a **false positive** — confirmed by grep that
  neither `applyPendingChanges()` nor `checkPastDueGracePeriod()` publishes `SubscriptionExpiredEvent` at
  all; the review was analyzing an unrelated method (`handleSubscriptionDeleted`) not in this story's
  scope. Note added to AC1 so the dev doesn't chase it. **Issue #2** (concurrent-modification race) was
  **factually wrong on its proposed mechanism but pointed at something real** — re-read all three
  entities (`PaymentCoachSubscription`, `PaymentPlayerSubscription`, marketplace `CoachSubscription`)
  directly: none carries `@Version`, so no optimistic-lock exception can ever be thrown, contradicting
  AC2's own original text (which incorrectly claimed one could). Corrected AC2 to state the real behavior
  (silent last-write-wins on a race, not an exception) and explicitly scoped it out as a pre-existing,
  unchanged-by-this-story risk (same shape as the already-filed `skillars-11-1` payment-parity items) —
  not something this story adds `@Version` to fix. **Issue #3** (applied-flag re-selection) was
  **re-verified and confirmed accurate** — read the actual native query
  (`WHERE applied = false AND voided_at IS NULL AND effective_at <= :cutoff ... FOR UPDATE SKIP LOCKED`);
  the story's assumption was correct, note added citing the exact query. This also surfaced a real,
  previously-unflagged correctness dependency: `FOR UPDATE SKIP LOCKED`'s claim only lasts as long as the
  transaction that issued it, so AC3's `@SchedulerLock` must land in the same change as AC1/AC2, not as
  an independent follow-up — added to AC3. **Issue #4** (lockAtMostFor sizing) — pushed back on
  "query the production DB," which no prior scheduler story in this codebase has done (including
  deferred-115, which used worst-case arithmetic instead); kept the arithmetic-based approach, added
  explicit guidance not to over-scope. **Issue #5** (both-fail test coverage) — **genuine gap, adopted**;
  added explicit "both coach and player fail in the same run" test cases to AC1 and AC2, plus a
  cross-loop-independence test. **Issue #6** (`syncMarketplaceTier` race/upsert) — investigated: it's a
  read-then-insert against a natural-key `@Id` (a real race would throw a PK violation, not corrupt
  data), and the method is also called from synchronous non-scheduled paths outside `@SchedulerLock`'s
  reach — a genuine, **pre-existing** race unrelated to and not worsened by this story (this story's
  per-item `try/catch` actually contains the failure better than today's whole-batch rollback); documented
  as out of scope in Dev Notes rather than fixed. **Issue #7** (batch-read protection) — reviewer
  themselves called this "probably not an issue"; no action, consistent with every sibling scheduler's
  same shape. **Issue #8** (downgrade-tier ambiguity) — **largely a false positive**: the actual code is
  two hardcoded literal pairs (`SCOUT`/`CANCELLED`, `ATHLETE`/`CANCELLED`), already quoted verbatim in
  AC2's own text; no lookup or state machine exists to document further. **Issue #9**
  (`lockAtLeastFor` justification) — reasonable, added one clarifying sentence to AC3 (consistency with
  the sibling pattern, not independently load-bearing for a daily cron). **Issue #10** (ledger hygiene
  specificity) — reviewer found no action needed; agreed. **Issue #11** (logging format) — added a
  structured-logging guidance note to Dev Notes, matching `SessionPackForfeitureScheduler`'s existing
  style. **Issue #12** (idempotency as defense-in-depth) — adopted, added to AC3. **Also found during
  this pass, not flagged by the review at all:** `SubscriptionService` has no `TransactionTemplate`
  dependency today, and `PastDueGracePeriodTest.java`'s 8 existing `@InjectMocks`-based tests have no
  mock for one — adding the dependency without stubbing `TransactionTemplate.execute(...)` to invoke its
  callback would make those tests silently no-op (pass with stale assertions) rather than fail loudly;
  added as an explicit task and Verification Checklist item. The review's own "Sign-Off: No false
  positives identified" was itself inaccurate — at least Issue #1 and part of Issue #2 were false/wrong
  as stated, corrected above.
- 2026-09-16: Dev implementation complete (`/bmad-dev-story`). AC1/AC2: `SubscriptionService` gained a
  `TransactionTemplate` constructor dependency; `applyPendingChanges()` and `checkPastDueGracePeriod()`
  restructured from one method-level `@Transactional` wrapping the whole batch into short-transaction
  batch load + per-item transaction + `try/catch`, mirroring `SessionPackForfeitureScheduler` exactly.
  AC3: `@SchedulerLock(lockAtMostFor = "PT30M", lockAtLeastFor = "PT2M")` added to both
  `SubscriptionChangeApplicator.applyPendingChanges()` and
  `SubscriptionGracePeriodChecker.checkGracePeriods()`, each with a worst-case-arithmetic sizing
  comment (no config-bound batch-size ceiling exists on either query — a known, explicitly out-of-scope
  gap). AC4: the three closed bullets deleted from `deferred-work.md`'s ad-hoc-audit section;
  reconstruction check passed via `git diff`. New tests: `SubscriptionSchedulerIsolationTest` (5 —
  per-item failure isolation for both methods, including both-coach-and-player-fail cases) and
  `SubscriptionSchedulerLockTest` (2 — `@SchedulerLock` presence/sizing, mirroring
  `VideoLifecycleSchedulerTest`'s established reflection-check pattern); `PastDueGracePeriodTest` and
  `TierEntitlementGatingTest` updated with a `TransactionTemplate` mock so their pre-existing tests
  keep passing (and don't silently no-op) after the new constructor dependency. Two test-authoring
  mistakes were self-caught by the unit runs before being accepted: a missing `lenient()` on one mock
  stub, and two assertions claiming a failed-and-rolled-back entity mutation that a plain Mockito POJO
  mock cannot actually reproduce (corrected to assert only what the restructure and the mock together
  can prove). Full targeted validation: 23/23 unit tests green
  (`PastDueGracePeriodTest`/`TierEntitlementGatingTest`/`SubscriptionSchedulerIsolationTest`/`SubscriptionSchedulerLockTest`)
  plus 32/32 green against a real Testcontainers Postgres
  (`SubscriptionLifecycleIT`/`SubscriptionResourceIT`/`PlayerSubscriptionOwnershipIT`, all run
  unmodified), zero regressions. No local `mvn verify` per project convention. Status → review.
- 2026-09-16: Code review response complete. **Every line citation in the review below was checked
  against the actual code and none of them matched** — `PastDueGracePeriodTest.java:325` doesn't exist
  (file is 167 lines), `SubscriptionService.java:159,196,245,268` land in unrelated pre-existing methods
  this story never touched, `:444-145` isn't a valid range. Evidently run against a stale/different
  snapshot. Each of the 7 Patch findings was independently re-judged on its semantic merit against the
  real code regardless of its citation: 2 were genuine, low-risk gaps and were fixed (batch-load
  row-count logging, added mirroring `PaymentPendingSweeper`'s own convention; a one-line comment on
  each player loop explaining the pre-existing, intentional `syncMarketplaceTier` asymmetry, which this
  story's own Dev Notes already explained but not in-code). 5 were dismissed as false positives with
  reasons recorded per finding (batch-load try/catch — matches `SessionPackForfeitureScheduler`'s own
  established pattern exactly, not a regression; entity-ID null guards — `coach_id`/`player_id` are
  `NOT NULL` DB columns, cannot be null in practice; test-mocking-too-lenient — misreads what Mockito's
  `lenient()` does, it doesn't gate callback invocation; hardcoded-tier-defaults — a re-ask of the
  pre-implementation review's own Issue #8, already dismissed below; null-check-brittle-contract —
  matches `PaymentPendingSweeper`'s identical existing convention). Of the 4 Defer findings (already
  checked off, no action requested), 3 were accurate and 1 was factually wrong on its premise (claimed
  `FOR UPDATE SKIP LOCKED` was "never shown/verified" — it was, both at story-creation time and during
  implementation, by directly reading the repository files) though harmless since it carried no action
  item. See the corrected "Review Findings" section below for the full per-finding disposition. 23/23
  targeted unit tests re-run green after the two fixes; no functional/test-observable behavior changed
  by either fix.

### Review Findings

**Note on this review's reliability:** every single line citation below was checked against the actual
code and **none of them match**. `PastDueGracePeriodTest.java:325` doesn't exist — the file is 167
lines long. `SubscriptionService.java:159,196,245,268` (cited for the per-item loops this story added)
land in `persistCoachSubscription`/`changeCoachTier`/`cancelCoachSubscription`/`persistCoachCancellation`
— pre-existing methods this story never touched. `:444-145` isn't even a valid ascending range. This
review was evidently run against a stale or different snapshot of the code, not the file as implemented.
Each finding below was therefore re-judged on its own semantic merit against the real code (not
dismissed on citation-mismatch alone), same standard the pre-implementation review response applied.

**Patch findings — all 7 reviewed, 2 fixed, 5 dismissed:**

- [x] [Review][Patch][FIXED] No logging of batch-load row counts — real gap, low-risk, cheap. Added
  `log.info(...)` after all four batch loads (`applyPendingChanges()`'s coach/player loads,
  `checkPastDueGracePeriod()`'s coach/player loads), mirroring `PaymentPendingSweeper`'s existing
  `"{} booking(s) ..."` convention (confirmed present there; confirmed **absent** from
  `SessionPackForfeitureScheduler`, so not universal in this module — added because it's genuinely
  useful, not because omitting it would have been wrong).

- [x] [Review][Patch][FIXED] `syncMarketplaceTier` called asymmetrically (coach only) — real gap in
  *code* comments specifically (the asymmetry itself is intentional and was already explained in this
  story's own Dev Notes — "players have no marketplace-visible tier" — just not inline). Added a
  one-line comment at both player loops (`applyPendingChanges()` and `checkPastDueGracePeriod()`)
  pointing future readers at the reason, at zero behavioral risk.

- [x] [Review][Patch][DISMISSED — false positive] Batch-load exceptions unhandled (catch missing) —
  cited lines don't exist at the batch loads. On the merits: mirrors `SessionPackForfeitureScheduler`'s
  own established pattern exactly (`List<SessionPackPurchase> expired = transactionTemplate.execute(...); if (expired == null) return;`
  — no try/catch there either, confirmed by reading it). A batch-SELECT failure was a total-run failure
  before this story (inside the old method-level `@Transactional`) and is a total-run failure after —
  unrelated to AC1/AC2's per-item isolation, which is what this story targets. Not a regression, not
  in scope.

- [x] [Review][Patch][DISMISSED — false positive] Entity ID null guards missing in per-item loops —
  cited lines (159/196/245/268) are in unrelated, untouched methods. On the merits: `coach_id`/`player_id`
  are `NOT NULL` columns (confirmed in `V138__baseline_schema.sql`) on rows already loaded from the DB
  — cannot be null in practice. No sibling scheduler in this module null-checks its entity IDs either.

- [x] [Review][Patch][DISMISSED — false positive] Test mocking is too lenient — cited line doesn't
  exist (file is 167 lines). On the merits, this misreads what Mockito's `lenient()` does: it does
  **not** affect whether the stub's callback runs — the `doAnswer` unconditionally invokes
  `action.accept(null)` every time `executeWithoutResult(...)` is called. `lenient()` only suppresses
  strict-stubbing's "unnecessary stubbing" check for the handful of tests with an empty batch (where
  `executeWithoutResult` is never reached at all). The "batch-load failures are untested" sub-claim is
  true but out-of-scope for the same reason as the batch-load-try/catch finding above.

- [x] [Review][Patch][DISMISSED — false positive, already litigated] Hardcoded tier defaults lack
  validation — cited lines land elsewhere entirely. This is a re-ask of the pre-implementation review's
  own Issue #8, already dismissed in this file's Change Log below: `"SCOUT"`/`"ATHLETE"` are hardcoded
  literal constants (not user input), so `CoachSubscriptionTier.valueOf("SCOUT")` cannot ever throw —
  it's a compile-time-guaranteed-valid literal, not a value carrying uncertainty. The player-side
  `sub.setTier("ATHLETE")` calls don't invoke any `valueOf(...)` at all (`PaymentPlayerSubscription.tier`
  is a plain `String` field).

- [x] [Review][Patch][DISMISSED — matches existing convention] Null-check defensive pattern suggests
  brittle contract — cited lines land elsewhere. `PaymentPendingSweeper.sweepStrandedPayments()` uses
  the identical `if (x == null) ...` guard on its own `TransactionTemplate.execute(...)` result
  (confirmed by reading it) — this is an established codebase convention this story is consistent with,
  not a new brittleness.

**Defer findings** (as left by the reviewer — re-checked, 3 accurate, 1 factually wrong but harmless
since it carries no action item):

- [x] [Review][Defer] Per-item exception handling is generic (no type distinction) — accurate, correctly
  deferred; matches every sibling scheduler's identical `catch (Exception e)` shape.

- [x] [Review][Defer] Idempotency of tier reapplication unverified — accurate, correctly deferred;
  matches AC3's own defense-in-depth note in this story.

- [x] [Review][Defer][CORRECTION] FOR UPDATE SKIP LOCKED assumption undocumented — the premise is
  **wrong**, not merely low-priority: this *was* shown and verified, both at story-creation time (AC1's
  own text quotes the exact clause from the native query) and independently during implementation (the
  repository files were read directly — see Dev Agent Record). No action needed regardless, since this
  finding carried no patch request, but noting the correction so it isn't mistaken for an open gap.

- [x] [Review][Defer] No aggregated metrics on partial batch failures — accurate, correctly deferred;
  no metrics infrastructure exists for this to hook into, consistent with the rest of this module's
  schedulers.
