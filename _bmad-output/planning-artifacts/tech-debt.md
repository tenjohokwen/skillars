# Tech Debt Backlog

Pre-existing issues surfaced during code review that should be addressed in a dedicated cleanup story. None are blockers for current functionality.

---

## TD-1: Shared `completionLoading` in booking.store.js

**Source:** Story 3.8 review — Finding 1  
**File:** `src/frontend/src/stores/booking.store.js`

All action handlers (`handleStartSession`, `handleEndSession`, `handlePauseSession`, `handleResumeSession`, `handleRequestReschedule`, `handleAcceptReschedule`, `handleDeclineReschedule`, `handleDuplicateNextWeek`) share a single `completionLoading` and `completionError` ref. If two actions are triggered concurrently (e.g., two bookings acted on at once), they will overwrite each other's loading and error state, causing incorrect spinner behavior or swallowed errors.

**Fix:** Give each action category its own scoped loading/error pair, or accept an `actionKey` parameter and track state per-key in a `Map`.

---

## TD-2: Fire-and-Forget Email Notifications (No Retry)

**Source:** Story 3.8 review — Finding 6  
**File:** `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/BookingEmailListener.java`

All `@TransactionalEventListener(phase = AFTER_COMMIT)` handlers publish to an `Envelope` event, which presumably dispatches to a mail service. There is no retry mechanism if the mail service is temporarily unavailable at the moment of dispatch. A transient outage will permanently drop the notification — there is no outbox, no dead-letter queue, and no re-delivery path.

**Fix:** Implement an outbox table (`notification_outbox`) written within the same transaction as the business event, processed by a scheduled poller. Alternatively, configure a Spring retry policy on the mail dispatch call. Applies to all event handlers: `onBookingRequested`, `onBookingConfirmed`, `onBookingDeclined`, `onBookingExpired`, `onBookingReminder`, `onQuickCompleteConfirmationRequired`, `onRescheduleRequested`, `onRescheduleAccepted`, `onRescheduleDeclined`, `onDuplicateBookingProposed`.

---

## ~~TD-3: Empty Email String Not Filtered in Notification Loops~~ ✓ Fixed 2026-06-16

**Source:** Story 3.8 review — Finding 7  
**File:** `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/BookingEmailListener.java`  
**Related:** `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java`

`resolveEmail(Long userId)` returns `""` when a user record is not found. In `onRescheduleAccepted` (line 165) and `onBookingReminder` (line 221), the loop iterates `List.of(parentEmail, coachEmail)` — parent first. If the parent's email is empty and the mail infrastructure throws on an empty address, the coach's notification in the same loop iteration may be skipped.

**Fix:** Filter empty strings before the loop:
```java
List.of(event.getParentEmail(), event.getCoachEmail())
    .stream()
    .filter(e -> e != null && !e.isBlank())
    .forEach(email -> { ... });
```
Apply to both `onRescheduleAccepted` and `onBookingReminder`.
