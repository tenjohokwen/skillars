# Story Deferred-67: Booking Completion Lock-Conflict Error Code & Exception Chaining

Status: done

## Story

As an engineer operating this platform,
I want `BookingCompletionService`'s seven `OptimisticLockingFailureException` catch blocks to carry a
dedicated, correctly-classified error code and to consistently chain the original exception as cause,
so that a booking-status concurrency conflict is distinguishable from a real authorization failure at the
wire level, and no debugging stack trace is lost to a swallowed cause.

### Why this story exists

Scoped by re-mining `deferred-work.md` for items touching the Booking/Availability/Reschedule module,
immediately after `skillars-deferred-66` shipped (still `review` status at the time of this story's
creation — per this series' established precedent of `skillars-deferred-65` starting while
`skillars-deferred-64` was itself still in review, this does not block starting the next story). The
newest, most specific section of the ledger — `## Deferred from: code review of skillars-deferred-66
(2026-08-25)` — carries four items, all in `BookingCompletionService.java`, all found by that story's own
code review after it added `OptimisticLockingFailureException` handling to 3 previously-unguarded methods
(`startSession`, `initiateQuickComplete`, `submitWrapUp`'s `LIVE`-mode branch) to match the 4 that already
had it (`endSession`, `pauseSession`, `resumeSession`, `confirmCompletion`). Three of the four are real,
open, and actionable — this story's AC1 and AC2. The fourth (imperative "retry" wording) was explicitly
recorded as an intentional, accepted pattern — **not picked up here.**

**Live-verified against current source** (not trusted from ledger text alone, per this file's own stated
convention): `BookingCompletionService.java` today has all 7 methods catching
`OptimisticLockingFailureException` and converting it to `OperationNotAllowedException` +
`SecurityError.MISSING_RIGHTS`. Three sites (`startSession:56-61`, `initiateQuickComplete:120-123`,
`submitWrapUp`'s `LIVE` branch `:162-165`) already chain the cause via the
`OperationNotAllowedException(String, Throwable, ErrorCode)` constructor `skillars-deferred-66` added, and
carry a comment explaining the `MISSING_RIGHTS` reuse. The other four (`endSession:78`, `pauseSession:91`,
`resumeSession:104`, `confirmCompletion:194`) neither chain the cause nor carry that comment, and
`confirmCompletion`'s message string is `"Session already confirmed"` — wrong for this exception (it fires
on ordinary concurrent modification, not specifically on an already-confirmed booking).

**A finding beyond what the ledger items state, worth recording precisely:** `SecurityError.MISSING_RIGHTS`
has no entry in any of the four `i18n/messages*.properties` files (`grep -n "MISSING_RIGHTS"
src/main/resources/i18n/messages_en.properties` returns nothing). `ApiAdvice.operationDeniedHandler` resolves
the user-facing message via `messageSource.getMessage(exception.getErrorCode().getErrorCode(), ...,
defaultMessage, locale)`, where `defaultMessage` is the handler's own hardcoded `"The operation is not
granted. You can contact help desk"`. So today, every one of these 7 endpoints' concurrency-conflict
response carries that generic "not granted" text in its `errorMsg.message` field, not the more specific
"Booking status changed concurrently — retry" wording that only appears in the exception's *internal*
Java message (used for server-side logging, never sent to the client). **This has no live user-facing
effect right now** — every frontend consumer of these 7 endpoints (`ActiveSessionScreen.vue`'s
`handleEndSession`/`handlePauseSession`/`handleResumeSession`, `ParentBookingsPage.vue`'s
`handleConfirmCompletion`) uses a bare `catch { ... }` that shows its own fixed local i18n string
(`booking.completion.actionError` / `error.verificationFailed`) regardless of what the backend returns —
verified by direct read of both files. So AC1 below is correctness/observability work (server logs, API
contract, any future frontend consumer that does read `errorMsg.message`), not a live UI bug fix — stated
plainly so the dev agent doesn't oversell it in the PR/commit message.

**Decision made with the project owner during this story's creation:** keep the existing
`OperationNotAllowedException` / HTTP 403 routing for all 7 sites (do not let the exception propagate
unhandled to `ApiAdvice`'s existing `ObjectOptimisticLockingFailureException` → 409 handler, which was the
other option considered). Add a **new, dedicated `BookingError` code** instead, so the wire-level error code
correctly distinguishes "retry, this was a race" from a genuine "you don't have the rights" rejection,
without touching HTTP status, existing tests' exception-type assertions, or the pattern `skillars-deferred-66`
just shipped.

## Acceptance Criteria

1. **Add `BookingError.CONCURRENT_MODIFICATION` (errorKey `booking.concurrentModification`) and route all
   seven `BookingCompletionService` `OptimisticLockingFailureException` catches through it instead of
   `SecurityError.MISSING_RIGHTS`.**
   `[src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java:30-61]` — add
   `CONCURRENT_MODIFICATION` as a new enum constant (after `NO_SHOW_TOO_EARLY`, the most recent addition)
   and map it to `"booking.concurrentModification"` in the `getErrorCode()` switch. Extend the class-level
   doc comment with a short paragraph following the two existing ones (the `skillars-deferred-30` and
   `skillars-deferred-31` splits), explaining this one differs in *kind*, not just *source*: it does not
   split an existing authorization throw into a more specific one — it replaces a genuine authorization code
   (`SecurityError.MISSING_RIGHTS`) that was being reused for a non-authorization case (a concurrent-write
   race). HTTP status is unaffected either way — `ApiAdvice.operationDeniedHandler` still maps
   `OperationNotAllowedException` to 403 unconditionally, same as the doc comment's existing note already
   states.

   **Add the i18n message key to all four backend properties files**, inserting alongside the existing
   `booking.noShowTooEarly` entry (same relative position in each file, matching this project's established
   per-locale ordering):
   - `[src/main/resources/i18n/messages.properties:88]` (base/fallback, mirrors `messages_en.properties`)
   - `[src/main/resources/i18n/messages_en.properties:132]` —
     `booking.concurrentModification=This booking was just updated by another action. Please try again.`
   - `[src/main/resources/i18n/messages_de.properties:75]` —
     `booking.concurrentModification=Diese Buchung wurde soeben durch eine andere Aktion aktualisiert. Bitte versuchen Sie es erneut.`
   - `[src/main/resources/i18n/messages_fr.properties:122]` —
     `booking.concurrentModification=Cette réservation vient d'être mise à jour par une autre action. Veuillez réessayer.`

   **In `BookingCompletionService.java`**, add `import
   com.softropic.skillars.platform.booking.contract.BookingError;` and change all 7
   `OptimisticLockingFailureException` catch blocks (`startSession`, `endSession`, `pauseSession`,
   `resumeSession`, `initiateQuickComplete`, `submitWrapUp`'s `LIVE`-mode branch, `confirmCompletion`) to
   throw with `BookingError.CONCURRENT_MODIFICATION` instead of `SecurityError.MISSING_RIGHTS`. **Do not
   remove the `SecurityError` import** — `verifyCoachOwnership` and `verifyStatus` still throw
   `SecurityError.MISSING_RIGHTS` for genuine authorization/state failures, unrelated to this AC. **Remove
   the three explanatory comments** above `startSession`'s, `initiateQuickComplete`'s, and `submitWrapUp`'s
   catch blocks that justify reusing `MISSING_RIGHTS` (`skillars-deferred-66`'s review added them) — they
   describe a choice this AC reverses, and the new dedicated code is self-explanatory without a comment.

2. **Fix `confirmCompletion()`'s wrong exception message and chain the original exception as cause in the
   four catch blocks that still don't.**
   `[src/main/java/com/softropic/skillars/platform/booking/service/BookingCompletionService.java]` —
   `endSession` (`:78`), `pauseSession` (`:91`), `resumeSession` (`:104`), and `confirmCompletion` (`:194`)
   currently call the two-argument `OperationNotAllowedException(String, ErrorCode)` constructor, discarding
   the caught `OptimisticLockingFailureException`. Change all four to the three-argument
   `OperationNotAllowedException(String, Throwable, ErrorCode)` constructor (already added by
   `skillars-deferred-66`, used unchanged by the other three sites), passing the caught exception `e` as
   cause. Additionally, change `confirmCompletion`'s message string from `"Session already confirmed"` to
   `"Booking status changed concurrently — retry"` — the exact string every other one of the 7 sites uses,
   since this exception fires on any concurrent write conflict, not specifically an already-confirmed
   booking (a booking confirmed by a genuinely separate action would fail `verifyStatus`'s earlier check
   with a different message entirely, before ever reaching this catch). After this AC and AC1, **all seven
   catch blocks are byte-for-byte identical**:
   ```java
   } catch (OptimisticLockingFailureException e) {
       throw new OperationNotAllowedException("Booking status changed concurrently — retry", e, BookingError.CONCURRENT_MODIFICATION);
   }
   ```

   **Tests:** update `BookingCompletionServiceTest.java`
   (`[src/test/java/com/softropic/skillars/platform/booking/service/BookingCompletionServiceTest.java]`).
   Add `import com.softropic.skillars.platform.booking.contract.BookingError;`. Strengthen the 3 existing
   concurrency tests (`startSession_concurrentModification_throwsRetryableException:192-201`,
   `initiateQuickComplete_concurrentModification_throwsRetryableException:203-212`,
   `submitWrapUp_liveMode_concurrentModification_throwsRetryableException:214-224`) with an additional
   `.extracting(t -> ((OperationNotAllowedException) t).getErrorCode()).isEqualTo(BookingError.CONCURRENT_MODIFICATION)`
   assertion chained after the existing `.hasCauseInstanceOf(...)`. Add four new test cases, one per
   newly-covered method, mirroring the same `assertThatThrownBy` shape:
   - `endSession_concurrentModification_throwsRetryableException` — set booking status to `IN_PROGRESS`,
     mock `bookingService.transition(eq(BOOKING_ID), eq(BookingEvent.COMPLETE_PENDING), any())` to throw.
   - `pauseSession_concurrentModification_throwsRetryableException` — set status to `IN_PROGRESS`, mock
     `transition(eq(BOOKING_ID), eq(BookingEvent.PAUSE), any())` to throw.
   - `resumeSession_concurrentModification_throwsRetryableException` — set status to `PAUSED`, mock
     `transition(eq(BOOKING_ID), eq(BookingEvent.RESUME), any())` to throw.
   - `confirmCompletion_concurrentModification_throwsRetryableException` — status is already
     `COMPLETED_PENDING_CONFIRMATION` from `setUp()`; mock `transition(eq(BOOKING_ID),
     eq(BookingEvent.COMPLETE), any())` to throw. Do **not** stub `completionDataRepository.findByBookingId`
     — `transition()` is called before that lookup in `confirmCompletion`, so the mocked throw short-circuits
     the method before it's reached.
   Each new test asserts `isInstanceOf(OperationNotAllowedException.class)`,
   `.hasCauseInstanceOf(OptimisticLockingFailureException.class)`, and the same `errorCode` extraction
   assertion as the strengthened existing 3, for **7 total concurrency-conflict tests** (3 strengthened +
   4 new), one per method, covering all 7 methods once shipped (up from today's 3-of-7 coverage —
   `skillars-deferred-66` deliberately left the other 4 untested as out of its own bounded scope; this story
   finally touches all 7 catch blocks' code, so covering all 7 with tests is in scope here).

## Tasks / Subtasks

- [x] Task 1: Dedicated `BookingError` code for lock conflicts (AC1)
  - [x] 1.1: Add `CONCURRENT_MODIFICATION` to `BookingError` enum + `getErrorCode()` switch + doc comment
  - [x] 1.2: Add `booking.concurrentModification` key to `messages.properties`, `messages_en.properties`,
        `messages_de.properties`, `messages_fr.properties`
  - [x] 1.3: Import `BookingError` in `BookingCompletionService.java`; swap `SecurityError.MISSING_RIGHTS` →
        `BookingError.CONCURRENT_MODIFICATION` in all 7 catch blocks
  - [x] 1.4: Remove the 3 now-stale `MISSING_RIGHTS`-reuse explanatory comments
- [x] Task 2: Message fix + exception chaining for the remaining 4 sites (AC2)
  - [x] 2.1: Fix `confirmCompletion`'s message string
  - [x] 2.2: Chain cause in `endSession`, `pauseSession`, `resumeSession`, `confirmCompletion` via the
        existing 3-arg `OperationNotAllowedException` constructor
  - [x] 2.3: Strengthen the 3 existing concurrency tests with an `errorCode` assertion
  - [x] 2.4: Add 4 new concurrency tests, one per newly-covered method
- [x] Task 3: Ledger hygiene — mark the 3 picked-up items `[PICKED UP by skillars-deferred-67 ACn]` in
      `deferred-work.md`'s `## Deferred from: code review of skillars-deferred-66` section now; flip to
      `[CLOSED by skillars-deferred-67 ACn: ...]` once AC1/AC2 actually ship. Leave the 4th item (imperative
      "retry" wording) untouched — not picked up by this story.

## Dev Notes

**This is a 7-call-site, copy-the-target-shape change — do not redesign the concurrency handling.** The
target catch-block shape is given verbatim in AC2 above; every one of the 7 sites ends up identical to it.
Do not introduce a different message, a different exception type, a retry loop, or change HTTP status.

**Both ACs touch the same 7 blocks — implement together, not as two passes.** AC1 (the `ErrorCode` swap)
and AC2 (message fix + chaining) are separated only because they were two distinct ledger findings; there
is no reason to edit each catch block twice. Land the final byte-for-byte-identical shape directly.

**Do not touch `verifyCoachOwnership` or `verifyStatus`.** Both still throw `SecurityError.MISSING_RIGHTS`
for real authorization/precondition failures — genuinely out of scope, unrelated to the concurrency-conflict
catches this story fixes.

**No live frontend behavior changes.** Every consumer of these 7 endpoints ignores `errorMsg.message` and
shows its own fixed local string on any error (verified in "Why this story exists" above) — do not add
frontend work not requested by these ACs; there is none needed.

**Existing test file to extend (do not create a new one):** `BookingCompletionServiceTest.java`.

### Project Structure Notes

Backend only: `platform.booking.contract.BookingError`, `platform.booking.service.BookingCompletionService`,
and the four `src/main/resources/i18n/messages*.properties` files. No new migrations, no frontend changes,
no new classes.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md`, `## Deferred from: code review of
  skillars-deferred-66 (2026-08-25)` — source of all 3 items this story picks up.
- `skillars-deferred-66` — shipped the 3-of-7 partial fix and the `OperationNotAllowedException` 3-arg
  constructor this story reuses; its own code review filed the 4 items this story re-mines.
- `platform.booking.contract.BookingError` class doc comment — the established precedent and rationale for
  splitting a reused `SecurityError.MISSING_RIGHTS` into a dedicated, more specific code.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

None — no failures encountered. `mvn -o test -Dtest=BookingCompletionServiceTest`: 14/14 green (10 pre-existing tests unaffected in behavior, 3 strengthened with an `errorCode` assertion, 4 new concurrency-conflict tests added). `mvn verify` not run per `docs/validation-strategy.md`.

### Completion Notes List

- AC1: Added `BookingError.CONCURRENT_MODIFICATION` (`booking.concurrentModification`) with a doc-comment paragraph explaining it replaces a genuine authorization code reused for a non-authorization case (unlike the class's two prior code-splits). Added the i18n key to all four `messages*.properties` files, positioned directly after `booking.noShowTooEarly`. Imported `BookingError` into `BookingCompletionService` and swapped all 7 `OptimisticLockingFailureException` catches from `SecurityError.MISSING_RIGHTS` to `BookingError.CONCURRENT_MODIFICATION`; removed the 3 now-stale `MISSING_RIGHTS`-reuse comments (`startSession`, `initiateQuickComplete`, `submitWrapUp`'s `LIVE` branch). `SecurityError` import kept — still used by `verifyCoachOwnership`/`verifyStatus`/two inline status guards, unrelated to this AC.
- AC2: Fixed `confirmCompletion`'s message from `"Session already confirmed"` to `"Booking status changed concurrently — retry"`. Chained the caught exception as cause (3-arg `OperationNotAllowedException` constructor) in the 4 sites that previously discarded it (`endSession`, `pauseSession`, `resumeSession`, `confirmCompletion`). All 7 catch blocks are now byte-for-byte identical, matching the spec's target shape exactly.
- Tests: strengthened the 3 existing concurrency tests (`startSession`/`initiateQuickComplete`/`submitWrapUp` LIVE-mode) with an `errorCode` extraction assertion; added 4 new tests (`endSession`/`pauseSession`/`resumeSession`/`confirmCompletion`), each asserting exception type, chained cause, and `BookingError.CONCURRENT_MODIFICATION`. 7 of 7 methods now covered for the concurrency-conflict path (up from 3 of 7).
- Task 3: Flipped all 3 picked-up `deferred-work.md` ledger items (the `confirmCompletion` message bug, the `MISSING_RIGHTS` misclassification, the missing cause-chaining) from `[PICKED UP by skillars-deferred-67 ACn]` to `[CLOSED by skillars-deferred-67 ACn: ...]` with a one-line mechanism note each. The 4th item (imperative "retry" wording) was left untouched, as scoped.
- No frontend changes — none needed; verified in the story's own "Why this story exists" section that every consumer of these 7 endpoints ignores `errorMsg.message`.

### Review Findings

**Acceptance Auditor:** ✅ All ACs passed. 0 violations.

**Edge Case Hunter:** All 7 catch blocks verified byte-for-byte identical. i18n keys verified in all 4 locales. All 7 test paths trigger correctly.

**Findings:**

- [x] [Review][Patch] Test status setup style consistency [BookingCompletionServiceTest.java:231-275] — submitWrapUp and confirmCompletion exception tests rely on `setUp()`'s default `COMPLETED_PENDING_CONFIRMATION` status, while the other 5 methods' exception tests explicitly set their status before calling the method. Current implementation is correct; this is a consistency improvement for future-proofing. **Resolved:** both tests now explicitly call `booking.setStatus(BookingStatus.COMPLETED_PENDING_CONFIRMATION.name())` before mocking/asserting, matching the other 5 methods' style. `mvn -o test -Dtest=BookingCompletionServiceTest` 14/14 green post-patch.

- [x] [Review][Defer] SecurityError.MISSING_RIGHTS reuse check in other services — verify that other services in the booking module do not have the same pattern (reusing an authorization error code for concurrency conflicts). This story scoped to BookingCompletionService only; pre-existing issue, out-of-scope.

### File List

- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java` (modified)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingCompletionService.java` (modified)
- `src/main/resources/i18n/messages.properties` (modified)
- `src/main/resources/i18n/messages_en.properties` (modified)
- `src/main/resources/i18n/messages_de.properties` (modified)
- `src/main/resources/i18n/messages_fr.properties` (modified)
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingCompletionServiceTest.java` (modified)
- `_bmad-output/implementation-artifacts/deferred-work.md` (modified — ledger hygiene)

## Change Log

- 2026-08-25: Implementation complete. AC1 (dedicated `BookingError.CONCURRENT_MODIFICATION` code + i18n) and AC2 (message fix + cause chaining) shipped; all 7 `BookingCompletionService` catch blocks now byte-for-byte identical. Test coverage for the concurrency-conflict path extended from 3-of-7 to 7-of-7 methods (14/14 green). Ledger hygiene: 3 `deferred-work.md` items flipped `[PICKED UP]` → `[CLOSED]`. Status: review.
- 2026-08-25: Code review complete, status done (Acceptance Auditor: 0 AC violations; Edge Case Hunter confirmed all 7 catch blocks byte-for-byte identical, i18n keys present in all 4 locales, all 7 test paths trigger correctly). 1 patch applied — `submitWrapUp`/`confirmCompletion`'s concurrency tests now explicitly set `booking.status` to `COMPLETED_PENDING_CONFIRMATION` before asserting, matching the other 5 methods' style instead of relying on `setUp()`'s default; `mvn -o test -Dtest=BookingCompletionServiceTest` 14/14 green post-patch. 1 finding deferred — whether other booking-module services reuse `SecurityError.MISSING_RIGHTS` for concurrency conflicts, filed as a fresh `deferred-work.md` section (`## Deferred from: code review of skillars-deferred-67`), out of this story's `BookingCompletionService`-only scope.
