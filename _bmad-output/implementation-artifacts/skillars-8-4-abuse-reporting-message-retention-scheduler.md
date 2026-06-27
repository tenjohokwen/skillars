# Story 8.4: Abuse Reporting & Message Retention Scheduler

Status: done

## Story

As a platform user,
I want to report abusive messages and as a platform operator I want message data automatically hard-deleted after 24 months,
so that users have a safe reporting mechanism and the platform meets GDPR data minimisation obligations.

## Acceptance Criteria

1. **Given** a user wants to report a message
   **When** `POST /api/messaging/conversations/{conversationId}/messages/{messageId}/report` is called with `{ "reason": "HARASSMENT" | "INAPPROPRIATE_CONTENT" | "SPAM" | "CONTACT_SOLICITATION" | "OTHER" }`
   **Then** `@PreAuthorize` verifies the authenticated user is a party to the conversation — `403` otherwise
   **And** a row is inserted into `message_reports` (id BIGINT PK [TSID], messageId BIGINT FK, reportedBy BIGINT, reason VARCHAR(30), details VARCHAR(500) nullable, status VARCHAR(20) DEFAULT 'OPEN', createdAt TIMESTAMPTZ, resolvedAt TIMESTAMPTZ nullable, resolvedBy BIGINT nullable)
   **And** `messages.moderationStatus` is set to `UNDER_REVIEW` if not already `BLOCKED`
   **And** a `MessageReportedEvent(reportId, messageId, conversationId, reportedBy, reason)` is published
   **And** `201 Created` with the `reportId`
   **And** a user may report the same message only once — duplicate returns `409` with `ErrorDto` code `messaging.alreadyReported`

2. **Given** a user reports a conversation
   **When** `POST /api/messaging/conversations/{conversationId}/report` is called with `{ "reason": "...", "details": "..." }`
   **Then** same party verification applies
   **And** a `conversation_reports` row is inserted (id, conversationId, reportedBy, reason, details, status, createdAt, resolvedAt, resolvedBy)
   **And** `conversations.status` is set to `BLOCKED`
   **And** a `ConversationReportedEvent` is published
   **And** `201 Created` with `reportId`

3. **Given** a conversation is `BLOCKED`
   **When** either party attempts to send a new message
   **Then** `403` with `ErrorDto` code `messaging.conversationBlocked`
   **And** both parties can still READ historical messages

4. **Given** the retention scheduler runs
   **When** `@Scheduled(cron = "0 0 2 * * *")` fires daily at 02:00 UTC
   **Then** `MessageRetentionScheduler` hard-deletes all rows from `messages` where `created_at < now() - INTERVAL '{retentionMonths} months'`
   **And** the corresponding `message_reports` rows cascade-delete automatically (FK ON DELETE CASCADE)
   **And** after message deletion, any `conversations` where `last_message_at < cutoff` AND message count = 0 are also hard-deleted along with their `conversation_reports` (FK ON DELETE CASCADE)
   **And** the count is logged: `Retention run complete: deleted {messageCount} messages, {conversationCount} conversations`
   **And** the 24-month window is read from `ConfigService.getInt("platform.message_retention_months", 24)` — never hardcoded

5. **Given** the retention scheduler encounters a DB error mid-run
   **When** the exception propagates
   **Then** the scheduler catches it, logs at ERROR level with full stack trace, and does NOT rethrow

6. **Given** a sender wants to soft-delete their own message
   **When** `DELETE /api/messaging/conversations/{conversationId}/messages/{messageId}` is called
   **Then** `messages.deletedAt = now()` is set — excluded from GET results
   **And** content is NOT physically removed — the retention scheduler handles physical deletion at 24 months
   **And** only the original sender may soft-delete — `403` on mismatch
   **And** already-deleted messages return `409` with `ErrorDto` code `messaging.alreadyDeleted`
   **And** messages with `moderationStatus` of `UNDER_REVIEW` or `BLOCKED` may NOT be soft-deleted — `403` with `ErrorDto` code `messaging.moderationPending` (preserves evidence during active moderation)

## Tasks / Subtasks

- [x] **Task 1 — Flyway Migration V66** (AC: 1, 2, 4)
  - [x] Create `src/main/resources/db/migration/V66__messaging_reports.sql`:
    ```sql
    CREATE TABLE messaging.message_reports (
        id           BIGINT PRIMARY KEY,
        message_id   BIGINT NOT NULL REFERENCES messaging.messages(id) ON DELETE CASCADE,
        reported_by  BIGINT NOT NULL,
        reason       VARCHAR(30) NOT NULL,
        details      VARCHAR(500),
        status       VARCHAR(20) NOT NULL DEFAULT 'OPEN',
        created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        resolved_at  TIMESTAMPTZ,
        resolved_by  BIGINT,
        CONSTRAINT uq_message_reports_message_reporter UNIQUE (message_id, reported_by)
    );

    CREATE TABLE messaging.conversation_reports (
        id               BIGINT PRIMARY KEY,
        conversation_id  BIGINT NOT NULL REFERENCES messaging.conversations(id) ON DELETE CASCADE,
        reported_by      BIGINT NOT NULL,
        reason           VARCHAR(30) NOT NULL,
        details          VARCHAR(500),
        status           VARCHAR(20) NOT NULL DEFAULT 'OPEN',
        created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        resolved_at      TIMESTAMPTZ,
        resolved_by      BIGINT,
        CONSTRAINT uq_conversation_reports_conv_reporter UNIQUE (conversation_id, reported_by)
    );

    CREATE INDEX idx_message_reports_message_id ON messaging.message_reports(message_id);
    CREATE INDEX idx_conversation_reports_conversation_id ON messaging.conversation_reports(conversation_id);
    ```
  - [x] The `ON DELETE CASCADE` on `message_reports.message_id` means the retention scheduler's `DELETE FROM messages` automatically removes linked reports — no explicit report deletion needed in the scheduler

- [x] **Task 2 — New Enums** (AC: 1, 2)
  - [x] Create `platform/messaging/contract/MessageReportReason.java`:
    ```java
    public enum MessageReportReason {
        HARASSMENT, INAPPROPRIATE_CONTENT, SPAM, CONTACT_SOLICITATION, OTHER
    }
    ```
  - [x] Create `platform/messaging/contract/ReportStatus.java`:
    ```java
    public enum ReportStatus {
        OPEN, UNDER_REVIEW, RESOLVED, DISMISSED
    }
    ```

- [x] **Task 3 — JPA Entities** (AC: 1, 2)
  - [x] Create `platform/messaging/repo/MessageReport.java`:
    - Extends `BaseEntity` (`@Tsid` Long id — same as `Message` and `Conversation`)
    - `@Table(name = "message_reports", schema = "messaging")`
    - Fields: `messageId` (Long), `reportedBy` (Long), `reason` (MessageReportReason — `@Enumerated(STRING)`), `details` (String nullable), `status` (ReportStatus — `@Enumerated(STRING)`, default `OPEN`), `createdAt` (Instant), `resolvedAt` (Instant nullable), `resolvedBy` (Long nullable)
    - `@Getter @Setter @NoArgsConstructor`
  - [x] Create `platform/messaging/repo/ConversationReport.java`:
    - Same structure with `conversationId` (Long) instead of `messageId`
    - `@Table(name = "conversation_reports", schema = "messaging")`

- [x] **Task 4 — Repositories** (AC: 1, 2, 4)
  - [x] Create `platform/messaging/repo/MessageReportRepository.java`:
    ```java
    public interface MessageReportRepository extends JpaRepository<MessageReport, Long> {
        boolean existsByMessageIdAndReportedBy(Long messageId, Long reportedBy);
    }
    ```
  - [x] Create `platform/messaging/repo/ConversationReportRepository.java`:
    ```java
    public interface ConversationReportRepository extends JpaRepository<ConversationReport, Long> {
        boolean existsByConversationIdAndReportedBy(Long conversationId, Long reportedBy);
    }
    ```
  - [x] Add bulk-delete method to `MessageRepository` for the scheduler:
    ```java
    @Modifying
    @Query(value = "DELETE FROM messaging.messages WHERE created_at < :cutoff", nativeQuery = true)
    int deleteExpiredMessages(@Param("cutoff") Instant cutoff);
    ```
  - [x] Add orphan-conversation cleanup to `ConversationRepository` for the scheduler:
    ```java
    @Modifying
    @Query(value = """
        DELETE FROM messaging.conversations
        WHERE last_message_at < :cutoff
          AND NOT EXISTS (SELECT 1 FROM messaging.messages m WHERE m.conversation_id = conversations.id)
        """, nativeQuery = true)
    int deleteOrphanConversations(@Param("cutoff") Instant cutoff);
    ```

- [x] **Task 5 — Contract Events** (AC: 1, 2)
  - [x] Create `platform/messaging/contract/MessageReportedEvent.java`:
    ```java
    public record MessageReportedEvent(
        Long reportId, Long messageId, Long conversationId,
        Long reportedBy, String reason) {}
    ```
  - [x] Create `platform/messaging/contract/ConversationReportedEvent.java`:
    ```java
    public record ConversationReportedEvent(
        Long reportId, Long conversationId, Long reportedBy, String reason) {}
    ```

- [x] **Task 6 — New DTO Records** (AC: 1, 2, 6)
  - [x] Create `platform/messaging/contract/ReportRequest.java`:
    ```java
    public record ReportRequest(
        @NotNull MessageReportReason reason,
        @Size(max = 500) String details) {}
    ```
  - [x] Create `platform/messaging/contract/ReportResponse.java`:
    ```java
    public record ReportResponse(Long reportId) {}
    ```

- [x] **Task 7 — Update `MessagingErrorCode`** (AC: 1, 2, 3, 6)
  - [x] Add four new codes to `MessagingErrorCode.java`:
    - `CONVERSATION_BLOCKED("messaging.conversationBlocked")`
    - `ALREADY_REPORTED("messaging.alreadyReported")`
    - `ALREADY_DELETED("messaging.alreadyDeleted")`
    - `MODERATION_PENDING("messaging.moderationPending")`

- [x] **Task 8 — Update `MessagingApiAdvice`** (AC: 1, 2, 3, 6)
  - [x] Update the `handleOperationNotAllowed()` method to also check for 409 codes:
    ```java
    HttpStatus status;
    if (MessagingErrorCode.INVALID_CONTENT.getErrorCode().equals(code)) {
        status = HttpStatus.BAD_REQUEST;
    } else if (MessagingErrorCode.ALREADY_REPORTED.getErrorCode().equals(code)
            || MessagingErrorCode.ALREADY_DELETED.getErrorCode().equals(code)) {
        status = HttpStatus.CONFLICT;
    } else {
        status = HttpStatus.FORBIDDEN;
    }
    ```
  - [x] Also add `@ExceptionHandler` for `201 Created` responses — NOTE: `MessagingResource` handles `201` by returning `ResponseEntity.status(HttpStatus.CREATED).body(...)` directly, so no advice change is needed for 201

- [x] **Task 9 — `MessagingReportService`** (AC: 1, 2, 3)
  - [x] Create `platform/messaging/service/MessagingReportService.java` with `@Service @RequiredArgsConstructor @Slf4j @Transactional`
  - [x] Inject: `ConversationRepository`, `MessageRepository`, `MessageReportRepository`, `ConversationReportRepository`, `ApplicationEventPublisher`
  - [x] Implement `reportMessage(Long conversationId, Long messageId, Long reporterUserId, String role, MessageReportReason reason, String details)`:
    1. Load `conv` — throw `ResourceNotFoundException` if missing
    2. Call `verifyIsPartyInternal(conv, reporterUserId, role)` — reuse same logic as `MessagingService.verifyIsParty()` (see note below)
    3. Load `message` — throw `ResourceNotFoundException` if missing; verify `message.getConversationId().equals(conversationId)` — `403` if not
    4. Check `messageReportRepository.existsByMessageIdAndReportedBy(messageId, reporterUserId)` — throw `OperationNotAllowedException(..., MessagingErrorCode.ALREADY_REPORTED)` if true
    5. Set `message.moderationStatus = UNDER_REVIEW` if current status is not `BLOCKED`; save message
    6. Create and save `MessageReport`: set `messageId`, `reportedBy = reporterUserId`, `reason` (already typed as `MessageReportReason` — no `valueOf()` needed), `details`, `status = OPEN`, `createdAt = Instant.now()` — wrap `messageReportRepository.save(report)` in a try/catch for `DataIntegrityViolationException` and rethrow as `OperationNotAllowedException(..., MessagingErrorCode.ALREADY_REPORTED)` to handle concurrent duplicate submissions cleanly
    7. Publish `new MessageReportedEvent(report.getId(), messageId, conversationId, reporterUserId, reason.name())`
    8. Return `new ReportResponse(report.getId())`
  - [x] Implement `reportConversation(Long conversationId, Long reporterUserId, String role, MessageReportReason reason, String details)`:
    1. Load `conv` — throw `ResourceNotFoundException` if missing
    2. Verify party (same logic)
    3. Check `conversationReportRepository.existsByConversationIdAndReportedBy(conversationId, reporterUserId)` — `409` if duplicate
    4. Set `conv.status = BLOCKED`; save conv
    5. Create and save `ConversationReport`
    6. Publish `new ConversationReportedEvent(report.getId(), conversationId, reporterUserId, reason)`
    7. Return `new ReportResponse(report.getId())`

- [x] **Task 10 — Update `MessagingService`** (AC: 3, 6)
  - [x] In `sendMessage()`, after `verifyIsParty(conv, senderUserId, role)` and before the age policy check, add:
    ```java
    if (conv.getStatus() == ConversationStatus.BLOCKED) {
        throw new OperationNotAllowedException(
            "Conversation is blocked — no new messages can be sent",
            MessagingErrorCode.CONVERSATION_BLOCKED);
    }
    ```
  - [x] Add new `@Transactional` method `softDeleteMessage(Long conversationId, Long messageId, Long callerUserId)`:
    ```java
    public void softDeleteMessage(Long conversationId, Long messageId, Long callerUserId) {
        Message message = messageRepository.findById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException("Message not found", "message"));
        if (!message.getConversationId().equals(conversationId)) {
            throw new OperationNotAllowedException("Message does not belong to this conversation",
                MessagingErrorCode.NOT_A_PARTY);
        }
        if (!message.getSenderId().equals(callerUserId)) {
            throw new OperationNotAllowedException("Only the original sender may delete this message",
                MessagingErrorCode.NOT_A_PARTY);
        }
        if (message.getModerationStatus() == MessageModerationStatus.UNDER_REVIEW
                || message.getModerationStatus() == MessageModerationStatus.BLOCKED) {
            throw new OperationNotAllowedException(
                "Message under active moderation cannot be deleted",
                MessagingErrorCode.MODERATION_PENDING);
        }
        if (message.getDeletedAt() != null) {
            throw new OperationNotAllowedException("Message is already deleted",
                MessagingErrorCode.ALREADY_DELETED);
        }
        message.setDeletedAt(Instant.now());
        messageRepository.save(message);
    }
    ```
  - [x] **IMPORTANT**: The party verification in `MessagingReportService` must duplicate the `verifyIsParty()` logic from `MessagingService` (same circular-dependency avoidance as `ModerationResultApplier.resolveRecipient()` duplicates `MessagingService.resolveRecipient()`). Do NOT inject `MessagingService` into `MessagingReportService` — this creates a circular dependency. Copy the four-line party check verbatim.

- [x] **Task 11 — `MessageRetentionScheduler`** (AC: 4, 5)
  - [x] Create `platform/messaging/service/MessageRetentionScheduler.java`:
    - `@Component @RequiredArgsConstructor @Slf4j`
    - Inject: `MessageRepository`, `ConversationRepository`, `ConfigService`, `TransactionTemplate`
    - Method annotated `@Scheduled(cron = "0 0 2 * * *")` only — **no `@Transactional`** (each delete step manages its own transaction via `TransactionTemplate`):
    ```java
    @Scheduled(cron = "0 0 2 * * *")
    public void runRetention() {
        int retentionMonths = configService.getInt("platform.message_retention_months", 24);
        Instant cutoff = Instant.now().atZone(ZoneOffset.UTC).minusMonths(retentionMonths).toInstant();

        int messageCount;
        try {
            messageCount = transactionTemplate.execute(status ->
                messageRepository.deleteExpiredMessages(cutoff));
        } catch (Exception e) {
            log.error("Retention scheduler: message deletion failed", e);
            return;
        }

        int conversationCount = 0;
        try {
            conversationCount = transactionTemplate.execute(status ->
                conversationRepository.deleteOrphanConversations(cutoff));
        } catch (Exception e) {
            log.error("Retention scheduler: conversation cleanup failed", e);
        }

        log.info("Retention run complete: deleted {} messages, {} conversations",
            messageCount, conversationCount);
    }
    ```
  - [x] **Config key** already seeded in `V20__platform_config.sql` as `(37, 'platform.message_retention_months', '24', 'LONG', 'Message retention period in months')` — no new Flyway migration needed for this
  - [x] **Cascade correctness**: `message_reports.message_id` has `ON DELETE CASCADE` so deleting expired messages also cleans up reports. `conversation_reports.conversation_id` has `ON DELETE CASCADE` so deleting orphan conversations also cleans up their reports. No explicit report deletion needed.
  - [x] **Instant calculation**: Use `Instant.now().atZone(ZoneOffset.UTC).minusMonths(retentionMonths).toInstant()` for exact calendar-month arithmetic. Add import `java.time.ZoneOffset`. Do NOT use `retentionMonths * 30L` days — 24 × 30 = 720 days, but 24 calendar months is 730–731 days.

- [x] **Task 12 — Update `MessagingResource`** (AC: 1, 2, 6)
  - [x] Inject `MessagingReportService` into `MessagingResource`
  - [x] Add `DELETE /api/messaging/conversations/{conversationId}/messages/{messageId}`:
    ```java
    @DeleteMapping("/conversations/{conversationId}/messages/{messageId}")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    @Observed(name = "messaging.softDeleteMessage")
    public ResponseEntity<Void> softDeleteMessage(
            @PathVariable Long conversationId,
            @PathVariable Long messageId,
            Authentication auth) {
        Long userId = resolveUserId();
        messagingService.softDeleteMessage(conversationId, messageId, userId);
        return ResponseEntity.noContent().build();
    }
    ```
  - [x] Add `POST /api/messaging/conversations/{conversationId}/messages/{messageId}/report`:
    ```java
    @PostMapping("/conversations/{conversationId}/messages/{messageId}/report")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    @Observed(name = "messaging.reportMessage")
    public ResponseEntity<ReportResponse> reportMessage(
            @PathVariable Long conversationId,
            @PathVariable Long messageId,
            @Valid @RequestBody ReportRequest request,
            @RequestParam(name = "role", required = false) String roleHint,
            Authentication auth) {
        Long userId = resolveUserId();
        String role = resolveRole(auth, roleHint);
        ReportResponse result = messagingReportService.reportMessage(
            conversationId, messageId, userId, role, request.reason(), request.details());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }
    ```
  - [x] Add `POST /api/messaging/conversations/{conversationId}/report`:
    ```java
    @PostMapping("/conversations/{conversationId}/report")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    @Observed(name = "messaging.reportConversation")
    public ResponseEntity<ReportResponse> reportConversation(
            @PathVariable Long conversationId,
            @Valid @RequestBody ReportRequest request,
            @RequestParam(name = "role", required = false) String roleHint,
            Authentication auth) {
        Long userId = resolveUserId();
        String role = resolveRole(auth, roleHint);
        ReportResponse result = messagingReportService.reportConversation(
            conversationId, userId, role, request.reason(), request.details());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }
    ```
  - [x] Add `@Valid` import; `jakarta.validation.Valid` is already on classpath

- [x] **Task 13 — Tests** (AC: 1, 2, 3, 4, 5, 6)
  - [x] **`AbuseReportIT.java`** (`@SpringBootTest @Testcontainers`, `platform/messaging/api/`):
    - Use ID range `9840_xxx_xxx_xxx` (9840 range is free; ranges 9700/9800/9810/9820/9830 are taken by prior messaging ITs)
    - `@MockitoBean GeminiClient geminiClient` stubbed to `ModerationVerdict.SAFE` (same as other messaging ITs after Story 8.3)
    - Setup: coach + player + parent (same pattern as `ConversationResourceIT`)
    - `reportMessage_validParty_returns201WithReportId()`: coach reports a message → 201, verify `message_reports` row in DB via JdbcTemplate, verify `messages.moderation_status = 'UNDER_REVIEW'`
    - `reportMessage_duplicate_returns409()`: same coach reports same message again → 409, body code `messaging.alreadyReported`
    - `reportConversation_blocksConversation_returns201()`: report conversation → 201, verify `conversations.status = 'BLOCKED'`, verify `conversation_reports` row
    - `sendMessage_blockedConversation_returns403WithCode()`: after conversation report, attempt to send → 403 with code `messaging.conversationBlocked`
    - `readMessages_blockedConversation_allowed()`: GET messages on blocked conversation → 200 (reading is still allowed)
    - `reportMessage_nonParty_returns403()`: set up a third authenticated user not involved in the conversation (e.g. a second coach); call report endpoint authenticated as that user → 403 with code `messaging.notAParty`

  - [x] **`SoftDeleteIT.java`** (`@SpringBootTest @Testcontainers`, `platform/messaging/api/`):
    - Use ID range `9850_xxx_xxx_xxx`
    - `@MockitoBean GeminiClient geminiClient` stubbed to `SAFE`
    - `softDeleteMessage_bySender_returns204()`: sender deletes their own message → 204; verify `messages.deleted_at IS NOT NULL` in DB; verify message absent from `GET /messages` list
    - `softDeleteMessage_byNonSender_returns403()`: other party tries to delete → 403
    - `softDeleteMessage_alreadyDeleted_returns409()`: delete twice → 409 with code `messaging.alreadyDeleted`
    - `softDeleteMessage_underReview_returns403()`: send a message, call the report endpoint as the other party (sets `moderation_status = UNDER_REVIEW`), then attempt soft-delete as the original sender → 403 with code `messaging.moderationPending`

  - [x] **`RetentionSchedulerTest.java`** (unit, `platform/messaging/service/`):
    - `@ExtendWith(MockitoExtension.class)`, `@Mock MessageRepository messageRepository`, `@Mock ConversationRepository conversationRepository`, `@Mock ConfigService configService` — **no `@InjectMocks`**; construct manually with a real `TransactionTemplate` that executes callbacks immediately (same pattern as `VideoLifecycleSchedulerTest`):
      ```java
      MessageRetentionScheduler scheduler;

      @BeforeEach void setUp() {
          TransactionTemplate txTemplate = new TransactionTemplate() {
              @Override public <T> T execute(TransactionCallback<T> action) {
                  return action.doInTransaction(null);
              }
          };
          scheduler = new MessageRetentionScheduler(messageRepository, conversationRepository, configService, txTemplate);
          when(configService.getInt("platform.message_retention_months", 24)).thenReturn(24);
      }
      ```
    - `runRetention_deletesExpiredMessagesAndOrphanConversations()`:
      - Stub `messageRepository.deleteExpiredMessages(any())` → `5`
      - Stub `conversationRepository.deleteOrphanConversations(any())` → `2`
      - Call `scheduler.runRetention()`
      - Verify `messageRepository.deleteExpiredMessages(argThat(cutoff -> cutoff.isBefore(Instant.now().minus(700, ChronoUnit.DAYS))))` called
      - Verify `conversationRepository.deleteOrphanConversations(any())` called
    - `runRetention_dbErrorCaught_noRethrow()`:
      - Stub `messageRepository.deleteExpiredMessages(any())` → throw `RuntimeException("DB error")`
      - Call `scheduler.runRetention()` — must NOT throw
      - Verify `conversationRepository.deleteOrphanConversations(any())` NOT called (scheduler returns early after step 1 failure)
    - `runRetention_usesConfiguredRetentionPeriod()`:
      - `when(configService.getInt(..., 24)).thenReturn(12)` (12-month override)
      - Call scheduler; capture cutoff arg passed to `deleteExpiredMessages`; verify it is approximately 365 days before now (12 exact calendar months, within a few seconds tolerance)

## Dev Notes

### CRITICAL: `MessagingReportService` Must Copy Party Verification — Not Import `MessagingService`

`MessagingReportService` needs the same party-verification logic as `MessagingService.verifyIsParty()`. Do NOT inject `MessagingService` into `MessagingReportService` — `MessagingService` already injects `ModerationService` and others; adding a cross-dependency here risks a circular bean graph. Copy the 10-line `verifyIsParty()` implementation verbatim into `MessagingReportService`. This is the same intentional-duplication pattern used for `ModerationResultApplier.resolveRecipient()` in Story 8.3. Document it with a brief comment matching the style in `ModerationResultApplier`.

### CRITICAL: `BLOCKED` Conversation Check Placement in `sendMessage()`

The `BLOCKED` check must be added AFTER `verifyIsParty()`, not before. Placing it before the party check reveals the conversation's blocked status to authenticated users who are not parties — a non-party should only ever receive `messaging.notAParty`, never `messaging.conversationBlocked`. Party membership is always the first gate:

```java
// in sendMessage(), after verifyIsParty():
var conv = conversationRepository.findById(conversationId).orElseThrow(...);
verifyIsParty(conv, senderUserId, role);
if (conv.getStatus() == ConversationStatus.BLOCKED) {
    throw new OperationNotAllowedException(..., MessagingErrorCode.CONVERSATION_BLOCKED);
}
```

### CRITICAL: FK CASCADE Order in Retention Scheduler

The retention scheduler does:
1. `messageRepository.deleteExpiredMessages(cutoff)` — cascades to `message_reports` via FK
2. `conversationRepository.deleteOrphanConversations(cutoff)` — cascades to `conversation_reports` via FK

Step 2 must run AFTER step 1 because: after step 1, conversations that had messages before the cutoff now have `message count = 0`, so the orphan query can clean them up. This order is not a race — both run in the same `@Transactional` method.

**Do NOT** reverse the order (conversations before messages): `conversation_reports` cascade would fire correctly, but then deleting messages would violate `messages.conversation_id FK` if the conversation was already deleted. Keep messages-first.

### CRITICAL: Retention `Instant` Calculation — Use Exact Month Arithmetic

`Instant` does not support month arithmetic directly. Use `ZoneOffset.UTC` for exact calendar-month calculation — this matches the "24 months" AC precisely:

```java
Instant cutoff = Instant.now().atZone(ZoneOffset.UTC).minusMonths(retentionMonths).toInstant();
```

Add import `java.time.ZoneOffset`. Do NOT use `retentionMonths * 30L` days — 24 × 30 = 720 days, but 24 calendar months is 730–731 days depending on leap years.

### CRITICAL: Retention Scheduler Uses `TransactionTemplate`, Not `@Transactional`

Do NOT annotate `runRetention()` with `@Transactional`. If `deleteExpiredMessages` hits a DB error, PostgreSQL puts the connection-level transaction in an aborted state — any subsequent SQL on the same connection fails with "current transaction is aborted, commands ignored until end of transaction block." Wrapping both deletes in a single `@Transactional + try/catch` means step 2 also fails silently in the broken connection, and the `catch` swallows both errors.

Use `TransactionTemplate` per step instead (the same pattern as `VideoLifecycleScheduler`): each step gets its own short-lived transaction. If step 1 fails, the exception is caught, logged, and the method returns early — step 2 is never attempted in a broken context. `TransactionTemplate` is already a Spring bean; inject it via `@RequiredArgsConstructor`.

### CRITICAL: `MessagingApiAdvice` 409 Mapping — Do NOT Create a New Exception Class

The project pattern for module-specific conflict errors uses the existing `OperationNotAllowedException` with module-specific error codes, then maps to the right HTTP status in the module's advice. The video module uses dedicated exception classes (`VideoAlreadyResolvedException`) but messaging already centralizes all exceptions through `OperationNotAllowedException`. Extend the existing advice method rather than adding a new class to avoid proliferating the exception hierarchy for two cases.

The updated `MessagingApiAdvice.handleOperationNotAllowed()` returns 409 when the code matches `ALREADY_REPORTED` or `ALREADY_DELETED`, and 403 otherwise (except `INVALID_CONTENT` → 400). This pattern is consistent and doesn't require a new exception class.

### CRITICAL: `CoachProfileRepository` Needed in `MessagingReportService` for Party Check

`verifyIsParty()` in `MessagingService` injects `CoachProfileRepository` to resolve the coach's profile UUID from the userId for coach party verification. `MessagingReportService` must also inject `CoachProfileRepository` for the same reason. Check the copied `verifyIsParty()` logic:
```java
case "COACH" -> {
    var coach = coachProfileRepository.findByUserId(callerUserId);
    yield coach.map(c -> Objects.equals(c.getId(), conv.getCoachId())).orElse(false);
}
```

### CRITICAL: `message_reports.reason` is VARCHAR(30) — Validate Enum Mapping

The `MessageReportReason` enum values: `HARASSMENT`(10), `INAPPROPRIATE_CONTENT`(20), `SPAM`(4), `CONTACT_SOLICITATION`(19), `OTHER`(5). Longest is `INAPPROPRIATE_CONTENT` at 20 chars. VARCHAR(30) is safe. `@Enumerated(EnumType.STRING)` on `MessageReport.reason` handles the mapping automatically.

### Pattern: `MessagingReportService` Party Verification

Copy this verbatim from `MessagingService.verifyIsParty()` with a short comment:
```java
// Duplicates MessagingService.verifyIsParty() — injecting MessagingService would create a circular dep
private void verifyIsParty(Conversation conv, Long callerUserId, String role) {
    boolean isParty = switch (role) {
        case "COACH" -> {
            var coach = coachProfileRepository.findByUserId(callerUserId);
            yield coach.map(c -> Objects.equals(c.getId(), conv.getCoachId())).orElse(false);
        }
        case "PARENT" -> Objects.equals(conv.getParentId(), callerUserId);
        default -> Objects.equals(conv.getPlayerId(), callerUserId);
    };
    if (!isParty) {
        throw new OperationNotAllowedException(
            "Caller is not a party to this conversation",
            MessagingErrorCode.NOT_A_PARTY);
    }
}
```

### Scheduler Registration — `@EnableScheduling` Already Active

`@EnableScheduling` is present on the main application class or a configuration class from prior stories (the booking and video schedulers already work). Do NOT add it again. `MessageRetentionScheduler` is `@Component` — Spring will discover and register it automatically.

### Test Setup: `@MockitoBean GeminiClient` Required in All New Messaging ITs

Since Story 8.3, `GeminiModerationService` is `@Primary` and wires a real `GeminiClient`. All `@SpringBootTest` integration tests in `platform.messaging` MUST add:
```java
@MockitoBean GeminiClient geminiClient;

@BeforeEach void setUp() {
    when(geminiClient.evaluate(any())).thenReturn(ModerationVerdict.SAFE);
}
```
Without this, tests will try to call the real Gemini API and fail at context startup (fail-fast from `GeminiConfig` when `GEMINI_API_KEY` is blank).

### Test ID Range for New ITs

Previously used ranges (from Story 8.3 notes):
- `9700` → `ConversationResourceIT`
- `9800` → `MessagingAccessControlIT`
- `9810` → `ParentalOversightResourceIT`
- `9820` → `ModerationFailClosedIT`
- `9830` → `BlockedMessageContentHidingIT`

Use:
- `9840_xxx_xxx_xxx` → `AbuseReportIT`
- `9850_xxx_xxx_xxx` → `SoftDeleteIT`

### Module Package Structure

```
platform/messaging/contract/
  + MessageReportReason.java          (enum — NEW)
  + ReportStatus.java                 (enum — NEW)
  + ReportRequest.java                (record — NEW)
  + ReportResponse.java               (record — NEW)
  + MessageReportedEvent.java         (record — NEW)
  + ConversationReportedEvent.java    (record — NEW)
  ~ MessagingErrorCode.java           (add CONVERSATION_BLOCKED, ALREADY_REPORTED, ALREADY_DELETED, MODERATION_PENDING)

platform/messaging/repo/
  + MessageReport.java                (entity — NEW)
  + ConversationReport.java           (entity — NEW)
  + MessageReportRepository.java      (JPA repo — NEW)
  + ConversationReportRepository.java (JPA repo — NEW)
  ~ MessageRepository.java            (add deleteExpiredMessages @Modifying query)
  ~ ConversationRepository.java       (add deleteOrphanConversations @Modifying query)

platform/messaging/service/
  + MessagingReportService.java       (@Service — NEW)
  + MessageRetentionScheduler.java    (@Component scheduler — NEW)
  ~ MessagingService.java             (add BLOCKED check in sendMessage, add softDeleteMessage)

platform/messaging/api/
  ~ MessagingResource.java            (add 3 new endpoints)
  ~ MessagingApiAdvice.java           (extend 409 handling)

src/main/resources/db/migration/
  + V66__messaging_reports.sql        (NEW — creates message_reports + conversation_reports)

src/test/java/.../platform/messaging/api/
  + AbuseReportIT.java                (NEW)
  + SoftDeleteIT.java                 (NEW)
src/test/java/.../platform/messaging/service/
  + RetentionSchedulerTest.java       (unit — NEW)
```

### References

- `MessagingService.java` — `platform/messaging/service/MessagingService.java` (party verification logic to duplicate; add BLOCKED check; add `softDeleteMessage()`)
- `MessagingResource.java` — `platform/messaging/api/MessagingResource.java` (add 3 endpoints; `resolveUserId()` and `resolveRole()` are private helpers already present)
- `MessagingApiAdvice.java` — `platform/messaging/api/MessagingApiAdvice.java` (extend to return 409)
- `MessagingErrorCode.java` — `platform/messaging/contract/MessagingErrorCode.java` (add 3 new codes)
- `ConversationStatus.java` — `platform/messaging/contract/ConversationStatus.java` (BLOCKED status already defined)
- `Conversation.java` — `platform/messaging/repo/Conversation.java` (has `status` field — set to BLOCKED on report)
- `Message.java` — `platform/messaging/repo/Message.java` (has `deletedAt` field — set on soft-delete)
- `BaseEntity.java` — `infrastructure/persistence/BaseEntity.java` (`@Tsid` Long id — entities extend this)
- `ConfigService.java` — `platform/config/service/ConfigService.java` (`getInt(key, defaultValue)` method — use for retention months)
- `V20__platform_config.sql` — confirms `platform.message_retention_months = '24'` already seeded (row 37)
- `V65__messaging_module_init.sql` — confirms `messaging` schema, `conversations` and `messages` tables structure
- `VideoLifecycleSchedulerTest.java` — `test/platform/video/service/VideoLifecycleSchedulerTest.java` (scheduler unit test pattern with anonymous `TransactionTemplate`)
- `ModerationResultApplier.java` — `platform/messaging/service/ModerationResultApplier.java` (intentional duplication pattern precedent)
- `ConversationResourceIT.java` — `test/platform/messaging/api/ConversationResourceIT.java` (integration test setup pattern with role setup, JdbcTemplate, `@MockitoBean GeminiClient`)

### Epic 10 Wiring Note

`MessageReportedEvent` and `ConversationReportedEvent` are published in this story but consumed by the `platform.admin` module in Epic 10 (`Story 10.1`). The events should be published via Spring `ApplicationEventPublisher` — synchronous `@EventListener` in the admin module. No async infrastructure is needed here; the event payloads contain all data the admin module needs to create `admin_alerts` rows.

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

- Implemented all 13 tasks covering AC 1–6 in full.
- `V66__messaging_reports.sql` creates `message_reports` and `conversation_reports` tables with FK ON DELETE CASCADE constraints for automatic report cleanup during retention runs.
- `MessagingReportService` copies `verifyIsParty()` verbatim from `MessagingService` (same intentional-duplication pattern as `ModerationResultApplier`) to avoid circular bean dependency.
- `MessagingService.sendMessage()` now checks `ConversationStatus.BLOCKED` AFTER `verifyIsParty()` — non-parties always get `messaging.notAParty`, never `messaging.conversationBlocked`.
- `MessageRetentionScheduler` uses `TransactionTemplate` (not `@Transactional`) to isolate message and conversation delete steps — a DB failure in step 1 logs, returns early, and never corrupts step 2's transaction context.
- Cutoff uses `ZoneOffset.UTC` + `minusMonths()` for exact calendar-month arithmetic, not day multiplication.
- `MessagingApiAdvice` now maps `ALREADY_REPORTED` and `ALREADY_DELETED` to HTTP 409; all other `OperationNotAllowedException` codes remain 403 (except `INVALID_CONTENT` → 400).
- All 728 tests pass (0 failures, 0 errors). The ERROR log in `RetentionSchedulerTest` is intentional from the no-rethrow error path test.

### File List

- `src/main/resources/db/migration/V66__messaging_reports.sql` (NEW)
- `src/main/java/com/softropic/skillars/platform/messaging/contract/MessageReportReason.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/messaging/contract/ReportStatus.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/messaging/contract/ReportRequest.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/messaging/contract/ReportResponse.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/messaging/contract/MessageReportedEvent.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/messaging/contract/ConversationReportedEvent.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/messaging/contract/MessagingErrorCode.java` (MODIFIED — added CONVERSATION_BLOCKED, ALREADY_REPORTED, ALREADY_DELETED, MODERATION_PENDING)
- `src/main/java/com/softropic/skillars/platform/messaging/repo/MessageReport.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/messaging/repo/ConversationReport.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/messaging/repo/MessageReportRepository.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/messaging/repo/ConversationReportRepository.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/messaging/repo/MessageRepository.java` (MODIFIED — added deleteExpiredMessages)
- `src/main/java/com/softropic/skillars/platform/messaging/repo/ConversationRepository.java` (MODIFIED — added deleteOrphanConversations)
- `src/main/java/com/softropic/skillars/platform/messaging/service/MessagingReportService.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/messaging/service/MessageRetentionScheduler.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/messaging/service/MessagingService.java` (MODIFIED — added BLOCKED check in sendMessage, added softDeleteMessage)
- `src/main/java/com/softropic/skillars/platform/messaging/api/MessagingResource.java` (MODIFIED — added 3 new endpoints, injected MessagingReportService)
- `src/main/java/com/softropic/skillars/platform/messaging/api/MessagingApiAdvice.java` (MODIFIED — extended 409 handling)
- `src/test/java/com/softropic/skillars/platform/messaging/api/AbuseReportIT.java` (NEW)
- `src/test/java/com/softropic/skillars/platform/messaging/api/SoftDeleteIT.java` (NEW)
- `src/test/java/com/softropic/skillars/platform/messaging/service/RetentionSchedulerTest.java` (NEW)

### Review Findings

- [x] [Review][Decision] MODERATION_PENDING guard in `softDeleteMessage` — resolved: guard kept, AC6 updated to document that messages under UNDER_REVIEW or BLOCKED moderation status return 403 with `messaging.moderationPending`.
- [x] [Review][Patch] `sendMessage()` BLOCKED check uses stale `conv` — fixed: added re-check of `c.getStatus() == BLOCKED` inside `transactionTemplate.execute()` after fresh conversation load [`MessagingService.java:157`]
- [x] [Review][Patch] `RetentionSchedulerTest` leap-year flaky — fixed: replaced 365-day approximation with month-based bounds (`isAfter(now - 13mo).isBefore(now - 11mo)`) [`RetentionSchedulerTest.java:81`]
- [x] [Review][Defer] CASCADE deletes open abuse reports during retention [`V66__messaging_reports.sql:3`, `MessageRepository.java:33`] — The bare `DELETE FROM messaging.messages WHERE created_at < :cutoff` cascades to `message_reports` via FK, permanently destroying any OPEN or UNDER_REVIEW report records before admin can act. Spec explicitly specifies CASCADE (AC4). Design decision for Epic 10: admin module should archive or block retention for messages with open reports — deferred, pre-existing spec decision.
- [x] [Review][Defer] `deleteOrphanConversations` skips conversations with NULL `last_message_at` [`ConversationRepository.java:31`] — Conversations created but abandoned before any message was sent have `last_message_at IS NULL`; `NULL < :cutoff` evaluates to NULL in SQL so they are never purged. Low-severity accumulation risk — deferred, pre-existing.
- [x] [Review][Defer] Redundant explicit indexes in V66 migration [`V66__messaging_reports.sql:27-28`] — `idx_message_reports_message_id` and `idx_conversation_reports_conversation_id` are redundant: the unique constraints `(message_id, reported_by)` and `(conversation_id, reported_by)` already provide B-tree indexes with those columns as the leftmost key — deferred, pre-existing.
- [x] [Review][Defer] `softDeleteMessage` ALREADY_DELETED race window [`MessagingService.java:263`] — Two concurrent soft-deletes by the same user can both pass the `deletedAt == null` guard at READ_COMMITTED, causing the second to silently succeed instead of returning 409. Requires `@Version` on Message for optimistic locking — deferred, out of scope.
- [x] [Review][Defer] `@PreAuthorize(IS_AUTHENTICATED)` used on report endpoints instead of party-check annotation [`MessagingResource.java:140,151,168`] — AC1/2 state "@PreAuthorize verifies caller is party". Party check is service-layer only. Consistent with module pattern; 403 outcome preserved — deferred, architectural pattern.

## Change Log

- 2026-06-27: Story 8.4 implemented — Flyway V66 migration, 2 report entities + 4 report repositories, 2 domain events, 2 contract DTOs, 4 new error codes, MessagingReportService, MessageRetentionScheduler, 3 new endpoints in MessagingResource, MessagingApiAdvice 409 extension, softDeleteMessage in MessagingService, BLOCKED check in sendMessage. 13 tests (6 AbuseReportIT, 4 SoftDeleteIT, 3 RetentionSchedulerTest). All 728 tests pass.
