# Story Review — skillars-deferred-164

**Story:** `skillars-deferred-164-availability-window-dry-sweeper-lock-proof-ledger-type-safety-storage-bulk-delete-and-alert-hardening`
**Audit date:** 2026-10-27
**Real HEAD at audit:** `b5680880` — "Merge pull request #302 from tenjohokwen/skillars-deferred-163-config-recap-ci-cleanup"
**Method:** /mto-story-review — 4 parallel read-only verification layers, then an adversarial re-check (Step 4b) done in the main session by re-reading the cited source directly. All citations were checked against the HEAD above. The story's own "HEAD b5680880" claim happens to be correct, but it was re-checked rather than trusted.

---

## 1. Citation verification (Layer 1)

38 citations checked: 24 MATCH, 8 DRIFTED (line numbers only, meaning unchanged), 3 FALSE, 0 CANNOT_LOCATE.

| # | Citation (story wording) | Verdict | Evidence / corrected location |
|---|---|---|---|
| 1 | `PaymentPendingSweeper.sweepOne` `:141-188` | DRIFTED | Method spans **:141-198**. Lock at :155-156 `lockRetryer.withBoundedRetry("PaymentPendingSweeper.sweepOne", () -> bookingRepository.findByIdForUpdate(bookingId).orElse(null))` |
| 2 | `reserveCaptureTransactional` `:126`, PESSIMISTIC_WRITE, REQUIRES_NEW | MATCH | :125 `@Transactional(propagation = Propagation.REQUIRES_NEW)`, :128-129 same `findByIdForUpdate` under `lockRetryer` |
| 3 | "DEADLOCK CONSTRAINT" `PaymentLifecycleService.java:210,296` | MATCH | both comments present |
| 4-8 | 5 availability call sites (`BookingService:290`, `RescheduleService:223`/`:409`, `BookingDuplicationService:92`, `BookingBatchService:285`, doc `:270`) | MATCH | all at cited lines |
| 9 | `ParentCreditLedger.java:39` plain String | MATCH | `private String type;` |
| 10 | `CreditWalletService.java:32-33` holds CASH_OUT_DEBIT/STRIPE_FEE_DEBIT/**CASH_OUT_REVERSAL** | PARTIAL | :32-33 hold only `CASH_OUT_DEBIT`, `STRIPE_FEE_DEBIT`. `CASH_OUT_REVERSAL` is only at `CashOutService:56,58` |
| 11 | `CreditWalletRefundOutboxHandler.java:47` BOOKING_REFUND | MATCH | also :42 `existsByReferenceIdAndType(..., "BOOKING_REFUND")`, which the story doesn't mention |
| 12 | BPPS `:254,303`, PLS `:339,351` | DRIFTED (+1) | literals on :255/:304 and :340/:352 |
| 13 | "No DB `CHECK` constraint exists on this column" | **FALSE** | `V138__baseline_schema.sql:1823` `CONSTRAINT chk_ledger_type CHECK (((type)::text = ANY ((ARRAY['BOOKING_DEDUCTION', ...6 values])))`; also `chk_ledger_amount_sign` :1818-1822. `uq_pcl_booking_refund` (:3916) is correct |
| 14 | `CONFLICT_STATUSES` `:51` | MATCH | used at :254, :301 |
| 15 | `BookingStatus` has 18 constants | MATCH | counted 18 |
| 16 | `Booking.java:48` plain String | MATCH | no `@Enumerated` |
| 17 | `SessionPackPurchase.java:49-50` | MATCH | |
| 18 | "`setExpiresAt` … one hit" / "written exactly once outside the entity" | **FALSE** | also `SessionPackPaymentService.java:173` (`extendPack`, +30d) and `:280` (`createPurchase`, now+60d) |
| 19 | `ParentRegistrationService.java:100-108` | DRIFTED | catch at **:103-109** |
| 20 | `user_phone_key` `V138:2727` | MATCH | no later migration touches it |
| 21 | Constraint-name pattern in SluPersistenceRetrier / ReviewFlagService / ReviewSubmissionService | MATCH | :181-185 / :186-188 / :262-264 |
| 22 | `CoachRegistrationService.java:101-105` always `emailInUse` | MATCH | :103-104 |
| 23 | `getParentBookings` `:508-549`, null at `:546`; `getCoachBookingRequests` `:551` | MATCH (±1) | method decl :552 (annotation :551) |
| 24 | `i18nTemplateKeyResolutionSpec.js:168` = 500 | MATCH | |
| 25 | `EnvelopeEntitySchemaIT` `:76+` | DRIFTED | :76 is Javadoc; test method at :85 |
| 26 | `envelope_entity_status_check` `V138:614` + enum values | MATCH | constraint and enum agree today (6 values) |
| 27 | `package.json:49` happy-dom ^15.11.6 | MATCH | |
| 28-33 | StorageService / S3 / Local / Replicated / `deleteRawBytes:324` / BlobDeletionOutboxHandler | MATCH | only 3 `implements StorageService`, no test fakes |
| 34 | `AlertNotificationListener` `:56-95`, blank `:63-68`, catch `:87-93` | DRIFTED (2-5 lines) | method :52-100; blank branch :65-71; catch :91-98 |
| 35 | `DrillMetadata.java:9-14` comment from deferred-76 AC10 | MATCH | |
| 36 | `VideoModerationScan.scannedAt` updatable=false / @PrePersist | MATCH | :39, :42-44 |
| 37-38 | `assert-context-count.sh`, all referenced paths | MATCH | |

---

## 2. Ledger & precedent attribution (Layer 2)

Sources checked for each claim: ledger text, code comments/Javadoc, `git log`/blame.

| Claim | Verdict | Notes |
|---|---|---|
| All 14 mapped bullets exist and are open | CONFIRMED | none carries a `[DECIDED]`/`[CLOSED]` tag |
| Row 6 section = "skillars-deferred-12 story creation" | **WRONG section** | bullet is at ledger :866 under `## Deferred from: code review of skillars-1-4-parent-registration-player-profiles-shadow-accounts Group 1 (2026-06-12)` (header at :859; `git log -S` → 15d1ca2b, story 1-4) |
| D9 bullet's "other half" is `Booking.status` (AC4, AC15b) | **WRONG** | ledger :956: "Stringly-typed computed `status` field … [`SessionPackPurchaseResponse.java`, `PackSessionService.java`]". The other half is the computed `"EXHAUSTED"/"PAUSED"/"EXPIRED"/"ACTIVE"` status in `SessionPackPaymentService.computeStatus`, not `Booking.status`. No owner decision covers it, so the story drops it without saying so |
| deferred-49 "revisit if a fourth caller ever appears" | CONFIRMED (soft) | ledger :1030 "worth revisiting if a fourth caller ever appears". Its "three" left out the original `createBookingRequest`, so counted the same way it went from 3 to 4 duplicated sites (only Batch is new). The story's "it has, twice over" / "3 → 5" overstates it |
| deferred-161 baseline "567/567, +1 pre-existing unhandled error" (AC10) | **WRONG baseline** | deferred-161 :285-286: "+1 … on vitest 3; after the bump 567/567 on vitest 4.1.12 with **no** unhandled error". The real baseline is 0 unhandled errors |
| D164-C "same rationale as deferred-161 AC1" | CONFIRMED | deferred-161 AC1 was explicitly "unreachable today … defensive" (commit 5f5e2994) |
| `BookingPaymentStatus` "Not an enum" Javadoc (D164-E) | CONFIRMED | blame 63862bcd (UAT.3) |
| `DrillMetadata` comment by deferred-76 AC10 | CONFIRMED (caveat) | blame 82a89a91. The comment is about `@PositiveOrZero` being inert, but it does state that no live endpoint accepts user-supplied DrillMetadata. Ledger :1026 carries only the deferred-63 audit tag; the deferred-76 re-audit exists only in code |
| Ledger bullet 12's three gaps | CONFIRMED (with a gap) | ledger :3224-3227. Gap 2 also names the sibling `VideoModerationEmailListener` ("doesn't either"), which AC12 omits |
| Bullet 8 asks only for a literal floor | CONFIRMED | dynamic/wrapped counts are already pinned exactly |
| Bullet 9 names `envelope_entity_status_check` ↔ `EmailDeliveryStatus` | CONFIRMED | |
| Bullet 10 recommends `^20` | CONFIRMED | |
| Delete/re-annotate convention (deferred-161/163) | CONFIRMED (161); 163 only deleted (all its items were closed by code) | |
| `computeMetricValue` returns -1.0 | CONFIRMED | `AlertEvaluationService:87-91`; sole publisher :72 gated on `>= 0` |
| Hikari auto-commit=false | CONFIRMED | `application.yaml:192` |

---

## 3. Mechanistic claims (Layer 3, re-checked in Step 4b)

**M-1 AC1: "assert that call blocks until sweepOne's transaction completes". WRONG.**
`BookingRepository.java:248-249`: `@Lock(PESSIMISTIC_WRITE)` + `@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))`, which is NOWAIT. `PessimisticLockRetryer` (`:90-99` defaults `max-attempts:8`, `initial-backoff-ms:100`, `max-backoff-ms:800`, `multiplier:1.6`) catches the lock failure and retries, then rethrows on the 8th attempt. A concurrent `reserveCapture` polls for about 2.4-3.2 s and then fails. It does not block. Stale comments (`BookingPaymentPersistenceService.java:100` "5 s lock.timeout hint", `PaymentLifecycleService.java:89`) are the likely source of the wording.

**M-2 AC1: hook placement "right after `findByIdForUpdate` … before the `bookingPaymentRepository.findById` read" cannot produce a red mutation test. CONFIRMED defect.**
Re-read `PaymentPendingSweeper.java:155-182`. With the lock replaced by a plain `findById` and the hook before :157, B's `reserveCaptureTransactional` (REQUIRES_NEW) inserts `CAPTURE_PENDING` and commits during the hook. A's `findById` at :157 (READ COMMITTED) then sees it and takes the `reportUnrecoverable` branch, which is the safe outcome, so the test stays green. The race the lock actually guards (as the sweepOne comment itself says: "decides on the ABSENCE of a payment row and then writes one") is between the absence check at :157-170 and `bookingPaymentRepository.save(bp)` at :182. `BookingPayment` has an assigned `@Id` and no `@Version`, so that `save` merges over B's row with `CHARGE_FAILED`. Ledger :993 records that an earlier IT was **deleted for exactly this reason** ("passed unchanged against the unlocked code"), and `PaymentPendingSweeperIT:302-308` documents it. The story doesn't mention this history.

**M-3 AC1: "No production code changes" vs. "Add a package-private hook". CONFIRMED contradiction.** `sweepOne` is `private`, so the hook has to live in `PaymentPendingSweeper`.

**M-4 AC2: the five sites "differ only in key names". PARTIALLY WRONG.**
Messages differ ("Requested slot…" at BookingService/BookingBatchService vs "Proposed slot…" at RescheduleService ×2/BookingDuplicationService). `RescheduleService:413` has a **third** key, `"submitted coach id"`. All five use the same exception type and `BookingError`. The batch site gets windows prefetched by `createBatch`, so the "fetch" is not part of the shared idiom. All callers are in `platform.booking.service`, so a package-private helper does compile.

**M-5 AC2: "`isSlotWithinAvailabilityWindow` … still used standalone nowhere after this". WRONG.** `BookingServiceTest:709-840` calls it directly, and the new helper itself will call it.

**M-6 AC3: "No DB CHECK constraint" / "6 literals across 5 files". WRONG.** See citation #13. There are also missed write sites: `admin/service/DisputeService.java:231,244,254` (`"BOOKING_REFUND"`, cross-module, so the constant must be `public`), the read at `CreditWalletRefundOutboxHandler:42`, and JPQL literals at `ParentCreditLedgerRepository:20,41,44`.

**M-7 AC4: CONFIRMED.** `BookingStatus` is a plain enum, so `.name()` gives the exact literals.

**M-8 AC5: "written exactly once outside the entity", "package-private `extendExpiry`", "pause/resume". WRONG on all three.** There are three production writers (citation #18). `@Setter` is class-level (`:22`). The entity is in `payment.repo` and the callers are in `payment.service`, so package-private is not visible to them. `PackSessionService` has no resume path; only pause (`:310`) extends expiry.

**M-9 AC6: the catch block can catch the `user_phone_key` violation. WRONG. The catch is dead for a real DB collision.**
`ParentRegistrationService` is class-level `@Transactional` (:50). :102 is plain `userRepository.save(user)`. `User → Customer → AbstractAuditingEntity → BaseEntity` with `@Id @Tsid` (`BaseEntity.java:27`), an app-assigned id, so `persist` defers the INSERT to flush/commit, which happens after the try. `sendVerificationEmail` (:264-276) only does another Tsid `save` and an event publish, and runs after the catch anyway. All three precedents the story mirrors rely on an in-try flush: `ReviewSubmissionService:105` and `ReviewFlagService:125` use `saveAndFlush`. With only a constraint-name check, the fix as written changes nothing at runtime. No test covers `phoneInUse` (grep of src/test: 0 hits).

**M-10 AC9: CONFIRMED.** The regex parser at `EnvelopeEntitySchemaIT:86-96` works on the status constraint, and the values match today, so the new test will be green on day one.

**M-11 AC11: PARTIALLY.** The SDK v2 claims hold. Corrections: `LocalFileSystemStorageService.delete` (:58-70) has a path-traversal guard (`normalize()` + `startsWith(baseDir)`), so `deleteAll` must loop over `delete(key)`, not call `Files.deleteIfExists` raw. S3 `DeleteObjects` reports per-key failures in `errors()` without throwing `SdkException`, so `@Retryable` won't fire on them. `ReplicatedStorageService.delete` delegates to the primary only (story correct).

**M-12 AC12: CONFIRMED.** Synchronous `@EventListener` on a singleton, driven by a 30 s `@Scheduled`. `ConfigService.find` returns `Optional` but can throw on a forced refresh, so (c) is testable. A `Clock` bean exists. The statuses are plain `kv` log strings, not an enum.

**M-13 AC7: CONFIRMED.** Only `pages/coach/CoachBookingRequestsPage.vue` reads `parentName`.

---

## 4. Corner cases / false assumptions / missed flows (survived Step 4b)

### Critical
- **C1 (AC1): the mutation test cannot go red with the hook where AC1 puts it** (M-2). Fix: put the hook after the `existing.isPresent()` early return, before the `new BookingPayment()` / `save` at :176-182. With the lock removed the expected result is red (A's `CHARGE_FAILED` merges over B's committed `CAPTURE_PENDING` while B reported `RESERVED`).
- **C2 (AC1): the "blocks" assertion is impossible under NOWAIT + bounded retry** (M-1). Rewrite the assertion:
  - B returns `BOOKING_NOT_PENDING`.
  - `persistence.lock_retry.retries{lock="BookingPaymentPersistenceService.reserveCapture"}` is above 0.
  - Final state: booking `DECLINED`, row `CHARGE_FAILED`.

  The hook must release within about 2 s, using a latch with a timeout and a `volatile` hook reset in `finally`/`@AfterEach` (a stuck hook holds the row lock and a connection, which hangs `DatabaseResetTestExecutionListener`'s truncate). Also reword "No production code changes" (M-3).
- **C3 (AC2): moving the throw into a `BookingService` method breaks existing mocked unit tests.** `RescheduleServiceTest:50`, `BookingDuplicationServiceTest:42` and `BookingBatchServiceTest:72` declare `@Mock BookingService`, stub `isSlotWithinAvailabilityWindow(...)` to return `false`, and expect `SLOT_OUTSIDE_AVAILABILITY` (e.g. `BookingDuplicationServiceTest:222`, `BookingBatchServiceTest:198`, `:262`). They also `verify(...)` it (`RescheduleServiceTest:120`, `BookingDuplicationServiceTest:122`, `BookingBatchServiceTest:228` `times(2)`). A mocked void `requireSlot…` never throws. Either make the helper a static thrower fed the boolean from the (still-mocked) instance call, or list these test rewrites explicitly in Task 2.
- **C4 (AC6): the constraint-name fix is inert without `saveAndFlush`** (M-9). AC6 must require `userRepository.saveAndFlush(user)` inside the try (matching the review-service precedents), plus an IT registering two parents with different emails and the same phone. A mocked-throw unit test alone would certify a path production never reaches. Note that `ApiAdvice`'s known-constraint list doesn't include `user_phone_key`, so today a real collision surfaces as a generic data error, not `phoneInUse`.

### High
- **H1 (AC2): the helper signature doesn't fit** (M-4). It needs the message and a caller-built detail map (or a wording enum covering message + keys + optional coach-id). Drop "byte-for-byte". `BookingBatchServiceTest:202,225` assert `hasMessageContaining("not within coach availability")`.
- **H2 (AC3): a `chk_ledger_type` CHECK exists** (M-6). "No migration needed" still holds, but the constants-holder Javadoc should state that adding a value needs a migration widening `chk_ledger_type` (and possibly `chk_ledger_amount_sign`), as `BookingPaymentStatus` does. Optionally add an AC9-style parity test.
- **H3 (AC3): missed sites.** `DisputeService:231,244,254` (admin module, so `public` constant), `CreditWalletRefundOutboxHandler:42`, and the JPQL at `ParentCreditLedgerRepository:20,41,44` (constant concatenation is legal in annotations; decide either way). The frontend `CreditStatementPage.vue:54` stays a literal, which is worth saying.
- **H4 (AC5): D164-D's evidence and AC5's encapsulation option are both wrong** (M-8). The owner decision itself (no `updatable=false`) still stands, and with three writers it is even more clearly right. Fix: either Javadoc naming all three writers, or a **public** `extendExpiryBy(Duration)` while keeping the setter (removing the setter touches about 30 test call sites). Drop "resume".
- **H5 (AC10): wrong baseline.** The real baseline is 0 unhandled errors (Layer 2). Use `^20.11.6` (the Quasar AE's nested pin) rather than bare `^20`. happy-dom 20 defaults `enableJavaScriptEvaluation: false`, so compare unhandled errors as well as pass counts. Node 22.16.0 meets happy-dom 20's Node ≥20 requirement. (Layer 4 also reported the live count is now 577 tests and `routerGuardSpec.js` times out under full-suite load; not independently re-run in Step 4b, so treat it as a dev-time check.)

### Medium
- **M1 (AC4/AC15b): mislabelled D9 half.** AC15(b) should name the `SessionPackPurchaseResponse` computed status and either decide it (same constants/enum treatment, or `[DECIDED]` with reasoning) or leave it open. "`Booking.status` half" is not what the bullet says.
- **M2 (AC12): sibling listener.** The ledger's gap 2 also names `VideoModerationEmailListener` (same key, no trim). Either include it or re-annotate that half explicitly. A "configurable cooldown" through `ConfigService` would add a second lookup inside the very path (c) is separating, so prefer a constant/`@Value` and an injected `Clock`. "No second log" needs a log-capture appender, which the test doesn't have today. The test constructs the listener directly, so a constructor change ripples into it.
- **M3 (AC11): contract details.** Loop over `delete(key)` on the local filesystem (traversal guard). Specify empty/null list behaviour. Decide whether non-empty `DeleteObjectsResponse.errors()` throws. Add an IT against the SeaweedFS test container, since `S3StorageServiceTest` mocks `S3Client` and wouldn't catch checksum/Content-MD5 incompatibility.
- **M4 (mapping row 6): wrong section** (Layer 2). Fix it so the dev finds the bullet.

### Low
- **L1 (AC7 vs AC15c):** AC7 says re-annotate `[DECIDED — verified, no defect]`; AC15(c) says delete. Pick one.
- **L2 (AC8):** live literal count is **1641** (Layer 4 ran the spec's own `scanCallSites` from a scratch copy), not 1571. 95% ≈ 1559, so the hardcoded 1500 is looser than the story's own 95% rule. Use about 1550 (the assertion is strict `toBeGreaterThan`).
- **L3 (AC2 framing):** "it has, twice over" / "3 → 5" overstates it; counted the ledger's way it went 3 → 4 (only Batch is new).
- **L4 (AC6 "new finding"):** the ledger bullet already says "same pre-existing pattern as CoachRegistrationService". The new bullet is still worth adding, and it should note the same no-flush problem.
- **L5 (AC1):** stale "5 s lock.timeout" comments at `BookingPaymentPersistenceService.java:100` / `PaymentLifecycleService.java:89`. Worth correcting while in there.
- **L6 (AC4, out of scope):** `CONFLICT_STATUSES` excludes `PAYMENT_PENDING`. Note only.

---

## 5. What did not survive re-verification

- **Layer 1's summary listing #22 (CoachRegistrationService) as "false":** the layer's own table marked it MATCH, and the cited lines (:103-104) do throw `emailInUse` unconditionally. Killed as a self-contradiction in that layer's output.
- **"AC9's new test would be red on day one" (hypothesis put to Layers 3/4):** killed. V138:614 and `EmailDeliveryStatus` agree, and no later migration (V140/V143/V168) alters the status check.
- **"Adding `deleteAll` to the interface breaks test fakes":** killed. Only the 3 production implementations exist, so no `default` method is required (it remains a style option).
- **"`payment/contract/` placement may violate ArchUnit/Modulith rules":** killed. No such rules exist, and `DisputeService` already imports `payment.contract`.
- **"The AC1 hook adds a Spring context":** killed. A package-private field set from the IT adds none. Only a `@MockitoSpyBean` alternative would.
- **"`Map.of` iteration order makes 'byte-for-byte' meaningless" (Layer 4 H1):** downgraded. Real, but cosmetic; the substantive point (differing messages, third key) is what survives as H1.
- **"ReplicatedStorageService.delete may also delete a replica":** killed. `:50-52` only delegates to the primary.

---

## 6. Recommendation

The story should **not** go to dev as written. Four Critical findings would each produce either a test that cannot fail (C1, C4) or an unplanned broken build (C3), or an impossible assertion (C2). All four were re-read directly in Step 4b against HEAD `b5680880`:

| Area | Confidence | What was independently re-checked |
|---|---|---|
| C1/C2 (AC1 hook + NOWAIT) | High | `sweepOne` :120-198, `reserveCaptureTransactional` :125-160, `BookingRepository:248-251`, retryer defaults :90-99, `BookingPayment` has no `@Version` |
| C3 (AC2 mock breakage) | High | grep of the three test classes' `@Mock BookingService` + stubs/verifies |
| C4 (AC6 no flush) | High | `@Transactional` :50, plain `save` :102, `@Id @Tsid` hierarchy, precedents' `saveAndFlush` |
| H2/H3 (ledger CHECK + DisputeService) | High | V138:1823 grep, DisputeService :231/244/254 |
| H4 (expiresAt writers) | High | grep of `SessionPackPaymentService` :173/:280, `@Setter`, package |
| H5 (AC10 baseline) | High on the baseline (deferred-161 :285-286 read); Medium on the 577/routerGuard timeout observation (Layer 4 only, not re-run) | |
| M1, M2, M4, L1, L3 | High | ledger :956, :1030, :866/:859, :3224-3227 read directly |
| M3 (AC11 details) | Medium | Layer 3 reads; the SDK checksum behaviour against SeaweedFS was not re-checked |
| L2 (1641 count) | Medium | Layer 4 ran the spec's own counter; not re-run in Step 4b |
| Owner decisions D164-A/B/C/E/F | High; they stand as decided | D164-D's conclusion stands, but its evidence ("one write site") is wrong and should be corrected in the story |

Suggested next step: apply C1-C4 and H1-H5 to the story in place and keep the status `ready-for-dev`. M1 (the D9 computed-status half) and M2 (the sibling listener) need an owner call: include them or explicitly re-annotate.
