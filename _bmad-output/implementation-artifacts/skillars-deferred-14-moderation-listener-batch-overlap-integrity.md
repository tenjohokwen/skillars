# Story Deferred-14: Moderation-Listener Write Safety & Batch/Reschedule Overlap Integrity

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a platform operator,
I want the Gemini review-moderation listener to stop overwriting moderation decisions it did not make, batch accepts to survive one bad booking instead of failing whole-batch with a 500, the two booking write paths that bypass the app-layer overlap check to get that check, and end-before-start booking payloads to be rejected as `400` validation errors rather than `403` security errors,
so that an admin's BLOCK cannot be silently reverted by an in-flight moderation call, a coach's batch accept partially succeeds as its own status model already promises, and slot conflicts on reschedule/batch surface as a deterministic domain error instead of a database constraint violation.

### Why this story exists

Five items in `deferred-work.md`, backend-only, **zero unshipped dependencies** — every library, repository method and transaction pattern this story needs already exists in the codebase. Every item below was re-verified against the current source on 2026-08-05 during story creation; file/line references come from direct reads, not from trusting the ledger.

| # | Source item | Verified current state (2026-08-05) |
|---|---|---|
| AC1 | `skillars-deferred-13` review D1 (2026-08-05) | **CONFIRMED, and it is the headline.** `ReviewModerationService.handleReviewSubmitted` (`:93-110`) opens a `REQUIRES_NEW` transaction after the Gemini call and does a plain unlocked `reviewRepository.findById(reviewId)` followed by an **unconditional** `review.setModerationStatus(finalStatus)`. No already-resolved guard, no lock. `coachRatingService.recompute(coachId)` (`:105-108`) sits **outside** the `ifPresentOrElse`, so it also fires when the review was not found at all. |
| AC2 | New, found while verifying `deferred-13` D2 | **`BookingBatchService.acceptAll` (`:156-168`) cannot partially succeed.** Its per-booking `catch (Exception e) { log.warn(...) }` swallows the exception, but `bookingService.acceptAndInitiatePayment` is a **cross-bean** `@Transactional` call, so a failure inside it marks the shared transaction rollback-only. The loop then continues, `batchRepository.save(batch)` runs, and the commit throws `UnexpectedRollbackException` → **500, zero bookings accepted, batch status unchanged**. This is the identical defect class the `deferred-12` code review reproduced in `PaymentLifecycleService` and fixed with a per-item `REQUIRES_NEW` `TransactionTemplate`. The `FULLY_ACCEPTED` / `PARTIALLY_ACCEPTED` branch at `:174-175` is currently unreachable for any batch containing one bad booking. |
| AC3 | `skillars-3-11` residual gap (2026-08-01), re-scoped from `deferred-13` D2 | `RescheduleService.acceptReschedule` (`:118-120`) rewrites `requestedStartTime`/`requestedEndTime` on a `CONFIRMED`/`UPCOMING` booking with **no coach lock and no overlap check**. `BookingBatchService.acceptAll` likewise transitions to `PAYMENT_PENDING` with no overlap check. Story 3.11 explicitly chose "option b" (DB constraint only) and recorded the missing app-layer check as residual. |
| AC4 | `skillars-3-3` Group C (2026-06-15) | `CreateBookingRequest` has no cross-field constraint. `BookingService.createBookingRequest` (`:172-176`) rejects `end <= start` with `OperationNotAllowedException(SecurityError.MISSING_RIGHTS)` → **`403`**, filing a plain malformed payload under a security error code. |
| AC5 | `skillars-deferred-13` review D3 (2026-08-05) | `ReviewFlagIT.flagReviewWithMissingCoachProfile_returns500WithCoachProfileMissing` (`:320-355`) cleans its orphan `coach_reviews` fixture in an in-test `try/finally`; a throw from the seeding `transactionTemplate.execute` before the `try` is entered leaks the row. `@AfterEach tearDown` exists at `:156`. |

### Corrections to `deferred-work.md` this story must record

Two ledger claims were checked and found **wrong**. Do not code against them.

- **`deferred-13` D2 is factually incorrect as written.** It claims booking exclusion-constraint violations "have no 409 mapping" and "surface as a raw `DataIntegrityViolationException` → 500". They do not. Story 3.11 already wired `excl_bkg_coach_slot_overlap` into the **global** `ApiAdvice` handler: `CONSTRAINT_MAPPINGS` (`ApiAdvice.java:143` → `booking.slotUnavailable`) and `CONFLICT_CONSTRAINTS` (`:153` → `409`). `BookingApiAdvice` does not declare a `DataIntegrityViolationException` handler, so the global one applies to `booking.api` too. The real residue is the **missing app-layer pre-check** — that is AC3.
- **D2 also names `BookingBatchService.createBatch` as an exposed path. It is not.** `createBatch` writes rows with `status = 'REQUESTED'` (`BookingBatchService.java:111`), and `V87`'s `WHERE (status IN (...))` clause **deliberately excludes `REQUESTED`**. `createBatch` can never violate the constraint. The reachable paths are `acceptAll` (REQUESTED → PAYMENT_PENDING) and `acceptReschedule` (time rewrite on CONFIRMED/UPCOMING).

### Items examined and deliberately NOT included

Recording these so the next audit does not re-litigate them:

- **`skillars-3-11` D1 (coach-suspension race) is already closed.** `deferred-12` added the locked re-read: `BookingService.java:198-212` now does `findByIdForUpdate` + `entityManager.refresh(lockedCoach, PESSIMISTIC_WRITE)` + a re-check of `SUSPENDED`/active status. The ledger entry at `deferred-work.md:114` is stale. **Delete it (AC6), do not implement it.**
- **`skillars-3-3` Group A "`canonicalTimezone` not IANA-validated" is already closed.** `BookingService.java:178-183` wraps `ZoneId.of(req.canonicalTimezone())` in a `try/catch (DateTimeException)`. Stale. **Delete it (AC6).**
- **`skillars-10-2` D1** (`AFTER_COMMIT` listener failure silently drops refunds) — a platform-wide event-reliability concern shared by every `@TransactionalEventListener`. Same reason `deferred-13` left it: too large. Leave in the file.
- **`skillars-8-2` D1/D2** (deleted player crashes `getConversations()` via `UserNotFoundException`) — real and still open, but messaging-module scope with its own age-policy surface. Belongs in a messaging story, not here. Leave in the file.
- **`booking.slotUnavailable` has no i18n entry** in any `src/frontend/src/i18n/*` locale. Pre-existing — the code already surfaces from `createBookingRequest` and `acceptBooking` today, so AC3 is not a regression. Out of scope; the i18n consolidation item (`deferred-9` D2) already owns locale work.

## Acceptance Criteria

1. **The moderation listener never overwrites a status it did not set.** Inside `ReviewModerationService.handleReviewSubmitted`'s `requiresNewTx.execute` block, the review is read with `reviewRepository.findByIdForUpdate(reviewId)` (not `findById`), and the status write happens **only when the locked status is `ReviewModerationStatus.PENDING`**. Any other observed status (`APPROVED`, `BLOCKED`, `UNDER_REVIEW`) means an admin — or an earlier delivery of this same event — already resolved the review: log at WARN with `reviewId`, the observed status, and the discarded verdict, then skip. `coachRatingService.recompute(coachId)` moves **inside** the same guarded branch, so a skipped write never recomputes and a not-found review never recomputes either (it does today). `review.setHeldReason(...)` stays coupled to the `UNDER_REVIEW` verdict exactly as now. `lastModifiedAt` is **not** touched — `ReviewSubmissionService.updateReview` reads it for the 365-day edit rule.

2. **The BLOCK-then-SAFE race is proven closed by test, not asserted.** An integration test drives the real sequence: a `PENDING` review, an admin `blockReview()` committing while the Gemini call is in flight, then the listener resolving `SAFE`. Post-condition: `moderation_status = BLOCKED`, the review is **not** re-published, `coachRatingService.recompute` is **not** called by the listener, and `review_moderation_log` holds exactly the one admin `BLOCKED` row. The test must be **mutation-verified**: reverting AC1's guard makes it fail. Per `deferred-13`'s finding, a barrier-based IT that only proves "the second caller waits" proves nothing here — the discriminator is what the listener **observes and writes** once unblocked.

3. **A batch accept partially succeeds instead of failing whole-batch.** `BookingBatchService.acceptAll` runs each booking's accept in its own `REQUIRES_NEW` transaction via a `TransactionTemplate` field, mirroring `PaymentLifecycleService.perBookingTx` (`:49-55`). One booking failing leaves the others committed, `acceptedIds` accurate, and the batch resolving to `PARTIALLY_ACCEPTED`; the outer transaction is never marked rollback-only by a caught failure. **Reproduce the current 500 first** (a mixed batch where one booking cannot be accepted must today produce `UnexpectedRollbackException` and zero accepted bookings) and keep that reproduction as the regression test. `bookingService.acceptAndInitiatePayment` is **not** modified — the single-booking path depends on its current `REQUIRED` semantics.

3a. **The batch-status write and the settlement event stay durable together.** Per-booking commits mean the bookings become durable **before** `acceptAll`'s own tail work (`batchRepository.save(batch)` + `BatchBookingAcceptedEvent`). If that tail is left in the outer transaction, a failure at its commit strands every booking durably in `PAYMENT_PENDING` with the settlement event never published — and there is **no recovery path**: `BookingExpiryScheduler.expireStaleRequests` (`:43-46`) only sweeps `REQUESTED` bookings via `findRequestedBookingsOlderThan`, so nothing ever revisits a stuck `PAYMENT_PENDING` row. The per-booking restructure also promotes `BookingBatchStatusListener` to a **second, earlier writer** of `batch.status` (see Dev Notes), which would leave the batch reading `FULLY_ACCEPTED` while no payment was ever collected — a healthy-looking row with no retry trigger. Therefore: after the loop, the batch-status write **and** the event publish happen together in a single trailing `REQUIRES_NEW` transaction (re-reading the batch inside it), leaving `acceptAll`'s own transaction with no post-loop work that can fail. This collapses the window rather than eliminating it — batch accept is no longer atomic **by design**, which is the whole point of AC3. State that residual explicitly in a code comment; do not describe the method as atomic.

4. **Both overlap-bypass paths get the app-layer check.** Inside each per-booking transaction in `acceptAll`, and inside `RescheduleService.acceptReschedule` before the time rewrite: acquire `coachProfileRepository.findByIdForUpdate(coachId)` (`.orElseThrow(ResourceNotFoundException)`), then call `bookingRepository.findOverlappingBookings(coachId, start, end, ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED, thisBookingId)` and throw `OperationNotAllowedException(..., BookingError.SLOT_UNAVAILABLE)` when non-empty — the same shape as `BookingService.acceptBooking` (`:277-293`). For reschedule, the checked window is the **proposed** times (`req.getProposedStartTime()` / `getProposedEndTime()`), not the current ones. Resulting HTTP status is **`403` with `errorKey: booking.slotUnavailable`**, matching every existing app-layer slot-conflict path — do **not** change it to 409 for "consistency" with the DB-constraint fallback; `BookingServiceConcurrencyIT` pins the 403 contract. The `V87` constraint stays in place as the backstop.

5. **Intra-batch overlaps are resolved cleanly.** A batch containing two overlapping slots for the same coach accepts the first and skips the second with `SLOT_UNAVAILABLE`, ending `PARTIALLY_ACCEPTED` — no `DataIntegrityViolationException`, no 500. (This outcome is only reachable because AC3's per-booking commit makes the first accept visible to the second's pre-check; the two ACs must land together.)

6. **End-before-start is a 400, not a 403.** `CreateBookingRequest` gains a class-level cross-field constraint — an `@AssertTrue`-annotated `isEndAfterStart()` accessor returning `true` when either timestamp is null (so `@NotNull` owns the null case and does not double-report). `POST /api/booking/requests` answers `400` for `requestedEndTime <= requestedStartTime`. The existing service-layer check in `BookingService.createBookingRequest` (`:172-176`) **stays** as defence-in-depth for non-HTTP callers; `BookingBatchService.createBatch`'s per-slot check (`:86-88`) is unchanged. All other `CreateBookingRequest` validation behaviour (`@Future` on start, `@NotBlank` timezone, `@Size` notes) is unchanged.

7. **The orphan-fixture cleanup follows the file's convention.** `ReviewFlagIT`'s orphan `coach_reviews` row is tracked in a field (nullable, reset per test) and deleted in `@AfterEach tearDown` (`:156`) alongside the other fixtures, with the in-test `try/finally` removed. The test's assertions and its `500` / `reviews.coachProfileMissing` contract are unchanged.

8. **`deferred-work.md` reflects reality.** **Story creation already did part of this — read the file before editing it.** The two stale entries (`skillars-3-11` D1 and the `skillars-3-3` Group A `canonicalTimezone` bullet) are **already deleted**, D2's wrong premise is **already corrected inline**, and a `## Last audit: 2026-08-05 (skillars-deferred-14 story creation)` block **already exists** — do not re-delete, re-annotate or duplicate any of that.

   What this story still has to do: delete the four items it actually closes — **D1**, **D2** and **D3** under `## Deferred from: code review of skillars-deferred-13-admin-moderation-action-integrity (2026-08-05)` (each carries an `OWNED BY skillars-deferred-14` annotation naming the AC that closes it), which empties that heading — **remove the heading too**; and the `No cross-field @AssertTrue on CreateBookingRequest` bullet under `## Deferred from: code review of skillars-3-3-booking-request-approval-workflow Group C (2026-06-15)`, which likewise empties that heading — **remove it too**. Then amend the existing story-creation audit block to record that those four are now closed by shipped code rather than merely owned.

   Nothing else is deleted; in particular the two `## Deferred from: code review of skillars-10-1 patches (2026-06-30)` items, `skillars-10-2` D1 and `skillars-8-2` D1/D2 stay. Do **not** add a second `## Last audit` heading dated 2026-08-05 — two already carry that date and are disambiguated by their parenthesised suffixes.

## Tasks / Subtasks

- [x] **Task 1 — Moderation-listener lock + PENDING guard (AC: 1)**
  - [x] In `ReviewModerationService.handleReviewSubmitted`, swap `reviewRepository.findById(reviewId)` for `reviewRepository.findByIdForUpdate(reviewId)` (already exists — `CoachReviewRepository:23-25`, `@Lock(PESSIMISTIC_WRITE)`).
  - [x] Inside the present-branch lambda, read `review.getModerationStatus()` first. If it is not `PENDING`, `log.warn` (include `reviewId`, observed status, discarded `finalStatus`) and return without writing.
  - [x] Move the `coachRatingService.recompute(coachId)` call (currently at `:105-108`, outside `ifPresentOrElse`) **into** the guarded present-branch, keeping its `APPROVED || BLOCKED` condition.
  - [x] Keep the surrounding `catch (Exception e)` swallow and its comment — an AFTER_COMMIT throw must still not become an HTTP 500. Update the comment to note the new guard.
  - [x] Add a comment stating why `PENDING` is the whole guard: `ReviewSubmissionService.submitReview` (`:63`) and `updateReview` (`:99`) are the only publishers of `ReviewSubmittedEvent` and both set `PENDING` immediately before publishing, so any other status is by definition **not** this delivery's to write. Enumerate all three other writers so the comment does not go stale — `AdminReviewService.approveReview`/`blockReview` (admin decisions), `ReviewFlagService.flag` (`:78-84`, auto-holds `APPROVED` → `UNDER_REVIEW` with `HeldReason.FLAG_THRESHOLD` at the configured flag count), and a duplicate delivery of this same event. The guard is correct for all three without special-casing: the flag path can only fire on an already-`APPROVED` review, which is by definition past this delivery's write.

- [x] **Task 2 — Repair `ReviewModerationServiceTest` (AC: 1)**
  - [x] Both existing tests (`bodyContainingDelimiterTokens_stripsThemBeforeSending`, `shortBody_promptIsDelimited`) stub `when(reviewRepository.findById(reviewId))`. After Task 1 that stub is unused → `MockitoExtension` strict stubbing fails with `UnnecessaryStubbingException`, and the unstubbed `findByIdForUpdate` returns `null` → NPE. Repoint both to `findByIdForUpdate` and give the returned `CoachReview` `moderationStatus = PENDING` (the field default is already `PENDING` — `CoachReview.java:51` — assert it explicitly rather than relying on it).
  - [x] Add a unit test: locked status `BLOCKED`, Gemini returns `SAFE` → `reviewRepository.save` never called, `coachRatingService.recompute` never called.
  - [x] Add a unit test: review not found → `recompute` never called (regression on the current behaviour, where it fires).

- [x] **Task 3 — Admin-BLOCK vs listener race IT (AC: 2)**
  - [x] Extend `ReviewModerationIT` (`src/test/java/com/softropic/skillars/platform/reviews/api/ReviewModerationIT.java`) — it already `@MockitoBean`s `GeminiClient` and has the full review/coach/user fixture set.
  - [x] Mechanism (the ordering matters, get it exactly this way): the listener is `AFTER_COMMIT` and **not** `@Async`, so it runs on the request thread — the existing tests rely on this, which is why they can assert the final status straight after the POST returns. Stub `geminiClient.evaluate(...)` with an `Answer` that counts down `geminiEnteredLatch` then blocks on `releaseLatch`. Run the review-submission POST on a **background thread** (it will park inside the listener), await `geminiEnteredLatch` on the main thread, call `AdminReviewService.blockReview(reviewId, "reason", adminId)` on the main thread and let it commit, then count down `releaseLatch` and join the background thread.
  - [x] `blockReview`'s `findByIdForUpdate` will not contend here: the Gemini call happens **outside** any transaction (`ReviewModerationService.java:58-91`), and the listener's `REQUIRES_NEW` transaction only opens after the verdict is computed. That is exactly what makes the race reachable in production and reproducible here.
  - [x] Assertions: `moderation_status = BLOCKED`; exactly one `review_moderation_log` row with `action = 'BLOCKED'`; the coach's aggregate rating unchanged by the listener.
  - [x] Mutation-verify: temporarily revert AC1's guard, confirm the test fails, restore. Record the result in Completion Notes — `deferred-13`'s review rejected an IT that passed against both the fixed and unfixed code.

- [x] **Task 4 — Reproduce the batch whole-batch-failure 500 (AC: 3)**
  - [x] Before changing `BookingBatchService`, write an IT in `BookingBatchResourceIT` (or `BatchAcceptPaymentIT`) with a batch of two bookings where the second cannot be accepted, and assert the **current** broken behaviour: the request fails and **zero** bookings reach `PAYMENT_PENDING`.
  - [x] Capture the actual exception type in Debug Log References. `UnexpectedRollbackException` is the static-analysis prediction, not a verified observation — if the real failure differs, say so and adjust AC3's framing rather than forcing the prediction.
  - [x] Only then apply Task 5, and flip the test to assert one accepted + one skipped + `PARTIALLY_ACCEPTED`.

- [x] **Task 5 — Per-booking transaction isolation in `acceptAll` (AC: 3)**
  - [x] Add `private final PlatformTransactionManager transactionManager;` to `BookingBatchService` (it uses `@RequiredArgsConstructor`) and a `@PostConstruct`-initialised `TransactionTemplate perBookingTx` with `PROPAGATION_REQUIRES_NEW`. Copy the shape and the explanatory comment style from `PaymentLifecycleService.java:44-55`.
  - [x] Wrap the `bookingService.acceptAndInitiatePayment(...)` call in the `acceptAll` loop (`:156-168`) in `perBookingTx.executeWithoutResult(tx -> ...)`. Keep the existing `try/catch (Exception e) { log.warn(...) }` **outside** the template call.
  - [x] Do **not** touch `BookingService.acceptAndInitiatePayment` — its Javadoc (`:314-328`) documents that `acceptBooking` reaches it by self-invocation and relies on `REQUIRED`.
  - [x] **Move `batchRepository.save(batch)` and the `BatchBookingAcceptedEvent` publish into a single trailing `REQUIRES_NEW` transaction (AC: 3a)** — re-read the batch inside it (`batchRepository.findById(batchId)`), set the status, save, and publish there. `PaymentLifecycleService.onBatchBookingAccepted` is `AFTER_COMMIT`, so it fires on that trailing commit and still sees the committed batch. Do **not** leave this pair in the outer transaction: with per-booking commits in place, an outer-commit failure would strand every booking in `PAYMENT_PENDING` with no settlement event and no sweeper to find them.
  - [x] Add a comment on `acceptAll` stating plainly that it is **no longer atomic** — bookings commit individually, and a crash between the last booking's commit and the trailing transaction leaves that batch unsettled. That is the deliberate trade for partial success; do not let a later reader assume all-or-nothing.
  - [x] The `resolveEmail`/`resolveParentName` lookups feeding the event have no application-level throw path, but run them **before** opening the trailing transaction anyway so it contains only the save and the publish.
  - [x] **`BookingBatchServiceTest` will break — fix it in the same commit.** It uses `@InjectMocks BookingBatchService service` (`:56`), and `@InjectMocks` does **not** run `@PostConstruct`, so `perBookingTx` would be `null` and `acceptAll_coachOwnsBooking_transitionsAllRequestedAndPublishesEvent` (`:140`) would NPE. There is no existing unit test for `PaymentLifecycleService` to copy from (`BatchPaymentIT` is a full-context IT, where `@PostConstruct` does run). Wire it the way `ReviewModerationServiceTest` (`:37-41`) does: add `@Mock PlatformTransactionManager transactionManager` and `@Mock TransactionStatus transactionStatus`, stub `lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus)`, and call `service.initPerBookingTx()` in `@BeforeEach`. Declare `initPerBookingTx()` **package-private** (not `private`) so the test can call it — this is why `PaymentLifecycleService.initPerBookingTx` (`:52`) is package-private.

- [x] **Task 6 — Overlap pre-check in the two bypass paths (AC: 4, 5)**
  - [x] Widen `BookingService.ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED` (`:120-121`) from `private static final` to package-private `static final`. `BookingBatchService` and `RescheduleService` are in the **same package** (`com.softropic.skillars.platform.booking.service`) — no new contract class, no public constant.
  - [x] `acceptAll`: inside the `perBookingTx` lambda, before `acceptAndInitiatePayment`, lock `coachProfileRepository.findByIdForUpdate(batch.getCoachId()).orElseThrow(...)`, then `findOverlappingBookings(batch.getCoachId(), b.getRequestedStartTime(), b.getRequestedEndTime(), ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED, b.getId())` and throw `OperationNotAllowedException(..., BookingError.SLOT_UNAVAILABLE)` if non-empty. The existing catch turns it into a skipped booking.
  - [x] `RescheduleService.acceptReschedule`: after the existing guards and **before** `booking.setRequestedStartTime(...)` (`:118`), do the same lock + check against `req.getProposedStartTime()` / `req.getProposedEndTime()`, excluding `bookingId`.
  - [x] `excludeBookingId` is mandatory in both — without it a booking already in an active status matches itself and masks the real state (see the comment at `BookingService.java:279-280`).
  - [x] Add ITs: `RescheduleResourceIT` — accepting a reschedule onto a slot occupied by another `CONFIRMED` booking for the same coach returns `403` / `booking.slotUnavailable` and leaves the original times intact. `BookingBatchResourceIT` — a batch with two mutually-overlapping slots ends `PARTIALLY_ACCEPTED` with exactly one booking in `PAYMENT_PENDING`.
  - [x] **`RescheduleServiceTest` will break — fix it in the same commit.** `acceptReschedule_coachOwnsBooking_updatesTimesAndStatus` (`:152`) does not stub `coachProfileRepository.findByIdForUpdate`, which will return an empty `Optional` and trip the new `orElseThrow`. Stub it to return the same `CoachProfile` the test already builds, and stub `bookingRepository.findOverlappingBookings(...)` to return an empty list. Add a companion unit test where the overlap query returns a non-empty list and assert `OperationNotAllowedException` with `BookingError.SLOT_UNAVAILABLE` and that `bookingRepository.save` is never called.

- [x] **Task 7 — Cross-field validation on `CreateBookingRequest` (AC: 6)**
  - [x] Add to the record:
        `@AssertTrue(message = "requestedEndTime must be after requestedStartTime") public boolean isEndAfterStart() { return requestedStartTime == null || requestedEndTime == null || requestedEndTime.isAfter(requestedStartTime); }`
        The null short-circuit is required so a null field reports only its `@NotNull` violation.
  - [x] Verify by IT (`BookingRequestResourceIT`) that the response is `400` and that `ApiAdvice.processFieldErrors` (`:410-416`) renders the violation without error — the constraint is on an accessor, so Spring reports it as a `FieldError` on property `endAfterStart` with a `null` rejected value. If `processFieldErrors` mishandles the null rejected value, fix it there and note it; do not work around it by moving the constraint.
  - [x] Leave `BookingService.createBookingRequest`'s service-level check and its `403` in place, and do not add a test asserting `403` for the HTTP path — bean validation now short-circuits before the service is reached.
  - [x] No frontend change: `BookingRequestPage.vue` derives `requestedEndTime` from a slot picker, so `end <= start` is not reachable from the UI. Confirm by grep rather than assumption before ticking this box.

- [x] **Task 8 — `ReviewFlagIT` fixture cleanup convention (AC: 7)**
  - [x] Add a nullable `private UUID orphanReviewId;` field, set it to `null` in `@BeforeEach setUp`, assign it in the orphan test before seeding.
  - [x] Move both `DELETE` statements into `@AfterEach tearDown` (`:156`) guarded by `if (orphanReviewId != null)`, and delete the in-test `try/finally`.
  - [x] Keep the assertion block and the `HttpServerErrorException` / `500` / `reviews.coachProfileMissing` expectations byte-for-byte.

- [x] **Task 9 — `deferred-work.md` ledger maintenance (AC: 8)**
  - [x] **Read the file first.** Story creation already deleted the two stale entries, corrected D2's premise inline, and added the `## Last audit: 2026-08-05 (skillars-deferred-14 story creation)` block. Re-doing any of that creates exactly the duplicate-heading/dangling-cross-ref mess `deferred-13`'s review had to clean up.
  - [x] Delete the four items this story closes: `deferred-13` D1, D2, D3 (heading becomes empty → delete it) and the `skillars-3-3` Group C bullet (heading becomes empty → delete it). Each carries an `OWNED BY skillars-deferred-14` annotation naming its AC, so there is no ambiguity about which items are in scope.
  - [x] Match on heading **plus** item id — the file's own preamble warns ids repeat across sections and that two different headings both start with `skillars-10-1`.
  - [x] Amend the existing story-creation audit block rather than adding a new one: change the "annotated as owned by, still open" bullet to record the four as closed by shipped code, and add whatever the implementation actually found that the story-creation read got wrong.
  - [x] Do **not** add a third `## Last audit: 2026-08-05` heading. Two already carry that date, disambiguated by `(deferred-13 code review)` and `(skillars-deferred-14 story creation)`.

- [x] **Task 10 — Full verification**
  - [x] `mvn -o verify` green (unit + IT). Baseline from `deferred-13`: 807 unit + 845 IT, 0 failures.
  - [x] Confirm `BookingServiceConcurrencyIT` still passes — AC4 touches the statuses list it depends on.
  - [x] Confirm `BatchAcceptPaymentIT` and `PaymentLifecycleService`'s batch path still pass — AC3 changes the transaction boundary **two** `AFTER_COMMIT` listeners observe: `PaymentLifecycleService.onBatchBookingAccepted` (now fires on the trailing transaction) and `BookingBatchStatusListener.onBookingStatusChanged` (now fires per booking). Check both, not just the payment one.
  - [x] Add an IT assertion that a fully-successful `acceptAll` ends with `batch.status = FULLY_ACCEPTED` **and** every booking settled by `PaymentLifecycleService` — i.e. the two writers of `batch.status` agree and the settlement event actually fired. A batch reading `FULLY_ACCEPTED` with unsettled bookings is the exact failure AC3a exists to prevent, so assert the settlement, not just the status.

## Dev Notes

### AC1 — why `PENDING` is a sufficient guard

`ReviewSubmittedEvent` has exactly two publishers, both in `ReviewSubmissionService`:

- `submitReview` (`:63-73`) — `setModerationStatus(PENDING)` then `save` then publish.
- `updateReview` (`:98-105`) — `setModerationStatus(PENDING)` then `save` then publish. Edits from `BLOCKED`/`UNDER_REVIEW` are already rejected at `:87-91`, so an edit always starts from `PENDING` or `APPROVED` and resets to `PENDING`.

So at the moment the listener's `REQUIRES_NEW` transaction opens, `PENDING` is the only status the listener itself could be responsible for. Everything that can move a review off `PENDING` must therefore win against this delivery:

- `AdminReviewService.approveReview` / `blockReview` — both take `findByIdForUpdate` and set `APPROVED`/`BLOCKED`. These are the race D1 describes.
- `ReviewFlagService.flag` (`:78-84`) — auto-holds `APPROVED` → `UNDER_REVIEW` with `HeldReason.FLAG_THRESHOLD` once the open-flag count crosses `reviews.autoHoldFlagThreshold`. Its guard is `moderationStatus == APPROVED`, so it can only act on a review this listener has already resolved; it cannot collide with an in-flight delivery, but it *is* a third writer and the code comment should say so rather than implying only admins and duplicate deliveries exist.
- A duplicate `AFTER_COMMIT` delivery of the same event.

All three lose to the stored value under a plain "write only if `PENDING`" rule — no special-casing needed. The guard therefore also makes the listener idempotent under duplicate delivery, a free property worth stating in the code comment.

`CoachReview` has **no `@Version`** (checked `CoachReview.java`), which is why this needs the pessimistic read rather than optimistic locking.

### AC1 — the `REQUIRES_NEW` / fresh-EntityManager subtlety

`ReviewModerationService`'s existing constructor comment (`:31-32`) already asserts that `REQUIRES_NEW` suspends the stale TX1 `EntityManager` bound to the thread during `AFTER_COMMIT` and binds a fresh one. That is what makes `findByIdForUpdate` return **fresh DB state** here — the same reason `AdminReviewService.approveReview`'s comment (`:74-80`) says the locked query is safe there but explicitly warns it is **not** in `BookingService.createBookingRequest`, where an earlier `findById` leaves the row managed and the later locked read returns the stale in-memory instance (which is why that method needs `entityManager.refresh(..., PESSIMISTIC_WRITE)` at `BookingService.java:204`).

The listener does **not** read the review before this point, so no `refresh` is needed. Do not add one speculatively — but do confirm the assumption holds via Task 3's IT rather than trusting the comment.

### AC3 — what actually breaks today, and why the fix is the `deferred-12` pattern

`acceptAll` is `@Transactional`. `bookingService.acceptAndInitiatePayment` is a **different bean**, so the call goes through the proxy and participates in `acceptAll`'s transaction. Spring marks the shared transaction rollback-only when a participating inner transaction fails. The `catch (Exception e)` in the loop therefore suppresses the *exception* but not the *rollback-only marker*: the loop finishes, `batch.setStatus(...)` + `save` run, and commit fails. `deferred-12`'s code review reproduced precisely this shape in `PaymentLifecycleService` — its comment at `:44-48` records that "`UnexpectedRollbackException` leaves no trace". Reuse that pattern; do not invent a new one.

Consequences to keep in mind when wiring the template:

- Each booking now commits independently. That is the semantics `PARTIALLY_ACCEPTED` already assumes — the status branch at `:174-175` exists precisely for it and is currently dead for any failing batch.
- The outer transaction still holds `batch` and the `requestedBookings` list as managed entities. It never mutates the `Booking` instances, so nothing is written back over the inner commits. Do not "refresh" them.
- **`BookingBatchStatusListener` becomes a second, earlier writer of `batch.status` — this is the non-obvious one.** `acceptAndInitiatePayment`'s `INITIATE_PAYMENT` leg publishes `BookingStatusChangedEvent` (`BookingService.java:139-141`), and `BookingBatchStatusListener.onBookingStatusChanged` (`AFTER_COMMIT`) calls `updateBatchStatusFromBooking`, which writes `batch.status`. `REQUIRES_NEW` suspends the outer transaction *including its synchronizations*, so the event now binds to the **per-booking** transaction and the listener fires at each per-booking commit instead of once after `acceptAll`.

  Trace it through both paths before writing code:
  - **Fully-successful batch:** for bookings 1..n−1 the listener early-returns on `requestedCount > 0` (`:161-163`). On booking *n*'s commit `requestedCount == 0`, so it computes `FULLY_ACCEPTED` and **commits it durably** — before `acceptAll` reaches its own status write. Today that listener write is a harmless same-value no-op landing strictly last; after the restructure it lands first and is durable on its own.
  - **Partially-successful batch:** failed bookings stay `REQUESTED` (the accept never ran), so `requestedCount > 0` holds on every delivery and the listener writes nothing at all. `acceptAll`'s own write is the only one, and `PARTIALLY_ACCEPTED` still comes out right.

  The hazard is the first path: `batch.status = FULLY_ACCEPTED` durable, all bookings durable in `PAYMENT_PENDING`, and — if the outer commit then fails — `BatchBookingAcceptedEvent` never published, so `PaymentLifecycleService.onBatchBookingAccepted` never runs and payment is never collected. The batch row looks healthy, so nothing signals a retry. AC3a's trailing `REQUIRES_NEW` is what collapses that window.
- **There is no *automatic* sweeper for stuck `PAYMENT_PENDING`, and the manual hatch does not help here.** No `@Scheduled` method in `src/main` reads `PAYMENT_PENDING` (all 27 checked); `BookingExpiryScheduler.expireStaleRequests` (`:43-46`) only declines `REQUESTED` bookings. `deferred-12` AC4 did add `CANCEL_PARENT` as a transition out of `PAYMENT_PENDING` (`BookingStateMachine.java:34-38`) for exactly this crash window — but it requires **the parent** to notice and act, and the transition map offers no coach, admin or system path. Worse, `PAYMENT_PENDING` is in `ACTIVE_SLOT_STATUSES` and in `V87`'s `WHERE` clause, so a stranded booking holds the coach's slot until someone cancels it. That is why AC3a collapses the window instead of tolerating it. The pre-existing gap is now tracked as D1 under `## Deferred from: skillars-deferred-14 story creation (2026-08-05)` in `deferred-work.md` — **do not build a sweeper in this story.**
- **Rejected mitigation:** making `updateBatchStatusFromBooking` the sole writer and dropping `acceptAll`'s own `batch.setStatus(...)`. It looks tidy but is wrong — on a partial batch the failed bookings stay `REQUESTED`, so `updateBatchStatusFromBooking` early-returns every time and the batch would never leave `PENDING`. AC3's headline outcome depends on `acceptAll` keeping its own write.

### AC4/AC5 — status set and exclusion semantics

- `ACTIVE_SLOT_STATUSES` = `REQUESTED, ACCEPTED, PAYMENT_PENDING, CONFIRMED, UPCOMING, IN_PROGRESS, PAUSED` (`BookingService.java:117-118`). `ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED` drops `REQUESTED` (`:120-121`).
- Use the **excluding-REQUESTED** variant in both new call sites, matching `acceptBooking`. Two overlapping `REQUESTED` bookings competing for one slot is expected in-band behaviour — `V87`'s header comment says so explicitly, and `createBookingRequest` (`:214-215`) deliberately uses the full list only because it is guarding *creation*.
- `V87` covers `ACCEPTED, PAYMENT_PENDING, CONFIRMED, UPCOMING, IN_PROGRESS, PAUSED`. The app-layer check's status set is therefore a superset-compatible match for the constraint on the accept path; the constraint remains the backstop for anything that bypasses the service layer entirely.
- `ApiAdvice` maps `OperationNotAllowedException` → **403** with `errorKey` from the `ErrorCode` (`:271-278`). `BookingError.SLOT_UNAVAILABLE` → `booking.slotUnavailable`. That is the contract `BookingServiceConcurrencyIT:159,214` pins. The DB-constraint fallback path returns 409 for the same key — a known asymmetry, deliberately left alone.

### AC6 — validation plumbing

`booking.api` is covered by `BookingApiAdvice` (`@Order(HIGHEST_PRECEDENCE)`, `basePackages = ...booking.api`), but that advice declares **only** a `PaymentGatewayException` handler. `MethodArgumentNotValidException` therefore falls through to the global `ApiAdvice.methodArgumentNotValidExceptionHandler` (`:410-416`) → `400` + `processFieldErrors`. No new advice needed.

No existing test asserts `403` for an end-before-start payload — grepped `src/test` for the service-layer message and for `requestedEndTime` assertions; the three files touching `requestedEndTime` (`BookingRequestResourceIT`, `BookingBatchResourceIT`, `SuspendedCoachBookingBlockIT`) have no such case. The change is contract-safe.

### Files to touch

**Modify (main):**
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewModerationService.java` — AC1
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java` — AC3, AC4
- `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java` — AC4
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java` — AC4 (visibility of `ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED` only)
- `src/main/java/com/softropic/skillars/platform/booking/contract/CreateBookingRequest.java` — AC6

**Modify (test):**
- `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewModerationServiceTest.java` — AC1 (existing stubs break)
- `src/test/java/com/softropic/skillars/platform/reviews/api/ReviewModerationIT.java` — AC2
- `src/test/java/com/softropic/skillars/platform/reviews/api/ReviewFlagIT.java` — AC7
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingBatchResourceIT.java` — AC3, AC5
- `src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java` — AC4
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java` — AC6
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java` — likely needs new mock wiring for `PlatformTransactionManager`

**Modify (docs):**
- `_bmad-output/implementation-artifacts/deferred-work.md` — AC8

**No new files. No migration. No frontend change. No i18n change.**

### Testing standards

- Integration tests are `*IT.java`, Testcontainers-backed, seeded with raw `jdbcTemplate` inside `transactionTemplate.execute` and torn down in `@AfterEach` — the project-wide pattern (FK-constrained seeding needs fixed ids, which is why Instancio is not used in ITs; see `skillars-5-6` AD4).
- Unit tests use `@ExtendWith(MockitoExtension.class)` with **strict** stubbing — an unused stub is a build failure, which is exactly what Task 2 has to repair.
- Concurrency ITs in this repo use `CyclicBarrier`/`CountDownLatch` + an executor; `BookingServiceConcurrencyIT` is the reference implementation.
- Every concurrency test added here must be mutation-verified. `deferred-13`'s review found that *both* of its barrier-based ITs passed unchanged against the unfixed code, and that even a "hold `SELECT FOR UPDATE`, assert timeout" test passed the mutation — waiting is not the discriminator. What separates locked from unlocked is **what the caller observes after it unblocks**. Design Task 3's assertions on observed state, not on timing.

### Project Structure Notes

- `BookingService`, `BookingBatchService` and `RescheduleService` all live in `com.softropic.skillars.platform.booking.service`, which is what makes the package-private constant in Task 6 the right call instead of promoting it to `booking.contract`.
- `AdminReviewService` lives in `platform.admin.service` but raises `reviews.contract` errors; `ReviewApiAdvice` already covers it via `assignableTypes` (`ReviewApiAdvice.java:26-28`). Task 3's IT crosses that boundary — no additional wiring needed.
- `ReviewModerationService` builds its own `TransactionTemplate` in the constructor; `PaymentLifecycleService` builds its in `@PostConstruct`. Both patterns are in use. For `BookingBatchService` follow `PaymentLifecycleService` (`@PostConstruct`), since `@RequiredArgsConstructor` is already generating its constructor.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` §`Deferred from: code review of skillars-deferred-13-admin-moderation-action-integrity (2026-08-05)` D1, D2, D3]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` §`Deferred from: code review of skillars-3-11-coach-slot-double-booking-prevention (2026-07-31)` D1 — stale]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` §`Deferred from: code review of skillars-3-3-booking-request-approval-workflow Group A / Group C (2026-06-15)`]
- [Source: `_bmad-output/implementation-artifacts/skillars-3-11-coach-slot-double-booking-prevention.md:267` — the "option a not applied" decision that created AC4's gap]
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-13-admin-moderation-action-integrity.md` — AC1's lock pattern, and its review's mutation-verification standard]
- [Source: `_bmad-output/implementation-artifacts/sprint-status.yaml` — `skillars-deferred-12` note, per-booking `REQUIRES_NEW` rationale]
- [Source: `src/main/resources/db/migration/V87__booking_overlap_exclusion_constraint.sql` — constraint scope and the `REQUESTED` exclusion rationale]

## Dev Agent Record

### Agent Model Used

claude-opus-5 (Claude Code)

### Debug Log References

**AC2 mutation verification (the discriminator, per Deferred-13's standard).**
`ReviewModerationIT#adminBlocksWhileGeminiInFlight_blockSurvivesSafeVerdict` was run against the code
with AC1's `PENDING` guard short-circuited (`if (false && current != PENDING)`), guard warning
confirmed absent from the log. Result: **FAILED — `expected: "BLOCKED" but was: "APPROVED"`**, i.e. the
exact D1 symptom. Guard restored → passes. `ReviewModerationServiceTest` also caught the mutation
independently (2 failures). One process note worth recording: the first mutation run appeared to pass,
but the build had failed at the *unit* stage so failsafe never ran and the report read was stale from
the prior green run. Re-run with surefire suppressed to get a real result.

**AC3 pre-fix reproduction (captured before any change to `BookingBatchService`).**
A 2-slot batch whose second slot collides with a `CONFIRMED` booking for the same coach returned:

```
500  {"errorKey":"generic.unknown","message":"An unknown Exception has occurred"}
DataIntegrityViolationException: could not execute batch [Batch entry 1 update booking.bookings
  ... status=('PAYMENT_PENDING') ...] was aborted:
  ERROR: conflicting key value violates exclusion constraint "excl_bkg_coach_slot_overlap"
  ...; constraint [null]
```

Zero bookings accepted, batch left `PENDING` — confirming AC3's claim that `acceptAll` could not
partially succeed. **The mechanism differs from AC3's prediction and the difference matters.** It is
not `UnexpectedRollbackException`: Hibernate defers the loop's UPDATEs to commit and issues them as one
JDBC batch, which fails as a unit. The resulting `DataIntegrityViolationException` carries
`constraint [null]`, so `ApiAdvice`'s name-keyed `CONSTRAINT_MAPPINGS`/`CONFLICT_CONSTRAINTS` lookup
cannot match it and the 409 mapping Story 3.11 wired never applies — hence `generic.unknown`/500 rather
than the 400 or 409 that mapping would produce. This means `deferred-13` D2's *conclusion* ("surfaces
as 500") was correct even though its stated *reason* ("no 409 mapping exists") was wrong; the mapping
exists but is unreachable on the batch path. Recorded in `deferred-work.md`'s audit block.

**AC5 post-fix behaviour.** Same fixture now logs
`Failed to accept booking … ERROR_CODE: SLOT_UNAVAILABLE` and `acceptedCount=1` — the collider is
skipped cleanly by the app-layer pre-check, never reaching the DB constraint.

### Completion Notes List

- **AC1** — `ReviewModerationService` now reads via `findByIdForUpdate` and writes only while the review
  is still `PENDING`; `coachRatingService.recompute` moved inside the guarded branch, which also fixes a
  pre-existing bug where it fired for a review that was not found at all. `lastModifiedAt` untouched
  (`ReviewSubmissionService.updateReview` reads it for the 365-day edit rule).
- **AC2** — Race IT added and mutation-verified in both directions (see Debug Log).
- **AC3** — `acceptAll` runs each booking in a `REQUIRES_NEW` `TransactionTemplate`
  (`PaymentLifecycleService.perBookingTx` pattern). `acceptAndInitiatePayment` untouched.
- **AC3a** — Batch-status write and `BatchBookingAcceptedEvent` moved into a single trailing
  `REQUIRES_NEW` transaction, with the email/name lookups hoisted above it so it contains only the save
  and the publish. `acceptAll` carries an explicit "deliberately NOT atomic" contract comment.
- **AC4/AC5** — Coach lock + `findOverlappingBookings` pre-check added to `acceptAll` (per booking) and
  `RescheduleService.acceptReschedule` (against the *proposed* window). Both use
  `ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED`, widened from `private` to package-private on
  `BookingService` rather than duplicated. Both return 403 `booking.slotUnavailable`, matching the
  existing app-layer paths — `BookingServiceConcurrencyIT` still green.
- **AC6** — `@AssertTrue isEndAfterStart()` on `CreateBookingRequest`, null-tolerant so `@NotNull` owns
  the null case. Two ITs pin 400 for end-before-start and for a zero-length window. Service-layer check
  retained for non-HTTP callers. `ApiAdvice.processFieldErrors` handled the accessor-level violation
  with no change needed. No frontend change: `BookingRequestPage.vue` derives the end time from a slot
  picker, so the state is unreachable from the UI.
- **AC7** — `ReviewFlagIT` orphan fixture tracked in a nullable field and cleaned in `@AfterEach`;
  in-test `try/finally` removed.
- **AC8** — `deferred-work.md`: the three `deferred-13` items and the `3-3` Group C bullet deleted
  (both headings became empty and were removed), and the story-creation audit block amended with what
  the implementation actually found about D2 (see Debug Log).
- **Three predicted test breakages all materialised and were fixed as specified:**
  `ReviewModerationServiceTest` (strict stubs on `findById`), `BookingBatchServiceTest` (`@InjectMocks`
  does not run `@PostConstruct` → null templates; `initTransactionTemplates()` made package-private and
  called from `@BeforeEach`), `RescheduleServiceTest` (unstubbed `findByIdForUpdate`).
- **One assertion corrected during implementation, not a spec change:** the AC5 IT initially asserted
  the accepted booking sits in `PAYMENT_PENDING`. It does not — the trailing transaction publishes the
  settlement event and `PaymentLifecycleService` settles it onward. That only became observable *because*
  the fix works; pre-fix the 500 meant nothing settled at all. Assertion changed to "not `REQUESTED`",
  plus a `payment.booking_payments` row check that pins settlement actually fired (AC3a's real risk is a
  batch row that reads healthy while nothing settled — invisible to a status-only assertion).

### Review Findings

- [x] [Review][Patch] Dangling cross-references in `deferred-work.md` still point at the `deferred-13` code-review heading this diff deletes [`_bmad-output/implementation-artifacts/deferred-work.md:78,89-90`] — **confirmed and fixed.** Both rewritten to record that those items were closed by this story rather than pointing at a heading that no longer exists.
- [x] [Review][Patch] No test asserts settlement (not just batch status) for a fully-successful `acceptAll` — Task 10's explicit ask [`src/test/java/com/softropic/skillars/platform/booking/api/BookingBatchResourceIT.java:263`] — **partially valid; the stated rationale is wrong but the gap it points at is real, and it is now closed.** `BatchAcceptPaymentIT.acceptAll_creditBasedBatch_bookingsReachConfirmed` (`:114-126`) already drives `acceptAll` end-to-end and asserts **both** bookings reach `CONFIRMED` — which is a *stronger* settlement assertion than a `booking_payments` row check, since `CONFIRMED` is only reachable via `PAYMENT_CAPTURED` from the settlement listener. So settlement for a fully-successful batch was covered. The genuine gap was narrower: no single test bound *status* and *settlement* together, and AC3a's specific failure mode is a batch reading `FULLY_ACCEPTED` while nothing settled. Settlement assertion added to `acceptAll_asOwningCoach_returns204AndUpdatesBookingsAndBatch` so that invariant is pinned in one place.
- [x] [Review][Patch] Redundant fully-qualified `java.math.BigDecimal` despite the top-of-file import [`src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java:225`] — **confirmed and fixed.**
- [x] [Review][Defer] `RescheduleService.acceptReschedule`'s new coach-lock wait widens a pre-existing TOCTOU race with `declineReschedule` [`src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java:94-145`] — deferred, pre-existing race class, widened by AC4's blocking lock acquisition
- [x] [Review][Defer] `acceptOneBooking`/`acceptReschedule` never re-check `SUSPENDED` after acquiring the coach lock, mirroring a pre-existing gap in `BookingService.acceptBooking` that Task 6 explicitly directed them to copy [`src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java:263-265`, `RescheduleService.java:128-129`] — deferred, pre-existing pattern
- [x] [Review][Defer] `ReviewModerationService`'s AC1 `PENDING`-only guard can't distinguish a stale in-flight Gemini verdict from an earlier edit from a fresh one when a review is re-edited mid-moderation [`src/main/java/com/softropic/skillars/platform/reviews/service/ReviewModerationService.java:93-124`] — deferred, but **narrower than the finding states, to the point of being practically unreachable.** Reaching it needs two `ReviewSubmittedEvent` deliveries for one review in flight simultaneously. `ReviewSubmissionService.updateReview` (`:82-86`) rejects any edit whose `lastModifiedAt` is within the last 365 days, and the listener deliberately does not touch `lastModifiedAt`, so the second event cannot be published until 365 days after the first — by which time the first Gemini call has long since returned. Recorded because the *design* limitation is real (the guard carries no version or event nonce, so it would not survive a relaxation of the 365-day rule), not because the state is reachable today.
- [x] [Review][Defer] `acceptAll`'s batch-status formula ignores bookings moved out of `REQUESTED` before `acceptAll` starts, and now races the earlier-firing `BookingBatchStatusListener` write introduced by AC3's per-booking commits [`src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java:215-244,285-317`] — deferred, but the finding **understates one half and overstates the other**, so recording the trace. The formula bug is pre-existing: `acceptedIds.size() == requestedBookings.size()` compares against the `REQUESTED` subset at loop start, so a batch containing an already-`DECLINED` booking yields `FULLY_ACCEPTED` when it should be `PARTIALLY_ACCEPTED`. What this diff changed is **which writer wins**: previously `acceptAll` wrote first and the `AFTER_COMMIT` listener corrected it afterwards (the listener counts *all* bookings, so it computed the right value); now the listener fires at the last per-booking commit and the trailing transaction overwrites it with the naive value. The winner flipped, and it flipped toward the wrong value — that is a behaviour change this diff introduced, not merely a pre-existing bug. **It is nonetheless self-correcting in practice:** settlement transitions each booking and republishes `BookingStatusChangedEvent`, so the listener runs again after the trailing commit and restores the correct value. The exposure is a transient wrong read between the trailing commit and settlement. Left deferred because the real fix is to unify the two status formulas, and `updateBatchStatusFromBooking` is shared with the individual accept/decline paths — genuinely outside this story.

### File List

**Modified — main:**
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewModerationService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/CreateBookingRequest.java`

**Modified — test:**
- `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewModerationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/reviews/api/ReviewModerationIT.java`
- `src/test/java/com/softropic/skillars/platform/reviews/api/ReviewFlagIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingBatchResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceTest.java`

**Modified — docs:**
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

**No new files. No migration. No frontend change. No i18n change.**

## Change Log

- 2026-08-05 — Full `mvn -o verify` green: **812 unit + 850 IT, 0 failures, 0 errors, 4 skipped**
  (18m18s). `BookingServiceConcurrencyIT` and `BatchAcceptPaymentIT` both still pass — the two suites
  most exposed to AC4's status-set change and AC3/AC3a's transaction-boundary change.
- 2026-08-05 — Deferred-14 implemented. AC1–AC8 complete. Headline: the Gemini moderation listener can
  no longer revert an admin decision (mutation-verified), and `acceptAll` can now partially succeed
  instead of failing whole-batch with a 500 (pre-fix behaviour reproduced first). Overlap pre-checks
  added to the two paths that bypassed them; end-before-start booking payloads now 400 instead of 403.
