# Story UAT.3: Payment Capture Integrity & Backup Retention

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **Read "Regression surface" in Dev Notes before writing any code.** This story changes the shape of
> `payment.booking_payments` — a row can now exist *before* money moves. Four `existsById` calls in
> `PaymentLifecycleService` are idempotency guards that read "a row exists ⇒ this booking is settled",
> and **one of them (`:199`) silently inverts** the moment a pre-capture row exists: its condition
> becomes permanently false and the batch charge-failure path stops declining anything. Eleven test
> files touch this code, four of them are certain breaks and one fails to compile. Triage that list up
> front.

## Story

As the operator running UAT payment tests,
I want a booking's Stripe capture to leave a durable record before the money moves, and a parent's cancellation never to commit while that capture is in flight,
so that no test — and no real parent — can end up with money captured, the booking cancelled, and nothing in the database saying so.

### Why this story exists

Source: `_bmad-output/implementation-artifacts/uat-readiness-priorities.md` (2026-08-09 ranking), which
measures the open `deferred-work.md` backlog against one goal — deploy to a VPS, create a
player/parent/coach/admin account, log in, search a coach, pay, book.

`skillars-uat-1` (merged `8a76652`) took every UAT blocker needing no product decision.
`skillars-uat-2` (merged `237b835`) took P0-5, the one the first booking hits. Both explicitly deferred
**P1 #2 and P1 #3 — the payment-integrity pair** — to "after the first round of UAT payment testing".
That round is what this story makes safe to trust: both items are about money moving with no durable
record of it, which is exactly the failure you cannot debug after the fact.

The two items share one mechanism and are therefore one story, not two. P1 #3 asks for a durable
pre-capture record; P1 #2 asks for an interlock that stops a cancel committing mid-capture. **The
pre-capture record *is* the interlock** — a `CAPTURE_PENDING` row is the only thing a cancelling
transaction can read to know a capture may already have happened. Fixing them separately means
building the same row twice.

P2 #4 (backup retention) rides along as the last unclaimed P2 row: a two-script ops change with no
overlap into the Java tree, following the precedent of `uat-1` AC8 and `uat-2` AC6.

Every claim below was verified by direct read of the working tree at `237b835` on 2026-08-11.

| AC | Source item | Verified current state (2026-08-11, `237b835`) |
|---|---|---|
| AC1, AC3 | **P1 #3** — `deferred-15` story-creation D1 | **CONFIRMED, and the ledger's analysis is exactly right.** `PaymentLifecycleService:102` calls `paymentGateway.chargeAndCapture(...)` and *then* `:114` `persistPaymentSuccess(...)`; the batch path charges the whole batch at `:190-191` and settles per booking at `:209-233`. Both DB writes join a transaction that can still fail. `StripePaymentGateway` is not `@Transactional` and its only durable write — `stripeCustomer.setLastPaymentIntentId(...)` (`:67-70`) — joins the caller's transaction, so it rolls back in precisely the window that matters, and is keyed per parent and overwritten on every charge. `PaymentGateway` (contract) exposes no capture-lookup method. `StripeWebhookService` handles no `payment_intent.*` / `charge.*` event. Net: **money captured at Stripe, booking in `PAYMENT_PENDING`, no `booking_payments` row, and no durable signal anywhere that recovery is needed.** |
| AC2 | **P1 #2** — `deferred-12` D2 | **CONFIRMED, with one severity correction — see below.** `BookingService.cancelBookingAsParent:596-626` loads the booking with the unlocked `getBookingOrThrow` (`:580-583`), reads status at `:609`, computes `refundEligible=false` for `PAYMENT_PENDING` (`:610-613`), and commits `CANCELLED_PARENT`. `PAYMENT_PENDING → CANCEL_PARENT → CANCELLED_PARENT` is a legal transition (`BookingStateMachine:34-38`). Nothing anywhere takes a lock on `booking.bookings`; `@Version` (`Booking:55-57`) cannot help because the two writers commit in separate transactions. The settling listener's subsequent `PAYMENT_CAPTURED` from `CANCELLED_PARENT` throws `BookingStateTransitionException` out of an `AFTER_COMMIT` listener, where nothing catches it and nothing counts it. |
| AC4 | Found while scoping AC2 | **No AC-source item — new.** There is no `try`/`catch`, no ERROR log and no meter around **any of the five** settle-side `transition(...)` calls (`BookingPaymentPersistenceService:55,82,100,127,156`). Whatever the framework does with an exception escaping an `AFTER_COMMIT` listener, the application emits **zero application-level signal** that a settle just failed. AC1–AC3 make the known path unreachable; AC4 makes any *unknown* path visible instead of silent. |
| AC5 | Enabled by AC1 | `PaymentPendingSweeper:126-129` bails out on every credit-funded booking with `reportUnrecoverable(booking, "CREDIT_FUNDED")`, and its class Javadoc (`:34-58`) states why in detail, ending: *"The change that would make full coverage safe — a durable pre-capture record, plus making the four `existsById` idempotency checks status-aware — is tracked in `deferred-work.md`."* **AC1 and AC3 are that change.** This AC collects the payoff and rewrites the Javadoc, which otherwise ships contradicting the code. |
| AC6 | **P2 #4** — `deploy-3-1` review | **CONFIRMED.** `deploy/backup/pg-backup.sh` uploads one `.sql.gz` per run and never deletes; `install-crons.sh:9` runs it `0 */6 * * *` — **1,460 dumps/year, unbounded**. `deploy/backup/volume-snapshot.sh` creates one Hetzner snapshot per day (`install-crons.sh:10`) and never deletes. No lifecycle rule, no rotation script, no retention section in `docs/deployment/backup-restore.md`, no `*_RETENTION_DAYS` variable in `docs/deployment/secrets-reference.md`. |
| AC7 | Ledger hygiene | `deferred-12` D2, `deferred-15` story-creation D1 and the `deploy-3-1` retention row are closed by this story and must be recorded as such — dated one-line notes, following the `deferred-13`/`-14`/`-16`/`uat-1`/`uat-2` convention rather than silent deletion. |

### Severity correction on P1 #2 — read this before deciding the story is urgent

**`POST /api/bookings/{id}/cancel` has no caller in the frontend.** `booking.api.js:64` exports
`cancelBooking`, and grepping `cancelBooking` across `src/frontend/src` returns **that one line and
nothing else** — no page, no store, no component invokes it. `ParentBookingsPage.vue`'s only `cancel`
hit is a `q-btn` closing a dialog. So the parent-cancel race is reachable today **only by a direct API
call**, not by a UAT tester clicking through the app.

That is a real correction to the priorities doc's framing ("you will be exercising cancel flows with
test cards") and it is recorded honestly rather than quietly. It does **not** reduce the story:

1. **AC1/AC3/AC5 do not depend on it at all.** The stranded-capture window (P1 #3) opens on any JVM
   death, container restart or rolling deploy between the Stripe call and the commit — no cancel
   required. That is the item with money genuinely at risk during UAT.
2. **AC2 is what makes AC5 safe.** Widening the sweeper to credit-funded bookings rests on the
   invariant "no payment row ⇒ no Stripe call was made". An unlocked cancel that can commit inside the
   reservation window is a second writer that can invalidate the state the invariant is read against.
3. The missing cancel UI is itself a gap — recorded as a new deferred item under AC7, **not fixed
   here** (it is parent-journey UX with its own confirmation/refund-preview design).

## Acceptance Criteria

### AC1 — `CAPTURE_PENDING`: a `booking_payments` row that exists before the money moves

**New Flyway migration `V94__booking_payment_capture_pending.sql`.** `V93__session_duration.sql` is the
current highest — confirm with `ls src/main/resources/db/migration/ | sort -V | tail -1` before writing,
since another branch may have landed one.

```sql
-- UAT.3 AC1. A booking_payments row may now exist BEFORE the Stripe capture, so that
-- "no row" provably means "no charge was ever attempted" (see PaymentPendingSweeper).
ALTER TABLE payment.booking_payments
    DROP CONSTRAINT chk_bp_status,
    ADD CONSTRAINT chk_bp_status
        CHECK (status IN ('CAPTURE_PENDING', 'CAPTURED', 'CHARGE_FAILED', 'FROZEN'));
```

`chk_bp_status` is declared inline in `V62__session_payment_credit_wallet.sql:90` and PostgreSQL names
it exactly as written there, so `DROP CONSTRAINT chk_bp_status` is safe. **`CAPTURE_PENDING` is 15
characters and `booking_payments.status` is `VARCHAR(16)` (`V62:86`)** — it fits, with one character to
spare. Do not rename it to anything longer without widening the column.

**New method on `BookingPaymentPersistenceService`** — this is the only place a pre-capture row is
ever written:

```java
public enum CaptureReservation { RESERVED, BOOKING_NOT_PENDING, ALREADY_SETTLED, CAPTURE_UNCONFIRMED }

@Transactional(propagation = Propagation.REQUIRES_NEW)
public CaptureReservation reserveCapture(UUID bookingId, BigDecimal intendedCredit,
                                         BigDecimal intendedStripe, UUID batchId)
```

Body, in this exact order:

1. `bookingRepository.findByIdForUpdate(bookingId)` — **new repository method, see below.** Empty ⇒
   `BOOKING_NOT_PENDING`.
2. Status is not `PAYMENT_PENDING` ⇒ `BOOKING_NOT_PENDING`. *This is the branch that makes a cancel
   that won the race cost nothing: the caller returns before touching Stripe.*
3. An existing `booking_payments` row: status `CAPTURE_PENDING` ⇒ `CAPTURE_UNCONFIRMED`; any other
   status ⇒ `ALREADY_SETTLED`.
4. Otherwise insert `BookingPayment` with `status = "CAPTURE_PENDING"`, `creditDebited =
   intendedCredit`, `stripeCharged = intendedStripe`, `batchPaymentIntentId = batchId` (null for the
   single path), `stripePaymentIntentId = null`, `capturedAt = null` ⇒ `RESERVED`.

**`REQUIRES_NEW` is load-bearing and so is what the method does *not* touch.** It must commit
independently of the caller — a row that rolls back with the caller's transaction is worth nothing,
since that transaction rolling back is the exact scenario being defended against. And because
`REQUIRES_NEW` runs on a **second pooled connection**, this method must touch **only**
`booking.bookings` (the locked read) and `payment.booking_payments`. If the calling transaction already
holds a lock or a pending write on the same booking row, this method self-deadlocks against its own
caller on a different connection — and `UPDATE` has no lock timeout, so it hangs rather than failing.
See AC3 for the call ordering that keeps this true, and verify it there rather than assuming it.

**New `BookingRepository.findByIdForUpdate`** — copy the annotation stack from
`BookingRescheduleRequestRepository:28-31` verbatim, bounded lock wait included:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
@Query("SELECT b FROM Booking b WHERE b.id = :id")
Optional<Booking> findByIdForUpdate(@Param("id") UUID id);
```

The 5-second bound is not decoration: without it a contended row blocks the request thread
indefinitely; with it, `ApiAdvice.pessimisticLockExceptionHandler:579-585` already maps the timeout to a
clean `409`. That handler exists precisely for this idiom (`CoachProfileRepository:28-34`).

Two smaller points, both easy to get wrong:

- `reserveCapture` reads `booking_payments` **unlocked**, after locking the booking row. That is
  sufficient: two concurrent reservations for the same booking serialise on the booking-row lock, so the
  read-then-insert is protected by it, and the `pk_booking_payments` primary key is the backstop. Do not
  add a second `findByIdForUpdate` on `BookingPaymentRepository`.
- `BookingService.readStatusOrThrow` is **private** and returns a 404-shaped exception on an
  unrecognised status (`deferred-12` D4). Do not try to reuse it from the payment module. Compare the
  status string directly, exactly as `PaymentPendingSweeper:124` already does.

**The invariant this AC establishes — state it in the Javadoc, because AC5 depends on it:**

> For a booking still in `PAYMENT_PENDING`, the **absence** of a `payment.booking_payments` row proves
> no Stripe call has been attempted for it.

It holds because the *only* two Stripe-charging call sites for bookings
(`PaymentLifecycleService:102` and `:190`) are gated behind `reserveCapture` by AC3, and every other
settlement path never calls Stripe at all.

**The settlement paths this story deliberately does NOT change, and why they are already safe** — read
this before "completing" the fix by reserving everywhere:

| Path | Why no reservation is needed |
|---|---|
| Pack-funded, single (`handlePackBasedBooking:77-90`) | `packSessionService.deductSession` and `persistPaymentSuccess` both run inside `onBookingAccepted`'s one `REQUIRES_NEW` transaction. A cancel that wins the race makes the `PAYMENT_CAPTURED` transition illegal, the transaction rolls back, and **the deduction rolls back with it**. Self-healing, with no external side effect to strand. |
| Pack-funded, batch (`:143-162`) | Same, per booking: `perBookingTx` wraps `deductSession` + `confirmPackBatchPayment` in one transaction — the code comment at `:149-151` already records that this is why no manual `restoreSession` compensation exists. |
| Credit-only, where `stripeAmount == 0` | `persistPaymentSuccess` writes the `BOOKING_DEDUCTION` ledger entry, the `booking_payments` row and the transition in one transaction (`:32-36`). No row ⇒ no deduction. |

Only the Stripe leg is broken, and only because Stripe is not transactional. Reserving on the other
paths would add rows that violate the invariant above — "row exists" would stop implying "a charge was
attempted" — and would break AC5. **Do not do it.**

**Readers of `booking_payments` — audited, one change needed:**

| Reader | Effect of a `CAPTURE_PENDING` row | Action |
|---|---|---|
| `BookingPaymentRepository` `sumGrossByCoachAndPeriod`, `countCapturedByCoachAndPeriod`, `sumTotalGross`, `countCapturedForPeriod` | None — all four filter `status = 'CAPTURED'`. | none |
| `BookingPaymentRepository.findByCoachAndPeriod` / `findBookingIdsByCoachAndPeriod` | None — both filter `captured_at BETWEEN :from AND :to`, and `captured_at` is `NULL` on a pre-capture row, which no `BETWEEN` matches. | none — but **assert it** (see AC-tests) |
| `RevenueReportingService.getCoachReceipt:146` / `getParentReceipt:176` | **Changes behaviour.** Both do a bare `findById(...).orElseThrow(404)`, so a pre-capture row would render a receipt showing money that may never have been taken. | Insert `.filter(bp -> "CAPTURED".equals(bp.getStatus()))` between the `findById` and the `orElseThrow`, preserving today's 404 for anything else |
| `DisputeService:133,168` | Unreachable — `DISPUTED` is only reachable from `IN_PROGRESS`/`PAUSED`/`COMPLETED*` (`BookingStateMachine:54-75`), all downstream of `CONFIRMED`. | none; note it in the AC7 record so a later reader does not re-derive it |
| `GdprExportService:136` | `findAllById` includes the row. Harmless — it is the parent's own payment data and the export is a factual dump. | none |

### AC2 — A parent cancellation cannot commit while a capture is in flight

`BookingService.cancelBookingAsParent` (`:595-626`) gains the other half of the interlock.

New shape, replacing lines `597-613`:

```java
// Unlocked read + ownership check FIRST, locked re-read second. Deliberately in this order:
// taking a row lock before authorising the caller lets any authenticated user pin an arbitrary
// booking row for the duration of the transaction before receiving their 403 — the exact finding
// deferred-16 D2 raised against MessagingService.softDeleteMessage. One extra SELECT is cheap.
Booking unlocked = getBookingOrThrow(bookingId);
if (!Objects.equals(unlocked.getParentId(), parentUserId)) {
    throw new OperationNotAllowedException("Parent does not own this booking", SecurityError.MISSING_RIGHTS);
}
Booking booking = bookingRepository.findByIdForUpdate(bookingId)
    .orElseThrow(() -> new ResourceNotFoundException("Booking not found", "booking"));
BookingStatus statusBeforeCancel = readStatusOrThrow(booking);

// UAT.3 AC2. A CAPTURE_PENDING row means a Stripe charge for this booking is in flight or has
// completed without its record committing. Cancelling on top of it is what produced
// "money captured, booking cancelled, no refund" (deferred-12 D2). Refuse and let the parent
// retry: within seconds the booking is CONFIRMED and the ordinary refund rules apply.
if (statusBeforeCancel == BookingStatus.PAYMENT_PENDING
        && bookingPaymentRepository.findById(bookingId)
               .filter(bp -> "CAPTURE_PENDING".equals(bp.getStatus())).isPresent()) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, "booking.paymentInProgress");
}
```

Then the existing `paymentWasCaptured` / `refundEligible` computation (`:610-613`) and everything below
it stays **exactly as written** — including the deliberate `CONFIRMED`/`UPCOMING` whitelist and its
comment. The only change is that `statusBeforeCancel` is now read under the row lock, so it cannot be
stale by the time `transition(...)` writes.

- `ResponseStatusException(HttpStatus.CONFLICT, ...)` is the established in-service 409 idiom here —
  `DisputeService:163` uses it for `disputes.alreadyResolved`, and `ApiAdvice:118-121` preserves the
  status and surfaces the reason. Do **not** invent a new exception type or add a `BookingError`
  constant: `BookingError` values map to `OperationNotAllowedException`, which does not produce a 409.
- `BookingService` gains `private final BookingPaymentRepository bookingPaymentRepository;`. Crossing
  from `platform.booking` into `platform.payment.repo` is already the established shape in this class —
  it injects `SessionPackPurchaseRepository` from the same package (`BookingService:41-42,114`). Do not
  route this through `PaymentGateway`; it is a database question, not a gateway one.
- `transition(...)` re-reads the booking through `getBookingOrThrow` inside the same persistence
  context, so it returns the same locked instance. Do **not** add a second locked read.

**Interleavings this must produce — pin all three (see AC-tests):**

| Order | Outcome |
|---|---|
| Cancel commits first | `reserveCapture` acquires the lock, sees `CANCELLED_PARENT`, returns `BOOKING_NOT_PENDING`. **Stripe is never called.** No money moves, `refundEligible=false` is correct. |
| Reservation commits first | Cancel blocks on the lock, then reads `PAYMENT_PENDING` + a `CAPTURE_PENDING` row ⇒ **409**. Seconds later the booking is `CONFIRMED` and a retry cancels through the ordinary refundable path. |
| Neither in flight | Unchanged from today, byte for byte. |

### AC3 — Both settlement paths reserve before charging, and update rather than insert after

**Single booking — `PaymentLifecycleService.handleCreditBasedBooking:92-117`.** Reserve only when a
Stripe call is actually about to happen; a fully credit-covered booking (`stripeAmount == 0`) touches
Stripe never and must keep writing exactly one `CAPTURED` row at settle time, unchanged.

```java
if (stripeAmount.compareTo(BigDecimal.ZERO) > 0) {
    CaptureReservation reservation = persistenceService.reserveCapture(
        event.getBookingId(), creditToUse, stripeAmount, null);
    if (reservation != CaptureReservation.RESERVED) {
        abortSettle(event.getBookingId(), reservation);   // logs + meter, see below
        return;                                            // BEFORE the gateway call
    }
    try {
        paymentIntentId = paymentGateway.chargeAndCapture(...);   // unchanged
    } catch (PaymentGatewayException e) { ...existing failure path... }
}
```

`abortSettle` (one private helper, used by both paths) maps outcome → severity:

| Outcome | Level | Meter |
|---|---|---|
| `BOOKING_NOT_PENDING` | `WARN` — expected and correct; the booking was cancelled or already settled | `booking.payment.settle_aborted{reason="booking_not_pending"}` |
| `ALREADY_SETTLED` | `WARN` — duplicate event delivery, the case `:62` already logs today | `booking.payment.settle_aborted{reason="already_settled"}` |
| `CAPTURE_UNCONFIRMED` | **`ERROR`** — a previous attempt reserved and did not finish; **money may already be at Stripe** | `booking.payment.settle_aborted{reason="capture_unconfirmed"}` |

**Never re-charge on `CAPTURE_UNCONFIRMED`.** A duplicate delivery landing on a reserved row means the
prior attempt's outcome is unknown; charging again risks double-charging a parent. Leave the booking in
`PAYMENT_PENDING` and let the sweeper (AC5) escalate it. Follow `PaymentPendingSweeper.reportUnrecoverable`
(`:162-173`) for the log wording — ERROR here must mean "an operator has to reconcile this".

**Write-back, not insert.** `persistPaymentSuccess:46-54` and `persistPaymentFailure:76-81` both do
`new BookingPayment()` + `save()`. With a reserved row present that is a `merge()`, not an insert
(Spring Data's `isNew()` is false for an assigned `@Id`), which silently overwrites `frozenAt` and any
other column the fresh object left null. Change both to **load the row if it exists and mutate it**,
falling back to a new instance when it does not:

```java
BookingPayment bp = bookingPaymentRepository.findById(bookingId).orElseGet(BookingPayment::new);
bp.setBookingId(bookingId);
... set the fields this path owns ...
bp.setStatus("CAPTURED");
bp.setCapturedAt(Instant.now());
```

Apply the same to `confirmPackBatchPayment`, `confirmCreditBatchPayment` and `declineBatchBooking`, so
one helper covers every writer and no path can clobber a reserved row.

**Batch booking — `PaymentLifecycleService.onBatchBookingAccepted:127-234`.** Restructure the credit
branch (`:164-233`); leave the pack loop (`:143-162`) alone entirely.

1. **Reserve before pricing.** Replace the subtotal loop (`:167-176`) with a loop that, per
   credit-funded booking: resolves the price, calls `reserveCapture(bookingId, ZERO, price, batchId)`,
   and keeps the booking **only** if the result is `RESERVED` (otherwise `abortSettle` and drop it).
   Build `reserved` — the list of `(bookingId, price)` that actually hold a reservation.
   *Intended credit is not known per booking until the wallet split below, so reserve with the price in
   `stripeCharged` and let the settle write-back correct both columns. The reserved row's amounts are
   an operator's reconciliation hint, not an accounting record — only `CAPTURED` rows are summed.*
2. **Compute the subtotal from `reserved` only**, never from the full `creditIds`. Charging for a
   booking that failed to reserve is the exact defect this AC exists to prevent.
3. If `reserved` is empty, **return before** the wallet read, the ledger entry and the charge.
4. `creditWalletService.writeLedgerEntry(BOOKING_DEDUCTION)` (`:182-185`) and
   `chargeAndCaptureForBatch` (`:190-191`) stay where they are, operating on the reserved subtotal.
5. **`:198-202` is a live break, not a refactor.** Today the charge-failure path declines every booking
   `if (!bookingPaymentRepository.existsById(bookingId))`. Every reserved booking now *has* a row, so
   that condition is permanently false and **nothing would be declined** — the whole batch would strand
   in `PAYMENT_PENDING`. Replace it with: decline every booking in `reserved`, writing its existing row
   to `CHARGE_FAILED` via the AC3 write-back helper.
6. The settle loop (`:209-233`) iterates `reserved` instead of `creditIds`, and its `existsById` guard
   at `:210` becomes the status-aware check below.

**Deadlock check the dev must perform, not assume.** `reserveCapture` is `REQUIRES_NEW` and takes a
`PESSIMISTIC_WRITE` lock on `booking.bookings` on a second connection, while `onBatchBookingAccepted`'s
own `REQUIRES_NEW` transaction is open on the first. Confirm by reading, before writing any code, that
the outer transaction holds **no** lock and **no** uncommitted write on those booking rows at the point
each `reserveCapture` call is made — today it only issues plain `findById` reads (`:134,146,169,211`),
which take no row locks under `READ COMMITTED`. If a future edit adds a write there, this deadlocks
with **no timeout** (a plain `UPDATE` ignores the 5 s `lock.timeout` hint, which applies only to the
locked `SELECT`). Say so in a comment at both call sites.

**The four `existsById` guards become status-aware** — `:62`, `:145`, `:199`, `:210`. One private
helper, used at all four:

```java
// A row no longer means "settled": AC1 writes one BEFORE the charge. Only a terminal status
// proves this booking is done; CAPTURE_PENDING means a prior attempt died mid-capture and must
// escalate rather than be silently skipped as a duplicate.
private boolean isSettled(UUID bookingId) {
    return bookingPaymentRepository.findById(bookingId)
        .map(bp -> !"CAPTURE_PENDING".equals(bp.getStatus()))
        .orElse(false);
}
```

At `:62` (single path) the duplicate-delivery guard becomes: terminal row ⇒ `WARN` + return, as today;
`CAPTURE_PENDING` row ⇒ `abortSettle(..., CAPTURE_UNCONFIRMED)` + return. At `:145` (pack loop)
behaviour is unchanged in practice — pack settlement never reserves — but use the same helper so no
second reading of "row exists" survives in the file.

### AC4 — A failed settle transition is loud

`BookingPaymentPersistenceService` calls `bookingService.transition(...)` at **five** sites — `:55`
(`persistPaymentSuccess`), `:82` (`persistPaymentFailure`), `:100` (`confirmPackBatchPayment`), `:127`
(`confirmCreditBatchPayment`) and `:156` (`declineBatchBooking`) — all inside `AFTER_COMMIT` listeners.
A `BookingStateTransitionException` at any of them produces no application-level ERROR and no meter
today.

- Wrap **all five** (one private helper — do not copy a `try`/`catch` five times) so
  `BookingStateTransitionException` is logged at **ERROR** with `bookingId`, the status it was read
  from, and the event attempted, and increments
  `booking.payment.settle_conflict{event="PAYMENT_CAPTURED"|"PAYMENT_FAILED"}`.
- **Count the sites before you start. `:82` is the one most likely to be missed and the one most likely
  to fire.** It is `persistPaymentFailure`'s `PAYMENT_FAILED` transition — the synchronous decline path,
  reached on *every* declined test card in the single-booking credit flow and on every pack-deduction
  failure. It is therefore the highest-traffic settle transition in UAT, and `PAYMENT_FAILED` is exactly
  as illegal from `CANCELLED_PARENT` as `PAYMENT_CAPTURED` is. Leaving it unwrapped would keep the
  busiest site silent while closing the four quiet ones — the opposite of this AC's point. AC3 already
  changes this same method's payment-row write to load-then-mutate, so you are in the method regardless.
- **Rethrow after logging.** Do not swallow. Rolling the settle transaction back is correct — and the
  reserved `CAPTURE_PENDING` row survives it (AC1 wrote it in its own transaction), which is precisely
  the durable signal AC5 escalates on. Swallowing would commit a half-settled state with the row
  already updated.
- Micrometer counters are not transactional, so the increment survives the rollback. That is intended;
  say so in a comment, or the next reader will "fix" it.
- Follow `PaymentPendingSweeper:70-71,155,168` for meter naming and `Counter.builder(...).register(meterRegistry)`
  construction. `BookingPaymentPersistenceService` does not currently inject `MeterRegistry`; add it.

AC1–AC3 make the known route to this exception unreachable. AC4 is for the routes nobody has found yet.

### AC5 — The sweeper covers credit-funded bookings

`PaymentPendingSweeper.sweepOne:120-160`. Replace the funding-type test with a payment-row test:

```java
Optional<BookingPayment> existing = bookingPaymentRepository.findById(bookingId);
if (existing.isPresent()) {
    reportUnrecoverable(booking, "CAPTURE_PENDING".equals(existing.get().getStatus())
        ? "CAPTURE_UNCONFIRMED" : "PAYMENT_ROW_PRESENT");
    return;
}
// No row at all ⇒ UAT.3 AC1's invariant says no Stripe call was ever attempted for this
// booking, whatever its funding type. Safe to decline and hand the coach's slot back.
```

- **Delete the `sessionPackPurchaseId == null` bail-out (`:126-129`) and the `CREDIT_FUNDED` reason
  entirely.** A credit-funded booking with no payment row is now decidable, which is the whole point.
- Keep `reportUnrecoverable`'s ERROR level, its `reason` tag and its counter shape unchanged — the
  `booking.payment_pending.unrecoverable` counter simply gains a `CAPTURE_UNCONFIRMED` reason and loses
  `CREDIT_FUNDED`.
- **Rewrite the class Javadoc (`:34-58`).** Two full paragraphs are dedicated to explaining why
  credit-funded bookings are knowingly left alone, one of them opening *"Do not 'complete' this sweeper
  by dropping the pack-funded precondition."* Shipping AC5 without rewriting them leaves the file
  arguing against its own code. The replacement must state the new invariant, name `reserveCapture` as
  what establishes it, and say what `CAPTURE_UNCONFIRMED` means for an operator.
- Everything else in the class — the grace-window config (`:65-68,92-94`), the `@SchedulerLock` margin
  and its comment (`:83-90`), the one-transaction-per-booking loop, the `OptimisticLockingFailureException`
  branch (`:108-113`) — stays exactly as it is.

**Operator runbook entry (`docs/deployment/runbook.md`).** A `CAPTURE_PENDING` row has no automated
exit: the booking cannot be swept, and AC2 refuses the parent's cancel for as long as it stands. Add a
short section covering the manual resolution:

1. Alert source: `booking.payment_pending.unrecoverable{reason="CAPTURE_UNCONFIRMED"}` and the matching
   ERROR line.
2. In the Stripe dashboard, search PaymentIntents by metadata `referenceId` = the booking id (single
   path) or the batch id (batch path) — `StripePaymentGateway:49-50` writes both.
3. Charge found ⇒ set the row to `CAPTURED` with `captured_at` and `stripe_payment_intent_id`, and
   transition the booking to `CONFIRMED`. No charge ⇒ set the row to `CHARGE_FAILED` and decline.
4. Both are SQL + a state transition; record the SQL, and note that it is deliberately manual because
   only a human can read the Stripe side.

### AC6 — Backup retention

**New `deploy/backup/prune-backups.sh`**, matching the conventions of its two siblings exactly:
`#!/usr/bin/env bash`, `set -euo pipefail`, `. /opt/skillars/.env`, `[prune-backups]` /
`[prune-backups][error]` log prefixes, and a final `Done. $(date -u)`.

Two independent parts; **a failure in one must not skip the other**, and neither may silently no-op.

1. **S3 dumps.** Retain `${BACKUP_RETENTION_DAYS:-14}` days of `s3://${HOS_BUCKET}/${PREFIX}`. List with
   the same `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `--endpoint-url "${HOS_ENDPOINT}"` shape
   `pg-backup.sh:32-37` uses. **Two safety rails, both required:**
   - Never delete unless at least `${BACKUP_RETENTION_MIN_KEEP:-8}` objects would remain. A clock skew,
     an empty listing or a changed prefix must not be able to empty the bucket.
   - `--dry-run` (or `DRY_RUN=1`) prints what it would delete and exits 0 without deleting. The first
     production run should be a dry run; say so in the doc.
   Derive the age from the object key's `%Y%m%dT%H%M%SZ` stamp (`pg-backup.sh:10-11`), **not** from
   `aws s3 ls`'s printed date — the key is the format this project controls.
2. **Hetzner snapshots.** Retain `${SNAPSHOT_RETENTION_DAYS:-7}` days of snapshots whose description
   matches the `daily-YYYY-MM-DD` pattern `volume-snapshot.sh:9-10` writes. Same `HCLOUD_TOKEN` bearer
   auth, same explicit HTTP-code check as `volume-snapshot.sh:20-26`.
   **Verify the list and delete endpoints against the current Hetzner Cloud API docs before writing
   this half.** `volume-snapshot.sh` creates via `POST /v1/volumes/{id}/actions/create_snapshot`, and
   `deploy/backup/drill-log.md` contains only its own placeholder row — **no restore drill has ever
   been run**, so nothing in this repo demonstrates that endpoint has ever been exercised against the
   live API. If the response shape does not match what you expect, the script must **exit non-zero with
   the body in the message**, never treat "no snapshots parsed" as "nothing to prune". Never filter on
   description alone without also confirming the snapshot belongs to `HETZNER_VOLUME_ID`.

**`deploy/backup/install-crons.sh`** gains a third entry using the identical `grep -qF` idempotence
guard (`:12-24`), logging to the same `${LOG}`, scheduled clear of both existing jobs — `30 3 * * *`
(pg-backup runs at `0 */6`, the snapshot at `0 2`).

**Docs:**
- `docs/deployment/backup-restore.md` — a **Retention** section stating both windows, both env vars,
  the minimum-keep floor, and the dry-run first run.
- `docs/deployment/secrets-reference.md` — add `BACKUP_RETENTION_DAYS`, `BACKUP_RETENTION_MIN_KEEP` and
  `SNAPSHOT_RETENTION_DAYS` to the table at `:48-54`, matching its existing column shape (value example
  + where it comes from). These are tuning knobs, not secrets; say so in the description column.
- `docs/deployment/runbook.md` — retention is already documented there for Loki (30 d, `:61-62`) and
  Prometheus (15 d, `:66-67`). Add backups beside them so all four windows read from one place.

### AC7 — Ledger and priorities hygiene

**`_bmad-output/implementation-artifacts/deferred-work.md`** — dated one-line closure notes, not
deletions (the `deferred-13`/`-14`/`-16`/`uat-1`/`uat-2` convention):

| Item | Record |
|---|---|
| `deferred-12` D2 (parent-cancel races the settle) | **Closed** by AC2 + AC3. Note that the fix is a booking-row lock plus a `CAPTURE_PENDING` interlock, and that the endpoint has no frontend caller today (see the severity correction above) |
| `deferred-15` story-creation D1 (no durable pre-capture record) | **Closed** by AC1 + AC3 + AC5. Its own text names all three required pieces — the row, the status-aware `existsById` guards, and the widened sweeper precondition. Confirm each shipped before closing |
| `deploy-3-1` "No retention policy" row (`:969-977` block) | **Closed** by AC6, naming `prune-backups.sh` and both windows |

**New items to record** (under a `## Deferred from: skillars-uat-3-...` heading):
- **No Stripe idempotency key on `chargeAndCapture`.** `StripeClient.createSubscription:64-70` already
  uses `RequestOptions.setIdempotencyKey(...)`; `createPaymentIntent:28-30` does not. A
  `PaymentGatewayException` from a network timeout may mean "charged" or "not charged", and the failure
  path writes `CHARGE_FAILED` either way — unchanged by this story, and now the last remaining
  ambiguity in the capture path. A booking-id-derived key would close it.
- **`CAPTURE_PENDING` has no automated exit.** AC5 escalates and the AC5 runbook entry documents the
  manual resolution; nothing times it out. Deliberate — only a human can read the Stripe side.
- **`POST /api/bookings/{id}/cancel` has no frontend caller.** `booking.api.js:64` is dead. A parent
  cannot cancel through the app at all, which is a parent-journey gap with its own
  confirmation/refund-preview design.
- **`DisputeService`'s payment lookups are unguarded on status** — safe only because `DISPUTED` is
  unreachable from `PAYMENT_PENDING`. Record the reasoning so a future state-machine edit has a
  tripwire.
- **`SessionPackPurchaseRepository.findByIdForUpdate` (`:17-20`) has no `jakarta.persistence.lock.timeout`
  hint**, unlike the two sibling `findByIdForUpdate` methods it otherwise mirrors
  (`CoachProfileRepository:28-34`, `BookingRescheduleRequestRepository:26-31`). Contention on a pack row
  therefore blocks the request thread indefinitely instead of surfacing as `ApiAdvice`'s 409. It also
  carries an extra `@Transactional` the other two do not. Pre-existing, untouched by this story, and a
  three-line fix — but harmonising the three is its own change with its own regression pass.

**`_bmad-output/implementation-artifacts/uat-readiness-priorities.md`:**
- Add a row to the **Story claims** table for this file, listing P1 #2 (AC2), P1 #3 (AC1/AC3/AC5), P2 #4
  (AC6).
- Mark P1 #2, P1 #3 and P2 #4 inline as **CLAIMED**, in the same `> **CLAIMED …** Do not pick up again.`
  form P0-1/P0-3/P0-5 already use.
- Update **Still unclaimed** (line 22-23) to: P0-2 and P0-4 (both product decisions), P1 #7 and #8 (the
  i18n pair), everything in P3.
- **Suggested sequence:** strike item 7 (payment integrity) as done by this story, and strike item 9's
  `deploy-3-1` clause — after this, P2 is empty.
- Correct the P1 #2 entry's framing per the severity correction above: cancel flows are **not**
  exercisable from the UI.

## Tasks / Subtasks

- [x] **Task 0 — Triage the regression surface before writing code (AC: all)**
  - [x] Read "Regression surface" in Dev Notes and open all ten affected test files. Decide per file:
        changes shape / changes assertion / unaffected. Write that list down first.
  - [x] Confirm the next free Flyway version: `ls src/main/resources/db/migration/ | sort -V | tail -1`.
  - [x] Confirm the deadlock precondition for AC3 by reading `onBatchBookingAccepted` and
        `handleCreditBasedBooking` for any lock or write on `booking.bookings` before each
        `reserveCapture` call site.
- [x] **Task 1 — Pre-capture record (AC: 1)**
  - [x] `V94__booking_payment_capture_pending.sql`; drop + re-add `chk_bp_status`.
  - [x] `BookingRepository.findByIdForUpdate` with the bounded lock-wait hint.
  - [x] `CaptureReservation` enum + `reserveCapture` on `BookingPaymentPersistenceService`,
        `REQUIRES_NEW`, with the invariant and the self-deadlock constraint in its Javadoc.
  - [x] Guard `RevenueReportingService.getCoachReceipt` / `getParentReceipt` on `status = CAPTURED`.
- [x] **Task 2 — Cancel interlock (AC: 2)**
  - [x] Inject `BookingPaymentRepository` into `BookingService`.
  - [x] Rework `cancelBookingAsParent`: unlocked read → ownership → locked re-read → 409 on a
        `CAPTURE_PENDING` row → existing refund logic untouched.
- [x] **Task 3 — Settlement paths (AC: 3)**
  - [x] Single path: reserve → abort or charge; `abortSettle` helper with its three severities.
  - [x] Write-back helper; convert all five `new BookingPayment()` + `save()` sites in
        `BookingPaymentPersistenceService`.
  - [x] Batch path: reserve-then-price, subtotal from `reserved`, early return on empty, **rewrite the
        `:198-202` decline loop**, settle loop over `reserved`.
  - [x] `isSettled` helper replacing all four `existsById` guards.
- [x] **Task 4 — Loud settle failures (AC: 4)**
  - [x] Inject `MeterRegistry`; wrap **all five** `transition(...)` calls — `:55`, **`:82`**, `:100`,
        `:127`, `:156` — via one helper; log ERROR, count, rethrow. Grep
        `bookingService.transition` in the file and confirm the count is five before moving on.
- [x] **Task 5 — Sweeper (AC: 5)**
  - [x] Payment-row test replaces the funding-type test; `CAPTURE_UNCONFIRMED` reason.
  - [x] Rewrite the class Javadoc.
  - [x] Runbook section for resolving a `CAPTURE_PENDING` row.
- [x] **Task 6 — Tests (AC: 1–5)**
  - [x] Fix every file identified in Task 0.
  - [x] Add the new coverage listed under "Testing requirements".
  - [x] **Mutation-check the interlock**: with the `CAPTURE_PENDING` branch of AC2 deleted, at least one
        named test must fail. Record the test name and the failure in the completion notes.
- [x] **Task 7 — Backup retention (AC: 6)**
  - [x] `prune-backups.sh` (S3 half + snapshot half, dry-run, minimum-keep floor).
  - [x] `install-crons.sh` third entry.
  - [x] `backup-restore.md`, `secrets-reference.md`, `runbook.md`.
  - [x] `shellcheck` all three scripts, matching whatever the siblings already pass.
- [x] **Task 8 — Ledger and priorities (AC: 7)**
  - [x] Three closures + four new deferred items in `deferred-work.md`.
  - [x] Story-claims row, three inline CLAIMED marks, "Still unclaimed" update, sequence update, and the
        P1 #2 severity correction in `uat-readiness-priorities.md`.
- [x] **Task 9 — Verify**
  - [x] Full `mvn -o verify`, `0F/0E`. Derive your contribution by **diffing `@Test` counts against
        `HEAD`**, not by subtracting totals — see "Baseline" in Dev Notes.
  - [x] Confirm no IT class silently stopped running: concrete `*IT` sources vs. failsafe reports.

### Review Findings

Code review 2026-08-11 (bmad-code-review): Blind Hunter (diff-only) + Edge Case Hunter (diff + project
read access) + Acceptance Auditor (diff + this spec). 23 unified findings after dedup: 13 patch, 7
deferred (1 resolved from decision-needed — Mbah chose to defer the AC6 HETZNER_VOLUME_ID gap pending
the D1 backup-strategy decision), 3 dismissed as noise/working-as-intended. **All 13 patches applied
and independently re-verified against source 2026-08-11** — including the headline one:
`PaymentPendingSweeper.sweepOne()` now takes `findByIdForUpdate` on the booking row before its
payment-row check, serialising against `reserveCapture` on the same lock, closing the silent-merge-
overwrite path found during review. `reserveCapture` calls are now wrapped (`reserveOrReport`),
`transitionOrReport` catches `RuntimeException` broadly, the batch loop no longer abandons reserved
siblings on a thrown reservation, `CAPTURE_PENDING` is consolidated into `BookingPaymentStatus`, the
concurrency IT now asserts only the two legal outcomes with both threads' throwables surfaced on
failure, and `prune-backups.sh` gained explicit arg/`.env`/numeric-input validation and a real
non-numeric-HTTP-status guard on both the list and delete calls.

- [x] [Review][Defer] AC6's explicit safety rail is unimplemented — `prune_snapshots()` filters
      Hetzner images on the `daily-YYYY-MM-DD` description pattern alone, never confirming the snapshot
      belongs to `HETZNER_VOLUME_ID` as AC6 requires verbatim. No `HETZNER_VOLUME_ID` reference exists
      anywhere in `prune-backups.sh`. [deploy/backup/prune-backups.sh:150-168] — deferred, pending
      D1: `volume-snapshot.sh` doesn't produce real snapshots against the live API yet, so this
      AC line is moot until Mbah picks the real replacement backup approach for the Hetzner Volume.

- [x] [Review][Patch] `PaymentPendingSweeper.sweepOne()` can silently overwrite a live `CAPTURE_PENDING` row instead of erroring — the exact "hide a possibly-real Stripe charge" failure AC1 exists to prevent [src/main/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeper.java:130-153]
- [x] [Review][Patch] `reserveCapture()` calls in both settle listeners are unguarded — an exception from `reserveCapture` itself escapes the AFTER_COMMIT listener with no ERROR log or counter, undermining AC4's own stated purpose [src/main/java/com/softropic/skillars/platform/payment/service/PaymentLifecycleService.java:167,263]
- [x] [Review][Patch] `transitionOrReport()` only catches `BookingStateTransitionException` — any other exception from `bookingService.transition()` bypasses the ERROR log/counter AC4 added [src/main/java/com/softropic/skillars/platform/payment/service/BookingPaymentPersistenceService.java:133-148]
- [x] [Review][Patch] Batch settle loop: if `reserveCapture` throws mid-loop, already-reserved sibling bookings are left as `CAPTURE_UNCONFIRMED` with no decline/reconciliation attempt [src/main/java/com/softropic/skillars/platform/payment/service/PaymentLifecycleService.java:250-268]
- [x] [Review][Patch] `prune-backups.sh` accepts any first argument as a no-op — a typo'd dry-run flag (e.g. `--dry_run`) silently runs live with no warning [deploy/backup/prune-backups.sh:20-23]
- [x] [Review][Patch] `CAPTURE_PENDING` is duplicated as 4 independent string literals across `BookingService`, `BookingPaymentPersistenceService`, `PaymentLifecycleService` (as `RESERVED_STATUS`), and `PaymentPendingSweeper`, with nothing enforcing they stay in sync [src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:634; .../payment/service/BookingPaymentPersistenceService.java:34; .../PaymentLifecycleService.java:37; .../PaymentPendingSweeper.java:139]
- [x] [Review][Patch] `CaptureReservationIT`'s concurrency test docstring claims "only two outcomes are legal, and both are asserted explicitly," but the assertion accepts a third (`PAYMENT_PENDING`) and a `catch (Throwable ignored)` can mask the settle-completion failure the test exists to catch [src/test/java/com/softropic/skillars/platform/payment/service/CaptureReservationIT.java:213-238]
- [x] [Review][Patch] `runbook.md` Scenario 4 Symptoms section lists the sweeper and settle-abort counters but omits `booking.payment.settle_conflict`, the metric covering "routes nobody has found yet" [docs/deployment/runbook.md]
- [x] [Review][Patch] `runbook.md` Scenario 4's diagnostic SQL has no caveat that `credit_debited`/`stripe_charged` are a reconciliation hint, not an accounting record, for batch bookings — that caveat currently lives only in `deferred-work.md` [docs/deployment/runbook.md]
- [x] [Review][Patch] `prune-backups.sh` sources `/opt/skillars/.env` unconditionally at the top under `set -euo pipefail`, before `DRY_RUN` is resolved — a missing/unreadable `.env` aborts with no `[prune-backups][error]`-style diagnostic, unlike every other failure branch [deploy/backup/prune-backups.sh:17]
- [x] [Review][Patch] `prune_snapshots()`'s HTTP status check (`tail -n1`/`head -n-1` split, `[ "$http_code" -ne 200 ] 2>/dev/null`) masks a curl transport failure (empty/non-numeric `http_code`) behind a suppressed arithmetic error instead of an explicit check [deploy/backup/prune-backups.sh:145-148]
- [x] [Review][Patch] `SNAPSHOT_RETENTION_DAYS` is not validated as numeric before being fed to `date -d "$X days ago"` — an operator typo has unverified fallback behavior on the deploy target's GNU date [deploy/backup/prune-backups.sh:158-159]
- [x] [Review][Patch] `docs/deployment/secrets-reference.md`'s `HCLOUD_TOKEN` row still says "used only by `volume-snapshot.sh`," now stale since `prune-backups.sh` also consumes it [docs/deployment/secrets-reference.md:48]

- [x] [Review][Defer] `BookingService.cancelBookingAsParent`'s locked read races a settle-side plain `UPDATE` with no lock-timeout hint — could hang instead of surfacing a clean 409 [src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:602-618] — deferred, architectural lock-timeout question spanning both cancel and settle paths, not a regression introduced by this diff
- [x] [Review][Defer] V94's `DROP CONSTRAINT`/`ADD CONSTRAINT` pair takes an `ACCESS EXCLUSIVE` lock and revalidates the CHECK against every existing row on the busiest settlement table [src/main/resources/db/migration/V94__booking_payment_capture_pending.sql] — deferred, acceptable at current UAT-stage table size; revisit with an online-migration strategy before this table is large in production
- [x] [Review][Defer] `reserveCapture`'s `REQUIRES_NEW` + `PESSIMISTIC_WRITE` opens a second pooled connection per attempted reservation, held up to the 5s `lock.timeout` under contention, with no pool-sizing discussion [src/main/java/com/softropic/skillars/platform/payment/service/BookingPaymentPersistenceService.java:73-105] — deferred, load-dependent and speculative without a concurrency/load test
- [x] [Review][Defer] `DisputeService`'s payment lookups remain a bare, status-unguarded `findById`; correctness depends on an invariant enforced entirely in `BookingStateMachine` — deferred, pre-existing code not touched by this diff
- [x] [Review][Defer] `prune_snapshots()` hardcodes `per_page=50` on the Hetzner images list call with no pagination loop, unlike the sibling S3 routine which explicitly paginates [deploy/backup/prune-backups.sh:150] — deferred, low likelihood while volume-snapshot.sh remains broken and produces zero real snapshots (see D1)
- [x] [Review][Defer] `BookingServiceTest` still constructs `BookingService` positionally, grown by one more parameter in this diff — the project's own tracking docs already flag this as a recurring compile-break risk [src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java] — deferred, test-hygiene debt already tracked elsewhere, not fixed by this story

**Resolution 2026-08-11 — all 13 patches applied.** Every finding was legitimate; none was rejected.
Full `mvn -o verify` re-run green after the patches. Notes on the four that were more than mechanical:

- **The sweeper TOCTOU (finding 1) was real, and closing it broke six tests — which is the evidence
  it was real.** `sweepOne` now re-reads the booking with `findByIdForUpdate`, the same lock
  `reserveCapture` takes, so the two serialise. `PaymentPendingSweeperTest`'s `stage()` helper stubbed
  the *unlocked* read, so six of seven tests failed the moment the lock landed: they had been
  asserting against a code path the sweeper no longer takes. Updated to stub the locked read.
- **A test written for this patch round was DELETED for failing its own mutation check.** I added
  `sweepRacingAReservation_neverOverwritesAGrantedReservation`, an IT racing the sweeper against a
  live reservation to prove the new lock. **It passed unchanged with the lock removed** — both
  threads start on one latch, but the sweeper first reads config and queries for stranded bookings
  while `reserveCapture` goes almost straight to its insert, so the reservation won every time by a
  wide margin and the correct outcome came from the payment-row check rather than the lock. It cost
  280 s and proved nothing: precisely the decorative-lock failure `deferred-13` and `deferred-15`
  both recorded. Replaced with a deterministic test of a guarantee that *is* reachable — after the
  sweep declines, a late settlement must refuse to reserve — mutation-verified (`expected "DECLINED"
  but was "PAYMENT_PENDING"`). **The lock itself is therefore justified by reasoning, not by a test**;
  its read-then-write window is microseconds wide and not reachable from this level. Recorded as D11.
- **Finding 2 was widened while fixing it.** A thrown `reserveCapture` is now caught, counted
  (`settle_aborted{reason="reservation_failed"}`) and logged, then the booking is *dropped from the
  batch and its siblings continue* — which also closes finding 4. Letting it escape would abandon
  every already-reserved sibling in `CAPTURE_PENDING`, each needing manual Stripe reconciliation, to
  punish one booking's failure.
- **The three new shell guards were executed, not just `shellcheck`ed.** `--dry_run` exits 2 and
  refuses; `BACKUP_RETENTION_DAYS=fourteen` exits 1 with a named error; a missing `.env` exits 1 with
  the `[prune-backups][error]` prefix. Finding 11 was the sharpest of the shell set: the
  `[ "$http_code" -ne 200 ] 2>/dev/null` idiom (copied from `volume-snapshot.sh`) **inverts** on a
  non-numeric status — the suppressed arithmetic error reads as "condition false" and the script
  proceeds as though it had received a 200. Now an explicit numeric check, at both call sites.

Dismissed as noise (3): `volume-snapshot.sh` still failing nightly with the cron unchanged (already the story's own D1, explicitly deliberate and loudly flagged — not a new finding); `reserveCapture` logging a missing booking row identically to an ordinary cancel race (working as intended — the event always carries a validated booking id); `prune_s3_dumps` skipping rather than failing on an unrecognised key name (intentional documented fail-safe — "never delete what we could not date," visible in the summary line's skipped count).

## Dev Notes

### Baseline

`HEAD` is `237b835` (Story UAT.2, PR #40). `src/test/java` holds **138 `*IT.java` sources**, of which
four are abstract bases (`AbstractIntegrationTest`, `BasePaymentIT`, `BaseVideoIT`, `BaseStorageIT`,
`BaseSessionIT` — count them yourself, that list is from a `find` and may be stale) — expect roughly 134
concrete classes and one failsafe report each.

**Do not quote a test-count delta by subtracting totals from a CI run.** `uat-1` recorded that doing so
attributed another commit's deletions to itself; `uat-2` recorded a stale `ProbeIT` report with no
source file inflating the count. Both derived their real contribution by diffing per-file `@Test` counts
against `HEAD`. Do the same.

### Regression surface — the eleven files that touch this code

Triage all of these in Task 0. The first four are certain breaks; `BookingServiceTest` fails to compile.

| File | Why it breaks |
|---|---|
| `payment/service/CreditRoutingTest.java` | **Certain.** `@InjectMocks PaymentLifecycleService` — adding a constructor dependency or changing an existing one changes the wiring. `setUp:59-61` stubs `bookingPaymentRepository.existsById(BOOKING_ID) → false` for **every** test; AC3 replaces that call, so the stub becomes unused and Mockito's strict stubs fail the class with `UnnecessaryStubbingException` — not with an assertion error, which makes it look unrelated to your change. `caseB` (partial credit → Stripe) and every other `stripeAmount > 0` case additionally needs `reserveCapture` stubbed to `RESERVED`, or the settle silently aborts and the `verify(persistenceService).persistPaymentSuccess(...)` fails. |
| `payment/service/PaymentPendingSweeperTest.java` | **Certain.** `creditFundedStrandedBooking_isReportedNotDeclined:128` asserts the exact behaviour AC5 removes — it must **invert** to "declined", not be deleted. `packFundedBookingWithExistingPaymentRow_isReportedNotDeclined:140` stubs `existsById`, which becomes `findById` and now needs a status. Built by hand rather than `@InjectMocks` (`:48`), so the constructor arg list is written out explicitly. |
| `payment/service/PaymentPendingSweeperIT.java` | **Certain.** Its four cases *are* the sweeper's contract, and its class Javadoc says cases (c) and (d) exist to assert the **absence** of a transition. Case (c) (credit-funded) inverts; a fifth case for `CAPTURE_PENDING` is the natural home for AC5's new branch. |
| `booking/service/BookingServiceTest.java` | **Certain — this one fails to compile.** `:94-97` **hand-constructs** `new BookingService(...)` with a positional argument list rather than using `@InjectMocks`, so AC2's new constructor parameter breaks the build immediately. Then three `cancelBookingAsParent` tests need attention — **but not the same attention**; see the note directly below this table before touching them. `@Mock` list at `:66-81`. |
| `payment/service/ExpiredPackBookingValidationTest.java` | Uses `@InjectMocks BookingService` (`:71`), so it compiles fine and Mockito injects **null** for the new dependency. It only exercises `createBookingRequest`, so it will pass — silently, with a null field. Add the `@Mock` anyway; this is the twelfth-file class of miss `uat-2` recorded. |
| `payment/service/BatchPaymentIT.java` | Invokes the batch listener directly with no surrounding transaction (see `PaymentLifecycleService:121-126`). The reserve step adds `REQUIRES_NEW` transactions in a context that has none — the most likely place for an AC3 mis-wiring to surface. |
| `booking/service/BatchAcceptPaymentIT.java` | End-to-end accept → settle. Note its teardown is the one `deferred-12` D5 flagged for unscoped `DELETE` + `session_replication_role` without `try`/`finally` — **do not fix that here**, it is a suite-wide item. |
| `booking/api/BookingBatchResourceIT.java` | Asserts `booking_payments` rows after a batch accept. Row counts may now include reserved rows depending on where the test asserts. |
| `payment/service/PaymentWebhookIdempotencyIT.java` | Touches `booking_payments`; confirm it does not assert "exactly N rows". |
| `payment/service/ReceiptOwnershipIT.java` | AC1's receipt guard changes a 200 to a 404 for any non-`CAPTURED` row. Check whether its fixtures insert a status at all. |
| `payment/service/RevenueReportingServiceTest.java` | Mocks `findById` for the receipt methods; the guard adds a status condition. |

**`BookingServiceTest`'s three cancel tests: only ONE of them gets a `bookingPaymentRepository` stub.**
AC2's payment-row lookup sits behind `statusBeforeCancel == BookingStatus.PAYMENT_PENDING &&`, and Java
`&&` short-circuits — so `findById` executes for exactly one of the three fixtures. The class is
`@ExtendWith(MockitoExtension.class)` with no `@MockitoSettings` relaxing it (`:63`), i.e. **strict
stubs**, so stubbing it in the other two fails them with `UnnecessaryStubbingException` — the same
assertion-free, unrelated-looking failure flagged for `CreditRoutingTest` above.

| Test | Fixture status | `findByIdForUpdate` | `bookingPaymentRepository.findById` |
|---|---|---|---|
| `..._paymentPendingBooking_cancelsWithoutRefundEligibility:476` | `PAYMENT_PENDING` | yes | **yes** — stub it to `Optional.empty()`, or the test now takes the 409 branch and fails |
| `..._acceptedBatchBooking_cancelsWithoutRefundEligibility:500` | `ACCEPTED` | yes | **no** — never reached |
| `..._confirmedBookingMoreThan24hOut_staysRefundEligible:521` | `CONFIRMED` | yes | **no** — never reached |

All three keep their existing `bookingRepository.findById` stub as well: AC2 does the unlocked read
*and* the locked re-read, so both methods are called.

Also grep for hand-written `INSERT INTO payment.booking_payments` in fixture SQL — any row without an
explicit `status` now hits the widened `CHECK` rather than the old one, and any row relying on the old
four-value list is fine, but a fixture asserting the constraint's exact definition is not.

### Architecture and pattern compliance

- **Module boundary.** `platform.booking` → `platform.payment.repo` is already established in the exact
  class being changed (`BookingService:41-42`), so AC2's injection is consistent, not a new coupling.
  The reverse direction (`platform.payment` → `platform.booking`) is likewise already everywhere in
  `PaymentLifecycleService`. Do not introduce anything into `infrastructure.*` for this story.
- **Layering.** `reserveCapture` belongs on `BookingPaymentPersistenceService`, not on
  `PaymentLifecycleService`: that class exists precisely because `@Transactional` write logic was
  extracted out of the lifecycle service to escape Spring's self-invocation proxy bypass
  (`CreditRoutingTest:39-41` documents the incident). A `REQUIRES_NEW` method called from
  `PaymentLifecycleService` on itself would be **silently ignored** — same bug, same class, second time.
- **Pessimistic lock idiom.** **Two** call sites carry the full stack —
  `CoachProfileRepository:28-34` and `BookingRescheduleRequestRepository:26-31`, the latter of which
  states it copied the former "including the bounded lock wait". Copy from either, hint included; the
  409 mapping downstream depends on the timeout actually firing.
  **`SessionPackPurchaseRepository:17-20` is a third `findByIdForUpdate` but is NOT the model to copy** —
  it has `@Lock` and `@Query` but **no `@QueryHints`**, so it blocks indefinitely under contention, and
  it carries an extra `@Transactional` the other two do not. Do not "harmonise" the three in this story;
  that is a separate change with its own blast radius. Named here only so you do not pick the wrong one
  of the three greps returns.
- **Lock-then-authorise.** `deferred-16` D2 is an open finding against taking a row lock before the
  authorisation check. AC2 is written to avoid repeating it. Do not "optimise away" the unlocked first
  read.
- **Meters.** Follow `PaymentPendingSweeper:70-71` for names and
  `Counter.builder(name).tag(...).register(meterRegistry).increment()` for construction.
- **No new REST endpoint** is added by this story, so no new `@PreAuthorize` decision arises.
- **DTOs** are unchanged; nothing here crosses the API boundary except the new 409, which flows through
  the existing `ResponseStatusException` handler.

### Reading the existing code — what must be preserved

Files marked UPDATE, with the behaviour that must survive:

- **`PaymentLifecycleService`** — both `REQUIRES_NEW` listeners and their comments (`:43-48`, `:121-126`)
  record two separate incidents (`deferred-12`): without `REQUIRES_NEW`, batch siblings are silently
  discarded at commit, and nested `@Transactional` calls join a completed transaction and lose their
  writes. `perBookingTx` (`:49-55`) exists for the same reason. **Change none of it.** AC3 adds a step
  before the charge and rewrites one loop's guard; it does not restructure the transaction model.
- **`BookingPaymentPersistenceService`** — `persistPaymentSuccess`'s contract is that the
  `BOOKING_DEDUCTION` ledger entry, the `BookingPayment` row and the `PAYMENT_CAPTURED` transition
  commit **atomically** (`:32-36`). AC3's write-back must not split that. `declineBatchBooking`'s
  `REQUIRES_NEW` and its Javadoc (`:140-147`) are equally deliberate.
- **`BookingService.cancelBookingAsParent`** — the refund whitelist (`:602-613`) is a `deferred-12` AC4
  fix with a nine-line comment explaining why it is a whitelist and not a blacklist. AC2 changes how
  `statusBeforeCancel` is *read*, not what is done with it.
- **`BookingStateMachine:30-38`** — the comment above the `PAYMENT_PENDING` map explains that
  `CANCEL_PARENT` from `PAYMENT_PENDING` is the parent's escape hatch from a booking whose payment never
  captured. **That transition stays legal.** AC2 refuses it only while a reservation is outstanding —
  the escape hatch is exactly what keeps a genuinely stranded booking cancellable.
- **`PaymentPendingSweeper`** — everything outside `sweepOne`'s funding-type branch and the class
  Javadoc.

### Testing requirements

Standards: JUnit 5, AssertJ `assertThat`, Mockito for unit tests, `@SpringBootTest` + Testcontainers via
`AbstractIntegrationTest` / `BasePaymentIT` for ITs, Instancio for generated data, Awaitility for async.

**`IntegrationTestConventionTest` will fail the build** if a new concrete `*IT` declares its own Spring
context, and it pins `EXPECTED_TEST_PROPERTY_SOURCE_COUNT = 5`. Extend `BasePaymentIT` (payment-side) or
`AbstractIntegrationTest` (booking-side); add no `@TestPropertySource`.

New coverage required:

1. **`reserveCapture`, unit** — one test per `CaptureReservation` outcome, including that
   `BOOKING_NOT_PENDING` and `CAPTURE_UNCONFIRMED` write **no** row.
2. **Cancel-vs-reserve, IT** — both orderings, against a real database.
   `BookingServiceConcurrencyIT` is the pattern to follow (`CountDownLatch`, `ExecutorService`,
   `AtomicReference` for the thrown exception) — **but not its `Thread.sleep(1500)`**, which
   `deferred-12` D1 records as a non-durable barrier that passes green with the fix removed. Use a real
   barrier, and assert on something only the lock can produce: for "cancel first", assert
   `paymentGateway.chargeAndCapture` was **never invoked**; for "reserve first", assert the cancel
   returned 409 *and* the booking ended `CONFIRMED`.
3. **Batch charge-failure declines the reserved set** — the `:198-202` inversion. Without this test the
   break ships silently, because every existing batch test asserts the success path.
4. **`isSettled` semantics** — a `CAPTURE_PENDING` row must **not** be treated as a duplicate delivery;
   assert `chargeAndCapture` is not called a second time and the ERROR counter increments.
5. **Sweeper, IT** — no row ⇒ declined for a **credit-funded** booking (the inversion);
   `CAPTURE_PENDING` row ⇒ reported, **not** declined, no transition, no new payment row.
6. **Revenue queries ignore reserved rows** — insert a `CAPTURE_PENDING` row and assert
   `sumGrossByCoachAndPeriod`, `countCapturedByCoachAndPeriod` and `findByCoachAndPeriod` are unchanged.
   The `captured_at IS NULL` exclusion is load-bearing and currently accidental; pin it.
7. **Mutation check (Task 6)** — deleting AC2's `CAPTURE_PENDING` branch must fail a **named** test.
   `uat-2` established the bar: the failing assertion must discriminate against the specific wrong
   behaviour, not merely against "something threw".

There is **no frontend test suite** in this project, and this story touches no `.vue` file — so unlike
`uat-1` and `uat-2`, nothing here is verified by code reading alone. AC6's shell scripts are the one
exception: they cannot be exercised without a live Hetzner account, so state plainly in the completion
notes which parts were run (`shellcheck`, `--dry-run` against a local S3-compatible endpoint if you have
one) and which were not.

### Previous story intelligence

From `skillars-uat-2` (`237b835`) and `skillars-uat-1` (`8a76652`) — both cost real time to the
following, all of which apply here:

- **A migration can break integration tests that never mention it.** `V92` broke 8 IT classes because
  fixtures wired authority FKs by literal id. `V94` only alters a `CHECK`, which is far safer — but grep
  fixture SQL for `booking_payments` before assuming so.
- **Verify the baseline, never assume it.** `uat-1` quoted a CI figure from two commits behind and had
  to correct the record.
- **Fix the fixture, not the check.** `uat-2` found `RescheduleServiceTest` built a "1 hour" range from
  two separate `Instant.now()` calls, making it 1 hour plus microseconds, and correctly fixed the
  fixture rather than loosening the new rule. Expect the same temptation when a settle test starts
  aborting because its booking is not in `PAYMENT_PENDING`.
- **`ConfigService` caches with a 5-minute TTL.** Not used by this story's ACs, but relevant if you add
  a config key — a changed value looks like a no-op for up to five minutes.
- **Prettier.** `uat-1` D5 records that most pre-existing frontend files fail `prettier --check` at
  `HEAD`, so the repo violates its own mandatory-Prettier rule and wants a dedicated sweep. This story
  touches no frontend file — keep it that way and the issue does not arise.
- **`deferred-19`'s `IntegrationTestConventionTest`** fails the build on any concrete `*IT` declaring its
  own context. Design new tests to be constructor-injectable or to reuse an existing base.

### Git intelligence

Last three commits: `237b835` (UAT.2 — session duration & booking slot integrity), `8a76652` (UAT.1 —
admin bootstrap & coach onboarding), `bf9c828` (docs/deferred). The two UAT stories established the
house pattern this story follows: one migration, a narrowly-scoped service change, a mutation check on
the guard, a ledger closure pass, and small ops fixes folded in rather than tracked separately.

`uat-2` added `SessionDurationResolver` to `platform.booking.service` and `sessionDurationResolver` to
`BookingService`'s constructor — the most recent precedent for adding a dependency to that class, and
for the accompanying `@Mock` in `BookingServiceTest`.

### Items examined and NOT folded in

Recorded so the next pass does not re-litigate them:

- **P0-2 — a player account cannot book anything.** Still a product decision ("register + browse" vs.
  player self-booking). Unchanged by this story; note that this story's changes are role-agnostic, so
  self-booking inherits them for free.
- **P0-4 — a coach cannot subscribe through the UI.** Still blocked on the `payment.stripe_customers`
  re-key (`StripeCustomer`'s `@Id` is `parent_id`). `deferred-11` D3 folds into it. **This story touches
  `payment.booking_payments` only and does not go near `stripe_customers`** — deliberately, so the two
  do not collide in one migration.
- **P1 #7 / #8 — the i18n pair.** `uat-1` and `uat-2` both deliberately left them: one sweep or none.
  Unchanged. Note the interaction `uat-2` recorded — booking error codes resolve in no bundle, and
  `deferred-18` D6 means `ApiAdvice` cannot select a non-English bundle anyway, so AC2's new
  `booking.paymentInProgress` reason string will surface as a generic toast. **Do not add a partial
  bundle fix here**; add the key to all four bundles only if you are also doing #8, which you are not.
- **`skillars-10-2` D1 — `AFTER_COMMIT` listener failure silently drops refunds.** P3, left alone by
  three prior audits as a platform-wide event-reliability concern needing an outbox. AC4 is deliberately
  **not** an attempt at it: AC4 adds observability to four specific call sites, it does not add retry,
  dead-lettering or an outbox. Do not scope-creep into one.
- **`deferred-12` D1, D4, D5** — the `Thread.sleep` barrier, `readStatusOrThrow` returning 404 for an
  unrecognised status, and the `BatchAcceptPaymentIT` teardown. All in files this story edits; all left
  alone. D1's lesson is *applied* (write a real barrier in the new test) without rewriting the old test,
  which is its own change with its own regression risk.
- **`deferred-15` D2/D3/D4** — the double `SELECT ... FOR UPDATE` idiom, `updatedAt` as the stranded
  clock, and the unlocked batch-status read. All pre-existing, all knowingly accepted, none made worse
  here. D3 in particular is worth re-reading: it assumes no write path touches a `PAYMENT_PENDING`
  booking outside settlement. **AC1's `reserveCapture` takes a lock but must write nothing to the
  booking row** — confirm it does not, or D3's speculative risk becomes live.
- **Everything in P3.** Unchanged.

### Project structure notes

New files: one migration, one shell script, plus test classes. Everything else is an edit to an existing
file. Packages follow `com.softropic.skillars.platform.{module}.{layer}` — `CaptureReservation` lives in
`platform.payment.service` beside the service that returns it, or in `platform.payment.contract` if you
prefer it addressable from `platform.booking`; **it is not needed there** (AC2 reads the row's status
string directly), so `service` is the tighter choice.

### References

- [Source: `_bmad-output/implementation-artifacts/uat-readiness-priorities.md#P1` — items 2 and 3;
  `#P2` — item 4]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: code review of
  skillars-deferred-12-booking-payment-review-integrity (2026-08-04)` D2; `## Deferred from:
  skillars-deferred-15 story creation (2026-08-05)` D1; `## Deferred from: code review of
  deploy-3-1-postgresql-backup-automation (2026-06-04)`]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/service/PaymentLifecycleService.java:59-234`]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/service/BookingPaymentPersistenceService.java:32-158`]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeper.java:34-173`]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/service/StripePaymentGateway.java:34-82`]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:131-150,571-626,722-737`]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/service/BookingStateMachine.java:30-38`]
- [Source: `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java:118-121,576-585`]
- [Source: `src/main/resources/db/migration/V62__session_payment_credit_wallet.sql:80-91`]
- [Source: `deploy/backup/pg-backup.sh`, `deploy/backup/volume-snapshot.sh`, `deploy/backup/install-crons.sh`,
  `deploy/backup/drill-log.md`]
- [Source: `docs/deployment/backup-restore.md`, `docs/deployment/secrets-reference.md:48-54`,
  `docs/deployment/runbook.md:56-70`]
- [Source: `_bmad-output/project-context.md` — module structure, testing rules, migration rules]

### Open questions for Mbah (do not block implementation)

1. **Retention windows.** AC6 defaults to 14 days of dumps (56 objects at the current 6-hourly cadence)
   and 7 daily snapshots. Both are env-var driven, so changing them later is a `.env` edit, not a code
   change. Say if you want different numbers.
2. **A stuck `CAPTURE_PENDING` blocks the parent's cancel indefinitely** until an operator resolves it.
   That is the safe default — the alternative lets a parent cancel a booking whose money may be at
   Stripe. Given there is no cancel UI today, the practical impact is zero; flag it if you disagree.
3. **P0-2 and P0-4 remain the only unclaimed items outside the i18n pair**, and both are product
   decisions. After this story, the only non-decision work left on the UAT list is P1 #7/#8.

## Dev Agent Record

### Agent Model Used

Claude Opus 5 (`claude-opus-5`), BMad `dev-story` workflow, 2026-08-11.

### Debug Log References

**Task 0 — regression-surface triage, decided before any code was written.** The story listed eleven
files; the twelfth (`ExpiredPackBookingValidationTest`) it also flagged. Verdicts:

| File | Predicted | Actual |
|---|---|---|
| `CreditRoutingTest` | certain break | **Confirmed.** `setUp`'s `existsById` stub became unused; every `stripeAmount > 0` case needed `reserveCapture → RESERVED`; added `@Spy SimpleMeterRegistry` for the new constructor arg |
| `PaymentPendingSweeperTest` | certain break | **Confirmed.** Credit-funded case inverted to "declined"; `existsById` → `findById` with a status |
| `PaymentPendingSweeperIT` | certain break | **Confirmed.** Case (c) inverted, fifth case added for `CAPTURE_PENDING` |
| `BookingServiceTest` | fails to compile | **Confirmed.** Positional `new BookingService(...)` at `:94-97`; the short-circuit table in Dev Notes was exactly right — only the `PAYMENT_PENDING` fixture reaches the payment lookup |
| `ExpiredPackBookingValidationTest` | silent null | **Confirmed.** `@Mock` added; class still passes either way |
| `BatchPaymentIT` | most likely mis-wiring site | Passed unchanged — it seeds bookings at `PAYMENT_PENDING`, which is what the reservation needs |
| `BatchAcceptPaymentIT` | check | Passed unchanged (real accept path, bookings exist) |
| `BookingBatchResourceIT` | row counts | Passed unchanged — a reserved row is updated in place, so counts stay 1/booking |
| `PaymentWebhookIdempotencyIT` | "confirm it does not assert exactly N rows" | **BROKE, and worse than predicted — see Completion Note 3** |
| `ReceiptOwnershipIT` | receipt guard | Unaffected — both tests assert the 403 that fires *before* the payment lookup |
| `RevenueReportingServiceTest` | receipt guard | Unaffected — its receipt tests only cover wrong-owner 403s; no success-path `findById` stub exists |
| fixture SQL | grep `INSERT INTO payment.booking_payments` | Two Java files only (`PaymentPendingSweeperIT`, `AdminDisputeResolveIT`), both inserting `CAPTURED` — safe under the widened `CHECK`. No `.sql` resource fixtures touch the table |

**Task 0 — deadlock precondition, confirmed by reading, not assumed.** `onBatchBookingAccepted`
issues only plain `findById` reads (`:134,146,169,211`) and every write it makes to
`booking.bookings` goes through `perBookingTx` (`REQUIRES_NEW`, already committed) before the
reservation loop begins. `handleCreditBasedBooking` reaches its reservation after a wallet read
only. Neither caller holds a lock or an uncommitted write on the rows `reserveCapture` locks on its
second connection. Written as a comment at both call sites.

**Mutation checks — both performed, both discriminating.**

1. *AC2 interlock.* Deleting the `CAPTURE_PENDING` branch fails
   `BookingServiceTest#cancelBookingAsParent_captureInFlight_refuses409AndLeavesBookingPending`.
   **The first version of this test did not meet the bar and was rewritten.** It initially failed
   with `Expecting actual throwable to be an instance of ResponseStatusException but was
   ResourceNotFoundException: Coach pricing not found` — i.e. it only proved "something threw", the
   exact weakness `uat-2` recorded. The downstream cancel path is now stubbed `lenient()` so the
   mutation lets the cancel run to completion, and the failure is
   **`Expecting code to raise a throwable`** — the cancel *succeeds*, which is the specific wrong
   behaviour. Restored and re-verified green.
2. *AC3 batch decline inversion (`:198-202`).* Restoring the pre-fix `!existsById` condition fails
   `CaptureReservationIT#batchChargeFails_everyReservedBookingIsDeclined` with
   **`expected: "DECLINED" but was: "PAYMENT_PENDING"`** — the whole batch strands, exactly as the
   story predicted. Restored and re-verified green.

**Hetzner API verification (AC6, required before writing the snapshot half).** Checked
`GET /v1/images?type=snapshot` + `DELETE /v1/images/{id}` and, in doing so, found that the endpoint
`volume-snapshot.sh` calls does not exist — see Completion Note 2.

### Completion Notes List

**0. REVIEW ROUND (2026-08-11, after the notes below).** All **13** review patches applied; none
rejected — every finding was legitimate and several were sharp. 8 items deferred (D11–D18 in
`deferred-work.md`), 3 dismissed as noise by the review itself. The four consequential outcomes:

- ✅ Resolved review finding [High]: the sweeper could overwrite a granted reservation. `sweepOne`
  now re-reads under `findByIdForUpdate` — the same lock `reserveCapture` takes, so the two
  serialise. The bug was real: `save()` on an assigned `@Id` with no `@Version` is a `merge()`, so
  an unlocked sweeper issued an UPDATE over the `CAPTURE_PENDING` row rather than a failing INSERT.
  Six of seven `PaymentPendingSweeperTest` cases broke on the fix because `stage()` stubbed the
  *unlocked* read — they had been asserting a path the sweeper no longer takes.
- ⚠️ **I wrote a test for that lock and then deleted it, because it failed its own mutation check.**
  `sweepRacingAReservation_neverOverwritesAGrantedReservation` passed unchanged with the lock
  removed. Both threads release on one latch, but the sweeper first reads config and runs the
  stranded-booking query while `reserveCapture` goes almost straight to its insert — so the
  reservation won every time and the correct outcome came from the payment-row check, not the lock.
  280 s of runtime proving nothing, which is the exact decorative-lock failure `deferred-13` and
  `deferred-15` each recorded. Replaced with a deterministic, mutation-verified test of a guarantee
  that *is* reachable (after the sweep declines, a late settlement must refuse to reserve —
  `expected "DECLINED" but was "PAYMENT_PENDING"`). **The lock is therefore justified by reasoning,
  not by a test**; recorded honestly as D11 rather than left to read as proven.
- ✅ Resolved review findings [Med] ×2 together: a thrown `reserveCapture` is now caught, counted
  (`settle_aborted{reason="reservation_failed"}`) and logged, and on the batch path that booking is
  dropped while its siblings continue. Letting it escape would strand every already-reserved sibling
  in `CAPTURE_PENDING` — each needing manual Stripe reconciliation — to punish one booking's failure.
- ✅ Resolved review finding [Med], and it was the sharpest of the shell set: the
  `[ "$http_code" -ne 200 ] 2>/dev/null` idiom **inverts** on a non-numeric status — the suppressed
  arithmetic error reads as "false" and the script continues as though it got a 200. Now an explicit
  numeric check at both call sites. All three new shell guards were **executed**, not just
  `shellcheck`ed: `--dry_run` → exit 2 refusing; `BACKUP_RETENTION_DAYS=fourteen` → exit 1 named;
  missing `.env` → exit 1 with the proper log prefix.

**A post-patch full run failed and was diagnosed, not waved away.** `mvn -o verify` came back with 6
errors, all in `MessageModerationSweeperIT` and all one root cause:
`FileNotFoundException: class path resource [.../MessageModerationSweeperIT.class] cannot be opened
because it does not exist` — a **missing class file**, not an assertion. Three things placed it
outside this diff: the class touches no payment, booking or sweeper code; it was green 6/6 in the
pre-review full run; and the `.class` file was present on disk afterwards. Confirmed by re-running
the class in isolation — **6/6, BUILD SUCCESS**. The run also took 47:54 against 24:01 for the same
suite pre-review, consistent with machine contention from overlapping mutation-check builds writing
the same `target/`. Because the root cause was a stale/torn build artifact, the final verification
was re-run as `mvn -o clean verify` rather than trusting the incremental tree — "probably flaky" is
the reasoning that ships a real break, so it was proven instead of assumed.

Also from the round: `CAPTURE_PENDING` was collapsed from four independent string literals into
`platform.payment.contract.BookingPaymentStatus` (with `isTerminal`), `transitionOrReport` now also
catches non-`BookingStateTransitionException` failures under a second counter
(`booking.payment.settle_error`), and `CaptureReservationIT`'s concurrency test no longer overclaims
in its docstring or swallow the settle thread's throwable.

**1. All seven ACs implemented. Full `mvn -o verify` BUILD SUCCESS (24:01): 860 unit + 874 IT,
0 failures, 0 errors, 1+4 skipped.**

**Contribution derived by diffing `@Test` counts against `HEAD`, never by subtracting totals** (the
`uat-1`/`uat-2` lesson): `@Test` in `src/test/**/*Test.java` went **762 → 774 (+12 unit)** and in
`src/test/**/*IT.java` **885 → 892 (+7 IT)**. Each figure was then confirmed against this build's own
per-class reports: BookingServiceTest 23→26, CreditRoutingTest 6→8, PaymentPendingSweeperTest 6→7,
CaptureReservationTest 6 (new), PaymentPendingSweeperIT 6→7, CaptureReservationIT 6 (new).

**Do not reconcile 860/874 against the `823 unit + 863 IT` figure `uat-2` recorded** — the two are
counted on different bases (this tree has 774 `@Test` annotations in unit files but runs 860 tests;
five files use `@ParameterizedTest`). The *delta* is the measured quantity and it is +12/+7. This is
precisely the trap the story's "Baseline" note warns about, met from the other direction.

**No IT class silently stopped running.** 139 `*IT.java` sources − 4 abstract bases (`BaseVideoIT`,
`BasePaymentIT`, `BaseStorageIT`, `BaseSessionIT`) = **135 concrete**, against **136** failsafe `.txt`
reports. The extra is the stale `ProbeIT` report `uat-2` also recorded — timestamped **Aug 10 15:15**,
a day before this run's 13:03, with no source file. Per-class report sum 875 = console 874 + that one
stale file, which confirms both the staleness and that 874 is the true run count. Correction to the
story's Dev Notes: it listed **five** abstract bases including `AbstractIntegrationTest`, but that
file ends in `Test.java`, not `IT.java`, so it was never in the `*IT.java` count — the real figure is
four.

**2. AC6 surfaced a live ops defect far larger than the AC itself, and it is the most important thing
in this story.** The AC required verifying the Hetzner list/delete endpoints against current API docs
before writing the pruner. Doing so established that **the Hetzner Cloud API has no volume snapshot
at all** — verified against four independent sources: the generated Python SDK exposes only
`attach`/`detach`/`resize`/`change_protection`/`create`/`delete`/`update` on `VolumesClient`; the API
changelog never mentions volume snapshots; Pulumi's `hcloud.Snapshot` requires a `ServerId`; and
Hetzner's own guidance states server snapshots exclude attached volumes and a native snapshot of an
external volume cannot be created.

`volume-snapshot.sh` POSTs to `/v1/volumes/{id}/actions/create_snapshot`, which 404s, so it has
failed on every cron run since `deploy-3-1` and **no snapshot has ever existed**. Consequence: the
Hetzner Volume at `/opt/skillars/data` — postgres data, redis AOF, prometheus, loki, grafana, tempo,
`acme.json` — **has no backup**, and `restore-from-snapshot.sh` plus Section B of
`backup-restore.md` have nothing to restore from. The Object Storage `pg_dump` stream is unaffected
and is the only working backup, of the database only. Nothing in the repo contradicted this because
`drill-log.md` holds only its placeholder row: no restore drill has ever been run, which is exactly
what would have caught it.

**Deliberately not fixed here.** Choosing the replacement — file-level backup of the volume to Object
Storage, a Hetzner Storage Box, or an explicit decision that dumps-only is acceptable and Section B
is deleted — is an operational decision and a deployment story, not a payment one. Recorded as
`uat-3` D1, ranked **P1 #9** in `uat-readiness-priorities.md` (above P2 deliberately: this is a live
data-loss exposure, not a deployment inconvenience), and warned loudly in `volume-snapshot.sh`,
`backup-restore.md` and `runbook.md`. `prune-backups.sh`'s snapshot half is written against the real
images API so it is correct for any snapshot that does exist, and it emits `[warn]` on a zero match
rather than exiting quietly — because zero is precisely what a broken producer looks like. **Mbah
needs to decide this one.**

**3. `PaymentWebhookIdempotencyIT` broke harder than the story predicted, and the failure mode
generalises.** The story asked only whether it asserts "exactly N rows". In fact it seeds **no
`booking.bookings` rows at all** — it declares `@MockitoBean BookingService` specifically so
transitions never need one — so `reserveCapture`'s locked read found nothing, returned
`BOOKING_NOT_PENDING`, and the test's "exactly one BookingPayment" assertion failed with zero rows.
Fixed by seeding a real `PAYMENT_PENDING` booking in the one test that reaches Stripe (the fixture,
not the check — `uat-2`'s recorded lesson). The other two tests in the class pass untouched only
because neither reaches Stripe: full credit cover and pack-funded both skip the reservation.
**Generalisation, recorded as D7: any test driving a settlement path now needs a real booking row,
which was not true before this story.**

**4. Three deliberate choices worth flagging to a reviewer.**

- *`abortSettle`'s severities and the no-recharge rule.* `CAPTURE_UNCONFIRMED` logs ERROR and never
  re-charges: a duplicate delivery landing on a reserved row means the prior attempt's outcome at
  Stripe is unknown, and charging again risks double-charging a parent. The booking stays
  `PAYMENT_PENDING` for the sweeper to escalate.
- *`onBookingAccepted` gained a second guard, not just a status-aware first one.* `isSettled` handles
  the duplicate-delivery case exactly as before; a new `hasReservation` branch routes an outstanding
  reservation to `abortSettle` instead. Folding both into one predicate would have made a
  died-mid-capture booking indistinguishable from a duplicate and silently skipped it.
- *The reserved row's amounts are an upper bound, not an accounting record* (D8). On the batch path a
  booking's credit/Stripe split is not known until the wallet is read for the batch as a whole, so
  the reservation carries `creditDebited = 0, stripeCharged = price` and the settle write-back
  corrects both. Sound because only `CAPTURED` rows are ever summed — pinned by
  `CaptureReservationIT#capturePendingRowIsInvisibleToEveryRevenueQuery`, which asserts all five
  revenue queries are unmoved by a reserved row. An operator reading a stuck row via runbook
  Scenario 4 should know the figures are an upper bound on the Stripe leg.

**5. `CaptureReservationIT` forks one additional Spring context** via `@MockitoSpyBean PaymentGateway`,
which is the only way to assert "Stripe was never reached". `IntegrationTestConventionTest` passes
(it governs `@SpringBootTest`/`@ActiveProfiles`/`@Import`/`@TestPropertySource`, and the pinned
`EXPECTED_TEST_PROPERTY_SOURCE_COUNT = 5` is unchanged), but recording the cost explicitly is in the
spirit of `deferred-19`. The batch-decline test's `doThrow` is safe across tests because
`@MockitoSpyBean` defaults to `MockReset.AFTER`.

**6. What was NOT verified.** `prune-backups.sh` passes `shellcheck` (as do all three siblings, which
were re-checked) but **neither half has been executed** — the S3 half needs live Hetzner Object
Storage credentials and the snapshot half a live `HCLOUD_TOKEN`, neither of which exists here. The
first production run must be `--dry-run`; that instruction is in `backup-restore.md`. The script also
newly requires `jq`, added to `provision.sh`'s apt list — **an already-provisioned Node will not have
it**, so run `apt-get install -y jq` before installing the cron; the snapshot half hard-fails with a
clear message rather than skipping silently. Recorded as D10. This story touches **no** `.vue` file,
so unlike `uat-1` and `uat-2` there is no frontend behaviour resting on code reading alone.

**7. Ledger and priorities (AC7).** Closed with dated in-place notes rather than deletion, per the
`deferred-13`/`-14`/`-16`/`uat-1`/`uat-2` convention: `deferred-12` D2, `deferred-15` story-creation
D1 (each of its three named prerequisites confirmed shipped before closing), and the `deploy-3-1`
retention row. Ten new items recorded (D1–D10). In `uat-readiness-priorities.md`: story-claims row
marked IMPLEMENTED, P1 #2's severity correction retained, new **P1 #9** added for the volume-backup
finding, P2 #4 marked implemented with a correction to its own wording (it claimed
`volume-snapshot.sh` "adds one snapshot a day" — it adds none), and the suggested sequence renumbered
to put the volume-backup decision at #8.

### File List

**New — production**
- `src/main/resources/db/migration/V94__booking_payment_capture_pending.sql`
- `src/main/java/com/softropic/skillars/platform/payment/service/CaptureReservation.java`
- `deploy/backup/prune-backups.sh`

**New — tests**
- `src/test/java/com/softropic/skillars/platform/payment/service/CaptureReservationTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/CaptureReservationIT.java`

**Modified — production**
- `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/BookingPaymentPersistenceService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/PaymentLifecycleService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeper.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/RevenueReportingService.java`

**Modified — tests**
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/CreditRoutingTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/ExpiredPackBookingValidationTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeperTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeperIT.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/PaymentWebhookIdempotencyIT.java`

**Modified — ops and docs**
- `deploy/backup/install-crons.sh`
- `deploy/backup/volume-snapshot.sh`
- `deploy/provision.sh`
- `docs/deployment/backup-restore.md`
- `docs/deployment/runbook.md`
- `docs/deployment/secrets-reference.md`

**Modified — artifacts**
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/uat-readiness-priorities.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/skillars-uat-3-payment-capture-integrity-and-backup-retention.md`

### Change Log

| Date | Change |
|---|---|
| 2026-08-11 | AC1 — `V94` widens `chk_bp_status` with `CAPTURE_PENDING`; `reserveCapture` (`REQUIRES_NEW`) + `CaptureReservation`; `BookingRepository.findByIdForUpdate` with bounded 5 s lock wait; receipt paths guarded on `status = CAPTURED` |
| 2026-08-11 | AC2 — `cancelBookingAsParent` reworked to unlocked read → ownership → locked re-read → 409 `booking.paymentInProgress` while a reservation stands; mutation-verified |
| 2026-08-11 | AC3 — both Stripe call sites reserve before charging; write-back helper across all five `booking_payments` writers; batch reserve-then-price with subtotal from `reserved`; `:198-202` decline loop rewritten (live break, mutation-verified); four `existsById` guards replaced by `isSettled`/`hasReservation` |
| 2026-08-11 | AC4 — all five settle-side `transition(...)` calls wrapped by one helper: ERROR log with the status read from, `booking.payment.settle_conflict` counter, rethrow |
| 2026-08-11 | AC5 — sweeper decides on the payment row, not the funding type; `CAPTURE_UNCONFIRMED` reason; class Javadoc rewritten so it no longer argues against its own code; runbook Scenario 4 added |
| 2026-08-11 | AC6 — `prune-backups.sh` (both streams, min-keep floor, dry-run, key-derived ages, fatal on empty listing); cron entry; `jq` provisioned; retention documented in three files. **Found and recorded: Hetzner Cloud has no volume snapshot, so `/opt/skillars/data` has no working backup** |
| 2026-08-11 | AC7 — three ledger closures, ten new deferred items, priorities doc updated incl. new P1 #9 |
| 2026-08-11 | Full `mvn -o verify` BUILD SUCCESS (24:01): 860 unit + 874 IT, 0F/0E. Status → review |
