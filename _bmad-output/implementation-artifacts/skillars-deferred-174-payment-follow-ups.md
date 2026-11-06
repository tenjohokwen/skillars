# Story skillars-deferred-174: Payment follow-ups (card ownership, card-column writes, stuck pack attempts)

Status: ready-for-dev

<!-- CREATED 2026-11-06 (/bmad-create-story) from the deferred-work.md payment residuals left by
     skillars-deferred-169 and skillars-deferred-172: RS09b, RS09c, RS09d, RS06b, RS06e, RS05c, RS-poison, RS07b,
     RS03c. Chargebacks (deferred-work "bank chargebacks and dashboard refunds") are excluded: their own story.
     Code read at origin/master c6d88a63 by three research passes, and the scope-changing claims were
     re-checked by hand (gateway full-row save, revert-before-CAS order, staleness guard). Decisions D0-D8 are
     PENDING OWNER; each has a recommendation. Citations are line numbers at c6d88a63; re-verify before editing.
     REVISED 2026-11-06 after /mto-story-review (story-review.md): D2(c) CAS condition, AC1 status codes, D3 close
     rules, D4 bounded retry, D5 option 3 (status-regression guard, now recommended), D7 retry semantics, AC12
     lock_timeout, Part A/B doc and checklist ownership, 167 wording, RS05c ledger attribution.
     SPLIT 2026-11-06 (owner D0 = 1): Part B (RS05c, RS-poison, RS07b, RS03c; D5-D8; AC7-AC11) moved to
     skillars-deferred-175-subscription-follow-ups.md. Numbering here is unchanged, so AC7-AC11 and T6-T9 are gaps. -->


## Story

As **a parent, coach or player paying by card**, I want the card the server saves and charges to be one I
actually set up, so that a forged or foreign card id cannot become my default and a purchase never charges a
card I did not choose.

As **an operator**, I want a stuck pack purchase to be closable without SQL and to alert once instead of every
five minutes, so that a parent is never blocked from buying for longer than it takes me to act.

## Ledger mapping (so future passes do not re-pick this)

| Ledger id | Severity | What | Part |
|---|---|---|---|
| RS09b | medium / logic | `/save-payment-method` accepts any `pm_` id | A |
| (new) | medium / logic | Gateway's post-capture full-row save rewrites the saved card with a stale value | A |
| RS09c | low / logic | Declined-card revert is compensation, not prevention | A |
| RS09d | low / concurrency | Resolver revert vs a late capture | A |
| RS06b | medium / logic | Stuck pack attempt closable only with SQL | A |
| RS06e | low / logic | Unresolvable attempt repeats every cycle | A |

Part B (RS05c, RS-poison, RS07b, RS03c) moved to skillars-deferred-175 (D0 = 1).

No eval case is tied to these ids (checked `evals/review/cases/**`). `_drafts/340-card-sync-not-repointed.yaml`
covers neighbouring saved-card sync code from skillars-deferred-169; re-run it if T2 changes that code. Every ledger
line above is deleted from `deferred-work.md` in this PR.

Out of scope, left in the ledger on purpose: RS05b (invoice events not parked, "left on purpose"), RS06c (optional
`Idempotency-Key`), RS06d (partial refund counted as captured, a policy call the owner has not made).

## Background (code at c6d88a63)

Paths below are relative to `src/main/java/com/softropic/skillars/platform/payment/` unless shown otherwise.
`Svc` = `service/SessionPackPaymentService.java`, `SubSvc` = `service/SubscriptionService.java`. Outside that
base: `ConfigBounds` is `platform/config/service/ConfigBounds.java`; `OperationalBacklogMetrics` is
`infrastructure/persistence/`; `AdminTierGuard` is `infrastructure/security/`.

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
- Then `Svc.recordCardFingerprint` (:805, via `paymentGateway.cardFingerprint`, `StripePaymentGateway:255`) retrieves
  the pm and stores its fingerprint as an identity signal for the caller, so a foreign pm plants someone else's
  fingerprint on this account. This is the main harm. The money harm is smaller than it looks: Stripe rejects a
  pm not attached to the customer, so the subscription sync logs `..._SYNC_REJECTED` and a charge fails. What
  remains is a poisoned card column (every later charge fails until the user saves a real card) and the fingerprint.
- Error mapping: `PaymentApiAdvice:18-28` turns every `PaymentGatewayException` into 422. A 400 with a key is a
  `ResponseStatusException(BAD_REQUEST, key)`, which `ApiAdvice` renders as `ErrorDto(key)` (the convention `Svc`
  already uses).
- `PaymentGateway` has two implementers (`StripePaymentGateway`, test `StubPaymentGateway`). A Mockito mock of it
  answers `false` for a new boolean method, so existing `save-payment-method` WebMvc cases must stub it.
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
  (Spring Data sees a non-null id and no version, calls `merge`; Hibernate re-selects the row, copies the detached
  state over it and updates every updatable column. Even a managed entity would write all columns, since there is
  no `@DynamicUpdate`.) The same stale write can bring back a card that GDPR erasure just cleared.
- Other full-row writers of this entity: `Svc.getOrCreateStripeCustomer:817-818` (detached, same hazard, but it
  writes the card on purpose a moment after reading it), `SubSvc:765-766` (transactional, re-reads first: fine),
  `StripeCustomerErasureOutboxSupport:45-46` (MANDATORY transaction: fine). Only the gateway's is fixed here.

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
  move NO_CHARGE to SUCCEEDED; if `customerExisted`, `scheduleSubscriptionCardSync` (:281-282, :577-588) runs. It
  returns early when the card is blank or equals `previousCard`; otherwise it calls
  `SubSvc.savePaymentMethodAndScheduleSync`, which in one transaction sets the column (unconditionally) and enqueues
  the subscription card sync. On a normal success the column already holds the attempted card (written at :203),
  so the set is a no-op and the enqueue is what matters. The resolver's late-success path calls it too (~:458).
- `previousCard` is a request-local snapshot (:176-178; null when there was no customer or no card); the resolver
  uses the persisted `a.getPreviousCard()`.
- `noCharge` is reached two ways: an absence verdict (age-gated) and, with no age gate, a found PaymentIntent that is
  neither succeeded nor pending (~:404).
- What RS09d really produces (the ledger's wording is off): an absence verdict needs Stripe to show no
  PaymentIntent, so the request thread stalled before creating it. Stalled before the card write: the revert is
  a no-op, consistent. Stalled between the card write and the gateway's read at :145: the revert restores the
  previous card, so the gateway charges the PREVIOUS card, and :282 then makes the never-charged attempted card
  the default. Stalled after :145: the attempted card is charged and the :227 merge plus :282 restore it. So the
  defect is "the card charged is not the card the user chose", reachable only by API clients and only past the
  30-minute absence age (`ConfigBounds.PACK_ATTEMPT_ABSENCE_AGE_MINUTES`, `ConfigBounds:273-275`, bounds 15-1440;
  read at Svc:369). A 30-minute stall needs a JVM pause: the Stripe client timeouts are far shorter.
- `PaymentGateway.chargeAndCapture(UUID, Long, UUID, BigDecimal[, String idempotencyKey])` takes no payment
  method: the gateway reads it from the row (:145). `chargeAndCaptureForBatch` delegates (:269-272); the booking
  callers are `PaymentLifecycleService:259, :473`.
- Two concurrent purchases are already mostly prevented: the attempt insert (:185, partial unique index
  `uq_sppa_one_open_per_parent`) comes before the card write (:203). Not prevented (pre-existing, out of scope):
  once the resolver marks a stalled attempt NO_CHARGE the index no longer holds it, so a second purchase can start
  while the first thread is still alive and later captures, giving two packs.

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
  `[PACK_PURCHASE_RECORD_FAILED]`, which IS in the MoneyIntegrityEvent regex (`grafana-alerts.yml:648`), so a stuck
  row re-fires that alert every 5 minutes; the ledger did not mention this). The other three tags
  (`LOOKUP_FAILED`, `CHARGE_UNREADABLE`, `UNRESOLVABLE`) are not in the regex. `RECORD_FAILED` also fires once on the
  request path (~:245).
- Which branches can heal on their own: lookup-failed, unreadable-charge and record-failed can succeed on a later
  cycle (Stripe or the DB was briefly down). Tier-deleted cannot in practice: tiers are deactivated, not deleted,
  and `fk_spp_tier` blocks deleting a referenced tier, so this branch is rare and permanent.
- The job selects with `findOpenOlderThan(OPEN, cutoff)` (`status IN :open AND createdAt <= :cutoff`), used only by
  `resolveStaleAttempts`. A client replay of the same attempt key (`answerKnownAttempt`) calls `resolveAttempt`
  directly.
- `resolveAttempt(Long)` returns `Resolution`, a package-private record in `service`; an unknown id returns
  `Resolution(UNKNOWN, null)` instead of throwing. Stripe helpers it uses: `findChargeByIdempotencyKey`
  (`ChargeLookup.pending()/succeeded()`) and `getCaptureState` (`fullyRefunded()/unreadable()`), both throwing
  `PaymentGatewayException`. A pending (processing) intent goes to UNKNOWN, not NO_CHARGE (~:397).
- `createPurchase` races are settled by `uq_spp_stripe_payment_intent_id` (V195); the loser re-reads the winner.
- Today's way out: `docs/deployment/monitoring.md:418-424` (runbook SQL setting REFUNDED or SUCCEEDED by hand).
  Alert `PackAttemptsUnresolved` (`deploy/lgtm/grafana-alerts.yml:793-815`) on the `pack_attempts` branch of
  `infrastructure/persistence/OperationalBacklogMetrics.java:93-94`.
- Admin precedent: `api/AdminFinanceResource.java` (`/api/admin/payment`, `HAS_FULL_ADMIN_ROLE`),
  `POST /coaches/{coachId}/payouts/release-held` (:58) replaced a runbook UPDATE; its service calls
  `AdminTierGuard.requireFullAdmin()` (`CoachPayoutHoldRecoveryService.java:103`) and audits by log tag only.
  `payment` imports only `admin.contract` today; keep it that way (no `AdminActionLog` from here). This is a
  convention; no architecture test was found that enforces it. The class-level `HAS_FULL_ADMIN_ROLE`
  (`hasRole('ROLE_ADMIN')`) already refuses LTD_ADMIN; `requireFullAdmin()` is defence in depth.
- Admin error conventions: unknown id `ResourceNotFoundException` (404, `ApiAdvice:679`); conflict
  `ResponseStatusException(CONFLICT, key)`; `@Valid` body failure 400 (`ApiAdvice:527`); a Stripe failure
  (`PaymentGatewayException`) 422.

## Decisions (DECIDED 2026-11-06: option 1 in each)

- **D0: scope. DECIDED 2026-11-06: option 1** (Part B is skillars-deferred-175).
  - (1, recommended, chosen) Two stories: this one keeps Part A (cards and pack attempts, the money-adjacent half),
    and Part B moves verbatim to skillars-deferred-175. Every AC and task below carries an [A] or [B] tag so the
    split is mechanical. For comparison, skillars-deferred-167 (five items, twelve ACs) took seven `/pr-review`
    rounds; this story has nine items and fourteen ACs.
  - (2) One story, both parts. One migration and one PR, but a long review.
- **D1 (RS09b): how the server proves the card is the caller's. DECIDED 2026-11-06: option 1.**
  - (1, recommended, chosen) Ownership check: retrieve the pm and require `pm.customer == caller's stripe_customer_id`.
    Blocks foreign and unattached ids, the poisoned column and the fingerprint planting. Needs no API or frontend
    change beyond showing the new error. The resource's "no row: create a customer and save the pm" branch then
    answers 400 (a fresh customer cannot own it; the real flow always creates the row through `/setup-intent`
    first). A mismatch answers 400 `payment.paymentMethodNotOwned` (`ResponseStatusException`); a Stripe failure
    during the check answers 422 through the existing `PaymentGatewayException` advice. Proves the card was
    attached through our customer-scoped SetupIntent (our only attach path; `StripeClient.attachPaymentMethod`
    has no callers), not that it is chargeable today.
  - (2) Require the SetupIntent id too and check `status == succeeded`, same customer, same pm. Strongest, but a
    DTO change, a new Stripe call, and frontend changes in `payment.api.js` and `PaymentMethodCard.vue`.
  - Either way, apply the same check to the pack-purchase card write (`Svc.getOrCreateStripeCustomer`). It becomes
    mandatory if D2 charges the attempted card directly.
- **D2 (new finding, RS09c, RS09d): card-column writes. DECIDED 2026-11-06: option 1.**
  - (1, recommended, chosen) Three targeted fixes:
    - (a) Replace the gateway's :227 `save` with
      `UPDATE stripe_customers SET last_payment_intent_id = :id WHERE parent_id = :p`.
    - (b) The pack charge uses the attempt's own card (`attemptedCard`, falling back to the column only when the
      request sent none), so the card charged never depends on the mutable default. Add an overload
      `chargeAndCapture(..., String idempotencyKey, String paymentMethodId)` to `PaymentGateway`; the booking and
      batch callers keep the existing signature, so their mocks stay as they are.
    - (c) The success path re-applies the card with a compare-and-set that accepts both expected states:
      `SET pm = :attempted WHERE parent_id = :p AND (pm = :attempted OR pm IS NOT DISTINCT FROM :previous)`.
      `pm = :attempted` is the normal case (the column was written before the charge); `:previous` is the case
      where the resolver reverted it. One row updated: enqueue the subscription card sync in the same transaction
      (a new `@Transactional` method on `SubscriptionService` beside `savePaymentMethodAndScheduleSync`). Zero rows:
      someone saved another card meanwhile; keep it, no sync, log `[PACK_PURCHASE_CARD_SUPERSEDED]` at info.
      Independently, log `[PACK_PURCHASE_CAS_LOST]` at warn when the attempt's SUCCEEDED came from NO_CHARGE.
    - Leaves the crash window between a failed charge and the revert documented (no outbox for it).
  - (2) Also add a `payment_method_version` column bumped by every card writer, with the revert matching its own
    version. Closes the same-card ambiguity fully, but touches every writer, for a path only API clients reach.
- **D3 (RS06b): closing a stuck attempt. DECIDED 2026-11-06: option 1.**
  - (1, recommended, chosen) API-only admin action, `POST /api/admin/payment/pack-attempts/{id}/close` on
    `AdminFinanceResource` (class-level `HAS_FULL_ADMIN_ROLE`) plus `AdminTierGuard.requireFullAdmin()` in the
    service, body `{reason}` (required, max 500). A new public service method does, in order:
    1. `findById`, else 404; a terminal row is 409 `payment.packAttempt.notOpen`.
    2. `resolveAttempt(id)` (the full resolver, including a `createPurchase` retry; it ignores the D4 skip). If
       that closed the row, return its status.
    3. Otherwise read Stripe with the resolver's helpers and close only to what Stripe supports:
       - succeeded and a pack exists for the PaymentIntent: SUCCEEDED;
       - fully refunded: REFUNDED;
       - no PaymentIntent and the attempt is past the absence age, or a PaymentIntent that failed: NO_CHARGE,
         through `noCharge()` so the declined-card revert runs;
       - pending (processing) PaymentIntent: refuse, 409 `payment.packAttempt.chargePending`;
       - no PaymentIntent but younger than the absence age: refuse, 409 `payment.packAttempt.tooRecent` (a live
         request thread may still charge);
       - captured, unrefunded, no pack (step 2's retry failed): refuse, 409 `payment.packAttempt.capturedNoPack`;
         refund it in Stripe, then close.
    4. Every close is a CAS from the open set and also writes `closed_by`, `close_reason` and clears
       `needs_operator_since`. A Stripe failure answers 422. A `[PACK_ATTEMPT_ADMIN_CLOSED]` log tag is the audit
       (the release-held precedent). No new status, so no CHECK widening (rule 5 of `migration-conventions.md`
       would otherwise force a two-release rollout).
  - (2) Same, plus a "stuck pack purchases" table on `AdminFinancePage.vue`.
  - (3) A new `MANUALLY_CLOSED` status. Needs the CHECK widened one release before any code writes it.
- **D4 (RS06e): stop the repeat. DECIDED 2026-11-06: option 1.**
  - (1, recommended, chosen) New nullable column `needs_operator_since timestamptz`, set once by CAS where it is null:
    - tier-deleted: at once (it cannot heal);
    - unreadable-charge and record-failed: only once the attempt is older than
      `payment.packAttempt.operatorAfterMinutes` (new `ConfigBounds` key, default 60, bounds 15-1440); before that
      the job keeps retrying, because a DB or Stripe blip heals on the next cycle;
    - lookup-failed: never (that is a Stripe outage; marking would flood the operator queue).
    Setting it logs `[PACK_PURCHASE_ATTEMPT_NEEDS_OPERATOR]` once (added to the MoneyIntegrityEvent regex). The
    job query `findOpenOlderThan` gains `AND needs_operator_since IS NULL`, so later cycles skip the row and
    `[PACK_PURCHASE_RECORD_FAILED]` stops re-firing after about 12 cycles. The row keeps its open status, so it
    still blocks a second purchase (safe) and still feeds `PackAttemptsUnresolved`. D3's close action is the way
    out (its step 2 is also the operator's "retry now"). A client replay of the same attempt key still calls
    `resolveAttempt` directly; that is accepted (it is the payer asking, and it can heal the row).
  - (2) Back off instead (retry hourly). Still repeats, and still re-fires the regex alert.
  - (3) Mark all three branches at once (the first draft). Loses the automatic heal of a transient failure.
## Acceptance Criteria

Written with the recommended option of every decision. The [A] tags and the AC7-AC11 gap are left from the split
(D0 = 1); those ACs are skillars-deferred-175 AC1-AC5.

1. **AC1 [A] (RS09b, D1)** `POST /api/payment/save-payment-method` saves a card only when Stripe reports it attached
   to the caller's Stripe customer. A pm attached to another customer, an unattached pm, and a caller with no
   `stripe_customers` row each answer 400 `payment.paymentMethodNotOwned` and change nothing (no row, no outbox
   entry, no fingerprint). A Stripe failure during the check answers 422 through the existing
   `PaymentGatewayException` advice and saves nothing. The check runs in the resource before
   `savePaymentMethodAndScheduleSync`, outside its transaction. The same check guards the pack-purchase card write
   (same 400). `StubPaymentGateway` answers "owned"; WebMvc tests that mock `PaymentGateway` stub the new method.
   The new error key exists in every backend bundle (`MessageBundleParityTest`) and every frontend locale, and
   `PaymentMethodCard.vue` shows it.
2. **AC2 [A] (new finding, D2a)** After a capture the gateway updates only `last_payment_intent_id`. A repository IT
   proves that a card saved between the gateway's read and its post-capture write survives (red with the old
   `save`).
3. **AC3 [A] (RS09d, D2b)** A pack purchase charges the card the request named; when it named none, the saved card,
   as today. A test drives the RS09d interleaving (the resolver reverts after the card write, before the charge)
   and asserts the charged pm is the attempted one. Booking and batch charges are unchanged.
4. **AC4 [A] (RS09c, D2c)** On success the attempted card is re-applied only if the column holds the attempted or
   the previous card, and the subscription card sync is enqueued in the same transaction. Tests: the normal success
   (column holds attempted: one row, sync enqueued); the resolver reverted it (column holds previous: restored,
   sync enqueued); a third card saved meanwhile (kept, no sync, `[PACK_PURCHASE_CARD_SUPERSEDED]`); a null previous.
   `[PACK_PURCHASE_CAS_LOST]` is logged when the attempt's SUCCEEDED came from NO_CHARGE. The crash window (a crash
   between a failed charge and the revert) is stated in the revert's javadoc as accepted.
5. **AC5 [A] (RS06b, D3)** `POST /api/admin/payment/pack-attempts/{id}/close` with `{reason}`:
   a. LTD_ADMIN and non-admins get 403; a blank or over-500-character reason gets 400; an unknown id gets 404;
      an already-terminal attempt gets 409 `payment.packAttempt.notOpen`; a Stripe failure gets 422.
   b. It runs the resolver first and returns the resulting status if that closed it.
   c. Otherwise it closes by CAS from the open set, writing `closed_by` and `close_reason` and clearing
      `needs_operator_since`: SUCCEEDED when a pack exists for the PaymentIntent, REFUNDED when fully refunded,
      NO_CHARGE (through `noCharge()`, so the card revert runs) when the PaymentIntent failed or none exists past
      the absence age.
   d. It refuses, leaving the row unchanged: 409 `payment.packAttempt.chargePending` for a processing PaymentIntent,
      409 `payment.packAttempt.tooRecent` for no PaymentIntent inside the absence age, 409
      `payment.packAttempt.capturedNoPack` for a captured, unrefunded PaymentIntent with no pack.
   e. After a close the parent can start a new purchase (the partial unique index no longer holds the row).
   f. `monitoring.md`'s `PackAttemptsUnresolved` runbook replaces the hand-written UPDATEs with this action.
6. **AC6 [A] (RS06e, D4)** Tier-deleted sets `needs_operator_since` at once; unreadable-charge and record-failed set
   it only past `payment.packAttempt.operatorAfterMinutes` (new `ConfigBounds` key, default 60, bounds 15-1440);
   lookup-failed never does. Each logs `[PACK_PURCHASE_ATTEMPT_NEEDS_OPERATOR]` once; the job then skips the row (no
   repeated tags from the job). The row stays open: it still blocks a second purchase and still counts in the
   `pack_attempts` gauge. The new tag is in the MoneyIntegrityEvent regex. Tests: each branch before and after the
   threshold, the tier-deleted branch (it has no test today), the skip in `findOpenOlderThan`, and a client replay
   still resolving the row.
12. **AC12 [A] (migration)** One additive migration (next free number, V233 at c6d88a63) adds
    `payment.session_pack_purchase_attempts.closed_by`, `.close_reason` (varchar 500) and `.needs_operator_since`,
    all nullable, following `docs/deployment/migration-conventions.md`: no CHECK change, `SET LOCAL lock_timeout`
    before the `ALTER TABLE` (rule 7), and a header comment naming the lock taken (rule 9).
    `MigrationConventionLintTest` green. Code reads and writes the columns in the same release, as V221 did; the
    header says so.
13. **AC13 [A] (docs)** `docs/dev-docs/payment/index.html` and `docs/security-api-endpoints.md` describe the ownership
    check, the admin close endpoint and the needs-operator state; `docs/deployment/provisioning-checklist.md` gains
    the admin close action and the new `ConfigBounds` key; search index rebuilt; `check-docs` green. If D0 = 1, the
    story that merges second rebases its `index.html` and search-index edits on the first.
14. **AC14 [A] (ledger)** The ledger lines for every item this story implements are deleted from
    `deferred-work.md`; the residuals it accepts are filed as one new line each: the revert crash window; the
    same-card ambiguity (D2 option 1); the double pack after a NO_CHARGE verdict on a stalled thread; the other
    detached full-row writer (`Svc.getOrCreateStripeCustomer`).

## Tasks / Subtasks

- [x] T0a D0 recorded (option 1); Part B moved to skillars-deferred-175.
- [x] T0 Owner decisions D1-D4 recorded in this file (option 1 each).
- [ ] T1 [A] (AC1) Gateway `paymentMethodBelongsTo(customerId, pmId)` (Stripe + stub); resource check before save;
      no-row branch rejects; pack-purchase write checks too; error key in backend bundles and frontend locales;
      `PaymentMethodCard.vue` error mapping. Tests: `SessionPackPaymentResourceWebMvcTest` (mismatch, unattached,
      no row, Stripe down = 422; stub the new method in existing cases), `StripePaymentGatewayTest`,
      `PaymentMethodCardSpec.js`; check the no-row cases in `SubscriptionLifecycleIT` and `CaptureReservationIT`.
- [ ] T2 [A] (AC2-AC4) Targeted `last_payment_intent_id` update; `chargeAndCapture` overload with a payment method;
      the two-state CAS re-apply plus enqueue in one transaction. Tests: `StripeCustomerRepositoryIT`,
      `SessionPackPaymentServiceTest` (:740, :767, :771, :929-948; the purchase mocks move to the overload),
      `StripePaymentGatewayTest`.
- [ ] T3 [A] (AC12) Migration V233.
- [ ] T4 [A] (AC5) Public close method in `SessionPackPaymentService`, endpoint, runbook. Tests:
      `AdminFinanceResourceWebMvcTest` (role gate, validation, 404/409/422 mapping), a service test (each Stripe
      state, each refusal, CAS loss, `needs_operator_since` cleared), `PackPurchaseAttemptIT` (index released).
- [ ] T5 [A] (AC6) Needs-operator marking, threshold key, job-query skip, regex. Tests: `SessionPackPaymentServiceTest`
      (each branch before and after the threshold, including the missing tier-gone test), `PackPurchaseAttemptIT`.
- [ ] T10 [A] (AC13) Docs; `node docs/dev-docs/tools/build-search-index.mjs`, then `check-docs.mjs`.
- [ ] T11 [A] (AC14) Ledger lines; sprint-status; `/pr-review` until a clean round; PR.

## Dev Notes

- **Read before editing:** `SessionPackPaymentService` (the whole purchase and resolver flow), `StripePaymentGateway`
  (:130-260, :621-656), `SubscriptionService.savePaymentMethodAndScheduleSync` and `syncPaymentMethodToSubscriptions`,
  `PaymentRecoveryJob`, `AdminFinanceResource`
  and `CoachPayoutHoldRecoveryService` (the admin-action pattern), story skillars-deferred-169 (why the attempt table
  and parking exist; its AC8 card revert).
- **Money safety:** every attempt status write stays a CAS from an explicit expected set. Never close or skip an
  attempt in a way that ends the job's ability to grant or refund captured money, except through AC5's refusals.
  A close to NO_CHARGE always goes through `noCharge()`.
- **Transactions:** `purchasePack`, `resolveAttempt` and the gateway are not transactional; each
  repository write commits on its own. Keep Stripe calls outside DB transactions (the ownership check included).
  The D2(c) CAS and its outbox enqueue share one transaction.
- **Migrations:** additive only (rule 5: no CHECK widening without a prior release); rule 7 `SET LOCAL lock_timeout`;
  rule 9 header. Next free number V233 at c6d88a63; recheck at implementation.
- **Lock audit:** a new `PessimisticLockRetryer` call site means updating `EXPECTED_CALL_SITE_COUNT` in
  `PessimisticLockRetryerCallSiteAuditTest` (68 at c6d88a63). None is expected.
- **Test context cache:** `IntegrationTestConventionTest.distinctContextKeyCountIsPinned` (14) and
  `.github/scripts/assert-context-count.sh`. A `@MockitoBean` added to a `@WebMvcTest` slice only changes that slice
  (safe). One added to an `AbstractIntegrationTest` or `BasePaymentIT` subclass forks a context. The planned ITs
  (`PackPurchaseAttemptIT`, `StripeCustomerRepositoryIT`) must add no mocks.
- **Alerts:** no new alert here (D4 adds a tag to the existing MoneyIntegrityEvent regex). A new alert would need a
  `monitoring.md` runbook entry (`AlertDocumentationParityTest`) and a provisioning-checklist line.
- **Error keys:** `payment.paymentMethodNotOwned`, `payment.packAttempt.notOpen`, `.chargePending`, `.tooRecent`,
  `.capturedNoPack` in every backend bundle; only `paymentMethodNotOwned` reaches the frontend (the close is API-only).
- **No local `mvn verify`:** run targeted tests with `-DskipFrontend -Djacoco.skip=true`; CI is the full gate.
  New worktrees need `src/frontend/.quasar` copied and `node_modules` linked before running Vitest.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` (RS lines 159-175 at c6d88a63)
- `_bmad-output/implementation-artifacts/skillars-deferred-169-stripe-retry-double-charge-webhook-ordering-and-card-save-fixes.md`
- `_bmad-output/implementation-artifacts/skillars-deferred-175-subscription-follow-ups.md` (Part B, split off by D0)
- `docs/deployment/migration-conventions.md` (rule 5), `docs/deployment/monitoring.md`, `deploy/lgtm/grafana-alerts.yml`

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created.

### File List

### Change Log

- 2026-11-06: created (ready-for-dev); decisions D0-D8 pending owner.
- 2026-11-06: revised after /mto-story-review (story-review.md): D2(c) two-state CAS, D3 close rules and refusals, D4 bounded marking, D5 status-regression guard recommended, D6 log volume, D7 retry dedupe and runbook, D8 enqueue only with a customer; AC1 400/422, AC12 lock_timeout, [A]/[B] tags, docs and checklist ownership; background corrections.
- 2026-11-06: D0 = 1; Part B moved to skillars-deferred-175.
- 2026-11-06: owner decided D1-D4 (option 1 each).
