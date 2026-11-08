# Story skillars-deferred-178: Pack-attempt edge cases (stalled capture, unreadable charge, deleted customer, ownership race)

Status: in-progress

<!-- CREATED 2026-11-08 from deferred-work.md (RS section): RS06f, RS06g, RS06h, RS06j, the open residuals of the
     skillars-deferred-169/174 pack-attempt work. Owner chose this bundle on 2026-11-08 ("yes start with Pack-attempt edge
     cases") under the standing instruction to work autonomously; the design choices below are the recommended option each.
     Code read at origin/master c9f86f1e. -->

## Story

As **a parent buying a session pack**, I want a purchase that was judged uncharged never to charge me later, and a
duplicate of my own request to get the same answer as the first.

As **an operator**, I want a stuck pack attempt to be closable from Stripe's own records, never by hand-written SQL.

## Ledger mapping (so future passes do not re-pick this)

| Ledger id | Severity | What | AC |
|---|---|---|---|
| RS06f | low / concurrency | A request stalled past the absence age captures after the resolver wrote NO_CHARGE; the parent may have bought again | AC1 |
| RS06g | low / edge-case | A succeeded intent with no readable charge object stays open even after the operator refunds | AC2 |
| RS06h | low / edge-case | A lookup that fails on every try (customer deleted at Stripe) is 422 forever and never marked | AC3 |
| RS06j | low / edge-case | A same-key request losing the insert race gets 400/422 from the ownership check, not the winner's answer | AC4 |

All four lines are deleted from `deferred-work.md` in this PR; the residual accepted below is filed as RS06k.

## Background (code at c9f86f1e)

Paths are relative to `src/main/java/com/softropic/skillars/platform/payment/`.

- `service/SessionPackPaymentService.purchasePack`: replay check by key, gates, ownership check of a named card
  (`requireOwnedPaymentMethod`), attempt insert, customer and card write, then `paymentGateway.chargeAndCapture`. Not
  transactional: the resolver can write NO_CHARGE for a stalled request once it is past `payment.packAttempt.absenceAgeMinutes`
  (30), which frees the parent's one open-attempt slot.
- `resolve` (job, replay, admin close): `findChargeByIdempotencyKey` (customer-scoped PaymentIntent list, strongly
  consistent) or, for an admin close of an UNRECONCILED row, `retrieveCharge`; then `getCaptureState`, whose
  `CaptureState.unreadable()` is true when the PaymentIntent has no latest charge object (`OpenReason.CHARGE_UNREADABLE`,
  admin close answers 409 capturedNoPack). A failed lookup is `LOOKUP_FAILED` (422 from the close action) and is never
  marked needs-operator, since it reads as an outage.
- NO_CHARGE to SUCCEEDED is allowed (proof of capture beats an absence verdict) and logged `[PACK_PURCHASE_CAS_LOST]
  key=NO_CHARGE_TO_SUCCEEDED`, which no alert matches.

## Decisions (taken 2026-11-08: the recommended option in each)

**D17 (RS06f).** 1 *(chosen)*: re-read the attempt just before the charge (`existsByIdAndStatusNot(id, IN_PROGRESS)`) and
refuse with no charge if the resolver closed it; make the late capture an alert source (`[PACK_PURCHASE_LATE_CAPTURE]` in
MoneyIntegrityEvent) with a runbook step. 2: hold the open-attempt slot until the request thread ends (a heartbeat column the
absence verdict respects). Rejected for now: a schema change and a liveness protocol for a JVM pause past 30 minutes.

**D18 (RS06g).** 1 *(chosen)*: when the latest charge object is missing, judge the PaymentIntent from its `amount_received` and
its succeeded refunds, listed by PaymentIntent (`Refund.list(payment_intent)`). Stripe-backed, no operator assertion. 2: an
admin "force close" with an operator's word. Rejected: closes money rows on an assertion.

**D19 (RS06h).** 1 *(chosen)*: when the customer-scoped list fails permanently (`isPermanentLookupFailure`: a 4xx such as
`resource_missing` for a deleted customer), search PaymentIntents by the charge-key metadata (`PaymentIntent.search`), which
does not go through the customer. Search is eventually consistent (about a minute), well inside the 30-minute absence age.
2: mark such rows needs-operator and let the operator assert. Rejected, as D18.2.

**D20 (RS06j).** 1 *(chosen)*: when the ownership check refuses, re-read the attempt by key; a same-key attempt that appeared
meanwhile answers the request, as a later retry would be answered.

## Acceptance Criteria

1. **AC1 (RS06f, D17.1)** A purchase whose attempt left IN_PROGRESS before the charge is refused with no charge
   (`[PACK_PURCHASE_ATTEMPT_CLOSED_BEFORE_CHARGE]`). A capture that still lands on a NO_CHARGE attempt logs
   `[PACK_PURCHASE_LATE_CAPTURE]`, which MoneyIntegrityEvent matches.
2. **AC2 (RS06g, D18.1)** `getCaptureState` of a PaymentIntent without a readable charge but with `amount_received` reports
   captured = amount received and refunded = its succeeded refunds; unreadable only when nothing was received.
3. **AC3 (RS06h, D19.1)** `findChargeByIdempotencyKey` falls back to the key search on a permanent list failure only; a
   transient failure stays a lookup failure. The search only finds (newest match in the window); an empty search is a
   lookup failure, never a proven absence (residual RS06l).
4. **AC4 (RS06j, D20.1)** A same-key request refused by the ownership check is answered from a same-key attempt that exists by
   then.
5. **AC5** Docs: monitoring.md (MoneyIntegrityEvent, PackAttemptsUnresolved), provisioning checklist changelog, payment dev
   docs, Payments dashboard panel, ledger (four lines deleted, RS06k filed).

## Tasks / Subtasks

- [x] T1 (AC1) pre-charge re-read, late-capture tag, alert regex and dashboard panel; tests.
- [x] T2 (AC2) `StripeClient.listRefundsForPaymentIntent`, `captureStateFromRefunds`; tests.
- [x] T3 (AC3) `StripeClient.searchPaymentIntentsByMetadata`, the permanent-failure fallback; tests.
- [x] T4 (AC4) winner re-read on ownership refusal; test.
- [x] T5 (AC5) docs and ledger.

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5 (claude-opus-5-5)

### File List

- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/StripePaymentGateway.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/StripeClient.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseAttemptRepository.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/StripePaymentGatewayTest.java`
- `deploy/lgtm/grafana-alerts.yml`, `deploy/lgtm/dashboards/payments.json`
- `docs/deployment/monitoring.md`, `docs/deployment/provisioning-checklist.md`, `docs/dev-docs/payment/index.html`,
  `docs/dev-docs/assets/search-index.js`
- `_bmad-output/implementation-artifacts/deferred-work.md`, `_bmad-output/implementation-artifacts/sprint-status.yaml`

### Change Log

- 2026-11-08: created from RS06f/g/h/j; D17-D20 option 1; implemented T1-T5.
- 2026-11-08: `/pr-review` round 1 (f56f15a4): an unknown charge outcome on an attempt the resolver closed meanwhile raises
  `[PACK_PURCHASE_LATE_CAPTURE]` (general, medium); a refused-before-charge request reverts the card it wrote (both
  reviewers); a pending refund keeps the capture state unreadable instead of reading as captured (general, medium); a
  failing refund page is a lookup failure (general); an empty key search proves absence only for a charge past
  `SEARCH_SETTLE` (1 h), since Search is eventually consistent (both reviewers).
- 2026-11-08: `/pr-review` round 2 (bc23a17b): an empty key search is never a proven absence (general: the booking
  reconciler shares the lookup and would decline a captured booking; the settle window is withdrawn), and the search takes
  the newest match created in the lookup window; the late-capture alert after an unknown outcome fires only on NO_CHARGE
  (both reviewers); its status read is guarded; the closed-by-the-resolver check also runs before the card write, so a stale
  request never writes or reverts a card a later purchase may own (general). RS06l filed.
