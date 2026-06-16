# Story skillars-3.7: Session Pause & Resume

Status: done

## Story

As a coach,
I want to pause and resume a live session when there is an interruption on the pitch,
so that the session timer accurately reflects net active coaching time and I can restart without losing session state.

## Acceptance Criteria

1. **AC 1: Pause Session** — Given a booking is in `IN_PROGRESS` status and the coach taps "Pause", when the pause action is confirmed, then `BookingCompletionService.pauseSession(bookingId, coachUserId)` fires `BookingEvent.PAUSE` (COACH) → `PAUSED`. The Active Session Screen freezes the elapsed timer and displays a "PAUSED" indicator. The "End Session" button is hidden while paused.

2. **AC 2: Resume Session** — Given a booking is in `PAUSED` status and the coach taps "Resume", when the resume action is confirmed, then `BookingCompletionService.resumeSession(bookingId, coachUserId)` fires `BookingEvent.RESUME` (COACH) → `IN_PROGRESS`. The elapsed timer resumes from where it left off. The "End Session" button visibility follows the net active time rule (≥ 5 minutes of non-paused time).

3. **AC 3: SSE Updates** — Given a booking is in `IN_PROGRESS` or `PAUSED` status, when the coach taps "Pause" or "Resume", then `BookingStatusChangedEvent` is published and SSE subscribers receive the updated status automatically — no extra wiring required (existing `BookingService.transition()` → `BookingSseService` pipeline handles this).

4. **AC 4: Multiple Cycles** — Given a coach pauses and resumes multiple times in one session, when the session finally ends via "End Session", then net active time is the total elapsed time minus all paused durations — tracked client-side by stopping/restarting the timer interval in `ActiveSessionScreen.vue`. The 5-minute gate for "End Session" is based on net active seconds ≥ 300, not wall-clock time.

## Tasks / Subtasks

### Backend — Database Migration

- [x] Task 1: Flyway migration `V34__booking_paused_status.sql` in `src/main/resources/db/migration` (AC: 1, 2)
  - [x] Drop and recreate the status CHECK constraint to add `PAUSED`:
    ```sql
    ALTER TABLE booking.bookings
        DROP CONSTRAINT chk_bkg_status;

    ALTER TABLE booking.bookings
        ADD CONSTRAINT chk_bkg_status CHECK (status IN (
            'REQUESTED',
            'ACCEPTED',
            'PAYMENT_PENDING',
            'CONFIRMED',
            'UPCOMING',
            'IN_PROGRESS',
            'PAUSED',
            'COMPLETED_PENDING_CONFIRMATION',
            'COMPLETED',
            'DECLINED',
            'CANCELLED_PARENT',
            'CANCELLED_COACH',
            'NO_SHOW_PLAYER',
            'NO_SHOW_COACH',
            'DISPUTED',
            'REFUND_PENDING',
            'REFUNDED'
        ));
    ```
  - [x] This is a non-breaking additive change — no existing rows have status `PAUSED` so the constraint drop/add is safe

### Backend — Domain Enums

- [x] Task 2: Add `PAUSED` to `BookingStatus.java` in `platform.booking.contract` (AC: 1, 2)
  - [x] Current values: `REQUESTED, ACCEPTED, PAYMENT_PENDING, CONFIRMED, UPCOMING, IN_PROGRESS, COMPLETED_PENDING_CONFIRMATION, COMPLETED, DECLINED, CANCELLED_PARENT, CANCELLED_COACH, NO_SHOW_PLAYER, NO_SHOW_COACH, DISPUTED, REFUND_PENDING, REFUNDED`
  - [x] Add `PAUSED` between `IN_PROGRESS` and `COMPLETED_PENDING_CONFIRMATION`
  - [x] **Verify** that the `status` field on `Booking.java` is annotated `@Enumerated(EnumType.STRING)` — confirmed `status` is stored as `String` (VARCHAR) — no JPA enum annotation present, safe to add enum value
  - [x] File: `src/main/java/com/softropic/skillars/platform/booking/contract/BookingStatus.java`

- [x] Task 3: Add `PAUSE` and `RESUME` to `BookingEvent.java` in `platform.booking.contract` (AC: 1, 2)
  - [x] Current values: `ACCEPT, DECLINE, SCHEDULE_UPCOMING, INITIATE_PAYMENT, PAYMENT_CAPTURED, PAYMENT_FAILED, CANCEL_PARENT, CANCEL_COACH, START, NO_SHOW_PLAYER, NO_SHOW_COACH, COMPLETE_PENDING, COMPLETE, QUICK_COMPLETE, DISPUTE, SETTLE_REFUND, SETTLE_COMPLETE, REFUND_PROCESSED`
  - [x] Add `PAUSE` and `RESUME` after `START`
  - [x] File: `src/main/java/com/softropic/skillars/platform/booking/contract/BookingEvent.java`

### Backend — State Machine

- [x] Task 4: Update `BookingStateMachine.java` — add pause/resume transitions (AC: 1, 2)
  - [x] File: `src/main/java/com/softropic/skillars/platform/booking/service/BookingStateMachine.java`
  - [x] Current `IN_PROGRESS` transitions: `COMPLETE_PENDING → COMPLETED_PENDING_CONFIRMATION`, `DISPUTE → DISPUTED`
  - [x] Add to `IN_PROGRESS` map: `BookingEvent.PAUSE → BookingStatus.PAUSED`
  - [x] Add new `PAUSED` entry to the transition map:
    ```java
    t.put(BookingStatus.PAUSED, Map.of(
        BookingEvent.RESUME,           BookingStatus.IN_PROGRESS,
        BookingEvent.COMPLETE_PENDING, BookingStatus.COMPLETED_PENDING_CONFIRMATION,
        BookingEvent.CANCEL_COACH,     BookingStatus.CANCELLED_COACH,
        BookingEvent.CANCEL_PARENT,    BookingStatus.CANCELLED_PARENT
    ));
    ```
  - [x] `CANCEL_COACH` and `CANCEL_PARENT` mirror the same transitions already defined from `IN_PROGRESS` — without them, any system-initiated or admin cancellation on a PAUSED booking throws an unhandled state machine exception
  - [x] The `COMPLETE_PENDING` from `PAUSED` allows a coach to end from paused state via backend — frontend hides the button but backend supports it
  - [x] `Map.of()` cap is 10 entries. New `PAUSED` map has 4 entries. Safe.

### Backend — BookingService EVENT_ROLES

- [x] Task 5: Add `PAUSE` and `RESUME` to `EVENT_ROLES` in `BookingService.java` (AC: 1, 2, 3)
  - [x] File: `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
  - [x] `EVENT_ROLES` is a `Map.ofEntries(...)` at line 59. Currently 18 entries — adding 2 = 20 total. `Map.ofEntries()` has no entry cap.
  - [x] Add after the `START` entry:
    ```java
    Map.entry(BookingEvent.PAUSE,  EnumSet.of(ActorRole.COACH)),
    Map.entry(BookingEvent.RESUME, EnumSet.of(ActorRole.COACH)),
    ```
  - [x] Without these entries, `validateActorAuthorization()` will throw for COACH firing PAUSE/RESUME

### Backend — BookingCompletionService

- [x] Task 6: Add `pauseSession()` and `resumeSession()` methods in `BookingCompletionService.java` (AC: 1, 2)
  - [x] File: `src/main/java/com/softropic/skillars/platform/booking/service/BookingCompletionService.java`
  - [ ] Add after `endSession()`:
    ```java
    @Transactional
    public void pauseSession(UUID bookingId, Long coachUserId) {
        CoachProfile coach = resolveCoach(coachUserId);
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        verifyCoachOwnership(booking, coach);
        verifyStatus(booking, BookingStatus.IN_PROGRESS);
        try {
            bookingService.transition(bookingId, BookingEvent.PAUSE, new TransitionContext(ActorRole.COACH, coachUserId));
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException("Booking status changed concurrently — retry", SecurityError.MISSING_RIGHTS);
        }
    }

    @Transactional
    public void resumeSession(UUID bookingId, Long coachUserId) {
        CoachProfile coach = resolveCoach(coachUserId);
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        verifyCoachOwnership(booking, coach);
        verifyStatus(booking, BookingStatus.PAUSED);
        try {
            bookingService.transition(bookingId, BookingEvent.RESUME, new TransitionContext(ActorRole.COACH, coachUserId));
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException("Booking status changed concurrently — retry", SecurityError.MISSING_RIGHTS);
        }
    }
    ```
  - [x] `resolveCoach()`, `verifyCoachOwnership()`, `verifyStatus()` are existing private helpers in the same class — reuse them exactly as `endSession()` does
  - [x] `OptimisticLockingFailureException` is already imported in the file (used in `confirmCompletion()`) — no new import needed

- [x] Task 7: Update `endSession()` in `BookingCompletionService.java` to also accept `PAUSED` status (AC: 1)
  - [ ] Current `endSession()` calls `verifyStatus(booking, BookingStatus.IN_PROGRESS)` — this throws if a coach somehow calls end while paused via API
  - [ ] Change `verifyStatus()` call to allow both statuses:
    ```java
    @Transactional
    public void endSession(UUID bookingId, Long coachUserId) {
        CoachProfile coach = resolveCoach(coachUserId);
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        verifyCoachOwnership(booking, coach);
        BookingStatus current = BookingStatus.valueOf(booking.getStatus());
        if (current != BookingStatus.IN_PROGRESS && current != BookingStatus.PAUSED) {
            throw new OperationNotAllowedException(
                "Booking is in status " + current + ", expected IN_PROGRESS or PAUSED",
                SecurityError.MISSING_RIGHTS);
        }
        try {
            bookingService.transition(bookingId, BookingEvent.COMPLETE_PENDING, new TransitionContext(ActorRole.COACH, coachUserId));
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException("Booking status changed concurrently — retry", SecurityError.MISSING_RIGHTS);
        }
    }
    ```
  - [x] Both `IN_PROGRESS → COMPLETE_PENDING` and `PAUSED → COMPLETE_PENDING` are valid state machine transitions after Task 4

### Backend — REST Endpoints

- [x] Task 8: Add pause/resume endpoints to `SessionCompletionResource.java` (AC: 1, 2)
  - [x] File: `src/main/java/com/softropic/skillars/platform/booking/api/SessionCompletionResource.java`
  - [ ] Add after the `endSession` endpoint:
    ```java
    @PostMapping("/{id}/pause")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> pauseSession(@PathVariable UUID id) {
        bookingCompletionService.pauseSession(id, currentUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> resumeSession(@PathVariable UUID id) {
        bookingCompletionService.resumeSession(id, currentUserId());
        return ResponseEntity.noContent().build();
    }
    ```
  - [x] Return `204 No Content` per project REST conventions for body-less success
  - [x] `@Observed(name = "booking.completion")` is on the class — metrics covered automatically

### Backend — Tests

- [x] Task 9: Unit test `SessionPauseResumeServiceTest.java` in `src/test/java/.../platform/booking/service/` (AC: 1, 2, 4)
  - [x] `@ExtendWith(MockitoExtension.class)` — no Spring context, mock all dependencies
  - [x] Test 1: Single pause — `pauseSession()` with IN_PROGRESS booking fires `PAUSE` (COACH) transition; verifies `bookingService.transition()` called with `BookingEvent.PAUSE`
  - [x] Test 2: Resume — `resumeSession()` with PAUSED booking fires `RESUME` (COACH) transition
  - [x] Test 3: Multiple cycles — `pauseSession()` then `resumeSession()` twice in sequence; each fires the correct transition
  - [x] Test 4: End from PAUSED — `endSession()` with PAUSED booking fires `COMPLETE_PENDING` (COACH); status check allows PAUSED
  - [x] Test 5: `pauseSession()` with non-IN_PROGRESS booking → throws `OperationNotAllowedException`
  - [x] Test 6: `resumeSession()` with non-PAUSED booking → throws `OperationNotAllowedException`
  - [x] Test 7: `pauseSession()` with wrong coach → throws `OperationNotAllowedException`
  - [x] Used AssertJ for assertions; Mockito for mocks — project standards (also includes optimistic lock test)

- [x] Task 10: Extend `SessionCompletionResourceIT.java` with pause/resume tests (AC: 1, 2, 3)
  - [x] File: `src/test/java/.../platform/booking/api/SessionCompletionResourceIT.java`
  - [x] Test 1: `POST /api/bookings/{id}/pause` from IN_PROGRESS → 204 + booking status = PAUSED
  - [x] Test 2: `POST /api/bookings/{id}/resume` from PAUSED → 204 + booking status = IN_PROGRESS
  - [x] Test 3: `POST /api/bookings/{id}/pause` unauthenticated → 401
  - [x] Test 4: `POST /api/bookings/{id}/pause` with PARENT auth → 403
  - [x] Test 5: `POST /api/bookings/{id}/pause` from PAUSED (already paused) → 4xx (state machine rejects)
  - [x] Test 6: `POST /api/bookings/{id}/end` from PAUSED → 204 + status = COMPLETED_PENDING_CONFIRMATION
  - [x] Follow existing `@SpringBootTest`, `@Testcontainers`, `@Import(TestConfig.class)`, `@ActiveProfiles({"dev","test"})` annotation pattern

### Frontend — API

- [x] Task 11: `booking.api.js` — add pause/resume API calls (AC: 1, 2)
  - [x] File: `src/frontend/src/api/booking.api.js`
  - [ ] Add after `endSession`:
    ```js
    export const pauseSession = (id) => api.post(`/api/bookings/${id}/pause`)
    export const resumeSession = (id) => api.post(`/api/bookings/${id}/resume`)
    ```

### Frontend — Store

- [x] Task 12: `booking.store.js` — add pause/resume actions (AC: 1, 2)
  - [x] File: `src/frontend/src/stores/booking.store.js`
  - [x] Import `pauseSession` and `resumeSession` from `src/api/booking.api` (add to existing import statement)
  - [ ] Add two new actions (follow same pattern as `handleEndSession`):
    ```js
    async function handlePauseSession(bookingId) {
      completionLoading.value = true
      completionError.value = null
      try {
        await pauseSession(bookingId)
      } catch (e) {
        completionError.value = e
        throw e
      } finally {
        completionLoading.value = false
      }
    }

    async function handleResumeSession(bookingId) {
      completionLoading.value = true
      completionError.value = null
      try {
        await resumeSession(bookingId)
      } catch (e) {
        completionError.value = e
        throw e
      } finally {
        completionLoading.value = false
      }
    }
    ```
  - [x] Expose both in `return { ... }` block

### Frontend — ActiveSessionScreen.vue

- [x] Task 13: `ActiveSessionScreen.vue` — add pause/resume UI and timer logic (AC: 1, 2, 4)
  - [x] File: `src/frontend/src/components/booking/ActiveSessionScreen.vue`
  - [x] **Add `bookingStatus` prop** — the parent already receives SSE status updates; pass it down:
    ```js
    const props = defineProps({
      // ... existing props ...
      bookingStatus: { type: String, required: true }
    })
    ```
  - [x] **Add reactive state** (after existing `const elapsed = ref(0)` and `const ending = ref(false)`):
    ```js
    const isPaused = ref(false)
    const pausing = ref(false)
    const resuming = ref(false)
    ```
  - [x] **Update `endAllowed` computed** — currently `elapsed.value >= 300`; change to:
    ```js
    const endAllowed = computed(() => !isPaused.value && elapsed.value >= 300)
    ```
    `elapsed` only increments when not paused (timer interval stops on pause), so it naturally tracks net active seconds
  - [x] **Update timer interval** — stop on pause, restart on resume. Change `onMounted`:
    ```js
    onMounted(() => {
      if (props.bookingStatus === 'PAUSED') {
        isPaused.value = true
        // Do NOT start the interval — session is already paused server-side
      } else {
        timer = setInterval(() => { elapsed.value++ }, 1000)
      }
    })
    ```
    This handles the reconnect case: if the coach reloads the app while the booking is already `PAUSED`, the client starts in the correct paused state instead of showing an active timer.
  - [x] **Add SSE-driven pause watcher** — so a pause triggered from another device/tab also stops the local timer:
    ```js
    watch(() => props.bookingStatus, (status) => {
      if (status === 'PAUSED' && !isPaused.value) {
        isPaused.value = true
        clearInterval(timer)
        timer = null
      }
    })
    ```
    The interval is also stopped/restarted directly by `handlePauseSession`/`handleResumeSession` for the local-action path.
  - [x] **Add `handlePauseSession()` function**:
    ```js
    async function handlePauseSession() {
      pausing.value = true
      try {
        clearInterval(timer)
        timer = null
        isPaused.value = true
        await bookingStore.handlePauseSession(props.bookingId)
      } catch (e) {
        // Revert optimistic UI on error
        isPaused.value = false
        timer = setInterval(() => { elapsed.value++ }, 1000)
      } finally {
        pausing.value = false
      }
    }
    ```
  - [x] **Add `handleResumeSession()` function**:
    ```js
    async function handleResumeSession() {
      resuming.value = true
      try {
        await bookingStore.handleResumeSession(props.bookingId)
        isPaused.value = false
        timer = setInterval(() => { elapsed.value++ }, 1000)
      } catch (e) {
        // Stay paused on error — do not restart timer
      } finally {
        resuming.value = false
      }
    }
    ```
  - [x] **Update template** — add Pause/Resume buttons and PAUSED indicator. The current template has only: back button, drill name, timer, pips, next drill, and end button. Insert between pips and end button:
    ```html
    <!-- PAUSED indicator -->
    <div v-if="isPaused" class="active-session__paused-indicator">
      {{ t('booking.completion.paused') }}
    </div>

    <!-- Pause button: shown when IN_PROGRESS (not paused) -->
    <q-btn
      v-if="!isPaused"
      flat
      class="active-session__pause-btn"
      :label="t('booking.completion.pause')"
      :loading="pausing"
      @click="handlePauseSession"
    />

    <!-- Resume button: shown when paused -->
    <q-btn
      v-if="isPaused"
      unelevated
      class="active-session__resume-btn"
      :label="t('booking.completion.resume')"
      :loading="resuming"
      @click="handleResumeSession"
    />
    ```
  - [x] **Update End Session button** — add `v-if="!isPaused"` so it hides while paused:
    ```html
    <q-btn
      v-if="!isPaused"
      unelevated
      class="active-session__end-btn"
      ...
    />
    ```
  - [x] **Add styles** for new elements in `<style lang="scss" scoped>`:
    ```scss
    .active-session__paused-indicator {
      font-size: 24px;
      font-weight: 700;
      color: var(--accent-warning);
      letter-spacing: 0.1em;
      margin-bottom: 24px;
      animation: pulse 2s infinite;
    }

    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.5; }
    }

    .active-session__pause-btn {
      width: 100%;
      max-width: 400px;
      height: 48px;
      color: var(--text-secondary);
      margin-bottom: 12px;
    }

    .active-session__resume-btn {
      width: 100%;
      max-width: 400px;
      height: 56px;
      background: var(--accent-warning);
      color: #fff;
      font-size: 18px;
      font-weight: 600;
      border-radius: 12px;
      margin-bottom: 12px;
    }
    ```

### Frontend — BookingStateChip.vue

- [x] Task 14: `BookingStateChip.vue` — add `PAUSED` chip entry (AC: 1)
  - [x] File: `src/frontend/src/components/booking/BookingStateChip.vue`
  - [x] Add to `statusMap` after `IN_PROGRESS`:
    ```js
    PAUSED: { key: 'booking.requests.statusPaused', cls: 'chip--warning' },
    ```
  - [x] Without this, a booking displaying `PAUSED` status shows raw enum value instead of translated label

### Frontend — i18n

- [x] Task 15: `i18n/en/index.js` — add pause/resume keys (AC: 1, 2)
  - [x] File: `src/frontend/src/i18n/en/index.js`
  - [x] Under `booking.completion` (currently has: `endSession`, `endSessionDisabled`, `quickComplete`, `summaryTitle`, `confirmCompletion`, `confirmationSuccess`, `wrapUpTitle`, `noDrillPlan`, `nextDrill`, `sessionIntelligencePlaceholder`), add:
    ```js
    pause: 'Pause',
    resume: 'Resume',
    paused: 'PAUSED',
    ```
  - [x] Under `booking.requests` (check existing file — `statusInProgress` is already there), add:
    ```js
    statusPaused: 'Paused',
    ```
  - [x] Do NOT add `booking.completion.startSession` — `booking.schedule.startSession` already exists from Story 3.5

## Dev Notes

### ⚠️ CRITICAL: Timer Logic — Stop Interval on Pause

The correct pattern to freeze the timer display when paused is to `clearInterval(timer)` on pause and call `setInterval(...)` again on resume. Do NOT leave the interval running during pause expecting to subtract paused time — that requires reactive tracking of current-pause duration which is complex and error-prone.

With the stop/restart approach, `elapsed.value` naturally becomes net active seconds. The `endAllowed` computed `!isPaused.value && elapsed.value >= 300` is correct: `elapsed` only advanced during non-paused time.

**Optimistic UI for pause:** Clear the interval and set `isPaused = true` BEFORE the API call. If the API call fails, revert `isPaused = false` and restart the interval. This prevents the timer from ticking during the network round-trip.

**Resume order:** Call the API FIRST, then set `isPaused = false` and restart the interval. This prevents the timer from ticking if the resume API call fails.

### ⚠️ CRITICAL: `endSession()` Must Accept PAUSED Status

The current `endSession()` in `BookingCompletionService.java` calls `verifyStatus(booking, BookingStatus.IN_PROGRESS)` (line 62–63). After Task 4 adds `PAUSED → COMPLETE_PENDING` to the state machine, this guard must be updated to allow both `IN_PROGRESS` and `PAUSED`. If not changed, a coach calling end while paused will get a 403, even though the state machine would permit it.

### ⚠️ CRITICAL: V34 Migration Number

The previous migration is `V33__session_completion_data.sql` (created in Story 3.6). The new migration **must** be `V34__booking_paused_status.sql`. Do not re-use V33.

### ⚠️ CRITICAL: SSE Propagation is Automatic

`BookingService.transition()` always calls `eventPublisher.publishEvent(new BookingStatusChangedEvent(this, bookingId, newStatus.name()))` after every successful transition. So PAUSE → PAUSED and RESUME → IN_PROGRESS will both push SSE status updates to all subscribers without any extra code. Do NOT add additional SSE publishing in `pauseSession()` or `resumeSession()`.

### ⚠️ CRITICAL: `endAllowed` Must Check `!isPaused.value`

The AC says "the End Session button is hidden while paused". This is implemented via `v-if="!isPaused"` on the End Session button. The `endAllowed` computed also includes `!isPaused.value` as a defense-in-depth guard, but the primary hide is the `v-if`. Both must be set.

### Current State of Modified Files

**`BookingStateMachine.java` (current `IN_PROGRESS` map):**
```java
t.put(BookingStatus.IN_PROGRESS, Map.of(
    BookingEvent.COMPLETE_PENDING, BookingStatus.COMPLETED_PENDING_CONFIRMATION,
    BookingEvent.DISPUTE, BookingStatus.DISPUTED
));
```
After Task 4, becomes:
```java
t.put(BookingStatus.IN_PROGRESS, Map.of(
    BookingEvent.PAUSE,            BookingStatus.PAUSED,
    BookingEvent.COMPLETE_PENDING, BookingStatus.COMPLETED_PENDING_CONFIRMATION,
    BookingEvent.DISPUTE,          BookingStatus.DISPUTED
));
```
Plus new `PAUSED` map added.

**`ActiveSessionScreen.vue` (current `endAllowed`):**
```js
const endAllowed = computed(() => elapsed.value >= 300)
```
After Task 13, becomes:
```js
const endAllowed = computed(() => !isPaused.value && elapsed.value >= 300)
```

**`BookingStateChip.vue` (current `IN_PROGRESS` entry, next line to add after):**
```js
IN_PROGRESS: { key: 'booking.requests.statusInProgress', cls: 'chip--primary' },
```
Add after it:
```js
PAUSED: { key: 'booking.requests.statusPaused', cls: 'chip--warning' },
```

### Pause UX Guidance

- Pause button: `flat` style (secondary visual weight — it's not the primary action)
- Resume button: `unelevated` with `--accent-warning` background (amber/orange — draws attention that session is paused)
- "PAUSED" text indicator: amber color, pulsing animation — coaches on a pitch need immediate visual confirmation
- End Session button: hidden via `v-if` when paused, not just disabled — hiding is clearer than a greyed-out state

### API Path Convention

All endpoints use `/api/bookings/` (plural). The epics dev notes occasionally say `/api/booking/` — that is a documentation error confirmed across all previous stories 3.3–3.6. Correct paths:
- `POST /api/bookings/{id}/pause`
- `POST /api/bookings/{id}/resume`

### Coach Identity Resolution Pattern

`currentUserId()` in `SessionCompletionResource` returns the user ID (`Long`). `BookingCompletionService.resolveCoach(coachUserId)` converts it to a `CoachProfile` with UUID `coachId`. This is the existing pattern — do not bypass it.

### Package and File Location Summary

| File | Package / Path |
|------|----------------|
| `V34__booking_paused_status.sql` | `src/main/resources/db/migration/` |
| `BookingStatus.java` | `platform.booking.contract` |
| `BookingEvent.java` | `platform.booking.contract` |
| `BookingStateMachine.java` | `platform.booking.service` |
| `BookingService.java` | `platform.booking.service` |
| `BookingCompletionService.java` | `platform.booking.service` |
| `SessionCompletionResource.java` | `platform.booking.api` |
| `SessionPauseResumeServiceTest.java` | `src/test/.../platform/booking/service/` |
| `SessionCompletionResourceIT.java` | `src/test/.../platform/booking/api/` (extend existing) |
| `booking.api.js` | `src/frontend/src/api/` |
| `booking.store.js` | `src/frontend/src/stores/` |
| `ActiveSessionScreen.vue` | `src/frontend/src/components/booking/` |
| `BookingStateChip.vue` | `src/frontend/src/components/booking/` |
| `i18n/en/index.js` | `src/frontend/src/i18n/en/` |

### Files Summary

**New Files:**
- `src/main/resources/db/migration/V34__booking_paused_status.sql`
- `src/test/java/.../platform/booking/service/SessionPauseResumeServiceTest.java`

**Modified Files:**
- `src/main/java/.../platform/booking/contract/BookingStatus.java` — add `PAUSED`
- `src/main/java/.../platform/booking/contract/BookingEvent.java` — add `PAUSE`, `RESUME`
- `src/main/java/.../platform/booking/service/BookingStateMachine.java` — add `PAUSE` to `IN_PROGRESS` map; add new `PAUSED` map
- `src/main/java/.../platform/booking/service/BookingService.java` — add `PAUSE`/`RESUME` to `EVENT_ROLES`
- `src/main/java/.../platform/booking/service/BookingCompletionService.java` — add `pauseSession()`, `resumeSession()`; update `endSession()` status check
- `src/main/java/.../platform/booking/api/SessionCompletionResource.java` — add `/pause` and `/resume` endpoints
- `src/test/.../platform/booking/api/SessionCompletionResourceIT.java` — add pause/resume tests
- `src/frontend/src/api/booking.api.js` — add `pauseSession`, `resumeSession`
- `src/frontend/src/stores/booking.store.js` — add `handlePauseSession`, `handleResumeSession`
- `src/frontend/src/components/booking/ActiveSessionScreen.vue` — pause/resume state, timer stop/restart, buttons, PAUSED indicator
- `src/frontend/src/components/booking/BookingStateChip.vue` — add `PAUSED` entry
- `src/frontend/src/i18n/en/index.js` — add pause/resume i18n keys

### Previous Story Intelligence (from Story 3.6)

- `parentId` and `playerId` on `Booking` are **Long** (TSID), not UUID — only `coachId` is UUID
- API prefix is **plural**: `/api/bookings/...` (never `/api/booking/...`)
- `SecurityUtil.getCurrentUser()` → cast to `(Principal)` → `.getBusinessId()` → `Long.parseLong()` — already implemented in `SessionCompletionResource.currentUserId()`
- SSE `BookingStatusChangedEvent` fires on every `bookingService.transition()` call — no extra wiring needed
- `BookingStateChip.vue` currently missing `PAUSED` — must add it or paused bookings show raw enum text
- `coachProfileRepository.findByUserId(coachUserId)` — use `resolveCoach()` private helper, already exists in `BookingCompletionService`
- `@TransactionalEventListener(AFTER_COMMIT)` for all event listeners in `platform.notification`
- The `QUICK_COMPLETE` event is a review-patch finding: Quick Complete Review Fix confirmed it uses `QUICK_COMPLETE` (COACH) for Live Mode and `COMPLETE` (PARENT/SYSTEM) for Quick Mode — do not confuse with the new `PAUSE`/`RESUME` events

### References

- Epic source: `_bmad-output/planning-artifacts/skillars-epics.md` (Story 3.7 section, approx. line 1241)
- `BookingStateMachine.java`: `src/main/java/com/softropic/skillars/platform/booking/service/BookingStateMachine.java`
- `BookingService.java`: `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `BookingCompletionService.java`: `src/main/java/com/softropic/skillars/platform/booking/service/BookingCompletionService.java`
- `SessionCompletionResource.java`: `src/main/java/com/softropic/skillars/platform/booking/api/SessionCompletionResource.java`
- `ActiveSessionScreen.vue`: `src/frontend/src/components/booking/ActiveSessionScreen.vue`
- `BookingStateChip.vue`: `src/frontend/src/components/booking/BookingStateChip.vue`
- Previous story: `_bmad-output/implementation-artifacts/skillars-3-6-session-completion-live-mode-quick-complete.md`
- Project context: `_bmad-output/project-context.md`

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

None.

### Completion Notes List

- Implemented full pause/resume session lifecycle across backend and frontend.
- V34 migration adds `PAUSED` to the booking status CHECK constraint — non-breaking additive change.
- `BookingStatus.PAUSED` added between `IN_PROGRESS` and `COMPLETED_PENDING_CONFIRMATION` — safe because `Booking.status` is stored as `String` (not JPA enum ordinal).
- `BookingStateMachine` now has `IN_PROGRESS → PAUSE → PAUSED` and `PAUSED → RESUME → IN_PROGRESS` transitions, plus `PAUSED → COMPLETE_PENDING`, `CANCEL_COACH`, `CANCEL_PARENT` for resilience.
- `endSession()` updated to accept both `IN_PROGRESS` and `PAUSED` statuses, wrapped in `OptimisticLockingFailureException` catch per RF-2.
- `pauseSession()` and `resumeSession()` use optimistic-update-with-revert pattern in frontend: pause clears interval optimistically, reverts on API error; resume calls API first, then restarts interval.
- SSE reconnect handling: on mount checks `bookingStatus === 'PAUSED'` and sets `isPaused.value = true` without starting the timer. A `watch()` also covers multi-device/tab PAUSED SSE events (RF-1 fix).
- Quasar build initially failed due to `no-unused-vars` on bare `catch (e)` blocks — fixed by using bare `catch {}` syntax (ES2019).
- Unit tests: 8/8 pass in `SessionPauseResumeServiceTest`. Existing `BookingCompletionServiceTest` (6) and `BookingStateMachineTest` (52) all pass — no regressions.

### File List

- `src/main/resources/db/migration/V34__booking_paused_status.sql` (new)
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingStatus.java` (modified — added PAUSED)
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingEvent.java` (modified — added PAUSE, RESUME)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingStateMachine.java` (modified — PAUSE transition from IN_PROGRESS; new PAUSED map)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java` (modified — PAUSE/RESUME in EVENT_ROLES)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingCompletionService.java` (modified — pauseSession, resumeSession, updated endSession)
- `src/main/java/com/softropic/skillars/platform/booking/api/SessionCompletionResource.java` (modified — /pause and /resume endpoints)
- `src/test/java/com/softropic/skillars/platform/booking/service/SessionPauseResumeServiceTest.java` (new)
- `src/test/java/com/softropic/skillars/platform/booking/api/SessionCompletionResourceIT.java` (modified — 6 pause/resume tests added)
- `src/frontend/src/api/booking.api.js` (modified — pauseSession, resumeSession exports)
- `src/frontend/src/stores/booking.store.js` (modified — handlePauseSession, handleResumeSession actions)
- `src/frontend/src/components/booking/ActiveSessionScreen.vue` (modified — pause/resume UI, timer logic, PAUSED indicator, SSE watcher)
- `src/frontend/src/components/booking/BookingStateChip.vue` (modified — PAUSED entry in statusMap)
- `src/frontend/src/i18n/en/index.js` (modified — pause, resume, paused, statusPaused keys)

### Review Findings

#### RF-1: No reconnect handling when coach re-opens app while booking is PAUSED — VALID (Medium)

**Location:** `ActiveSessionScreen.vue` — Task 13

**Issue:** Task 13 handles pause/resume actions but has no spec for what happens when the component mounts and the backing booking is already in `PAUSED` status (e.g., coach kills the browser tab and reopens the app). On fresh mount, `isPaused = ref(false)` and `elapsed = ref(0)`, so the client-side state diverges from the server: net active time is lost and the PAUSED indicator is not shown.

A `beforeunload` handler alone is insufficient — it is unreliable on mobile and can be suppressed. The durable fix is a mount-time status check.

**Required changes (add to Task 13):**

1. Add a `bookingStatus` prop (type `String`) sourced from the parent, which already receives SSE updates. On `onMounted`, if `bookingStatus === 'PAUSED'`, set `isPaused.value = true` and do NOT start the timer interval.
2. Watch the `bookingStatus` prop so that an SSE-driven PAUSED update (from another device/tab) also stops the timer: `watch(() => props.bookingStatus, (s) => { if (s === 'PAUSED' && !isPaused.value) { isPaused.value = true; clearInterval(timer); timer = null } })`

Note: "timer drifts" is inaccurate — the client-side timer simply resets to zero on remount; accumulated net active time is lost, not corrupted.

---

#### RF-2: `pauseSession()` / `endSession()` do not catch `OptimisticLockingFailureException` — VALID (Low)

**Location:** `BookingCompletionService.java` — Tasks 6 & 7

**Issue:** `Booking` already has `@Version private Integer version` (optimistic locking). In a concurrent `endSession()` + `pauseSession()` race, the losing transaction throws `OptimisticLockingFailureException`, which propagates as an unhandled 500 to the client. There is no silent data corruption — the state machine remains consistent — but the error response is unfriendly.

The review's suggested fix (`@Lock(LockModeType.PESSIMISTIC_WRITE)`) is wrong for this project: pessimistic locks are not used anywhere and contradict the established pattern. The correct fix mirrors `confirmCompletion()`.

**Required change (add to Tasks 6 & 7):** Wrap the `bookingService.transition(...)` call in both `pauseSession()` and `endSession()` with a catch for `OptimisticLockingFailureException`:
```java
try {
    bookingService.transition(bookingId, BookingEvent.PAUSE, new TransitionContext(ActorRole.COACH, coachUserId));
} catch (OptimisticLockingFailureException e) {
    throw new OperationNotAllowedException("Booking status changed concurrently — retry", SecurityError.MISSING_RIGHTS);
}
```
Add the same pattern in `resumeSession()`. Add `OptimisticLockingFailureException` to the import list (already present in the file via the `confirmCompletion()` catch).

---

#### RF-3: Quick Complete auto-confirm could fire on a PAUSED session — FALSE POSITIVE

**Location:** `QuickCompleteTimeoutService.java` / `SessionCompletionDataRepository`

**Finding was invalid.** The `findPendingQuickCompletes` query already requires `b.status = 'COMPLETED_PENDING_CONFIRMATION'`, which is mutually exclusive with `PAUSED`. A Live Mode session also produces no `SessionCompletionData` record with `completionMode = 'QUICK'`. Even if both conditions were somehow met, the state machine has no `PAUSED → COMPLETE` transition. Adding `AND b.status != 'PAUSED'` to the query would be dead code. No change required.

#### Code Review Pass (2026-06-16)

- [x] [Review][Patch] **P1 [Critical] `bookingStatus` prop not passed from `CoachCommandCenterPage.vue`** — `ActiveSessionScreen` declares `bookingStatus: { type: String, required: true }` but the parent never binds `:booking-status`. The `onMounted` reconnect guard and the SSE `watch()` are permanently dead in production. Fix: add `:booking-status="activeBookingStatus"` (or equivalent reactive ref) to the `<ActiveSessionScreen>` binding in `CoachCommandCenterPage.vue`. [`src/frontend/src/components/booking/ActiveSessionScreen.vue`]
- [x] [Review][Patch] **P2 [Medium] Back button visible while PAUSED at <300s — coaching can navigate away, orphaning server PAUSED state** — The guard was changed from `v-if="!endAllowed"` to `v-if="elapsed < 300"`. Since `endAllowed` previously was only `elapsed >= 300`, these were equivalent — but now a paused session at elapsed < 300 leaves the back button visible. If the coach taps it, the overlay closes via `@close`, no API call is made, and the booking is stuck in `PAUSED` server-side with no UI path to resume or end. Fix: change guard to `v-if="!isPaused && elapsed < 300"`. [`src/frontend/src/components/booking/ActiveSessionScreen.vue` — back button `v-if`]
- [x] [Review][Patch] **P3 [Low] Vague `4xx` assertions in IT tests for wrong-coach and already-paused scenarios** — `pauseSession_alreadyPaused_returns4xx`, `pauseSession_wrongCoach_returns4xx`, and `resumeSession_wrongCoach_returns4xx` all assert `is4xxClientError()` instead of specific status codes. A regression returning 404 (IDOR leak) instead of 403 for wrong-coach, or 500 for already-paused, would pass these tests. Fix: assert `HttpStatus.FORBIDDEN` (403) for wrong-coach, and the concrete error status (e.g. 422 or 400) for already-paused. [`src/test/java/.../booking/api/SessionCompletionResourceIT.java`]
- [x] [Review][Defer] **D1 [Medium] SSE race during in-flight pause: remote resume from another device can restart timer while `pausing=true`** — If an SSE update delivers `IN_PROGRESS` while the local pause API call is in-flight, the `watch` handler calls `startTimer()` (guard passes because `isPaused=true`). Timer runs while pause button shows `:loading`. Server state remains consistent (optimistic lock rejects conflicting transition); UI self-corrects on next SSE event. Real multi-device scenario, beyond this story's scope. [`src/frontend/src/components/booking/ActiveSessionScreen.vue`] — deferred, multi-device edge case beyond scope
- [x] [Review][Defer] **D2 [High] SSE heartbeat handler closes/reopens EventSource unconditionally, resetting retry counter while active polling is running** — Pre-existing in `booking.store.js`; not introduced by this story. Can cause multi-second status gaps when heartbeat arrives during polling fallback. — deferred, pre-existing
- [x] [Review][Defer] **D3 [Medium] `elapsed` resets to 0 on component remount; `sessionStartTime` prop is never consumed** — Pre-existing limitation. If the component remounts (navigation, SSE reconnect), elapsed active time is lost. `sessionStartTime` exists to reconstruct elapsed but is never used. [`src/frontend/src/components/booking/ActiveSessionScreen.vue`] — deferred, pre-existing
- [x] [Review][Defer] **D4 [Low] `completionLoading` is shared across pause/resume/end — no per-operation distinction** — Pre-existing store design. The component uses its own `pausing`/`resuming` refs so button spinners are correct; only consumers of `completionLoading` (e.g., navigation guards) see mixed signals. [`src/frontend/src/stores/booking.store.js`] — deferred, pre-existing

## Change Log

| Date | Version | Description | Author |
|------|---------|-------------|--------|
| 2026-06-16 | 1.0 | Story created with full implementation context | claude-sonnet-4-6 |
| 2026-06-16 | 1.1 | Adversarial review pass: RF-1 reconnect handling added to Task 13; RF-2 OptimisticLockingFailureException catch added to Tasks 6, 7; RF-5 CANCEL_COACH/CANCEL_PARENT added to PAUSED state machine map (Task 4); RF-6 @Enumerated(EnumType.STRING) verification added to Task 2 | claude-sonnet-4-6 |
| 2026-06-16 | 1.2 | Full implementation: all 15 tasks completed. 8 new unit tests pass; Quasar build passes; no regressions in existing suite. | claude-sonnet-4-6 |
