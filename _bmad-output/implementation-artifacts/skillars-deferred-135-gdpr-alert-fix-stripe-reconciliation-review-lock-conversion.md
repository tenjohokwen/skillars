# Story: GdprErasureService Alert-Catch Fix, Stripe→Payment Reconciliation Sweep & Review-Lock NOWAIT Conversion

**Story Key:** `skillars-deferred-135-gdpr-alert-fix-stripe-reconciliation-review-lock-conversion`
**Epic:** Deferred Work
**Priority:** High (one genuine, provable correctness bug of the identical shape `skillars-deferred-134`
AC1 just fixed, shared by both of `GdprErasureService`'s own alert-raising paths; one durable
reconciliation sweep closing a gap explicitly named as "a separate story" across four consecutive prior
stories; one lock-contention conversion "armed, not pulled" since `skillars-deferred-130`).
**Status:** ready-for-dev
**Created:** 2026-09-25

---

## Context

Master is at `4c0a9316` (`skillars-deferred-134`, PR #228, merged; plus an unrelated infra fix, PR #229,
replacing MinIO with SeaweedFS in the test/dev/UAT stack after `quay.io/minio/minio` stopped serving
anonymous pulls — irrelevant to this story's own scope, noted only so `HEAD` makes sense to whoever reads
this later). Per this project's own standing convention ("read same-day code-review deferrals before
drawing scope"), this story is sourced from `skillars-deferred-134`'s own freshly-written ledger entry
(`deferred-work.md:3833-3842`) plus a fresh mining pass over the rest of the ledger's still-open items.

**Three owner decisions taken live (AskUserQuestion) during drafting:**

1. **Stripe→payment reconciliation gap (open since `skillars-deferred-131`, re-confirmed by 132/133/134)
   — build the durable fix now, not another alert-only extension or a further decline.** →
   **Build a genuine scheduled reconciliation sweep.** `skillars-deferred-133`/`134` shipped alert-only,
   webhook-triggered detection for a coach whose Stripe subscription has drifted from the local
   `payment.coach_subscriptions` table — that only fires when a relevant webhook actually arrives, and
   does nothing for a coach whose subscription went orphaned before any webhook fired, or if a webhook
   was ever missed. This story closes that residual for real: a scheduled sweep calls Stripe's own
   list-subscriptions API and diffs against the local table.
2. **`CoachReviewRepository.findByIdForUpdate`'s 5 remaining blocking call sites (armed since
   `skillars-deferred-130`, re-confirmed at 131/132/133) — convert now or keep deferring.** →
   **Convert all 5 now**, mirroring `ReviewFlagService.flag()`'s own already-shipped
   `findByIdForUpdateNoWait` + `PessimisticLockRetryer` pattern (`skillars-deferred-132` AC1 Fix 2).
3. **`ConfigBounds.BoundedKey`'s full call-site migration (the literal-bounds-to-registry-lookup
   refactor, not the `defaultValue` field itself — that field was already added, registry-only, by
   `skillars-deferred-133` AC2) — force it in, or decline a 4th consecutive time?** → **Decline again.**
   Nothing new has surfaced since the last decline (`deferred-work.md:3293-3294`) to change the
   calculus; this story's own scope is already substantial across its other two decisions.

### AC1's origin — the exact bug `skillars-deferred-134` itself flagged as unproven

`skillars-deferred-134`'s own dev-story record (`deferred-work.md:3833-3842`, also its own story file's
"Independent Re-Verification" section) found and fixed a real bug in `AdminAlertEventListener.insertAlert`:
catching `DataIntegrityViolationException` *inside* a `requiresNewTemplate.executeWithoutResult(...)`
callback does not work, because once `saveAndFlush`'s flush throws, Hibernate marks the underlying
`EntityTransaction` rollback-only regardless of whether the translated exception is caught in application
code — `TransactionTemplate`'s own `commit()` then throws `UnexpectedRollbackException` right back out,
defeating the isolation entirely. The fix: let the exception propagate *out* of the `REQUIRES_NEW`
callback (so `TransactionTemplate` rolls the isolated transaction back, not commits it, and re-throws the
*original* `DataIntegrityViolationException` unchanged), and catch it in the caller's own method body,
outside `executeWithoutResult(...)`.

That story's own record flags, in its own words: *"`GdprErasureService.raiseErasureAlert`/`markFailed`
most likely share this exact same latent bug (`insertErasureAlertIfAbsent`'s try/catch is INSIDE the
`REQUIRES_NEW` boundary in both call shapes, identical to `AdminAlertEventListener`'s pre-134 shape) —
never actually proven either way … a future story should add the `GdprErasureService` analog of
`AdminAlertEventListenerConcurrencyIT`."* This story is that future story.

**Independently re-verified against current `HEAD` (`GdprErasureService.java`), not assumed:**

- `insertErasureAlertIfAbsent` (`:624-644`) — its own `try { ...; adminAlertRepository.saveAndFlush(alert);
  } catch (DataIntegrityViolationException e) { ... }` (`:631-643`) is a plain, non-transactional private
  helper. The `catch`'s reachability depends entirely on *where its caller puts the transaction boundary*
  — exactly the property `AdminAlertEventListener.insertAlert`'s pre-134 shape got wrong.
- `raiseErasureAlert` (`:583-585`): `requiresNewTemplate.executeWithoutResult(status ->
  insertErasureAlertIfAbsent(requestId, reason));` — the `catch` (inside `insertErasureAlertIfAbsent`) sits
  **inside** the `REQUIRES_NEW` callback. Same shape `AdminAlertEventListener.insertAlert` had before
  `skillars-deferred-134` fixed it.
- `markFailed` (`:410-418`) is itself `@Transactional(propagation = Propagation.REQUIRES_NEW)` — an
  **annotation-based** `REQUIRES_NEW`, not a `TransactionTemplate` callback — and calls
  `insertErasureAlertIfAbsent(requestId, UNCLASSIFIED_FAILURE)` directly (`:417`) from inside that
  proxy-managed transaction. The failure mode is identical even though the mechanism differs: once
  `saveAndFlush` throws inside the proxy-advised method body, Hibernate still marks that same transaction
  rollback-only, and the AOP proxy's own commit-time logic still throws `UnexpectedRollbackException` back
  at `markFailed`'s own caller, regardless of the `catch` inside `insertErasureAlertIfAbsent` having
  already handled the exception at the application level.

**Callers of each path, confirmed by reading the file:**

- `raiseErasureAlert` is called from three places inside `eraseTransactional`/`eraseParentChildren`: the
  PLAYER branch's two catch blocks (`CHILD_VANISHED`, `CHILD_DELETE_LOCK_TIMEOUT`/`CHILD_CONTENDED`,
  `:297,:321`) and `eraseParentChildren`'s own deadline-exceeded throw path (`:483`) plus its per-child
  catch blocks (`:514,:536`). All of these run **inside `eraseTransactional`'s own
  `@Transactional(REQUIRES_NEW)` transaction** (`:197`) — a genuinely concurrent race here (two *different*
  `GdprRequest`s somehow raising an alert for the identical `(requestId, GDPR_ERASURE_DEADLINE)` slot at the
  same time) would poison `eraseTransactional`'s own transaction, silently discarding the rest of that
  erasure run (anonymisation, refresh-token revocation, etc. that hadn't committed yet) — the same
  "actively defeats the isolation's own purpose" failure `skillars-deferred-134`'s own AC1 diagnosed for
  `AdminAlertEventListener`.
- `markFailed` is called from `GdprEventListener`'s own exception-handling path when `erase()` throws
  (read `GdprEventListener.java` before starting — not modified by this story, but its call shape
  determines what "the caller" means for `markFailed`'s own race).

**A structural nuance this story's own test-design must confront, not assumed away:** unlike
`AdminAlertEventListener.insertAlert`, which has *seven independent public callers* that can genuinely run
concurrently for the *same* `(referenceId, type)` slot (multiple users reporting the same message, flagging
the same review, etc.), `GdprErasureService`'s own alert-raising paths are keyed by `requestId` — a single
`GdprRequest`'s own id — and `eraseParentChildren`'s loop over a PARENT's children is a plain sequential
`for` loop, not concurrent. **There is no obvious pair of genuinely independent, always-concurrent public
callers that race for the identical `(requestId, reason)` dedup slot the way two different
`MessagingReportService.reportMessage` callers do for `AdminAlertEventListener`'s `AdminAlertEventListenerConcurrencyIT`.**
Two *different* `GdprRequest`s never collide (different `requestId` → different `referenceId` → no unique-
index collision). The dev-story phase must find (or construct) a genuinely concurrent scenario before
writing the proof test — options to investigate, in rough order of preference, **none pre-approved, this
is exactly the kind of thing `skillars-deferred-134`'s own dev-story "found and corrected during
implementation, not assumed"**:
  - Two duplicate `GdprRequest` rows somehow existing for the same user (is this reachable? check
    `GdprRequestService`'s own request-creation path for a uniqueness guard) processed concurrently —
    if genuinely reachable, this gives two *different* `requestId`s and does NOT exercise the bug (no
    collision). Rule this out explicitly if it's the first idea considered.
  - A single `erase()` run whose `eraseParentChildren` loop raises `CHILD_VANISHED` for one child and (on
    a *second*, genuinely concurrent thread somehow entering the same method for the same `requestId`)
    something else for the same request — this needs the loop's own sequential nature bypassed, which
    likely means testing `raiseErasureAlert`/`insertErasureAlertIfAbsent` more directly rather than
    through the full public `erase()` entry point (both are `private` — consider whether a
    package-visible test seam is justified, mirroring how `skillars-deferred-128`'s own test seam
    (`ReflectionTestUtils.setField`) was accepted for `gdprEraseLockBudget` when no public seam existed).
  - A genuinely concurrent `raiseErasureAlert` call racing a genuinely concurrent `markFailed` call for
    the *same* `requestId` (e.g., `eraseParentChildren` raises `CHILD_CONTENDED` for a child on one thread
    while, implausibly but perhaps constructible in a test, `GdprEventListener` independently invokes
    `markFailed` for the same `requestId` on another) — investigate whether this is realistically
    reachable or purely synthetic; if purely synthetic, that is an acceptable, disclosed choice as long as
    it is disclosed as such (this project's own convention: say plainly when a proof is synthetic, not
    reachable in practice, per `skillars-deferred-132` AC1 Fix 6's own precedent for "closed by structural
    reasoning, not exhaustively proven").
  - Whatever fixture is chosen, the required assertions are the same shape as
    `AdminAlertEventListenerConcurrencyIT`'s: (a) neither racer's own call throws; (b) whatever primary
    work each racer was doing durably persists (re-read from the database, not the in-memory return
    value) — the actual bug this AC fixes is a racer's own legitimate progress being silently rolled back;
    (c) exactly one `OPEN` `admin_alerts` row exists for the `requestId`.

### The fix (mirror `AdminAlertEventListener.insertAlert`'s shipped mechanism exactly)

Read `AdminAlertEventListener.java`'s current `insertAlert` method and its own inline comment
(`:149-168`) before starting — it is the primary reference for the corrected mechanism, more detailed than
this story repeats.

- **`raiseErasureAlert` (`:583-585`):** move the `try { ... } catch (DataIntegrityViolationException e)
  { ... }` to wrap the `requiresNewTemplate.executeWithoutResult(...)` call itself, not to sit inside its
  lambda. `insertErasureAlertIfAbsent` keeps calling `saveAndFlush` unconditionally (no try/catch inside
  it any more for this call path — see below, `markFailed` still needs one).
- **`markFailed` (`:410-418`):** this is the harder half. It is currently a plain
  `@Transactional(propagation = REQUIRES_NEW)` **annotation**, not a `TransactionTemplate`. For the catch
  to genuinely sit *outside* the `REQUIRES_NEW` transaction boundary, `markFailed`'s own alert-raising call
  needs the same `requiresNewTemplate.executeWithoutResult(...)` + outside-catch shape `raiseErasureAlert`
  uses — **not** the class's own annotation-based `REQUIRES_NEW`. Read `markFailed`'s own Javadoc
  (`:396-409`) first: it explicitly calls `insertErasureAlertIfAbsent` directly, not through
  `raiseErasureAlert`, specifically to avoid opening a *second* concurrently-held connection from the same
  pool right when `erase()`'s own `assertConnectionPoolNotSaturated` pre-check just reported the pool
  saturated — **that reasoning does not change**: `markFailed`'s own status-update
  (`gdprRequestRepository.findById(requestId).ifPresent(...)`) and its alert-raise still need to run
  together without opening two separate `REQUIRES_NEW` transactions on top of an already-fragile pool.
  Design this carefully during implementation — one plausible shape: keep `markFailed` itself
  `@Transactional(REQUIRES_NEW)` for the status update, but restructure so the
  `insertErasureAlertIfAbsent` call and its catch happen in the *outer* (non-transactional) `markFailed`
  wrapper if one can be introduced without duplicating `assertConnectionPoolNotSaturated`'s own
  reasoning — or route `markFailed`'s alert-raise through a shared private helper that takes an explicit
  `TransactionTemplate`/catch shape parameterized by whether a fresh connection is safe to open, given the
  pool-saturation context that motivated the original direct-call design. **Do not silently regress
  `markFailed`'s own documented pool-conservation reasoning while fixing this bug — both properties must
  hold simultaneously; if they turn out to be in real tension, surface that explicitly rather than quietly
  picking one.**
- `insertErasureAlertIfAbsent` itself (`:624-644`) needs no signature change — same as
  `AdminAlertEventListener.insertAlert`'s own extraction, the write-and-flush stays inside, only the catch
  moves to wherever each caller's own transaction boundary now sits.

### Test plan

New `GdprErasureServiceConcurrencyIT` (real Testcontainers Postgres, package
`com.softropic.skillars.platform.admin.service`, mirroring `AdminAlertEventListenerConcurrencyIT`'s own
file structure/`AbstractIntegrationTest` base) — see the "structural nuance" callout above for the fixture
design this test still needs to work out; do not copy `AdminAlertEventListenerConcurrencyIT`'s own fixture
verbatim, since (as documented above) it relies on two independent public callers this class's own API
doesn't offer for the identical slot.

**Mutation-check before considering AC1 done — same two mutations `skillars-deferred-134`'s own AC1
required, applied to both `raiseErasureAlert` and `markFailed`:**
1. Revert the catch-outside fix back to catch-inside for each path: confirm the new IT fails with
   `UnexpectedRollbackException` (or the annotation-based equivalent for `markFailed`).
2. Confirm the fix does not regress `markFailed`'s own pool-conservation property — re-read
   `assertConnectionPoolNotSaturated`'s own Javadoc and, if the chosen fix shape opens any new connection
   acquisition on the `markFailed` path that wasn't there before, add or extend a test proving the
   pool-saturation pre-check still runs before it (mirroring whatever existing test proves this today for
   `markFailed`'s current shape — find it first).

Also update `GdprErasureServiceTest`'s existing Mockito-based tests if any assert on `saveAndFlush`/`save`
interactions in a way this restructuring changes (read the existing file first).

---

## AC2: Stripe→payment reconciliation sweep — the durable fix for a residual named across four stories

**Files:**
- New: a reconciliation method on `SubscriptionService` (mirror `reconcileMarketplaceTiers`'s own
  placement under its `// ─── Scheduled: Marketplace Tier Reconciliation ──────────────────────────────`
  section header style) plus a new thin `@Scheduled`/`@SchedulerLock` wrapper class, mirroring
  `SubscriptionTierReconciliationScheduler`'s own two-file split (thin scheduler class delegates to the
  service's own method).
- `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java`
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java` (new key)

### The gap this closes (verified against current `HEAD`)

`SubscriptionService.reconcileMarketplaceTiers` (`:613-646`) — `skillars-deferred-132`'s own sweep — has
this exact residual documented in its own Javadoc (`:608-611`): *"This is a payment → marketplace sweep
only … It does NOT close the separate, still-open Stripe → payment reconciliation residual (a coach with
a Stripe subscription but no local `payment.coach_subscriptions` row) the ledger calls for elsewhere."*
That residual has been re-confirmed, unclosed, across `skillars-deferred-131` (`deferred-work.md:3406-3413`,
originating bullet), `132` (`:3408-3413`, re-confirmed), and `133`/`134` (alert-only detection shipped,
explicitly not the reconciliation sweep itself — `deferred-work.md:3414-3438`).

The gap: `StripeWebhookService.maybeAlertOrphanedLiveSubscription`/`maybeAlertOrphanedInvoicePaymentFailed`
only fire when a relevant Stripe webhook event actually arrives for the orphaned subscription. A coach
whose local row was deleted/never-created for some other reason, with no subsequent webhook event ever
touching that specific subscription again, generates no signal at all, indefinitely. A scheduled sweep
that asks Stripe directly (rather than waiting for Stripe to tell us) is the durable fix — this is the
mechanism the ledger's own bullet always described as "a separate story," now that separate story.

### The fix — design requirements (read the actual code first, then design for real; nothing below is a
pre-verified implementation, it is a spec for what dev-story must build and empirically confirm)

1. **Read `StripeWebhookService.maybeAlertOrphanedLiveSubscription`'s full method
   (`StripeWebhookService.java`, currently `:215-252` — re-verify against `HEAD` at implementation time)
   before writing a line of new code.** Reuse its exact resolution chain and constants — do not
   reimplement: `LIVE_SUBSCRIPTION_STATUSES` (`:59`, `Set.of("active", "trialing", "past_due")`),
   `SUBSCRIBE_RACE_GRACE_WINDOW` (`:69`, 10 minutes), the `stripeCustomerRepository.findByStripeCustomerId`
   → `StripeCustomer.parentId` → `coachProfileRepository.findByUserId` chain, and the final
   `eventPublisher.publishEvent(new CoachSubscriptionOrphanedEvent(this, coachId, sub.getId()))` →
   `AdminAlertEventListener.onCoachSubscriptionOrphaned` → `AdminAlertType.SUBSCRIPTION_ORPHANED` path.
   The sweep's own per-subscription orphan check should call the *same* logic (extract a shared method
   both the webhook handler and the new sweep call, rather than duplicating the resolution chain a third
   time), unless the two call shapes turn out to have genuinely different preconditions the extraction
   can't cleanly share — investigate before assuming a shared method is trivial.
2. **List live Stripe subscriptions.** Pinned SDK: `stripe-java:28.4.0` (`pom.xml:224`, confirmed). Use
   `Subscription.list(SubscriptionListParams...)` with its own status filter/pagination — read the pinned
   jar's own `Subscription`/`SubscriptionListParams`/`SubscriptionCollection` classes (or the SDK's own
   javadoc jar if attached) to confirm the exact call shape and pagination mechanism
   (`SubscriptionCollection.autoPagingIterable()` is the SDK's own standard pattern for this — confirm it
   exists on this pinned version before relying on it). Filter to the same `LIVE_SUBSCRIPTION_STATUSES`
   the webhook path already uses (do not invent a different status set).
3. **Diff against local state.** For each live Stripe subscription, resolve customer → coach via the
   shared resolution chain from (1); if it resolves to a real `CoachProfile`, check
   `paymentCoachSubscriptionRepository.findByCoachId(coachId)` (or by `stripeSubId`, whichever the shared
   resolution method naturally returns) — if absent (or present but with a different/stale
   `stripeSubscriptionId`, worth deciding during implementation whether that second case belongs in this
   sweep's scope or is `reconcileMarketplaceTiers`'s job instead — investigate, don't assume), apply the
   same `SUBSCRIBE_RACE_GRACE_WINDOW` check the webhook path already does, then raise the same
   `SUBSCRIPTION_ORPHANED` alert. `insertAlert`'s own dedup (per `(referenceId, type, OPEN)`, this AC1's
   own fixed mechanism) means a coach already alerted via a webhook won't get a redundant second alert from
   this sweep finding the same drift.
4. **Explicitly NOT auto-heal**, matching `skillars-deferred-133` AC3's precedent cited in its own ledger
   entry: `SubscriptionService.java:663`'s own comment documents the deliberate no-`priceId`→tier
   reverse-map constraint — this sweep alerts, a human reconciles, exactly like the webhook path.
5. **Pagination and rate limits.** Check whether any existing Stripe SDK usage in this codebase (grep
   `StripeWebhookService.java`, `PaymentConfig.java`, `StripeOnboardingService.java`, and any
   `StripePaymentGateway*` class) already has an established pattern for handling
   `com.stripe.exception.RateLimitException` or similar — if none exists, this sweep is the first one to
   need it; a simple bounded-retry-with-backoff (or, if the coach volume this application handles makes a
   single list call unlikely to ever approach Stripe's rate limits in practice, an explicit note that this
   is accepted for now and revisited if volume ever warrants it) is acceptable — decide and document
   during implementation, do not silently skip the question.
6. **`@SchedulerLock` sizing.** Mirror `SubscriptionTierReconciliationScheduler`'s own Javadoc style
   (`lockAtMostFor`/`lockAtLeastFor` derived from real worst-case arithmetic: per-subscription cost ×
   expected worst-case subscription volume, not a guessed round number) — this sweep's own worst case is
   dominated by Stripe API round-trip latency per page, not local DB work, so size it from that, not from
   `reconcileMarketplaceTiers`'s own DB-only arithmetic. Schedule at a cadence that doesn't collide with
   the existing payment schedulers (`SubscriptionChangeApplicator` 02:00, `SubscriptionGracePeriodChecker`
   03:00, `SubscriptionTierReconciliationScheduler` 04:00) — 05:00 is the natural next slot, but confirm no
   other scheduler already claims it.
7. **New `ConfigBounds` key** for whatever needs to be configurable (at minimum, consider whether the
   cadence itself should be config-driven the way lock timeouts are elsewhere in this codebase, or whether
   a fixed cron expression — matching the three existing payment schedulers' own fixed-cron precedent — is
   sufficient and a new config key is unwarranted scope; if a key genuinely is needed — e.g. a
   Stripe-API-page-size bound — add it `HAS_CODE_DEFAULT`, following `GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS`'s
   own registration shape (`ConfigBounds.java:302` and its `HAS_CODE_DEFAULT` set membership,
   `:352-370`) — registry-only `defaultValue`, no migration needed, matching this codebase's own
   established convention).

### Test plan

Read an existing WireMock-backed Stripe test first — `BasePaymentIT` (provides a real `WireMockServer` for
`stripe-service` via `@InjectWireMock`) is this codebase's established pattern for a real-HTTP-shaped
Stripe integration test; `StripeWebhookVerificationTest` mocks at a different layer (check which, and
whether it's more appropriate for a unit-style test of the new sweep's per-subscription logic vs. a real
`BasePaymentIT`-based IT for the actual `Subscription.list(...)` HTTP call and pagination). At minimum:
- A new IT (extending `BasePaymentIT` or equivalent) stubbing Stripe's `GET /v1/subscriptions` to return a
  live subscription with no matching local `payment.coach_subscriptions` row → confirms an alert is raised
  through the real event/listener chain, re-read from the database (not a mock interaction count).
- A matched case (local row exists) → no alert.
- The grace-window suppression case, mirroring the webhook path's own existing test.
- Pagination: stub a multi-page response (if `autoPagingIterable()` or equivalent is used) and confirm
  every page is actually processed, not just the first.
- A duplicate-detection case: a coach already alerted via `StripeWebhookVerificationTest`'s own webhook
  path should not get a second alert from this sweep for the same drift (this exercises AC1's own fixed
  dedup mechanism from the sweep's side, not just the webhook's).

---

## AC3: Convert `CoachReviewRepository`'s 5 remaining blocking `findByIdForUpdate` call sites to NOWAIT

**Files:**
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminReviewService.java` (2 sites)
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewModerationService.java` (1 site —
  **see the critical caveat below before touching this one**)
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java` (2 sites)
- `src/main/java/com/softropic/skillars/platform/reviews/repo/CoachReviewRepository.java` (Javadoc update)
- `src/test/java/.../PessimisticLockRetryerCallSiteAuditTest.java` (expected-count bump)

### Current state (re-verified against `HEAD`, exact current line numbers — confirmed unchanged since
`skillars-deferred-133` AC4's own annotation at `deferred-work.md:3569-3570`)

`CoachReviewRepository.findByIdForUpdate` (`CoachReviewRepository.java:25-27`, a plain blocking
`@Lock(PESSIMISTIC_WRITE)` query) is still called, genuinely blocking, at exactly 5 sites:
- `AdminReviewService.java:81` (`approveReview`) and `:121` (`blockReview`) — "admin-double-click race"
  guards, straightforward, no special caveat.
- `ReviewSubmissionService.java:129` (`updateReview`) and `:164` (a second method — read both call sites'
  own surrounding comments before converting).
- `ReviewModerationService.java:102` — **read the caveat below before converting this one.**

This decision was originally raised at `skillars-deferred-130`'s own code review
(`deferred-work.md:3535-3549`, `[DECIDED 2026-09-23 (owner): accepted for now]` — converting the shared
method was costed at "changing a shared repository method used by four call sites, wrapping each in
`PessimisticLockRetryer`, and adding a contention test per site — module-wide work that does not belong in
a narrow lock-fix story"), then re-triaged at `skillars-deferred-131` (M5-2, `:3555-3574`, "trigger
partially discharged" once `ReviewFlagService.flag()` itself got its own dedicated NOWAIT method,
`findByIdForUpdateNoWait`, used only by that one call site) and left explicitly "armed, not pulled" at
`132`/`133`. This story pulls it.

### ⚠️ Critical caveat, found while re-verifying this AC's own premise — read before implementing

`CoachReviewRepository.findByIdForUpdateNoWait`'s own comment (`:29-35`) says: *"converting the shared
method in place would have silently fail-fast-converted those five blocking sites with no retry, **one of
which (`ReviewModerationService.handleReviewSubmitted`) relies on the lock genuinely blocking**, per its
own comment."* Read `ReviewModerationService.java` around its `findByIdForUpdate` call (`:92-102`) —
its own comment explains *why the lock is taken* (a Gemini moderation call runs for seconds outside any
transaction; the locked read afterward must see any admin decision recorded in the meantime, not a stale
pre-Gemini-call snapshot) but does **not** itself explain why the *wait must be unbounded* rather than
bounded-with-retry.

**The owner's decision was to convert all 5, including this one** — this is very likely still safe,
because `ReviewFlagService.flag()`'s own already-shipped conversion (`skillars-deferred-132` AC1 Fix 2)
did not simply switch to NOWAIT-and-immediately-fail; it wraps the NOWAIT attempt in
`PessimisticLockRetryer.withBoundedRetry(...)`, which retries *in place* on a bounded budget — behaviorally
close to blocking for realistic contention windows (an admin's `approveReview`/`blockReview` transaction is
short), while adding a hard ceiling unbounded blocking doesn't have. **But this must be empirically
confirmed for `ReviewModerationService` specifically, not assumed from the other four's straightforward
conversions**, per this project's own "verify, don't guess" convention:
- Write a dedicated concurrency test for `ReviewModerationService`'s own AFTER_COMMIT-listener path racing
  a concurrent `AdminReviewService.approveReview`/`blockReview` call for the same review — the exact
  scenario `ReviewModerationService`'s own comment worries about (an admin resolving the review while the
  Gemini call is in flight). Confirm the moderation verdict is still correctly applied (or correctly
  discarded via the epoch/status guards already in the method, `:110-134`) after the NOWAIT+retry
  conversion, under a realistic contention window, not silently dropped by an exhausted retry budget.
- If this test reveals the retry budget is genuinely insufficient for this specific site's real-world
  timing characteristics (e.g., because the admin's own transaction can legitimately run longer than the
  other four sites' admin actions), **surface that as a finding, not a silent workaround** — options
  include a per-call-site retry budget override (check whether `PessimisticLockRetryer.withBoundedRetry`
  already supports one) or, if genuinely necessary, leaving this one site as the sole exception (4
  converted, not 5) with an explicit, disclosed reason — a corrected scope, not a silently-abandoned
  owner decision, mirroring how `skillars-deferred-134`'s own AC1 was corrected mid-implementation when its
  original fix design turned out not to work as drafted.
- Update `CoachReviewRepository.findByIdForUpdateNoWait`'s own comment once the conversion lands — it will
  no longer be accurate that "the other five call sites … stay genuinely blocking."

### The fix (for the 4, or 5, sites the investigation above confirms safe to convert)

Mirror `ReviewFlagService.flag()`'s exact existing pattern (`ReviewFlagService.java:114-117`):
```java
CoachReview review = lockRetryer.withBoundedRetry("<CallingClass>.<methodName>",
    () -> reviewRepository.findByIdForUpdateNoWait(reviewId)
        .orElseThrow(() -> new <ExistingExceptionType>(...)));
```
Each site keeps its own existing exception type/message on the not-found branch — only the lock-acquisition
mechanism changes, not the not-found handling. `lockRetryer` (`PessimisticLockRetryer`) needs injecting into
`AdminReviewService`/`ReviewModerationService` wherever not already present — check each class's existing
constructor injection first.

**Bump `PessimisticLockRetryerCallSiteAuditTest.EXPECTED_CALL_SITE_COUNT`** from its current `34`
(`PessimisticLockRetryerCallSiteAuditTest.java:110`) to `34 + N` where `N` is the number of sites actually
converted (4 or 5, per the `ReviewModerationService` investigation above) — update the class's own running
Javadoc history comment (`:33-41`) with a new "Now `34+N` as of `skillars-deferred-135`'s new … " line,
matching the existing convention of that comment chain.

### Test plan

- Update/add tests for `AdminReviewService`, `ReviewSubmissionService` mirroring
  `ReviewFlagServiceConcurrencyIT`'s own pattern for at least the highest-risk pairing per class (e.g., two
  concurrent `approveReview`/`blockReview` calls for `AdminReviewService`; two concurrent
  `updateReview`-vs-whatever-the-second-site-is calls for `ReviewSubmissionService`) — use judgment on
  whether every one of the 4-5 sites needs its own dedicated concurrency IT or whether some can be
  reasoned through by analogy with a documented note (this project has done both in different stories —
  `skillars-deferred-131`/`132` scoped test coverage for multiple similar call-site conversions in one AC
  without necessarily writing N dedicated ITs for N sites; read those stories' own dev-story records for
  precedent before deciding).
- The dedicated `ReviewModerationService` concurrency test described in the caveat above is **required**,
  not optional, regardless of how the other 3-4 sites' coverage is scoped.
- `PessimisticLockRetryerCallSiteAuditTest` itself needs no new test, just the count bump — but re-run it
  to confirm it now passes with the new count before considering this AC done.

---

## AC4: Ledger hygiene

Standard closeout task for this story series:

- **`GdprErasureService` latent-bug finding** (`deferred-work.md:3833-3842`) — annotate
  `[CLOSED by skillars-deferred-135 AC1 — <one-line summary of the actual mechanism landed, once
  implementation confirms it, per this bullet's own "found and corrected during implementation, not
  assumed" precedent>]`. Do not delete the surrounding prose — this project's established convention is to
  annotate inline, not delete.
- **Stripe→payment reconciliation residual** (originating bullet `deferred-work.md:3390-3438`, most
  recently touched by `skillars-deferred-134`'s own `[CLOSED by skillars-deferred-134 AC2]` sub-annotation
  at `:3432-3434` for the *webhook* half only) — append
  `[CLOSED by skillars-deferred-135 AC2 — the durable Stripe → payment reconciliation sweep this bullet's
  own residual always named as "a separate story" is now built: <one-line summary of the actual scheduler/
  method once implementation confirms it>]` after the existing text, without disturbing the prior
  `[CLOSED by skillars-deferred-133 AC3 Fix 3 — alert-only, first step ...]` annotation already there (both
  stay, layered chronologically, matching this file's own established multi-annotation convention on other
  bullets, e.g. the `BoundedKey` D2 bullet's own two-layer closure at `:3256-3294`).
- **M5-2 NOWAIT-conversion trigger** (`deferred-work.md:3555-3574`, most recently annotated
  `[Trigger partially discharged by skillars-deferred-132 AC1 Fix 2 ...]`) — append
  `[CLOSED by skillars-deferred-135 AC3 — the remaining <4 or 5, per AC3's own investigation> call sites
  converted to findByIdForUpdateNoWait + PessimisticLockRetryer, matching flag()'s own precedent
  exactly<; ReviewModerationService's site left blocking, see AC3's own disclosed reason, if that turns
  out to be the outcome>]`.
- **`BoundedKey` full call-site migration residual** (`deferred-work.md:3293-3294`) — append a short note:
  *"Declined a 4th consecutive time (`skillars-deferred-135`, owner decision) — nothing new has surfaced
  to change the calculus since the prior decline."* Documentation only, no code change for this item.
- Re-run the standard grep sweep for every file this story touches
  (`GdprErasureService.java`, `SubscriptionService.java`, `AdminReviewService.java`,
  `ReviewModerationService.java`, `ReviewSubmissionService.java`, `CoachReviewRepository.java`) across the
  full ledger — confirm no other bullet references these files in a way this story's changes affect.
- Add `last_updated`/`development_status` entries to `sprint-status.yaml` per the standing convention (see
  this file's own header comment chain for the exact style to continue).

**Explicitly not in scope, left open:**
- `ConfigBounds.BoundedKey`'s full call-site migration (declined a 4th time, above).
- The Fix 1 lock-order inversion (`ReviewFlagService.flag` ↔ `GdprErasureService.erase`) — formally
  `[DECIDED]` since `skillars-deferred-132`, unreachable today by construction; not re-touched by this
  story since neither of this story's own changes affects that lock's discipline.
- AC1's own auto-retry residual (`GdprErasureService`'s alerting stays alert-only, matching
  `skillars-deferred-133` AC1's own precedent) — not reopened by this story.
- AC2's own detection-latency residual (`customer.subscription.created` still not dispatched by
  `handleEventAtomically` — this sweep closes the "no local row at all" gap on its own cadence, not
  real-time detection) and player-side Stripe reconciliation (still a final decision, not a residual,
  matching every prior story's identical scoping).

---

## Tasks

- [ ] 1. **AC1:** Investigate a genuinely concurrent test fixture for `GdprErasureService`'s alert-raising
   paths (see the "structural nuance" callout — this is real design work, not a formality). Fix
   `raiseErasureAlert` (move catch outside `requiresNewTemplate.executeWithoutResult(...)`) and `markFailed`
   (convert its alert-raise to the same catch-outside shape without regressing its own documented
   pool-conservation property — read that method's Javadoc first). Add the new
   `GdprErasureServiceConcurrencyIT`. Run both mutation-checks (catch-inside regression, pool-conservation
   regression) before marking done.
- [ ] 2. **AC2:** Read `maybeAlertOrphanedLiveSubscription` and `reconcileMarketplaceTiers` fully first.
   Extract/share the Stripe-customer→coach resolution chain if clean to do so. Build the new scheduled
   sweep (service method + thin `@SchedulerLock` wrapper class), calling Stripe's `Subscription.list(...)`
   with pagination, diffing against local state, reusing the existing `SUBSCRIPTION_ORPHANED` alert
   mechanism. Size `@SchedulerLock` from real worst-case arithmetic. Add a new `ConfigBounds` key only if
   genuinely needed (investigate first). New WireMock-backed IT(s) per the test plan above.
- [ ] 3. **AC3:** Investigate `ReviewModerationService`'s specific blocking-vs-NOWAIT-with-retry safety
   question FIRST, before converting any of the 5 sites, per the critical caveat above. Convert the sites
   the investigation confirms safe (4 or 5) to `findByIdForUpdateNoWait` + `PessimisticLockRetryer`,
   mirroring `ReviewFlagService.flag()`'s exact pattern. Bump
   `PessimisticLockRetryerCallSiteAuditTest.EXPECTED_CALL_SITE_COUNT`. Update
   `CoachReviewRepository.findByIdForUpdateNoWait`'s own stale comment. New concurrency tests per the test
   plan above (the `ReviewModerationService` one is mandatory regardless of scoping decisions for the
   others).
- [ ] 4. **AC4:** Ledger closeout (4 items annotated/closed, grep sweep, `sprint-status.yaml` update).
- [ ] 5. Full targeted-suite regression run for every touched class (`platform.admin.service`,
   `platform.payment.service`, `platform.reviews.service`, plus `PessimisticLockRetryerCallSiteAuditTest`,
   `GdprErasureServiceTest`, `GdprErasureIT`) — no local `mvn verify` (GitHub CI is this project's sole
   full-verification gate, per standing convention).

---

## Dev Notes

- **Read `AdminAlertEventListener.insertAlert`'s current method body and inline comment
  (`AdminAlertEventListener.java:138-183`) before starting AC1** — it is the single best reference for the
  corrected catch-outside-`REQUIRES_NEW` mechanism this AC ports to `GdprErasureService`; do not re-derive
  the reasoning from scratch.
- **AC1's own fixture design is genuinely open, not a copy-paste of `AdminAlertEventListenerConcurrencyIT`**
  — see this story's own "structural nuance" callout in AC1's Context section. Budget real investigation
  time for this before writing the test.
- **AC2 is the largest single piece of new functionality in this story** — a new Stripe API integration
  surface (list + paginate), not just a new alert branch on an existing webhook handler. Treat it with
  the corresponding care: read the pinned SDK's actual `Subscription.list`/pagination API before assuming
  its shape, and confirm rate-limit handling (or the explicit decision to accept the risk at this volume)
  before calling AC2 done.
- **AC3's `ReviewModerationService` caveat is not optional due diligence** — `CoachReviewRepository`'s own
  existing comment explicitly warns this site may behaviorally depend on genuinely blocking (not
  bounded-retry) semantics. Confirm empirically before converting; do not convert-and-assume.
- **This story's citations were verified against `master@4c0a9316`** (post `skillars-deferred-134`/PR #228
  and the SeaweedFS infra fix/PR #229). Re-diff every cited line against whatever `master` actually looks
  like by the time implementation starts, per this project's own standing "diff cited lines to ensure
  they're still accurate" convention.

---

## Dev Agent Record

### Completion Notes

_(Filled in by `/bmad-dev-story` on implementation.)_

### File List

_(Filled in by `/bmad-dev-story` on implementation.)_

### Change Log

- 2026-09-25: Story created via `/bmad-create-story`, sourced from `skillars-deferred-134`'s own
  same-day ledger closeout plus a fresh mining pass over `deferred-work.md`'s still-open items. Three
  owner decisions taken live (AskUserQuestion): build the Stripe→payment reconciliation sweep now (AC2);
  convert all remaining `CoachReviewRepository.findByIdForUpdate` call sites to NOWAIT now (AC3); decline
  `ConfigBounds.BoundedKey`'s full call-site migration a 4th consecutive time (AC4). Status: ready-for-dev.

## Story Completion Status

Not yet implemented. Status: ready-for-dev.
