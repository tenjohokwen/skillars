# Story: Marketplace/Reviews Correctness Sweep, ReviewFlagService Hygiene & CI Reset-Deadlock Fix

**Story Key:** `skillars-deferred-131-marketplace-reviews-hardening-and-ci-reset-deadlock-fix`
**Epic:** Deferred Work
**Priority:** Medium (two genuine production defects — a subscription-tier read that permanently breaks
after an admin reinstate, and a race that can orphan a live Stripe subscription — plus a
reachable-but-cosmetic double-submit gap, a lock-scope hygiene fix, three low-severity `ReviewFlagService`
gaps, deterministic concurrency-test coverage, and a first-occurrence CI test-infrastructure deadlock).
**Status:** done
**Created:** 2026-09-23

---

## Context

Follows `skillars-deferred-130` (PR #218, merged to master at `e2d0233b`). That story's own
post-implementation `/bmad-code-review` (four layers, 2026-09-23) surfaced several findings it
correctly left out of its own narrow scope — recorded in `deferred-work.md`'s **actual** newest
section, `## Deferred from: code review of skillars-deferred-130-... (2026-09-23)` (tail of the file).
This story's Fixes 1–8 map one-to-one onto that section's bullets. This story closes the genuine
defects from that list, plus a first-occurrence CI flake found live during this session's post-merge
master verification (run `35891248593`, job `test`, before a rerun passed clean).

**Correction (pre-implementation `story-review.md` audit, 2026-09-23):** this story's first draft
mis-identified its own source, naming `## Last audit: 2026-09-23 (skillars-deferred-130 dev-story
completion)` and `## Deferred from: story review of skillars-deferred-130-...` as the two newest
sections. They are not — two further sections were appended after both of them:
`## Deferred from: code review of skillars-deferred-129-...` and, last in the file,
`## Deferred from: code review of skillars-deferred-130-...` (the one named above). The deferred-129
section is GDPR/`ConfigService` scoped, not marketplace/reviews, and its items are already `[AUDIT ...
PARTIALLY CLOSED]` or explicitly noted as out of a prior story's scope — none require action here,
except one residual worth naming for AC5: `ReviewFlagService.java:76` / `ReviewSubmissionService.java`
re-type `getBoundedInt` bounds and key strings rather than referencing shared constants, found during
that audit and left open as out-of-scope for -129/-130. Not fixed by this story either (it's
config-hygiene, not correctness) — record it explicitly in the ledger rather than let AC5 imply it was
resolved.

All line citations below were re-verified against `HEAD = e2d0233b` (this branch's base) immediately
before this story was drafted, and again during a pre-implementation `story-review.md` senior-dev audit
(2026-09-23) that caught several drifted/incorrect citations from the first draft — corrected inline
below, each flagged with what changed and why.

**Owner decisions taken live (AskUserQuestion) before drafting**, per the established convention of
resolving scope/design choices at story creation rather than leaving them for dev-story:

1. `reinstateCoach`'s missing-subscription gap — **fix** (find-or-create), not document-only.
2. `syncMarketplaceTier`'s race with `publishProfile` — **fix** (close the race at its source), not
   catch-and-recover or document-only.
3. Scope breadth — **full sweep**: bundle the three marginal low-severity `ReviewFlagService` items
   (lock-scope, DIVE mapping, null-guard) alongside the two clear one-liners, not core-scope-only.
4. The CI reset deadlock (found live this session) — **investigate and fix in this story**, not
   ledger-only.

**Further owner decisions taken live (AskUserQuestion) during a pre-implementation `story-review.md`
senior-dev audit, 2026-09-23** — the audit found four places where this story's first draft had picked
a mechanism the codebase contradicts or that would not work; each was re-opened as a genuine design
choice rather than silently corrected:

5. Fix 3's `validateAllStepsComplete` — **run it** (build a seam and call it from `reinstateCoach`),
   not accept-and-document. `CoachProfileService.java:355-362`'s own javadoc (added by story-130's code
   review) proves `DRAFT → SUSPENDED → reinstate → ACTIVE` is reachable, so the original "scope creep
   with no defect behind it" justification was factually wrong.
6. Fix 3's scope — **extend to `AdminCoachEnforcementService.deleteStrike`'s tier-3 ACTIVE branch too**
   (`:460-462`), not `reinstateCoach` only. Identical defect, identical one-line remedy, reachable by
   the same `DRAFT → PENDING_REVIEW` path, already under the same lock.
7. Fix 5's stale-read remedy — **projection guards** (scalar queries for `authorId`/`coachId` so
   nothing enters the persistence context before the locked read), not refresh-under-lock. Keeps the
   existing "first read of the row" comment true verbatim and adds no `EntityManager` dependency.
8. AC4 Task 2's mechanism — **quiesce the async executors** before the reset transaction opens, not
   `pg_advisory_xact_lock` (which cannot serialize against a counterparty that never takes it — the
   hypothesized actor is an ordinary `@Async` JPA writer, not another reset).

---

## AC1: Marketplace/Reviews Correctness Fixes

### Fix 1 — `ReviewSubmissionService.submitReview`'s double-submit catch is dead code

**Context:** `ReviewSubmissionService.java:68-74` (re-verified against current `HEAD` during the
pre-implementation `story-review.md` audit — the story's first draft cited `:66-67`, which is actually
the two `review.setModerationStatus(...)`/`setLastModifiedAt(...)` lines *before* the `try`; the
ledger's original `:68-74` was correct all along). `CoachReview.reviewId` uses `GenerationType.UUID`
(confirmed `CoachReview.java:28-31`, not a DB-generated sequence), so
`review = coachReviewRepository.save(review);` at `:69` never flushes inside the `try` — Hibernate only
assigns the UUID client-side and enqueues the INSERT for the next natural flush point (end of
transaction, or an unrelated later query). The real `uq_coach_reviews_author_coach` unique constraint
(`author_id, coach_id` — `V138__baseline_schema.sql:3080-3084`; not "`review_author_coach_unique`-style",
that name doesn't exist) violation from a genuine concurrent double-submit therefore surfaces
**uncommitted, outside this method**, at whatever later flush point the surrounding `@Transactional`
boundary hits — not inside the `catch (DataIntegrityViolationException e)` at `:70`, which can only ever
catch a violation that never actually happens here. A concurrent double-submit today gets whatever
generic error the eventual uncaught flush failure maps to, not the clean `ALREADY_SUBMITTED` the
pre-check at `:47-51` already throws for the non-concurrent case.

**Fix:** change `review = coachReviewRepository.save(review);` to
`review = coachReviewRepository.saveAndFlush(review);` at `:69`, forcing the flush (and therefore the
constraint check) inside the existing `try`, so the existing `catch` block actually does what its comment
already claims. No other change — the catch's error mapping (`ALREADY_SUBMITTED`) is already correct,
just unreachable.

**Test:** new `ReviewSubmissionServiceConcurrencyIT` — no existing reviews concurrency IT exercises
`submitReview` (`ReviewFlagServiceConcurrencyIT` tests `flag()`; the closest-named class,
`ReviewSubmissionServiceIT`, does not exist — the real submission IT is
`src/test/java/.../reviews/api/ReviewSubmissionIT.java`, an API-layer test, not a place to add a
Testcontainers-lock-contention case). Proves two concurrent `submitReview` calls for the same
`(coachId, authorId)` yield exactly one persisted review and the loser gets `ALREADY_SUBMITTED`, not a
generic 500/400. **Test-design trap (pre-implementation audit finding):** mirror
`CoachProfileServiceConcurrencyIT`'s shape where only the lock/insert **winner** is wrapped in
`transactionTemplate.execute(...)`, and the **loser**'s `submitReview` call is made directly, outside any
outer transaction. If the loser is invoked from inside an outer `transactionTemplate.execute`, its
flush-time `DataIntegrityViolationException` marks that outer transaction rollback-only, and the test
observes `UnexpectedRollbackException` at the outer boundary instead of the expected `ALREADY_SUBMITTED`
— a false failure that reads like the fix didn't work. In production `ReviewResource` opens no
transaction, so `ReviewSubmissionService`'s own `@Transactional` is always the outermost boundary; the
test must preserve that.

### Fix 2 — `CoachProfileService.publishProfile` never sets `statusChangedAt`

**Context:** `CoachProfileService.java:329` (`profile.setStatus(CoachProfileStatus.ACTIVE);`) — ledger
cited this correctly, only the surrounding line numbers shifted from story-130's own edits. Every sibling
status writer sets it: `AdminCoachEnforcementService.suspendCoach:143`, `reinstateCoach:231`, and the
two escalation branches at `:450`/`:461` — all via `coach.setStatusChangedAt(Instant.now())`. A freshly
published profile is the one status transition in the whole enforcement/marketplace surface that skips
it. `status_changed_at` is nullable with no DB default (`V138__baseline_schema.sql:1585`) and no entity
default, and is not set at `DRAFT` creation either — so a freshly published profile carries **NULL**,
not a stand-in creation timestamp. `AdminCoachEnforcementService`'s own admin queue query,
`findByStatusInOrderByStatusChangedAtAsc` (`CoachProfileRepository.java:53-55`, call site
`getCoachesUnderEnforcement:498`), sorts `ASC NULLS LAST` — but that query only lists
`PENDING_REVIEW`/`SUSPENDED` by default (`:488`), so a freshly-published `ACTIVE` profile only reaches
it under an explicit `status=ACTIVE` filter. The more direct, always-reachable effect is a `null`
`statusChangedAt` surfacing in `CoachEnforcementListItemDto` (`:511`) for any admin view of that coach.

**Fix:** add `import java.time.Instant;` to `CoachProfileService.java` (not currently imported — the
file uses `OffsetDateTime`/`ZoneId` for other timestamps) and add
`profile.setStatusChangedAt(Instant.now());` immediately alongside the `setStatus(ACTIVE)` call at
`:329`, matching the `Instant.now()` convention every sibling writer uses (do not use
`OffsetDateTime.now()` here — confirm `CoachProfile.statusChangedAt`'s declared type is `Instant` before
implementing, matching `AdminCoachEnforcementService`'s usage).

**Test:** extend `CoachProfileServiceConcurrencyIT` or `CoachProfileBuilderIT` with a direct assertion
that `publishProfile` sets `statusChangedAt` to a fresh timestamp (not null, not the row's creation time).

### Fix 3 — `reinstateCoach`/`deleteStrike` can leave a coach ACTIVE with no subscription row and no validation

**Context (citations corrected during pre-implementation `story-review.md` audit — the first draft's
`:219-221` pointed at the early-return `if (coach.getStatus() == ACTIVE) return;` guard, not the write):**

| What | Line (`AdminCoachEnforcementService.java`) |
|---|---|
| `reinstateCoach`'s `lockRetryer.withBoundedRetry(... findByIdForUpdate ...)` | `:217-218` |
| `reinstateCoach`'s `coach.setStatus(ACTIVE); coach.setStatusChangedAt(...); save(coach);` | `:230-232` |
| `deleteStrike`'s own `findByIdForUpdate` lock | `:409-410` |
| `deleteStrike`'s tier-3 `coach.setStatus(ACTIVE); ...; save(coach);` branch | `:460-462` |

`reinstateCoach` accepts `SUSPENDED`, `PENDING_REVIEW`, and `REDUCED` as source statuses and sets
`ACTIVE` unconditionally; `deleteStrike`'s tier-3 branch (when the in-window strike count drops below
`visibilityThreshold`) does the same from `PENDING_REVIEW`/`REDUCED`. Neither touches
`coach_subscriptions`, and **neither re-validates the profile is actually publishable.** If a coach
reaches one of those statuses without a subscription row present,
`CoachProfileService.getCoachSubscriptionTier` (`:494-497`) throws `ResourceNotFoundException`
permanently for that coach across all **10 call sites** that read it (`RadarAssessmentService:52`/`:98`,
`ReportGenerationService:140`/`:262`/`:275`, `DevelopmentCorrelationService:51`, `QuotaConfigService:63`,
`DrillLibraryService:187`, `CoachMarketplaceResource:88`) — `publishProfile` cannot repair it, since the
coach is no longer `DRAFT` after reinstate/tier-3-revert.

**Reachability of the missing-subscription case, confirmed (owner decision, story-review audit):**
`CoachProfileService.java:355-362`'s own javadoc (added by story-130's code review) documents that
`AdminCoachEnforcementService.suspendCoach` has no `ACTIVE` guard and the strike-service transitions only
check the *target* status — so `DRAFT → SUSPENDED → reinstate → ACTIVE` (and the equivalent through
`deleteStrike`'s tier-3 branch) is reachable *without the profile ever having gone through
`publishProfile`*, meaning `validateAllStepsComplete` was never run at all, not merely once. This is
exactly the ledger's own framing of the defect.

**Fix — both halves, on both call sites (owner decision, story-review audit; corrects the story's first
draft, which had dropped `validateAllStepsComplete` on an since-refuted premise):**

1. **Subscription find-or-create**, mirroring `publishProfile`'s own `skillars-deferred-130` pattern
   (`CoachProfileService.java:343-350`): inject `CoachSubscriptionRepository` into
   `AdminCoachEnforcementService` (already `@RequiredArgsConstructor` — a new `private final` field is
   sufficient, no constructor edits needed; `grep -rn "new AdminCoachEnforcementService("
   src/test` found no manual instantiations as of this story's creation — re-check before implementing)
   and add, right after each `ACTIVE` write (`:230-232` and `:460-462`):

   ```java
   CoachSubscription subscription = coachSubscriptionRepository.findByCoachId(coach.getId())
       .orElseGet(() -> {
           CoachSubscription created = new CoachSubscription();
           created.setCoachId(coach.getId());
           created.setTier(CoachSubscriptionTier.SCOUT);
           return created;
       });
   coachSubscriptionRepository.save(subscription);
   ```

   Runs under the lock already held at each site (`:217-218` / `:409-410`) — no additional locking
   needed.

2. **Re-validate the profile before writing ACTIVE**, using `CoachProfileService.validateAllStepsComplete`
   (`:513`, currently `private`). This needs a new seam: widen its visibility to package-private or add a
   thin public wrapper on `CoachProfileService` (e.g. `validateReadyForActivation(CoachProfile profile)`)
   that `AdminCoachEnforcementService` can call — decide the exact shape at implementation time, since it
   crosses a service boundary that doesn't otherwise exist between these two classes. Call it immediately
   before each `ACTIVE` write, inside the same lock; if it throws, the reinstate/strike-delete call should
   fail with a clear error (do not silently no-op an admin action) rather than leave the coach in the
   pre-write status with no explanation. Confirm at implementation time whether `MarketplaceException`
   (validateAllStepsComplete's current throw type) is the right shape to surface from
   `AdminCoachEnforcementService`, or whether it needs translating.

**Additional belt-and-braces fix (M3, story-review audit) — default `getCoachSubscriptionTier` to
`SCOUT` on a missing row, not throw:** the two find-or-create sites above only repair *future* coaches
taking those two paths — they do nothing for any row already in the broken state today, nor for any path
added later that reaches `ACTIVE` without going through one of these two call sites.
`CoachProfileService.getCoachSubscriptionTier` (`:494-497`) already has the answer in the same file: every
other missing-subscription case in this codebase (`publishProfile`, `syncMarketplaceTier`) defaults to
`SCOUT`, not an error. Change its `.orElseThrow(...)` to `.orElse(CoachSubscriptionTier.SCOUT)`. This
closes the read-side failure surface unconditionally, independent of which write path caused the gap —
the two find-or-create fixes above remain worth doing anyway, since they keep the row itself consistent
for any other code that reads `coach_subscriptions` directly rather than through this method.

**Test:** extend `AdminCoachEnforcementConcurrencyIT` or `AdminCoachEnforcementServiceIsolationTest`
(the real existing classes — no `AdminCoachEnforcementServiceIT` exists) with: (1) reinstate a
`SUSPENDED` coach with no pre-existing subscription row and builder-step data missing, assert it now
throws the validation error instead of silently activating; (2) reinstate a `SUSPENDED` coach with
complete builder-step data and no subscription row, assert a `SCOUT`-tier row now exists; (3) reinstate a
coach who already has a subscription row (e.g. `INSTRUCTOR`/`ACADEMY` from a Stripe purchase made while
suspended — `CoachSubscriptionTier` is `{SCOUT, INSTRUCTOR, ACADEMY}`, not `PRO`/`ELITE`, which are
*player* tiers), assert the existing tier is preserved, not reset to `SCOUT`; (4) repeat (1)-(3) for
`deleteStrike`'s tier-3 branch. Unit test on `CoachProfileService.getCoachSubscriptionTier` asserting a
missing row now returns `SCOUT` rather than throwing.

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
`SubscriptionService` has no existing lock helper today).

**Exact code shape (corrected during pre-implementation `story-review.md` audit — "wrap the body"
would have broken `PessimisticLockRetryerCallSiteAuditTest`'s DENYLIST, which rejects any `.save(` inside
a retried lambda, and would violate `withBoundedRetry`'s own documented contract that the supplier "can
legitimately execute more than once for a single logical call"): the lock acquisition alone goes inside
`withBoundedRetry`; the `ifPresentOrElse` writes run *after* it returns, not inside it — the Postgres row
lock is held for the whole transaction, not just the lambda, so this preserves the guarantee exactly
(the same argument the audit test's own javadoc makes for `DrillUploadService`'s identical fix):

```java
private void syncMarketplaceTier(UUID coachId, String tier) {
    lockRetryer.withBoundedRetry(() -> coachProfileRepository.findByIdForUpdate(coachId)
        .orElseThrow(() -> /* see missing-profile decision below */));
    coachSubscriptionRepository.findByCoachId(coachId).ifPresentOrElse(
        cs -> { cs.setTier(...); coachSubscriptionRepository.save(cs); },
        () -> { CoachSubscription cs = new CoachSubscription(); cs.setCoachId(coachId);
                cs.setTier(...); coachSubscriptionRepository.save(cs); }
    );
}
```

**Missing-profile decision:** `findByIdForUpdate` finding no profile for `coachId` is reachable —
`payment.coach_subscriptions` and `marketplace.coach_profiles` are separate tables with separate
lifecycles, and the webhook call site (`:606`, `handleSubscriptionDeleted`) resolves the coach from a
Stripe subscription id, not from a profile lookup, so a webhook for a coach with no marketplace profile
row is a real possibility. **Throw** (do not proceed unlocked) — silently skipping the lock on a
missing-profile path would reintroduce exactly the race this fix closes for that path, and a webhook
receiving events for a coach with no profile row is itself a data-integrity signal worth surfacing rather
than papering over. Use a `PaymentGatewayException` consistent with this method's siblings' error
handling, not a raw `ResourceNotFoundException` (confirm the exact exception type/message convention this
file already uses for similar cases before implementing).

**What this fix does and does not close (M4, story-review audit — record honestly for the ledger
closeout in AC5, don't overclaim):** this closes the *common* case — a fast colliding
`syncMarketplaceTier` now waits for `publishProfile` instead of racing it. It does **not** eliminate the
Stripe-orphan risk entirely: `findByIdForUpdate` is NOWAIT, so `PessimisticLockRetryer` still exhausts
its ~3.2s budget and rethrows `PessimisticLockingFailureException` on sustained contention, which still
rolls back `persistCoachSubscription` while the Stripe subscription created earlier in the same call
chain (outside this DB transaction, `SubscriptionService.java:141`) survives orphaned — just rarer, and
now reachable from a *slow* `publishProfile` rather than only a colliding one. The durable fix (a
compensating action or a reconciliation sweep for Stripe subscriptions with no local row) is a separate
story; name it in the ledger rather than imply this fix closes the orphan risk outright.

**Call-site count bump (B4, story-review audit):** this is a new `.withBoundedRetry(` call site.
`PessimisticLockRetryerCallSiteAuditTest.EXPECTED_CALL_SITE_COUNT` must move from `32` to `33`. Fix 3
adds none — it reuses each method's already-existing lock.

**Test:** new `SubscriptionServiceConcurrencyIT` (real Testcontainers Postgres, `TransactionTemplate` +
`CountDownLatch`, mirroring `CoachProfileServiceConcurrencyIT`'s own shape): race `publishProfile`
against `syncMarketplaceTier` for the same coach, assert exactly one `coach_subscriptions` row survives
and neither side throws an uncaught `DataIntegrityViolationException`. Positive verification worth noting
so the dev doesn't second-guess it: the three sweeper/webhook call sites (`:467`, `:534`, `:606`) each run
one coach per transaction via `transactionTemplate.executeWithoutResult`, so adding a row lock introduces
no batch lock-ordering deadlock risk.

---

## AC2: `ReviewFlagService` Hygiene Sweep

### Fix 5 — `flag()` holds its exclusive lock across five no-op exit paths

**Context:** `ReviewFlagService.java:47-49` (the lock), `:51-54` (self-flag guard), `:56-63` (missing
coach profile / coach-flags-own-profile guards), `:65-68` (`ALREADY_FLAGGED` existence guard), `:75-81`
(the insert + its own DIVE-catch exit). None of these five exits write anything, yet all run under the
row's exclusive `FOR UPDATE` lock acquired at `:47` — a user repeatedly flagging a review they already
flagged serializes admin moderation (`AdminReviewService.approveReview`/`blockReview`) behind a lock held
for a call that was always going to no-op.

**Fix — projection guards, not a two-read restructure (owner decision, story-review audit; corrects the
story's first draft, which proposed an unlocked `reviewRepository.findById` pre-read):** the first draft's
plan would have reintroduced the exact stale-read bug `skillars-deferred-130` AC1 Fix 1 closed.
`CoachReviewRepository.findByIdForUpdate` is a JPQL query (`:23-25`) with **no** `@QueryHints` — if an
earlier unlocked `findById` in the same method has already put the `CoachReview` in the persistence
context, Hibernate's identity map means the later locked query takes the DB lock but **returns the
already-managed instance without refreshing its fields**, exactly the gotcha
`ReviewSubmissionService.updateReview:108-113` and `AdminReviewService.approveReview:75-81` both document
and guard against. The auto-hold decision at `:97` (`review.getModerationStatus()`) and the writes at
`:98-101` would then silently run on a pre-lock snapshot.

Instead: never load the `CoachReview` entity for the four write-independent guards at all, so nothing
enters the persistence context before the locked read runs.

1. Add scalar/projection queries to `CoachReviewRepository` for exactly the two facts the guards need —
   `authorId` (self-flag check, `:51-54`) and `coachId` (to resolve the coach-profile guards, `:56-63`) —
   e.g. `Optional<Object[]> findAuthorAndCoachIdByReviewId(UUID reviewId)` or two single-field query
   methods, whichever fits this repository's existing style better (decide at implementation time).
2. Run `reviewFlagRepository.existsByReviewIdAndFlaggedBy` (`:65-68`) against the projected data — it is
   a separate table/query already, not dependent on the review row's freshness.
3. Only after all four guards pass, acquire the lock via the existing `reviewRepository.findByIdForUpdate`
   call and proceed exactly as today from the insert (`:70-81`) through the auto-hold check (`:92-104`).
   This is genuinely the transaction's first load of the `CoachReview` entity, so the existing code
   comment's "first read of the row" reasoning (`:44-46`) stays **true verbatim** — no rewrite needed
   there, and no new `EntityManager` dependency is introduced.

This adds one extra unlocked projection query on the common (non-error) path and on the four
guard-rejection paths — acceptable, since the previous behavior on those same rejection paths was to hold
the exclusive lock for the same round trip anyway.

**Test:** extend `ReviewFlagServiceConcurrencyIT` with a case proving a repeat-flag no-op (`ALREADY_FLAGGED`)
does NOT block a concurrent `approveReview`/`blockReview` on the same review — assert the admin call
completes without waiting on the flagger's rejected call.

### Fix 6 — `flag()`'s blanket `DataIntegrityViolationException → ALREADY_FLAGGED` mapping

**Context:** `ReviewFlagService.java:75-81` (post-restructure, line numbers will shift after Fix 5 —
re-verify at implementation time). The catch maps every constraint violation on the insert to
`ALREADY_FLAGGED`, but `ReviewFlag` has other constraints that can fail concurrently and none of those
mean "already flagged." Corrected candidate list (story-review audit — `review_flags.flagged_by` has
**no FK**; `review_flags_review_id_fkey` on `review_id` is the table's only FK,
`V138__baseline_schema.sql:4528-4532`): a null `reason`, a `details` value over its length bound, the
`review_id` FK, and `review_flags_pkey`. Low severity — unreachable from REST today given upstream
validation, per the ledger's own framing — but worth precision while this method is already being
touched twice in this story.

**Fix:** check the violated constraint name before mapping to `ALREADY_FLAGGED` (mirroring
`CoachProfileService.publishProfile`'s own `skillars-deferred-130` pattern of checking
`coach_subscriptions_pkey` by name before mapping to `marketplace.alreadyPublished` — though note that
pattern was itself withdrawn there in favor of find-or-create; here there genuinely is a real unique index
worth checking by name, since this file's insert really can violate it concurrently). Something in the
shape of:

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
message/constraint name. Note (F3, story-review audit): `review_flags_unique_flagger` is a **unique
index**, not a table constraint (`CREATE UNIQUE INDEX ... ON reviews.review_flags USING btree (review_id,
flagged_by)`, `V138__baseline_schema.sql:3931-3934`) — Postgres does report an index name in a `23505`
violation's `constraint` field the same way it reports a named constraint, so name-matching still works,
but this is worth knowing since a Mockito fixture built against the wrong exception shape wouldn't catch
the mismatch (see Test below).

**Test:** a Mockito unit test alone only proves the parser matches its own hand-built fixture — it
cannot confirm the real Postgres exception carries the constraint/index name where the parser expects it.
Pin the real message shape with **one Testcontainers IT** that provokes a genuine duplicate-flag
`DataIntegrityViolationException` (two flags from the same `flaggedBy` on the same review, racing or
sequential) and asserts it still maps to `ALREADY_FLAGGED`; keep the Mockito test only for the negative
branch (a DIVE with a different constraint name propagates uncaught).

### Fix 7 — `flag()` NPEs on a null `flaggedBy`

**Context:** `ReviewFlagService.java:59` (`flaggedBy.equals(coachProfile.getUserId())` — ledger cited
`:61`, drifted after story-130's own comment additions). Unreachable from REST today, since
`resolveUserId()` (confirm call site) guards against a null caller before this method is invoked — this
is defensive hardening, not a live bug.

**Fix:** add an explicit null check for `flaggedBy` at the top of `flag()`, before the lock acquisition at
`:47`, throwing the same `OperationNotAllowedException`/`ReviewErrorCode` shape as the other guards
(confirm the right error code — likely a new one, since none of the existing `ReviewErrorCode` values fit
"missing flagger identity" — or reuse `REVIEW_NOT_FOUND` if the project convention is to not leak
"invalid caller identity" as a distinct error to the API surface; decide at implementation time). Adding a
new `ReviewErrorCode` constant is the whole change if that route is taken — confirmed (story-review
audit) `src/main/resources/i18n/messages*.properties` carries no `reviews.*` error keys and no test
enumerates `ErrorCode` values, so no other wiring is needed.

**Test:** new `ReviewFlagServiceTest` (Mockito) — no such class exists yet, this is a new file, not an
addition to an existing one (confirmed via `find`) — calling `flag(reviewId, null, reason, details)`
directly, asserting a clean exception instead of an NPE. Fix 6's negative-branch Mockito test above can
live in the same new class.

### Fix 8 — Silent flag-wipe on approve/block gets a log line

**Context (citation note, story-review audit — the audit itself miscounted these on a first pass; both
below are re-confirmed directly against current source):** `AdminReviewService.java:94`
(`reviewFlagRepository.resolveAllOpenFlags(reviewId, Instant.now());` inside `approveReview` — this
citation was correct all along, matching the ledger). `blockReview`'s own equivalent call is at `:126`
(also matching the ledger — **extending this fix to `blockReview` too**, not `approveReview` only, per
the ledger's own framing: "`BLOCKED` reviews likewise still accept flags that can never act"). The
existing `log` field usage referenced for style is at `:106` (`log.info("Review approved: ...")`), not
`:104`.

Flags cast while a review is `PENDING`/`UNDER_REVIEW` accumulate past the auto-hold threshold, are
event-published as real via `ReviewFlaggedEvent`, then silently wiped by `resolveAllOpenFlags` with no
log or alert when the review is later approved or blocked — the review's final state carries
`openFlagCount == 0` and no record anything was ever flagged. Pre-existing, not introduced by story-130 or
this story.

**Fix (F8, story-review audit — one statement instead of two):** rather than a separate
`countByReviewIdAndResolvedAtIsNull` read before the resolve call, change
`ReviewFlagRepository.resolveAllOpenFlags` (`:22-24`) to return `int` (the row count `@Modifying` JPQL
`UPDATE` already computes) instead of `void`, and log a `WARN` using that returned count when it is
non-zero, naming the reviewId, in both `approveReview` (`:94`) and `blockReview` (`:126`). No extra query,
no window between count and update. (Note: the query already has `clearAutomatically = true`, which
detaches `review` after the call — harmless at both call sites today, since only getters are used on it
afterward, but worth knowing if a future edit adds a write after this call.)

**Test:** unit or IT assertion that approving *and* blocking a review with open flags each log the
expected WARN message with the correct count (use a log-capture test utility if this codebase has an
established one — confirmed precedent exists: `ListAppender`/`OutputCaptureExtension`/`CapturedOutput`
are already used in ≥10 test classes, e.g. `BookingServiceTest`, `SluCalculationServiceIT` — no new
dependency needed).

---

## AC3: Concurrency IT Determinism

**Context:** Both `ReviewFlagServiceConcurrencyIT` (two `Thread.sleep(300)` call sites, `:191` and
`:300`) and `CoachProfileServiceConcurrencyIT` (one, `:192`) — three call sites total across the two
existing files, not two — use a fixed `Thread.sleep(300)` as their "contention established" signal, and
take wall-clock `Instant.now()` readings after `transactionTemplate.execute()` returns for cross-thread
ordering assertions. Nothing verifies the contending thread actually blocked on the lock — on a cold JVM
or a constrained CI runner the test can pass without ever exercising the lock it claims to test, and the
ordering assertion can fail on a correct system under load. Recorded as a Deferred item in
`skillars-deferred-130`'s own code review; this story closes it since AC1/AC2 above already touch both
files' subject services and this story is adding `SubscriptionServiceConcurrencyIT` as a third instance
of the same pattern (Fix 4's test). `Awaitility` itself is already a dependency (`pom.xml:447-448`), used
elsewhere for non-lock waits (e.g. `RefundOutboxIT`, `SluSnapshotOutboxIT`) — not, contrary to the
story's first draft, in `BookingServiceConcurrencyIT`, which does not use it.

**Fix — two different signals for two different lock disciplines (corrected during pre-implementation
`story-review.md` audit — a single `pg_locks` poll cannot work across all three ITs):**

- **`ReviewFlagServiceConcurrencyIT`** locks via `CoachReviewRepository.findByIdForUpdate` (`:23-25`),
  which carries **no** `@QueryHints` — a genuine blocking `FOR UPDATE`, so the contending backend truly
  sits in a wait state and is visible as `granted = false`. Use the `pg_locks`/`pg_stat_activity` poll:

  ```sql
  SELECT count(*) FROM pg_locks l
  JOIN pg_stat_activity a ON l.pid = a.pid
  WHERE NOT l.granted AND a.query LIKE '%<table>%'
  ```

  polled via `Awaitility.await().atMost(...).until(...)`. Verify the Testcontainers DB role can read
  other backends' `pg_stat_activity.query` (needs superuser or `pg_read_all_stats`) before committing to
  this route.

- **`CoachProfileServiceConcurrencyIT`** and the new **`SubscriptionServiceConcurrencyIT`** both lock via
  `CoachProfileRepository.findByIdForUpdate` (`:35-38`), which carries
  `@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))` — **NOWAIT**. The
  contending backend never enters a wait state: it fails instantly with `55P03`, and
  `PessimisticLockRetryer.withBoundedRetry` rolls back to its JDBC savepoint and sleeps *in Java*
  (75–800ms, growing, 8 attempts). For essentially the whole window the backend is idle, not waiting on a
  Postgres lock — a `pg_locks` poll here would spin until timeout and fail, including on a **correct**
  system, which is worse than the `Thread.sleep(300)` it replaces. Use the retryer's own instrumentation
  instead: `PessimisticLockRetryer` already increments a `persistence.lock_retry.retries` counter on the
  injected `MeterRegistry` (`PessimisticLockRetryer.java:182-186`) whenever a retry happens. Poll
  `Awaitility.await().atMost(...).until(() -> retriesCounter.count() > 0)` — a direct, deterministic proof
  the contender actually hit the lock and is retrying, stronger than "a backend is waiting," and needs no
  `pg_stat_activity` privileges.

Apply both signals across all three concurrency ITs for consistency — implement each helper once, share
it across the files that need that discipline.

**Test:** this AC *is* the test change — no additional test needed beyond confirming the three ITs still
pass with the new polling mechanisms, and ideally one deliberate run with the fix reverted (in the lock
under test, not the poll) to confirm each poll still catches the regression it's meant to catch.

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

**Task 2 (fix — corrected during pre-implementation `story-review.md` audit; owner decision: quiesce, not
advisory-lock):** a `pg_advisory_xact_lock` around the reset (the story's first draft) **cannot** prevent
this deadlock. An advisory lock only serializes transactions that *also* take that same advisory lock —
the hypothesized counterparty (`RadarCompositeCalculationService`'s `@Async("reportExecutor")`
`@TransactionalEventListener(AFTER_COMMIT)` listener, confirmed at `:78`/`:82`, running ordinary JPA
writes on `PlayerProfile`-adjacent state) would never call it, so it would remain entirely unaffected and
the `ShareLock` cycle on `main.player_profiles` would stay exactly as reachable as before. The only thing
an advisory lock actually serializes here is *two concurrent resets* — which cannot occur (one Failsafe
fork, `pom.xml:660-661`; no `junit-platform.properties`; test methods run sequentially). This also
resolves the story's own internal contradiction between Task 1 ("do not implement a fix that assumes
[the hypothesis] without verifying first") and the old Task 2 ("structural, independent of the exact root
cause") — an advisory lock's effectiveness depends entirely on whether the counterparty takes it, which
is a root-cause question, so it was never actually root-cause-independent.

**Fix — quiesce the async executor(s) before the reset transaction opens:** drain/await
`reportExecutor` (and any sibling `@Async` + `AFTER_COMMIT` writer touching `player_profiles`-adjacent
tables, found during Task 1's investigation) before `beforeTestMethod`'s transactional block
(`:118-124`) starts, so no async writer from a preceding test's committed transaction can still be
in-flight when the reset's `DELETE FROM main.player_profiles` runs. There is currently **no** async-drain
step anywhere in the listener — the sequence today is tx-block → `flushRedis` → `evictInProcessCaches` →
`resetStatefulStubBeans` → `recordCost` (`:126-129`), with no wait on any executor in between. Implementation
options to weigh at implementation time: (a) if `reportExecutor` is a `ThreadPoolTaskExecutor` obtainable
from the Spring context, submit a no-op task and block on its `Future` (or use its queue/active-count
introspection) to confirm it's idle before proceeding; (b) if that's not clean, a short bounded
`Awaitility`-style poll on the executor's active-task count. Confirm which `@Async` executor(s) actually
need draining from Task 1's findings before committing to the exact mechanism — do not guess a second
executor exists without confirming it.

**Rejected alternative, and why:** a bounded retry-on-`40P01` around the reset transaction was
considered (the reset is idempotent test infrastructure — `truncateApplicationTables` +
`restoreReferenceData` reach the same end state on a second attempt, so retry carries none of the
"retry-and-hope" downside that applies to production business logic). Quiescing was chosen instead
because it removes the second concurrent actor directly rather than reducing the odds of colliding with
it — a fix that closes the mechanism, not just lowers its failure rate. If Task 1's investigation finds
draining the executor(s) is impractical for some reason not yet known, fall back to the bounded-retry
shape and say so plainly in the Dev Agent Record, rather than silently reintroducing the advisory-lock
approach this audit ruled out.

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
  skillars-deferred-131-...]` convention used throughout the file's prior sections. All eight bullets
  under `## Deferred from: code review of skillars-deferred-130-... (2026-09-23)` (tail of the file,
  the story's actual source — see the corrected Context section above) map to this story's Fixes 1-8 and
  should close together.
- **Two items inside the code this story restructures three times are NOT yet in the ledger as items
  this story addresses — triage both explicitly, don't let AC5 close silently over them (M5, story-review
  audit):**
  1. **Lock-order inversion `ReviewFlagService.flag` ↔ `GdprErasureService.erase`** (ledger entry at
     `deferred-work.md:3319-3325`). `flag()` takes `coach_reviews` then `coach_profiles` (via
     `CoachRatingService.recompute` → `updateRatingAggregate`); `erase()` takes them in the opposite
     order. Fix 5's projection-guard restructure changes *when* `flag()` first touches each row (the
     coach-profile guard now runs before the `coach_reviews` lock is acquired), but that guard is an
     unlocked projection read — it takes no row lock — so the recorded lock-order cycle is unchanged.
     Record this explicitly in the closeout note; don't leave it silently unaddressed.
  2. **`CoachReviewRepository.findByIdForUpdate` blocking-vs-NOWAIT** (ledger entry at
     `deferred-work.md:3346-3364`, `[DECIDED 2026-09-23 ... accepted for now ... or when the reviews
     module is next opened for a locking change]`). Fix 5 **is** a locking change to the reviews module —
     the decision's own stated revisit trigger fires. Either revisit the NOWAIT conversion now, or record
     explicitly in the closeout note why the trigger doesn't apply here (e.g. Fix 5 only touches guard
     ordering, not the lock discipline itself) — AC5 cannot close out honestly while this sits untouched
     and unmentioned.
- **The deferred-129 code-review section is a separate, older, already-triaged section — not this
  story's source and not something to close from here** (M6, story-review audit; see the corrected
  Context section). Its one open residual relevant to this story's files
  (`ReviewFlagService.java:76`/`ReviewSubmissionService.java` re-typing `getBoundedInt` bounds/keys) is
  config-hygiene, not correctness — record it as still-open, out of this story's scope, rather than
  implying AC5 touched it.
- Add a new `## Last audit: <date> (skillars-deferred-131 dev-story completion)` section, following the
  established format from `skillars-deferred-130`'s own such section, summarizing what was found true,
  what was corrected during implementation (if anything — several items above already flag likely
  implementation-time corrections, e.g. Fix 3's seam design for `validateAllStepsComplete` and Fix 4's
  missing-profile exception type), and what — if anything — remains open (the two M5 items above should
  land here explicitly, not just in Fixes 1-8's closure).
- If the AC4 investigation confirms a different root cause than the hypothesis above, record the
  confirmed mechanism precisely (not the guess) — mirroring `skillars-deferred-123`'s AC5 precedent.
- The ledger's one "N items remain" running-narrative sentence (`deferred-work.md:3237`) is scoped to
  the deferred-129 section, not a global counter — confirmed during story-review audit, so there is
  nothing to hunt for elsewhere; only touch that one sentence if this story's closures actually change
  what it's counting (unlikely, since it's deferred-129-scoped).

---

## Tasks

- [x] **Task 1 (AC1):** Diff-check Fixes 1-4's cited lines against current `HEAD` immediately before
      touching each file (per this project's "diff cited lines before implementation" convention — this
      story's own citations may drift further if any other work lands on `master` between creation and
      implementation).
- [x] **Task 2 (AC1):** Implement Fix 1 (`submitReview` `saveAndFlush`) + its concurrency test.
- [x] **Task 3 (AC1):** Implement Fix 2 (`publishProfile` `statusChangedAt`) + its test.
- [x] **Task 4 (AC1):** Implement Fix 3 on **both** `reinstateCoach` and `deleteStrike`'s tier-3 branch:
      subscription find-or-create + a new `validateAllStepsComplete` seam on `CoachProfileService` called
      before each `ACTIVE` write, plus the `getCoachSubscriptionTier` defensive `SCOUT` default + its
      tests.
- [x] **Task 5 (AC1):** Implement Fix 4 (`syncMarketplaceTier` lock — lock acquisition inside
      `withBoundedRetry`, writes after it returns; missing-profile throws) + new
      `SubscriptionServiceConcurrencyIT`; bump `PessimisticLockRetryerCallSiteAuditTest
      .EXPECTED_CALL_SITE_COUNT` 32 → 33.
- [x] **Task 6 (AC2):** Implement Fix 5 (projection-guard restructure — new scalar queries on
      `CoachReviewRepository`, no unlocked entity pre-read) — re-verify all line numbers in Fixes 6/7/8
      after this restructures the file.
- [x] **Task 7 (AC2):** Implement Fixes 6, 7, 8 + their tests (new `ReviewFlagServiceTest`; one
      Testcontainers IT for Fix 6's real constraint-name shape; Fix 8 covers both `approveReview` and
      `blockReview`).
- [x] **Task 8 (AC3):** Build two deterministic-wait helpers — a `pg_locks` poll for the blocking
      `ReviewFlagServiceConcurrencyIT` site, and a `persistence.lock_retry.retries`-meter poll for the two
      NOWAIT sites (`CoachProfileServiceConcurrencyIT`, the new `SubscriptionServiceConcurrencyIT`).
- [x] **Task 9 (AC4):** Investigate the CI deadlock's actual concurrent actor empirically; document the
      finding regardless of whether it matches this story's hypothesis.
- [x] **Task 10 (AC4):** Implement the async-executor-quiesce fix in `DatabaseResetTestExecutionListener`
      (not an advisory lock — ruled out by story-review audit, see AC4 Context); validate with repeated
      CI/local runs.
- [x] **Task 11 (AC5):** Ledger closeout in `deferred-work.md`, including the two M5 items and the M6
      scope correction noted in AC5 above.
- [x] **Task 12:** Full targeted regression sweep (at minimum: `platform.reviews.**`,
      `platform.marketplace.**`, `platform.payment.**` [for `SubscriptionService`],
      `platform.admin.**` [for `AdminCoachEnforcementService`/`AdminReviewService`],
      `platform.development.**` [for the CI deadlock's suspected actor], plus
      `PessimisticLockRetryerCallSiteAuditTest` [Fix 4 adds the 33rd call site — the count bump is
      already known, not conditional]). No `mvn verify` run locally per this project's standing
      convention (`docs/validation-strategy.md`) — GitHub CI is the sole full-verification gate.

### Review Findings

_Source: `/bmad-code-review` 2026-09-23 — four parallel layers (Blind Hunter, Edge Case Hunter,
Acceptance Auditor, `/txn-and-concurrency-audit`). 58 raw findings → 33 unique after dedup → 8
dismissed → 25 actionable (3 decision-needed). Acceptance Auditor verdict: **all 8 Fixes IMPLEMENTED with the
`story-review.md`-corrected mechanisms honoured**; AC3 and AC4 partial. Findings below are about
whether the shipped code does what it claims, not about missing implementation.
**All 20 actionable items (3 Decisions + 17 Patches) resolved 2026-09-23 — see inline notes on each: 15
fixed, 2 investigated and confirmed false positives (documented with the reasoning that dismisses
each), all verified against a live Testcontainers Postgres run where the finding concerned test/lock
timing. The 5 already-`[Review][Defer]`red items below were left as previously decided.**

- [x] [Review][Patch] **IMPLEMENTED 2026-09-23.** **`deleteStrike`'s new validation rolls back the strike deletion itself** [src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java:400,489] — **RESOLVED 2026-09-23 (code review, Decision 1): keep the delete, skip the activation.** Wrap the tier-3 activation block in `try { ... } catch (MarketplaceException e)`; on failure keep the strike deletion, leave the coach at its current status, WARN naming the incomplete step, and write the `COACH_STRIKE_DELETED` log row with reason "Strike deleted (no status change, profile incomplete)". Rationale: this is the exact shape the tier-1 branch already uses, and it restores `deferred-122 AC1 Fix step 4`'s stated invariant — "only the log row below, which every deleteStrike call writes on every branch" — which the rollback silently broke. Catching the specific `MarketplaceException` satisfies `project-context.md` (only generic `catch (Exception)` is banned). `reinstateCoach` is NOT changed by this item: there the admin explicitly asked for ACTIVE, nothing prior is lost, and failing loudly is correct — see Decision 2 for its separate treatment.
- [x] [Review][Patch] **IMPLEMENTED 2026-09-23.** **A SUSPENDED coach with no availability windows is permanently un-reinstatable** [src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java:241] — **RESOLVED 2026-09-23 (code review, Decision 2): reinstate incomplete profiles to DRAFT.** In `reinstateCoach`, catch `MarketplaceException` from `validateReadyForActivation` and write `CoachProfileStatus.DRAFT` instead of `ACTIVE`, with a distinct WARN. Rationale: the trap's actual cause is that nothing writes DRAFT back, so the coach can never reach a status from which `saveStep4` or `publishProfile` are permitted. Reinstating to DRAFT closes it at the cause, returns the coach to a self-serviceable onboarding state, and leaves `deferred-64 AC1`'s deliberate `saveStep4` SUSPENDED guard (mirroring `RescheduleService`/`BookingDuplicationService`) fully intact. An incomplete profile was never legitimately ACTIVE, and suspension side effects (cancelled bookings) already applied at suspend time and are not undone by reinstate either way. Rejected: a force-reinstate override (new endpoint/DTO/`@PreAuthorize`/UI — scope creep, and it reintroduces the silent-ACTIVE state Fix 3 exists to prevent); relaxing `saveStep4`'s guard (reverses a dated, reasoned security decision).
- [x] [Review][Patch] **IMPLEMENTED 2026-09-23.** **Fix 4's lock newly contends with the booking hot path; a contended `subscribeCoach` loses the payment row after Stripe has charged** [src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java:167] — **RESOLVED 2026-09-23 (code review, Decision 3): catch and defer the marketplace sync.** In `persistCoachSubscription`, wrap `syncMarketplaceTier(coachId, tier)` in `catch (PessimisticLockingFailureException e)` and log `[COACH_SUBSCRIBE_TIER_SYNC_DEFERRED coachId={} tier={}]` for reconciliation, so the payment row commits. Rationale: `subscribeCoach` calls Stripe deliberately outside the transaction (`SubscriptionService.java:145`), so a rollback here means a live charge with no billing record — the worst available outcome. `marketplace.coach_subscriptions` is a derived projection already re-synced by `applyPendingChanges:471` and `checkPastDueGracePeriod:538`, and `getCoachSubscriptionTier`'s new `SCOUT` default makes a temporarily stale tier fail closed. `PessimisticLockingFailureException` is specific, satisfying `project-context.md`. **The two scheduler loops are deliberately left unchanged** — their per-item `catch` already leaves failed rows eligible for the next run by `deferred-116 AC2`'s design, so the residual there is run duration and pool occupancy, not correctness; the batch-size × ~3.2s arithmetic is recorded as a follow-up rather than patched here.
- [x] [Review][False positive] `SubscriptionServiceConcurrencyIT`'s contention proof is structurally unobtainable — the syncer blocks in the FK `FOR KEY SHARE`, never reaching the NOWAIT lock [src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionServiceConcurrencyIT.java:166] — **VERIFIED FALSE 2026-09-23**: ran the test live against Testcontainers Postgres three times; each run logged exactly the claimed mechanism working as designed — three genuine `55P03`/`PessimisticLockingFailureException` retries on `syncMarketplaceTier`'s NOWAIT `findByIdForUpdate` while the publisher's lock was held, then success once released (surefire: `Tests run: 1, Failures: 0`). The pending `PaymentCoachSubscription` insert does not block the retry loop's own `entityManager.flush()` the way theorized; empirically the NOWAIT path is what's exercised, not the FK wait. No change made.
- [x] [Review][Patch] **IMPLEMENTED 2026-09-23.** `quiesceReportExecutor` drains 1 of 6 DB-writing async pools; `taskExecutor` (`TimelineEventListener`, `SluCalculationService`) also takes FK `FOR KEY SHARE` on `player_profiles` [src/test/java/com/softropic/skillars/config/DatabaseResetTestExecutionListener.java:192-202] — renamed to `quiesceAsyncExecutors` and generalized to drain every `ThreadPoolTaskExecutor` bean in the context (`ctx.getBeansOfType(...)`), not a hardcoded name, so all six pools (`taskExecutor`, `reportExecutor`, `moderationTaskExecutor`, `outboxDrainPool`, `sendMailPool`, `sluRetryExecutor`) are covered and any future pool is covered automatically.
- [x] [Review][Patch] **IMPLEMENTED 2026-09-23.** Quiesce timeout throws out of `beforeTestMethod` *above* the reset transaction, leaving the DB dirty for the failing test and all subsequent ones [src/test/java/com/softropic/skillars/config/DatabaseResetTestExecutionListener.java:114] — each executor's `ConditionTimeoutException` is now caught individually and logged (`System.err`), and the reset transaction always proceeds afterward, so a single slow-to-drain pool can no longer skip the reset for the rest of the JVM.
- [x] [Review][Patch] **IMPLEMENTED 2026-09-23.** `awaitFirstLockAttempt` halves the sleep it replaced (300ms → 150ms) while converting the previously-tolerated outcome into a hard assertion — new flake surface in 2 ITs [src/test/java/com/softropic/skillars/utils/ConcurrencyLockWaitSupport.java:52,58-60] — restored to 300ms (the value already proven reliable), keeping the new hard `assertGenuineLockRetryOccurred` assertion.
- [x] [Review][Patch] **IMPLEMENTED 2026-09-23.** `getCoachSubscriptionTier`'s new `orElse(SCOUT)` default has no `log.warn` — removes the only loud signal that a paying coach's marketplace row is missing [src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java:509-513] — added `@Slf4j` to the class and a `log.warn` naming the `coachId` on the missing-row default.
- [x] [Review][Patch] **IMPLEMENTED 2026-09-23.** `concurrentSubmit…` and `concurrentDuplicateFlag…` can both pass on a reverted fix — if the threads serialise, the unlocked pre-check throws and the DIVE branch never runs [src/test/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionServiceConcurrencyIT.java:98, ReviewFlagServiceConcurrencyIT.java:453] — rewrote both to hold the winner's transaction open (uncommitted) past its own return, mirroring `CoachProfileServiceConcurrencyIT`'s hold-open technique, forcing the loser's unlocked pre-check to run first and its `saveAndFlush` to genuinely race the real DB constraint. Re-ran both live: logs confirm the real `23505` `uq_coach_reviews_author_coach` and `review_flags_unique_flagger` violations are now deterministically hit (surefire: both green).
- [x] [Review][Patch] **IMPLEMENTED 2026-09-23.** `AdminReviewServiceTest`'s `.contains("3")`/`.contains("2")` are vacuous — the random `REVIEW_ID` UUID already in the message contains the digit ~87% of the time (verified) [src/test/java/com/softropic/skillars/platform/admin/service/AdminReviewServiceTest.java:89-90,137-138] — changed to `.contains("3 flag(s)")`/`.contains("2 flag(s)")`, an unambiguous substring the log format actually produces.
- [x] [Review][Patch] **IMPLEMENTED 2026-09-23.** `submitReview`'s blanket `catch (DataIntegrityViolationException)` now mislabels every constraint failure as ALREADY_SUBMITTED — the same reasoning Fix 6 applied two files over was not applied here [src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java:69-74] — added `isAlreadySubmittedViolation`, mirroring `ReviewFlagService.isUniqueFlaggerViolation` exactly: only a `uq_coach_reviews_author_coach` constraint-name match maps to `ALREADY_SUBMITTED`; anything else rethrows.
- [x] [Review][Patch] **IMPLEMENTED 2026-09-23.** `publishProfile_setsStatusChangedAtToFreshTimestamp`'s `minusSeconds(5)` window admits the row's own creation time, so it cannot test what its `as(...)` claims [src/test/java/com/softropic/skillars/platform/marketplace/service/CoachProfileServiceConcurrencyIT.java:261-263] — capture moved to immediately before the `publishProfile` call, no cushion (both run in the same JVM/clock, so none is needed).
- [x] [Review][Patch] **IMPLEMENTED 2026-09-23 (partial, reasoned).** `awaitBlockingLockWaiter`'s `pg_locks` probe has no database, PID, relation or lock-mode filter — can return early on an unrelated waiter [src/test/java/com/softropic/skillars/utils/ConcurrencyLockWaitSupport.java:72-83] — added `a.datname = current_database()`. A relation/lock-mode filter was deliberately NOT added: a session blocked on a row `findByIdForUpdate` already holds waits on the LOCKING TRANSACTION's id (`locktype = 'transactionid'`), not on a lock keyed to the relation, so there is no relation-scoped lock row to filter on — documented inline rather than adding an incorrect filter.
- [x] [Review][False positive] `syncMarketplaceTier`'s comment misattributes what the lock closes — the INSERT branch it names is already serialised by the pre-existing blocking FK lock; the lock is load-bearing on the UPDATE paths instead [src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java:686-707] — **ASSESSED FALSE 2026-09-23**: the pre-existing FK `FOR KEY SHARE` only engages at the physical INSERT statement (flush time), which is *after* the Java-level `findByCoachId` decision to insert has already been made on stale (unlocked) data — so the FK lock delays the INSERT's execution but does not prevent the PK violation the comment describes; it still occurs once the FK wait unblocks. The new explicit lock's actual value is exactly what the comment claims: it moves the lock acquisition *before* the `findByCoachId` decision point, so a re-check after the lock correctly sees the other writer's committed row and takes the UPDATE branch instead. The comment's causal story holds up; no change made. (Confirmed separately by the passing `SubscriptionServiceConcurrencyIT` reproduction above.)
- [x] [Review][Patch] **IMPLEMENTED 2026-09-23.** `clearAutomatically = true` comment says "detaches any managed ReviewFlag"; Spring Data implements it as `entityManager.clear()`, detaching the whole context including the just-locked `CoachReview` [src/main/java/com/softropic/skillars/platform/reviews/repo/ReviewFlagRepository.java:22-26] — comment corrected to state the whole-context detach and name why it's still harmless today (no mapped associations) plus the future risk (a later lazy-association access would `LazyInitializationException`, not stale-read).
- [x] [Review][Patch] **IMPLEMENTED 2026-09-23.** WARN text says flags were "silently resolved" in the very log line that makes it non-silent — reword to "auto-resolved" [src/main/java/com/softropic/skillars/platform/admin/service/AdminReviewService.java:100,118] — reworded both `approveReview` and `blockReview`'s WARN lines.
- [x] [Review][Patch] **IMPLEMENTED 2026-09-23.** `recordCost` now charges up to 10s of async-executor waiting to the database-reset cost metric — move `startNanos` below the quiesce call [src/test/java/com/softropic/skillars/config/DatabaseResetTestExecutionListener.java:109-114] — `startNanos` moved to after `quiesceAsyncExecutors(ctx)`.
- [x] [Review][Patch] **IMPLEMENTED 2026-09-23.** Three new Mockito test classes use manual positional constructors (14-arg and 6-arg) where every sibling uses `@InjectMocks` — the exact brittleness story-review F13 verified was absent [src/test/java/…/CoachProfileServiceTest.java, AdminReviewServiceTest.java, ReviewFlagServiceTest.java] — all three converted to `@InjectMocks`; all field types are distinct so constructor resolution is unambiguous. All three test classes re-run green.
- [x] [Review][Patch] **IMPLEMENTED 2026-09-23.** Duplicated code: the 9-line `CoachSubscription` find-or-create appears verbatim in two branches of one class, and `seedCompleteBuilderSteps` is copy-pasted across three IT classes [src/main/java/…/AdminCoachEnforcementService.java:242-256,491-503; ManualStrikeIT/ReinstateIT/AdminCoachEnforcementConcurrencyIT] — extracted `AdminCoachEnforcementService.ensureScoutSubscriptionExists(UUID)`; extracted `com.softropic.skillars.utils.CoachProfileTestFixtures.seedCompleteBuilderSteps(JdbcTemplate, UUID)`, a new shared test utility, and updated all three IT classes to call it.
- [x] [Review][Patch] **IMPLEMENTED 2026-09-23.** `SubscriptionServiceConcurrencyIT`'s javadoc claims a tier outcome holds "regardless of which writer wins the lock", but the test gates the syncer on `publishLockHeld` so the reverse ordering is never exercised [src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionServiceConcurrencyIT.java:28-34,178-188] — javadoc corrected to disclose that only the publish-first ordering is tested; the reverse-ordering claim now explicitly rests on the code-reading argument, not on this test. A full bidirectional-ordering IT is not added (higher-effort, lower-value given the code-reading argument already holds) — named here as a scope decision, not silently dropped.
- [x] [Review][Defer] `assertGenuineLockRetryOccurred` reads a JVM-wide cumulative counter, so cross-test async retries can satisfy it [src/test/java/com/softropic/skillars/utils/ConcurrencyLockWaitSupport.java:104-109] — deferred, requires meter tagging in `PessimisticLockRetryer` (production, out of scope)
- [x] [Review][Defer] `submitReview`'s caught DIVE leaves the physical transaction rollback-only; the first caller opening an outer transaction converts a clean 4xx into `UnexpectedRollbackException` [src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java:69] — deferred, no current violator; constraint lives only in a test javadoc
- [x] [Review][Defer] `PessimisticLockRetryerCallSiteAuditTest` matches regexes against raw lambda source and audits `withBoundedRetry` sites rather than `findByIdForUpdate` sites, so a new unwrapped NOWAIT lock is invisible to it [src/test/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryerCallSiteAuditTest.java:80-90,103] — deferred, pre-existing weakness; this story's new site is correctly covered
- [x] [Review][Defer] New tests hand-build entities instead of using Instancio, against `project-context.md`'s Testing Rules [src/test/java/…/AdminReviewServiceTest.java, ReviewFlagServiceTest.java, PastDueGracePeriodTest.java] — deferred, consistent with wider codebase practice; convention drift, not this change's fault
- [x] [Review][Defer] AC4 Task 1's empirical requirement ("investigate empirically… do not implement a fix that assumes it without verifying first") was met with a grep-level structural reading; neither named option (diagnostic logging, `pg_stat_activity` capture at a reproduced deadlock) was attempted — deferred, honestly disclosed in the story and ledger; ties to the single-pool quiesce patch above

**Dismissed as noise (8):** Fix 4's lock claimed inert via `REQUIRES_NEW` (wrong — `PessimisticLockRetryer` uses savepoints in the caller's transaction); `SubscriptionSchedulerIsolationTest` stubs needing `lenient()` (**verified false** — all 5 tests route a succeeding coach through `syncMarketplaceTier` at `SubscriptionService:471`/`:538`, so both stubs are consumed); `PaymentGatewayException` mis-mapping for a missing profile (near-unreachable — the FK guarantees the parent row; inline FQN matches this file's own 30 existing usages); `INVALID_FLAGGER` missing a message-bundle key (verified — no `reviews.*` keys exist, no i18n wiring on this path); `isUniqueFlaggerViolation`'s single-level unwrap (byte-for-byte mirror of the verified `SluPersistenceRetrier.java:181-185` house pattern, and the IT pins the real 23505 shape); `validateReadyForActivation` being a pure pass-through (the *named* public wrapper was explicitly prescribed by `story-review.md` over visibility widening); AC4 having no revert-detecting test (story-sanctioned — "no unit-testable assertion for 'does not deadlock'"); AC5 absent from the diff (informational — the `src/`-only review scope excluded it; verified complete in the working tree).

## Dev Notes

- **This story went through a full pre-implementation `story-review.md` senior-dev audit (2026-09-23,
  `/story-review` skill) before being handed to `dev-story`** — unlike its own first draft's Dev Notes
  claimed would still be needed. The audit found and this story now incorporates fixes for: four blocking
  mechanism errors (B1: Fix 5's original plan reintroduced the exact stale-read bug deferred-130 closed;
  B2: AC4's original advisory-lock mechanism cannot work against its hypothesized counterparty; B3: a
  single `pg_locks` signal cannot observe both this codebase's lock disciplines; B4: Fix 4's original code
  shape would have broken `PessimisticLockRetryerCallSiteAuditTest` and violated `withBoundedRetry`'s own
  contract), six major findings (M1-M6, see Context sections and AC5 above), and numerous citation/factual
  corrections (some of which corrected the *audit's own* miscounted lines in turn — see Fix 8's Context,
  which flags where the audit itself was wrong). Every "confirm at implementation time" caveat that
  remains below (constructor-injection risk, error-code shape for Fix 7, the exact form of Fix 5's
  projection queries) was independently re-checked during the audit and left open only because it's a
  genuine implementation-time judgment call, not because it was unverified.
- Fixes 3 and 4 both add new constructor dependencies to existing `@RequiredArgsConstructor` Spring
  beans (`AdminCoachEnforcementService` gains `CoachSubscriptionRepository`; `SubscriptionService` gains
  `CoachProfileRepository` and `PessimisticLockRetryer`). No manual-constructor test instantiations of
  either class were found as of this story's creation (`grep -rn "new AdminCoachEnforcementService(\|new
  SubscriptionService("` under `src/test`) — re-check before implementing, since a manual instantiation
  would need updating and would not fail loudly (a missing constructor arg is a compile error, so this
  is actually safe by construction — noted for completeness, not as a real risk).
- Fix 3's `validateAllStepsComplete` seam crosses a service boundary that doesn't otherwise exist
  (`AdminCoachEnforcementService` calling into `CoachProfileService`) — decide the exact shape
  (package-private widening vs. a new public wrapper method) at implementation time, and decide whether
  its `MarketplaceException` throw type needs translating for `AdminCoachEnforcementService`'s callers.
- AC4's fix touches `DatabaseResetTestExecutionListener`, which runs before **every** test method across
  the entire suite (~135 IT classes, ~905 test methods per the file's own Javadoc). Treat this as the
  highest-blast-radius change in this story and validate accordingly — a full CI run (not just a targeted
  package sweep) is required before this AC can be considered done, since its whole point is suite-wide
  reset behavior.
- No frontend changes anticipated — confirm via `git status --short` before opening the PR, per this
  project's established `frontend-tests` label convention.

## Dev Agent Record

### Completion Notes

**AC1 — Marketplace/Reviews Correctness Fixes (Fixes 1-4).** All four implemented per the story's
corrected shapes. Fix 1: `submitReview` now `saveAndFlush`es inside its `try`; new
`ReviewSubmissionServiceConcurrencyIT` proves the loser of a concurrent double-submit gets
`ALREADY_SUBMITTED`, not an uncaught flush-time failure — the test deliberately makes neither call the
outer boundary of a `TransactionTemplate`, per the story's own trap warning. Fix 2: `publishProfile` now
sets `statusChangedAt`, pinned by a new `CoachProfileServiceConcurrencyIT` test. Fix 3: implemented on
both `reinstateCoach` and `deleteStrike`'s tier-3 branch — a `CoachSubscription` find-or-create, a new
`CoachProfileService.validateReadyForActivation` public seam (widened from `private
validateAllStepsComplete`) run under the lock already held before each `ACTIVE` write, and a defensive
`getCoachSubscriptionTier` default to `SCOUT` instead of throwing. This is a genuine behavior change: an
admin reinstate/strike-delete on an incomplete profile now fails with `MarketplaceException` instead of
silently activating it — `ReinstateIT` and `ManualStrikeIT`'s shared coach fixtures needed complete
builder-step data added (specialties/age-groups/pricing/availability) so their existing happy-path
assertions kept passing, and their `tearDown()`s needed a `coach_subscriptions` delete added ahead of the
now-FK-referenced `coach_profiles` delete. Six new `AdminCoachEnforcementConcurrencyIT` tests cover the
validation-rejection, fresh-SCOUT, and existing-tier-preserved cases for both methods. Fix 4:
`SubscriptionService.syncMarketplaceTier` now takes the same `coach_profiles` row lock `publishProfile`
already takes (lock acquisition alone inside `PessimisticLockRetryer.withBoundedRetry`, writes after it
returns — verified against `PessimisticLockRetryerCallSiteAuditTest`'s DENYLIST, whose
`EXPECTED_CALL_SITE_COUNT` moved 32→33); new `SubscriptionServiceConcurrencyIT` proves neither side
throws uncaught and the final tier is deterministic regardless of which writer wins. Fix 4 closes only
the *common* (colliding) case, not the rarer sustained-contention Stripe-orphan risk — recorded honestly
in the ledger, not overclaimed.

**AC2 — `ReviewFlagService` Hygiene Sweep (Fixes 5-8).** Fix 5: the four write-independent guards
(self-flag, missing coach profile, coach-flags-own-profile, `ALREADY_FLAGGED`) now run against a new
unlocked scalar projection (`CoachReviewRepository.findAuthorAndCoachIdByReviewId`, returning
`List<Object[]>` — an `Optional<Object[]>` return type was tried first and does not unwrap correctly
against Hibernate 6's tuple result, a genuine implementation-time correction caught by the concurrency
test suite) before the row lock, so `findByIdForUpdate` remains genuinely the transaction's first entity
load. Fix 6: the `DataIntegrityViolationException` catch now checks for
`review_flags_unique_flagger` by name before mapping to `ALREADY_FLAGGED`, mirroring
`SluPersistenceRetrier.isSessionSkillUniqueViolation`'s established pattern; pinned by both a Mockito
negative-branch test and a new Testcontainers IT racing two genuine duplicate flags. Fix 7: a null
`flaggedBy` now throws `OperationNotAllowedException`/new `ReviewErrorCode.INVALID_FLAGGER` instead of
NPE-ing. Fix 8: `ReviewFlagRepository.resolveAllOpenFlags` now returns the row count its own
`@Modifying` update already computes; both `approveReview` and `blockReview` WARN when that count is
non-zero. New `ReviewFlagServiceTest` (Mockito) and `AdminReviewServiceTest` (Mockito, log-capture via
`ListAppender`) cover Fixes 6/7 and Fix 8 respectively — neither test class existed before this story.

**AC3 — Concurrency IT Determinism.** New shared `com.softropic.skillars.utils.ConcurrencyLockWaitSupport`
provides `awaitBlockingLockWaiter` (a live `pg_locks`/`pg_stat_activity` poll) for the blocking-`FOR
UPDATE` discipline (`ReviewFlagServiceConcurrencyIT`), and `awaitFirstLockAttempt` +
`assertGenuineLockRetryOccurred` for the NOWAIT discipline (`CoachProfileServiceConcurrencyIT`,
`SubscriptionServiceConcurrencyIT`). **Implementation-time correction:** the story's own suggested
pre-release gate — `Awaitility.await().until(() -> retriesCounter.count() > 0)` — does not work against
`PessimisticLockRetryer`'s real implementation: `recordRetries(...)` fires only once the whole retry loop
CONCLUDES (success or exhaustion), not per failed attempt, so gating a lock-holder's release on it either
returns immediately (a stale JVM-wide value) or blocks until the contender has already exhausted its
budget — confirmed the hard way (first attempt caused `CoachProfileServiceConcurrencyIT`'s
`concurrentSuspend_isNotRevertedByPublish` and `SubscriptionServiceConcurrencyIT` to release the holder's
lock only AFTER the contender had already given up, surfacing exactly the uncaught
`PessimisticLockingFailureException` the fix was supposed to prevent). Corrected to a bounded
pre-release delay plus a real post-hoc assertion, run after both threads join.

**AC4 — CI Database-Reset Deadlock.** Task 1 (investigate): confirmed the story's own hypothesis
structurally, not via literal reproduction — `RadarCompositeCalculationService.onRadarEntrySubmitted` and
`ReportGenerationService`'s own equivalent `@Async("reportExecutor")` +
`@TransactionalEventListener(AFTER_COMMIT)` listener share one `ThreadPoolTaskExecutor` and both touch
`player_profiles`-adjacent state; `DatabaseResetTestExecutionListener`'s single reset transaction has no
fixed cross-table delete order and can collide with either if one is still in flight from a preceding
test's committed transaction. The race itself was not reproduced locally (first occurrence in ~14 prior
green master runs; genuinely non-deterministic). Task 2 (fix): `quiesceReportExecutor` bounded-polls
`reportExecutor`'s active-count and queue down to zero before the reset transaction opens. Validated by
running the full `platform.development.**` package (231 tests, including `RadarAssessmentResourceIT` —
the class the original CI failure occurred ahead of) clean, twice.

**AC5 — Ledger Closeout.** All ten relevant `deferred-work.md` bullets (8 under the code-review-of-130
section, 2 under the story-review-of-130 section duplicating Fixes 1/2) now carry
`[CLOSED by skillars-deferred-131 ...]` notes. The two M5 items (lock-order inversion; the NOWAIT-decision
revisit trigger) are explicitly triaged as still-open, not silently closed over. The M6 residual
(deferred-129 section, `getBoundedInt` re-typing) is recorded as out-of-scope, untouched. A new
`## Last audit: 2026-09-23` section summarizes all of the above plus what remains open after this story.

**Validation.** No `mvn verify` run locally, per this project's standing `docs/validation-strategy.md`
convention — GitHub CI is the sole full-verification gate. Locally ran targeted Maven test selections
(Docker/Testcontainers available in this environment): all new/changed unit tests (`ReviewFlagServiceTest`,
`AdminReviewServiceTest`, `CoachProfileServiceTest`, `SubscriptionSchedulerIsolationTest`,
`PastDueGracePeriodTest`, `PessimisticLockRetryerCallSiteAuditTest`) and all new/changed concurrency ITs
pass; full package sweeps of `platform.reviews.**` (55), `platform.marketplace.**` (70),
`platform.payment.**` (345), `platform.admin.**` (166), `platform.development.**` (231), and
`platform.session.**`/`platform.video.**` (548) all pass with zero failures. Frontend unchanged
(confirmed via `git status --short`), so no `frontend-tests` label needed on the PR.

### File List

**Production code:**
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java` (Fix 1)
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java` (Fix 2,
  Fix 3 seam, Fix 3 belt-and-braces default)
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java` (Fix 3)
- `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java` (Fix 4)
- `src/main/java/com/softropic/skillars/platform/reviews/repo/CoachReviewRepository.java` (Fix 5
  projection query)
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewFlagService.java` (Fixes 5, 6, 7)
- `src/main/java/com/softropic/skillars/platform/reviews/contract/ReviewErrorCode.java` (Fix 7 —
  `INVALID_FLAGGER`)
- `src/main/java/com/softropic/skillars/platform/reviews/repo/ReviewFlagRepository.java` (Fix 8 —
  `resolveAllOpenFlags` returns `int`)
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminReviewService.java` (Fix 8 — WARN
  logging)
- `src/test/java/com/softropic/skillars/config/DatabaseResetTestExecutionListener.java` (AC4 quiesce fix)

**Post-implementation code review pass (2026-09-23) — additional production files touched:**
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java`
  (Decisions 1 & 2 — catch `MarketplaceException` in `deleteStrike`'s tier-3 branch and
  `reinstateCoach`; extracted `ensureScoutSubscriptionExists`)
- `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java`
  (Decision 3 — catch `PessimisticLockingFailureException` around `syncMarketplaceTier` in
  `persistCoachSubscription`)
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java`
  (`@Slf4j` + `log.warn` on `getCoachSubscriptionTier`'s missing-row default)
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java`
  (`isAlreadySubmittedViolation` constraint-name guard on the DIVE catch)
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminReviewService.java` ("silently
  resolved" → "auto-resolved" wording)
- `src/main/java/com/softropic/skillars/platform/reviews/repo/ReviewFlagRepository.java`
  (`clearAutomatically` comment accuracy)

**New test files:**
- `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionServiceConcurrencyIT.java`
- `src/test/java/com/softropic/skillars/platform/marketplace/service/CoachProfileServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionServiceConcurrencyIT.java`
- `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewFlagServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/admin/service/AdminReviewServiceTest.java`
- `src/test/java/com/softropic/skillars/utils/ConcurrencyLockWaitSupport.java`
- `src/test/java/com/softropic/skillars/utils/CoachProfileTestFixtures.java` (code review pass —
  extracted from 3-way duplication)

**Modified test files:**
- `src/test/java/com/softropic/skillars/platform/marketplace/service/CoachProfileServiceConcurrencyIT.java`
  (Fix 2 test; AC3 lock-wait signal)
- `src/test/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementConcurrencyIT.java`
  (Fix 3 tests — 6 new)
- `src/test/java/com/softropic/skillars/platform/admin/api/ReinstateIT.java` (Fix 3 fixture completeness
  + FK-safe teardown)
- `src/test/java/com/softropic/skillars/platform/admin/api/ManualStrikeIT.java` (Fix 3 fixture
  completeness + FK-safe teardown)
- `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewFlagServiceConcurrencyIT.java`
  (Fix 5/6 tests — 2 new; AC3 lock-wait signal on 2 existing tests)
- `src/test/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryerCallSiteAuditTest.java`
  (Fix 4 — `EXPECTED_CALL_SITE_COUNT` 32→33)
- `src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionSchedulerIsolationTest.java`
  (Fix 4 — new `coachProfileRepository`/`lockRetryer` mocks)
- `src/test/java/com/softropic/skillars/platform/payment/service/PastDueGracePeriodTest.java` (Fix 4 —
  same)

**Post-implementation code review pass (2026-09-23) — additional test files touched:**
- `src/test/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementConcurrencyIT.java`
  (renamed/updated `reinstateCoach_incompleteProfile_...`/`deleteStrike_incompleteProfile_...` for
  Decisions 1/2's new behavior; `seedCompleteBuilderSteps` now delegates to
  `CoachProfileTestFixtures`)
- `src/test/java/com/softropic/skillars/platform/admin/api/ManualStrikeIT.java` /
  `src/test/java/com/softropic/skillars/platform/admin/api/ReinstateIT.java` (same
  `CoachProfileTestFixtures` delegation)
- `src/test/java/com/softropic/skillars/platform/marketplace/service/CoachProfileServiceConcurrencyIT.java`
  (`publishProfile_setsStatusChangedAtToFreshTimestamp`'s timestamp-cushion fix)
- `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionServiceConcurrencyIT.java`
  / `ReviewFlagServiceConcurrencyIT.java` (rewrote the duplicate-race tests to hold the winner's
  transaction open, forcing a genuine DB-level race)
- `src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionServiceConcurrencyIT.java`
  (javadoc accuracy — discloses only the publish-first ordering is tested)
- `src/test/java/com/softropic/skillars/utils/ConcurrencyLockWaitSupport.java` (`awaitFirstLockAttempt`
  restored to 300ms; `awaitBlockingLockWaiter` gained a `datname` filter)

All re-run live against Testcontainers Postgres after the changes above; every affected class green
(`AdminCoachEnforcementConcurrencyIT`, `ManualStrikeIT`, `ReinstateIT`,
`CoachProfileServiceConcurrencyIT`, `ReviewSubmissionServiceConcurrencyIT`,
`ReviewFlagServiceConcurrencyIT`, `SubscriptionServiceConcurrencyIT`, `AdminReviewServiceTest`,
`CoachProfileServiceTest`, `ReviewFlagServiceTest`, `PastDueGracePeriodTest`,
`SubscriptionSchedulerIsolationTest`, `PessimisticLockRetryerCallSiteAuditTest`), plus a full sweep of
`platform.reviews.**`, `platform.admin.**`, `platform.marketplace.**`, `platform.payment.**` (375 test
classes, 0 failures) and `platform.development.**` (async-executor quiesce generalization).

**Ledger:**
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC5 closeout — additive only)

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
  investigation + structural fix), AC5 (standard ledger closeout). Story file:
  `skillars-deferred-131-marketplace-reviews-hardening-and-ci-reset-deadlock-fix.md`. Branch:
  `story/deferred-131-marketplace-hardening`.

- 2026-09-23: **Pre-implementation `story-review.md` senior-dev audit** (`/story-review` skill) run
  against this story before handing it to `dev-story`. Verdict was "do not hand off as written" — every
  cited file was re-opened and every line number re-counted against source. Findings, all independently
  re-verified against source in this pass before acting on them:

  - **4 blocking mechanism errors, corrected:** Fix 5's originally-proposed two-read restructure would
    have reintroduced the exact Hibernate identity-map stale-read bug `skillars-deferred-130` AC1 Fix 1
    closed (fixed: projection guards instead, owner decision); AC4's originally-proposed
    `pg_advisory_xact_lock` cannot serialize against its hypothesized counterparty, since that counterparty
    would never take the same advisory lock (fixed: quiesce the async executor(s) instead, owner
    decision); AC3's single `pg_locks`-poll signal cannot work across this codebase's two different lock
    disciplines — blocking `FOR UPDATE` vs. NOWAIT+retry (fixed: two signals, one per discipline); Fix 4's
    originally-proposed code shape ("wrap the body") would have broken
    `PessimisticLockRetryerCallSiteAuditTest`'s DENYLIST and violated `withBoundedRetry`'s own read-only
    contract (fixed: lock acquisition only inside the retried lambda, writes after it returns).
  - **6 major findings, addressed:** Fix 3's scope-narrowing (dropping `validateAllStepsComplete`) rested
    on a premise `CoachProfileService.java:355-362`'s own javadoc contradicts — restored, via a new seam
    (owner decision); the identical gap exists in `deleteStrike`'s tier-3 branch, not just
    `reinstateCoach` — extended to cover both (owner decision); `getCoachSubscriptionTier`'s throw is one
    of 10 call sites the missing-subscription gap breaks, not just the two write paths Fix 3 touches —
    added a defensive `SCOUT` default as belt-and-braces; Fix 4 changes which exception causes the
    Stripe-orphan risk rather than removing it — now stated honestly rather than overclaimed; two ledger
    items inside code this story restructures (`ReviewFlagService`/`GdprErasureService` lock-order
    inversion; `CoachReviewRepository`'s blocking-lock decision's own revisit trigger) were neither closed
    nor mentioned — both now explicitly triaged in AC5; the story had mis-identified which
    `deferred-work.md` sections it sourced from (two further sections were appended after the ones
    named) — corrected in Context, with the actually-newest section's one residual item recorded as
    out-of-scope rather than silently unaddressed.
  - **Citation corrections:** several line numbers drifted or were miscounted in the first draft (Fixes 1,
    3, 8) — re-verified and corrected throughout. One correction reversed itself: the audit's own claim
    that `AdminReviewService.approveReview`'s `resolveAllOpenFlags` call sits at `:95` was itself wrong on
    direct re-verification — it is at `:94`, matching both the ledger and this story's original citation;
    only the `log.info` line citation (`:104` → `:106`) needed correcting. Recorded here so a future reader
    doesn't treat the audit's line numbers as infallible either.
  - Numerous minor factual corrections folded in throughout (wrong constraint/index names, a non-existent
    test class referenced as "extend," `PRO`/`ELITE` used for what are actually player-only tiers, an
    `Awaitility` precedent citation that pointed at a class that doesn't use it, an undercount of existing
    `Thread.sleep` call sites) — see each Fix's Context section for the corrected version; not re-listed
    exhaustively here.
  - **4 further owner decisions taken live (AskUserQuestion)** to resolve the genuine design choices the
    audit surfaced, all following the "Recommended" option: (1) Fix 3 — run `validateAllStepsComplete`
    via a new seam rather than accept the residual risk; (2) Fix 3 — extend to `deleteStrike`'s identical
    gap rather than `reinstateCoach` only; (3) Fix 5 — projection guards rather than refresh-under-lock;
    (4) AC4 Task 2 — quiesce the async executor(s) rather than retry-on-deadlock.

- 2026-09-23: **Implementation complete** (`/bmad-dev-story`). All 12 tasks / 5 ACs done; see Dev Agent
  Record → Completion Notes for the full per-AC summary, including three implementation-time corrections
  discovered while implementing (not anticipated by either the first draft or the pre-implementation
  audit): (1) Fix 5's projection query needed `List<Object[]>`, not `Optional<Object[]>` — the latter
  does not unwrap correctly against Hibernate 6's tuple result, caught by the concurrency test suite via
  a `ClassCastException`; (2) AC3's suggested `retriesCounter.count() > 0` pre-release poll cannot work
  against `PessimisticLockRetryer`'s real implementation, since its counter only updates once a retry
  sequence concludes, not per attempt — corrected to a bounded delay plus a post-hoc assertion, after
  first reproducing the resulting `PessimisticLockingFailureException` the naive version caused; (3) Fix
  3's genuine behavior change (an admin action on an incomplete profile now fails loudly instead of
  silently activating it) required completing builder-step-data fixtures and adding FK-safe teardown
  ordering in `ReinstateIT`/`ManualStrikeIT`, which the story's own test plan did not call out. Full
  targeted regression sweep (reviews/marketplace/payment/admin/development/session/video packages, 1300+
  tests) passes clean; no `mvn verify` run locally per this project's standing convention. Status moved
  to `review`.

- 2026-09-23: **All 20 outstanding `/bmad-code-review` findings resolved** (the 3 Decisions plus 17
  `[Review][Patch]` items — see Review Findings above for the per-item resolution/verification note on
  each). 15 were genuine defects and fixed; 2 (`SubscriptionServiceConcurrencyIT`'s contention-proof
  claim and `syncMarketplaceTier`'s lock-attribution comment) were investigated and found to be false
  positives on closer analysis — the first was verified empirically by running the IT live against
  Testcontainers Postgres three times and confirming the claimed NOWAIT-retry mechanism genuinely fires;
  the second by tracing the actual Postgres FK-lock timing against the Java-level decision point it
  gates. Two `ReviewSubmissionServiceConcurrencyIT`/`ReviewFlagServiceConcurrencyIT` tests that could
  previously pass on a reverted fix (an unlocked pre-check short-circuit) were rewritten to hold the
  winning transaction open, forcing the loser through the real DB constraint path — re-run live and
  confirmed to hit the actual `23505` violations. Two admin-service tests
  (`reinstateCoach_incompleteProfile_...`/`deleteStrike_incompleteProfile_...`) were renamed and
  rewritten to assert the corrected (non-throwing) behavior Decisions 1/2 introduced, since they
  previously asserted the exact pre-review behavior the review replaced. All affected classes re-run
  live and green; a full sweep of `platform.reviews.**`/`admin.**`/`marketplace.**`/`payment.**` (375
  classes) and `platform.development.**` shows no regressions. No `mvn verify` run locally per this
  project's standing convention — GitHub CI remains the sole full-verification gate.
