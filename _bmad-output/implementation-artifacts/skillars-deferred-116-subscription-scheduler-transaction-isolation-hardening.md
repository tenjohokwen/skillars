# Story: Subscription Scheduler Transaction-Isolation & Cluster-Lock Hardening

**Story Key:** `skillars-deferred-116-subscription-scheduler-transaction-isolation-hardening`
**Epic:** Deferred Work
**Priority:** Medium (one silent-batch-rollback resilience gap, one cluster-safety gap — same bug class as an already-fixed sibling)
**Status:** ready-for-dev
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

**Given** `applyPendingChanges()` (`SubscriptionService.java:446-466`) carries a method-level
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

**Verified by:**
- A test forcing `CoachSubscriptionTier.valueOf(...)` (or the direct DB-level equivalent — an
  intentionally-invalid `to_tier` row) to throw for one pending coach change in a multi-change batch that
  also includes at least one valid coach change and at least one valid player change, asserting: the valid
  coach change and the valid player change are still applied in the same run, and the failing change's
  `applied` flag remains `false` (not marked applied, not silently dropped).
- Existing `SubscriptionService` test coverage (check `SubscriptionLifecycleIT.java`,
  `PastDueGracePeriodTest.java`, `TierEntitlementGatingTest.java` for any existing
  `applyPendingChanges`/`checkPastDueGracePeriod` coverage before writing new tests — none was found at
  story-creation time, so this is likely new test-class territory, not an extension) re-run green.

**Ledger:** [`src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java:446-466,631-643`,
`src/main/resources/db/migration/V138__baseline_schema.sql:1780,1844`]

---

### AC2: `SubscriptionService.checkPastDueGracePeriod()` — per-item transaction isolation

**Given** `checkPastDueGracePeriod()` (`SubscriptionService.java:482-502`) has the identical shape to
AC1's method: one method-level `@Transactional` wrapping the `paymentCoachSubscriptionRepository.findByStatusAndPastDueSinceBefore(...)`
read and its `for` loop (downgrading each past-due coach to `SCOUT`/`CANCELLED` and calling
`syncMarketplaceTier`), then the identical read+loop for past-due players (downgrading to `ATHLETE`/`CANCELLED`,
no `syncMarketplaceTier` call for players),

**When** the scheduler runs (`SubscriptionGracePeriodChecker`, daily at `0 0 3 * * *`) and any single
past-due subscription's downgrade write fails — the same `CoachSubscriptionTier.valueOf("SCOUT")` call
inside `syncMarketplaceTier` cannot itself fail (the literal is a valid enum constant), but a concurrent
modification to the same `PaymentCoachSubscription`/`PaymentPlayerSubscription`/`CoachSubscription` row
between this method's read and write (e.g. a webhook-driven status update landing mid-sweep) can throw an
optimistic-lock or constraint failure,

**Then today**: identical failure mode to AC1 — the single `@Transactional` rolls back every other
past-due coach's and player's grace-period cancellation for that entire run, not just the conflicting row.

**Fix:** identical restructure to AC1 — short-transaction batch load, per-item `transactionTemplate.execute(...)`
wrapped in `try/catch (Exception e) { log.error(...); }`. A failed row is naturally re-selected on
tomorrow's run (the query filters on `status = 'PAST_DUE'` and a cutoff timestamp — a row that failed to
downgrade is still `PAST_DUE` and still past the cutoff, so it stays eligible with no extra bookkeeping
needed, unlike AC1's `applied` flag).

**Verified by:**
- A test forcing one past-due coach's (or player's) downgrade write to throw in a multi-row batch that
  also includes at least one succeeding coach and one succeeding player, asserting the succeeding rows
  are still downgraded/cancelled in the same run.
- `PastDueGracePeriodTest.java`'s existing coverage (grace-period-boundary cases) re-run green — this is
  the one existing test file directly exercising this method; read it first to match its established
  Mockito-based test shape rather than introducing a different pattern.

**Ledger:** [`src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java:482-502`]

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
write and the `SubscriptionExpiredEvent`-adjacent logging would race), and nothing prevents
`checkPastDueGracePeriod`'s cancellation-and-downgrade from double-firing.

**Fix:** add `@SchedulerLock` to `SubscriptionChangeApplicator.applyPendingChanges()` and
`SubscriptionGracePeriodChecker.checkGracePeriods()`, mirroring `SessionPackForfeitureScheduler`'s
`@SchedulerLock(name = "...", lockAtMostFor = "PT15M", lockAtLeastFor = "PT2M")` shape. Size
`lockAtMostFor` generously relative to realistic batch sizes at this project's current scale (there is no
config-bound batch-size ceiling on either query today, unlike `VideoLifecycleScheduler`'s — note this as
a known gap in the story but do not scope-creep into adding one unless implementation reveals it is
trivial to do consistently with the AC1/AC2 restructure); document the sizing basis in a code comment,
the same way `SessionPackForfeitureScheduler`/`EmailRetryScheduler` do.

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

- [ ] **Task 1 — AC1: `applyPendingChanges()` hardening**
  - [ ] Restructure into short-transaction batch load + per-item `transactionTemplate.execute(...)` +
        `try/catch (Exception e) { log.error(...); }`, mirroring `SessionPackForfeitureScheduler`
  - [ ] Confirm a failed change's `applied` flag stays `false` (naturally re-selected next run)
  - [ ] Test(s) per AC1's "Verified by"

- [ ] **Task 2 — AC2: `checkPastDueGracePeriod()` hardening**
  - [ ] Identical restructure to Task 1
  - [ ] Test(s) per AC2's "Verified by"

- [ ] **Task 3 — AC3: `@SchedulerLock` on both wrapper classes**
  - [ ] Add `@SchedulerLock` to `SubscriptionChangeApplicator.applyPendingChanges()` and
        `SubscriptionGracePeriodChecker.checkGracePeriods()`, sized and documented per AC3
  - [ ] Test(s)/verification per AC3's "Verified by"

- [ ] **Task 4 — AC4: ledger updates**
  - [ ] Delete all three bullets from the target `deferred-work.md` section
  - [ ] Reconstruction check

- [ ] **Task 5 — Final validation**
  - [ ] Run all new/modified targeted test classes together; confirm zero regressions in the payment
        module's subscription test suites (`SubscriptionLifecycleIT`, `PastDueGracePeriodTest`,
        `TierEntitlementGatingTest`, `SubscriptionResourceIT`, `PlayerSubscriptionOwnershipIT`)
  - [ ] Update Verification Checklist, File List, Change Log, Dev Agent Record
  - [ ] Mark story Status → review

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
    (`SubscriptionService.java:631-643`) before touching its call sites.
  - Player downgrades have no `syncMarketplaceTier` equivalent (players have no marketplace-visible tier)
    — do not add one; confirm this asymmetry is intentional by reading `applyPendingChanges()`'s player
    loop and `checkPastDueGracePeriod()`'s player loop before assuming symmetry with the coach path.
- **Testing approach:** `PastDueGracePeriodTest.java` already establishes a `@Mock`-based unit-test
  pattern with `@InjectMocks SubscriptionService service` — extend that pattern for both AC1 and AC2
  rather than introducing a container-backed IT, unless the per-item `transactionTemplate.execute(...)`
  restructure makes the mock-based approach awkward (in which case check `SessionPackForfeitureScheduler`'s
  own test suite, if any exists, for how it handles testing around `TransactionTemplate`).
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
- `src/main/resources/db/migration/V138__baseline_schema.sql:1770-1850` (AC1 — confirms `to_tier`'s
  unconstrained `VARCHAR(20)` shape on both `coach_subscription_changes` and `player_subscription_changes`)
- `src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachSubscriptionTier.java` (AC1 —
  the three-value enum `valueOf(...)` is called against; note its declaration-order comment before
  touching anything nearby)

---

## Verification Checklist

- [ ] AC1: `applyPendingChanges()` no longer carries a method-level `@Transactional` wrapping the whole
      batch; a malformed/failing `to_tier` on one pending change no longer prevents other coach or player
      changes in the same run from applying; the failing change's `applied` flag stays `false`
- [ ] AC2: `checkPastDueGracePeriod()` restructured identically; one row's failure no longer aborts other
      coaches'/players' grace-period downgrades in the same run
- [ ] AC3: both `SubscriptionChangeApplicator.applyPendingChanges()` and
      `SubscriptionGracePeriodChecker.checkGracePeriods()` carry `@SchedulerLock` with a documented
      sizing basis
- [ ] AC4: `deferred-work.md`'s `ad-hoc audit of payment module subscription schedulers` section has all
      three bullets deleted outright; reconstruction check passed
- [ ] No regressions in existing payment module subscription test suites

---

## References

- `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: ad-hoc audit of payment
  module subscription schedulers (2026-09-16)`
- `_bmad-output/implementation-artifacts/skillars-deferred-115-scheduler-transaction-isolation-hardening.md`
  — sibling story this audit followed on from; same bug class, same fix pattern, same project
  conventions (ledger hygiene, no local `mvn verify`)

---

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

---

## Change Log

- 2026-09-16: Story created via `/bmad-create-story`, mined from a fresh ad-hoc audit of the payment
  module's subscription schedulers — the natural sequel to `skillars-deferred-115`, which explicitly
  scoped itself to notification/video and declared booking/payment/messaging out of scope. Found the
  identical bug class (batch processed in one transaction, no per-item isolation, no `@SchedulerLock`)
  in `SubscriptionService.applyPendingChanges()`/`checkPastDueGracePeriod()`. Unlike deferred-115's AC1,
  no owner decision is needed here — there is no pre-existing partial mitigation to weigh against, and an
  in-module sibling (`SessionPackForfeitureScheduler`) already demonstrates the exact pattern to mirror.
