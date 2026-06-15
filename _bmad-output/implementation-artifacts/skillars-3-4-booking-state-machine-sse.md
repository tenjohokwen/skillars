# Story skillars-3.4: Booking State Machine & SSE

Status: done

## Story

As a coach or parent,
I want booking status changes to appear in real time without reloading the page,
So that I can react immediately to confirmations, cancellations, and disputes as they happen.

## Acceptance Criteria

1. **AC 1: Full State Machine** — Given the full 16-state booking state machine is implemented, when any transition is attempted, then only `BookingService.transition(bookingId, BookingEvent, context)` may change the booking status — all 16 states and their valid transitions are encoded in `BookingStateMachine`. An invalid transition (e.g., COMPLETED → REQUESTED) throws `BookingStateTransitionException` and returns `409 Conflict` with `ErrorDto` code `booking.invalidTransition`. Valid transition graph: REQUESTED → ACCEPTED | DECLINED; ACCEPTED → PAYMENT_PENDING | CANCELLED_COACH | CANCELLED_PARENT; PAYMENT_PENDING → CONFIRMED | REFUND_PENDING; CONFIRMED → UPCOMING | CANCELLED_COACH | CANCELLED_PARENT; UPCOMING → IN_PROGRESS | NO_SHOW_PLAYER | NO_SHOW_COACH | CANCELLED_COACH | CANCELLED_PARENT; IN_PROGRESS → COMPLETED_PENDING_CONFIRMATION | DISPUTED; COMPLETED_PENDING_CONFIRMATION → COMPLETED | DISPUTED; COMPLETED → DISPUTED; DISPUTED → REFUND_PENDING | COMPLETED; REFUND_PENDING → REFUNDED.

2. **AC 2: SSE Push on State Change** — Given a booking status changes on the server, when the transition completes and the `@TransactionalEventListener(AFTER_COMMIT)` fires a `BookingStatusChangedEvent`, then the SSE emitter for every connected client subscribed to that booking pushes the new status immediately, and the `BookingStateChip` on the parent and coach UIs updates without a page reload.

3. **AC 3: SSE Connection** — Given a client connects to `GET /api/bookings/{id}/events`, when the SSE connection is established, then the server registers a `SseEmitter` for that booking and client, the emitter has a 5-minute timeout, on timeout the client receives a `heartbeat` event to trigger reconnection, and on reconnection the client receives the current booking status immediately as the first event.

4. **AC 4: SSE Resilience** — Given the SSE connection drops, when the frontend detects the connection loss, then it reconnects with exponential backoff: 1s → 2s → 4s → max 30s. After 3 consecutive failed reconnects it falls back to 2-second polling on `GET /api/bookings/{id}`. Once SSE reconnects successfully, polling stops.

5. **AC 5: Parent Cancellation** — Given a cancellation is initiated, when a parent cancels a `CONFIRMED` or `UPCOMING` booking, then `BookingService.transition(bookingId, CANCEL_PARENT, context)` advances status to `CANCELLED_PARENT`, and refund eligibility is calculated per the cancellation matrix (FR-PAY-012): >24h before session → `FULL`; 6–24h → `PARTIAL`; <6h → `NONE`. The refund amount and eligibility are stored on the booking record for Epic 7 payment processing.

6. **AC 6: Coach Cancellation and No-Show** — Given a coach cancels a `CONFIRMED` or `UPCOMING` booking, when `transition(bookingId, CANCEL_COACH, context)` is called, then status advances to `CANCELLED_COACH` and full refund is flagged as eligible regardless of timing. For no-show: `NO_SHOW_PLAYER` → credit not deducted (no-op refund eligibility); `NO_SHOW_COACH` → full refund eligible. The reliability strike event is queued (published as `CoachReliabilityStrikeQueuedEvent`) for Epic 7 processing when coach cancel < 24h before session or NO_SHOW_COACH.

## Tasks / Subtasks

- [x] Task 1: Flyway V32 migration — expand schema for full 16-state machine (AC: 1, 5, 6)
  - [x] Create `src/main/resources/db/migration/V32__booking_state_machine.sql`
  - [x] DROP and recreate `chk_bkg_status` CHECK constraint to include all 16 states: `'REQUESTED','ACCEPTED','PAYMENT_PENDING','CONFIRMED','UPCOMING','IN_PROGRESS','COMPLETED_PENDING_CONFIRMATION','COMPLETED','DECLINED','CANCELLED_PARENT','CANCELLED_COACH','NO_SHOW_PLAYER','NO_SHOW_COACH','DISPUTED','REFUND_PENDING','REFUNDED'`
  - [x] Add `refund_amount NUMERIC(10,2)` (nullable — populated only when refund is applicable)
  - [x] Add `refund_eligibility VARCHAR(10) CHECK (refund_eligibility IN ('FULL','PARTIAL','NONE'))` (nullable — populated on cancellation/no-show transitions)

- [x] Task 2: `BookingStatus` enum in `platform.booking.contract` (AC: 1)
  - [x] Create `BookingStatus.java` enum with all 16 values: `REQUESTED, ACCEPTED, PAYMENT_PENDING, CONFIRMED, UPCOMING, IN_PROGRESS, COMPLETED_PENDING_CONFIRMATION, COMPLETED, DECLINED, CANCELLED_PARENT, CANCELLED_COACH, NO_SHOW_PLAYER, NO_SHOW_COACH, DISPUTED, REFUND_PENDING, REFUNDED`

- [x] Task 3: `BookingEvent` enum in `platform.booking.contract` (AC: 1)
  - [x] Create `BookingEvent.java` enum: `ACCEPT, DECLINE, INITIATE_PAYMENT, PAYMENT_CAPTURED, PAYMENT_FAILED, CANCEL_PARENT, CANCEL_COACH, START, NO_SHOW_PLAYER, NO_SHOW_COACH, COMPLETE_PENDING, COMPLETE, QUICK_COMPLETE, DISPUTE, SETTLE_REFUND, SETTLE_COMPLETE, REFUND_PROCESSED`
  - [x] Note: `QUICK_COMPLETE` directly advances from `COMPLETED_PENDING_CONFIRMATION` to `COMPLETED` (used in Story 3.6)

- [x] Task 4: `TransitionContext` record in `platform.booking.contract` (AC: 1, 5, 6)
  - [x] Create `TransitionContext.java` record: `(ActorRole actorRole, Long actorUserId)`
  - [x] Create `ActorRole.java` enum: `COACH, PARENT, SYSTEM`

- [x] Task 5: `BookingStateTransitionException` in `platform.booking.contract` (AC: 1)
  - [x] Create `BookingStateTransitionException.java` — extends `RuntimeException` with field `String errorCode = "booking.invalidTransition"` and constructor `(BookingStatus from, BookingEvent event)`
  - [x] Follow `MarketplaceException` pattern (simple RuntimeException with `getErrorCode()`)

- [x] Task 6: Update `ApiAdvice` — add handler for `BookingStateTransitionException` → 409 (AC: 1)
  - [x] In `platform.security.api.ApiAdvice`, add `@ExceptionHandler(BookingStateTransitionException.class)` method returning `@ResponseStatus(HttpStatus.CONFLICT)` using `logErrorAndReturnDTO(ex, ex.getMessage(), ex.getErrorCode())`

- [x] Task 7: `BookingStateMachine` in `platform.booking.service` (AC: 1)
  - [x] Create `BookingStateMachine.java` as `@Component` encapsulating `Map<BookingStatus, Set<BookingEvent>> TRANSITIONS`
  - [x] Encode the full graph: REQUESTED→{ACCEPT, DECLINE}; ACCEPTED→{INITIATE_PAYMENT, CANCEL_COACH, CANCEL_PARENT}; PAYMENT_PENDING→{PAYMENT_CAPTURED, PAYMENT_FAILED}; CONFIRMED→{CANCEL_COACH, CANCEL_PARENT} (UPCOMING is scheduler-driven, not event-driven); UPCOMING→{START, NO_SHOW_PLAYER, NO_SHOW_COACH, CANCEL_COACH, CANCEL_PARENT}; IN_PROGRESS→{COMPLETE_PENDING, DISPUTE}; COMPLETED_PENDING_CONFIRMATION→{COMPLETE, QUICK_COMPLETE, DISPUTE}; COMPLETED→{DISPUTE}; DISPUTED→{SETTLE_REFUND, SETTLE_COMPLETE}; REFUND_PENDING→{REFUND_PROCESSED}
  - [x] `validate(BookingStatus from, BookingEvent event)` — throws `BookingStateTransitionException` if not valid
  - [x] `targetStatus(BookingStatus from, BookingEvent event)` — returns the resulting `BookingStatus`
  - [x] Note on CONFIRMED→UPCOMING: The `BookingReminderScheduler` still transitions CONFIRMED→UPCOMING via `booking.setStatus("UPCOMING")` internally — it does NOT go through `transition()` because the scheduler is the authoritative clock trigger, not a user event. Only user/system-initiated events route through `transition()`.

- [x] Task 8: `BookingStatusChangedEvent` in `platform.booking.contract` (AC: 2)
  - [x] Create `BookingStatusChangedEvent.java` — extends `ApplicationEvent`; fields: `UUID bookingId`, `String newStatus`

- [x] Task 9: `CoachReliabilityStrikeQueuedEvent` in `platform.booking.contract` (AC: 6)
  - [x] Create `CoachReliabilityStrikeQueuedEvent.java` — extends `ApplicationEvent`; fields: `UUID bookingId`, `UUID coachId`, `String reason` (e.g., "CANCEL_WITHIN_24H" or "NO_SHOW_COACH")
  - [x] No listener for this event is wired in this story — Epic 7 will add the listener

- [x] Task 10: `BookingService.transition()` and refactoring (AC: 1, 5, 6)
  - [x] Inject `BookingStateMachine` into `BookingService`
  - [x] Add `@Transactional public void transition(UUID bookingId, BookingEvent event, TransitionContext context)`:
    1. Load booking (throw 404 if not found)
    2. Parse current `BookingStatus` from `booking.getStatus()` (String → enum via `BookingStatus.valueOf()`)
    3. Call `bookingStateMachine.validate(currentStatus, event)` — throws `BookingStateTransitionException` if invalid
    4. `BookingStatus newStatus = bookingStateMachine.targetStatus(currentStatus, event)`
    5. Apply refund logic for cancellation events (see Dev Notes: Refund Calculation)
    6. Apply reliability strike queuing for CANCEL_COACH (< 24h) and NO_SHOW_COACH
    7. `booking.setStatus(newStatus.name())`, `bookingRepository.save(booking)`
    8. Publish `BookingStatusChangedEvent(this, bookingId, newStatus.name())` via `eventPublisher.publishEvent()`
  - [x] **Remove** the static `ALLOWED_TRANSITIONS` map from `BookingService` — replaced by `BookingStateMachine`
  - [x] **Remove** the private `validateTransition(String from, String to)` method — replaced by `bookingStateMachine.validate()`
  - [x] Refactor `acceptBooking()` to call `transition(bookingId, ACCEPT, ctx)` then `transition(bookingId, PAYMENT_CAPTURED, ctx)` (stub payment gateway always succeeds)
  - [x] Refactor `declineBooking()` to call `transition(bookingId, DECLINE, ctx)`
  - [x] Add `@Transactional(readOnly = true) public BookingResponse getBooking(UUID id)` — returns current booking state for polling fallback (`GET /api/bookings/{id}`)

- [x] Task 11: `BookingSseService` in `platform.booking.service` (AC: 2, 3)
  - [x] Create `BookingSseService.java` as `@Service @RequiredArgsConstructor @Slf4j`
  - [x] Field: `ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>()`
  - [x] `SseEmitter subscribe(UUID bookingId, String currentStatus)`: create emitter with 5-minute timeout; add to registry; send current status as first event (`event("status"), data(currentStatus)`); register `onCompletion`, `onTimeout`, `onError` callbacks that remove from registry and send heartbeat on timeout; return emitter
  - [x] `@TransactionalEventListener(phase = AFTER_COMMIT) void onStatusChanged(BookingStatusChangedEvent event)`: get emitter list for bookingId; for each emitter, send `event("status"), data(event.newStatus())`; remove emitters that fail to send
  - [x] Use `CopyOnWriteArrayList` (not raw `List`) for thread-safe iteration while removing

- [x] Task 12: `BookingEventResource` in `platform.booking.api` (AC: 3)
  - [x] Create `BookingEventResource.java` with `@RequestMapping("/api/bookings")` and `@Observed(name = "booking.events")`
  - [x] `@GetMapping("/{id}/events") @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)`: resolve authenticated user; verify booking exists and actor is a party (parentId or coachId matches); call `bookingSseService.subscribe(id, currentStatus)` and return `ResponseEntity<SseEmitter>` with `Content-Type: text/event-stream`
  - [x] Also add `@GetMapping("/{id}") @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)`: return `ResponseEntity<BookingResponse>` from `bookingService.getBooking(id)` — used by polling fallback
  - [x] Path correction: epics dev notes say `/api/booking/bookings/{id}/events` — this is wrong per project convention. Correct path is `/api/bookings/{id}/events` (follows existing `AvailabilityResource`, `BookingResource`, `SessionPackResource` all using `/api/bookings/...`)

- [x] Task 13: Frontend — `booking.api.js` additions (AC: 3, 4)
  - [x] Add `getBookingById(id)` — `api.get(\`/api/bookings/${id}\`)` — used by polling fallback
  - [x] Note: SSE connection is NOT through axios — use native browser `EventSource`. Do NOT add an axios call for SSE.

- [x] Task 14: Frontend — `useBookingSse` composable exported from `booking.store.js` (AC: 3, 4)
  - [x] Export `useBookingSse(bookingId)` as a composable function (not inside `defineStore`) from `booking.store.js`
  - [x] State: `status = ref(null)`, `connectionState = ref('disconnected')` — `'connected' | 'reconnecting' | 'polling' | 'disconnected'`
  - [x] Implement exponential backoff reconnect: delays `[1000, 2000, 4000, 8000, 16000, 30000]` ms (cap at 30s), retry count tracked
  - [x] After 3 consecutive failed reconnects, switch to polling mode: `setInterval(() => getBookingById(bookingId).then(r => status.value = r.data.status), 2000)`
  - [x] When SSE reconnects successfully, `clearInterval(pollingIntervalId)`, reset retry count
  - [x] `cleanup()` function: close EventSource, clearInterval — called on component unmount via `onUnmounted`
  - [x] Return `{ status, connectionState, cleanup }`
  - [x] EventSource URL: `/api/bookings/${bookingId}/events` with credentials included
  - [x] On `message` event where `event.type === 'status'`: `status.value = event.data`
  - [x] On `error` event: start backoff reconnect cycle

- [x] Task 15: Frontend — `BookingStateChip.vue` additions (AC: 2)
  - [x] Add all new statuses to `statusMap` in `BookingStateChip.vue`:
    - `PAYMENT_PENDING`: key `booking.requests.statusPaymentPending`, cls `chip--warning`
    - `IN_PROGRESS`: key `booking.requests.statusInProgress`, cls `chip--primary`
    - `COMPLETED_PENDING_CONFIRMATION`: key `booking.requests.statusCompletingPending`, cls `chip--warning`
    - `CANCELLED_PARENT`: key `booking.requests.statusCancelledParent`, cls `chip--neutral`
    - `CANCELLED_COACH`: key `booking.requests.statusCancelledCoach`, cls `chip--neutral`
    - `NO_SHOW_PLAYER`: key `booking.requests.statusNoShowPlayer`, cls `chip--error`
    - `NO_SHOW_COACH`: key `booking.requests.statusNoShowCoach`, cls `chip--error`
    - `REFUND_PENDING`: key `booking.requests.statusRefundPending`, cls `chip--warning`
    - `REFUNDED`: key `booking.requests.statusRefunded`, cls `chip--neutral`

- [x] Task 16: Frontend — i18n keys (AC: 2)
  - [x] Add to `src/frontend/src/i18n/en/index.js` under `booking.requests`:
    - `statusPaymentPending: 'Payment pending'`
    - `statusInProgress: 'In progress'`
    - `statusCompletingPending: 'Completing...'`
    - `statusCancelledParent: 'Cancelled'`
    - `statusCancelledCoach: 'Cancelled by coach'`
    - `statusNoShowPlayer: 'Player no-show'`
    - `statusNoShowCoach: 'Coach no-show'`
    - `statusRefundPending: 'Refund pending'`
    - `statusRefunded: 'Refunded'`

- [x] Task 17: Unit tests — `BookingStateMachineTest` (AC: 1)
  - [x] Create `src/test/java/com/softropic/skillars/platform/booking/service/BookingStateMachineTest.java`
  - [x] `@ExtendWith(MockitoExtension.class)` or plain JUnit 5 (no Spring context needed)
  - [x] Test every valid transition in the graph — assert `targetStatus()` returns correct result
  - [x] Test invalid transitions — assert `BookingStateTransitionException` is thrown
  - [x] Test terminal states (REFUNDED, DECLINED) — assert all events throw `BookingStateTransitionException`
  - [x] Cover: COMPLETED_PENDING_CONFIRMATION → COMPLETE via `COMPLETE` event, COMPLETED_PENDING_CONFIRMATION → COMPLETED via `QUICK_COMPLETE` event (both must reach `BookingStatus.COMPLETED`)

- [x] Task 18: Integration test — `BookingSseIT` (AC: 2, 3)
  - [x] Create `src/test/java/com/softropic/skillars/platform/booking/api/BookingSseIT.java`
  - [x] Follow `BookingRequestResourceIT` annotation pattern: `@SpringBootTest`, `@Testcontainers`, `@Import(TestConfig.class)`, `@ActiveProfiles({"dev","test"})`
  - [x] Test 1: `GET /api/bookings/{id}/events` returns 200 with `Content-Type: text/event-stream`
  - [x] Test 2: SSE subscriber receives `status` event after `bookingService.transition()` is called in the same test (use `Awaitility` to wait for SSE event to arrive)
  - [x] Test 3: Unauthorized access (no JWT) returns 401
  - [x] Test 4: Non-party access (different parent/coach) returns 403

## Review Findings

### Decision-Needed

_All decisions resolved._

- [x] [Review][Decision] Scheduler bypass → **resolved: route both schedulers through `transition(ActorRole.SYSTEM)`** (moved to Patches)
- [x] [Review][Decision] V32 migration CANCELLED data gap → **resolved: dismissed — app is in development, no existing CANCELLED rows**
- [x] [Review][Decision] `acceptBooking()` 3× events → **resolved: suppress PAYMENT_PENDING event** (moved to Patches)
- [x] [Review][Decision] Refund boundary at exactly 6h → **resolved: use `>= 6` — exactly 6h = PARTIAL** (moved to Patches)
- [x] [Review][Decision] `PAYMENT_FAILED` refund eligibility → **resolved: defer to Epic 7** (moved to Deferred)
- [x] [Review][Decision] `useBookingSse()` not wired into `BookingStateChip` → **resolved: defer to consuming page story** (moved to Deferred)

### Patches

- [x] [Review][Patch] Off-by-one: polling fallback activates after 4th failure, not 3rd [booking.store.js:43] — `retryCount > 3` should be `retryCount >= 3` to match AC 4 ("after 3 consecutive failed reconnects")
- [x] [Review][Patch] `heartbeat` event does not reset `retryCount` [booking.store.js:56-59] — the heartbeat reconnect path calls `connect()` but never zeroes `retryCount`; subsequent errors accumulate and trigger premature polling mode
- [x] [Review][Patch] Heartbeat during polling mode starts new `EventSource` without stopping polling [booking.store.js:55-59] — `es.addEventListener('heartbeat', () => { es.close(); connect() })` does not call `clearInterval(pollingInterval)` first; both polling and SSE run concurrently after a heartbeat
- [x] [Review][Patch] No SSE retry path once polling is active [booking.store.js:43-53] — `retryCount > 3 && !pollingInterval` is never true again once polling starts; client polls indefinitely even if server recovers; needs a reset path (e.g. reset `retryCount` and `pollingInterval` on successful status event)
- [x] [Review][Patch] `useBookingSse` does not self-register `onUnmounted` cleanup [booking.store.js] — `EventSource` and `pollingInterval` leak on component destruction; composable must call `onUnmounted(cleanup)` internally
- [x] [Review][Patch] `connectionState` set to `'connected'` before SSE `open` event fires [booking.store.js:30] — set it inside `es.onopen` instead to avoid showing a false connected state during negotiation
- [x] [Review][Patch] `BookingStatus.valueOf()` unguarded on unknown/legacy DB status [BookingService.java:88] — throws `IllegalArgumentException` → uncaught → 500; wrap in try/catch and throw a `ResourceNotFoundException` or `BookingStateTransitionException` with a clear message
- [x] [Review][Patch] `CANCEL_PARENT` from `ACCEPTED` sets refund eligibility without confirmed payment [BookingService.java:applyRefundLogic] — `applyRefundLogic` fires for all states including ACCEPTED (pre-payment capture); refund eligibility should only be computed when a payment was actually captured (PAYMENT_PENDING or later)
- [x] [Review][Patch] `declineBooking()` double-publishes notification events [BookingService.java:190-197] — calls `transition()` (which publishes `BookingStatusChangedEvent`) then also publishes `BookingDeclinedEvent`; notification listeners may fire twice; consider suppressing `BookingStatusChangedEvent` for the DECLINE path or removing the redundant publish
- [x] [Review][Patch] Double DB read in `BookingEventResource.getBooking()` [BookingEventResource.java:55-59] — `getBookingOrThrow()` called for party check, then `bookingService.getBooking(id)` issues a second identical query; pass the already-fetched entity to avoid redundant IO and a TOCTOU window
- [x] [Review][Patch] `currentUserId()` unchecked cast to `Principal` [BookingEventResource.java:70-73] — `ClassCastException` → 500 if the security context holds a non-`Principal` implementation; add `instanceof` guard and return 401 on mismatch
- [x] [Review][Patch] `removeEmitter()` not atomic with `computeIfAbsent` in `subscribe()` [BookingSseService.java:63-71] — concurrent `subscribe` + `removeEmitter` can evict the new subscriber's freshly-inserted list from the `ConcurrentHashMap`; replace with `emitters.compute()` to make the remove-and-cleanup atomic
- [x] [Review][Patch] Coach reliability strike misses exactly-24h boundary [BookingService.java:~265] — `hoursUntilSession < 24` means a cancellation at exactly 24h before session does NOT trigger a strike; verify policy intent and update to `<= 24` if "within 24 hours" is inclusive
- [x] [Review][Patch] `BookingSseIT` Test 2 verifies DB state, not live SSE event delivery [BookingSseIT.java] — spec requires "SSE subscriber receives status event after transition (use Awaitility to wait for SSE event to arrive)"; the test only queries `SELECT status FROM booking.bookings` — rewrite to open a real SSE connection, fire the transition, and assert the event is received via `Awaitility`
- [x] [Review][Patch] Route `BookingReminderScheduler` and `BookingExpiryScheduler` through `transition(ActorRole.SYSTEM)` — both schedulers call `booking.setStatus()` directly; SSE clients miss CONFIRMED→UPCOMING and REQUESTED→DECLINED status changes; route through `BookingService.transition()` so `BookingStatusChangedEvent` fires and state machine validation applies [BookingReminderScheduler.java, BookingExpiryScheduler.java]
- [x] [Review][Patch] Suppress `PAYMENT_PENDING` intermediate event in `acceptBooking()` [BookingService.java:acceptBooking] — `transition()` publishes `BookingStatusChangedEvent` for every call; in `acceptBooking()`'s 3-step sequence SSE clients see a spurious PAYMENT_PENDING state; extract a package-private `transitionInternal()` that skips event publishing for the first two calls, or suppress via a flag, then publish only the final CONFIRMED event
- [x] [Review][Patch] Refund boundary at exactly 6h — change `hoursUntilSession > 6` to `hoursUntilSession >= 6` so exactly 6h before session is PARTIAL, not NONE [BookingService.java:applyRefundLogic]

### Deferred

- [x] [Review][Defer] No optimistic/pessimistic lock on `transition()` [BookingService.java:85] — deferred, pre-existing pattern; concurrent callers can both pass `validate()` on the same booking; address with `@Lock(PESSIMISTIC_WRITE)` in a concurrency-hardening pass
- [x] [Review][Defer] `getRequestedStartTime()` null not guarded before `ChronoUnit.HOURS.between()` in `applyRefundLogic` [BookingService.java:256] — deferred, pre-existing entity nullability; column is set at booking creation and in practice never null
- [x] [Review][Defer] SSE endpoint accepts subscriptions for terminal-state bookings [BookingEventResource.java:37] — deferred, resource management not in this story's scope; emitters accumulate for COMPLETED/CANCELLED/REFUNDED bookings that will never receive another event
- [x] [Review][Defer] `verifyIsParty` has no admin bypass path — deferred, no admin role exists in this system yet; revisit when admin management stories are implemented
- [x] [Review][Defer] Negative `hoursUntilSession` for past-session cancellations silently maps to NONE [BookingService.java:256] — deferred, edge case; negative hours fall through to NONE which is probably correct but undocumented
- [x] [Review][Defer] Polling fallback has no exponential backoff — deferred, design choice; 2 s fixed interval is the spec-prescribed degraded mode; add backoff if hammering becomes observable in production
- [x] [Review][Defer] `isCoachParty()` returns generic 403 when coach profile is deleted [BookingEventResource.java:70-73] — deferred, UX edge case; indistinguishable from an unauthorized third-party attempt; add a more specific error if coach-account-deletion story introduces this scenario
- [x] [Review][Defer] Dead `CANCELLED` entry in `BookingStateChip.statusMap` — deferred, harmless dead code; `'CANCELLED'` no longer exists in `BookingStatus` enum but the chip entry serves as a graceful-degradation fallback for any legacy data; clean up once data migration is confirmed complete
- [x] [Review][Defer] `PAYMENT_FAILED` sets no refund eligibility — deferred to Epic 7; `refundEligibility` stays `null` for payment failures and Epic 7 handles payment-failure refund logic independently [BookingService.java:applyRefundLogic]
- [x] [Review][Defer] `useBookingSse()` not wired into `BookingStateChip` — deferred; SSE wire-up belongs to the consuming page/component story; chip will be connected when the parent booking detail page is implemented

## Dev Notes

### ⚠️ CRITICAL: Path Correction — SSE Endpoint

The epics dev notes specify `GET /api/booking/bookings/{id}/events`. **This is wrong** — it uses singular `booking` and creates a `/booking/bookings` redundancy. The established pattern in this project uses `/api/bookings/...` (plural) for all booking module endpoints (see `AvailabilityResource`, `BookingResource`, `SessionPackResource`). **Correct path: `GET /api/bookings/{id}/events`.**

### ⚠️ CRITICAL: `Booking.status` Is String — No Entity-Level Enum

The `Booking` entity uses `String status` (not a JPA `@Enumerated` field). Do **not** change the entity field type — this avoids a breaking migration and is consistent with all existing code. The `BookingStatus` and `BookingEvent` enums live in `platform.booking.contract` for type-safety in service logic only. Parse with `BookingStatus.valueOf(booking.getStatus())` and write back with `booking.setStatus(newStatus.name())`.

### ⚠️ CRITICAL: `BookingReminderScheduler` Does NOT Use `transition()`

The `BookingReminderScheduler` transitions `CONFIRMED → UPCOMING` directly via `booking.setStatus("UPCOMING")`. This is intentional — the scheduler is a clock trigger, not a user-initiated event. Do **not** route scheduler transitions through `transition()`. The state machine validating user events must not block scheduler-driven transitions. The `CONFIRMED → {scheduler→UPCOMING}` path is separate from `CONFIRMED → {CANCEL_COACH, CANCEL_PARENT}` (which DO go through `transition()`).

### ⚠️ CRITICAL: `acceptBooking()` Refactoring

The current `acceptBooking()` does `REQUESTED → ACCEPTED → CONFIRMED` in a single transaction. With the new state machine:
- `ACCEPTED → CONFIRMED` is not a direct transition; the graph requires `ACCEPTED → PAYMENT_PENDING → CONFIRMED`
- For pre-paid session packs, the `StubPaymentGateway` captures payment immediately
- Refactor `acceptBooking()` to call:
  1. `transition(bookingId, BookingEvent.ACCEPT, ctx)` → ACCEPTED
  2. `transition(bookingId, BookingEvent.PAYMENT_CAPTURED, ctx)` → CONFIRMED
- Both calls within a single `@Transactional` method (the outer `acceptBooking()` owns the transaction boundary)
- The `BookingStatusChangedEvent` is published twice (once for ACCEPTED, once for CONFIRMED) — both fire AFTER_COMMIT when the outer transaction commits

### ⚠️ CRITICAL: `IllegalStateTransitionException` → Do NOT Use `IllegalStateException`

The `ApiAdvice` already has a handler for `IllegalStateException` → 409 with `generic.conflict`. The spec requires 409 with code `booking.invalidTransition` specifically. Create `BookingStateTransitionException extends RuntimeException` (like `MarketplaceException`) and add a dedicated `@ExceptionHandler(BookingStateTransitionException.class)` in `ApiAdvice`. Do **not** extend `IllegalStateException` or rely on the generic handler.

### Refund Calculation Logic (inside `transition()`)

Called when events are `CANCEL_PARENT`, `CANCEL_COACH`, `NO_SHOW_PLAYER`, `NO_SHOW_COACH`:

```
long hoursUntilSession = ChronoUnit.HOURS.between(Instant.now(), booking.getRequestedStartTime());

if event == CANCEL_PARENT:
    refundEligibility = hoursUntilSession > 24 ? FULL : hoursUntilSession > 6 ? PARTIAL : NONE
    refundAmount = calculated based on pack price (Epic 7 will process actual transfer)

if event == CANCEL_COACH:
    refundEligibility = FULL
    if hoursUntilSession < 24: publish CoachReliabilityStrikeQueuedEvent

if event == NO_SHOW_PLAYER:
    refundEligibility = NONE  (credit still consumed — Story 3.6 handles deduction)

if event == NO_SHOW_COACH:
    refundEligibility = FULL
    publish CoachReliabilityStrikeQueuedEvent
```

Store `booking.setRefundEligibility(eligibility.name())` and leave `booking.setRefundAmount(null)` for now (Epic 7 calculates actual amounts). The `refundAmount` column is nullable and intentionally left null in this story.

### `BookingStateMachine.targetStatus()` — Full Mapping Table

```
REQUESTED + ACCEPT                      → ACCEPTED
REQUESTED + DECLINE                     → DECLINED
ACCEPTED + INITIATE_PAYMENT             → PAYMENT_PENDING
ACCEPTED + CANCEL_COACH                 → CANCELLED_COACH
ACCEPTED + CANCEL_PARENT                → CANCELLED_PARENT
PAYMENT_PENDING + PAYMENT_CAPTURED      → CONFIRMED
PAYMENT_PENDING + PAYMENT_FAILED        → REFUND_PENDING
CONFIRMED + CANCEL_COACH                → CANCELLED_COACH
CONFIRMED + CANCEL_PARENT               → CANCELLED_PARENT
UPCOMING + START                        → IN_PROGRESS
UPCOMING + NO_SHOW_PLAYER               → NO_SHOW_PLAYER
UPCOMING + NO_SHOW_COACH                → NO_SHOW_COACH
UPCOMING + CANCEL_COACH                 → CANCELLED_COACH
UPCOMING + CANCEL_PARENT                → CANCELLED_PARENT
IN_PROGRESS + COMPLETE_PENDING          → COMPLETED_PENDING_CONFIRMATION
IN_PROGRESS + DISPUTE                   → DISPUTED
COMPLETED_PENDING_CONFIRMATION + COMPLETE        → COMPLETED
COMPLETED_PENDING_CONFIRMATION + QUICK_COMPLETE  → COMPLETED
COMPLETED_PENDING_CONFIRMATION + DISPUTE         → DISPUTED
COMPLETED + DISPUTE                     → DISPUTED
DISPUTED + SETTLE_REFUND                → REFUND_PENDING
DISPUTED + SETTLE_COMPLETE              → COMPLETED
REFUND_PENDING + REFUND_PROCESSED       → REFUNDED
```

### `BookingSseService` — Thread Safety

Use `ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>>`. When the `onStatusChanged` listener iterates to push events:
- Iterate a snapshot of the list (CoW handles this)
- On send failure, remove the emitter from the registry
- Do NOT hold a lock during `emitter.send()` — SseEmitter.send() can block

Cleanup on `onCompletion`/`onTimeout`/`onError`:
```java
emitter.onCompletion(() -> removeEmitter(bookingId, emitter));
emitter.onTimeout(() -> { tryHeartbeat(emitter); removeEmitter(bookingId, emitter); });
emitter.onError(e -> removeEmitter(bookingId, emitter));
```

Heartbeat on timeout: attempt `emitter.send(SseEmitter.event().name("heartbeat").data(""))` — this triggers the client's `onerror` handler to reconnect.

### Frontend `useBookingSse` — EventSource Pattern

```javascript
// exported from booking.store.js (NOT inside defineStore)
export function useBookingSse(bookingId) {
  const status = ref(null)
  const connectionState = ref('disconnected')
  let es = null
  let retryCount = 0
  let pollingInterval = null
  const delays = [1000, 2000, 4000, 8000, 16000, 30000]

  function connect() {
    es = new EventSource(`/api/bookings/${bookingId}/events`, { withCredentials: true })
    connectionState.value = 'connected'
    es.addEventListener('status', (e) => { status.value = e.data; retryCount = 0 })
    es.onerror = () => {
      es.close()
      retryCount++
      if (retryCount > 3 && !pollingInterval) {
        connectionState.value = 'polling'
        pollingInterval = setInterval(async () => {
          const r = await getBookingById(bookingId)
          status.value = r.data.status
        }, 2000)
      } else {
        connectionState.value = 'reconnecting'
        const delay = delays[Math.min(retryCount - 1, delays.length - 1)]
        setTimeout(connect, delay)
      }
    }
    es.addEventListener('heartbeat', () => { es.close(); connect() })
  }

  function cleanup() {
    es?.close()
    clearInterval(pollingInterval)
    pollingInterval = null
  }

  connect()
  return { status, connectionState, cleanup }
}
```

Import `getBookingById` from `src/api/booking.api` inside the composable. Wire `cleanup` in component via `onUnmounted(cleanup)`.

### `BookingEventResource` — Security Check

The SSE endpoint must verify the requesting user is a party to the booking (not just authenticated):

```java
@GetMapping("/{id}/events")
@PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
public ResponseEntity<SseEmitter> subscribeToEvents(@PathVariable UUID id) {
    Principal principal = (Principal) securityUtil.getCurrentUser();
    Long actorUserId = Long.parseLong(principal.getBusinessId());
    Booking booking = bookingService.getBookingOrThrow(id); // throws 404 if not found
    boolean isParty = Objects.equals(booking.getParentId(), actorUserId)
        || isCoachParty(booking.getCoachId(), actorUserId);
    if (!isParty) throw new OperationNotAllowedException("Not a party to this booking", SecurityError.MISSING_RIGHTS);
    SseEmitter emitter = bookingSseService.subscribe(id, booking.getStatus());
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_EVENT_STREAM)
        .body(emitter);
}
```

`isCoachParty(UUID coachId, Long actorUserId)` uses `coachProfileRepository.findByUserId(actorUserId)` and checks if the coach's id matches `booking.getCoachId()`.

Check `SecurityConstants` for `IS_AUTHENTICATED` constant — add if not present.

### Package and File Location Summary

| File | Package / Path |
|------|----------------|
| `BookingStatus.java` | `platform.booking.contract` |
| `BookingEvent.java` | `platform.booking.contract` |
| `TransitionContext.java` | `platform.booking.contract` |
| `ActorRole.java` | `platform.booking.contract` |
| `BookingStateTransitionException.java` | `platform.booking.contract` |
| `BookingStatusChangedEvent.java` | `platform.booking.contract` |
| `CoachReliabilityStrikeQueuedEvent.java` | `platform.booking.contract` |
| `BookingStateMachine.java` | `platform.booking.service` |
| `BookingSseService.java` | `platform.booking.service` |
| `BookingEventResource.java` | `platform.booking.api` |
| `V32__booking_state_machine.sql` | `src/main/resources/db/migration/` |
| `BookingStateMachineTest.java` | `src/test/.../platform/booking/service/` |
| `BookingSseIT.java` | `src/test/.../platform/booking/api/` |

### Files Modified (Not New)

| File | Change |
|------|--------|
| `BookingService.java` | Remove `ALLOWED_TRANSITIONS` map and `validateTransition()`, add `transition()`, add `BookingStateMachine` injection, refactor `acceptBooking()`/`declineBooking()`, add `getBooking(UUID id)` |
| `ApiAdvice.java` | Add `@ExceptionHandler(BookingStateTransitionException.class)` → 409 |
| `BookingResource.java` | (No change required — existing endpoints remain unchanged) |
| `BookingStateChip.vue` | Add 9 new status entries to `statusMap` |
| `booking.api.js` | Add `getBookingById(id)` |
| `booking.store.js` | Export `useBookingSse` composable (above `defineStore`) |
| `i18n/en/index.js` | Add 9 new booking status label keys |

### Previous Story Learnings from Story 3.3

- `parentId` and `playerId` on `Booking` are **Long** (TSID), not UUID — only `coachId` is UUID
- Module URL prefix is **plural**: `/api/bookings/...` (not `/api/booking/...`)
- `@TransactionalEventListener(AFTER_COMMIT)` in `platform.notification` for all notifications
- `resolveEmail(Long userId)` is a private helper in `BookingService` — reuse pattern
- Scheduler must use per-item try-catch to prevent single failure from rolling back batch
- `BookingRepository.countInFlightBookings()` returns `long` (not `int`) — JPQL COUNT always returns Long
- `BookingStateChip` fallback for unknown status shows raw string + `chip--neutral` — this is intentional graceful degradation

### Security Constants Check

Before adding `@PreAuthorize(SecurityConstants.IS_AUTHENTICATED)` to `BookingEventResource`, grep `SecurityConstants.java` to confirm the constant exists. If only `HAS_COACH_ROLE` and `HAS_PARENT_ROLE` exist, add `IS_AUTHENTICATED = "isAuthenticated()"` to `SecurityConstants`.

### Project Structure Notes

- DDD module: `platform.booking` — this story adds new layers to an existing module
- `BookingStateMachine` goes in `service` layer (it's a domain rule encoder, not infrastructure)
- `BookingSseService` goes in `service` layer (`SseEmitter` is Spring web but the registry is a domain service)
- Do NOT put `BookingSseService` in `infrastructure.*` — it holds booking business state (emitter registry keyed by booking UUID)
- `BookingEventResource` goes in `api` layer — suffixed `Resource`, `@Observed`, `@PreAuthorize` on all methods

### References

- Story 3.3 review learnings: `_bmad-output/implementation-artifacts/skillars-3-3-booking-request-approval-workflow.md#Review Findings`
- Booking entity current state: `src/main/java/.../platform/booking/repo/Booking.java`
- Existing BookingService: `src/main/java/.../platform/booking/service/BookingService.java`
- ApiAdvice exception handlers: `src/main/java/.../platform/security/api/ApiAdvice.java`
- MarketplaceException pattern: `src/main/java/.../platform/marketplace/contract/MarketplaceException.java`
- Project context (package structure, rules): `_bmad-output/project-context.md`
- Epic 3.4 source: `_bmad-output/planning-artifacts/skillars-epics.md` lines 1083–1131

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

- Implemented full 16-state `BookingStateMachine` as immutable `Map<BookingStatus, Map<BookingEvent, BookingStatus>>` using static factory method to avoid type-inference issues with `Map.ofEntries`; fully-qualified enum references required for `NO_SHOW_PLAYER`/`NO_SHOW_COACH` which exist in both `BookingEvent` and `BookingStatus`.
- `BookingService.transition()` now owns all status change logic: state machine validation, refund eligibility calculation, `CoachReliabilityStrikeQueuedEvent` publishing, status persistence, and `BookingStatusChangedEvent` publishing.
- `acceptBooking()` refactored to 3 sequential transition calls (ACCEPT → INITIATE_PAYMENT → PAYMENT_CAPTURED) matching the state graph; stub payment always succeeds.
- `BookingSseService` uses `ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>>` with `@TransactionalEventListener(AFTER_COMMIT)` for thread-safe SSE push after DB commit; heartbeat on timeout triggers client reconnection.
- `BookingEventResource` added with party verification (parent ID or coach profile lookup) and 403 for non-parties.
- `SecurityConstants.IS_AUTHENTICATED` added to support `@PreAuthorize` on SSE endpoint.
- `BookingStateTransitionException` wired into `ApiAdvice` as 409 Conflict.
- Frontend `useBookingSse` composable exported from `booking.store.js` using native `EventSource` with 6-step exponential backoff `[1s→2s→4s→8s→16s→30s]` and polling fallback after 3 failures.
- `BookingStateChip.vue` extended with 9 new status entries; `i18n/en/index.js` extended with matching label keys.
- Flyway V32 migration expands `chk_bkg_status` to all 16 states, adds nullable `refund_amount` and `refund_eligibility` columns.
- SSE integration test uses `restTemplate.execute()` with custom `ResponseExtractor` to capture status/content-type without blocking on the infinite SSE stream; security tests use `Accept: application/json` to avoid content-negotiation failures.

### File List

**New Files:**
- `src/main/resources/db/migration/V32__booking_state_machine.sql`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingStatus.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingEvent.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/ActorRole.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/TransitionContext.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingStateTransitionException.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingStatusChangedEvent.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/CoachReliabilityStrikeQueuedEvent.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingStateMachine.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingSseService.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/BookingEventResource.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingStateMachineTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingSseIT.java`

**Modified Files:**
- `src/main/java/com/softropic/skillars/platform/booking/repo/Booking.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java`
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`
- `src/frontend/src/api/booking.api.js`
- `src/frontend/src/stores/booking.store.js`
- `src/frontend/src/components/booking/BookingStateChip.vue`
- `src/frontend/src/i18n/en/index.js`

## Change Log

| Date | Version | Description | Author |
|------|---------|-------------|--------|
| 2026-06-15 | 1.0 | Story 3.4 implemented: 16-state booking state machine, SSE push on status change, SSE connection management with heartbeat/reconnect, exponential backoff + polling fallback, parent/coach cancellation with refund eligibility, coach reliability strike queuing | claude-sonnet-4-6 |
