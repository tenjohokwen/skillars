# Story: Lock Contention, Transactional Safety & Config-Bounds Hardening

**Story Key:** `skillars-deferred-132-lock-contention-transactional-safety-and-config-bounds-hardening`
**Epic:** Deferred Work
**Priority:** Medium (one genuine unbounded-wait risk carried across four prior stories, a real
marketplace-tier reconciliation gap, a rollback-only transaction trap, plus five lower-severity
lock/config/test hygiene items and one empirical CI-deadlock verification pass).
**Status:** done
**Created:** 2026-09-24

---

## Context

Sourced from a full front-to-back audit of `deferred-work.md` (all dated sections, oldest to
newest) plus `story-review.md`, run immediately after `skillars-deferred-131` (PR #219) merged to
master at `2842df52`. Scope requested: **Genuine one-off bugs & gaps, Lock contention & deadlock
risks, Transactional safety, Config bounds & enforcement, Pre-existing design issues** — explicitly
bundled into one non-small story per this project's standing convention.

Every candidate below was cross-checked against the ledger's own `[CLOSED by ...]` /
`[DECIDED: accepted risk ...]` / `[Review][Patch] IMPLEMENTED ...` markers and excluded if already
resolved — including all 8 `code review of skillars-deferred-130` bullets and both
`story review of skillars-deferred-130` bullets (closed by `skillars-deferred-131`), the
deferred-126/127 accepted-risk items, and the deferred-123/125 items blocked on a production deploy
that hasn't happened. All remaining line citations below were re-verified directly against
`HEAD = 2842df52` while drafting this story (several had drifted from the ledger's own citations,
corrected inline).

**Owner decisions taken live (AskUserQuestion) before drafting:**

1. `ReviewFlagService.flag()`'s blocking `FOR UPDATE` lock (3rd time raised) — **convert to
   NOWAIT+retry**, matching the module's established `PessimisticLockRetryer` convention, not a 4th
   decline. **Mechanism narrowed during story review:** `CoachReviewRepository.findByIdForUpdate` has
   six call sites, not one — the decision is honored via a **new**, `flag()`-only
   `findByIdForUpdateNoWait` method rather than converting the shared method in place (see Fix 2),
   which would have silently fail-fast-converted five other blocking call sites with no retry,
   including one whose outer catch depends on the lock blocking.
2. `syncMarketplaceTier`'s residual stale-tier gap on the upgrade path (see fact-check below) —
   **add a reconciliation sweep**, not an inline retry or accepted-risk framing.
3. GDPR erasure's `REQUIRES_NEW` connection-acquisition wait (deferred 4 stories running, 128→131,
   fix shape now fully scoped) — **build the dedicated capacity fix now**, not a 5th decline.
   **Mechanism corrected during story review:** the cross-thread `Future`-based bounded-wait guard
   this story originally proposed as option (b) is unsound (can create a genuine two-transaction
   deadlock, breaks the typed exception discrimination this codebase built across deferred-128/129)
   and is ruled out; see Fix 4 for the corrected direction.
4. `submitReview`'s rollback-only physical transaction under a hypothetical outer caller —
   **`REQUIRES_NEW` propagation**, not a test-only guard or continued documentation. **Scope widened
   during story review:** `ReviewFlagService.flag()` has the identical, deliberately-mirrored trap
   and gets the same fix (see Fix 7).
5. `ConfigBounds.BoundedKey`'s missing `default` field — **fix only the two review config keys'
   call sites** (see Fix 9 below — narrower than originally scoped once verified, see its Context),
   not a full `BoundedKey` record widening.
6. `PessimisticLockRetryer`'s JVM-wide cumulative retry counter — **add per-call-site attribution**
   (Micrometer `Tags`), not leave the new AC3 concurrency-IT assertions unable to prove what they
   claim.
7. Deferred-131's CI-deadlock fix shipped against a hypothesized, never-empirically-reproduced root
   cause — **attempt real reproduction now**, not accept the "mechanism closed, not exhaustively
   proven" framing permanently.
8. A cross-cutting Instancio convention sweep (new deferred-131 test classes hand-build entities) —
   **left out of this story**; cross-cutting drift, not itself a bug, would bloat an already-large
   story.

**Pre-implementation fact-check on Decision 2 (done before asking, not guessed):**
`SubscriptionService.persistCoachSubscription`'s Decision-3 catch-and-defer (shipped in
`skillars-deferred-131`, lines 168-182) prevents the *worst* outcome — losing the payment row after
Stripe has already charged — by catching `PessimisticLockingFailureException` around
`syncMarketplaceTier` and logging `COACH_SUBSCRIBE_TIER_SYNC_DEFERRED` instead of propagating. But
neither of the two scheduled jobs that also call `syncMarketplaceTier`
(`SubscriptionChangeApplicator.applyPendingChanges` at `SubscriptionService.java:473-497`,
`SubscriptionGracePeriodChecker.checkGracePeriods()`, which delegates to
`SubscriptionService.checkPastDueGracePeriod` at `:536-560`) re-syncs a coach outside
its own narrow trigger (a *pending scheduled downgrade*, or *past-due* status). A coach whose
`subscribeCoach` upgrade hits lock contention at exactly the wrong moment is left reading a stale
`marketplace.coach_subscriptions` tier — defaulting to `SCOUT` per `getCoachSubscriptionTier`'s own
`skillars-deferred-131` fix — **indefinitely**, until they happen to trigger one of those two
schedulers' narrow conditions. This is a real, currently-unclosed gap, not fully resolved by
Decision 3 as the ledger's AC5 note implies — Fix 3 below closes it directly.

---

## AC1: Lock Contention & Deadlock Risks

### Fix 1 — `ReviewFlagService.flag()`'s lock-order inversion with `GdprErasureService.erase()` (document, not fix)

**Context:** `ReviewFlagService.java:92` (`reviewRepository.findByIdForUpdate` — locks
`coach_reviews` first) → `:135` (`coachRatingService.recompute(review.getCoachId())` — eventually
writes `coach_profiles`). `GdprErasureService.erase()` touches the opposite order within one
transaction: `:168` (`coachProfileRepository.findByUserId(userId)` + save — writes the erasing
user's own `coach_profiles` row) → `:182-183`
(`coachReviewRepository.deleteNonApprovedByAuthorId`/`anonymiseApprovedReviews` — writes
`coach_reviews` rows *authored by* that same user). Re-verified: line numbers drifted 1-2 lines from
the ledger's own citations (`:166`→`:168`, `:178-179`→`:182-183`), mechanism unchanged.

**Why still unreachable (corrected during story review — the guard this argument originally cited
does not exist; the real reason is stronger and doesn't depend on one):** `erase(u)` only takes the
`coach_profiles` lock inside an `ifPresent` branch (`GdprErasureService.java:168`), so a non-coach
`u` takes **no** `coach_profiles` lock at all — no cycle possible regardless of what `u` authored. If
`u` *is* a coach, `u` can author **zero** reviews: `ReviewResource.resolveRole` (`:142-146`) resolves
anyone holding `ROLE_COACH` to `"COACH"` before checking any other role, and `AuthorRole`
(`platform/reviews/contract/AuthorRole.java`) contains only `PARENT, PLAYER` — so
`AuthorRole.valueOf("COACH")` throws `AUTHOR_ROLE_NOT_ALLOWED` (`ReviewSubmissionService.java:52-59`)
before a coach-authored review can ever be created. Either way, `erase()`'s `coach_reviews` writes
(`:182-183`, which only match rows *authored by* `u`) can never overlap a review `flag()`'s
`recompute` would also touch — the two lock sets are disjoint by construction for both account
shapes, not merely "not the same row pair" for an incidental reason. Note this protection is a
contract-enum membership plus a role-precedence check in the API layer, not a dedicated self-review
guard — a future story adding `COACH` to `AuthorRole` would make this reachable with nothing here to
flag it; worth a one-line note in the closeout for that reason.

**Decision (this story does not re-litigate the two prior "accepted, revisit trigger not met"
findings — record explicitly per AC5, consistent with how deferred-131 handled the identical
situation for its own M5 items):** continue to document as a latent, currently-unreachable design
issue rather than restructure either method's lock order. Reordering `erase()` to touch
`coach_reviews` before `coach_profiles` would itself require auditing every other table `erase()`
touches for the same risk — a much larger change than this latent, unreachable issue justifies today.

**Test:** none — no behavior change. Record in AC5 ledger closeout, 3rd consecutive story to
re-confirm "still open, still unreachable."

### Fix 2 — Add a NOWAIT-only `findByIdForUpdateNoWait` method to `CoachReviewRepository`, used only by `flag()`

**Context (corrected during story review — the original "sole call site" premise was false and
would have shipped a regression):** `CoachReviewRepository.java:23-25` is a plain
`@Lock(LockModeType.PESSIMISTIC_WRITE)` with no `@QueryHints` — genuinely blocking. `@QueryHints`
lives on the **repository method**, not on any one caller, and `findByIdForUpdate` in fact has
**six** call sites: `ReviewFlagService.java:92` (`flag()`), `ReviewSubmissionService.java:113`
(`updateReview()`) and `:148` (`submitCoachResponse()`), `AdminReviewService.java:81`
(`approveReview()`) and `:121` (`blockReview()`), and `ReviewModerationService.java:102` — a
non-async `@TransactionalEventListener(AFTER_COMMIT)` handler that re-reads the review inside its
own `REQUIRES_NEW` `TransactionTemplate`. Converting the shared method to NOWAIT in place would
silently turn the other five sites' blocking locks into fail-fast ones with no retry. The worst
case: `ReviewModerationService.handleReviewSubmitted`'s outer catch (`:154-163`) deliberately
swallows everything, including a lock-acquisition failure on that read, with a comment noting this
is safe *because the lock blocks* — under NOWAIT it becomes reachable on any brief overlap (an admin
approving, an author editing, a concurrent `flag()`), and the review is left `PENDING`
**permanently** (the event is not re-published, nothing sweeps it). Secondary: `approveReview`/
`blockReview` would 409 an admin, `updateReview`/`submitCoachResponse` would 409 the author, on
timing that today just waits milliseconds.

**Fix (rescoped to a dedicated method, keeping the blast radius matched to what the ledger actually
raised — `flag()`'s own read only):**
1. Add a **second** repository method to `CoachReviewRepository`: `findByIdForUpdateNoWait`, same
   query, with `@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))`.
   Leave the existing `findByIdForUpdate` untouched — it stays blocking and keeps serving the other
   five call sites exactly as today.
2. Inject `PessimisticLockRetryer` into `ReviewFlagService` (not currently a dependency — confirm no
   manual-constructor test instantiations before adding the field, `grep -rn "new
   ReviewFlagService(" src/test`).
3. Change `flag()`'s call at `:92` to `findByIdForUpdateNoWait`, wrapped in
   `lockRetryer.withBoundedRetry(...)`, mirroring every other NOWAIT lock site's exact shape (lock
   acquisition alone inside the retried lambda; `flag()`'s existing writes — the flag insert at
   `:96-102`, and the auto-hold check/writes — stay exactly where they are today, after the lock call
   returns).
4. This is a **new** `.withBoundedRetry(` call site: `PessimisticLockRetryerCallSiteAuditTest
   .EXPECTED_CALL_SITE_COUNT` must move from `33` to `34`.

**Test:** both existing `ReviewFlagServiceConcurrencyIT` tests that call
`ConcurrencyLockWaitSupport.awaitBlockingLockWaiter` block on `flag()`'s own read (not on
`updateReview`'s), so both break under NOWAIT and both need rework, for different reasons:
`concurrentUpdateReview_doesNotRevertEditOrWronglyAutoHold` (~line 148-241) and
`flagUnderGenuineLockContention_stillAppliesAutoHoldOnceThresholdReached` (~line 255-371) must move
from the `pg_locks`-wait poll to `awaitFirstLockAttempt` + the tagged `assertGenuineLockRetryOccurred`
(Fix 5) — under NOWAIT the contending `flag()` call never enters a Postgres wait state, so the
existing poll can never be satisfied, and the retryer's own bounded budget can exhaust before the
holder releases if the two aren't re-timed together. Also re-verify
`concurrentDuplicateFlagFromSameFlagger_loserGetsAlreadyFlaggedViaRealConstraintViolation` (~line
466), which now exercises the retry path for the first time even though it doesn't call the helper.
`awaitBlockingLockWaiter` keeps at least the `updateReview`-vs-`flag()` scenario's *setup* relying on
`updateReview`'s own (still-blocking) `findByIdForUpdate`, but confirm at implementation time whether
it still has a genuine caller anywhere in this class after the rewrite — if not, remove it and its
now-partially-stale class javadoc (`ConcurrencyLockWaitSupport.java:20-23`), which currently states
as fact that "`CoachReviewRepository.findByIdForUpdate` carries no `@QueryHints`" — true for five of
six call sites after this fix, not the repository as a whole, so the javadoc must be corrected either
way. Re-run live against Testcontainers Postgres to confirm the NOWAIT path is genuinely exercised.

### Fix 3 — Marketplace-tier reconciliation sweep for deferred `syncMarketplaceTier` syncs

**Context:** see the Context section's fact-check above. `SubscriptionService`'s two existing
scheduled jobs (`SubscriptionChangeApplicator.applyPendingChanges`,
`SubscriptionGracePeriodChecker.checkGracePeriods()` — which delegates to
`SubscriptionService.checkPastDueGracePeriod` — both in
`platform/payment/service/`, each already `@SchedulerLock`-guarded) only touch coaches matching
their own narrow trigger. There is no general "does `marketplace.coach_subscriptions.tier` still
match `payment.coach_subscriptions.tier`" sweep anywhere in this codebase.

**Fix:** add a third scheduled job, `SubscriptionTierReconciliationScheduler` (new file, same
package, same `@SchedulerLock` pattern as its two siblings), calling a new
`SubscriptionService.reconcileMarketplaceTiers()`:

```java
public void reconcileMarketplaceTiers() {
    List<PaymentCoachSubscription> active = transactionTemplate.execute(
        status -> paymentCoachSubscriptionRepository.findAllByStatusIn(List.of("ACTIVE", "TRIALLING")));
    if (active == null) active = List.of();
    for (PaymentCoachSubscription sub : active) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                Optional<CoachSubscription> marketplace =
                    coachSubscriptionRepository.findByCoachId(sub.getCoachId());
                CoachSubscriptionTier paymentTier;
                try {
                    paymentTier = CoachSubscriptionTier.valueOf(sub.getTier());
                } catch (IllegalArgumentException e) {
                    log.error("[COACH_TIER_RECONCILE_SKIPPED coachId={} unrecognizedTier={}]",
                        sub.getCoachId(), sub.getTier(), e);
                    return;
                }
                if (marketplace.isEmpty() || marketplace.get().getTier() != paymentTier) {
                    syncMarketplaceTier(sub.getCoachId(), sub.getTier());
                    log.warn("[COACH_TIER_RECONCILED coachId={} tier={}]", sub.getCoachId(), sub.getTier());
                }
            });
        } catch (Exception e) {
            log.error("Failed to reconcile marketplace tier for coach {}", sub.getCoachId(), e);
        }
    }
}
```

**Correction from story review — the original comparison was a defect, not a stylistic choice:**
`marketplace.get().getTier()` returns `CoachSubscriptionTier` (an enum, `CoachSubscription.java:30`)
while `sub.getTier()` returns `String` (`PaymentCoachSubscription.java:45`). `Enum.equals(String)` is
always `false`, so the original `!marketplace.get().getTier().equals(sub.getTier())` snippet would
have taken the `if` branch for **every** active/trialling coach on **every** run — real lock
contention against `publishProfile`/`reinstateCoach`/`deleteStrike` and the two sibling schedulers on
every pass, a rewritten `marketplace.coach_subscriptions` row every pass, and `COACH_TIER_RECONCILED`
logged at WARN for every coach on a daily cadence instead of only on genuine drift. It would also
make the story's own acceptance test ("a coach whose tiers already match causes no write") impossible
to pass. The corrected version above compares on one side of the boundary (`CoachSubscriptionTier`)
and guards `valueOf` explicitly, since a payment row with a `tier` outside `COACH_TIERS`
(`SubscriptionService.java:63`) would otherwise throw `IllegalArgumentException` per row instead of
being skipped cleanly.

`findAllByStatusIn` is a new `PaymentCoachSubscriptionRepository` query method (the existing
`findByStatusAndPastDueSinceBefore` is a different, narrower query — do not reuse it); pass the same
`List.of("ACTIVE", "TRIALLING")` status set `countActiveByTier` (`PaymentCoachSubscriptionRepository.java:19`)
already encodes, so the two sets can't drift independently. Reuses `syncMarketplaceTier`'s
already-locked write path (Fix 4 from `skillars-deferred-131`) — no new lock discipline needed here,
this job simply calls the same method the two siblings already call. Confirm at implementation time
whether the active-coach volume needs cursor-based paging (mirror whichever of the two sibling
schedulers' own sizing comments applies once the coach-subscription row count is checked).

**Missing-profile note:** `syncMarketplaceTier` (post-`skillars-deferred-131` Fix 4) already throws
on a missing `coach_profiles` row rather than proceeding unlocked — this sweep's per-row `try/catch`
means one such coach logs and is skipped, not a batch-wide failure, consistent with the two sibling
schedulers' own per-row isolation. Note this per-row skip logs at ERROR on **every** run with no path
to resolution if the profile is permanently missing — consider a one-shot alert or a lower log level
for the repeat case rather than a permanent daily `log.error`; decide at implementation time.

**Missed flow — a live billing-divergence path this fix does not close (identified during story
review, carve out explicitly rather than silently leaving it under this fix's umbrella):**
`changeCoachTier`'s upgrade branch (`SubscriptionService.java:216-227`) calls
`self.persistCoachTierUpgrade(coachId, sub, newTier)` (`:230-237`), which has **no** catch around
its own `syncMarketplaceTier(coachId, newTier)` call — unlike `subscribeCoach`'s
`persistCoachSubscription` (the sibling path this story's Context fact-check already covers), which
does catch `PessimisticLockingFailureException` there (Decision 3, lines 168-182). If
`syncMarketplaceTier` throws here, the **whole** `persistCoachTierUpgrade` transaction rolls back
*after* Stripe has already been called to update the subscription tier (`changeCoachTier:216`,
outside any transaction) — leaving Stripe billing at the new tier while
`payment.coach_subscriptions.tier` stays at the old one. This is a payment/entitlement divergence,
not merely a stale marketplace projection, and this story's reconciliation sweep would actively mask
it: treating `payment.coach_subscriptions` as the source of truth, the sweep would "reconcile"
`marketplace` back down to the stale (old) tier and log `COACH_TIER_RECONCILED` as though it had
fixed something. Decide at implementation time whether to add the same catch-and-defer to
`persistCoachTierUpgrade` as part of this fix (recommended — small, mirrors an existing pattern) or
explicitly carve it out as a separate, still-open ledger item; either way, do not let AC5's closeout
imply this divergence path is resolved unless the catch is actually added. This is also distinct from
the ledger's still-open residual calling for a **Stripe → payment** reconciliation sweep (coach has
a Stripe subscription but no local `payment.coach_subscriptions` row) — this fix's sweep is
**payment → marketplace** only; see AC5 for keeping that residual explicitly open.

**Test:** new `SubscriptionTierReconciliationSchedulerIT` (or extend
`SubscriptionSchedulerIsolationTest`'s existing pattern) — a coach with a `PaymentCoachSubscription`
at `INSTRUCTOR` and a stale/absent `marketplace.coach_subscriptions` row gets corrected by one sweep
pass; a coach whose tiers already match causes no write (assert no `COACH_TIER_RECONCILED` log line
for that coach); a payment row with a tier outside `COACH_TIERS` is skipped, not thrown, and does not
abort the rest of the sweep. Also add a `SubscriptionSchedulerLockTest` case for the new scheduler —
that test class carries one `@SchedulerLock` reflection test per scheduler today, so a third
scheduler needs a third case, not just the `SubscriptionSchedulerIsolationTest` extension.

### Fix 4 — Bound GDPR erasure's `REQUIRES_NEW` connection-acquisition wait

**Context:** `GdprErasureService.erase()` (`@Transactional(propagation = Propagation.REQUIRES_NEW)`)
and its internal `requiresNewTemplate` (`GdprErasureService.java:135-138`,
`PROPAGATION_REQUIRES_NEW` via a hand-built `TransactionTemplate`) both acquire a connection from
the single shared Hikari pool (`application.yaml:172-191` — `maximum-pool-size: 25`,
`connection-timeout: 30000`). Under sustained pool exhaustion, `erase()`'s attempt to open its own
new transaction can block for the full 30s Hikari `connection-timeout` with no bound of its own —
raised at `skillars-deferred-128` (Hazard 2), re-confirmed 3 times since (129, 130, 131) as
"accepted risk," this story's owner decision is to finally close it.

**Context this story's original draft omitted, and which strengthens why a fix is worth doing now:**
`erase()` runs on the **HTTP request thread**, invoked from a non-`@Async`
`@TransactionalEventListener(phase = AFTER_COMMIT)` (`GdprEventListener.java:21/31`,
`GdprErasureService.java:349-352`). Spring runs `afterCommit` handlers *before* the outer
transaction's own connection is released back to the pool, so `erase()`'s `REQUIRES_NEW` genuinely
needs a **second** pooled connection while the first is still (briefly) held — that is why the
30s-unbounded wait exists at all, and `gdprEraseLockBudget` (`:122`, currently a 10s *lock-retry*
ceiling, not a connection-acquisition one) already documents that this wait sits directly on the
request's own response time (`:344-351`).

**Fix shape — two viable mechanisms; this story rules one candidate out explicitly rather than
prescribing a specific implementation, since the tradeoff is genuinely architectural:**

- **(a) Dedicated secondary `HikariDataSource` + `EntityManagerFactory` + `PlatformTransactionManager`**
  scoped only to `GdprErasureService`'s two `REQUIRES_NEW` entry points — a small pool (2-3
  connections) with a short `connectionTimeout` (e.g. 5000ms), same JDBC URL/credentials as the
  primary pool. **Re-costed during story review — more invasive than the original draft implied:**
  there is **no `@EnableJpaRepositories` anywhere in `src/main/java`** — Spring Boot
  auto-configures repository scanning via `HibernateJpaAutoConfiguration`. Introducing a second EMF
  bean backs that auto-configuration off entirely, which means the **primary** EMF also has to be
  hand-wired at that point, not just the secondary one — this is closer to "take over JPA
  bootstrapping for the whole app" than "duplicate ~11 repository interfaces," and should be costed
  and scoped as such if chosen (e.g. spike whether Spring Boot's multi-EMF auto-config support
  handles this more cleanly than a fully manual wire-up before committing to it).
- **(b) Ruled out: run the acquisition on another thread and `Future.get(timeout)`.** This story's
  original draft proposed exactly this and it is unsound, for reasons found during story review:
  `future.get` timing out does **not** cancel the submitted task — it keeps running, and when the
  pool eventually frees a connection it can commit `erase()`'s deletes **after** `erase()` has
  already alerted and moved on, concurrently with the still-open outer transaction holding
  `main."user"`/`player_profiles` locks for the same account — a genuine two-transaction deadlock,
  strictly worse than today's serialized (if slow) wait. It also crosses a `ThreadLocal` boundary
  (`TransactionSynchronizationManager`, both services' `@PersistenceContext`-proxied
  `EntityManager`s) that the acquisition logic depends on, and `future.get()` wraps every exception
  in `ExecutionException`, silently breaking the two call sites' typed
  `catch (DeleteStatementLockTimeoutException | PessimisticLockingFailureException)`
  (`GdprErasureService.java:237`, `:427`) that the `CHILD_CONTENDED`/`CHILD_DELETE_LOCK_TIMEOUT`
  discrimination (built across deferred-128/129) depends on. Do not use this mechanism.
- **Suggested direction instead:** keep everything on the caller's own thread and bound the wait
  there — either extend the existing `gdprEraseLockBudget` deadline (already sampled per child at
  `:383-398`) to also cover the PLAYER branch and the connection-acquisition step itself, so a single
  budget bounds the whole operation; or, if a genuinely separate pool is wanted without a second EMF,
  a plain second `DataSource` driven through `DataSourceTransactionManager` (no JPA/EMF involved,
  used only for a raw bounded-wait probe or a narrowly-scoped non-JPA write) with its own short
  `connectionTimeout`. Whatever is chosen, it must fail fast **on the caller's own thread** — rule
  out any cross-thread hand-off explicitly in the implementation notes.

**Test:** an IT that saturates the pool (or mocks the acquisition path) to force the bound to trip,
asserting `erase()` surfaces a clear, alerted failure rather than hanging for 30s, and that the typed
`DeleteStatementLockTimeoutException`/`PessimisticLockingFailureException` discrimination still
matches. Needs its own Testcontainers `@ServiceConnection` wiring if route (a) is chosen — confirm at
implementation time.

### Fix 5 — `PessimisticLockRetryer`'s retry counter gets per-call-site attribution

**Context:** `PessimisticLockRetryer.java:182-186` (`recordRetries`) increments a single JVM-wide
`persistence.lock_retry.retries` counter (line 184) shared across all 33 (34 after Fix 2)
`.withBoundedRetry(` call sites. `skillars-deferred-131`'s new `ConcurrencyLockWaitSupport
.assertGenuineLockRetryOccurred` polls this same counter to prove a *specific* lock site actually
retried — but any unrelated in-flight retry elsewhere in the same JVM (5+ other async pools are live
during the test suite) can satisfy it, so the assertion can pass without the intended site ever
retrying.

**Fix:** add a `String lockName` parameter to `PessimisticLockRetryer.withBoundedRetry` (or an
overload, to avoid touching all 33/34 existing call sites' signatures if this codebase's convention
favors overloads elsewhere — confirm at implementation time), and tag the counter/timer with it:

```java
meterRegistry.counter("persistence.lock_retry.retries", "lock", lockName).increment(retries);
```

Every existing call site passes a short, stable name identifying its lock (e.g.
`"CoachProfileRepository.findByIdForUpdate"`), and `ConcurrencyLockWaitSupport
.assertGenuineLockRetryOccurred` (and its `awaitFirstLockAttempt` companion) gain a `lockName`
parameter to poll only that tag. Additive — no behavior change to the retry loop itself, purely
instrumentation.

**Tag every registration path, not just `retries` (noted during story review):** this fix should also
tag `persistence.lock_retry.exhausted` (`PessimisticLockRetryer.java:159`) and the
`persistence.lock_retry` timer (`:189`) with the same `lock` tag — leaving them untagged while
`retries` is tagged is an inconsistent half-fix, and this codebase already documents a hard
constraint that makes tagging **all** of them non-optional once any one of them is tagged:
`PrometheusMeterRegistry` rejects a second registration of the same meter name with a different
tag-key set (`ConfigStartupAssertion.java:310-315`), so every registration path for a given meter name
must carry the same tag set. Also: `ConcurrencyLockWaitSupport.currentLockRetryCount` currently uses
`meterRegistry.find(name).counter()`, which returns an arbitrary match once several
differently-tagged counters of the same name exist — the new `lockName` parameter must actually be
plumbed into that lookup (`meterRegistry.find(name).tag("lock", lockName).counter()`), it isn't
cosmetic.

**Test:** update `ConcurrencyLockWaitSupport`'s own unit/IT coverage (if any exists) plus re-run
every concurrency IT that currently uses `assertGenuineLockRetryOccurred`
(`CoachProfileServiceConcurrencyIT`, `SubscriptionServiceConcurrencyIT`, this story's Fix 2 rewrite
of `ReviewFlagServiceConcurrencyIT`) to confirm each now asserts on its own tagged counter, not the
shared one — and deliberately trigger an unrelated concurrent retry during one such test run to
confirm the assertion no longer passes on a false signal (the regression this fix is meant to close).

### Fix 6 — Empirically verify the CI reset-deadlock's hypothesized root cause

**Context:** `skillars-deferred-131` AC4 shipped `DatabaseResetTestExecutionListener
.quiesceAsyncExecutors` (`:210` onward, draining every `ThreadPoolTaskExecutor` bean before the
reset transaction) against a **structural, not empirical** diagnosis — `pom.xml`'s single-fork
config plus a grep confirming `RadarCompositeCalculationService`/`ReportGenerationService` share
`reportExecutor` and both write `player_profiles`-adjacent state. The fix was never confirmed
against a real reproduction of the original deadlock (master CI run `35891248593`).

**Task:** attempt a real reproduction now that a fix has already shipped, both to validate it
targets the right actor and to rule out a second one. **Corrected during story review:**
`quiesceAsyncExecutors` is already wired to run before **every** `beforeTestMethod` reset
(`DatabaseResetTestExecutionListener.java:113`, drain logic `:210-226`) — the original draft's steps
1-2 (add logging, run repeatedly, watch for overlap) would only ever observe a window the shipped fix
is already closing, making a "not reproduced" result methodologically guaranteed rather than an
empirical finding. The fix under test must be temporarily disabled to reproduce anything:
1. Temporarily short-circuit `quiesceAsyncExecutors` (e.g. an early `return` behind a system property
   or a commented-out call at `:113`) so the pre-fix race window reopens.
2. Add diagnostic logging around `RadarCompositeCalculationService`'s `@Async("reportExecutor")`
   entry point (`:82`) and `ReportGenerationService`'s equivalent (`:201`) — log entry/exit with the
   test thread name and a timestamp.
3. Run the `platform.development.**` package (231 tests per `skillars-deferred-131`'s own count)
   repeatedly (aim for at least 20-30 consecutive runs, matching the "several reruns — this is a
   race" expectation already recorded in the ledger) watching for any overlap between a logged
   async task still running and the next test class's `beforeTestMethod` reset firing. **This step
   runs local `mvn test` repeatedly against a live investigation** — an explicit, temporary exception
   to this project's "no local `mvn verify`, GitHub CI is the sole full-verification gate" convention
   (Task 15), justified because a race that reproduces roughly 1-in-N locally is not practical to
   chase through CI-only runs; state this exception plainly in the Dev Agent Record rather than
   silently running locally.
4. Reinstate `quiesceAsyncExecutors` (undo step 1) before going further — do not leave the race
   window open.
5. If reproduced with the quiesce disabled: confirm it does **not** reproduce with the quiesce
   restored, and note the confirmed mechanism precisely in this story's Dev Agent Record, mirroring
   `skillars-deferred-123`'s AC5 precedent for stating a confirmed (not hypothesized) mechanism. Also
   record the one known gap in the shipped fix: `quiesceAsyncExecutors` catches
   `ConditionTimeoutException` and **proceeds with the reset anyway** after its own 10s wait
   (`:218-224`) — so the race window is not fully closed for an in-flight async task that runs longer
   than 10s; state this as a known, accepted residual rather than implying the fix eliminates the
   race unconditionally.
6. If **not** reproduced even with the quiesce disabled after a reasonable number of attempts: say so
   plainly — this remains "mechanism closed by structural reasoning, not exhaustively reproduced,"
   now with a documented, bounded reproduction attempt (including the deliberate disable/reinstate
   step) behind that statement rather than none at all.
7. Remove the diagnostic logging before this story's PR (temporary investigation aid only, not a
   permanent addition) unless the investigation finds it worth keeping in a reduced form — decide at
   implementation time. Confirm `quiesceAsyncExecutors` is back to its shipped, always-on state before
   committing.

**Test:** none beyond the repeated CI/local runs themselves — this AC is the verification, not a
new automated test (same framing `skillars-deferred-131` used for its own AC4).

---

## AC2: Transactional Safety

### Fix 7 — `submitReview`'s (and `ReviewFlagService.flag()`'s) rollback-only transaction under a hypothetical outer caller

**Context:** `ReviewSubmissionService.java:31` (`@Transactional`, class-level) — `submitReview`
(`:41-85`) catches `DataIntegrityViolationException` at its `saveAndFlush` call (`:69-70`) and
returns a clean `ALREADY_SUBMITTED` `OperationNotAllowedException`. This is safe **only** because
`ReviewResource` (the REST layer) never opens its own transaction around this call — Spring's
default propagation (`REQUIRED`) makes `submitReview`'s `@Transactional` the outermost boundary
today. If any future caller wraps this in its own `@Transactional` method, the underlying Postgres
transaction — already marked rollback-only by the DIVE at flush time — surfaces as
`UnexpectedRollbackException` at the outer boundary instead of the clean 4xx this method's own catch
block promises.

**Fix:** change the class-level `@Transactional` to a method-level
`@Transactional(propagation = Propagation.REQUIRES_NEW)` on `submitReview` specifically (leave the
class-level `@Transactional` as-is for every other method in this file — `updateReview` and any
others have no such trap and don't need the isolation). This guarantees `submitReview` always runs
in its own physical transaction regardless of caller nesting, so a caught-and-translated
`DataIntegrityViolationException` can never propagate rollback-only state to an outer transaction.

**Also apply to `ReviewFlagService.flag()` (identified during story review — same trap, deliberately
mirrored code):** `ReviewFlagService.java:101-114` has the byte-for-byte identical shape —
`saveAndFlush` inside a `try`, catch `DataIntegrityViolationException`, translate the unique-flagger
violation to a clean `ALREADY_FLAGGED` 4xx — and `ReviewSubmissionService.java:69-70`'s own comment
states these two methods were *deliberately* made to mirror each other. `ReviewFlagService` is
class-level `@Transactional` today with no other caller opening an outer transaction, so the same
propagation change applies for the same reason; leaving it out would ship an inconsistent half-fix
on two sibling methods in the same module. Apply the same `REQUIRES_NEW` fix to `flag()` as part of
this Fix.

**Weigh, don't ignore, before applying `REQUIRES_NEW` to either method:**
- **Semantic change, not pure isolation.** The `coach_reviews`/`review_flags` row now survives an
  outer rollback, and each method's `AFTER_COMMIT` listener (moderation for reviews, auto-hold's
  `coachRatingService.recompute` chain for flags) fires on the *inner* commit. Today an outer
  rollback discards both together. Likely desirable, but it is a behavior change and should be stated
  as one, not folded silently into "isolation."
- **A second pooled connection per call**, in the same story that treats the 25-connection pool as a
  constrained resource under Fix 4.
- **Self-deadlock risk the new test must be written to avoid, not walk into.** If a hypothetical (or
  test-constructed) outer transaction has itself touched the same `uq_coach_reviews_author_coach` /
  unique-flagger key the inner `REQUIRES_NEW` call also targets, the suspended outer transaction holds
  the uncommitted index entry while the inner one blocks on it — on the same thread, forever. A test
  that "wraps a call to `submitReview`/`flag()` in an outer `@Transactional`/`TransactionTemplate`
  block" walks straight toward this if the outer transaction touches the same unique key; construct
  the outer transaction to touch unrelated rows (or nothing beyond opening/holding a transaction) so
  the test proves the propagation fix without deadlocking the test process itself.

**Test:** new test(s) proving the fix on both methods, built per the self-deadlock note above: wrap a
call to `submitReview` (and, separately, `flag()`) in an outer `@Transactional`/`TransactionTemplate`
block that does not touch the same unique-constraint row, combined with a concurrent duplicate
submission/flag; assert the loser still gets a clean `ALREADY_SUBMITTED`/`ALREADY_FLAGGED`, not
`UnexpectedRollbackException`, from *inside* that outer transaction. This is the regression test the
ledger noted was missing ("no current violator; constraint lives only in a test javadoc") — this
story adds the violator scenario as a real test rather than leaving the constraint
undocumented-but-untested.

### Fix 8 — `GdprErasureService` fully hydrates `PerformanceReport` rows to read one column

**Context:** `GdprErasureService.deletePlayerDevelopmentData` — `performanceReportRepository
.findByPlayerIdOrderByGeneratedAtDesc(playerId)` (currently at `:664`, drifted from the ledger's
`:621` after `skillars-deferred-128`/`130`'s own restructuring of this method) loads every
`PerformanceReport` entity in full just to read `getStorageKey()` (`:665-668`) before
`deleteAllByPlayerId` (`:671`) bulk-deletes them. Benign today (memory-proportional to a single
player's report count, not a correctness bug), but unnecessary entity hydration on a path this story
is already touching for Fix 9 (same method, `deletePlayerDevelopmentData`).

**Fix:** add a projection query to `PerformanceReportRepository`:
```java
@Query("SELECT p.storageKey FROM PerformanceReport p WHERE p.playerId = :playerId AND p.storageKey IS NOT NULL")
List<String> findStorageKeysByPlayerId(@Param("playerId") Long playerId);
```
Replace the `findByPlayerIdOrderByGeneratedAtDesc(...).forEach(...)` block with
`childBlobKeys.addAll(performanceReportRepository.findStorageKeysByPlayerId(playerId));` — the
`ORDER BY generatedAtDesc` was never load-bearing for this use (the keys are only added to a set for
later enqueue, order irrelevant), and the null-check moves into the query's `WHERE` clause instead of
the loop body.

**Test:** unit or IT assertion that a player with a mix of `PENDING_UPLOAD` (null `storageKey`) and
`READY` (non-null) reports enqueues only the non-null keys — same assertion the current code already
implicitly makes, now against the projection path.

**Dead code left behind (noted during story review):** once the projection replaces the only caller,
`PerformanceReportRepository.findByPlayerIdOrderByGeneratedAtDesc` (`:15`) has zero remaining callers
across `src/main` and `src/test`, and its explanatory comment (`:13-14`, "Used by GDPR erasure…
callers there must null-check `getStorageKey()`") goes stale. Delete the method and its comment as
part of this fix rather than leaving unreferenced code behind.

### Fix 9 — GDPR erasure's `lock_timeout` config re-read once per child, on the caller's connection

**Context:** `GdprErasureService.deletePlayerDevelopmentData` (`:628` onward) reads
`configService.getBoundedLong(ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.key(), 5L, 2L,
120L)` at `:630-631`, **before** `requiresNewTemplate.executeWithoutResult` opens — i.e., still on
the **outer** (`eraseParentChildren`'s) transaction's connection. `eraseParentChildren`'s loop
(`:415`) calls `deletePlayerDevelopmentData` once per child, so this read happens once per child, all
on the same outer connection that already holds the `main."user"`/`player_profiles` lock for that
parent. `ConfigService.refreshCache()` (`ConfigService.java:306`) is `private synchronized` and does
its own `configRepository.findAll()` (`:311`) — a cache-TTL-boundary refresh triggered by one child's
read can, in the worst case, force every other `ConfigService` caller in the JVM to wait behind that
one `synchronized` block while this thread is also holding a database lock and a pooled connection.

**Note this mitigates frequency, not the hazard itself** — the underlying risk (`refreshCache()`'s
`private synchronized configRepository.findAll()` stalling every other `ConfigService` caller in the
JVM while this thread holds a lock and a pooled connection) is unchanged in kind; hoisting the read
makes it happen once per `erase()` call instead of once per child, which reduces probability, not
the class of risk. State it that way in the story rather than implying the risk is closed.

**Fix — two call sites, not one (corrected during story review):**
`deletePlayerDevelopmentData(Long playerId)` (`:628`) is called from **two** places:
`eraseParentChildren`'s loop (`:415`, once per child) and `erase()`'s own PLAYER branch (`:215`,
already exactly once). Read `lockTimeoutSeconds` **once** at the top of `eraseParentChildren` (before
its loop starts) and once at the equivalent point in `erase()`'s PLAYER branch, and pass it as a
parameter into `deletePlayerDevelopmentData(Long playerId, long lockTimeoutSeconds)`. For the PLAYER
branch — described in the method's own javadoc (`:622-625`) as "the most common account shape" — this
change is a **no-op in practice**: that path already calls `deletePlayerDevelopmentData` exactly
once, so there is no repeated-read hazard to remove there; only the PARENT loop's N-children
repetition is actually fixed. Say so plainly rather than implying both branches benefit equally. This
keeps the existing "read before the lock acquisition, not after" placement this method's own javadoc
already documents as intentional (mirroring `recalculateComposite`'s convention).

**Test:** unit test asserting `configService.getBoundedLong` (the only call site of this key in the
file, `:630`, so the count is unambiguous) is invoked exactly once per `erase()` call for the PARENT
branch **regardless of child count**, including the 0-children case — this assertion is only true if
the read is hoisted unconditionally in `eraseParentChildren` (before any emptiness check), not placed
after one; if the fix is instead written to skip the read entirely when there are zero children, the
correct assertion is "once per non-empty `erase()` call, zero times for zero children" — pick one
placement and state the matching assertion, don't claim "regardless of child count" if the read is
conditional. Cover with 0, 1, and 3+ children for the PARENT branch, and confirm the PLAYER branch
still reads exactly once (unchanged behavior, guards against a future accidental double-read).

---

## AC3: Config Bounds & Enforcement

### Fix 10 — Point `ReviewFlagService`/`ReviewSubmissionService` at their existing `ConfigBounds` constants

**Context (corrected from the ledger's framing — verified at HEAD, more precise and cheaper than
originally scoped):** `ConfigBounds.REVIEWS_SUBMISSION_WINDOW_DAYS` and
`ConfigBounds.REVIEWS_AUTO_HOLD_FLAG_THRESHOLD` **already exist** (`ConfigBounds.java:164-171`),
are already registered in `HAS_CODE_DEFAULT` (`:343-344`) and `ALL` (`:373-374`), and their
`[min, max]` bounds (`1L-365L`, `1L-1000L`) already exactly match the raw literals at both call
sites. The gap is narrower than "add BoundedKey constants" — it's that the two call sites never
switched to reference them:

- `ReviewFlagService.java:127` —
  `configService.getBoundedInt("reviews.autoHoldFlagThreshold", 3, 1, 1000)`
- `ReviewSubmissionService.java:180` —
  `configService.getBoundedInt("reviews.submissionWindowDays", 14, 1, 365)`

Both pass a **raw string literal key** instead of referencing the constant's `.key()`.
**Correction from story review — the fail-fast claim above is wrong and should not be used as the
motivation:** `ConfigStartupAssertion` iterates `ConfigBounds.ALL` and looks each key up **by
string** (`:83-85`), so `reviews.submissionWindowDays` is boot-protected today regardless of whether
the call site references the constant or the literal — the literal-vs-constant form at the call site
is irrelevant to `ConfigStartupAssertion`. The genuine value of this fix is narrower: **future
typo-drift protection at the call site** — if `reviews.submissionWindowDays` were ever mistyped at
either usage, referencing the constant makes that a compile error; a raw literal would silently
create an unrelated, always-defaulted key with no compile-time signal (`ConfigStartupAssertion`
would still boot-protect the *typo'd* key correctly, it just wouldn't be the key anyone intended).
Lead with this framing, not the fail-fast one.

**Fix:** replace each raw string literal with the existing constant's `.key()`:
```java
int threshold = configService.getBoundedInt(
    ConfigBounds.REVIEWS_AUTO_HOLD_FLAG_THRESHOLD.key(), 3, 1, 1000);
```
```java
int windowDays = configService.getBoundedInt(
    ConfigBounds.REVIEWS_SUBMISSION_WINDOW_DAYS.key(), 14, 1, 365);
```
No production behavior change (the string values are identical) — purely closes the
drift-detection/coverage/fail-fast gap. `ConfigBounds.BoundedKey`'s missing `default` field
(the broader item the ledger also flagged) is **not** touched by this fix, per the owner decision
above — record that residual explicitly in AC5, not silently implied as resolved.

**Test — corrected during story review:** `ConfigBoundsEnumCoverageTest` does **not** exercise this
change at all; it only validates the registry's own internal consistency (enum coverage,
`min ≤ max`, non-blank note, no duplicate keys, `HAS_CODE_DEFAULT` membership) and never scans call
sites for any key — referencing the constant instead of the literal changes nothing that test
asserts. The only new guard this fix adds is a per-service unit assertion pinning the exact key
string, mirroring `GdprErasureServiceTest`'s existing bounds-literal-pin pattern:
```java
verify(configService).getBoundedInt(eq(ConfigBounds.REVIEWS_AUTO_HOLD_FLAG_THRESHOLD.key()), eq(3), eq(1), eq(1000));
verify(configService).getBoundedInt(eq(ConfigBounds.REVIEWS_SUBMISSION_WINDOW_DAYS.key()), eq(14), eq(1), eq(365));
```
Leaving the `1, 365` / `1, 1000` bounds as re-typed literals (not also switched to
`ConfigBounds.REVIEWS_SUBMISSION_WINDOW_DAYS.min()`/`.max()`, etc.) is intentional and has an
existing precedent worth citing: `GdprErasureServiceTest`'s javadoc (`:53-65`) documents that
re-typing bounds as literals in the test is the deliberate convention, so the `Mockito verify(...)`
pins the numbers as an independent drift detector rather than trivially re-deriving them from the
same constant it's meant to check.

---

## AC4: Genuine One-off Bugs & Gaps

### Fix 11 — `PessimisticLockRetryerCallSiteAuditTest` audits call sites, not lock sites

**Context:** the test (per its own javadoc, `PessimisticLockRetryerCallSiteAuditTest.java:20-59`)
enumerates every `.withBoundedRetry(` occurrence and regex-audits each lambda's raw source against a
DENYLIST — but it never enumerates `findByIdForUpdate(` occurrences directly. A brand-new
`findByIdForUpdate` call added **without** a `withBoundedRetry` wrapper is entirely invisible to
this test, since it only ever looks *inside* retry wrappers it already found, never asks "does every
lock call have one."

**Correction from story review — "all current `findByIdForUpdate` sites are correctly wrapped
today" is false, and a blanket assertion would fail on its first run.** The ledger's own claim is
narrower than this story's original draft: `deferred-work.md` says *"all 15 `coachProfileRepository`
`findByIdForUpdate` sites are correctly wrapped today"* — specific to one repository, not all of
them. Four repositories declare `findByIdForUpdate` **without** `@QueryHints(lock.timeout = 0)` —
i.e. genuinely blocking locks that correctly have **no** retry wrapper, by design, and must stay
that way: `VideoQuotaRepository` (`QuotaService:81`), `CoachPayoutRepository`
(`CoachPayoutTransferHandler:66`, `DisputeService:315`), `MessageRepository`
(`AdminMessageService:101,140`, `MessagingService:332`, `ModerationResultApplier:53`,
`MessageModerationSweeper:115`), and `CoachReviewRepository` (the five sites this story's Fix 2
deliberately leaves blocking, plus `flag()`'s own new NOWAIT site once Fix 2 lands). A blanket "every
`findByIdForUpdate` call site must also match `.withBoundedRetry(` in the same method" assertion
would flag all of these as violations on its very first run. This also corrects Fix 2's own Context
claim that `CoachReviewRepository` was "the reviews module's only remaining blocking lock" — three
other repositories still block, by design, elsewhere in the codebase.

**Fix:** scope the new assertion to repositories whose `findByIdForUpdate` declaration carries
`@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))` — confirmed today
as `VideoRepository`, `PlayerProfileRepository`, `BookingBatchRepository`,
`BookingRescheduleRequestRepository`, `BookingRepository`, `SessionPackPurchaseRepository`,
`CoachProfileRepository`, `DrillRepository`, and (after Fix 2) `CoachReviewRepository`'s new
`findByIdForUpdateNoWait` method specifically, not its unchanged blocking `findByIdForUpdate`. For
each NOWAIT repository, enumerate every call to its NOWAIT-lock method across `src/main/java` (same
source-scanning approach the existing test already uses) and assert each call site also matches a
`.withBoundedRetry(` occurrence in the same method — a simple "does this method's source contain both
tokens" check is sufficient given the existing test's own established rigor level; do not
over-engineer a full AST match where the existing test doesn't either. State the four blocking
repositories as an explicit, justified exemption list in the test's own javadoc or a constant — that
list is itself a useful artifact: it's the set that would need revisiting if the NOWAIT convention is
ever made universal across this codebase.

**Note on implementation cost:** the existing test has no method-boundary parsing today — it works on
file offsets plus balanced-paren matching from `.withBoundedRetry(` (`:142-198`). A genuine "does the
call site inside this specific method also have a wrapper" check requires adding method-boundary
detection the existing test doesn't have. A file-scoped check ("does this file contain both a NOWAIT
`findByIdForUpdate*` call and at least one `.withBoundedRetry(`") is the cheaper option consistent
with the existing test's rigor level, at the cost of a higher false-negative rate (an unwrapped call
in a file that also happens to wrap something else elsewhere would pass); name this tradeoff
explicitly and pick a scope (method vs. file) rather than assuming method-level parsing is free.

**Test:** this fix *is* a test change. Prove it catches the regression it's meant to catch by
temporarily adding an unwrapped call to a NOWAIT repository's lock method in a scratch location,
confirming the new assertion fails, then removing it before commit. Also confirm the new assertion
passes cleanly against the four exempted (blocking) repositories without modification.

### Fix 12 — Two of four branch×reason combinations untested in GDPR erasure's lock-timeout handling

**Context:** `GdprErasureService`'s lock-timeout/contention error handling has two call sites
(`erase`'s own `PLAYER` branch, `eraseParentChildren`'s loop) each distinguishing two failure
causes (`CHILD_CONTENDED` vs. `CHILD_DELETE_LOCK_TIMEOUT`, per the constants at
`GdprErasureService.java:129-130`) — four branch×reason combinations total.

**Correction from story review — existing coverage is wrong, both missing tests belong in
`GdprErasureIT`, not `GdprErasureServiceTest`.** `GdprErasureServiceTest.java` contains exactly one
test (`deletePlayerDevelopmentData_readsLockTimeoutConfigWithTheDocumentedBoundsLiterally`, a
`verify(configService).getBoundedLong(...)` bounds-literal pin) and covers **zero** of the four
branch×reason combinations — there is nothing there to mirror for this fix. Both currently-covered
combinations live in `GdprErasureIT`:

| Branch | Reason | Test | Assertion |
|---|---|---|---|
| PARENT (`eraseParentChildren`) | `CHILD_CONTENDED` | `erase_parentUser_contendedChild_skipsOthersProcessed_marksRequestFailed` | `:1146` |
| PLAYER (`erase`) | `CHILD_DELETE_LOCK_TIMEOUT` | `erase_selfRegisteredPlayer_downstreamDeleteStatementLockTimeout_boundedNotHanging_marksFailedWithDistinguishedAlert` | `:1275` |

Missing: **PARENT × `CHILD_DELETE_LOCK_TIMEOUT`** and **PLAYER × `CHILD_CONTENDED`**. Both belong in
`GdprErasureIT`, mirroring the corresponding existing test's exact setup/mocking shape for that
branch. Note the cost: each of the two existing tests holds a raw lock for several seconds with a
~20s future to force the timeout, so adding two more roughly doubles this file's contribution to
suite time — acceptable but worth acknowledging rather than treating as free.

**Fix:** no production code change — add the two missing test cases to `GdprErasureIT`.

**Test:** this fix *is* the test addition — confirm all four combinations are now exercised and
green.

---

## AC5: Ledger Closeout

Update `deferred-work.md`:

- Mark every item this story resolves (Fixes 2-12, i.e. all except Fix 1 which stays documented-only)
  `[CLOSED by skillars-deferred-132-... (PR #<number>)]`, following the established convention.
- **Fix 1** (lock-order inversion), the **`BoundedKey` missing-default residual** (not touched by
  Fix 10), the **Stripe → payment reconciliation sweep** the ledger separately calls for
  (`deferred-work.md:3328-3345`, restated at `:3502` — "a compensating action or a reconciliation
  sweep for Stripe subscriptions with no local row"; distinct from Fix 3's **payment → marketplace**
  sweep, and not closed by it), and — if `persistCoachTierUpgrade`'s missing catch (Fix 3's "missed
  flow" note) is carved out rather than fixed — that billing-divergence path itself, all stay
  explicitly **open**. Record each plainly in a new
  `## Last audit: 2026-09-24 (skillars-deferred-132 dev-story completion)` section, mirroring
  `skillars-deferred-131`'s own AC5 precedent for triaging items a story deliberately leaves
  unresolved rather than letting AC5's blanket "Mark every item this story resolves" language imply
  they're closed.
- If Fix 6's investigation reproduces (or fails to reproduce) the CI deadlock's root cause, record
  the actual finding precisely — not the hypothesis — mirroring `skillars-deferred-123`'s AC5
  precedent.
- If Fix 4 lands via approach (a) (secondary EntityManagerFactory) rather than (b) (bounded-wait
  guard), record which was chosen and why, since this story deliberately left that choice open.

---

## Tasks

- [x] **Task 1 (AC1):** Diff-check every cited line against current `HEAD` immediately before
      touching each file (this story's citations may drift further if anything else lands on
      `master` between creation and implementation).
- [x] **Task 2 (AC1):** Implement Fix 1 — documentation-only, no code change; confirm the
      `AuthorRole` enum still excludes `COACH` and `ReviewResource.resolveRole` still resolves
      `ROLE_COACH` before any other role — the two facts this fix's disjoint-lock-sets argument
      depends on — before writing the closeout note.
- [x] **Task 3 (AC1):** Implement Fix 2 (new `CoachReviewRepository.findByIdForUpdateNoWait` used
      only by `ReviewFlagService.flag()`, wrapped in `PessimisticLockRetryer`) + rewritten
      `ReviewFlagServiceConcurrencyIT` contention tests; bump
      `PessimisticLockRetryerCallSiteAuditTest.EXPECTED_CALL_SITE_COUNT` 33→34 (an overload for Fix 5
      adds no `.withBoundedRetry(` occurrence by itself — only a new call site moves this count, and
      the only one this story adds is Fix 2's).
- [x] **Task 4 (AC1):** Implement Fix 3 (`SubscriptionTierReconciliationScheduler` +
      `SubscriptionService.reconcileMarketplaceTiers`, with the corrected enum-vs-`valueOf` comparison
      and guarded `valueOf`) + new IT + `SubscriptionSchedulerLockTest` case; decide whether to add
      the missing catch to `persistCoachTierUpgrade` as part of this task or carve it out as a
      separate open ledger item (do not let AC5 imply it's resolved either way without checking).
- [x] **Task 5 (AC1):** Implement Fix 4 (GDPR erasure `REQUIRES_NEW` connection-acquisition bound) —
      decide between (a) a scoped secondary EMF/DataSource and the same-thread bounded-deadline
      direction the story suggests instead of (b) (the cross-thread `Future` mechanism is ruled out,
      not a choice), implement, add its IT.
- [x] **Task 6 (AC1):** Implement Fix 5 (`PessimisticLockRetryer` per-call-site retry attribution) —
      update all existing `.withBoundedRetry(` call sites with a lock name if the parameter-not-overload
      route is chosen; tag `persistence.lock_retry.exhausted` and the `persistence.lock_retry` timer
      with the same `lock` tag, not just `retries`; plumb `lockName` into
      `ConcurrencyLockWaitSupport.currentLockRetryCount`'s `meterRegistry.find(...)` lookup; update
      every concurrency IT that uses `assertGenuineLockRetryOccurred`.
- [x] **Task 7 (AC1):** Implement Fix 6 (empirical CI-deadlock reproduction attempt) — temporarily
      disable `quiesceAsyncExecutors`, add diagnostic logging, run `platform.development.**`
      repeatedly locally (an explicit, stated exception to the no-local-`mvn verify` convention),
      reinstate the quiesce, document the actual finding including the known >10s residual gap.
- [x] **Task 8 (AC2):** Implement Fix 7 (`submitReview` **and** `ReviewFlagService.flag()` →
      `REQUIRES_NEW`) + new outer-transaction regression tests for both, built to avoid the
      same-thread self-deadlock the story's own note describes.
- [x] **Task 9 (AC2):** Implement Fix 8 (`PerformanceReport` projection query) + test; delete the
      now-dead `findByPlayerIdOrderByGeneratedAtDesc`.
- [x] **Task 10 (AC2):** Implement Fix 9 (single config read per `erase()` call, passed down) + test.
- [x] **Task 11 (AC3):** Implement Fix 10 (point both review config call sites at their existing
      `ConfigBounds` constants) + quick key-name assertions.
- [x] **Task 12 (AC4):** Implement Fix 11 (`PessimisticLockRetryerCallSiteAuditTest` lock-site
      coverage, scoped to the NOWAIT repositories with the four blocking repositories named as an
      explicit exemption list) — verify it catches a deliberately-introduced unwrapped call and
      passes cleanly against the four exemptions before removing the scratch test case.
- [x] **Task 13 (AC4):** Implement Fix 12 (two missing branch×reason GDPR erasure tests).
- [x] **Task 14 (AC5):** Ledger closeout in `deferred-work.md`, including Fix 1's, the
      `BoundedKey`-default residual's, and the still-separate Stripe→payment reconciliation
      residual's explicit still-open notes (plus `persistCoachTierUpgrade`'s missing catch if carved
      out rather than fixed under Task 4), plus Fix 6's actual finding.
- [x] **Task 15:** Full targeted regression sweep (at minimum: `platform.reviews.**`,
      `platform.payment.**`, `platform.admin.**` [`GdprErasureService`],
      `platform.development.**` [Fix 6's investigation target],
      `PessimisticLockRetryerCallSiteAuditTest`, `ConfigBoundsEnumCoverageTest`). No `mvn verify`
      run locally per this project's standing convention (`docs/validation-strategy.md`) — GitHub
      CI is the sole full-verification gate.

## Dev Notes

- This story deliberately leaves two implementation-time design choices open rather than
  prescribing a single mechanism: Fix 4's secondary-EMF-vs-same-thread-bounded-deadline choice (the
  cross-thread `Future` mechanism from this story's earlier draft is ruled out, not one of the
  options), and Fix 5's parameter-vs-overload choice for `withBoundedRetry`. Both are flagged inline
  with the tradeoffs already researched — decide and record the choice made, don't silently pick one
  without noting why.
- Fix 7 covers both `submitReview` and `ReviewFlagService.flag()` — they share the identical
  DIVE-catch-and-translate trap by deliberate design (the two methods mirror each other), so applying
  the fix to only one would ship an inconsistent half-fix.
- Fix 2 and Fix 5 both touch `PessimisticLockRetryerCallSiteAuditTest`'s expected call-site count —
  implement Fix 2 first (or track both bumps together) to avoid a transient miscount mid-implementation.
- Fix 3 adds a new scheduled job — mirror `SubscriptionChangeApplicator`/`SubscriptionGracePeriodChecker`'s
  exact `@SchedulerLock` naming and cadence conventions (check their annotations directly before
  choosing a schedule for the new job; don't guess a cron expression).
- No frontend changes anticipated — confirm via `git status --short` before opening the PR, per this
  project's established `frontend-tests` label convention.

## Dev Agent Record

### Completion Notes

All 5 ACs / 15 Tasks implemented, tested, and verified green. No `mvn verify` run locally per this
project's standing convention (`docs/validation-strategy.md`) — GitHub CI is the sole full-verification
gate. Targeted regression sweep run instead (all green, 0 failures/errors): `platform.reviews.**`,
`platform.payment.**` (349 tests), `platform.admin.**` (173 tests, includes `GdprErasureIT`'s 32 tests),
`platform.development.**` (231 tests), `PessimisticLockRetryerCallSiteAuditTest`,
`ConfigBoundsEnumCoverageTest`, `ConfigStartupAssertionTest`.

**AC1 (Fixes 1–6):**
- **Fix 1** (lock-order inversion, `ReviewFlagService.flag` ↔ `GdprErasureService.erase`) —
  documentation-only, confirmed the two facts the disjoint-lock-sets argument depends on
  (`AuthorRole` still excludes `COACH`; `ReviewResource.resolveRole` still resolves `ROLE_COACH`
  before any other role) before recording the 3rd-consecutive-story re-confirmation in the ledger.
- **Fix 2** — new `CoachReviewRepository.findByIdForUpdateNoWait` (NOWAIT), used only by
  `ReviewFlagService.flag()`, wrapped in `PessimisticLockRetryer`; the shared blocking
  `findByIdForUpdate` is untouched and still serves its other five call sites.
  `PessimisticLockRetryerCallSiteAuditTest.EXPECTED_CALL_SITE_COUNT` bumped 33→34. Rewrote
  `ReviewFlagServiceConcurrencyIT`'s two blocking-wait tests to the NOWAIT-retry pattern
  (`awaitFirstLockAttempt` + tagged `assertGenuineLockRetryOccurred`) — proven against real
  Testcontainers Postgres, genuine NOWAIT contention/retry confirmed via the `55P03` log line.
  `ConcurrencyLockWaitSupport.awaitBlockingLockWaiter` removed (zero remaining callers after the
  rewrite) along with its class javadoc's now-stale claim.
- **Fix 3** — new `SubscriptionTierReconciliationScheduler` + `SubscriptionService
  .reconcileMarketplaceTiers()`, with the corrected `CoachSubscriptionTier`-vs-`String` comparison
  (the original `Enum.equals(String)` snippet from story drafting was always `false` — caught before
  implementation) and a guarded `valueOf` that skips (not throws on) an unrecognized payment-side
  tier. New `PaymentCoachSubscriptionRepository.findAllByStatusIn`. Also added the missing
  catch-and-defer to `persistCoachTierUpgrade` (Task 4's "recommended" branch) — a live
  billing-divergence path the story's own review found, mirroring `persistCoachSubscription`'s
  existing Decision-3 pattern. 8 new tests (3 unit-level reconciliation scenarios in
  `SubscriptionSchedulerIsolationTest` + 1 `SchedulerLock` reflection case), all against real/mocked
  fixtures — `SubscriptionResourceIT`/`SubscriptionLifecycleIT` re-run clean (28 tests).
- **Fix 4** — GDPR erasure `REQUIRES_NEW` connection-acquisition bound. Chosen mechanism: a
  same-thread, read-only `HikariPoolMXBean` pre-check (not the costlier secondary-EMF option (a),
  and not the ruled-out cross-thread `Future` option (b)) — see `GdprErasureService.erase`'s own
  Javadoc for the full tradeoff record. `erase()` split into a non-`@Transactional` wrapper (runs
  the pre-check) delegating via a new `self`-proxy field to `eraseTransactional` (keeps the
  `@Transactional(REQUIRES_NEW)` body unchanged) — necessary because a check inside a declaratively
  `@Transactional` method's own body runs too late (the AOP proxy already acquired its connection).
  The same guard also covers `deletePlayerDevelopmentData`'s own nested `REQUIRES_NEW` acquisition.
  New `GdprErasureIT` test saturates the real pool (borrows every connection directly off the
  `DataSource`) and proves `erase()` fails fast (<5s) with `PessimisticLockingFailureException`
  instead of hanging toward the 30s `connection-timeout`. Full `GdprErasureIT` (32 tests) reconfirmed
  green after this change.
- **Fix 5** — `PessimisticLockRetryer.withBoundedRetry` gained a `lockName` parameter (not an
  overload — dozens of existing unit tests mock this method with a single-`Supplier`-arg stub, and
  Prometheus requires every registration of a given meter name to carry the same tag set, so an
  untagged overload wasn't viable alongside a tagged one). All 34 call sites updated with a
  `ClassName.methodName`-style name; all `persistence.lock_retry.retries`/`.exhausted` counters and
  the `persistence.lock_retry` timer now carry the same `lock` tag.
  `ConcurrencyLockWaitSupport.currentLockRetryCount`/`assertGenuineLockRetryOccurred` take a
  `lockName` and poll only that tag. ~20 existing unit test files' `withBoundedRetry(any())` mocks
  updated to `withBoundedRetry(anyString(), any())` (and `getArgument(0)` → `getArgument(1)` in
  their answer lambdas) — mechanical but wide-reaching; full targeted re-run confirmed no
  regressions.
- **Fix 6** — empirical CI-deadlock reproduction attempt. `quiesceAsyncExecutors` temporarily
  short-circuited behind a system property (reverted), temporary entry/exit diagnostic logging added
  to `RadarCompositeCalculationService.onRadarEntrySubmitted`/`ReportGenerationService
  .onReportGenerated` (removed), `platform.development.**` (231 tests) run repeatedly against real
  Testcontainers Postgres with the quiesce disabled. **9 valid consecutive runs, zero
  reproductions** (a 10th run was invalidated by an unrelated concurrent-edit compile error during
  the investigation itself, discarded rather than counted) — confirmed via the diagnostic log that
  both async listeners were genuinely dispatched each run. `quiesceAsyncExecutors` confirmed back to
  its shipped, always-on state. Mechanism status unchanged: closed by structural reasoning, not
  exhaustively proven — see `DatabaseResetTestExecutionListener`'s own Javadoc and the ledger's
  `[CLOSED by skillars-deferred-132 AC1 Fix 6]` note for the full record.

**AC2 (Fixes 7–9):**
- **Fix 7** — `submitReview` and `ReviewFlagService.flag()` both gained method-level
  `@Transactional(propagation = REQUIRES_NEW)`, overriding their class-level default. Both methods'
  existing concurrency ITs needed their "hold the winner open via an outer `TransactionTemplate`"
  technique replaced with a raw-SQL row holder (`holdConflictingReviewRow`/`holdConflictingFlagRow`)
  — REQUIRES_NEW commits as soon as the method returns regardless of any enclosing transaction, so
  the old hold-open technique no longer held. New tests on both methods
  (`concurrentSubmit_calledFromWithinAnOuterTransaction_...`,
  `concurrentFlag_calledFromWithinAnOuterTransaction_...`) prove an outer transaction survives the
  inner DIVE cleanly (no `UnexpectedRollbackException`) while the loser still gets the clean 4xx.
- **Fix 8** — new `PerformanceReportRepository.findStorageKeysByPlayerId` projection replaces the
  full-entity `findByPlayerIdOrderByGeneratedAtDesc(...).forEach(...)` hydration in
  `GdprErasureService.deletePlayerDevelopmentData`; the now-dead repository method deleted. New
  `GdprErasureIT` test (mixed `PENDING_UPLOAD`/`READY` reports) asserts via the `fileStorageService`
  mock interaction, not a post-`erase()` outbox-row query — empirically found mid-implementation that
  `erase()`'s own `AFTER_COMMIT` drain removes the outbox row synchronously before `erase()` even
  returns, so a row-existence assertion would always read zero regardless of correctness.
- **Fix 9** — `lockTimeoutSeconds` now read once per `erase()` call (in `eraseParentChildren`,
  unconditionally before its loop, and in `erase()`'s PLAYER branch) and passed as a parameter into
  `deletePlayerDevelopmentData`, which no longer reads it internally. Closes the PARENT loop's
  N-children repetition; a no-op in practice for the PLAYER branch, which already called the method
  once. 4 new `GdprErasureServiceTest` cases (0/1/3-children PARENT + the existing PLAYER case) pin
  "exactly once" per scenario.

**AC3 (Fix 10):** Both `ReviewFlagService`/`ReviewSubmissionService` config call sites now reference
`ConfigBounds.REVIEWS_AUTO_HOLD_FLAG_THRESHOLD.key()`/`.REVIEWS_SUBMISSION_WINDOW_DAYS.key()` instead
of raw string literals; bounds stay re-typed as literals per this codebase's convention. New pin
tests: a 3rd case in `ReviewFlagServiceTest`, and a new `ReviewSubmissionServiceTest` file (none
existed before).

**AC4 (Fixes 11–12):**
- **Fix 11** — `PessimisticLockRetryerCallSiteAuditTest` gained a second assertion auditing lock
  sites, not just retry-wrapper sites: every NOWAIT `findByIdForUpdate*` call (across the 9 confirmed
  NOWAIT repositories) must co-occur with a `.withBoundedRetry(` in its own file. Four genuinely
  blocking repositories (`VideoQuotaRepository`, `CoachPayoutRepository`, `MessageRepository`,
  `CoachReviewRepository`'s own `findByIdForUpdate`) named as an explicit exemption list. Verified to
  catch a deliberately-introduced unwrapped call in a scratch file (confirmed failure, then removed)
  and to pass cleanly against all four exemptions unmodified.
- **Fix 12** — two new `GdprErasureIT` tests close the previously-untested `(branch × reason)`
  combinations: PARENT × `CHILD_DELETE_LOCK_TIMEOUT` and PLAYER × `CHILD_CONTENDED`, each mirroring
  its corresponding existing test's mechanism for the other branch. All four combinations now
  covered.

**AC5:** `deferred-work.md` closeout — every item this story resolves (Fixes 2–12) marked
`[CLOSED by skillars-deferred-132 ...]` at its original ledger bullet, plus a new
`## Last audit: 2026-09-24 (skillars-deferred-132 dev-story completion)` summary section. Left
explicitly open: Fix 1 (re-confirmed, not closed), the `BoundedKey` missing-`default` residual, the
Stripe→payment reconciliation sweep (distinct from Fix 3's payment→marketplace sweep), and Fix 6's
own `ConditionTimeoutException`-catch-and-proceed residual (>10s async tasks).

### Debug Log

- Two Maven-process-corruption incidents during Fix 6's background investigation loop, both
  self-diagnosed and recovered: (1) editing `GdprErasureService.java`/`PerformanceReportRepository
  .java` mid-flight while the investigation's own background `mvn test` loop was running corrupted
  `target/test-classes` for one run (`ClassNotFoundException: SharedContainers`) — resolved by never
  editing `src/**` files while any other Maven process is active, confirmed via `ps aux` before every
  subsequent edit; (2) a genuine environment-level ~30-minute pause (Hikari's own housekeeper logged
  "Thread starvation or clock leap detected") killed an in-flight regression-sweep Maven process
  mid-run — restarted cleanly from scratch with no code changes needed.
- Fix 7's rewrite of `ReviewSubmissionServiceConcurrencyIT`/`ReviewFlagServiceConcurrencyIT`'s
  "winner holds the row open via an outer `TransactionTemplate`" tests initially appeared to still
  pass post-Fix-7 but ~15–20x slower (52–59s vs. 2–4s) — root-caused to `REQUIRES_NEW` committing the
  winner's row before the outer wrapper's own hold-open latch logic could matter, silently degrading
  the test into the "scheduling-dependent shortcut" its own pre-existing comment warned against.
  Fixed by decoupling the row-holding mechanism from `submitReview`/`flag()`'s own transaction
  boundary entirely (a raw-SQL holder in a plain `transactionTemplate.execute` block).
- Fix 8's new `GdprErasureIT` test initially failed (`outboxCountForReadyKey` expected 1, was 0)
  despite `findStorageKeysByPlayerId` empirically confirmed (via temporary debug logging) to return
  the correct key — root-caused to `erase()`'s own `AFTER_COMMIT` drain firing synchronously and
  removing the outbox row before the test's post-`erase()` query ran; fixed by asserting via the
  `fileStorageService` mock interaction instead, mirroring the file's own established
  `erase_playerUser_deletesPerformanceReportFromS3` pattern.

## File List

**New:**
- `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionTierReconciliationScheduler.java`
- `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionServiceTest.java`

**Modified — production:**
- `src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java`
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java`
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/PerformanceReportRepository.java`
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/PaymentCoachSubscriptionRepository.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/BookingPaymentPersistenceService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeper.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java`
- `src/main/java/com/softropic/skillars/platform/reviews/repo/CoachReviewRepository.java`
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewFlagService.java`
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java`
- `src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java`
- `src/main/java/com/softropic/skillars/platform/video/service/PlaybackService.java`

**Modified — tests:**
- `src/test/java/com/softropic/skillars/config/DatabaseResetTestExecutionListener.java`
- `src/test/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryerCallSiteAuditTest.java`
- `src/test/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryerTest.java`
- `src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java`
- `src/test/java/com/softropic/skillars/platform/admin/service/GdprErasureServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/AvailabilityServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingDuplicationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculatorTest.java`
- `src/test/java/com/softropic/skillars/platform/marketplace/service/CoachProfileServiceConcurrencyIT.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/CaptureReservationTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/ExpiredPackBookingValidationTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/PackSessionServiceParityTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/PackSessionServicePauseTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/PastDueGracePeriodTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeperTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionSchedulerIsolationTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionSchedulerLockTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionServiceConcurrencyIT.java`
- `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewFlagServiceConcurrencyIT.java`
- `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewFlagServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionServiceConcurrencyIT.java`
- `src/test/java/com/softropic/skillars/platform/session/service/DrillUploadServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/video/service/PlaybackRevocationWindowUnitTest.java`
- `src/test/java/com/softropic/skillars/platform/video/service/PlaybackServiceTest.java`
- `src/test/java/com/softropic/skillars/utils/ConcurrencyLockWaitSupport.java`

**Modified — artifacts:**
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

No frontend changes — confirmed via `git status --short`, per this project's `frontend-tests` label
convention (Dev Notes).

## Change Log

- 2026-09-24: Story created via `/bmad-create-story`, branched off `master` post-`skillars-deferred-131`-merge
  (PR #219, squash-merged as `2842df52`). Sourced from a full front-to-back audit of `deferred-work.md`
  (delegated to a research fork to keep the ledger's raw content out of the main session's context)
  covering Genuine one-off bugs & gaps, Lock contention & deadlock risks, Transactional safety,
  Config bounds & enforcement, and Pre-existing design issues, per this project's "do not create
  small stories" convention. All `[CLOSED by ...]`/`[DECIDED: accepted risk ...]` items excluded;
  12 open candidates identified, 7 requiring a real owner decision (resolved live via
  AskUserQuestion, recorded in Context above), 5 mechanical/well-scoped. One candidate (a
  cross-cutting Instancio test-convention sweep) was deliberately left out as out-of-theme scope
  creep. A pre-drafting fact-check on the marketplace-tier reconciliation candidate (Fix 3)
  corrected the ledger's implied "Decision 3 already covers this" framing — Decision 3 only
  prevents payment-row loss, it does not itself re-sync a stale tier on the upgrade path, which
  Fix 3 now closes directly. A second correction narrowed Fix 10's scope after verifying
  `ConfigBounds.java` directly: the two `BoundedKey` constants the ledger implied needed creating
  already exist and are already registered in `HAS_CODE_DEFAULT`/`ALL` — the actual gap is just
  that both call sites reference them by raw string literal instead of by constant, a cheaper and
  lower-risk fix than originally scoped. 5 ACs bundled: AC1 (6 lock-contention/deadlock items,
  including one documentation-only and one investigation-only), AC2 (3 transactional-safety fixes),
  AC3 (1 config-bounds fix), AC4 (2 genuine one-off test-coverage gaps), AC5 (standard ledger
  closeout). Story file:
  `skillars-deferred-132-lock-contention-transactional-safety-and-config-bounds-hardening.md`.
  Branch: `story/deferred-132-lock-safety-hardening`.
- 2026-09-24: Revised per `story-review.md` (adversarial, source-verified senior-dev audit run
  against `HEAD = 68fafa7d`). Every finding was independently re-verified against source before
  applying — no false positives found on spot-check across type signatures, call-site counts, and
  guard existence. Three premises were **false and would have shipped regressions**: Fix 2's "sole
  call site" claim (`CoachReviewRepository.findByIdForUpdate` actually has six; rescoped to a new
  `flag()`-only `findByIdForUpdateNoWait` method rather than converting the shared one); Fix 3's
  tier-comparison snippet (`Enum.equals(String)` is always `false`, so the sweep as drafted would
  have rewritten and WARN-alerted on every active coach on every run; corrected to compare on one
  side of the enum/String boundary with a guarded `valueOf`); and Fix 11's "all sites correctly
  wrapped today" claim (four repositories — `VideoQuotaRepository`, `CoachPayoutRepository`,
  `MessageRepository`, `CoachReviewRepository` — are deliberately blocking with no wrapper; the new
  assertion is now scoped to NOWAIT repositories only, with the four named as an explicit exemption
  list). Fix 4's recommended cross-thread `Future`-based mechanism was found unsound (can create a
  two-transaction deadlock, breaks a typed-exception discrimination built across deferred-128/129)
  and replaced with a same-thread-only direction. Fix 7 was widened to also cover
  `ReviewFlagService.flag()`, which has the identical, deliberately-mirrored trap. Fix 3 gained an
  explicit carve-out for a live billing-divergence path in `persistCoachTierUpgrade` that the
  original draft's Context never traced, plus a distinct still-open Stripe→payment reconciliation
  residual now named in AC5 rather than risking a false closure. Fix 1's reachability argument was
  rewritten around a guard that does not exist in this codebase, replaced with a disjoint-lock-sets
  argument that is both accurate and more durable. Fix 6's reproduction protocol was corrected to
  actually reproduce something (the fix under test, `quiesceAsyncExecutors`, was already wired in
  before every reset, making the original steps circular). Fixes 9, 10, and 12 had incorrect
  coverage/fail-fast/call-site claims corrected without changing their underlying (correct)
  conclusions. Full findings retained in `story-review.md` for reference.
- 2026-09-24: Dev-story implementation complete (`/bmad-dev-story`). All 12 fixes across 5 ACs
  implemented, tested, and independently re-verified against real Testcontainers Postgres where
  concurrency/transaction semantics were in play (not just unit-level mocks). Two implementation-time
  corrections found and fixed before completion, neither present in the story's own drafted text:
  (1) Fix 7's `REQUIRES_NEW` change silently broke both `ReviewSubmissionServiceConcurrencyIT`'s and
  `ReviewFlagServiceConcurrencyIT`'s pre-existing "hold the winner open via an outer
  `TransactionTemplate`" row-holding technique (the winner's row now commits as soon as the method
  returns, regardless of the outer wrapper) — rewritten to a raw-SQL row holder decoupled from either
  method's own transaction boundary; (2) Fix 8's new outbox-enqueue test initially failed because
  `erase()`'s own `AFTER_COMMIT` drain removes the outbox row synchronously before a post-`erase()`
  row-count query can observe it — fixed by asserting via the `fileStorageService` mock interaction
  instead, mirroring this file's own established pattern. Fix 4 (GDPR erasure connection-acquisition
  bound, deferred and re-confirmed as accepted risk across four prior stories: 128→131) is closed via
  a same-thread `HikariPoolMXBean` pre-check — the cheaper of the two mechanisms this story's own
  Dev Notes deliberately left open, avoiding the costlier secondary-EntityManagerFactory option's
  "take over JPA bootstrapping for the whole app" scope. Fix 6's empirical CI-deadlock reproduction
  attempt (9 valid consecutive local runs, `platform.development.**`, quiesce temporarily disabled)
  did not reproduce the race — mechanism status unchanged from skillars-deferred-131's own structural
  diagnosis, now with a documented, bounded attempt behind it. Full targeted regression sweep (Task
  15) green throughout: `platform.reviews.**`, `platform.payment.**` (349 tests),
  `platform.admin.**` (173 tests, including `GdprErasureIT`'s 32), `platform.development.**` (231
  tests), `PessimisticLockRetryerCallSiteAuditTest`, `ConfigBoundsEnumCoverageTest`,
  `ConfigStartupAssertionTest`. `deferred-work.md` closed out per AC5, with Fix 1, the `BoundedKey`
  residual, the Stripe→payment reconciliation sweep, and Fix 6's own `ConditionTimeoutException`
  residual left explicitly open. No `mvn verify` run locally, per project convention. Status →
  `review`.

---

## Code Review (bmad-code-review + txn-and-concurrency-audit)

**Review Date:** 2026-09-24  
**Layers:** Blind Hunter, Acceptance Auditor, Txn/Concurrency Audit (Edge Case Hunter failed on schema)  
**Group 1 (Story & Documentation):** 2,380-line diff across 4 files

### CRITICAL Findings (1)

**C1: ConfigBounds test will fail on 0L defaults with non-zero minimums**
- **File:** `ConfigBoundsEnumCoverageTest.java` (implied in test assertions)
- **Issue:** The new test `hasCodeDefaultKeysDefaultIsWithinItsOwnBounds` asserts all HAS_CODE_DEFAULT keys have defaultValue within [min, max] bounds. However, 4 keys have 0L defaults but min>0:
  - BOOKING_QUICK_COMPLETE_TIMEOUT_HOURS: [1L, 168L] with 0L default
  - MODERATION_SLA_MINUTES: [1L, 10080L] with 0L default
  - MODERATION_LOCK_TIMEOUT_MINUTES: [1L, 1440L] with 0L default
  - VIDEO_RESERVATION_TIMEOUT_MINUTES: [1L, 1440L] with 0L default
- **Impact:** Test assertion `isBetween(k.min(), k.max())` fails at runtime.
- **Fix:** Either (a) exclude these 4 keys from HAS_CODE_DEFAULT (use 0L as uninitialized sentinel), or (b) update bounds/defaults to allow 0L. Verify intent before patching.
- **Status:** Unresolved — requires decision.

### MEDIUM Findings (5)

**M1: Pool saturation exceptions misclassified as lock contention**
- **File:** `GdprErasureService.java:515-534`
- **Issue:** When HikariCP pool saturates, `assertConnectionPoolNotSaturated` throws `PessimisticLockingFailureException`, caught as CHILD_CONTENDED and logged as "lock was genuinely contended." Misleads admins about root cause (resource exhaustion vs. lock contention).
- **Impact:** Incorrect alert/diagnostic framing.
- **Fix:** Introduce distinct exception type or marker field to distinguish pool saturation from lock contention at catch site.
- **Status:** Unresolved — requires implementation.

**M2: insertErasureAlertIfAbsent only catches DataIntegrityViolationException**
- **File:** `GdprErasureService.java:616-631`
- **Issue:** Catch block only handles constraint-race violations. Other DB errors (connection failure, serialization error, CHECK constraint violation on alert type) propagate and roll back entire REQUIRES_NEW transaction in `markFailed`, coupling alert persistence to request status updates.
- **Impact:** If alerting fails for other reasons, GdprRequest never gets marked FAILED; state inconsistency.
- **Fix:** Broaden exception handling OR decouple alert write from status update (consider separate outbox pattern if alert persistence is critical).
- **Status:** Unresolved — requires implementation decision.

**M3: Potential null dereference in maybeAlertOrphanedLiveSubscription logging**
- **File:** `StripeWebhookService.java:52-54`
- **Issue:** Catch block logs `sub.getId()` directly: `log.warn("[STRIPE_WEBHOOK_ORPHAN_ALERT_FAILED stripeSubId={}]", sub.getId(), e)`. If getId() returns null, logging emits null instead of safe placeholder.
- **Impact:** Reduced debuggability in logs.
- **Fix:** Use `Objects.requireNonNullElse(sub.getId(), "unknown")` or add null check before logging.
- **Status:** Unresolved — requires implementation.

**M4: AdminQueueService.getAlerts N+1 query pattern**
- **File:** `AdminQueueService.java:42-74`
- **Issue:** Initial query fetches alert rows, then per-alert loop calls `buildSummary`, which issues 1-2 queries per alert (messageRepository, conversationReportRepository, reviewFlagRepository). Worst case: 1 + (20 × 2) = 41 queries per page.
- **Impact:** Scalability concern; works for small page size (20) but not efficient.
- **Fix:** Batch-fetch all message/conversation/review details in one query each using IN clauses, join in memory during DTO construction.
- **Status:** Unresolved — requires implementation.

**M5: Missing migration file V153 verification needed**
- **File:** Implied in diff; migration file not directly visible
- **Issue:** Diff adds SUBSCRIPTION_ORPHANED to AdminAlertType enum. New test `adminAlertsTypeCheckConstraint_containsEveryAdminAlertTypeEnumValue` expects DB CHECK constraint to include it. Migration V153__admin_alerts_subscription_orphaned_type.sql is referenced in code comments but not confirmed in diff.
- **Impact:** If migration doesn't properly update CHECK constraint, test fails at runtime; type enum/constraint divergence.
- **Fix:** Verify (a) V153 is present in commit, (b) it properly alters admin_alerts.type CHECK constraint to include 'SUBSCRIPTION_ORPHANED'. If missing, add migration.
- **Status:** Unresolved — requires verification.

### LOW Findings (3 — Deferred)

**L1: StripeCustomerRepository.findByStripeCustomerId returns List, not Optional**
- **Status:** DEFER — Pre-existing design choice; callers already correctly check isEmpty() before access.

**L2: maybeAlertOrphanedLiveSubscription takes first result without uniqueness guarantee**
- **Status:** DEFER — Pre-existing data model; latent only if multiple parents map to same Stripe customer (unlikely in practice).

**L3: GdprErasureService.eraseParentChildren loads entire children list into memory**
- **Status:** DEFER — Low risk; deadline filtering mitigates impact. Typical parent child counts unlikely to cause memory pressure.

### Verified CORRECT (5 — No Action)

✅ GdprErasureService: REQUIRES_NEW isolation prevents cross-transaction corruption  
✅ GdprErasureService: TOCTOU handling is explicit and documented  
✅ StripeWebhookService: Grace window mitigates provisioning race correctly  
✅ GdprErasureService: Lock ordering serialized via pessimistic lock (prevents deadlock)  
✅ StripeWebhookService: Webhook idempotency with ON CONFLICT DO NOTHING is atomic  
✅ ConfigBounds: Lock timeouts have documented reasoning and correct bounds

### Summary

- **1 CRITICAL** (immediate)
- **5 MEDIUM** (high priority, 4 patches + 1 verification)
- **3 LOW** (deferred)
- **5 CORRECT** (confirmed safe)
- **1 FAILED LAYER** (Edge Case Hunter schema validation error)

**Next Steps:** Continue review of Groups 2–7, or address findings above before proceeding.
