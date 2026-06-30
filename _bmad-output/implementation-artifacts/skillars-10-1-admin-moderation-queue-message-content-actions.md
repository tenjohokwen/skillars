# Story 10.1: Admin Moderation Queue & Message Content Actions

Status: done

## Story

As a platform admin,
I want a unified queue showing all held messages and conversation reports, and the ability to approve or block individual messages,
So that harmful content flagged by Gemini or reported by users is resolved promptly without manual triage across separate systems.

## Acceptance Criteria

1. **Given** a `MessageReportedEvent` or `ConversationReportedEvent` is published (Story 8.4)
   **When** the admin module consumes it
   **Then** an `admin_alerts` row is inserted: `(alertId UUID PK, type ENUM(MESSAGE_REPORT/CONVERSATION_REPORT/REVIEW_FLAG/STRIKE_THRESHOLD/DISPUTE_RAISED) NOT NULL, referenceId VARCHAR(36) NOT NULL, referenceType ENUM(MESSAGE/CONVERSATION/REVIEW/COACH/BOOKING) NOT NULL, status ENUM(OPEN/IN_PROGRESS/RESOLVED) NOT NULL DEFAULT 'OPEN', createdAt TIMESTAMPTZ, resolvedAt TIMESTAMPTZ nullable, resolvedBy BIGINT nullable)`
   **And** similarly, a `ReviewFlaggedEvent` (Story 9.3) and a `StrikeThresholdReachedEvent` (Story 7.3) each insert an `admin_alerts` row with the appropriate type and referenceId

   **DEVIATION from epic spec:** Epic specifies `referenceId UUID NOT NULL` and `resolvedBy UUID nullable`. In this codebase, messaging entities use TSID-based `Long` IDs (`messageId`, `conversationId`). The column is `VARCHAR(36)` to hold both UUID strings and Long strings. Admin user IDs are `Long` (per `SecurityUtil`/`getBusinessId()`) — `resolvedBy` and all admin IDs are `BIGINT`.

2. **Given** an admin views the moderation queue
   **When** `GET /api/admin/queue?type={MESSAGE_REPORT|CONVERSATION_REPORT|REVIEW_FLAG|ALL}&status=OPEN&page={n}` is called
   **Then** `@PreAuthorize` admin role required
   **And** alerts are returned ordered by `createdAt ASC` — oldest unresolved first
   **And** each entry includes: `alertId`, `type`, `referenceId`, `referenceType`, `status`, `createdAt`, and a `summary` field:
   - `MESSAGE_REPORT`: first 100 chars of message content (admin sees full content)
   - `CONVERSATION_REPORT`: reporter's stated `reason` from `conversation_reports`
   - `REVIEW_FLAG`: `flagCount` and top flag reason from `review_flags`
   - `STRIKE_THRESHOLD`: empty string `""` (coach profile lookup deferred to enforcement view — no coach query in this story)

3. **Given** an admin views a held message
   **When** `GET /api/admin/messages/{messageId}` is called
   **Then** `@PreAuthorize` admin role required
   **And** the full message content is returned including `senderId`, `senderRole`, `conversationId`, `moderationStatus`, `createdAt`
   **And** the conversation context is included: last 5 messages before and after this message by `createdAt` — blocked messages in the context window show `content = null`; all other statuses show full content
   **And** all `message_reports` for this message are returned with `reason` and `details` — `reportedBy` identity NOT included

4. **Given** an admin approves a held message
   **When** `POST /api/admin/messages/{messageId}/approve` is called
   **Then** `@PreAuthorize` admin role required
   **And** `messages.moderationStatus` is set to `APPROVED` and `messages.deliveredAt = now()`
   **And** an SSE event `{ type: "NEW_MESSAGE", messageId, conversationId }` is emitted to the recipient's active connection post-transaction (via `TransactionSynchronizationManager.afterCommit()`)
   **And** all `message_reports` for this message are soft-resolved: `resolvedAt = now()`, `resolvedBy = adminId` on each open report row
   **And** the corresponding `admin_alerts` row (type=MESSAGE_REPORT for this messageId) is resolved: `status = RESOLVED`, `resolvedAt = now()`, `resolvedBy = adminId`
   **And** action logged in `admin_action_log` (`actionType = MESSAGE_APPROVE`)
   **And** `200 OK`

5. **Given** an admin blocks a message
   **When** `POST /api/admin/messages/{messageId}/block` is called with `{ "reason": "..." }`
   **Then** `@PreAuthorize` admin role required
   **And** `messages.moderationStatus` is set to `BLOCKED`
   **And** an SSE event `{ type: "MESSAGE_BLOCKED", messageId, conversationId }` is emitted to the sender's active connection post-transaction
   **And** all `message_reports` soft-resolved; `admin_alerts` row resolved
   **And** action logged in `admin_action_log` (`actionType = MESSAGE_BLOCK`)
   **And** `200 OK`

6. **Given** an admin views a reported conversation
   **When** `GET /api/admin/conversations/{conversationId}` is called
   **Then** `@PreAuthorize` admin role required
   **And** the full conversation thread is returned with all non-deleted messages regardless of `moderationStatus`, ordered by `createdAt ASC` — admin sees full content of all messages (no content nulling in conversation view)
   **And** `conversation_reports` for this conversation are included with `reason` and `details` — `reportedBy` NOT included
   **And** `conversations.status` is included

7. **Given** an admin wants to unblock a conversation
   **When** `POST /api/admin/conversations/{conversationId}/unblock` is called
   **Then** `@PreAuthorize` admin role required
   **And** `conversations.status` is set to `ACTIVE`
   **And** all open `conversation_reports` rows are soft-resolved: `resolvedAt = now()`, `resolvedBy = adminId`
   **And** the `admin_alerts` row (type=CONVERSATION_REPORT for this conversationId) is resolved
   **And** action logged in `admin_action_log` (`actionType = CONVERSATION_UNBLOCK`)
   **And** `200 OK`

8. **Given** an admin wants to see a summary of unresolved work
   **When** `GET /api/admin/queue/summary` is called
   **Then** `@PreAuthorize` admin role required
   **And** a count of OPEN alerts is returned broken down by type: `{ messageReports: N, conversationReports: N, reviewFlags: N, strikeAlerts: N, disputes: N, total: N }`
   **And** this endpoint is `@Transactional(readOnly=true)` — uses a single GROUP BY query

## Tasks / Subtasks

- [x] **Task 1 — Flyway Migration V70** (AC: 1, 8)
  - [x] Create `src/main/resources/db/migration/V70__admin_alerts_action_log.sql`:
    ```sql
    CREATE SCHEMA IF NOT EXISTS admin;

    CREATE TABLE admin.admin_alerts (
        alert_id       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
        type           VARCHAR(25) NOT NULL CHECK (type IN ('MESSAGE_REPORT','CONVERSATION_REPORT','REVIEW_FLAG','STRIKE_THRESHOLD','DISPUTE_RAISED')),
        reference_id   VARCHAR(36) NOT NULL,
        reference_type VARCHAR(15) NOT NULL CHECK (reference_type IN ('MESSAGE','CONVERSATION','REVIEW','COACH','BOOKING')),
        status         VARCHAR(15) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_PROGRESS','RESOLVED')),
        created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        resolved_at    TIMESTAMPTZ,
        resolved_by    BIGINT
    );
    CREATE INDEX admin_alerts_status_created ON admin.admin_alerts(status, created_at);

    CREATE TABLE admin.admin_action_log (
        log_id       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
        admin_id     BIGINT      NOT NULL,
        action_type  VARCHAR(25) NOT NULL CHECK (action_type IN ('MESSAGE_APPROVE','MESSAGE_BLOCK','CONVERSATION_UNBLOCK','REVIEW_APPROVE','REVIEW_BLOCK','COACH_SUSPEND','COACH_REINSTATE','DISPUTE_RESOLVE')),
        reference_id VARCHAR(36) NOT NULL,
        reason       VARCHAR(500),
        created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
    ```
  - [x] `admin_alerts.reference_id` is `VARCHAR(36)` — holds UUID strings (36 chars with hyphens) for reviews/coaches and Long TSID strings (≤19 digits) for messages/conversations
  - [x] `resolved_by` is `BIGINT` (admin IDs in this system are Long, not UUID)
  - [x] Include all 8 `action_type` values now to avoid future migration churn; only MESSAGE_APPROVE, MESSAGE_BLOCK, CONVERSATION_UNBLOCK are used in this story

- [x] **Task 2 — Admin Contract Enums** (AC: 1, 2)
  - [x] Create `platform/admin/contract/AdminAlertType.java`:
    ```java
    public enum AdminAlertType {
        MESSAGE_REPORT, CONVERSATION_REPORT, REVIEW_FLAG, STRIKE_THRESHOLD, DISPUTE_RAISED
    }
    ```
  - [x] Create `platform/admin/contract/AdminAlertStatus.java`:
    ```java
    public enum AdminAlertStatus { OPEN, IN_PROGRESS, RESOLVED }
    ```
  - [x] Create `platform/admin/contract/AdminAlertReferenceType.java`:
    ```java
    public enum AdminAlertReferenceType { MESSAGE, CONVERSATION, REVIEW, COACH, BOOKING }
    ```
  - [x] Create `platform/admin/contract/AdminActionType.java`:
    ```java
    public enum AdminActionType {
        MESSAGE_APPROVE, MESSAGE_BLOCK, CONVERSATION_UNBLOCK,
        REVIEW_APPROVE, REVIEW_BLOCK, COACH_SUSPEND, COACH_REINSTATE, DISPUTE_RESOLVE
    }
    ```

- [x] **Task 3 — `AdminAlert` Entity and Repository** (AC: 1, 2, 4, 5, 7, 8)
  - [x] Create `platform/admin/repo/AdminAlert.java`:
    ```java
    @Entity @Table(schema = "admin", name = "admin_alerts")
    @Getter @Setter @NoArgsConstructor
    public class AdminAlert {
        @Id @GeneratedValue(strategy = GenerationType.UUID)
        @Column(name = "alert_id") private UUID alertId;

        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 25) private AdminAlertType type;

        @Column(name = "reference_id", nullable = false, length = 36) private String referenceId;

        @Enumerated(EnumType.STRING)
        @Column(name = "reference_type", nullable = false, length = 15) private AdminAlertReferenceType referenceType;

        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 15) private AdminAlertStatus status = AdminAlertStatus.OPEN;

        @Column(name = "created_at", nullable = false, updatable = false)
        private Instant createdAt = Instant.now();

        @Column(name = "resolved_at") private Instant resolvedAt;
        @Column(name = "resolved_by") private Long resolvedBy;
    }
    ```
  - [x] `AdminAlert` does NOT extend `BaseEntity` — it uses a UUID PK (`@GeneratedValue(strategy = GenerationType.UUID)`), not TSID. This is intentional to match the `alert_id UUID PK DEFAULT gen_random_uuid()` schema.
  - [x] Create `platform/admin/repo/AdminAlertRepository.java`:
    ```java
    public interface AdminAlertRepository extends JpaRepository<AdminAlert, UUID> {
        // Queue: filter by type (optional — null = ALL) and status, ordered by createdAt ASC
        @Query("""
            SELECT a FROM AdminAlert a
            WHERE (:type IS NULL OR a.type = :type)
              AND a.status = :status
            ORDER BY a.createdAt ASC
            """)
        Page<AdminAlert> findByTypeAndStatus(
            @Param("type") AdminAlertType type,
            @Param("status") AdminAlertStatus status,
            Pageable pageable);

        // Summary: count OPEN alerts by type
        @Query("SELECT a.type, COUNT(a) FROM AdminAlert a WHERE a.status = 'OPEN' GROUP BY a.type")
        List<Object[]> countOpenByType();

        // Resolve: find OPEN alert for a given referenceId and type
        Optional<AdminAlert> findFirstByReferenceIdAndTypeAndStatus(
            String referenceId, AdminAlertType type, AdminAlertStatus status);
    }
    ```
  - [x] **`findByTypeAndStatus` with nullable `type`**: SpEL `:type IS NULL OR a.type = :type` lets the caller pass `null` for "ALL". Tested carefully: passing null to an `@Param` ENUM type in JPA requires `@Param("type") @Nullable AdminAlertType type` — add `import org.springframework.lang.Nullable;`.

- [x] **Task 4 — `AdminActionLog` Entity and Repository** (AC: 4, 5, 7)
  - [x] Create `platform/admin/repo/AdminActionLog.java`:
    ```java
    @Entity @Table(schema = "admin", name = "admin_action_log")
    @Getter @Setter @NoArgsConstructor
    public class AdminActionLog {
        @Id @GeneratedValue(strategy = GenerationType.UUID)
        @Column(name = "log_id") private UUID logId;

        @Column(name = "admin_id", nullable = false) private Long adminId;

        @Enumerated(EnumType.STRING)
        @Column(name = "action_type", nullable = false, length = 25) private AdminActionType actionType;

        @Column(name = "reference_id", nullable = false, length = 36) private String referenceId;
        @Column(length = 500) private String reason;

        @Column(name = "created_at", nullable = false, updatable = false)
        private Instant createdAt = Instant.now();
    }
    ```
  - [x] Does NOT extend `BaseEntity` — UUID PK same as `AdminAlert`.
  - [x] Create `platform/admin/repo/AdminActionLogRepository.java`:
    ```java
    public interface AdminActionLogRepository extends JpaRepository<AdminActionLog, UUID> {}
    ```

- [x] **Task 5 — Update Messaging Repos for Admin Reads** (AC: 3, 4, 5, 6, 7)
  - [x] In `platform/messaging/repo/MessageRepository.java`, add:
    ```java
    // Context window: 5 messages BEFORE pivot (ordered DESC so we get closest-to-pivot; caller reverses for display)
    @Query("SELECT m FROM Message m WHERE m.conversationId = :convId AND m.createdAt < :pivot AND m.deletedAt IS NULL ORDER BY m.createdAt DESC")
    List<Message> findBeforePivot(@Param("convId") Long convId, @Param("pivot") Instant pivot, Pageable pageable);

    // Context window: 5 messages AFTER pivot
    @Query("SELECT m FROM Message m WHERE m.conversationId = :convId AND m.createdAt > :pivot AND m.deletedAt IS NULL ORDER BY m.createdAt ASC")
    List<Message> findAfterPivot(@Param("convId") Long convId, @Param("pivot") Instant pivot, Pageable pageable);

    // Admin conversation view: ALL messages including soft-deleted — admin must see full picture
    // (a user can soft-delete incriminating messages after reporting; excluding them blinds the admin)
    @Query("SELECT m FROM Message m WHERE m.conversationId = :convId ORDER BY m.createdAt ASC")
    List<Message> findAllForAdmin(@Param("convId") Long convId);
    ```
  - [x] In `platform/messaging/repo/MessageReportRepository.java`, add:
    ```java
    List<MessageReport> findByMessageId(Long messageId);
    ```
  - [x] In `platform/messaging/repo/ConversationReportRepository.java`, add:
    ```java
    List<ConversationReport> findByConversationId(Long conversationId);

    @Modifying
    @Query("UPDATE ConversationReport r SET r.resolvedAt = :resolvedAt, r.resolvedBy = :resolvedBy WHERE r.conversationId = :convId AND r.resolvedAt IS NULL")
    void resolveAllOpenByConversationId(@Param("convId") Long convId, @Param("resolvedAt") Instant resolvedAt, @Param("resolvedBy") Long resolvedBy);
    ```
  - [x] In `platform/messaging/repo/MessageReportRepository.java`, add:
    ```java
    @Modifying
    @Query("UPDATE MessageReport r SET r.resolvedAt = :resolvedAt, r.resolvedBy = :resolvedBy WHERE r.messageId = :messageId AND r.resolvedAt IS NULL")
    void resolveAllOpenByMessageId(@Param("messageId") Long messageId, @Param("resolvedAt") Instant resolvedAt, @Param("resolvedBy") Long resolvedBy);
    ```
  - [x] Add `@Modifying(clearAutomatically = true)` to all `@Modifying` queries (consistent with fix applied in Story 9.2/9.3).

- [x] **Task 6 — Admin DTOs** (AC: 2, 3, 6, 8)
  - [x] Create `platform/admin/contract/AdminAlertDto.java`:
    ```java
    public record AdminAlertDto(
        UUID alertId, String type, String referenceId, String referenceType,
        String status, Instant createdAt, String summary) {}
    ```
  - [x] Create `platform/admin/contract/AdminQueueSummaryDto.java`:
    ```java
    public record AdminQueueSummaryDto(
        long messageReports, long conversationReports, long reviewFlags,
        long strikeAlerts, long disputes, long total) {}
    ```
  - [x] Create `platform/admin/contract/AdminMessageDetailDto.java`:
    ```java
    public record AdminMessageDetailDto(
        Long messageId, Long conversationId, Long senderId, String senderRole,
        String content, String moderationStatus, Instant deliveredAt, Instant createdAt,
        List<AdminMessageContextDto> contextBefore,
        List<AdminMessageContextDto> contextAfter,
        List<AdminMessageReportDto> reports) {}
    ```
  - [x] Create `platform/admin/contract/AdminMessageContextDto.java`:
    ```java
    // Dual-use DTO:
    // (1) Message context window (AC 3): content is null when moderationStatus == BLOCKED; full otherwise.
    // (2) Conversation detail view (AC 6): content is null when message is soft-deleted (deletedAt != null);
    //     full content for all other statuses including BLOCKED — admin sees everything in conversation view.
    public record AdminMessageContextDto(
        Long messageId, String senderRole, String content,
        String moderationStatus, Instant createdAt) {}
    ```
  - [x] Create `platform/admin/contract/AdminMessageReportDto.java`:
    ```java
    public record AdminMessageReportDto(String reason, String details, Instant createdAt) {}
    ```
  - [x] Create `platform/admin/contract/AdminConversationDetailDto.java`:
    ```java
    public record AdminConversationDetailDto(
        Long conversationId, String status,
        List<AdminMessageContextDto> messages,
        List<AdminConversationReportDto> reports) {}
    ```
  - [x] Create `platform/admin/contract/AdminConversationReportDto.java`:
    ```java
    public record AdminConversationReportDto(String reason, String details, Instant createdAt) {}
    ```
  - [x] Create `platform/admin/contract/AdminBlockMessageRequest.java`:
    ```java
    public record AdminBlockMessageRequest(@NotBlank @Size(max = 500) String reason) {}
    ```

- [x] **Task 7 — `AdminAlertEventListener`** (AC: 1)
  - [x] Create `platform/admin/service/AdminAlertEventListener.java`:
    ```java
    @Component
    @RequiredArgsConstructor
    @Slf4j
    public class AdminAlertEventListener {

        private final AdminAlertRepository adminAlertRepository;

        @EventListener
        @Transactional
        public void onMessageReported(MessageReportedEvent event) {
            insertAlert(AdminAlertType.MESSAGE_REPORT,
                String.valueOf(event.messageId()),
                AdminAlertReferenceType.MESSAGE);
        }

        @EventListener
        @Transactional
        public void onConversationReported(ConversationReportedEvent event) {
            insertAlert(AdminAlertType.CONVERSATION_REPORT,
                String.valueOf(event.conversationId()),
                AdminAlertReferenceType.CONVERSATION);
        }

        @EventListener
        @Transactional
        public void onReviewFlagged(ReviewFlaggedEvent event) {
            insertAlert(AdminAlertType.REVIEW_FLAG,
                event.reviewId().toString(),
                AdminAlertReferenceType.REVIEW);
        }

        @EventListener
        @Transactional
        public void onStrikeThreshold(StrikeThresholdReachedEvent event) {
            insertAlert(AdminAlertType.STRIKE_THRESHOLD,
                event.getCoachId().toString(),
                AdminAlertReferenceType.COACH);
        }

        private void insertAlert(AdminAlertType type, String referenceId, AdminAlertReferenceType referenceType) {
            if (adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(
                    referenceId, type, AdminAlertStatus.OPEN).isPresent()) {
                log.debug("Admin alert already OPEN for type={}, referenceId={} — skipping duplicate", type, referenceId);
                return;
            }
            AdminAlert alert = new AdminAlert();
            alert.setType(type);
            alert.setReferenceId(referenceId);
            alert.setReferenceType(referenceType);
            adminAlertRepository.save(alert);
            log.debug("Admin alert created: type={}, referenceId={}", type, referenceId);
        }
    }
    ```
  - [x] `@EventListener` (NOT `@TransactionalEventListener`) — same pattern as `AccountSuspensionEventListener`. The events are published within an existing transaction (from messaging, reviews, payment services), so this listener participates in that same transaction. If the outer transaction rolls back, the alert insert rolls back too. This is the correct behavior.
  - [x] `StrikeThresholdReachedEvent` extends `ApplicationEvent` (Spring class); the method parameter type `StrikeThresholdReachedEvent` is enough for Spring to resolve it via `@EventListener` — no class attribute needed.
  - [x] Imports: `MessageReportedEvent` from `platform.messaging.contract`, `ConversationReportedEvent` from `platform.messaging.contract`, `ReviewFlaggedEvent` from `platform.reviews.contract`, `StrikeThresholdReachedEvent` from `platform.payment.contract.event`.
  - [x] **DUPLICATION GUARD — dedup at insert time, applies to ALL event types**: `ReviewFlaggedEvent` fires once per flag, `MessageReportedEvent` fires once per user-report (Story 8.4: 409 only prevents the same user reporting twice — different users each fire their own event), and `ConversationReportedEvent` fires once per reporter. All three can produce multiple events for the same entity. Without dedup, queue counts inflate and `findFirstByReferenceIdAndTypeAndStatus` on approve/block resolves only ONE of many OPEN alerts, leaving orphans that never clear. Fix: `insertAlert()` checks for an existing OPEN alert before saving — if `findFirstByReferenceIdAndTypeAndStatus(referenceId, type, OPEN).isPresent()`, skip the insert (log at debug). This ensures at most one OPEN alert per `(referenceId, type)` pair, making the resolve logic correct.

- [x] **Task 8 — `AdminQueueService`** (AC: 2, 8)
  - [x] Create `platform/admin/service/AdminQueueService.java`:
    - `@Service @RequiredArgsConstructor @Slf4j`
    - Inject: `AdminAlertRepository`, `MessageRepository`, `ConversationReportRepository`, `ReviewFlagRepository`
    - Method `getAlerts(String typeParam, int page)` → `Page<AdminAlertDto>` — annotate `@Transactional(readOnly = true)`:
      1. Parse `typeParam`: `"ALL"` or `null` → `type = null` (no filter); others → `AdminAlertType.valueOf(typeParam)`
      2. `Pageable p = PageRequest.of(Math.max(0, page), 20)`
      3. `adminAlertRepository.findByTypeAndStatus(type, AdminAlertStatus.OPEN, p)` → map each to `AdminAlertDto` via `buildSummary(alert)`
    - Method `buildSummary(AdminAlert alert)` → `String`:
      - `MESSAGE_REPORT`: load `messageRepository.findById(Long.parseLong(alert.getReferenceId()))` → first 100 chars of `message.getContent()`, or `"[message not found]"` if absent
      - `CONVERSATION_REPORT`: load `conversationReportRepository.findByConversationId(Long.parseLong(alert.getReferenceId()))` → first report's `reason.name()`, or `"[no report]"` if empty
      - `REVIEW_FLAG`: parse UUID from `alert.getReferenceId()` → `reviewFlagRepository.countByReviewId(uuid) + " flags, top: " + topReason(uuid)` — `topReason` groups by reason and returns name of max
      - `STRIKE_THRESHOLD`: return `""` — matches AC 2 (coach profile lookup deferred to enforcement view)
      - default: `""`
    - Method `getSummary()` → `AdminQueueSummaryDto` — annotate `@Transactional(readOnly = true)`:
      1. `List<Object[]> rows = adminAlertRepository.countOpenByType()` — each row: `[AdminAlertType, Long]`
      2. Map into counters by `AdminAlertType` enum
      3. Map rows into an `EnumMap<AdminAlertType, Long>` then build DTO — never leave an unrecognised type silently missing from `total`:
         ```java
         Map<AdminAlertType, Long> counts = new EnumMap<>(AdminAlertType.class);
         for (Object[] row : rows) { counts.put((AdminAlertType) row[0], (Long) row[1]); }
         long total = counts.values().stream().mapToLong(Long::longValue).sum();
         return new AdminQueueSummaryDto(
             counts.getOrDefault(AdminAlertType.MESSAGE_REPORT,      0L),
             counts.getOrDefault(AdminAlertType.CONVERSATION_REPORT,  0L),
             counts.getOrDefault(AdminAlertType.REVIEW_FLAG,          0L),
             counts.getOrDefault(AdminAlertType.STRIKE_THRESHOLD,     0L),
             counts.getOrDefault(AdminAlertType.DISPUTE_RAISED,       0L),
             total);
         ```
         `disputes` is 0 for this story (DISPUTE_RAISED alert rows arrive in Story 10.3); `getOrDefault` handles the missing key correctly.
    - **CRITICAL**: `buildSummary()` for MESSAGE_REPORT reads full content — admin sees the actual held content. NOT nulled. This is intentional and correct for the admin moderation use case.
    - **CRITICAL**: UUID parsing for REVIEW_FLAG: `UUID.fromString(alert.getReferenceId())`. Wrap in try-catch and return `"[invalid referenceId]"` on `IllegalArgumentException`.

- [x] **Task 9 — `AdminMessageService`** (AC: 3, 4, 5)
  - [x] Create `platform/admin/service/AdminMessageService.java`:
    - `@Service @RequiredArgsConstructor @Slf4j`
    - Inject: `MessageRepository`, `MessageReportRepository`, `ConversationRepository`, `AdminAlertRepository`, `AdminActionLogRepository`, `MessagingEmitterRegistry`, `CoachProfileRepository`, `AgePolicyService`
    - **`@Transactional(readOnly = true)` method `getMessageDetail(Long messageId)`** → `AdminMessageDetailDto`:
      1. Load `Message` via `messageRepository.findById(messageId).orElseThrow(ResourceNotFoundException("Message not found", "message"))`
      2. Load context before: `messageRepository.findBeforePivot(msg.getConversationId(), msg.getCreatedAt(), PageRequest.of(0, 5))` → reverse the list (findBeforePivot returns DESC; display order is ASC)
      3. Load context after: `messageRepository.findAfterPivot(msg.getConversationId(), msg.getCreatedAt(), PageRequest.of(0, 5))`
      4. Map context messages to `AdminMessageContextDto` — content is `null` if `status == BLOCKED`, full otherwise
      5. Load reports: `messageReportRepository.findByMessageId(messageId)` → map to `AdminMessageReportDto(reason.name(), details, createdAt)` — `reportedBy` NOT included
      6. Return `AdminMessageDetailDto` — `content` is the FULL message content regardless of status (admin view)
    - **`@Transactional` method `approveMessage(Long messageId, Long adminId)`**:
      1. Load `Message` or throw `ResourceNotFoundException`
      2. **Idempotency guard**: `if (message.getModerationStatus() != MessageModerationStatus.UNDER_REVIEW) return;` — prevents duplicate SSE events, duplicate action log rows, and deliveredAt timestamp overwrites on retry or concurrent admin calls
      3. `message.setModerationStatus(MessageModerationStatus.APPROVED)` + `setDeliveredAt(Instant.now())`
      4. `messageRepository.save(message)`
      5. `messageReportRepository.resolveAllOpenByMessageId(messageId, Instant.now(), adminId)`
      6. Resolve alert: `adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(String.valueOf(messageId), MESSAGE_REPORT, OPEN)` → if present: set `status=RESOLVED`, `resolvedAt=now()`, `resolvedBy=adminId`, save
      7. Log: save `AdminActionLog(adminId=adminId, actionType=MESSAGE_APPROVE, referenceId=String.valueOf(messageId), reason=null)`
      8. Register SSE afterCommit: `TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { emitNewMessage(message); } })`
    - **`@Transactional` method `blockMessage(Long messageId, String reason, Long adminId)`**:
      1. Load `Message` or throw
      2. **Idempotency guard**: `if (message.getModerationStatus() != MessageModerationStatus.UNDER_REVIEW) return;` — same rationale as approveMessage; prevents duplicate SSE to sender on retry
      3. `message.setModerationStatus(MessageModerationStatus.BLOCKED)` (no deliveredAt change)
      4. Save; resolve reports; resolve alert; log (`MESSAGE_BLOCK`, reason stored)
      5. Register SSE afterCommit: emit `MESSAGE_BLOCKED` to sender (`message.getSenderId()`)
    - **Private `emitNewMessage(Message message)`**:
      ```java
      Long recipientId = resolveRecipient(message.getConversationId(), message.getSenderRole().name());
      if (recipientId != null) {
          emitterRegistry.emit(recipientId, "NEW_MESSAGE",
              Map.of("type", "NEW_MESSAGE", "messageId", message.getId(), "conversationId", message.getConversationId()));
      }
      ```
    - **Private `resolveRecipient(Long conversationId, String senderRole)`**:
      ```java
      var conv = conversationRepository.findById(conversationId).orElse(null);
      if (conv == null) return null;
      if ("COACH".equals(senderRole)) {
          AgeMessagingPolicy agePolicy = AgeMessagingPolicy.from(
              agePolicyService.getMessagingPolicy(conv.getPlayerId()));
          return agePolicy.parentIsBlocked() ? conv.getPlayerId() : conv.getParentId();
      } else {
          return coachProfileRepository.findById(conv.getCoachId())
              .map(CoachProfile::getUserId).orElse(null);
      }
      ```
    - **Import**: `AgeMessagingPolicy` from `platform.messaging.service` (cross-module import — consistent with other cross-module patterns in this codebase, e.g. `AdminReviewService` imports from `platform.reviews.repo`).
    - **Import**: `MessagingEmitterRegistry` from `platform.messaging.service`.
    - **CRITICAL**: SSE emit is registered via `TransactionSynchronizationManager.registerSynchronization()` afterCommit — same pattern as `ModerationResultApplier`. This ensures SSE fires only after the DB transaction commits, preventing phantom deliveries on rollback.
    - **CRITICAL**: Both `resolveAllOpenByMessageId` and `resolveAllOpenByConversationId` have `@Modifying(clearAutomatically = true)` — required for JPA L1 cache consistency.

- [x] **Task 10 — `AdminConversationService`** (AC: 6, 7)
  - [x] Create `platform/admin/service/AdminConversationService.java`:
    - `@Service @RequiredArgsConstructor @Slf4j`
    - Inject: `ConversationRepository`, `MessageRepository`, `ConversationReportRepository`, `AdminAlertRepository`, `AdminActionLogRepository`
    - **`@Transactional(readOnly = true)` method `getConversationDetail(Long conversationId)`** → `AdminConversationDetailDto`:
      1. Load `Conversation` or throw `ResourceNotFoundException("Conversation not found", "conversation")`
      2. Load messages: `messageRepository.findAllForAdmin(conversationId)` — includes soft-deleted messages, ordered ASC; admin sees full picture including messages a sender may have deleted after reporting
      3. Map messages to `AdminMessageContextDto` — `content = null` if `m.getDeletedAt() != null` (soft-deleted; user removed it — shown as null to indicate absence); otherwise full content; admin sees BLOCKED message content too in this view (no status-based nulling in conversation view)
      4. Load reports: `conversationReportRepository.findByConversationId(conversationId)` → map to `AdminConversationReportDto(reason.name(), details, createdAt)` — `reportedBy` NOT included
      5. Return `AdminConversationDetailDto`
    - **`@Transactional` method `unblockConversation(Long conversationId, Long adminId)`**:
      1. Load `Conversation` or throw
      2. `conversation.setStatus(ConversationStatus.ACTIVE)` + save
      3. `conversationReportRepository.resolveAllOpenByConversationId(conversationId, Instant.now(), adminId)`
      4. Resolve alert: `adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(String.valueOf(conversationId), CONVERSATION_REPORT, OPEN)` → resolve if present
      5. Log: save `AdminActionLog(adminId, CONVERSATION_UNBLOCK, String.valueOf(conversationId), null)`

- [x] **Task 11 — `AdminModerationResource`** (AC: 2, 3, 4, 5, 6, 7, 8)
  - [x] Create `platform/admin/api/AdminModerationResource.java`:
    ```java
    @RestController
    @RequestMapping("/api/admin")
    @RequiredArgsConstructor
    @Observed(name = "admin.moderation")
    public class AdminModerationResource {

        private final AdminQueueService adminQueueService;
        private final AdminMessageService adminMessageService;
        private final AdminConversationService adminConversationService;
        private final SecurityUtil securityUtil;

        @GetMapping("/queue")
        @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
        @Observed(name = "admin.queue")
        public ResponseEntity<Page<AdminAlertDto>> getQueue(
                @RequestParam(defaultValue = "ALL") String type,
                @RequestParam(defaultValue = "OPEN") String status,
                @RequestParam(defaultValue = "0") int page) {
            if (!"OPEN".equalsIgnoreCase(status)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only status=OPEN is supported in this version");
            }
            return ResponseEntity.ok(adminQueueService.getAlerts(type, page));
        }

        @GetMapping("/queue/summary")
        @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
        @Observed(name = "admin.queue.summary")
        public ResponseEntity<AdminQueueSummaryDto> getQueueSummary() {
            return ResponseEntity.ok(adminQueueService.getSummary());
        }

        @GetMapping("/messages/{messageId}")
        @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
        @Observed(name = "admin.messages.detail")
        public ResponseEntity<AdminMessageDetailDto> getMessageDetail(@PathVariable Long messageId) {
            return ResponseEntity.ok(adminMessageService.getMessageDetail(messageId));
        }

        @PostMapping("/messages/{messageId}/approve")
        @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
        @Observed(name = "admin.messages.approve")
        public ResponseEntity<Void> approveMessage(@PathVariable Long messageId) {
            adminMessageService.approveMessage(messageId, resolveAdminId());
            return ResponseEntity.ok().build();
        }

        @PostMapping("/messages/{messageId}/block")
        @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
        @Observed(name = "admin.messages.block")
        public ResponseEntity<Void> blockMessage(
                @PathVariable Long messageId,
                @Valid @RequestBody AdminBlockMessageRequest request) {
            adminMessageService.blockMessage(messageId, request.reason(), resolveAdminId());
            return ResponseEntity.ok().build();
        }

        @GetMapping("/conversations/{conversationId}")
        @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
        @Observed(name = "admin.conversations.detail")
        public ResponseEntity<AdminConversationDetailDto> getConversationDetail(
                @PathVariable Long conversationId) {
            return ResponseEntity.ok(adminConversationService.getConversationDetail(conversationId));
        }

        @PostMapping("/conversations/{conversationId}/unblock")
        @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
        @Observed(name = "admin.conversations.unblock")
        public ResponseEntity<Void> unblockConversation(@PathVariable Long conversationId) {
            adminConversationService.unblockConversation(conversationId, resolveAdminId());
            return ResponseEntity.ok().build();
        }

        private Long resolveAdminId() {
            Object principal = securityUtil.getCurrentUser();
            if (!(principal instanceof Principal p)) {
                throw new InsufficientAuthenticationException("Unexpected principal type");
            }
            try { return Long.parseLong(p.getBusinessId()); }
            catch (NumberFormatException e) {
                throw new InsufficientAuthenticationException("Invalid business ID");
            }
        }
    }
    ```
  - [x] `@RequestMapping("/api/admin")` — intentional: this resource handles the unified `/api/admin/queue`, `/api/admin/messages/**`, `/api/admin/conversations/**` paths. It does NOT conflict with `AdminReviewResource` which maps to `/api/admin/reviews`.
  - [x] `resolveAdminId()` — same pattern as `AdminReviewResource.resolveAdminId()` (copy exactly to keep pattern consistent).
  - [x] The `status` query param is validated: only `OPEN` is accepted; any other value throws `ResponseStatusException(HttpStatus.BAD_REQUEST, "Only status=OPEN is supported in this version")`. Import `org.springframework.web.server.ResponseStatusException` and `org.springframework.http.HttpStatus`. Silently ignoring an unsupported param would make callers passing `status=IN_PROGRESS` believe they got IN_PROGRESS results.

- [x] **Task 12 — Integration Test: `AdminQueueIT`** (AC: 1, 2, 8)
  - [x] Create `platform/admin/api/AdminQueueIT.java` in TSID range `9000_xxx`:
    - `ADMIN_ID = 9000_000_100L`, `PARENT_ID = 9000_000_001L`, `PLAYER_ID = 9000_000_002L`, `COACH_USER_ID = 9000_000_010L`
    - Seed: admin user (ROLE_ADMIN), parent, player, coach. Conversation + 1 UNDER_REVIEW message. 1 message_report row for that message. 1 conversation_report row.
    - Seed: Publish `MessageReportedEvent` via `ApplicationEventPublisher` within a transaction (or insert alert rows directly via JdbcTemplate)
    - **Approach**: Insert `admin_alerts` rows directly via JdbcTemplate (event publishing in @BeforeEach is complex with @Transactional test boundary) — this tests the queue API, not the event listener
    - Test 1: `adminCanViewQueue_returnsAlerts()` — GET queue as admin, verify alertId, type, summary fields present
    - Test 2: `nonAdminCannotViewQueue_returns403()` — parent token → 403
    - Test 3: `filterByType_returnsOnlyMatchingAlerts()` — insert MESSAGE_REPORT and REVIEW_FLAG alerts; GET queue?type=MESSAGE_REPORT → only MESSAGE_REPORT returned
    - Test 4: `queueSummary_returnsCountByType()` — GET /api/admin/queue/summary → verify `messageReports` count matches seeded rows

- [x] **Task 13 — Integration Test: `MessageApproveIT`** (AC: 3, 4)
  - [x] Create `platform/admin/api/MessageApproveIT.java` in TSID range `9010_xxx`:
    - `ADMIN_ID = 9010_000_100L`, `PARENT_ID = 9010_000_001L`, `PLAYER_ID = 9010_000_002L`, `COACH_USER_ID = 9010_000_010L`
    - Seed: admin user, parent, player, coach, conversation, 1 message with `moderation_status = 'UNDER_REVIEW'`, 1 `message_reports` row, 1 `admin_alerts` row (type=MESSAGE_REPORT)
    - Test 1: `adminCanViewMessageDetail()` — GET /api/admin/messages/{id} → returns full content, reports list, contextBefore/After (empty since only 1 message)
    - Test 2: `approveMessage_setsApprovedDeliveredAt_resolvesReportsAndAlert()` — POST approve → verify `moderation_status = 'APPROVED'`, `delivered_at` not null, `message_reports.resolved_at` set, `admin_alerts.status = 'RESOLVED'`, `admin_action_log` row inserted
    - Test 3: `nonAdminCannotApproveMessage_returns403()` — parent token → 403
    - **Note**: SSE delivery is best-effort and cannot be reliably tested in integration tests. Test 2 verifies only DB state. SSE delivery is tested separately if needed.

- [x] **Task 14 — Integration Test: `MessageBlockIT`** (AC: 5)
  - [x] Create `platform/admin/api/MessageBlockIT.java` in TSID range `9020_xxx`:
    - `ADMIN_ID = 9020_000_100L`, `PARENT_ID = 9020_000_001L`, `PLAYER_ID = 9020_000_002L`, `COACH_USER_ID = 9020_000_010L`
    - Seed: same structure as `MessageApproveIT` but message status `UNDER_REVIEW`
    - Test 1: `blockMessage_setsBlocked_resolvesReportsAndAlert()` — POST block with reason → `moderation_status = 'BLOCKED'`, `delivered_at` remains null, reports resolved, alert resolved, action log row with reason
    - Test 2: `blockMessageWithoutReason_returns400()` — POST block with empty body → 400

- [x] **Task 15 — Integration Test: `ConversationUnblockIT`** (AC: 6, 7)
  - [x] Create `platform/admin/api/ConversationUnblockIT.java` in TSID range `9030_xxx`:
    - `ADMIN_ID = 9030_000_100L`, `PARENT_ID = 9030_000_001L`, `PLAYER_ID = 9030_000_002L`, `COACH_USER_ID = 9030_000_010L`
    - Seed: admin, parent, player, coach, conversation with `status = 'BLOCKED'`, 2 messages (1 APPROVED, 1 BLOCKED), 1 `conversation_reports` row, 1 `admin_alerts` row
    - Test 1: `adminCanViewConversationDetail()` — GET /api/admin/conversations/{id} → both messages returned; BLOCKED message's content visible to admin; reports list present
    - Test 2: `unblockConversation_setsActive_resolvesReportAndAlert()` — POST unblock → `conversations.status = 'ACTIVE'`, `conversation_reports.resolved_at` set, `admin_alerts.status = 'RESOLVED'`, action log inserted
    - Test 3: `nonAdminCannotViewConversation_returns403()`

- [x] **Task 16 — Unit Test: `AdminAlertEventListenerTest`** (AC: 1)
  - [x] Create `platform/admin/service/AdminAlertEventListenerTest.java`:
    - `@ExtendWith(MockitoExtension.class)`, inject mock `AdminAlertRepository`
    - Default stub: `adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(any(), any(), any())` returns `Optional.empty()` (no existing alert) — stub in `@BeforeEach`
    - Test 1: `onMessageReported_insertsAlert()` — call `listener.onMessageReported(new MessageReportedEvent(...))` directly; verify `adminAlertRepository.save()` called with `type=MESSAGE_REPORT`, `referenceId=String.valueOf(messageId)`, `referenceType=MESSAGE`
    - Test 2: `onConversationReported_insertsAlert()` — verify `type=CONVERSATION_REPORT`, `referenceType=CONVERSATION`
    - Test 3: `onReviewFlagged_insertsAlert()` — verify `type=REVIEW_FLAG`, `referenceId=reviewId.toString()`, `referenceType=REVIEW`
    - Test 4: `onStrikeThreshold_insertsAlert()` — verify `type=STRIKE_THRESHOLD`, `referenceType=COACH`
    - Test 5: `duplicateEvent_skipsInsert()` — stub `findFirstByReferenceIdAndTypeAndStatus` returns `Optional.of(new AdminAlert())`; call `onMessageReported`; verify `save()` is **NOT** called
  - [x] This is the only verification of the fan-in path from domain events to admin alerts. The integration tests (Tasks 12–15) bypass this path via direct JdbcTemplate seeding.

## Dev Notes

### Module Package Structure

```
src/main/java/com/softropic/skillars/
  platform/admin/
    contract/ (NEW package)
      + AdminAlertType.java          (enum — NEW)
      + AdminAlertStatus.java        (enum — NEW)
      + AdminAlertReferenceType.java (enum — NEW)
      + AdminActionType.java         (enum — NEW)
      + AdminAlertDto.java           (record — NEW)
      + AdminQueueSummaryDto.java    (record — NEW)
      + AdminMessageDetailDto.java   (record — NEW)
      + AdminMessageContextDto.java  (record — NEW)
      + AdminMessageReportDto.java   (record — NEW)
      + AdminConversationDetailDto.java (record — NEW)
      + AdminConversationReportDto.java (record — NEW)
      + AdminBlockMessageRequest.java   (record — NEW)
    repo/
      + AdminAlert.java              (entity — NEW, UUID PK via @GeneratedValue)
      + AdminAlertRepository.java    (NEW)
      + AdminActionLog.java          (entity — NEW, UUID PK via @GeneratedValue)
      + AdminActionLogRepository.java (NEW)
      ~ (existing ReviewModerationLog, ReviewModerationLogRepository unchanged)
    service/
      + AdminAlertEventListener.java (@Component — NEW)
      + AdminQueueService.java       (@Service — NEW)
      + AdminMessageService.java     (@Service — NEW)
      + AdminConversationService.java (@Service — NEW)
      ~ AdminReviewService.java      (existing — NO changes)
    api/
      + AdminModerationResource.java (@RestController /api/admin — NEW)
      ~ AdminReviewResource.java     (existing — NO changes)
    config/
      ~ (unchanged)

  platform/messaging/
    repo/
      ~ MessageRepository.java      (add findBeforePivot, findAfterPivot, findAllForAdmin — MODIFIED)
      ~ MessageReportRepository.java (add findByMessageId, resolveAllOpenByMessageId — MODIFIED)
      ~ ConversationReportRepository.java (add findByConversationId, resolveAllOpenByConversationId — MODIFIED)

src/main/resources/db/migration/
  + V70__admin_alerts_action_log.sql (NEW)

src/test/java/com/softropic/skillars/
  platform/admin/api/
    + AdminQueueIT.java             (TSID range 9000_xxx — NEW)
    + MessageApproveIT.java         (TSID range 9010_xxx — NEW)
    + MessageBlockIT.java           (TSID range 9020_xxx — NEW)
    + ConversationUnblockIT.java    (TSID range 9030_xxx — NEW)
```

### CRITICAL: `AdminAlert` and `AdminActionLog` Do NOT Extend `BaseEntity`

`BaseEntity` uses TSID (Long) as primary key via `@Id @Tsid`. The `admin_alerts` and `admin_action_log` tables use UUID PKs (`DEFAULT gen_random_uuid()`). These entities use `@GeneratedValue(strategy = GenerationType.UUID)` on a UUID field instead. This is the same pattern as `ReviewFlag.java` (Story 9.3) which uses `@Id @GeneratedValue(strategy = GenerationType.UUID)`.

### CRITICAL: `referenceId` Is VARCHAR(36), Not UUID — Handling Both Long and UUID

The epic spec says `referenceId UUID NOT NULL`. In this codebase:
- `messageId` and `conversationId` are `Long` (TSID-based bigint in `main.user` TSID convention)
- `reviewId` and `coachId` are `UUID`

Store as `VARCHAR(36)` (`String` in Java). Conversion rules:
- Long IDs → `String.valueOf(longId)` — at most 19 digits, fits in VARCHAR(36)
- UUID IDs → `uuid.toString()` — 36 chars with hyphens, fits exactly in VARCHAR(36)

Reverse parsing in `AdminQueueService.buildSummary()`:
- `MESSAGE_REPORT`, `CONVERSATION_REPORT`: `Long.parseLong(alert.getReferenceId())`
- `REVIEW_FLAG`: `UUID.fromString(alert.getReferenceId())` — in try-catch
- `STRIKE_THRESHOLD`: UUID coachId (not used in summary computation)

### CRITICAL: `@Transactional` Placement in `AdminAlertEventListener`

The `@EventListener` methods must be `@Transactional` because the event is published from within a larger transaction (e.g., `ReviewFlagService.flag()` which is `@Transactional`). The listener participates in that same transaction by default when using `@EventListener` (non-transactional event type). Adding `@Transactional` ensures the alert INSERT is in the SAME transaction as the triggering event — if the outer transaction rolls back, the alert is not persisted.

**Do NOT use `@TransactionalEventListener`** — that fires AFTER the outer transaction commits, in a new transaction. Since these alerts are safety-critical, they must be atomic with the event that triggered them.

### CRITICAL: SSE Emit Must Use `TransactionSynchronizationManager.afterCommit()`

`AdminMessageService.approveMessage()` and `blockMessage()` are `@Transactional`. The SSE emit must happen AFTER the transaction commits (so the recipient's SSE client sees consistent DB state). Use:

```java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        // SSE emit here
    }
});
```

Same pattern as `ModerationResultApplier`. The lambda captures `message.getId()` and `message.getConversationId()` as effectively-final locals BEFORE the `registerSynchronization()` call.

### CRITICAL: `AgeMessagingPolicy` Import in Admin Service

`AgeMessagingPolicy` is in `platform.messaging.service` package (not `contract`). The admin service imports it cross-module. This is consistent with other cross-module patterns in this codebase (`AdminReviewService` imports from `platform.reviews.repo`, `platform.marketplace.repo`). The monolith does not enforce module boundaries at compile time.

If this cross-package import becomes problematic, extract `AgeMessagingPolicy` to `platform.messaging.contract` in a future refactor.

### CRITICAL: `resolveAllOpenByConversationId` and `resolveAllOpenByMessageId` — `@Modifying(clearAutomatically = true)`

Both `@Modifying` queries must use `clearAutomatically = true` to invalidate the JPA L1 cache after the bulk UPDATE. Without this, subsequent reads in the same transaction return stale (pre-update) entity state. Pattern established in `ReviewFlagRepository.resolveAllOpenFlags()` (Story 9.2/9.3 fix).

### CRITICAL: `AdminAlertRepository.findByTypeAndStatus` with Nullable Type

The JPQL `(:type IS NULL OR a.type = :type)` pattern requires `@Param("type") @Nullable AdminAlertType type`. Spring Data JPA evaluates `null` as the literal SQL NULL when this SpEL pattern is used. Passing `null` for the "ALL types" case is correct. Verify the query works with both null (all types) and a specific enum value.

### CRITICAL: Queue Alert `summary` Is a Best-Effort Field

The summary is built via N+1 reads in `buildSummary()`. For MVP admin queues this is acceptable. The entire queue response is wrapped in `@Transactional(readOnly = true)` to ensure consistent reads. If a referenced entity no longer exists (deleted message, resolved flag), return a fallback string — never throw.

### CRITICAL: `admin_alerts` Deduplication at Insert Time

`ReviewFlaggedEvent` fires once per flag, `MessageReportedEvent` fires once per user-report (Story 8.4 only prevents the same user reporting twice — different users each produce their own event), and `ConversationReportedEvent` fires once per reporter. All three can produce multiple events for the same entity, inflating the queue and leaving orphaned OPEN alerts after resolution (since `findFirstByReferenceIdAndTypeAndStatus` would resolve only one row).

`insertAlert()` deduplicates at insert time: it calls `findFirstByReferenceIdAndTypeAndStatus(referenceId, type, OPEN)` and skips the save if an OPEN alert already exists. This guarantees at most one OPEN alert per `(referenceId, type)` pair, which makes the `findFirst` resolve path correct. Integration tests must NOT seed two alerts with the same referenceId+type unless specifically testing the dedup path.

### CRITICAL: Context Window Before/After — Caller Must Reverse "Before" List

`findBeforePivot()` returns results ordered `DESC` (closest-to-pivot first, to respect the LIMIT). The display order for the context window is chronological (oldest first). After loading:
```java
List<Message> beforeRaw = messageRepository.findBeforePivot(convId, pivot, PageRequest.of(0, 5));
List<Message> before = new ArrayList<>(beforeRaw);
Collections.reverse(before);  // now oldest first for display
```

### CRITICAL: `admin_action_log` CHECK Constraint Includes All 8 Action Types

Include all 8 action types in the `CHECK (action_type IN (...))` constraint even though only 3 are used in this story. This avoids a future migration to alter the constraint. See Task 1 for the full list.

### References — Files to Read Before Implementing

- `ReviewFlag.java` — `platform/reviews/repo/ReviewFlag.java` — UUID PK pattern (`@GeneratedValue(strategy = GenerationType.UUID)`) to copy for AdminAlert and AdminActionLog
- `AdminReviewResource.java` — `platform/admin/api/AdminReviewResource.java` — `resolveAdminId()` helper pattern to copy exactly
- `AdminReviewService.java` — `platform/admin/service/AdminReviewService.java` — cross-module repo injection pattern
- `ModerationResultApplier.java` — `platform/messaging/service/ModerationResultApplier.java` — `TransactionSynchronizationManager.afterCommit()` SSE pattern + `resolveRecipient()` logic to replicate
- `MessagingEmitterRegistry.java` — `platform/messaging/service/MessagingEmitterRegistry.java` — `emit(userId, eventType, data)` method signature
- `AgeMessagingPolicy.java` — `platform/messaging/service/AgeMessagingPolicy.java` — `parentIsBlocked()` method
- `AccountSuspensionEventListener.java` — `platform/security/infrastructure/listener/AccountSuspensionEventListener.java` — `@EventListener @Transactional` pattern
- `ReviewFlagRepository.java` — `platform/reviews/repo/ReviewFlagRepository.java` — `resolveAllOpenFlags(@Modifying clearAutomatically=true)` bulk UPDATE pattern
- `AdminReviewQueueIT.java` — `platform/admin/api/AdminReviewQueueIT.java` — admin test setup: admin user + ROLE_ADMIN authority insertion pattern
- `MessageRepository.java` — `platform/messaging/repo/MessageRepository.java` — existing queries to not duplicate
- `ConversationReportRepository.java` — `platform/messaging/repo/ConversationReportRepository.java` — add `findByConversationId` and `resolveAllOpenByConversationId`

### Story 9.3 Findings That Impact This Story

- `@Modifying(clearAutomatically = true)` is mandatory on all bulk UPDATE queries — not just `@Modifying`. Apply to `resolveAllOpenByMessageId` and `resolveAllOpenByConversationId`.
- `Math.max(0, page)` guard for pagination — apply in `AdminQueueService.getAlerts()`.
- Admin user setup in tests requires 3 rows: `main.user` (skillars_role='ADMIN'), `main.authority` (name='ROLE_ADMIN'), `main.user_authority` linking them. Use `ON CONFLICT (name) DO NOTHING` for authority insert. Cleanup in `tearDown()` deletes from `user_authority`, `user`, `authority` (in that order to respect FK constraints).

### Epic Deviation Notes for Future Stories

- **`ReviewModerationResolvedEvent` missing `adminId`**: Epic 10 stories that wire review approval/block actions to the unified action log must read `adminId` from `review_moderation_log`, not from the event payload. (Documented in Story 9.3.)
- **`admin_alerts` deduplication**: Implemented at insert time in `insertAlert()` — one OPEN alert per `(referenceId, type)`. Story 10.2+ new event types must use the same pattern (call `insertAlert()` via the shared private method; do not save directly).
- **`resolvedBy` type**: Epic spec says UUID; this implementation uses BIGINT. Any Epic 10 consumer of `resolvedBy` must treat it as Long.
- **Direct messaging repo injection**: Epic dev notes specify a `MessageQueryService`/`ConversationQueryService` interface boundary. This story injects `MessageRepository`, `MessageReportRepository`, and `ConversationReportRepository` directly — consistent with `AdminReviewService` doing the same for review repos, and with the codebase's lack of compile-time module enforcement. No interface exists to call. Story 10.2+ should not assume the interface pattern is in place.

### Review Findings

- [x] [Review][Patch] Uncaught `IllegalArgumentException` → HTTP 500 on invalid `type` query param [AdminQueueService.java:38]
- [x] [Review][Patch] `insertAlert()` race condition — no unique partial index; concurrent events can bypass the check-then-save dedup and create duplicate OPEN `admin_alerts` rows, leaving orphaned alerts after resolution [AdminAlertEventListener.java:57, V70__admin_alerts_action_log.sql]
- [x] [Review][Patch] `unblockConversation()` missing idempotency guard — double-unblock writes a second `admin_action_log` row with no actual state change [AdminConversationService.java:68]
- [x] [Review][Defer] `buildSummary()` MESSAGE_REPORT returns null `summary` when `message.content` is null — downstream JSON serializes as `"summary": null` instead of a fallback string [AdminQueueService.java:59] — deferred, pre-existing data concern
- [x] [Review][Defer] `findBeforePivot` / `findAfterPivot` strict `<`/`>` on `createdAt` excludes messages sharing the exact pivot timestamp from the context window — low-probability edge, pre-existing timestamp comparison pattern [MessageRepository.java:33-37] — deferred, pre-existing limitation

#### Round 2 — Patch Review (2026-06-30)

- [x] [Review][Patch] `findAllForAdmin` has no pagination bound — add `Pageable pageable` parameter with a hard cap of 500 at the call site in `AdminConversationService`; caller passes `PageRequest.of(0, 500)` [MessageRepository.java:39, AdminConversationService.java]
- [x] [Review][Patch] `resolveAllOpenByMessageId` and `resolveAllOpenByConversationId` omit `r.status = RESOLVED` from their SET clause — resolved rows permanently show `status = 'OPEN'` alongside a non-null `resolvedAt`, splitting report state for any consumer filtering by `status` [MessageReportRepository.java, ConversationReportRepository.java]
- [x] [Review][Patch] `deleteExpiredMessages` retains bare `@Modifying` in same file where three new queries use `clearAutomatically = true` — Task 5 spec says "all `@Modifying` queries" [MessageRepository.java:42]
- [x] [Review][Defer] `findBeforePivot`/`findAfterPivot` return empty context when pivot message `createdAt` is null at JPA layer — DB `NOT NULL` prevents this in production; only affects in-memory test fixtures that bypass setCreatedAt() [MessageRepository.java:33-37] — deferred, DB constraint is the guard
- [x] [Review][Defer] Context window (`findBeforePivot`/`findAfterPivot`) excludes soft-deleted messages while `findAllForAdmin` includes them — chronological gap with no indication in admin message detail view; intentional spec asymmetry between views [MessageRepository.java:33-40] — deferred, intentional design, UX concern for service layer

## Dev Agent Record

### Agent Model Used
claude-sonnet-4-6

### Debug Log References

- JPA L1 cache eviction bug: `@Modifying(clearAutomatically = true)` without `flushAutomatically = true` cleared the entity manager cache before the pending `Message`/`Conversation` dirty check was flushed, causing `save()` changes to be silently dropped. Fix: added `flushAutomatically = true` to `resolveAllOpenByMessageId` and `resolveAllOpenByConversationId` — same pattern as `ReviewFlagRepository`.
- `approveMessage` 404: `resolveRecipient()` calls `agePolicyService.getMessagingPolicy(playerId)` which requires a `player_profiles` row for the player ID. `MessageApproveIT` setUp was missing the `main.player_profiles` insert; added with `ADULT` age tier so SSE goes to player directly.
- TSID test IDs: all TSID tables lack a DB-side DEFAULT sequence — IDs must be provided explicitly in test INSERT statements; `RETURNING id` returns null and was removed.

### Completion Notes List

- All 16 tasks implemented and all 17 tests pass (17 admin + full regression suite clean).
- Flyway V70 creates `admin` schema with `admin_alerts` (UUID PK, VARCHAR(36) referenceId for dual TSID/UUID) and `admin_action_log`.
- `AdminAlertEventListener` deduplicates on insert: at most one OPEN alert per `(referenceId, type)` pair, ensuring `findFirstByReferenceIdAndTypeAndStatus` on approve/block always resolves exactly one alert.
- SSE is registered via `TransactionSynchronizationManager.registerSynchronization().afterCommit()` — fires only after commit, no phantom delivery on rollback.
- Idempotency guards in `approveMessage` and `blockMessage`: early-return if status != UNDER_REVIEW prevents duplicate SSE and duplicate action log rows on retry.

### File List

- src/main/resources/db/migration/V70__admin_alerts_action_log.sql (NEW)
- src/main/java/com/softropic/skillars/platform/admin/contract/AdminAlertType.java (NEW)
- src/main/java/com/softropic/skillars/platform/admin/contract/AdminAlertStatus.java (NEW)
- src/main/java/com/softropic/skillars/platform/admin/contract/AdminAlertReferenceType.java (NEW)
- src/main/java/com/softropic/skillars/platform/admin/contract/AdminActionType.java (NEW)
- src/main/java/com/softropic/skillars/platform/admin/contract/AdminAlertDto.java (NEW)
- src/main/java/com/softropic/skillars/platform/admin/contract/AdminQueueSummaryDto.java (NEW)
- src/main/java/com/softropic/skillars/platform/admin/contract/AdminMessageDetailDto.java (NEW)
- src/main/java/com/softropic/skillars/platform/admin/contract/AdminMessageContextDto.java (NEW)
- src/main/java/com/softropic/skillars/platform/admin/contract/AdminMessageReportDto.java (NEW)
- src/main/java/com/softropic/skillars/platform/admin/contract/AdminConversationDetailDto.java (NEW)
- src/main/java/com/softropic/skillars/platform/admin/contract/AdminConversationReportDto.java (NEW)
- src/main/java/com/softropic/skillars/platform/admin/contract/AdminBlockMessageRequest.java (NEW)
- src/main/java/com/softropic/skillars/platform/admin/repo/AdminAlert.java (NEW)
- src/main/java/com/softropic/skillars/platform/admin/repo/AdminAlertRepository.java (NEW)
- src/main/java/com/softropic/skillars/platform/admin/repo/AdminActionLog.java (NEW)
- src/main/java/com/softropic/skillars/platform/admin/repo/AdminActionLogRepository.java (NEW)
- src/main/java/com/softropic/skillars/platform/admin/service/AdminAlertEventListener.java (NEW)
- src/main/java/com/softropic/skillars/platform/admin/service/AdminQueueService.java (NEW)
- src/main/java/com/softropic/skillars/platform/admin/service/AdminMessageService.java (NEW)
- src/main/java/com/softropic/skillars/platform/admin/service/AdminConversationService.java (NEW)
- src/main/java/com/softropic/skillars/platform/admin/api/AdminModerationResource.java (NEW)
- src/main/java/com/softropic/skillars/platform/messaging/repo/MessageRepository.java (MODIFIED)
- src/main/java/com/softropic/skillars/platform/messaging/repo/MessageReportRepository.java (MODIFIED)
- src/main/java/com/softropic/skillars/platform/messaging/repo/ConversationReportRepository.java (MODIFIED)
- src/test/java/com/softropic/skillars/platform/admin/api/AdminQueueIT.java (NEW)
- src/test/java/com/softropic/skillars/platform/admin/api/MessageApproveIT.java (NEW)
- src/test/java/com/softropic/skillars/platform/admin/api/MessageBlockIT.java (NEW)
- src/test/java/com/softropic/skillars/platform/admin/api/ConversationUnblockIT.java (NEW)
- src/test/java/com/softropic/skillars/platform/admin/service/AdminAlertEventListenerTest.java (NEW)
