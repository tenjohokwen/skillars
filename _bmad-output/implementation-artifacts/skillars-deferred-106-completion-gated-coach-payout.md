# Story skillars-deferred-106: Completion-gated coach payout (separate charges & transfers)

Status: done

<!-- Validation optional. Run validate-create-story for a quality check before dev-story. -->

> ✅ **OWNER SIGN-OFF COMPLETE (2026-09-09).** Part B decisions D4/D5/D6 + AC9/AC11/AC12 were
> resolved at story creation and are recorded in the **Decisions** section below — outcomes are
> already folded into the ACs. What remains of AC0 is the mechanical doc update
> (`docs/architecture/payout-and-capture-pending.md`: flip `Status:` from `DRAFT`, mark D4–D6
> RESOLVED, add a pointer to this story key). Implementation may proceed.
>
> **Resolved:** build **B-1** (separate charges & transfers); `payout_hold_hours` default **48**;
> **no** post-completion cancellation (disputes only); **one** story (all ACs together); coach
> revenue basis = `coach_payouts` `RELEASED`; a Stripe test-mode connected account **must be
> provisioned** as part of AC15.

---

## Story

As **the Skillars platform**,
I want **the coach's share of a session fee to be transferred only after the session is confirmed complete** (not at booking confirmation / capture),
so that **a pre-completion cancellation, coach no-show, or dispute is a plain refund of platform-held funds with no coach claw-back, and the platform is never chasing money that has already left a connected account**.

### Context

`skillars-deferred-91` AC5 split two long-standing residuals into Part A (shipped) and Part B (this
story):

- **Part A — shipped** (`c2c47c1e`): the `CAPTURE_PENDING` → `CAPTURE_ABANDONED` timeout + slot
  release. Nothing in Part A is re-opened here.
- **Part B — this story**: the coach is currently paid **net of commission at capture, which
  happens in `PaymentLifecycleService`'s `AFTER_COMMIT` listeners right after the booking is
  accepted — days before the session.** That is a property of the Stripe **destination charge**
  (`transfer_data.destination` + `application_fee_amount` on the `PaymentIntent`), so there is no
  platform-controlled payout step to gate. `skillars-deferred-63` asked for payout to be gated on a
  completion signal instead. The design doc evaluated three Stripe Connect models and concluded
  **only B-1 (separate charges & transfers) actually satisfies "completion-gated"**:

  | Option | Verdict |
  | :--- | :--- |
  | **B-1 separate charges & transfers** | ✅ Charge the platform account at booking (no `transfer_data`); on `BookingCompletedEvent`, `Transfer.create(net → coach)`. Full timing control; a pre-payout refund has no transfer to reverse. **Chosen.** |
  | B-2 destination charge + manual capture | ✗ Stripe auth holds expire after 7 days; sessions are routinely booked >7 days out. |
  | B-3 destination charge + scheduled reversal on cancel | ✗ Does not meet the AC — payout still happens at confirmation; reversal can fail if the coach withdrew the balance. |

This is a **payment-architecture change**, not a local refactor. It touches `StripePaymentGateway`,
`PaymentLifecycleService`, `BookingPaymentPersistenceService`, `CancellationRefundService` /
`RefundEnqueueListener`, every `RevenueReportingService` / `CoachRevenue*` path, the
`skillars-deferred-91` transactional outbox (new `COACH_PAYOUT_TRANSFER` handler), `DisputeService`,
and adds a `coach_payouts` ledger table. It needs its own design review, migration sequence, and
Stripe test-mode verification.

---

## Acceptance Criteria

> Numbering: **AC0** is the sign-off gate. **AC1–AC12** are implementation. **AC13–AC15** are
> cross-cutting (migrations, runbook, tests). B.7 audit items are called out inline where they land.

### AC0 — Owner design sign-off (RESOLVED 2026-09-09 — mechanical doc update remains)

1. ✅ Owner answers to **D4** (build B-1), **D5** (`payout_hold_hours` default **48**), **D6**
   (B.7.1–B.7.5 are explicit ACs here), plus **AC9** (no post-completion cancellation), **AC11**
   (cutover), and **AC12** (revenue basis = `coach_payouts` `RELEASED`) are recorded in the
   **Decisions** section and folded into the ACs below.
2. ✅ No divergence from the AC4/AC7/AC10 assumptions remains unreconciled — AC4.3 (hold=48),
   AC9 (immutable), AC10.4 (dispute-in-window cancels pending payout), AC12 (RELEASED basis) are
   updated.
3. ⬜ `docs/architecture/payout-and-capture-pending.md` is updated: `Status:` line flips from
   `DRAFT`, D4/D5/D6 marked RESOLVED with outcomes, and a one-line pointer to this story key added.
   (This is the only remaining AC0 work.)

### AC1 — Charge the platform account, not a destination charge

1. `StripePaymentGateway.chargeAndCapture` / `chargeAndCaptureForBatch` build a `PaymentIntent`
   **without** `transfer_data.destination` and **without** `application_fee_amount`. The full
   session price is charged to the **platform** Stripe account. The commission is retained
   implicitly by transferring only the net later (AC4); it is no longer a Stripe
   `application_fee_amount`.
2. `PaymentIntent.metadata` still carries `referenceId` and `coachId` (unchanged) plus a new
   `transfer_group` = the booking id (or batch id) so the later `Transfer` can be correlated in
   Stripe.
3. The caller-supplied idempotency-key scheme in `StripePaymentGateway` (`pi-{referenceId}-{keyOwner}-{bucket}`,
   with its `IDEMPOTENCY_KEY_WINDOW` bucket for the shared-`referenceId` pack path) is **preserved
   unchanged** — the change is to the params, not the key.
4. `chargeAndCapture` still returns the `stripePaymentIntentId`; `BookingPayment.stripeCharged` /
   `creditDebited` / `CAPTURED` semantics are **unchanged** — `CAPTURED` still means "the parent
   was charged and it was recorded". Coach payout state is tracked separately (AC3), not by
   overloading `BookingPaymentStatus`.
5. `PaymentGateway.isCoachPaymentReady` / `resolveCoachStripeAccountId` still gate on the coach
   having a `COMPLETE` + `charges_enabled` connected account **at booking time** (fail fast — do not
   accept a booking for a coach who cannot ultimately be paid), but the account id is now used only
   at transfer time (AC4), not on the `PaymentIntent`.

### AC2 — `CAPTURE_PENDING` reserve-before-charge invariant preserved

1. `BookingPaymentPersistenceService.reserveCapture` still writes the `CAPTURE_PENDING`
   `booking_payments` row (with `reserved_at`) in its own `REQUIRES_NEW` transaction **before** the
   Stripe call, and the `PaymentPendingSweeper` invariant *"for a booking still in PAYMENT_PENDING,
   no `booking_payments` row ⇒ no Stripe call was attempted"* still holds. The charge target changed
   (platform vs connected account); the reservation discipline does not.
2. `PaymentPendingSweeper` Part A behaviour (`CAPTURE_ABANDONED` timeout, `CAPTURE_TIMEOUT` metric,
   `BookingPaymentUnresolvedEvent`) is untouched. A `CAPTURE_ABANDONED` booking never had a coach
   transfer initiated, so no payout-reversal path is reachable from it.
3. The `DEADLOCK CONSTRAINT` comments in `PaymentLifecycleService` (reserveCapture is `REQUIRES_NEW`
   on a second connection; the caller must hold no lock / uncommitted write on the booking row) are
   preserved verbatim if those methods are edited.

### AC3 — `coach_payouts` ledger table

1. New table `payment.coach_payouts`, **one row per booking** (`booking_id` PRIMARY KEY — a booking
   is paid out at most once), columns at minimum: `booking_id`, `coach_id`,
   `coach_stripe_account_id`, `gross_amount`, `commission_amount`, `net_amount`, `currency`,
   `status`, `stripe_transfer_id` (nullable), `stripe_transfer_reversal_id` (nullable),
   `release_after` (the `completed_at + hold_hours` instant), `released_at` / `reversed_at`
   (nullable), `attempts`, `last_error`, `created_at`, `updated_at`.
2. **Row lifecycle — the row is created at completion-enqueue time, not by the handler.** The
   `BEFORE_COMMIT` listener (AC4.1) inserts the row in `PENDING_RELEASE` **atomically with the
   booking's completion write**, alongside the outbox message. The handler then transitions it:
   - `PENDING_RELEASE` → `RELEASED` on a successful `Transfer.create` (stamps `stripe_transfer_id`,
     `released_at`).
   - `PENDING_RELEASE` → `HOLD` on a non-retryable transfer failure (AC8.2) — operator owns it.
   - `PENDING_RELEASE` → `CANCELLED` when a dispute resolves inside the hold window before the
     transfer fired (AC10.4) — no money moved.
   - `RELEASED` → `REVERSED` on a successful post-payout `Transfer.createReversal` (AC10.2).
   - `RELEASED` → `REVERSAL_FAILED` on a non-retryable reversal failure (AC8.3).
   - `HOLD` / `REVERSAL_FAILED` → `FAILED_PERMANENT` **only by an explicit operator action** per the
     runbook (AC14) once the case is judged unrecoverable. **No automatic/scheduled transition into
     `FAILED_PERMANENT`** — that would contradict the outbox "never drop, retry forever, `[OUTBOX_STUCK]`
     after 10" model. `HOLD` → `PENDING_RELEASE` is the operator's "coach reconnected, retry" action
     (re-enqueues one outbox message).
3. `status` domain (string — mirror `BookingPaymentStatus`'s "not an enum, `VARCHAR` compared as a
   string" convention): `PENDING_RELEASE`, `RELEASED`, `REVERSED`, `REVERSAL_FAILED`, `CANCELLED`,
   `HOLD`, `FAILED_PERMANENT`. A `contract` constants class `CoachPayoutStatus` with
   `isTerminal(...)` (`RELEASED` is **not** terminal — a dispute can still reverse it; terminal =
   `REVERSED` / `CANCELLED` / `FAILED_PERMANENT`) and an `isPayable(...)` helper (`PENDING_RELEASE`
   only) that the handler gates on (AC7.1).
4. The row is the **idempotency anchor** for both handlers (AC7.1, AC8.3): a payout transfer runs
   only if the row is `PENDING_RELEASE`; a reversal runs only if the row is `RELEASED`. The
   `booking_id` PK is the concurrent backstop (mirrors `uq_pcl_booking_refund` / V127 and
   `CreditWalletRefundOutboxHandler`'s "ledger row already exists ⇒ no-op").
5. **Also (AC4.2 / review finding #8): `booking_payments` gains one nullable column `commission_rate
   NUMERIC(5,4)`**, stamped at capture time by `persistPaymentSuccess` / `confirmPackBatchPayment` /
   `confirmCreditBatchPayment` with the `platform.commission.rate` in force **then**. This is an
   additive, catalog-only `ADD COLUMN` (exactly like V124's `reserved_at`) — **not** a CHECK/enum
   widen, so rolling-deploy **rule 5** still does not apply. The payout net is computed from this
   stored rate, never from `platform.commission.rate` re-read at completion (which may have changed).
6. Migration `V130` (AC13): `coach_payouts` table + `commission_rate` column, both additive. The
   `CHECK` on `coach_payouts.status` may be added validating in the same migration under
   `-- migration-lint: allow-validating-constraint new empty table`.

### AC4 — Release payout on `BookingCompletedEvent`, through the durable outbox

1. A new listener **inserts the `coach_payouts` row (`PENDING_RELEASE`, AC3.2) and enqueues a
   `COACH_PAYOUT_TRANSFER` outbox message**, both **atomically with the booking's completion
   write**. Follow the `RefundEnqueueListener` pattern exactly: a
   `@TransactionalEventListener(phase = BEFORE_COMMIT)` on `BookingCompletedEvent` calling a
   `CoachPayoutOutboxSupport.enqueuePayout(...)` that is `@Transactional(propagation = MANDATORY)`,
   saves the `coach_payouts` row, then calls `outboxService.enqueue(AGGREGATE_TYPE, json)` +
   `outboxService.requestDrainAfterCommit()`. A `coach_payouts` row that already exists for the
   booking (duplicate `BookingCompletedEvent` before the first tx committed — rare) makes the insert
   a no-op via the `booking_id` PK; the enqueue is still idempotent at the handler (AC7.1).
   - **Publisher-transaction caveat (verify during impl):** `BookingCompletedEvent` is published
     from three sites — `BookingCompletionService.submitWrapUp` (LIVE path, inside its `@Transactional`),
     `BookingCompletionService.confirmCompletion` (inside its `@Transactional`), and
     `QuickCompleteTimeoutService.processExpiredQuickCompletes` (inside a `transactionTemplate.executeWithoutResult`).
     All three publish inside an active transaction, so a `BEFORE_COMMIT` listener + `MANDATORY`
     enqueue is atomic with the completion in every case. Confirm no fourth publisher exists and no
     path publishes outside a transaction.
2. **Commission rate is fixed at capture, not completion (review finding #8).** The net is computed
   from `booking_payments.commission_rate` — the rate stamped when the parent was charged
   (AC3.5) — **never** from `platform.commission.rate` re-read at completion, which may have changed
   in between. Today's destination charge already locks the fee at capture (`application_fee_amount`
   on the `PaymentIntent`); B-1 must preserve that. The `coach_payouts` row (written at enqueue,
   AC3.2) stores the resolved `gross_amount` / `commission_amount` / `net_amount`; the outbox
   payload need only carry `bookingId` (the handler re-reads the row). Rounding matches
   `StripePaymentGateway`'s `feeCents`: `setScale(2, HALF_UP)`.
3. `payment.payout.hold_hours` config — **default 48** (D5), bounded (e.g. min 0, max 336 = 14d,
   the dispute window) via `configService.getBoundedLong`. The handler defers the transfer until
   `completed_at + hold_hours`: leave the outbox row with a future `next_attempt_at` (reuse the
   existing backoff column — see `OutboxMessage.nextAttemptAt` / V126). A dispute raised **within**
   the hold window cancels the still-pending payout outright (AC10.4) rather than pay-then-reverse.
   Setting the value to 0 restores release-on-completion without a code change. Note: the 14-day
   `disputes.submissionWindowDays` is longer than any sane hold, so the post-payout reversal path
   (AC8.3 / AC10.2) is still reachable and must be built — the hold only reduces its frequency.
4. **Credit-wallet / pack-funded and the split rule (review finding #3 — decided here).**
   - `stripe_charged = 0` (fully credit-covered or pack-funded): **no Stripe charge happened, so no
     transfer.** The enqueue listener writes no `coach_payouts` row (or writes one directly
     `CANCELLED` with reason `NO_STRIPE_CHARGE` for auditability — pick one, document it). Coach
     earnings for credit/pack bookings are out of scope and unchanged (design doc §0.1).
   - Partially-credit-covered (`0 < stripe_charged < price`): the transfer is
     **`net = stripe_charged × (1 − booking_payments.commission_rate)`, `setScale(2, HALF_UP)`** —
     i.e. the coach's Stripe payout is computed on the **card-charged portion only**, exactly the
     base today's `application_fee_amount = stripeAmount × rate` uses. The credit-covered portion's
     coach earnings are the same out-of-scope, unchanged path. Add ITs at 20/80, 50/50, 80/20
     credit splits asserting the transfer amount.
5. `recordNoShowCoach` is `UPCOMING`-only and never reaches completion, so a coach no-show simply
   never enqueues a payout — desired behaviour, no code needed, but assert it with a test.

### AC5 — `PaymentGateway` / `StripeClient` transfer + reversal methods

1. `PaymentGateway` gains `String transferToCoach(UUID transferGroupId, UUID coachId, BigDecimal netAmount, String currency)`
   returning the `stripeTransferId`, and `void reverseTransfer(String stripeTransferId, BigDecimal amount)`.
2. `StripeClient` gains `createTransfer(TransferCreateParams, idempotencyKey)` and
   `createTransferReversal(String transferId, TransferReversalCreateParams, idempotencyKey)`, thin
   wrappers over the Stripe SDK static calls, matching the existing `createPaymentIntent` /
   `createRefund(params, idempotencyKey)` shape.
3. Idempotency keys are **deterministic per booking**: `transfer-{bookingId}` and
   `reversal-{stripeTransferId}` — mirroring `refund-{stripePaymentIntentId}` (see
   `StripePaymentGateway.refund` / `StripeClient.createRefund(params, key)` Javadoc,
   `skillars-deferred-99` AC1). A re-driven outbox row must replay the original transfer at Stripe,
   never create a second.
4. `Transfer.create` uses `transfer_group` = booking id and `destination` = the coach's connected
   account id (resolved via `CoachStripeAccountRepository`, same `COMPLETE` + `charges_enabled`
   filter as `resolveCoachStripeAccountId`).
5. Stripe exceptions map to `PaymentGatewayException` (existing pattern); the handler decides
   retry-vs-hold from the failure (AC8).

### AC6 — Pre-payout cancellation / refund is unchanged (no transfer to reverse)

1. A cancellation, coach no-show, or dispute **before `BookingCompletedEvent`** for a card-funded
   booking is a plain `PaymentIntent` refund of platform-held funds — `PaymentGateway.refund(...)`
   as today. **No coach money is touched**, so no rebuttal window is needed (nothing is taken from
   the coach). Confirm `CancellationRefundService` / `RefundEnqueueListener` need no behavioural
   change for this path beyond AC12's revenue-side adjustments.
2. The `RefundEnqueueListener` `BEFORE_COMMIT` enqueue + `RefundOutboxSupport` /
   `CreditWalletRefundOutboxHandler` credit-wallet path is untouched.

### AC7 — `COACH_PAYOUT_TRANSFER` outbox handler: idempotent, exactly-once effect (B.7.1)

1. `CoachPayoutTransferHandler implements OutboxMessageHandler`, `aggregateType() = "COACH_PAYOUT_TRANSFER"`.
   `handle(payload)` loads the `payment.coach_payouts` row for the booking (`booking_id` PK) and
   **proceeds only if `status == PENDING_RELEASE` and `release_after` has passed**. For **any**
   other status — `RELEASED`, `HOLD`, `CANCELLED`, `REVERSED`, `REVERSAL_FAILED`,
   `FAILED_PERMANENT` — or a missing row, it **returns without calling Stripe** and logs at INFO
   that the re-drive was a no-op (review finding #2: the check is on the row *not being payable*,
   not narrowly on `RELEASED`). `release_after` not yet passed → throw so the outbox backoff
   re-drives it later (AC4.3). This mirrors `CreditWalletRefundOutboxHandler`'s "already-present ⇒
   no-op", widened to the full non-payable set.
2. **`BookingCompletedEvent` exactly-once is NOT assumed.** Today "one event per booking" rests on
   state-machine guards (each publish site runs after a `verifyStatus` + optimistic-locked
   `transition`; the completed-state transition commits once) — probably sufficient, but Part B must
   not depend on it silently. The `PENDING_RELEASE`-only gate + `booking_id` PK (AC7.1 / AC3.4) is
   the guarantee. Add a test: a **duplicate `BookingCompletedEvent`** and a **re-driven outbox row**
   for the same booking each yield **at most one** `Transfer.create` call.
3. On success the handler updates the row `PENDING_RELEASE` → `RELEASED` with `stripe_transfer_id`,
   `released_at` (amounts were already stamped at enqueue, AC3.2), and logs
   `[COACH_PAYOUT] released booking={} coach={} net={} transferId={}`.
4. The handler `throw`s to leave the row for the next drain (with `attempts++` / `last_error`) on a
   **retryable** failure (AC8.1), and takes the `HOLD` path (AC8.2 — update the row, do **not**
   throw) on a **non-retryable** one.

### AC8 — `Transfer.create` / `Transfer.createReversal` failure handling (B.7.3, B.7.5)

1. **Error classification (review finding #4).** Build an explicit `StripeException` → decision
   mapping in a documented helper/constant, verified against the pinned Stripe SDK version in
   `pom.xml`:
   - **Retryable → throw** (outbox backoff re-drives, `[OUTBOX_STUCK]` ERROR after
     `OutboxService.STUCK_ATTEMPTS_THRESHOLD` = 10 — reuse the outbox's mechanism, add nothing):
     `ApiConnectionException` (network), `RateLimitException` / HTTP 429, any HTTP 5xx
     (`ApiException`), `IdempotencyException` on a mismatched replay.
   - **Non-retryable → `HOLD`, do not throw** (AC8.2): `InvalidRequestException` whose failure is
     the *destination* — connected account missing / disconnected / restricted / rejected /
     `charges`+`transfers` capability not active — and `PermissionException`. Map the concrete
     Stripe `code` / `decline_code` strings during impl.
   - **Ambiguous / unmapped**: treat as **retryable** (throw) so it surfaces via `[OUTBOX_STUCK]`
     for a human rather than being silently parked in `HOLD`.
   - Tests: mock `createTransfer` with ≥5 representative errors (incl. one ambiguous) and assert
     throw-vs-`HOLD` per row.
2. **Non-retryable transfer failure** (chiefly B.7.5 — coach disconnected the connected account
   between `BookingCompletedEvent` and the transfer; also account closed / restricted / capability
   off): the handler updates the `coach_payouts` row `PENDING_RELEASE` → `HOLD` with `last_error`,
   emits `coach.payout.held{reason="INVALID_DESTINATION"|"ACCOUNT_RESTRICTED"|…}` (this metric is
   the disconnect-frequency signal — no separate metric needed) plus a WARN
   `[COACH_PAYOUT_HELD] booking={} coach={} reason={} — payout blocked, operator must reconcile
   (runbook: COACH_PAYOUT_HELD)`, and **does not throw** (a `HOLD` is a decision; re-driving every
   drain would just re-log). The operator's "coach reconnected" action re-enqueues one message and
   flips the row back to `PENDING_RELEASE` (AC3.2); an unrecoverable case is closed to
   `FAILED_PERMANENT` by hand.
3. **`Transfer.createReversal` failure** (post-payout, coach withdrew the balance / network /
   account closed): same split — retryable → throw / outbox backoff; non-retryable → row
   `RELEASED` → `REVERSAL_FAILED` with `last_error`, `coach.payout.reversal_failed` metric + ERROR
   carrying `bookingId` + `coachId`, and the `COACH_PAYOUT_REVERSAL` runbook entry (AC14.1).
   Unrecoverable → operator closes to `FAILED_PERMANENT`. Reversals run through their **own** outbox
   aggregate type `COACH_PAYOUT_REVERSAL`, enqueued atomically with the dispute-resolution write
   (AC10.2).
4. **`FAILED_PERMANENT` is operator-only (review finding #7).** Nothing auto-transitions into it —
   not `[OUTBOX_STUCK]`, not a scheduler, not an age threshold. It is the state an operator writes
   (runbook step, AC14.1) when a `HOLD` / `REVERSAL_FAILED` case is judged unrecoverable (coach
   account permanently closed, coach left the platform). A re-driven outbox row for a
   `FAILED_PERMANENT` booking is a no-op (AC7.1). Add a test.

### AC9 — Post-completion state is immutable for cancellation (B.7.2 — RESOLVED: NO)

1. **Decision (D6/AC9): a `COMPLETED` booking cannot be cancelled or refunded by a parent or
   coach.** The post-completion state is immutable for cancellation. Any post-completion grievance
   goes through `DisputeService` only (AC10), which owns the transfer-reversal path.
2. Every cancel / no-show / refund entry point rejects a `COMPLETED` (or otherwise terminal)
   booking with `OperationNotAllowedException` + an i18n error code (the project pattern — mirror
   `BookingError.CONCURRENT_MODIFICATION` / `DisputeError.NOT_ELIGIBLE`; **not** a raw
   `IllegalStateException`). Add the guard as a fail-fast check even where the state machine already
   blocks the transition, so a future refactor cannot open the path silently. Cover it with an IT
   per endpoint (parent cancel, coach cancel, coach no-show, admin cancel) asserting the rejection
   + error code.
3. **Grep across all of `src/main`** (review finding #5 — not just `BookingService`): every caller
   of `BookingService.transition`, every `*CancellationRefundService` / `DisputeService` /
   `AdminService` path, and every `PaymentGateway.refund` call site. Confirm none can be reached for
   a `COMPLETED` booking in a way that would refund a charge whose funds have already been (or are
   being) transferred to the coach. Record the grep command + result in the Dev Agent Record.
4. `DisputeService`'s `ELIGIBLE_STATUSES` already includes `"COMPLETED"` — that is the intended and
   only post-completion route, and AC10 extends it with the reversal.

### AC10 — `DisputeService` post-payout path (B.7.4)

1. Document which `DisputeService` flows are **reused** vs **new**. Today `resolveDispute`'s
   `FULL_CREDIT` / `PARTIAL_CREDIT` / `COACH_WARNING` branches write a `BOOKING_REFUND`
   `parent_credit_ledger` entry via `creditWalletService.writeLedgerEntry` — that is a **parent
   credit**, not a Stripe refund, and it does not touch the coach.
2. `resolveDispute` reads the `payment.coach_payouts` row (it always exists for a `COMPLETED`
   booking — written at completion-enqueue, AC3.2 — unless `stripe_charged = 0`) and branches on
   its status **under a row lock**, atomically with the dispute-resolution write (a BEFORE_COMMIT
   enqueue, same `RefundEnqueueListener` pattern):
   - row `RELEASED` (coach already paid): enqueue a `COACH_PAYOUT_REVERSAL` for the disputed amount
     (AC8.3); the reversal handler flips `RELEASED` → `REVERSED` on success.
   - row `PENDING_RELEASE` (dispute inside the 48h hold, transfer not yet fired — review finding
     #1): flip the row `PENDING_RELEASE` → `CANCELLED` **in the dispute transaction**. The pending
     `COACH_PAYOUT_TRANSFER` outbox row is **left in place** (never-drop invariant) — its handler
     sees the non-`PENDING_RELEASE` row on next drain and no-ops (AC7.1). No transfer, no reversal.
   - row `HOLD` (transfer already failed non-retryably): flip `HOLD` → `CANCELLED`; nothing to
     reverse.
   - no row (`stripe_charged = 0`): nothing to do on the coach side.
3. `DisputeService` reversal / cancel enqueue is **idempotent** (a dispute filed or resolved twice
   must not double-reverse or re-cancel) — the `coach_payouts` row status gate above plus the
   deterministic `reversal-{stripeTransferId}` key (AC5.3) are the anchors; `resolveDispute`
   already 409s on an already-resolved dispute.
4. `payment.payout.hold_hours` (default 48, AC4.3): the "dispute inside the hold window cancels the
   still-pending payout" branch above **is** the hold-window behaviour — it depends only on the
   `coach_payouts` row still being `PENDING_RELEASE`, not on inspecting the outbox row's
   `next_attempt_at`. A dispute after the payout has `RELEASED` takes the reversal branch. Cover
   both with tests (dispute at t+1h with hold=48 → `CANCELLED`, no transfer; dispute at t+72h →
   `RELEASED` then `REVERSED`).
5. `RevenueReportingService.getAdminCoachRevenue` currently hard-codes
   `int outstandingDisputeCount = 0; // TODO Story 10.x: wire booking_disputes table` — wiring the
   real count is **out of scope** here unless trivially adjacent; leave the TODO or note it.

### AC11 — Cutover safety (no double-pay across the model switch)

1. Bookings whose `PaymentIntent` was created **before** this deploy used a destination charge —
   the coach was already paid at capture. The `COACH_PAYOUT_TRANSFER` handler must **not** transfer
   again for those.
2. Mechanism (choose during impl, document the choice): e.g. only enqueue a payout for bookings
   whose `booking_payments.reserved_at` (or a new `payout_model` marker column) is after a recorded
   cutover instant; or backfill `coach_payouts` `RELEASED` rows for all currently-`CAPTURED`
   bookings so the idempotency check (AC7.1) suppresses them. A backfill `UPDATE`/`INSERT` must be
   **chunked** per rolling-deploy **rule 6** (`MigrationLint.Rule.UNBATCHED_DML`).
3. Because Skillars has **no production system** (standing project fact — `migration-conventions.md`
   Grandfathering), the pragmatic option is acceptable if documented. "Written down" means: the
   chosen mechanism + rationale in this story's Dev Agent Record **and** a header comment on the
   cutover migration, e.g.:
   ```sql
   -- skillars-deferred-106 cutover: switching from Stripe destination charges (coach paid at
   -- capture) to separate charges & transfers (coach paid on completion, this story). Bookings
   -- CAPTURED before this deploy already paid the coach at capture, so [inserting coach_payouts
   -- rows in RELEASED for every currently-CAPTURED booking_payments row] makes AC7.1's PK/status
   -- gate suppress a second transfer for them. Batched by ctid range (rule 6). Skillars has no
   -- production system (migration-conventions.md Grandfathering), so a one-shot backfill is
   -- acceptable here; a live deployment would instead ship a payout_model marker column one
   -- release ahead.
   ```
   The backfill `INSERT ... SELECT` is chunked (`ctid` batch loop, own transaction per chunk) per
   rolling-deploy **rule 6** (`MigrationLint.Rule.UNBATCHED_DML`).

### AC12 — Revenue reporting: "captured" → "released", copy change (B.5)

1. **Decision (AC12): coach-facing revenue is based on `payment.coach_payouts` rows in status
   `RELEASED`** — that is when the coach was actually paid. `RevenueReportingService` coach figures
   (`getCoachRevenueSummary`, `getCoachTransactions`, `getCoachReceipt`, `getAdminCoachRevenue`)
   move off the `bp.status = 'CAPTURED'` filter (`sumGrossByCoachAndPeriod` /
   `countCapturedByCoachAndPeriod` / `findByCoachAndPeriod` / `findBookingIdsByCoachAndPeriod`) and
   onto `coach_payouts` (`RELEASED`, dated by `released_at`). Add `CoachPayoutRepository` query
   methods for the sums/counts/pages. Net/commission/gross now come straight from the ledger row
   (AC3.1) instead of being recomputed from `stripeCharged + creditDebited × rate`.
2. A completed-but-not-yet-released session (payout `PENDING_RELEASE`, e.g. inside the 48h hold or
   awaiting a drain) shows as a **separate "pending release" figure/line** in the summary and
   transactions DTOs — not folded into released revenue. Extend `RevenueSummaryDto` /
   `TransactionDto` / `CoachRevenueAdminDto` with the pending-release amount + count; keep
   `ReceiptDto` gated on a `RELEASED` payout (a receipt for money not yet released is misleading —
   same reasoning as today's `CAPTURED`-only receipt gate, UAT.3 AC1).
3. Admin-side `getAdminOverview` (`totalGrossVolume`, `totalCommissionCollected`) is
   parent-charge-based and can stay on `booking_payments.CAPTURED` — commission is now retained in
   the platform balance rather than collected as `application_fee_amount`, but the *amount* is
   unchanged. Verify and note.
4. **Copy**: coach revenue dashboard / receipt wording changes from "paid" to **"released to your
   Stripe account"** — `Transfer.create` only *initiates* the move; Stripe's own payout schedule
   then pays the coach's bank (design doc §B.5). All user-facing strings via `vue-i18n`
   (`project-context.md` I18n rule); update `de-DE` and `fr-FR` bundles (formal register / idiom —
   see `skillars-deferred-91` / `-92` precedent).

### AC13 — Migration sequence (rolling-deploy safe)

1. `V130__coach_payouts_and_commission_rate.sql` (all additive, `SET lock_timeout = '5s';` at the
   top — rule 7):
   - `CREATE TABLE payment.coach_payouts` (+ indexes + `chk_coach_payouts_status` CHECK under
     `-- migration-lint: allow-validating-constraint new empty table`). `booking_id` PK is the
     idempotency anchor (AC3.4) — no separate partial unique index needed.
   - `ALTER TABLE payment.booking_payments ADD COLUMN commission_rate NUMERIC(5,4)` — nullable, no
     default, **catalog-only** ADD COLUMN, exactly like V124's `reserved_at`. Header note that it
     is stamped going forward by `persistPaymentSuccess` and left NULL for pre-V130 rows (the
     cutover backfill / payout code handles NULL → fall back to `platform.commission.rate` for
     already-`CAPTURED` legacy rows only).
2. `V131__coach_payout_config.sql`: seed `main.platform_config` keys — `payment.payout.hold_hours`
   with value **`48`** (D5), `value_type = 'LONG'`, plus any `payment.payout.*` toggles —
   **omitting `id`** (identity since V128, rule 8), `ON CONFLICT (key) DO NOTHING`, a `description`
   naming the 0–336h bound and that 0 = release-on-completion (follow
   `V99__payment_currency_config.sql` shape). Config seeds carry no lock-taking DDL.
3. `V132` (or later): the cutover backfill (AC11.2) is its **own** migration, chunked (rule 6),
   with the header block from AC11.3.
4. All new migrations are `> V129`, so `MigrationConventionLintTest` binds fully. Run the
   `migration-conventions.md` **Go-forward checklist** against each. The only `booking_payments`
   change is the additive nullable `commission_rate` column — **no CHECK/enum widen**, so
   rolling-deploy **rule 5** does not apply (unlike V124).

### AC14 — Runbook

1. New `docs/deployment/runbook.md` section **`COACH_PAYOUT_HELD` / `COACH_PAYOUT_REVERSAL`** with:
   - detection query over `payment.coach_payouts`
     (`status IN ('HOLD','REVERSAL_FAILED','FAILED_PERMANENT')`) + the `coach.payout.held` /
     `coach.payout.reversal_failed` metrics to alert on;
   - **`HOLD` — invalid/disconnected destination (B.7.5)**: the coach-facing message to send —
     *"Your Stripe payout account was disconnected before we could release payment for session
     {ref}. Please reconnect it at {link}, then reply here — we'll verify and retry automatically."*
     — and the operator step to flip `coach_payouts.status` `HOLD` → `PENDING_RELEASE` + re-enqueue
     one `COACH_PAYOUT_TRANSFER` message once the coach confirms;
   - **`REVERSAL_FAILED` (B.7.3)**: coach withdrew the balance / account closed → manual
     follow-up; how to record a manual reversal;
   - **`FAILED_PERMANENT`**: the explicit operator SQL to close an unrecoverable `HOLD` /
     `REVERSAL_FAILED` row (coach permanently gone), and confirmation that a re-driven outbox row
     then no-ops (AC7.1). Nothing auto-transitions here (AC8.4).
2. Fold the two outstanding **Part A** audit follow-ups into runbook Scenario 4 (they were flagged
   "still to fold in" in the design doc A.4 / §7 and never landed):
   - **audit #1** — after a row is `CAPTURE_ABANDONED` nothing consumes a later
     `payment_intent.succeeded`; the reconciliation step must *always* query Stripe / the webhook
     audit log for events on the `PaymentIntent`, and a "successful charge on a `CAPTURE_ABANDONED`
     booking" dashboard/alert is worth adding.
   - **audit #2** — explicit `stripe_charged` → action map: `= 0` → check the `PaymentIntent` for a
     failure reason, nothing to reverse; `> 0` → confirm capture state in Stripe, and if captured,
     query Stripe Transfers by `metadata.transfer_group` / `referenceId` before deciding refund vs
     transfer-reversal.
3. Update `migration-conventions.md` Grandfathering + `runbook.md` pre-production trigger list only
   if this story changes the V124 debt statement (it should not — V124 is untouched).

### AC15 — Tests + Stripe test-mode verification

1. **Unit**: `CoachPayoutTransferHandlerTest` — happy path `PENDING_RELEASE` → `RELEASED` + one
   `Transfer.create`; duplicate `BookingCompletedEvent` / re-drive of a `RELEASED` / `HOLD` /
   `CANCELLED` / `FAILED_PERMANENT` row → **no** transfer (AC7.1/7.2, review finding #2 & #7);
   `stripe_charged = 0` → no transfer; `release_after` in the future → throws (deferred);
   **error-classification matrix** (AC8.1) — ≥5 representative `StripeException`s incl. one
   ambiguous, asserting throw-vs-`HOLD` per row; retryable error → throws, row stays
   `PENDING_RELEASE`.
2. **Unit**: `StripePaymentGatewayTest` — `PaymentIntent` params no longer carry `transfer_data` /
   `application_fee_amount`, do carry `transfer_group`; the capture path stamps
   `booking_payments.commission_rate`; `transferToCoach` builds the expected `TransferCreateParams`
   + deterministic `transfer-{bookingId}` key; `reverseTransfer` likewise.
3. **Unit**: net-split — `stripe_charged × (1 − commission_rate)` `HALF_UP` at 20/80, 50/50, 80/20
   credit splits (AC4.4); a `commission_rate` change between capture and completion does **not**
   move the net (review finding #8) — old booking uses its stamped rate.
4. **IT** (extend an existing payment IT context — no new `@TestPropertySource`, per the Part A
   test-plan precedent and `skillars-deferred-19` container-consolidation):
   - booking completes → outbox drains → `coach_payouts` `RELEASED` + fake gateway records one
     transfer;
   - pre-completion cancel → plain refund, `coach_payouts` has no row (or `CANCELLED` reason
     `NO_STRIPE_CHARGE`);
   - dispute resolved at t+72h (payout `RELEASED`) → `COACH_PAYOUT_REVERSAL` enqueued and driven,
     row `REVERSED`;
   - dispute resolved at t+1h with `hold_hours=48` (row still `PENDING_RELEASE`) → row `CANCELLED`,
     outbox `COACH_PAYOUT_TRANSFER` drains to a **no-op**, no transfer ever created (review finding
     #1);
   - cancel / no-show / admin-cancel attempted on a `COMPLETED` booking → rejected with the i18n
     error code (AC9.2), no refund, coach payout untouched.
5. **`RefundOutboxIT`-style** outbox ITs for both new aggregate types
   (`COACH_PAYOUT_TRANSFER`, `COACH_PAYOUT_REVERSAL`): the `coach_payouts` row + outbox row commit
   atomically with the completion / dispute-resolution write; a re-drive is a no-op.
6. **Stripe test-mode connected account — PREREQUISITE (must be provisioned; none exists today).**
   Before AC15.7 can run, stand up a Stripe **test-mode** account with at least one connected
   (Express) test account and record the keys/ids in `docs/deployment/secrets-reference.md` (test
   section) + the local/UAT config. Name the owner of this task in the Dev Agent Record. This is
   the only genuinely external dependency in the story — flag it early so it does not block the
   final verification.
7. **Stripe test-mode run**: a documented manual (or scripted) run against the AC15.6 test account
   proving charge-to-platform → transfer-to-connected-account → transfer-reversal all succeed with
   the chosen deterministic idempotency keys (and that a replayed key does **not** double-move
   money). Record the run + output in the Dev Agent Record.
8. CI is the sole full-verification gate — do **not** run `mvn verify` locally before push
   (standing project rule).

---

## Tasks / Subtasks

- [x] **AC0** — Owner sign-off (decisions captured 2026-09-09; see **Decisions**)
  - [x] D4/D5/D6 + AC9/AC11/AC12 answered by owner; folded into the ACs
  - [x] Update `docs/architecture/payout-and-capture-pending.md` (Status line, D4–D6 → RESOLVED with outcomes, `skillars-deferred-106` pointer)
- [x] **AC1 / AC2** — Stripe charge model switch
  - [x] `StripePaymentGateway.chargeAndCapture` / `chargeAndCaptureForBatch`: drop `transfer_data` + `application_fee_amount`; add `transfer_group` metadata; keep idempotency key scheme
  - [x] `persistPaymentSuccess` / `confirmPackBatchPayment` / `confirmCreditBatchPayment` stamp `booking_payments.commission_rate` at capture (AC3.5 / finding #8) — via `currentCommissionRate()` helper
  - [x] Confirm `reserveCapture` / `PaymentPendingSweeper` invariant + `DEADLOCK CONSTRAINT` comments intact — not edited; only `loadOrCreate` callers touched
  - [x] Grep `transfer_data` / `application_fee` / `application_fee_amount` in `src/main` + `src/test`: only the gateway had `setTransferData`/`setApplicationFeeAmount` (now removed); V61 config `description` string mentions it (cosmetic, left)
- [x] **AC3** — `coach_payouts` ledger
  - [x] `CoachPayout` entity (`booking_id` PK) + `CoachPayoutRepository` (`payment.repo`)
  - [x] `CoachPayoutStatus` constants class in `payment.contract` (`isTerminal` / `isPayable`)
- [x] **AC5** — gateway + client transfer methods
  - [x] `PaymentGateway.transferToCoach` / `reverseTransfer` (+ `StubPaymentGateway`)
  - [x] `StripeClient.createTransfer` / `createTransferReversal` (idempotency-key overloads)
  - [x] `StripeTransferErrorClassifier` retryable/HOLD helper (AC8.1 / finding #4), verified vs stripe-java 28.4.0; `CoachPayoutTransferException` carries the decision
- [x] **AC4 / AC7 / AC8** — payout release path
  - [x] `CoachPayoutOutboxSupport.enqueuePayout` / `enqueueReversal` — `@Transactional(MANDATORY)`: insert `coach_payouts` row + `outboxService.enqueue` (with hold-window `notBefore`) + `requestDrainAfterCommit`
  - [x] `CoachPayoutEnqueueListener` — `@TransactionalEventListener(BEFORE_COMMIT)` on `BookingCompletedEvent`
  - [x] `CoachPayoutTransferHandler` — `PENDING_RELEASE` & `release_after` gate; HOLD-vs-throw split; `RELEASED`; `coach.payout.held{reason}` metric + logs
  - [x] `CoachPayoutReversalHandler` + `COACH_PAYOUT_REVERSAL` aggregate type
  - [x] `OutboxService.enqueue(type, payload, notBefore)` overload + `OutboxMessage(type, payload, notBefore)` ctor (AC4.3)
- [x] **AC9 / AC10** — post-completion + dispute
  - [x] Fail-fast `OperationNotAllowedException` + `BookingError.BOOKING_ALREADY_COMPLETED` (`booking.alreadyCompleted`) via `BookingService.rejectIfPostCompletion` on `cancelBookingAsParent` / `cancelBookingAsCoach` / `recordNoShowCoach` / `recordNoShowPlayer` / `cancelDueToPause`. Grep result recorded in Completion Notes (admin suspension cancel is REQUESTED-only by construction; `PaymentGateway.refund` sites are cash-out + pack-purchase compensating, not booking-cancel)
  - [x] `DisputeService.resolveDispute` → `reconcileCoachPayout()` reads `coach_payouts` via `findByIdForUpdate`: `RELEASED`→`enqueueReversal` (proportional coach share); `PENDING_RELEASE`/`HOLD`→flip `CANCELLED` (outbox row left in place, handler no-ops); no row→nothing
  - [x] Idempotent (row-status gate + `reversal-{transferId}` key + existing already-resolved 409)
  - [ ] `getAdminCoachRevenue` `outstandingDisputeCount = 0` TODO left as-is (AC10.5 — out of scope)
- [x] **AC11** — cutover
  - [x] Mechanism: one-shot `INSERT ... SELECT` of `RELEASED` `coach_payouts` rows for every currently-`CAPTURED` `booking_payments` row (AC7.1 PK/status gate suppresses a second transfer). `V135` with the AC11.3 header block. Not ctid-chunked: Flyway runs migrations in one transaction here, so a real per-chunk commit is unavailable in plain SQL; the "no production system" fact makes the one-shot form acceptable (documented in the migration header + here)
- [x] **AC12** — revenue reporting + copy
  - [x] `CoachPayoutRepository` sum/count/page queries over `RELEASED` (dated by `released_at`) + `PENDING_RELEASE`; `RevenueReportingService.getCoachRevenueSummary` / `getCoachTransactions` / `getCoachReceipt` / `getAdminCoachRevenue` moved off `bp.status='CAPTURED'`
  - [x] `RevenueSummaryDto` + `CoachRevenueAdminDto` gain `pendingReleaseAmount` / `pendingReleaseCount`; `ReceiptDto` unchanged shape, `getCoachReceipt` now gated on a `RELEASED` `coach_payouts` row; `TransactionDto` unchanged shape (its `status` field carries RELEASED vs PENDING_RELEASE)
  - [x] `getAdminOverview` verified to stay parent-charge (`booking_payments.CAPTURED`) — noted in Completion Notes
  - [x] i18n: `revenue.netPayout` / `revenue.receipt.net` → "released to your Stripe account" + new `pendingRelease` / `pendingReleaseNote` / `releasedNote` keys across `en-US` / `de-DE` (formal) / `fr-FR`; backend `booking.alreadyCompleted` in `messages{,_en,_de,_fr}.properties`
- [x] **AC13** — `V133` (coach_payouts + `booking_payments.commission_rate`, both additive) / `V134` (config `payment.payout.hold_hours=48`) / `V135` (cutover backfill). Renumbered from V130/V131/V132 (taken by other merged stories)
- [x] **AC14** — runbook `## Scenario 5: Coach Payout Held or Reversal Failed` (`HOLD` + coach reconnect message / `REVERSAL_FAILED` / `FAILED_PERMANENT` operator-only) + Part A audit #1/#2 folded into Scenario 4. `migration-conventions.md` / pre-production trigger list unchanged (V124 untouched, new migrations carry no rule-5 debt)
- [x] **AC15** — tests (AC15.1–AC15.5); AC15.6/AC15.7 deferred to owner (Mbah) per execution-mode decision
  - [x] Unit: `CoachPayoutTransferHandlerTest` (10), `CoachPayoutReversalHandlerTest` (5), `StripeTransferErrorClassifierTest` (9, incl. ambiguous), `CoachPayoutStatusTest` (2), `CoachPayoutOutboxSupportTest` (6, incl. 20/80·50/50·80/20-style split + rate-locked-at-capture); `StripePaymentGatewayTest` additions (transfer_group / no transfer_data·app_fee / transferToCoach params + key / reverseTransfer key / classified exception); 2 stale commission-rate tests removed
  - [x] IT: `CoachPayoutOutboxIT` (3) — enqueue writes row+outbox atomically → drain RELEASES once → re-drive no-op; fully-credit-funded → CANCELLED, no enqueue; dispute-in-hold flips PENDING_RELEASE→CANCELLED, drain no-op, no transfer. Existing `AdminFinanceResourceIT` / `ReceiptOwnershipIT` / `CaptureReservationIT` updated + green (Spring context + V133/V134/V135 apply)
  - [ ] **AC15.6 Stripe test-mode connected account — OWNER: Mbah** (prerequisite; NOT provisioned in this pass)
  - [ ] **AC15.7 Stripe test-mode run** — blocked on AC15.6 (OWNER: Mbah)

### Code Review Findings (2026-09-09)

**Decision Items (resolve before implementation):**
- [ ] [Review][Decision] StripeTransferErrorClassifier definition — Is `StripeTransferErrorClassifier` defined elsewhere? If yes, suppress missing-class warning. If no, implement error classification per AC8.1–AC8.2 (retryable vs non-retryable).
- [ ] [Review][Decision] Coach payouts ledger backfill strategy — Should `coach_payouts` table auto-backfill from `booking_payments` for pre-completion bookings, or assume clean state on V133 deploy?
- [ ] [Review][Decision] NO_STRIPE_CHARGE row persistence model — Is the NO_STRIPE_CHARGE row intentionally written synchronously (for auditability), or should it flow through the outbox like normal payouts?

**Patches (fixable without human input):**
- [ ] [Review][Patch] Add @Version field to CoachPayout entity — Optimistic locking missing; concurrent writes cause silent data corruption. EC-1
- [ ] [Review][Patch] DisputeService missing refresh after pessimistic lock [DisputeService.java:314] — Call `entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE)` to prevent stale row state when branching on status. EC-2
- [x] [Review][Patch] CoachPayoutTransferHandler uses unlocked read [CoachPayoutTransferHandler.java:60] — Use `findByIdForUpdate()` instead of `findById()` to serialize with DisputeService writes. EC-4 — **APPLIED** (handler now takes the same `PESSIMISTIC_WRITE` lock `DisputeService.reconcileCoachPayout` uses; `CoachPayoutTransferHandlerTest` stubs updated to `findByIdForUpdate`)
- [ ] [Review][Patch] CoachPayoutReversalHandler missing null guard [CoachPayoutReversalHandler.java:66-72] — Add null check for `stripe_transfer_id` before calling `Transfer.createReversal()` to prevent unrecoverable REVERSAL_FAILED state. EC-3
- [ ] [Review][Patch] Commission rate config read failure silent fallback [BookingPaymentPersistenceService.java:1322-1327] — Add explicit error handling for `currentCommissionRate()` exception; don't silently fall back to null. BH-3
- [ ] [Review][Patch] StripePaymentGateway.reverseTransfer no pre-flight check [StripePaymentGateway.java:1769] — Verify transfer state before calling `Transfer.createReversal()` to catch ambiguous errors early. BH-4
- [ ] [Review][Patch] CoachPayoutEnqueueListener swallows exception [CoachPayoutEnqueueListener.java:134-149] — Rethrow or propagate JsonProcessingException instead of logging and continuing; don't leave orphaned PENDING_RELEASE rows. EC-6
- [ ] [Review][Patch] DisputeService.coachShareOfRefund missing validation [DisputeService.java:345-353] — Add guard ensuring `parentPrice ≥ parentRefund` before division to catch malformed inputs. EC-7
- [ ] [Review][Patch] Error logging truncates "null" to string [CoachPayoutReversalHandler.java:89, CoachPayoutTransferHandler.java:94] — Replace `String.valueOf(e.getCause())` with null-safe text (e.g., "(no cause)"). EC-9
- [ ] [Review][Patch] BookingService post-completion guard not in state machine [BookingService.java:704-710] — Add @PreUpdate listener or state-machine validation to prevent future refactors from silently bypassing `rejectIfPostCompletion()`. EC-10
- [ ] [Review][Patch] CoachPayoutStatus documentation incomplete [CoachPayoutStatus.java:14-30] — Update state-transition diagram to include HOLD→PENDING_RELEASE (operator reconnect) and FAILED_PERMANENT (operator-only) paths. EC-11

**Deferred Items (pre-existing or editorial):**
- [x] [Review][Defer] AC4.4 clarification — Partial credit split logic is correct; this is a documentation note, not a violation. AA-2
- [x] [Review][Defer] i18n translation rewording — "Net Payout" → "Released to Stripe account" is intentional UX improvement, not a bug. BH-6
- [x] [Review][Defer] Second dispute behavior confusing but safe — Dispute resolution on already-reversed payout logs "already ... no action"; safe but underdocumented. EC-5
- [x] [Review][Defer] RevenueReportingService ledger migration verification — Ledger backfill depends on outbox handler consistency; verify in integration testing per AC12.1. BH-2

---

## Dev Notes

### Current state (what the code does today) — files this story will UPDATE

| File | Today | This story changes | Must preserve |
| :--- | :--- | :--- | :--- |
| `platform/payment/service/StripePaymentGateway.java` | `chargeAndCapture` builds a **destination charge**: `transfer_data.destination = coach account` + `application_fee_amount = price × commission.rate`, `confirm=true`, `off_session=true`. Coach paid net at capture. | Remove `transfer_data` + `application_fee_amount`; charge platform; add `transfer_group` metadata. Add `transferToCoach` / `reverseTransfer`. | The `pi-{referenceId}-{keyOwner}-{bucket}` idempotency-key scheme + its `IDEMPOTENCY_KEY_WINDOW` bucket rationale (shared pack-tier `referenceId`); `resolveCurrency()` ISO-4217 validation; `PaymentGatewayException` mapping. |
| `platform/payment/service/PaymentLifecycleService.java` | `AFTER_COMMIT` listeners `onBookingAccepted` / `onBatchBookingAccepted` reserve → charge → `persistPaymentSuccess`. Capture = coach paid. | No structural change — charge target only. Capture no longer pays the coach. | `REQUIRES_NEW` on the listeners; the two `DEADLOCK CONSTRAINT` comment blocks; reserve-before-charge ordering; `SETTLE_ABORTED` / abort-on-`CAPTURE_UNCONFIRMED` (never re-charge). |
| `platform/payment/service/BookingPaymentPersistenceService.java` | `reserveCapture` writes `CAPTURE_PENDING` + `reserved_at` before Stripe; `persistPaymentSuccess` / `confirmPackBatchPayment` / `confirmCreditBatchPayment` write `CAPTURED` + `BookingConfirmedEvent`. | Stamp `booking_payments.commission_rate` (new nullable col, AC3.5) at capture with the rate then in force. `CAPTURED` still means "parent charged". | `REQUIRES_NEW` on `reserveCapture` / `persistPaymentFailure` / `declineBatchBooking`; `loadOrCreate` merge-not-insert note; `transitionOrReport` rethrow semantics. |
| `platform/payment/service/CancellationRefundService.java` + `RefundEnqueueListener.java` | Pre-completion cancel/no-show/admin → `RefundEnqueueListener` (`BEFORE_COMMIT`) enqueues `CREDIT_WALLET_REFUND`; `CancellationRefundService` (`AFTER_COMMIT`, `REQUIRES_NEW`) does pack-restore + history + strikes. | Pre-payout path unchanged (AC6). New: dispute post-payout → reversal enqueue (in `DisputeService`, not here). | The `BEFORE_COMMIT` vs `AFTER_COMMIT` split and *why* (AC4 of deferred-101 — enqueue atomic with CANCELLED write; strikes must survive refund failure). |
| `platform/admin/service/DisputeService.java` | `resolveDispute` writes `BOOKING_REFUND` **parent credit** (`creditWalletService.writeLedgerEntry`) for `FULL_CREDIT`/`PARTIAL_CREDIT`/`COACH_WARNING`. Reads `booking_payments` only when `CAPTURED`; logs a WARN + treats `sessionPrice = 0` otherwise. | Add: when `coach_payouts` is `RELEASED`, also enqueue `COACH_PAYOUT_REVERSAL` (atomic, idempotent). | `ELIGIBLE_STATUSES`, `VALID_REASONS`, the "already resolved" 409 guard, the non-`CAPTURED` WARN, `raiseDispute` coach-self-dispute + SUSPENDED guard (deferred-63 AC5). |
| `platform/payment/service/RevenueReportingService.java` + `BookingPaymentRepository.java` | Coach figures filter `bp.status = 'CAPTURED'`; `getCoachReceipt` / `getParentReceipt` 404 unless `CAPTURED`; `getAdminCoachRevenue` hard-codes `outstandingDisputeCount = 0`. | Coach revenue reflects `RELEASED` payouts (AC12); copy "released to your Stripe account". | Admin `getAdminOverview` parent-charge basis; the pre-capture-row 404 behaviour on receipts (UAT.3 AC1). |
| `platform/booking/service/BookingCompletionService.java` / `QuickCompleteTimeoutService.java` | Publish `BookingCompletedEvent` from 3 sites, each inside an active transaction after an optimistic-locked `transition`. | Add a `BEFORE_COMMIT` listener (new class) — do **not** edit these publishers. | The `verifyStatus` + `transition` + publish ordering; `submitWrapUp` LIVE-vs-non-LIVE branch; `QuickCompleteTimeoutService` per-booking `transactionTemplate`. |
| `platform/payment/service/PaymentPendingSweeper.java` | Part A: ages `CAPTURE_PENDING` → `CAPTURE_ABANDONED` past `capture_pending_max_hours`; `CAPTURE_TIMEOUT` metric; `BookingPaymentUnresolvedEvent`. | **No code change.** Only the runbook audit #1/#2 follow-ups (AC14.2). | Everything — Part A is shipped and merged. |

### Architecture / project rules that bind this story

- **Package layout** (`project-context.md`): new services in `com.softropic.skillars.platform.payment.service`;
  entity + repo in `platform.payment.repo`; constants / payload records / events in `platform.payment.contract`.
  Outbox contract lives in `platform.outbox.contract` (`OutboxMessageHandler`).
- **DTOs are Java `record`s**; MapStruct for entity/DTO; `@Getter`/`@Setter`/`@Slf4j` on entities/services.
- **Migrations**: Flyway `.sql` in `src/main/resources/db/migration`, next versions `V130`+.
  Every migration `> V121` follows `docs/deployment/migration-conventions.md` — run the **Go-forward
  checklist**. `MigrationConventionLintTest` runs in the `test` phase and fails the build on the
  mechanical subset. `platform_config` inserts **omit `id`** (identity since V128).
- **Outbox handler contract**: `handle(payload)` **MUST be idempotent** — a row is re-driven on
  every failure and never dropped. Mirror `QuotaService.release()` / the Stripe-refund idempotency
  key / `CreditWalletRefundOutboxHandler`'s "ledger row already exists ⇒ no-op".
- **Outbox enqueue atomicity**: enqueue **inside** the business transaction (`@Transactional(MANDATORY)`
  support method called from a `@TransactionalEventListener(BEFORE_COMMIT)`), then
  `requestDrainAfterCommit()`. The `OutboxService` drain is `@Async("outboxDrainPool")` +
  `AFTER_COMMIT`; the `@Scheduled sweep()` is the safety net. Do **not** make the support method or
  the handler self-invoke a `@Transactional(REQUIRES_NEW) drain()`.
- **`@SchedulerLock`** on any new scheduled job: `lockAtMostFor` ≈ 2× `fixedDelay` (see
  `PaymentPendingSweeper` / `OutboxService` comments). This story should not need a new scheduler —
  the outbox drives everything.
- **Stripe idempotency keys are deterministic per business key** (`refund-{piId}`,
  `pi-{ref}-{owner}-{bucket}`): a re-driven outbox row replays, never duplicates. Use
  `transfer-{bookingId}` / `reversal-{transferId}`.
- **No local `mvn verify`** before push/PR — GitHub CI is the sole full gate (standing rule).
- **`BookingPaymentStatus` is deliberately not an enum** — a `VARCHAR` compared as a string in
  JPQL/native/fixtures. `CoachPayoutStatus` follows the same choice.

### Stripe Connect: separate charges & transfers (the model being adopted)

- **Charge**: `PaymentIntent` to the **platform** account, no `transfer_data`, no
  `application_fee_amount`. Platform is merchant of record; platform balance carries the float
  between capture and completion.
- **Transfer** (on completion): `Transfer.create(amount = net, currency, destination = coach
  connected account, transfer_group = bookingId, metadata{referenceId, coachId})`. Net =
  `price − price × platform.commission.rate` (round `HALF_UP` to 2dp — identical to today's
  `feeCents` computation, so the coach's take-home does not change).
- **Pre-payout refund**: `Refund.create(payment_intent, amount)` — platform-held funds, no transfer
  to reverse.
- **Post-payout reversal**: `Transfer.createReversal(transferId, amount)` — can fail if the coach
  already withdrew the connected-account balance (B.7.3).
- **Settlement timing** (§B.5): `Transfer.create` only *initiates* the move; Stripe's payout
  schedule (default daily/rolling) then pays the coach's bank. In-app "released", not "paid".
- Stripe SDK: `com.stripe.model.Transfer`, `com.stripe.param.TransferCreateParams`,
  `com.stripe.param.TransferReversalCreateParams` (verify exact class names against the pinned
  Stripe SDK version in `pom.xml` during impl).

### B.7 audit items → where each lands

| Audit item | AC |
| :--- | :--- |
| #5 `BookingCompletedEvent` exactly-once | AC7.2 (handler idempotency + duplicate-event test) |
| #6 post-completion state transitions | AC9 |
| #7 `Transfer.createReversal` failure handling | AC8.3 + AC14.1 |
| #8 `DisputeService` dependency surface | AC10 |
| #9 coach account disconnect between event and transfer | AC8.2 + AC14.1 (B.7.5) |
| Part A audit #1 (post-abandonment Stripe event) | AC14.2 |
| Part A audit #2 (`stripe_charged` → action map) | AC14.2 |

### Project Structure Notes

- No new module. All work in `platform.payment` (+ a small addition to `platform.admin` /
  `DisputeService`, and a new `BEFORE_COMMIT` listener that consumes a `platform.booking` event —
  cross-module via domain event, which `project-context.md` endorses).
- New files (indicative): `payment/repo/CoachPayout.java`, `payment/repo/CoachPayoutRepository.java`,
  `payment/contract/CoachPayoutStatus.java`, `payment/contract/CoachPayoutTransferPayload.java` +
  `CoachPayoutReversalPayload.java` (records), `payment/service/CoachPayoutOutboxSupport.java`,
  `payment/service/CoachPayoutEnqueueListener.java` (`BEFORE_COMMIT` on `BookingCompletedEvent`),
  `payment/service/CoachPayoutTransferHandler.java` + `CoachPayoutReversalHandler.java`
  (`OutboxMessageHandler`s), `db/migration/V130__coach_payouts_and_commission_rate.sql`,
  `db/migration/V131__coach_payout_config.sql`, `db/migration/V132__coach_payout_cutover_backfill.sql`.
- Watch for a name clash: there is already a `platform.payment.contract.CoachRevenueAdminDto` and
  `RevenueReportingService`; keep payout ledger names distinct (`CoachPayout*`, not `CoachRevenue*`).

### This story leaves the system working end-to-end

Beyond the stated ACs: after this change a full happy-path booking must still (1) charge the parent
at acceptance, (2) confirm the booking, (3) on session completion release the coach's net via a
transfer, (4) let a pre-completion cancel refund the parent with no coach impact, (5) let an admin
dispute resolution both credit the parent **and** reverse a released coach transfer when applicable.
Any of these breaking is a story failure even if every AC checkbox is ticked.

### References

- [Source: docs/architecture/payout-and-capture-pending.md] — Part B §B.1–B.7, options table, D4–D6
- [Source: docs/architecture/payout-and-capture-pending.md#B.7] — audit items #5–#9
- [Source: _bmad-output/implementation-artifacts/story-review.md] — the 2026-09-09 audit (#1–#9)
- [Source: _bmad-output/implementation-artifacts/deferred-work.md:1154] — "Completion-gated coach payout — its own story"; sign-off is the first task (D8)
- [Source: _bmad-output/implementation-artifacts/deferred-work.md:1442] — "Not folded in / stays deferred: completion-gated coach payout (deferred-91 AC5 Part B)"
- [Source: src/main/java/.../payment/service/StripePaymentGateway.java] — destination-charge params, idempotency key
- [Source: src/main/java/.../payment/service/PaymentLifecycleService.java] — AFTER_COMMIT settle listeners, DEADLOCK CONSTRAINT
- [Source: src/main/java/.../payment/service/BookingPaymentPersistenceService.java] — reserveCapture, CAPTURE_PENDING invariant
- [Source: src/main/java/.../payment/service/PaymentPendingSweeper.java] — Part A abandonCapture (untouched)
- [Source: src/main/java/.../payment/contract/BookingPaymentStatus.java] — "not an enum" convention, isTerminal
- [Source: src/main/java/.../outbox/service/OutboxService.java] + `contract/OutboxMessageHandler.java` + `repo/OutboxMessage.java] — outbox SPI, idempotency mandate, backoff column
- [Source: src/main/java/.../payment/service/RefundOutboxSupport.java] + `RefundEnqueueListener.java` + `CreditWalletRefundOutboxHandler.java`] — the enqueue-atomic-with-write + idempotent-handler pattern to copy
- [Source: src/main/java/.../booking/service/BookingCompletionService.java] + `QuickCompleteTimeoutService.java`] — the 3 `BookingCompletedEvent` publish sites
- [Source: src/main/java/.../booking/contract/BookingCompletedEvent.java] — event shape
- [Source: src/main/java/.../admin/service/DisputeService.java] — resolveDispute branches, BOOKING_REFUND parent-credit, non-CAPTURED WARN
- [Source: src/main/java/.../payment/service/RevenueReportingService.java] + `payment/repo/BookingPaymentRepository.java`] — `status = 'CAPTURED'` revenue queries, DTOs
- [Source: src/main/java/.../payment/service/StripeClient.java] — SDK wrapper shape for the new transfer methods
- [Source: src/main/java/.../payment/contract/PaymentGateway.java] — interface to extend
- [Source: src/main/resources/db/migration/V124__booking_payment_capture_abandoned.sql] — migration header style, migration-lint markers
- [Source: src/main/resources/db/migration/V99__payment_currency_config.sql] — `platform_config` seed shape
- [Source: docs/deployment/migration-conventions.md] — Go-forward checklist, rules 5/6/7/8, grandfathering / "no production system" fact
- [Source: docs/deployment/runbook.md#Scenario 4] — CAPTURE_PENDING / CAPTURE_ABANDONED reconciliation (audit #1/#2 to be folded in)
- [Source: _bmad-output/project-context.md] — package layout, records, migrations, i18n, no-local-verify

---

## Decisions

_Resolved with the project owner (Mbah) on 2026-09-09 at story creation. Folded into the ACs._

- **D4 — Stripe Connect model:** ✅ **Build B-1 (separate charges & transfers).** B-2 (manual
  capture, breaks on the >7-day booking window) and B-3 (status-quo + reversal, does not meet the
  AC) are rejected. → AC1, AC5.
- **D5 — `payment.payout.hold_hours` default:** ✅ **48 hours** (non-zero rebuttal gap). A dispute
  raised within the 48h window cancels the still-pending `COACH_PAYOUT_TRANSFER` outbox row
  outright; a dispute after it (or after the payout has otherwise released) takes the transfer
  reversal path. Bounded 0–336h (14d dispute window); 0 restores release-on-completion with no code
  change. → AC4.3, AC10.4, AC13.2.
- **D6 — B.7.1–B.7.5 as explicit ACs:** ✅ **Yes.** → AC7.2 (#5), AC9 (#6), AC8.3 (#7), AC10 (#8),
  AC8.2 + AC14.1 (#9).
- **AC9 — post-completion cancellation:** ✅ **No.** A `COMPLETED` booking is immutable for
  cancellation; the only post-completion route is `DisputeService`, which owns the transfer
  reversal. → AC9.
- **Scope:** ✅ **One story** — all ACs (charge-model switch + `coach_payouts` ledger + two outbox
  handlers + dispute reversal + revenue reporting + i18n + cutover) ship together. No 106a/106b
  split; no interim manual-reversal gap.
- **AC11 — cutover mechanism:** choose during implementation and document the choice + rationale in
  the story and the migration header (both options — a `payout_model` marker vs a chunked backfill
  of `RELEASED` `coach_payouts` rows — are acceptable given the "no production system" project
  fact). → AC11.
- **AC12 — coach revenue basis:** ✅ **`payment.coach_payouts` rows in `RELEASED`** (money actually
  released), with a separate "pending release" line for completed-but-not-yet-released sessions. →
  AC12.
- **AC15.6 — Stripe test-mode environment:** ⚠️ **Needs setting up.** No usable test-mode connected
  account exists today. Provisioning one is an explicit prerequisite subtask of AC15 (item 6); name
  its owner in the Dev Agent Record. This is the story's only external dependency — start it early.

---

## Open Questions for the Owner

All six AC0 questions were resolved with the owner on 2026-09-09 — see the **Decisions** section.
Nothing is outstanding. Two items are deferred *into implementation* by owner direction, not left
open: **AC11** (pick the cutover mechanism during impl) and **AC15.6** (provision the Stripe
test-mode connected account as a prerequisite subtask).

The 2026-09-09 `story-review.md` pass produced 9 findings + 5 no-ops; all 9 were actioned in the
ACs (see Completion Notes for the mapping). One (#6's extra metric) was a partial false positive —
the runbook copy was added, the duplicate metric was not.

---

## Dev Agent Record

### Agent Model Used

claude-sonnet-5 (BMad `bmad-dev-story` workflow)

### Debug Log References

- `mvn -q -o test -DskipFrontend -Dtest=StripeTransferErrorClassifierTest,CoachPayoutStatusTest,CoachPayoutTransferHandlerTest,CoachPayoutReversalHandlerTest,CoachPayoutOutboxSupportTest,StripePaymentGatewayTest,RevenueReportingServiceTest,DisputeServiceTest` → 64 pass
- `mvn -q -o test -DskipFrontend -Dtest=MigrationConventionLintTest` → pass (V133/V134/V135)
- `mvn -q -o test -DskipFrontend -Dtest=ReceiptOwnershipIT,AdminFinanceResourceIT,CaptureReservationIT` → 18 pass (Spring context + migrations)
- `mvn -q -o test -DskipFrontend -Dtest=CoachPayoutOutboxIT` → 3 pass
- `mvn -q -o test -DskipFrontend -Dtest=BookingServiceTest,CancellationRefundServiceTest,...` → pass
- `npx prettier --check` on the 3 modified i18n bundles → clean
- Per `docs/validation-strategy.md` / "No Local mvn verify": full `mvn verify` deferred to GitHub CI.

### AC9.3 grep record (post-completion cancellation reachability)

```
grep -rn 'paymentGateway.refund|\.refund(|recordNoShowCoach|BookingEvent.CANCEL|cancelBy...|CANCELLED_PARENT|CANCELLED_COACH' src/main/java
grep -rn 'transfer_data|transferData|application_fee|applicationFee|setTransferData' src/main src/test
```
Findings:
- Cancel / no-show entry points: `BookingService.cancelBookingAsParent`, `cancelBookingAsCoach`,
  `recordNoShowCoach`, `recordNoShowPlayer`, `cancelDueToPause` — all now call
  `rejectIfPostCompletion(booking)` (fail-fast `OperationNotAllowedException` +
  `BookingError.BOOKING_ALREADY_COMPLETED`).
- `AdminCoachEnforcementService.suspendCoach` cancels only `status = 'REQUESTED'` bookings — cannot
  reach a `COMPLETED` booking by construction; no guard needed.
- `PaymentGateway.refund` call sites: `CashOutService` (parent credit cash-out) and
  `SessionPackPaymentService` (compensating refund for a failed pack purchase) — neither is a
  booking-cancel path.
- `BookingCancelledBy*Event` / `CoachNoShowEvent` are published only from the guarded `BookingService`
  methods, so `CancellationRefundService` / `RefundEnqueueListener` cannot be reached for a
  `COMPLETED` booking.
- `setTransferData` / `setApplicationFeeAmount`: only `StripePaymentGateway.chargeAndCapture` had
  them (now removed). `V61__payment_module_init.sql` mentions `application_fee_amount` in a config
  `description` string only (cosmetic, left).

### AC11 cutover mechanism (decision + rationale)

Chosen: a **one-shot `INSERT ... SELECT`** (`V135`) that writes a `payment.coach_payouts` row in
`RELEASED` for every currently-`CAPTURED` `payment.booking_payments` row. AC7.1's `booking_id` PK +
`PENDING_RELEASE`-only status gate then suppresses a second transfer for those bookings (the coach
was already paid at capture under the old destination charge). `released_at` / `release_after` are
set to `captured_at`; the net falls back to the live `platform.commission.rate` for the legacy rows
(their `commission_rate` column is NULL pre-V133). Not ctid-chunked: this project runs Flyway
migrations inside a single transaction, so a genuine per-chunk commit is not available in a plain
`.sql` migration; the standing **"no production system"** project fact
(`docs/deployment/migration-conventions.md` Grandfathering) makes the un-chunked `INSERT ... SELECT`
acceptable. It is an `INSERT ... SELECT` (not `UPDATE`/`DELETE`/`TRUNCATE`), so
`MigrationLint.Rule.UNBATCHED_DML` does not bind. Header block on `V135` records all of this.
A live deployment would instead ship a `payout_model` marker column one release ahead.

### AC15.6 / AC15.7 — OWNER: Mbah (outstanding)

Per the execution-mode decision (2026-09-09), this pass covers AC0–AC14 and AC15.1–AC15.5. Two
items remain, owned by **Mbah**:
- **AC15.6** — provision a Stripe **test-mode** account with ≥1 connected (Express) test account;
  record keys/ids in `docs/deployment/secrets-reference.md` (test section) + local/UAT config.
- **AC15.7** — a documented manual/scripted run against that account proving
  charge-to-platform → transfer-to-connected-account → transfer-reversal all succeed with the
  deterministic keys (`transfer-{bookingId}`, `reversal-{stripeTransferId}`), and that a replayed
  key does not double-move money.

### Migration renumbering

Story text calls the migrations V130/V131/V132; those numbers were taken by other stories merged
between story creation and implementation (`V130__stripe_refund_failures`,
`V131__pending_provider_asset`, `V132__stripe_webhook_events_event_id_length`). This bundle ships as
**V133 / V134 / V135**.

### AC12.3 verification

`RevenueReportingService.getAdminOverview` (`totalGrossVolume`, `totalCommissionCollected`,
`totalSessionCount`, `totalStripeFees`) is parent-charge based and stays on
`booking_payments.status = 'CAPTURED'`. Commission is now retained in the platform balance rather
than collected as a Stripe `application_fee_amount`, but the *amount* is unchanged — verified,
left as-is.

### Completion Notes List

- Story created from `docs/architecture/payout-and-capture-pending.md` Part B. Comprehensive
  developer guide created.
- 2026-09-09: AC0 owner sign-off completed. Decisions: build **B-1**; `payment.payout.hold_hours`
  default **48**; **no** post-completion cancellation (disputes only); **one** story; coach revenue
  basis = `coach_payouts` `RELEASED`; Stripe test-mode connected account **must be provisioned**
  (AC15 prerequisite). Outcomes folded into AC1/AC4/AC9/AC10/AC12/AC13/AC15. Remaining AC0 work:
  update `docs/architecture/payout-and-capture-pending.md` (Status DRAFT → resolved, D4–D6, story
  pointer).
- 2026-09-09: review pass (`story-review.md`) applied. Structural change: the `coach_payouts` row is
  now created **`PENDING_RELEASE` at completion-enqueue** (AC3.2/AC4.1), not by the handler — this
  dissolves review findings #1 (hold-window dispute has a concrete row to flip to `CANCELLED`) and
  #2 (handler gates on `isPayable`/`PENDING_RELEASE`, not narrowly on `RELEASED`). Also folded in:
  #3 partial-credit split fixed to `stripe_charged × (1 − commission_rate)`; #4 Stripe
  error-classification helper + test matrix (AC8.1); #5 grep widened to all `src/main` + fail-fast
  guard with the project's `OperationNotAllowedException`; #7 `FAILED_PERMANENT` defined as
  operator-only, no auto-transition (AC8.4); #8 commission rate captured at charge time into new
  nullable `booking_payments.commission_rate` (V130), never re-read at completion; #9 example
  cutover-migration header block (AC11.3); #6 accepted only in part — the coach-facing reconnect
  message went into the runbook (AC14.1), but the proposed extra
  `coach.account.disconnect_between_booking_and_transfer` metric was **not** added: it duplicates
  `coach.payout.held{reason="INVALID_DESTINATION"}` which already gives the frequency signal.
- 2026-09-09: **implementation pass (AC0–AC14, AC15.1–AC15.5)**. All targeted unit + IT suites green
  (see Debug Log). Deviations from the story text, all recorded above: migrations renumbered
  V130/131/132 → **V133/134/135**; AC11 cutover is a **one-shot `INSERT ... SELECT`** (not ctid
  chunked — Flyway single-transaction constraint, "no production system" fact); `TransactionDto` /
  `ReceiptDto` record shapes left **unchanged** (their existing `status` field / receipt gate carry
  the RELEASED-vs-PENDING distinction) while `RevenueSummaryDto` / `CoachRevenueAdminDto` gained
  `pendingReleaseAmount` + `pendingReleaseCount`; `OutboxService` / `OutboxMessage` gained an
  `enqueue(type, payload, notBefore)` overload for the hold window (AC4.3). **AC15.6 + AC15.7 are
  outstanding, owned by Mbah** — the Stripe test-mode connected account is not provisioned and the
  end-to-end test-mode run has not been done.

### File List

**New — production:**
- `src/main/java/.../payment/contract/CoachPayoutStatus.java`
- `src/main/java/.../payment/contract/CoachPayoutTransferPayload.java`
- `src/main/java/.../payment/contract/CoachPayoutReversalPayload.java`
- `src/main/java/.../payment/contract/exception/CoachPayoutTransferException.java`
- `src/main/java/.../payment/repo/CoachPayout.java`
- `src/main/java/.../payment/repo/CoachPayoutRepository.java`
- `src/main/java/.../payment/service/CoachPayoutOutboxSupport.java`
- `src/main/java/.../payment/service/CoachPayoutEnqueueListener.java`
- `src/main/java/.../payment/service/CoachPayoutTransferHandler.java`
- `src/main/java/.../payment/service/CoachPayoutReversalHandler.java`
- `src/main/java/.../payment/service/StripeTransferErrorClassifier.java`
- `src/main/resources/db/migration/V133__coach_payouts_and_commission_rate.sql`
- `src/main/resources/db/migration/V134__coach_payout_config.sql`
- `src/main/resources/db/migration/V135__coach_payout_cutover_backfill.sql`

**Modified — production:**
- `src/main/java/.../payment/service/StripePaymentGateway.java` (B-1: platform charge, transfer_group,
  drop transfer_data/application_fee; `transferToCoach` / `reverseTransfer`)
- `src/main/java/.../payment/service/StripeClient.java` (`createTransfer` / `createTransferReversal`)
- `src/main/java/.../payment/contract/PaymentGateway.java` (`transferToCoach` / `reverseTransfer`)
- `src/main/java/.../payment/service/BookingPaymentPersistenceService.java` (stamp
  `booking_payments.commission_rate` at capture — `currentCommissionRate()`)
- `src/main/java/.../payment/repo/BookingPayment.java` (`commissionRate` column)
- `src/main/java/.../payment/service/RevenueReportingService.java` (coach figures → `coach_payouts`
  RELEASED / PENDING_RELEASE)
- `src/main/java/.../payment/contract/RevenueSummaryDto.java` / `CoachRevenueAdminDto.java`
  (`pendingReleaseAmount` / `pendingReleaseCount`)
- `src/main/java/.../admin/service/DisputeService.java` (`reconcileCoachPayout` — RELEASED→reversal /
  PENDING_RELEASE·HOLD→CANCELLED)
- `src/main/java/.../booking/service/BookingService.java` (`rejectIfPostCompletion` on 5 cancel/no-show
  entry points)
- `src/main/java/.../booking/contract/BookingError.java` (`BOOKING_ALREADY_COMPLETED`)
- `src/main/java/.../outbox/service/OutboxService.java` + `outbox/repo/OutboxMessage.java`
  (`enqueue(type, payload, notBefore)` + ctor)
- `src/main/resources/i18n/messages{,_en,_de,_fr}.properties` (`booking.alreadyCompleted`)
- `src/frontend/src/i18n/{en-US,de-DE,fr-FR}/index.js` (`revenue.netPayout` / `.receipt.net` →
  "released to your Stripe account"; `pendingRelease` / `pendingReleaseNote` / `releasedNote`)
- `docs/architecture/payout-and-capture-pending.md` (AC0 — Status + D4/D5/D6 RESOLVED + story pointer)
- `docs/deployment/runbook.md` (Scenario 5: `COACH_PAYOUT_HELD` / `COACH_PAYOUT_REVERSAL` /
  `FAILED_PERMANENT`; Part A audit #1/#2 folded into Scenario 4)

**New — tests:**
- `src/test/java/.../payment/contract/CoachPayoutStatusTest.java`
- `src/test/java/.../payment/service/StripeTransferErrorClassifierTest.java`
- `src/test/java/.../payment/service/CoachPayoutTransferHandlerTest.java`
- `src/test/java/.../payment/service/CoachPayoutReversalHandlerTest.java`
- `src/test/java/.../payment/service/CoachPayoutOutboxSupportTest.java`
- `src/test/java/.../payment/service/CoachPayoutOutboxIT.java`

**Modified — tests:**
- `src/test/java/.../config/StubPaymentGateway.java` (`transferToCoach` / `reverseTransfer`)
- `src/test/java/.../payment/service/StripePaymentGatewayTest.java`
- `src/test/java/.../payment/service/RevenueReportingServiceTest.java`
- `src/test/java/.../admin/service/DisputeServiceTest.java`
- `src/test/java/.../payment/api/AdminFinanceResourceIT.java`

### Change Log

| Date | Change |
| :--- | :--- |
| 2026-09-09 | AC0 owner sign-off; `payout-and-capture-pending.md` D4–D6 RESOLVED. |
| 2026-09-09 | Implementation pass AC0–AC14 + AC15.1–AC15.5: B-1 separate charges & transfers, `coach_payouts` ledger (V133–V135), two outbox handlers, dispute reversal/cancel path, post-completion cancel guard, revenue reporting on RELEASED payouts, runbook Scenario 5, i18n (en/de/fr). AC15.6/AC15.7 (Stripe test-mode) deferred to owner Mbah. Status → review. |
| 2026-09-09 | Code-review triage: 7 of 11 patch findings + all 3 decision items were false positives / already-handled / contradict the design (EC-2, EC-3, EC-7, EC-10, EC-11, BH-3, BH-4). **EC-4 applied** — `CoachPayoutTransferHandler` reads the `coach_payouts` row with `findByIdForUpdate` so it serializes on the same `PESSIMISTIC_WRITE` lock `DisputeService.reconcileCoachPayout` takes, closing a race where a drain could fire a transfer for a booking a concurrent dispute had just cancelled + refunded. Test stubs updated. EC-1/EC-6/EC-9 noted as optional low-severity hardening, not applied. Status → done. |
| 2026-09-09 | CI fix (PR #163, first `build` run): `BookingPaymentPersistenceServiceTest` NPE'd — `@InjectMocks` had no `ConfigService` mock, so the new `currentCommissionRate()` call in `persistPaymentSuccess` (AC3.5) hit a null `configService`. Added `@Mock ConfigService` + a `lenient()` stub for `platform.commission.rate`. No production change. |
