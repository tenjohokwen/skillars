# Story 9.2: Review Moderation & Rating Aggregation

Status: done

## Story

As a platform operator,
I want reviews moderated before they appear on coach profiles and coach ratings computed from approved reviews only,
so that the displayed rating is trustworthy and harmful content never reaches the public profile.

## Acceptance Criteria

1. **Given** a `ReviewSubmittedEvent` is received
   **When** `ReviewModerationService` processes it
   **Then** `GeminiClient` is called with the review body (if present) OUTSIDE any `@Transactional` boundary — same pattern as `ModerationOrchestrationService`
   **And** if body is null or blank (rating-only review): moderation is skipped and `moderationStatus` is set directly to `APPROVED`
   **And** after the Gemini call, a `@Transactional` write (via `TransactionTemplate`) updates `reviews.coach_reviews.moderation_status`
   **And** on SAFE verdict: `moderationStatus = APPROVED`; coach rating aggregate is recomputed
   **And** on UNSAFE verdict: `moderationStatus = BLOCKED`; review never appears on profile; coach rating unchanged
   **And** on UNCERTAIN verdict or Gemini API failure: `moderationStatus = UNDER_REVIEW`; review held for admin queue (Epic 10); coach rating unchanged — fail-closed

2. **Given** one or more APPROVED reviews exist for a coach
   **When** the coach's rating aggregate is recomputed
   **Then** `CoachRatingService.recompute(coachId)` updates `coach_profiles.average_rating = ROUND(AVG(rating), 1)` and `coach_profiles.review_count = COUNT(*)` using only `moderationStatus = APPROVED` reviews
   **And** both fields are updated in a single `@Transactional` write on `coach_profiles`
   **And** if no APPROVED reviews exist: `averageRating = null` and `reviewCount = 0`

3. **Given** an admin resolves a review from `UNDER_REVIEW` to `APPROVED` or `BLOCKED` (Epic 10 action)
   **When** the status transition is persisted
   **Then** a `ReviewModerationResolvedEvent(reviewId, coachId, newStatus)` is published
   **And** `CoachRatingService.recompute(coachId)` is called if `newStatus = APPROVED` or the review was previously APPROVED and is now BLOCKED
   *(Note: Admin endpoints that publish this event are in Epic 10. This story implements the event record and `CoachRatingService.recompute()` so Epic 10 can call them.)*

4. **Given** a user views a coach's public profile
   **When** `GET /api/marketplace/coaches/{coachId}` includes rating data
   **Then** `averageRating` (nullable Double, 1 decimal place) and `reviewCount` are returned from `coach_profiles` — pre-computed values, not computed at query time

5. **Given** a user wants to read coach reviews
   **When** `GET /api/reviews/coaches/{coachId}?page={n}&sort=newest|highest|lowest` is called
   **Then** only `moderationStatus = APPROVED` reviews are returned
   **And** each `ReviewDto` contains: `reviewId`, `authorRole`, `rating`, `body` (nullable), `coachResponseBody` (nullable), `coachResponseAt` (nullable), `createdAt`, `lastModifiedAt`
   **And** `authorId` is NOT returned — reviews are anonymous to the public
   **And** pagination: 10 reviews per page; default sort is `newest` (`lastModifiedAt DESC`); `highest` = `rating DESC`; `lowest` = `rating ASC`

6. **Given** a coach wants to see all their reviews including pending and blocked
   **When** `GET /api/reviews/coaches/me` is called by an authenticated coach
   **Then** all reviews for `coach_reviews.coachId = authenticatedCoach.id` are returned regardless of `moderationStatus`
   **And** `authorId` is NOT returned
   **And** `moderationStatus` IS returned in the `CoachOwnReviewDto` response

## Tasks / Subtasks

- [x] **Task 1 — Flyway Migration V68** (AC: 2, 4)
  - [x] Create `src/main/resources/db/migration/V68__reviews_rating_aggregation.sql`:
    ```sql
    ALTER TABLE marketplace.coach_profiles
        ADD COLUMN average_rating NUMERIC(3,1) DEFAULT NULL,
        ADD COLUMN review_count   INT          NOT NULL DEFAULT 0;
    ```
  - [x] V67 is the last migration. V68 is next — no collision.
  - [x] No data backfill needed — all coaches start with NULL/0 (no approved reviews yet).

- [x] **Task 2 — Update `CoachProfile` Entity** (AC: 2, 4)
  - [x] Modify `platform/marketplace/repo/CoachProfile.java`:
    ```java
    @Column(name = "average_rating", columnDefinition = "NUMERIC(3,1)")
    private Double averageRating;   // null = no approved reviews

    @Column(name = "review_count", nullable = false)
    private int reviewCount;        // 0 until approved reviews exist
    ```
  - [x] `Double` (boxed, nullable) — NOT `double` (primitive). The AC requires nullable.
  - [x] Add standard Lombok `@Getter`/`@Setter` — already on the class via class-level annotation.

- [x] **Task 3 — Update `CoachProfileDto` and `CoachCardDto`** (AC: 4)
  - [x] Rename `double aggregateRating` → `Double averageRating` in `platform/marketplace/contract/CoachProfileDto.java`
  - [x] Rename `double aggregateRating` → `Double averageRating` in `platform/marketplace/contract/CoachCardDto.java`
  - [x] Both are Java `record` types — update the record component name and type. The JSON field name changes from `aggregateRating` → `averageRating`. This is an intentional API improvement.

- [x] **Task 4 — Update `CoachProfileRepository` with `@Modifying` Query** (AC: 2)
  - [x] Add to `platform/marketplace/repo/CoachProfileRepository.java`:
    ```java
    @Modifying
    @Query("UPDATE CoachProfile p SET p.averageRating = :avgRating, p.reviewCount = :reviewCount WHERE p.id = :coachId")
    void updateRatingAggregate(@Param("coachId") UUID coachId,
                               @Param("avgRating") Double avgRating,
                               @Param("reviewCount") int reviewCount);
    ```
  - [x] `@Modifying` requires a `@Transactional` context — provided by the calling `CoachRatingService.recompute()`.

- [x] **Task 5 — Update `CoachProfileService.getPublicProfile()`** (AC: 4)
  - [x] In `platform/marketplace/service/CoachProfileService.java`, line 266 currently hardcodes `0.0` and `0`.
  - [x] Replace with real values.
  - [x] The DTO record component is now `Double averageRating` (nullable). Passing `null` is valid.

- [x] **Task 6 — Update `CoachSearchService` DTO Assembly** (AC: 4)
  - [x] In `platform/marketplace/service/CoachSearchService.java`, replaced hardcoded `0.0` and `0` with real entity values.
  - [x] Fixed `buildSort()` to separate "rating" case to sort by `averageRating DESC`.

- [x] **Task 7 — Wire `minRating` Filter in `CoachSearchSpecification`** (AC: 4)
  - [x] In `platform/marketplace/service/CoachSearchSpecification.java`, `hasMinRating()` now uses real `averageRating` column.

- [x] **Task 8 — Add Aggregate and Listing Queries to `CoachReviewRepository`** (AC: 2, 5, 6)
  - [x] Added `countByCoachIdAndModerationStatus`, `computeAverageRating`, `findByCoachIdAndModerationStatus`, `findByCoachId` to `CoachReviewRepository`.

- [x] **Task 9 — New DTOs** (AC: 5, 6)
  - [x] Created `platform/reviews/contract/ReviewDto.java`
  - [x] Created `platform/reviews/contract/CoachOwnReviewDto.java`
  - [x] Created `platform/reviews/contract/ReviewListResponse.java`
  - [x] Created `platform/reviews/contract/CoachOwnReviewListResponse.java`
  - [x] **NO `authorId`** in either DTO — reviews are anonymous.

- [x] **Task 10 — New `ReviewModerationResolvedEvent`** (AC: 3)
  - [x] Created `platform/reviews/contract/ReviewModerationResolvedEvent.java`

- [x] **Task 11 — New `CoachRatingService`** (AC: 2, 3)
  - [x] Created `platform/reviews/service/CoachRatingService.java` with `@Transactional recompute(UUID coachId)`.

- [x] **Task 12 — New `ReviewModerationService`** (AC: 1)
  - [x] Created `platform/reviews/service/ReviewModerationService.java` with `@TransactionalEventListener(phase = AFTER_COMMIT)`.
  - [x] Gemini call is outside any `@Transactional` context.
  - [x] Status write-back and optional rating recompute run in a single `REQUIRES_NEW` `TransactionTemplate` (see Debug Log for why `REQUIRES_NEW` was required instead of the story-spec `REQUIRED`).

- [x] **Task 13 — New `ReviewQueryService`** (AC: 5, 6)
  - [x] Created `platform/reviews/service/ReviewQueryService.java` with `listApprovedReviews()` and `listCoachOwnReviews()`.

- [x] **Task 14 — Update `ReviewResource` with New Endpoints** (AC: 5, 6)
  - [x] Added `GET /api/reviews/coaches/me` (coach role) and `GET /api/reviews/coaches/{coachId}` (public) to `ReviewResource`.
  - [x] `GET /coaches/me` mapped before `GET /coaches/{coachId}` so Spring MVC resolves literal "me" first.

- [x] **Task 15 — Add `platform.reviews.moderation.gemini` config to `application.yaml`** (AC: 1)
  - [x] Added prompt-template and max-input-chars under `platform.reviews.moderation.gemini`.

- [x] **Task 16 — Integration Test `ReviewModerationIT`** (AC: 1, 2)
  - [x] Created `ReviewModerationIT.java` in TSID range 8030_xxx — 4/4 tests pass.

- [x] **Task 17 — Integration Test `PublicReviewListIT`** (AC: 5, 6)
  - [x] Created `PublicReviewListIT.java` in TSID range 8040_xxx — 5/5 tests pass.

- [x] **Task 18 — Unit Test `CoachRatingServiceTest`** (AC: 2, 3)
  - [x] Created `CoachRatingServiceTest.java` — 3/3 tests pass.

## Dev Notes

### CRITICAL: `@TransactionalEventListener(phase = AFTER_COMMIT)` — NOT plain `@EventListener`

Story 9.1 review explicitly deferred this fix to Story 9.2:
> "[Review][Defer] `eventPublisher.publishEvent()` called inside `@Transactional` — if Story 9.2 registers a synchronous `@EventListener`, any exception it throws will roll back the review save; use `@TransactionalEventListener(phase = AFTER_COMMIT)` in Story 9.2"

`ReviewSubmissionService` is annotated `@Transactional`. When `eventPublisher.publishEvent(ReviewSubmittedEvent)` is called inside, Spring defers the event to after the TX commits if the listener uses `@TransactionalEventListener`. With plain `@EventListener`, a Gemini timeout would roll back the review save. Use:
```java
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handleReviewSubmitted(ReviewSubmittedEvent event) { ... }
```

Do NOT add `@Async` — synchronous for MVP, as stated in epic dev notes.

### CRITICAL: Gemini Call Must Be Outside `@Transactional`

The `handleReviewSubmitted()` method must NOT be annotated with `@Transactional`. The `GeminiClient.evaluate()` call happens directly in it (outside any transaction). The DB write-back uses `TransactionTemplate` to open a short, targeted transaction. This matches the `ModerationOrchestrationService` pattern (non-transactional outer method → short `TransactionTemplate`-scoped write-back), not the messaging `GeminiModerationService` which wraps an internal `ModerationResultApplier` service specific to messaging.

### CRITICAL: `ModerationVerdict` is in `platform.messaging.contract`

`GeminiClient.evaluate()` returns `ModerationVerdict` from `platform.messaging.contract`. This is a pre-existing architectural compromise (infrastructure importing from a platform module — `GeminiClientImpl` already imports it). The reviews module must also import it:
```java
import com.softropic.skillars.platform.messaging.contract.ModerationVerdict;
```
Do NOT redefine `ModerationVerdict` in `platform.reviews.contract` — that would create two copies of the same enum.

### CRITICAL: DTO Field Rename — `aggregateRating` → `averageRating`

The current `CoachProfileDto` and `CoachCardDto` have `double aggregateRating` (primitive, non-nullable). Story 9.2 introduces `Double averageRating` (boxed, nullable) to represent null when no approved reviews exist. This is a breaking API rename that is intentional and expected (the old value was always 0.0 — a stub).

**4 files to update:**
1. `CoachProfileDto.java` — record component: `double aggregateRating` → `Double averageRating`
2. `CoachCardDto.java` — same
3. `CoachProfileService.java` lines 272-273 — record constructor: `0.0` → `profile.getAverageRating()`, `0` → `profile.getReviewCount()`
4. `CoachSearchService.java` lines 78-79 — DTO assembly: `0.0` → `p.getAverageRating()`, `0` → `p.getReviewCount()`

### CRITICAL: Cross-Module Write (`CoachRatingService` → `marketplace.coach_profiles`)

`CoachRatingService` in `platform.reviews.service` writes to `marketplace.coach_profiles` via `CoachProfileRepository`. This is a cross-module write. The precedent: `ReviewSubmissionService` already imports `CoachProfileRepository` for existence checks. The cross-module dependency is: reviews → marketplace (read + write). The reverse dependency does NOT exist (marketplace knows nothing about reviews). No circular dependency.

Import in `CoachRatingService`:
```java
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
```

### CRITICAL: Review Listing URLs Differ From Epic Spec

Epic 9.2 AC specifies `GET /api/coaches/{coachId}/reviews` and `GET /api/coaches/me/reviews`. This story implements `GET /api/reviews/coaches/{coachId}` and `GET /api/reviews/coaches/me`. The story's URLs are correct — they follow the established `/api/reviews/` base path of `ReviewResource` (consistent with the existing submission, update, and coach-response endpoints). The epic spec used the logical resource path as shorthand. **Frontend teams must be informed** to call `/api/reviews/coaches/{coachId}` and `/api/reviews/coaches/me`, not `/api/coaches/...`.

### CRITICAL: `@Modifying` Query Requires `@Transactional` Caller

`CoachProfileRepository.updateRatingAggregate()` is annotated `@Modifying`. In Spring Data JPA, `@Modifying` methods require a `@Transactional` context. The call site is `CoachRatingService.recompute()` which is `@Transactional` — this satisfies the requirement. Do NOT add `@Transactional` to the repository method itself.

### CRITICAL: Rating Rounding — `Math.round(avg * 10.0) / 10.0`

The AC says "ROUND(AVG(rating), 1)". The Java implementation:
```java
Double rounded = avg == null ? null : Math.round(avg * 10.0) / 10.0;
```
This rounds to 1 decimal place (e.g., 3.75 → 3.8, 4.25 → 4.3). Valid for ratings 1-5. The DB column `NUMERIC(3,1)` stores up to 99.9, sufficient.

### CRITICAL: `@TransactionalEventListener` Timing — No `Awaitility` Needed in Tests

`@TransactionalEventListener(phase = AFTER_COMMIT)` fires SYNCHRONOUSLY in the same thread, after the outer transaction commits. The sequence is:
1. HTTP request → `ReviewSubmissionService.submitReview()` (TX opens)
2. Review saved → `ReviewSubmittedEvent` published (deferred to post-commit)
3. TX commits
4. `ReviewModerationService.handleReviewSubmitted()` fires (still same thread)
5. Gemini called → `TransactionTemplate` opens new TX → status updated → TX commits
6. HTTP response returned to test client

The moderation completes BEFORE the HTTP response reaches the test. Standard `assertThat()` after the HTTP call works. No `Awaitility`, no `Thread.sleep()`.

### Pattern: `CoachOwnReviewService` — No `CoachProfileRepository` Needed

`GET /api/reviews/coaches/me` needs the coach's profile UUID (from JWT userId → coachId). The `coachProfileService.getCoachIdByUserId(userId)` method already exists (used by Story 9.1's coach response endpoint). Inject `CoachProfileService` into `ReviewResource` (it's already injected — the coach response endpoint uses it).

### Module Package Structure

```
src/main/java/com/softropic/skillars/
  platform/reviews/
    contract/
      + ReviewDto.java                      (record — NEW)
      + CoachOwnReviewDto.java              (record — NEW)
      + ReviewListResponse.java             (record — NEW)
      + CoachOwnReviewListResponse.java     (record — NEW)
      + ReviewModerationResolvedEvent.java  (record — NEW; consumed by Epic 10)
    service/
      + ReviewModerationService.java        (@Service, @TransactionalEventListener — NEW)
      + CoachRatingService.java             (@Service, @Transactional — NEW)
      + ReviewQueryService.java             (@Service, @Transactional(readOnly=true) — NEW)
    repo/
      ~ CoachReviewRepository.java          (add: countByCoachIdAndModerationStatus, computeAverageRating,
                                             findByCoachIdAndModerationStatus, findByCoachId — MODIFIED)
    api/
      ~ ReviewResource.java                 (add: GET /coaches/{coachId}, GET /coaches/me — MODIFIED)

  platform/marketplace/
    repo/
      ~ CoachProfile.java                   (add: averageRating Double, reviewCount int — MODIFIED)
      ~ CoachProfileRepository.java         (add: updateRatingAggregate @Modifying — MODIFIED)
    contract/
      ~ CoachProfileDto.java                (rename: aggregateRating→averageRating, double→Double — MODIFIED)
      ~ CoachCardDto.java                   (rename: aggregateRating→averageRating, double→Double — MODIFIED)
    service/
      ~ CoachProfileService.java            (getPublicProfile: use real values — MODIFIED)
      ~ CoachSearchService.java             (DTO assembly: use real values — MODIFIED)
      ~ CoachSearchSpecification.java       (hasMinRating: wire up real filter — MODIFIED)

src/main/resources/db/migration/
  + V68__reviews_rating_aggregation.sql     (NEW)

src/main/resources/
  ~ application.yaml                        (add platform.reviews.moderation.gemini config — MODIFIED)

src/test/java/com/softropic/skillars/
  platform/reviews/
    api/
      + ReviewModerationIT.java             (NEW — @MockitoBean GeminiClient)
      + PublicReviewListIT.java             (NEW)
    service/
      + CoachRatingServiceTest.java         (unit test — NEW)
```

### References

- `ReviewSubmissionService.java` — `platform/reviews/service/ReviewSubmissionService.java` (event publishing on lines 72-73 and 103-104 — `ReviewModerationService.handleReviewSubmitted()` is the consumer)
- `GeminiModerationService.java` — `platform/messaging/service/GeminiModerationService.java` (the Story 8.3 pattern: non-transactional outer method → transactional write-back via injected service)
- `ModerationOrchestrationService.java` — `platform/video/service/ModerationOrchestrationService.java` (lines 65-66: `@TransactionalEventListener(phase = AFTER_COMMIT)` + `TransactionTemplate` pattern to study and replicate)
- `ModerationFailClosedIT.java` — `platform/messaging/api/ModerationFailClosedIT.java` (`@MockitoBean GeminiClient` pattern + `when(geminiClient.evaluate(any())).thenThrow(...)`)
- `CoachProfileService.java` — lines 266-286: `getPublicProfile()` constructs `CoachProfileDto` — update lines 272-273
- `CoachSearchService.java` — lines 69-82: DTO assembly — update lines 78-79
- `CoachSearchSpecification.java` — lines 107-112: `hasMinRating()` to wire up
- `ReviewResource.java` — `platform/reviews/api/ReviewResource.java` (existing `resolveUserId()` helper to reuse; `coachProfileService.getCoachIdByUserId()` on coach response path line ~56)
- `ModerationVerdict.java` — `platform/messaging/contract/ModerationVerdict.java` (SAFE, UNSAFE, UNCERTAIN — import from here; do not redefine)
- `SecurityConstants.java` — `infrastructure/security/SecurityConstants.java` (IS_PERMIT_ALL for public review list; HAS_COACH_ROLE for coach self-view)
- `ReviewSubmissionIT.java` — `platform/reviews/api/ReviewSubmissionIT.java` (TSID ranges 8000-8009; test setup pattern — insertUser, insertCoachProfile, insertBooking via JdbcTemplate)
- `project-context.md` — `_bmad-output/project-context.md` (record DTOs, @PreAuthorize on every endpoint, @Observed, Testcontainers pattern)

### Story 9.1 Review Findings Applied in This Story

- **Deferred P4** — `@TransactionalEventListener(phase = AFTER_COMMIT)` in `ReviewModerationService` — **IMPLEMENTED in Task 12**. Do NOT use plain `@EventListener`.
- **Deferred P5** — `SecurityUtil.getCurrentUser()` throws 500 for non-standard tokens — pre-existing, not addressed here.

### Review Findings

- [x] [Review][Patch] NULL averageRating sorts FIRST in DESC — coaches with no reviews appear at top of rating sort [`CoachSearchService.java`, `buildSort()` case "rating"]
- [x] [Review][Patch] @TransactionalEventListener failure → HTTP 500 to client despite committed review — `requiresNewTx.execute()` unguarded; DB failure during status write bubbles through AFTER_COMMIT to the HTTP response path [`ReviewModerationService.java:76`, `handleReviewSubmitted()`]
- [x] [Review][Patch] Negative `page` parameter throws HTTP 500 on public endpoint — `PageRequest.of(page, ...)` throws `IllegalArgumentException` for `page < 0`; no handler in `ApiAdvice`; endpoint is unauthenticated [`ReviewQueryService.java:30`, `ReviewResource.java` both endpoints]
- [x] [Review][Patch] Re-edit path leaves stale aggregate when re-moderated to BLOCKED — `ReviewModerationService` only calls `recompute` on APPROVED; if a previously-APPROVED review is re-edited and then blocked, its rating is never removed from the aggregate, violating AC2 [`ReviewModerationService.java:84-86`]
- [x] [Review][Patch] `@Modifying` without `clearAutomatically = true` — bulk JPQL update bypasses first-level cache; any code that loads `CoachProfile` before `recompute()` in the same TX will read stale rating fields [`CoachProfileRepository.java`, `updateRatingAggregate`]
- [x] [Review][Patch] `ReviewModerationResolvedEvent` missing `previousStatus` field — Epic 10 listener cannot implement the "recompute if review was previously APPROVED and is now BLOCKED" branch of AC3 without knowing prior status [`ReviewModerationResolvedEvent.java`]
- [x] [Review][Patch] Race: two separate queries (count then avg) can yield `reviewCount=N, averageRating=null` — concurrent delete between queries leaves profile in contradictory state [`CoachRatingService.java:23-28`]
- [x] [Review][Defer] AC3 admin endpoint for UNDER_REVIEW resolution not implemented [`ReviewModerationResolvedEvent.java`] — deferred, intentional per story spec note ("Admin endpoints in Epic 10")
- [x] [Review][Defer] Prompt injection in Gemini moderation — review body concatenated directly onto prompt template with no sanitisation [`ReviewModerationService.java:215`] — deferred, pre-existing: same `promptTemplate + input` pattern used in messaging module (Story 8.3)

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

**Bug 1 — `booking.bookings.player_id NOT NULL` constraint violation in `ReviewModerationIT`**
- Cause: booking INSERT used `NULL` for `player_id` but the DB schema has `NOT NULL`.
- Fix: Added `PLAYER_ID = 8030_000_002L`, inserted a `main.player_profiles` row in `setUp()`, passed `PLAYER_ID` to booking INSERT, deleted from `player_profiles` in `tearDown()`.

**Bug 2 — `GET /api/reviews/coaches/{coachId}` returning 401 Unauthorized**
- Cause: The filter chain in `AppEndpoints` has `/api/**` in `SECURED_ENDPOINTS`. The `@PreAuthorize("permitAll()")` annotation runs AFTER the filter chain, so it was never reached.
- Fix: Added `/api/reviews/coaches/**` to `AppEndpoints.PUBLIC_ENDPOINTS` (same pattern as `/api/marketplace/coaches/**`). The `@PreAuthorize(HAS_COACH_ROLE)` on `/coaches/me` still protects that endpoint at the method level.

**Bug 3 — `ReviewModerationIT` all 4 tests fail: status remains PENDING**
- Root cause: `@TransactionalEventListener(phase = AFTER_COMMIT)` fires during Spring TX1's `afterCommit()` phase. At this point, TX1's `EntityManagerHolder` is still bound to the thread with `transactionActive = true` (cleanup happens later in `afterCompletion()`). When `TransactionTemplate.execute()` (default `PROPAGATION_REQUIRED`) runs inside the listener, `JpaTransactionManager.isExistingTransaction()` returns `true` for the stale TX1 — so TX2 attempts to JOIN the committed TX1 entity manager. Any JPA write operation on this committed entity manager throws `jakarta.persistence.TransactionRequiredException: Executing an update/delete query`.
- Fix: Changed the `TransactionTemplate` to use `PROPAGATION_REQUIRES_NEW` (configured at bean construction time). `REQUIRES_NEW` always suspends the current (stale) TX1 entity manager and creates a fresh entity manager bound to a new real JDBC connection. All write operations (status update + rating recompute) succeed inside this clean TX2.
- Implementation note: Since `@RequiredArgsConstructor` cannot initialize a custom `TransactionTemplate`, the service was refactored to inject `PlatformTransactionManager` via an explicit `@Autowired` constructor and create the `REQUIRES_NEW` template during construction.
- Also consolidated: `coachRatingService.recompute()` is called from WITHIN the `requiresNewTx.execute()` lambda. Because `recompute()` has `@Transactional(REQUIRED)`, it JOINs the active TX2 (no separate TX3 opened). This is cleaner than two separate transaction boundaries.

### Completion Notes List

- All 18 tasks implemented and tested.
- Test results: ReviewModerationIT 4/4, PublicReviewListIT 5/5, CoachRatingServiceTest 3/3 — 12/12 total PASS.
- `ReviewModerationService` deviates from the story's code sketch in Task 12: uses explicit `@Autowired` constructor (not `@RequiredArgsConstructor`) and `PROPAGATION_REQUIRES_NEW` (not default `REQUIRES_NEW`) — required to avoid `TransactionRequiredException` in `@TransactionalEventListener(AFTER_COMMIT)` context. See Debug Log Bug 3.
- Public endpoint `/api/reviews/coaches/**` added to `AppEndpoints.PUBLIC_ENDPOINTS`; method-level `@PreAuthorize` still guards `/coaches/me`.
- `ReviewSubmissionIT` booking setup fixed: `player_id` now correctly populated from inserted `player_profiles` row (Bug 1).

### File List

**New files:**
- `src/main/resources/db/migration/V68__reviews_rating_aggregation.sql`
- `src/main/java/com/softropic/skillars/platform/reviews/contract/ReviewDto.java`
- `src/main/java/com/softropic/skillars/platform/reviews/contract/CoachOwnReviewDto.java`
- `src/main/java/com/softropic/skillars/platform/reviews/contract/ReviewListResponse.java`
- `src/main/java/com/softropic/skillars/platform/reviews/contract/CoachOwnReviewListResponse.java`
- `src/main/java/com/softropic/skillars/platform/reviews/contract/ReviewModerationResolvedEvent.java`
- `src/main/java/com/softropic/skillars/platform/reviews/service/CoachRatingService.java`
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewModerationService.java`
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewQueryService.java`
- `src/test/java/com/softropic/skillars/platform/reviews/api/ReviewModerationIT.java`
- `src/test/java/com/softropic/skillars/platform/reviews/api/PublicReviewListIT.java`
- `src/test/java/com/softropic/skillars/platform/reviews/service/CoachRatingServiceTest.java`

**Modified files:**
- `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfile.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfileRepository.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachProfileDto.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachCardDto.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachSearchService.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachSearchSpecification.java`
- `src/main/java/com/softropic/skillars/platform/reviews/repo/CoachReviewRepository.java`
- `src/main/java/com/softropic/skillars/platform/reviews/api/ReviewResource.java`
- `src/main/java/com/softropic/skillars/platform/security/config/AppEndpoints.java`
- `src/main/resources/application.yaml`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-06-29: Story 9.2 implemented — Flyway V68, rating aggregation, Gemini review moderation, public/coach listing endpoints, 12 tests. Dev: claude-sonnet-4-6
