# Story skillars-deferred-177: GDPR erasure × Stripe residuals (refuse an erased payer, sweep missed Stripe erasures)

Status: in-progress

<!-- CREATED 2026-11-08 from deferred-work.md (RS section): RS03h and RS03i, both filed by skillars-deferred-176's PR review
     round 7. Chosen autonomously (owner instruction 2026-11-08: "continue with the next story ... work autonomously"):
     every other open ledger item needs an owner decision or its own design (JWT flip date, session rotation, RS03b,
     RS06c contract change, RS06d policy). Code read at origin/master 37a4affa. Decisions D15-D16 continue 176's
     numbering; taken as the recommended option under the same instruction. -->

## Story

As **a person who erased their account**, I want no Stripe customer of mine to survive the erasure, and no payment to be
taken from me by a request that was still running when I erased it.

## Ledger mapping (so future passes do not re-pick this)

| Ledger id | Severity | What | AC |
|---|---|---|---|
| RS03h | low / edge-case | A pack purchase or SetupIntent overlapping the payer's own erasure still completes | AC1 |
| RS03i | low / database | A failed late `scheduleErasure` after customer creation is only logged; the Stripe customer keeps the person's data | AC2 |

Both lines are deleted from `deferred-work.md` in this PR; the residual the decisions accept is filed as RS03j.

## Background (code at 37a4affa)

Paths are relative to `src/main/java/com/softropic/skillars/platform/`.

- `payment/service/SessionPackPaymentService.getOrCreateStripeCustomer`: on an existing `stripe_customers` row it writes a
  named card through `StripeCustomerCardWrites.writeCardIfPayerLive` and ignores its `false` (erased payer); with no card it
  checks nothing. On a new row it calls `StripeCustomerEmailSyncOutboxSupport.syncIfEmailChangedSince`, which schedules the
  late Stripe erasure when the `FOR SHARE` email read sees the tombstone but returns nothing. `purchasePack` then charges.
- `payment/api/SessionPackPaymentResource.createSetupIntent`: same shape for a SetupIntent.
- `syncIfEmailChangedSince` catches every `RuntimeException` and logs `[STRIPE_CUSTOMER_EMAIL_SYNC_ENQUEUE_FAILED]`. In the
  tombstone branch it is the only scheduler of that customer's Stripe erasure (the erasure transaction found no row), so a
  failure there is permanent and no alert matches the tag.
- `payment/service/StripeCustomerErasureOutboxSupport.scheduleErasure` (MANDATORY) NULLs the card and enqueues
  `STRIPE_CUSTOMER_ERASURE`; it leaves no trace on the row that it ran.

## Decisions (taken 2026-11-08 under the owner's "work autonomously" instruction: the recommended option in each)

**D15 (RS03h): how to stop a request for an erased payer.**
1. *(chosen)* Refuse before the charge: `getOrCreateStripeCustomer` and `createSetupIntent` throw
   `PaymentGatewayException("payment.accountErased")` when the payer reads as erased under the row lock (existing row,
   card or not) or the post-creation check saw the tombstone. `purchasePack` already turns a pre-charge
   `PaymentGatewayException` into NO_CHARGE + `PackNoChargeException`. Leaves the window between the check and the Stripe
   call (RS03j).
2. Hold the row lock across the charge so the erasure waits. Rejected: holds a DB connection across a Stripe call, which
   `purchasePack` is deliberately structured to avoid.
3. Re-check after the charge and refund. Rejected for now: a new refund path for a sub-second window.

**D16 (RS03i): how to make the late Stripe erasure durable.**
1. *(chosen)* Stamp `stripe_customers.erasure_scheduled_at` in `scheduleErasure`, and add an hourly ShedLock sweep that
   calls `scheduleErasure` for every tombstoned payer whose row is unstamped, one transaction per payer, 100 per run. No
   backfill: earlier erasures are re-enqueued once, which the handler treats as a no-op at Stripe (`resource_missing`).
2. Only alert on the tag. Rejected: leaves a manual GDPR step.
3. Make the late scheduling throw. Rejected: the customer row is already committed on the SetupIntent path, so a retry
   never comes back through creation.

## Acceptance Criteria

1. **AC1 (RS03h, D15.1)** A pack purchase or SetupIntent whose payer reads as erased (existing row: a named card under the
   row lock as it is written, otherwise a `FOR SHARE` read of the account, never the row lock settlement holds; new row,
   including a SetupIntent that lost the creation race: the post-creation `FOR SHARE` read sees the tombstone) is refused with 422
   `payment.accountErased` before any charge or SetupIntent; a purchase's attempt is marked NO_CHARGE.
2. **AC2 (RS03i, D16.1)** `scheduleErasure` stamps `erasure_scheduled_at` in its transaction (V234). The hourly
   `StripeCustomerErasureSweep` enqueues the Stripe erasure for each tombstoned payer whose row is unstamped, once; a failure
   for one payer does not stop the others and is retried next run.
3. **AC3** Docs: monitoring.md (GdprRequestOverdue note), provisioning checklist (job table, changelog), dev docs
   (payment, scheduled jobs), ledger (RS03h/RS03i deleted, RS03j filed).

## Tasks / Subtasks

- [x] T1 (AC1) `StripeCustomerCardWrites.payerLive`; `syncIfEmailChangedSince` returns whether the account was erased;
  `getOrCreateStripeCustomer`, `requirePayerLive` and `createSetupIntent` refuse; `PaymentApiAdvice` logs it at WARN; tests.
- [x] T2 (AC2) V234, `StripeCustomer.erasureScheduledAt`, `markErasureScheduled`, `findErasedPayersWithoutScheduledErasure`,
  `StripeCustomerErasureSweep`; unit tests and `StripeCustomerRepositoryIT` sweep tests.
- [x] T3 (AC3) Docs and ledger.

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5 (claude-opus-5-5)

### File List

- `src/main/resources/db/migration/V234__stripe_customer_erasure_scheduled_at.sql` (new)
- `src/main/java/com/softropic/skillars/platform/payment/service/StripeCustomerErasureSweep.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/repo/StripeCustomer.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/StripeCustomerRepository.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/StripeCustomerErasureOutboxSupport.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/StripeCustomerEmailSyncOutboxSupport.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/StripeCustomerCardWrites.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java`
- `src/main/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResource.java`
- `src/main/java/com/softropic/skillars/platform/payment/api/PaymentApiAdvice.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/exception/PaymentGatewayException.java`, `PackNoChargeException.java`
- test `StripeCustomerCardWritesTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/StripeCustomerErasureSweepTest.java` (new)
- `src/test/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResourceWebMvcTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/StripeCustomerEmailSyncOutboxSupportTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/StripeCustomerErasureOutboxSupportTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/repo/StripeCustomerRepositoryIT.java`
- `docs/deployment/monitoring.md`, `docs/deployment/provisioning-checklist.md`, `docs/dev-docs/payment/index.html`,
  `docs/dev-docs/scheduled-jobs/index.html`, `docs/dev-docs/assets/search-index.js`
- `_bmad-output/implementation-artifacts/deferred-work.md`, `_bmad-output/implementation-artifacts/sprint-status.yaml`

### Change Log

- 2026-11-08: created from RS03h + RS03i; D15/D16 option 1 taken under the owner's autonomy instruction; implemented T1-T3.
- 2026-11-08: `/pr-review` round 1 (04b38cfd): `payerLive` reads the account `FOR SHARE` instead of taking the
  `stripe_customers` row lock (both reviewers: the no-card purchase and every SetupIntent would wait on settlement's Stripe
  charge); a SetupIntent that loses the creation race now checks the payer (both reviewers); a late erasure whose scheduling
  failed still refuses the request (security reviewer, kept on merit though outside its category).
- 2026-11-08: `/pr-review` round 2 (23916508): a refused purchase keeps `payment.accountErased` (PackNoChargeException kept
  only `payment.cardDeclined`) and logs at WARN; the refusal nulls the attempt's card ids (a row inserted after the erasure's
  own nulling kept them); Stripe customer creation sent an idempotency key from the payer id and the email's hash, so a
  request that loses the first-creation race leaves no customer that no row (and no erasure) names.
- 2026-11-08: `/pr-review` round 3 (3600d91b): that key withdrawn (general reviewer, medium: Stripe stores a 500 against
  the key and replays it for 24 hours, so one Stripe error would block the payer's first customer for a day; security
  reviewer: a concurrent same-key request gets 409). Instead the request that loses the race enqueues the Stripe erasure of
  the customer it created (`StripeCustomerErasureOutboxSupport.scheduleOrphanErasure`), on both creation paths.
