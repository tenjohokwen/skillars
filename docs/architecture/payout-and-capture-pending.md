# Completion-gated coach payout & the `CAPTURE_PENDING` dead-end — design pass

**Status:** Part A **IMPLEMENTED & merged** (skillars-deferred-91 AC5, commit `c2c47c1e`) —
`CAPTURE_ABANDONED` status, `reserved_at` clock (V124), `PaymentPendingSweeper.abandonCapture`,
`CAPTURE_TIMEOUT` metric, and runbook Scenario 4 all shipped. Owner decisions **D1–D6 are all
resolved** (outcomes recorded below). Part B (B-1 separate charges & transfers) is now an
**implementation story: `skillars-deferred-106-completion-gated-coach-payout`** — all B.7 open
questions were folded into its ACs (AC7.2/AC9/AC8.3/AC10/AC8.2+AC14.1) and resolved with the owner
on 2026-09-09.
The 2026-09-09 story audit (`_bmad-output/implementation-artifacts/story-review.md`) is folded in:
its Part A points became the A.2 clarifications (items 3–7, tagged "audit #N"); its Part B
points became section B.7.

**Context:** skillars-deferred-91 AC5 folds together two long-standing residuals:
`skillars-uat-3` D3 / skillars-deferred-90 line 1325 (an unrecoverable `CAPTURE_PENDING`
row holds a coach's slot forever) and `skillars-deferred-63` story-creation
(coach payout settles at booking confirmation, before the session has happened).
The project owner asked for a design sub-doc first because Part B is a Stripe Connect
money-model change, not a local refactor.

---

## 0. Current state (what the code does today)

### 0.1 How a coach gets paid

`StripePaymentGateway.chargeAndCapture(...)` builds a **destination charge**:

```
PaymentIntent {
  amount            = session price
  transfer_data.destination = coach's connected account
  application_fee_amount     = price × platform.commission.rate
  confirm = true, off_session = true      // captured immediately
}
```

With a destination charge Stripe **moves the net amount to the coach's connected
account at capture time**. Capture happens inside `PaymentLifecycleService`'s
`AFTER_COMMIT` listeners right after `BookingService.acceptAndInitiatePayment`
commits the booking — i.e. **at booking confirmation, days before the session**.
There is no platform-controlled "payout" step to gate; the transfer is a property
of the PaymentIntent.

Credit-wallet–funded bookings are the exception: they debit
`parent_credit_ledger` and never touch Stripe, so the coach is *not* paid through
Stripe for those at all (coach-earnings accounting for credit bookings is out of
scope here and unchanged).

### 0.2 The `CAPTURE_PENDING` dead-end

`BookingPaymentPersistenceService.reserveCapture` writes a `CAPTURE_PENDING`
`booking_payments` row in its own transaction **before** either Stripe charge call,
so "no row ⇒ no Stripe call was ever attempted" is a provable invariant
(`skillars-uat-3` AC1). If the process dies between `reserveCapture` committing and
the charge completing, the row is left `CAPTURE_PENDING` and:

- `booking_payments.status` stays non-terminal (`BookingPaymentStatus.isTerminal`
  excludes `CAPTURE_PENDING` by design);
- the booking stays `PAYMENT_PENDING`, which is in `ACTIVE_SLOT_STATUSES` and in
  V87's `excl_bkg_coach_slot_overlap` exclusion constraint — **so it holds the
  coach's slot against every other booking**;
- `BookingService.recordNoShowCoach` / the cancel paths refuse to act while a
  `CAPTURE_PENDING` row exists (`BookingService.java:736-744`) — **so the parent
  cannot cancel either**;
- `PaymentPendingSweeper.sweepOne` finds the row, calls
  `reportUnrecoverable(booking, "CAPTURE_UNCONFIRMED")` (an ERROR log + the
  `booking.payment_pending.unrecoverable` counter) and **returns without changing
  anything**. The slot is held indefinitely; only a manual Stripe reconciliation
  clears it.

This is deliberate — an operator must read the Stripe side, because money may
already have moved with nothing recording it. But the *slot-hold* harm is
unbounded, and that is what Part A fixes.

---

## Part A — `CAPTURE_PENDING` automated exit (implementable now)

### A.1 Goal

Bound the **slot-hold** harm without resolving the payment. After a configurable
timeout a stuck `CAPTURE_PENDING` row transitions to a terminal state that frees
the slot and unblocks the parent's cancel, while still raising the same "an
operator must reconcile the Stripe side" alert.

### A.2 Design

1. **New config key** `booking.payment_pending.capture_pending_max_hours`
   (`getBoundedLong`, default **72**, min **6**, max **720**). Distinct from
   `booking.payment_pending_sweep_grace_minutes` (which gates the *no-row* decline
   path): a `CAPTURE_PENDING` row means money *may* have moved, so it gets a much
   longer, separately tunable grace.

2. **New terminal `BookingPaymentStatus`: `CAPTURE_ABANDONED`.** Not `CHARGE_FAILED`
   — `CHARGE_FAILED` asserts "no money moved" (it is what the no-row decline path
   writes, with `stripe_charged = 0`). `CAPTURE_ABANDONED` asserts "we stopped
   waiting; the Stripe side is unknown and an operator owns it". It is terminal
   (`isTerminal` returns true for it) so the booking can leave `PAYMENT_PENDING`.
   - Migration: **V124** widens the `chk_bp_status` CHECK to add
     `CAPTURE_ABANDONED`. Per `docs/deployment/migration-conventions.md` rule 5
     this must land **one release before** any code writes the value — so V124 is
     additive-only (widen the CHECK), and the code that writes `CAPTURE_ABANDONED`
     ships in the **next** story, OR V124 + the writing code ship together and the
     migration is flagged `-- migration-lint: allow-... enum-widen-same-release`
     with the reason "no production data; single-node deploy".
     - **Resolved (D2): same-release with the documented opt-out.** V124 widens the
       CHECK and `PaymentPendingSweeper.abandonCapture` writes the value in the same
       release, flagged `migration-lint: allow-enum-widen-same-release` under the
       standing "no production system exists" project fact. V124's own comment
       records that this must be split into widen-then-write across two releases
       before the first production deploy.

3. **`PaymentPendingSweeper.sweepOne` gains a `CAPTURE_PENDING` age branch** (it
   already re-reads the row under the booking-row lock):

   > **Resolved (audit #3): the clock is a new nullable `booking_payments.reserved_at`
   > column, not `created_at`.** V124 adds `reserved_at TIMESTAMPTZ` (nullable, no
   > default — catalog-only `ADD COLUMN`); `BookingPaymentPersistenceService.reserveCapture`
   > stamps it when it writes the `CAPTURE_PENDING` row, i.e. the timeout is measured
   > from when capture was *reserved*, immediately before the Stripe call — never from
   > booking creation. A row with `reserved_at IS NULL` (created before V124) **cannot
   > be aged** and stays on the existing `CAPTURE_UNCONFIRMED` manual path indefinitely.
   > (The audit's "measured from booking time, timeout fires too early" scenario does
   > not apply: `existing` is the `booking_payments` row, whose `created_at`/`reserved_at`
   > is the reservation instant, not the booking's creation time. `reserved_at` is
   > still preferred over the row's `created_at` because `reserveCapture` is the only
   > writer of a fresh `CAPTURE_PENDING` row and stamping an explicit field keeps the
   > semantics unambiguous.)

   - if `existing.status == CAPTURE_PENDING` **and**
     `existing.reserved_at` is non-null and older than
     `capture_pending_max_hours`:
     - write `existing.status = CAPTURE_ABANDONED` (keep `stripe_charged` as-is —
       do **not** zero it, the amount may be real);
     - `bookingService.transition(bookingId, PAYMENT_FAILED, SYSTEM)` — the same
       transition the no-row path uses, so the booking leaves `PAYMENT_PENDING`,
       the slot frees, and the parent's cancel is unblocked;
     - `meterRegistry.counter("booking.payment_pending.unrecoverable", "reason",
       "CAPTURE_TIMEOUT").increment()`;
     - `log.error("[CAPTURE_TIMEOUT] booking {} — CAPTURE_PENDING past "
       + "capture_pending_max_hours, marked CAPTURE_ABANDONED and slot released; "
       + "reconcile the Stripe side by hand (runbook: CAPTURE_ABANDONED)", ...)`;
     - publish `BookingPaymentUnresolvedEvent` to the parent — **not**
       `BookingDeclinedEvent` (code-review D10: the declined template tells the
       parent their session credits "have not been affected", the one claim
       `CAPTURE_ABANDONED` exists to avoid making).
   - if it is younger than the timeout (or `reserved_at IS NULL`): unchanged —
     `reportUnrecoverable(..., "CAPTURE_UNCONFIRMED")` as today (so the existing
     alert still fires every sweep until the timeout, then flips to
     `CAPTURE_TIMEOUT`).
   - **Concurrent / repeat sweeps are a no-op (audit #4).** Once `abandonCapture`
     has run the booking is no longer `PAYMENT_PENDING`, so `findPaymentPendingOlderThan`
     stops returning it and `sweepOne`'s own `status == PAYMENT_PENDING` re-check
     (under the booking-row lock) bails out first anyway. Even a sweep that raced in
     before that transition committed re-reads the payment row under the same lock
     and gates on `status == CAPTURE_PENDING`. No extra idempotency key is needed.
     (The `@SchedulerLock lockAtMostFor` is 2× the fixed delay for the same
     reason — see the sweeper class comment.)

4. **No automatic charge, confirm, or refund.** Part A only changes local booking
   state. `PaymentIntent` reconciliation stays manual, per `skillars-uat-3`'s own
   reasoning.

5. **`BookingService.java:736-744` guard** — once the row is `CAPTURE_ABANDONED`
   (terminal), the existing `!isTerminal` check already lets `recordNoShowCoach` /
   cancel through. Confirm no code path treats `CAPTURE_ABANDONED` as "still in
   flight" (grep every `CAPTURE_PENDING.equals(...)` / `isTerminal` caller).

6. **Runbook** — **shipped** as `docs/deployment/runbook.md` Scenario 4 ("A Booking
   Stuck in `CAPTURE_PENDING`"): how to find the PaymentIntent by
   `metadata.referenceId` / `metadata.coachId`, the detection query over
   `('CAPTURE_PENDING', 'CAPTURE_ABANDONED')` rows, and the re-open / tidy /
   refund branches for a captured vs. never-captured charge. It also states that
   `credit_debited` / `stripe_charged` on a stuck row are **a reconciliation hint,
   not a ledger** — a card booking carries `credit_debited = 0` and the whole
   price under `stripe_charged`; the real split is only written by the CAPTURE
   step that never ran.

7. **Two runbook additions from the 2026-09-09 audit** (fold into Scenario 4):
   - **Post-abandonment Stripe event (audit #1).** After a row is
     `CAPTURE_ABANDONED` the booking is `PAYMENT_FAILED`/terminal and nothing in
     the app consumes a later `payment_intent.succeeded` for it — there is no
     automatic tie-back. The runbook's reconciliation step must therefore
     *always* query Stripe (or the webhook audit log) for events on the
     PaymentIntent, not trust the local terminal state, and a "successful charge
     on a `CAPTURE_ABANDONED` booking" dashboard/alert is worth adding.
   - **`stripe_charged` → action map (audit #2).** Make the branch explicit:
     `stripe_charged = 0` → check the PaymentIntent for a failure reason; if it
     never captured there is nothing to reverse. `stripe_charged > 0` → the
     PaymentIntent value is only a hint; confirm capture state in Stripe, and if
     captured, query Stripe Transfers by `metadata.referenceId` to see whether the
     coach transfer also went through before deciding refund vs. transfer-reversal.

### A.3 Test plan (Part A)

- Unit (`PaymentPendingSweeperTest`): a `CAPTURE_PENDING` row older than the
  timeout → `CAPTURE_ABANDONED` + `PAYMENT_FAILED` transition + `CAPTURE_TIMEOUT`
  counter; a young one → unchanged, `CAPTURE_UNCONFIRMED` still reported.
- IT (extend an existing payment IT context, no new `@TestPropertySource`): seed a
  `CAPTURE_PENDING` row with `reserved_at` well in the past, run
  `sweepStrandedPayments()` (release the ShedLock first), assert the booking left
  `PAYMENT_PENDING`, the slot is free (a second booking for the same slot now
  inserts), and the parent cancel endpoint returns 2xx.
- A `reserved_at IS NULL` (pre-V124) row is never aged — sweeper leaves it
  `CAPTURE_PENDING` on the manual path.

_Delivered in `PaymentPendingSweeperTest` (incl.
`capturePendingRow_withNullReservedAt_isNeverAged`) / `PaymentPendingSweeperIT`._

### A.4 ACs that fell out of Part A — **all delivered** (skillars-deferred-91 AC5, `c2c47c1e`)

- **AC5a-1** ✅ new `CAPTURE_ABANDONED` status + V124 (`reserved_at` column + CHECK widen).
- **AC5a-2** ✅ `PaymentPendingSweeper.abandonCapture` timeout branch +
  `booking.payment_pending.unrecoverable{reason="CAPTURE_TIMEOUT"}` metric + `[CAPTURE_TIMEOUT]`
  ERROR + `BookingPaymentUnresolvedEvent` to the parent (code-review D10: **not**
  `BookingDeclinedEvent` — that would wrongly tell the parent their credits were unaffected).
- **AC5a-3** ✅ runbook Scenario 4 (see items 6–7 above for the audit follow-ups still to fold in).

---

## Part B — completion-gated coach payout (design only; owner decision required)

### B.1 The problem

Today the coach is paid (net of commission) **at capture, which is at booking
confirmation**. If the session is later cancelled, no-showed by the coach, or
disputed, the platform has already transferred the money and must **claw it back**
— a Stripe `Transfer` reversal against a connected account that may no longer hold
the balance. `skillars-deferred-63` asked for payout to be **gated on a completion
signal** instead.

### B.2 What "completion signal" means here

The booking lifecycle is intended to produce **one** `BookingCompletedEvent` per
booking (see B.7.1 for the exactly-once caveat the Part B story must nail down):

- **`BookingCompletedEvent`** — published by `QuickCompleteTimeoutService`
  (auto-confirm after `booking.quick_complete_timeout_hours`) and by the
  coach/parent explicit-complete paths (`BookingCompletionService.submitWrapUp`
  LIVE path and `confirmCompletion`). This is the natural payout trigger.
- Parent confirmation is *within* the quick-complete window and also ends in
  `BookingCompletedEvent`, so gating on the event covers both.
- `recordNoShowCoach` (`BookingService.java:739-758`) is `UPCOMING`-only by a
  prior owner decision and never reaches completion — so a coach no-show simply
  **never triggers payout**, which is the desired behaviour.

So: **release payout on `BookingCompletedEvent`, through a durable step** (the
skillars-deferred-91 AC1 outbox is the obvious vehicle — a lost payout is exactly
the failure class the outbox exists for).

### B.3 Stripe Connect model change (this is the real cost)

Destination charges cannot be completion-gated — the transfer is part of the
PaymentIntent. Three ways to hold funds until completion:

| Option | Mechanism | Pros | Cons |
| :--- | :--- | :--- | :--- |
| **B-1 Separate charges & transfers** | Charge to the **platform** account (no `transfer_data`); on `BookingCompletedEvent`, `Transfer.create(amount=net, destination=coach, transfer_group=bookingId)` | Full control of timing; a pre-payout cancel/refund is a plain refund with **no transfer to reverse**; clean dispute story | Platform balance now carries float; platform is merchant of record; payout accounting (`CoachRevenue*`) must move from "captured" to "transferred"; every existing charge/refund/report path touched |
| **B-2 Destination charge + `on_behalf_of`, delayed via manual capture** | Authorize at booking (`capture_method=manual`), capture on `BookingCompletedEvent` | Smaller diff; funds only move on capture | Stripe auth holds expire after 7 days — sessions booked >7 days out (common) would need re-auth; parent sees a pending hold for days |
| **B-3 Keep destination charge, add a scheduled `Transfer` reversal on cancel/dispute** | No change to the happy path; on a post-payout cancel, `Transfer.createReversal(...)` | Minimal change | Does **not** meet the AC — payout still happens at confirmation; reversal can fail if the coach withdrew the balance |

**B-1 is the only option that actually satisfies "completion-gated".** B-2 breaks
on the >7-day booking window. B-3 is the status quo with a bandaid.

### B.4 Dispute-window interaction (the coach-rebuttal question in the AC)

The AC asks whether a coach gets a rebuttal window *before* an automatic
`NO_SHOW_COACH` refund fires. With B-1:

- A no-show / late-cancel refund *before* `BookingCompletedEvent` is a plain
  refund of a platform-held charge — **no coach money is touched**, so no rebuttal
  window is needed (nothing is taken from the coach).
- After `BookingCompletedEvent` the coach has been paid; a subsequent refund
  (dispute upheld) needs a `Transfer` reversal. **This** is where a rebuttal
  window matters. Recommendation: route post-payout refunds through the existing
  dispute system (`DisputeService`) rather than an automatic path, and add a
  `payout_hold_hours` (default 0) config so the owner *can* introduce a delay
  between `BookingCompletedEvent` and the transfer if they later want a
  standard rebuttal gap. Default 0 = pay on completion, matching today's intent.

### B.5 Stripe Connect settlement timing vs in-app completion

Even with B-1, `Transfer.create` only *initiates* the move to the connected
account; Stripe's own payout schedule (default daily/rolling) then pays the
coach's bank. In-app "completed & paid" therefore means "transfer initiated", not
"money in the coach's bank" — the revenue dashboard copy needs to say
"released to your Stripe account" not "paid".

### B.6 Recommendation

1. **Part A ✅ shipped** in skillars-deferred-91 (bounded, low-risk, closed a real
   slot-hold bug).
2. **Part B (B-1) is its own story.** It is a payment-architecture change that
   touches `StripePaymentGateway`, `PaymentLifecycleService`,
   `BookingPaymentPersistenceService`, `CancellationRefundService`, every
   `CoachRevenue*` / `RevenueReporting*` path, the outbox (new
   `COACH_PAYOUT_TRANSFER` handler), and a new `coach_payouts` ledger table. It
   needs its own design review, migration sequence, and Stripe test-mode
   verification. Filing to `deferred-work.md` under skillars-deferred-91 residuals
   (AC20) as **"Completion-gated coach payout (B-1 separate charges & transfers) —
   own story"** with this doc as the input.

### B.7 Open questions the Part B story must answer (2026-09-09 audit #5–#9)

Not blockers for Part A (already shipped) — but each must be resolved inside the
Part B story before B-1 is built, and each should become an explicit AC there.

1. **`BookingCompletedEvent` exactly-once (audit #5).** Today "one event per
   booking" rests on state-machine guards, not an idempotency key: the three
   publish sites (`QuickCompleteTimeoutService`,
   `BookingCompletionService.submitWrapUp` LIVE path,
   `BookingCompletionService.confirmCompletion`) each run after a `verifyStatus` +
   optimistic-locked `transition`, and the completed-state transition can only
   commit once. Probably sufficient — but Part B must **not** depend on it
   silently. Required: make the `COACH_PAYOUT_TRANSFER` outbox handler
   **idempotent per booking id** (the outbox already dedupes — that is the right
   place), plus a test that a duplicate `BookingCompletedEvent` yields at most one
   `Transfer.create`.
2. **Post-completion state transitions (audit #6).** Spec explicitly whether a
   parent or coach can cancel/refund *after* `BookingCompletedEvent` has fired and
   the coach has been paid. If yes → that path is a `Transfer` reversal (B.4's
   dispute route), not a charge refund. If no → document the post-completion
   booking state as immutable and have the cancel endpoints reject it.
3. **`Transfer.createReversal` failure handling (audit #7).** B.4 routes
   post-payout refunds through `DisputeService` but is silent on the reversal
   itself failing (coach withdrew the balance, network timeout, account closed).
   Part B needs: retry with backoff, an alert carrying booking id + coach id, and
   a DLQ / manual-follow-up path for unrecoverable reversals. "An operator owns
   it" must name the runbook entry.
4. **`DisputeService` dependency surface (audit #8).** Confirm `DisputeService`
   can (a) apply reversals idempotently, (b) handle both pre-payout (charge
   refund) and post-payout (transfer reversal) disputes, and (c) honour a
   configurable `payout_hold_hours` gap. Document reused vs. new flows; new flows
   are in scope for the Part B story.
5. **Coach connected-account disconnect between event and transfer (audit #9).**
   Coach disconnects their Stripe account after `BookingCompletedEvent` but before
   `CoachPayoutTransferHandler` runs → `Transfer.create` fails invalid-destination.
   Part B: the outbox retry holds the payout; add a runbook entry ("invalid
   destination → ask the coach to reconnect; manual transfer if needed") and an
   alert after N failed attempts.

**Owner decisions:**

- **D1 — RESOLVED.** Part A scope (A.4) shipped as specified in skillars-deferred-91.
- **D2 — RESOLVED: same release with the documented `migration-lint` opt-out.**
  V124 widens the CHECK and `abandonCapture` writes `CAPTURE_ABANDONED` in the
  same release, under the "no production system exists" project fact; V124's
  comment flags the pre-prod split-into-two-releases requirement.
- **D3 — RESOLVED: default `capture_pending_max_hours` = 72** (bounded 6–720),
  shipped.
- **D4 — RESOLVED (2026-09-09): build B-1 (separate charges & transfers)** as its
  own story, `skillars-deferred-106-completion-gated-coach-payout`, with this doc
  as input. B-2 (manual capture — breaks on the >7-day booking window) and B-3
  (status quo + reversal — does not meet the AC) are rejected.
- **D5 — RESOLVED (2026-09-09): `payment.payout.hold_hours` default 48**, not 0.
  Bounded 0–336h (14d dispute window); 0 restores release-on-completion with no
  code change. A dispute raised inside the 48h hold cancels the still-pending
  `COACH_PAYOUT_TRANSFER` outbox row outright (no pay-then-reverse); a dispute
  after release takes the `Transfer.createReversal` path.
- **D6 — RESOLVED (2026-09-09): yes.** B.7.1–B.7.5 are explicit ACs in
  `skillars-deferred-106` — AC7.2 (#5 exactly-once), AC9 (#6 post-completion
  immutable — cancellation rejected, disputes only), AC8.3 + AC14.1 (#7 reversal
  failure), AC10 (#8 `DisputeService` surface), AC8.2 + AC14.1 (#9 connected-account
  disconnect).
