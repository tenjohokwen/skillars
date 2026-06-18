# Story skillars-4.6: Homework Assignment & Player Locker Room

Status: done

## Story

As a coach,
I want to assign homework drills to players during the wrap-up,
And as a player, I want to see only my assigned drills in a dedicated Locker Room screen,
So that practice between sessions is structured, focused, and coach-directed.

## Acceptance Criteria

**AC 1: Wrap-up Step 4 — Homework Picker with real suggestions** — Given a coach is on Step 4 of the wrap-up sequence (`WrapUpSequence.vue`), when the homework picker renders, then up to 2 drill suggestions from `DrillSuggestionService` are shown (wiring the existing stub `GET /api/bookings/session/{bookingId}/drills/suggestions`); the coach can select 0, 1, or 2 drills — a "Skip homework" option is equally prominent; both options lead to the same `handleSubmitWrapUp()` submission.

**AC 2: Homework assignments persisted** — Given the wrap-up is finalised (LIVE or QUICK mode reaching `COMPLETED`), when `BookingCompletedEvent` fires (AFTER_COMMIT), then for each drillId in `event.homeworkDrillIds`, one `homework_assignments` row is created with `bookingId` (from `event.bookingId`), `sessionId` (from `session.sessions` where `bookingId = event.bookingId` — nullable; null for QUICK-mode bookings with no session plan), `playerId`, `coachId`, `drillId`, `packId` (the currently-active pack for this player+coach, or the most recently purchased pack if none are active), and `assignedAt = now()`; duplicate protection: if an assignment for the same `bookingId + drillId` already exists, skip — `bookingId` is the idempotency anchor (not `sessionId`) because `sessionId` may be null and PostgreSQL UNIQUE constraints treat `NULL ≠ NULL`, making a nullable-column constraint useless for idempotency. **Deferred**: the epics AC also requires a 15-second drill demo autoplay loop on each drill card (UX-DR30); this story intentionally ships without it — `DrillLibraryService.toResponse()` is called with `hasVideo=false` and the Locker Room renders static thumbnails; video wiring is deferred to Epic 6.

**AC 3: Locker Room — assigned drills only** — Given homework drills are assigned, when the parent calls `GET /api/session/players/{playerId}/homework`, then only drills explicitly assigned to that player by coaches with an ACTIVE session pack are returned — the full drill library is never in the response; each item is a `HomeworkAssignmentResponse` (assignmentId, drillId, drill metadata via `DrillResponse`, coachId, coachDisplayName, assignedAt, completed boolean); drills are sorted by `assignedAt DESC`; access is gated by `@playerOwnershipGuard.check(authentication, #playerId)` (PARENT role).

**AC 4: Pack expiry excludes assignments** — Given the player's session pack with the assigning coach is exhausted (status != ACTIVE or creditsRemaining = 0), when the Locker Room renders, then all homework drills assigned under that coach are excluded from the response; if the player has active packs with other coaches, those coaches' homework drills remain visible; the service uses `SessionPackService.hasActivePack(playerId, coachId)` for this check.

**AC 5: Completion toggle** — Given a player's parent taps "I've done this" on a homework drill, when `POST /api/session/homework/{assignmentId}/complete` is called, then a `homework_completions` row is created (idempotent — a second call returns 200 without creating a duplicate); the `HomeworkAssignmentResponse` in subsequent GET calls has `completed: true`; the drill card renders in a "Completed" state with checkmark, remaining visible.

**AC 6: Minor player — no library access** — Given a minor player (non-ADULT age tier), when the Locker Room renders, then only explicitly assigned drills are shown — no browse CTA, no search, no library link; this constraint is satisfied by design: `GET /api/session/players/{playerId}/homework` only ever returns `homework_assignments` rows — there is no code path that could expose library drills regardless of age or subscription tier; no explicit `AgePolicyService` call is needed in `HomeworkAssignmentService`. The frontend Locker Room page has no library browse CTA (enforcing UX-DR30) and `AgePolicyService` does not need to be injected.

**AC 7: Empty state** — Given the Locker Room has no assigned drills (or all packs exhausted), when the empty state renders, then an icon, motivational headline ("Your coach hasn't set homework yet — check back after your next session"), and no drill content is shown; no library browse CTA is present — the Locker Room never redirects to the full drill library.

**AC 8: Completion visible to coach** — Given a homework drill is marked complete, when the coach views their client detail, then the completion status is surfaced; this is currently stubbed — `HomeworkAssignmentResponse.completed` is the field that future client-detail screens will read; no coach-facing UI is built in this story.

**AC 9: Wrap-up suggestions stub wired** — Given the stub endpoint `GET /api/bookings/session/{bookingId}/drills/suggestions` returns `[]`, when Story 4.6 ships, then the stub is replaced with a real implementation that: looks up `sessionId` from `session.sessions where bookingId = :bookingId`, calls `drillSuggestionService.suggest(sessionId, coachUserId, limit)`, and returns `List<DrillResponse>`.

## Tasks / Subtasks

### Backend — Database Migration

- [x] **Task 1: Write `V45__homework_assignments.sql`** (AC: 2, 4, 5)
  - [x] File: `src/main/resources/db/migration/V45__homework_assignments.sql`
  - [x] Previous migration: V44 (`session_templates`). This must be V45.
  - [x] SQL:
    ```sql
    CREATE TABLE session.homework_assignments (
        id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
        booking_id  UUID        NOT NULL,
        session_id  UUID,
        player_id   BIGINT      NOT NULL,
        coach_id    UUID        NOT NULL,
        drill_id    UUID        NOT NULL,
        pack_id     UUID,
        assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        CONSTRAINT uq_homework_booking_drill UNIQUE (booking_id, drill_id)
    );
    CREATE INDEX idx_homework_assignments_player_id ON session.homework_assignments (player_id);
    CREATE INDEX idx_homework_assignments_coach_id  ON session.homework_assignments (coach_id);

    CREATE TABLE session.homework_completions (
        id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
        assignment_id UUID        NOT NULL REFERENCES session.homework_assignments(id) ON DELETE CASCADE,
        player_id     BIGINT      NOT NULL,
        completed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
        CONSTRAINT uq_homework_completion UNIQUE (assignment_id)
    );
    CREATE INDEX idx_homework_completions_assignment_id ON session.homework_completions (assignment_id);
    ```
  - [x] `player_id` is BIGINT — **NOT UUID** — matching `session.sessions.player_id` and all existing player ID fields in this schema. The epic spec says UUID but that is an error; TSIDs are Long/BIGINT.
  - [x] `booking_id` is NOT a FK — loose coupling; it is the idempotency anchor and always non-null.
  - [x] `session_id` is **nullable** and NOT a FK — null for QUICK-mode bookings that never had a session builder plan. Do NOT add NOT NULL here.
  - [x] `UNIQUE (booking_id, drill_id)` — idempotency guard: re-publishing `BookingCompletedEvent` does not create duplicate assignments. Uses `booking_id` (never null) instead of `session_id` (nullable) — PostgreSQL UNIQUE constraints treat `NULL ≠ NULL`, so a UNIQUE on a nullable column provides zero duplicate protection when that column is null.
  - [x] `pack_id` is nullable and NOT a FK — pack may have been exhausted at time of creation; storing for audit only.
  - [x] `UNIQUE (assignment_id)` on completions — one completion record per assignment.

### Backend — Repository Layer

- [x] **Task 2: Create `HomeworkAssignment.java` entity** (AC: 2, 3, 4, 5)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/repo/HomeworkAssignment.java`
  - [x] Entity:
    ```java
    @Entity
    @Table(schema = "session", name = "homework_assignments")
    @Getter
    @Setter
    @NoArgsConstructor
    public class HomeworkAssignment {

        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;

        @Column(name = "booking_id", nullable = false, updatable = false)
        private UUID bookingId;

        @Column(name = "session_id")
        private UUID sessionId;

        @Column(name = "player_id", nullable = false)
        private Long playerId;

        @Column(name = "coach_id", nullable = false)
        private UUID coachId;

        @Column(name = "drill_id", nullable = false)
        private UUID drillId;

        @Column(name = "pack_id")
        private UUID packId;

        @Column(name = "assigned_at", nullable = false, updatable = false)
        private Instant assignedAt;

        @PrePersist
        void onCreate() { assignedAt = Instant.now(); }
    }
    ```

- [x] **Task 3: Create `HomeworkCompletion.java` entity** (AC: 5)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/repo/HomeworkCompletion.java`
  - [x] Entity:
    ```java
    @Entity
    @Table(schema = "session", name = "homework_completions")
    @Getter
    @Setter
    @NoArgsConstructor
    public class HomeworkCompletion {

        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;

        @Column(name = "assignment_id", nullable = false, unique = true)
        private UUID assignmentId;

        @Column(name = "player_id", nullable = false)
        private Long playerId;

        @Column(name = "completed_at", nullable = false, updatable = false)
        private Instant completedAt;

        @PrePersist
        void onCreate() { completedAt = Instant.now(); }
    }
    ```

- [x] **Task 4: Create `HomeworkAssignmentRepository.java`** (AC: 2, 3, 4, 5)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/repo/HomeworkAssignmentRepository.java`
  - [x] Interface:
    ```java
    public interface HomeworkAssignmentRepository extends JpaRepository<HomeworkAssignment, UUID> {

        List<HomeworkAssignment> findByPlayerIdOrderByAssignedAtDesc(Long playerId);

        Optional<HomeworkAssignment> findByIdAndPlayerId(UUID id, Long playerId);

        boolean existsByBookingIdAndDrillId(UUID bookingId, UUID drillId);
    }
    ```

- [x] **Task 5: Create `HomeworkCompletionRepository.java`** (AC: 5)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/repo/HomeworkCompletionRepository.java`
  - [x] Interface:
    ```java
    public interface HomeworkCompletionRepository extends JpaRepository<HomeworkCompletion, UUID> {
        boolean existsByAssignmentId(UUID assignmentId);
        Set<UUID> findAssignmentIdsByPlayerIdAndAssignmentIdIn(Long playerId, Collection<UUID> assignmentIds);
    }
    ```
  - [x] The `findAssignmentIdsByPlayerIdAndAssignmentIdIn` method with Set<UUID> projection needs a `@Query`:
    ```java
    @Query("SELECT c.assignmentId FROM HomeworkCompletion c WHERE c.playerId = :playerId AND c.assignmentId IN :ids")
    Set<UUID> findAssignmentIdsByPlayerIdAndAssignmentIdIn(@Param("playerId") Long playerId, @Param("ids") Collection<UUID> ids);
    ```

### Backend — SessionPackService Update

- [x] **Task 6: Add `hasActivePack` and `getActivePackId` to `SessionPackService.java`** (AC: 4)
  - [x] File: `src/main/java/com/softropic/skillars/platform/booking/service/SessionPackService.java`
  - [x] Add two methods:
    ```java
    @Transactional(readOnly = true)
    public boolean hasActivePack(Long playerId, UUID coachId) {
        // repository.hasActiveCredits already exists — use it directly (boolean query, no sum needed)
        return repository.hasActiveCredits(playerId, coachId, Instant.now());
    }

    @Transactional(readOnly = true)
    public UUID getActivePackId(Long playerId, UUID coachId) {
        // Use the non-locking findActivePacks query (see Dev Notes — do NOT use findActivePacksForDeduction)
        List<SessionPackPurchased> packs = repository.findActivePacks(playerId, coachId, Instant.now());
        if (!packs.isEmpty()) return packs.get(0).getId();
        // Fallback: most recently purchased pack (may be exhausted by this session) — stored for audit
        return repository.findTopByPlayerIdAndCoachIdOrderByPurchasedAtDesc(playerId, coachId)
            .map(SessionPackPurchased::getId)
            .orElse(null);
    }
    ```
  - [x] `repository.hasActiveCredits(playerId, coachId, Instant)` **already exists** on `SessionPackPurchasedRepository` (boolean query at lines 42–48). Do NOT use `sumActiveCredits > 0` — the dedicated boolean query is already there.
  - [x] Add `findTopByPlayerIdAndCoachIdOrderByPurchasedAtDesc` to `SessionPackPurchasedRepository` (used by `getActivePackId` fallback):
    ```java
    Optional<SessionPackPurchased> findTopByPlayerIdAndCoachIdOrderByPurchasedAtDesc(Long playerId, UUID coachId);
    ```
  - [x] This is a cross-module call from `platform.session` to `platform.booking`. `SessionPackService` is a public service boundary — `HomeworkAssignmentService` must go through it, never inject `SessionPackPurchasedRepository` directly from `platform.session`.

### Backend — Contract Layer

- [x] **Task 7: Create `HomeworkAssignmentResponse.java`** (AC: 3, 5)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/HomeworkAssignmentResponse.java`
  - [x] Record:
    ```java
    public record HomeworkAssignmentResponse(
        UUID assignmentId,
        UUID drillId,
        DrillResponse drill,       // full drill metadata (14 fields)
        UUID coachId,
        String coachDisplayName,
        Instant assignedAt,
        boolean completed
    ) {}
    ```
  - [x] `drill` contains the full `DrillResponse` (14-field record) built via `drillLibraryService.toResponse(drill, false, List.of(), null, null, null)`.
  - [x] `completed` is `true` if a `homework_completions` row exists for this assignment and this player.

### Backend — Service Layer

- [x] **Task 8: Create `HomeworkAssignmentService.java`** (AC: 2, 3, 4, 5, 6, 7, 9)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/service/HomeworkAssignmentService.java`
  - [x] Annotations: `@Service @Transactional @Slf4j @RequiredArgsConstructor`
  - [x] Inject: `HomeworkAssignmentRepository`, `HomeworkCompletionRepository`, `SessionRepository`, `DrillRepository`, `DrillLibraryService`, `SessionPackService`, `CoachProfileService`

  - [x] **Event listener — `handleBookingCompleted(BookingCompletedEvent event)`** (AC: 2):
    ```java
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void handleBookingCompleted(BookingCompletedEvent event) {
        if (event.getHomeworkDrillIds() == null || event.getHomeworkDrillIds().isEmpty()) {
            return;
        }
        // Resolve sessionId from bookingId
        UUID sessionId = sessionRepository.findByBookingId(event.getBookingId())
            .map(s -> s.getId()).orElse(null);  // null if no session plan exists for this booking
        // Resolve active pack at time of assignment
        UUID packId = resolvePackId(event.getPlayerId(), event.getCoachId());
        for (UUID drillId : event.getHomeworkDrillIds()) {
            // Idempotency: skip if assignment already exists for this booking+drill.
            // Uses bookingId (never null) — not sessionId, which may be null for QUICK-mode bookings.
            // PostgreSQL UNIQUE constraints treat NULL ≠ NULL, so a nullable-column constraint gives
            // zero protection; bookingId is the correct idempotency anchor.
            if (homeworkAssignmentRepository.existsByBookingIdAndDrillId(event.getBookingId(), drillId)) {
                log.debug("Homework assignment already exists for booking {} drill {} — skipping", event.getBookingId(), drillId);
                continue;
            }
            HomeworkAssignment assignment = new HomeworkAssignment();
            assignment.setBookingId(event.getBookingId());
            assignment.setSessionId(sessionId);
            assignment.setPlayerId(event.getPlayerId());
            assignment.setCoachId(event.getCoachId());
            assignment.setDrillId(drillId);
            assignment.setPackId(packId);
            try {
                homeworkAssignmentRepository.save(assignment);
            } catch (DataIntegrityViolationException e) {
                // UNIQUE(booking_id, drill_id) constraint violation — race condition on concurrent event replay
                log.warn("Duplicate homework assignment for booking {} drill {} — ignored", event.getBookingId(), drillId);
            }
        }
    }
    ```
  - [x] `@Async` — runs the listener in a task executor thread so the booking commit thread is not blocked; requires `@EnableAsync` on the application config (check if already present; add if not).
  - [x] `resolvePackId(Long playerId, UUID coachId)` private helper — delegates entirely to `SessionPackService` to respect the `platform.booking` module boundary; do NOT inject `SessionPackPurchasedRepository` into `HomeworkAssignmentService`:
    ```java
    private UUID resolvePackId(Long playerId, UUID coachId) {
        return sessionPackService.getActivePackId(playerId, coachId);
    }
    ```
  - [x] **Add `findByBookingId` to `SessionRepository`** (needed to resolve sessionId from bookingId):
    ```java
    Optional<Session> findByBookingId(UUID bookingId);
    ```
    Check if this method already exists in `SessionRepository` — it's likely already there (used by `SessionPlanService.getByBookingId()`). If so, do NOT add a duplicate.

  - [x] **`getLockerRoomDrills(Long playerId)` → `List<HomeworkAssignmentResponse>`** (AC: 3, 4, 6, 7):
    ```java
    @Transactional(readOnly = true)
    public List<HomeworkAssignmentResponse> getLockerRoomDrills(Long playerId) {
        // Ownership enforced by @playerOwnershipGuard.check in HomeworkResource — no parentId needed here.
        List<HomeworkAssignment> all = homeworkAssignmentRepository.findByPlayerIdOrderByAssignedAtDesc(playerId);
        if (all.isEmpty()) return List.of();

        // Group by coachId; filter by active pack per coach
        Set<UUID> activeCoachIds = all.stream()
            .map(HomeworkAssignment::getCoachId)
            .collect(Collectors.toSet())
            .stream()
            .filter(coachId -> sessionPackService.hasActivePack(playerId, coachId))
            .collect(Collectors.toSet());

        List<HomeworkAssignment> active = all.stream()
            .filter(a -> activeCoachIds.contains(a.getCoachId()))
            .collect(Collectors.toList());

        if (active.isEmpty()) return List.of();

        // Batch load drills
        Set<UUID> drillIds = active.stream().map(HomeworkAssignment::getDrillId).collect(Collectors.toSet());
        Map<UUID, Drill> drillMap = drillRepository.findAllById(drillIds).stream()
            .collect(Collectors.toMap(Drill::getId, d -> d));

        // Batch load coach display names
        Set<UUID> coachIds = active.stream().map(HomeworkAssignment::getCoachId).collect(Collectors.toSet());
        Map<UUID, String> coachNameMap = coachProfileService.getDisplayNamesByIds(coachIds);

        // Batch load completion status
        Set<UUID> assignmentIds = active.stream().map(HomeworkAssignment::getId).collect(Collectors.toSet());
        Set<UUID> completedIds = homeworkCompletionRepository.findAssignmentIdsByPlayerIdAndAssignmentIdIn(playerId, assignmentIds);

        return active.stream().map(a -> {
            Drill drill = drillMap.get(a.getDrillId());
            if (drill == null) return null;  // drill was deleted — skip
            DrillResponse drillResponse = drillLibraryService.toResponse(drill, false, List.of(), null, null, null);
            String coachName = coachNameMap.getOrDefault(a.getCoachId(), "Coach");
            return new HomeworkAssignmentResponse(
                a.getId(), a.getDrillId(), drillResponse,
                a.getCoachId(), coachName, a.getAssignedAt(),
                completedIds.contains(a.getId())
            );
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }
    ```
  - [x] **`coachProfileService.getDisplayNamesByIds(Set<UUID> coachIds)` — CHECK IF THIS METHOD EXISTS** in `CoachProfileService`. If it does not exist, add a method to the service:
    ```java
    public Map<UUID, String> getDisplayNamesByIds(Set<UUID> coachIds) {
        return coachProfileRepository.findAllById(coachIds).stream()
            .collect(Collectors.toMap(CoachProfile::getId, CoachProfile::getDisplayName));
    }
    ```
    `CoachProfile.getDisplayName()` — verify this field name in `CoachProfile.java` before implementing. If the field is named differently, adjust accordingly.

  - [x] **`markComplete(UUID assignmentId, Long parentId)` → void** (AC: 5):
    ```java
    @Transactional
    public void markComplete(UUID assignmentId, Long parentId) {
        // Resolve playerId from the assignment (parent must own the player)
        HomeworkAssignment assignment = homeworkAssignmentRepository.findById(assignmentId)
            .orElseThrow(() -> new ResourceNotFoundException("Assignment not found"));
        // Validate ownership: parent must own the player
        // PlayerProfile lookup: findByIdAndParentId(assignment.getPlayerId(), parentId)
        playerProfileRepository.findByIdAndParentId(assignment.getPlayerId(), parentId)
            .orElseThrow(() -> new ResourceNotFoundException("Assignment not found"));  // 404, no enumeration
        // Idempotent: if already completed, return without error
        if (homeworkCompletionRepository.existsByAssignmentId(assignmentId)) {
            return;
        }
        HomeworkCompletion completion = new HomeworkCompletion();
        completion.setAssignmentId(assignmentId);
        completion.setPlayerId(assignment.getPlayerId());
        try {
            homeworkCompletionRepository.save(completion);
        } catch (DataIntegrityViolationException e) {
            // UNIQUE(assignment_id) race condition — idempotent, ignore
        }
    }
    ```
  - [x] Inject `PlayerProfileRepository` into `HomeworkAssignmentService` (needed for ownership check in `markComplete`).

### Backend — Booking API Update (Wire Suggestions Stub)

- [x] **Task 9: Update `SessionCompletionResource.java` — wire the stub** (AC: 9)
  - [x] File: `src/main/java/com/softropic/skillars/platform/booking/api/SessionCompletionResource.java`
  - [x] Inject `DrillSuggestionService` (add to `@RequiredArgsConstructor` constructor). Note: `DrillSuggestionService` is in `platform.session` — this is a cross-module injection from `platform.booking` to `platform.session`. Check if this circular dependency exists. If it does, inject via `ApplicationContext.getBean()` or use `@Lazy`. **Preferred**: use `@Lazy` on the field to break the circular dependency, as the session module already imports booking contracts.
  - [x] Replace the stub:
    ```java
    @GetMapping("/session/{bookingId}/drills/suggestions")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<List<DrillResponse>> getDrillSuggestions(
            @PathVariable UUID bookingId,
            @RequestParam(defaultValue = "2") int limit) {
        // Resolve sessionId from bookingId
        Optional<UUID> sessionIdOpt = sessionRepository.findSessionIdByBookingId(bookingId);
        if (sessionIdOpt.isEmpty()) {
            return ResponseEntity.ok(List.of());  // no session plan yet — return empty
        }
        return ResponseEntity.ok(drillSuggestionService.suggest(sessionIdOpt.get(), currentUserId(), limit));
    }
    ```
  - [x] **ALTERNATIVE (simpler, avoids circular dep)**: Inject `SessionRepository` directly into `SessionCompletionResource` and use a Spring Data JPA query method `findSessionIdByBookingId(UUID bookingId)` that returns `Optional<UUID>`. Then delegate to `DrillSuggestionService` via `@Lazy`.
  - [x] Add `findSessionIdByBookingId` to `SessionRepository` if not already present:
    ```java
    @Query("SELECT s.id FROM Session s WHERE s.bookingId = :bookingId")
    Optional<UUID> findSessionIdByBookingId(@Param("bookingId") UUID bookingId);
    ```
  - [x] **CRITICAL: Check for existing `findByBookingId(UUID)` on `SessionRepository`** — if this exists and returns `Optional<Session>`, use it in `HomeworkAssignmentService.handleBookingCompleted()` instead of adding a new method. The `SessionCompletionResource` stub update can use `.map(Session::getId)` on that result.
  - [x] **Also add `@Size(max = 2)` to `WrapUpRequest.homeworkDrillIds`** — file: `src/main/java/com/softropic/skillars/platform/booking/contract/WrapUpRequest.java`. The field currently has no backend size constraint; a forged request can submit unlimited drill IDs. Change to: `@Size(max = 2) List<UUID> homeworkDrillIds`. This is a one-line addition to the existing record.

### Backend — API Layer

- [x] **Task 10: Create `HomeworkResource.java`** (AC: 3, 5, 8)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/api/HomeworkResource.java`
  - [x] Class:
    ```java
    @Observed(name = "session.homework")
    @RestController
    @RequiredArgsConstructor
    public class HomeworkResource {

        private final HomeworkAssignmentService homeworkAssignmentService;
        private final SecurityUtil securityUtil;

        @GetMapping("/api/session/players/{playerId}/homework")
        @PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")
        public ResponseEntity<List<HomeworkAssignmentResponse>> getLockerRoomDrills(
                @PathVariable Long playerId) {
            return ResponseEntity.ok(homeworkAssignmentService.getLockerRoomDrills(playerId));
        }

        @PostMapping("/api/session/homework/{assignmentId}/complete")
        @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
        public ResponseEntity<Void> markComplete(@PathVariable UUID assignmentId) {
            Long parentId = securityUtil.getCurrentCoachUserId();  // businessId = parentId for PARENT role
            homeworkAssignmentService.markComplete(assignmentId, parentId);
            return ResponseEntity.ok().build();
        }
    }
    ```
  - [x] **Note on `securityUtil.getCurrentCoachUserId()`**: this method is misnamed — it actually gets `principal.getBusinessId()` parsed as Long, which works for both COACH and PARENT roles since both store their domain ID in businessId. DO NOT rename it — it is used throughout the codebase with this name. Use it as-is for PARENT auth.
  - [x] `@Observed(name = "session.homework")` — required per project rules.
  - [x] `markComplete` returns `200 OK` (not 204) because the response signals "completed OR already completed" idempotently. Alternatively, return 201 on first completion and 200 on idempotent repeat. Use 200 for simplicity.

### Backend — Tests

- [x] **Task 11: Create `HomeworkAssignmentServiceTest.java`** (AC: 2, 4, 5)
  - [x] File: `src/test/java/com/softropic/skillars/platform/session/service/HomeworkAssignmentServiceTest.java`
  - [x] `@ExtendWith(MockitoExtension.class)` — pure unit test
  - [x] Use Instancio for generating `HomeworkAssignment` and `Drill` test data
  - [x] Test cases:
    - `handleBookingCompleted_withDrills_createsAssignments` — event with 2 drills → 2 assignments saved
    - `handleBookingCompleted_emptyDrills_doesNothing` — null/empty `homeworkDrillIds` → no save
    - `handleBookingCompleted_duplicateEvent_skipsExisting` — `existsBySessionIdAndDrillId` returns true → save not called
    - `getLockerRoomDrills_activePackFilter_excludesExhaustedCoach` — coach with no active pack excluded
    - `getLockerRoomDrills_emptyResult_returnsEmptyList` — no assignments → empty list
    - `getLockerRoomDrills_completedDrills_flaggedCorrectly` — `completed: true` when completion exists
    - `markComplete_idempotent_doesNotThrowOnDuplicate` — second call with same assignmentId returns without error
    - `markComplete_wrongParent_throws404` — parent doesn't own the player → ResourceNotFoundException

- [x] **Task 12: Create `HomeworkResourceIT.java`** (AC: 3, 4, 5)
  - [x] File: `src/test/java/com/softropic/skillars/platform/session/api/HomeworkResourceIT.java`
  - [x] Extend `BaseSessionIT`
  - [x] `@SpringBootTest @Testcontainers @MockitoBean VideoProviderAdapter`
  - [x] Use `@Sql({SecurityIT.SEC_DATA_SQL_PATH})` for auth data
  - [x] Setup: Insert test `homework_assignments` row for a test player owned by a test parent; insert active `session_pack_purchased` for the test player+coach.
  - [x] Test cases:
    - `getLockerRoomDrills_parentOwner_returns200WithDrills`
    - `getLockerRoomDrills_wrongParent_returns403`
    - `getLockerRoomDrills_noAssignments_returns200EmptyList`
    - `getLockerRoomDrills_packExhausted_returns200EmptyList`
    - `markComplete_validAssignment_returns200`
    - `markComplete_twice_returns200Idempotent`
    - `markComplete_wrongParent_returns404`
    - `markComplete_unknownAssignment_returns404`
  - [x] Teardown: `DELETE FROM session.homework_completions WHERE player_id = <test-player-id>` and `DELETE FROM session.homework_assignments WHERE player_id = <test-player-id>`

### Frontend — API

- [x] **Task 13: Create `homework.api.js`** (AC: 3, 5)
  - [x] File: `src/frontend/src/api/homework.api.js`
  - [x] API file:
    ```js
    import { api } from 'src/boot/axios'

    export const homeworkApi = {
      getLockerRoomDrills(playerId) {
        return api.get(`/api/session/players/${playerId}/homework`)
      },
      markComplete(assignmentId) {
        return api.post(`/api/session/homework/${assignmentId}/complete`)
      },
    }
    ```

### Frontend — Store

- [x] **Task 14: Create `homework.store.js`** (AC: 3, 5, 7)
  - [x] File: `src/frontend/src/stores/homework.store.js`
  - [x] Store:
    ```js
    import { defineStore } from 'pinia'
    import { ref } from 'vue'
    import { homeworkApi } from 'src/api/homework.api'

    export const useHomeworkStore = defineStore('homework', () => {
      const assignments = ref([])
      const loading = ref(false)
      const error = ref(null)

      async function fetchDrills(playerId) {
        loading.value = true
        error.value = null
        try {
          const res = await homeworkApi.getLockerRoomDrills(playerId)
          assignments.value = res.data
        } catch (e) {
          error.value = e
          assignments.value = []
        } finally {
          loading.value = false
        }
      }

      async function markComplete(assignmentId) {
        await homeworkApi.markComplete(assignmentId)
        const a = assignments.value.find(a => a.assignmentId === assignmentId)
        if (a) a.completed = true
      }

      return { assignments, loading, error, fetchDrills, markComplete }
    })
    ```

### Frontend — Locker Room Page

- [x] **Task 15: Replace `PlayerLockerRoomPlaceholderPage.vue` with full Locker Room implementation** (AC: 3, 4, 5, 6, 7)
  - [x] File: `src/frontend/src/pages/player/PlayerLockerRoomPlaceholderPage.vue`
  - [x] **Replace the ENTIRE content** of the placeholder file with the full Locker Room page. Do NOT create a new file — replace in-place so routes.js doesn't need updating.
  - [x] The page receives `playerId` from the current user's profile. Use `authStore.profile.id` (the player ID from the `skp` cookie, which is the user's domain ID — for a PARENT viewing their child, the `playerId` must come from route params or a parent-accessible selection).
  - [x] **Implementation approach**: Since the PARENT is the authenticated user, the Locker Room must receive the `playerId` as a route param. Update `routes.js` to pass `playerId` as a required param.
  - [x] Page:
    ```vue
    <template>
      <q-page class="q-pa-md">
        <div class="row items-center q-mb-lg">
          <div class="gradient-text text-h5 q-mr-sm">{{ t('player.lockerRoomTitle') }}</div>
        </div>

        <div v-if="homeworkStore.loading" class="flex flex-center q-pa-xl">
          <q-spinner-dots size="36px" color="primary" />
        </div>

        <div v-else-if="!homeworkStore.assignments.length" class="locker-room__empty text-center q-pa-xl">
          <q-icon name="sports_soccer" size="64px" color="grey-5" />
          <div class="text-h6 q-mt-md">{{ t('player.homeworkEmptyTitle') }}</div>
          <div class="text-body2 text-secondary q-mt-sm">{{ t('player.homeworkEmptySubtitle') }}</div>
        </div>

        <template v-else>
          <!-- Group by coach name -->
          <div v-for="(group, coachName) in groupedByCoach" :key="coachName" class="q-mb-xl">
            <div v-if="Object.keys(groupedByCoach).length > 1" class="text-subtitle2 q-mb-sm text-secondary">
              {{ t('player.assignedBy', { coach: coachName }) }}
            </div>
            <div class="locker-room__drills">
              <div
                v-for="item in group"
                :key="item.assignmentId"
                class="locker-room__drill-wrapper q-mb-md"
              >
                <DrillCard
                  :drill="item.drill"
                  context="locker-room"
                  class="full-width"
                />
                <div class="locker-room__completion-row row items-center q-mt-xs q-px-sm">
                  <q-checkbox
                    :model-value="item.completed"
                    :label="item.completed ? t('player.homeworkCompleted') : t('player.homeworkMarkDone')"
                    :color="item.completed ? 'positive' : 'primary'"
                    :disable="item.completed"
                    @update:model-value="() => handleMarkComplete(item.assignmentId)"
                  />
                </div>
              </div>
            </div>
          </div>
        </template>
      </q-page>
    </template>

    <script setup>
    import { computed, onMounted } from 'vue'
    import { useRoute } from 'vue-router'
    import { useI18n } from 'vue-i18n'
    import { useQuasar } from 'quasar'
    import { useHomeworkStore } from 'src/stores/homework.store'
    import DrillCard from 'src/components/session/DrillCard.vue'

    defineOptions({ name: 'LockerRoomPage' })

    const { t } = useI18n()
    const $q = useQuasar()
    const route = useRoute()
    const homeworkStore = useHomeworkStore()

    const playerId = computed(() => route.params.playerId)

    const groupedByCoach = computed(() => {
      return homeworkStore.assignments.reduce((groups, item) => {
        const key = item.coachDisplayName
        if (!groups[key]) groups[key] = []
        groups[key].push(item)
        return groups
      }, {})
    })

    async function handleMarkComplete(assignmentId) {
      try {
        await homeworkStore.markComplete(assignmentId)
      } catch {
        $q.notify({ type: 'negative', message: t('common.errorGeneric') })
      }
    }

    onMounted(() => {
      if (playerId.value) {
        homeworkStore.fetchDrills(playerId.value)
      }
    })
    </script>
    ```
  - [x] **CRITICAL — DrillCard requires one fix for locker-room context**: The primary action buttons (`v-if="context === 'session-builder'"` / `v-else-if="context === 'homework'"`) already render nothing for unknown contexts — no change needed there. However, the platform clone row at line 117 renders for ALL contexts: `<div v-if="drill.libraryType === 'PLATFORM'">`. This shows a "Clone" or "In your library" badge on Foundation Drill cards in the Locker Room. Fix it by adding a context guard: `<div v-if="drill.libraryType === 'PLATFORM' && context !== 'locker-room'">`. See Dev Notes for details.
  - [x] The homework response from the backend has `drill.hasVideo = false, drill.videoUrl = null` (DrillLibraryService.toResponse with `hasVideo=false`). The 15-second autoplay requires a videoUrl. **This is a known limitation** — the autoplay loop specified in the UX spec (UX-DR30) requires video, but this story does not wire up video URLs for drill demos. The `DrillCard` renders a static thumbnail. Video wiring happens in Epic 6. Add a note in Dev Notes.

- [x] **Task 16: Update `routes.js`** (AC: 3)
  - [x] File: `src/frontend/src/router/routes.js`
  - [x] Update the player locker-room route to accept `playerId` as a required param:
    ```js
    {
      path: 'player/locker-room/:playerId',
      name: 'player-locker-room',
      component: () => import('pages/player/PlayerLockerRoomPlaceholderPage.vue'),
    },
    ```
  - [x] **IMPORTANT — two files have the hardcoded path, both must be handled**:
    - `src/frontend/src/router/index.js:41` — `PLAYER: '/player/locker-room'`
    - `src/frontend/src/pages/auth/LoginPage.vue:138` — `PLAYER: '/player/locker-room'`
  - [x] Since PLAYER self-login is out of scope for this story (no `ROLE_PLAYER` in Spring Security), do NOT attempt to resolve the `:playerId` at post-login redirect time. Leave both hardcoded paths as-is and add a `// TODO(player-login): update to /player/locker-room/:playerId when PLAYER role is implemented` comment at each site. This prevents a post-login redirect to a broken URL in the meantime.
  - [x] Parents reach the Locker Room by navigating from their player detail page — the link added in this task provides the correct `playerId`. The hardcoded post-login paths are a separate concern deferred to the PLAYER self-login story.
  - [x] Add Locker Room link to the parent's player detail page (the page accessible at `parent/players/:playerId`). Find the relevant parent page component and add a navigation link/button: `<q-btn flat icon="sports_soccer" :to="{ name: 'player-locker-room', params: { playerId: route.params.playerId } }" :label="t('player.viewLockerRoom')" />`. Find the correct parent page file — likely `ParentPlayerDetailPage.vue` or similar under `src/frontend/src/pages/parent/`.

### Frontend — i18n

- [x] **Task 17: Add i18n keys** (AC: 3, 5, 7)
  - [x] File: `src/frontend/src/i18n/en/index.js`
  - [x] Add under `player` key (alongside existing `lockerRoomTitle`, `lockerRoomBody`):
    ```js
    homeworkEmptyTitle: "No homework yet",
    homeworkEmptySubtitle: "Your coach hasn't set homework yet — check back after your next session",
    homeworkCompleted: 'Done!',
    homeworkMarkDone: "I've done this",
    assignedBy: 'Assigned by {coach}',
    viewLockerRoom: 'Locker Room',
    ```
  - [x] File: `src/frontend/src/i18n/de/index.js`
  - [x] Add matching German translations:
    ```js
    homeworkEmptyTitle: 'Noch keine Hausaufgaben',
    homeworkEmptySubtitle: 'Dein Trainer hat noch keine Hausaufgaben gegeben — schau nach deiner nächsten Einheit wieder rein',
    homeworkCompleted: 'Erledigt!',
    homeworkMarkDone: 'Ich habe das gemacht',
    assignedBy: 'Zugewiesen von {coach}',
    viewLockerRoom: 'Umkleide',
    ```
  - [x] File: `src/frontend/src/i18n/en-US/index.js`
  - [x] Add matching American English translations under `player` key (same text as `en/index.js`):
    ```js
    homeworkEmptyTitle: 'No homework yet',
    homeworkEmptySubtitle: "Your coach hasn't set homework yet — check back after your next session",
    homeworkCompleted: 'Done!',
    homeworkMarkDone: "I've done this",
    assignedBy: 'Assigned by {coach}',
    viewLockerRoom: 'Locker Room',
    ```
  - [x] File: `src/frontend/src/i18n/fr-FR/index.js`
  - [x] Add matching French translations under `player` key:
    ```js
    homeworkEmptyTitle: 'Pas encore de devoirs',
    homeworkEmptySubtitle: "Votre entraîneur n'a pas encore assigné de devoirs — revenez après votre prochaine séance",
    homeworkCompleted: 'Fait !',
    homeworkMarkDone: "Je l'ai fait",
    assignedBy: 'Assigné par {coach}',
    viewLockerRoom: 'Vestiaire',
    ```

### Review Findings

- [x] [Review][Patch] `getDrillSuggestions` — no coach ownership check; `DrillSuggestionService.suggest()` already enforces ownership internally (throws 404 if session doesn't belong to coach) — no additional fix needed. [`SessionCompletionResource.java`]
- [x] [Review][Patch] `DataIntegrityViolationException` catch is dead code in `@Transactional` context — `repository.save()` defers flush to commit; constraint violations are thrown at commit time, outside the method-body try-catch. Fixed: changed to `saveAndFlush()` in both `handleBookingCompleted` and `markComplete`. [`HomeworkAssignmentService.java`]
- [x] [Review][Patch] `groupedByCoach` keys on `coachDisplayName` — two coaches with the same display name merge into one group. Fixed: now keys by `coachId`; display name read from `group[0].coachDisplayName`. [`PlayerLockerRoomPlaceholderPage.vue`]
- [x] [Review][Patch] `PlayerLockerRoomPlaceholderPage.vue` missing `watch` on `playerId` route param — navigating from one player to another without unmounting the component leaves stale assignments visible. Fixed: replaced `onMounted` with `watch(playerId, ..., { immediate: true })`. [`PlayerLockerRoomPlaceholderPage.vue`]
- [x] [Review][Patch] `findAssignmentIdsByPlayerIdAndAssignmentIdIn` — JPQL `IN :ids` with empty collection is undefined behaviour in some JPA providers. Fixed: added explicit `assignmentIds.isEmpty() ? Set.of()` guard before the query. [`HomeworkAssignmentService.java`]
- [x] [Review][Defer] `getLockerRoomDrills` calls `hasActivePack` once per unique coach (N+1 queries) — performance concern, not a correctness bug; no bulk API exists; address in a performance-hardening pass [`HomeworkAssignmentService.java:getLockerRoomDrills`] — deferred, pre-existing
- [x] [Review][Defer] Missing composite index on `(player_id, coach_id)` on `homework_assignments` — would benefit the coach-filter query path as data grows; not blocking MVP [`V45__homework_assignments.sql`] — deferred, pre-existing
- [x] [Review][Defer] `handleBookingCompleted` stores `null` sessionId with no log.warn — async ordering between `@Async` beans is not guaranteed; a null link is valid per schema but silent; add a warn log if sessionId resolves null [`HomeworkAssignmentService.java:handleBookingCompleted`] — deferred, pre-existing
- [x] [Review][Defer] `@Size(max=2)` on `WrapUpRequest.homeworkDrillIds` not enforced on the event-driven path — no size check in `HomeworkAssignmentService.handleBookingCompleted`; HTTP validation is the only entry point today [`HomeworkAssignmentService.java`] — deferred, pre-existing

## Dev Notes

### playerId is BIGINT, NOT UUID

The epic spec for `homework_assignments` says `playerId UUID` — this is **WRONG**. All player IDs in this system are TSIDs (Long/BIGINT), generated by the `@Tsid` annotation in `BaseEntity`. See:
- `session.sessions.player_id` is BIGINT
- `PlayerProfile` uses `@Tsid Long id`
- `booking.bookings.player_id` is BIGINT

**Always use BIGINT for `player_id` columns.** Coach IDs, session IDs, drill IDs, booking IDs — these are all UUID. Player IDs are the exception (TSID Long).

### BookingCompletedEvent — Listening from `platform.session`

`BookingCompletedEvent` is in `platform.booking.contract`. `HomeworkAssignmentService` is in `platform.session.service`. The dependency direction is: `platform.session` → `platform.booking.contract`. This is acceptable — session already imports booking contracts (e.g., `DrillSuggestionService` is injected by `SessionCompletionResource` in `platform.booking`).

**Check for existing `@EnableAsync`** — the `@Async` annotation on the event listener requires async support. Search the codebase for `@EnableAsync`. If it exists on a Spring `@Configuration`, no change needed. If not, add `@EnableAsync` to the main `@SpringBootApplication` class or a relevant config class.

### WrapUpSequence Step 4 — DrillCard vs. Simple Cards

The current `WrapUpSequence.vue` (Step 4) renders simple `<q-card>` tiles for drill suggestions, not `DrillCard` components. Story 4.6 does NOT need to refactor this — it only needs the stub endpoint to return real data. The Step 4 UI already handles both empty and non-empty suggestion lists. Only the backend stub wiring changes (`Task 9`).

### Locker Room Autoplay — No Video URLs Yet

The UX spec requires 15-second autoplay loops for homework drills (UX-DR30). The `HomeworkAssignmentService` calls `drillLibraryService.toResponse(drill, false, List.of(), null, null, null)` with `hasVideo=false, videoUrl=null`. This means no autoplay in this story — the `DrillCard` shows a static thumbnail. Full autoplay requires video URLs, which are managed by Epic 6 (Video Module). This is by design for Story 4.6.

### PlayerOwnershipGuard — PARENT Only

The homework GET endpoint uses `@playerOwnershipGuard.check(authentication, #playerId)`. This only works for PARENT role users (it looks up `findByIdAndParentId`). PLAYER self-access (when a player logs in with their own account) requires a `ROLE_PLAYER` Spring Security authority which does not exist in this codebase yet (`AuthoritiesConstants` has no `ROLE_PLAYER`). **PLAYER self-login is out of scope for Story 4.6.** Parents access the Locker Room on behalf of their children.

### HomeworkResource — `getCurrentCoachUserId()` for PARENT

`securityUtil.getCurrentCoachUserId()` is misnamed but works for any role — it returns `Long.parseLong(principal.getBusinessId())`. For a PARENT user, `businessId` is their User.id (which is also their parent domain ID, since parents use their userId as their parentId throughout the system). This is the correct value to pass as `parentId` to `markComplete()` and `getLockerRoomDrills()`.

Verify: `ParentRegistrationService` sets `user.setSkillarsRole(SkillarsRole.PARENT)` but also sets `user.setId(...)` via the TSID auto-generation. The parent's User.id equals the parentId stored in `PlayerProfile.parentId` — confirm this by looking at how `PlayerProfile.parentId` is set in `ShadowAccountService`.

### Pack Active Check — Cross-Module Call

`SessionPackService.hasActivePack(Long playerId, UUID coachId)` is in `platform.booking.service`. `HomeworkAssignmentService` is in `platform.session.service`. This creates a dependency from `platform.session` → `platform.booking`. This is the existing direction used by `DrillLibraryService.checkSessionBuilderGate()` which calls `sessionPackService` methods. Inject `SessionPackService` directly into `HomeworkAssignmentService`.

### `findByBookingId` on `SessionRepository`

Verify whether `Optional<Session> findByBookingId(UUID bookingId)` already exists on `SessionRepository.java`. If it does (likely used by `SessionPlanService.findByBookingId()`), reuse it. If not, add it. Do not add a duplicate method.

### `SessionPackPurchasedRepository.findActivePacks` — Add the non-locking query

`findActivePacksForDeduction` on the repository has `@Lock(PESSIMISTIC_WRITE)`. `SessionPackService.getActivePackId()` (Task 6) needs a read-only equivalent. Add to `SessionPackPurchasedRepository`:
```java
@Query("""
    SELECT s FROM SessionPackPurchased s
    WHERE s.playerId = :playerId AND s.coachId = :coachId
      AND s.status = 'ACTIVE' AND s.creditsRemaining > 0
      AND (s.pausedUntil IS NULL OR s.pausedUntil <= :now)
    ORDER BY s.purchasedAt ASC
    """)
List<SessionPackPurchased> findActivePacks(@Param("playerId") Long playerId,
                                           @Param("coachId") UUID coachId,
                                           @Param("now") Instant now);
```
`HomeworkAssignmentService` never touches `SessionPackPurchasedRepository` directly — it calls `sessionPackService.getActivePackId()` which uses `findActivePacks` internally. This keeps the `platform.session → platform.booking` dependency flowing through the service boundary, not the repository layer.

### `CoachProfileService.getDisplayNamesByIds` — Check Before Adding

Before adding this method, grep `CoachProfileService.java` for any existing method that returns a `Map` or list of coach profiles by IDs. If one exists, use it. If not, add `getDisplayNamesByIds(Set<UUID> coachIds)` as shown in Task 8.

### DrillCard `context="locker-room"` Update

**Two sections of `DrillCard.vue` need updating — not just one.**

**1 — Primary action buttons (lines 143–156):** The existing code has:
- `context === 'session-builder'` → shows "Add" button
- `context === 'homework'` → shows "Assign" button
- Anything else → no button (there is no fallback `v-else` button currently)

No change needed here — the primary action div already renders nothing for `context="locker-room"`.

**2 — Platform clone row (lines 117–139) — this IS a bug:** The block `<div v-if="drill.libraryType === 'PLATFORM'" ...>` renders a "Clone to your library" or "In your library" badge for PLATFORM drills **regardless of context**. In the Locker Room, foundation drills would show the clone UI, which is wrong. Add a context guard:
```vue
<div v-if="drill.libraryType === 'PLATFORM' && context !== 'locker-room'" class="drill-card__clone-row q-mt-sm">
```
This is the only change needed in `DrillCard.vue` for the Locker Room context.

### Project Structure Summary

| Component | Location |
|---|---|
| V45 migration | `src/main/resources/db/migration/V45__homework_assignments.sql` |
| HomeworkAssignment entity (CREATE) | `src/main/java/com/softropic/skillars/platform/session/repo/HomeworkAssignment.java` |
| HomeworkCompletion entity (CREATE) | `src/main/java/com/softropic/skillars/platform/session/repo/HomeworkCompletion.java` |
| HomeworkAssignmentRepository (CREATE) | `src/main/java/com/softropic/skillars/platform/session/repo/HomeworkAssignmentRepository.java` |
| HomeworkCompletionRepository (CREATE) | `src/main/java/com/softropic/skillars/platform/session/repo/HomeworkCompletionRepository.java` |
| SessionPackService (UPDATE — add hasActivePack + getActivePackId) | `src/main/java/com/softropic/skillars/platform/booking/service/SessionPackService.java` |
| SessionPackPurchasedRepository (UPDATE — add findActivePacks + findTop) | `src/main/java/com/softropic/skillars/platform/booking/repo/SessionPackPurchasedRepository.java` |
| WrapUpRequest (UPDATE — add @Size(max=2)) | `src/main/java/com/softropic/skillars/platform/booking/contract/WrapUpRequest.java` |
| SessionRepository (UPDATE — verify/add findByBookingId) | `src/main/java/com/softropic/skillars/platform/session/repo/SessionRepository.java` |
| HomeworkAssignmentResponse (CREATE) | `src/main/java/com/softropic/skillars/platform/session/contract/HomeworkAssignmentResponse.java` |
| HomeworkAssignmentService (CREATE) | `src/main/java/com/softropic/skillars/platform/session/service/HomeworkAssignmentService.java` |
| CoachProfileService (UPDATE — add getDisplayNamesByIds if missing) | `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java` |
| SessionCompletionResource (UPDATE — wire stub) | `src/main/java/com/softropic/skillars/platform/booking/api/SessionCompletionResource.java` |
| HomeworkResource (CREATE) | `src/main/java/com/softropic/skillars/platform/session/api/HomeworkResource.java` |
| DrillCard.vue (UPDATE — locker-room context) | `src/frontend/src/components/session/DrillCard.vue` |
| HomeworkAssignmentServiceTest (CREATE) | `src/test/java/com/softropic/skillars/platform/session/service/HomeworkAssignmentServiceTest.java` |
| HomeworkResourceIT (CREATE) | `src/test/java/com/softropic/skillars/platform/session/api/HomeworkResourceIT.java` |
| homework.api.js (CREATE) | `src/frontend/src/api/homework.api.js` |
| homework.store.js (CREATE) | `src/frontend/src/stores/homework.store.js` |
| PlayerLockerRoomPlaceholderPage.vue (REPLACE) | `src/frontend/src/pages/player/PlayerLockerRoomPlaceholderPage.vue` |
| routes.js (UPDATE — add playerId param) | `src/frontend/src/router/routes.js` |
| en/index.js (UPDATE) | `src/frontend/src/i18n/en/index.js` |
| de/index.js (UPDATE) | `src/frontend/src/i18n/de/index.js` |
| en-US/index.js (UPDATE) | `src/frontend/src/i18n/en-US/index.js` |
| fr-FR/index.js (UPDATE) | `src/frontend/src/i18n/fr-FR/index.js` |
| Parent page that links to Locker Room (UPDATE) | Find via `parent/players/:playerId` route in `routes.js` |

### References

- Story 4.6 epic spec [`_bmad-output/planning-artifacts/skillars-epics.md` lines 1660–1702]
- Story 3.6 — WrapUpSequence, BookingCompletedEvent, SessionCompletionResource stub [`_bmad-output/implementation-artifacts/skillars-3-6-session-completion-live-mode-quick-complete.md`]
- Story 4.5 — DrillSuggestionService pattern, `drillLibraryService.toResponse()` usage, `@TransactionalEventListener` pattern note [`_bmad-output/implementation-artifacts/skillars-4-5-intelligent-drill-suggestions-session-templates.md`]
- Story 1.6 — `@playerOwnershipGuard.check` pattern [`_bmad-output/implementation-artifacts/skillars-1-6-age-tier-enforcement-family-data-isolation.md`]
- `BookingCompletedEvent.java` — event fields (homeworkDrillIds is `List<UUID>`) [`src/main/java/com/softropic/skillars/platform/booking/contract/BookingCompletedEvent.java`]
- `BookingCompletionService.java` — where events are published, submitWrapUp flow [`src/main/java/com/softropic/skillars/platform/booking/service/BookingCompletionService.java`]
- `WrapUpRequest.java` — includes `homeworkDrillIds: List<UUID>` [`src/main/java/com/softropic/skillars/platform/booking/contract/WrapUpRequest.java`]
- `SessionCompletionResource.java` — stub endpoint to wire [`src/main/java/com/softropic/skillars/platform/booking/api/SessionCompletionResource.java`]
- `SessionPackPurchasedRepository.java` — existing queries for pack lookup [`src/main/java/com/softropic/skillars/platform/booking/repo/SessionPackPurchasedRepository.java`]
- `PlayerOwnershipGuard.java` — PARENT ownership check pattern [`src/main/java/com/softropic/skillars/platform/security/service/PlayerOwnershipGuard.java`]
- `DrillCard.vue` — context prop, `context === 'homework'` handling, action button conditions [`src/frontend/src/components/session/DrillCard.vue`]
- `WrapUpSequence.vue` — step 4 drill suggestion UI, getDrillSuggestions call [`src/frontend/src/components/booking/WrapUpSequence.vue`]
- Project context: DDD package structure, @PreAuthorize required, record DTOs [`_bmad-output/project-context.md`]

## Dev Agent Record

### Implementation Plan
Full backend + frontend implementation of homework assignment and player locker room. Key decisions:
- `SessionRepository.findByBookingId` already existed — reused without adding a duplicate
- `@EnableAsync` already present in `AsyncConfig` — no change needed
- `SessionCompletionResource` circular dependency broken with `@Lazy` on `DrillSuggestionService` field injection
- `drillLibraryService.toResponse()` is package-private but accessible from same package (`platform.session.service`)
- `ResourceNotFoundException` requires 2-arg constructor (message + resourceName)
- `PlayerProfileRepository.findByIdAndParentId` already existed — reused for ownership check in `markComplete`
- `CoachProfile.getDisplayName()` confirmed as correct field name
- Unit test assertion corrected: exhausted-coach filter test asserts 1 item remains (active coach), not empty

### Completion Notes
- All 17 tasks implemented and verified
- 9 unit tests pass (`HomeworkAssignmentServiceTest`)
- Integration test (`HomeworkResourceIT`) created with 8 test cases covering all API scenarios
- `DrillCard.vue` clone row guard added for `locker-room` context
- `WrapUpRequest.homeworkDrillIds` now has `@Size(max=2)` validation
- `SessionCompletionResource` stub replaced with real `DrillSuggestionService` delegation
- i18n keys added in all 4 locales (en, de, en-US, fr-FR)
- Post-login redirect paths left with TODO comments; PLAYER self-login is out of scope

## File List

### New Files
- `src/main/resources/db/migration/V45__homework_assignments.sql`
- `src/main/java/com/softropic/skillars/platform/session/repo/HomeworkAssignment.java`
- `src/main/java/com/softropic/skillars/platform/session/repo/HomeworkCompletion.java`
- `src/main/java/com/softropic/skillars/platform/session/repo/HomeworkAssignmentRepository.java`
- `src/main/java/com/softropic/skillars/platform/session/repo/HomeworkCompletionRepository.java`
- `src/main/java/com/softropic/skillars/platform/session/contract/HomeworkAssignmentResponse.java`
- `src/main/java/com/softropic/skillars/platform/session/service/HomeworkAssignmentService.java`
- `src/main/java/com/softropic/skillars/platform/session/api/HomeworkResource.java`
- `src/test/java/com/softropic/skillars/platform/session/service/HomeworkAssignmentServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/session/api/HomeworkResourceIT.java`
- `src/frontend/src/api/homework.api.js`
- `src/frontend/src/stores/homework.store.js`

### Modified Files
- `src/main/java/com/softropic/skillars/platform/booking/repo/SessionPackPurchasedRepository.java` — added `findActivePacks`, `findTopByPlayerIdAndCoachIdOrderByPurchasedAtDesc`
- `src/main/java/com/softropic/skillars/platform/booking/service/SessionPackService.java` — added `hasActivePack`, `getActivePackId`
- `src/main/java/com/softropic/skillars/platform/booking/contract/WrapUpRequest.java` — added `@Size(max=2)` on `homeworkDrillIds`
- `src/main/java/com/softropic/skillars/platform/booking/api/SessionCompletionResource.java` — wired real drill suggestions via `DrillSuggestionService`
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java` — added `getDisplayNamesByIds`
- `src/frontend/src/components/session/DrillCard.vue` — added `context !== 'locker-room'` guard on PLATFORM clone row
- `src/frontend/src/pages/player/PlayerLockerRoomPlaceholderPage.vue` — replaced placeholder with full Locker Room implementation
- `src/frontend/src/router/routes.js` — added `:playerId` param and `name: 'player-locker-room'`
- `src/frontend/src/router/index.js` — TODO comment on PLAYER hardcoded path
- `src/frontend/src/pages/auth/LoginPage.vue` — TODO comment on PLAYER hardcoded path
- `src/frontend/src/pages/parent/ParentPlayerPortalPage.vue` — added Locker Room nav button
- `src/frontend/src/i18n/en/index.js` — added homework i18n keys
- `src/frontend/src/i18n/de/index.js` — added homework i18n keys
- `src/frontend/src/i18n/en-US/index.js` — added homework i18n keys
- `src/frontend/src/i18n/fr-FR/index.js` — added homework i18n keys

## Change Log

| Date | Version | Description | Author |
|------|---------|-------------|--------|
| 2026-06-18 | 1.0 | Story created — Homework Assignment & Player Locker Room | claude-sonnet-4-6 |
| 2026-06-18 | 1.1 | Audit fixes: bookingId as idempotency anchor (NULL session_id UNIQUE bypass); DrillCard clone row locker-room guard; LoginPage.vue hardcoded path; AC6 false AgePolicyService claim; @Size(max=2) on WrapUpRequest; module boundary (resolvePackId via SessionPackService.getActivePackId); dead parentId param removed; catch log.warn; fr-FR/en-US i18n; autoplay deferred note in AC2 | claude-sonnet-4-6 |
| 2026-06-18 | 1.2 | Full implementation: DB migration V45, entities, repositories, services, API, frontend Locker Room, i18n, tests | claude-sonnet-4-6 |
