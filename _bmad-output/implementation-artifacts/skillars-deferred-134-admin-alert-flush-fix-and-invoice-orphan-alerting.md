# Story: AdminAlertEventListener Flush-Timing Fix & Stripe Invoice-Payment-Failed Orphan Alerting

**Story Key:** `skillars-deferred-134-admin-alert-flush-fix-and-invoice-orphan-alerting`
**Epic:** Deferred Work
**Priority:** Medium (one genuine, provable correctness bug shared by all 7 admin-alert event types, plus
one explicitly-flagged residual from `skillars-deferred-133`'s own AC3 — a Stripe orphan-detection gap
symmetric to the one that story just closed).
**Status:** ready-for-dev
**Created:** 2026-09-24

---

## Context

Sourced from a full front-to-back audit of `deferred-work.md` run immediately after
`skillars-deferred-133` (PR #227) was opened against master at `71caec86625662e027a4ac832658074c11ba1a13`
(not yet merged at story-creation time — every citation below was re-verified against
`origin/story/deferred-133-gdpr-alerts-bounds-stripe`, the exact tree this story will land on top of
once that PR merges).

Per this project's own repeated finding across the `deferred-90` → `deferred-133` series, the "genuine
one-off bugs & gaps" class is thin. This pass did not re-mine the entire 3,860-line ledger from scratch —
prior stories have already done that exhaustively and repeatedly confirmed the older sections are
`[DECIDED]`/`[DISMISSED]`/deploy-blocked/human-only (native-speaker i18n review, etc.). Instead, per this
project's own convention (**"read same-day code-review deferrals before drawing scope"**), this pass
focused on `skillars-deferred-133`'s own freshly-written ledger entries and its Code Review section,
which is where a real, unmined, one-off bug turned up.

**Two owner decisions taken live (AskUserQuestion) during drafting:**

1. **Bundle AC2 (Stripe invoice-payment-failed orphan alerting) or keep AC1 alone?** → **Bundle it.**
   AC1 alone (below) is a real, well-scoped bug, but this project's standing convention is not to ship
   small stories. AC2 extends a pattern `skillars-deferred-133` AC3 *just* shipped to a second webhook
   handler with the identical no-op gap — same shape, same file, same author's own explicit residual
   note, low risk of scope creep.
2. **Should AC2 also cover player-side Stripe orphans, or stay coach-only?** → **Coach-only**, mirroring
   `skillars-deferred-133` AC3's own scoping decision exactly. `SUBSCRIPTION_ORPHANED` stays a
   coach-`AdminAlertReferenceType.COACH` alert; no new reference type or player resolution path is
   introduced. Player-side Stripe reconciliation remains untouched, as it always has been (no ledger item
   has ever called for it).

### AC1's origin (a bug, not a ledger bullet — found by re-reading `skillars-deferred-133`'s own fix)

`skillars-deferred-133` AC1 fixed an almost-identical bug in `GdprErasureService`: a
`catch (DataIntegrityViolationException e)` wrapped around a plain `adminAlertRepository.save(alert)`
call that could never actually fire, because `AdminAlert.alertId` is
`@GeneratedValue(strategy = GenerationType.UUID)` (`AdminAlert.java:29-31`) — an in-memory, pre-execution
id strategy, so Hibernate has no reason to flush at `save()` time. The real constraint violation only
surfaces when the persistence context flushes, which happens at the enclosing transaction's commit —
structurally outside any try/catch scoped to the `save()` call itself. `GdprErasureService` fixed this by
switching to `saveAndFlush` (`GdprErasureService.java:637`, extracted into `insertErasureAlertIfAbsent`,
`:624-641`), confirmed via a throwaway Testcontainers test before the fix (empirically reproduced, not
assumed).

While reviewing that fix, `AdminAlertEventListener.java` (the *other*, older, more heavily-used admin-alert
insert path — shared by `MESSAGE_REPORT`, `CONVERSATION_REPORT`, `MODERATION_UNRESOLVED`, `REVIEW_FLAG`,
`STRIKE_THRESHOLD`, `DISPUTE_RAISED`, and now `SUBSCRIPTION_ORPHANED`) turned out to have the **identical
bug**, still unfixed, and — remarkably — **already self-documented as broken** in the same file:

```java
// AdminAlertEventListener.java:53-54 (Javadoc on onMessageHeldForReview, unchanged since it was written)
 * catch and log. Note a try/catch in this method body would not work — {@code AdminAlert.alertId}
 * is {@code GenerationType.UUID}, so the INSERT is deferred to flush at commit, after the body
```

That comment correctly explains *why a try/catch wouldn't work here* — and is placed directly above a
method that calls the shared private helper `insertAlert` (`:125-141`), whose own `save()` call
(`:138`) is wrapped in exactly the try/catch the comment says doesn't work (`:140`,
`catch (DataIntegrityViolationException e)`). The author who wrote that Javadoc understood the flush-timing
hazard for `onMessageHeldForReview`'s own `REQUIRES_NEW` boundary, but the shared helper both that method
and five/six others call was never fixed to match. `skillars-deferred-133`'s own ledger entry for its AC1
fix says explicitly: *"`AdminAlertEventListener.insertAlert`'s superficially-similar catch (`save`, not
`saveAndFlush`) has this same latent gap for a genuine concurrent race — pre-existing, out of this story's
scope to fix, noted here for a future story."* This story is that future story.

### AC2's origin (an explicit residual named in `skillars-deferred-133`'s own AC3 ledger entry)

`skillars-deferred-133` AC3 fixed `StripeWebhookService.handleSubscriptionUpdated`'s orphan-detection
branch (a Stripe subscription with no local match) to alert instead of silently `log.warn`-and-drop. Its
own ledger entry names, as an **explicit, undone residual**: *"`handleInvoicePaymentFailed` has the
identical untouched no-op shape and is out of this fix's scope."* Verified directly against
`StripeWebhookService.java:269-288` (post-`deferred-133`, i.e. the tree this story lands on): the method
deserializes the `Invoice`, extracts `stripeSubId = invoice.getSubscription()`, increments a counter, then
calls `subscriptionService.handleSubscriptionWebhook("invoice.payment_failed", stripeSubId, Map.of())`
directly — **no orphan check at all**, unlike `handleSubscriptionUpdated`'s pre-`deferred-133` shape which
at least logged a warning. Tracing into `SubscriptionService.handleInvoicePaymentFailed`
(`SubscriptionService.java:711-728`): both `paymentCoachSubscriptionRepository.findByStripeSubscriptionId`
(`:712`) and `paymentPlayerSubscriptionRepository.findByStripeSubscriptionId` (`:722`) are `ifPresent`
no-ops — if neither matches, the event is fully silent (not even a log line). A payment failure on a
subscription Stripe knows about but this system has no local row for is exactly the kind of billing
drift `skillars-deferred-133` AC3 exists to surface — this is the same underlying gap, reached via a
different webhook event type.

---

## AC1: `AdminAlertEventListener.insertAlert`'s dedup catch never fires — switch to `saveAndFlush`

**File:** `src/main/java/com/softropic/skillars/platform/admin/service/AdminAlertEventListener.java`

### The bug

`insertAlert` (`:125-141`):

```java
private void insertAlert(AdminAlertType type, String referenceId,
                         AdminAlertReferenceType referenceType, String reason) {
    if (adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(
            referenceId, type, AdminAlertStatus.OPEN).isPresent()) {
        log.debug("Admin alert already OPEN for type={}, referenceId={} — skipping duplicate", type, referenceId);
        return;
    }
    try {
        AdminAlert alert = new AdminAlert();
        alert.setType(type);
        alert.setReferenceId(referenceId);
        alert.setReferenceType(referenceType);
        alert.setReason(reason);
        adminAlertRepository.save(alert);          // :138 — does not flush, id is UUID/pre-generated
        log.debug("Admin alert created: type={}, referenceId={}", type, referenceId);
    } catch (DataIntegrityViolationException e) {    // :140 — never reached for this exact reason
        log.debug("Admin alert duplicate suppressed by unique index for type={}, referenceId={}", type, referenceId);
    }
}
```

The `isPresent()` read-then-write above is a classic TOCTOU: it prevents most duplicates, but not a
genuine concurrent race (two callers publishing for the same `(referenceId, type)` within the same
window, both passing the read, both attempting an insert). `admin_alerts_unique_open_per_ref` — a
**partial** unique index, `CREATE UNIQUE INDEX admin_alerts_unique_open_per_ref ON admin.admin_alerts
USING btree (reference_id, type) WHERE (status = 'OPEN')` (`V138__baseline_schema.sql:3166`) — is the
real backstop for that race. The `catch` block exists specifically to turn that backstop's violation into
a graceful skip. Because `save()` doesn't flush, the violation instead surfaces uncaught, at the
*enclosing* transaction's commit:

- For the five plain-`@Transactional` callers (`onMessageReported`, `onConversationReported`,
  `onReviewFlagged`, `onStrikeThreshold`, `onDisputeRaised`) — the uncaught
  `DataIntegrityViolationException` propagates out of the event listener and into whatever transaction
  published the event (`ReviewFlagService.flag()`, `ReliabilityStrikeService.issue()`,
  `DisputeService`'s raise path, etc.). A benign, expected alert-dedup collision under concurrency can
  fail the **caller's own primary business operation** — worse than the `GdprErasureService` case
  `skillars-deferred-133` fixed, which only affected the alert write itself.
- For the two `REQUIRES_NEW` callers (`onMessageHeldForReview`, `onCoachSubscriptionOrphaned`) — the
  failure is isolated to that listener's own transaction, caught by the publishers per
  `onMessageHeldForReview`'s own Javadoc ("both publishers catch and log"). Lower severity, but still not
  the graceful dedup skip the code visibly intends.

### The fix

Mirror `GdprErasureService.insertErasureAlertIfAbsent`'s already-shipped fix exactly
(`GdprErasureService.java:624-641`): change `adminAlertRepository.save(alert)` to
`adminAlertRepository.saveAndFlush(alert)` at `:138`. This forces the INSERT (and any constraint
violation) to happen synchronously inside the try block, where the existing catch can actually catch it.
No other change to `insertAlert`'s signature, callers, or the surrounding `isPresent()` pre-check is
needed — this is a single-token fix with a large blast-radius test story, same as `deferred-133` AC1's.

**Do not attempt to also close the outer TOCTOU** (a lock or a `SELECT ... FOR UPDATE` before the check) —
that would be a materially larger, higher-risk change to code that fires on every admin-alert-worthy event
in the system, for a race the unique index (once actually reachable via `saveAndFlush`) already closes
completely. Out of scope, not a residual — the unique index is a complete, correct backstop once the catch
can see it.

### Test plan

**Existing coverage today:** `AdminAlertEventListenerTest` is a pure Mockito unit test
(`@ExtendWith(MockitoExtension.class)`, mocked `AdminAlertRepository`) — it can verify `saveAndFlush` was
called instead of `save` (an interaction assertion), but a mock cannot reproduce Hibernate's real
flush-timing semantics, so it **cannot prove the fix actually closes the race**. Update every existing
`verify(adminAlertRepository).save(...)` assertion in this test to `verify(adminAlertRepository)
.saveAndFlush(...)` (mechanical, matches the production change) — but do not treat that as sufficient
proof by itself.

**New real-DB proof required**, mirroring `skillars-deferred-133` AC1's own discipline (a "throwaway
Testcontainers test before fixing, not assumed" plus a permanent regression test): add a concurrency test
against a real Postgres (via `AbstractIntegrationTest`, the pattern `AdminQueueIT` already uses) that
races two threads publishing the same event `(referenceId, type)` pair (e.g. `StrikeThresholdReachedEvent`
for the same `coachId`, via `ApplicationEventPublisher` against a real `AdminAlertEventListener` bean —
not the repository directly, so `insertAlert`'s own `isPresent()` pre-check is genuinely exercised) and
asserts: (a) both publish calls return normally — no exception escapes either transaction; (b) exactly one
`OPEN` `admin_alerts` row exists for that `(referenceId, type)`. Use a real thread-interleaving mechanism
(a `CountDownLatch`/raw-JDBC-lock pattern, matching this project's own established concurrency-test
convention — see `AdminCoachEnforcementConcurrencyIT`, `ReliabilityStrikeConcurrencyIT`), not a bare race
with no ordering guarantee, so the test is deterministic rather than flaky.

**Mutation-check before considering AC1 done:** temporarily revert `saveAndFlush` back to `save`, confirm
the new concurrency test fails (an uncaught `DataIntegrityViolationException` from one of the two racing
publishes), then restore the fix. This is the same discipline `skillars-deferred-133`'s own dev-story
applied to its `GdprErasureService` fix — do not skip it; a test that "passes either way" would not
actually prove anything.

---

## AC2: `StripeWebhookService.handleInvoicePaymentFailed` gains the same orphan-alerting `skillars-deferred-133` AC3 shipped for `handleSubscriptionUpdated`

**Files:**
- `src/main/java/com/softropic/skillars/platform/payment/service/StripeWebhookService.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/AdminAlertType.java` (Javadoc update only)

### Current behavior (the gap)

`handleInvoicePaymentFailed` (`StripeWebhookService.java:269-288`):

```java
private void handleInvoicePaymentFailed(Event event) {
    Invoice invoice = ...deserializeUnsafe()...;         // throws RuntimeException on failure (existing, unchanged)
    if (invoice == null) { log.warn(...); return; }
    String stripeSubId = invoice.getSubscription();
    if (stripeSubId == null || stripeSubId.isBlank()) { log.warn(...); return; }
    invoicePaymentFailedCounter.increment();
    subscriptionService.handleSubscriptionWebhook("invoice.payment_failed", stripeSubId, Map.of());  // :287 — no orphan check
}
```

`SubscriptionService.handleInvoicePaymentFailed(stripeSubscriptionId)` (`SubscriptionService.java:711-728`)
does two `ifPresent`-only lookups against `paymentCoachSubscriptionRepository`/
`paymentPlayerSubscriptionRepository`. If `stripeSubId` matches neither, **nothing happens** — no log, no
alert, no metric distinguishing "matched" from "orphaned" failures. A coach whose Stripe subscription is
failing to bill, but whose local `payment.coach_subscriptions` row is missing or desynced, generates no
signal anywhere in this system today.

### The fix

Add a new private method in `StripeWebhookService`, `maybeAlertOrphanedInvoicePaymentFailed(Invoice
invoice)`, called from `handleInvoicePaymentFailed` **before** delegating to
`subscriptionService.handleSubscriptionWebhook` (mirroring `handleSubscriptionUpdated`'s existing
`coachFound`/`playerFound` check-then-`maybeAlertOrphanedLiveSubscription` ordering at `:190-195`).
Reuse, don't duplicate, the resolution chain `maybeAlertOrphanedLiveSubscription` already established
(`:215-247`):

1. Look up `stripeSubId` in both `paymentCoachSubscriptionRepository`/`paymentPlayerSubscriptionRepository`
   (same two finders `SubscriptionService.handleInvoicePaymentFailed` uses) — if either matches, this is
   not an orphan; return without alerting (the normal, already-working `PAST_DUE` update path continues
   unchanged in `SubscriptionService`).
2. If neither matches: resolve `invoice.getCustomer()` (confirmed `String` in stripe-java `28.4.0` —
   `javap` against the pinned jar shows `public java.lang.String getCustomer()` on `Invoice`, same
   signature as `Subscription.getCustomer()` which `:223` already relies on) through
   `stripeCustomerRepository.findByStripeCustomerId(...)` → `StripeCustomer.parentId` (the coach/parent's
   `main.user.id`) → `coachProfileRepository.findByUserId(userId)`. If no `CoachProfile` resolves
   (a player, or an unrecognized customer), return without alerting — **coach-only, by this story's own
   owner decision**, matching `skillars-deferred-133` AC3's identical scoping.
3. Apply the same `SUBSCRIBE_RACE_GRACE_WINDOW` (10 minutes, reuse the existing constant — do not
   introduce a second one) check against `paymentCoachSubscriptionRepository.findByCoachId(coachId)`'s
   `updatedAt`, exactly as `maybeAlertOrphanedLiveSubscription` does at `:238-244` — a coach mid-`
   subscribeCoach` provisioning could in principle receive an early payment-failure retry before its
   local row links, and this guard is already proven correct for the symmetric case.
4. Publish the **same** event and alert type `skillars-deferred-133` AC3 introduced —
   `CoachSubscriptionOrphanedEvent` → `AdminAlertType.SUBSCRIPTION_ORPHANED` — rather than minting a new
   type. Both webhook events describe the identical underlying condition (Stripe has state for this coach
   that the local system does not), just detected via two different event types; `insertAlert`'s existing
   per-`(referenceId, type, OPEN)` dedup (AC1, above) means a coach already alerted via
   `handleSubscriptionUpdated` won't get a second, redundant alert from `handleInvoicePaymentFailed` firing
   moments later for the same underlying drift.

**No live-status allowlist analogous to `LIVE_SUBSCRIPTION_STATUSES` is needed here** — `invoice.
payment_failed` is itself already a live-billing-attempt signal (Stripe only emits it for an actual failed
payment attempt on an actual invoice), unlike `customer.subscription.updated`, which fires on every status
transition including the normal post-cancellation settle that motivated that allowlist in the first place.
Document this reasoning inline (a one-line comment on the new method), so a future reader doesn't assume
one was simply forgotten.

**Wrap the new method in the same `catch (Exception e)` pattern `maybeAlertOrphanedLiveSubscription`
uses** (`:246-248`) — for the identical reason documented on that method's own Javadoc: a deterministic
failure in the new alerting path must never roll back `handleEventAtomically`'s idempotency-record insert
and put Stripe into an indefinite retry loop on a routine webhook.

**Update `AdminAlertType.SUBSCRIPTION_ORPHANED`'s Javadoc** (`AdminAlertType.java`, currently written
singular to "a Stripe customer.subscription.updated event") to also mention `invoice.payment_failed` as a
second trigger path, so the enum's own documentation doesn't go stale the moment this ships.

### Test plan

Extend `StripeWebhookVerificationTest` (do not add a new test class — this project's own established
convention per `skillars-deferred-133` AC3's own test-plan correction) with cases mirroring the 6 already
added for `handleSubscriptionUpdated`'s orphan path:
- Orphaned `invoice.payment_failed` (no local match, resolves to a known coach) → alert published.
- Matched `invoice.payment_failed` (local coach or player row exists) → no alert, existing `PAST_DUE`
  update path runs unchanged (regression coverage for the untouched behavior).
- Orphaned but within the 10-minute `subscribeCoach` grace window → no alert (grace-window suppression,
  same assertion shape as the existing subscription-updated grace-window test).
- Orphaned, resolves to a player (not a coach) → no alert (coach-only scoping proof).
- Two independent orphaned `invoice.payment_failed` events for the same coach → `insertAlert`'s own
  dedup means only one `OPEN` alert exists (this is `AdminAlertEventListener`'s responsibility, not this
  webhook's — assert via a real DB read, not a mock interaction count, so the assertion actually exercises
  AC1's fix rather than merely restating it).

Also extend `AdminQueueIT`'s existing `AdminAlertType`/`admin_alerts_type_check` pinning case (added by
`skillars-deferred-133` AC3) if it enumerates alert *sources*, not just types — confirm during
implementation whether that test needs a second data point or already covers this by type alone.

---

## AC3: Ledger hygiene

Standard closeout task for this story series:

- Delete the two ledger bullets this story closes (the `AdminAlertEventListener.insertAlert` note added
  by `skillars-deferred-133`'s own AC1 entry, and the `handleInvoicePaymentFailed` residual named in its
  AC3 entry) — annotate both `[CLOSED by skillars-deferred-134 AC1 / AC2 ...]` with a one-line summary of
  the actual fix, per this file's own established convention, rather than deleting the surrounding prose
  outright.
- Re-run the standard grep sweep for `AdminAlertEventListener.java`, `StripeWebhookService.java`,
  `SubscriptionService.java`, `AdminAlertType.java` across the full ledger — confirm no other bullet
  references these files in a way this story's changes affect. (Not expected to find anything new; this
  is the standard due-diligence pass every story in this series performs before closing.)
- Add `last_updated`/`development_status` entries to `sprint-status.yaml` per the standing convention (see
  below).

**Explicitly not in scope, left open:**
- `AdminAlertEventListener`'s outer `isPresent()`-then-insert TOCTOU pattern itself (see AC1's own "do not
  attempt to also close" note) — the unique index is a complete backstop once `saveAndFlush` makes it
  reachable; narrowing the TOCTOU window further has no correctness benefit.
- Any change to `handleAccountUpdated` or `handleSubscriptionDeleted` — neither has an equivalent orphan
  no-op gap (`handleSubscriptionDeleted`'s orphan branch already logs and correctly no-ops; deleting a
  subscription that was never locally tracked is not billing drift worth alerting on).
- Player-side Stripe orphan detection, for either webhook event — a final decision (this story's own
  Context section), not a residual.

---

## Tasks

1. **AC1:** `AdminAlertEventListener.insertAlert` — `save` → `saveAndFlush` at `:138`. Update
   `AdminAlertEventListenerTest`'s existing `save`/`saveAndFlush` assertions. Add the new real-DB
   concurrency IT (new class or an addition to an existing `AdminQueueIT`-style real-DB test — dev's
   choice, follow this project's existing naming convention for concurrency ITs). Mutation-check per AC1's
   test plan before marking done.
2. **AC2:** `StripeWebhookService` — new `maybeAlertOrphanedInvoicePaymentFailed`, wired into
   `handleInvoicePaymentFailed` before the `subscriptionService.handleSubscriptionWebhook` delegation.
   `AdminAlertType.SUBSCRIPTION_ORPHANED` Javadoc update. Extend `StripeWebhookVerificationTest` with the
   5 new cases listed above.
3. **AC3:** Ledger closeout (2 bullets `[CLOSED]`, grep sweep, `sprint-status.yaml` update).
4. Full targeted-suite regression run for every touched class (`platform.admin.service`,
   `platform.payment.service`, plus `AdminQueueIT`) — no local `mvn verify` (GitHub CI is this project's
   sole full-verification gate, per standing convention).

---

## Dev Notes

- **Read `GdprErasureService.java:590-645` before starting AC1** — `insertErasureAlertIfAbsent` is the
  exact template for the fix and its own Javadoc explains the flush-timing hazard in more detail than this
  story repeats. Do not re-derive that reasoning from scratch; cite it.
- **`AdminAlertEventListener.insertAlert`'s callers span 5 different publishing modules** (messaging,
  reviews, payment/strikes, disputes, payment/subscriptions) — when writing the new concurrency IT, pick
  whichever publisher has the simplest existing test fixture already in the codebase (likely
  `StrikeThresholdReachedEvent` or `ReviewFlaggedEvent`, both already used in `AdminQueueIT`'s existing
  seed data) rather than building new fixtures from scratch.
- **`Invoice.getCustomer()`'s `String` return type was confirmed by decompiling the pinned
  `stripe-java-28.4.0.jar` directly** (`javap -p` against `com/stripe/model/Invoice.class`), not assumed
  from `Subscription`'s identical-looking method — the two classes are unrelated types in the SDK and a
  version bump could in principle diverge them. Worth re-confirming if `pom.xml`'s pinned `stripe-java`
  version ever changes before this story ships.
- **This story's citations were verified against `origin/story/deferred-133-gdpr-alerts-bounds-stripe`**,
  not local `master` — that branch was still an open, CI-pending PR (#227) at story-creation time. Before
  starting implementation, re-diff every cited line against whatever `master` actually looks like once
  #227 has merged — the branch this story lands on should be created fresh off post-merge `master`, not
  off this draft's own reference commit.

---

## Story Completion Status

Set to **ready-for-dev** once copied onto a real branch off fresh `master` (post-`skillars-deferred-133`
merge). No implementation has been performed as part of drafting this story — `AC1`/`AC2`'s file:line
citations above were verified by direct inspection of `origin/story/deferred-133-gdpr-alerts-bounds-stripe`
in a disposable git worktree, not by editing this story's own working tree.
