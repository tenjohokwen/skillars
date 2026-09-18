# Story: Coach Enforcement Status TOCTOU Lock Gap

**Story Key:** `skillars-deferred-121-coach-enforcement-status-toctou-lock-gap`
**Epic:** Deferred Work
**Priority:** High (a genuine lost-update bug reachable today by two admins — or one admin and one automated strike escalation — acting on the same coach within a normal request window; no multi-instance deployment or scale threshold required, unlike the forward-looking hardening in `skillars-deferred-120`)
**Status:** done
**Created:** 2026-09-17

---

## Provenance & Scoping (read before starting)

`skillars-deferred-120` closed the `@Scheduled`-method sweep `skillars-deferred-115` began — every
`@Scheduled` method in the codebase has now been examined under the transaction-boundary/TOCTOU/
scheduler-lock-parity lens at least once. This story's research (2026-09-17) therefore switched lenses:
applied the same transaction-safety/concurrency audit (TOCTOU, write skew, missing locks on
multi-writer rows) to modules no prior `skillars-deferred-11x` story had examined at all —
`platform.admin`, `platform.marketplace`, `platform.reviews`, `platform.session` — focusing on
`@Transactional` service methods with a read-then-write shape rather than scheduled jobs.
`deferred-work.md` was grepped for every class/method named below; zero open bullets reference this
story's finding (the one historical hit, `deferred-15`'s 2026-08-05 audit narrative naming
`AdminCoachEnforcementService.suspendCoach`'s *old* unlocked read, describes a bug already fixed by
that same story's AC4 — confirmed by the `Deferred-15 AC4` comment still present at
`AdminCoachEnforcementService.java:103-106` — not this story's finding).

**One genuine, live bug found and independently re-verified against HEAD before this story was
drafted (AC1).** Unlike `skillars-deferred-120`'s AC1/AC2 (reachable only after a future
multi-instance deploy), this bug is reachable **today**, single-instance, by two ordinary concurrent
requests — no scale threshold, no future deployment change required.

**Complete inventory of every write site to `CoachProfile.status` in the codebase** (built by grepping
`\.setStatus(CoachProfileStatus\.` — eight occurrences across three files, merged into six rows below
because `ReliabilityStrikeService.issue`'s two writes share one lock call and `CoachProfileService`'s
two draft-creation writes share one "new row" justification), because this bug's fix is "use the lock
every sibling writer already uses," and that claim is only trustworthy if every sibling was actually
checked:

| Site | Locked via `findByIdForUpdate`? |
|---|---|
| `ReliabilityStrikeService.issue:96,104` (auto-escalation on strike threshold) | ✅ yes (`:88`) |
| `CoachProfileService.getOrCreateDraft:147` / `saveStep1:157` (new-row creation) | N/A — new row, no concurrent reader yet |
| `CoachProfileService.publishProfile:309` (coach's own DRAFT→ACTIVE submit) | ✅ yes (`saveStep4`, the step immediately before publish, already takes the lock at `:250` and the entity stays managed through to `publishProfile`'s own `requireProfile` re-read in the same request; see Dev Notes for why this one is not being touched by this story) |
| `AdminCoachEnforcementService.suspendCoach:114` | ✅ yes (`:107`, fixed by `skillars-deferred-15` AC4) |
| `AdminCoachEnforcementService.reinstateCoach:163` | ❌ **no — this story's AC1** |
| `AdminCoachEnforcementService.deleteStrike:236` | ❌ **no — this story's AC1** |

Two of six writers of the same row skip the lock every other writer takes. `CoachProfile` carries no
`@Version` column (confirmed by direct read of `CoachProfile.java`) — this is the identical fact
`skillars-deferred-100`'s own code comment in `ReliabilityStrikeService.java:70-79` cites as the reason
every writer of this row must serialize via `findByIdForUpdate` instead: "`CoachProfile` carries no
`@Version`, so there was no optimistic-lock backstop either." `reinstateCoach` and `deleteStrike`
violate that established, explicitly-documented invariant.

**Examined and ruled out as false leads (do not re-report these):**
- `DisputeService.resolveDispute`/`dismissDispute` — `Dispute` **does** carry `@Version`
  (`Dispute.java:65`), and `resolveDispute`'s money-movement call (`CreditWalletService.writeLedgerEntry`,
  plain `@Transactional` REQUIRED) joins the same transaction as the final `disputeRepository.save(dispute)`
  — so two concurrent resolutions racing the unlocked `findById` read still only ever commit one of them;
  the loser's optimistic-lock failure at flush time rolls back its entire transaction, credit-wallet write
  included. Wasted computation on the loser, not a double-refund.
- `ReviewFlagService.flag`'s unlocked `review.getModerationStatus()` re-check before auto-holding at the
  flag threshold — two concurrent flags at the exact threshold boundary can both pass the guard and both
  call `coachRatingService.recompute()` / set the identical `UNDER_REVIEW` terminal state redundantly.
  Duplicate idempotent work, not corruption — `recompute()` (`CoachRatingService.java:21-29`) is a pure
  recalculation from the current `COUNT`/`AVG` of approved reviews followed by an unconditional
  `UPDATE`, confirmed idempotent by direct read, not assumed — and the DB-level
  `review_flags_unique_flagger` unique index already prevents the more serious concern (same user
  double-flagging). Not worth its own AC.
- `GdprRequestService.requestErasure`/`requestExport`'s unlocked `existsBy...PENDING/PROCESSING` check —
  looked like the same TOCTOU shape, but `V138__baseline_schema.sql:3202` already has a **unique partial
  index** (`idx_gdpr_requests_unique_active` on `(user_id, request_type) WHERE status IN ('PENDING',
  'PROCESSING')`) backstopping it at the DB level, and `ApiAdvice`'s global
  `@ExceptionHandler(DataIntegrityViolationException.class)` (`ApiAdvice.java:174`) already converts the
  loser's constraint violation into a handled 400, not a raw 500. A genuinely concurrent double-submit
  gets an uglier error message (generic 400 instead of the friendlier existing-check's 409) but never
  double-processes — cosmetic, not worth its own AC.

**Owner decision needed before implementation:** none — the fix mirrors an established sibling pattern
(`suspendCoach`'s own `findByIdForUpdate` + `lockRetryer.withBoundedRetry` call, four lines above the
first bug site in the same file) already in this exact class.

---

## Acceptance Criteria

### AC1 — `AdminCoachEnforcementService.reinstateCoach` and `.deleteStrike` write `CoachProfile.status` without the row lock every sibling writer uses

**The bug, traced end to end:**

`reinstateCoach` (`AdminCoachEnforcementService.java:150-179`) reads the coach via a plain
`coachProfileRepository.findById(coachId)` (`:151`), decides whether the transition is legal based on
that read (`:154-161`), and — several lines later, after also querying `resolveOpenStrikeAlert`'s alert
lookup — writes `coach.setStatus(CoachProfileStatus.ACTIVE)` and saves (`:163-165`). No
`findByIdForUpdate`, no re-check of `coach.getStatus()` immediately before the write.

`deleteStrike` (`:209-252`) has the identical shape for its conditional revert-to-`ACTIVE` path: it
deletes the strike (`:218`), computes a fresh strike `count` (`:220`), *then* reads the coach via plain
`findById` (`:224`), and if `count < visibilityThreshold` and the coach is in a review-adjacent state,
writes `coach.setStatus(CoachProfileStatus.ACTIVE)` (`:236`) — again with no lock and no re-check.

Every other writer of this same field — `suspendCoach` in this very class (`:107`, fixed by
`skillars-deferred-15` AC4, with a code comment at `:103-106` explaining exactly why the lock is
needed), `ReliabilityStrikeService.issue` (`:88`, fixed by `skillars-deferred-100` AC1, with its own
extensive comment at `:69-79` making the same argument), and `CoachProfileService.saveStep4`/
`publishProfile`'s locked chain (`:250`) — takes `coachProfileRepository.findByIdForUpdate(coachId)`
via `lockRetryer.withBoundedRetry(...)` first. `reinstateCoach` and `deleteStrike` are the only two of
six write sites that don't.

**Why a plain `findById` breaks serialization even though the writer eventually blocks:** Postgres does
not block a plain `SELECT` on another transaction's uncommitted `SELECT ... FOR UPDATE` — MVCC lets it
read the last *committed* row version immediately, with no wait. The writer only blocks later, at its
own `UPDATE` (issued via `save()`), which does need the row's write lock and so queues behind the lock
holder. By the time that blocked `UPDATE` is unblocked and finally executes, it carries the *decision*
computed from the stale pre-block read — not a decision re-validated against whatever the lock holder
just committed. This is the exact "decorative lock" failure mode the `:103-106` comment in this file
already names for a different pair of callers (the booking-accept paths vs. `suspendCoach`) — it
applies equally to `reinstateCoach`/`deleteStrike` vs. any locked writer, and nothing in this class
currently protects against it for either method.

**Failure scenario (concrete, reachable today, single-instance, no scale threshold):**

1. Coach X is at `PENDING_REVIEW` (auto-set by an earlier `ReliabilityStrikeService.issue` strike-count
   escalation).
2. Admin A calls `PUT /admin/coaches/{id}/reinstate` believing the review is resolved. `reinstateCoach`
   runs: reads `coach` via plain `findById` → sees `PENDING_REVIEW` (a legal source state, passes the
   `:157-161` check), begins computing the transition to `ACTIVE`.
3. Concurrently — a routine no-show or unexcused-cancellation on an unrelated booking for the same coach
   fires `ReliabilityStrikeService.issue` (called from an `AFTER_COMMIT` refund listener, no admin
   action required). `issue()` takes the row lock (`findByIdForUpdate`, `:88`), reads the fresh rolling
   strike count, and — because the coach is already at `PENDING_REVIEW` and the new count still clears
   the suspension threshold — its own guard (`coach.getStatus() != PENDING_REVIEW`) is `false`, so it
   does *not* write in this specific sub-case. *(A starker version of the same race: admin B calls
   `suspendCoach` on the same coach in step 3 instead of a strike firing — `suspendCoach` takes the
   lock, cancels the coach's `REQUESTED` bookings, publishes `CoachSuspendedEvent`, and commits
   `SUSPENDED`.)*
4. Using the `suspendCoach`-races-`reinstateCoach` variant: Admin A's `reinstateCoach` transaction (from
   step 2) now reaches its `coachProfileRepository.save(coach)` call. That `UPDATE` needs the row's
   write lock, which Admin B's `suspendCoach` transaction held until it committed in step 3 — so Admin
   A's `UPDATE` was blocked, then unblocks the instant `suspendCoach` commits, and executes carrying the
   decision computed in step 2: write `status = ACTIVE`.
5. Net result: the coach ends up `ACTIVE` immediately after being suspended — with all of `suspendCoach`'s
   side effects (the coach's `REQUESTED` bookings already cancelled, `CoachSuspendedEvent` and
   `CoachSuspensionNotificationEvent` already published, an `AdminActionLog` row already recorded) fully
   applied and never undone, while the coach's visible status silently reverts to `ACTIVE` with **no
   error, no conflict response, and no re-verification** — `reinstateCoach`'s own `resolveOpenStrikeAlert`
   call also resolves whatever `STRIKE_THRESHOLD` alert was open, based on Admin A's stale view, even
   though a fresh suspension (and possibly a fresh alert) may have just landed. An admin's suspension
   action is silently, invisibly overridden by a reinstate call that raced it — the opposite of what
   either admin intended, and nothing in either audit log entry reveals that a race occurred.

`deleteStrike`'s revert-to-`ACTIVE` path has the same shape against the same set of concurrent writers,
just gated by a different condition (`count < visibilityThreshold`).

**Correction (2026-09-18, `/bmad-code-review` — Blind Hunter and Acceptance Auditor, independently):**
the `suspendCoach`-races-`reinstateCoach` "starker version" in steps 3–5 above is **not** actually
closed by this story's fix, and the failure scenario's own framing ("an admin's suspension action is
silently, invisibly overridden") overstates what the lock changes. `SUSPENDED` is, and remains after
this fix, an explicitly legal source status for `reinstateCoach` (`:162-163`) — so once Admin A's
`reinstateCoach` call re-reads under its own lock and correctly observes the *fresh* `SUSPENDED` state
Admin B's `suspendCoach` just committed, it still proceeds to overwrite it to `ACTIVE`, because that is
exactly what an explicit reinstate call is defined to do from `SUSPENDED`. The lock makes the read
fresh; it does not make `reinstateCoach` reject a fresh suspension, because nothing in its business
logic distinguishes "this `SUSPENDED` predates my stale snapshot" from "this `SUSPENDED` just landed
after I re-read." Whether an explicit admin reinstate call *should* be allowed to override a
just-landed concurrent suspension is a genuine, separate product question this story does not answer —
recorded as a new deferred item, not folded into this AC.

**What this story's fix actually closes, and what the new test actually proves:** the case where the
concurrent writer's fresh state is `ACTIVE` — e.g., a second `reinstateCoach` call, or any other locked
writer that lands on `ACTIVE` — while this call's decision was mid-flight. Pre-fix, a stale non-`ACTIVE`
read sails past the `:159-161` early-return and re-runs the write, publishing a second
`CoachReinstatedEvent` and inserting a second `COACH_REINSTATE` `admin_action_log` row for a coach that
was already reinstated — a duplicate/idempotency defect, not a suspension-override defect. Post-fix,
the locked re-read observes the fresh `ACTIVE` state and no-ops via the existing early-return. This is
exactly what `AdminCoachEnforcementConcurrencyIT.reinstateCoach_contendsWithConcurrentStatusChange_actsOnFreshNotStaleState`
demonstrates and what its mutation check verifies. `deleteStrike`'s analogous fix is fully accurate as
written — its revert decision has no such legal-source-state escape hatch, so the fresh-count re-read
does change the outcome in the concurrent-strikes scenario its own test proves.

**Fix:**

- `reinstateCoach`: replace the plain `findById` at `:151` with the same
  `lockRetryer.withBoundedRetry(() -> coachProfileRepository.findByIdForUpdate(coachId).orElseThrow(...))`
  call `suspendCoach` already uses at `:107-108` — same exception message, same pattern, no new helper
  needed. No other line in the method needs to change: the existing status checks at `:154-161` already
  run against whatever `coach` variable is bound, so binding it to the locked read is sufficient.
- `deleteStrike`: replace the plain `findById` at `:224` with the identical
  `lockRetryer.withBoundedRetry(...findByIdForUpdate...)` call, and **move it to before the `count`
  computation at `:220`** — not just before the `reverted` decision at `:231` — so the count and the
  status decision are read consistently under the same lock, mirroring `ReliabilityStrikeService.issue`'s
  own ordering (`:69-79`'s comment: "the count read is deliberately moved *below* [the lock] so the count
  and the status decision are consistent for the winner"). The strike deletion itself (`:218`) can stay
  where it is — it doesn't touch the coach row.

**Verified by:** a new integration test that forces a *deterministic* interleaving rather than racing two
threads and hoping the scheduler cooperates — see Task 3 for why an uncontrolled simultaneous-release
race (the `ReliabilityStrikeConcurrencyIT` shape) does not actually discriminate pre-fix from post-fix
behavior here, and what to build instead (the `RescheduleServiceConcurrencyIT` holder-thread shape,
combined with an event/action-log assertion, not an elapsed-time one).

---

### AC2 — Ledger hygiene closeout

- [x] Re-run the grep sweep this story's Provenance section already ran (`AdminCoachEnforcementService`,
      `reinstateCoach`, `deleteStrike`, `CoachProfile.status`, `findByIdForUpdate`) against HEAD
      immediately before marking this story done, and confirm no bullet added by another story in the
      interim now overlaps this one.
- [x] Confirm `deferred-work.md`'s one historical hit under `## Last audit: 2026-08-05 (skillars-deferred-15
      story creation)` (the `suspendCoach:101` "plain `findById`" narrative) still correctly describes
      already-fixed history, not this story's finding — it should require no edit.

---

## Tasks/Subtasks

- [x] **Task 1 — AC1: lock `reinstateCoach`**
  - [x] Replace the plain `findById` at `AdminCoachEnforcementService.java:151` with
        `lockRetryer.withBoundedRetry(() -> coachProfileRepository.findByIdForUpdate(coachId)
        .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile")))`
        — copy `suspendCoach`'s `:107-108` call exactly (same exception, same message)
  - [x] Confirm no other line in `reinstateCoach` needs to change (the existing status-check and write
        logic already operate on the `coach` variable)

- [x] **Task 2 — AC1: lock `deleteStrike`**
  - [x] Replace the plain `findById` at `:224` with the same `lockRetryer.withBoundedRetry(...
        findByIdForUpdate...)` call, keeping the exact same `.orElseThrow(() -> new
        ResourceNotFoundException("Coach profile not found", "coach_profile"))` the plain `findById` at
        `:224-225` already throws today — same exception, same message, as Task 1 does for `reinstateCoach`
  - [x] Move that locked read to before the `count` computation currently at `:220`, so `count` and the
        `reverted` decision are both computed after the lock is held (mirroring
        `ReliabilityStrikeService.issue`'s ordering) — leave the strike-delete call at `:218` where it is

- [x] **Task 3 — AC1: regression test proving the fix**
  - **Why a `ReliabilityStrikeConcurrencyIT`-style simultaneous-release race is the wrong shape here:**
    that test's correctness property is order-*independent* — `issue()` converges to the same final state
    (`PENDING_REVIEW`, one event) no matter which of two identical concurrent calls wins, so a bare
    `CountDownLatch` release proves the fix regardless of scheduler timing. `reinstateCoach` vs.
    `suspendCoach` is order-*dependent by design* — they write opposite states, and whichever one's
    transaction genuinely commits last is *correctly* the one that should win once the lock is real,
    both before and after this fix (Postgres row locks make every writer's `UPDATE` block on a
    concurrent `SELECT ... FOR UPDATE` regardless of whether the *reader* took a lock — only the
    *decision* differs). A test that races both with a bare latch and asserts "final status matches
    whoever won" cannot prove anything: it can't observe which thread the DB scheduler actually favored,
    and the elapsed-time trick `RescheduleServiceConcurrencyIT` uses to prove *its* fix (see below)
    doesn't discriminate here either, because the blocking point — the eventual `UPDATE` — exists on the
    unfixed code path too. Do not build the test this way.
  - **What actually discriminates pre-fix from post-fix:** whether `reinstateCoach`'s *decision* (not
    just its final write) is computed from stale or fresh data. Use a scenario where a stale read and a
    fresh read produce *different outcomes*, not just a different-timed version of the same outcome:
    - Seed a coach at `PENDING_REVIEW` (a legal `reinstateCoach` source state).
    - **Holder thread** (mirror `RescheduleServiceConcurrencyIT.acceptReschedule_briefContentionOnRescheduleRequestRow_succeedsAfterBoundedRetry`'s
      shape exactly): inside `transactionTemplate.execute`, run raw JDBC
      `SELECT id FROM marketplace.coach_profiles WHERE id = ? FOR UPDATE`, count down a `CountDownLatch`,
      `Thread.sleep(holdMillis)`, then raw-SQL `UPDATE marketplace.coach_profiles SET status = 'ACTIVE',
      status_changed_at = ? WHERE id = ?` before the transaction block returns (committing `ACTIVE` —
      simulating "this coach was already reinstated/activated by another concurrent process while my
      `reinstateCoach` call was in flight").
    - Wait on the latch, then call `enforcementService.reinstateCoach(coachId, "test", adminId)` as the
      contender, from the main thread (no second thread needed — the holder already ran concurrently).
    - **Pre-fix:** the contender's plain `findById` executes *while the holder's transaction is still
      open* (MVCC serves the last-committed value, `PENDING_REVIEW`, not blocked by the holder's `FOR
      UPDATE`), so it passes the legal-source check on stale data, later blocks at its own `save()`
      flush until the holder commits, then unconditionally writes `ACTIVE` again — publishing a second
      `CoachReinstatedEvent` and inserting a second `admin_action_log` row with
      `action_type = 'COACH_REINSTATE'`, even though the coach was already `ACTIVE`.
    - **Post-fix:** the contender's `findByIdForUpdate` blocks until the holder commits, then reads the
      *fresh* `ACTIVE` row and hits the method's own `if (coach.getStatus() == ACTIVE) return;` early-out
      — no write, no event, no action-log row.
    - **Assert (post-fix):** zero `CoachReinstatedEvent`s captured for this coach (mirror
      `ReliabilityStrikeConcurrencyIT`'s `@EventListener`-based capture-component pattern) and zero new
      `admin_action_log` rows with `reference_id = coachId AND action_type = 'COACH_REINSTATE'` — not an
      elapsed-time assertion, which proves nothing here.
  - [x] Add a new `AdminCoachEnforcementConcurrencyIT` (package `platform.admin.service`, extending
        `AbstractIntegrationTest` directly, calling `AdminCoachEnforcementService` methods directly — not
        an HTTP client) implementing the scenario above
  - [x] **Mutation check** (write this into the test's own Javadoc, matching
        `ReliabilityStrikeConcurrencyIT`'s convention): revert the fix (plain `findById` again) and
        confirm the test fails deterministically — this scenario is 100% reproducible, not
        timing-flaky, because the contender's stale read is guaranteed to happen while the holder's
        transaction is still open (the test only starts the contender after the latch confirms the
        holder's lock is held)
  - [x] Add a second, analogous test (or a lighter-weight unit/Mockito-style assertion of read-then-decide
        ordering if a second full concurrency IT is disproportionate — use judgement) for `deleteStrike`'s
        revert path: use the same holder-thread technique to concurrently insert enough fresh strikes
        (raw SQL) to push the rolling count back *above* `visibilityThreshold` while `deleteStrike` is
        in flight — pre-fix, a stale count read wrongly reverts the coach to `ACTIVE`; post-fix, the
        fresh post-lock count read correctly leaves it un-reverted. At minimum, confirm `deleteStrike`'s
        revert path still passes its existing non-concurrent behavior unchanged.

- [x] **Task 4 — AC2: ledger closeout**
  - [x] Re-run the grep sweep against HEAD; confirm the one historical `deferred-work.md` hit needs no
        edit

- [x] **Task 5 — Final validation**
  - [x] Run `AdminCoachEnforcementConcurrencyIT`, `ReinstateIT`, `CoachSuspensionIT`, `ManualStrikeIT`,
        `CoachEnforcementListIT` together — zero regressions
  - [x] Update Verification Checklist, File List, Change Log, Dev Agent Record
  - [x] Mark story Status → done

---

### Review Findings

_From `/bmad-code-review` on 2026-09-18 — three parallel layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor)._

**Decision needed**

- [x] [Review][Decision] Explicit `FOR UPDATE` now conflicts with the FK's `FOR KEY SHARE`, creating a new 409 surface on both methods — Pre-change, the only lock these methods took on `coach_profiles` was the implicit `FOR NO KEY UPDATE` of `save(coach)`'s status UPDATE, which is *compatible* with the `FOR KEY SHARE` that any in-flight child-row INSERT holds on the parent row (`coach_reliability_strikes_coach_id_fkey`, and every other FK to `coach_profiles`). `findByIdForUpdate` takes an explicit `FOR UPDATE`, which conflicts. A concurrent `ReliabilityStrikeService.issue`, booking insert, or pricing insert that holds the coach row for longer than `PessimisticLockRetryer`'s ~3.2s budget now turns a previously-succeeding admin reinstate/strike-delete into a `PessimisticLockingFailureException` → 409. `suspendCoach` has carried this same property since skillars-deferred-15 without incident, so accepting it is defensible — but the story never articulates the tradeoff, and `LockModeType` offers no `FOR NO KEY UPDATE` equivalent, so the fix is not unambiguous. Options: (a) accept and document, consistent with `suspendCoach`; (b) narrow the lock via a native query; (c) raise the retry budget for these paths. [src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java:156,228]

**Patch**

- [x] [Review][Patch] `reinstateCoach`'s justification comment describes a bug the fix does not prevent — a fresh `SUSPENDED` read still reinstates, because `SUSPENDED` is an explicitly legal source status (`:162-163`). The fix changes behaviour only when the fresh status is `ACTIVE`, i.e. the duplicate-reinstate case the new test actually proves (second `CoachReinstatedEvent` + second `COACH_REINSTATE` action-log row). Raised independently by Blind Hunter and Acceptance Auditor. Rewrite the comment, and the matching AC1 narrative and sprint-status entry, to describe the defect that is actually closed. [src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java:150-155]
- [x] [Review][Patch] IT javadoc and story claim `findByIdForUpdate` "blocks until the holder commits" — it is NOWAIT (`CoachProfileRepository.java:36`, `jakarta.persistence.lock.timeout = 0`); it fails immediately and `PessimisticLockRetryer` retries on a ~3.2s jittered budget. The "deterministic, not timing-flaky" claim inherits the same caveat: the pre-fix failure depends on the contender's read landing inside the holder's 1200ms sleep, and the post-fix pass depends on the retry budget exceeding the hold. `RescheduleServiceConcurrencyIT:150-153` documents that budget explicitly; the copy dropped it. [src/test/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementConcurrencyIT.java:56-65]
- [x] [Review][Patch] Missing prolonged-contention test — the mirrored `RescheduleServiceConcurrencyIT` pairs its brief-contention success case with an 8000ms-hold case asserting a bounded `PessimisticLockingFailureException` (`:206-249`). This IT copies only the first. Add the second for `deleteStrike`, which also pins that the flushed strike DELETE is rolled back on the exhaustion path. Raised by both Edge Case Hunter and Acceptance Auditor. [src/test/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementConcurrencyIT.java:122,180]
- [x] [Review][Patch] Add the assertions that stop a vacuous pass — assert the contender actually waited (`elapsed >= holdMillis - 200`, as `RescheduleServiceConcurrencyIT:186-188` does), and in test 2 assert the strike row was actually deleted and a `COACH_STRIKE_DELETED` action-log row written. Both tests currently assert only absence, so a run in which no contention occurred is indistinguishable from a correct one. [src/test/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementConcurrencyIT.java:155-169,227-232]
- [x] [Review][Patch] Holder thread is not joined on the failure path — `holder.get(15, SECONDS)` sits after `contender.get(20, SECONDS)` inside the `try`, so any contender failure skips it and `finally` only calls `pool.shutdownNow()`, which does not wait. A holder left mid-transaction still holds its row lock, which then blocks `DatabaseResetTestExecutionListener`'s `TRUNCATE` (needs `ACCESS EXCLUSIVE`) in the next test. Separately, `contender.get(20)` + `holder.get(15)` = 35s exceeds the method's `@Timeout(30)`, so a genuinely stuck contender aborts on the JUnit timeout and discards the `TimeoutException` that would have named which future hung. [src/test/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementConcurrencyIT.java:147-153,219-225]
- [x] [Review][Patch] Add a warning comment at `issueManualStrike`'s unlocked `findById` — its *absence* of a lock is load-bearing. `ReliabilityStrikeService.issue` is `Propagation.REQUIRES_NEW`; if a future story "completes the pattern" by mirroring this fix onto that line, the outer REQUIRED transaction's `FOR UPDATE` and the inner suspended transaction's `NO_WAIT FOR UPDATE` collide on the same row on every call — guaranteed retry exhaustion, 409 on every manual strike. Nothing currently records this. [src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java:192-193]
- [x] [Review][Patch] File List is incomplete — it names the service and the new IT but omits `_bmad-output/implementation-artifacts/sprint-status.yaml` and the story file itself, both of which the working tree shows as modified. [_bmad-output/implementation-artifacts/skillars-deferred-121-coach-enforcement-status-toctou-lock-gap.md:369-377]
- [x] [Review][Patch] Provenance grep count is off — "six sites total" for `\.setStatus(CoachProfileStatus\.` actually returns eight hits across five files; the inventory table has six rows because it merges `ReliabilityStrikeService:96,104` and the two `CoachProfileService` draft writes. The table itself is complete and correct; only the narrative count is wrong. [_bmad-output/implementation-artifacts/skillars-deferred-121-coach-enforcement-status-toctou-lock-gap.md:31-33]

**Deferred (pre-existing, not caused by this change)**

- [x] [Review][Defer] `deleteStrike` has no path back to `REDUCED`: a post-delete count in `[visibilityThreshold, suspensionThreshold)` leaves the coach in `PENDING_REVIEW` with a sub-suspension strike count [AdminCoachEnforcementService.java:239-241] — deferred, pre-existing
- [x] [Review][Defer] `visibilityThreshold > suspensionThreshold` is an accepted configuration (both bounded independently at `1..Long.MAX_VALUE`), under which the revert can fire while the fresh count still exceeds the suspension threshold [AdminCoachEnforcementService.java:232-241] — deferred, pre-existing
- [x] [Review][Defer] Deleting a strike that is already outside the 30-day window still fires a full revert-to-`ACTIVE` plus alert resolution, even though the delete changed nothing [AdminCoachEnforcementService.java:231-246] — deferred, pre-existing
- [x] [Review][Defer] Concurrent duplicate `deleteStrike` surfaces `StaleStateException` rather than a 404 — `findById(strikeId)` takes no lock and there is no post-lock existence re-check [AdminCoachEnforcementService.java:216-223] — deferred, pre-existing
- [x] [Review][Defer] The strike DELETE is flushed by `withBoundedRetry`'s `entityManager.flush()` *before* the savepoint, so its wait is unbounded, unretried, and silently folded into the `persistence.lock_retry` timer — relocated by this change, not introduced by it [PessimisticLockRetryer.java:132-134] — deferred, pre-existing
- [x] [Review][Defer] `issueManualStrike` applies no coach-status guard: the coach can be `DEACTIVATED`/`SUSPENDED` between its unlocked existence check and `issue`'s locked read [AdminCoachEnforcementService.java:192-193] — deferred, pre-existing
- [x] [Review][Defer] `getEnforcementProfile` composes status and strike count from two unlocked statements under READ COMMITTED, so the admin UI can see `status=PENDING_REVIEW` with `activeStrikes=2` [AdminCoachEnforcementService.java:75-78] — deferred, pre-existing
- [x] [Review][Defer] `OffsetDateTime.now()` for the 30-day count window is evaluated after the lock wait, so the window origin slides with contention — same shape in `ReliabilityStrikeService.issue:90` [AdminCoachEnforcementService.java:231] — deferred, pre-existing

---

## Dev Notes

- **No local `mvn verify`** per project convention (`docs/validation-strategy.md`) — GitHub CI is the
  sole full-verification gate. Run targeted suites only.
- **This is a correctness fix, not a hardening measure.** Unlike `skillars-deferred-120`'s AC1/AC2 (which
  needed a future multi-instance deployment to be reachable), this race is reachable today by two ordinary
  concurrent admin requests, or one admin request racing an automatic strike-threshold escalation — no
  scale threshold, no deployment change. Prioritize accordingly.
- **Do not touch `CoachProfileService.publishProfile`.** It was checked (see the inventory table above)
  and reads a `profile` instance that was already locked and refreshed one step earlier in the same
  profile-builder flow (`saveStep4`, `:249-255`), inside the same conceptual multi-step submission the
  user drives themselves — there is no plausible concurrent second writer of a still-`DRAFT` coach's
  status racing a self-service publish call the way an admin action can race another admin action. Adding
  a redundant second lock acquisition here would be unjustified scope creep for this story.
- **Do not touch `DisputeService`, `ReviewFlagService`, or `GdprRequestService`.** All three were
  investigated as candidates and ruled out — see "Examined and ruled out as false leads" in Provenance.
  Do not re-open them without new evidence.
- **`lockRetryer` is already an injected field** (`AdminCoachEnforcementService.java:71`,
  `PessimisticLockRetryer`) — no new dependency, no new field, just two new call sites using it.
- **Testing approach:** mirror `ReliabilityStrikeConcurrencyIT`'s two-thread `CountDownLatch`/
  `ExecutorService` race shape exactly — it is the established precedent in this codebase for proving a
  `findByIdForUpdate` fix actually closes a `CoachProfile`-row race, including its "mutation check"
  Javadoc convention (state what reverting the fix does to the test, so a future reader can trust the
  test is load-bearing, not just green by construction).
- **Seeding a coach for the IT:** copy `ReinstateIT.setUp()`'s raw-`jdbcTemplate` coach-profile insert
  pattern (`marketplace.coach_profiles` with an explicit `status` column) rather than going through the
  profile-builder API — it's faster and this story's concurrency test needs a coach already at a specific
  status, not a freshly-built one.

### Project Structure Notes

- AC1 touches `platform.admin.service` (`AdminCoachEnforcementService`) only — no new module, no
  migration, no new dependency.
- No REST contract changes — `AdminCoachEnforcementResource`'s existing endpoints are unaffected; the fix
  is entirely inside the service layer's read/lock discipline.

---

## Files to Read Before Implementation

- `src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java` (AC1 —
  whole file; `suspendCoach:101-147` is the exact pattern to mirror twice)
- `src/main/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeService.java:39-113`
  (AC1 reference — `issue()`'s locked count-then-decide ordering and its own extensive comment
  explaining why `CoachProfile`'s missing `@Version` makes the lock mandatory, not optional)
- `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfile.java` (AC1 — confirm no
  `@Version` field before assuming the lock, not an optimistic backstop, is the only fix)
- `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfileRepository.java` (AC1 —
  confirm `findByIdForUpdate`'s existing signature/lock mode; no repository change needed, just a new
  call site)
- `src/test/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeConcurrencyIT.java`
  (AC1 — the `StrikeEventCapture` `@EventListener` capture-component pattern and mutation-check Javadoc
  convention to mirror; **do not** mirror its bare-`CountDownLatch`-simultaneous-release race shape — see
  Task 3 for why that shape doesn't discriminate pre-fix from post-fix for an order-dependent write)
- `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceConcurrencyIT.java:155-198`
  (AC1 — `acceptReschedule_briefContentionOnRescheduleRequestRow_succeedsAfterBoundedRetry`'s holder-thread
  shape to mirror instead: a raw-JDBC `SELECT ... FOR UPDATE` + `CountDownLatch` + `Thread.sleep` +
  committed raw-SQL write, giving the test a *deterministic* forced interleaving instead of an
  uncontrolled race — the elapsed-time assertion in that specific test does not carry over to this story's
  fix (its blocking point exists on both sides of this story's fix, so it doesn't discriminate here); only
  the holder-thread *mechanism* is the reusable part)
- `src/test/java/com/softropic/skillars/platform/admin/api/ReinstateIT.java` (AC1 — the raw-SQL
  coach-profile seeding pattern for a coach starting at a specific `CoachProfileStatus`, and the
  `@BeforeEach`/`@AfterEach` cleanup shape for `marketplace.coach_profiles` rows in this module)
- `src/test/java/com/softropic/skillars/platform/admin/api/CoachSuspensionIT.java` (AC1 — existing
  `suspendCoach` test coverage to confirm nothing here needs to change)
- `src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java` (AC1 —
  confirm `withBoundedRetry`'s contract before using it in two new call sites)
- `_bmad-output/implementation-artifacts/skillars-deferred-100-*.md` and
  `skillars-deferred-15-*.md` (if present under `_bmad-output/implementation-artifacts/`) or, failing
  that, the code comments at `ReliabilityStrikeService.java:69-79` and
  `AdminCoachEnforcementService.java:103-106` — the two prior fixes of this exact bug class on this exact
  row, whose reasoning this story's fix must match, not reinvent

---

## Verification Checklist

- [x] AC1: `reinstateCoach` and `deleteStrike` both read `CoachProfile` via
      `lockRetryer.withBoundedRetry(() -> coachProfileRepository.findByIdForUpdate(...))`, matching
      `suspendCoach`'s existing pattern; `deleteStrike`'s locked read happens before its `count`
      computation, not after
- [x] AC1: new `AdminCoachEnforcementConcurrencyIT` proves `reinstateCoach` no longer acts on a stale
      pre-lock read — a holder thread commits a concurrent status change mid-flight and the test asserts
      `reinstateCoach`'s decision reflects the post-holder fresh state (no duplicate `CoachReinstatedEvent`,
      no duplicate `admin_action_log` row when the fresh state makes the transition a no-op), not an
      elapsed-time proxy; the test's Javadoc states what reverting the fix does to the test (mutation
      check), and the failure is deterministic, not timing-flaky
- [x] AC2: ledger grep sweep re-run against HEAD; the one historical hit confirmed to need no edit
- [x] No regressions in `AdminCoachEnforcementConcurrencyIT`, `ReinstateIT`, `CoachSuspensionIT`,
      `ManualStrikeIT`, `CoachEnforcementListIT`, or any other test touching
      `AdminCoachEnforcementService`

---

## File List

- `src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java` (modified
  — AC1: `reinstateCoach` and `deleteStrike` now read `CoachProfile` via `lockRetryer.withBoundedRetry(()
  -> coachProfileRepository.findByIdForUpdate(...))` instead of a plain `findById`; `deleteStrike`'s locked
  read moved to before its rolling-strike-count computation; comments corrected and extended per the
  2026-09-18 code review — see Change Log)
- `src/test/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementConcurrencyIT.java` (new
  — AC1: deterministic holder-thread concurrency IT proving `reinstateCoach` and `deleteStrike` act on
  fresh, not stale, post-lock state; mutation-checked against the pre-fix code; extended per the
  2026-09-18 code review with a prolonged-contention test, vacuous-pass guards, and a robustness fix —
  see Change Log)
- `_bmad-output/implementation-artifacts/skillars-deferred-121-coach-enforcement-status-toctou-lock-gap.md`
  (this file — modified: Tasks/Subtasks, Dev Agent Record, File List, Change Log, Status per the dev-story
  workflow; AC1 narrative corrected and Provenance grep-count fixed per the 2026-09-18 code review)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (modified — `development_status` entry for
  this story key updated to `review`, then a follow-up note appended for the code review response)
- `_bmad-output/implementation-artifacts/deferred-work.md` (modified — new "Deferred from: code review of
  skillars-deferred-121..." section recording the 8 pre-existing, out-of-scope findings the review
  surfaced in `AdminCoachEnforcementService`)

---

## Change Log

- 2026-09-18: Code review response applied (`/bmad-code-review`, three parallel layers — Blind Hunter,
  Edge Case Hunter, Acceptance Auditor). Independently re-verified every Decision/Patch finding against
  the actual code before applying anything (per explicit instruction to watch for false positives) —
  **zero false positives found; all 9 findings (1 decision + 8 patch) confirmed genuine and applied.**
  Most significant: the code comment and the story's own AC1 "Failure scenario" narrative both
  overstated what the fix closes — `SUSPENDED` remains an explicitly legal `reinstateCoach` source
  status even after the fix, so the `suspendCoach`-races-`reinstateCoach` scenario the story led with
  (an admin's suspension "silently, invisibly overridden") is **not** actually prevented by this
  story's lock; that is a separate, unaddressed product question (recorded as a new deferred item, not
  folded into this AC), and the comment/narrative now correctly describe what the fix and its test
  actually prove instead: a duplicate-reinstate/redundant-event defect when the concurrent writer's
  fresh state is `ACTIVE`. `deleteStrike`'s fix and narrative were already accurate as written (no
  legal-source-state escape hatch on that path). The `FOR UPDATE` vs. the FK's `FOR KEY SHARE` tradeoff
  (a new 409 surface under sustained contention with an FK-child-inserting transaction) is real and now
  documented at both call sites — accepted, consistent with `suspendCoach` having carried the identical
  property since `skillars-deferred-15` without incident. Added a warning comment at
  `issueManualStrike`'s unlocked `findById` recording why it must stay unlocked (a future naive "complete
  the pattern" fix there would self-deadlock against `ReliabilityStrikeService.issue`'s
  `REQUIRES_NEW`). Test hardening: corrected "blocks until the holder commits" to the accurate
  NOWAIT-plus-~3.2s-bounded-retry mechanism throughout; added elapsed-time lower-bound assertions to
  both existing tests so a run with no genuine contention can no longer pass vacuously; added
  strike-deleted and `COACH_STRIKE_DELETED`-log assertions to the `deleteStrike` revert test; restructured
  both tests so the holder thread is always joined even if the contender fails unexpectedly (previously
  skipped on the failure path, risking a leaked row lock blocking the next test's `TRUNCATE`), and raised
  `@Timeout` from 30 to 45 to comfortably clear the `contender.get(20)+holder.get(15)` budget; added a new
  `deleteStrike_prolongedContentionOnCoachRow_...` test mirroring
  `RescheduleServiceConcurrencyIT`'s 8000ms-hold case, proving a bounded `PessimisticLockingFailureException`
  under sustained contention and that the already-flushed strike DELETE rolls back with the rest of the
  transaction. **Self-caught bug while adding the prolonged-contention test:** the first draft measured
  `elapsedMillis` *after* `holder.get()` inside the same `finally`, so the measurement included the
  holder's remaining ~5s sleep on top of the contender's real ~3s retry-exhaustion (observed 8006ms vs.
  an asserted `<4500ms` bound) — moved the measurement to before `holder.get()` in all three tests;
  re-ran and confirmed correct (~3s exhaustion, assertion passes). Mutation-checked the new/changed
  tests by hand: reverted the service fix (`git stash`), confirmed all three
  `AdminCoachEnforcementConcurrencyIT` tests fail deterministically (including the new prolonged-contention
  test, which fails because an unlocked `deleteStrike` never contends at all — no exception is thrown),
  then restored the fix. Full targeted suite (`AdminCoachEnforcementConcurrencyIT`, `ReinstateIT`,
  `CoachSuspensionIT`, `ManualStrikeIT`, `CoachEnforcementListIT`) re-run together: 18/18 pass, zero
  regressions. Provenance grep-count narrative corrected (eight occurrences across three files, not "six
  sites"; the reviewer's own "five files" was itself off by one — actually three). File List extended to
  include the story file, `sprint-status.yaml`, and `deferred-work.md` (all were already modified but
  omitted). No production code *behavior* changed by this response — only comments/documentation and test
  coverage; the underlying lock fix from the initial implementation is unchanged.
- 2026-09-18: Story implemented via `/bmad-dev-story`. `AdminCoachEnforcementService.reinstateCoach` and
  `.deleteStrike` now take `coachProfileRepository.findByIdForUpdate` under `lockRetryer.withBoundedRetry`
  before deciding/writing `CoachProfile.status`, mirroring `suspendCoach`'s existing pattern exactly (same
  exception, same message); `deleteStrike`'s locked read was moved to before its rolling strike-count
  computation so the count and the revert decision are read consistently under the same lock, mirroring
  `ReliabilityStrikeService.issue`'s established ordering. New `AdminCoachEnforcementConcurrencyIT`
  (package `platform.admin.service`) proves both fixes with a deterministic holder-thread interleaving
  (mirroring `RescheduleServiceConcurrencyIT`'s raw-JDBC-lock-then-sleep mechanism) rather than an
  uncontrolled race, discriminating via duplicate-event/duplicate-action-log and stale-count-revert
  assertions rather than elapsed time. Mutation-checked by hand: temporarily reverted both fixes back to
  plain `findById` and confirmed both new tests fail deterministically (`reinstateCoach`'s test caught a
  spurious `CoachReinstatedEvent`; `deleteStrike`'s test caught a wrongful revert to `ACTIVE` on a stale
  count), then restored the fix. AC2 ledger grep sweep re-run against HEAD — the one historical
  `deferred-work.md` hit (`AdminCoachEnforcementService.suspendCoach:101`, `skillars-deferred-15` AC4)
  still correctly describes already-fixed history, not this story's finding; no edit needed. Full
  targeted suite (`AdminCoachEnforcementConcurrencyIT`, `ReinstateIT`, `CoachSuspensionIT`,
  `ManualStrikeIT`, `CoachEnforcementListIT`) run together: 17/17 pass, zero regressions. No `mvn verify`
  run locally per project convention — GitHub CI is the sole full-verification gate.
- 2026-09-17: Story created via `/bmad-create-story`. Fresh ad-hoc audit (transaction-safety/concurrency
  lens, not the now-closed scheduler-lock lens) applied to `platform.admin`, `platform.marketplace`,
  `platform.reviews`, `platform.session` — modules no prior `skillars-deferred-11x` story had examined.
  One genuine, live (not forward-looking) bug found: `AdminCoachEnforcementService.reinstateCoach` and
  `.deleteStrike` write `CoachProfile.status` without the `findByIdForUpdate` lock every other writer of
  that field uses, breaking the write-skew protection `skillars-deferred-15` AC4 and
  `skillars-deferred-100` AC1 already established for this exact row. Three candidates investigated and
  ruled out as false leads (`DisputeService` — protected by `@Version`; `ReviewFlagService` — DB unique
  index already prevents the serious case, remainder is idempotent duplicate work; `GdprRequestService` —
  already backstopped by a DB partial unique index + a global exception handler). Branched off master
  post-`skillars-deferred-120`-merge (PR #208).
- 2026-09-18: Story reviewed post-creation (`story-review.md`) — PASS, no false positives, bug premise
  and all three false-lead dismissals independently re-confirmed against the actual code (including a
  fresh direct read of `CoachRatingService.recompute()` to confirm its idempotency rather than assume
  it). One substantive gap found in the review and fixed: Task 3's original test design (an uncontrolled
  simultaneous-release race between `suspendCoach` and `reinstateCoach`, mirroring
  `ReliabilityStrikeConcurrencyIT`) does not actually discriminate pre-fix from post-fix behavior for an
  *order-dependent* write — unlike `issue()`'s order-*independent* convergence, and unlike
  `RescheduleServiceConcurrencyIT`'s elapsed-time proof (whose blocking point exists on both sides of
  *this* fix, so timing alone proves nothing here). Replaced with a deterministic holder-thread design
  (mirroring `RescheduleServiceConcurrencyIT`'s raw-SQL-lock-then-sleep mechanism, but discriminating via
  a duplicate-event/duplicate-action-log assertion instead of elapsed time) that is 100% reproducible,
  not timing-flaky, both as a bug demonstration and as a regression guard. Minor fixes: `deleteStrike`'s
  fix task now states the exact exception to preserve (previously only implied by "the same call");
  added a concrete, equally deterministic discriminator for `deleteStrike`'s own optional concurrency
  test (concurrent strikes pushing the count back above threshold, instead of an unspecified "call
  order" assertion).

---

## Dev Agent Record

### Implementation Plan

1. Read `AdminCoachEnforcementService.java` (`suspendCoach`, `reinstateCoach`, `deleteStrike`) and
   `ReliabilityStrikeService.issue` to confirm the exact locked-read pattern and exception/message to
   mirror, plus `CoachProfileRepository.findByIdForUpdate` (NOWAIT + `PessimisticLockRetryer` retry) and
   confirmed `CoachProfile` has no `@Version`.
2. Task 1: replaced `reinstateCoach`'s plain `findById` with `lockRetryer.withBoundedRetry(() ->
   coachProfileRepository.findByIdForUpdate(coachId).orElseThrow(...))`, same exception/message as
   `suspendCoach`. No other line changed — the existing status checks already operate on the `coach`
   variable.
3. Task 2: replaced `deleteStrike`'s plain `findById` with the same locked-read call, and moved it to
   before the rolling `count` computation (previously after), mirroring `ReliabilityStrikeService.issue`'s
   ordering comment.
4. Task 3: read `ReliabilityStrikeConcurrencyIT` (event-capture + mutation-check Javadoc convention) and
   `RescheduleServiceConcurrencyIT` (holder-thread raw-JDBC-lock-then-sleep mechanism) as directed by the
   story's Dev Notes, then wrote `AdminCoachEnforcementConcurrencyIT` with two tests: one holder thread
   commits a concurrent `ACTIVE` status change while holding the row's `FOR UPDATE` lock, and the
   contender's `reinstateCoach`/`deleteStrike` call is only started once the lock is confirmed held —
   discriminating pre-fix (stale read passes the legal-transition/count check, second event/duplicate
   action-log row) from post-fix (locked read blocks until the holder commits, then reads fresh state and
   no-ops) behavior deterministically, not via elapsed time.
5. Compiled (`mvn test-compile`), ran the new IT alone (2/2 pass), then git-stashed the service-file fix
   to perform the story's required mutation check against the pre-fix code (both tests failed
   deterministically, confirming they are load-bearing), then restored the fix (`git stash pop`) and
   re-verified compilation.
6. Task 4: re-ran the ledger grep sweep against HEAD (`AdminCoachEnforcementService`, `reinstateCoach`,
   `deleteStrike`, `CoachProfile.status`, `findByIdForUpdate`) — the only hit is the pre-existing,
   already-fixed `suspendCoach:101` narrative under `deferred-15`'s audit note; no edit needed.
7. Task 5: ran the full targeted suite together (`AdminCoachEnforcementConcurrencyIT`, `ReinstateIT`,
   `CoachSuspensionIT`, `ManualStrikeIT`, `CoachEnforcementListIT`) — 17/17 pass, zero regressions. Updated
   Verification Checklist, File List, Change Log, and this Dev Agent Record; set Status to `review`.

### Debug Log

- No blocking issues. One self-caught risk while designing the `deleteStrike` concurrency test: the
  initial seed count needed to land the coach in a revertible pre-state (`count < visibilityThreshold`
  and status `PENDING_REVIEW`) before the holder's concurrent strikes push it back over — used
  `visibilityThreshold - 1` seeded strikes plus the strike-to-delete (so post-delete count =
  `visibilityThreshold - 1`), then the holder inserts 2 more to land above threshold. Verified against
  the real default (`ReliabilityStrikeConfig.DEFAULT_VISIBILITY_THRESHOLD`) via `configService`, not a
  hardcoded literal, so the test stays correct if the default ever changes.

### Completion Notes

- ✅ AC1 implemented: `reinstateCoach` and `deleteStrike` both now take the row lock every other writer
  of `CoachProfile.status` already uses, closing the last two of six unlocked write sites.
- ✅ AC1 verified by a new, deterministic (non-flaky) concurrency IT with a hand-run mutation check
  (fix reverted → both tests fail; fix restored → both pass).
- ✅ AC2 (ledger closeout) confirmed — no `deferred-work.md` edit needed.
- ✅ No regressions: 17/17 in the targeted suite; no `mvn verify` run locally per project convention
  (GitHub CI is the sole full-verification gate).
- Scope discipline: did not touch `CoachProfileService.publishProfile`, `DisputeService`,
  `ReviewFlagService`, or `GdprRequestService`, per the story's explicit Dev Notes guidance.
