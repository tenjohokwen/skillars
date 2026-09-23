# Story: Marketplace/Reviews Correctness Sweep, ReviewFlagService Hygiene & CI Reset-Deadlock Fix

**Story Key:** `skillars-deferred-131-marketplace-reviews-hardening-and-ci-reset-deadlock-fix`
**Epic:** Deferred Work
**Priority:** Medium (two genuine production defects — a subscription-tier read that permanently breaks
after an admin reinstate, and a race that can orphan a live Stripe subscription — plus a
reachable-but-cosmetic double-submit gap, a lock-scope hygiene fix, three low-severity `ReviewFlagService`
gaps, deterministic concurrency-test coverage, and a first-occurrence CI test-infrastructure deadlock).
**Status:** ready-for-dev
**Created:** 2026-09-23

---

## Context

Follows `skillars-deferred-130` (PR #218, merged to master at `e2d0233b`). That story's own
post-implementation `/bmad-code-review` (four layers, 2026-09-23) surfaced several findings it
correctly left out of its own narrow scope — recorded in `deferred-work.md`'s two newest sections,
`## Last audit: 2026-09-23 (skillars-deferred-130 dev-story completion)` and
`## Deferred from: story review of skillars-deferred-130-...`. This story closes the genuine defects
from that list, plus a first-occurrence CI flake found live during this session's post-merge master
verification (run `35891248593`, job `test`, before a rerun passed clean).

All line citations below were re-verified against `HEAD = e2d0233b` (this branch's base) immediately
before this story was drafted — per this project's standing "always re-verify line numbers at story
creation time" convention. Several drifted by 1-3 lines from the ledger's own citations, corrected inline.

**Owner decisions taken live (AskUserQuestion) before drafting**, per the established convention of
resolving scope/design choices at story creation rather than leaving them for dev-story:

1. `reinstateCoach`'s missing-subscription gap — **fix** (find-or-create), not document-only.
2. `syncMarketplaceTier`'s race with `publishProfile` — **fix** (close the race at its source), not
   catch-and-recover or document-only.
3. Scope breadth — **full sweep**: bundle the three marginal low-severity `ReviewFlagService` items
   (lock-scope, DIVE mapping, null-guard) alongside the two clear one-liners, not core-scope-only.
4. The CI reset deadlock (found live this session) — **investigate and fix in this story**, not
   ledger-only.

---

## AC1: Marketplace/Reviews Correctness Fixes

### Fix 1 — `ReviewSubmissionService.submitReview`'s double-submit catch is dead code

**Context:** `ReviewSubmissionService.java:66-73` (ledger cited `:68-74`, drifted 2 lines — unrelated
prior edits shifted the method body). `CoachReview.reviewId` uses `GenerationType.UUID` (not a
DB-generated sequence), so `coachReviewRepository.save(review)` at `:66-67` never flushes inside the
`try` — Hibernate only assigns the UUID client-side and enqueues the INSERT for the next natural flush
point (end of transaction, or an unrelated later query). The real `review_author_coach_unique`-style
constraint violation from a genuine concurrent double-submit therefore surfaces **uncommitted, outside
this method**, at whatever later flush point the surrounding `@Transactional` boundary hits — not inside
the `catch (DataIntegrityViolationException e)` at `:70-73`, which can only ever catch a violation that
never actually happens here. A concurrent double-submit today gets whatever generic error the eventual
uncaught flush failure maps to, not the clean `ALREADY_SUBMITTED` the pre-check at `:47-51` already
throws for the non-concurrent case.

**Fix:** change `review = coachReviewRepository.save(review);` to
`review = coachReviewRepository.saveAndFlush(review);` at `:66-67`, forcing the flush (and therefore the
constraint check) inside the existing `try`, so the existing `catch` block actually does what its comment
already claims. No other change — the catch's error mapping (`ALREADY_SUBMITTED`) is already correct,
just unreachable.

**Test:** new `ReviewSubmissionServiceConcurrencyIT` (or an addition to an existing reviews concurrency
IT if one already exercises `submitReview` — check `ReviewFlagServiceConcurrencyIT` and any
`ReviewSubmissionServiceIT` first) proving two concurrent `submitReview` calls for the same
`(coachId, authorId)` yield exactly one persisted review and the loser gets `ALREADY_SUBMITTED`, not a
generic 500/400.

### Fix 2 — `CoachProfileService.publishProfile` never sets `statusChangedAt`

**Context:** `CoachProfileService.java:329` (`profile.setStatus(CoachProfileStatus.ACTIVE);`) — ledger
cited this correctly, only the surrounding line numbers shifted from story-130's own edits. Every sibling
status writer sets it: `AdminCoachEnforcementService.suspendCoach:143`, `reinstateCoach:231`, and the
two escalation branches at `:450`/`:461` — all via `coach.setStatusChangedAt(Instant.now())`. A freshly
published profile is the one status transition in the whole enforcement/marketplace surface that skips
it. `AdminCoachEnforcementService`'s own admin queue query,
`findByStatusInOrderByStatusChangedAtAsc` (confirm exact method name/call site before implementing),
sorts by this column — a freshly published profile sorts as if it had been in its current status since
whenever the row was first created (or last touched by an unrelated write), not since publish.

**Fix:** add `import java.time.Instant;` to `CoachProfileService.java` (not currently imported — the
file uses `OffsetDateTime`/`ZoneId` for other timestamps) and add
`profile.setStatusChangedAt(Instant.now());` immediately alongside the `setStatus(ACTIVE)` call at
`:329`, matching the `Instant.now()` convention every sibling writer uses (do not use
`OffsetDateTime.now()` here — confirm `CoachProfile.statusChangedAt`'s declared type is `Instant` before
implementing, matching `AdminCoachEnforcementService`'s usage).

**Test:** extend `CoachProfileServiceConcurrencyIT` or `CoachProfileBuilderIT` with a direct assertion
that `publishProfile` sets `statusChangedAt` to a fresh timestamp (not null, not the row's creation time).

### Fix 3 — `AdminCoachEnforcementService.reinstateCoach` can leave a coach with no subscription row

**Context:** `AdminCoachEnforcementService.java:219-221` (the `ACTIVE` write; ledger cited `:217-232`,
which is the right neighborhood but the write itself is at `:219-221`). `reinstateCoach` accepts
`SUSPENDED`, `PENDING_REVIEW`, and `REDUCED` as source statuses and sets `ACTIVE` unconditionally, but
never touches `coach_subscriptions`. If a coach reaches one of those statuses without a subscription row
already present (or if a coach's subscription row was never created because they never actually went
through `publishProfile`'s own find-or-create — confirm at implementation time whether this is reachable
given the accepted source statuses, or is purely defensive), `CoachSubscriptionRepository
.getCoachSubscriptionTier`-style reads (confirm exact method name) throw `ResourceNotFoundException`
permanently — `publishProfile` cannot repair it, since the coach is no longer `DRAFT` after reinstate.

**Fix (scope corrected from the original owner decision — noted for the record):** the owner decision
was "find-or-create the subscription row and run `validateAllStepsComplete`". On inspection,
`validateAllStepsComplete` is `private` to `CoachProfileService` (`:513`) and validates builder-step
data that does not change while a profile is suspended/reduced/pending-review — every reachable source
status here was `ACTIVE` at some point, meaning it already passed that validation once, and nothing in
this module clears builder-step data on suspension. Re-running it here would be scope creep with no
defect behind it. **Implement only the subscription find-or-create**, mirroring `publishProfile`'s own
`skillars-deferred-130` pattern: inject `CoachSubscriptionRepository` into
`AdminCoachEnforcementService` (already `@RequiredArgsConstructor` — a new `private final` field is
sufficient, no constructor edits needed; check for any test that manually constructs this service with
`new AdminCoachEnforcementService(...)` rather than mocking — none found as of this story's creation, but
re-check before implementing) and add, right after the `ACTIVE` write at `:219-221`:

```java
coachSubscriptionRepository.findByCoachId(coach.getId())
    .orElseGet(() -> {
        CoachSubscription created = new CoachSubscription();
        created.setCoachId(coach.getId());
        created.setTier(CoachSubscriptionTier.SCOUT);
        return created;
    });
coachSubscriptionRepository.save(subscription);
```

(pseudocode above needs the local var wired correctly — see `CoachProfileService.java:335-343` for the
exact pattern being mirrored, including its explanatory comment on why find-or-create rather than
unconditional insert). This already runs under `reinstateCoach`'s own `findByIdForUpdate` lock
(`:207-208`), so no additional locking is needed here.

**Test:** new `AdminCoachEnforcementServiceIT` (or extend an existing one) case: reinstate a
`SUSPENDED` coach with no pre-existing subscription row, assert a `SCOUT`-tier row now exists and the
tier-read path no longer throws. Second case: reinstate a coach who already has a subscription row
(e.g. `PRO` from a Stripe purchase made while suspended, if reachable — confirm), assert the existing
tier is preserved, not reset to `SCOUT`.

### Fix 4 — `SubscriptionService.syncMarketplaceTier` can lose a race to `publishProfile`

**Context:** `SubscriptionService.java:682-694` (confirmed exact). This method already reads as
find-or-create at the Java level (`coachSubscriptionRepository.findByCoachId(coachId).ifPresentOrElse(...)`),
but the `else` branch's `save()` on a manually-`@Id`-assigned new `CoachSubscription` still requires a
persistence-context `merge()` under the hood (same underlying mechanism story-130's own Decision 1
diagnosed for `publishProfile`'s old code) — merge reads current state, then inserts at flush if no row
existed at read time. `marketplace.coach_subscriptions.coach_id` has a non-deferrable FK to
`marketplace.coach_profiles.id` (`V138__baseline_schema.sql:4441`), so the INSERT branch's flush takes a
`FOR KEY SHARE` lock on the referenced `coach_profiles` row — which blocks behind `publishProfile`'s own
`FOR UPDATE` lock (from its `skillars-deferred-130` fix) on that same row if the two run concurrently for
the same coach. Once `publishProfile` commits first (having inserted its own row via its own
find-or-create), `syncMarketplaceTier`'s still-pending INSERT resumes and violates the
`coach_subscriptions` primary key — uncaught here, rolling back `persistCoachSubscription`'s whole
transaction while any Stripe subscription created earlier in the same call chain (outside this DB
transaction) survives, orphaned with no local record.

**Fix (mechanism corrected from the literal "add find-or-create" framing — the find-or-create shape
already exists; the actual gap is that this check-then-act runs with no lock at all):** close the race
by making `syncMarketplaceTier` take the same `coach_profiles` row lock `publishProfile` already takes,
so the two writers serialize on the shared resource instead of racing on it. Inject
`CoachProfileRepository` and `PessimisticLockRetryer` into `SubscriptionService` (both already used
elsewhere in the `marketplace`/`infrastructure.persistence` packages this file already imports from —
confirm `SubscriptionService` doesn't already have an equivalent lock helper before adding a second one),
and wrap the existing body of `syncMarketplaceTier` in a
`lockRetryer.withBoundedRetry(() -> coachProfileRepository.findByIdForUpdate(coachId)...)` call before
the `ifPresentOrElse`, mirroring `CoachProfileService.publishProfile`'s own Fix 3 shape. If
`findByIdForUpdate` finds no profile for `coachId` (confirm this is actually unreachable given
`syncMarketplaceTier`'s five call sites — `:163`, `:216`, `:467`, `:534`, `:606` — before deciding how to
handle it defensively), that is itself worth a decision at implementation time: throw, or proceed
unlocked as today.

**Test:** new `SubscriptionServiceConcurrencyIT` (real Testcontainers Postgres, `TransactionTemplate` +
`CountDownLatch`, mirroring `CoachProfileServiceConcurrencyIT`'s own shape): race `publishProfile`
against `syncMarketplaceTier` for the same coach, assert exactly one `coach_subscriptions` row survives
and neither side throws an uncaught `DataIntegrityViolationException`.

---

## AC2: `ReviewFlagService` Hygiene Sweep

### Fix 5 — `flag()` holds its exclusive lock across five no-op exit paths

**Context:** `ReviewFlagService.java:47-49` (the lock), `:51-54` (self-flag guard), `:56-63` (missing
coach profile / coach-flags-own-profile guards), `:65-68` (`ALREADY_FLAGGED` existence guard), `:75-81`
(the insert + its own DIVE-catch exit). None of these five exits write anything, yet all run under the
row's exclusive `FOR UPDATE` lock acquired at `:47` — a user repeatedly flagging a review they already
flagged serializes admin moderation (`AdminReviewService.approveReview`/`blockReview`) behind a lock held
for a call that was always going to no-op.

**Fix — the two-read restructure (owner decision, since this is the only shape that doesn't reintroduce
the TOCTOU `skillars-deferred-130` AC1 Fix 1 closed):** split `flag()` into an unlocked pre-read for the
four write-independent guards, then re-fetch with the lock only for the moderation-status-dependent write
path:

1. Unlocked `reviewRepository.findById(reviewId)` (inherited from `JpaRepository`, not the `@Lock`-annotated
   `findByIdForUpdate`) for the self-flag check (`:51-54`) and to obtain `coachId` for the coach-profile
   guards (`:56-63`).
2. Run `reviewFlagRepository.existsByReviewIdAndFlaggedBy` (`:65-68`)'s check against this same unlocked
   read — it is a separate table/query already, not dependent on the locked row's freshness.
3. Only after all four guards pass, acquire the lock via the existing `reviewRepository.findByIdForUpdate`
   call and proceed exactly as today from the insert (`:70-81`) through the auto-hold check (`:92-104`).

This adds one extra unlocked read on the common (non-error) path and on the four guard-rejection paths —
acceptable, since the previous behavior on those same rejection paths was to hold the exclusive lock for
the same round trip anyway. Preserve the existing code comment's reasoning about why the *locked* read
must be the first read of the row **for the write-path decision** — that guarantee is unchanged, it now
just starts one guard-check later.

**Test:** extend `ReviewFlagServiceConcurrencyIT` with a case proving a repeat-flag no-op (`ALREADY_FLAGGED`)
does NOT block a concurrent `approveReview`/`blockReview` on the same review — assert the admin call
completes without waiting on the flagger's rejected call.

### Fix 6 — `flag()`'s blanket `DataIntegrityViolationException → ALREADY_FLAGGED` mapping

**Context:** `ReviewFlagService.java:75-81` (post-restructure, line numbers will shift after Fix 5 —
re-verify at implementation time). The catch maps every constraint violation on the insert to
`ALREADY_FLAGGED`, but `ReviewFlag` has other constraints that can fail concurrently (a null `reason`,
`details` exceeding its length bound, a `flagged_by` FK failure) and none of those mean "already
flagged." Low severity — unreachable from REST today given upstream validation, per the ledger's own
framing — but worth precision while this method is already being touched twice in this story.

**Fix:** check the violated constraint name before mapping to `ALREADY_FLAGGED` (mirroring
`CoachProfileService.publishProfile`'s own `skillars-deferred-130` pattern of checking
`coach_subscriptions_pkey` by name before mapping to `marketplace.alreadyPublished` — though note that
pattern was itself withdrawn there in favor of find-or-create; here there genuinely is a real unique
constraint (`review_flags_unique_flagger`) worth checking by name, since this file's insert really can
violate it concurrently). Something in the shape of:

```java
} catch (DataIntegrityViolationException e) {
    if (isUniqueFlaggerViolation(e)) {
        throw new OperationNotAllowedException(
            "You have already flagged this review", ReviewErrorCode.ALREADY_FLAGGED);
    }
    throw e;
}
```

with `isUniqueFlaggerViolation` checking for `review_flags_unique_flagger` in the exception's root cause
message/constraint name (confirm the exact constraint name from the migration that created it before
implementing).

**Test:** unit test on `ReviewFlagService` (Mockito, not a full IT) asserting a DIVE with a different
constraint name propagates uncaught, while one naming `review_flags_unique_flagger` still maps to
`ALREADY_FLAGGED`.

### Fix 7 — `flag()` NPEs on a null `flaggedBy`

**Context:** `ReviewFlagService.java:59` (`flaggedBy.equals(coachProfile.getUserId())` — ledger cited
`:61`, drifted after story-130's own comment additions). Unreachable from REST today, since
`resolveUserId()` (confirm call site) guards against a null caller before this method is invoked — this
is defensive hardening, not a live bug.

**Fix:** add an explicit null check for `flaggedBy` at the top of `flag()`, before the lock acquisition at
`:47`, throwing the same `OperationNotAllowedException`/`ReviewErrorCode` shape as the other guards
(confirm the right error code — likely a new one, since none of the existing `ReviewErrorCode` values fit
"missing flagger identity" — or reuse `REVIEW_NOT_FOUND` if the project convention is to not leak
"invalid caller identity" as a distinct error to the API surface; decide at implementation time).

**Test:** unit test calling `flag(reviewId, null, reason, details)` directly, asserting a clean exception
instead of an NPE.

### Fix 8 — Silent flag-wipe on approve gets a log line

**Context:** `AdminReviewService.java:94` (`reviewFlagRepository.resolveAllOpenFlags(reviewId,
Instant.now());` inside `approveReview`). Flags cast while a review is `PENDING` accumulate past the
auto-hold threshold, are event-published as real via `ReviewFlaggedEvent`, then silently wiped by this
call with no log or alert when the review is later approved — the review goes live with
`openFlagCount == 0` and no record anything was ever flagged. Pre-existing, not introduced by
story-130 or this story.

**Fix:** read the open-flag count immediately before the resolve call (reusing
`reviewFlagRepository.countByReviewIdAndResolvedAtIsNull(reviewId)`, the same query `ReviewFlagService.flag`
already uses) and log a `WARN` when it is non-zero, naming the reviewId and count, before calling
`resolveAllOpenFlags`. No behavior change — `AdminReviewService` already has a `log` field (used at
`:104`).

**Test:** unit or IT assertion that approving a review with open flags logs the expected WARN message
(use a log-capture test utility if this codebase has an established one — check for precedent before
adding a new dependency).

---

## AC3: Concurrency IT Determinism

**Context:** Both `ReviewFlagServiceConcurrencyIT` and `CoachProfileServiceConcurrencyIT` (added by
`skillars-deferred-130`) use a fixed `Thread.sleep(300)` as their sole "contention established" signal,
and take wall-clock `Instant.now()` readings after `transactionTemplate.execute()` returns for
cross-thread ordering assertions. Nothing verifies the contending thread actually blocked on the lock —
on a cold JVM or a constrained CI runner the test can pass without ever exercising the lock it claims to
test, and the ordering assertion can fail on a correct system under load. Recorded as a Deferred item in
`skillars-deferred-130`'s own code review; this story closes it since AC1/AC2 above already touch both
files' subject services and this story is adding `SubscriptionServiceConcurrencyIT` as a third instance
of the same pattern (Fix 4's test).

**Fix:** replace the `Thread.sleep(300)` contention signal with a deterministic poll against
`pg_locks`/`pg_stat_activity`, confirming the contending backend is actually waiting on the target row's
lock before the main thread proceeds. This is a new pattern for this codebase (no existing precedent
found — `Awaitility` itself is already used elsewhere, e.g. `BookingServiceConcurrencyIT`, but not
against `pg_locks`), so keep the polling helper narrow and well-commented; a sketch:

```sql
SELECT count(*) FROM pg_locks l
JOIN pg_stat_activity a ON l.pid = a.pid
WHERE NOT l.granted AND a.query LIKE '%<table>%'
```

polled via `Awaitility.await().atMost(...).until(...)` rather than a fixed sleep. Apply to all three
concurrency ITs (`ReviewFlagServiceConcurrencyIT`, `CoachProfileServiceConcurrencyIT`, the new
`SubscriptionServiceConcurrencyIT` from Fix 4) for consistency — implement the helper once, share it.

**Test:** this AC *is* the test change — no additional test needed beyond confirming the three ITs still
pass with the new polling mechanism, and ideally one deliberate run with the fix reverted (in the lock
under test, not the poll) to confirm the poll still catches the regression it's meant to catch.

---

## AC4: CI Database-Reset Deadlock — Investigate, Then Fix

**Context:** Master CI run `35891248593` (post-`skillars-deferred-130`-merge, `test` job) failed with a
genuine PostgreSQL `deadlock detected` (not a test assertion failure) inside
`DatabaseResetTestExecutionListener.truncateApplicationTables` (`DatabaseResetTestExecutionListener.java:356`,
invoked from `beforeTestMethod` via `:118-124`), while resetting the database ahead of
`RadarAssessmentResourceIT.getMyEntries_returnsOwnEntriesOnly`. Two Postgres backends
(`pg_locks`/deadlock-detector-reported "Process 145"/"Process 146") deadlocked on a `ShareLock` while
deleting from `main.player_profiles`. A rerun of the same commit passed clean, and all ~14 prior master
CI runs on this branch were green — this is a first occurrence, not a known recurring flake.

**Correction to this session's earlier working theory:** initial diagnosis (recorded in this session's
own chat, not yet in any file) guessed "two parallel Maven Failsafe test forks." That is **wrong** —
`pom.xml`'s `maven-failsafe-plugin` configuration (`pom.xml:660-661`) explicitly documents "All ~135 IT
classes share ONE forked JVM (no `forkCount`/`reuseForks` here)". With a single fork and no JUnit 5
parallel-execution configuration found (`src/test/resources/junit-platform.properties` does not exist),
the second concurrent Postgres backend must be a **second connection from the same JVM** — most likely an
async component (this codebase has several `@Async` + `@TransactionalEventListener(phase = AFTER_COMMIT)`
writers touching player-profile-adjacent tables, e.g. `RadarCompositeCalculationService` — confirmed via
`grep` to be both `@Async` and an `AFTER_COMMIT` listener touching `PlayerProfile`-related state) still
running on its own executor thread from a **preceding** test class's committed transaction, still
in-flight when the next test class's `beforeTestMethod` reset fires. This is a hypothesis, not a
confirmed root cause — do not implement a fix that assumes it without verifying first.

**Task 1 (investigate, empirically — this project's own established convention: verify, don't guess):**
reproduce or otherwise confirm which concurrent actor collided with the reset. Options: add temporary
diagnostic logging around `RadarCompositeCalculationService`'s async entry point and any other
`AFTER_COMMIT` listener touching `player_profiles`-adjacent tables to see if one is still active when a
`RadarAssessmentResourceIT` test starts; or capture `pg_stat_activity` at the moment of a reproduced
deadlock (may require several reruns — this is a race, not deterministic). Document the actual finding
in this story's Dev Agent Record and in `deferred-work.md`, whether or not it matches the hypothesis
above (mirrors `skillars-deferred-123`'s own AC5 precedent, where the empirical investigation found a
mechanism matching none of that story's candidate guesses).

**Task 2 (fix — structural, independent of the exact root cause):** regardless of which concurrent actor
is confirmed, wrap `DatabaseResetTestExecutionListener.beforeTestMethod`'s transactional block
(`:118-124`, everything from `snapshotReferenceDataOnce` through `backdateShedLock`) in a Postgres
advisory lock (`pg_advisory_xact_lock` with a fixed, arbitrary key, released automatically at transaction
end) so the reset can never deadlock against any other concurrent transaction touching the same tables —
it will simply wait its turn instead. This is deliberately not a retry-on-deadlock wrapper: an advisory
lock closes the contention at its source (matching this codebase's own established "prefer explicit
locking over retry-and-hope" convention, e.g. every `findByIdForUpdate` call site in the marketplace/
reviews modules), where a retry only reduces the odds of the CI failure recurring without addressing why
two transactions are touching the same rows in the first place. The reset itself measures ~100ms mean on
CI per the file's own `AC5.6` documentation (`:311-314`), so serializing it against a rare concurrent
actor is not a meaningful throughput concern for a ~135-class suite sharing one JVM.

**Test:** this is test-infrastructure-only — there is no unit-testable assertion for "does not deadlock."
Validate by re-running the full `test` job (or at minimum the `platform.development.**` package plus
several classes known to trigger the same async paths) enough times to build confidence, and note in the
Dev Agent Record how many runs were attempted. Do not claim this "fixes" the flake if it was never
reproduced locally — say plainly that it closes the identified mechanism and reduces risk, consistent
with the empirical honesty this project's other stories model (e.g. `skillars-deferred-123`'s AC5 comment
about only being able to verify a mechanism, not exhaustively rule out others).

---

## AC5: Ledger Closeout

Update `deferred-work.md`:

- Mark every item this story resolves (Fixes 1-8, the concurrency-IT determinism item, the CI deadlock)
  as closed, citing this story's key and PR number (once known) — following the exact `[CLOSED by
  skillars-deferred-131-...]` convention used throughout the file's prior sections.
- Add a new `## Last audit: <date> (skillars-deferred-131 dev-story completion)` section, following the
  established format from `skillars-deferred-130`'s own such section, summarizing what was found true,
  what was corrected during implementation (if anything — several items above already flag likely
  implementation-time corrections, e.g. Fix 3's scope narrowing and Fix 4's mechanism correction), and
  what — if anything — remains open.
- If the AC4 investigation confirms a different root cause than the hypothesis above, record the
  confirmed mechanism precisely (not the guess) — mirroring `skillars-deferred-123`'s AC5 precedent.
- Re-verify the current "remaining open items" count/summary elsewhere in the ledger is not stale after
  this story's closures (the file has a running narrative of "N items remain" in places — check for one
  and correct it, per this project's own recurring self-correction pattern in prior stories' AC3/AC5-style
  closeout tasks).

---

## Tasks

- [ ] **Task 1 (AC1):** Diff-check Fixes 1-4's cited lines against current `HEAD` immediately before
      touching each file (per this project's "diff cited lines before implementation" convention — this
      story's own citations may drift further if any other work lands on `master` between creation and
      implementation).
- [ ] **Task 2 (AC1):** Implement Fix 1 (`submitReview` `saveAndFlush`) + its concurrency test.
- [ ] **Task 3 (AC1):** Implement Fix 2 (`publishProfile` `statusChangedAt`) + its test.
- [ ] **Task 4 (AC1):** Implement Fix 3 (`reinstateCoach` subscription find-or-create, scope-corrected —
      no `validateAllStepsComplete` call) + its tests.
- [ ] **Task 5 (AC1):** Implement Fix 4 (`syncMarketplaceTier` lock, mechanism-corrected — lock via
      `coachProfileRepository.findByIdForUpdate`, not a redundant find-or-create) + new
      `SubscriptionServiceConcurrencyIT`.
- [ ] **Task 6 (AC2):** Implement Fix 5 (two-read restructure) — re-verify all line numbers in Fixes
      6/7/8 after this restructures the file.
- [ ] **Task 7 (AC2):** Implement Fixes 6, 7, 8 + their tests.
- [ ] **Task 8 (AC3):** Build the shared `pg_locks`-based deterministic-wait helper; apply to all three
      concurrency ITs (existing two + Fix 4's new one).
- [ ] **Task 9 (AC4):** Investigate the CI deadlock's actual concurrent actor empirically; document the
      finding regardless of whether it matches this story's hypothesis.
- [ ] **Task 10 (AC4):** Implement the `pg_advisory_xact_lock` fix in
      `DatabaseResetTestExecutionListener`; validate with repeated CI/local runs.
- [ ] **Task 11 (AC5):** Ledger closeout in `deferred-work.md`.
- [ ] **Task 12:** Full targeted regression sweep (at minimum: `platform.reviews.**`,
      `platform.marketplace.**`, `platform.payment.**` [for `SubscriptionService`],
      `platform.admin.**` [for `AdminCoachEnforcementService`/`AdminReviewService`],
      `platform.development.**` [for the CI deadlock's suspected actor], plus
      `PessimisticLockRetryerCallSiteAuditTest` if any new `findByIdForUpdate`/`withBoundedRetry` call
      sites are added by Fixes 3/4 — count bump required if so). No `mvn verify` run locally per this
      project's standing convention (`docs/validation-strategy.md`) — GitHub CI is the sole
      full-verification gate.

## Dev Notes

- This story was **not** run through a pre-implementation `story-review.md` senior-dev audit before
  being handed to `dev-story`, unlike `skillars-deferred-123`/`-130`. Given the number of "confirm at
  implementation time" and "re-verify before implementing" caveats already threaded through the Context
  sections above (Fixes 3 and 4 in particular had their originally-proposed mechanisms corrected during
  this story's own creation, before any code was written), consider running `/bmad-code-review` in
  "no-spec" mode against this story file itself, or simply treating every "confirm at implementation
  time" note above as a mandatory pre-implementation check, before writing code.
- Fixes 3 and 4 both add new constructor dependencies to existing `@RequiredArgsConstructor` Spring
  beans (`AdminCoachEnforcementService` gains `CoachSubscriptionRepository`; `SubscriptionService` gains
  `CoachProfileRepository` and `PessimisticLockRetryer`). No manual-constructor test instantiations of
  either class were found as of this story's creation (`grep -rn "new AdminCoachEnforcementService(\|new
  SubscriptionService("` under `src/test`) — re-check before implementing, since a manual instantiation
  would need updating and would not fail loudly (a missing constructor arg is a compile error, so this
  is actually safe by construction — noted for completeness, not as a real risk).
- AC4's fix touches `DatabaseResetTestExecutionListener`, which runs before **every** test method across
  the entire suite (~135 IT classes, ~905 test methods per the file's own Javadoc). Treat this as the
  highest-blast-radius change in this story and validate accordingly — a full CI run (not just a targeted
  package sweep) is required before this AC can be considered done, since its whole point is suite-wide
  reset behavior.
- No frontend changes anticipated — confirm via `git status --short` before opening the PR, per this
  project's established `frontend-tests` label convention.

## Dev Agent Record

### Completion Notes

_To be filled by `/bmad-dev-story`._

### File List

_To be filled by `/bmad-dev-story`._

---

## Change Log

- 2026-09-23: Story created via `/bmad-create-story`, branched off `master` post-`skillars-deferred-130`-merge
  (PR #218, squash-merged as `e2d0233b`). Sourced from `deferred-work.md`'s two newest sections (added by
  `skillars-deferred-130`'s own post-implementation code review, same-day) per this project's "read
  same-day code-review deferrals before drawing scope" convention — no items were mined from older ledger
  sections, since a fresh audit (background research pass) confirmed every older section is already
  `[CLOSED by ...]` or `[DECIDED: accepted risk ...]`. All cited line numbers re-verified against
  `HEAD = e2d0233b` at creation time; several had drifted 1-3 lines from the ledger's own citations,
  corrected throughout. Additionally scoped in a first-occurrence CI test-infrastructure deadlock found
  live during this session's post-merge master verification (run `35891248593`), not yet in the ledger
  before this story.

  Four owner decisions taken live (AskUserQuestion): (1) fix `reinstateCoach`'s missing-subscription gap
  rather than document it — implementation scope corrected during drafting from "find-or-create +
  validateAllStepsComplete" to "find-or-create only," since `validateAllStepsComplete` validates
  builder-step data that does not degrade while suspended, making the second half scope creep with no
  defect behind it; (2) fix `syncMarketplaceTier`'s race with `publishProfile` rather than catch-and-recover
  or document it — mechanism corrected during drafting from "add find-or-create" (the method already has
  that shape) to "add the missing lock," since the actual gap is an unlocked check-then-act, not a missing
  find-or-create; (3) full sweep of `ReviewFlagService`'s three marginal low-severity items (lock-scope
  restructure, DIVE-mapping precision, null-`flaggedBy` guard) plus a fourth item surfaced during
  discussion (a WARN log for `approveReview`'s silent flag-wipe), rather than core-scope-only; (4)
  investigate and fix the CI reset deadlock in this story rather than ledger it as a first-occurrence
  watch item — during investigation for this story (not yet implementation), the initial live-session
  working theory ("two parallel Maven Failsafe forks") was found to be incorrect (`pom.xml` confirms a
  single shared fork) and corrected to a single-JVM-concurrent-connection hypothesis before drafting
  AC4's Context section, per this project's own "verify, don't guess" convention.

  5 ACs bundled per this project's "do not create small stories" convention: AC1 (4 marketplace/reviews
  production-code fixes), AC2 (4 `ReviewFlagService` hygiene items), AC3 (deterministic concurrency-IT
  lock-wait detection, replacing `Thread.sleep`, applied to 2 existing + 1 new IT), AC4 (CI reset-deadlock
  investigation + structural fix via advisory lock), AC5 (standard ledger closeout). Story file:
  `skillars-deferred-131-marketplace-reviews-hardening-and-ci-reset-deadlock-fix.md`. Branch:
  `story/deferred-131-marketplace-hardening` (to be created).
