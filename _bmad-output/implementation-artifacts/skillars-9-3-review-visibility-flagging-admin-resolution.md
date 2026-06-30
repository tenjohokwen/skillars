# Story 9.3: Review Visibility, Flagging & Admin Resolution

Status: in-progress

## Story

As a parent or player,
I want to flag suspicious reviews and as a platform admin I want to be able to review flagged content and reinstate or remove reviews,
so that the review system remains trustworthy and coaches cannot be harmed by coordinated fake negative reviews.

## Acceptance Criteria

1. **Given** a user views coach search results
   **When** `GET /api/marketplace/coaches` returns coach cards
   **Then** each coach card includes `averageRating` (nullable) and `reviewCount` from `coach_profiles` — the pre-computed fields from Story 9.2; no additional query per coach is made
   **Note:** Epic spec specified `GET /api/coaches/search`; this story uses `GET /api/marketplace/coaches` — confirm the actual route with frontend routing before implementing.

2. **Given** a user views a coach's full profile
   **When** `GET /api/marketplace/coaches/{coachId}` is called
   **Then** the response includes the rating summary (`averageRating`, `reviewCount`) and the first page of APPROVED reviews inline — same data as `GET /api/reviews/coaches/{coachId}?page=0&sort=newest`; one round-trip for the full profile view
   **Note:** Epic spec specified `GET /api/coaches/{coachId}/profile`; this story uses `GET /api/marketplace/coaches/{coachId}` — confirm the actual route with frontend routing before implementing.

3. **Given** a user wants to flag a review as fake or abusive
   **When** `POST /api/reviews/{reviewId}/flag` is called with `{ "reason": "FAKE_REVIEW" | "OFFENSIVE_CONTENT" | "CONFLICT_OF_INTEREST" | "OTHER", "details": "..." }`
   **Then** `@PreAuthorize` authenticated user required; the flagger must not be the review author — `403` with `ErrorDto` code `reviews.cannotFlagOwnReview`
   **And** a row is inserted into `review_flags` (flagId UUID PK, reviewId UUID NOT NULL FK, flaggedBy BIGINT NOT NULL, reason VARCHAR NOT NULL, details VARCHAR(500) nullable, createdAt TIMESTAMPTZ)
   **And** if `review_flags` count for this `reviewId` reaches `reviews.autoHoldFlagThreshold` (from ConfigService, default 3): `coach_reviews.moderation_status` is set to `UNDER_REVIEW`, `coach_reviews.held_reason = FLAG_THRESHOLD`, and `CoachRatingService.recompute(coachId)` is called — the review is pulled from public view pending admin decision
   **And** a `ReviewFlaggedEvent(reviewId, coachId, flagCount, autoHeld)` is published for admin notification
   **And** a user may flag the same review only once — `409` with `ErrorDto` code `reviews.alreadyFlagged`
   **And** `201 Created` with `{ "flagId": "<UUID>" }`

4. **Given** an admin views the review moderation queue
   **When** `GET /api/admin/reviews/queue?status=UNDER_REVIEW&page={n}` is called by an authenticated admin
   **Then** all reviews with `moderation_status = UNDER_REVIEW` are returned, ordered by `updated_at ASC` (oldest held first)
   **And** each entry includes: `reviewId`, `coachId`, `coachName`, `authorRole`, `rating`, `body`, `createdAt`, `lastModifiedAt`, `heldReason` (GEMINI_UNCERTAIN / GEMINI_FAILURE / FLAG_THRESHOLD / MANUAL), `flagCount` (count of rows in `review_flags` for this review), `flags` (list of `{ reason, details, createdAt }` — `flaggedBy` is NOT included)

5. **Given** an admin reviews a held review
   **When** `POST /api/admin/reviews/{reviewId}/approve` is called
   **Then** `@PreAuthorize` admin role required
   **And** `coach_reviews.moderation_status` is set to `APPROVED`
   **And** all `review_flags` rows for this review are soft-resolved: `resolved_at = now()` is set on each flag row (flags are retained for audit; not deleted)
   **And** `CoachRatingService.recompute(coachId)` is called — the review re-enters the aggregate
   **And** a `ReviewModerationResolvedEvent(reviewId, coachId, previousStatus, APPROVED, adminId)` is published
   **And** `200 OK`

6. **Given** an admin determines a review violates platform rules
   **When** `POST /api/admin/reviews/{reviewId}/block` is called with `{ "reason": "..." }`
   **Then** `@PreAuthorize` admin role required
   **And** `coach_reviews.moderation_status` is set to `BLOCKED`
   **And** all `review_flags` rows for this review are soft-resolved (`resolved_at = now()`)
   **And** `CoachRatingService.recompute(coachId)` is called if the review was previously APPROVED
   **And** a `ReviewModerationResolvedEvent(reviewId, coachId, previousStatus, BLOCKED, adminId)` is published
   **And** `200 OK`

7. **Given** a review has been blocked by admin
   **When** the review author calls `GET /api/reviews/me/coaches/{coachId}`
   **Then** their own review is returned with `moderationStatus = BLOCKED` — the author can see their review was removed but is not told who made the decision or why

8. **Given** an authenticated author views their own review
   **When** `GET /api/reviews/me/coaches/{coachId}` is called
   **Then** the author's own review for that coach is returned including `moderationStatus`
   **And** `403` if no review exists for this `(authorId, coachId)` pair — not `404`

9. **Given** a coach's account is suspended or deleted
   **When** the coach's profile is deactivated
   **Then** `coach_profiles.average_rating` and `review_count` are retained in the DB — reviews are historical records and are not deleted on deactivation

## Tasks / Subtasks

- [x] **Task 1 — Flyway Migration V69** (AC: 3, 4, 5, 6)
  - [x] Create `src/main/resources/db/migration/V69__review_flags_moderation_log.sql`:
    ```sql
    -- New table: review_flags
    CREATE TABLE reviews.review_flags (
        flag_id    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
        review_id  UUID        NOT NULL REFERENCES reviews.coach_reviews(review_id),
        flagged_by BIGINT      NOT NULL,
        reason     VARCHAR(30) NOT NULL,
        details    VARCHAR(500),
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        resolved_at TIMESTAMPTZ
    );
    CREATE UNIQUE INDEX review_flags_unique_flagger ON reviews.review_flags(review_id, flagged_by);

    -- New table: review_moderation_log (audit trail of admin decisions)
    CREATE TABLE reviews.review_moderation_log (
        log_id     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
        review_id  UUID        NOT NULL,
        admin_id   BIGINT      NOT NULL,
        action     VARCHAR(10) NOT NULL CHECK (action IN ('APPROVED', 'BLOCKED')),
        reason     VARCHAR(500),
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    -- Add held_reason to coach_reviews (nullable — existing UNDER_REVIEW rows pre-date this column)
    ALTER TABLE reviews.coach_reviews
        ADD COLUMN held_reason VARCHAR(20);
    ```
  - [x] V68 is the last migration. V69 is next — no collision.
  - [x] `flagged_by` is `BIGINT`, not UUID — user IDs in `main.user` are `BIGINT`.
  - [x] `held_reason` is nullable; existing UNDER_REVIEW rows get NULL and display as null in the admin queue. New transitions (via Story 9.3 code) always set it.

- [x] **Task 2 — New Enums** (AC: 3, 4)
  - [x] Create `platform/reviews/contract/ReviewFlagReason.java`:
    ```java
    public enum ReviewFlagReason { FAKE_REVIEW, OFFENSIVE_CONTENT, CONFLICT_OF_INTEREST, OTHER }
    ```
  - [x] Create `platform/reviews/contract/HeldReason.java`:
    ```java
    public enum HeldReason { GEMINI_UNCERTAIN, GEMINI_FAILURE, FLAG_THRESHOLD, MANUAL }
    ```

- [x] **Task 3 — Add error codes to `ReviewErrorCode`** (AC: 3)
  - [x] In `platform/reviews/contract/ReviewErrorCode.java`, add:
    ```java
    CANNOT_FLAG_OWN_REVIEW("reviews.cannotFlagOwnReview"),
    CANNOT_FLAG_OWN_COACHED_REVIEW("reviews.cannotFlagOwnCoachedReview"),
    ALREADY_FLAGGED("reviews.alreadyFlagged"),
    REVIEW_NOT_FOUND("reviews.reviewNotFound");
    ```
  - [x] `REVIEW_NOT_FOUND` used when reviewer tries to flag a non-existent review.

- [x] **Task 4 — Update `CoachReview` entity** (AC: 4)
  - [x] In `platform/reviews/repo/CoachReview.java`, add:
    ```java
    @Enumerated(EnumType.STRING)
    @Column(name = "held_reason", length = 20)
    private HeldReason heldReason;   // null for pre-9.3 UNDER_REVIEW rows
    ```
  - [x] Import: `com.softropic.skillars.platform.reviews.contract.HeldReason`

- [x] **Task 5 — New `ReviewFlag` JPA Entity** (AC: 3)
  - [x] Create `platform/reviews/repo/ReviewFlag.java`:
    ```java
    @Entity
    @Table(schema = "reviews", name = "review_flags")
    @Getter @Setter @NoArgsConstructor
    public class ReviewFlag {
        @Id @GeneratedValue(strategy = GenerationType.UUID)
        @Column(name = "flag_id") private UUID flagId;

        @Column(name = "review_id", nullable = false) private UUID reviewId;
        @Column(name = "flagged_by", nullable = false) private Long flaggedBy;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 30) private ReviewFlagReason reason;
        @Column(length = 500) private String details;
        @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
        @Column(name = "resolved_at") private Instant resolvedAt;  // null = open
    }
    ```

- [x] **Task 6 — New `ReviewFlagRepository`** (AC: 3, 4, 5, 6)
  - [x] Create `platform/reviews/repo/ReviewFlagRepository.java`:
    ```java
    public interface ReviewFlagRepository extends JpaRepository<ReviewFlag, UUID> {
        boolean existsByReviewIdAndFlaggedBy(UUID reviewId, Long flaggedBy);
        long countByReviewIdAndResolvedAtIsNull(UUID reviewId);  // open flags only — used for auto-hold threshold
        long countByReviewId(UUID reviewId);                     // total flags — used for admin queue display and event payload
        List<ReviewFlag> findByReviewIdOrderByCreatedAtAsc(UUID reviewId);

        @Modifying
        @Query("UPDATE ReviewFlag f SET f.resolvedAt = :resolvedAt WHERE f.reviewId = :reviewId AND f.resolvedAt IS NULL")
        void resolveAllOpenFlags(@Param("reviewId") UUID reviewId, @Param("resolvedAt") Instant resolvedAt);
    }
    ```
  - [x] `resolveAllOpenFlags` resolves in a single bulk UPDATE — only sets `resolvedAt` for currently-open flags (NULL check). Caller must be `@Transactional`.

- [x] **Task 7 — Update `CoachReviewRepository`** (AC: 4)
  - [x] In `platform/reviews/repo/CoachReviewRepository.java`, add:
    ```java
    // For author self-view (GET /api/reviews/me/coaches/{coachId})
    Optional<CoachReview> findByAuthorIdAndCoachId(Long authorId, UUID coachId);

    // For admin queue — UNDER_REVIEW ordered by last-modified ASC (oldest-held-first)
    Page<CoachReview> findByModerationStatusOrderByLastModifiedAtAsc(
        ReviewModerationStatus status, Pageable pageable);
    ```

- [x] **Task 8 — New DTOs** (AC: 3, 4, 7, 8)
  - [x] Create `platform/reviews/contract/ReviewFlagRequest.java`:
    ```java
    public record ReviewFlagRequest(
        @NotNull ReviewFlagReason reason,
        @Size(max = 500) String details) {}
    ```
  - [x] Create `platform/reviews/contract/ReviewFlagResponse.java`:
    ```java
    public record ReviewFlagResponse(UUID flagId) {}
    ```
  - [x] Create `platform/reviews/contract/ReviewFlagDto.java` (admin view — no flaggedBy):
    ```java
    public record ReviewFlagDto(String reason, String details, Instant createdAt) {}
    ```
  - [x] Create `platform/reviews/contract/AdminReviewQueueEntryDto.java`:
    ```java
    public record AdminReviewQueueEntryDto(
        UUID reviewId, UUID coachId, String coachName, String authorRole,
        int rating, String body, Instant createdAt, Instant lastModifiedAt,
        String heldReason,     // nullable String (not enum) so null maps to null in JSON
        long flagCount, List<ReviewFlagDto> flags) {}
    ```
  - [x] Create `platform/reviews/contract/BlockReviewRequest.java`:
    ```java
    public record BlockReviewRequest(@NotBlank @Size(max = 500) String reason) {}
    ```
  - [x] Create `platform/reviews/contract/AuthorReviewDto.java` (author self-view):
    ```java
    public record AuthorReviewDto(
        UUID reviewId, String authorRole, int rating, String body,
        String moderationStatus,
        String coachResponseBody, Instant coachResponseAt,
        Instant createdAt, Instant lastModifiedAt) {}
    ```

- [x] **Task 9 — New `ReviewFlaggedEvent`** (AC: 3)
  - [x] Create `platform/reviews/contract/ReviewFlaggedEvent.java`:
    ```java
    public record ReviewFlaggedEvent(UUID reviewId, UUID coachId, long flagCount, boolean autoHeld) {}
    ```

- [x] **Task 10 — New `ReviewFlagService`** (AC: 3)
  - [x] Create `platform/reviews/service/ReviewFlagService.java`:
    - `@Service @Transactional`
    - Inject: `CoachReviewRepository`, `ReviewFlagRepository`, `CoachProfileRepository`, `CoachRatingService`, `ConfigService`, `ApplicationEventPublisher`
    - Method `flag(UUID reviewId, Long flaggedBy, ReviewFlagReason reason, String details)`:
      1. Load `CoachReview` via `findById(reviewId).orElseThrow(...)` → `403` with `REVIEW_NOT_FOUND` (not 404 — per AC: "403 if no review exists")
      2. Verify `!review.getAuthorId().equals(flaggedBy)` → throw `OperationNotAllowedException(CANNOT_FLAG_OWN_REVIEW)`
      3. Verify the flagger is not the coach being reviewed: `coachProfileRepository.findById(review.getCoachId())` → check that the profile's linked user ID field (confirm field name in `CoachProfile.java` — likely `userId` or `ownerId`) does not equal `flaggedBy` → throw `OperationNotAllowedException(CANNOT_FLAG_OWN_COACHED_REVIEW)`. This uses the same `reviews → marketplace` cross-module read pattern already established by `CoachRatingService`.
      4. Verify `!reviewFlagRepository.existsByReviewIdAndFlaggedBy(reviewId, flaggedBy)` → throw with `ALREADY_FLAGGED`
      5. Save new `ReviewFlag`
      6. Count open (unresolved) flags: `long openFlagCount = reviewFlagRepository.countByReviewIdAndResolvedAtIsNull(reviewId)`
      7. Read threshold: `int threshold = configService.getInt("reviews.autoHoldFlagThreshold", 3)`
      8. `boolean autoHeld = false;`
         If `openFlagCount >= threshold && review.getModerationStatus() == APPROVED`:
         — guard is `== APPROVED` (not `!= UNDER_REVIEW`): prevents (a) double auto-hold and (b) transitioning a PENDING_AI_REVIEW review out of the Gemini pipeline prematurely
         - `review.setModerationStatus(UNDER_REVIEW)`
         - `review.setHeldReason(HeldReason.FLAG_THRESHOLD)`
         - `reviewRepository.save(review)`
         - `coachRatingService.recompute(review.getCoachId())`
         - `autoHeld = true`
      9. `long totalFlagCount = reviewFlagRepository.countByReviewId(reviewId)` — total (including resolved) for event payload
      10. Publish `new ReviewFlaggedEvent(reviewId, review.getCoachId(), totalFlagCount, autoHeld)`
      11. Return `flagId` (UUID of saved `ReviewFlag`)
  - [x] **CRITICAL:** The entire method is `@Transactional` — the flag insert + optional status update must be atomic. If flag is saved but status update fails, the transaction rolls back the flag too (no partial state).
  - [x] **CRITICAL:** `reviews.autoHoldFlagThreshold` must be seeded in `platform_config`. Add to the `V69` migration or add an INSERT in the migration file:
    ```sql
    INSERT INTO main.platform_config (key, value, description, updated_at)
    VALUES ('reviews.autoHoldFlagThreshold', '3', 'Number of flags to auto-hold a review for admin review', NOW())
    ON CONFLICT (key) DO NOTHING;
    ```
    Check the `platform_config` table's schema for correct column names before writing this INSERT.

- [x] **Task 11 — Update `ReviewModerationService` to set `heldReason`** (AC: 4)
  - [x] In `platform/reviews/service/ReviewModerationService.java`, inside the `requiresNewTx.execute()` lambda, after `review.setModerationStatus(finalStatus)`, add:
    ```java
    if (finalStatus == ReviewModerationStatus.UNDER_REVIEW) {
        review.setHeldReason(geminiFailure ? HeldReason.GEMINI_FAILURE : HeldReason.GEMINI_UNCERTAIN);
    }
    ```
  - [ ] To distinguish GEMINI_FAILURE from GEMINI_UNCERTAIN, introduce a local flag before the Gemini call and set it in the catch block:
    ```java
    boolean geminiFailure = false;
    ModerationVerdict verdict;
    try {
        verdict = geminiClient.evaluate(promptTemplate + input);
    } catch (Exception e) {
        log.warn("...");
        verdict = ModerationVerdict.UNCERTAIN;
        geminiFailure = true;
    }
    ```
  - [x] The `geminiFailure` flag must be effectively-final. Refactor to use a local single-element array or a local record if needed to pass it into the lambda (Java lambda capture restriction).
  - [x] Only `requiresNewTx.execute()` touches the DB. The `finalStatus` and `geminiFailure` flag are computed outside the lambda (no transaction open during Gemini call — same as before).

- [x] **Task 12 — New `AdminReviewService`** (AC: 4, 5, 6)
  - [x] Create `platform/admin/service/AdminReviewService.java`:
    - `@Service @RequiredArgsConstructor @Slf4j`
    - Inject: `CoachReviewRepository`, `ReviewFlagRepository`, `CoachRatingService`, `ApplicationEventPublisher`
    - Method `getUnderReviewQueue(int page)` → `Page<AdminReviewQueueEntryDto>` — annotate with `@Transactional(readOnly = true)` to ensure all N+1 reads within the method see a consistent snapshot (a review approved concurrently mid-iteration would otherwise produce stale results):
      1. `Pageable p = PageRequest.of(Math.max(0, page), 20)` — sorted by `lastModifiedAt ASC` via the repository query
      2. Query: `reviewRepository.findByModerationStatusOrderByLastModifiedAtAsc(UNDER_REVIEW, p)`
      3. For each review, build `AdminReviewQueueEntryDto`:
         - `flagCount = reviewFlagRepository.countByReviewId(review.getReviewId())`
         - `flags = reviewFlagRepository.findByReviewIdOrderByCreatedAtAsc(review.getReviewId())` → map to `ReviewFlagDto(reason.name(), details, createdAt)`
         - `heldReason = review.getHeldReason() != null ? review.getHeldReason().name() : null`
         - `coachName`: NOT in `CoachReview` — read from `CoachProfileRepository.findById(coachId).map(CoachProfile::getDisplayName).orElse("Unknown")`
      4. Inject `CoachProfileRepository` for `coachName` lookup.
      **NOTE:** The per-review DB calls (flagCount, flags, coachProfile) introduce N+1 reads. For MVP with small admin queues, this is acceptable. If the queue grows large, optimize later with a JPQL projection query.

    - Method `approveReview(UUID reviewId, Long adminId)`:
      1. Load review (or throw `ResourceNotFoundException`)
      2. Capture `previousStatus = review.getModerationStatus()`
      3. `review.setModerationStatus(APPROVED)`
      4. `review.setHeldReason(null)` — clear stale held reason; `heldReason` is only meaningful while status is `UNDER_REVIEW`
      5. `review.setLastModifiedAt(Instant.now())`
      6. `reviewRepository.save(review)`
      7. `reviewFlagRepository.resolveAllOpenFlags(reviewId, Instant.now())`
      8. `coachRatingService.recompute(review.getCoachId())`
      9. Log to `review_moderation_log` (via JDBC or a JPA entity — see Task 14)
      10. Publish `new ReviewModerationResolvedEvent(reviewId, coachId, previousStatus, APPROVED)` — **EPIC CONTRACT DEVIATION:** The epic AC5 specifies `ReviewModerationResolvedEvent(reviewId, coachId, APPROVED, adminId)` with `adminId` as the 4th field. The existing event signature is `(reviewId, coachId, previousStatus, newStatus)` — `adminId` is absent. Admin identity is captured in `review_moderation_log`. Epic 10 stories that consume this event must retrieve `adminId` from `review_moderation_log` rather than the event payload.

    - Method `blockReview(UUID reviewId, String reason, Long adminId)`:
      1. Load review (or 404)
      2. Capture `previousStatus = review.getModerationStatus()`
      3. `review.setModerationStatus(BLOCKED)`
      4. `review.setHeldReason(null)` — clear stale held reason
      5. `review.setLastModifiedAt(Instant.now())`
      6. `reviewRepository.save(review)`
      7. `reviewFlagRepository.resolveAllOpenFlags(reviewId, Instant.now())`
      8. If `previousStatus == APPROVED`: `coachRatingService.recompute(review.getCoachId())`
      9. Log to audit table
      10. Publish `new ReviewModerationResolvedEvent(reviewId, coachId, previousStatus, BLOCKED)`

    - Both `approveReview` and `blockReview` must be `@Transactional`.
    - `getUnderReviewQueue` must be `@Transactional(readOnly = true)` — see method note above.

- [x] **Task 13 — `ReviewModerationLog` Entity and Repository** (AC: 5, 6)
  - [x] Create `platform/admin/repo/ReviewModerationLog.java` (JPA entity):
    ```java
    @Entity @Table(schema = "reviews", name = "review_moderation_log")
    @Getter @Setter @NoArgsConstructor
    public class ReviewModerationLog {
        @Id @GeneratedValue(strategy = GenerationType.UUID)
        @Column(name = "log_id") private UUID logId;
        @Column(name = "review_id", nullable = false) private UUID reviewId;
        @Column(name = "admin_id", nullable = false) private Long adminId;
        @Column(nullable = false, length = 10) private String action;
        @Column(length = 500) private String reason;
        @Column(name = "created_at", nullable = false, updatable = false)
        private Instant createdAt = Instant.now();
    }
    ```
  - [x] Create `platform/admin/repo/ReviewModerationLogRepository.java`:
    ```java
    public interface ReviewModerationLogRepository extends JpaRepository<ReviewModerationLog, UUID> {}
    ```
  - [x] Inject `ReviewModerationLogRepository` into `AdminReviewService`. Save a log entry in `approveReview()` and `blockReview()`.

- [x] **Task 14 — New `AdminReviewResource`** (AC: 4, 5, 6)
  - [x] Create `platform/admin/api/AdminReviewResource.java`:
    ```java
    @RestController
    @RequestMapping("/api/admin/reviews")
    @RequiredArgsConstructor
    @Observed(name = "admin.reviews")
    public class AdminReviewResource {

        private final AdminReviewService adminReviewService;
        private final SecurityUtil securityUtil;

        @GetMapping("/queue")
        @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
        @Observed(name = "admin.reviews.queue")
        public ResponseEntity<Page<AdminReviewQueueEntryDto>> getQueue(
                @RequestParam(defaultValue = "UNDER_REVIEW") String status,
                @RequestParam(defaultValue = "0") int page) {
            if (!"UNDER_REVIEW".equals(status)) {
                return ResponseEntity.badRequest().build();  // only UNDER_REVIEW supported; Epic 10 extends to other statuses
            }
            return ResponseEntity.ok(adminReviewService.getUnderReviewQueue(Math.max(0, page)));
        }

        @PostMapping("/{reviewId}/approve")
        @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
        @Observed(name = "admin.reviews.approve")
        public ResponseEntity<Void> approveReview(@PathVariable UUID reviewId) {
            Long adminId = resolveAdminId();
            adminReviewService.approveReview(reviewId, adminId);
            return ResponseEntity.ok().build();
        }

        @PostMapping("/{reviewId}/block")
        @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
        @Observed(name = "admin.reviews.block")
        public ResponseEntity<Void> blockReview(
                @PathVariable UUID reviewId,
                @Valid @RequestBody BlockReviewRequest request) {
            Long adminId = resolveAdminId();
            adminReviewService.blockReview(reviewId, request.reason(), adminId);
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
  - [x] Use same `resolveAdminId()` pattern as `ReviewResource.resolveUserId()`.
  - [x] Import `com.softropic.skillars.platform.reviews.contract.AdminReviewQueueEntryDto` and `BlockReviewRequest`.
  - [x] `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` = `"hasRole('ROLE_ADMIN') or hasRole('ROLE_LTD_ADMIN')"`.
  - [x] The `status` query param returns `400 Bad Request` for any value other than `UNDER_REVIEW` — prevents silent wrong results. Epic 10 extends this to support additional statuses.

- [x] **Task 15 — Add flagging endpoint to `ReviewResource`** (AC: 3)
  - [x] In `platform/reviews/api/ReviewResource.java`, inject `ReviewFlagService`:
    ```java
    private final ReviewFlagService reviewFlagService;
    ```
  - [ ] Add method:
    ```java
    @PostMapping("/{reviewId}/flag")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    @Observed(name = "reviews.flag")
    public ResponseEntity<ReviewFlagResponse> flagReview(
            @PathVariable UUID reviewId,
            @Valid @RequestBody ReviewFlagRequest request) {
        Long flaggedBy = resolveUserId();
        UUID flagId = reviewFlagService.flag(reviewId, flaggedBy, request.reason(), request.details());
        return ResponseEntity.status(HttpStatus.CREATED).body(new ReviewFlagResponse(flagId));
    }
    ```
  - [ ] Add author self-view method:
    ```java
    @GetMapping("/me/coaches/{coachId}")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    @Observed(name = "reviews.selfView")
    public ResponseEntity<AuthorReviewDto> getMyReviewForCoach(
            @PathVariable UUID coachId) {
        Long userId = resolveUserId();
        return ResponseEntity.ok(reviewQueryService.getAuthorReview(userId, coachId));
    }
    ```
  - [x] **URL mapping order:** Spring MVC resolves `/me/coaches/{coachId}` (literal `me` segment) separately from `/coaches/me` — no conflict. But keep the new `/me/coaches/{coachId}` method BEFORE `/coaches/{coachId}` in the class for readability.
  - [x] Update `ReviewApiAdvice` to handle `ALREADY_FLAGGED` → `409 CONFLICT`, `REVIEW_NOT_FOUND` → `403 FORBIDDEN` (per AC: flag of non-existent review returns 403, not 404), and `CANNOT_FLAG_OWN_COACHED_REVIEW` → `403 FORBIDDEN`. All thrown as `OperationNotAllowedException`. Add new cases in the `if`/`else if` block.

- [x] **Task 16 — Add `getAuthorReview` to `ReviewQueryService`** (AC: 7, 8)
  - [x] In `platform/reviews/service/ReviewQueryService.java`, add:
    ```java
    public AuthorReviewDto getAuthorReview(Long authorId, UUID coachId) {
        CoachReview r = reviewRepository.findByAuthorIdAndCoachId(authorId, coachId)
            .orElseThrow(() -> new OperationNotAllowedException(
                "No review found for this author-coach pair",
                ReviewErrorCode.REVIEW_NOT_FOUND));
        return new AuthorReviewDto(
            r.getReviewId(), r.getAuthorRole().name(), r.getRating(), r.getBody(),
            r.getModerationStatus().name(),
            r.getCoachResponseBody(), r.getCoachResponseAt(),
            r.getCreatedAt(), r.getLastModifiedAt());
    }
    ```
  - [x] Per AC: return `403` (not `404`) when the review doesn't exist. `ReviewApiAdvice` handles `OperationNotAllowedException` with `REVIEW_NOT_FOUND` → `403`.

- [x] **Task 17 — Profile endpoint enrichment** (AC: 2)
  - [x] Add `getFirstPageForCoach(UUID coachId)` to `ReviewQueryService`:
    ```java
    public ReviewListResponse getFirstPageForCoach(UUID coachId) {
        return listApprovedReviews(coachId, 0, "newest");
    }
    ```
  - [x] Update `CoachProfileDto` in `platform/marketplace/contract/CoachProfileDto.java`:
    - Add `List<ReviewDto> reviews` as the LAST record component.
    - Import: `com.softropic.skillars.platform.reviews.contract.ReviewDto`
    - This creates a `marketplace → reviews` dependency AT THE CONTRACT LEVEL. Because `reviews → marketplace` already exists (Story 9.2: `CoachRatingService` writes `CoachProfileRepository`), this creates a circular service-layer dependency if done in services. Instead, the enrichment is done AT THE CONTROLLER LEVEL:
  - [x] In `platform/marketplace/api/CoachMarketplaceResource.java`:
    - Inject `ReviewQueryService` from `platform.reviews.service` (controller-level cross-module reference — allowed, controllers aggregate resources).
    - Update `getCoachProfile()`:
      ```java
      @GetMapping("/{coachId}")
      @Observed(name = "marketplace.profile")
      @PreAuthorize(SecurityConstants.IS_PERMIT_ALL)
      public ResponseEntity<CoachProfileDto> getCoachProfile(@PathVariable UUID coachId) {
          CoachProfileDto base = coachProfileService.getPublicProfile(coachId);
          ReviewListResponse reviews = reviewQueryService.getFirstPageForCoach(coachId);
          return ResponseEntity.ok(new CoachProfileDto(
              base.id(), base.displayName(), base.photoUrl(), base.verificationTier(),
              base.capabilityBadges(), base.averageRating(), base.reviewCount(),
              base.bio(), base.languages(), base.city(), base.district(),
              base.specialties(), base.ageGroupsCoached(), base.perSessionPrice(),
              base.currency(), base.sessionPacks(), base.available(),
              base.reliabilityStrikeCount(), base.mediaGallery(),
              reviews));   // <-- new reviews field (last component)
      }
      ```
    - `CoachProfileService.getPublicProfile()` itself does NOT call `ReviewQueryService` — avoids circular service-layer dependency.
  - [x] **CRITICAL:** Update `CoachProfileService.getPublicProfile()` to pass `List.of()` (empty) for `reviews` when constructing the `CoachProfileDto` internally (it now requires the field). The controller always overwrites it. Implemented as `null` (controller replaces it).
    - Actually: since `CoachProfileService.getPublicProfile()` is called ONLY from the controller, AND the controller immediately reconstructs the DTO with real reviews, the simplest approach is to have `CoachProfileService` pass `List.of()` as a placeholder. Search the codebase for other callers of `getPublicProfile()` to ensure no other caller exists before updating.

- [x] **Task 18 — Add `/api/admin/reviews/**` to `AppEndpoints.SECURED_ENDPOINTS`** (Security)
  - [x] In `platform/security/config/AppEndpoints.java`: admin endpoints are already secured via `/api/admin/**` pattern. Verify the existing pattern covers `/api/admin/reviews/**`. If it does, no change needed. If not, add it.
  - [x] Grep for `admin` in `AppEndpoints.java` to confirm. `/api/**` covers all admin review endpoints.

- [x] **Task 19 — Integration Test: `ReviewFlagIT`** (AC: 3)
  - [x] Create `platform/reviews/api/ReviewFlagIT.java` in TSID range `8050_xxx`:
    - `PARENT_ID = 8050_000_001L`, `FLAGGING_USER_ID = 8050_000_002L`, `COACH_USER_ID = 8050_000_010L`
    - Seed: 2 parent users, 1 coach, 1 completed booking, 1 approved review (direct DB insert: `moderation_status = 'APPROVED'`)
    - Test 1: `flagSubmitted_createsFlag_returns201()` — flag succeeds, DB row exists
    - Test 2: `flagOwnReview_returns403WithCannotFlagOwnReview()` — author tries to flag their own review
    - Test 3: `flagSameReviewTwice_returns409WithAlreadyFlagged()` — same flagger, same review → 409
    - Test 4: `flagThresholdReached_reviewSetToUnderReview()` — insert 3 flags from 3 different users (use TSID `8050_000_003L` etc.), check `moderation_status = UNDER_REVIEW` and `held_reason = FLAG_THRESHOLD` in DB
    - **Note:** Insert the `APPROVED` review directly via JdbcTemplate with `moderation_status = 'APPROVED'` (bypass normal submission flow so Gemini is not involved).

- [x] **Task 20 — Integration Test: `AdminReviewQueueIT`** (AC: 4, 5, 6)
  - [x] Create `platform/admin/api/AdminReviewQueueIT.java` in TSID range `8060_xxx`:
    - `ADMIN_ID = 8060_000_100L`, `COACH_USER_ID = 8060_000_010L`
    - Seed an UNDER_REVIEW review with a few flags
    - Test 1: `adminCanViewQueue()` — admin token, GET queue, review appears with correct fields (`flagCount`, `flags` without `flaggedBy`, `heldReason`)
    - Test 2: `nonAdminCannotViewQueue()` — parent token → 403
    - Test 3: `approveReview_setsApprovedAndRecomputesRating()` — POST approve, check `moderation_status = APPROVED`, check `resolved_at` set on flags, check `review_count` updated in `coach_profiles`
    - Test 4: `blockReview_setsBlockedAndResolvesFlags()` — POST block with reason, check `moderation_status = BLOCKED`, check flags resolved
    - Test 5: `blockPreviouslyApprovedReview_recomputesRatingDown()` — seed an APPROVED review with rating 5, block it, verify `review_count` decremented and `average_rating` changes
    - **Admin user setup:** Admin user in `main.user` with `skillars_role = 'ADMIN'` and authority `ROLE_ADMIN`. Follow the same pattern as other IT tests (insert user + authority + user_authority rows).

- [x] **Task 21 — Integration Test: `AuthorSelfViewIT`** (AC: 7, 8)
  - [x] Create `platform/reviews/api/AuthorSelfViewIT.java` in TSID range `8070_xxx`:
    - `PARENT_ID = 8070_000_001L`, `COACH_USER_ID = 8070_000_010L`
    - Test 1: `authorCanSeeOwnApprovedReview()` — review in DB with `APPROVED`, endpoint returns it with `moderationStatus = "APPROVED"`
    - Test 2: `authorCanSeeOwnBlockedReview()` — review in DB with `BLOCKED`, endpoint returns it (author sees the block status)
    - [x] Test 3: `noReview_returns403()` — no review for this author-coach pair → 403

## Dev Notes

### CRITICAL: `flaggedBy` is `BIGINT`, NOT UUID

The epic AC spec says `flaggedBy UUID NOT NULL` but all user IDs in `main.user` are `BIGINT`. The `CoachReview.authorId` is `Long`. The `ReviewFlagService.flag()` receives `Long flaggedBy` derived from `resolveUserId()` → `Long.parseLong(p.getBusinessId())`. The `review_flags` table must use `BIGINT` for `flagged_by`. The epic spec's UUID was an error.

### CRITICAL: Avoid Circular Service-Layer Dependency — Enrichment at Controller Level

Story 9.2 established `reviews → marketplace` (CoachRatingService writes CoachProfileRepository). This story's profile enrichment MUST NOT add `marketplace → reviews` in the service layer (circular dependency). The controller (`CoachMarketplaceResource`) is the correct place to aggregate data from multiple modules. `CoachProfileService.getPublicProfile()` builds the base DTO; the controller calls `ReviewQueryService.getFirstPageForCoach()` and reconstructs the DTO with the reviews included.

### CRITICAL: `CoachProfileDto` Record — All 20 → 21 Fields

`CoachProfileDto` is a Java record with 20 components (lines 8-27). Adding `List<ReviewDto> reviews` makes it 21. The only constructor call site is `CoachProfileService.getPublicProfile()` (line 266-287). Update that call to pass `List.of()` as the last argument (controller will replace it). Double-check with grep:
```bash
grep -rn "new CoachProfileDto(" src/
```
All occurrences must be updated.

### CRITICAL: `resolveAllOpenFlags` — `@Modifying` Requires `@Transactional` Caller

`ReviewFlagRepository.resolveAllOpenFlags()` has `@Modifying`. Both callers (`AdminReviewService.approveReview()` and `blockReview()`) are `@Transactional` — this satisfies the requirement. Also add `clearAutomatically = true` to `@Modifying` to avoid L1 cache staleness:
```java
@Modifying(clearAutomatically = true)
```

### CRITICAL: `ReviewApiAdvice` — Map New Error Codes to HTTP Status

Update `ReviewApiAdvice.handleOperationNotAllowed()` to add new HTTP mappings:
- `ALREADY_FLAGGED.getErrorCode()` → `HttpStatus.CONFLICT` (409)
- `REVIEW_NOT_FOUND.getErrorCode()` → `HttpStatus.FORBIDDEN` (403) ← per AC: 403, not 404
- `CANNOT_FLAG_OWN_REVIEW.getErrorCode()` → `HttpStatus.FORBIDDEN` (403) (already handled by default `else` branch, but be explicit)
- `CANNOT_FLAG_OWN_COACHED_REVIEW.getErrorCode()` → `HttpStatus.FORBIDDEN` (403)

### CRITICAL: Admin Queue N+1 Reads — Acceptable for MVP

`AdminReviewService.getUnderReviewQueue()` does N+1 queries for flag counts, flag lists, and coach names. With small admin queues (tens of items), this is fine. Do NOT optimize now — only note it for future tech debt. The method MUST still carry `@Transactional(readOnly = true)` regardless of optimization state — without a transaction boundary, concurrent approvals mid-iteration can produce mixed-status results within a single response.

### CRITICAL: `review_moderation_log` schema lives in `reviews` schema, not `main`

Both `review_flags` and `review_moderation_log` are in the `reviews` schema. The `AdminReviewResource` touches these via JPA entities in `platform.admin.repo` — the schema separation doesn't prevent this.

### CRITICAL: Admin Setup in Tests — `ROLE_ADMIN` Authority

Admin users in tests need:
1. Row in `main.user` with `skillars_role = 'ADMIN'`
2. Row in `main.authority` with `name = 'ROLE_ADMIN'`
3. Row in `main.user_authority` linking them

Pattern from `ReviewModerationIT`: the authority is inserted with `ON CONFLICT (name) DO NOTHING` to avoid failures if another test suite inserted it first. Use a distinct ID for the admin authority (e.g., `8060` for `ROLE_ADMIN` in this test). **Cleanup:** Delete from `main.user_authority`, `main.user`, `main.authority`, and `main.sec` in `tearDown()`.

### CRITICAL: `held_reason` is Nullable — Epic Deviation and Remediation Path

The `AdminReviewQueueEntryDto.heldReason` is a `String` (not `HeldReason` enum) so it serializes naturally: `null` → `null` in JSON. Pre-9.3 UNDER_REVIEW reviews (set by `ReviewModerationService` before Task 11 is implemented) will have `held_reason = NULL` in the DB. These are pre-existing test data rows; the admin queue returns them with `heldReason: null`.

**Epic deviation:** The epic dev notes specify `heldReason NOT NULL`. The column is nullable here to avoid a NOT NULL violation on pre-9.3 rows. Future remediation: once all legacy UNDER_REVIEW rows have been resolved by admins, run a follow-up migration — `UPDATE reviews.coach_reviews SET held_reason = 'GEMINI_UNCERTAIN' WHERE moderation_status = 'UNDER_REVIEW' AND held_reason IS NULL;` then `ALTER TABLE reviews.coach_reviews ALTER COLUMN held_reason SET NOT NULL;`.

### CRITICAL: `platform_config` Seed for `reviews.autoHoldFlagThreshold`

The `ConfigService.getInt("reviews.autoHoldFlagThreshold", 3)` uses default `3` if the key is missing. For production, seed it in the migration or add a seed SQL file. For tests, the default `3` is used automatically (no DB insert needed if the key is absent — `ConfigService.getInt(key, defaultValue)` handles it gracefully).

### CRITICAL: `@Transactional` on `ReviewFlagService.flag()` — Review Update Must Be Atomic

The flag insert and the optional `moderationStatus` + `held_reason` update (if threshold is crossed) must be in the SAME transaction. If the status update fails, the flag insert rolls back too. `@Transactional` on the service method achieves this — do NOT split them into two separate transactions.

### CRITICAL: `ReviewModerationResolvedEvent` Missing `adminId` — Epic Contract Deviation

The epic AC5 and AC6 both specify `ReviewModerationResolvedEvent(reviewId, coachId, newStatus, adminId)`. The existing event has signature `(reviewId, coachId, previousStatus, newStatus)` — `adminId` is absent and `previousStatus` was added instead. Epic 10 stories that consume this event MUST NOT assume `adminId` is present; they must join against `review_moderation_log` on `review_id` to retrieve the admin identity. This deviation must be raised at Epic 10 planning.

### CRITICAL: Flag Threshold Uses Open Flags Only — Prevents Re-Hold on First New Flag After Approval

`countByReviewIdAndResolvedAtIsNull` (not `countByReviewId`) is used for the auto-hold threshold check. Using the total count would cause a re-approved review (with 3 resolved flags) to immediately auto-hold again on a single new flag from any new user, since total count (4) would already exceed the threshold of 3.

### CRITICAL: Re-Flagging After Approval Is Permanently Blocked for Prior Flaggers

The unique index `review_flags_unique_flagger(review_id, flagged_by)` has no filter on `resolved_at`. Once a user flags a review, they can never flag it again regardless of how many admin approval cycles it goes through. This is intentional (prevents harassment via repeated flagging) but means users who flagged in good faith cannot re-flag if the same review is later re-approved as suspicious. Document this constraint in admin tooling.

### CRITICAL: `review_flags.flagged_by` Has No FK to `main.user`

`flagged_by BIGINT NOT NULL` references `main.user(id)` by convention but without a DB constraint. On GDPR erasure (user deletion), flag records will retain the deleted user's ID with no cascade. Before implementing user deletion in any future Epic, ensure a cleanup step anonymises or nullifies flag rows for the erased user. Defer the FK decision to the GDPR/deletion story — adding `ON DELETE RESTRICT` now would block user deletion until flags are manually cleaned.

### Module Package Structure

```
src/main/java/com/softropic/skillars/
  platform/reviews/
    contract/
      + ReviewFlagReason.java           (enum — NEW)
      + HeldReason.java                 (enum — NEW)
      + ReviewFlaggedEvent.java         (record — NEW; published by ReviewFlagService)
      + ReviewFlagRequest.java          (record — NEW)
      + ReviewFlagResponse.java         (record — NEW)
      + ReviewFlagDto.java              (record — NEW; admin view, no flaggedBy)
      + AdminReviewQueueEntryDto.java   (record — NEW; admin queue entry)
      + BlockReviewRequest.java         (record — NEW)
      + AuthorReviewDto.java            (record — NEW)
      ~ ReviewErrorCode.java            (add CANNOT_FLAG_OWN_REVIEW, CANNOT_FLAG_OWN_COACHED_REVIEW, ALREADY_FLAGGED, REVIEW_NOT_FOUND — MODIFIED)
    repo/
      ~ CoachReview.java                (add heldReason field — MODIFIED)
      ~ CoachReviewRepository.java      (add findByAuthorIdAndCoachId, findByModerationStatusOrderByLastModifiedAtAsc — MODIFIED)
      + ReviewFlag.java                 (entity — NEW)
      + ReviewFlagRepository.java       (NEW)
    service/
      + ReviewFlagService.java          (@Service @Transactional — NEW)
      ~ ReviewModerationService.java    (set heldReason for UNDER_REVIEW — MODIFIED)
      ~ ReviewQueryService.java         (add getAuthorReview, getFirstPageForCoach — MODIFIED)
    api/
      ~ ReviewResource.java             (add POST /{reviewId}/flag, GET /me/coaches/{coachId} — MODIFIED)
      ~ ReviewApiAdvice.java            (add ALREADY_FLAGGED→409, REVIEW_NOT_FOUND→403 — MODIFIED)

  platform/admin/
    api/
      + AdminReviewResource.java        (@RestController /api/admin/reviews — NEW)
    service/
      + AdminReviewService.java         (@Service — NEW)
    repo/
      + ReviewModerationLog.java        (entity — NEW)
      + ReviewModerationLogRepository.java (NEW)

  platform/marketplace/
    contract/
      ~ CoachProfileDto.java            (add List<ReviewDto> reviews as last field — MODIFIED)
    api/
      ~ CoachMarketplaceResource.java   (inject ReviewQueryService, enrich getCoachProfile — MODIFIED)

src/main/resources/db/migration/
  + V69__review_flags_moderation_log.sql   (NEW)

src/test/java/com/softropic/skillars/
  platform/reviews/api/
    + ReviewFlagIT.java                  (TSID range 8050_xxx — NEW)
    + AuthorSelfViewIT.java              (TSID range 8070_xxx — NEW)

  platform/admin/api/
    + AdminReviewQueueIT.java            (TSID range 8060_xxx — NEW)
```

### References — Files to Read Before Implementing

- `CoachReview.java` — `platform/reviews/repo/CoachReview.java` (entity to update with `heldReason`)
- `CoachReviewRepository.java` — `platform/reviews/repo/CoachReviewRepository.java` (add new queries)
- `ReviewResource.java` — `platform/reviews/api/ReviewResource.java` (add flagging + self-view endpoints; `resolveUserId()` helper to reuse)
- `ReviewApiAdvice.java` — `platform/reviews/api/ReviewApiAdvice.java` (add new error code HTTP mappings)
- `ReviewModerationService.java` — `platform/reviews/service/ReviewModerationService.java` (update to set `heldReason`; uses `@Autowired` constructor, NOT `@RequiredArgsConstructor`)
- `ReviewQueryService.java` — `platform/reviews/service/ReviewQueryService.java` (add `getAuthorReview`, `getFirstPageForCoach`)
- `CoachRatingService.java` — `platform/reviews/service/CoachRatingService.java` (call `recompute()` from `AdminReviewService`)
- `ReviewModerationResolvedEvent.java` — `platform/reviews/contract/ReviewModerationResolvedEvent.java` (4 fields: reviewId, coachId, previousStatus, newStatus — consumed by Epic 10)
- `CoachProfileDto.java` — `platform/marketplace/contract/CoachProfileDto.java` (add `reviews` field as 21st component)
- `CoachMarketplaceResource.java` — `platform/marketplace/api/CoachMarketplaceResource.java` (inject ReviewQueryService, update `getCoachProfile`)
- `CoachProfileService.java` lines 266-286 — `getPublicProfile()` constructs `CoachProfileDto` — update to pass `List.of()` for reviews placeholder
- `AdminVideoResource.java` — `platform/video/api/AdminVideoResource.java` (admin resource pattern: `@PreAuthorize(HAS_ADMIN_ROLE)` on each method, `@RequestMapping("/api/video/admin")`)
- `SecurityConstants.java` — `infrastructure/security/SecurityConstants.java` (`HAS_ADMIN_ROLE = "hasRole('ROLE_ADMIN') or hasRole('ROLE_LTD_ADMIN')"`)
- `ReviewModerationIT.java` — `platform/reviews/api/ReviewModerationIT.java` (test setup pattern: authority+user+player+coach+booking inserts; `insertUser()` helper pattern; `@MockitoBean GeminiClient`)
- `ConfigService.java` — `platform/config/service/ConfigService.java` (use `getInt(key, defaultValue)` for threshold)
- `AppEndpoints.java` — `platform/security/config/AppEndpoints.java` (verify `/api/admin/**` covers new admin review endpoints; `PUBLIC_ENDPOINTS` should NOT include admin endpoints)

### Story 9.2 Review Findings That Impact This Story

- **Deferred AC3** from 9.2: "Admin endpoints that publish `ReviewModerationResolvedEvent` are in Epic 10" — This story NOW implements those admin endpoints in `platform.admin`. The event was already created in Story 9.2.
- **`@Modifying` without `clearAutomatically = true`** (patched in 9.2 for `updateRatingAggregate`) — Apply the same fix to `resolveAllOpenFlags`.
- **Negative `page` parameter** (patched in 9.2 for review listing) — Apply `Math.max(0, page)` in `AdminReviewService.getUnderReviewQueue()` and `ReviewApiAdvice` is already handling validation.

## Dev Agent Record

### Agent Model Used
claude-sonnet-4-6

### Debug Log References
- Compilation error: `Map<String, String>` in `ReviewFlagIT.flagSameReviewTwice` — fixed to `Map<String, Object>` to match `HttpTestClient.makeHttpRequest` signature
- `CoachProfileDto` has 20 components; added `ReviewListResponse reviews` as 21st (last) — updated `ReportGenerationServiceTest` stub to pass `null` for the new field

### Completion Notes List
- ✅ Task 1: V69 Flyway migration — `review_flags`, `review_moderation_log` tables, `held_reason` column on `coach_reviews`, `reviews.autoHoldFlagThreshold=3` config seeded
- ✅ Task 2: `ReviewFlagReason` and `HeldReason` enums created
- ✅ Task 3: `ReviewErrorCode` extended with CANNOT_FLAG_OWN_REVIEW, CANNOT_FLAG_OWN_COACHED_REVIEW, ALREADY_FLAGGED, REVIEW_NOT_FOUND
- ✅ Task 4: `CoachReview` entity updated with `heldReason` field (nullable)
- ✅ Task 5: `ReviewFlag` JPA entity created
- ✅ Task 6: `ReviewFlagRepository` created with `resolveAllOpenFlags(@Modifying clearAutomatically=true)` and all required query methods
- ✅ Task 7: `CoachReviewRepository` extended with `findByAuthorIdAndCoachId` and `findByModerationStatusOrderByLastModifiedAtAsc`
- ✅ Task 8: Six new DTOs — `ReviewFlagRequest`, `ReviewFlagResponse`, `ReviewFlagDto`, `AdminReviewQueueEntryDto`, `BlockReviewRequest`, `AuthorReviewDto`
- ✅ Task 9: `ReviewFlaggedEvent` record created
- ✅ Task 10: `ReviewFlagService` — full flagging logic with atomic transaction, auto-hold threshold, coach self-flag guard, event publishing
- ✅ Task 11: `ReviewModerationService` updated — `boolean[] geminiFailure` workaround for lambda capture; sets GEMINI_FAILURE vs GEMINI_UNCERTAIN on `heldReason`
- ✅ Task 12: `AdminReviewService` — `getUnderReviewQueue` (@Transactional readOnly), `approveReview`, `blockReview` with rating recompute and flag resolution
- ✅ Task 13: `ReviewModerationLog` entity and `ReviewModerationLogRepository` created in `platform/admin/repo`
- ✅ Task 14: `AdminReviewResource` created — queue, approve, block endpoints with `HAS_ADMIN_ROLE` guard
- ✅ Task 15: `ReviewResource` updated — added `GET /me/coaches/{coachId}` and `POST /{reviewId}/flag` endpoints; `ReviewApiAdvice` updated for ALREADY_FLAGGED→409
- ✅ Task 16: `ReviewQueryService` extended with `getAuthorReview` (403 on miss) and `getFirstPageForCoach`
- ✅ Task 17: `CoachProfileDto` updated with `ReviewListResponse reviews` as 21st field; `CoachProfileService` passes `null` placeholder; `CoachMarketplaceResource` enriches at controller level; `ReportGenerationServiceTest` stub updated
- ✅ Task 18: Verified `/api/**` pattern in `AppEndpoints` covers `/api/admin/reviews/**` — no change needed
- ✅ Task 19: `ReviewFlagIT` — 4 tests covering flag success, own-review guard, duplicate flag, threshold auto-hold
- ✅ Task 20: `AdminReviewQueueIT` — 5 tests covering queue view, non-admin guard, approve, block, block-approved-recomputes-rating
- ✅ Task 21: `AuthorSelfViewIT` — 3 tests covering APPROVED view, BLOCKED view, no-review-403

### File List
src/main/resources/db/migration/V69__review_flags_moderation_log.sql
src/main/java/com/softropic/skillars/platform/reviews/contract/ReviewFlagReason.java
src/main/java/com/softropic/skillars/platform/reviews/contract/HeldReason.java
src/main/java/com/softropic/skillars/platform/reviews/contract/ReviewErrorCode.java
src/main/java/com/softropic/skillars/platform/reviews/repo/CoachReview.java
src/main/java/com/softropic/skillars/platform/reviews/repo/ReviewFlag.java
src/main/java/com/softropic/skillars/platform/reviews/repo/ReviewFlagRepository.java
src/main/java/com/softropic/skillars/platform/reviews/repo/CoachReviewRepository.java
src/main/java/com/softropic/skillars/platform/reviews/contract/ReviewFlagRequest.java
src/main/java/com/softropic/skillars/platform/reviews/contract/ReviewFlagResponse.java
src/main/java/com/softropic/skillars/platform/reviews/contract/ReviewFlagDto.java
src/main/java/com/softropic/skillars/platform/reviews/contract/AdminReviewQueueEntryDto.java
src/main/java/com/softropic/skillars/platform/reviews/contract/BlockReviewRequest.java
src/main/java/com/softropic/skillars/platform/reviews/contract/AuthorReviewDto.java
src/main/java/com/softropic/skillars/platform/reviews/contract/ReviewFlaggedEvent.java
src/main/java/com/softropic/skillars/platform/reviews/service/ReviewFlagService.java
src/main/java/com/softropic/skillars/platform/reviews/service/ReviewModerationService.java
src/main/java/com/softropic/skillars/platform/reviews/service/ReviewQueryService.java
src/main/java/com/softropic/skillars/platform/reviews/api/ReviewResource.java
src/main/java/com/softropic/skillars/platform/reviews/api/ReviewApiAdvice.java
src/main/java/com/softropic/skillars/platform/admin/service/AdminReviewService.java
src/main/java/com/softropic/skillars/platform/admin/repo/ReviewModerationLog.java
src/main/java/com/softropic/skillars/platform/admin/repo/ReviewModerationLogRepository.java
src/main/java/com/softropic/skillars/platform/admin/api/AdminReviewResource.java
src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachProfileDto.java
src/main/java/com/softropic/skillars/platform/marketplace/api/CoachMarketplaceResource.java
src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java
src/test/java/com/softropic/skillars/platform/reviews/api/ReviewFlagIT.java
src/test/java/com/softropic/skillars/platform/reviews/api/AuthorSelfViewIT.java
src/test/java/com/softropic/skillars/platform/admin/api/AdminReviewQueueIT.java
src/test/java/com/softropic/skillars/platform/development/service/ReportGenerationServiceTest.java

### Review Findings

- [x] [Review][Patch] Concurrent duplicate-flag races → HTTP 500 instead of 409 [ReviewFlagService.java:52-55] — Fixed: `save` replaced with `saveAndFlush` wrapped in `DataIntegrityViolationException` catch → rethrow as `ALREADY_FLAGGED` (409).
- [x] [Review][Patch] `lastModifiedAt` not set on flag-triggered auto-hold [ReviewFlagService.java:69-73] — Fixed: added `review.setLastModifiedAt(Instant.now())` before `reviewRepository.save(review)` in auto-hold block.
- [x] [Review][Patch] Test teardown leaks extra users in `ReviewFlagIT.flagThresholdReached` [ReviewFlagIT.java:231-281] — Fixed: added cleanup loop for users `8050_000_020L..8050_000_021L` in `tearDown()`.
- [x] [Review][Patch] `averageRating` returns `0.0` instead of `null` for coaches with no reviews [CoachProfileService.java:272] — Fixed: changed to `profile.getAverageRating()` (pass through null).
- [x] [Review][Patch] Missing integration test for `CANNOT_FLAG_OWN_COACHED_REVIEW` guard (AC3) [ReviewFlagIT.java] — Fixed: added `flagByReviewedCoach_returns403WithCannotFlagOwnCoachedReview()` test.
- [x] [Review][Defer] Double-approve produces spurious audit log + event — No guard in `approveReview()` against re-approving an already-APPROVED review. Idempotent on state but creates duplicate log entries. Defer to Epic 10 when admin UI enforces valid state transitions. — deferred, no current trigger path
- [x] [Review][Defer] `ReviewApiAdvice` scope excludes `AdminReviewResource` — Advice covers `platform.reviews.api` only; admin controller is in `platform.admin.api`. `ResourceNotFoundException` in admin service falls to global handler, may return different error shape. Acceptable if global handler returns consistent JSON, but verify. — deferred, pre-existing architectural boundary
- [x] [Review][Defer] Coach flag guard silently skips when coach profile is deleted — `coachProfileRepository.findById(...).ifPresent(...)` returns empty if profile missing; guard is silently skipped. AC9 says profiles are retained, so this path rarely occurs. — deferred, pre-existing edge case, low risk

## Change Log

- 2026-06-30: Code review — 5 patches, 3 deferred, 1 dismissed. Agent: claude-sonnet-4-6
- 2026-06-29: Story 9.3 implemented — review flagging, admin resolution queue, author self-view, profile enrichment. All 21 tasks complete. Main + test sources compile clean. Agent: claude-sonnet-4-6
- 2026-06-29: Story 9.3 created — review flagging, admin resolution queue, author self-view, profile enrichment with reviews. Story context engine: claude-sonnet-4-6
