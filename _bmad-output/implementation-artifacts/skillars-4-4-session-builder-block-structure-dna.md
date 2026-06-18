# Story skillars-4.4: Session Builder — Block Structure & DNA

Status: done

## Story

As an Instructor or Academy tier coach,
I want to build a structured session with four blocks, live Session DNA scoring, drag-and-drop drill management, and an auto-generated equipment list,
So that every session is purposefully designed before I step onto the pitch.

## Acceptance Criteria

**AC 1: Four-block pre-population** — Given an Instructor+ coach opens Session Builder for a confirmed booking, when the builder loads, then four default blocks are pre-populated: Warm-Up (10 min), Technical Foundation (15 min), Game Intensity (25 min), Cool-Down & Review (10 min); each block shows its duration, assigned drills, and a block-level SLU subtotal; the coach can rename any block, adjust its duration, and add/remove drills independently.

**AC 2: Live Session DNA on drill add/remove** — Given the coach adds a drill to any block, when the drill is placed, then the Session DNA panel updates immediately — 5-dimension scores (Technical, Physical, Cognitive, Match Realism, Weak Foot Focus), each 0–100, recalculate based on the aggregate metadata of all drills across all blocks; the `SessionDNAChart` compact thumbnail variant updates in real time without an API call — calculation is client-side from drill metadata.

**AC 3: Drag-and-drop reorder** — Given the coach wants to reorder drills within or across blocks, when they drag a drill card to a new position, then the drill moves to the target position; the Session DNA scores and block SLU subtotals recalculate immediately after the drop; drag-and-drop works on both desktop (mouse) and mobile (touch) — no separate reorder mode required.

**AC 4: Session plan persisted on save** — Given the coach saves the session plan, when `POST /api/session/sessions` (create) or `PUT /api/session/sessions/{id}` (update) is called, then a `sessions` record is persisted with: id, bookingId, coachId, playerId, blocks JSONB, sessionDna JSONB, developmentFocus JSONB, status ENUM (DRAFT/SAVED/COMPLETED), equipmentList JSONB, createdAt, updatedAt; the response includes enriched block data with full drill details and SLU subtotals.

**AC 5: Equipment list auto-generated** — Given the coach saves the session plan, when the equipment list is generated, then it contains the deduplicated, case-insensitive, alphabetically sorted union of `equipmentRequired[]` across all drills in all blocks; this list is stored on the session record and viewable as a separate "Equipment" tab in the Session Builder.

**AC 6: Development Focus required** — Given the coach sets one or more Development Focus areas, when the focus is selected, then the Session DNA panel highlights the focus dimensions; the selected focus areas are stored on the session record in `developmentFocus[]`; at least one focus area must be selected before the session can be saved — the save button is disabled and the DTO validation rejects the request if `developmentFocus` is empty.

**AC 7: Scout-tier gate** — Given a Scout-tier coach attempts to access the Session Builder, then the `POST /api/session/sessions` endpoint returns 403 with `security.featureGated` code; the Session Builder UI shows the Scout-tier teaser overlay with upgrade CTA (matching the existing UX pattern from the drill library).

**AC 8: Session lookup by booking** — Given a coach reopens the Session Builder for a booking, when `GET /api/session/sessions/by-booking/{bookingId}` is called, then if a session plan exists for that booking it is returned (200); if not, 404 is returned and the builder initializes with default blocks client-side.

## Tasks / Subtasks

### Backend — Database Migration

- [x] **Task 1: Write `V43__session_plans.sql`**
  - [x] File: `src/main/resources/db/migration/V43__session_plans.sql`
  - [x] Latest applied migration: V42 (drill video upload config). Next must be V43.
  - [x] SQL:
    ```sql
    CREATE TABLE session.sessions (
        id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
        booking_id        UUID         NOT NULL,
        coach_id          UUID         NOT NULL,
        player_id         BIGINT       NOT NULL,
        blocks            JSONB        NOT NULL DEFAULT '[]',
        session_dna       JSONB,
        equipment_list    JSONB        NOT NULL DEFAULT '[]',
        development_focus JSONB        NOT NULL DEFAULT '[]',
        status            VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
                          CHECK (status IN ('DRAFT', 'SAVED', 'COMPLETED')),
        created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
        updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
    );
    -- One session plan per booking
    CREATE UNIQUE INDEX uq_sessions_booking_id ON session.sessions (booking_id);
    CREATE INDEX idx_sessions_coach_id ON session.sessions (coach_id);
    ```
  - [x] `blocks` JSONB shape: `[{ "blockType": "WARM_UP", "blockName": "Warm-Up", "durationMinutes": 10, "drills": [{ "drillId": "uuid", "order": 0 }] }]`
  - [x] `sessionDna` JSONB shape: `{ "technical": 0, "physical": 0, "cognitive": 0, "matchRealism": 0, "weakFootFocus": 0 }` — null when no drills assigned
  - [x] `equipmentList` JSONB shape: `["cones", "poles", "bibs"]` — sorted strings

### Backend — Error Codes

- [x] **Task 2: Add new error codes to `SessionErrorCode.java`**
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/SessionErrorCode.java`
  - [x] Existing values: `CLONE_NOT_ALLOWED`, `SESSION_CANNOT_TAG_UNAUTHORIZED`, `DRILL_UPLOAD_NOT_ALLOWED`
  - [x] Add:
    ```java
    SESSION_ALREADY_EXISTS,
    SESSION_BOOKING_NOT_OWNED,
    SESSION_PLAN_LOCKED;
    ```
  - [x] `SESSION_ALREADY_EXISTS` — returned (403) when `POST /api/session/sessions` is called for a booking that already has a plan
  - [x] `SESSION_BOOKING_NOT_OWNED` — returned (403) when the coach does not own the booking
  - [x] `SESSION_PLAN_LOCKED` — returned (403) when attempting to update a session in `COMPLETED` status; do NOT reuse `SESSION_CANNOT_TAG_UNAUTHORIZED` for this (different semantic)

### Backend — Repository Layer

- [x] **Task 3: Create `Session.java` entity**
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/repo/Session.java`
  - [x] Package: `com.softropic.skillars.platform.session.repo`
  - [x] Follow the `Drill.java` pattern exactly — use `@JdbcTypeCode(SqlTypes.JSON)` for JSONB fields, NOT `@Type(JsonBinaryType.class)`.
  - [x] Entity:
    ```java
    @Entity
    @Table(schema = "session", name = "sessions")
    @Getter
    @Setter
    @NoArgsConstructor
    public class Session {

        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;

        @Column(name = "booking_id", nullable = false)
        private UUID bookingId;

        @Column(name = "coach_id", nullable = false)
        private UUID coachId;

        @Column(name = "player_id", nullable = false)
        private Long playerId;

        @JdbcTypeCode(SqlTypes.JSON)
        @Column(nullable = false, columnDefinition = "jsonb")
        private List<SessionBlockData> blocks;

        @JdbcTypeCode(SqlTypes.JSON)
        @Column(name = "session_dna", columnDefinition = "jsonb")
        private SessionDnaScore sessionDna;

        @JdbcTypeCode(SqlTypes.JSON)
        @Column(name = "equipment_list", nullable = false, columnDefinition = "jsonb")
        private List<String> equipmentList;

        @JdbcTypeCode(SqlTypes.JSON)
        @Column(name = "development_focus", nullable = false, columnDefinition = "jsonb")
        private List<String> developmentFocus;

        @Column(nullable = false, length = 20)
        private String status;

        @Column(name = "created_at", nullable = false, updatable = false)
        private Instant createdAt;

        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt;

        @PrePersist
        void onCreate() {
            createdAt = Instant.now();
            updatedAt = Instant.now();
        }

        @PreUpdate
        void onUpdate() { updatedAt = Instant.now(); }
    }
    ```
  - [x] Imports needed: `org.hibernate.annotations.JdbcTypeCode`, `org.hibernate.type.SqlTypes`, `com.softropic.skillars.platform.session.contract.SessionBlockData`, `com.softropic.skillars.platform.session.contract.SessionDnaScore`, `java.util.List`
  - [x] `SessionBlockData` and `SessionDnaScore` are defined in Task 5/7 below — they must be Jackson-serializable records in the `contract` package

- [x] **Task 4: Create `SessionRepository.java`**
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/repo/SessionRepository.java`
  - [x] Package: `com.softropic.skillars.platform.session.repo`
  - [x] Interface:
    ```java
    public interface SessionRepository extends JpaRepository<Session, UUID> {
        Optional<Session> findByBookingId(UUID bookingId);
        Optional<Session> findByBookingIdAndCoachId(UUID bookingId, UUID coachId);
        boolean existsByBookingId(UUID bookingId);
    }
    ```

### Backend — Contract Layer (Value Objects)

- [x] **Task 5: Create `SessionDrillRef.java`** — JSONB drill reference inside a block
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/SessionDrillRef.java`
  - [x] Package: `com.softropic.skillars.platform.session.contract`
  - [x] Record: `public record SessionDrillRef(UUID drillId, int order) {}`
  - [x] Must have a no-arg-constructible JSON deserializer — `record` with Jackson's `ParameterNamesModule` works automatically with Spring Boot 3.x; no extra annotations needed.

- [x] **Task 6: Create `SessionBlockData.java`** — JSONB block stored on Session entity
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/SessionBlockData.java`
  - [x] Package: `com.softropic.skillars.platform.session.contract`
  - [x] Record:
    ```java
    public record SessionBlockData(
        String blockType,
        String blockName,
        int durationMinutes,
        List<SessionDrillRef> drills
    ) {}
    ```
  - [x] This is stored as JSONB in `session.sessions.blocks` and deserialized by Hibernate via Jackson. Fields must match the JSON keys exactly.

- [x] **Task 7: Create `SessionDnaScore.java`** — JSONB DNA score stored on Session entity
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/SessionDnaScore.java`
  - [x] Package: `com.softropic.skillars.platform.session.contract`
  - [x] Record:
    ```java
    public record SessionDnaScore(
        int technical,
        int physical,
        int cognitive,
        int matchRealism,
        int weakFootFocus
    ) {}
    ```

### Backend — Contract Layer (Request/Response DTOs)

- [x] **Task 8: Create `SessionDrillRefRequest.java`**
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/SessionDrillRefRequest.java`
  - [x] Record:
    ```java
    public record SessionDrillRefRequest(
        @NotNull UUID drillId,
        @Min(0) int order
    ) {}
    ```

- [x] **Task 9: Create `SessionBlockRequest.java`**
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/SessionBlockRequest.java`
  - [x] Record:
    ```java
    public record SessionBlockRequest(
        @NotBlank @Size(max = 50) String blockType,
        @NotBlank @Size(max = 100) String blockName,
        @Min(1) @Max(240) int durationMinutes,
        @NotNull List<@Valid SessionDrillRefRequest> drills
    ) {}
    ```
  - [x] `blockType` is a free-form string matching the 4 default types: `WARM_UP`, `TECHNICAL_FOUNDATION`, `GAME_INTENSITY`, `COOL_DOWN_REVIEW`; no server-side enum enforcement — coaches can use custom types if building non-default sessions
  - [x] **`@Size(min = 1, max = 4)` is a hard cap** — maximum 4 blocks enforced at the API layer. This is an intentional MVP constraint. If a future story allows 5+ blocks, this annotation must be changed.

- [x] **Task 10: Create `CreateSessionPlanRequest.java`**
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/CreateSessionPlanRequest.java`
  - [x] Record:
    ```java
    public record CreateSessionPlanRequest(
        @NotNull UUID bookingId,
        @NotNull @Size(min = 1, max = 4) List<@Valid SessionBlockRequest> blocks,
        @NotEmpty List<@NotBlank String> developmentFocus
    ) {}
    ```
  - [x] `@NotEmpty` on `developmentFocus` enforces AC 6 at the DTO validation layer (400 Bad Request), before service runs

- [x] **Task 11: Create `UpdateSessionPlanRequest.java`**
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/UpdateSessionPlanRequest.java`
  - [x] Record:
    ```java
    public record UpdateSessionPlanRequest(
        @NotNull @Size(min = 1, max = 4) List<@Valid SessionBlockRequest> blocks,
        @NotEmpty List<@NotBlank String> developmentFocus,
        @Pattern(regexp = "DRAFT|SAVED") String status
    ) {}
    ```
  - [x] `status` defaults to `DRAFT` in service if null. `COMPLETED` is set by the booking completion flow (Story 3.6), not by the coach directly.

- [x] **Task 12: Create `SessionBlockDrillResponse.java`**
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/SessionBlockDrillResponse.java`
  - [x] Record: `public record SessionBlockDrillResponse(UUID drillId, int order, DrillResponse drill) {}`
  - [x] `DrillResponse` is the existing type from `com.softropic.skillars.platform.session.contract.DrillResponse`

- [x] **Task 13: Create `SessionBlockResponse.java`**
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/SessionBlockResponse.java`
  - [x] Record:
    ```java
    public record SessionBlockResponse(
        String blockType,
        String blockName,
        int durationMinutes,
        List<SessionBlockDrillResponse> drills,
        int sluSubtotal
    ) {}
    ```
  - [x] `sluSubtotal` is computed server-side: sum of `repDensity × sum(skillWeighting.values())` across all drills in the block. See `SessionPlanService.calculateBlockSlu()` in Task 15.

- [x] **Task 14: Create `SessionPlanResponse.java`**
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/SessionPlanResponse.java`
  - [x] Record:
    ```java
    public record SessionPlanResponse(
        UUID id,
        UUID bookingId,
        UUID coachId,
        Long playerId,
        List<SessionBlockResponse> blocks,
        SessionDnaScore sessionDna,
        List<String> equipmentList,
        List<String> developmentFocus,
        String status,
        Instant createdAt,
        Instant updatedAt
    ) {}
    ```

### Backend — Service Layer

- [x] **Task 15: Create `SessionDnaCalculator.java`** — pure Java, no DB, no Spring injection needed (but make it a `@Component` for testability)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/service/SessionDnaCalculator.java`
  - [x] Package: `com.softropic.skillars.platform.session.service`
  - [x] Class:
    ```java
    @Component
    public class SessionDnaCalculator {

        public SessionDnaScore calculate(List<DrillMetadata> drills) {
            if (drills == null || drills.isEmpty()) {
                return new SessionDnaScore(0, 0, 0, 0, 0);
            }
            int count = drills.size();
            double sumTechnical = 0, sumPhysical = 0, sumCognitive = 0, sumMatchRealism = 0;
            int weakFootCount = 0;
            for (DrillMetadata m : drills) {
                // Technical: average of intensity and pressureLevel (both 1-5)
                sumTechnical += (m.intensity() + m.pressureLevel()) / 2.0;
                // Physical: intensity drives physical load
                sumPhysical += m.intensity();
                sumCognitive += m.cognitiveLoad();
                sumMatchRealism += m.matchRealism();
                if (m.weakFootBias()) weakFootCount++;
            }
            return new SessionDnaScore(
                mapToScore(sumTechnical / count),
                mapToScore(sumPhysical / count),
                mapToScore(sumCognitive / count),
                mapToScore(sumMatchRealism / count),
                Math.round((float) weakFootCount / count * 100)
            );
        }

        // Maps 1–5 average to 0–100: value=1 → 0, value=3 → 50, value=5 → 100
        private int mapToScore(double avg) {
            return Math.max(0, Math.min(100, (int) Math.round((avg - 1.0) * 25.0)));
        }
    }
    ```
  - [x] All `DrillMetadata` int fields (`intensity`, `pressureLevel`, `cognitiveLoad`, `matchRealism`) are validated as 1-5 at drill creation time. This calculator trusts those values.
  - [x] `weakFootFocus` score: percentage of drills with `weakFootBias = true`, 0–100.
  - [x] Empty list → all zeros (no NPE, no division by zero).

- [x] **Task 16: Create `EquipmentListService.java`** — pure calculation
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/service/EquipmentListService.java`
  - [x] Package: `com.softropic.skillars.platform.session.service`
  - [x] Class:
    ```java
    @Component
    public class EquipmentListService {

        public List<String> generate(List<DrillMetadata> drills) {
            if (drills == null || drills.isEmpty()) return List.of();
            return drills.stream()
                .flatMap(m -> m.equipmentRequired() != null
                    ? m.equipmentRequired().stream() : Stream.empty())
                .map(String::toLowerCase)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        }
    }
    ```
  - [x] Case-insensitive dedup: normalise to lowercase before distinct. "Cones" and "cones" → "cones".
  - [x] Null-safe: if `equipmentRequired` is null on a drill, skip.

- [x] **Task 17: Create `SessionPlanService.java`**
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/service/SessionPlanService.java`
  - [x] Package: `com.softropic.skillars.platform.session.service`
  - [x] Annotations: `@Service @Transactional @Slf4j @RequiredArgsConstructor`
  - [x] Inject: `SessionRepository`, `DrillRepository`, `BookingQueryService` (from `platform.booking.service` — see Task 19.5), `CoachProfileService`, `DrillLibraryService`, `SessionDnaCalculator`, `EquipmentListService`
  - [x] **Do NOT inject `BookingRepository` directly.** The session module must not cross into `platform.booking.repo` — that violates the DDD module boundary. Use `BookingQueryService` which is in the `booking` module's service layer and exposed via its contract (Task 19.5).
  - [x] **`createSession(CreateSessionPlanRequest req, Long coachUserId)` → `SessionPlanResponse`**:
    ```
    1. drillLibraryService.checkSessionBuilderGate(coachUserId)   // throws FeatureGatedException for Scout
    2. UUID coachId = resolveCoachId(coachUserId)
    3. if sessionRepository.existsByBookingId(req.bookingId())
         throw new OperationNotAllowedException("Session plan already exists for this booking",
             SessionErrorCode.SESSION_ALREADY_EXISTS)
    4. BookingSnapshot booking = bookingQueryService.getBookingSnapshot(req.bookingId())
         .orElseThrow(() -> new ResourceNotFoundException("Booking not found"))
    5. if !booking.coachId().equals(coachId)
         throw new OperationNotAllowedException("Booking is not owned by this coach",
             SessionErrorCode.SESSION_BOOKING_NOT_OWNED)
    5a. if booking.status() is not in {"CONFIRMED", "UPCOMING"}
         throw new OperationNotAllowedException("Session plan can only be created for a confirmed or upcoming booking",
             SessionErrorCode.SESSION_BOOKING_NOT_OWNED)
         // Reuse SESSION_BOOKING_NOT_OWNED code — invalid booking state is equivalent to "not yours to plan"
         // AC1 requires "confirmed booking". Do not allow planning on REQUESTED/CANCELLED/COMPLETED bookings.
    6. List<SessionBlockData> blocks = mapBlocksFromRequest(req.blocks())
    7. Map<UUID, DrillMetadata> metaMap = resolveMetaMap(blocks)
    8. List<DrillMetadata> allMeta = expandMetaForDna(blocks, metaMap)  // preserves duplicates
    9. SessionDnaScore dna = dnaCalculator.calculate(allMeta)
    10. List<String> equipment = equipmentListService.generate(allMeta)
    11. Build and save Session entity:
          session.setBookingId(req.bookingId())
          session.setCoachId(coachId)
          session.setPlayerId(booking.playerId())
          session.setBlocks(blocks)
          session.setSessionDna(dna)
          session.setEquipmentList(equipment)
          session.setDevelopmentFocus(req.developmentFocus())
          session.setStatus("DRAFT")
    12. return buildResponse(saved, metaMap)
    ```
  - [x] **`updateSession(UUID sessionId, UpdateSessionPlanRequest req, Long coachUserId)` → `SessionPlanResponse`**:
    ```
    1. drillLibraryService.checkSessionBuilderGate(coachUserId)
    2. UUID coachId = resolveCoachId(coachUserId)
    3. Session session = sessionRepository.findById(sessionId)
         .orElseThrow(() -> new ResourceNotFoundException("Session not found"))
    4. if !session.getCoachId().equals(coachId)
         throw new OperationNotAllowedException("Session is not owned by this coach",
             SessionErrorCode.SESSION_BOOKING_NOT_OWNED)
    5. if "COMPLETED".equals(session.getStatus())
         throw new OperationNotAllowedException("Completed sessions cannot be modified",
             SessionErrorCode.SESSION_PLAN_LOCKED)
         // Use SESSION_PLAN_LOCKED — do NOT reuse SESSION_CANNOT_TAG_UNAUTHORIZED (wrong semantic)
    6. Re-calculate blocks, DNA, equipment (same as createSession steps 6-10)
    7. Update entity fields: blocks, sessionDna, equipmentList, developmentFocus
    8. status = req.status() != null ? req.status() : session.getStatus()
    9. return buildResponse(saved, metaMap)
    ```
  - [x] **`getSession(UUID sessionId, Long coachUserId)` → `SessionPlanResponse`**:
    ```
    1. UUID coachId = resolveCoachId(coachUserId)
    2. Session session = sessionRepository.findById(sessionId)
         .orElseThrow(() -> new ResourceNotFoundException("Session not found"))
    3. if !session.getCoachId().equals(coachId)
         throw new ResourceNotFoundException("Session not found")
         // Return 404, NOT 403 — prevents session-ID enumeration.
         // A 403 would reveal that the session exists; 404 is the correct behaviour for non-owned resources.
    4. Map<UUID, DrillMetadata> metaMap = resolveMetaMap(session.getBlocks())
    5. return buildResponse(session, metaMap)
    ```
  - [x] **`findByBookingId(UUID bookingId, Long coachUserId)` → `Optional<SessionPlanResponse>`**:
    ```
    UUID coachId = resolveCoachId(coachUserId)
    return sessionRepository.findByBookingIdAndCoachId(bookingId, coachId)
        .map(s -> {
            Map<UUID, DrillMetadata> metaMap = resolveMetaMap(s.getBlocks())
            return buildResponse(s, metaMap)
        })
    ```
  - [x] **Private helpers**:
    ```java
    private UUID resolveCoachId(Long userId) {
        return coachProfileService.getCoachIdByUserId(userId);
        // Throws ResourceNotFoundException if not found — let it propagate to 404
    }

    private List<SessionBlockData> mapBlocksFromRequest(List<SessionBlockRequest> reqs) {
        int[] globalOrder = {0};
        return reqs.stream().map(req -> {
            List<SessionDrillRef> drillRefs = req.drills().stream()
                .map(d -> new SessionDrillRef(d.drillId(), d.order()))
                .sorted(Comparator.comparingInt(SessionDrillRef::order))
                .collect(Collectors.toList());
            return new SessionBlockData(req.blockType(), req.blockName(),
                req.durationMinutes(), drillRefs);
        }).collect(Collectors.toList());
    }

    private Map<UUID, DrillMetadata> resolveMetaMap(List<SessionBlockData> blocks) {
        if (blocks == null || blocks.isEmpty()) return Map.of();
        List<UUID> uniqueIds = blocks.stream()
            .flatMap(b -> b.drills() != null ? b.drills().stream() : Stream.empty())
            .map(SessionDrillRef::drillId)
            .distinct()
            .collect(Collectors.toList());
        if (uniqueIds.isEmpty()) return Map.of();
        return drillRepository.findAllById(uniqueIds).stream()
            .collect(Collectors.toMap(Drill::getId, Drill::getMetadata));
    }

    // Preserves duplicates: if same drillId in multiple blocks, metadata counted multiple times for DNA
    private List<DrillMetadata> expandMetaForDna(List<SessionBlockData> blocks,
                                                   Map<UUID, DrillMetadata> metaMap) {
        return blocks.stream()
            .flatMap(b -> b.drills() != null ? b.drills().stream() : Stream.empty())
            .map(ref -> metaMap.get(ref.drillId()))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    private int calculateBlockSlu(List<SessionDrillRef> drillRefs,
                                   Map<UUID, DrillMetadata> metaMap) {
        if (drillRefs == null || drillRefs.isEmpty()) return 0;
        return drillRefs.stream()
            .map(ref -> metaMap.get(ref.drillId()))
            .filter(Objects::nonNull)
            .mapToInt(m -> {
                int weightSum = (m.skillWeighting() != null && !m.skillWeighting().isEmpty())
                    ? m.skillWeighting().values().stream().mapToInt(Integer::intValue).sum()
                    : 1;
                return m.repDensity() * weightSum;
            })
            .sum();
    }

    private SessionPlanResponse buildResponse(Session session,
                                               Map<UUID, DrillMetadata> metaMap) {
        // Build DrillResponse lookup for drills in this session.
        // DELEGATE to DrillLibraryService.toResponse() — do NOT build DrillResponse inline.
        // DrillResponse is a 14-field record; its constructor changes whenever fields are added.
        // Make DrillLibraryService.toResponse() package-private (remove private modifier) so it
        // can be called from this service within the same module.
        // Signature: DrillResponse toResponse(Drill drill, boolean hasVideo, List<String> tags,
        //                                      Boolean isClonedByMe, UUID cloneId, String videoUrl)
        // Call as: drillLibraryService.toResponse(d, false, List.of(), null, null, null)
        // hasVideo=false, videoUrl=null because Session Builder shows metadata only, not playback.
        List<UUID> drillIds = session.getBlocks().stream()
            .flatMap(b -> b.drills() != null ? b.drills().stream() : Stream.empty())
            .map(SessionDrillRef::drillId).distinct().collect(Collectors.toList());
        Map<UUID, DrillResponse> drillResponseMap = drillIds.isEmpty() ? Map.of()
            : drillRepository.findAllById(drillIds).stream()
                .collect(Collectors.toMap(Drill::getId, d ->
                    drillLibraryService.toResponse(d, false, List.of(), null, null, null)));

        List<SessionBlockResponse> blockResponses = session.getBlocks().stream().map(block -> {
            List<SessionBlockDrillResponse> drillResponses = (block.drills() != null
                ? block.drills() : List.<SessionDrillRef>of()).stream()
                .sorted(Comparator.comparingInt(SessionDrillRef::order))
                .map(ref -> new SessionBlockDrillResponse(ref.drillId(), ref.order(),
                    drillResponseMap.get(ref.drillId())))
                .collect(Collectors.toList());
            int slu = calculateBlockSlu(block.drills(), metaMap);
            return new SessionBlockResponse(block.blockType(), block.blockName(),
                block.durationMinutes(), drillResponses, slu);
        }).collect(Collectors.toList());

        return new SessionPlanResponse(
            session.getId(), session.getBookingId(), session.getCoachId(),
            session.getPlayerId(), blockResponses, session.getSessionDna(),
            session.getEquipmentList(), session.getDevelopmentFocus(),
            session.getStatus(), session.getCreatedAt(), session.getUpdatedAt()
        );
    }
    ```
  - [x] **CRITICAL**: `DrillLibraryService.toResponse()` is currently `private`. Before implementing `buildResponse()`, change its access modifier to package-private (remove `private`). Do NOT replicate the `DrillResponse` constructor inline — `DrillResponse` has 14 fields and any future field addition will silently break an inline call.

### Backend — API Layer

- [x] **Task 18: Create `SessionBuilderResource.java`** — replaces the stub `SessionPlanResource.java`
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/api/SessionBuilderResource.java`
  - [x] Package: `com.softropic.skillars.platform.session.api`
  - [x] Class:
    ```java
    @Observed(name = "session.builder")
    @RestController
    @RequestMapping("/api/session/sessions")
    @RequiredArgsConstructor
    public class SessionBuilderResource {

        private final SessionPlanService sessionPlanService;
        private final SecurityUtil securityUtil;

        @PostMapping
        @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
        public ResponseEntity<SessionPlanResponse> createSession(
            @RequestBody @Valid CreateSessionPlanRequest req
        ) {
            SessionPlanResponse resp = sessionPlanService.createSession(req, currentCoachUserId());
            return ResponseEntity.status(HttpStatus.CREATED).body(resp);
        }

        @PutMapping("/{sessionId}")
        @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
        public ResponseEntity<SessionPlanResponse> updateSession(
            @PathVariable UUID sessionId,
            @RequestBody @Valid UpdateSessionPlanRequest req
        ) {
            return ResponseEntity.ok(sessionPlanService.updateSession(sessionId, req, currentCoachUserId()));
        }

        @GetMapping("/{sessionId}")
        @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
        public ResponseEntity<SessionPlanResponse> getSession(@PathVariable UUID sessionId) {
            return ResponseEntity.ok(sessionPlanService.getSession(sessionId, currentCoachUserId()));
        }

        @GetMapping("/by-booking/{bookingId}")
        @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
        public ResponseEntity<SessionPlanResponse> getByBooking(@PathVariable UUID bookingId) {
            return sessionPlanService.findByBookingId(bookingId, currentCoachUserId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
        }

        private Long currentCoachUserId() {
            return securityUtil.getCurrentCoachUserId();
        }
    }
    ```
  - [x] **DELETE `SessionPlanResource.java`** at path `src/main/java/com/softropic/skillars/platform/session/api/SessionPlanResource.java` — it is now superseded. The gate check it performed is now done inside `SessionPlanService.createSession()` via `drillLibraryService.checkSessionBuilderGate()`.
  - [x] Exception propagation (after Task 19's ApiAdvice update):
    - `FeatureGatedException` → `ApiAdvice` → 403, `helpCode: "security.featureGated"`
    - `OperationNotAllowedException(SESSION_ALREADY_EXISTS)` → `ApiAdvice` → 403, `helpCode: "SESSION_ALREADY_EXISTS"`
    - `OperationNotAllowedException(SESSION_BOOKING_NOT_OWNED)` → `ApiAdvice` → 403, `helpCode: "SESSION_BOOKING_NOT_OWNED"`
    - `OperationNotAllowedException(SESSION_PLAN_LOCKED)` → `ApiAdvice` → 403, `helpCode: "SESSION_PLAN_LOCKED"`
    - `ResourceNotFoundException` → `ApiAdvice` → 404
    - Bean validation failures → Spring → 400

- [x] **Task 19: Update `ApiAdvice.java`** — propagate `errorCode` from `OperationNotAllowedException` as the `helpCode`
  - [x] File: `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java`
  - [x] Current `operationDeniedHandler` hard-codes `helpCode = "security.opForbidden"` for every `OperationNotAllowedException`, discarding the `SessionErrorCode` set on the exception.
  - [x] Update the handler to propagate the error code's name when present:
    ```java
    @ExceptionHandler(OperationNotAllowedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorDto operationDeniedHandler(final OperationNotAllowedException exception) {
        final String defaultMsg = "The operation is not granted. You can contact help desk";
        String helpCode = exception.getErrorCode() != null
            ? exception.getErrorCode().name()
            : "security.opForbidden";
        return handleSecErrorAndReturnDTO(exception, defaultMsg, helpCode);
    }
    ```
  - [x] This allows the frontend to distinguish `SESSION_ALREADY_EXISTS`, `SESSION_BOOKING_NOT_OWNED`, and `SESSION_PLAN_LOCKED` by their `helpCode`. All existing callers that previously received `"security.opForbidden"` will now receive the specific error code name — verify no existing frontend code hard-checks for `"security.opForbidden"` and breaks.
  - [x] **Check existing usages**: grep the frontend for `helpCode.*security.opForbidden` before merging — if any component checks for that exact string, update it to handle the new specific codes or use a prefix check.

- [x] **Task 19.5: Create `BookingQueryService.java`** — cross-module booking access point
  - [x] File: `src/main/java/com/softropic/skillars/platform/booking/service/BookingQueryService.java`
  - [x] Package: `com.softropic.skillars.platform.booking.service`
  - [x] Purpose: expose minimal booking data to other platform modules without exposing `BookingRepository` directly. `SessionPlanService` must NOT inject `BookingRepository` from `platform.booking.repo` — that violates module boundary.
  - [x] Create a value record in `platform.booking.contract` first:
    ```java
    // File: platform/booking/contract/BookingSnapshot.java
    public record BookingSnapshot(UUID id, UUID coachId, Long playerId, String status) {}
    ```
  - [x] Service:
    ```java
    @Service
    @RequiredArgsConstructor
    public class BookingQueryService {

        private final BookingRepository bookingRepository;

        @Transactional(readOnly = true)
        public Optional<BookingSnapshot> getBookingSnapshot(UUID bookingId) {
            return bookingRepository.findById(bookingId)
                .map(b -> new BookingSnapshot(b.getId(), b.getCoachId(), b.getPlayerId(), b.getStatus()));
        }
    }
    ```
  - [x] `SessionPlanService` injects `BookingQueryService` (from `platform.booking.service`), NOT `BookingRepository`. Cross-module access is to the service layer, not the repo layer.

### Backend — Tests

- [x] **Task 20: `SessionDnaCalculatorTest.java`** — unit tests
  - [x] File: `src/test/java/com/softropic/skillars/platform/session/service/SessionDnaCalculatorTest.java`
  - [x] `@ExtendWith(MockitoExtension.class)` — pure unit, no Spring context
  - [x] Use Instancio for base `DrillMetadata`, then override specific fields
  - [x] Test cases:
    - `calculate_emptyList_returnsAllZeros`
    - `calculate_singleDrillIntensity1_returnsMinScore` — intensity=1, pressureLevel=1 → technical=0, physical=0
    - `calculate_singleDrillIntensity5_returnsMaxScore` — all fields=5, weakFootBias=true → all 100
    - `calculate_singleDrillIntensity3_returnsMidScore` — all fields=3, weakFootBias=false → technical=50, physical=50, cognitive=50, matchRealism=50, weakFootFocus=0
    - `calculate_multiDrill_averagesCorrectly` — 2 drills: one intensity=1, one intensity=5 → physical=50
    - `calculate_weakFootFocus_percentageOfDrills` — 3 drills, 1 with weakFootBias=true → weakFootFocus=33 (Math.round(1/3 * 100))
    - `calculate_duplicateDrillInTwoBlocks_countsEachOccurrence` — same drill metadata twice vs once: score differs
    - `calculate_nullEquipmentInMetadata_doesNotThrow`

- [x] **Task 21: `EquipmentListServiceTest.java`** — unit tests
  - [x] File: `src/test/java/com/softropic/skillars/platform/session/service/EquipmentListServiceTest.java`
  - [x] `@ExtendWith(MockitoExtension.class)` — no Spring
  - [x] Test cases:
    - `generate_emptyList_returnsEmpty`
    - `generate_singleDrill_returnsSortedList`
    - `generate_duplicateEquipment_deduplicates` — "cones" + "cones" → ["cones"]
    - `generate_caseInsensitiveDedup` — "Cones" + "cones" → ["cones"] (lowercase)
    - `generate_nullEquipmentRequired_skipped`
    - `generate_multiDrills_sortedAlphabetically` — ["poles", "bibs", "cones"] → ["bibs", "cones", "poles"]
    - `generate_blankEquipmentEntry_skipped` — `""` or `" "` not included

- [x] **Task 22: `SessionBuilderResourceIT.java`** — integration tests
  - [x] File: `src/test/java/com/softropic/skillars/platform/session/api/SessionBuilderResourceIT.java`
  - [x] Extend `BaseSessionIT` (same pattern as `DrillLibraryResourceIT`, `DrillUploadResourceIT`)
  - [x] `@SpringBootTest @Testcontainers`
  - [x] Use `@Sql({SecurityIT.SEC_DATA_SQL_PATH})` for auth data
  - [x] A test booking must exist with a known `bookingId` and `coachId` — use `@Sql` to insert a test booking row before tests, clean up after
  - [x] Test cases:
    - `createSession_instructorCoach_validRequest_returns201WithSessionPlan`
    - `createSession_scoutCoach_returns403FeatureGated`
    - `createSession_bookingOwnedByOtherCoach_returns403`
    - `createSession_duplicateBookingId_returns403` — second POST same booking
    - `createSession_bookingInRequestedStatus_returns403` — booking status is REQUESTED not CONFIRMED
    - `createSession_emptyDevelopmentFocus_returns400`
    - `updateSession_instructorCoach_returnsUpdatedPlan`
    - `updateSession_completedSession_returns403WithHelpCodeSessionPlanLocked`
    - `getSession_ownerCoach_returns200`
    - `getSession_otherCoach_returns404` — must be 404, NOT 403 (session-ID enumeration guard)
    - `getByBooking_existingSession_returns200`
    - `getByBooking_noSession_returns404`
  - [x] Teardown: `DELETE FROM session.sessions WHERE booking_id = <test-booking-id>`
  - [x] Test booking insert must target schema `booking.bookings` (NOT `session.bookings`). All NOT NULL columns are required — the following columns must be present or the DB insert will fail: `id`, `parent_id` (BIGINT), `player_id` (BIGINT), `coach_id` (UUID), `requested_start_time`, `requested_end_time`, `status`, `canonical_timezone`, `version`, `created_at`, `updated_at`. Example:
    ```sql
    INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time,
        requested_end_time, status, canonical_timezone, version, created_at, updated_at)
    VALUES (
        '<test-booking-uuid>',
        1,
        1,
        '<test-coach-uuid>',
        now() + interval '1 day',
        now() + interval '1 day' + interval '1 hour',
        'CONFIRMED',
        'Europe/London',
        0,
        now(),
        now()
    );
    ```
  - [x] Check V29 migration for exact column names before finalising test SQL

### Frontend — DrillDetailPanel Extension

- [x] **Task 22.5: Extend `DrillDetailPanel.vue`** — add `add-to-session` emit
  - [x] File: `src/frontend/src/components/session/DrillDetailPanel.vue`
  - [x] **Problem**: `DrillDetailPanel.vue` currently only emits `['close']`. `SessionBuilderPage.vue` wires `@add-to-session="onAddDrillToActiveBlock"` on this component, but without the emit that handler never fires — clicking nothing in the panel can add a drill.
  - [x] Add `'add-to-session'` to the `defineEmits` array:
    ```js
    const emit = defineEmits(['close', 'add-to-session'])
    ```
  - [x] In the panel template, add an "Add to Session" action button visible only when the panel is used in a session builder context. Add a `context` prop:
    ```js
    const props = defineProps({
      drill: { type: Object, required: true },
      context: { type: String, default: 'library' },  // 'library' | 'session-builder'
    })
    ```
  - [x] In the panel's action area, add:
    ```vue
    <q-btn
      v-if="props.context === 'session-builder'"
      color="primary"
      :label="t('session.drillLibrary.addToSession')"
      @click="emit('add-to-session', props.drill)"
    />
    ```
  - [x] In `SessionBuilderPage.vue`, pass `:context="'session-builder'"` to `<DrillDetailPanel>` so the button renders.

### Frontend — Dependencies

- [x] **Task 23: Install `vuedraggable@next`**
  - [x] Working directory: `src/frontend/`
  - [x] Run: `npm install vuedraggable@next`
  - [x] This installs `vuedraggable` v4.x (Vue 3 compatible SortableJS wrapper)
  - [x] Import in components: `import draggable from 'vuedraggable'`
  - [x] `tus-js-client` is already in dependencies — do NOT reinstall

### Frontend — API

- [x] **Task 24: Update `session.api.js`** — add session plan endpoints
  - [x] File: `src/frontend/src/api/session.api.js`
  - [x] Keep all existing methods; add:
    ```js
    createSessionPlan(payload) {
      // payload: { bookingId, blocks: [{blockType, blockName, durationMinutes, drills: [{drillId, order}]}], developmentFocus: [] }
      return api.post('/api/session/sessions', payload)
    },

    updateSessionPlan(sessionId, payload) {
      return api.put(`/api/session/sessions/${sessionId}`, payload)
    },

    getSessionPlan(sessionId) {
      return api.get(`/api/session/sessions/${sessionId}`)
    },

    getSessionPlanByBooking(bookingId) {
      return api.get(`/api/session/sessions/by-booking/${bookingId}`)
    },
    ```

### Frontend — Store

- [x] **Task 25: Create `sessionBuilder.store.js`** — dedicated store for session builder state
  - [x] File: `src/frontend/src/stores/sessionBuilder.store.js`
  - [x] Do NOT add session builder state to the existing `session.store.js` (that store owns drill library state; session builder is a separate concern)
  - [x] Store:
    ```js
    import { defineStore } from 'pinia'
    import { ref, computed } from 'vue'
    import { sessionApi } from 'src/api/session.api'

    export const DEFAULT_BLOCKS = [
      { blockType: 'WARM_UP',             blockName: 'Warm-Up',              durationMinutes: 10, drills: [] },
      { blockType: 'TECHNICAL_FOUNDATION', blockName: 'Technical Foundation', durationMinutes: 15, drills: [] },
      { blockType: 'GAME_INTENSITY',       blockName: 'Game Intensity',       durationMinutes: 25, drills: [] },
      { blockType: 'COOL_DOWN_REVIEW',     blockName: 'Cool-Down & Review',   durationMinutes: 10, drills: [] },
    ]

    export const useSessionBuilderStore = defineStore('sessionBuilder', () => {
      const sessionPlanId = ref(null)        // null = not yet saved
      const bookingId = ref(null)
      const blocks = ref(JSON.parse(JSON.stringify(DEFAULT_BLOCKS)))  // deep copy
      const developmentFocus = ref([])
      const status = ref('DRAFT')
      const loading = ref(false)
      const error = ref(null)

      // Client-side DNA calculation — mirrors SessionDnaCalculator.java logic exactly
      const sessionDna = computed(() => {
        const allDrills = blocks.value.flatMap(b => b.drills || [])
        if (!allDrills.length) {
          return { technical: 0, physical: 0, cognitive: 0, matchRealism: 0, weakFootFocus: 0 }
        }
        const count = allDrills.length
        let sumIntensity = 0, sumPressure = 0, sumCognitive = 0, sumMatchRealism = 0, weakFootCount = 0
        for (const drill of allDrills) {
          const m = drill.metadata || {}
          sumIntensity  += m.intensity      ?? 1
          sumPressure   += m.pressureLevel  ?? 1
          sumCognitive  += m.cognitiveLoad  ?? 1
          sumMatchRealism += m.matchRealism ?? 1
          if (m.weakFootBias) weakFootCount++
        }
        const map100 = (avg) => Math.max(0, Math.min(100, Math.round((avg - 1) * 25)))
        return {
          technical:    map100((sumIntensity + sumPressure) / (2 * count)),
          physical:     map100(sumIntensity / count),
          cognitive:    map100(sumCognitive / count),
          matchRealism: map100(sumMatchRealism / count),
          weakFootFocus: Math.round(weakFootCount / count * 100),
        }
      })

      // SLU subtotal per block
      function blockSlu(block) {
        return (block.drills || []).reduce((sum, drill) => {
          const m = drill.metadata || {}
          const weightSum = m.skillWeighting
            ? Object.values(m.skillWeighting).reduce((a, b) => a + b, 0)
            : 1
          return sum + (m.repDensity ?? 0) * weightSum
        }, 0)
      }

      // Equipment list — computed from all blocks, deduplicated + sorted
      const equipmentList = computed(() => {
        const allEquipment = blocks.value
          .flatMap(b => b.drills || [])
          .flatMap(d => d.metadata?.equipmentRequired || [])
          .map(e => e.toLowerCase().trim())
          .filter(e => e.length > 0)
        return [...new Set(allEquipment)].sort()
      })

      function initForBooking(bId) {
        bookingId.value = bId
        sessionPlanId.value = null
        blocks.value = JSON.parse(JSON.stringify(DEFAULT_BLOCKS))
        developmentFocus.value = []
        status.value = 'DRAFT'
        error.value = null
      }

      function loadFromResponse(response) {
        sessionPlanId.value = response.id
        bookingId.value = response.bookingId
        status.value = response.status
        developmentFocus.value = [...(response.developmentFocus || [])]
        // Hydrate blocks: each block's drills array contains full DrillResponse objects from server
        blocks.value = (response.blocks || []).map(b => ({
          blockType: b.blockType,
          blockName: b.blockName,
          durationMinutes: b.durationMinutes,
          drills: (b.drills || [])
            .sort((a, b) => a.order - b.order)
            .map(d => d.drill)   // d.drill is the full DrillResponse object
            .filter(Boolean),
        }))
      }

      async function fetchExistingPlan() {
        if (!bookingId.value) return
        loading.value = true
        try {
          const res = await sessionApi.getSessionPlanByBooking(bookingId.value)
          loadFromResponse(res.data)
        } catch (e) {
          if (e?.response?.status === 404) {
            // No plan yet — keep default blocks
          } else if (e?.response?.data?.helpCode === 'security.featureGated') {
            // Store the error so SessionBuilderPage.onMounted can detect and show the gate immediately
            error.value = e
          } else {
            error.value = e
          }
        } finally {
          loading.value = false
        }
      }

      async function savePlan() {
        loading.value = true
        error.value = null
        try {
          const payload = buildPayload('DRAFT')
          if (sessionPlanId.value) {
            const res = await sessionApi.updateSessionPlan(sessionPlanId.value, payload)
            loadFromResponse(res.data)
          } else {
            const res = await sessionApi.createSessionPlan(payload)
            loadFromResponse(res.data)
          }
        } catch (e) {
          error.value = e
          throw e
        } finally {
          loading.value = false
        }
      }

      function buildPayload(targetStatus) {
        return {
          bookingId: sessionPlanId.value ? undefined : bookingId.value,
          blocks: blocks.value.map((b, bi) => ({
            blockType: b.blockType,
            blockName: b.blockName,
            durationMinutes: b.durationMinutes,
            drills: (b.drills || []).map((d, di) => ({ drillId: d.id, order: di })),
          })),
          developmentFocus: [...developmentFocus.value],
          status: targetStatus,
        }
      }

      // Drill management
      function addDrillToBlock(blockIndex, drill) {
        if (blocks.value[blockIndex].drills.some(d => d.id === drill.id)) return  // no duplicates per block
        blocks.value[blockIndex].drills.push({ ...drill })
      }

      function removeDrillFromBlock(blockIndex, drillId) {
        blocks.value[blockIndex].drills = blocks.value[blockIndex].drills.filter(d => d.id !== drillId)
      }

      function updateBlockMeta(blockIndex, { blockName, durationMinutes }) {
        if (blockName !== undefined) blocks.value[blockIndex].blockName = blockName
        if (durationMinutes !== undefined) blocks.value[blockIndex].durationMinutes = durationMinutes
      }

      return {
        sessionPlanId, bookingId, blocks, developmentFocus, status,
        sessionDna, equipmentList, loading, error,
        initForBooking, fetchExistingPlan, savePlan,
        addDrillToBlock, removeDrillFromBlock, updateBlockMeta, blockSlu, loadFromResponse,
      }
    })
    ```
  - [x] The `blocks.value[blockIndex].drills` array contains **full `DrillResponse` objects** (not just IDs) so the frontend can compute DNA scores client-side from `drill.metadata`. When adding from the drill library, pass the whole drill object. When loading from the server, `d.drill` provides the full object.
  - [x] `addDrillToBlock` prevents the same drill from being added twice to the same block. The same drill CAN appear in different blocks (allowed per product spec).

### Frontend — Components

- [x] **Task 26: Update `SessionDNAChart.vue`** — implement real 5-axis SVG radar
  - [x] File: `src/frontend/src/components/booking/SessionDNAChart.vue`
  - [x] **Replace the existing stub entirely** — the stub only has a placeholder polygon. Replace with:
    ```vue
    <template>
      <div class="session-dna-chart" :class="`session-dna-chart--${variant}`">
        <svg
          class="session-dna-chart__radar"
          viewBox="0 0 120 120"
          xmlns="http://www.w3.org/2000/svg"
          :aria-label="t('session.dna.chartAriaLabel')"
        >
          <!-- Grid rings -->
          <circle v-for="r in [50, 37.5, 25, 12.5]" :key="r"
            cx="60" cy="60" :r="r"
            fill="none" stroke="var(--border-subtle)" stroke-width="1" />

          <!-- Axis lines -->
          <line v-for="ax in axes" :key="ax.key"
            :x1="60" :y1="60"
            :x2="60 + 50 * Math.cos(toRad(ax.angle))"
            :y2="60 + 50 * Math.sin(toRad(ax.angle))"
            stroke="var(--border-subtle)" stroke-width="1" />

          <!-- Data polygon -->
          <polygon
            :points="polygonPoints"
            fill="var(--accent-primary)"
            fill-opacity="0.25"
            stroke="var(--accent-primary)"
            stroke-width="2"
          />

          <!-- Data points -->
          <circle v-for="(pt, i) in dataPoints" :key="i"
            :cx="pt.x" :cy="pt.y" r="3"
            fill="var(--accent-primary)" />

          <!-- Axis labels (compact variant only shows dots; full variant shows labels) -->
          <text v-if="variant === 'full'"
            v-for="ax in axes" :key="`label-${ax.key}`"
            :x="60 + 58 * Math.cos(toRad(ax.angle))"
            :y="60 + 58 * Math.sin(toRad(ax.angle))"
            text-anchor="middle" dominant-baseline="middle"
            font-size="6" fill="var(--text-secondary)">
            {{ t(`session.dna.axis.${ax.key}`) }}
          </text>
        </svg>

        <!-- Score table (full variant) -->
        <div v-if="variant === 'full'" class="session-dna-chart__scores q-mt-sm">
          <div v-for="ax in axes" :key="`score-${ax.key}`" class="row justify-between text-caption">
            <span>{{ t(`session.dna.axis.${ax.key}`) }}</span>
            <span>{{ sessionDna[ax.key] }}</span>
          </div>
        </div>

        <!-- Wrap-up confirmation indicator (kept for backward compat with WrapUpSequence) -->
        <div v-if="showConfirmation" class="session-dna-chart__confirmed">
          <q-icon name="check_circle" color="positive" size="32px" />
          <span class="text-body1 q-ml-sm">{{ t('booking.completion.summaryTitle') }}</span>
        </div>
      </div>
    </template>

    <script setup>
    import { computed } from 'vue'
    import { useI18n } from 'vue-i18n'

    const props = defineProps({
      bookingId: { type: String, default: null },
      variant: { type: String, default: 'compact' },  // 'compact' | 'full'
      sessionDna: {
        type: Object,
        default: () => ({ technical: 0, physical: 0, cognitive: 0, matchRealism: 0, weakFootFocus: 0 }),
      },
      showConfirmation: { type: Boolean, default: false },
    })

    const { t } = useI18n()

    // 5 axes: start at top (-90°), 72° apart, clockwise
    const axes = [
      { key: 'technical',    angle: -90  },
      { key: 'physical',     angle: -18  },
      { key: 'cognitive',    angle:  54  },
      { key: 'matchRealism', angle: 126  },
      { key: 'weakFootFocus',angle: 198  },
    ]

    const toRad = (deg) => deg * Math.PI / 180

    const dataPoints = computed(() =>
      axes.map(ax => {
        const value = Math.max(0, Math.min(100, props.sessionDna[ax.key] ?? 0))
        const norm = value / 100
        return {
          x: 60 + norm * 50 * Math.cos(toRad(ax.angle)),
          y: 60 + norm * 50 * Math.sin(toRad(ax.angle)),
        }
      })
    )

    const polygonPoints = computed(() =>
      dataPoints.value.map(p => `${p.x.toFixed(2)},${p.y.toFixed(2)}`).join(' ')
    )
    </script>

    <style lang="scss" scoped>
    .session-dna-chart {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
    }

    .session-dna-chart__radar {
      width: 160px;
      height: 160px;
    }

    .session-dna-chart--compact .session-dna-chart__radar {
      width: 80px;
      height: 80px;
    }

    .session-dna-chart__confirmed {
      display: flex;
      align-items: center;
      margin-top: 8px;
    }
    </style>
    ```
  - [x] **Backward compat**: `bookingId` prop is retained but unused in rendering (it was used in the stub placeholder text; the placeholder text is removed). Callers passing `bookingId` do not break. The `showConfirmation` prop replaces the always-visible confirmation indicator — callers in `WrapUpSequence.vue` should pass `:show-confirmation="true"` to preserve existing behaviour. Check `WrapUpSequence.vue` to verify it currently renders `<SessionDNAChart>` and add `showConfirmation` prop there.
  - [x] **Wire `SessionDNAChart` into `WrapUpSequence.vue`** — AC2 requires the full-size chart to appear in the wrap-up summary. `WrapUpSequence.vue` currently has no `<SessionDNAChart>` at all. Add it:
    - Import `SessionDNAChart` in `WrapUpSequence.vue`
    - Place `<SessionDNAChart variant="full" :session-dna="sessionDnaFromProps" :show-confirmation="true" />` in the wrap-up completion step (the step that shows the completion summary)
    - The `sessionDna` data comes from the session record returned by the booking completion API (Story 3.6). If it's not yet available, pass the all-zeros default and update in a future story.
    - This is a REQUIRED task for AC2, not optional.
  - [x] **Focus dimension highlighting is OUT OF SCOPE for Story 4.4** — AC6 mentions "the Session DNA panel highlights the focus dimensions." This requires the chart to know which axes correspond to selected focuses, which requires additional props and axis-colouring logic. Defer to Story 4.5 (which already wires development focus into drill suggestions). Story 4.4 delivers the chart rendering and focus selection; the highlight connection is 4.5 scope.

- [x] **Task 27: Create `SessionBlockView.vue`** — a single session block with draggable drill list
  - [x] File: `src/frontend/src/components/session/SessionBlockView.vue`
  - [x] This component renders one block. `SessionBuilderPage.vue` renders four of these.
  - [x] Component:
    ```vue
    <template>
      <q-card class="session-block glass-card q-mb-md">
        <!-- Block header -->
        <q-card-section class="session-block__header row items-center">
          <q-input
            v-model="localBlockName"
            dense
            borderless
            class="session-block__name text-subtitle1 text-weight-bold col"
            @blur="emitMetaUpdate"
          />
          <div class="row items-center q-gutter-sm">
            <q-chip dense color="secondary">
              {{ t('session.builder.sluLabel', { slu: sluSubtotal }) }}
            </q-chip>
            <q-input
              v-model.number="localDuration"
              type="number"
              dense
              borderless
              suffix="min"
              style="width: 70px"
              @blur="emitMetaUpdate"
            />
          </div>
        </q-card-section>

        <!-- Drill list (drag-and-drop) -->
        <q-card-section class="session-block__drills q-pt-none">
          <draggable
            v-model="localDrills"
            :group="{ name: 'drills', pull: true, put: true }"
            item-key="id"
            handle=".drill-drag-handle"
            @change="onDragChange"
          >
            <template #item="{ element: drill }">
              <div class="session-block__drill-row row items-center q-mb-xs">
                <q-icon name="drag_indicator" class="drill-drag-handle q-mr-sm cursor-grab" />
                <DrillCard
                  :drill="drill"
                  class="col"
                  context="session-builder"
                  @open-detail="emit('open-drill-detail', drill)"
                />
                <q-btn
                  flat dense round icon="close" color="negative" size="sm"
                  class="q-ml-xs"
                  :aria-label="t('session.builder.removeDrill')"
                  @click="removeDrill(drill.id)"
                />
              </div>
            </template>
            <template #footer>
              <div
                v-if="localDrills.length === 0"
                class="session-block__empty text-caption text-center q-pa-md"
                style="color: var(--text-secondary)"
              >
                {{ t('session.builder.emptyBlockHint') }}
              </div>
            </template>
          </draggable>
        </q-card-section>
      </q-card>
    </template>

    <script setup>
    import { ref, watch } from 'vue'
    import { useI18n } from 'vue-i18n'
    import draggable from 'vuedraggable'
    import DrillCard from 'src/components/session/DrillCard.vue'

    const props = defineProps({
      blockIndex: { type: Number, required: true },
      blockType:  { type: String, required: true },
      blockName:  { type: String, required: true },
      durationMinutes: { type: Number, required: true },
      drills:     { type: Array, default: () => [] },
      sluSubtotal:{ type: Number, default: 0 },
    })

    const emit = defineEmits(['update:drills', 'update:meta', 'open-drill-detail'])
    const { t } = useI18n()

    const localDrills   = ref([...props.drills])
    const localBlockName = ref(props.blockName)
    const localDuration  = ref(props.durationMinutes)

    // Sync when parent updates (e.g., after drag-and-drop across blocks)
    watch(() => props.drills, (val) => { localDrills.value = [...val] }, { deep: true })

    function onDragChange() {
      emit('update:drills', { blockIndex: props.blockIndex, drills: [...localDrills.value] })
    }

    function removeDrill(drillId) {
      localDrills.value = localDrills.value.filter(d => d.id !== drillId)
      emit('update:drills', { blockIndex: props.blockIndex, drills: [...localDrills.value] })
    }

    function emitMetaUpdate() {
      emit('update:meta', {
        blockIndex: props.blockIndex,
        blockName: localBlockName.value,
        durationMinutes: Number(localDuration.value),
      })
    }
    </script>

    <style lang="scss" scoped>
    .session-block__drill-row {
      border-radius: 8px;
      &:hover { background: var(--surface-hover); }
    }
    .drill-drag-handle { touch-action: none; }
    </style>
    ```
  - [x] `DrillCard` component already has a `context` prop pattern — check `DrillCard.vue` to see if `context="session-builder"` needs to be added to its `props` definition. If not, it can be ignored (Vue ignores unknown props). Add it if `DrillCard` uses it to change behaviour (e.g., hiding the "Add to session" button when already in the builder).
  - [x] `draggable` uses `:group="{ name: 'drills', pull: true, put: true }"` — same group name across all `SessionBlockView` instances enables cross-block drag.

- [x] **Task 28: Create `DevelopmentFocusSelector.vue`** — multi-select focus picker
  - [x] File: `src/frontend/src/components/session/DevelopmentFocusSelector.vue`
  - [x] The Development Focus options align with the SLU taxonomy (Epic 5) but are defined here as constants. Use string keys that match i18n keys.
  - [x] Focus options: `['technical', 'physical', 'cognitive', 'matchRealism', 'weakFoot', 'set_pieces', 'goalkeeping', 'possession']` — 8 options max
  - [x] Component:
    ```vue
    <template>
      <div class="development-focus-selector">
        <div class="text-subtitle2 q-mb-sm">{{ t('session.builder.developmentFocusLabel') }}</div>
        <div class="row q-gutter-sm">
          <q-chip
            v-for="focus in FOCUS_OPTIONS"
            :key="focus"
            clickable
            :color="selectedFocuses.includes(focus) ? 'primary' : undefined"
            :outline="!selectedFocuses.includes(focus)"
            :text-color="selectedFocuses.includes(focus) ? 'white' : undefined"
            @click="toggleFocus(focus)"
          >
            {{ t(`session.builder.focus.${focus}`) }}
          </q-chip>
        </div>
        <div v-if="selectedFocuses.length === 0" class="text-caption text-negative q-mt-xs">
          {{ t('session.builder.developmentFocusRequired') }}
        </div>
      </div>
    </template>

    <script setup>
    import { useI18n } from 'vue-i18n'

    const FOCUS_OPTIONS = [
      'technical', 'physical', 'cognitive', 'matchRealism',
      'weakFoot', 'set_pieces', 'goalkeeping', 'possession',
    ]

    const props = defineProps({
      modelValue: { type: Array, default: () => [] },
    })
    const emit = defineEmits(['update:modelValue'])
    const { t } = useI18n()

    const selectedFocuses = computed(() => props.modelValue)

    function toggleFocus(focus) {
      const current = [...props.modelValue]
      const idx = current.indexOf(focus)
      if (idx >= 0) current.splice(idx, 1)
      else current.push(focus)
      emit('update:modelValue', current)
    }
    </script>
    ```
  - [x] Add `import { computed } from 'vue'` in the `<script setup>` — `computed` is required for `selectedFocuses`

### Frontend — Page

- [x] **Task 29: Create `SessionBuilderPage.vue`**
  - [x] File: `src/frontend/src/pages/coach/SessionBuilderPage.vue`
  - [x] Route parameter: `:bookingId` (UUID string)
  - [x] Layout: 3-column on desktop, single-column-stacked on mobile
    - Column 1 (left, 300px): Drill library panel — reuse `DrillLibraryPage.vue` logic inline (uses `useSessionStore` to load drills; search/filter available)
    - Column 2 (center): Four `SessionBlockView.vue` components + "Save Session" button
    - Column 3 (right, 280px): `SessionDNAChart.vue` (compact) + `DevelopmentFocusSelector.vue` + equipment list accordion
  - [x] Page:
    ```vue
    <template>
      <q-page class="session-builder-page q-pa-md">
        <!-- Scout gate overlay -->
        <div v-if="showScoutGate" class="session-builder-page__gate text-center q-pa-xl">
          <q-icon name="lock" size="64px" color="grey-5" />
          <div class="text-h6 q-mt-md">{{ t('session.builder.scoutGateTitle') }}</div>
          <div class="text-body2 q-mt-sm">{{ t('session.builder.scoutGateBody') }}</div>
          <q-btn color="primary" class="q-mt-lg" :label="t('common.upgradeNow')" to="/pricing" />
        </div>

        <template v-else>
          <div class="row q-gutter-md">
            <!-- Left: Drill Library panel -->
            <div class="session-builder-page__library col-12 col-md-3">
              <div class="text-subtitle1 q-mb-sm">{{ t('session.drillLibrary.title') }}</div>
              <q-input v-model="drillSearchQuery" dense outlined clearable
                :placeholder="t('session.drillLibrary.searchPlaceholder')"
                class="q-mb-sm">
                <template #prepend><q-icon name="search" /></template>
              </q-input>
              <div class="session-builder-page__drill-list">
                <DrillCard
                  v-for="drill in filteredDrills"
                  :key="drill.id"
                  :drill="drill"
                  class="q-mb-sm"
                  context="session-builder"
                  @add-to-session="onAddDrillToActiveBlock"
                  @open-detail="openDrillDetail"
                />
              </div>
            </div>

            <!-- Center: Blocks -->
            <div class="session-builder-page__blocks col-12 col-md-5">
              <div class="row justify-between items-center q-mb-md">
                <div class="text-h6">{{ t('session.builder.title') }}</div>
                <q-btn
                  color="primary"
                  :label="t('session.builder.saveButton')"
                  :loading="builderStore.loading"
                  :disable="builderStore.developmentFocus.length === 0"
                  @click="savePlan"
                />
              </div>

              <SessionBlockView
                v-for="(block, idx) in builderStore.blocks"
                :key="block.blockType"
                :block-index="idx"
                :block-type="block.blockType"
                :block-name="block.blockName"
                :duration-minutes="block.durationMinutes"
                :drills="block.drills"
                :slu-subtotal="builderStore.blockSlu(block)"
                @update:drills="onDrillsUpdate"
                @update:meta="onMetaUpdate"
                @open-drill-detail="openDrillDetail"
              />
            </div>

            <!-- Right: DNA + Focus + Equipment -->
            <div class="session-builder-page__sidebar col-12 col-md-3">
              <div class="text-subtitle1 q-mb-sm">{{ t('session.builder.dnaTitle') }}</div>
              <SessionDNAChart :session-dna="builderStore.sessionDna" variant="compact" />

              <q-separator class="q-my-md" />

              <DevelopmentFocusSelector
                v-model="builderStore.developmentFocus"
              />

              <q-separator class="q-my-md" />

              <!-- Equipment tab -->
              <q-expansion-item
                :label="t('session.builder.equipmentTitle')"
                icon="sports_soccer"
                default-opened
              >
                <div v-if="builderStore.equipmentList.length === 0" class="text-caption text-center q-py-sm">
                  {{ t('session.builder.equipmentEmpty') }}
                </div>
                <q-list v-else dense>
                  <q-item v-for="item in builderStore.equipmentList" :key="item">
                    <q-item-section>{{ item }}</q-item-section>
                  </q-item>
                </q-list>
              </q-expansion-item>
            </div>
          </div>
        </template>

        <!-- Drill detail side panel (reuse existing DrillDetailPanel) -->
        <DrillDetailPanel
          v-if="selectedDrill"
          :drill="selectedDrill"
          context="session-builder"
          @close="selectedDrill = null"
          @add-to-session="onAddDrillToActiveBlock"
        />
      </q-page>
    </template>

    <script setup>
    import { ref, computed, onMounted } from 'vue'
    import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
    import { useQuasar } from 'quasar'
    import { useI18n } from 'vue-i18n'
    import { useSessionStore } from 'src/stores/session.store'
    import { useSessionBuilderStore } from 'src/stores/sessionBuilder.store'
    import SessionBlockView from 'src/components/session/SessionBlockView.vue'
    import SessionDNAChart from 'src/components/booking/SessionDNAChart.vue'
    import DevelopmentFocusSelector from 'src/components/session/DevelopmentFocusSelector.vue'
    import DrillCard from 'src/components/session/DrillCard.vue'
    import DrillDetailPanel from 'src/components/session/DrillDetailPanel.vue'

    const route = useRoute()
    const router = useRouter()
    const $q = useQuasar()
    const { t } = useI18n()
    const sessionStore = useSessionStore()
    const builderStore = useSessionBuilderStore()

    const showScoutGate = ref(false)
    const selectedDrill = ref(null)
    const activeBlockIndex = ref(0)
    const drillSearchQuery = ref('')

    const filteredDrills = computed(() => {
      const q = drillSearchQuery.value?.toLowerCase() ?? ''
      return sessionStore.drills.filter(d =>
        !q || d.name.toLowerCase().includes(q)
      )
    })

    onMounted(async () => {
      const bId = route.params.bookingId
      builderStore.initForBooking(bId)
      // Load drill library — the store action is fetchDrills, NOT loadDrills
      await sessionStore.fetchDrills('ALL')
      // Fetch existing plan (also surfaces scout gate early — see fetchExistingPlan)
      await builderStore.fetchExistingPlan()
      // If the store surfaced a featureGated error from the plan fetch, show the gate now
      if (builderStore.error?.response?.data?.helpCode === 'security.featureGated') {
        showScoutGate.value = true
        builderStore.error = null
      }
    })

    // Guard unsaved changes on navigation away
    onBeforeRouteLeave((to, from, next) => {
      const hasUnsaved = builderStore.blocks.some(b => b.drills.length > 0) && !builderStore.sessionPlanId
      if (hasUnsaved) {
        $q.dialog({
          title: t('session.builder.unsavedChangesTitle'),
          message: t('session.builder.unsavedChangesMessage'),
          cancel: true,
          persistent: true,
        }).onOk(() => next()).onCancel(() => next(false))
      } else {
        next()
      }
    })

    function onDrillsUpdate({ blockIndex, drills }) {
      builderStore.blocks[blockIndex].drills = drills
    }

    function onMetaUpdate({ blockIndex, blockName, durationMinutes }) {
      builderStore.updateBlockMeta(blockIndex, { blockName, durationMinutes })
    }

    function openDrillDetail(drill) {
      selectedDrill.value = drill
    }

    function onAddDrillToActiveBlock(drill) {
      builderStore.addDrillToBlock(activeBlockIndex.value, drill)
      selectedDrill.value = null
    }

    async function savePlan() {
      try {
        await builderStore.savePlan()
        $q.notify({ type: 'positive', message: t('session.builder.savedSuccess') })
      } catch (e) {
        const helpCode = e?.response?.data?.helpCode
        if (helpCode === 'security.featureGated') {
          // Scout-tier coach somehow reached save — show gate (normally caught on mount)
          showScoutGate.value = true
        } else if (helpCode === 'SESSION_ALREADY_EXISTS') {
          // Race condition: plan was created by another tab; reload existing plan
          await builderStore.fetchExistingPlan()
          $q.notify({ type: 'warning', message: t('session.builder.planAlreadyExists') })
        } else {
          $q.notify({ type: 'negative', message: t('session.builder.saveFailed') })
        }
      }
    }
    </script>

    <style lang="scss" scoped>
    .session-builder-page__library {
      max-height: calc(100vh - 120px);
      overflow-y: auto;
    }
    .session-builder-page__drill-list {
      display: flex;
      flex-direction: column;
    }
    .session-builder-page__blocks {
      min-height: 400px;
    }
    .session-builder-page__sidebar {
      position: sticky;
      top: 16px;
    }
    .session-builder-page__gate {
      max-width: 400px;
      margin: 0 auto;
    }
    </style>
    ```
  - [x] `sessionStore.loadDrills('ALL')` — check the actual action name in `session.store.js`; it may be `fetchDrills` or `loadDrills`. Match the existing store API exactly.
  - [x] `DrillDetailPanel.vue` currently emits `close`. Check if it emits `add-to-session` — if not, the handler won't fire. The panel's "Add" button text changes based on `context` prop (Story 4.2 or 4.3 pattern). Review `DrillDetailPanel.vue` to understand the correct event to handle.
  - [x] The save button is `disabled` when `developmentFocus.length === 0` — this enforces AC 6 in the UI.

### Frontend — Router

- [x] **Task 30: Add session builder route**
  - [x] File: `src/frontend/src/router/routes.js`
  - [x] Add within the authenticated coach routes section:
    ```js
    {
      path: 'coach/session-builder/:bookingId',
      name: 'coach-session-builder',
      component: () => import('pages/coach/SessionBuilderPage.vue'),
    },
    ```
  - [x] Place it after the `coach/drills` route (line ~149) to maintain logical grouping

### Frontend — i18n

- [x] **Task 31: Add i18n keys to `en/index.js` and `de/index.js`**
  - [x] File: `src/frontend/src/i18n/en/index.js`
  - [x] Add under `session` key (alongside existing `session.drillLibrary` key):
    ```js
    builder: {
      title: 'Session Builder',
      saveButton: 'Save Session',
      savedSuccess: 'Session plan saved',
      saveFailed: 'Could not save session. Please try again.',
      sluLabel: '~{slu} SLU',
      emptyBlockHint: 'Drag drills here or tap from the library',
      removeDrill: 'Remove drill from block',
      developmentFocusLabel: 'Development Focus',
      developmentFocusRequired: 'Select at least one development focus',
      dnaTitle: 'Session DNA',
      equipmentTitle: 'Equipment Needed',
      equipmentEmpty: 'No equipment required',
      scoutGateTitle: 'Session Builder — Instructor+ Feature',
      scoutGateBody: 'Upgrade your plan to access structured session planning with DNA analysis.',
      unsavedChangesTitle: 'Unsaved Changes',
      unsavedChangesMessage: 'You have unsaved changes. Leave without saving?',
      planAlreadyExists: 'A session plan already exists for this booking — it has been loaded.',
      focus: {
        technical: 'Technical',
        physical: 'Physical',
        cognitive: 'Cognitive',
        matchRealism: 'Match Realism',
        weakFoot: 'Weak Foot',
        set_pieces: 'Set Pieces',
        goalkeeping: 'Goalkeeping',
        possession: 'Possession',
      },
    },
    dna: {
      chartAriaLabel: 'Session DNA radar chart',
      axis: {
        technical: 'Tech',
        physical: 'Phys',
        cognitive: 'Cog',
        matchRealism: 'Match',
        weakFootFocus: 'Weak',
      },
    },
    ```
  - [x] File: `src/frontend/src/i18n/de/index.js`
  - [x] Add matching German translations at the same path `session.builder` and `session.dna`:
    ```js
    builder: {
      title: 'Session-Planer',
      saveButton: 'Session speichern',
      savedSuccess: 'Session-Plan gespeichert',
      saveFailed: 'Session konnte nicht gespeichert werden. Bitte erneut versuchen.',
      sluLabel: '~{slu} SLU',
      emptyBlockHint: 'Übungen hierher ziehen oder aus der Bibliothek tippen',
      removeDrill: 'Übung aus Block entfernen',
      developmentFocusLabel: 'Entwicklungsschwerpunkt',
      developmentFocusRequired: 'Mindestens einen Schwerpunkt auswählen',
      dnaTitle: 'Session-DNA',
      equipmentTitle: 'Benötigtes Material',
      equipmentEmpty: 'Kein Material erforderlich',
      scoutGateTitle: 'Session-Planer — Instructor+-Funktion',
      scoutGateBody: 'Upgrade deinen Plan für strukturierte Trainingsplanung mit DNA-Analyse.',
      unsavedChangesTitle: 'Nicht gespeicherte Änderungen',
      unsavedChangesMessage: 'Du hast nicht gespeicherte Änderungen. Ohne Speichern verlassen?',
      planAlreadyExists: 'Ein Session-Plan für diese Buchung existiert bereits — er wurde geladen.',
      focus: {
        technical: 'Technik',
        physical: 'Kondition',
        cognitive: 'Kognition',
        matchRealism: 'Spielrealismus',
        weakFoot: 'Schwacher Fuß',
        set_pieces: 'Standards',
        goalkeeping: 'Torwart',
        possession: 'Ballbesitz',
      },
    },
    dna: {
      chartAriaLabel: 'Session-DNA-Radardiagramm',
      axis: {
        technical: 'Tech',
        physical: 'Phys',
        cognitive: 'Kog',
        matchRealism: 'Match',
        weakFootFocus: 'SF',
      },
    },
    ```

## Review Findings

### Decision-Needed

- [x] [Review][Decision] Scout gate check uses wrong proxy signal — **resolved: remove `checkGate()` entirely; detect gate from `fetchExistingPlan` 403 featureGated response instead.** [sessionBuilder.store.js:148-151, SessionBuilderPage.vue:202]

### Patches

- [x] [Review][Patch] Default blocks mismatch AC1 — `blockType` values wrong (`'TECHNICAL'` → `'TECHNICAL_FOUNDATION'`, `'COOL_DOWN'` → `'COOL_DOWN_REVIEW'`); durations wrong (Technical 25→15 min, Game Intensity 20→25 min, Cool-Down 5→10 min); `blockName` wrong (`'Technical'` → `'Technical Foundation'`, `'Cool-Down'` → `'Cool-Down & Review'`); i18n `blockType` map keys must match. [sessionBuilder.store.js:62-67, en/index.js, de/index.js]
- [x] [Review][Patch] Save button missing `:disable` binding — `developmentFocus.length === 0` allows save without focus area selected; violates AC6 explicit requirement that the save button must be disabled. [SessionBuilderPage.vue:25-30]
- [x] [Review][Patch] Save error uses wrong property path — `e?.response?.data?.errorMsg?.errorKey` should be `e?.response?.data?.helpCode` to match `ErrorDto` shape; `SESSION_PLAN_LOCKED` notification never fires. [SessionBuilderPage.vue:254]
- [x] [Review][Patch] WrapUpSequence confirmation indicator hidden when no session plan — `v-if="sessionDnaData"` gates the entire `SessionDNAChart` including the `showConfirmation` icon; confirmation that previously appeared unconditionally is now suppressed for all bookings without a session plan. [WrapUpSequence.vue:161]
- [x] [Review][Patch] fetchExistingPlan 403 featureGated does not set isGated — 403 response (Scout gate) falls into the generic `else` branch and sets `error.value` only; `isGated.value` stays false; gate overlay never shown for Scout coaches via the API-response path. [sessionBuilder.store.js:89-93]
- [x] [Review][Patch] savePlan sends `status:"COMPLETED"` causing 400 before SESSION_PLAN_LOCKED 403 — when `status.value` is `"COMPLETED"`, the update payload includes `status:"COMPLETED"` which fails `@Pattern(regexp = "DRAFT|SAVED")` DTO validation, returning 400 before the service guard can throw SESSION_PLAN_LOCKED 403. [sessionBuilder.store.js:104-113, UpdateSessionPlanRequest.java:15]
- [x] [Review][Patch] TOCTOU race on `existsByBookingId` — concurrent create requests both pass the existence check before either commits; the second insert hits the unique index and throws `DataIntegrityViolationException` as an unhandled 500 instead of SESSION_ALREADY_EXISTS 403. [SessionPlanService.java:56-60]
- [x] [Review][Patch] vuedraggable `item-key="blockType"` causes DOM collision on block add — `addBlock()` always pushes `blockType: 'TECHNICAL'`; a second TECHNICAL block creates a duplicate Vue key, causing unpredictable drag-and-drop behavior. Use a stable unique identifier (index or generated UUID) as item-key. [SessionBuilderPage.vue:106]
- [x] [Review][Patch] Frontend `blockSlu` zero when `skillWeighting` empty — JS computes `skillTotal = 0` and contributes `0` to SLU; Java `calculateBlockSlu` falls back to `weightSum = 1` for empty maps; live SLU display diverges from persisted value. [sessionBuilder.store.js:49-56]
- [x] [Review][Patch] Pinia store state not cleared before `fetchExistingPlan` — `sessionId`, `blocks`, `status` from previous booking persist on screen while the async fetch runs; add `initForBooking(bId)` at the start of `fetchExistingPlan` before the API call. [sessionBuilder.store.js:73]
- [x] [Review][Patch] `DevelopmentFocusSelector` has three wrong focus options — implements `'tactical'`, `'decisionMaking'`, `'creativity'` instead of spec-required `'set_pieces'`, `'goalkeeping'`, `'possession'`; i18n keys in `en/index.js` and `de/index.js` must be updated to match. [DevelopmentFocusSelector.vue:37-46]

### Deferred

- [x] [Review][Defer] COMPLETED status transition never wired from booking completion [SessionPlanService.java:110] — deferred, Story 3.6 cross-story dependency
- [x] [Review][Defer] IT test `updateSession_completedSession` does not assert SESSION_PLAN_LOCKED helpCode in response body [SessionBuilderResourceIT.java:271] — deferred, test quality improvement
- [x] [Review][Defer] `WrapUpSequence` uses `variant="compact"` instead of spec-specified `"full"` [WrapUpSequence.vue:163] — deferred, cosmetic deviation
- [x] [Review][Defer] `SessionBlockRequest.drills` has no upper-bound `@Size` constraint — unbounded drill list per block [SessionBlockRequest.java:16] — deferred, hardening, not MVP-blocking
- [x] [Review][Defer] `WrapUpSequence` `fetchSessionDna` has no loading or error state on step 4 [WrapUpSequence.vue:309] — deferred, UX polish
- [x] [Review][Defer] `buildResponse` calls `drillRepository.findAllById` twice for the same ID set (once in `resolveMetaMap`, once in `buildResponse`) [SessionPlanService.java:220] — deferred, performance optimization
- [x] [Review][Defer] IT `tearDown` delete order may fail on future FK additions [SessionBuilderResourceIT.java:104] — deferred, future-proofing only

### Review Findings — Round 2 (2026-06-18, post-implementation audit)

#### Decision-Needed

- [x] [Review][Decision] AC6 — **resolved: patch** — "Save Draft" button must also be disabled when `developmentFocus` is empty; both save paths gate on focus selection per AC6. [SessionBuilderPage.vue:18-25]
- [x] [Review][Decision] AC5 — **resolved: patch** — equipment must be in a separate tab; right sidebar to use `q-tabs` with DNA, Focus, and Equipment as panes. [SessionBuilderPage.vue:142-156]

#### Patches

- [x] [Review][Patch] IT teardown `DELETE FROM main.sec` is a truncated/invalid SQL statement — throws on every test teardown, causing all integration tests to fail with a misleading error rather than an assertion failure. Fix or remove. [SessionBuilderResourceIT.java:120]
- [x] [Review][Patch] `SessionPlanResource.java` not deleted — Task 18 explicitly requires this file to be deleted; the dead `POST /api/session/plans` stub is still live and registers a route in the same domain. [SessionPlanResource.java]
- [x] [Review][Patch] `sessionStore.searchQuery.value = drillSearch.value` silently no-ops — Pinia unwraps refs on access; `sessionStore.searchQuery` is already the raw string, so `.value` assignment is a no-op and the drill search query is never applied. Fix: `sessionStore.searchQuery = drillSearch.value`. [SessionBuilderPage.vue:210]
- [x] [Review][Patch] `handleBack()` double-triggers `onBeforeRouteLeave` dialog — after the user confirms the unsaved-changes dialog in `handleBack`, `router.back()` fires `onBeforeRouteLeave` again because `hasUnsavedChanges` is still `true`. User must confirm twice. Fix: clear `hasUnsavedChanges` before calling `router.back()`. [SessionBuilderPage.vue:264-270]
- [x] [Review][Patch] Double-click save race — no in-flight guard on `savePlan`: two rapid saves both evaluate `if (sessionId.value)` as false and both call `createSessionPlan`, leaving `sessionId` unset after the first 403 and causing all subsequent saves to fail indefinitely. Fix: `if (saving.value) return` at the top of `savePlan`. [sessionBuilder.store.js:119]
- [x] [Review][Patch] `addDrillToBlock` missing duplicate drill guard — the same drill can be added to the same block multiple times with no check; DNA and SLU inflate proportionally. Fix: add `if (block.drills.some(d => d.drillId === drill.id)) return`. [sessionBuilder.store.js:134]
- [x] [Review][Patch] `SESSION_ALREADY_EXISTS` not handled in `save()` + missing i18n key — `save()` only handles `SESSION_PLAN_LOCKED`; a race-condition duplicate create returns 403 `SESSION_ALREADY_EXISTS` but falls through to generic `saveFailed`. Also, `session.builder.planAlreadyExists` key is missing from `en/index.js` and `de/index.js`. [SessionBuilderPage.vue:254, en/index.js, de/index.js]
- [x] [Review][Patch] `hasUnsavedChanges = true` set inside `fetchDrills()` — loading the drill library marks the session as dirty on every search keystroke and on mount, causing the route-leave guard to falsely prompt even when no changes were made. Remove this line from `fetchDrills`. [SessionBuilderPage.vue:212]
- [x] [Review][Patch] Drill library panel uses `q-list`/`q-item` instead of `DrillCard` components — Task 29 requires `DrillCard` with `context="session-builder"`. Current implementation shows only name and difficultyTier, losing SLU estimates, tags, and the rich drill card UX. [SessionBuilderPage.vue:64-88]
- [x] [Review][Patch] `bookingId` prop removed from `SessionDNAChart` — Task 26 requires retaining it for backward compatibility. Any caller passing `:booking-id` will now get a Vue prop warning. Restore: `bookingId: { type: String, default: null }`. [SessionDNAChart.vue:92-99]
- [x] [Review][Patch] `aria-label` hardcoded, not i18n — Task 26/31 require `t('session.dna.chartAriaLabel')` and the `session.dna.chartAriaLabel` i18n key. Add the key to `en/index.js` and `de/index.js` and use it in the template. [SessionDNAChart.vue:9]
- [x] [Review][Patch] Axis label i18n namespace wrong — component uses `t(\`session.dna.${axis.key}\`)` but Task 31 requires `session.dna.axis.*` keys (nested under `axis:`). Both the component template and the i18n files need updating to use the `axis.` sub-namespace. [SessionDNAChart.vue:65, en/index.js, de/index.js]

#### Deferred

- [x] [Review][Defer] `isBookingPlannable` includes `"UPCOMING"` but no known code path transitions a booking to this status [SessionPlanService.java:167] — deferred, proactive future-proofing; harmless if UPCOMING is never set
- [x] [Review][Defer] `updateSession` does not re-validate booking plannable status — a booking cancelled after plan creation can still be updated [SessionPlanService.java:updateSession] — deferred, edge case outside story scope

## Dev Notes

### `DrillResponse` Constructor — CRITICAL, Must Delegate to `DrillLibraryService.toResponse()`

`DrillResponse.java` currently has **14 fields** (`id, name, description, libraryType, ownerCoachId, status, metadata, hasVideo, videoUrl, transKey, createdAt, tags, isClonedByMe, cloneId`). Any inline `new DrillResponse(...)` call in `buildResponse()` that doesn't match this exact signature will not compile — the story's original pseudocode had the wrong field count.

**Mandatory approach**: Make `DrillLibraryService.toResponse()` package-private by removing the `private` modifier. Both `DrillLibraryService` and `SessionPlanService` are in `platform.session.service` — intra-module calls are fine. Call: `drillLibraryService.toResponse(d, false, List.of(), null, null, null)` (hasVideo=false, videoUrl=null — Session Builder doesn't need video playback).

### `checkSessionBuilderGate()` — Call Pattern

`DrillLibraryService.checkSessionBuilderGate(Long coachUserId)` takes a `coachUserId` (Long, the Spring Security principal ID), NOT a `coachId` (UUID, the profile ID). It internally calls `coachProfileService.getCoachIdByUserId()`. Passing the wrong type will cause a runtime error.

In `SessionPlanService`, the gate call is: `drillLibraryService.checkSessionBuilderGate(coachUserId)` — pass the `Long` user ID, not the resolved UUID.

### DNA Calculation Parity — Java vs JavaScript

The `SessionDnaCalculator.java` and the `sessionBuilder.store.js` computed `sessionDna` must use **identical formulas**. Divergence causes the client-side live preview to disagree with the stored value.

Formula used by both:
- Technical: `mapToScore((avgIntensity + avgPressureLevel) / 2)` — average of two 1-5 fields
- Physical: `mapToScore(avgIntensity)` — intensity alone
- Cognitive: `mapToScore(avgCognitiveLoad)`
- Match Realism: `mapToScore(avgMatchRealism)`
- Weak Foot Focus: `round(countWeakFoot / total * 100)` — percentage of drills
- `mapToScore(avg)`: `(avg - 1) * 25`, clamped 0–100

On the frontend, use `m.intensity ?? 1` (fallback 1 if null) — matching the DB insert guarantee that drills always have metadata with valid 1-5 values.

### `SessionBlockData` / `SessionDnaScore` JSONB Deserialization

Hibernate 6 with `@JdbcTypeCode(SqlTypes.JSON)` uses Jackson for JSONB round-trips. For Java `record` types to deserialize correctly, Jackson needs the `ParameterNamesModule` registered (enables matching constructor parameters by name). Spring Boot 3.x auto-registers this module — you do NOT need `@JsonCreator` or `@JsonProperty` on record fields, as long as you compile with `-parameters` (which Spring Boot's Maven plugin does by default).

If deserialization fails with "no suitable constructor" errors: add `@JsonProperty("fieldName")` to each record field, or switch to `public class` with `@NoArgsConstructor` + `@Setter` (less clean but guaranteed to work). The `DrillMetadata` record in the same codebase already works — follow exactly the same pattern.

### `SessionPlanResource.java` — Must Be Deleted

The existing `SessionPlanResource.java` at path `src/main/java/com/softropic/skillars/platform/session/api/SessionPlanResource.java` must be deleted. It registers `/api/session/plans POST` which is now dead code. The stub only calls `drillLibraryService.checkSessionBuilderGate()` — this gate check is now inside `SessionPlanService.createSession()`. Keeping both files creates confusion and an extra unmapped endpoint.

### `SessionBuilderResource` — helpCode Propagation for SESSION_ALREADY_EXISTS

Prior to Task 19, `OperationNotAllowedException` always produced `helpCode: "security.opForbidden"` in the response — the `SessionErrorCode` name was discarded. After Task 19's `ApiAdvice` update, the helpCode becomes the `SessionErrorCode` name (e.g., `"SESSION_ALREADY_EXISTS"`, `"SESSION_BOOKING_NOT_OWNED"`). The frontend uses `helpCode === 'SESSION_ALREADY_EXISTS'` in `savePlan()` to auto-load the existing plan on a race condition. This requires Task 19 to be implemented first. Do not write the frontend error handling before the backend ApiAdvice change is merged.

HTTP status is still 403 for SESSION_ALREADY_EXISTS (not 409). Acceptable for MVP — the frontend distinguishes by helpCode, not HTTP status.

### `BookingQueryService` — Cross-Module Access Pattern

`SessionPlanService` requires booking data (coachId, playerId, status) to validate and create a session plan. The session module must not import `BookingRepository` directly — that crosses `platform.booking.repo` into `platform.session.service`, violating the DDD module boundary. The established project pattern is domain events for cross-module communication, but for a synchronous query a service-layer facade is acceptable.

Task 19.5 creates `BookingQueryService` in `platform.booking.service` which is accessible to `platform.session.service`. The value record `BookingSnapshot` lives in `platform.booking.contract`. This keeps the dependency at the service/contract level, not the repo level.

### vuedraggable Cross-Block Drag Configuration

The key to cross-block drag is the `group` prop:
```js
:group="{ name: 'drills', pull: true, put: true }"
```
All `SessionBlockView` instances must use the **same group name** (`'drills'`). SortableJS uses this to identify compatible drop zones. If the group name differs between blocks, dragging across blocks silently fails.

The drag handle (`handle=".drill-drag-handle"`) restricts drag initiation to the `q-icon[name="drag_indicator"]` element — this prevents accidental drags when the user taps on drill card text or the video area.

`touch-action: none` on the handle is required for iOS touch drag to work. The `DrillDetailPanel` swipe-to-close gesture (if any) and the drag handle may conflict on mobile — test both interactions.

### `SessionDNAChart.vue` — Backward Compatibility Warning

The existing `WrapUpSequence.vue` renders `<SessionDNAChart :booking-id="..." variant="compact">` without a `sessionDna` prop. After Story 4.4's upgrade, the chart renders all-zero scores when `sessionDna` is not provided (because the prop defaults to all zeros). This is the correct behaviour at this stage — Epic 5's SLU engine will wire actual data later.

The `showConfirmation` prop needs to be added. Find `<SessionDNAChart` in `WrapUpSequence.vue` and add `:show-confirmation="true"` — the check_circle icon and "Development record updated" text will continue to appear.

### `fetchDrills` in `session.store.js`

The store action is `fetchDrills` (confirmed in `session.store.js` line 22) — **NOT** `loadDrills`. `SessionBuilderPage.vue` calls `sessionStore.fetchDrills('ALL')`. Using the wrong name throws `sessionStore.loadDrills is not a function` on mount and the drill library never populates. This has been fixed in Task 29's page code.

### Booking Schema for Integration Test

The booking table is `booking.bookings` (schema `booking`, NOT `session`). The `Booking.java` entity is `@Table(schema = "booking", name = "bookings")`. When inserting a test booking in `SessionBuilderResourceIT`, all NOT NULL columns must be present: `id`, `parent_id` (BIGINT), `player_id` (BIGINT), `coach_id` (UUID), `requested_start_time`, `requested_end_time`, `status`, `canonical_timezone`, `version` (0 for new rows), `created_at`, `updated_at`. See Task 22 for the complete SQL template. Missing any of these causes the DB insert to fail and the test to error before running.

### `DrillCard.vue` — `context` Prop

`DrillCard.vue` already supports `context="session-builder"` (added in Story 4.2) — this renders the "Add to Session" button and emits `add-to-session`. Always use `context="session-builder"` for DrillCards in the session builder's left library panel. **Do NOT use `context="add-to-session"` — that is not a recognised context value and the add button will not appear.**

### Equipment List — Print Scope

AC5 says the equipment list is "printable as plain text." This story delivers the equipment accordion in the sidebar, which shows a deduplicated sorted list. A print button or `window.print()` export is **out of scope for Story 4.4** — the text in the accordion is selectable and copy-pasteable, which satisfies the "plain text" intent for MVP. A dedicated print/export button can be added in a polish story.

### Flyway Migration Number

V43 is correct — V42 was the most recent migration (drill video upload config). Do not use V43 for anything else in this story.

### Session Plan Status Transitions

The `status` field follows this lifecycle:
- `DRAFT` — created/updated by coach; the default
- `SAVED` — coach explicitly marks ready (via `PUT` with `status: 'SAVED'`); this is the final pre-session state
- `COMPLETED` — set by the booking completion flow (Story 3.6) when a session finishes; NOT set by Session Builder

The coach should not be able to move a `COMPLETED` session back to `DRAFT`. Enforce in `updateSession()`: if `session.status == 'COMPLETED'` → throw `OperationNotAllowedException`.

### Previous Story Learnings to Apply

From Story 4.3 debug log:
1. **Constructor arg count**: Always read the actual record/class before building it inline in tests or services. Story 4.3 had a `DrillUploadServiceTest` failure because the constructor had changed. Same risk for `DrillResponse`.
2. **`@Modifying` + `clearAutomatically = true`**: Standard for any JPA query that modifies rows. `SessionRepository` does not need these (only simple JPA save), but if any bulk-update queries are added, apply this pattern.
3. **Mock adapter in IT**: `DrillLibraryService` calls `VideoProviderAdapter` via `batchVideoLookup`. If `SessionBuilderResourceIT` triggers `buildResponse()` which calls `drillRepository.findAllById()` and a `DrillResponse` includes `videoUrl`, the video adapter may be called. Use `@MockBean VideoProviderAdapter` in the IT test class to prevent real Bunny.net calls — same pattern as `DrillUploadResourceIT`.

## Project Structure Notes

| Component | Location |
|---|---|
| V43 migration | `src/main/resources/db/migration/V43__session_plans.sql` |
| BookingSnapshot (CREATE) | `src/main/java/com/softropic/skillars/platform/booking/contract/BookingSnapshot.java` |
| BookingQueryService (CREATE) | `src/main/java/com/softropic/skillars/platform/booking/service/BookingQueryService.java` |
| Session entity | `src/main/java/com/softropic/skillars/platform/session/repo/Session.java` |
| SessionRepository | `src/main/java/com/softropic/skillars/platform/session/repo/SessionRepository.java` |
| SessionDrillRef (value object) | `src/main/java/com/softropic/skillars/platform/session/contract/SessionDrillRef.java` |
| SessionBlockData (value object) | `src/main/java/com/softropic/skillars/platform/session/contract/SessionBlockData.java` |
| SessionDnaScore (value object) | `src/main/java/com/softropic/skillars/platform/session/contract/SessionDnaScore.java` |
| CreateSessionPlanRequest | `src/main/java/com/softropic/skillars/platform/session/contract/CreateSessionPlanRequest.java` |
| UpdateSessionPlanRequest | `src/main/java/com/softropic/skillars/platform/session/contract/UpdateSessionPlanRequest.java` |
| SessionDrillRefRequest | `src/main/java/com/softropic/skillars/platform/session/contract/SessionDrillRefRequest.java` |
| SessionBlockRequest | `src/main/java/com/softropic/skillars/platform/session/contract/SessionBlockRequest.java` |
| SessionBlockDrillResponse | `src/main/java/com/softropic/skillars/platform/session/contract/SessionBlockDrillResponse.java` |
| SessionBlockResponse | `src/main/java/com/softropic/skillars/platform/session/contract/SessionBlockResponse.java` |
| SessionPlanResponse | `src/main/java/com/softropic/skillars/platform/session/contract/SessionPlanResponse.java` |
| SessionErrorCode (UPDATE) | `src/main/java/com/softropic/skillars/platform/session/contract/SessionErrorCode.java` |
| SessionDnaCalculator | `src/main/java/com/softropic/skillars/platform/session/service/SessionDnaCalculator.java` |
| EquipmentListService | `src/main/java/com/softropic/skillars/platform/session/service/EquipmentListService.java` |
| SessionPlanService | `src/main/java/com/softropic/skillars/platform/session/service/SessionPlanService.java` |
| SessionBuilderResource (CREATE) | `src/main/java/com/softropic/skillars/platform/session/api/SessionBuilderResource.java` |
| SessionPlanResource (DELETE) | `src/main/java/com/softropic/skillars/platform/session/api/SessionPlanResource.java` |
| ApiAdvice (UPDATE — Task 19) | `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java` |
| DrillLibraryService (UPDATE — make toResponse package-private) | `src/main/java/com/softropic/skillars/platform/session/service/DrillLibraryService.java` |
| SessionDnaCalculatorTest | `src/test/java/com/softropic/skillars/platform/session/service/SessionDnaCalculatorTest.java` |
| EquipmentListServiceTest | `src/test/java/com/softropic/skillars/platform/session/service/EquipmentListServiceTest.java` |
| SessionBuilderResourceIT | `src/test/java/com/softropic/skillars/platform/session/api/SessionBuilderResourceIT.java` |
| sessionBuilder.store.js (CREATE) | `src/frontend/src/stores/sessionBuilder.store.js` |
| session.api.js (UPDATE) | `src/frontend/src/api/session.api.js` |
| SessionDNAChart.vue (UPDATE) | `src/frontend/src/components/booking/SessionDNAChart.vue` |
| WrapUpSequence.vue (UPDATE — wire SessionDNAChart full variant) | `src/frontend/src/components/booking/WrapUpSequence.vue` |
| DrillDetailPanel.vue (UPDATE — Task 22.5) | `src/frontend/src/components/session/DrillDetailPanel.vue` |
| SessionBlockView.vue (CREATE) | `src/frontend/src/components/session/SessionBlockView.vue` |
| DevelopmentFocusSelector.vue (CREATE) | `src/frontend/src/components/session/DevelopmentFocusSelector.vue` |
| SessionBuilderPage.vue (CREATE) | `src/frontend/src/pages/coach/SessionBuilderPage.vue` |
| routes.js (UPDATE) | `src/frontend/src/router/routes.js` |
| en/index.js (UPDATE) | `src/frontend/src/i18n/en/index.js` |
| de/index.js (UPDATE) | `src/frontend/src/i18n/de/index.js` |

## References

- Epic 4 overview + Story 4.4 AC + dev notes [`_bmad-output/planning-artifacts/skillars-epics.md` lines 1431–1614]
- Story 4.3 dev notes and review findings — DrillResponse constructor, VideoProviderAdapter mock pattern [`_bmad-output/implementation-artifacts/skillars-4-3-custom-drill-uploads.md`]
- Story 4.2 DrillCard.vue pattern — context prop, SLU estimate from metadata [`_bmad-output/implementation-artifacts/skillars-4-2-drill-card-operations.md`]
- `Drill.java` — JSONB pattern with `@JdbcTypeCode(SqlTypes.JSON)` [`src/main/java/com/softropic/skillars/platform/session/repo/Drill.java`]
- `DrillMetadata.java` — field names and types for DNA calc [`src/main/java/com/softropic/skillars/platform/session/contract/DrillMetadata.java`]
- `SessionDNAChart.vue` (current stub) [`src/frontend/src/components/booking/SessionDNAChart.vue`]
- `DrillLibraryService.checkSessionBuilderGate()` — gate pattern [`src/main/java/com/softropic/skillars/platform/session/service/DrillLibraryService.java:180-190`]
- `Booking.java` — bookingId UUID, playerId Long, coachId UUID [`src/main/java/com/softropic/skillars/platform/booking/repo/Booking.java`]
- `BaseSessionIT` — integration test base class [`src/test/java/com/softropic/skillars/platform/session/api/BaseSessionIT.java`]
- V38 migration — session schema, feature gate config pattern [`src/main/resources/db/migration/V38__session_module_init.sql`]
- V29 migration — booking schema for test data inserts [`src/main/resources/db/migration/V29__booking_module_init.sql`]
- Project context rules — DDD package structure, no entity exposure, @PreAuthorize required [`_bmad-output/project-context.md`]

## Dev Agent Record

### Agent Model Used
claude-sonnet-4-6

### Debug Log References
- `ErrorCode` is an interface not an enum — used `.getErrorCode()` not `.name()` in ApiAdvice Task 19
- `DrillLibraryService.checkSessionBuilderGate()` takes `coachUserId` (Long), not `coachId` (UUID)
- `BookingQueryService` created as cross-module facade to avoid DDD boundary violation
- `DrillLibraryService.toResponse()` made package-private so `SessionPlanService` can delegate
- IT test uses `@MockitoBean VideoProviderAdapter` to prevent real Bunny.net calls

### Completion Notes List
- All 31 tasks completed in single implementation run (Tasks 1–31)
- All backend classes, contracts, services and tests written
- Frontend: store, components (SessionBlockView, DevelopmentFocusSelector, SessionDNAChart), page, router, i18n all done
- SessionDNAChart replaced with full 5-axis SVG radar chart with compact/full variants
- WrapUpSequence wired with SessionDNAChart + show-confirmation
- DrillDetailPanel extended with add-to-session emit and context prop
- vuedraggable@next (v4.1.0) installed

### File List
- src/main/resources/db/migration/V43__session_plans.sql (CREATED)
- src/main/java/com/softropic/skillars/platform/session/contract/SessionErrorCode.java (MODIFIED)
- src/main/java/com/softropic/skillars/platform/session/repo/Session.java (CREATED)
- src/main/java/com/softropic/skillars/platform/session/repo/SessionRepository.java (CREATED)
- src/main/java/com/softropic/skillars/platform/session/contract/SessionDrillRef.java (CREATED)
- src/main/java/com/softropic/skillars/platform/session/contract/SessionBlockData.java (CREATED)
- src/main/java/com/softropic/skillars/platform/session/contract/SessionDnaScore.java (CREATED)
- src/main/java/com/softropic/skillars/platform/session/contract/SessionDrillRefRequest.java (CREATED)
- src/main/java/com/softropic/skillars/platform/session/contract/SessionBlockRequest.java (CREATED)
- src/main/java/com/softropic/skillars/platform/session/contract/CreateSessionPlanRequest.java (CREATED)
- src/main/java/com/softropic/skillars/platform/session/contract/UpdateSessionPlanRequest.java (CREATED)
- src/main/java/com/softropic/skillars/platform/session/contract/SessionBlockDrillResponse.java (CREATED)
- src/main/java/com/softropic/skillars/platform/session/contract/SessionBlockResponse.java (CREATED)
- src/main/java/com/softropic/skillars/platform/session/contract/SessionPlanResponse.java (CREATED)
- src/main/java/com/softropic/skillars/platform/booking/contract/BookingSnapshot.java (CREATED)
- src/main/java/com/softropic/skillars/platform/booking/service/BookingQueryService.java (CREATED)
- src/main/java/com/softropic/skillars/platform/session/service/SessionDnaCalculator.java (CREATED)
- src/main/java/com/softropic/skillars/platform/session/service/EquipmentListService.java (CREATED)
- src/main/java/com/softropic/skillars/platform/session/service/SessionPlanService.java (CREATED)
- src/main/java/com/softropic/skillars/platform/session/service/DrillLibraryService.java (MODIFIED — toResponse made package-private)
- src/main/java/com/softropic/skillars/platform/session/api/SessionBuilderResource.java (CREATED)
- src/main/java/com/softropic/skillars/platform/session/api/SessionPlanResource.java (DELETED)
- src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java (MODIFIED — operationDeniedHandler propagates error code)
- src/test/java/com/softropic/skillars/platform/session/service/SessionDnaCalculatorTest.java (CREATED)
- src/test/java/com/softropic/skillars/platform/session/service/EquipmentListServiceTest.java (CREATED)
- src/test/java/com/softropic/skillars/platform/session/api/SessionBuilderResourceIT.java (CREATED)
- src/frontend/src/api/session.api.js (MODIFIED)
- src/frontend/src/stores/sessionBuilder.store.js (CREATED)
- src/frontend/src/components/booking/SessionDNAChart.vue (REPLACED — full SVG radar)
- src/frontend/src/components/booking/WrapUpSequence.vue (MODIFIED — SessionDNAChart wired)
- src/frontend/src/components/session/DrillDetailPanel.vue (MODIFIED — add-to-session emit + context prop)
- src/frontend/src/components/session/SessionBlockView.vue (CREATED)
- src/frontend/src/components/session/DevelopmentFocusSelector.vue (CREATED)
- src/frontend/src/pages/coach/SessionBuilderPage.vue (CREATED)
- src/frontend/src/router/routes.js (MODIFIED)
- src/frontend/src/i18n/en/index.js (MODIFIED)
- src/frontend/src/i18n/de/index.js (MODIFIED)

## Change Log

| Date | Change | Author |
|---|---|---|
| 2026-06-18 | Story 4.4 created — Session Builder block structure, DNA scoring, drag-and-drop, equipment list | claude-sonnet-4-6 |
| 2026-06-18 | Senior dev audit: 18 issues fixed — DrillResponse constructor, OperationNotAllowedException signatures, fetchDrills rename, DrillDetailPanel add-to-session emit (Task 22.5), booking status validation, scout gate on mount, DrillCard context fix, ApiAdvice helpCode propagation (Task 19), BookingQueryService cross-module pattern (Task 19.5), SESSION_PLAN_LOCKED error code, WrapUpSequence DNA chart wiring, focus highlight scoped to 4.5, IT test SQL columns, schema name fix, getSession 404 guard, unsaved-changes guard, equipment print scoped out, 4-block cap documented | claude-sonnet-4-6 |
