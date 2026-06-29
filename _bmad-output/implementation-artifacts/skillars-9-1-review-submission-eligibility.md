# Story 9.1: Review Submission & Eligibility

Status: in-progress

## Story

As a parent or player,
I want to leave a star rating and optional written review for a coach I have trained with,
so that other parents and players can make informed decisions when choosing a coach.

## Acceptance Criteria

1. **Given** the `platform.reviews` module is initialised
   **When** the Flyway migration runs
   **Then** the following table exists:
   `coach_reviews` (reviewId UUID PK, coachId UUID NOT NULL, authorId BIGINT NOT NULL, authorRole VARCHAR(10) NOT NULL, rating SMALLINT NOT NULL CHECK(rating BETWEEN 1 AND 5), body VARCHAR(1000) nullable, moderationStatus VARCHAR(15) NOT NULL DEFAULT 'PENDING', coachResponseBody VARCHAR(500) nullable, coachResponseAt TIMESTAMPTZ nullable, createdAt TIMESTAMPTZ NOT NULL DEFAULT NOW(), lastModifiedAt TIMESTAMPTZ NOT NULL DEFAULT NOW())
   **And** a unique constraint exists on `coach_reviews(authorId, coachId)` — one review per author per coach, regardless of session count

2. **Given** a parent or player wants to submit a review for a coach
   **When** `POST /api/reviews/coaches/{coachId}` is called with `{ "rating": 4, "body": "..." }`
   **Then** a recency gate is checked: a COMPLETED booking exists for this (authorId, coachId) pair with `updatedAt >= now() - INTERVAL 'reviews.submissionWindowDays days'` (default 14) — `403` with `ErrorDto` code `reviews.noRecentSession` if not found
   **And** if a review already exists for this `(authorId, coachId)` pair: `409` with `ErrorDto` code `reviews.alreadySubmitted`
   **And** the review is created with `moderationStatus = PENDING` and `lastModifiedAt = now()`
   **And** `ReviewSubmittedEvent(reviewId, coachId, authorId, rating, body)` is published — Story 9.2 handles moderation
   **And** `201 Created` with the `reviewId`

3. **Given** a review body is provided
   **When** the submission is validated
   **Then** `body` must not exceed 1000 characters — `400` with `ErrorDto` code `reviews.bodyTooLong`
   **And** `body` may be null or empty — a rating-only review is valid

4. **Given** an author wants to update their existing review
   **When** `PATCH /api/reviews/{reviewId}` is called with `{ "rating": 5, "body": "updated text" }`
   **Then** `@PreAuthorize` verifies `coach_reviews.authorId == authenticatedUser.id` — `403` on mismatch
   **And** the recency gate is re-checked: a completed session with this coach within the last `reviews.submissionWindowDays` days must exist — `403` with `ErrorDto` code `reviews.noRecentSession` if not
   **And** an annual gate is checked: the review's `lastModifiedAt` must be BEFORE `now() - INTERVAL '365 days'` — `403` with `ErrorDto` code `reviews.updateTooSoon` if modified within the last 365 days
   **And** edits are only permitted while `moderationStatus IN (APPROVED, PENDING)` — `403` with `ErrorDto` code `reviews.editNotPermitted` if `BLOCKED` or `UNDER_REVIEW`
   **And** `moderationStatus` is reset to `PENDING`, `lastModifiedAt = now()`, and a new `ReviewSubmittedEvent` is published
   **And** `coachResponseBody` and `coachResponseAt` are cleared — the coach may submit a fresh response to the updated review

5. **Given** a coach wants to respond to an approved review
   **When** `POST /api/reviews/{reviewId}/response` is called with `{ "body": "..." }`
   **Then** `@PreAuthorize` verifies caller has COACH role; `coach_reviews.coachId` must match the authenticated coach's profile UUID — `403` on mismatch
   **And** a response is only permitted if `moderationStatus = APPROVED` — `403` with `ErrorDto` code `reviews.reviewNotApproved`
   **And** if `coachResponseBody IS NOT NULL`: `409` with `ErrorDto` code `reviews.responseAlreadySubmitted`
   **And** `coachResponseBody` must not exceed 500 characters — `400` with `ErrorDto` code `reviews.responseTooLong`
   **And** `coachResponseBody` and `coachResponseAt` are set; `moderationStatus` is unchanged

## Tasks / Subtasks

- [x] **Task 1 — Flyway Migration V67** (AC: 1)
  - [ ] Create `src/main/resources/db/migration/V67__reviews_module_init.sql`:
    ```sql
    CREATE SCHEMA IF NOT EXISTS reviews;

    CREATE TABLE reviews.coach_reviews (
        review_id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
        coach_id           UUID        NOT NULL,
        author_id          BIGINT      NOT NULL,
        author_role        VARCHAR(10) NOT NULL,
        rating             SMALLINT    NOT NULL CHECK (rating BETWEEN 1 AND 5),
        body               VARCHAR(1000),
        moderation_status  VARCHAR(15) NOT NULL DEFAULT 'PENDING',
        coach_response_body VARCHAR(500),
        coach_response_at  TIMESTAMPTZ,
        created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        last_modified_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        CONSTRAINT uq_coach_reviews_author_coach UNIQUE (author_id, coach_id)
    );

    CREATE INDEX idx_coach_reviews_coach_id ON reviews.coach_reviews(coach_id);
    CREATE INDEX idx_coach_reviews_author_id ON reviews.coach_reviews(author_id);

    -- Config: submission window (days a COMPLETED booking must be within for eligibility)
    INSERT INTO platform.platform_config (id, key, value, type, description)
    VALUES (38, 'reviews.submissionWindowDays', '14', 'LONG', 'Days within which a completed session must exist to submit/update a review');
    ```
  - [ ] **authorId is BIGINT (Long) — NOT UUID**: the epic spec says `authorId UUID` but the system identifies all users (parents, players) as Long TSID IDs (see `Booking.parentId`, `Booking.playerId`, all auth contracts in `platform.security`). Using UUID would break eligibility queries against `bookings.parent_id` and `bookings.player_id`. Use BIGINT.
  - [ ] Config row ID `38` — V20 ends at row 37 (`platform.message_retention_months`). Verify no collision before migrating.
  - [ ] No FK to `marketplace.coach_profiles(id)` — cross-schema FK creates tight coupling between modules. Store `coach_id UUID` as a plain column (same approach used in `booking.bookings.coach_id`).

- [x] **Task 2 — New Enums** (AC: 1, 2, 4, 5)
  - [ ] Create `platform/reviews/contract/AuthorRole.java`:
    ```java
    public enum AuthorRole {
        PARENT, PLAYER
    }
    ```
  - [ ] Create `platform/reviews/contract/ReviewModerationStatus.java`:
    ```java
    public enum ReviewModerationStatus {
        PENDING, APPROVED, BLOCKED, UNDER_REVIEW
    }
    ```

- [x] **Task 3 — JPA Entity `CoachReview`** (AC: 1)
  - [ ] Create `platform/reviews/repo/CoachReview.java`:
    - **Do NOT extend `BaseEntity`** — `CoachReview` uses UUID PK (not TSID Long). Follow the same pattern as `CoachProfile` and `Booking`.
    ```java
    @Entity
    @Table(schema = "reviews", name = "coach_reviews")
    @Getter @Setter @NoArgsConstructor
    public class CoachReview {

        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        @Column(name = "review_id", updatable = false, nullable = false)
        private UUID reviewId;

        @Column(name = "coach_id", nullable = false)
        private UUID coachId;

        @Column(name = "author_id", nullable = false)
        private Long authorId;

        @Enumerated(EnumType.STRING)
        @Column(name = "author_role", nullable = false, length = 10)
        private AuthorRole authorRole;

        @Column(nullable = false)
        private Integer rating;

        @Column(length = 1000)
        private String body;

        @Enumerated(EnumType.STRING)
        @Column(name = "moderation_status", nullable = false, length = 15)
        private ReviewModerationStatus moderationStatus = ReviewModerationStatus.PENDING;

        @Column(name = "coach_response_body", length = 500)
        private String coachResponseBody;

        @Column(name = "coach_response_at")
        private Instant coachResponseAt;

        @Column(name = "created_at", nullable = false, updatable = false)
        private Instant createdAt = Instant.now();

        @Column(name = "last_modified_at", nullable = false)
        private Instant lastModifiedAt = Instant.now();
    }
    ```

- [x] **Task 4 — Repository `CoachReviewRepository`** (AC: 2, 4, 5)
  - [ ] Create `platform/reviews/repo/CoachReviewRepository.java`:
    ```java
    public interface CoachReviewRepository extends JpaRepository<CoachReview, UUID> {
        boolean existsByAuthorIdAndCoachId(Long authorId, UUID coachId);
        Optional<CoachReview> findByReviewIdAndAuthorId(UUID reviewId, Long authorId);
    }
    ```

- [x] **Task 5 — Add Cross-Module Query to `BookingRepository`** (AC: 2, 4)
  - [ ] Add new query to `platform/booking/repo/BookingRepository.java`:
    ```java
    /**
     * Reviews eligibility check: a COMPLETED booking exists for this coach where
     * the authenticated user is either the parent or player and the booking was
     * completed (updatedAt) within the given window. updatedAt is the system proxy
     * for completedAt — status transitions set updatedAt via @PreUpdate.
     */
    @Query("""
        SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END
        FROM Booking b
        WHERE b.coachId = :coachId
          AND (b.parentId = :authorId OR b.playerId = :authorId)
          AND b.status = 'COMPLETED'
          AND b.updatedAt >= :windowStart
        """)
    boolean existsRecentCompletedBookingByAuthor(
        @Param("coachId") UUID coachId,
        @Param("authorId") Long authorId,
        @Param("windowStart") Instant windowStart);
    ```
  - [ ] **Why new query**: the existing `existsRecentCompletedBooking(coachId, playerId, windowStart)` only checks `playerId`. Reviews can be authored by a parent (who is not the player) — the new query checks BOTH `parentId` and `playerId` via OR.

- [x] **Task 6 — Domain Event `ReviewSubmittedEvent`** (AC: 2, 4)
  - [ ] Create `platform/reviews/contract/ReviewSubmittedEvent.java`:
    ```java
    public record ReviewSubmittedEvent(
        UUID reviewId,
        UUID coachId,
        Long authorId,
        int rating,
        String body) {}
    ```
  - [ ] Published via Spring `ApplicationEventPublisher` — consumed by `ReviewModerationService` in Story 9.2.

- [x] **Task 7 — DTO Records** (AC: 2, 3, 4, 5)
  - [ ] Create `platform/reviews/contract/SubmitReviewRequest.java`:
    ```java
    public record SubmitReviewRequest(
        @NotNull @Min(1) @Max(5) Integer rating,
        @Size(max = 1000) String body) {}
    ```
  - [ ] Create `platform/reviews/contract/SubmitReviewResponse.java`:
    ```java
    public record SubmitReviewResponse(UUID reviewId) {}
    ```
  - [ ] Create `platform/reviews/contract/CoachResponseRequest.java`:
    ```java
    public record CoachResponseRequest(
        @NotBlank @Size(max = 500) String body) {}
    ```
  - [ ] **Note on `body` validation**: AC3 says body may be null/empty (rating-only review). Use `@Size(max = 1000)` without `@NotBlank` on `SubmitReviewRequest.body`. For `CoachResponseRequest.body`, use `@NotBlank` (a coach response must have content).
  - [ ] **Note on 400 for `bodyTooLong`**: `@Size(max = 1000)` on the record field triggers a standard validation 400 via Spring's `MethodArgumentNotValidException`. The `ReviewApiAdvice` must handle this and return code `reviews.bodyTooLong`. Same for `reviews.responseTooLong`. See Task 9.

- [x] **Task 8 — Error Code Enum `ReviewErrorCode`** (AC: 2, 3, 4, 5)
  - [ ] Create `platform/reviews/contract/ReviewErrorCode.java`:
    ```java
    public enum ReviewErrorCode implements ErrorCode {
        NO_RECENT_SESSION("reviews.noRecentSession"),
        ALREADY_SUBMITTED("reviews.alreadySubmitted"),
        BODY_TOO_LONG("reviews.bodyTooLong"),
        RESPONSE_TOO_LONG("reviews.responseTooLong"),
        UPDATE_TOO_SOON("reviews.updateTooSoon"),
        EDIT_NOT_PERMITTED("reviews.editNotPermitted"),
        REVIEW_NOT_APPROVED("reviews.reviewNotApproved"),
        RESPONSE_ALREADY_SUBMITTED("reviews.responseAlreadySubmitted"),
        COACH_MISMATCH("reviews.coachMismatch"),
        AUTHOR_MISMATCH("reviews.authorMismatch");

        private final String code;
        ReviewErrorCode(String code) { this.code = code; }

        @Override
        public String getErrorCode() { return code; }
    }
    ```

- [x] **Task 9 — Exception Advice `ReviewApiAdvice`** (AC: 2, 3, 4, 5)
  - [ ] Create `platform/reviews/api/ReviewApiAdvice.java`:
    ```java
    @Slf4j
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @RestControllerAdvice(basePackages = "com.softropic.skillars.platform.reviews.api")
    public class ReviewApiAdvice {

        @ExceptionHandler(OperationNotAllowedException.class)
        public ResponseEntity<ErrorDto> handleOperationNotAllowed(OperationNotAllowedException ex) {
            String code = ex.getErrorCode() != null
                ? ex.getErrorCode().getErrorCode()
                : "reviews.error";
            ErrorDto body = new ErrorDto(code, new ErrorMsg(code, ex.getMessage()));
            HttpStatus status;
            if (ReviewErrorCode.ALREADY_SUBMITTED.getErrorCode().equals(code)
                    || ReviewErrorCode.RESPONSE_ALREADY_SUBMITTED.getErrorCode().equals(code)) {
                status = HttpStatus.CONFLICT;
            } else if (ReviewErrorCode.BODY_TOO_LONG.getErrorCode().equals(code)
                    || ReviewErrorCode.RESPONSE_TOO_LONG.getErrorCode().equals(code)) {
                status = HttpStatus.BAD_REQUEST;
            } else {
                status = HttpStatus.FORBIDDEN;
            }
            return ResponseEntity.status(status).body(body);
        }

        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<ErrorDto> handleValidation(MethodArgumentNotValidException ex) {
            // Map @Size violations on 'body'/'response body' fields to module error codes
            boolean isBody = ex.getBindingResult().getFieldErrors().stream()
                .anyMatch(fe -> "body".equals(fe.getField()));
            String code = isBody ? "reviews.bodyTooLong" : "reviews.responseTooLong";
            ErrorDto body = new ErrorDto(code, new ErrorMsg(code, "Field length exceeded"));
            return ResponseEntity.badRequest().body(body);
        }
    }
    ```
  - [ ] Import `org.springframework.web.bind.MethodArgumentNotValidException`.

- [x] **Task 10 — Service `ReviewSubmissionService`** (AC: 2, 3, 4, 5)
  - [ ] Create `platform/reviews/service/ReviewSubmissionService.java` with `@Service @RequiredArgsConstructor @Slf4j @Transactional`
  - [ ] Inject: `CoachReviewRepository`, `BookingRepository`, `CoachProfileRepository`, `ApplicationEventPublisher`, `ConfigService`
  - [ ] Implement `submitReview(UUID coachId, Long authorId, String authorRoleStr, Integer rating, String body)`:
    1. Verify the coachId exists: `coachProfileRepository.existsById(coachId)` → throw `ResourceNotFoundException("Coach", coachId.toString())` if not found
    2. Call `checkEligibility(coachId, authorId)` — shared with update path
    3. Check duplicate: `coachReviewRepository.existsByAuthorIdAndCoachId(authorId, coachId)` → throw `OperationNotAllowedException(..., ReviewErrorCode.ALREADY_SUBMITTED)` — also catch `DataIntegrityViolationException` on save (concurrent duplicate) and rethrow as same error
    4. Create `CoachReview`: set `coachId`, `authorId`, `authorRole = AuthorRole.valueOf(authorRoleStr)`, `rating`, `body`, `moderationStatus = PENDING`, `lastModifiedAt = Instant.now()`
    5. Save review; publish `ReviewSubmittedEvent(review.getReviewId(), coachId, authorId, rating, body)`
    6. Return `new SubmitReviewResponse(review.getReviewId())`
  - [ ] Implement `updateReview(UUID reviewId, Long authorId, Integer rating, String body)`:
    1. Load review: `coachReviewRepository.findByReviewIdAndAuthorId(reviewId, authorId)` → throw `OperationNotAllowedException(..., ReviewErrorCode.AUTHOR_MISMATCH)` if not found (403, not 404 — prevents leaking review existence to non-authors)
    2. Check annual gate: `review.getLastModifiedAt().isAfter(Instant.now().minus(365, ChronoUnit.DAYS))` → throw `OperationNotAllowedException(..., ReviewErrorCode.UPDATE_TOO_SOON)` if true
    3. Check moderation status: if `BLOCKED` or `UNDER_REVIEW` → throw `OperationNotAllowedException(..., ReviewErrorCode.EDIT_NOT_PERMITTED)`
    4. Call `checkEligibility(review.getCoachId(), authorId)` — re-check recency gate
    5. Apply update: `rating`, `body`, `moderationStatus = PENDING`, `lastModifiedAt = Instant.now()`, clear `coachResponseBody = null`, `coachResponseAt = null`
    6. Save; publish new `ReviewSubmittedEvent` with same reviewId
    7. Return `204 No Content` (no body — resource method returns `ResponseEntity<Void>`)
  - [ ] Implement `submitCoachResponse(UUID reviewId, UUID coachId, String responseBody)`:
    1. Load review by `reviewId`: `coachReviewRepository.findById(reviewId)` → throw `ResourceNotFoundException` if absent
    2. Verify coach ownership: `review.getCoachId().equals(coachId)` → throw `OperationNotAllowedException(..., ReviewErrorCode.COACH_MISMATCH)` on mismatch
    3. Check moderation: `review.getModerationStatus() != APPROVED` → throw `OperationNotAllowedException(..., ReviewErrorCode.REVIEW_NOT_APPROVED)`
    4. Check duplicate response: `review.getCoachResponseBody() != null` → throw `OperationNotAllowedException(..., ReviewErrorCode.RESPONSE_ALREADY_SUBMITTED)`
    5. Set `coachResponseBody = responseBody`, `coachResponseAt = Instant.now()`; save
  - [ ] Implement private `checkEligibility(UUID coachId, Long authorId)`:
    ```java
    private void checkEligibility(UUID coachId, Long authorId) {
        int windowDays = configService.getInt("reviews.submissionWindowDays", 14);
        Instant windowStart = Instant.now().minus(windowDays, ChronoUnit.DAYS);
        boolean eligible = bookingRepository.existsRecentCompletedBookingByAuthor(
            coachId, authorId, windowStart);
        if (!eligible) {
            throw new OperationNotAllowedException(
                "No completed session within submission window",
                ReviewErrorCode.NO_RECENT_SESSION);
        }
    }
    ```
  - [ ] **`body` length validation**: `@Size(max=1000)` on the DTO record triggers validation BEFORE service is called. Do NOT add an explicit length check in the service — that would be redundant.
  - [ ] **Annual gate direction**: The gate BLOCKS updates within 365 days of last modification. `isAfter(Instant.now().minus(365, DAYS))` = last modification is MORE RECENT than 365 days ago → block. Allow only if it's OLDER than 365 days.

- [x] **Task 11 — Resource `ReviewResource`** (AC: 2, 3, 4, 5)
  - [ ] Create `platform/reviews/api/ReviewResource.java`:
    ```java
    @RestController
    @RequestMapping("/api/reviews")
    @RequiredArgsConstructor
    @Observed(name = "reviews")
    public class ReviewResource {

        private final ReviewSubmissionService reviewSubmissionService;
        private final CoachProfileService coachProfileService;
        private final SecurityUtil securityUtil;  // or equivalent JWT principal helper used in other resources

        @PostMapping("/coaches/{coachId}")
        @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
        @Observed(name = "reviews.submit")
        public ResponseEntity<SubmitReviewResponse> submitReview(
                @PathVariable UUID coachId,
                @Valid @RequestBody SubmitReviewRequest request,
                Authentication auth) {
            Long userId = resolveUserId(auth);
            String role = resolveRole(auth);
            SubmitReviewResponse response = reviewSubmissionService.submitReview(
                coachId, userId, role, request.rating(), request.body());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }

        @PatchMapping("/{reviewId}")
        @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
        @Observed(name = "reviews.update")
        public ResponseEntity<Void> updateReview(
                @PathVariable UUID reviewId,
                @Valid @RequestBody SubmitReviewRequest request,
                Authentication auth) {
            Long userId = resolveUserId(auth);
            reviewSubmissionService.updateReview(reviewId, userId, request.rating(), request.body());
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/{reviewId}/response")
        @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
        @Observed(name = "reviews.coachResponse")
        public ResponseEntity<Void> submitCoachResponse(
                @PathVariable UUID reviewId,
                @Valid @RequestBody CoachResponseRequest request,
                Authentication auth) {
            Long userId = resolveUserId(auth);
            UUID coachId = coachProfileService.getCoachIdByUserId(userId);
            reviewSubmissionService.submitCoachResponse(reviewId, coachId, request.body());
            return ResponseEntity.noContent().build();
        }
    }
    ```
  - [ ] **`resolveUserId(auth)` and `resolveRole(auth)`**: replicate the same private helpers used in `MessagingResource`. Look at `MessagingResource.resolveUserId()` (line 208+) for the exact implementation — it reads the userId Long from the JWT principal. Copy the pattern verbatim.
  - [ ] **`coachProfileService.getCoachIdByUserId(userId)`**: already implemented in `CoachProfileService` (line 297) — returns `UUID` from `coachProfileRepository.findByUserId(userId)`. Inject `CoachProfileService` (not just the repo).
  - [ ] **`@PatchMapping` for update**: AC4 uses PATCH (partial update) — returns `204 No Content` per REST conventions. Consistent with the project rule in `project-context.md`: "Use `@PatchMapping` for partial updates; return `204 No Content` for body-less success."

- [x] **Task 12 — Module Config `ReviewsModuleConfig`** (AC: 1)
  - [ ] Create `platform/reviews/config/ReviewsModuleConfig.java` if any Spring beans need to be declared. For MVP this may be empty — skip if not needed.
  - [ ] **Do NOT create `@Configuration` boilerplate if the module has no beans beyond standard JPA/service scan**. The `@Service`/`@RestController` beans are auto-discovered. Only needed for custom `@Bean` factories.

- [x] **Task 13 — Integration Tests** (AC: 2, 3, 4, 5)
  - [ ] **`ReviewSubmissionIT.java`** (`@SpringBootTest @Testcontainers`, `platform/reviews/api/`):
    - TSID range: use `8000_xxx_xxx_xxx` — this block is currently unused by any other module's ITs
    - Setup: create coach user + coach profile (insert directly via JdbcTemplate using schema `marketplace.coach_profiles`), parent user with a COMPLETED booking within the last 7 days (update `updatedAt` to `now()-3 days` via JdbcTemplate after inserting)
    - `submitReview_validEligibility_returns201WithReviewId()`: POST → 201; verify `reviews.coach_reviews` row in DB
    - `submitReview_noRecentSession_returns403WithCode()`: set booking `updatedAt` to 30 days ago → 403 with `reviews.noRecentSession`
    - `submitReview_ratingOnly_returns201()`: null body → 201 (rating-only review is valid)
    - `submitReview_duplicate_returns409()`: submit twice → 409 with `reviews.alreadySubmitted`
    - `submitReview_bodyTooLong_returns400()`: body of 1001 chars → 400 with `reviews.bodyTooLong`
    - `submitReview_coachNotFound_returns404()`: random UUID coachId → 404

  - [ ] **`ReviewUpdateIT.java`** (`@SpringBootTest @Testcontainers`, `platform/reviews/api/`):
    - TSID range: `8010_xxx_xxx_xxx`
    - Setup: coach + parent + completed booking + existing review in `APPROVED` status (insert directly)
    - `updateReview_noRecentSession_returns403()`: set booking updatedAt to 30 days ago → 403 with `reviews.noRecentSession`
    - `updateReview_tooSoon_returns403()`: existing review `last_modified_at = now()-100 days` → 403 with `reviews.updateTooSoon`
    - `updateReview_afterOneYear_returns204()`: existing review `last_modified_at = now()-400 days` → 204; verify DB update + `moderation_status = 'PENDING'` + `coach_response_body = null`
    - `updateReview_blockedStatus_returns403()`: existing review `moderation_status = 'BLOCKED'` → 403 with `reviews.editNotPermitted`
    - `updateReview_wrongAuthor_returns403()`: different user tries to PATCH another user's review → 403

  - [ ] **`CoachResponseIT.java`** (`@SpringBootTest @Testcontainers`, `platform/reviews/api/`):
    - TSID range: `8020_xxx_xxx_xxx`
    - Setup: coach + player + approved review in DB
    - `submitResponse_approvedReview_returns204()`: coach responds → 204; verify `coach_response_body` and `coach_response_at` in DB
    - `submitResponse_notApproved_returns403()`: review with `moderation_status = 'PENDING'` → 403 with `reviews.reviewNotApproved`
    - `submitResponse_duplicate_returns409()`: respond twice → 409 with `reviews.responseAlreadySubmitted`
    - `submitResponse_coachMismatch_returns403()`: different coach tries to respond → 403 with `reviews.coachMismatch`
    - `submitResponse_tooLong_returns400()`: body of 501 chars → 400 with `reviews.responseTooLong`

## Dev Notes

### CRITICAL: `authorId` is Long (BIGINT), Not UUID

The epic spec says `authorId UUID NOT NULL` but this is inconsistent with the entire system. All user identifiers in this codebase are TSID Long IDs:
- `Booking.parentId` → `Long`
- `Booking.playerId` → `Long`
- `LoginResponse.userId` → `Long`
- `MessagingService.resolveUserId()` → returns `Long`
- `BookingRepository.existsRecentCompletedBooking(@Param("playerId") Long playerId, ...)`

Using `UUID` for `authorId` would make the eligibility cross-module join against `bookings.parent_id` (BIGINT) impossible without a type mismatch. Implement `authorId` as `BIGINT`/`Long` throughout. The `reviewId UUID PK` is correct — UUID is used for review entity identity (same as CoachProfile, Booking).

### CRITICAL: `CoachReview` Must NOT Extend `BaseEntity`

`BaseEntity` provides `@Tsid Long id`. The reviews module uses `UUID reviewId` as PK (consistent with CoachProfile and Booking which also use UUID PKs without BaseEntity). Add `@GeneratedValue(strategy = GenerationType.UUID)` on `reviewId`.

### CRITICAL: Eligibility Query Uses `updatedAt`, Not `completedAt`

The `Booking` entity has no `completedAt` column. The system uses `updatedAt` (managed by `@PreUpdate`) as the timestamp proxy for when a booking last changed state. For a `COMPLETED` booking, `updatedAt` reflects the completion time. `BookingRepository.existsRecentCompletedBooking()` already uses `b.updatedAt >= :windowStart` — the new `existsRecentCompletedBookingByAuthor` follows the same pattern. Do NOT add a `completedAt` column to `Booking`.

### CRITICAL: Annual Gate Logic Direction

The gate ALLOWS updates only when the review was last modified MORE than 365 days ago:
```java
// BLOCK if modified within 365 days (too soon)
if (review.getLastModifiedAt().isAfter(Instant.now().minus(365, ChronoUnit.DAYS))) {
    throw new OperationNotAllowedException("...", ReviewErrorCode.UPDATE_TOO_SOON);
}
```
This reads: "if `lastModifiedAt` is MORE RECENT than `now - 365 days`" → block. Allow only when the review is older than 365 days.

### CRITICAL: Cross-Module Dependency — Reviews → Booking

`ReviewSubmissionService` injects `BookingRepository` to perform the eligibility check. This is a read-only cross-module dependency (reviews reads booking data). No circular dependency risk — booking has no dependency on reviews. Document the import:
```java
import com.softropic.skillars.platform.booking.repo.BookingRepository;
```
This follows the established pattern where messaging reads `CoachProfileRepository` for party verification.

### CRITICAL: Cross-Module Dependency — Reviews → Marketplace

`ReviewSubmissionService` injects `CoachProfileRepository` to verify the coachId exists before allowing submission. `ReviewResource` injects `CoachProfileService` (not just the repo) for `getCoachIdByUserId()` on the coach response path.

### CRITICAL: Coach Response Authorization Pattern

The coach response endpoint (`POST /api/reviews/{reviewId}/response`) uses `@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)`. The service then verifies the authenticated coach's profile UUID matches `coach_reviews.coachId`. This is the same service-layer ownership check pattern used throughout the project (e.g., messaging party verification, file deletion ownership). Never skip the service-layer check even with role-level `@PreAuthorize`.

### Pattern: Duplicate Detection with Race Safety

In `submitReview()`, wrap the `coachReviewRepository.save(review)` in a try/catch for `DataIntegrityViolationException` and rethrow as `OperationNotAllowedException(..., ReviewErrorCode.ALREADY_SUBMITTED)`. The unique constraint on `(author_id, coach_id)` in the DB catches concurrent duplicate submissions cleanly — same pattern used in Story 8.4's `MessagingReportService`.

### Pattern: `resolveUserId()` and `resolveRole()`

Copy the private helper implementations from `MessagingResource.java` (line 208+). Do NOT reinvent JWT extraction — the pattern is already established and tested. The `resolveRole()` helper reads the role from the JWT authentication principal (COACH/PARENT/PLAYER string).

### Annual Gate: `ChronoUnit.DAYS` vs Calendar Month

Use `Instant.now().minus(365, ChronoUnit.DAYS)` for the 365-day annual gate — this is a flat day count, not calendar months. Unlike the retention scheduler (which used `minusMonths()` for exact month semantics), the annual gate in the AC is explicitly stated as "365 days" — `ChronoUnit.DAYS` is correct here.

### Module Package Structure

```
src/main/java/com/softropic/skillars/platform/reviews/
  contract/
    + AuthorRole.java                   (enum — NEW)
    + ReviewModerationStatus.java       (enum — NEW)
    + ReviewErrorCode.java              (enum implements ErrorCode — NEW)
    + SubmitReviewRequest.java          (record — NEW)
    + SubmitReviewResponse.java         (record — NEW)
    + CoachResponseRequest.java         (record — NEW)
    + ReviewSubmittedEvent.java         (record — NEW)
  repo/
    + CoachReview.java                  (entity — NEW, UUID PK, no BaseEntity)
    + CoachReviewRepository.java        (JPA repo — NEW)
  service/
    + ReviewSubmissionService.java      (@Service — NEW)
  api/
    + ReviewResource.java               (@RestController — NEW)
    + ReviewApiAdvice.java              (@RestControllerAdvice — NEW)
  config/                               (create only if @Bean declarations are needed)

src/main/java/com/softropic/skillars/platform/booking/repo/
  ~ BookingRepository.java              (add existsRecentCompletedBookingByAuthor query)

src/main/resources/db/migration/
  + V67__reviews_module_init.sql        (NEW — reviews schema, coach_reviews table, config row 38)

src/test/java/com/softropic/skillars/platform/reviews/api/
  + ReviewSubmissionIT.java             (NEW)
  + ReviewUpdateIT.java                 (NEW)
  + CoachResponseIT.java                (NEW)
```

### Story 9.2 Wiring Note

`ReviewSubmittedEvent` is published in this story and consumed by `ReviewModerationService` in Story 9.2 via `@EventListener`. Story 9.2 also adds `averageRating` and `reviewCount` columns to `marketplace.coach_profiles` via a new Flyway migration — do NOT add those columns in V67.

### References

- `BookingRepository.java` — `platform/booking/repo/BookingRepository.java` (add new eligibility query; `existsRecentCompletedBooking` pattern on line 93 for reference — extend it for parent+player OR check)
- `Booking.java` — `platform/booking/repo/Booking.java` (parentId Long, playerId Long, coachId UUID, status String, updatedAt Instant — confirms type layout)
- `CoachProfile.java` — `platform/marketplace/repo/CoachProfile.java` (UUID PK pattern without BaseEntity — replicate for CoachReview)
- `CoachProfileRepository.java` — `platform/marketplace/repo/CoachProfileRepository.java` (findByUserId, existsById methods)
- `CoachProfileService.java` — `platform/marketplace/service/CoachProfileService.java` (`getCoachIdByUserId(Long userId)` line 297)
- `MessagingResource.java` — `platform/messaging/api/MessagingResource.java` (`resolveUserId()` line 208+, `resolveRole()` — copy helpers verbatim)
- `MessagingApiAdvice.java` — `platform/messaging/api/MessagingApiAdvice.java` (advice pattern to replicate for ReviewApiAdvice)
- `MessagingErrorCode.java` — `platform/messaging/contract/MessagingErrorCode.java` (ErrorCode enum pattern to replicate)
- `SecurityConstants.java` — `infrastructure/security/SecurityConstants.java` (IS_AUTHENTICATED, HAS_COACH_ROLE constants)
- `ConfigService.java` — `platform/config/service/ConfigService.java` (`getInt(key, defaultValue)` method)
- `V20__platform_config.sql` — last row is row 37 (`platform.message_retention_months`); V67 inserts row 38 (`reviews.submissionWindowDays`)
- `project-context.md` — `_bmad-output/project-context.md` (PATCH → 204, @PreAuthorize on every endpoint, record DTOs, MapStruct mappers)
- `BaseEntity.java` — `infrastructure/persistence/BaseEntity.java` (TSID Long id — CoachReview MUST NOT extend this; use UUID PK instead)

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- Story spec said `platform.platform_config` with `type` column and config ID 38; actual table is `main.platform_config` with `value_type` column and last-used ID 512 (V64). Fixed: used ID 513 and correct schema/column.
- JdbcTemplate `?::uuid` cast syntax in WHERE clauses of UPDATE statements silently matched 0 rows; fixed by passing UUID objects directly (no cast needed with PostgreSQL JDBC 42.7.x).
- Test-body `jdbcTemplate.update()` calls needed wrapping in `transactionTemplate.execute()` to guarantee commit visibility before the subsequent HTTP request.
- `ReviewApiAdvice.handleValidation()` checked field name `"body"` to distinguish SubmitReviewRequest from CoachResponseRequest — both DTOs have a field named `body`, so the check was ambiguous. Fixed by using `ex.getBindingResult().getObjectName()` instead.

### Completion Notes List

- Implemented `platform.reviews` module: `reviews.coach_reviews` table (V67 migration), `CoachReview` JPA entity (UUID PK, no BaseEntity), `CoachReviewRepository`, cross-module JPQL query on `BookingRepository`, `ReviewSubmissionService` with submit/update/coachResponse flows, `ReviewResource` REST controller, `ReviewApiAdvice` exception handler, all contract types (enums, records, event).
- `authorId` implemented as BIGINT/Long throughout (not UUID) to allow eligibility joins against `booking.bookings.parent_id` and `player_id`.
- Unique constraint `(author_id, coach_id)` guards against duplicate reviews; race-safe via try/catch on `DataIntegrityViolationException`.
- Config-driven submission window via `ConfigService.getInt("reviews.submissionWindowDays", 14)`.
- `ReviewSubmittedEvent` published via `ApplicationEventPublisher` for consumption by Story 9.2's moderation service.
- 16 integration tests (ReviewSubmissionIT, ReviewUpdateIT, CoachResponseIT) — all green.

### File List

- `src/main/resources/db/migration/V67__reviews_module_init.sql` (NEW)
- `src/main/java/com/softropic/skillars/platform/reviews/contract/AuthorRole.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/reviews/contract/ReviewModerationStatus.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/reviews/contract/ReviewErrorCode.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/reviews/contract/ReviewSubmittedEvent.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/reviews/contract/SubmitReviewRequest.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/reviews/contract/SubmitReviewResponse.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/reviews/contract/CoachResponseRequest.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/reviews/repo/CoachReview.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/reviews/repo/CoachReviewRepository.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/reviews/api/ReviewResource.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/reviews/api/ReviewApiAdvice.java` (NEW)
- `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java` (MODIFIED — added existsRecentCompletedBookingByAuthor)
- `src/test/java/com/softropic/skillars/platform/reviews/api/ReviewSubmissionIT.java` (NEW)
- `src/test/java/com/softropic/skillars/platform/reviews/api/ReviewUpdateIT.java` (NEW)
- `src/test/java/com/softropic/skillars/platform/reviews/api/CoachResponseIT.java` (NEW)

### Review Findings

- [x] [Review][Patch] COACH role reaches `submitReview` → `AuthorRole.valueOf("COACH")` throws unhandled `IllegalArgumentException` → HTTP 500 [ReviewSubmissionService.java:52, ReviewResource.java:46]
- [x] [Review][Patch] `ReviewApiAdvice.handleValidation` maps all `SubmitReviewRequest` validation failures (including rating @NotNull/@Min/@Max) to `reviews.bodyTooLong`; similarly `@NotBlank` failure on `CoachResponseRequest.body` returns `reviews.responseTooLong` — both wrong codes for non-length violations [ReviewApiAdvice.java:41-45]
- [x] [Review][Patch] Race condition in `submitCoachResponse` — check-then-set on `coachResponseBody` is not atomic; no DB constraint or pessimistic lock prevents two concurrent requests both passing the null-guard and overwriting each other [ReviewSubmissionService.java:112-119]
- [x] [Review][Defer] `eventPublisher.publishEvent()` called inside `@Transactional` — if Story 9.2 registers a synchronous `@EventListener`, any exception it throws will roll back the review save; use `@TransactionalEventListener(phase = AFTER_COMMIT)` in Story 9.2 [ReviewSubmissionService.java:64-65, 95-96] — deferred, pre-existing
- [x] [Review][Defer] `SecurityUtil.getCurrentUser()` throws `IllegalStateException` (→ 500) for non-standard auth tokens — consistent with `MessagingResource` pattern, project-wide risk not introduced here [ReviewResource.java:78-86] — deferred, pre-existing

## Change Log

| Date | Version | Description | Author |
|------|---------|-------------|--------|
| 2026-06-29 | 1.0 | Initial implementation — reviews module scaffold, all 13 tasks complete, 16 integration tests green | claude-sonnet-4-6 |
