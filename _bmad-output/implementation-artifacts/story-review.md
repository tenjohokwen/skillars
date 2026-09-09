# Story Review: skillars-deferred-106 (Completion-gated Coach Payout)

**Story:** `skillars-deferred-106-completion-gated-coach-payout.md`  
**Review Date:** 2026-09-09  
**Status:** ready-for-dev  
**Reviewer Assessment:** Comprehensive and well-structured. Seven findings requiring clarification or explicit test coverage during implementation. No blockers; all deferred decisions are pragmatic and documented.

---

## Findings Summary

| Severity | Count | Category |
|----------|-------|----------|
| 🔴 CRITICAL | 2 | Ambiguous AC semantics, idempotency logic needs clarification |
| 🟡 MAJOR | 5 | Deferred implementation decisions, edge case test coverage required |
| 🟠 MINOR | 1 | Documentation completeness |
| ✅ No-Op | 5 | Story adequately handles the concern |

---

## Critical Findings

### 🔴 Finding #1: AC10.4 dispute-within-hold-window semantics ambiguous

**AC Location:** AC10.4 — "a dispute raised **within** the hold window...mark the `coach_payouts` row `REVERSED`/`HOLD`"

**Issue:** The `coach_payouts` row is created by the handler (AC7.3: "On success the handler writes..."), which means it doesn't exist during the hold window (before the handler fires). The AC is ambiguous about how to mark a non-existent row:

**Three possible interpretations:**
1. Dispute-resolution directly inserts a `coach_payouts` row in REVERSED status → handler idempotency check sees it and no-ops.
2. Dispute-resolution deletes/cancels the outbox row so handler never runs → no payout row created.
3. Dispute-resolution enqueues a reversal message that no-ops if payout never transferred.

**Risk:** Implementation might choose the wrong approach, creating gaps:
- Double-transfer if reversal isn't properly queued.
- Orphaned outbox rows if approach #2 isn't atomic.
- Unrecoverable state if approach #3 has a race.

**Impact:** Race condition between payout and dispute; silent failures in edge cases.

**Action Required:** Before dev-story phase, clarify which approach is intended. Recommend documenting in AC10 and adding an explicit IT test: "dispute filed within hold window → verify payout is cancelled and no transfer occurs, and `coach_payouts` row correctly reflects cancellation or non-existence."

---

### 🔴 Finding #2: AC7.1 idempotency incomplete for non-RELEASED rows

**AC Location:** AC7.1 — "idempotent per booking id: it no-ops if a `RELEASED` row already exists"

**Issue:** The AC only checks for RELEASED rows, but AC8.2 allows HOLD rows (non-retryable failures) to exist. When the handler re-drives (e.g., after config change or manual re-trigger), it doesn't check for existing HOLD rows.

**Scenario:**
1. Handler runs, `Transfer.create()` fails with `invalid_destination` → writes HOLD row (AC8.2).
2. Coach reconnects Stripe account.
3. System re-enqueues the payout or re-drives the outbox.
4. Handler runs again. Does it check if a HOLD row exists? The AC doesn't say.

**Risk:** Handler might create a duplicate transfer attempt or overwrite the HOLD status with a new state.

**Impact:** Silent state corruption; duplicate transfers at Stripe level (prevented by idempotency key, but confusing for logs/metrics).

**Action Required:** During AC7 implementation, explicitly document: "handler checks if **any** row exists for the booking (regardless of status) and returns early if found." This applies to AC8.2's HOLD flow and any future re-trigger. Add a test: "handler called twice for same booking with HOLD status in between → verify no duplicate transfer created."

---

## Major Findings

### 🟡 Finding #3: Partial credit-funded bookings — exact split undefined (AC4.4)

**AC Location:** AC4.4 — "decide and document the exact split during impl and add a test"

**Issue:** For a $100 session with $40 credit-covered (so $60 `stripe_charged`), the story doesn't specify whether the coach gets:
- **Option A:** Net of $60 only (30% commission = $42 to coach)
- **Option B:** Pro-rata share of full net (net of $100 = $70 pro-rata to $60 = $42 to coach)
- **Option C:** Something else

Both options result in $42 in this example, but with different rates or splits they diverge significantly.

**Risk:** Silent underpayment or overpayment to coach; no error signal; difficult to detect in testing.

**Impact:** Coach revenue incorrect; platform overpays or underpays.

**Action Required:** During AC4 implementation, **decide and document explicitly** which approach is used. Add a comment in the code explaining the rationale. Add an integration test with multiple split ratios (e.g., 50/50 credit, 20/80 credit) to verify the split logic against the chosen option. Example test case: `$100 session, 30% commission, 50% credit-covered → coach gets [decided amount]`.

---

### 🟡 Finding #4: Stripe error classification for retryable vs non-retryable (AC8.1-8.2)

**AC Location:** AC8.1-8.2 — "retryable (network timeout, Stripe 5xx, rate-limit)" vs "non-retryable / needs a human"

**Issue:** The story lists error types (invalid destination, account closed, insufficient permissions) but provides no Stripe error code mapping. Stripe uses specific codes like `invalid_account`, `account_closed_or_restricted`, `resource_missing`, which the implementation must map to retry/hold decisions.

**Scenarios where mapping matters:**
- Stripe `rate_limit_error` (5xx) → should retry, but implementation might treat as non-retryable.
- `invalid_account_id` (destination account doesn't exist) → should HOLD, but might retry.
- `transfer_failed` (vague) → needs investigation to determine if retryable.

**Risk:** Payouts stuck in wrong state (HOLD when they should retry, or infinite retry loops).

**Impact:** Payouts fail to deliver; operator confusion about which runbook section applies.

**Action Required:** During AC8 implementation, create an explicit Stripe error code → retry/hold decision table in a comment or constant. Reference Stripe SDK docs and test with at least 5 different error codes (including one ambiguous case like `transfer_failed`). Example test: mock `createTransfer` with `rate_limit_error` → verify throws (retryable); mock with `invalid_account_id` → verify HOLD path (non-retryable).

---

### 🟡 Finding #5: Post-completion cancellation guard may be incomplete (AC9.3 verification)

**AC Location:** AC9.3 — "grep every caller of `BookingService.transition`...and confirm none can be reached for a `COMPLETED` booking"

**Issue:** This is framed as a verification task deferred to "during impl", but:
1. Only checks `BookingService` — other services might transition bookings (e.g., `AdminService`, `DisputeService`, `CoachService`).
2. It's a negative assertion ("confirm none can be reached") — easy to miss a path if you don't grep the entire codebase.
3. No assertion in the code itself to prevent future regressions.

**Scenario:** A future refactor adds a cancel path in a new service; no grep catches it; COMPLETED booking gets refunded while coach is being paid.

**Risk:** Coach double-paid (transfer + refund); logic integrity violation.

**Impact:** Financial error; requires manual reconciliation.

**Action Required:** During AC9 implementation, (1) expand grep to all of `src/main` for any booking state transition + cancel/refund entry points; (2) add an explicit assertion in `BookingService.cancel()` and `BookingService.recordNoShowCoach()` that throws `IllegalStateException` if called on COMPLETED booking (fail fast); (3) add an integration test: "attempt cancel/no-show via each endpoint for COMPLETED booking → verify rejection with correct error code." Document the grep result in the Dev Agent Record.

---

### 🟡 Finding #6: Coach account disconnect between booking and transfer (B.7.5 edge case)

**AC Location:** AC1.5, AC8.2, B.7.5 — "coach account invalid between event and transfer"

**Issue:** The story acknowledges this edge case (B.7.5: "coach disconnected their connected account between event and transfer") and routes it to HOLD + runbook. But doesn't explore operational frequency or UX impact:
- How often does this happen? (Metrics not defined.)
- User-facing message: "Why wasn't I paid?" — no clear guidance in AC12.4's copy changes.
- Operator runbook: asks coach to reconnect, but doesn't specify if it's automatic or manual re-trigger.

**Risk:** Operational overhead; poor UX for the edge case.

**Impact:** Coach frustration; support tickets; unclear runbook steps.

**Action Required:** During AC8.2 implementation, add:
1. A metric `coach.account.disconnect_between_booking_and_transfer` to track frequency (so we know if this is rare or common).
2. A WARNING log when account disconnect is detected in the handler (for operational awareness).
3. Documentation in AC14 runbook with expected copy to send to coaches: *"Your Stripe account disconnected before we could pay you. Please [reconnect at X]. Reply to this ticket when done, and we'll verify and retry your payment."*

---

### 🟡 Finding #7: `FAILED_PERMANENT` status mentioned but undefined (AC3.2)

**AC Location:** AC3.2 — "`FAILED_PERMANENT` (DLQ)"

**Issue:** The status is listed as a domain value, but no AC specifies:
- When a row transitions to FAILED_PERMANENT.
- Whether `[OUTBOX_STUCK]` (after 10 retries) triggers an automatic transition.
- Whether an operator can manually mark HOLD → FAILED_PERMANENT.
- Whether FAILED_PERMANENT is a terminal state with no further action.

**Scenarios:**
1. Retryable error occurs 10+ times → `[OUTBOX_STUCK]` logs, but row stays PENDING_RELEASE. When does it move to FAILED_PERMANENT?
2. Non-retryable error (account closed) → row is HOLD. After 30 days with no reconnect, should it auto-transition to FAILED_PERMANENT?

**Risk:** Payout rows accumulate in intermediate states; no clear end state for unrecoverable failures.

**Impact:** DLQ handling incomplete; no operator procedure for closure.

**Action Required:** During AC3/AC8 implementation, clarify:
1. Automatic transition: e.g., after `STUCK_ATTEMPTS_THRESHOLD`, automatically write FAILED_PERMANENT (or add a separate scheduler job).
2. Manual transition: document in AC14 runbook how an operator moves HOLD → FAILED_PERMANENT if the case is unrecoverable.
3. Add a test: outbox re-drives a FAILED_PERMANENT row → verify no-op and no new transfer attempted.

---

### 🟡 Finding #8: Commission rate immutable assumption (AC4.2)

**AC Location:** AC4.2 — "Compute the net using the **same** `platform.commission.rate`...so the coach's net is identical"

**Issue:** This assumes the commission rate doesn't change between booking and payout. But if the platform changes its rate mid-month, old bookings should use the old rate (captured at booking time), new bookings should use the new rate.

**Scenario:**
1. Commission rate is 30%.
2. Booking A created at 2026-09-01 (price $100, coach net = $70).
3. Rate changed to 25% on 2026-09-10.
4. Booking A session completes on 2026-09-15; handler computes net using current rate (25%) → coach gets $75 (overpaid by $5).

**Risk:** Coach overpaid or underpaid depending on when the rate changed.

**Impact:** Silent revenue error; difficult to audit.

**Action Required:** During AC4 implementation, confirm: the commission rate used for payout is captured from booking creation time, **not** completion time. Either (1) store `booking_prices.commission_rate` at booking time, or (2) pass the rate in the outbox payload (AC4.2 mentions "pre-computed `netAmount`", so the payload should carry amounts, not just the rate). Add a test: change commission rate mid-test; verify old booking uses old rate, new booking uses new rate.

---

### 🟠 Finding #9: Cutover documentation incomplete (AC11.3)

**AC Location:** AC11.3 — "the cutover reasoning must be written down in the story and the migration header"

**Issue:** AC11.3 says the "no production system" fact makes pragmatism acceptable, but doesn't specify what "written down" means. No example SQL comment block is provided. A future developer might not understand why a pragmatic approach was chosen or might over-engineer a production-safe solution.

**Risk:** Future confusion; possible unnecessary refactoring.

**Impact:** Technical debt; unclear decision rationale.

**Action Required:** During AC11 implementation, when writing the migration header, include an explicit SQL comment:
```sql
-- Cutover safety note: This migration [backfills coach_payouts RELEASED rows / adds a payout_model marker column] 
-- to avoid double-paying coaches when switching from destination-charge to separate-transfer model.
-- The Skillars project has no production system (see migration-conventions.md Grandfathering), so this pragmatic 
-- approach is acceptable. In a production system, a rolling-deploy-safe marker column would be required.
```

---

## No-Op Findings (Adequately Handled)

The following potential issues were considered but are acceptably addressed by the story:

### ✅ Revenue reporting during pending payout (AC12.2)
**Concern:** Completed sessions in PENDING_RELEASE might be invisible to coaches.  
**Story handles:** AC12.2 explicitly adds "pending release" figures to DTOs. ✓

### ✅ Refund after transfer succeeds but before Stripe settlement (AC8.3)
**Concern:** Coach withdraws balance; reversal fails.  
**Story handles:** AC8.3 covers reversal failure → HOLD/REVERSAL_FAILED + operator runbook. ✓

### ✅ Duplicate BookingCompletedEvent handling (AC7.2)
**Concern:** Two events → two transfers.  
**Story handles:** AC7.2 tests duplicate events; idempotency + partial unique index prevent double-transfer. ✓

### ✅ Query N+1 problem for revenue reporting (AC12)
**Concern:** Checking both `booking_payments` and `coach_payouts` for each booking.  
**Story handles:** AC12.1 moves entirely to `coach_payouts` RELEASED; single-table queries. ✓

### ✅ Stripe test-mode account provisioning (AC15.5)
**Concern:** No test account exists.  
**Story handles:** AC15.5 explicitly marks as PREREQUISITE; named task in Dev Agent Record. ✓

---

## Consistency Checks

| Check | Status | Notes |
|-------|--------|-------|
| All ACs numbered and structured | ✅ | AC0–AC15, clear hierarchy |
| No circular dependencies between ACs | ✅ | AC1 → AC2 → AC3 → AC4 flow is linear |
| Decision points clearly marked (D4–D6) | ✅ | Owner sign-off completed 2026-09-09 |
| Deferred decisions documented | ✅ | AC11 (cutover mechanism), AC8.3 (reversal status), AC4.4 (credit split) all noted |
| Test coverage mentioned for each AC | ✅ | AC13/AC15 outline unit, IT, and Stripe tests |
| Runbook sections tied to ACs | ✅ | AC14 references AC8.2 / AC10 / audit #1/#2 |
| Migration strategy clear | ✅ | V130/V131 + optional cutover migration per AC11 |

---

## Recommended Actions Before dev-story Phase

1. **Sync on Critical Findings #1 & #2** with architect/owner (15 min call to clarify AC10.4 + AC7.1 idempotency logic).
2. **Document Deferred Decisions** for findings #3–#9 as explicit checkpoints in the Dev Agent Record (these are pragmatic; just need to be decided and recorded).
3. **Verify AC9.3 Grep** plan — decide upfront whether to search all of `src/main` or just `BookingService`, and who will record the result.

---

## Verdict

**Ready for dev-story phase.** Story is comprehensive and well-thought-out. The nine findings are not bugs or missing requirements, but ambiguities and edge cases that will emerge during implementation. Critical findings #1 and #2 should be clarified in a brief sync; the rest are manageable as implementation decisions with explicit test coverage.

No false positives identified. All documented assumptions are reasonable given the "no production system" project state and the owner sign-off already completed (AC0).
