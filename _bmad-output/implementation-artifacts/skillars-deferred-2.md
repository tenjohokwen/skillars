# Story Deferred-2: Email Reliability Hardening

Status: backlog

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

- [ ] **Task 1 — Replace `ShortCode`-based send IDs with UUID-derived long** (AC: 1)
  - [ ] Identify every place a `MailEnvelope` (or equivalent) is created with a `ShortCode.shortenInt(...)` send ID:
    `find src -name "*.java" | xargs grep -n "ShortCode.shortenInt"`
  - [ ] Replace each call with a collision-safe ID. Two options — pick whichever fits `MailManager`'s ID type:
    - **Option A — if send ID is `long`**: `ThreadLocalRandom.current().nextLong()` (64-bit, no birthday collision risk at realistic volumes)
    - **Option B — if send ID is `String`**: `UUID.randomUUID().toString()` directly
    - **Option C — if send ID is `int` (cannot change type)**: `(int)(UUID.randomUUID().getMostSignificantBits() ^ UUID.randomUUID().getLeastSignificantBits())` — still 32 bits but XOR of two independent UUIDs; this is marginally better but the real fix is to widen the type
  - [ ] Read `MailEnvelope.java` and `MailManager.java` to confirm the `sendId` field type before deciding which option applies
  - [ ] **Preferred**: widen the send ID to `long` or `String` in `MailEnvelope` if the type is currently `int` — the collision risk is fundamental to the bit width; a wider type is the correct fix
  - [ ] Ensure the `MailManager` idempotency check (DB unique constraint on `sendId`) is updated if the column type changes

- [ ] **Task 2 — Fix `MailManager.isRetryable()` to unwrap cause** (AC: 2)
  - [ ] Read `MailManager.java` — find the `isRetryable(Exception exception)` method
  - [ ] Update it to unwrap the exception cause before checking the type:
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
  - [ ] Confirm the exact exception class names used in the existing `isRetryable` check — do not remove any currently-correct recoverable checks; only add the non-recoverable guard
  - [ ] Add a unit test for `isRetryable` covering: wrapped `MailParseException` returns false; `MailConnectException` (or equivalent timeout) returns true

- [ ] **Task 3 — Add WARN log and skip-on-blank in email resolvers** (AC: 3)
  - [ ] Find all `resolveEmail(...)` calls (or inline email resolution) in booking and session pack listeners:
    `find src -name "*.java" | xargs grep -n "resolveEmail\|getEmail()"`
    — focus on `BookingEmailListener.java`, `BookingReminderScheduler.java`, `BookingExpiryScheduler.java`, `SessionPackEmailListener.java`
  - [ ] For each resolution site, apply this pattern:
    ```java
    String email = resolveEmail(userId, bookingId);
    if (email == null || email.isBlank()) {
        log.warn("[EMAIL_SKIP] Could not resolve email for userId={} bookingId={} — notification skipped",
                 userId, bookingId);
        return;  // do not queue envelope
    }
    ```
  - [ ] The existing code silently accepts a blank email and passes it to the mail sender, which then fails and may retry. The fix is to short-circuit before queuing

- [ ] **Task 4 — Unit tests for email reliability** (AC: 1, 2, 3)
  - [ ] Add `BookingEmailListenerTest.java` (or extend existing) with:
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

### Debug Log References

### Completion Notes List

### File List

**Modified Files (expected):**
- `src/main/java/com/softropic/skillars/platform/booking/listener/BookingEmailListener.java`
- `src/main/java/com/softropic/skillars/platform/payment/listener/SessionPackEmailListener.java`
- `src/main/java/com/softropic/skillars/platform/booking/scheduler/BookingReminderScheduler.java`
- `src/main/java/com/softropic/skillars/platform/booking/scheduler/BookingExpiryScheduler.java`
- `src/main/java/com/softropic/skillars/infrastructure/mail/MailManager.java`
- *(Flyway migration if send_id column widened)*
