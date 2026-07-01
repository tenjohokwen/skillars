# Story Deferred-2: Email Reliability Hardening

Status: done

## Story

As a platform operator,
I want email delivery to be reliable and idempotency keys to be collision-safe,
so that booking notifications and reminders are never silently dropped, corrupted, or endlessly retried on unrecoverable errors.

## Acceptance Criteria

1. **Given** any booking or session-pack email notification is sent via `MailManager`
   **When** an idempotency key (send ID) is generated for the envelope
   **Then** the key has at least 64 bits of entropy and is effectively collision-free across all emails sent by the platform
   **And** `ShortCode.shortenInt(UUID.randomUUID().hashCode())` (which collapses 128 bits to 32 bits — ~77k birthday collision threshold) is no longer used as a send ID in any `BookingEmailListener`, `SessionPackEmailListener`, or related listener
   **Affected files**: `BookingEmailListener.java`, `SessionPackEmailListener.java`, `BookingReminderScheduler.java` (any caller that builds a `MailEnvelope` with a `ShortCode`-based ID)

2. **Given** `MailManager.isRetryable(exception)` evaluates whether a mail delivery failure should be retried
   **When** the exception is a `MessagingException` wrapping a `MailParseException` (or any other non-recoverable cause)
   **Then** `isRetryable()` unwraps the cause before checking, so non-recoverable exceptions are not retried to exhaustion
   **And** if the exception cause is a `MailParseException`, `AddressException`, or equivalent parse-time error, `isRetryable()` returns `false`

3. **Given** a booking email listener needs to resolve the recipient email address
   **When** the parent or coach user is not found (deleted account, orphaned booking row)
   **Then** `resolveEmail(...)` (or equivalent) logs a `WARN` with the booking ID and the absent user ID rather than silently returning `""` and queuing a delivery that will fail invisibly
   **And** the listener skips sending the email (no envelope queued) when the resolved email is blank

## Tasks / Subtasks

- [x] **Task 1 — Replace `ShortCode`-based send IDs with UUID-derived long** (AC: 1)
  - [x] Identify every place a `MailEnvelope` (or equivalent) is created with a `ShortCode.shortenInt(...)` send ID:
    `find src -name "*.java" | xargs grep -n "ShortCode.shortenInt"`
  - [x] Replace each call with a collision-safe ID. Two options — pick whichever fits `MailManager`'s ID type:
    - **Option A — if send ID is `long`**: `ThreadLocalRandom.current().nextLong()` (64-bit, no birthday collision risk at realistic volumes)
    - **Option B — if send ID is `String`**: `UUID.randomUUID().toString()` directly
    - **Option C — if send ID is `int` (cannot change type)**: `(int)(UUID.randomUUID().getMostSignificantBits() ^ UUID.randomUUID().getLeastSignificantBits())` — still 32 bits but XOR of two independent UUIDs; this is marginally better but the real fix is to widen the type
  - [x] Read `MailEnvelope.java` and `MailManager.java` to confirm the `sendId` field type before deciding which option applies
  - [x] **Preferred**: widen the send ID to `long` or `String` in `MailEnvelope` if the type is currently `int` — the collision risk is fundamental to the bit width; a wider type is the correct fix
  - [x] Ensure the `MailManager` idempotency check (DB unique constraint on `sendId`) is updated if the column type changes

- [x] **Task 2 — Fix `MailManager.isRetryable()` to unwrap cause** (AC: 2)
  - [x] Read `MailManager.java` — find the `isRetryable(Exception exception)` method
  - [x] Update it to unwrap the exception cause before checking the type:
    ```java
    private boolean isRetryable(Exception exception) {
        Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
        // Non-recoverable: parse errors, invalid address, malformed template
        if (cause instanceof MailParseException
                || cause instanceof AddressException
                || cause instanceof jakarta.mail.internet.ParseException) {
            return false;
        }
        // Existing recoverable checks (connection timeout, SMTP 4xx, etc.)
        return cause instanceof MailException
            || cause instanceof MessagingException;
    }
    ```
  - [x] Confirm the exact exception class names used in the existing `isRetryable` check — do not remove any currently-correct recoverable checks; only add the non-recoverable guard
  - [x] Add a unit test for `isRetryable` covering: wrapped `MailParseException` returns false; `MailConnectException` (or equivalent timeout) returns true

- [x] **Task 3 — Add WARN log and skip-on-blank in email resolvers** (AC: 3)
  - [x] Find all `resolveEmail(...)` calls (or inline email resolution) in booking and session pack listeners:
    `find src -name "*.java" | xargs grep -n "resolveEmail\|getEmail()"`
    — focus on `BookingEmailListener.java`, `BookingReminderScheduler.java`, `BookingExpiryScheduler.java`, `SessionPackEmailListener.java`
  - [x] For each resolution site, apply this pattern:
    ```java
    String email = resolveEmail(userId, bookingId);
    if (email == null || email.isBlank()) {
        log.warn("[EMAIL_SKIP] Could not resolve email for userId={} bookingId={} — notification skipped",
                 userId, bookingId);
        return;  // do not queue envelope
    }
    ```
  - [x] The existing code silently accepts a blank email and passes it to the mail sender, which then fails and may retry. The fix is to short-circuit before queuing

- [x] **Task 4 — Unit tests for email reliability** (AC: 1, 2, 3)
  - [x] Add `BookingEmailListenerTest.java` (or extend existing) with:
    - `sendId_hasNoCollisionAcross10kEmails()` — generate 10,000 send IDs and assert no duplicates via `Set`
    - `isRetryable_wrappedParseException_returnsFalse()`
    - `isRetryable_connectionTimeout_returnsTrue()`
    - `resolveEmail_unknownUser_logsWarnAndSkips()` — mock `userRepository.findById()` returning empty; assert listener returns without calling `mailManager.send()`

## Dev Notes

### Why this matters

The `ShortCode.shortenInt(UUID.randomUUID().hashCode())` collision at ~77k emails means a medium-traffic platform will encounter collisions regularly. When two envelopes share a send ID, the `MailManager` unique constraint throws `DataIntegrityViolationException` on the second — the `isRetryable` check incorrectly marks it retryable (it's a `RuntimeException` wrapping the constraint violation), causing endless retries for a structurally impossible delivery. The booking confirmation is never sent and the error floods the log.

### `MailEnvelope` send ID type

Read `MailEnvelope.java` before Task 1. If `sendId` is `int`, the widening fix requires a Flyway migration to change the DB column type. That migration should be added to this story (V76 or next available after V75). If it is already `String` or `long`, no migration is needed — just change the generator call.

### `isRetryable` — do not break existing checks

The existing recoverable checks (SMTP timeout, 4xx rate-limit, transient connection error) must remain. Only add the non-recoverable guard. Read the full `isRetryable` method before editing.

### `resolveEmail` call sites

The blank-email guard in Task 3 is defensive: `MailManager` should ideally reject blank addresses too. Both layers can guard independently — the listener guard is added here; any existing `MailManager` validation is preserved.

### Flyway migration (only if send ID type changes)

If `MailEnvelope.sendId` is `int` (or equivalent narrow DB column), add:
```sql
-- V76__mail_envelope_send_id_widen.sql
ALTER TABLE <schema>.mail_envelopes
    ALTER COLUMN send_id TYPE BIGINT;
```
Confirm table name and schema from existing migrations before writing.

### References — Files to Read Before Implementing

- `MailEnvelope.java` — send ID field type
- `MailManager.java` — `isRetryable()` method and envelope-queuing logic
- `BookingEmailListener.java` — all `ShortCode.shortenInt(...)` usages
- `SessionPackEmailListener.java` — same
- `BookingReminderScheduler.java`, `BookingExpiryScheduler.java` — `resolveEmail` call sites and blank-email handling
- `ShortCode.java` — confirm what `shortenInt` does (bit collapse or something else)

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `mvn compile` — clean after each task's edits
- `mvn -Dtest=MailManagerResilienceTest test` — 5/5 pass
- `mvn -Dtest=BookingReminderSchedulerTest test` — 2/2 pass
- `mvn -Dtest=BookingExpirySchedulerTest test` — 2/2 pass
- `mvn -Dtest=BookingEmailListenerTest test` — 6/6 pass
- `mvn -Dtest=SessionPackEmailListenerTest test` — 1/1 pass
- `mvn test` (full unit suite) — 749 run, 0 failures, 0 errors, 1 skipped (pre-existing, unrelated)

### Completion Notes List

- **AC1**: `Envelope.sendId` and `EnvelopeEntity.sendId` were already `String` (unique column) — no widening or Flyway migration was needed (Option B applied directly). Replaced every `ShortCode.shortenInt(UUID.randomUUID().hashCode())` call in `BookingEmailListener.java` and `SessionPackEmailListener.java` with `UUID.randomUUID().toString()` and removed the now-unused `ShortCode` import from both files. `BookingReminderScheduler.java`/`BookingExpiryScheduler.java` never called `ShortCode` themselves (they resolve emails, not send IDs), so no changes were needed there for AC1. Other `ShortCode.shortenInt` usages in the codebase (tenant/account-change/video-moderation listeners, 2FA/registration "help codes") are out of this story's file scope — the `helpCode` in those flows is a user-facing support code, and changing it to a full UUID would be a UX regression unrelated to this story's ACs, so they were left untouched.
- **AC2**: `isRetryable()` originally checked only the top-level exception. Tracing the actual call path showed `toEnvelopeEntity()` (which sets the persisted `retry` flag consumed by `EmailRetryScheduler`) is invoked with an outer `RuntimeException` that wraps the original `MessagingException`, which in turn can wrap a `MailParseException`/`AddressException` as its own cause (JavaMail's `MessagingException.getCause()` returns the chained "next exception"). A single-level unwrap would miss that second layer, so `isRetryable()` now walks the full cause chain via `ExceptionUtils.getThrowableList(...)` and checks every exception in it against `MailParseException`, `MailPreparationException`, `AddressException`, and `jakarta.mail.internet.ParseException`. Existing recoverable checks are preserved (nothing was removed from the non-repairable list, only added to).
- **AC3**: Added a booking-ID-aware `resolveEmail(userId, bookingId)` overload (was `resolveEmail(userId)`) in both `BookingReminderScheduler` and `BookingExpiryScheduler` that logs a `WARN` with both IDs when the user lookup misses. `BookingEmailListener.onBookingReminder(...)` already filtered blank/null emails before queuing an envelope; `onBookingExpired(...)` did not, so a blank-email guard (log + early return, no envelope published) was added there to match.
- Ran the full `mvn test` unit suite (749 tests) after all changes — 0 failures, 0 errors, confirming no regressions.

### File List

**Modified Files:**
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/BookingEmailListener.java`
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/SessionPackEmailListener.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingReminderScheduler.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingExpiryScheduler.java`
- `src/main/java/com/softropic/skillars/platform/notification/service/MailManager.java`
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/MailManagerResilienceTest.java`

**New Files:**
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingReminderSchedulerTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingExpirySchedulerTest.java`
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/BookingEmailListenerTest.java`
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/SessionPackEmailListenerTest.java`

No Flyway migration was required — `sendId` was already a `String` column.

### Review Findings

- [x] [Review][Patch] Expand AC3's blank-email guard to the remaining unguarded `resolveEmail()` call sites (decision: expand scope now). Applied the WARN-log + skip-on-blank pattern to `BookingService.java` (7 call sites + `resolveEmail`/`resolveCoachEmail` helpers), `RescheduleService.java` (4 call sites + `resolveEmail` helper), and `BookingDuplicationService.java` (1 call site), and added the matching blank-email skip guard to the 9 previously-unguarded `BookingEmailListener` handlers (`onBookingRequested/onBookingConfirmed/onBookingDeclined/onRescheduleRequested/onRescheduleDeclined/onDuplicateBookingProposed/onBookingCancelledByParent/onBookingCancelledByCoach/onCoachNoShow/onPlayerNoShow`). [`BookingService.java`, `RescheduleService.java`, `BookingDuplicationService.java`, `BookingEmailListener.java`]
- [x] [Review][Patch] Bound `MailManager.isRetryable()`'s cause-chain walk (decision: bound the walk). Replaced the unbounded `ExceptionUtils.getThrowableList(...)` walk with an explicit check of the exception, its cause, and its cause-of-cause (the two known call-site depths: 1 level from the retry loop, 2 levels via the `toEnvelopeEntity` outer-catch `RuntimeException` wrapper), so an unrelated non-repairable type buried deeper in some other exception's chain can no longer be misclassified, while still catching AC2's documented double-wrap scenario. [`MailManager.java:135`]
- [x] [Review][Patch] Added `isRetryable_wrappedAddressException_persistsRetryFalse` and `isRetryable_wrappedJakartaParseException_persistsRetryFalse` covering the two exception types that had no direct test coverage. [`MailManagerResilienceTest.java`]

Full `mvn test` suite re-run after all patches: 751 run, 0 failures, 0 errors, 1 skipped (pre-existing, unrelated) — `BUILD SUCCESS`.
- [x] [Review][Defer] `BookingExpiredEvent`/`BookingReminderEvent`/`BookingConfirmedEvent` constructors are invoked positionally with 6-8 raw same-typed arguments across all four new test files — pre-existing lack of a builder on these event classes, not introduced by this diff; a future field reorder could silently miscompile or swap same-typed fields with no test catching it. [`src/main/java/com/softropic/skillars/platform/booking/contract/`] — deferred, pre-existing

## Change Log

- 2026-07-01: Implemented all 4 tasks (collision-safe send IDs, `isRetryable()` cause-chain unwrap, WARN+skip-on-blank email resolution, unit test coverage). Status moved to `review`.
