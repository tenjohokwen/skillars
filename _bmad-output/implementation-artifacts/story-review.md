# Story Audit: skillars-deferred-136-gdpr-datasource-retry-packsession-lock-fix

**Audit Date:** 2026-09-25  
**Auditor:** Senior dev review  
**Re-audit (2026-09-25, second pass):** Both of the original review's headline findings (AC1 "missing pre-spike task", AC3 "critical caveat incomplete") were independently re-verified against the actual story text and source (`BookingService.java`, `BookingStateMachine.java`) and turned out to be **false positives / overstated**. See the ⚠️→✅ corrections inline below. All other findings (AC2, AC4, AC5, AC6, every file:line citation table) were independently re-checked and hold up.
**Status:** All citations verified accurate. No real blockers. Story is ready for dev as written.

---

## Summary

The story file is well-researched, technically sound, and properly scoped. All file references, line numbers, and story precedents were verified against `HEAD` (dd5ef063 post-deferred-135, plus the story-creation commit `c26abaa7`). **No correctness bugs found, and on re-audit, no real blockers either.** The original review's two "critical" findings do not survive independent verification:
- The AC1 "missing pre-spike task" claim is wrong — the task already exists as the first sentence of Task 1 in the Tasks section (just not broken out as a separate "1a" line item).
- The AC3 "critical caveat incomplete" claim is overstated — `BookingService.transition()` was read in full for this re-audit; it acquires a pessimistic lock on exactly one table (`booking`, single row) and calls into `BookingStateMachine`, which is pure in-memory logic with zero DB access (no `Repository`/`EntityManager`/`@Transactional` in that class). There is no hidden multi-table locking to discover.

All test file citations verified as accurate.

---

## Detailed Findings

### ✅ AC1: Dedicated HikariDataSource — File Citations & Assumptions

**All File Citations Verified:**
- `DataSourceConfig.java:24-39` — Primary `dataSource`/`hikariConfig` beans with `@ConditionalOnProperty` ✓
- `GdprErasureService.java:122` — `private TransactionTemplate requiresNewTemplate;` ✓
- `GdprErasureService.java:158-162` — `initTemplates()` verified ✓
- `GdprErasureService.java:733-758` — `assertConnectionPoolNotSaturated` full method verified ✓
- `GdprErasureService.java:885-961` — `deletePlayerDevelopmentData` with pre-check at line 889 ✓
- `TestConfig.java:44-64` — `JdbcConnectionDetails` bean and `SharedContainers.postgres()` reuse verified ✓

**Critical Open Question — Verified Correctly:**
- `@EnableJpaRepositories` absence: Grep confirms it does NOT exist in codebase ✓
- Story correctly identifies that second `EntityManagerFactory` is likely NOT required ✓

**✅ ORIGINAL "FALSE ASSUMPTION" FINDING — CORRECTED, WAS A FALSE POSITIVE:**
- **Original claim:** AC1 assumes a `JpaTransactionManager` can back a `TransactionTemplate` sharing an existing `EntityManagerFactory` "without binding at the JDBC level in a way that conflicts" (lines 128-132), and that "no pre-implementation spike task is explicitly added to the task list."
- **Re-verification result:** The premise ("story flags this as genuinely open, correctly") was right, but the conclusion was wrong. The story's **Tasks** section (story line 428) reads: *"1. **AC1:** Resolve the open `EntityManagerFactory`-sharing question empirically first (see AC1's own design note). Add the dedicated `HikariDataSource`/`PlatformTransactionManager` pair..."* — this already IS the pre-spike requirement, sequenced first within Task 1. The Dev Notes section (story line 455) reinforces it again: *"must be resolved with a real spike/read of Spring's `JpaTransactionManager`/`LocalContainerEntityManagerFactoryBean` docs... before writing the fix, not assumed to 'just work'."*
- **Risk:** NONE — nothing is missing. The recommendation to split it into a separate "Task 1a" is a pure readability/optics preference, not a substantive gap. Not worth a story edit.

**Design Tradeoffs Correctly Noted:** TOCTOU gap acceptance, pool sizing, why removing pre-check is not an option ✓

---

### ✅ AC2: Scheduled GDPR Auto-Retry — Precedents Verified, Schema Decision Correctly Left Open

**Verified Precedents:**
- `EmailRetryScheduler.java:54-62` — `MAX_RETRY_ATTEMPTS = 6` and attempts-tracking shape ✓
- `StripeSubscriptionReconciliationScheduler.java:21-36` — Real arithmetic sizing documentation ✓

**Current State Verified:**
- `GdprRequest.java:21-46` — No `updatedAt`, `failedAt`, or `retry_count` column; migration needed ✓
- `GdprRequestRepository.java` — `findByStatus(String, Pageable)` exists ✓
- `GdprRequestService.requestErasure:81-97` — Manual resubmit creates NEW row (dedup guard rationale correct) ✓

**Missed Flow — Blob Re-Enqueue Idempotency Not Explicitly Documented:**
- Story claims re-drive "makes genuine forward progress with no data left unrecoverable" (line 186). Verified: `deletePlayerDevelopmentData` performs 12 fixed delete statements (all idempotent to re-run) and blob-enqueue inside the REQUIRES_NEW transaction.
- **Gap:** The code comment at line 849 says "no dedup, so a repeated key across reports would count more than once, though that is not expected in practice" — but if a prior `erase()` call completed this method and failed later, a re-drive will re-enqueue the same keys. This is NOT a bug (the outbox can tolerate dupes), but it should be noted explicitly in implementation as: "blob-enqueue inside deletePlayerDevelopmentData may create duplicate outbox rows on retry; this is acceptable per existing code design (line 849 comment)."
- **Verdict:** No idempotency bug, but the assumption that idempotency is "already solved" should be softened.

**Schema Decision Correctly Left Open:**
- Grace window: story leaves "failed_at column OR rely on createdAt + cadence" open (lines 178-180). Correct approach ✓
- Note: `createdAt` is `@Column(updatable=false)` set at construction, so by daily scheduler run it will be ~24h old, providing natural grace window without a new column.

---

### ⚠️ AC3: PackSessionService Lock-Scope & TOCTOU — Critical Caveat Incomplete

**All Method Locations Verified:**
- `PackSessionService.java:140-247` — `pausePack` method location ✓
- Lines 142-144, 195-196, 198-219, 220-222, 224-227 — All TOCTOU/under-confirmation issues verified ✓
- `BookingService.cancelDueToPause:665-685` — Correct location; does `transition()` with OptimisticLockingFailureException catch ✓

**D1 Issue (Missing Validation):** Correctly identified ✓

**D5/D8 Issues:** Correctly identified, legacy `SessionPackService` confirmed DELETED by Story 11.3 ✓

**✅ ORIGINAL "CRITICAL CAVEAT INCOMPLETE" FINDING — RE-VERIFIED, OVERSTATED:**
- **Original claim:** The story doesn't show `transition()`'s method body, so it might acquire locks on tables beyond `booking` (e.g. `booking_state_history`, audit tables, cascaded state machines), creating an undiscovered lock-ordering hazard.
- **Re-verification performed for this audit:** Read `BookingService.java:151-176` (`transition()`/`transitionInternal()`) and `BookingService.java:665-685` (`cancelDueToPause`) in full, plus grepped `BookingStateMachine.java` for `Repository|@Transactional|findBy|EntityManager` (zero matches — it's pure in-memory validation/lookup logic, no DB access at all).
  - `transitionInternal` acquires exactly one lock: `bookingRepository.findByIdForUpdate(bookingId)` + `entityManager.refresh(booking, LockModeType.PESSIMISTIC_WRITE)` — a single row on the single `booking` table. It then calls the DB-free `BookingStateMachine.validate`/`targetStatus`, `booking.setStatus(...)`, `bookingRepository.save(booking)` (same row, same table), and optionally publishes a Spring event (not a DB lock).
  - `cancelDueToPause` itself does one unlocked `getBookingOrThrow` read, calls `transition(...)`, then an unlocked `coachProfileRepository.findById` (no lock) after the transition returns.
  - **Conclusion: there is no hidden multi-table locking inside `transition()`.** The speculative hazard (locks on `booking_state_history`/audit tables/other state machines) does not exist in this codebase.
- **What the real question actually is:** the only lock-ordering question is `session_pack_purchases` (held by `pausePack` for the whole method today) vs. the single `booking` row lock acquired per-iteration inside the `cancelDueToPause` loop. That's exactly what the story's own caveat text already gestures at ("via `cancelDueToPause`'s own optimistic-locked `transition`") — it just doesn't spell out that `transition()` is single-table. This is a one-file, ~30-line read, not an open-ended investigation.
- **Recommendation:** No story edit needed. Optionally add one sentence to the AC3 caveat noting `transition()` is confirmed single-row/single-table so the implementer doesn't need to re-derive this, but it is not a blocker either way.

**Verdict:** Story correctly identifies the hazard and appropriately defers the final lock-scope decision to implementation; the "the investigation must be widened" framing in the original review overstated the actual risk. The investigation is trivial and, having now been done, does not change the AC's recommended fallback (leave D5's lock scope as-is if not cleanly safe).

---

### ✅ AC4: DatabaseResetTestExecutionListener — Constraint Correctly Identified

- `quiesceAsyncExecutors` method's Javadoc already rejected "let it propagate" as "strictly worse" ✓
- Story correctly identifies only the `atMost` bound can be raised ✓
- Note: Story correctly says this is test-infra-only and should be verified via targeted local run, not full mvn verify ✓

---

### ✅ AC5: Test Hygiene — All Hand-Built Entities Verified

| File | Line(s) | Entity | Found |
|------|---------|--------|-------|
| AdminReviewServiceTest.java | 57 | `new CoachReview()` | ✓ |
| ReviewFlagServiceTest.java | 96, 101 | `new CoachProfile()` (2×) | ✓ |
| ReviewFlagServiceTest.java | 124, 129 | `new CoachReview()` (2×) | ✓ |
| PastDueGracePeriodTest.java | 90 | `new CoachProfile()` | ✓ |
| PastDueGracePeriodTest.java | 164 | `new PaymentCoachSubscription()` | ✓ |
| PastDueGracePeriodTest.java | 173 | `new PaymentPlayerSubscription()` | ✓ |
| SubscriptionSchedulerIsolationTest.java | 100, 279, 317, 350, 370, 380, 390, 398, 406, 417 | Mixed (10 instances) | ✓ |

**Total:** 18 hand-built instances across 4 files — story count is accurate.

Instancio usage precedent verified to exist elsewhere in codebase ✓

---

### ✅ AC6: Ledger Hygiene — Six Items to Close (with one caveat on BookingPaymentPersistenceService lines)

**1. Hazard 2:** `deferred-work.md:3118-3121` with `[AUDIT 2026-09-23 (skillars-deferred-130)]` marker — verified ✓

**2. markFailed auto-retry:** Story leaves as "search for where deferred-133/135 documented this" — correct approach ✓

**3. D1, D5, D8:** Lines 1191, 1195, 1196 in deferred-work.md — verified ✓

**4. CI reset-quiesce:** Story correctly notes to search first (conservative approach) ✓

**5. Instancio hygiene:** Story correctly notes to search for the 4 file names together — correct approach ✓

**6. `acceptBooking` PAYMENT_CAPTURED claim — VERIFICATION RESULT:**
- ✓ `BookingService.acceptBooking` is at line 350 (method range ~350-414 as story indicates)
- ✓ Does NOT transition directly to `PAYMENT_CAPTURED`; instead calls `acceptAndInitiatePayment` (line 394)
- ✓ Returns `PAYMENT_PENDING` status per comment at line 409: "Return PAYMENT_PENDING status — PaymentLifecycleService handles CONFIRMED/DECLINED"
- ✓ `BookingPaymentPersistenceService.java` file EXISTS and is the correct location for PAYMENT_CAPTURED transitions
- ⚠️ **Line numbers 234, 279, 307 marked "re-verify at implementation time"** — this is appropriate conservatism. The file is real and contains PAYMENT_CAPTURED transitions (verified via grep), but story should provide search pattern for implementer: `grep -n "transitionOrReport.*PAYMENT_CAPTURED" BookingPaymentPersistenceService.java` instead of raw line numbers.

**Verdict:** Core claim (acceptBooking only does PAYMENT_PENDING, not PAYMENT_CAPTURED) is **CORRECT.** The ledger entry IS stale. Re-verification flag is appropriately conservative.

---

## Story-Level Metadata Verified

- Master at `dd5ef063` (deferred-135, PR #230) ✓
- "no 2026-09-25 ledger entries exist yet" ✓
- Four owner decisions documented via AskUserQuestion ✓
- Story names referenced: 128, 129, 130, 131, 132, 133, 134, 135, 11.1, 11.3, 7.2, 66, 125, 126 — all verified in git log ✓
- Explicit exclusions section correctly identifies previous-story work ✓

---

## Minor Corner Cases & Clarifications (Not Bugs)

### AC1: Spring Data Projection Query
- `performanceReportRepository.findStorageKeysByPlayerId(playerId)` at line 927 returns `List<String>` projection (not entities) — this is correct and intentional. Story doesn't call it out, but it's not a gap (code comment exists at line 926).

### AC2: Manual Resubmit + Scheduled Retry Racing
- Dedup guard prevents concurrent PENDING/PROCESSING rows for same userId, which blocks both manual resubmit AND scheduled retry from executing simultaneously. Story correctly documents this (lines 189-190). **Note for implementation:** Add test case verifying that manual resubmit attempt while scheduler is mid-drive correctly skips the manual attempt (because userId already has a PROCESSING row).

### AC3: Booking Cancellation Idempotency
- If `pausePack` is retried after some bookings are already cancelled, `cancelDueToPause` will throw `OptimisticLockingFailureException` (line 672-674) and convert to 409. This is safe but should be noted: "pausePack is NOT itself idempotent; ensure caller handles 409 gracefully without auto-retry."

### AC4: Test Timeout Verification
- Story correctly says verify via "targeted local run" not full mvn verify. **Implementation note:** Specify which test subset (e.g., all scheduler IT tests) should be run to verify reset-quiesce fix.

---

## Documentation Gaps (Not Errors)

| Item | Status |
|------|--------|
| ~~AC1 pre-spike task for EntityManagerFactory verification~~ | **Not a gap** — already present as Task 1's first sentence + restated in Dev Notes |
| ~~AC3 lock-ordering investigation scope~~ | **Not a gap** — re-verified: `transition()` is single-row/single-table; no widening needed |
| AC6 item 2 search pattern for markFailed | Marked as "search", could add grep pattern suggestion |
| AC6 item 6 search pattern for BookingPaymentPersistenceService | Lines marked "re-verify"; should suggest grep pattern |

---

## Recommendations for Implementation

1. **AC1:** Follow Task 1 as written — the EntityManagerFactory/dual-DataSource spike is already the required first step, no story change needed. Do NOT assume it works without the spike.

2. **AC3:** No additional investigation needed beyond what the story already scopes — `transition()` is confirmed single-row/single-table (re-verified this audit), so the lock-ordering question is exactly what the story's caveat already describes (`session_pack_purchases` vs. the per-iteration `booking` row lock). Decide D5 per the story's own disclosed-fallback language.

3. **AC2 SHOULD-DO:** Add test case for scheduled retry + manual resubmit racing on same userId.

4. **AC4 SHOULD-DO:** Specify which test subset verifies reset-quiesce timeout fix (scheduler tests recommended).

5. **AC6 SHOULD-DO:** Use search patterns (provided above) to locate exact line numbers for deferred-work.md items before drafting cleanup bullets.

---

## Conclusion

**Status: READY FOR DEV, NO BLOCKERS**

The story demonstrates high research quality (every citation re-verified against HEAD, four owner decisions documented, explicit exclusions noted). No correctness bugs found. All test file citations accurate. All major file paths verified.

**On re-audit, neither of the original review's "critical" findings held up:**
- AC1's "missing pre-spike task" was already in Task 1 — false positive, no story change needed.
- AC3's "critical caveat incomplete" was overstated — `transition()` was read in full this pass and confirmed single-row/single-table with no hidden multi-table locking; the story's existing caveat language already covers the real (and only) lock-ordering question.

**No blockers.** Proceed to dev in the order the Tasks section already specifies (AC1's spike first, per Task 1).
