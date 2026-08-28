# Story skillars-deferred-78: Availability write-lock parity, reschedule signature wiring, and session-cancellation guard

Status: done

<!-- Revised 2026-08-28 after senior-dev-review (story-review.md): every "verify X" instruction the
review flagged has been resolved by direct code investigation and replaced with the confirmed fact
below (with citation) rather than left as a task for the dev agent. AC1 widened in scope after the
review's precedent-verification concern surfaced a real, previously-unflagged bug (see AC1). One
genuine product decision (AC8 backfill) was brought back to the project owner; answer incorporated
below. -->

## Story

As the platform owner,
I want the remaining open Booking/Availability/Reschedule deferred-work.md items closed — the availability write-side race (both the batch and single-booking paths), the reschedule staleness-guard parity gap, availability display ordering, SSE polling backoff, booking-event 403 diagnosability, weekStart bounds, notification-listener defensive hardening, and orphaned session plans on booking cancellation — so that the module's remaining ledger residue (accumulated across `skillars-deferred-49` through `skillars-deferred-77`) is closed out rather than carried forward indefinitely,
so that concurrent coach/parent actions on availability and bookings behave correctly under race conditions, notifications and diagnostics are reliable, and no orphaned session-plan or availability-display bugs remain in production.

## Acceptance Criteria

1. **Close the availability write-side TOCTOU for real, by acquiring the per-coach lock *before* reading `CoachAvailabilityWindow` rows — in every write path that validates against them, not just the one the ledger named.**

   **Confirmed during review that the originally-cited "precedent" does not actually do this today.** `BookingService.createBookingRequest` (`BookingService.java:175-256`) reads `windows` at line 229 (`List<CoachAvailabilityWindow> windows = coachAvailabilityWindowRepository.findByCoachId(req.coachId())`) and runs the signature check and `isSlotWithinAvailabilityWindow` against it — all of this happens **before** the per-coach lock is acquired at line 250 (`CoachProfile lockedCoach = lockRetryer.withBoundedRetry(...)`). The existing lock only serializes the *booking-overlap* check that follows it, not the *availability-window* read. So `BookingService.createBookingRequest` itself has the identical write-side TOCTOU the ledger only flagged for `BookingBatchService` — this was never previously identified. Closing it requires reordering, not just copying, the existing pattern.

   - **`BookingService.createBookingRequest`**: move the lock-acquisition block (currently lines 250-256) to immediately after the duration-parity check (after line 227, before the `windows` fetch at line 229) — i.e. lock the coach row first, then do the window fetch + signature check + `isSlotWithinAvailabilityWindow` check + overlap check, all under that one lock, all using `lockedCoach` in place of the earlier unlocked `coach` where its status is read. Do **not** move the lock before the cheap field validations (start-time-in-past, end-after-start, duration-match at lines 202-227) — those stay unlocked and first, exactly as the existing `UAT.2 AC3` comment on line 191-193 explains ("so a malformed request costs neither a window query nor a row lock"); only the window-read-through-overlap-check portion moves under the lock.
   - **`BookingBatchService.createBatch`**: acquire the same per-coach lock immediately before the fresh re-check block already described in the `skillars-deferred-69 AC7` comment (`BookingBatchService.java:185` area, right before `Duration freshRequiredDuration = sessionDurationResolver.resolve(...)`), and keep it held through that block's writes. This AC's original scope — closing exactly this gap — is unchanged; only the reference precedent above it has been corrected.
   - **`RescheduleService.validateRescheduleProposal`**: currently has **no lock at all** — its `windows` fetch (`RescheduleService.java:183`, `coachAvailabilityWindowRepository.findByCoachId(booking.getCoachId())`) is fully unlocked. Add the identical per-coach lock immediately before that fetch. (AC2 below adds the signature check itself to this same method; that check is meaningless against concurrent edits unless it runs under this lock, so AC2 depends on this AC.)
   - **Lock-span semantics (resolves the review's "holding through commit" ambiguity)**: no explicit "release" step is needed or should be added — a Postgres row lock taken via `PESSIMISTIC_WRITE` inside a `@Transactional` method is held automatically until that transaction commits or rolls back. "Holding the lock through commit" simply means: acquire it before the window read, and do not fetch `CoachAvailabilityWindow` a second time from a different transaction/connection after that point. All three methods above are already `@Transactional` (`createBookingRequest` and `createBatch` at their own method level; `validateRescheduleProposal` inherits its transaction from `requestReschedule`/`requestRescheduleAsCoach`, both `@Transactional` — confirmed, `validateRescheduleProposal` is a private same-class call, no proxy bypass concern since it's never invoked externally).
   - **`AvailabilityService.addWindow`, `updateWindow`, `deleteWindow`** (`AvailabilityService.java:242,255,270`): replace their unlocked `requireProfile(userId)` call with the two-step pattern `BookingService.createBookingRequest` already uses elsewhere in the same file for its *own* coach lookup (confirmed at `BookingService.java:187-188` for the unlocked step, `245-256` for the locked step): resolve the coach by `userId` via the existing unlocked `requireProfile` first (for a clean 404 before taking any lock), then re-lock by the resolved id: `lockRetryer.withBoundedRetry(() -> { CoachProfile c = coachProfileRepository.findByIdForUpdate(profile.getId()).orElseThrow(...); entityManager.refresh(c, LockModeType.PESSIMISTIC_WRITE); return c; })`. Without this, none of the three lock-acquisition points above actually serialize against anything — a pessimistic lock only works if both the reader-that-must-not-race and the writer-that-must-not-race take it. `AvailabilityService` needs `PessimisticLockRetryer` and `EntityManager` injected via its constructor (it does not have them today — confirmed by its current field list; `BookingService`'s constructor is the reference for what to add).
   - **`addBlock`/`deleteBlock` are confirmed out of scope, not just assumed**: `isSlotWithinAvailabilityWindow` (`BookingService.java:904`) takes only a `windows` (`CoachAvailabilityWindow`) parameter and never reads `CoachAvailabilityBlock` anywhere in its body — confirmed by direct read. A concurrent block edit cannot affect the outcome of any check this AC is closing, because blocks are never consulted by that check in the first place. No block-side lock is needed for this TOCTOU.
   - Test: extend `BookingServiceTest`, `BookingBatchServiceTest`, and `RescheduleServiceTest` each with a case proving a window mutation and a booking/reschedule-proposal write against the same coach are serialized (one blocks until the other's transaction completes) rather than both reading pre-lock state — mirror this codebase's existing coach-suspension-race test structure in `BookingServiceTest` (the one covering `deferred-12` AC3's re-check-under-lock pattern) for the shape of a concurrency test using two threads/transactions against an in-memory or Testcontainers DB.

2. **Wire `RescheduleService` to the `availabilitySignature` GET-vs-POST staleness guard that `BookingService.createBookingRequest` and `BookingBatchService.createBatch` already have** (`skillars-deferred-71` AC2 / `skillars-deferred-72` AC4). Confirmed still missing: `CreateRescheduleRequest` (`src/main/java/com/softropic/skillars/platform/booking/contract/CreateRescheduleRequest.java`) has no `availabilitySignature` field at all, and `RescheduleService.validateRescheduleProposal` never calls `AvailabilityService.computeAvailabilitySignature`. Depends on AC1's lock reordering in this same method — implement AC1's lock move in `validateRescheduleProposal` first, then add this check inside that locked section.
   - Add `String availabilitySignature` (nullable) to `CreateRescheduleRequest`.
   - In `validateRescheduleProposal`, immediately after the (now-locked, per AC1) `windows` fetch and before the `isSlotWithinAvailabilityWindow` call: if `req.availabilitySignature() != null`, compute `AvailabilityService.computeAvailabilitySignature(windows, originalDuration)` and throw `OperationNotAllowedException` with `BookingError.AVAILABILITY_CHANGED` on mismatch. **Confirmed variable to reuse**: `originalDuration` is computed at `RescheduleService.java:172-173` (`Duration originalDuration = Duration.between(booking.getRequestedStartTime(), booking.getRequestedEndTime());`) a few lines above the window fetch — reuse it exactly as named there; do not resolve a second duration.
   - Frontend wiring is explicitly **out of scope for this AC**: the reschedule proposal flow (`booking.store.js:486` calling `requestReschedule`, invoked from `ParentBookingsPage.vue`) does not currently fetch a fresh availability calendar before submitting a proposal, unlike `BookingRequestPage.vue`'s initial-booking flow — there is no existing signature to thread through yet. This AC adds backend support only (optional field, `null` = no check, fully backward compatible); wiring the frontend to fetch-and-echo a signature for reschedule proposals is a UX feature addition belonging in a future story once that flow is designed, not a bug fix.
   - Test: `RescheduleServiceTest` cases for (a) matching signature accepted, (b) stale signature rejected with `AVAILABILITY_CHANGED`, (c) `null` signature (today's default) unaffected — mirror the equivalent tests already covering this in `BookingServiceTest`/`BookingBatchServiceTest`.

3. **Fix nondeterministic availability window display order.** `CoachAvailabilityWindowRepository.findByCoachId` (`src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachAvailabilityWindowRepository.java:10`) has no `ORDER BY`, so `AvailabilityService.getAvailabilityCalendar`'s `windowResponses` (and every other caller) return windows in undefined order — confirmed still open (`skillars-deferred-18` D2; only the week-scoping-bounds half was closed by `skillars-deferred-65` AC3).
   - **Confirmed sort semantics**: `dayOfWeek` is `@Min(1) @Max(7)` on `CreateWindowRequest` — ISO-8601 `DayOfWeek.getValue()` convention (1=Monday...7=Sunday), matching the same convention already used in `BookingService.isSlotWithinAvailabilityWindow`'s day-of-week matching. A window cannot span midnight: `CreateWindowRequest.isValidTimeRange()` (`@AssertTrue`) requires `startTime.isBefore(endTime)` within the same `LocalTime`, so no multi-day window exists to complicate the sort — overnight-window support is a separate, already-`[DECIDED: no fix planned]` item (`skillars-deferred-49` review), not reopened here.
   - Sort key: `dayOfWeek` ascending, then `startTime` ascending, then **`id` ascending as a tertiary tiebreaker** for total determinism (two windows can share the same `dayOfWeek`+`startTime` only in a currently-unvalidated overlapping-window edge case, but the ordering must still be deterministic even then).
   - **Confirmed repository convention** (checked sibling repos in the same package): `CoachMediaItemRepository.findByCoachIdOrderByDisplayOrderAsc` and `CoachReliabilityStrikeRepository.findByCoachIdOrderByCreatedAtDesc` both use plain derived-method-name ordering, no `@Query`. Follow the same style: rename to `findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc`, updating the one call site in `AvailabilityService`.
   - **Confirmed test call sites to check** (found via direct search, not left as a "grep for it" instruction): `AvailabilityServiceTest`, `BookingServiceTest`, `BookingBatchServiceTest`, and `ExpiredPackBookingValidationTest` all reference `findByCoachId`-backed window setup — check each for an assertion or fixture that implicitly assumes insertion order before this change lands.
   - Test: `AvailabilityServiceTest` asserting windows returned in day-then-start-time-then-id order when inserted out of order.

4. **Add exponential backoff to the SSE polling fallback, and fix the two behaviors the review correctly identified as prerequisites for defining "backoff" at all.** `useBookingSse` (`src/frontend/src/stores/booking.store.js:50-120`) already has exponential backoff (`delays = [1000, 2000, 4000, 8000, 16000, 30000]`) for its SSE *reconnect* attempts — but once degraded into polling mode (`retryCount >= 3`), the fallback poll runs on a **fixed** `setInterval(..., 2000)` forever. Two things must change together, not just the interval shape:
   - **The polling call itself has no error handling today** — `const r = await getBookingById(bookingId)` inside the `setInterval` callback has no `try`/`catch`; a network failure mid-outage is an unhandled promise rejection on every tick, and there is currently no signal to distinguish "poll succeeded" from "poll failed" at all. Add a `try`/`catch` around the `getBookingById` call. **"Successful poll" (resolves the review's ambiguity) = the awaited call resolves without throwing** (this project's `api` axios wrapper rejects on any non-2xx response — confirmed by the existing pattern at `booking.store.js` line ~103, `e?.response?.status === 404`, which only makes sense if non-2xx already rejects). A caught failure widens the delay for the *next* poll; a successful resolution resets it to the floor.
   - Replace the fixed-interval loop with a recursive `setTimeout` chain (a `setInterval` can't vary its own delay), starting at 2000ms, doubling on each caught failure, capped at the same `30000` ceiling already defined in the `delays` array above it in this file — reusing that existing constant rather than inventing a new one is what makes the cap non-arbitrary; do not add a second, separate max-delay constant.
   - **Timer cleanup (a real gap the original AC missed, not just underspecified)**: replace the `pollingInterval`/`clearInterval` variable with a `pollingTimeout`/`clearTimeout` pair of the same name pattern, and ensure `cleanup()` (already called from `onUnmounted`, confirmed at the bottom of `useBookingSse`) clears the pending timeout the same way it already clears `pollingInterval` today — otherwise a pending recursive `setTimeout` fires after the component unmounts and calls `getBookingById` against a component that's gone.
   - **SSE-reconnect-vs-polling re-entry (confirmed, not left open)**: there is no code path back to SSE once polling mode is entered. The `'heartbeat'` SSE listener that calls `connect()` again can only fire on an *open* `EventSource`, and `es` is always closed before polling starts (`es.onerror` closes it, then falls into the `retryCount >= 3` branch) — so once degraded to polling, this composable stays in polling mode until a terminal booking status arrives, by existing (pre-this-story) design. This AC does not change that topology, only the polling cadence within it — documenting this here so it isn't mistaken for a bug this AC should also fix.
   - **No frontend test framework exists in this project** (`src/frontend/package.json`'s `"test"` script is `echo "No test specified" && exit 0`; no Vitest/Jest/Cypress config found anywhere under `src/frontend`) — a unit test with fake timers is not achievable without introducing a new test-runner dependency, which is out of scope for this AC. Verify manually instead: start the dev server, throttle/kill the backend mid-poll, and confirm the poll cadence widens instead of staying flat at 2s, per this project's established convention of manual browser verification for frontend behavior (no local automated frontend test suite exists to lean on here or anywhere else in this codebase).

5. **Make `isCoachParty` 403s diagnosable.** `BookingEventResource.verifyIsParty`/`isCoachParty` (`BookingEventResource.java:68-82`) returns the identical generic 403 ("Not a party to this booking") whether the caller is a genuine unauthorized third party or a coach whose `CoachProfile` row no longer exists.
   - **Confirmed the full decision tree (not just the two cases originally named)**: `verifyIsParty` returns without throwing for `securityUtil.isAdmin()` (line 69-71) and for a matching `parentId` (the `isParent` check, line 74 — a mismatched parent never reaches this code path distinctly, since `isParent` is one of two ORed conditions). The `throw` at line 76-78 is reached **only** when both `isParent` is false AND `isCoachParty` is false — so parent and admin cases never reach this throw at all and need no separate logging; the only two cases that can reach it are exactly the two the AC already names: no `CoachProfile` row for this actor, or a `CoachProfile` row that exists but whose id doesn't match `booking.getCoachId()`.
   - **Confirmed no soft-delete ambiguity**: `CoachProfileRepository.findByUserId` (`CoachProfileRepository.java:24`) has no `@Where`/status filter, and `CoachProfile` has no soft-delete annotation or `deleted`/`active` field — any deletion of a `CoachProfile` row is a hard delete. "No profile" and "profile was deleted" are the same case, not two cases to distinguish.
   - Add a WARN-level structured log (`kv("bookingId", ...)`, `kv("actorUserId", ...)`) before the `throw`, distinguishing: `coachProfileRepository.findByUserId(actorUserId)` empty → "actor has no coach profile" vs. present-but-mismatched → "actor coach profile does not match booking coach".
   - Test: two explicit `BookingEventResourceIT` (or unit-level, whichever matches this class's existing test style — check `BookingEventResourceIT` first) cases: (a) `coachProfileRepository.findByUserId` returns empty for the actor, asserting the "no coach profile" log path; (b) it returns a `CoachProfile` with an id different from the booking's `coachId`, asserting the "mismatched coach" log path. Both mock `coachProfileRepository.findByUserId` directly — it's the only dependency this branch touches.

6. **Bound `weekStart` on both endpoints that accept it unchecked.** Confirmed both `AvailabilityResource.getAvailability` (`AvailabilityResource.java:43-46`) and `ScheduleResource`'s equivalent endpoint (`ScheduleResource.java:41-43`) accept `@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart` with zero range validation.
   - **Make the bound configurable, not hardcoded** (resolves the review's "arbitrary 2-year bound" concern): this is a defensive guard against pathological input, not a business rule requiring product sign-off — externalize it as `@Value("${booking.availability.weekStartRangeYears:2}")`, mirroring `PessimisticLockRetryer`'s existing `@Value("${app.locking.retry.max-attempts:8}")` pattern, so the default (2 years past/future) is adjustable via config without a code change if it ever needs revisiting.
   - Reject with `OperationNotAllowedException` + a new `BookingError.WEEK_START_OUT_OF_RANGE` code if `weekStart.isBefore(LocalDate.now().minusYears(rangeYears))` or `weekStart.isAfter(LocalDate.now().plusYears(rangeYears))`. `LocalDate.minusYears`/`plusYears`/`isBefore` already handle leap years correctly (pure calendar arithmetic, no `Instant`/timezone conversion involved since `weekStart` is a bare `LocalDate`) — no additional edge-case handling is needed for that.
   - **Confirmed this fits the "add new code" category, not "reuse existing"**: `BookingError`'s own doc comment describes the established split — codes below `INVALID_SESSION_DURATION` are "request-state validation," the same bucket `START_TIME_IN_PAST`/`INVALID_TIME_RANGE` already occupy; `WEEK_START_OUT_OF_RANGE` is the same kind of thing (a malformed request parameter, not an authorization failure), so it belongs in that same bucket as a new value.
   - **Confirmed i18n naming convention**: existing `booking.*` keys in `messages.properties` follow `booking.camelCaseNoun` (e.g. `booking.sessionCrossesMidnight`, `booking.concurrentModification`) — add `booking.weekStartOutOfRange` to `messages.properties`, `messages_de.properties`, `messages_fr.properties`, and `messages_en.properties` (this codebase has all four backend locale files, not three — confirmed by listing `src/main/resources/i18n/`), plus the three frontend locale `index.js` files (`de-DE`, `fr-FR`, `en-US`).
   - **Confirmed no query-performance concern**: `ProjectedRevenueService.calculateWeeklyRevenue` and `AvailabilityService.getAvailabilityCalendar` both scope their queries to exactly one 7-9 day window derived from `weekStart` (`wkStart`/`wkEnd` = `weekStart` ± a few days), regardless of how far `weekStart` itself is from today — the bound's width has no bearing on per-query cost, only on which single week gets queried. No performance action needed here.
   - Test: `AvailabilityResourceIT` and the equivalent `ScheduleResource` IT with cases at: exactly `rangeYears` in the past (passes), one day further past (fails with `WEEK_START_OUT_OF_RANGE`), exactly `rangeYears` in the future (passes), one day further future (fails).

7. **Harden `BookingEmailListener`/`SessionPackEmailListener` against listener-body failures that never reach the outbox.** Investigated per project-owner direction: this codebase already has a robust, generic email delivery outbox — `MailManager.sendEmailFromTemplate` (`@Async` + `@TransactionalEventListener(AFTER_COMMIT)` on the `Envelope` event) persists an `EnvelopeEntity` with `SENT`/`FAILED` status, and `EmailRetryScheduler` polls `FAILED`+retryable rows on a schedule and redrives them. **Confirmed sufficient for actual SMTP/send failures — do not rebuild or duplicate it.** The real, narrower gap is upstream of it: `BookingEmailListener` (18 `onXxx` methods) and `SessionPackEmailListener` (3 `onXxx` methods) build their `data` map and construct the `Envelope` with **no try/catch** around the method body. If data-prep throws, the exception never reaches `publisher.publishEvent(new Envelope(...))` at all, so the notification never enters the outbox and is lost with only Spring's generic, context-free post-commit-synchronization log (no business context, not alertable).
   - **Confirmed `publisher.publishEvent` itself needs no separate handling**: `MailManager.sendEmailFromTemplate` is the sole listener of `Envelope` events (confirmed — no other class listens for `Envelope`) and is `@Async`, so Spring dispatches it on a separate thread; any exception inside it never propagates back through the synchronous `publisher.publishEvent(...)` call. This AC's try/catch protects each listener's own pre-publish data-prep code, nothing more is needed for `publishEvent` robustness.
   - **Confirmed the identifying field to log varies per method — do not assume a uniform `bookingId`**: of the 21 methods, most (the majority of `BookingEmailListener`'s 18) expose `event.getBookingId()`; the two batch events (`onBatchBookingRequested`, `onBatchBookingAccepted`) expose `getBatchId()` instead; `onDuplicateBookingProposed` exposes `getNewBookingId()`; all 3 `SessionPackEmailListener` methods expose `getPackId()`, not a booking id at all. Some getters are Lombok `@Getter`-generated (not textually visible via grep on the class body — e.g. `QuickCompleteConfirmationRequiredEvent`), so confirm each event's actual available getters at implementation time by reading the class, not by grepping method bodies. Log whichever identifying id(s) the specific event type actually exposes, plus the `EmailTemplate` being sent.
   - Wrap each of the 21 `onXxx` method bodies in `try { ...existing body... } catch (Exception e) { log.error("Failed to prepare/publish notification", kv("template", EmailTemplate.X), kv(<that method's own id field>, ...), e); }`, using the `net.logstash.logback.argument.StructuredArguments.kv` style already used in `EmailRetryScheduler`. **Uniform `catch (Exception e)` at `ERROR` is confirmed correct and consistent, not left as an open question**: `EmailRetryScheduler.sendAll`'s existing catch (`catch (Exception e) { log.error("Email retry attempt failed", ..., e); }`) already uses this exact undifferentiated shape for the analogous downstream failure case — mirror it, do not introduce exception-type-based log-level branching that has no precedent in this codebase.
   - Leave the existing early-return null-email guards (e.g. `onBookingRequested`'s `if (event.getCoachEmail() == null...) { log.warn(...); return; }`) as-is — those are already intentional and already logged, not part of this gap.
   - **Confirmed test mechanism (the review correctly flagged "mock a dependency" as the wrong technique here)**: `BookingEmailListener`'s only fields are `publisher` and `appBaseUrl`; `SessionPackEmailListener`'s only field is `publisher` — neither class has an injectable repository or service to make throw. The actual failure mode this AC guards against is a malformed event triggering an NPE during data-prep, e.g. `onBookingRequested`'s `event.getRequestedStartTime().toString()` (`BookingEmailListener.java` line ~65) throwing if `requestedStartTime` is null. Test by constructing an event instance with such a field `null` and asserting the method returns normally with the failure logged, rather than the exception propagating — do this for at least one method per listener class (one in `BookingEmailListener`, one in `SessionPackEmailListener`).

8. **Lock session plans on booking cancellation instead of leaving them orphaned — forward-only, per project-owner decision (existing already-orphaned rows are not retroactively migrated by this story).** Confirmed gap: `SessionPlanService.handleBookingCompleted` (`SessionPlanService.java:167-178`) is the *only* listener transitioning a session plan's status, and it only fires on `BookingCompletedEvent` — a booking that instead becomes `CANCELLED`/`CANCELLED_PARENT`/`CANCELLED_COACH`/`DECLINED`/`NO_SHOW_PLAYER`/`NO_SHOW_COACH`/etc. never transitions its paired `DRAFT`/`SAVED` session plan, leaving it editable forever.
   - Add a new `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT) @Transactional(propagation = Propagation.REQUIRES_NEW)` method in `SessionPlanService`, e.g. `handleBookingTerminalNonCompletion(BookingStatusChangedEvent event)`, subscribing to the same generic `BookingStatusChangedEvent` `BookingBatchStatusListener` already consumes — the single chokepoint `BookingService.transitionInternal` publishes on every status write (confirmed: the only `publishEvent=false` call site is `acceptAndInitiatePayment`'s intermediate, non-terminal `ACCEPT` step). Logic: if `bookingStateMachine.isTerminal(BookingStatus.valueOf(event.newStatus()))` is true AND `event.newStatus()` is not `"COMPLETED"`, look up the session by `bookingId` via `sessionRepository.findByBookingId`, and if its status is `"DRAFT"` or `"SAVED"`, set it to `"CANCELLED"` and save. **`isTerminal()` is authoritative and dynamic by design (`!TRANSITIONS.containsKey(status)`) — do not hardcode a separate list of terminal statuses to check against; the existing method already correctly covers every current and future terminal status without this AC needing to enumerate them.**
   - **Confirmed `"CANCELLED"` introduces no conflict**: no existing code anywhere in the `session` module uses `"CANCELLED"` as a session status (confirmed by direct search — zero hits). `Session.status` (`Session.java`) is a plain `@Column(nullable = false, length = 20)` string with no `@Enumerated`/CHECK constraint — `"CANCELLED"` (9 characters) requires no schema migration. `UpdateSessionPlanRequest`'s `@Pattern(regexp = "DRAFT|SAVED")` validator governs only what a coach can submit via `updateSession`'s request body; this AC's transition is a direct `session.setStatus(...)` + `save()` from an internal listener, never passing through that DTO/validator, so no conflict exists there either.
   - Wrap the save in the same `try { sessionRepository.save(session); } catch (DataIntegrityViolationException e) { log.warn(...); }` idiom `handleBookingCompleted` already uses at `SessionPlanService.java:171-175` — mirrors that exact existing defensive catch (its own log text already frames it generically as "concurrent modification or constraint violation"; this AC's version protects the identical class of concern, a session row being written to by two paths at once, with no new invariant to separately document).
   - Extend `SessionPlanService.updateSession`'s existing terminal-lock guard (confirmed at `SessionPlanService.java:127-131`, currently `if ("COMPLETED".equals(session.getStatus())) { throw ...SESSION_PLAN_LOCKED... }`) to also reject when status is `"CANCELLED"`, using the same existing `SessionErrorCode.SESSION_PLAN_LOCKED` — no new error code needed.
   - **Known, accepted, narrow residual race (documented per this codebase's established convention for such windows, not eliminated)**: `SessionPlanService.createSession` already gates on `isBookingPlannable(booking.status())` (`SessionPlanService.java:80-84`), which only permits `"CONFIRMED"` — so a session plan cannot normally be created against an already-terminal booking. A genuinely narrow cross-transaction race remains: `createSession`'s booking-status read is unlocked, so a booking cancellation committing in the brief window between that read and the new session's own commit could still produce a fresh `DRAFT` session this AC's listener already ran past (having found nothing to lock, moments earlier). Given how narrow this window is (milliseconds, requires two independent user actions to land almost simultaneously) and this codebase's repeated precedent of documenting rather than eliminating comparably narrow windows elsewhere (e.g. `skillars-deferred-69`'s own acknowledged batch-staleness note), this is documented here as accepted, not fixed — locking the booking row for the full duration of `createSession`'s drill-fetch/DNA-calculation work would be a disproportionately invasive change to that method's critical path for this. Flag for a future story only if it's ever observed in practice.
   - **Confirmed no frontend UI change is needed**: no session-plan page (`SessionBuilderPage.vue`/`sessionBuilder.store.js`) currently branches on session status to disable editing for *any* status, including the already-existing `COMPLETED` lock — confirmed by direct search, zero such branches exist today. A `SESSION_PLAN_LOCKED` rejection for `"CANCELLED"` will surface through the exact same generic `catch (e) { error.value = e }` path (`sessionBuilder.store.js` line ~140-141) that a `COMPLETED` rejection already does today — same existing behavior, no regression, nothing to add.
   - **Existing orphaned rows are explicitly out of scope for this story** (project-owner decision, 2026-08-28): any `DRAFT`/`SAVED` session plan already orphaned against an already-terminal booking before this AC ships stays as-is, editable, undocumented as a data-quality issue rather than migrated. If this becomes a real operational problem later, a one-time backfill migration (mirroring this codebase's `V103` timezone-backfill precedent) is the natural fix — not attempted here.
   - **Cross-AC note (confirmed, not left open)**: this AC's listener and AC7's `BookingEmailListener` cancellation-notification listeners are fully independent — Spring's `AbstractPlatformTransactionManager.triggerAfterCommit` invokes each registered `AFTER_COMMIT` synchronization in turn and catches+logs any exception per-synchronization before moving to the next, so a failure in one (even an AC7 listener this story hasn't fully hardened yet, hypothetically) cannot prevent this AC's listener from running, and vice versa.
   - Test: `SessionPlanServiceTest` cases — (a) publishing a `BookingStatusChangedEvent` with a cancelled/declined/no-show status against a `DRAFT` session asserts it transitions to `CANCELLED`; (b) the same with `COMPLETED` status confirms no interference with the existing `handleBookingCompleted` path; (c) `updateSession` against a `CANCELLED` session asserts `SESSION_PLAN_LOCKED`. Additionally, add one broader IT-level regression test spanning AC1/AC2/AC8 together: create a booking → request a reschedule → cancel the booking → assert the session plan (if one exists) is locked — since these three ACs touch the same booking lifecycle and nothing currently exercises them end-to-end together.

## Tasks / Subtasks

- [x] Task 1: Availability write-lock parity across all three write paths (AC: #1)
  - [x] Reorder `BookingService.createBookingRequest`'s lock acquisition to before its window fetch
  - [x] Move `BookingBatchService.createBatch`'s lock acquisition to before its fresh re-check
  - [x] Add per-coach lock acquisition to `RescheduleService.validateRescheduleProposal` (new — no lock exists there today)
  - [x] Add the same locked-read pattern to `AvailabilityService.addWindow`/`updateWindow`/`deleteWindow`, injecting `PessimisticLockRetryer`/`EntityManager`
  - [x] Add concurrency tests to `BookingServiceTest`, `BookingBatchServiceTest`, `RescheduleServiceTest` proving serialization
- [x] Task 2: RescheduleService availabilitySignature parity (AC: #2, depends on Task 1's lock reordering in the same method)
  - [x] Add `availabilitySignature` field to `CreateRescheduleRequest`
  - [x] Wire signature check into `validateRescheduleProposal`, reusing the existing `originalDuration` variable
  - [x] Add RescheduleServiceTest coverage (match/stale/null cases)
- [x] Task 3: Deterministic availability window ordering (AC: #3)
  - [x] Rename `findByCoachId` to `findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc`
  - [x] Check `AvailabilityServiceTest`/`BookingServiceTest`/`BookingBatchServiceTest`/`ExpiredPackBookingValidationTest` for insertion-order assumptions
  - [x] Add order-assertion test to AvailabilityServiceTest
- [ ] Task 4: SSE polling fallback backoff (AC: #4) — code complete, one subtask needs human follow-up (see below)
  - [x] Add try/catch around `getBookingById` in the polling loop
  - [x] Replace fixed `setInterval` polling with a backoff `setTimeout` chain, reusing the existing `30000` ceiling
  - [x] Rename `pollingInterval`/`clearInterval` to `pollingTimeout`/`clearTimeout` and confirm `cleanup()` still clears it
  - [ ] Manual dev-server verification of widening poll cadence under simulated outage — **not performed**: no browser-automation tool is available in this environment, and per this project's own convention there is no frontend automated test infrastructure to substitute. Verified instead via `npx eslint` (clean) and an isolated Node simulation of the exact backoff/reset algorithm (doubles on failure, caps at 30000ms, resets to the 2000ms floor on success — see Completion Notes). Recommend a human do the dev-server + simulated-outage check described in the AC before merge.
- [x] Task 5: isCoachParty diagnosability (AC: #5)
  - [x] Add distinguishing WARN log in `BookingEventResource.verifyIsParty`
  - [x] Add two explicit test cases (empty profile / mismatched profile) mocking `coachProfileRepository.findByUserId`
- [x] Task 6: weekStart bounds (AC: #6)
  - [x] Add configurable `booking.availability.weekStartRangeYears` property + `BookingError.WEEK_START_OUT_OF_RANGE`
  - [x] Add bound check to `AvailabilityResource` and `ScheduleResource`
  - [x] Add `booking.weekStartOutOfRange` to all 4 backend locale files + 3 frontend locale files
  - [x] Add IT coverage for both resources at exact and one-past-boundary cases
- [x] Task 7: Notification listener hardening (AC: #7)
  - [x] Wrap all 18 `BookingEmailListener` `onXxx` bodies in try/catch+structured log, using each method's own available id field
  - [x] Wrap all 3 `SessionPackEmailListener` `onXxx` bodies in try/catch+structured log
  - [x] Add one malformed-event failure-path test per listener class
- [x] Task 8: Session-plan cancellation lock (AC: #8)
  - [x] Add `handleBookingTerminalNonCompletion` listener to `SessionPlanService`
  - [x] Extend `updateSession`'s terminal-lock guard to include `"CANCELLED"`
  - [x] Add SessionPlanServiceTest coverage (cancel-transitions, completed-noninterference, locked-edit-rejection)
  - [x] Add cross-AC IT regression test: booking → reschedule → cancel → session locked

## Dev Notes

### Source ledger mapping (per project convention — record what maps to what)

- AC1 ← `## Deferred from: code review of skillars-deferred-69 (2026-08-26)`, "Batch Service Staleness Window Not Fully Closed" bullet — re-confirmed still open, and **widened during story review (2026-08-28)** after direct code inspection showed the story's own originally-cited precedent (`BookingService.createBookingRequest`'s lock) doesn't cover its own window read either. AC1 now closes the gap in all three read-side write paths, not just `BookingBatchService`.
- AC2 ← `## Deferred from: code review of skillars-uat-2-... — Group A (2026-08-10)`, "RescheduleService and BookingBatchService remain unwired" to `skillars-deferred-71` AC2's signature guard — `BookingBatchService` half closed by `skillars-deferred-72` AC4; `RescheduleService` half re-confirmed still open.
- AC3 ← `## Deferred from: code review of skillars-deferred-18-...` D2 — the week-scoping-bounds half was closed by `skillars-deferred-65` AC3, the per-window `ORDER BY` half never was.
- AC4 ← `## Deferred from: code review of skillars-3-4-booking-state-machine-sse` (untagged, never claimed) — "reconnect has backoff, fallback polling does not."
- AC5 ← same `skillars-3-4` section, "isCoachParty() returns generic 403..." bullet.
- AC6 ← `## Deferred from: code review of skillars-3-1-coach-availability-management`, "No date-range guard on weekStart GET parameter" — also found the identical unguarded pattern in `ScheduleResource`, folded into the same AC.
- AC7 ← `## Deferred from: skillars-10-2 ...` D1/D2-class items (AFTER_COMMIT listener failure silently drops notifications), explicitly deferred across 4+ prior stories as "platform-wide, needs its own design pass." **Re-scoped after investigation, per project-owner instruction to check the existing outbox mechanism first**: the outbox already covers actual send failures; this AC closes the narrower, previously-unidentified pre-publish gap. Do not re-open this as "needs a full redesign" in a future ledger pass.
- AC8 ← `## Deferred from: code review of skillars-deferred-75 (2026-08-27)`, "SessionPlanService Terminal Booking Check Order." Project-owner decisions (2026-08-28): close by locking (new `CANCELLED` status), not archiving; forward-only, no retroactive backfill of existing orphaned rows.

### Items investigated and found already closed — do not re-file these

- Batch-accept race in `updateBatchStatusFromBooking` (`skillars-3-9` W1) — already serialized via `batchRepository.findByIdForUpdate` + `lockRetryer.withBoundedRetry` (`skillars-deferred-69` AC6), confirmed at `BookingBatchService.java:467-486`.
- `BookingBatchStatusListener.findById` "outside a transaction" (`skillars-3-9` W2) — moot: a plain read handing off to `updateBatchStatusFromBooking`'s own `@Transactional(REQUIRES_NEW)` locked write.
- "Batch Lock Retry Timeout Lacks Graceful Degradation" (`skillars-deferred-69` code review) — already handled: `ApiAdvice.pessimisticLockExceptionHandler` (`ApiAdvice.java:580-586`) maps any `PessimisticLockingFailureException` to a friendly `409`, application-wide.
- `useBookingSse` "not wired into `BookingStateChip`" (`skillars-3-4`) — confirmed live: `BookingStateChip.vue` imports and calls it directly.
- `completionLoading`/`completionError` shared-state concern (`skillars-3-7`/`skillars-3-8`) — confirmed removed entirely, closed by `skillars-deferred-72` AC5.
- Overnight/midnight-crossing availability windows (`skillars-deferred-49` review) and DST-shift in `duplicateNextWeek` — both explicitly `[DECIDED: no fix planned]`, not reopened here.

### Explicitly out of scope for this story (flagged, not picked up)

- **Escrow/delayed coach payout tied to session completion** (`skillars-deferred-63`) — payment capture happens well before session completion with no payout gating; explicitly flagged as needing "its own design pass." Spans Booking+Payments; belongs in the future Payments-priority story.
- **Session-pack purchase / credit wallet for self-registered (no-parent) players** (`skillars-uat-5-player-self-booking`) — `payment.session_pack_purchases`/`parent_credit_ledger` both require a `parent_id`. Payments-module schema decision, out of scope here.

### Architecture / conventions to follow

- **Locking pattern**: `PessimisticLockRetryer.withBoundedRetry(() -> repo.findByIdForUpdate(id).orElseThrow(...))` + `entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE)` when the entity is already JPA-managed from an earlier unlocked read in the same method — see `BookingService.java:245-256`. AC1 extends this exact pattern; do not invent a different mechanism.
- **Error codes**: `BookingError`'s doc comment explains the split-vs-new-code convention — `WEEK_START_OUT_OF_RANGE` (AC6) is confirmed to fit the "new request-state-validation code" bucket per that comment.
- **i18n**: this project has **four** backend locale files (`messages.properties`, `messages_de.properties`, `messages_fr.properties`, `messages_en.properties` — confirmed, not three) plus three frontend locale `index.js` files (`de-DE`, `fr-FR`, `en-US`).
- **Structured logging**: `net.logstash.logback.argument.StructuredArguments.kv(...)`, per `EmailRetryScheduler`/`PessimisticLockRetryer`.
- **Configurable defensive bounds**: use `@Value("${property:default}")` for guard-rail constants that aren't precise business rules (AC6's `weekStartRangeYears`), mirroring `PessimisticLockRetryer`'s `app.locking.retry.*` properties — do not hardcode a bare numeric literal for a value someone may reasonably want to tune later without a redeploy of code (just config).
- **No frontend automated test infrastructure exists in this repository** — `src/frontend/package.json`'s `test` script is a no-op stub, and no Vitest/Jest/Cypress config exists anywhere under `src/frontend`. Frontend behavior verification in this story (AC4) is manual-only, via the dev server, consistent with the rest of this codebase.
- **Testing**: per `docs/validation-strategy.md`, do not run `mvn verify` locally — GitHub CI is the sole full-verification gate. Run targeted `mvn -o test -Dtest=<ClassName>` for touched classes.

### Project Structure Notes

All backend changes stay within the existing `booking`, `marketplace`, `notification`, and `session` packages under `src/main/java/com/softropic/skillars/platform/`. Frontend changes are confined to `src/frontend/src/stores/booking.store.js` (AC4) and the three locale `index.js` files (AC6) — confirmed no other frontend file needs touching (AC8's frontend concern was investigated and found to need no change). No detected conflicts with the established project structure.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` — source ledger (see per-AC citations above; exact line numbers there drift as future stories prune it).
- `_bmad-output/implementation-artifacts/story-review.md` — the senior-dev-review that drove this revision; every finding in it was either resolved by direct investigation (documented inline above with citations) or, for AC8's backfill question, brought back to the project owner (answer: forward-only, no backfill).
- [Source: skillars-deferred-69, skillars-deferred-71, skillars-deferred-72 story files] — precedent for the locking and signature-guard patterns extended in AC1/AC2.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

One story claim was found incorrect by direct test failure, not by static reading: AC8 states
`session.sessions.status` has "no @Enumerated/CHECK constraint" and that adding `"CANCELLED"`
"requires no schema migration." The new cross-AC IT (`SessionPlanCancellationLifecycleIT`) proved
this false against a real Postgres DB — `V43__session_plans.sql:10-11` declares an inline,
Postgres-auto-named `sessions_status_check` CHECK constraint restricting status to
`DRAFT/SAVED/COMPLETED`. Without a migration, every `handleBookingTerminalNonCompletion` write to
`CANCELLED` violated that constraint and was silently swallowed by the method's own (intentional,
per AC8) `DataIntegrityViolationException` catch — the session was never actually locked, only the
in-memory object was, which is exactly why a mocked-repository unit test could not have caught this
but a real-DB IT did. Added `V116__session_status_cancelled.sql` to widen the constraint. Flagging
this here since it contradicts a "confirmed" claim in the AC text itself (which this workflow is not
permitted to edit) — a future story/reviewer should not re-trust that specific claim without
re-verifying.

Two smaller test-fixture gaps were also found and fixed during implementation (not bugs in the
story's own logic):
- `BookingServiceTest`: 3 pre-existing tests (`createBookingRequest_slotOutsideAvailabilityWindows_throwsOperationNotAllowedException`,
  `createBookingRequest_sessionCrossesMidnight_throwsSessionCrossesMidnight`,
  `createBookingRequest_staleAvailabilitySignature_throwsAvailabilityChangedBeforeWindowCheck`) asserted the
  pre-AC1 lock ordering (`verify(coachProfileRepository, never()).findByIdForUpdate(...)`) — updated to reflect
  that AC1 now locks before these checks run, since that reordering is the point of the AC.
- `AvailabilityServiceTest`'s new AC3 order-assertion test initially NPE'd on an unstubbed
  `sessionDurationResolver.resolve(coachId)` — added the missing stub.

### Completion Notes List

- AC1–AC3 (availability write-lock parity, reschedule signature parity, deterministic window
  ordering): implemented together since they touch the same three write paths. Lock reordering in
  `BookingService.createBookingRequest`/`BookingBatchService.createBatch`/`RescheduleService
  .validateRescheduleProposal` proven with 3 new Postgres-backed concurrency ITs
  (`BookingServiceConcurrencyIT`, new `BookingBatchServiceConcurrencyIT`,
  `RescheduleServiceConcurrencyIT`) that delete the coach's availability window while holding the
  coach-row lock and assert the caller sees the post-lock (empty) window list, not a pre-lock
  snapshot — this is the only test technique that actually distinguishes "lock exists" from "lock
  closes the TOCTOU." `CoachAvailabilityWindowRepository.findByCoachId` renamed to
  `findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc`; every call site across main and test source
  updated (checked, no insertion-order assumptions existed in any affected test — all use
  single-window fixtures or explicit stubbed lists).
- AC4 (SSE polling backoff): implemented in `booking.store.js`. No frontend automated test
  infrastructure exists in this repo (confirmed, `package.json`'s `test` script is a no-op and no
  Vitest/Jest/Cypress config exists anywhere under `src/frontend`), so per the story's own
  instruction this was verified by (a) `npx eslint` clean, (b) an isolated Node simulation of the
  exact backoff/reset algorithm confirming the delay sequence doubles on failure, caps at 30000ms,
  and resets to the 2000ms floor on success. **Full interactive browser verification (dev server +
  simulated backend outage, watching actual poll cadence in the Network tab) was not performed** —
  this environment has no browser-automation tool available, and this is honestly flagged rather
  than claimed. Recommend a quick manual check before merge.
- AC5–AC8: straightforward, each with dedicated new test coverage (`BookingEventResourceIT` for
  AC5's two log-branch cases; `AvailabilityResourceIT`/`ScheduleResourceIT` boundary tests for AC6;
  `BookingEmailListenerTest`/`SessionPackEmailListenerTest` malformed-event tests for AC7;
  `SessionPlanServiceTest` + new `SessionPlanCancellationLifecycleIT` for AC8, the latter also
  serving as the cross-AC1/AC2/AC8 regression test the story asked for).
- Per `docs/validation-strategy.md`, ran targeted tests only (no `mvn verify`). All touched-area
  test classes pass; see File List for the full set. GitHub CI is the full-verification gate.

### File List

**Backend — main**
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java` (call-site rename only)
- `src/main/java/com/softropic/skillars/platform/booking/contract/CreateRescheduleRequest.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/AvailabilityResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/ScheduleResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/BookingEventResource.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachAvailabilityWindowRepository.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java` (call-site rename only)
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/BookingEmailListener.java`
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/SessionPackEmailListener.java`
- `src/main/java/com/softropic/skillars/platform/session/service/SessionPlanService.java`
- `src/main/resources/db/migration/V116__session_status_cancelled.sql` (new)
- `src/main/resources/i18n/messages.properties`
- `src/main/resources/i18n/messages_en.properties`
- `src/main/resources/i18n/messages_de.properties`
- `src/main/resources/i18n/messages_fr.properties`

**Frontend — main**
- `src/frontend/src/stores/booking.store.js`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/i18n/de-DE/index.js`
- `src/frontend/src/i18n/fr-FR/index.js`

**Tests — modified**
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/AvailabilityServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceConcurrencyIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/AvailabilityResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/ScheduleResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/ExpiredPackBookingValidationTest.java` (call-site rename only)
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/BookingEmailListenerTest.java`
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/SessionPackEmailListenerTest.java`
- `src/test/java/com/softropic/skillars/platform/session/service/SessionPlanServiceTest.java`

**Tests — new**
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceConcurrencyIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingEventResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/session/service/SessionPlanCancellationLifecycleIT.java`

## Change Log

- 2026-08-28: All 8 ACs implemented and targeted-tested. AC1 (availability write-lock parity across
  `BookingService`/`BookingBatchService`/`RescheduleService`, plus `AvailabilityService`'s writer
  side) and AC2 (reschedule `availabilitySignature` parity) proven with 3 new Postgres-backed
  concurrency ITs. AC3 (deterministic window ordering) via a renamed derived-query method. AC4 (SSE
  polling backoff) — no frontend test infra exists in this repo; verified via eslint + isolated
  algorithm simulation, full interactive browser verification flagged as not performed. AC5
  (isCoachParty diagnosability) with new log-capture IT. AC6 (weekStart bounds) with 22 new boundary
  ITs across both resources and full i18n coverage. AC7 (notification listener hardening) across all
  21 listener methods with malformed-event tests. AC8 (session-plan cancellation lock) with unit
  coverage plus a new cross-AC1/AC2/AC8 regression IT — which surfaced and required fixing a real gap
  the story text had gotten wrong: a DB-level CHECK constraint on `session.sessions.status`
  (`V43__session_plans.sql`) that the story claimed didn't exist; added
  `V116__session_status_cancelled.sql` to widen it to include `CANCELLED`. Status: ready-for-dev →
  review.
