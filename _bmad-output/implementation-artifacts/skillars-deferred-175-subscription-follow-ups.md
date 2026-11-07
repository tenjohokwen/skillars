# Story skillars-deferred-175: Subscription follow-ups (stale incomplete webhooks, parked-event poison, ungranted upgrades, Stripe email)

Status: ready-for-dev

<!-- CREATED 2026-11-06 by splitting skillars-deferred-174 (owner decision D0 = 1: two stories). This is 174's
     Part B, moved verbatim: RS05c, RS-poison, RS07b, RS03c. Decision ids D5-D8 keep their 174 numbers so the
     discussion stays traceable; ACs and tasks are renumbered (174 AC7-AC11 = AC1-AC5 here). Code read at
     origin/master c6d88a63 and checked by /mto-story-review (story-review.md, 2026-11-06). Citations are line
     numbers at c6d88a63; re-verify before editing. No migration. Owner decided D5-D8 (option 1 each). -->

## Story

As **a subscriber**, I want my subscription state, tier and Stripe receipt email to match what Stripe holds,
so that an old webhook, a failed save after an upgrade, or an email change does not leave the two apart.

As **an operator**, I want a parked webhook that keeps failing to raise an alert instead of being purged silently.

## Ledger mapping (so future passes do not re-pick this)

| Ledger id | Severity | What |
|---|---|---|
| RS05c | medium / logic | Older parked `subscription.updated` overwrites `active` |
| RS-poison | low / edge-case | A parked event that always fails blocks its subscription, no alert |
| RS07b | medium / edge-case | Tier upgrade persist fails after Stripe upgraded, unrecorded |
| RS03c | low / logic | Stripe customer email never updated after an email change |

No eval case is tied to these ids. `evals/review/cases/337-subscription-entitlement-integrity.yaml` covers
neighbouring code (`lastStripeEventAt`, from skillars-deferred-169); re-run it if T1 changes the webhook guard.
Every line above is deleted from `deferred-work.md` in this PR.

Sibling: skillars-deferred-174 (cards and pack purchase attempts) shares no code with this story. Both edit
`docs/dev-docs/payment/index.html` and the search index; whichever merges second rebases those.

## Background (code at c6d88a63)

Paths below are relative to `src/main/java/com/softropic/skillars/platform/payment/` unless shown otherwise.
`SubSvc` = `service/SubscriptionService.java`. Outside that base: `ConfigBounds` is
`platform/config/service/ConfigBounds.java`; `OperationalBacklogMetrics` is `infrastructure/persistence/`.

### Subscriptions and the Stripe customer

**RS05c: an older parked event overwrites `active`.**
- Parking table `payment.stripe_pending_subscription_events` (V228:50-65; `event_created bigint` = Stripe epoch
  seconds; no attempt or error column). `StripeWebhookService.handleSubscriptionUpdated:291-302` /
  `handleSubscriptionDeleted:396-405` park when no local row matches.
- Link: `SubSvc.persistCoachSubscription:246-281` / `persistPlayerSubscription:560-584` set tier, Stripe id,
  status from the create response, period end; neither sets `lastStripeEventAt`. After commit,
  `drainParkedEventsQuietly` (:202, :525) replays parked rows oldest first (`drainParkedEvents:1336`, each row in
  its own `@Transactional applyParkedEvent:1357-1368`, claim-by-DELETE).
- Guard `isStaleEvent` (SubSvc:1572-1579): `lastApplied != null && eventCreated.isBefore(lastApplied)`. With
  `lastStripeEventAt` null it always applies, so an `incomplete` event created before the first invoice was paid
  overwrites the `active` the create response just gave. `handleSubscriptionUpdated` (:1456) applies
  `applyStripeStatus(normalizeStripeStatus(...))` with no regression guard and, for coaches, calls
  `syncCoachEntitlementChange`, so the marketplace tier is revoked too. A live (not parked) event arriving after
  the persist meets the same null.
- Creation uses `ERROR_IF_INCOMPLETE` (`StripeClient:281`) and `createPaidStripeSubscription` (SubSvc:1681) cancels
  any response that is not billing-healthy, so the persisted state is always `active` or `trialing`. `incomplete`
  is only ever Stripe's state before the first payment: a row that is not INCOMPLETE locally can never legitimately
  go back to it.
- Replay order is `event_created ASC NULLS LAST, id ASC`. A parked `incomplete` followed by a later `active`
  heals itself; the harm needs the `incomplete` to be last (no later event, or same-second events delivered out of
  order). A live `incomplete` with `created` equal to the last applied one also passes the strict `isBefore`.
- A parked `deleted`, or an `updated` whose status is `canceled`/`incomplete_expired`, goes down the deletion path,
  and "deleted is final whatever its order". `persist*` hard-sets `cancelAtPeriodEnd=false` and the period end.
- `lastStripeEventAt` is an `Instant` on both entities (`PaymentCoachSubscription:66`,
  `PaymentPlayerSubscription:59`). Readers and writers: only the guard and its writes (SubSvc:1463-1554) and one unit
  test that sets it itself; no native SQL reads it. `SubscriptionService` uses `Instant.now()`, not `ClockProvider`.
- On the ledger's fix idea: the ledger says "anchor at persist time from the create response", which is right in
  spirit. The trap is implementing it with `Subscription.getCreated()`: under the strict `isBefore`, that skips
  nothing, because every event is created at or after the subscription.

**RS-poison: a parked event that always fails.**
- A failed apply logs `[STRIPE_WEBHOOK_PARKED_APPLY_FAILED]` and stops that subscription's drain (SubSvc:1341-1344);
  the job logs `[STRIPE_WEBHOOK_PARKED_DRAIN_FAILED]` (:1405). Every cycle retries it; later events wait.
- Also found: `purgeOlderThan` (`created_at < cutoff`, retention `payment.pendingSubscriptionEvent.retentionDays`,
  default 30, `ConfigBounds:283-285`) deletes a LINKED poison row too, counting it as
  `payment.subscription.parked_events_purged_unapplied` and logging it at info as "never linked", which is wrong
  for this case.
- The drain (`PaymentRecoveryJob.run` → `drainLinkableParkedEvents`) walks `findDistinctSubscriptionIds()` and drains
  only when `hasLocalRow(subId)`, which is why a linked poison row is retried every 5 minutes.
- No gauge or alert covers parked events. `AlertDocumentationParityTest` requires a runbook entry per alert (set
  equality of `grafana-alerts.yml` titles and `monitoring.md` headings).
- `OperationalBacklogMetrics` branches are raw cross-schema SQL in one string (no module import), so a payment
  branch is allowed there. `payment.coach_subscriptions.stripe_subscription_id` and
  `payment.player_subscriptions.stripe_subscription_id` have no index (small tables; a semi-join every 60 s is
  fine, but the class javadoc says each branch reads an indexed status). Branches with no rows must end
  `HAVING COUNT(*) > 0`, as `awaited_wrap_ups` does (:73), or an empty backlog reads as age 0.

**RS07b: tier upgrade persist fails after Stripe upgraded.**
- `changeCoachTier:298-333` / `changePlayerTier:589-628` (class not transactional) call
  `updateStripePrice:1758` → `StripeClient.updateSubscriptionTier:298` (`ALWAYS_INVOICE`, `ERROR_IF_INCOMPLETE`),
  then `self.persistCoachTierUpgrade:375-395` / `persistPlayerTierUpgrade:645-659` (`@Transactional`, row
  locked). Only a superseded subscription id (:378, :648) reaches `recordUngrantedUpgrade:361`
  (`[SUBSCRIPTION_UPGRADE_UNGRANTED]`, in the MoneyIntegrityEvent regex; counter; a
  `payment.subscription_upgrade_reconciliations` row). A thrown `payment.subscription.notFound` or a DB error
  escapes with no tag and no row: Stripe bills the new tier, the DB keeps the old one, nobody is told.
- No webhook heals it: `handleSubscriptionUpdated` (SubSvc:1456) deliberately does not sync tier (no
  price-to-tier map). The ledger's "after a GDPR scrub" trigger is unconfirmed (no code deletes subscription
  rows); a DB error or a lock timeout on `findBy*IdForUpdate` (blocking `PESSIMISTIC_WRITE`, no retryer) is the
  realistic one.
- `recordUngrantedUpgrade` (SubSvc:350-372) is private, never throws, and writes the row through
  `paymentRecoveryRecorder` in REQUIRES_NEW on the payment routed pool, so it is safe to call after the persist
  transaction rolled back. All its arguments are in scope in `change*Tier`. The superseded path returns normally
  (endpoint 204); its javadoc says "a client retry is a NEW upgrade key - a second proration".
- Retry after a failed persist: the next `change*Tier` reads the old tier from the DB and sends the target price
  again with a fresh key. The Stripe item already has that price, so the proration should be zero, but this is not
  confirmed from code. Nothing sets the reconciliation row's `resolved_at` (manual only), and there is no unique key
  on it, so a successful retry leaves an open row and repeated failures add duplicates. The runbook
  (`monitoring.md:~298`) says "refund or grant by hand" without "check whether a retry already granted it".

**RS03c: Stripe customer email.**
- Set once at creation (`StripePaymentGateway.createStripeCustomer:641-656`, email at :647-648). The only
  customer update is `StripeClient.scrubCustomer:240-245` (GDPR, via `StripeCustomerErasureOutboxHandler:72`).
  Coaches and players have customers too (`stripe_customers.parent_id` is really a user id).
- Email changes finalise in `security/service/EmailChangeService.confirm:200-245` (publishes
  `AccountChangeEvent(EMAIL_CHANGED, ...)` at :241) and `undo:248-296` (`EMAIL_CHANGE_UNDONE` at :293).
  `AccountChangeEvent` (`security/contract/event/AccountChangeEvent.java`) carries no user id, and six call sites
  construct it (`AccountChangeUserInfo` holds email, names and language, no id). Existing listeners are plain
  `@EventListener`; none in payment. Payment already imports `security.contract.event` (`StripeOnboardingService:13`).
- `EmailChangeService` is class-level `@Transactional`; the events are published before commit, so a synchronous
  listener that throws rolls the email change back (intended: an outbox insert failure blocks the change).
  `confirm` refuses a locked account (:208); `undo` does not (:270 only skips the password reset), so the handler's
  locked/erased check is required, not optional.
- `stripe_customers.parent_id` is the `@Id` and a user id, so `findById(userId)` finds at most one customer.
  `StripePaymentGateway` already reads `security.repo.UserRepository` (`findOneById`, `User.isLocked()`); the erased
  address is `deleted.<id>@erased.io` (`GdprErasureService:424`). Stripe gets the email only at customer creation
  (no `receipt_email`, no email lookup). `AccountManagementFacade:255` is the registration mapper, not a change path.
- Generic outbox: module `platform.outbox`, table `main.outbox_messages` (`OutboxMessageHandler` auto-registered by
  `OutboxRowProcessor:55-58`); the enqueue template is `StripeCustomerErasureOutboxSupport` (`Propagation.MANDATORY`).
  The test profile drains the outbox synchronously after commit, so an IT cannot see the row in the table.

## Decisions (DECIDED 2026-11-06: option 1 in each)

- **D5 (RS05c): stopping an old `incomplete` from overwriting `active`. DECIDED 2026-11-06: option 1.**
  - (1, recommended, chosen) A status-regression guard in `handleSubscriptionUpdated`, on the live and the parked path
    alike: an update whose normalised status is INCOMPLETE is ignored when the local row is not INCOMPLETE, logged
    as `[STRIPE_WEBHOOK_STATUS_REGRESSION_IGNORED]` and, on the parked path, deleted like any applied row. Grounded in
    Stripe's model: `incomplete` exists only before the first payment, and our creation never persists it
    (`ERROR_IF_INCOMPLETE`). No clock, no anchor, no skew, and no risk of skipping a cancellation or a
    `cancel_at_period_end` change.
  - (2) Anchor `lastStripeEventAt` at persist time (truncated to seconds) and skip parked events created at or before
    it. Must exclude `deleted` and terminal-status updates (else a cancellation is lost), still loses a same-second
    `past_due` or `cancel_at_period_end=true`, leaves a live same-second `incomplete` through the strict `isBefore`,
    and depends on the server clock (SubSvc uses `Instant.now()`).
  - (3) Anchor on the Stripe response's `Date` header. Same exclusions as (2) without the skew; unconfirmed that
    stripe-java 28.4.0 exposes it (`getLastResponse().headers()`).
- **D6 (RS-poison): detect, and stop the silent purge. DECIDED 2026-11-06: option 1.**
  - (1, recommended, chosen) A `parked_events_linked` backlog branch in `OperationalBacklogMetrics` (parked rows whose
    Stripe subscription id exists on a coach or player subscription; `HAVING COUNT(*) > 0`), an alert
    `ParkedSubscriptionEventsStuck` when its oldest age exceeds 1 h, a runbook entry in `monitoring.md` and a
    provisioning-checklist line. `purgeOlderThan` no longer deletes linked rows; each purge run that kept any logs
    one warn line `[STRIPE_WEBHOOK_PARKED_LINKED_NOT_PURGED] count=N` (the gauge alert is the signal, so no
    per-row error every five minutes). No schema change; no index (both tables are small; correct the class
    javadoc's "each branch reads an indexed status").
  - (2) Also an attempt counter and quarantine after N failures. Unblocks later events, but applies them out of
    order and needs a migration.
- **D7 (RS07b): a failed persist after Stripe upgraded. DECIDED 2026-11-06: option 1.**
  - (1, recommended, chosen) Record it: in `change*Tier`, catch a `RuntimeException` from `persist*TierUpgrade`, call
    `recordUngrantedUpgrade(...)` (REQUIRES_NEW, never throws), then rethrow. This also catches a lock timeout,
    which is right: the tier is not granted and Stripe has billed. The existing reconciliation row, tag and alert
    then cover it. `PaymentRecoveryRecorder` skips the insert when an open row (no `resolved_at`) already exists for
    the same Stripe subscription and target tier, so client retries do not stack rows. The runbook entry gains a
    first step: compare the local tier with Stripe; if a retry already granted it, mark the row resolved and stop.
  - (2) Self-heal: a price-to-tier reverse map from the `coachPriceKey`/`playerPriceKey` config, used by the
    `subscription.updated` webhook. Larger, and changes what the webhook trusts.
- **D8 (RS03c): carrying the change to Stripe. DECIDED 2026-11-06: option 1.**
  - (1, recommended, chosen) A new event `AccountEmailChangedEvent(userId)` in `security.contract.event`, published by
    `EmailChangeService.confirm` and `undo` inside their transactions (leaves `AccountChangeEvent`'s six
    constructors alone). A payment `@EventListener` enqueues `STRIPE_CUSTOMER_EMAIL_SYNC {userId}` on the outbox
    (MANDATORY, same transaction) only when the user has a `stripe_customers` row. The handler re-reads the user's
    current email (so confirm then undo ends right), skips locked or erased users (`deleted.<id>@erased.io`;
    required because `undo` does not refuse a locked account), treats a missing Stripe customer as done, and
    updates the customer email.
  - (2) Best effort after commit, failure only logged. Simpler; an outage loses the update.

## Acceptance Criteria

Written with the recommended option of every decision.

1. **AC1 (RS05c, D5)** A `subscription.updated` whose normalised status is INCOMPLETE is ignored, with
   `[STRIPE_WEBHOOK_STATUS_REGRESSION_IGNORED]`, when the local row is not INCOMPLETE, on the live path and the parked
   path (the parked row is deleted). Other statuses, `deleted` and terminal updates behave as today. Tests: IT, park
   an `incomplete` event, subscribe (create response `active`), drain: the row stays `active` and the coach keeps
   the marketplace tier (red without the guard); a live `incomplete` after subscribe is ignored; a `past_due` still
   applies.
2. **AC2 (RS-poison, D6)** A `parked_events_linked` branch on the backlog gauges (absent when empty); alert
   `ParkedSubscriptionEventsStuck` (oldest age > 1 h) in `deploy/lgtm/grafana-alerts.yml` with a runbook entry
   (`AlertDocumentationParityTest` green) and a provisioning-checklist line; `purgeOlderThan` keeps linked rows and
   logs one `[STRIPE_WEBHOOK_PARKED_LINKED_NOT_PURGED] count=N` warn per run that kept any. Tests:
   `OperationalBacklogMetricsIT` covers the branch (linked counted, unlinked not, empty absent); a purge test in
   `SubscriptionEntitlementIntegrityTest` (or the repository IT) proves a linked row survives and an unlinked one is
   purged.
3. **AC3 (RS07b, D7)** A `persist*TierUpgrade` that throws after `updateStripePrice` succeeded is recorded through
   `recordUngrantedUpgrade` (tag, counter, reconciliation row) and the exception still reaches the caller. A second
   failure for the same subscription and target tier while the first row is open adds no row. The runbook entry
   starts with "check whether a retry already granted it". Unit tests for coach and player, persist throwing
   `payment.subscription.notFound` and a DB exception; a recorder test for the duplicate skip.
4. **AC4 (RS03c, D8)** Confirming or undoing an email change for a user with a Stripe customer enqueues one
    `STRIPE_CUSTOMER_EMAIL_SYNC` in the same transaction; a user without one enqueues nothing; a change that rolls
    back enqueues nothing. The handler sets the Stripe customer's email to the user's CURRENT email; it is a no-op
    for a user with no customer, a locked or erased user, and a customer Stripe no longer has. Tests: through
    `EmailChangeServiceIT`, asserting the handler ran (spy on `StripeClient`, which the test profile's synchronous
    drain reaches) for confirm and undo and did not run after a rollback; a listener unit test (customer / no
    customer); a handler unit test per branch.
5. **AC5 (docs)** `docs/dev-docs/payment/index.html` describes the status-regression guard, the parked-event
    alert, the upgrade recording and the email sync; search index rebuilt; `check-docs` green.
6. **AC6 (ledger)** The four ledger lines are deleted from `deferred-work.md`; any residual this story accepts is
   filed as one new line (for D7: Stripe's proration on a retried upgrade is unconfirmed from code).

## Tasks / Subtasks

- [x] T0 Owner decisions D5-D8 recorded in this file (option 1 each).
- [ ] T1 (AC1) Status-regression guard. Tests: `SubscriptionEntitlementIntegrityTest` (`drain_*` and live cases),
      a new IT beside `SubscriptionLifecycleIT` that extends `BasePaymentIT` and adds no mock (its context key must
      match, or the pinned count in `IntegrationTestConventionTest` rises).
- [ ] T2 (AC2) Gauge branch, alert, runbook, provisioning-checklist line, purge change, javadoc correction. Tests:
      `OperationalBacklogMetricsIT`, purge test.
- [ ] T3 (AC3) Record a failed upgrade persist; recorder duplicate skip; runbook first step. Tests:
      `SubscriptionEntitlementIntegrityTest`, recorder test.
- [ ] T4 (AC4) Event, listener, outbox support and handler. Tests: `EmailChangeServiceIT` (confirm, undo,
      rollback), listener unit test, handler unit test beside `StripeCustomerErasureOutboxHandlerTest`.
- [ ] T5 (AC5) Docs; `node docs/dev-docs/tools/build-search-index.mjs`, then `check-docs.mjs`.
- [ ] T6 (AC6) Ledger lines; sprint-status; `/pr-review` until a clean round; PR.

## Dev Notes

- **Read before editing:** `SubscriptionService` (subscribe, persist, change tier, drain, guard, purge),
  `StripeWebhookService` (park paths), `PaymentRecoveryJob`, `PaymentRecoveryRecorder`, `OperationalBacklogMetrics`,
  `EmailChangeService`, `StripeCustomerErasureOutboxSupport`/`Handler` (the outbox pattern), story
  skillars-deferred-169 (why parking exists) and skillars-deferred-172 (email change).
- **Transactions:** `change*Tier` is not transactional; `persist*TierUpgrade` is, and `recordUngrantedUpgrade`
  writes in REQUIRES_NEW on the payment routed pool. `EmailChangeService` is class-level `@Transactional`; the new
  event is published inside it and the listener enqueues with `Propagation.MANDATORY`. Keep Stripe calls outside
  DB transactions.
- **No migration** is expected. If one becomes necessary, follow `docs/deployment/migration-conventions.md` (rule 5
  CHECK widening one release ahead; rule 7 `SET LOCAL lock_timeout`; rule 9 header).
- **Test context cache:** `IntegrationTestConventionTest.distinctContextKeyCountIsPinned` (14) and
  `.github/scripts/assert-context-count.sh`. A `@MockitoBean` added to an `AbstractIntegrationTest` or `BasePaymentIT`
  subclass forks a context. The new IT beside `SubscriptionLifecycleIT`, `OperationalBacklogMetricsIT` and
  `EmailChangeServiceIT` must add no mocks.
- **Alerts:** the new alert needs a `monitoring.md` runbook entry (`AlertDocumentationParityTest`) and a
  `docs/deployment/provisioning-checklist.md` line.
- **No local `mvn verify`:** run targeted tests with `-DskipFrontend -Djacoco.skip=true`; CI is the full gate.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` (RS05c, RS-poison, RS07b, RS03c)
- `_bmad-output/implementation-artifacts/skillars-deferred-174-payment-follow-ups.md` (the sibling; D0)
- `_bmad-output/implementation-artifacts/skillars-deferred-169-stripe-retry-double-charge-webhook-ordering-and-card-save-fixes.md`
- `_bmad-output/implementation-artifacts/skillars-deferred-172-account-email-change.md`
- `docs/deployment/monitoring.md`, `deploy/lgtm/grafana-alerts.yml`

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

### Change Log

- 2026-11-06: created (ready-for-dev) by splitting skillars-deferred-174 (D0 = 1); decisions D5-D8 pending owner.
- 2026-11-06: owner decided D5-D8 (option 1 each).
