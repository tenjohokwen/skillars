# Story: Marketplace/Reviews Concurrency Audit (Fresh Sweep) & ConfigBounds Drift-Test Fix

**Story Key:** `skillars-deferred-130-marketplace-reviews-concurrency-audit-and-config-bounds-fix`
**Epic:** Deferred Work
**Priority:** Medium (one genuine High-severity TOCTOU/lost-update hazard on a production write path,
one genuine Medium lock-omission gap found during story review, one Low double-submit error-code
hardening gap, and one small test-coverage fix; plus closing out the last two modules in this
codebase's ~30-story concurrency-hardening series that had never been swept).
**Status:** ready-for-dev
**Created:** 2026-09-23
**Reviewed:** 2026-09-23 (`_bmad-output/implementation-artifacts/story-review.md`, against
`HEAD = 7ed6d30b`) — see Change Log for what the review changed and why.

---

## Provenance & Scoping (read before starting)

`_bmad-output/implementation-artifacts/deferred-work.md` was swept end-to-end (3173 lines) at story
creation time. Unlike the last several stories in this series (125–129), **the ledger is now
unusually thin** — stories 125–129 each closed their own same-day deferrals, and everything left is
either `[CLOSED by ...]`, `[DECIDED: accepted risk ...]`, or explicitly re-confirmed out of scope
across 2–4 consecutive prior stories. There is no cluster of 3–4 substantial still-open ledger bugs
left to mine the way 125–129 did.

Per an owner decision taken live at story-creation time (`AskUserQuestion`), this story instead
anchors on the **fresh concurrency/TOCTOU audit of `platform.marketplace` and `platform.reviews`**
that stories 126, 127, 128, and 129 each explicitly flagged as the only two modules never swept
under this series' `@Scheduled`/pessimistic-lock/TOCTOU lens (every other module — admin, booking,
config, development, filestorage, messaging, monitoring, notification, outbox, payment, security,
session, video — has at least one closed finding from this series already).

The audit (`/txn-and-concurrency-audit`, this story's own creation session) found **zero `@Scheduled`
methods in either module** (confirmed: `grep -rn "@Scheduled" ... platform/marketplace
platform/reviews` returns no hits), so checks #3/#4 (sibling `@SchedulerLock` consistency, lock
duration derivation) do not apply. Both modules already use the codebase's `findByIdForUpdate` +
`entityManager.refresh(..., PESSIMISTIC_WRITE)` pessimistic-lock convention extensively and
correctly in their hottest write paths — `ReviewSubmissionService.updateReview`,
`ReviewModerationService.handleReviewSubmitted`, `AdminReviewService.approveReview`/`blockReview`,
`CoachProfileService.saveStep4` all lock, refresh, and re-check status before mutating.

**Checked 0 schedulers / 26 `@Transactional` annotations across 10 service classes / 11 entities**
across both modules (`CoachAgeGroup`, `CoachAvailabilityWindow`, `CoachMediaItem`, `CoachPricing`,
`CoachProfile`, `CoachReliabilityStrike`, `CoachSpecialty`, `CoachSubscription`, `SessionPack`,
`CoachReview`, `ReviewFlag` — recounted during story review, corrected from an initial estimate of
8 entities; see Change Log). No N+1, no `MultipleBagFetchException` risk. Findings, corrected during
story review (see Change Log for what changed from the original draft):

- **AC1 Fix 1 (High):** `ReviewFlagService.flag`'s auto-hold write has no lock, refresh, or re-check —
  real, but the reachable trigger and correct fix shape both changed during review.
- **AC1 Fix 2 (Low, downgraded from Medium during review):** `CoachProfileService.publishProfile`
  double-submit already surfaces as a handled 400, not the unhandled 500 originally claimed — the
  actual gap is a generic vs. specific error code.
- **AC1 Fix 3 (Medium, new — found during story review, not the original creation-session audit):**
  `publishProfile` takes no lock at all, and a concurrent admin suspension of the same profile can be
  silently reverted.

Two smaller, genuinely-open residuals from the immediately-preceding same-day
`deferred-work.md:3108-3172` section (`## Deferred from: code review of
skillars-deferred-129-...`, 2026-09-23, this story's own creation-session ledger sweep) were also
considered:

- **D1** (worst-case `N × seconds` erasure bound) — NOT reopened. This is the same-day recap of
  deferred-129's own owner-decided, honestly-documented non-ceiling; re-litigating it would
  contradict that decision.
- **D2** (`ConfigBounds` min/max re-declared as literals at `getBoundedLong` call sites) —
  **re-scoped during story review** (see AC2 below and its Change Log entry). The literal re-typing
  is not a bug — it is this codebase's own documented drift-detection convention
  (`ConfigBounds.java:33-35`), confirmed by a dated `/bmad-code-review` reversal of the exact opposite
  change at the sibling `RadarCompositeCalculationService` call site (2026-09-21). The genuine gap D2
  names is that `GdprErasureService`'s own new call site has no unit test pinning those literals the
  way the Radar call site does — AC2 now closes that.
- **D3** (only 1 of 4 branch × reason catch combinations tested), **D4** (`performance_reports`
  hydrated to read one column, left managed post-delete), **D5** (`lock_timeout` bound re-read once
  per child inside the outer transaction) — NOT reopened. All three are explicitly low-severity,
  pre-existing, test-debt with no production defect claimed; none is individually story-worthy and
  none was selected by the owner for this bundle.

A third candidate — `deferred-work.md:3101-3106`, the GDPR erasure inner transaction's pooled
**connection-acquisition** wait (up to `connection-timeout: 30000`, 3× AC2's own `~10s`
`gdprEraseLockBudget`; `lock_timeout` has no effect on it) — was investigated as a possible fourth AC
and **explicitly declined as a code-change item** (owner decision, `AskUserQuestion`, this story's
own creation session), after discovering the "scoped connection-timeout override" originally proposed
is not the small fix it first appeared to be. See AC3 (ledger closeout) for the full technical
finding and why it remains `[DECIDED: accepted risk]` for a fourth consecutive story.

### Out of scope (explicitly, not re-decided here)

- The full `getBoundedLong` signature refactor (reading `min`/`max` off `BoundedKey` for every
  existing call site) — this would work *against* this codebase's documented literals-as-drift-guard
  convention (`ConfigBounds.java:33-35`), not toward it. Not pursued at all, in either direction; see
  AC2's Change Log entry for the full reasoning.
- A second dedicated HikariCP connection pool to bound `deletePlayerDevelopmentData`'s
  connection-acquisition wait — considered and declined (see AC3's Context for the full reasoning:
  no clean per-call-site override exists through the standard `DataSource.getConnection()` path
  Spring's transaction manager uses, and a dedicated pool would need separate wiring for the
  Testcontainers `@ServiceConnection` test path to even be exercisable by `GdprErasureIT`, per this
  project's own IT-only validation convention).
- `deferred-work.md:3146-3172` (D3, D4, D5) — see above, not reopened.
- `main."user"` cleanup-sweep missing index — still blocked on production `EXPLAIN`/row-count
  evidence this project has no deploy history to generate; re-confirmed correctly out of scope for
  a fourth time by this story's own ledger sweep, not reopened.
- `radar_composite_dlq` post-erasure residual, `markFailed`'s generic no-`AdminAlert` gap — both
  already `[DECIDED: accepted risk — skillars-deferred-127]`, not reopened.
- `EmailRetryScheduler.retryFailedEmails()` whole-table-poll headline claim
  (`deferred-work.md:2214-2217`) — genuinely open but is the deliberately-left-open half of a bullet
  deferred-129 AC3 already partially closed; re-touching it now would reopen what that story
  deliberately left as a documented residual, not a fresh find from this story's own audit.
- `ReviewFlagService.java:76` / `ReviewSubmissionService.java:165` re-typing both bounds *and* the
  key string for `getBoundedInt` calls (found during story review, D1 of `story-review.md`) — noted
  here because it sits inside a file this story already edits, but not fixed: AC2's owner-decided
  scope is `GdprErasureService`'s own call site only, and (per the corrected AC2 direction) the right
  fix for a literal-typo risk is a pinning unit test, not a code change — out of scope for this story
  to add per-call-site.
- `ReviewSubmissionService.submitReview`'s `:68-74` catch of `DataIntegrityViolationException` being
  very likely dead code (`CoachReview` uses `GenerationType.UUID`, so `save()` doesn't flush inside
  the `try` — the `uq_coach_reviews_author_coach` violation actually surfaces at commit-time flush,
  outside it) — found during story review (`story-review.md` "Notes for the implementer"), genuinely
  pre-existing and out of this story's scope; worth its own ledger bullet, not fixed here.
- `publishProfile` never setting `statusChangedAt` on `DRAFT → ACTIVE` — found during story review,
  pre-existing, out of scope; worth a ledger line if `deferred-work.md` is being edited anyway for
  AC3, but not a fix task in this story.

All citations below were independently re-verified against `master@1746965d` (this story's own
creation-time `HEAD`) at creation time, and a second time during story review against the same
`HEAD`. **Re-verify again at actual implementation time** if this worktree's `HEAD` has moved.

---

## AC1 — Fix the concurrency/data-integrity gaps found by the `platform.marketplace`/`platform.reviews` audit

### Context

#### Fix 1 (High) — `ReviewFlagService.flag`'s auto-hold write has no lock, refresh, or re-check

`src/main/java/com/softropic/skillars/platform/reviews/service/ReviewFlagService.java:37-93`. The
method reads the target review via a plain, unlocked `reviewRepository.findById(reviewId)` (`:38`),
does its authorization/dedup checks, persists the new `ReviewFlag` row (`:61-72`, correctly guarded
by a caught `DataIntegrityViolationException` against the `review_flags_unique_flagger` unique
index — that part is fine), and then, if the open-flag count has just crossed the configured
threshold, **conditionally mutates the SAME unlocked, possibly-stale `review` instance's
`moderationStatus`** and saves it (`:78-86`):

```java
boolean autoHeld = false;
if (openFlagCount >= threshold && review.getModerationStatus() == ReviewModerationStatus.APPROVED) {
    review.setModerationStatus(ReviewModerationStatus.UNDER_REVIEW);
    review.setHeldReason(HeldReason.FLAG_THRESHOLD);
    review.setLastModifiedAt(Instant.now());
    reviewRepository.save(review);
    coachRatingService.recompute(review.getCoachId());
    autoHeld = true;
}
```

**Every other writer of `CoachReview.moderationStatus` in this codebase goes to real, deliberately-
commented lengths to avoid exactly this class of bug:**

- `ReviewSubmissionService.updateReview` (`:106-126`) — takes `findByIdForUpdate`, then
  `entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE)`, then **re-runs the moderation-
  status guard on the fresh locked instance**.
- `ReviewModerationService.handleReviewSubmitted` (`:93-140`, an `AFTER_COMMIT` listener in its own
  `REQUIRES_NEW` transaction) — takes `findByIdForUpdate` as the transaction's first read (so no
  `refresh` is even needed).
- `AdminReviewService.approveReview`/`blockReview` (`:73-143`) — both take `findByIdForUpdate` as
  their first read.

**Why the originally-drafted "admin-block race" scenario does not actually reach the write (story
review correction):** `reviews.review_flags` carries a non-`DEFERRABLE` foreign key to
`reviews.coach_reviews` (`V138__baseline_schema.sql:4531-4532`; confirmed zero `DEFERRABLE`
constraints anywhere in the schema). Postgres's FK referential-integrity check on the flag INSERT at
`:67` takes `FOR KEY SHARE` on the parent `coach_reviews` row and holds it to commit — which conflicts
with the `FOR UPDATE` that `CoachReviewRepository.findByIdForUpdate` issues (`:23-25`, a plain
blocking lock, unlike `CoachProfileRepository`'s `NO_WAIT` variant). At the *default* threshold (3),
this serialises `AdminReviewService.blockReview`'s locked write against this method's flag INSERT: an
admin commit before `:67` is seen by the fresh flag-count re-read at `:74` (count becomes 1, below
threshold, no write); an admin lock attempt after `:67` blocks until this transaction commits or the
flag INSERT itself blocks first. There is no interleaving that reaches the described stale write —
**verify this reasoning empirically with a two-session `psql` test before relying on it** (it rests on
two well-documented but unverified-in-this-codebase Postgres behaviours: RI checks taking `FOR KEY
SHARE`, and `FOR KEY SHARE` conflicting with `FOR UPDATE` — PG *Explicit Locking* §13.3.2).

**The scenario that actually is reachable:** `ReviewSubmissionService.updateReview` is the **only**
status-writer of `CoachReview` that does not call `ReviewFlagRepository.resolveAllOpenFlags` (confirmed:
only `AdminReviewService.approveReview`/`.blockReview` call it, `grep -rn resolveAllOpenFlags` returns
exactly those two call sites). `CoachReview` (`repo/CoachReview.java`) has no `@Version` and no
`@DynamicUpdate`, so the `save(review)` at `:83` is Hibernate's dirty-check UPDATE of every mapped
column from the in-memory instance, not a narrow one. So: a review is `APPROVED` with 2 open flags
(default threshold 3). `flag()` reads the row at `:38`. Before `flag()` reaches `:67`, the author's own
`updateReview` commits — setting `PENDING`, bumping `moderationEpoch`, rewriting `rating`/`body`,
clearing `coachResponse*` — and leaves the 2 flags open (it never resolves them). `flag()`'s own flag
INSERT is unrelated to that commit and proceeds normally; the count re-read at `:74` sees `3`; the
in-memory `review` still shows the stale `APPROVED`; `:83` flushes a **full-row UPDATE** that both sets
`UNDER_REVIEW` and **reverts the author's edit** — rating, body, coach response, epoch, all of it — with
no error. `ReviewModerationService`'s `AFTER_COMMIT` listener for that `updateReview` then finds
`UNDER_REVIEW != PENDING` (its own guard) and discards its verdict, so the reverted content is never
re-moderated. `coachRatingService.recompute` then runs on top of the reverted `rating`.

**Also live at `threshold = 1`:** `ConfigBounds.REVIEWS_AUTO_HOLD_FLAG_THRESHOLD` allows `min = 1`
(`ReviewFlagService.java:75`'s own comment: "0 → the first flag on any review auto-holds it"). At
`threshold = 1`, the FK-serialised count read at `:74` already satisfies `openFlagCount >= threshold`
on the very first flag — so under this non-default configuration, the originally-drafted admin-block
scenario *is* live (the admin's `BLOCKED` decision, if committed in the right window, can be reverted).

**Fix (corrected during story review — see Change Log):** lock the row **first**, replacing the plain
`findById` at `:38` entirely — not lock-after-insert as originally drafted. Locking after the flag
INSERT (the original plan) creates a real deadlock risk: two users flagging the same review
concurrently each hold a compatible `FOR KEY SHARE` on the parent row from their own flag INSERT, then
each tries to upgrade to `FOR UPDATE` — a textbook Postgres FK-upgrade deadlock, resolved by the
deadlock detector aborting one side (`CannotAcquireLockException` → `ApiAdvice`'s 409 "resource is
busy", so a flagging user can lose to a spurious conflict on the single most likely concurrent event on
this path). Taking `findByIdForUpdate` as the transaction's first read avoids the upgrade entirely
(mirrors `AdminReviewService.approveReview`'s own "first read of the row in this method" shape — no
`entityManager.refresh` needed either, for the same reason). Re-check **both** parts of the auto-hold
guard under the lock — `moderationStatus == APPROVED` **and** the open-flag count, re-running
`countByReviewIdAndResolvedAtIsNull` after the lock is held, not before — mirroring
`ReviewSubmissionService.updateReview`, which re-runs its whole guard on the refreshed instance, not
part of it. (A stale pre-lock count can otherwise justify an auto-hold the fresh count no longer
supports — e.g., an admin resolved the flags in the window between the pre-lock read and the lock.)

#### Fix 2 (Low, downgraded from Medium during story review) — `CoachProfileService.publishProfile` double-submit returns a generic error instead of `marketplace.alreadyPublished`

`src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java:299-318`.
Two concurrent `publishProfile(userId)` calls (e.g., a genuine accidental double-click, or a client
retry after a slow/timed-out first response) can both pass the unlocked `profile.getStatus() !=
CoachProfileStatus.DRAFT` guard (`:303-305`) before either has committed. Both then proceed to
`profile.setStatus(ACTIVE); coachProfileRepository.save(profile);` and construct+save a new
`CoachSubscription` row (`:309-315`). `marketplace.coach_subscriptions.coach_id` is the table's own
primary key (`V138__baseline_schema.sql:2852-2853`, confirmed), so the second transaction's
subscription insert throws a primary-key-violation `DataIntegrityViolationException` at flush/commit
time, rolling back that entire transaction — including its own `ACTIVE` status write.

**Corrected during story review: this is already a handled error today, not an unhandled 500.**
`ApiAdvice.java:174-200` has a class-wide `@ExceptionHandler(DataIntegrityViolationException.class)`.
`coach_subscriptions_pkey` is not in `CONSTRAINT_MAPPINGS` (`:135-150`) or `CONFLICT_CONSTRAINTS`
(`:152-166`), so today the loser gets **HTTP 400, `messageKey = "generic.dataError"`** — not a crash,
just an unhelpfully generic code, where the non-concurrent pre-check (`:304`) throws
`MarketplaceException("marketplace.alreadyPublished", ...)` → HTTP 422 via `ApiAdvice:395-400`. The
gap is real but narrow: a double-submit is not distinguishable from an unrelated data error to the
caller. This is a UX/error-code polish item, not a data-integrity defect — the PK constraint already
prevents two subscription rows, and the losing transaction's status flip is correctly rolled back with
it.

**Fix (corrected during story review):** catch `DataIntegrityViolationException` around the
subscription insert and translate it to the same `marketplace.alreadyPublished` error the
non-concurrent pre-check throws — **keeping the status code consistent at 422 for both paths**, which
is why this is a `try`/`catch` in the service rather than an `ApiAdvice` `CONSTRAINT_MAPPINGS`/
`CONFLICT_CONSTRAINTS` entry (that route is simpler code but would return 409 for the concurrent loser
while the pre-check returns 422 for the same `messageKey` — a status-code inconsistency for callers
that branch on HTTP status, not just the message key). Use `saveAndFlush` so the violation surfaces
synchronously inside this method, but **do not rely on the flush being scoped to only this insert** (an
earlier draft of this fix assumed that; it is false — `saveAndFlush` flushes the whole persistence
context, which at this point also contains the pending `coach_profiles` UPDATE from `:310`). Instead,
inspect the caught exception's underlying constraint name and only translate to
`marketplace.alreadyPublished` when it is `coach_subscriptions_pkey`; rethrow anything else unchanged.

(Confirmed during story review, worth a code comment: the only thing preventing a silent *UPDATE* of an
existing subscription row — resetting `activeSince` — instead of an INSERT-conflict is the
`status != DRAFT` pre-check at `:303-305`, since `CoachSubscription` has an assigned `@Id` with no
`@GeneratedValue`/`Persistable`, so `save`/`saveAndFlush` routes through `em.merge()`. No path today
sets an `ACTIVE` profile's status back to `DRAFT` (`setStatus(DRAFT)` occurs only on newly-constructed
entities), so this is not live — but it is the invariant that makes Fix 2 correct, and worth recording
so a future change to profile re-drafting doesn't silently break it.)

#### Fix 3 (Medium, new — found during story review, not the original creation-session audit) — `publishProfile` takes no lock at all; a concurrent admin suspension can be silently reverted

`publishProfile` (`:299-318`) never locks the `CoachProfile` row. `requireProfile(userId)` (`:301`)
returns a plain managed entity; `validateAllStepsComplete(profile)` (`:307`) runs several queries'
worth of window before the `:309-310` status write and its `:315` flush. `CoachProfile`
(`repo/CoachProfile.java`) has no `@Version`/`@DynamicUpdate`, so that flush is a full-row UPDATE from
the stale in-memory instance — the same shape of bug as Fix 1.

`AdminCoachEnforcementService.suspendCoach` (`:130-144`) has no status guard beyond "already SUSPENDED
→ return" — it will suspend a `DRAFT` profile — and takes the row lock properly
(`lockRetryer.withBoundedRetry(() -> coachProfileRepository.findByIdForUpdate(...))`) before setting
`SUSPENDED`/`statusChangedAt`. If `suspendCoach` commits inside `publishProfile`'s unlocked window,
`publishProfile`'s full-row UPDATE **reverts the suspension back to `ACTIVE`** and restores the old
`statusChangedAt` — no error, no conflict, the coach is bookable again. `suspendCoach`'s own comment
(`:131-134`) names this exact failure mode: "two writers serialise only when BOTH take it... making
that lock decorative" — `publishProfile` is the writer that doesn't. Reachability is narrower than
Fix 1's (needs an admin suspending a `DRAFT`-status profile specifically), hence Medium not High.

**Fix:** apply this module's own established pattern, the same three lines `saveStep4` (`:249-254`)
already uses — take the lock **after** the non-concurrent `DRAFT` pre-check and `validateAllStepsComplete`
(matching where `saveStep4` places it, right before the write it's protecting), then **re-check
`status == DRAFT` on the refreshed instance** before the `ACTIVE` write (this re-check is new relative
to `saveStep4`'s own shape, since `saveStep4` has no racing status writer to protect against — Fix 3
does):

```java
lockRetryer.withBoundedRetry(() -> {
    coachProfileRepository.findByIdForUpdate(profile.getId())
        .orElseThrow(() -> new MarketplaceException("marketplace.profileNotFound", ...));
    entityManager.refresh(profile, LockModeType.PESSIMISTIC_WRITE);
    return null;
});
if (profile.getStatus() != CoachProfileStatus.DRAFT) {
    throw new MarketplaceException("marketplace.alreadyPublished", "Profile is already published");
}
```

This module's `findByIdForUpdate` is `NO_WAIT`, and every one of its existing call sites wraps it in
`PessimisticLockRetryer` (`CoachProfileRepository.java:28-34`) — do the same here.
`PessimisticLockRetryerCallSiteAuditTest.EXPECTED_CALL_SITE_COUNT` is pinned and **must be bumped by
one** (re-read the current value at implementation time — it may have moved since this story was
written) or the build fails.

### Tasks

1. [ ] Re-verify all line citations above against current `HEAD` before implementing, including the
   two Postgres locking behaviours Fix 1's corrected reasoning rests on (FK RI check takes `FOR KEY
   SHARE`; `FOR KEY SHARE` conflicts with `FOR UPDATE`) — a 5-minute two-session `psql` check is enough.
2. [ ] `ReviewFlagService.flag` (`:37-93`): replace the plain `findById` at `:38` with
   `findByIdForUpdate` (inject `EntityManager`? — **no**, not needed here since this is now the
   transaction's first read of the row; only add it if a later refresh proves necessary). Move the
   `openFlagCount`/threshold computation (currently `:74-76`) to run *after* the lock is acquired, not
   before. Re-check `moderationStatus == APPROVED` on the locked instance immediately before writing
   `UNDER_REVIEW`/`HeldReason.FLAG_THRESHOLD`/`lastModifiedAt` and calling `coachRatingService.recompute`.
   If the re-check fails (status is no longer `APPROVED`), skip the auto-hold write without throwing —
   the flag INSERT at `:67` is part of the same transaction and must still commit regardless; only the
   escalation is skipped. Add a code comment cross-referencing `ReviewSubmissionService.updateReview`
   and this fix's actual trigger (the unresolved-flags gap in `updateReview`, not an admin race at the
   default threshold), matching this file's own citation convention.
3. [ ] `CoachProfileService.publishProfile` (`:299-318`): wrap
   `coachSubscriptionRepository.saveAndFlush(subscription)` in a `try`/`catch
   (DataIntegrityViolationException e)` that inspects the underlying constraint name (matching
   `ApiAdvice.resolveConstraintName`'s own extraction shape, or an equivalent local check) and throws
   `MarketplaceException("marketplace.alreadyPublished", "Profile is already published")` only when the
   constraint is `coach_subscriptions_pkey`; rethrow otherwise. Import
   `org.springframework.dao.DataIntegrityViolationException` (not currently imported in this file).
4. [ ] `CoachProfileService.publishProfile`: add the lock-refresh-recheck block from Fix 3 above,
   placed after `validateAllStepsComplete(profile)` and before the `ACTIVE` status write. Bump
   `PessimisticLockRetryerCallSiteAuditTest.EXPECTED_CALL_SITE_COUNT` by one (re-read its current value
   first).
5. [ ] Confirm no other caller of `ReviewFlagService.flag` or `CoachProfileService.publishProfile`
   depends on the exact prior (buggy) behavior — `grep -rn "\.flag(\|publishProfile(" src/main/java`
   and check each call site.

### Tests

- **Fix 1:** the concurrency scenario needs a service-level test, not an HTTP-level one —
  `ReviewFlagIT` drives `POST /api/reviews/.../flag` through `HttpTestClient` and has no seam to pause
  inside `flag()` between its read and its threshold-crossing write. Add a new
  `ReviewFlagServiceConcurrencyIT` under `src/test/java/.../platform/reviews/service/`, mirroring
  `RadarCompositeCalculationServiceConcurrencyIT`'s own shape (autowire the service directly against
  real Testcontainers Postgres; use a `TransactionTemplate` + latch on a separate thread to commit the
  racing `updateReview` before `flag()`'s threshold check runs) — this is the reachable trigger
  identified above, not the admin-race originally planned. Confirm the existing
  `ReviewFlagIT.flagThresholdReached_reviewSetToUnderReview` (`:201`) still passes as the ordinary
  non-concurrent regression check — it already exists; this AC does not need a new one for that path.
- **Fix 2/Fix 3:** a `CoachProfileBuilderIT` (or a new focused test) proving: (a) two concurrent
  `publishProfile` calls for the same profile result in exactly one `ACTIVE` status, exactly one
  `marketplace.coach_subscriptions` row, and the losing caller receiving
  `marketplace.alreadyPublished`/422, not a generic 400; (b) a `suspendCoach` committed inside a
  concurrent `publishProfile`'s window leaves the profile `SUSPENDED`, not reverted to `ACTIVE`. A
  third test should confirm the existing non-concurrent already-published pre-check (`:303-305`) is
  unaffected.

---

## AC2 — Add the missing drift-detection unit test for `GdprErasureService`'s `ConfigBounds` bounds

### Context

**Rewritten during story review — the original AC2 was wrong in both its diagnosis and its fix; see
Change Log.** `deferred-work.md:3135-3144` (D2, from skillars-deferred-129's own same-day code review)
observed that `GdprErasureService.java:630-631` re-types `2L`/`120L` as literals instead of reading
them from `ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS` (`ConfigBounds.java:280-284`), and
framed this as a divergence risk.

That framing does not hold up: `ConfigBounds.java:33-35`'s own class Javadoc documents that call sites
**intentionally** re-type `[min, max]` as literals, specifically so a `Mockito verify(...)` in each
call site's own unit test can pin the exact numbers as a drift detector — "if you change a bound,
change it in both places." The sibling call site D2's own text cites as precedent,
`RadarCompositeCalculationService.java:203-212`, carries a dated comment reversing the exact opposite
change: *"`/bmad-code-review` fix (2026-09-21): min/max passed as the same LITERAL numbers... not
`.min()`/`.max()` accessor calls... Reading the bound through its own accessors defeats that
convention."* `RadarCompositeCalculatorTest.java:353-354` is the live drift guard:
`verify(configService).getBoundedLong(eq(KEY.key()), eq(5L), eq(2L), eq(120L))`. Also corrected during
review: `getBoundedLong`'s 4-arg overload (`ConfigService.java:110-118`) does not clamp on
out-of-range — it **falls back to `defaultValue`** — so the divergence risk D2 actually describes was
mischaracterized too (a raised `max` with a stale literal does not clamp the read value down to the
old max; it would fall all the way back to the `5L` default with a WARN if the stored value then fell
outside the stale range).

`GdprErasureService.java:630-631` already follows the documented convention correctly — matching
`RadarCompositeCalculationService.java:212` exactly, both in key-via-accessor / bounds-as-literals
shape. **The actual gap D2 names is narrower than originally scoped: there is no unit test pinning
those literals for `GdprErasureService`, unlike the Radar call site.** `GdprErasureServiceTest.java`
does not exist today (only `GdprErasureIT.java`, an integration test). That is what this AC now closes.

### Tasks

1. [ ] Re-verify `GdprErasureService.java:630-631` and `ConfigBounds.java:280-284` against current
   `HEAD` before implementing. **Do not change the literal values or read them via accessors** — the
   literals are correct as written.
2. [ ] Create `src/test/java/com/softropic/skillars/platform/admin/service/GdprErasureServiceTest.java`
   (does not exist today), following `ReviewModerationServiceTest`'s established shape for a
   `@RequiredArgsConstructor` service that builds a `PROPAGATION_REQUIRES_NEW` `TransactionTemplate`
   programmatically: `@ExtendWith(MockitoExtension.class)`, `@Mock` every constructor dependency,
   `@Mock PlatformTransactionManager txManager` + `@Mock TransactionStatus transactionStatus` with
   `lenient().when(txManager.getTransaction(any())).thenReturn(transactionStatus)` in `@BeforeEach`.
   Since `GdprErasureService` builds its `requiresNewTemplate` field in a `@PostConstruct
   initTemplates()` method (`:135-139`) rather than the constructor, and Mockito's `@InjectMocks` does
   not invoke `@PostConstruct`, call `service.initTemplates()` explicitly in `@BeforeEach` after
   construction (or construct the service manually via `new GdprErasureService(...)` and call it —
   either way, do not rely on `@InjectMocks` alone to leave the service usable).
3. [ ] Drive the test through the `erase(requestId, userId)` public entry point with `role = PLAYER`
   (the shortest path to `deletePlayerDevelopmentData`, per `:142-220` — avoids the `PARENT` branch's
   child-loop entirely). Minimal stubs needed to reach the call: `gdprRequestRepository.findById` →
   present; `userRepository.findOneById` → a `User` with `SkillarsRole.PLAYER`;
   `coachProfileRepository.findByUserId` → empty; `playerProfileRepository.findByUserId` → present
   with a non-null id; `playerProfileRepository.findByIdForUpdate` → the same profile (for the lock
   `deletePlayerDevelopmentData` itself takes). Every other injected repository can be left an
   unstubbed `@Mock` (default empty/zero returns are fine for a test that only asserts the
   `getBoundedLong` call).
4. [ ] Assert `verify(configService).getBoundedLong(eq(ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.key()),
   eq(5L), eq(2L), eq(120L))` — mirroring `RadarCompositeCalculatorTest.java:353-354` exactly. This is
   the entire point of the test: a future edit that changes either the literals or switches to accessor
   calls without updating both places fails this test.
5. [ ] Do not touch any other `getBoundedLong`/`getBoundedInt` call site, and do not add a `min`/`max`
   default field to `ConfigBounds.BoundedKey` — both are out of scope (see Provenance's "Out of scope"
   section).

### Tests

- The new `GdprErasureServiceTest` above **is** the test for this AC — there is no separate behavior
  change to verify, since the production code is unchanged. Confirm `GdprErasureIT`'s existing
  boundary-value tests (`2`, `120`) still pass unchanged.

---

## AC3 — Standard `deferred-work.md` ledger closeout

### Tasks

1. [ ] Close out the "platform.marketplace/platform.reviews never audited" narrative that stories
   126, 127, 128, and 129 each recorded — this story is that audit. Record the corrected findings
   summary (1 High fixed by AC1 Fix 1, 1 Low fixed by AC1 Fix 2, 1 Medium fixed by AC1 Fix 3 — the
   third found only during story review, not the original creation-session audit pass) and that both
   modules are now confirmed swept under this series' concurrency lens. Record the corrected coverage
   numbers: 0 schedulers, 26 `@Transactional` annotations across 10 service classes, 11 entities
   (corrected from an initial miscount of 8 during the original audit — see this story's own
   Change Log).
2. [ ] `deferred-work.md:3108-3172` (the fresh same-day skillars-deferred-129 code-review section) —
   mark D2 (`:3135-3144`) **partially closed, not fully closed**: the literal-bounds shape was already
   correct (no code change), and the actual gap — a missing pinning unit test — is closed by AC2. Two
   residuals remain explicitly open and should be named in the ledger entry: (a) the code default `5`
   still appears nowhere in `ConfigBounds` despite the key being registered in `HAS_CODE_DEFAULT`
   (`ConfigBounds.java:330-348`) — `BoundedKey` has no default field, a separate pre-existing gap; (b)
   `ReviewFlagService.java:76` / `ReviewSubmissionService.java:165` re-type both bounds *and* the key
   string for `getBoundedInt`, found during this story's own review, not fixed (see Provenance's "Out
   of scope"). D1, D3, D4, D5 remain untouched, all already correctly `[Review][Defer]`-annotated by
   deferred-129's own review response — re-confirm, do not reword.
3. [ ] `deferred-work.md:3101-3106` (skillars-deferred-128's own review section, Hazard 2 — the
   pooled connection-acquisition wait) — **re-confirm `[DECIDED: accepted risk — skillars-deferred-128]`
   for a fourth consecutive story**, and extend the bullet's own "revisit if" condition with this
   story's own concrete finding: a real fix is not a config tweak — HikariCP has no per-call-site
   connection-acquisition-timeout override through the standard `DataSource.getConnection()` path
   Spring's transaction manager uses (`connection-timeout` is pool-wide only); the only real fix shape
   is a second, dedicated `HikariDataSource` scoped to `deletePlayerDevelopmentData`'s own
   `REQUIRES_NEW` transaction, which would also need separate wiring for the Testcontainers
   `@ServiceConnection` test path (`DataSourceConfig`'s custom `HikariConfig` bean is entirely skipped
   there, per `datasource.container=true`) to be exercisable by `GdprErasureIT` at all. Declined as a
   code-change item this story (owner decision, `AskUserQuestion`, this story's own creation session).
4. [ ] Record the two out-of-scope findings from story review as fresh ledger bullets (not fixed by
   this story, but newly discovered): `ReviewSubmissionService.submitReview`'s `:68-74` catch of
   `DataIntegrityViolationException` being very likely dead code (`CoachReview` uses
   `GenerationType.UUID`, so `save()` doesn't flush inside the `try` — the actual violation surfaces at
   commit-time flush, outside it, so the caller gets a generic 400 instead of
   `ALREADY_SUBMITTED`/`ReviewErrorCode`); and `publishProfile` never setting `statusChangedAt` on
   `DRAFT → ACTIVE`, unlike every `AdminCoachEnforcementService` transition (relevant because
   `findByStatusInOrderByStatusChangedAtAsc` orders on that column).
5. [ ] Grep-sweep `deferred-work.md` for any other reference to the items above that a targeted
   reword might miss (narrative mentions inside a `## Last audit:` summary section, etc.).
6. [ ] Add a new `## Last audit: <implementation date> (skillars-deferred-130 dev-story completion)`
   heading, placed immediately above the freshest section it touches (`:3108`, the skillars-
   deferred-129 same-day section), summarizing the marketplace/reviews audit outcome (as corrected by
   story review) and the AC2/AC3 ledger edits in its own body text.
7. [ ] Re-confirm this story's own "Out of scope" section above remains correctly untouched.

### Tests

- None expected — this AC is a documentation-only ledger edit.

---

## Dev Notes

- This story went through a senior-dev audit (`story-review.md`) after creation, before
  implementation started. The version of AC1/AC2 above already reflects that review's corrections —
  do not re-derive the original (incorrect) framing from the Change Log entries below; they exist to
  explain *why* the story reads the way it does, not as an alternate valid reading.
- **Two different lock conventions across the two modules.** `CoachProfileRepository.findByIdForUpdate`
  is `NO_WAIT` and every one of its call sites wraps it in `PessimisticLockRetryer`.
  `CoachReviewRepository.findByIdForUpdate` is a plain blocking lock and none of its call sites use the
  retryer. AC1 Fix 1 is in the reviews module — **no retryer**, do not reach for `lockRetryer` there by
  analogy with Fix 3. AC1 Fix 3 is in the marketplace module — retryer required, and
  `PessimisticLockRetryerCallSiteAuditTest.EXPECTED_CALL_SITE_COUNT` must be bumped or the build fails.
- **`CoachRatingService.recompute` is safe where Fix 1 leaves it, but must stay last.**
  `CoachProfileRepository.updateRatingAggregate` is `@Modifying(clearAutomatically = true)` with no
  `flushAutomatically` — safe here only because `recompute` runs a JPQL query that `FlushMode.AUTO`
  flushes the pending status UPDATE ahead of. Keep `recompute(...)` after every entity mutation in
  `flag()`, and do not put any `entityManager.refresh(...)` after it.
- `payment.coach_subscriptions` is a different table (different schema) from
  `marketplace.coach_subscriptions` — `SubscriptionService`/`PaymentCoachSubscription` is not a second
  writer of the PK Fix 2 handles. Checked during story review; not a finding.
- AC1 Fix 1 touches `ReviewFlagService.java` for the first time in this series' history — read the
  whole file (94 lines) before editing; it is small. AC1 Fix 2/Fix 3 touch `CoachProfileService.java`
  (516 lines) — read the whole `publishProfile` method and `saveStep4` (the pattern it now mirrors)
  before editing.
- AC2 touches only a new test file — `GdprErasureService.java` and `ConfigBounds.java` are **not**
  modified by this story (corrected scope; the original draft would have edited
  `GdprErasureService.java`).
- No frontend Vue/JS source is touched by this story — no `frontend-tests` PR label needed (confirm
  via `git status --short` before opening the PR).
- No local `mvn verify` — GitHub CI is the sole full-verification gate, per
  `docs/validation-strategy.md`.

### References

- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewFlagService.java` — AC1 Fix 1
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java` — AC1 Fix 1 (the actual racing writer; also the locked-write pattern precedent)
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewModerationService.java` — AC1 Fix 1 (locked-write pattern precedent; `AFTER_COMMIT` discard behavior referenced in the corrected scenario)
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminReviewService.java` — AC1 Fix 1 (locked-write pattern precedent; `resolveAllOpenFlags` caller)
- `src/main/java/com/softropic/skillars/platform/reviews/repo/CoachReviewRepository.java` — AC1 Fix 1 (`findByIdForUpdate`, plain blocking, no retryer)
- `src/main/java/com/softropic/skillars/platform/reviews/repo/CoachReview.java` — AC1 Fix 1 (no `@Version`/`@DynamicUpdate` — why the write is full-row)
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java` — AC1 Fix 2, Fix 3 (`publishProfile`, `saveStep4` pattern precedent)
- `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfileRepository.java` — AC1 Fix 3 (`findByIdForUpdate`, `NO_WAIT` + retryer convention)
- `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachSubscriptionRepository.java` — AC1 Fix 2
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java` — AC1 Fix 3 (`suspendCoach`, the concurrent actor in the failure scenario)
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java` — AC1 Fix 2 (existing `DataIntegrityViolationException` handling; `CONSTRAINT_MAPPINGS`/`CONFLICT_CONSTRAINTS` precedent considered and not used)
- `src/main/resources/db/migration/V138__baseline_schema.sql` — AC1 Fix 1 (`review_flags_review_id_fkey` non-deferrability), Fix 2 (`coach_subscriptions_pkey` citation)
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java` — AC2 (not modified; new test's subject)
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java` — AC2 (convention Javadoc at `:33-35`)
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java` — AC2 (`getBoundedLong` default-fallback vs. clamp behavior)
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java` — AC2 (sibling call site and its dated review-reversal comment)
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculatorTest.java` — AC2 (the `verify(...)` pattern this AC's new test mirrors)
- `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewModerationServiceTest.java` — AC2 (the `PlatformTransactionManager`/`TransactionStatus` mocking pattern this AC's new test needs)
- `src/main/java/com/softropic/skillars/infrastructure/config/DataSourceConfig.java` — AC3 (Hazard 2 finding, not fixed)
- `src/main/resources/application.yaml` — AC3 (`connection-timeout: 30000` citation)
- `_bmad-output/implementation-artifacts/deferred-work.md` — AC3
- `_bmad-output/implementation-artifacts/story-review.md` — the senior-dev audit this story's current version was corrected against
- `src/test/java/com/softropic/skillars/platform/reviews/api/ReviewFlagIT.java` — AC1 Fix 1 (existing non-concurrent regression test); new `ReviewFlagServiceConcurrencyIT` goes in `.../reviews/service/`, not here
- `src/test/java/com/softropic/skillars/platform/marketplace/api/CoachProfileBuilderIT.java` — AC1 Fix 2/Fix 3 test seam

## Change Log

- 2026-09-23: Story created via a fresh `/txn-and-concurrency-audit` of `platform.marketplace`/
  `platform.reviews`, rather than the manual ledger-mining process this series otherwise uses — the
  ledger itself was too thin to mine a comparable bundle this time. Owner decisions taken live
  (`AskUserQuestion`): (1) anchor on the fresh audit; (2) fix `ReviewFlagService.flag`'s TOCTOU via a
  lock-and-recheck pattern; (3) scope the `ConfigBounds` min/max divergence fix narrowly to
  `GdprErasureService`'s own call site; (4) decline the GDPR connection-acquisition-wait item as a
  code-change item and extend its ledger documentation instead.
- 2026-09-23 (story review, `story-review.md`, against `HEAD = 7ed6d30b`): senior-dev audit found the
  story should not be implemented as originally drafted. Corrections applied to this file:
  - **AC1 Fix 1:** kept the fix, but the stated failure scenario was wrong (an unverified assumption
    about FK-lock behavior actually closes that exact window at the default threshold) and the
    prescribed fix order (lock after the flag insert) introduces a new deadlock between two concurrent
    flaggers. Replaced the scenario with the one that is actually reachable (`ReviewSubmissionService
    .updateReview`'s full-row clobber, since it is the only status-writer that doesn't resolve open
    flags), reordered the fix to lock first, and moved the flag-count re-check under the lock too (the
    original task only re-checked status). Also corrected an inaccurate claim that the flag insert was
    already "committed" before the re-check — it is flushed, not committed, since the whole method is
    one transaction. Retargeted the concurrency test from `ReviewFlagIT` (HTTP-level, no seam for the
    required interleaving) to a new service-level `ReviewFlagServiceConcurrencyIT`.
  - **AC1 Fix 2:** downgraded High→Low. The headline justification ("unhandled 500") was false —
    `ApiAdvice` already returns a handled 400 today; the real gap is a generic vs. specific error code.
    The originally-approved `saveAndFlush`-scoping reasoning for the `try`/`catch` was also wrong
    (`saveAndFlush` flushes the whole persistence context, not just the one entity) — corrected the fix
    to inspect the constraint name explicitly rather than relying on flush scoping.
  - **AC1 Fix 3 (new):** the review found `publishProfile` has no lock at all — a real, more severe gap
    in the same method AC1 Fix 2 already opens, missed by the original creation-session audit. Added as
    a new Medium finding with its own fix, task, and test.
  - **AC2:** the original fix (reading `min`/`max` via `ConfigBounds` accessors) would have reversed a
    documented codebase convention (`ConfigBounds.java:33-35`) and a dated review decision at the exact
    sibling call site cited as precedent — neither was visible when the original scope decision was
    made. Owner re-decision (`AskUserQuestion`, post-review): replace AC2 with a pinning unit test for
    `GdprErasureService`'s existing (correct) literals, matching the convention instead of fighting it.
    No production code is changed by AC2 as corrected.
  - **AC3:** corrected the audit's own coverage numbers (11 entities, not 8; 26 `@Transactional`
    annotations, not "~20"), marked ledger item D2 partially- rather than fully-closed with its two
    named residuals, and added tasks to record the fresh findings the review surfaced (dead-code catch
    in `ReviewSubmissionService.submitReview`, missing `statusChangedAt` update in `publishProfile`) as
    new ledger bullets rather than silently dropping them.
