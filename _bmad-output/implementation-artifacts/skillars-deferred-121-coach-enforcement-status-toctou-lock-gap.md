# Story: Coach Enforcement Status TOCTOU Lock Gap

**Story Key:** `skillars-deferred-121-coach-enforcement-status-toctou-lock-gap`
**Epic:** Deferred Work
**Priority:** High (a genuine lost-update bug reachable today by two admins — or one admin and one automated strike escalation — acting on the same coach within a normal request window; no multi-instance deployment or scale threshold required, unlike the forward-looking hardening in `skillars-deferred-120`)
**Status:** ready-for-dev
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
`\.setStatus(CoachProfileStatus\.` — six sites total), because this bug's fix is "use the lock every
sibling writer already uses," and that claim is only trustworthy if every sibling was actually checked:

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
  Duplicate idempotent work, not corruption — the DB-level `review_flags_unique_flagger` unique index
  already prevents the more serious concern (same user double-flagging). Not worth its own AC.
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

**Verified by:** a new integration test proving the race is closed, mirroring
`ReliabilityStrikeConcurrencyIT`'s two-thread `CountDownLatch`/`ExecutorService` shape (see Dev Notes and
Files to Read) — race `suspendCoach` against `reinstateCoach` for the same coach and assert the coach
ends `SUSPENDED`, not `ACTIVE`, with `suspendCoach`'s booking-cancellation side effects intact regardless
of which thread's HTTP/service call returns first.

---

### AC2 — Ledger hygiene closeout

- [ ] Re-run the grep sweep this story's Provenance section already ran (`AdminCoachEnforcementService`,
      `reinstateCoach`, `deleteStrike`, `CoachProfile.status`, `findByIdForUpdate`) against HEAD
      immediately before marking this story done, and confirm no bullet added by another story in the
      interim now overlaps this one.
- [ ] Confirm `deferred-work.md`'s one historical hit under `## Last audit: 2026-08-05 (skillars-deferred-15
      story creation)` (the `suspendCoach:101` "plain `findById`" narrative) still correctly describes
      already-fixed history, not this story's finding — it should require no edit.

---

## Tasks/Subtasks

- [ ] **Task 1 — AC1: lock `reinstateCoach`**
  - [ ] Replace the plain `findById` at `AdminCoachEnforcementService.java:151` with
        `lockRetryer.withBoundedRetry(() -> coachProfileRepository.findByIdForUpdate(coachId)
        .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile")))`
        — copy `suspendCoach`'s `:107-108` call exactly (same exception, same message)
  - [ ] Confirm no other line in `reinstateCoach` needs to change (the existing status-check and write
        logic already operate on the `coach` variable)

- [ ] **Task 2 — AC1: lock `deleteStrike`**
  - [ ] Replace the plain `findById` at `:224` with the same `lockRetryer.withBoundedRetry(...
        findByIdForUpdate...)` call
  - [ ] Move that locked read to before the `count` computation currently at `:220`, so `count` and the
        `reverted` decision are both computed after the lock is held (mirroring
        `ReliabilityStrikeService.issue`'s ordering) — leave the strike-delete call at `:218` where it is

- [ ] **Task 3 — AC1: regression test proving the fix**
  - [ ] Add a new `AdminCoachEnforcementConcurrencyIT` (package `platform.admin.service`, extending
        `AbstractIntegrationTest` directly — mirror `ReinstateIT`'s raw-SQL coach-profile seeding, not an
        HTTP client call, since the test calls `AdminCoachEnforcementService` directly like
        `ReliabilityStrikeConcurrencyIT` calls `ReliabilityStrikeService`)
  - [ ] Seed one coach at `PENDING_REVIEW`
  - [ ] Race two threads via the `CountDownLatch`/`ExecutorService` pattern from
        `ReliabilityStrikeConcurrencyIT`: one calling `enforcementService.suspendCoach(...)`, the other
        calling `enforcementService.reinstateCoach(...)`, released together
  - [ ] Assert the coach's final status is deterministic and matches whichever transaction's row-lock
        acquisition actually won (not silently `ACTIVE` regardless of ordering) — the concrete, most
        useful assertion is that when `suspendCoach` is the one to actually persist last, the coach ends
        `SUSPENDED` and `reinstateCoach`'s write did not silently clobber it; confirm by asserting the
        final DB row matches one of the two legal terminal states consistently, not a state that
        contradicts the winning transaction's own commit
  - [ ] **Mutation check** (write this into the test's own Javadoc, matching
        `ReliabilityStrikeConcurrencyIT`'s convention): revert the fix (plain `findById` again) and
        confirm the test fails — i.e. prove the test would have caught this bug before the fix, not just
        that it passes after
  - [ ] Add a second, simpler test: a direct call to `deleteStrike` that reverts a coach to `ACTIVE` still
        reads a fresh `count` after acquiring the lock (can be a lighter-weight unit/Mockito-style
        assertion of call order if a second full concurrency IT is disproportionate — use judgement, but
        at minimum confirm `deleteStrike`'s revert path still passes its existing behavior unchanged for
        the non-concurrent case)

- [ ] **Task 4 — AC2: ledger closeout**
  - [ ] Re-run the grep sweep against HEAD; confirm the one historical `deferred-work.md` hit needs no
        edit

- [ ] **Task 5 — Final validation**
  - [ ] Run `AdminCoachEnforcementConcurrencyIT`, `ReinstateIT`, `CoachSuspensionIT`, `ManualStrikeIT`,
        `CoachEnforcementListIT` together — zero regressions
  - [ ] Update Verification Checklist, File List, Change Log, Dev Agent Record
  - [ ] Mark story Status → review

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
  (AC1 — the exact two-thread race shape, `StrikeEventCapture` event-listener pattern, and mutation-check
  Javadoc convention to mirror)
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

- [ ] AC1: `reinstateCoach` and `deleteStrike` both read `CoachProfile` via
      `lockRetryer.withBoundedRetry(() -> coachProfileRepository.findByIdForUpdate(...))`, matching
      `suspendCoach`'s existing pattern; `deleteStrike`'s locked read happens before its `count`
      computation, not after
- [ ] AC1: new `AdminCoachEnforcementConcurrencyIT` proves `suspendCoach` racing `reinstateCoach` no
      longer allows the reinstate to silently overwrite a concurrently-committed suspension; the test's
      Javadoc states what reverting the fix does to the test (mutation check)
- [ ] AC2: ledger grep sweep re-run against HEAD; the one historical hit confirmed to need no edit
- [ ] No regressions in `AdminCoachEnforcementConcurrencyIT`, `ReinstateIT`, `CoachSuspensionIT`,
      `ManualStrikeIT`, `CoachEnforcementListIT`, or any other test touching
      `AdminCoachEnforcementService`

---

## File List

_To be filled in during implementation._

---

## Change Log

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

---

## Dev Agent Record

### Implementation Plan

_To be filled in during implementation._

### Debug Log

_To be filled in during implementation._

### Completion Notes

_To be filled in during implementation._
