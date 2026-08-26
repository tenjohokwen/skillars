# Tech Debt Backlog

Pre-existing issues surfaced during code review that should be addressed in a dedicated cleanup story. None are blockers for current functionality.

---

## ~~TD-1: Shared `completionLoading` in booking.store.js~~ ✓ Resolved 2026-08-26 (`skillars-deferred-69` AC10)

**Source:** Story 3.8 review — Finding 1  
**File:** `src/frontend/src/stores/booking.store.js`

**Resolved by deletion, not the originally-proposed per-action-key `Map` refactor** — `skillars-deferred-69` story creation found the clobbering scenario this item describes is no longer reachable: a full-repo grep found zero consumers of `completionLoading`/`completionError` anywhere in `src/frontend/src` — every page calling the 10 handlers that set them already tracks its own local per-row loading state instead (e.g. `ParentBookingsPage.vue`'s `reschedulingId`/`confirmingId`). The refs were dead exported state, not a live clobbering bug, by the time this story shipped. AC10 removed both refs, every `completionLoading.value = .../completionError.value = ...` assignment across all handlers, and the two export lines — each handler's own `try`/`catch`/`throw e` behavior is what callers actually rely on, and that was preserved (simplified to a plain `await`, since a bare rethrow added nothing).

ORIGINAL: All action handlers (`handleStartSession`, `handleEndSession`, `handlePauseSession`, `handleResumeSession`, `handleRequestReschedule`, `handleAcceptReschedule`, `handleDeclineReschedule`, `handleDuplicateNextWeek`) share a single `completionLoading` and `completionError` ref. If two actions are triggered concurrently (e.g., two bookings acted on at once), they will overwrite each other's loading and error state, causing incorrect spinner behavior or swallowed errors.

**Fix:** Give each action category its own scoped loading/error pair, or accept an `actionKey` parameter and track state per-key in a `Map`.

---

## ~~TD-2: Fire-and-Forget Email Notifications (No Retry)~~ ✓ Resolved (found already fixed 2026-08-26, `skillars-deferred-69` story creation)

**Source:** Story 3.8 review — Finding 6
**File:** `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/BookingEmailListener.java`

**This was found already resolved during `skillars-deferred-69`'s story creation, by other work — not by that story itself.** `BookingEmailListener`'s `@TransactionalEventListener(AFTER_COMMIT)` handlers publish an `Envelope` event, which `EnvelopeEntity` (`platform.notification.repo`) persists as a durable, `@Version`-ed row (`attempts`, `status`, `deadline`, `retry`, unique `sendId`) — the outbox table this item originally asked for. `MailManager.sendEmailSync` dispatches with a circuit breaker + `RetryTemplate` and records the outcome. `EmailRetryScheduler` (`@Scheduled(fixedDelayString = "${email.retry.interval-ms:60000}")`) polls failed rows with `SELECT ... FOR UPDATE SKIP LOCKED` and retries up to `MAX_RETRY_ATTEMPTS=6` before giving up — the scheduled poller this item asked for. This infrastructure is platform-wide, not booking-specific, and was evidently built well after this tech-debt item was last touched (2026-06-16). No further action needed; do not build a second outbox.

ORIGINAL: All `@TransactionalEventListener(phase = AFTER_COMMIT)` handlers publish to an `Envelope` event, which presumably dispatches to a mail service. There is no retry mechanism if the mail service is temporarily unavailable at the moment of dispatch. A transient outage will permanently drop the notification — there is no outbox, no dead-letter queue, and no re-delivery path.

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

---

## TD-4: Reschedule/Cancel Dialog Submit Buttons Lack Loading State on ParentBookingsPage.vue

**Source:** `skillars-deferred-69` code review — Patch finding "Dialog Submit Button Lacks Loading State" (found for `CoachCommandCenterPage.vue`, fixed there; this file has the identical pre-existing gap, out of scope for that finding)
**File:** `src/frontend/src/pages/parent/ParentBookingsPage.vue`

The reschedule dialog's submit button (`@click="submitReschedule"`, ~line 141) and the cancel dialog's confirm button (`@click="submitCancelBooking"`, ~line 159) carry no `:loading`/`:disable` binding, even though both handlers already track a scoped ref around the call (`reschedulingId`/`cancelingId`, mirroring the pattern used everywhere else on this page). A user can double-click either button and fire duplicate requests before the first response returns.

**Fix:** Add `:loading="reschedulingId === rescheduleBookingId"` / `:disable="..."` to the reschedule submit button, and the equivalent for `cancelingId` on the cancel confirm button — same pattern as the fix applied to `CoachCommandCenterPage.vue`'s propose-new-time dialog.

---

## TD-5: Temporary Trivy Ignore for CVE-2026-14456 (openssl, Alpine base image)

**Source:** PR #107 (`skillars-deferred-69`) CI — two identical build failures ~10 minutes apart, project owner sign-off (2026-08-26) to add a temporary ignore rather than block the PR indefinitely
**File:** `.trivyignore` (new), `.github/workflows/pr-build.yml` (added `trivyignores: .trivyignore` input)

The `Scan image for vulnerabilities` CI step flags `libcrypto3`/`libssl3`/`openssl` `CVE-2026-14456` (HIGH) in the `eclipse-temurin:17-jre-alpine` base image. Trivy's vulnerability DB already lists a fix (`3.5.8-r0`), but Alpine 3.24's own `apk` repo was still only serving `3.5.7-r0` as of 2026-08-26 — the Dockerfile's existing `apk upgrade --no-cache` step (added for exactly this class of issue, see its own comment) can't pull a fix that hasn't been published upstream yet. Added `.trivyignore` (scoped to this one CVE, not a blanket suppression) plus the `trivyignores` input on the Trivy scan step to unblock the PR.

**Fix:** Once Alpine publishes `openssl`/`libssl3`/`libcrypto3` `3.5.8-r0` (or later) for Alpine 3.24, remove the `CVE-2026-14456` line from `.trivyignore`. If `.trivyignore` becomes empty, also remove the `trivyignores: .trivyignore` line from `.github/workflows/pr-build.yml` and delete the file. Check by re-running the PR build's Trivy step on any subsequent PR — a clean scan (0 findings) with the ignore line removed confirms the fix has landed.
