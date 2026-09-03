# Completion-gated coach payout & the `CAPTURE_PENDING` dead-end — design pass

**Status:** DRAFT — awaiting project-owner review (skillars-deferred-91 AC5, Task 0).
Confirmed still DRAFT by the 2026-09-03 code review (decision D8): **D1–D5 below remain unanswered.**
Part A shipped on a verbal scope call; obtaining this sign-off is the first task of the Part B story.
Nothing in "Part B" below is implemented yet; "Part A" is implementable as specified once this doc is signed off.

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
     with the reason "no production data; single-node deploy". **Owner decision:
     one-release gap, or same-release with the documented opt-out?**

3. **`PaymentPendingSweeper.sweepOne` gains a `CAPTURE_PENDING` age branch** (it
   already re-reads the row under the booking-row lock):
   - if `existing.status == CAPTURE_PENDING` **and**
     `existing.created_at` (or a new `capture_reserved_at`) is older than
     `capture_pending_max_hours`:
     - write `existing.status = CAPTURE_ABANDONED` (keep `stripe_charged` as-is —
       do **not** zero it, the amount may be real);
     - `bookingService.transition(bookingId, PAYMENT_FAILED, SYSTEM)` — the same
       transition the no-row path uses, so the booking leaves `PAYMENT_PENDING`,
       the slot frees, and the parent's cancel is unblocked;
     - `meterRegistry.counter("booking.payment_pending.unrecoverable", "reason",
       "CAPTURE_TIMEOUT").increment()`;
     - `log.error("[CAPTURE_TIMEOUT] booking {} — CAPTURE_PENDING for > {}h, "
       + "marked CAPTURE_ABANDONED and slot released; reconcile the Stripe side "
       + "by hand (runbook: CAPTURE_PENDING)", ...)`.
   - if it is younger than the timeout: unchanged — `reportUnrecoverable(...,
     "CAPTURE_UNCONFIRMED")` as today (so the existing alert still fires every
     sweep until the timeout, then flips to `CAPTURE_TIMEOUT`).

4. **No automatic charge, confirm, or refund.** Part A only changes local booking
   state. `PaymentIntent` reconciliation stays manual, per `skillars-uat-3`'s own
   reasoning.

5. **`BookingService.java:736-744` guard** — once the row is `CAPTURE_ABANDONED`
   (terminal), the existing `!isTerminal` check already lets `recordNoShowCoach` /
   cancel through. Confirm no code path treats `CAPTURE_ABANDONED` as "still in
   flight" (grep every `CAPTURE_PENDING.equals(...)` / `isTerminal` caller).

6. **Runbook** — add a `CAPTURE_ABANDONED` section to
   `docs/deployment/runbook.md`: how to find the PaymentIntent by
   `metadata.referenceId` / `metadata.coachId`, and how to either (a) refund it if
   it captured, or (b) leave it if it never captured.

### A.3 Test plan (Part A)

- Unit (`PaymentPendingSweeperTest`): a `CAPTURE_PENDING` row older than the
  timeout → `CAPTURE_ABANDONED` + `PAYMENT_FAILED` transition + `CAPTURE_TIMEOUT`
  counter; a young one → unchanged, `CAPTURE_UNCONFIRMED` still reported.
- IT (extend an existing payment IT context, no new `@TestPropertySource`): seed a
  `CAPTURE_PENDING` row with `created_at` well in the past, run
  `sweepStrandedPayments()` (release the ShedLock first), assert the booking left
  `PAYMENT_PENDING`, the slot is free (a second booking for the same slot now
  inserts), and the parent cancel endpoint returns 2xx.

### A.4 ACs that fall out of Part A (proposed for this story, pending owner sign-off)

- **AC5a-1** new `CAPTURE_ABANDONED` status + V124 CHECK widen.
- **AC5a-2** `PaymentPendingSweeper` timeout branch + `CAPTURE_TIMEOUT` metric + ERROR.
- **AC5a-3** runbook section.

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

The booking lifecycle already produces exactly one:

- **`BookingCompletedEvent`** — published by `QuickCompleteTimeoutService`
  (auto-confirm after `booking.quick_complete_timeout_hours`) and by the
  coach/parent explicit-complete paths. This is the natural payout trigger.
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

1. **Part A ships in this story** (bounded, low-risk, closes a real slot-hold bug).
2. **Part B (B-1) is its own story.** It is a payment-architecture change that
   touches `StripePaymentGateway`, `PaymentLifecycleService`,
   `BookingPaymentPersistenceService`, `CancellationRefundService`, every
   `CoachRevenue*` / `RevenueReporting*` path, the outbox (new
   `COACH_PAYOUT_TRANSFER` handler), and a new `coach_payouts` ledger table. It
   needs its own design review, migration sequence, and Stripe test-mode
   verification. Filing to `deferred-work.md` under skillars-deferred-91 residuals
   (AC20) as **"Completion-gated coach payout (B-1 separate charges & transfers) —
   own story"** with this doc as the input.

**Owner decisions needed:**

- **D1** — Confirm Part A scope (A.4) is right for this story.
- **D2** — Part A: `CAPTURE_ABANDONED` CHECK widen one release ahead (rule 5), or
  same release with a documented `migration-lint` opt-out (no prod data)?
- **D3** — Part A default `capture_pending_max_hours` = 72 acceptable?
- **D4** — Confirm Part B (B-1) is deferred to its own story, with this doc as input.
- **D5** — Part B, when built: `payout_hold_hours` default 0 (pay on completion) —
  agreed, or do you want a non-zero default rebuttal gap?
