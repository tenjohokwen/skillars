# Story 10.4: GDPR Data Tools & Account Deletion

Status: in-progress

## Story

As a platform user,
I want to export all my personal data and request deletion of my account,
so that the platform meets my rights under GDPR (Articles 15, 17, and 20).

## Acceptance Criteria

1. **Given** a user submits a data export request
   **When** `POST /api/gdpr/export` is called by an authenticated user
   **Then** a row is inserted into `admin.gdpr_requests` (`id UUID PK`, `user_id BIGINT NOT NULL`, `request_type VARCHAR(10) NOT NULL CHECK (request_type IN ('EXPORT','ERASURE'))`, `status VARCHAR(15) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED'))`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `completed_at TIMESTAMPTZ`, `download_url VARCHAR(2048)`, `expires_at TIMESTAMPTZ`)
   **And** `GdprExportRequestedEvent(requestId, userId)` is published — processing is asynchronous; the endpoint returns `202 Accepted` with `{ "requestId": "<uuid>" }` immediately
   **And** a user may have only one PENDING or PROCESSING export request at a time — `409` with `ErrorDto` code `gdpr.requestAlreadyPending`

2. **Given** a `GdprExportRequestedEvent` is consumed (AFTER_COMMIT of the original TX)
   **When** `GdprExportService.buildExport(UUID requestId, Long userId)` runs in a new transaction
   **Then** `gdpr_requests.status` is updated to `PROCESSING` immediately
   **And** a ZIP archive is assembled containing JSON files for all personal data:
   - `profile.json` — identity fields (`firstName`, `lastName`, `email`, `dateOfBirth`, `phone`)
   - `bookings.json` — all bookings where user is parent (`booking.parentId = userId`) or coach (`booking.coachId = coachProfileId` — requires loading coach profile UUID by `coachProfileRepository.findByUserId(userId)`) or player (`booking.playerId = userId`)
   - `payments.json` — all `parent_credit_ledger` entries where `parent_id = userId` (parents only); all `booking_payments` rows where `booking.parentId = userId`
   - `messages.json` — all non-deleted messages (`deletedAt IS NULL`) sent by the user (`senderId = userId`)
   - `reviews.json` — all `coach_reviews` where `authorId = userId`
   - `disputes.json` — all `admin.disputes` rows where `raised_by = userId`
   **And** the ZIP bytes are uploaded via `fileStorageService.storeBytes(zipBytes, storageKey, "application/zip", "attachment; filename=\"gdpr-export-" + requestId + ".zip\"")` where `storageKey = "gdpr/exports/" + requestId + ".zip"`
   **And** a presigned download URL is generated with a `gdpr.export.urlExpiryHours` (ConfigService default 48) hour TTL using a new `fileStorageService.signedDownloadUrl(storageKey, Duration.ofHours(expiryHours))` overload
   **And** `gdpr_requests.status = COMPLETED`, `download_url` and `expires_at = now() + urlExpiryHours` are set, `completed_at = now()`
   **And** a `GdprExportReadyEvent(requestId, userId)` is published (notification delivery — out of scope for this story)
   **And** if the build throws an exception: `gdpr_requests.status = FAILED`; the user may submit a new request

3. **Given** a user wants to download their export
   **When** `GET /api/gdpr/export/{requestId}` is called
   **Then** `@PreAuthorize` requires `IS_AUTHENTICATED`; service verifies `gdpr_requests.user_id == authenticatedUserId` — `403` with `ErrorDto` code `security.missingRights` on mismatch (throw `OperationNotAllowedException` with `SecurityError.MISSING_RIGHTS`)
   **And** if `status = COMPLETED AND expiresAt > now()`: return `302` redirect to the stored `downloadUrl`
   **And** if `status = COMPLETED AND expiresAt <= now()`: return `410 Gone` with `ErrorDto` code `gdpr.exportExpired`
   **And** if `status IN (PENDING, PROCESSING, FAILED)`: return `200 OK` with `{ "requestId": "...", "status": "..." }` — user can poll

4. **Given** a user submits an erasure request
   **When** `POST /api/gdpr/erasure` is called by an authenticated user
   **Then** if the user has an existing `PENDING` or `PROCESSING` export request, erasure cannot proceed — `409` with `ErrorDto` code `gdpr.exportInProgress`; the user must wait for the export to complete (or expire) before requesting erasure — check via `gdprRequestRepository.existsByUserIdAndRequestTypeAndStatusIn(userId, "EXPORT", List.of("PENDING","PROCESSING"))`
   **And** a coach cannot submit an erasure request while they have any bookings in `REQUESTED` or `ACCEPTED` state — `409` with `ErrorDto` code `gdpr.activeBookingsExist` — check by `bookingRepository.existsByCoachIdAndStatusIn(coachProfileId, List.of("REQUESTED","ACCEPTED"))` (requires new repo method; if user is not a coach, skip this check)
   **And** a row is inserted into `admin.gdpr_requests` (`request_type = ERASURE`, `status = PENDING`)
   **And** `GdprErasureRequestedEvent(requestId, userId)` is published and `202 Accepted` with `{ "requestId": "..." }` returned

5. **Given** a `GdprErasureRequestedEvent` is consumed (AFTER_COMMIT)
   **When** `GdprErasureService.erase(UUID requestId, Long userId)` runs in a new transaction
   **Then** `gdpr_requests.status = PROCESSING`
   **And** the following personal data fields are **anonymised** on `main.user` (preserves account shell for financial/audit records):
   - `login = 'deleted.' + userId + '@platform.invalid'` (must remain unique; email column is used as login)
   - `email = 'deleted.' + userId + '@platform.invalid'`
   - `firstName = 'Deleted'`
   - `lastName = 'User'`
   - `phone = null` (set `PhoneNumber` embedded object to null)
   - `dateOfBirth = null`
   - `avatarUrl = null` (the profile photo is PII — clear the URL; if the avatar is stored via `fileStorageService`, also call `fileStorageService.delete(storageKey)` to remove the physical file)
   - `activationKey = null`, `resetKey = null`
   - `activated = false` (prevents new JWT issuances; JWT filter checks `activated`)
   - `locked = true` (belt-and-suspenders: prevents login even if JWT filter check changes)
   - `persistentTokens.clear()` (revokes refresh tokens)
   **And** the following are anonymised on `coach_profiles` (if user is a coach):
   - `bio = null`, `location = null`
   **And** the following are **hard-deleted**:
   - All `messaging.messages` where `sender_id = userId` (ALL messages, including soft-deleted rows) — call `messageRepository.deleteAllBySenderId(userId)` (new native query; do NOT filter on `deleted_at IS NULL` — soft-deleted messages still contain authored content that must be physically removed under Article 17)
   - All `reviews.coach_reviews` where `author_id = userId AND moderation_status != 'APPROVED'` — call `coachReviewRepository.deleteByAuthorIdAndModerationStatusNot(userId, ReviewModerationStatus.APPROVED)` (new derived query)
   **And** APPROVED reviews are **anonymised** by setting `author_id = 0` (sentinel value representing "deleted user"; Long 0L is the platform's anonymous author ID — NOT a real user):
   - `coachReviewRepository.anonymiseApprovedReviews(userId)` — native update query: `UPDATE reviews.coach_reviews SET author_id = 0 WHERE author_id = :userId AND moderation_status = 'APPROVED'`
   **And** all Epic 5 player development records are **hard-deleted** where `player_id = userId`:
   - `playerTimelineRepository.deleteByPlayerId(userId)` — ALREADY EXISTS
   - `sluRepository.deleteAllByPlayerId(userId)` — add `@Modifying @Query("DELETE FROM PlayerSkillStat s WHERE s.playerId = :playerId")`
   - `sluWeeklySnapshotRepository.deleteAllByPlayerId(userId)` — add `@Modifying @Query("DELETE FROM PlayerSluWeeklySnapshot s WHERE s.id.playerId = :playerId")`
   - `neglectedSkillFlagRepository.deleteAllByPlayerId(userId)` — add derived delete method
   - `playerRadarBaselineRepository.deleteAllByIdPlayerId(userId)` — add `@Modifying @Query("DELETE FROM PlayerRadarBaseline b WHERE b.id.playerId = :playerId")`
   - `playerRadarCompositeRepository.deleteAllByIdPlayerId(userId)` — add `@Modifying @Query("DELETE FROM PlayerRadarComposite c WHERE c.id.playerId = :playerId")`
   - `radarAssessmentRepository.deleteAllByPlayerId(userId)` — add `@Modifying @Query("DELETE FROM RadarAssessmentEntry r WHERE r.playerId = :playerId")`
   - `performanceReportRepository.deleteAllByPlayerId(userId)` — add derived delete method
   - `homeworkCompletionRepository.deleteAllByPlayerId(userId)` — only if entity has `playerId` field (verify before adding; skip if not player-scoped)
   **And** all `admin.gdpr_requests` rows where `user_id = userId AND created_at < now() - INTERVAL '30 days'` are **hard-deleted** — keep recent requests
   **And** any S3 files from previously COMPLETED export requests are deleted: load all `admin.gdpr_requests` rows where `user_id = userId AND request_type = 'EXPORT' AND status = 'COMPLETED'`; for each, call `fileStorageService.delete("gdpr/exports/" + request.getId() + ".zip")` — this ensures no ZIP containing the user's PII remains in storage after erasure
   **And** `gdpr_requests.status = COMPLETED`, `completed_at = now()`
   **And** `UserErasedEvent(userId)` is published — consumed by `platform.security.infrastructure.listener.UserErasedEventListener` (new file) to set `activated = false`, `locked = true`, clear `persistentTokens` (session invalidation guard in case erasure and token invalidation are separated in future)
   **And** `AccountDeletionRequestedEvent(userId.toString(), userRole, linkedPlayerIds)` is published AFTER profile anonymisation — consumed by `platform.video.service.AccountDeletionCascadeListener` (Story 6.5) to trigger video purge
   - For coaches: `userId` in the event MUST be the coach profile UUID string (from `coachProfileRepository.findByUserId(userId).map(c -> c.getId().toString())`) — this is how video ownership is indexed in Bunny.net/video module
   - For parents: `userId = String.valueOf(userId)`, `linkedPlayerIds = playerProfileRepository.findByParentId(userId).stream().map(BaseEntity::getId).collect(...)` — wait, `PlayerProfile.id` is `Long` (BaseEntity uses BIGINT TSID), `linkedPlayerIds` is `List<Long>`
   - For players: `userId = String.valueOf(userId)`, `linkedPlayerIds = emptyList()`
   **And** the `AccountDeletionRequestedEvent` and `UserErasedEvent` MUST be published within the same transaction as the erasure (so they fire AFTER_COMMIT together)

6. **Given** an admin wants to track GDPR requests
   **When** `GET /api/admin/gdpr/requests?type=EXPORT|ERASURE&status=PENDING&page={n}` is called
   **Then** `@PreAuthorize` admin role required
   **And** all requests matching the filters are returned paginated with: `requestId`, `userId`, `requestType`, `status`, `createdAt`, `completedAt`
   **And** `type` and `status` params are optional; if omitted, all requests are returned

## Tasks / Subtasks

- [x] **Task 1 — Flyway V75: Create `admin.gdpr_requests` table + config** (AC: 1, 4)
  - [x] Create `src/main/resources/db/migration/V75__gdpr_requests_table.sql`:
    ```sql
    CREATE TABLE admin.gdpr_requests (
        id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
        user_id         BIGINT        NOT NULL,
        request_type    VARCHAR(10)   NOT NULL CHECK (request_type IN ('EXPORT','ERASURE')),
        status          VARCHAR(15)   NOT NULL DEFAULT 'PENDING'
                                      CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
        created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
        completed_at    TIMESTAMPTZ,
        download_url    VARCHAR(2048),
        expires_at      TIMESTAMPTZ
    );

    CREATE INDEX idx_gdpr_requests_user_id  ON admin.gdpr_requests(user_id);
    CREATE INDEX idx_gdpr_requests_status   ON admin.gdpr_requests(status, created_at);

    -- Partial unique index: at most one PENDING or PROCESSING request per user per type
    CREATE UNIQUE INDEX idx_gdpr_requests_unique_active
        ON admin.gdpr_requests(user_id, request_type)
        WHERE status IN ('PENDING','PROCESSING');

    -- Platform config: GDPR export URL TTL
    INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
    VALUES (nextval('main.platform_config_id_seq'), 'gdpr.export.urlExpiryHours', '48', 'LONG',
            'Hours before a GDPR export download URL expires', NOW())
    ON CONFLICT DO NOTHING;
    ```
  - [x] `user_id` is `BIGINT` (Long), NOT UUID — all user IDs in `main.user` are auto-increment BIGINT. The epic says UUID but this is incorrect for this codebase
  - [x] No FK on `user_id` — consistent with other admin tables; enforced at application level
  - [x] The unique partial index prevents the 409 duplicate check from being a TOCTOU race

- [x] **Task 2 — `GdprRequest` Entity and Repository** (AC: 1–6)
  - [x] Create `platform.admin.repo.GdprRequest`:
    ```java
    @Entity
    @Table(schema = "admin", name = "gdpr_requests")
    @Getter @Setter @NoArgsConstructor
    public class GdprRequest {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;

        @Column(name = "user_id", nullable = false)
        private Long userId;

        @Column(name = "request_type", nullable = false, length = 10)
        private String requestType;   // "EXPORT" or "ERASURE"

        @Column(nullable = false, length = 15)
        private String status = "PENDING";

        @Column(name = "created_at", nullable = false, updatable = false)
        private Instant createdAt = Instant.now();

        @Column(name = "completed_at")
        private Instant completedAt;

        @Column(name = "download_url", length = 2048)
        private String downloadUrl;

        @Column(name = "expires_at")
        private Instant expiresAt;
    }
    ```
  - [x] Create `platform.admin.repo.GdprRequestRepository`:
    ```java
    public interface GdprRequestRepository extends JpaRepository<GdprRequest, UUID> {

        boolean existsByUserIdAndRequestTypeAndStatusIn(Long userId, String requestType, List<String> statuses);

        Page<GdprRequest> findByRequestTypeAndStatus(String requestType, String status, Pageable pageable);

        Page<GdprRequest> findByRequestType(String requestType, Pageable pageable);

        Page<GdprRequest> findByStatus(String status, Pageable pageable);

        @Modifying
        @Query("DELETE FROM GdprRequest r WHERE r.userId = :userId AND r.createdAt < :cutoff")
        int deleteExpiredByUserId(@Param("userId") Long userId, @Param("cutoff") Instant cutoff);

        // Used by GdprErasureService to delete S3 export files before erasure completes
        List<GdprRequest> findByUserIdAndRequestTypeAndStatus(Long userId, String requestType, String status);
    }
    ```

- [x] **Task 3 — Domain Events** (AC: 1, 2, 4, 5)
  - [x] Create `platform.admin.contract.GdprExportRequestedEvent` (extends ApplicationEvent):
    ```java
    public class GdprExportRequestedEvent extends ApplicationEvent {
        private final UUID requestId;
        private final Long userId;
        // constructor + getters
    }
    ```
  - [x] Create `platform.admin.contract.GdprErasureRequestedEvent` (extends ApplicationEvent):
    ```java
    public class GdprErasureRequestedEvent extends ApplicationEvent {
        private final UUID requestId;
        private final Long userId;
        // constructor + getters
    }
    ```
  - [x] Create `platform.admin.contract.GdprExportReadyEvent` (extends ApplicationEvent):
    ```java
    public class GdprExportReadyEvent extends ApplicationEvent {
        private final UUID requestId;
        private final Long userId;
        // constructor + getters
    }
    ```
  - [x] Create `platform.security.contract.event.UserErasedEvent`:
    ```java
    public record UserErasedEvent(Long userId) {}
    ```
    Note: lives in `platform.security.contract.event` (alongside `AccountDeletionRequestedEvent`) since it's consumed by the security module

- [x] **Task 4 — `GdprError` ErrorCode enum** (AC: 1, 3, 4)
  - [x] Create `platform.admin.contract.GdprError`:
    ```java
    public enum GdprError implements ErrorCode {
        REQUEST_ALREADY_PENDING, ACTIVE_BOOKINGS_EXIST, EXPORT_EXPIRED, EXPORT_IN_PROGRESS;

        @Override
        public String getErrorCode() {
            return switch (this) {
                case REQUEST_ALREADY_PENDING -> "gdpr.requestAlreadyPending";
                case ACTIVE_BOOKINGS_EXIST  -> "gdpr.activeBookingsExist";
                case EXPORT_EXPIRED          -> "gdpr.exportExpired";
                case EXPORT_IN_PROGRESS      -> "gdpr.exportInProgress";
            };
        }
    }
    ```
  - [x] `REQUEST_ALREADY_PENDING`, `ACTIVE_BOOKINGS_EXIST`, and `EXPORT_IN_PROGRESS` → thrown as `ResponseStatusException(HttpStatus.CONFLICT, errorCode)` → 409
  - [x] `EXPORT_EXPIRED` → thrown as `ResponseStatusException(HttpStatus.GONE, errorCode)` → 410
  - [x] Import path: `com.softropic.skillars.infrastructure.exception.ErrorCode`

- [x] **Task 5 — Request/Response DTOs** (AC: 1–6)
  - [x] Create `platform.admin.contract.GdprRequestCreatedResponse`:
    ```java
    public record GdprRequestCreatedResponse(UUID requestId) {}
    ```
  - [x] Create `platform.admin.contract.GdprExportStatusResponse`:
    ```java
    public record GdprExportStatusResponse(UUID requestId, String status) {}
    ```
  - [x] Create `platform.admin.contract.AdminGdprRequestDto`:
    ```java
    public record AdminGdprRequestDto(
        UUID requestId, Long userId, String requestType, String status,
        Instant createdAt, Instant completedAt) {}
    ```

- [x] **Task 6 — `FileStorageService` overload for custom duration URLs** (AC: 2)
  - [x] Add method to `platform.filestorage.service.FileStorageService`:
    ```java
    public String signedDownloadUrl(String storageKey, Duration duration) {
        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(duration)
                .getObjectRequest(r -> r.bucket(storageProperties.getBucket()).key(storageKey))
                .build());
        return presigned.url().toString();
    }
    ```
  - [x] This overload bypasses the `FileStorageObject` table (same as the existing no-arg `storeBytes()` method) — GDPR exports are ephemeral and do not need to be tracked as `file_storage_objects`
  - [x] The `gdpr` platform module imports `FileStorageService` from `platform.filestorage.service` — NOT anything from `infrastructure.blobstore` directly (memory rule: never reference `infrastructure.blobstore` directly)

- [x] **Task 7 — `BookingRepository` additions for GDPR** (AC: 2, 4)
  - [x] Add to `platform.booking.repo.BookingRepository`:
    ```java
    List<Booking> findAllByCoachId(UUID coachId);      // for GDPR export: coach's bookings

    List<Booking> findAllByPlayerId(Long playerId);    // for GDPR export: player's bookings

    boolean existsByCoachIdAndStatusIn(UUID coachId, List<String> statuses);  // erasure guard
    ```
  - [x] `booking.coachId` is UUID (coach profile PK, NOT the user Long ID) — see Booking.java line 38
  - [x] For a coach user: call `coachProfileRepository.findByUserId(userId)` to get the coach profile UUID first, then query bookings by that UUID

- [x] **Task 8 — Repository additions for GDPR erasure** (AC: 5)
  - [x] **`MessageRepository`** — add:
    ```java
    @Modifying
    @Query(value = "DELETE FROM messaging.messages WHERE sender_id = :senderId", nativeQuery = true)
    int deleteAllBySenderId(@Param("senderId") Long senderId);
    ```
    Note: the query deletes ALL messages by this sender, including soft-deleted rows (`deleted_at IS NOT NULL`). Soft-deleted messages still contain the user's authored content in the DB and must be physically removed under Article 17. The export (AC2) only includes non-soft-deleted messages in `messages.json`; erasure must remove all.

  - [x] **`CoachReviewRepository`** — add:
    ```java
    // Deletes non-APPROVED reviews authored by the user
    @Modifying
    @Query("DELETE FROM CoachReview r WHERE r.authorId = :authorId AND r.moderationStatus <> :approved")
    int deleteNonApprovedByAuthorId(@Param("authorId") Long authorId,
                                    @Param("approved") ReviewModerationStatus approved);

    // Anonymises APPROVED reviews: sets authorId to 0 (sentinel for "deleted user")
    @Modifying
    @Query(value = "UPDATE reviews.coach_reviews SET author_id = 0 WHERE author_id = :authorId AND moderation_status = 'APPROVED'", nativeQuery = true)
    int anonymiseApprovedReviews(@Param("authorId") Long authorId);

    // Used for GDPR export
    List<CoachReview> findAllByAuthorId(Long authorId);
    ```

  - [x] **`SluRepository` (PlayerSkillStat)** — add:
    ```java
    @Modifying
    @Query("DELETE FROM PlayerSkillStat s WHERE s.playerId = :playerId")
    int deleteAllByPlayerId(@Param("playerId") Long playerId);
    ```

  - [x] **`SluWeeklySnapshotRepository`** — add:
    ```java
    @Modifying
    @Query("DELETE FROM PlayerSluWeeklySnapshot s WHERE s.id.playerId = :playerId")
    int deleteAllByPlayerId(@Param("playerId") Long playerId);
    ```

  - [x] **`RadarAssessmentRepository`** — add:
    ```java
    @Modifying
    @Query("DELETE FROM RadarAssessmentEntry r WHERE r.playerId = :playerId")
    int deleteAllByPlayerId(@Param("playerId") Long playerId);

    List<RadarAssessmentEntry> findByPlayerId(Long playerId);  // for GDPR export
    ```
    Note: `RadarAssessmentEntry.playerId` is `Long` — verify the field name in the entity before writing the query.

  - [x] **`NeglectedSkillFlagRepository`** — add:
    ```java
    @Modifying
    @Query("DELETE FROM NeglectedSkillFlag n WHERE n.playerId = :playerId")
    int deleteAllByPlayerId(@Param("playerId") Long playerId);
    ```

  - [x] **`PlayerRadarBaselineRepository`** — add:
    ```java
    @Modifying
    @Query("DELETE FROM PlayerRadarBaseline b WHERE b.id.playerId = :playerId")
    int deleteAllByPlayerId(@Param("playerId") Long playerId);
    ```

  - [x] **`PlayerRadarCompositeRepository`** — add:
    ```java
    @Modifying
    @Query("DELETE FROM PlayerRadarComposite c WHERE c.id.playerId = :playerId")
    int deleteAllByPlayerId(@Param("playerId") Long playerId);
    ```

  - [x] **`SluTargetRepository`** — verify entity has `playerId` field, then add:
    ```java
    @Modifying
    @Query("DELETE FROM PlayerSluTarget t WHERE t.id.playerId = :playerId")
    int deleteAllByPlayerId(@Param("playerId") Long playerId);
    ```

  - [x] **`PerformanceReportRepository`** — add:
    ```java
    @Modifying
    @Query("DELETE FROM PerformanceReport p WHERE p.playerId = :playerId")
    int deleteAllByPlayerId(@Param("playerId") Long playerId);
    ```

  - [x] **`PlayerTimelineRepository`** — `deleteByPlayerId(Long playerId)` **ALREADY EXISTS** — no change needed

  - [x] **`HomeworkCompletionRepository`** — `HomeworkCompletion.playerId` (`Long`) **CONFIRMED** — add:
    ```java
    @Modifying
    @Query("DELETE FROM HomeworkCompletion h WHERE h.playerId = :playerId")
    int deleteAllByPlayerId(@Param("playerId") Long playerId);
    ```

- [x] **Task 9 — `GdprRequestService`** (AC: 1, 3, 4, 6)
  - [x] Create `platform.admin.service.GdprRequestService` — `@Service @RequiredArgsConstructor @Slf4j`
  - [x] Inject: `GdprRequestRepository`, `BookingRepository`, `CoachProfileRepository`, `ApplicationEventPublisher`, `SecurityUtil`, `ConfigService`
  - [x] **`@Transactional` method `requestExport(Long userId)`** → `UUID requestId`:
    1. Check if `gdprRequestRepository.existsByUserIdAndRequestTypeAndStatusIn(userId, "EXPORT", List.of("PENDING","PROCESSING"))` — throw `ResponseStatusException(CONFLICT, "gdpr.requestAlreadyPending")` if true
    2. Create and save `GdprRequest(userId, "EXPORT", "PENDING")`
    3. Publish `GdprExportRequestedEvent(source, request.getId(), userId)`
    4. Return `request.getId()`
  - [x] **`@Transactional(readOnly = true)` method `getExportStatus(UUID requestId, Long userId)`** → `ResponseEntity`:
    1. Load request or throw `ResourceNotFoundException("GdprRequest", requestId.toString())`
    2. Verify `request.getUserId().equals(userId)` — throw `OperationNotAllowedException("Cannot access another user's GDPR request", SecurityError.MISSING_RIGHTS)` on mismatch
    3. If `COMPLETED && expires_at.isAfter(Instant.now())`: return `ResponseEntity.status(302).location(URI.create(request.getDownloadUrl())).build()`
    4. If `COMPLETED && !expires_at.isAfter(Instant.now())`: throw `ResponseStatusException(HttpStatus.GONE, "gdpr.exportExpired")` — using `!isAfter` (not `isBefore`) to correctly handle the exact-equality case where `expiresAt == now()`; `isBefore` would return false on equality, causing a COMPLETED + expired export to fall through to the 200 polling response
    5. Else: return `ResponseEntity.ok(new GdprExportStatusResponse(requestId, request.getStatus()))`
  - [x] **`@Transactional` method `requestErasure(Long userId)`** → `UUID requestId`:
    1. Check for in-flight export: `if (gdprRequestRepository.existsByUserIdAndRequestTypeAndStatusIn(userId, "EXPORT", List.of("PENDING","PROCESSING"))) throw new ResponseStatusException(CONFLICT, "gdpr.exportInProgress")`
    2. Determine if user is a coach: `coachProfileRepository.findByUserId(userId).ifPresent(cp -> { if (bookingRepository.existsByCoachIdAndStatusIn(cp.getId(), List.of("REQUESTED","ACCEPTED"))) throw new ResponseStatusException(CONFLICT, "gdpr.activeBookingsExist"); })`
    3. Create and save `GdprRequest(userId, "ERASURE", "PENDING")`
    4. Publish `GdprErasureRequestedEvent(source, request.getId(), userId)`
    5. Return `request.getId()`
  - [x] **`@Transactional(readOnly = true)` method `listRequests(String type, String status, Pageable pageable)`** → `Page<AdminGdprRequestDto>`:
    - Validate `type` against `Set.of("EXPORT","ERASURE")` if non-null; validate `status` against `Set.of("PENDING","PROCESSING","COMPLETED","FAILED")` if non-null — throw `ResponseStatusException(BAD_REQUEST, "gdpr.invalidFilterValue")` on mismatch; prevents silent empty-page responses for typos
    - Build query based on which params are non-null; delegate to appropriate repository method (use `findAll(pageable)` from JpaRepository when both are null)

- [x] **Task 10 — `GdprExportService`** (AC: 2)
  - [x] Create `platform.admin.service.GdprExportService` — `@Service @RequiredArgsConstructor @Slf4j`
  - [x] Inject: `GdprRequestRepository`, `UserRepository`, `BookingRepository`, `CoachProfileRepository`, `ParentCreditLedgerRepository`, `BookingPaymentRepository`, `MessageRepository`, `CoachReviewRepository`, `DisputeRepository`, `FileStorageService`, `ConfigService`, `ApplicationEventPublisher`, `ObjectMapper` (Jackson for JSON serialization)
  - [x] **Method `buildExport(UUID requestId, Long userId)`** — called by event listener; NOT `@Transactional` at the method level (the caller wraps in REQUIRES_NEW):
    1. Load and update request status to PROCESSING in a new TX
    2. Load user: `userRepository.findOneById(userId).orElseThrow(...)`
    3. Determine user role from `user.getSkillarsRole()`
    4. Load coach profile if coach: `coachProfileRepository.findByUserId(userId)`
    5. Build ZIP in memory using `java.util.zip.ZipOutputStream` with `ByteArrayOutputStream`:
       - `profile.json`: serialize `{firstName, lastName, email, dateOfBirth, phone}` via Jackson `ObjectMapper`
       - `bookings.json`: query bookings by parent/coach/player; serialize list
       - `payments.json`: load `ParentCreditLedger` entries and `BookingPayment` records (parent only or all roles); serialize
       - `messages.json`: load non-deleted messages by `senderId = userId`; serialize
       - `reviews.json`: load `CoachReview` by `authorId = userId`; serialize
       - `disputes.json`: load `Dispute` by `raisedBy = userId`; serialize
    6. Upload: `String storageKey = "gdpr/exports/" + requestId + ".zip"; fileStorageService.storeBytes(zipBytes, storageKey, "application/zip", "attachment; filename=\"gdpr-export-" + requestId + ".zip\"")`
    7. Generate URL: `long hours = configService.getLong("gdpr.export.urlExpiryHours", 48L); String url = fileStorageService.signedDownloadUrl(storageKey, Duration.ofHours(hours)); Instant expiresAt = Instant.now().plus(hours, ChronoUnit.HOURS);`
    8. Update request: `status = COMPLETED`, `downloadUrl = url`, `expiresAt = expiresAt`, `completedAt = Instant.now()`; save
    9. Publish `GdprExportReadyEvent(requestId, userId)`
    10. On any exception in step 3–9: catch, update `status = FAILED`, save, rethrow (or log and swallow — log at ERROR level)

- [x] **Task 11 — `GdprErasureService`** (AC: 5)
  - [x] Create `platform.admin.service.GdprErasureService` — `@Service @RequiredArgsConstructor @Slf4j`
  - [x] Inject: `GdprRequestRepository`, `UserRepository`, `CoachProfileRepository`, `PlayerProfileRepository` (find `main.player_profiles` by parentId), `BookingRepository`, `MessageRepository`, `CoachReviewRepository`, `DisputeRepository`, `SluRepository`, `SluWeeklySnapshotRepository`, `RadarAssessmentRepository`, `NeglectedSkillFlagRepository`, `PlayerRadarBaselineRepository`, `PlayerRadarCompositeRepository`, `SluTargetRepository`, `PerformanceReportRepository`, `PlayerTimelineRepository`, `GdprRequestRepository`, `ApplicationEventPublisher`
  - [x] **`@Transactional` method `erase(UUID requestId, Long userId)`**:
    1. Load request; update `status = PROCESSING`; save
    2. Load `User user = userRepository.findOneById(userId).orElseThrow(...)`
    3. Determine user role from `user.getSkillarsRole()` (SkillarsRole enum: COACH, PARENT, PLAYER, ADMIN)
    4. **Anonymise `main.user`**:
       ```java
       user.setLogin("deleted." + userId + "@platform.invalid");
       user.setEmail("deleted." + userId + "@platform.invalid");
       user.setFirstName("Deleted");
       user.setLastName("User");
       user.setPhone(null);
       user.setDateOfBirth(null);
       user.setAvatarUrl(null);   // PII — must be cleared under Article 17
       user.setActivationKey(null);
       user.setResetKey(null);
       user.setActivated(false);
       user.setLocked(true);
       user.getPersistentTokens().clear();
       userRepository.save(user);
       ```
    5. **Anonymise coach profile** (if COACH):
       ```java
       coachProfileRepository.findByUserId(userId).ifPresent(cp -> {
           cp.setBio(null);
           cp.setLocation(null);
           coachProfileRepository.save(cp);
       });
       ```
    6. **Hard-delete messages**: `messageRepository.deleteAllBySenderId(userId)` (native query)
    7. **Hard-delete non-APPROVED reviews**: `coachReviewRepository.deleteNonApprovedByAuthorId(userId, ReviewModerationStatus.APPROVED)`
    8. **Anonymise APPROVED reviews**: `coachReviewRepository.anonymiseApprovedReviews(userId)` — sets `author_id = 0`
    9. **Delete player development data** (if PLAYER or if parent with player profiles):
       - If `PLAYER`: delete all development records where `playerId = userId` using the repositories listed in Task 8
       - If `PARENT`: load `List<PlayerProfile> players = playerProfileRepository.findByParentId(userId)`; for each player, delete their development records
       - If `COACH`: coaches do not have `player_id` in development tables; skip
    10. **Delete old GDPR requests**: `gdprRequestRepository.deleteExpiredByUserId(userId, Instant.now().minus(30, ChronoUnit.DAYS))`
    10a. **Delete completed export S3 files**: load all `GdprRequest` rows where `userId = userId AND requestType = "EXPORT" AND status = "COMPLETED"`; for each, call `fileStorageService.delete("gdpr/exports/" + completedExport.getId() + ".zip")` — ensures no export ZIP containing this user's PII survives in S3 after erasure; log any deletion failure at WARN level but do not abort the erasure
    11. **Complete the request**: `request.setStatus("COMPLETED"); request.setCompletedAt(Instant.now()); gdprRequestRepository.save(request)`
    12. **Build `AccountDeletionRequestedEvent`**:
        - COACH: `String eventUserId = coachProfileRepository.findByUserId(userId).map(cp -> cp.getId().toString()).orElse(String.valueOf(userId))`; `AccountRole = COACH`; `linkedPlayerIds = emptyList()`
        - PARENT: `eventUserId = String.valueOf(userId)`; `AccountRole = PARENT`; `linkedPlayerIds = playerProfileRepository.findByParentId(userId).stream().map(p -> p.getId()).collect(toList())`
        - PLAYER: `eventUserId = String.valueOf(userId)`; `AccountRole = PLAYER`; `linkedPlayerIds = emptyList()`
    13. Publish `UserErasedEvent(userId)` and `AccountDeletionRequestedEvent(...)` in the same TX (both fire AFTER_COMMIT)
    14. **CRITICAL — FAILED status requires a separate transaction**: Do NOT try to `save(status=FAILED)` inside a catch block within this `@Transactional` method. When an exception causes the TX to roll back, any `repository.save()` in the same catch block is also rolled back — the FAILED status is never persisted and the request remains `PENDING` indefinitely (blocking future retry since the unique index sees PENDING).
        - Extract a helper method annotated `@Transactional(propagation = REQUIRES_NEW)` on `GdprErasureService` (or a separate `GdprRequestStatusUpdater` service to avoid circular injection):
          ```java
          @Transactional(propagation = Propagation.REQUIRES_NEW)
          public void markFailed(UUID requestId) {
              gdprRequestRepository.findById(requestId).ifPresent(r -> {
                  r.setStatus("FAILED");
                  r.setCompletedAt(null);
                  gdprRequestRepository.save(r);
              });
          }
          ```
        - The `erase()` method should NOT catch the exception internally. Instead, rethrow it; `GdprEventListener.onErasureRequested()` catches it and calls `markFailed(requestId)` in a new TX before logging:
          ```java
          // In GdprEventListener:
          } catch (Exception e) {
              gdprErasureService.markFailed(event.getRequestId());
              log.error("[GDPR_ERASURE_FAILED] requestId={} userId={}", event.getRequestId(), event.getUserId(), e);
          }
          ```
        - Inject `GdprRequestRepository` into `GdprEventListener` (or expose `markFailed` on `GdprErasureService`) to accomplish this

- [x] **Task 12 — `GdprEventListener`** (AC: 2, 5)
  - [x] Create `platform.admin.service.GdprEventListener` — `@Component @RequiredArgsConstructor @Slf4j`
  - [x] Inject: `GdprExportService`, `GdprErasureService` (which exposes `markFailed(UUID requestId)` with `REQUIRES_NEW` — see Task 11 step 14)
  - [x] Handler for export:
    ```java
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onExportRequested(GdprExportRequestedEvent event) {
        log.info("[GDPR_EXPORT] Processing export requestId={} userId={}", event.getRequestId(), event.getUserId());
        try {
            gdprExportService.buildExport(event.getRequestId(), event.getUserId());
        } catch (Exception e) {
            log.error("[GDPR_EXPORT_FAILED] requestId={} userId={}", event.getRequestId(), event.getUserId(), e);
        }
    }
    ```
  - [x] Handler for erasure:
    ```java
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onErasureRequested(GdprErasureRequestedEvent event) {
        log.info("[GDPR_ERASURE] Processing erasure requestId={} userId={}", event.getRequestId(), event.getUserId());
        try {
            gdprErasureService.erase(event.getRequestId(), event.getUserId());
        } catch (Exception e) {
            // CRITICAL: do NOT attempt to save status=FAILED inside GdprErasureService.erase() — that TX
            // has already rolled back and any save() within it is also rolled back. Call markFailed() here,
            // which opens a fresh REQUIRES_NEW TX, so the FAILED status is committed independently.
            gdprErasureService.markFailed(event.getRequestId());
            log.error("[GDPR_ERASURE_FAILED] requestId={} userId={}", event.getRequestId(), event.getUserId(), e);
        }
    }
    ```
  - [x] **CRITICAL**: Do NOT annotate the listener class with `@Transactional` — `@TransactionalEventListener(phase = AFTER_COMMIT)` runs without an active TX. The service methods (`buildExport`, `erase`) create their own transactions via `@Transactional`. This is the same pattern as `AccountDeletionCascadeListener` (Story 6.5)

- [x] **Task 13 — `UserErasedEventListener`** (AC: 5 — token invalidation)
  - [x] Create `platform.security.infrastructure.listener.UserErasedEventListener` — `@Component @RequiredArgsConstructor @Slf4j`
  - [x] Inject: `UserRepository`
  - [x] Handler:
    ```java
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserErased(UserErasedEvent event) {
        // Belt-and-suspenders: GdprErasureService already sets activated=false and locked=true in the same TX.
        // This listener is the formal contract for session invalidation in case erasure logic changes.
        log.info("[USER_ERASED] Confirming session invalidation for userId={}", event.userId());
        userRepository.findOneById(event.userId()).ifPresent(user -> {
            if (user.isActivated() || !user.isLocked()) {
                user.setActivated(false);
                user.setLocked(true);
                user.getPersistentTokens().clear();
                userRepository.save(user);
                log.warn("[USER_ERASED_REMEDIATION] userId={} was still active after erasure — corrected", event.userId());
            }
        });
    }
    ```
  - [x] This listener runs AFTER the erasure TX commits. It is idempotent — if the user is already `activated=false, locked=true`, the save is skipped

- [x] **Task 14 — `GdprResource`** (AC: 1, 3, 4)
  - [x] Create `platform.admin.api.GdprResource` — `@RestController @RequestMapping("/api/gdpr") @RequiredArgsConstructor @Observed(name = "gdpr")`
  - [x] Inject: `GdprRequestService`, `SecurityUtil`
  - [x] `POST /export` — `@PreAuthorize("isAuthenticated()")` — `@Observed(name = "gdpr.requestExport")` → calls `gdprRequestService.requestExport(resolveCurrentUserId())` → `202 Accepted` with `GdprRequestCreatedResponse(requestId)`
  - [x] `GET /export/{requestId}` — `@PreAuthorize("isAuthenticated()")` — `@Observed(name = "gdpr.exportStatus")` → delegates to `gdprRequestService.getExportStatus(requestId, resolveCurrentUserId())` → returns `ResponseEntity` directly (service handles 302/410/200)
  - [x] `POST /erasure` — `@PreAuthorize("isAuthenticated()")` — `@Observed(name = "gdpr.requestErasure")` → calls `gdprRequestService.requestErasure(resolveCurrentUserId())` → `202 Accepted` with `GdprRequestCreatedResponse(requestId)`
  - [x] `resolveCurrentUserId()` — same pattern as all other resources: `Long.parseLong(((Principal) securityUtil.getCurrentUser()).getBusinessId())`
  - [x] **CRITICAL**: `getExportStatus()` returns `ResponseEntity<?>` because the response type varies (302/410/200); the controller method must return `ResponseEntity<?>` not `ResponseEntity<GdprExportStatusResponse>`

- [x] **Task 15 — `AdminGdprResource`** (AC: 6)
  - [x] Create `platform.admin.api.AdminGdprResource` — `@RestController @RequestMapping("/api/admin/gdpr") @RequiredArgsConstructor @Observed(name = "admin.gdpr")`
  - [x] Inject: `GdprRequestService`
  - [x] `GET /requests` — `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` — `@Observed(name = "admin.gdpr.list")` — accepts `@RequestParam(required = false) String type`, `@RequestParam(required = false) String status`, `Pageable pageable` → calls `gdprRequestService.listRequests(type, status, pageable)` → `200 OK` with `Page<AdminGdprRequestDto>`

- [x] **Task 16 — Integration Tests** (AC: all)
  - [x] Create `platform.admin.api.GdprExportIT` — TSID range `9200_xxx`:
    - `PARENT_ID = 9200_000_001L`, `COACH_USER_ID = 9200_000_010L`, `ADMIN_ID = 9200_000_100L`
    - Seed: parent user, a parent booking, a message by parent, a review by parent (APPROVED + PENDING)
    - Test 1: `requestExport_returns202WithRequestId()` — POST `/api/gdpr/export` → 202, `requestId` UUID in response
    - Test 2: `requestExport_duplicate_returns409()` — POST twice while first is PENDING → 409 `gdpr.requestAlreadyPending`
    - Test 3: `pollStatus_pending_returns200WithStatus()` — GET `/api/gdpr/export/{requestId}` while PENDING → 200 `{"status":"PENDING"}`
    - Test 4: `requestExport_unauthenticated_returns401()` — POST without token → 401
    - Test 5: `getExport_otherUsersRequest_returns403()` — GET with a different user's token → 403
    - Test 6: `getExport_completed_notExpired_returns302()` — seed a COMPLETED request with `expires_at = now() + 24h`, `download_url` set; `@Autowired GdprExportService; gdprExportService.buildExport(requestId, userId)` or seed row directly; GET → 302 with `Location` header containing the download URL
    - Test 7: `getExport_completed_expired_returns410()` — seed a COMPLETED request with `expires_at = now() - 1h`; GET → 410 `gdpr.exportExpired`

  - [x] Create `platform.admin.api.GdprErasureIT` — TSID range `9210_xxx`:
    - `PARENT_ID = 9210_000_001L`, `PLAYER_ID = 9210_000_002L`, `COACH_USER_ID = 9210_000_010L`, `ADMIN_ID = 9210_000_100L`
    - Seed: parent user, player profile (parentId = PARENT_ID), approved review by parent, non-approved review by parent, message by parent, coach user + profile (no active bookings)
    - Test 1: `requestErasure_parent_returns202()` — POST `/api/gdpr/erasure` → 202, `requestId` UUID
    - Test 2: `requestErasure_unauthenticated_returns401()` — POST without token → 401
    - Test 3: `erase_anonymisesUserFields()` — after erasure event processes (use `@Transactional @Commit` or seed + direct service call): verify `main.user.login = 'deleted.{id}@platform.invalid'`, `activated = false`, `locked = true`
    - Test 4: `erase_deletesNonApprovedReviews_anonymisesApproved()` — verify approved review `author_id = 0`; non-approved review deleted
    - Test 5: `erase_deletesMessages()` — verify message no longer exists in DB
    - Test 6: `erase_retainsFinancialRecords()` — verify `parent_credit_ledger` rows still exist after erasure
    - Test 7: `adminCanListGdprRequests()` — GET `/api/admin/gdpr/requests` with admin token → 200 with `requestId`, `userId`, `requestType` in response; no PII beyond userId
    - Test 8: `erase_deactivatesUser_oldJwtRejected()` — mint a JWT for the user before erasure; call `gdprErasureService.erase(requestId, userId)` directly; then make any authenticated request with the old JWT → 401 (verifies `activated = false` / `locked = true` causes JWT rejection)

  - [x] Create `platform.admin.api.ActiveBookingsErasureBlockIT` — TSID range `9220_xxx`:
    - `COACH_USER_ID = 9220_000_010L`, `PARENT_ID = 9220_000_001L`
    - Seed: coach user + coach profile, parent user, booking with `status = 'REQUESTED'` where `coach_id = coachProfileId`
    - Test 1: `requestErasure_coachWithActiveBookings_returns409()` — POST `/api/gdpr/erasure` with coach token → 409 `gdpr.activeBookingsExist`
    - Test 2: `requestErasure_coachNoActiveBookings_returns202()` — seed coach with only COMPLETED booking → 202
    - Test 3: `requestErasure_withPendingExport_returns409()` — seed a PENDING export row for the user; POST `/api/gdpr/erasure` → 409 `gdpr.exportInProgress`

## Dev Notes

### Module & Package Structure

All new GDPR classes go in `platform.admin`:
```
src/main/java/com/softropic/skillars/
  platform/admin/
    contract/
      + GdprExportRequestedEvent.java     (event — NEW)
      + GdprErasureRequestedEvent.java    (event — NEW)
      + GdprExportReadyEvent.java         (event — NEW)
      + GdprError.java                    (ErrorCode enum — NEW)
      + GdprRequestCreatedResponse.java   (record — NEW)
      + GdprExportStatusResponse.java     (record — NEW)
      + AdminGdprRequestDto.java          (record — NEW)
    repo/
      + GdprRequest.java                  (@Entity — NEW)
      + GdprRequestRepository.java        (JpaRepository — NEW)
    service/
      + GdprRequestService.java           (@Service — NEW)
      + GdprExportService.java            (@Service — NEW)
      + GdprErasureService.java           (@Service — NEW)
      + GdprEventListener.java            (@Component — NEW)
    api/
      + GdprResource.java                 (@RestController /api/gdpr — NEW)
      + AdminGdprResource.java            (@RestController /api/admin/gdpr — NEW)

  platform/security/
    contract/event/
      + UserErasedEvent.java              (record — NEW)
    infrastructure/listener/
      + UserErasedEventListener.java      (@Component — NEW)
      ~ AccountSuspensionEventListener    (NO change)

  platform/filestorage/service/
    ~ FileStorageService.java             (MODIFIED — add signedDownloadUrl(String, Duration) overload)

  platform/booking/repo/
    ~ BookingRepository.java              (MODIFIED — add findAllByCoachId, findAllByPlayerId, existsByCoachIdAndStatusIn)

  platform/reviews/repo/
    ~ CoachReviewRepository.java          (MODIFIED — add deleteNonApprovedByAuthorId, anonymiseApprovedReviews, findAllByAuthorId)

  platform/messaging/repo/
    ~ MessageRepository.java             (MODIFIED — add deleteAllBySenderId)

  platform/development/repo/
    ~ SluRepository.java                 (MODIFIED — add deleteAllByPlayerId)
    ~ SluWeeklySnapshotRepository.java   (MODIFIED — add deleteAllByPlayerId)
    ~ RadarAssessmentRepository.java     (MODIFIED — add deleteAllByPlayerId, findByPlayerId)
    ~ NeglectedSkillFlagRepository.java  (MODIFIED — add deleteAllByPlayerId)
    ~ PlayerRadarBaselineRepository.java (MODIFIED — add deleteAllByPlayerId)
    ~ PlayerRadarCompositeRepository.java(MODIFIED — add deleteAllByPlayerId)
    ~ SluTargetRepository.java           (MODIFIED — add deleteAllByPlayerId, pending entity verification)
    ~ PerformanceReportRepository.java   (MODIFIED — add deleteAllByPlayerId)
    ~ PlayerTimelineRepository.java      (NO change — deleteByPlayerId ALREADY EXISTS)

src/main/resources/db/migration/
  + V75__gdpr_requests_table.sql         (NEW)

src/test/java/com/softropic/skillars/
  platform/admin/api/
    + GdprExportIT.java                  (TSID 9200_xxx — NEW)
    + GdprErasureIT.java                 (TSID 9210_xxx — NEW)
    + ActiveBookingsErasureBlockIT.java  (TSID 9220_xxx — NEW)
```

### CRITICAL: `gdpr_requests.user_id` MUST be `BIGINT` (Long), NOT UUID

The epic spec says `userId UUID NOT NULL` — **this is wrong for this codebase**. `UserRepository` extends `JpaRepository<User, Long>` — all `main.user.id` values are auto-increment BIGINT. Use `BIGINT NOT NULL` in the migration and `Long userId` in the entity. Same correction made in Story 10.3 (`disputes.raised_by`).

### CRITICAL: No `platform.identity` Module

The epic mentions `UserErasedEvent` consumed by `platform.identity`. This module does not exist. The consumer lives in `platform.security.infrastructure.listener.UserErasedEventListener` (new file). The `UserErasedEvent` record lives in `platform.security.contract.event` (alongside `AccountDeletionRequestedEvent`).

### CRITICAL: Coach Bookings Use `coachId = UUID` (Coach Profile PK), Not Long

`Booking.coachId` is `UUID` (the coach profile's PK, not the user's Long ID). For GDPR operations on a coach:
1. Load coach profile: `coachProfileRepository.findByUserId(userId)` — returns `CoachProfile` with `UUID id`
2. Use `coachProfile.getId()` to query bookings and videos

`CoachProfile.findByUserId(Long userId)` — verify this method exists in `CoachProfileRepository`. If not, add it. The `CoachProfile` entity has `@Column(name = "user_id", nullable = false, unique = true) private Long userId;` (confirmed from reading the entity).

### CRITICAL: APPROVED Review Anonymisation — `authorId` Cannot Hold UUID

The epic says APPROVED reviews are anonymised with `authorId = "platform anonymous UUID"`. But `CoachReview.authorId` is `Long` (`BIGINT NOT NULL`). A UUID cannot be stored in a Long column.

**Solution adopted**: Use `author_id = 0` (Long value 0) as the sentinel for "deleted user" in APPROVED reviews. Zero is never a valid TSID (TSIDs are always positive and start well above 0). The native query in `CoachReviewRepository.anonymiseApprovedReviews()` sets `author_id = 0`. No schema change needed — the column remains `BIGINT NOT NULL`.

**Do NOT** change `author_id` to nullable or UUID in this story. No Flyway migration change is required for `coach_reviews`.

The `gdpr.anonymousAuthorId` from the dev notes does NOT apply here since we use a Long sentinel. Do not add this to application.properties.

### CRITICAL: `FileStorageService.storeBytes()` Does Not Create `file_storage_objects` Records

The `storeBytes()` method puts bytes directly to S3 without creating a `FileStorageObject` entity. This is intentional for GDPR exports — the ZIP is ephemeral and does not need quota tracking or replication. The new `signedDownloadUrl(storageKey, Duration)` method similarly bypasses the `file_storage_objects` table.

**Do NOT** call `confirmUpload()` or create `FileStorageObject` records for GDPR exports.

### CRITICAL: ZipOutputStream Memory Usage

The ZIP is built entirely in memory (`ByteArrayOutputStream` → `ZipOutputStream`). For large exports (many bookings, messages), this could be megabytes. This is acceptable for MVP — a streaming approach would require a different upload API. Keep JSON serialization lean: only include fields listed in the ACs, not entire JPA entity graphs.

Use Jackson's `ObjectMapper` (auto-injected by Spring Boot) for JSON serialization. Configure dates as ISO-8601 strings (`objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)`).

### CRITICAL: `GdprErasureService.erase()` Transaction Scope

The erasure runs in a single `@Transactional` method. All DB changes (user anonymisation, message deletion, review deletion, development data deletion, request status update) happen in one TX. The `UserErasedEvent` and `AccountDeletionRequestedEvent` are published in this same TX and fire AFTER_COMMIT.

The listener `GdprEventListener.onErasureRequested()` is `@TransactionalEventListener(phase = AFTER_COMMIT)` — this means it runs outside any TX. When it calls `gdprErasureService.erase()` which is `@Transactional`, Spring creates a new TX for it. This is correct and mirrors the `AccountDeletionCascadeListener` pattern.

### CRITICAL: `@Modifying` Queries Must Be in a `@Transactional` Context

All `@Modifying @Query` methods called in `GdprErasureService.erase()` run within the outer `@Transactional` annotation. No need to add `@Transactional` on each repository method individually (Spring Data JPA handles this).

However, for composite-PK deletion queries (e.g., `PlayerSluWeeklySnapshot.id.playerId`), JPQL must use the embedded field path: `WHERE s.id.playerId = :playerId`. Test these queries in the integration test — JPQL composite key traversal is a common source of bugs.

### CRITICAL: Player Development Data Only Applies to PLAYER Role

Only delete development data (`player_skill_stats`, `radar_assessment_entries`, etc.) when the user is:
- A `PLAYER` directly
- OR a `PARENT` (their child players' development records must also be deleted)

For PARENT: iterate `playerProfileRepository.findByParentId(userId)` and delete development records for each player. `PlayerProfile.id` is `Long` (BIGINT TSID).

For COACH: no development table contains `playerId` for coaches. Skip.
For PLAYER: `playerId = userId` (the player's own Long ID).

### CRITICAL: Verify `HomeworkCompletion` Has a `playerId` Field Before Adding Delete Method

Read `HomeworkCompletion.java` before adding `deleteAllByPlayerId`. If it only has `sessionId` or tracks by `assignment_id`, the deletion scope is different (and may need to be joined differently). Do not add a method that doesn't match the entity structure.

### CRITICAL: `GdprEventListener` Must NOT Be `@Transactional`

The `@TransactionalEventListener(phase = AFTER_COMMIT)` runs after the producing TX commits. At this point there is no ambient transaction. If the listener class is annotated `@Transactional`, Spring may throw `TransactionRequiredException` depending on propagation. Match the pattern of `AccountDeletionCascadeListener` — no class-level `@Transactional` on the listener.

### CRITICAL: Unique Index Replaces Application-Level Duplicate Check

The partial unique index `idx_gdpr_requests_unique_active (user_id, request_type) WHERE status IN ('PENDING','PROCESSING')` in V75 prevents a race condition where two concurrent POSTs bypass the `existsByUserIdAndRequestTypeAndStatusIn()` check. The DB will throw a `DataIntegrityViolationException` on the second insert — catch this in the service and translate to `409`. OR rely purely on the index and remove the pre-check. The latter is cleaner; the index is the authoritative constraint.

### `CoachProfileRepository.findByUserId()` — ALREADY EXISTS

`CoachProfileRepository.findByUserId(Long userId)` returns `Optional<CoachProfile>` — **confirmed, no change needed**. The admin module already injects `CoachProfileRepository`. Use this method directly to resolve `coachId` UUID from `Long userId`.

### Reuse Existing `DisputeRepository.findByRaisedBy(Long raisedBy)` for GDPR Export

`DisputeRepository` already has `findByRaisedBy(Long raisedBy)` (added in Story 10.3). Inject `DisputeRepository` into `GdprExportService` and use this method directly.

### `PlayerProfileRepository.findByParentId()` — ALREADY EXISTS

`PlayerProfileRepository extends JpaRepository<PlayerProfile, Long>` — `PlayerProfile.id` is `Long` (TSID, from BaseEntity). `findByParentId(Long parentId)` — **confirmed, no change needed**. Inject `PlayerProfileRepository` directly into `GdprErasureService`.

### CRITICAL: Integration Tests for Async Event Processing

`@TransactionalEventListener(phase = AFTER_COMMIT)` events do NOT fire in the default `@Transactional @Rollback` Spring test context because the TX is rolled back, not committed. For IT tests that need to verify the async export/erasure result:

**Option A (Recommended)**: Call the service method directly in the test, bypassing the event listener:
```java
@Autowired GdprExportService gdprExportService;
// After seeding and creating the request row, call:
gdprExportService.buildExport(requestId, userId);
// Then assert gdpr_requests.status = COMPLETED, download_url is set, etc.
```

**Option B**: Use `@Commit` on the test method + `@Transactional` on the test class — forces commit so events fire. Requires manual cleanup in `@AfterEach`.

Option A is preferred: directly test the service method rather than the async plumbing. The event listener wiring is tested implicitly when the full app runs.

**Exception**: `ActiveBookingsErasureBlockIT` and `GdprExportIT` for the 409 and 202 response tests can use the normal `MockMvc` approach without needing to trigger the async phase.

### Admin User Test Setup (3-Row Pattern)

Same as Stories 10.1–10.3. Every IT class calling admin endpoints must seed:
```sql
INSERT INTO main.user (id, ..., skillars_role) VALUES (ADMIN_ID, ..., 'ADMIN');
INSERT INTO main.authority (name) VALUES ('ROLE_ADMIN') ON CONFLICT DO NOTHING;
INSERT INTO main.user_authority (user_id, authority_name) VALUES (ADMIN_ID, 'ROLE_ADMIN');
```
See `AdminQueueIT.java` for the exact seed pattern.

### Platform Config ID Sequencing

`platform_config` uses `nextval('main.platform_config_id_seq')`. The last used ID was `515` (from Story 10.3 disputes.submissionWindowDays). Use `516` for `gdpr.export.urlExpiryHours` in V75. Verify by checking V74 migration's last INSERT id.

### References — Files to Read Before Implementing

- `FileStorageService.java` — `platform/filestorage/service/` — `storeBytes()` + `signedDownloadUrl()` patterns; add the Duration overload here
- `AccountDeletionCascadeListener.java` — `platform/video/service/` — `@TransactionalEventListener(phase = AFTER_COMMIT)` without class-level `@Transactional` — exact pattern to follow for `GdprEventListener`
- `AccountDeletionRequestedEvent.java` — `platform/security/contract/event/` — payload shape and field types; `userId` is `String`, `userRole` is `AccountRole`, `linkedPlayerIds` is `List<Long>`
- `DisputeService.java` — `platform/admin/service/` — `@TransactionalEventListener` cross-module injection and event publishing patterns
- `AdminCoachEnforcementService.java` — `platform/admin/service/` — cross-module injection of `CoachProfileRepository` from marketplace module
- `AccountSuspensionEventListener.java` — `platform/security/infrastructure/listener/` — pattern for `UserErasedEventListener`; the GDPR listener is nearly identical
- `Booking.java` — `platform/booking/repo/` — `coachId` is `UUID`, `parentId` is `Long`, `playerId` is `Long`
- `CoachProfile.java` — `platform/marketplace/repo/` — `id` is `UUID`, `userId` is `Long` (FK to `main.user.id`)
- `CoachReview.java` — `platform/reviews/repo/` — `authorId` is `Long` (NOT UUID)
- `PlayerProfile.java` — `platform/security/repo/` — `id` is `Long` (BaseEntity), `parentId` is `Long`
- `PlayerTimelineRepository.java` — `platform/development/repo/` — has `deleteByPlayerId(Long)` — no change needed; use as reference for other repositories
- `SluRepository.java`, `RadarAssessmentRepository.java`, `NeglectedSkillFlagRepository.java` — read entity field names before writing JPQL delete queries (especially composite PKs)
- `AdminModerationResource.java` / `AdminDisputeResource.java` — `resolveAdminId()` pattern for `AdminGdprResource`
- `BookingRepository.java` — add `findAllByCoachId(UUID)`, `findAllByPlayerId(Long)`, `existsByCoachIdAndStatusIn(UUID, List<String>)` here
- `HomeworkCompletion.java` — `platform/session/repo/` — verify `playerId` field existence before adding delete method
- `V74__disputes_table.sql` — last migration; the platform_config INSERT used id 515; use 516 in V75
- `GdprRequestCreatedResponse` → pattern from `DisputeCreatedResponse` (record with one UUID field)
- `AdminQueueIT.java` — 3-row admin seed pattern for integration tests

### Story 10.3 Patterns to Reuse

- Event-driven async pattern: publish event in TX, consume `@TransactionalEventListener(AFTER_COMMIT)`
- `ResponseStatusException(HttpStatus.CONFLICT, errorCode)` for 409 (not OperationNotAllowedException)
- `ResponseStatusException(HttpStatus.GONE, errorCode)` for 410
- `OperationNotAllowedException(msg, SecurityError.MISSING_RIGHTS)` for 403 on ownership mismatch
- `@Observed(name = "...")` on each resource method

### CRITICAL: `GdprErasureService.erase()` — FAILED Status Must Use `REQUIRES_NEW`

`erase()` is `@Transactional`. If it throws, Spring rolls back the entire TX — including any `repository.save()` calls inside a catch block within that same method. The FAILED status update is silently discarded and the request stays `PENDING` indefinitely, blocking any future retry.

The correct pattern:
1. `erase()` does NOT catch exceptions — it lets them propagate.
2. `GdprErasureService` exposes a `@Transactional(propagation = REQUIRES_NEW) markFailed(UUID requestId)` method.
3. `GdprEventListener.onErasureRequested()` calls `markFailed()` in the catch block — this opens a new, independent TX that always commits regardless of the prior rollback.

`buildExport()` does NOT have this problem because it is not `@Transactional` — each repository call uses its own auto-TX, so the catch block's `save(FAILED)` commits correctly.

### CRITICAL: Concurrent EXPORT + ERASURE Must Be Blocked

The partial unique index allows one PENDING EXPORT and one PENDING ERASURE for the same user at the same time (index is per `request_type`). If both fire, the export can write a ZIP to S3 after the erasure completes, leaving PII in storage permanently. Two guards address this:

1. **AC4 pre-check**: `requestErasure()` rejects with `409 gdpr.exportInProgress` if the user has any PENDING or PROCESSING export.
2. **AC5 cleanup**: `erase()` deletes all S3 files from previously COMPLETED exports before marking the erasure COMPLETE.

### CRITICAL: `avatarUrl` Is PII — Must Be Cleared on Erasure

`main.user.avatarUrl` holds a URL to the user's profile photo. It is personal data under GDPR Article 17 and must be set to `null` during anonymisation. The epics list it explicitly. If the avatar is stored via `fileStorageService`, also delete the physical S3 object. Add `user.setAvatarUrl(null)` to the anonymisation block in `GdprErasureService.erase()` step 4.

### CRITICAL: `MessageRepository.deleteAllBySenderId` — No `deleted_at` Filter

The erasure must delete ALL messages sent by the user, including soft-deleted ones (`deleted_at IS NOT NULL`). Soft-deleted rows still contain the authored content in the database. The native query must be `DELETE FROM messaging.messages WHERE sender_id = :senderId` with no `deleted_at` clause. The method is named `deleteAllBySenderId` (not `deleteAllBySenderIdAndNotSoftDeleted`).

### CRITICAL: `expiresAt` Boundary — Use `!isAfter` Not `isBefore`

AC3 specifies `expiresAt <= now()` → 410. Implement as `!expires_at.isAfter(Instant.now())`, not `expires_at.isBefore(Instant.now())`. When `expiresAt == now()` exactly, `isBefore` returns `false` and the code would fall through to the 200 polling response instead of 410.

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

None.

### Completion Notes List

- Tasks 1–8: DB migration, entity, events, DTOs, error codes, and all repository extensions completed.
- `User.avatarUrl` does not exist on the entity — skipped this setter; documented in code.
- `CoachProfile.location` does not exist — nulled `city` and `district` instead.
- `FileStorageService.deleteRawBytes(String)` used for S3 deletion (story spec said `delete()`).
- `author_id = 0` (Long zero) used as APPROVED-review sentinel — TSID values are always positive.
- `FAILED` status in erasure handled via `@Transactional(REQUIRES_NEW) markFailed()` on `GdprErasureService` called from `GdprEventListener` catch block — NOT inside the rolled-back erasure TX.
- `MessageRepository.findNonDeletedBySenderId()` added for GDPR export (messages.json).
- `HomeworkCompletionRepository` is in `platform.session.repo`, not `platform.development.repo`.
- Integration tests use direct DB seeding for COMPLETED/expired export rows and `@MockitoBean FileStorageService` to avoid S3 calls.
- `SluTargetRepository` uses embedded PK path `t.id.playerId` per composite key pattern.

### File List

**New Files:**
- `src/main/resources/db/migration/V75__gdpr_requests_table.sql`
- `src/main/java/com/softropic/skillars/platform/admin/repo/GdprRequest.java`
- `src/main/java/com/softropic/skillars/platform/admin/repo/GdprRequestRepository.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/GdprExportRequestedEvent.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/GdprErasureRequestedEvent.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/GdprExportReadyEvent.java`
- `src/main/java/com/softropic/skillars/platform/security/contract/event/UserErasedEvent.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/GdprError.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/GdprRequestCreatedResponse.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/GdprExportStatusResponse.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/AdminGdprRequestDto.java`
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprRequestService.java`
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprExportService.java`
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java`
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprEventListener.java`
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/listener/UserErasedEventListener.java`
- `src/main/java/com/softropic/skillars/platform/admin/api/GdprResource.java`
- `src/main/java/com/softropic/skillars/platform/admin/api/AdminGdprResource.java`
- `src/test/java/com/softropic/skillars/platform/admin/api/GdprExportIT.java`
- `src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java`
- `src/test/java/com/softropic/skillars/platform/admin/api/ActiveBookingsErasureBlockIT.java`

**Modified Files:**
- `src/main/java/com/softropic/skillars/platform/filestorage/service/FileStorageService.java` — added `signedDownloadUrl(String, Duration)` overload
- `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java` — added `findAllByCoachId`, `findAllByPlayerId`, `existsByCoachIdAndStatusIn`
- `src/main/java/com/softropic/skillars/platform/messaging/repo/MessageRepository.java` — added `deleteAllBySenderId`, `findNonDeletedBySenderId`
- `src/main/java/com/softropic/skillars/platform/reviews/repo/CoachReviewRepository.java` — added `deleteNonApprovedByAuthorId`, `anonymiseApprovedReviews`, `findAllByAuthorId`
- `src/main/java/com/softropic/skillars/platform/development/repo/SluRepository.java` — added `deleteAllByPlayerId`
- `src/main/java/com/softropic/skillars/platform/development/repo/SluWeeklySnapshotRepository.java` — added `deleteAllByPlayerId`
- `src/main/java/com/softropic/skillars/platform/development/repo/SluTargetRepository.java` — added `deleteAllByPlayerId`
- `src/main/java/com/softropic/skillars/platform/development/repo/RadarAssessmentRepository.java` — added `deleteAllByPlayerId`, `findByPlayerId`
- `src/main/java/com/softropic/skillars/platform/development/repo/NeglectedSkillFlagRepository.java` — added `deleteAllByPlayerId`
- `src/main/java/com/softropic/skillars/platform/development/repo/PlayerRadarBaselineRepository.java` — added `deleteAllByPlayerId`
- `src/main/java/com/softropic/skillars/platform/development/repo/PlayerRadarCompositeRepository.java` — added `deleteAllByPlayerId`
- `src/main/java/com/softropic/skillars/platform/development/repo/PerformanceReportRepository.java` — added `deleteAllByPlayerId`
- `src/main/java/com/softropic/skillars/platform/session/repo/HomeworkCompletionRepository.java` — added `deleteAllByPlayerId`
- `src/main/java/com/softropic/skillars/platform/payment/repo/ParentCreditLedgerRepository.java` — added `findAllByParentId`

### Review Findings

- [x] [Review][Patch] `buildExport()` annotated `@Transactional` — FAILED status not committed on DB exceptions [GdprExportService.java:180]
- [x] [Review][Patch] NPE in `getExportStatus()` when `expiresAt` or `downloadUrl` is null on COMPLETED request [GdprRequestService.java:68]
- [x] [Review][Dismiss] `buildPayments()` queries `BookingPayment` by PK via `findAllById(bookingIds)` — false positive; `BookingPayment.bookingId` IS the entity PK (`@Id`), so `findAllById` is correct [GdprExportService.java]
- [x] [Review][Patch] Duplicate ERASURE request throws unhandled `DataIntegrityViolationException` → HTTP 500, not 409 — no pre-check exists; `requestErasure_duplicateErasure_returns409()` test will fail [GdprRequestService.java:78, GdprErasureIT.java:414]
- [x] [Review][Patch] ADMIN role falls through to `AccountRole.PLAYER` in `AccountDeletionRequestedEvent` payload [GdprErasureService.java:430]
- [x] [Review][Patch] Missing IT coverage: `erase_deletesNonApprovedReviews_anonymisesApproved()`, `erase_deletesMessages()`, `erase_retainsFinancialRecords()`, `erase_deactivatesUser_oldJwtRejected()` [GdprErasureIT.java]
- [x] [Review][Defer] DB connection held during S3 upload — resolved by Patch 1 (`@Transactional` removed) [GdprExportService.java:180]
- [x] [Review][Defer] `.distinct()` on Booking list may not deduplicate — JPA entity without overridden `equals()`/`hashCode()` [GdprExportService.java:250] — deferred, pre-existing JPA pattern concern
