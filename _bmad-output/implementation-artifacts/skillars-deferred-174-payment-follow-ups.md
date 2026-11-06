# Story skillars-deferred-174: Payment follow-ups (card ownership, stuck pack attempts, parked webhooks, upgrade drift, Stripe email)

Status: ready-for-dev

<!-- CREATED 2026-11-06 (/bmad-create-story) from the deferred-work.md payment residuals left by
     skillars-deferred-169 and skillars-deferred-172: RS09b, RS09c, RS09d, RS06b, RS06e, RS05c, RS-poison, RS07b,
     RS03c. Chargebacks (deferred-work "bank chargebacks and dashboard refunds") are excluded: their own story.
     Code read at origin/master c6d88a63 by three research passes, and the scope-changing claims were
     re-checked by hand (gateway full-row save, revert-before-CAS order, staleness guard). Decisions D0-D8 are
     PENDING OWNER; each has a recommendation. Citations are line numbers at c6d88a63; re-verify before editing. -->

## Story

As **a parent, coach or player paying by card**, I want the card the server saves and charges to be one I
actually set up, so that a forged or foreign card id cannot become my default and a purchase never charges a
card I did not choose.

As **an operator**, I want a stuck pack purchase to be closable without SQL and to alert once instead of every
five minutes, so that a parent is never blocked from buying for longer than it takes me to act.

As **a subscriber**, I want my subscription state, tier and Stripe receipt email to match what Stripe holds,
so that an old webhook, a failed save after an upgrade, or an email change does not leave the two apart.

## Ledger mapping (so future passes do not re-pick this)

| Ledger id | Severity | What | Part |
|---|---|---|---|
| RS09b | medium / logic | `/save-payment-method` accepts any `pm_` id | A |
| (new) | medium / logic | Gateway's post-capture full-row save rewrites the saved card with a stale value | A |
| RS09c | low / logic | Declined-card revert is compensation, not prevention | A |
| RS09d | low / concurrency | Resolver revert vs a late capture | A |
| RS06b | medium / logic | Stuck pack attempt closable only with SQL | A |
| RS06e | low / logic | Unresolvable attempt repeats every cycle | A |
| RS05c | medium / logic | Older parked `subscription.updated` overwrites `active` | B |
| RS-poison | low / edge-case | A parked event that always fails blocks its subscription, no alert | B |
| RS07b | medium / edge-case | Tier upgrade persist fails after Stripe upgraded, unrecorded | B |
| RS03c | low / logic | Stripe customer email never updated after an email change | B |

No eval case is tied to these items (checked `evals/review/cases/**`). Every line above is deleted from
`deferred-work.md` in this PR, except any item D0 moves to a follow-up story.

## Background (code at c6d88a63)

Paths below are relative to `src/main/java/com/softropic/skillars/platform/payment/` unless shown otherwise.
`Svc` = `service/SessionPackPaymentService.java`, `SubSvc` = `service/SubscriptionService.java`.

### Part A: cards and pack purchase attempts

**RS09b: the saved card is not checked against the caller.**
- `api/SessionPackPaymentResource.java:190-214`, `POST /api/payment/save-payment-method`,
  `@PreAuthorize(HAS_PARENT_PLAYER_OR_COACH_ROLE)`, user from `securityUtil.getCurrentCoachUserId()`.
  `SavedPaymentMethodRequest` only checks `pm_[A-Za-z0-9_]{1,255}` (`StripeIdFormat.java:17`).
- With no `stripe_customers` row it creates a Stripe customer and stores the submitted pm on it (:194-205): a
  brand-new customer no SetupIntent can have attached that card to.
- Then `SubSvc.savePaymentMethodAndScheduleSync` (:760-768, `@Transactional`): format check, store the pm,
  enqueue `SUBSCRIPTION_PAYMENT_METHOD_SYNC` (outbox, `PaymentMethodSyncOutboxSupport`). After commit,
  `syncPaymentMethodToSubscriptions` (:788) sets it as each live subscription's default and, for
  PAST_DUE/UNPAID, pays the open invoice (:823-835). No Stripe call checks the pm first.
- Then `Svc.recordCardFingerprint` (:805) retrieves the pm and stores its fingerprint as an identity signal for
  the caller, so a foreign pm plants someone else's fingerprint on this account.
- Only frontend caller: `src/frontend/src/components/payment/PaymentMethodCard.vue:220-233`: create SetupIntent
  (`POST /api/payment/setup-intent`, `SessionPackPaymentResource:170-188`, customer-scoped,
  `StripePaymentGateway.createSetupIntent:621-632`, returns only the client secret, id not stored), then
  `stripe.confirmCardSetup`, then sends `setupIntent.payment_method`. The SetupIntent id never reaches the server.
- Signup trusts the premise in a comment: `subscribeCoach` (SubSvc:174-198) and the player path (:504-512) say
  "the payment method was already attached to this exact customer when it was saved".
- `service/StripeClient.java` wraps the SDK (stripe-java 28.4.0): `retrievePaymentMethod` (:247) exists;
  `PaymentMethod.getCustomer()` gives the owning customer. Tests stub `PaymentGateway` with
  `src/test/java/com/softropic/skillars/config/StubPaymentGateway.java`.

**New finding: the post-capture save rewrites the card column.**
- `service/StripePaymentGateway.java:142-148` reads the whole `StripeCustomer` and charges its
  `stripePaymentMethodId`. After capture (:226-227) it does `stripeCustomer.setLastPaymentIntentId(...)` then
  `stripeCustomerRepository.save(stripeCustomer)`. `repo/StripeCustomer.java` has no `@Version` and no
  `@DynamicUpdate`, and the gateway is not transactional, so the save is a merge of a detached entity: a
  full-row UPDATE that writes back the card id read at :145. Any card saved between :145 and :227 (a
  `/save-payment-method`, a revert) is silently undone. Verified by reading; the emitted SQL was not captured.

**RS09c / RS09d: the declined-card revert.**
- Pack purchase writes the client-sent card before charging (`Svc.getOrCreateStripeCustomer:814-830`, called at
  :199-203). The shipped UI sends none (`SessionPackPurchasePage.vue:248` sends `undefined`), so this reaches
  API clients only.
- On a decline or an absence verdict, `markNoCharge` (Svc:494-501) calls `revertDeclinedCard` (:508-513), then
  CASes the attempt to NO_CHARGE from `{IN_PROGRESS, UNKNOWN}`. The revert is
  `StripeCustomerRepository.revertPaymentMethod:59-64`:
  `UPDATE ... SET pm = :previous WHERE parentId = :parentId AND pm = :attempted` (value match only: it cannot
  tell its own write from a later save of the same card).
- The request thread's success path: `markQuietly(attempt, SUCCEEDED, intentId, OPEN_OR_NO_CHARGE)` (:280) may
  move NO_CHARGE to SUCCEEDED; if `customerExisted`, `scheduleSubscriptionCardSync` (:281-282, :577-588) writes
  the attempted card back unconditionally.
- What RS09d really produces (the ledger's wording is off): an absence verdict needs Stripe to show no
  PaymentIntent, so the request thread stalled before creating it. Stalled before the card write: the revert is
  a no-op, consistent. Stalled between the card write and the gateway's read at :145: the revert restores the
  previous card, so the gateway charges the PREVIOUS card, and :282 then makes the never-charged attempted card
  the default. Stalled after :145: the attempted card is charged and the :227 merge plus :282 restore it. So the
  defect is "the card charged is not the card the user chose", reachable only by API clients and only past the
  30-minute absence age (`ConfigBounds.PACK_ATTEMPT_ABSENCE_AGE_MINUTES`, :273-275).
- Two concurrent purchases are already mostly prevented: the attempt insert (:185, partial unique index
  `uq_sppa_one_open_per_parent`) comes before the card write (:203).

**RS06b / RS06e: stuck attempts.**
- `repo/SessionPackPurchaseAttempt.java`: statuses `IN_PROGRESS, SUCCEEDED, NO_CHARGE, REFUNDED, UNKNOWN,
  UNRECONCILED` (:36), `OPEN = {IN_PROGRESS, UNKNOWN, UNRECONCILED}` (:39). V228 created the table with a CHECK
  on those six and `uq_sppa_one_open_per_parent ON (parent_id) WHERE status IN (open)` (V228:41-43). All status
  writes are CAS (`transitionRaw`, repo :37-41). `purgeTerminalOlderThan` (:49-51) deletes non-open rows after
  30 days.
- `service/PaymentRecoveryJob.java:32-33` (`fixedDelay` 5 min, ShedLock `PaymentRecoveryJob_run`) calls
  `Svc.resolveStaleAttempts` (:548-560) → `resolveAttempt` (:359-462) for every open row older than 10 min.
- Already self-healing: an operator full refund in Stripe moves the row to REFUNDED (:423-427).
- Stuck forever, retried every cycle: Stripe lookup always failing (:390-393, :413-416); unreadable charge
  object (:418-421, tag `[PACK_PURCHASE_ATTEMPT_CHARGE_UNREADABLE]`); tier deleted (:434-438,
  `[PACK_PURCHASE_ATTEMPT_UNRESOLVABLE]`); `createPurchase` always failing (:446-450,
  `[PACK_PURCHASE_RECORD_FAILED]`, which IS in the MoneyIntegrityEvent regex, so a stuck row re-fires that
  alert every 5 minutes; the ledger did not mention this).
- Today's way out: `docs/deployment/monitoring.md:418-424` (runbook SQL setting REFUNDED or SUCCEEDED by hand).
  Alert `PackAttemptsUnresolved` (`deploy/lgtm/grafana-alerts.yml:793-815`) on the `pack_attempts` branch of
  `infrastructure/persistence/OperationalBacklogMetrics.java:93-94`.
- Admin precedent: `api/AdminFinanceResource.java` (`/api/admin/payment`, `HAS_FULL_ADMIN_ROLE`),
  `POST /coaches/{coachId}/payouts/release-held` (:58) replaced a runbook UPDATE; its service calls
  `AdminTierGuard.requireFullAdmin()` (`CoachPayoutHoldRecoveryService.java:103`) and audits by log tag only.
  `payment` may import `admin.contract` but not `admin.repo` (no `AdminActionLog` from here).

### Part B: subscriptions and the Stripe customer

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
  overwrites the `active` the create response just gave. A live (not parked) event arriving after the persist
  meets the same null.
- The ledger's fix idea needs a correction: anchoring on `Subscription.getCreated()` skips nothing, because every
  event for a subscription is created at or after the subscription. The anchor must mean "the state as of the
  create response".

**RS-poison: a parked event that always fails.**
- A failed apply logs `[STRIPE_WEBHOOK_PARKED_APPLY_FAILED]` and stops that subscription's drain (SubSvc:1341-1344);
  the job logs `[STRIPE_WEBHOOK_PARKED_DRAIN_FAILED]` (:1405). Every cycle retries it; later events wait.
- Also found: `purgeOlderThan` (`created_at < cutoff`, retention `payment.pendingSubscriptionEvent.retentionDays`,
  default 30, `ConfigBounds:283-285`) deletes a LINKED poison row too, counting it as
  `payment.subscription.parked_events_purged_unapplied` and logging it at info as "never linked", which is wrong
  for this case.
- No gauge or alert covers parked events. `AlertDocumentationParityTest` requires a runbook entry per alert.

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
  rows); a DB error is the realistic one.

**RS03c: Stripe customer email.**
- Set once at creation (`StripePaymentGateway.createStripeCustomer:641-656`, email at :647-648). The only
  customer update is `StripeClient.scrubCustomer:240-245` (GDPR, via `StripeCustomerErasureOutboxHandler:72`).
  Coaches and players have customers too (`stripe_customers.parent_id` is really a user id).
- Email changes finalise in `security/service/EmailChangeService.confirm:200-245` (publishes
  `AccountChangeEvent(EMAIL_CHANGED, ...)` at :241) and `undo:248-296` (`EMAIL_CHANGE_UNDONE` at :293).
  `AccountChangeEvent` (`security/contract/event/AccountChangeEvent.java`) carries no user id, and six call sites
  construct it. Existing listeners are plain `@EventListener`; none in payment.
- Generic outbox: `platform.outbox` (`OutboxMessageHandler` auto-registered by `OutboxRowProcessor:55-58`); the
  enqueue template is `StripeCustomerErasureOutboxSupport` (`Propagation.MANDATORY`).

## Decisions (PENDING OWNER; recommendation first in each)

- **D0: scope.**
  - (1, recommended) Two stories: this one keeps Part A (cards and pack attempts, the money-adjacent half),
    and Part B moves verbatim to skillars-deferred-175. Each is reviewable in a normal number of `/pr-review`
    rounds; skillars-deferred-167 showed what a ten-item security story costs.
  - (2) One story, both parts. One migration and one PR, but a long review.
- **D1 (RS09b): how the server proves the card is the caller's.**
  - (1, recommended) Ownership check: retrieve the pm and require `pm.customer == caller's stripe_customer_id`.
    Blocks foreign and unattached ids and the fingerprint planting. Needs no API or frontend change. The
    resource's "no row: create a customer and save the pm" branch then answers 400 (a fresh customer cannot
    own it). Proves the card was attached through our customer-scoped SetupIntent (our only attach path;
    `StripeClient.attachPaymentMethod` has no callers), not that it is chargeable today.
  - (2) Require the SetupIntent id too and check `status == succeeded`, same customer, same pm. Strongest, but a
    DTO change, a new Stripe call, and frontend changes in `payment.api.js` and `PaymentMethodCard.vue`.
  - Either way, apply the same check to the pack-purchase card write (`Svc.getOrCreateStripeCustomer`).
- **D2 (new finding, RS09c, RS09d): card-column writes.**
  - (1, recommended) Three targeted fixes: (a) replace the gateway's :227 `save` with
    `UPDATE stripe_customers SET last_payment_intent_id = :id WHERE parent_id = :p`; (b) the pack charge uses
    the attempt's own card (`attemptedCard`, falling back to the column only when the request sent none), so the
    card charged never depends on the mutable default; (c) the success path re-applies the card with a
    compare-and-set (`SET pm = :attempted WHERE parent_id = :p AND pm IS NOT DISTINCT FROM :previous`) instead of
    the unconditional sync write, and logs `[PACK_PURCHASE_CAS_LOST]` when the attempt came back from NO_CHARGE.
    Leaves the crash window between a failed charge and the revert documented (no outbox for it).
  - (2) Also add a `payment_method_version` column bumped by every card writer, with the revert matching its own
    version. Closes the same-card ambiguity fully, but touches every writer, for a path only API clients reach.
- **D3 (RS06b): closing a stuck attempt.**
  - (1, recommended) API-only admin action, `POST /api/admin/payment/pack-attempts/{id}/close` on
    `AdminFinanceResource`, `HAS_FULL_ADMIN_ROLE` plus `AdminTierGuard.requireFullAdmin()`, body `{reason}`
    (required, max 500). It first runs `resolveAttempt(id)`; if still open it re-reads Stripe and closes only to
    the status Stripe supports: SUCCEEDED if a pack exists for the PaymentIntent, REFUNDED if fully refunded,
    NO_CHARGE if no PaymentIntent or nothing captured. A captured, unrefunded PaymentIntent with no pack is
    refused (409, `payment.packAttempt.capturedNoPack`): refund it in Stripe, then close. New nullable columns
    `closed_by` and `close_reason` record who and why; a `[PACK_ATTEMPT_ADMIN_CLOSED]` log tag is the audit
    (the release-held precedent). No new status, so no CHECK widening (rule 5 of
    `migration-conventions.md` would otherwise force a two-release rollout).
  - (2) Same, plus a "stuck pack purchases" table on `AdminFinancePage.vue`.
  - (3) A new `MANUALLY_CLOSED` status. Needs the CHECK widened one release before any code writes it.
- **D4 (RS06e): stop the repeat.**
  - (1, recommended) New nullable column `needs_operator_since timestamptz`. The resolver sets it once (CAS where
    it is null) on the tier-deleted, unreadable-charge and record-failed branches, logs a one-shot
    `[PACK_PURCHASE_ATTEMPT_NEEDS_OPERATOR]` (added to the MoneyIntegrityEvent regex), and skips such rows on
    later cycles. The row keeps its open status, so it still blocks a second purchase (safe) and still feeds
    `PackAttemptsUnresolved`. D3's close action is the way out. `[PACK_PURCHASE_RECORD_FAILED]` stops re-firing.
  - (2) Back off instead (retry hourly). Still repeats, and still re-fires the regex alert.
- **D5 (RS05c): the anchor.**
  - (1, recommended) Both `persist*Subscription` methods set `lastStripeEventAt` to the persist time (UTC, truncated
    to seconds, `ClockProvider`). The parked-replay path skips an event whose `created` is at or before that
    anchor (`!isAfter`); live events keep the strict `isBefore`. Risk: if the server clock runs ahead of Stripe's,
    a genuinely newer event inside that skew is skipped; the next Stripe event repairs it.
  - (2) Anchor on the Stripe response's `Date` header (Stripe's clock, no skew). Unconfirmed that stripe-java
    28.4.0 exposes it (`getLastResponse().headers()`); verify first, fall back to (1).
- **D6 (RS-poison): detect, and stop the silent purge.**
  - (1, recommended) A `parked_events_linked` backlog branch in `OperationalBacklogMetrics` (parked rows whose
    Stripe subscription id exists on a coach or player subscription), an alert
    `ParkedSubscriptionEventsStuck` when its oldest age exceeds 1 h, a runbook entry in `monitoring.md`, and
    `purgeOlderThan` no longer deleting linked rows (it logs `[STRIPE_WEBHOOK_PARKED_LINKED_NOT_PURGED]` at error
    instead). No schema change.
  - (2) Also an attempt counter and quarantine after N failures. Unblocks later events, but applies them out of
    order and needs a migration.
- **D7 (RS07b): a failed persist after Stripe upgraded.**
  - (1, recommended) Record it: in `change*Tier`, catch a `RuntimeException` from `persist*TierUpgrade`, call
    `recordUngrantedUpgrade(...)` (own transaction, never throws), then rethrow. The existing reconciliation row,
    tag and alert then cover it.
  - (2) Self-heal: a price-to-tier reverse map from the `coachPriceKey`/`playerPriceKey` config, used by the
    `subscription.updated` webhook. Larger, and changes what the webhook trusts.
- **D8 (RS03c): carrying the change to Stripe.**
  - (1, recommended) A new event `AccountEmailChangedEvent(userId)` in `security.contract.event`, published by
    `EmailChangeService.confirm` and `undo` inside their transactions (leaves `AccountChangeEvent`'s six
    constructors alone). A payment `@EventListener` enqueues `STRIPE_CUSTOMER_EMAIL_SYNC {userId}` on the outbox
    (MANDATORY, same transaction). The handler re-reads the user's current email (so confirm then undo ends
    right), skips users without a customer, locked or erased users (`deleted.<id>@erased.io`), treats a missing
    Stripe customer as done, and updates the customer email.
  - (2) Best effort after commit, failure only logged. Simpler; an outage loses the update.

## Acceptance Criteria

Written for D0 option 2 (one story) with the recommended option of every decision. If the owner picks D0
option 1, AC1-AC6 and AC12-AC14 stay here and AC7-AC11 move to skillars-deferred-175 with their tasks.

1. **AC1 (RS09b, D1)** `POST /api/payment/save-payment-method` saves a card only when Stripe reports it attached
   to the caller's Stripe customer. A pm attached to another customer, an unattached pm, and a caller with no
   `stripe_customers` row each answer 400 `payment.paymentMethodNotOwned` and change nothing (no row, no
   outbox entry, no fingerprint). A Stripe failure during the check fails closed with the existing
   `PaymentGatewayException` handling (no save). The check runs in the resource before
   `savePaymentMethodAndScheduleSync`, outside its transaction. The same check guards the pack-purchase card write.
   `StubPaymentGateway` answers "owned" so existing ITs are unaffected. The new error key exists in every backend
   bundle (`MessageBundleParityTest`) and every frontend locale, and `PaymentMethodCard.vue` shows it.
2. **AC2 (new finding, D2a)** After a capture the gateway updates only `last_payment_intent_id`. A repository IT
   proves that a card saved between the gateway's read and its post-capture write survives (red with the old
   `save`).
3. **AC3 (RS09d, D2b)** A pack purchase charges the card the request named; when it named none, the saved card,
   as today. A test drives the RS09d interleaving (the resolver reverts after the card write, before the charge)
   and asserts the charged pm is the attempted one.
4. **AC4 (RS09c, D2c)** On success the attempted card is re-applied only if the column still holds the previous
   card; a card saved by anyone else meanwhile is kept. `[PACK_PURCHASE_CAS_LOST]` is logged when the attempt's
   SUCCEEDED came from NO_CHARGE. The crash window (a crash between a failed charge and the revert) is stated in
   the revert's javadoc as accepted.
5. **AC5 (RS06b, D3)** `POST /api/admin/payment/pack-attempts/{id}/close` with `{reason}`:
   a. LTD_ADMIN and non-admins get 403; a blank or over-500-character reason gets 400; an unknown id gets 404;
      an already-terminal attempt gets 409 `payment.packAttempt.notOpen`.
   b. It runs the resolver first and returns the resulting status if that closed it.
   c. Otherwise it closes to the status Stripe supports (SUCCEEDED with a pack, REFUNDED when fully refunded,
      NO_CHARGE when nothing was captured), by CAS from the open set, writing `closed_by` and `close_reason`.
   d. A captured, unrefunded PaymentIntent with no pack is refused with 409 `payment.packAttempt.capturedNoPack`
      and the row is unchanged.
   e. After a close the parent can start a new purchase (the partial unique index no longer holds the row).
   f. `monitoring.md`'s `PackAttemptsUnresolved` runbook replaces the hand-written UPDATEs with this action.
6. **AC6 (RS06e, D4)** The tier-deleted, unreadable-charge and persistent record-failed branches set
   `needs_operator_since` once and log `[PACK_PURCHASE_ATTEMPT_NEEDS_OPERATOR]` once; later cycles skip the row
   (no repeated tags, no repeated `[PACK_PURCHASE_RECORD_FAILED]`). The row stays open: it still blocks a second
   purchase and still counts in the `pack_attempts` gauge. The new tag is in the MoneyIntegrityEvent regex. The
   tier-deleted branch gets the unit test it lacks today.
7. **AC7 (RS05c, D5)** Both `persist*Subscription` methods set `lastStripeEventAt`. A parked
   `subscription.updated` created at or before that anchor is skipped and deleted by the drain (logged as stale);
   one created after it applies. A live event older than the anchor is ignored, as today's guard already does
   once the anchor is set. IT: park an `incomplete` event, subscribe (create response `active`), drain: the row
   stays `active` (red without the anchor).
8. **AC8 (RS-poison, D6)** A `parked_events_linked` branch on the backlog gauges; alert
   `ParkedSubscriptionEventsStuck` (oldest age > 1 h) in `deploy/lgtm/grafana-alerts.yml` with a runbook entry
   (`AlertDocumentationParityTest` green); `purgeOlderThan` keeps linked rows and logs
   `[STRIPE_WEBHOOK_PARKED_LINKED_NOT_PURGED]` at error. Tests: `OperationalBacklogMetricsIT` covers the branch
   (linked counted, unlinked not); a purge test proves a linked row survives and an unlinked one is purged.
9. **AC9 (RS07b, D7)** A `persist*TierUpgrade` that throws after `updateStripePrice` succeeded is recorded through
   `recordUngrantedUpgrade` (tag, counter, reconciliation row) and the exception still reaches the caller. Unit
   tests for coach and player, persist throwing `payment.subscription.notFound` and a DB exception.
10. **AC10 (RS03c, D8)** Confirming or undoing an email change enqueues one `STRIPE_CUSTOMER_EMAIL_SYNC` row in
    the same transaction (none when the change rolls back). The handler sets the Stripe customer's email to the
    user's CURRENT email; it is a no-op for a user with no customer, a locked or erased user, and a customer
    Stripe no longer has. Tests: an IT through `EmailChangeServiceIT` (row enqueued on confirm and on undo), and
    a handler unit test for each branch.
11. **AC11 (docs, Part B)** `docs/dev-docs/payment/index.html` describes the anchor, the parked-event alert, the
    upgrade recording and the email sync; search index rebuilt; `check-docs` green.
12. **AC12 (migration)** One additive migration (next free number, V233 at c6d88a63) adds
    `session_pack_purchase_attempts.closed_by`, `.close_reason` (varchar 500) and `.needs_operator_since`, all
    nullable, following `docs/deployment/migration-conventions.md` (no CHECK change).
    `MigrationConventionLintTest` green. `docs/deployment/provisioning-checklist.md` gains the new alert and
    the admin action.
13. **AC13 (docs, Part A)** `docs/dev-docs/payment/index.html` and `docs/security-api-endpoints.md` describe the
    ownership check, the admin close endpoint and the needs-operator state.
14. **AC14 (ledger)** The ledger lines for every item this story implements are deleted from `deferred-work.md`;
    the residuals this story accepts (the revert crash window; same-card ambiguity if D2 option 1) are filed as
    one new line each.

## Tasks / Subtasks

- [ ] T0 Owner decisions D0-D8 recorded in this file; if D0 = 1, move Part B to skillars-deferred-175.
- [ ] T1 (AC1) Gateway `paymentMethodBelongsTo(customerId, pmId)` (Stripe + stub); resource check before save;
      no-row branch rejects; pack-purchase write checks too; error key in backend bundles and frontend locales;
      `PaymentMethodCard.vue` error mapping. Tests: `SessionPackPaymentResourceWebMvcTest` (mismatch, unattached,
      no row, Stripe down), `StripePaymentGatewayTest`, `PaymentMethodCardSpec.js`.
- [ ] T2 (AC2-AC4) Targeted `last_payment_intent_id` update; charge with the attempted card; CAS re-apply on
      success. Tests: `StripeCustomerRepositoryIT`, `SessionPackPaymentServiceTest` (:740, :767, :771, :929-948),
      `StripePaymentGatewayTest`.
- [ ] T3 (AC12) Migration V233.
- [ ] T4 (AC5) Admin close service and endpoint; runbook. Tests: `AdminFinanceResourceWebMvcTest` (role gate,
      validation), a service test (each Stripe state, the refusal, CAS loss), `PackPurchaseAttemptIT` (index
      released after close).
- [ ] T5 (AC6) Needs-operator marking, skip, regex. Tests: `SessionPackPaymentServiceTest` (three branches,
      including the missing tier-gone test), `PackPurchaseAttemptIT`.
- [ ] T6 (AC7) Anchor and parked-replay skip. Tests: `SubscriptionEntitlementIntegrityTest` (`drain_*`), new IT
      beside `SubscriptionLifecycleIT`.
- [ ] T7 (AC8) Gauge branch, alert, runbook, purge change. Tests: `OperationalBacklogMetricsIT`, purge test.
- [ ] T8 (AC9) Record a failed upgrade persist. Tests: `SubscriptionEntitlementIntegrityTest`.
- [ ] T9 (AC10) Event, listener, outbox support and handler. Tests: `EmailChangeServiceIT`, handler unit test
      beside `StripeCustomerErasureOutboxHandlerTest`.
- [ ] T10 (AC11, AC13) Docs; `node docs/dev-docs/tools/build-search-index.mjs`, then `check-docs.mjs`.
- [ ] T11 (AC14) Ledger lines; sprint-status; `/pr-review` until a clean round; PR.

## Dev Notes

- **Read before editing:** `SessionPackPaymentService` (the whole purchase and resolver flow), `StripePaymentGateway`
  (:130-260, :621-656), `SubscriptionService` (subscribe, persist, change tier, drain, guard), `StripeWebhookService`
  (park paths), `PaymentRecoveryJob`, `OperationalBacklogMetrics`, `AdminFinanceResource` and
  `CoachPayoutHoldRecoveryService` (the admin-action pattern), story skillars-deferred-169 (why the attempt table
  and parking exist; its AC8 card revert).
- **Money safety:** every attempt status write stays a CAS from an explicit expected set. Never close or skip an
  attempt in a way that ends the job's ability to grant or refund captured money, except through AC5's refusal.
- **Transactions:** `purchasePack`, `resolveAttempt`, `change*Tier` and the gateway are not transactional; each
  repository write commits on its own. Keep Stripe calls outside DB transactions (the ownership check included).
- **Migrations:** additive only (rule 5: no CHECK widening without a prior release). Next free number V233 at
  c6d88a63; recheck at implementation.
- **Lock audit:** a new `PessimisticLockRetryer` call site means updating `EXPECTED_CALL_SITE_COUNT` in
  `PessimisticLockRetryerCallSiteAuditTest` (68 at c6d88a63). None is expected.
- **Test context cache:** do not add a `@MockitoBean` to an existing IT or WebMvc slice without checking the
  context-cache gate (failsafe builds a fixed set of contexts; mock field names are part of the key). Prefer the
  stubs and existing mocks listed above.
- **Alerts:** a new alert needs a `monitoring.md` runbook entry (`AlertDocumentationParityTest`) and a
  provisioning-checklist line.
- **No local `mvn verify`:** run targeted tests with `-DskipFrontend -Djacoco.skip=true`; CI is the full gate.
  New worktrees need `src/frontend/.quasar` copied and `node_modules` linked before running Vitest.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` (RS lines 159-175 at c6d88a63)
- `_bmad-output/implementation-artifacts/skillars-deferred-169-stripe-retry-double-charge-webhook-ordering-and-card-save-fixes.md`
- `_bmad-output/implementation-artifacts/skillars-deferred-172-account-email-change.md`
- `docs/deployment/migration-conventions.md` (rule 5), `docs/deployment/monitoring.md`, `deploy/lgtm/grafana-alerts.yml`

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created.

### File List

### Change Log

- 2026-11-06: created (ready-for-dev); decisions D0-D8 pending owner.
