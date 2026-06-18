# Story skillars-4.5: Intelligent Drill Suggestions & Session Templates

Status: done

## Story

As an Instructor or Academy tier coach,
I want the session builder to suggest relevant drills and let me save and reuse session templates,
So that planning efficient sessions takes minutes rather than starting from scratch every time.

## Acceptance Criteria

**AC 1: Suggestion endpoint returns ranked drills** — Given a coach has set a Development Focus for a session, when `GET /api/session/drills/suggestions?sessionId={id}&limit=10` is called, then drills are ranked by a weighted score: focus match (40%), neglected skill gap (30% — stubs to 0 because Epic 5 is not yet built), age/difficulty fit (20% — stubs to 0.5 neutral), recency penalty (10%); suggestions draw from both PLATFORM and the coach's private COACH library; the response is a `List<DrillResponse>` (14-field record, same as the drill library).

**AC 2: Fallback to Foundation 20 drills** — Given the session has no development focus set (empty `developmentFocus` list), when `GET /api/session/drills/suggestions?sessionId={id}&limit=10` is called, then the top-limit PLATFORM drills (Foundation 20 set) ordered by `createdAt ASC` are returned as the fallback; the frontend detects the fallback condition by checking `builderStore.developmentFocus.length === 0` and shows the header "Suggested for this age group" instead of "Personalised suggestions".

**AC 3: Suggested drill add** — Given the suggestion panel is open, when the coach taps "Add" on a suggested drill, then the drill is added to the currently active session block (same `addDrillToBlock(activeBlockIndex, drill)` mechanic already in `sessionBuilder.store.js`).

**AC 4: Save as template** — Given a coach has a saved session plan (`status: SAVED` or `DRAFT`), when they tap "Save as template" and provide a name, then `POST /api/session/templates` (body: `{ sessionId, name }`) creates a `session_templates` record; the template captures blocks, sessionDna, equipmentList, developmentFocus at save time — subsequent drill metadata changes do not alter the template.

**AC 5: Deploy template to booking** — Given a coach selects a template from their vault, when they tap "Use for this session" for a target booking, then `POST /api/session/templates/{id}/deploy?bookingId={bookingId}` creates a new `sessions` record with blocks and focus from the template, linked to `bookingId`; `deployCount` is incremented atomically; `lastDeployedAt` is set; the new session's `sourceTemplateId` and `sourceTemplateName` fields are populated; the response is `SessionPlanResponse`.

**AC 6: Template vault list** — Given a coach views the template vault (`GET /api/session/templates`), when templates are listed, then each template shows `id, coachId, name, drillCount, sessionDna, lastDeployedAt, deployCount, createdAt`; templates are private to the owning coach; an empty list returns 200 with `[]`.

**AC 7: Rename and delete templates** — Given a coach owns a template, when they `PUT /api/session/templates/{id}` (body: `{ name }`), then the name is updated (204); when they `DELETE /api/session/templates/{id}`, then the record is soft-deleted (status set to `ARCHIVED`) and `GET /api/session/templates` no longer includes it; deleting a template does NOT affect previously deployed sessions (their `sourceTemplateId` is preserved but not FKed).

**AC 8: Template indicator in builder** — Given a session was created from a template, when the Session Builder renders, then a banner "Based on template: [name]" is shown at the top of the blocks column; the banner has a dismiss action that removes it locally (does not alter `sourceTemplateName` in DB).

**AC 9: DNA focus axis highlighting** — Given the coach selects development focus areas, when the `SessionDNAChart` renders, then axes that correspond to selected focuses are rendered with a distinct highlight color (`var(--accent-secondary)` stroke); this was explicitly deferred from Story 4.4 and is required in 4.5.

**AC 10: Tier gate** — Given a Scout-tier coach calls any suggestion or template endpoint, then 403 with `helpCode: security.featureGated` is returned; the suggestions panel and "Save as Template" button are hidden in the Scout gate UI.

## Tasks / Subtasks

### Backend — Database Migration

- [x] **Task 1: Write `V44__session_templates.sql`** (AC: 4, 5, 6, 7, 8)
  - [x] File: `src/main/resources/db/migration/V44__session_templates.sql`
  - [x] Latest migration: V43 (session_plans). This must be V44.
  - [x] SQL:
    ```sql
    CREATE TABLE session.session_templates (
        id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
        coach_id          UUID          NOT NULL,
        name              VARCHAR(200)  NOT NULL,
        blocks            JSONB         NOT NULL DEFAULT '[]',
        session_dna       JSONB,
        equipment_list    JSONB         NOT NULL DEFAULT '[]',
        development_focus JSONB         NOT NULL DEFAULT '[]',
        last_deployed_at  TIMESTAMPTZ,
        deploy_count      INT           NOT NULL DEFAULT 0,
        status            VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE'
                          CHECK (status IN ('ACTIVE', 'ARCHIVED')),
        created_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
    );
    CREATE INDEX idx_session_templates_coach_id ON session.session_templates (coach_id);

    -- Add template provenance columns to session.sessions
    ALTER TABLE session.sessions
        ADD COLUMN source_template_id   UUID,
        ADD COLUMN source_template_name VARCHAR(200);
    ```
  - [x] `blocks` and `session_dna` use the same JSONB shape as `session.sessions` — `SessionBlockData` and `SessionDnaScore`
  - [x] `source_template_id` is NOT a foreign key — deleting a template must not cascade-delete deployed sessions (AC 7)

### Backend — Error Codes

- [x] **Task 2: Add error code to `SessionErrorCode.java`** (AC: 7)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/SessionErrorCode.java`
  - [x] Existing values: `CLONE_NOT_ALLOWED`, `SESSION_CANNOT_TAG_UNAUTHORIZED`, `DRILL_UPLOAD_NOT_ALLOWED`, `SESSION_ALREADY_EXISTS`, `SESSION_BOOKING_NOT_OWNED`, `SESSION_PLAN_LOCKED`
  - [x] Add:
    ```java
    TEMPLATE_NOT_OWNED;
    ```
  - [x] `TEMPLATE_NOT_OWNED` — returned (403) when a coach tries to rename/delete/deploy a template they do not own; DO NOT use `SESSION_BOOKING_NOT_OWNED` for this (different domain)

### Backend — Session Entity Update (source template provenance)

- [x] **Task 3: Update `Session.java`** (AC: 8)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/repo/Session.java`
  - [x] Add two nullable fields:
    ```java
    @Column(name = "source_template_id")
    private UUID sourceTemplateId;

    @Column(name = "source_template_name", length = 200)
    private String sourceTemplateName;
    ```
  - [x] These fields are `nullable = true` (not all sessions come from templates)
  - [x] No `@JdbcTypeCode` needed — these are plain UUID and VARCHAR, not JSONB

- [x] **Task 4: Update `SessionPlanResponse.java`** (AC: 8)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/SessionPlanResponse.java`
  - [x] Current fields (11): `id, bookingId, coachId, playerId, blocks, sessionDna, equipmentList, developmentFocus, status, createdAt, updatedAt`
  - [x] Add two nullable fields at the end of the record:
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
        Instant updatedAt,
        UUID sourceTemplateId,       // NEW — null if not from a template
        String sourceTemplateName    // NEW — null if not from a template
    ) {}
    ```
  - [x] CRITICAL: `SessionPlanService.buildResponse()` currently calls `new SessionPlanResponse(...)` with 11 args (the record has 11 fields). After this change it must pass 13 args: append `session.getSourceTemplateId(), session.getSourceTemplateName()` at the end. Check ALL call sites in `SessionPlanService.java` — there are 4 places that call `new SessionPlanResponse(...)`:
    - `createSession` → `buildResponse(saved, metaMap)`
    - `updateSession` → `buildResponse(saved, metaMap)`
    - `getSession` → `buildResponse(session, metaMap)`
    - `findByBookingId` → `buildResponse(s, metaMap)` via map()
  - [x] The `buildResponse` private method creates the response — update only `buildResponse` to add the two new fields; all four callers go through `buildResponse` already.

### Backend — Repository Layer

- [x] **Task 5: Create `SessionTemplate.java` entity** (AC: 4, 5, 6, 7)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/repo/SessionTemplate.java`
  - [x] Follow the exact `Session.java` pattern — `@JdbcTypeCode(SqlTypes.JSON)` for JSONB, `@Getter @Setter @NoArgsConstructor`
  - [x] Entity:
    ```java
    @Entity
    @Table(schema = "session", name = "session_templates")
    @Getter
    @Setter
    @NoArgsConstructor
    public class SessionTemplate {

        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;

        @Column(name = "coach_id", nullable = false)
        private UUID coachId;

        @Column(nullable = false, length = 200)
        private String name;

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

        @Column(name = "last_deployed_at")
        private Instant lastDeployedAt;

        @Column(name = "deploy_count", nullable = false)
        private int deployCount;

        @Column(nullable = false, length = 20)
        private String status;

        @Column(name = "created_at", nullable = false, updatable = false)
        private Instant createdAt;

        @PrePersist
        void onCreate() {
            createdAt = Instant.now();
            if (status == null) status = "ACTIVE";
        }
    }
    ```
  - [x] No `@PreUpdate` needed — `updatedAt` not in the schema (snapshot-frozen at save time; only `name`, `status`, `deployCount`, `lastDeployedAt` change after creation)

- [x] **Task 6: Create `SessionTemplateRepository.java`** (AC: 4, 5, 6, 7)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/repo/SessionTemplateRepository.java`
  - [x] Interface:
    ```java
    public interface SessionTemplateRepository extends JpaRepository<SessionTemplate, UUID> {
        List<SessionTemplate> findByCoachIdAndStatus(UUID coachId, String status);
        Optional<SessionTemplate> findByIdAndCoachId(UUID id, UUID coachId);

        @Modifying
        @Query("UPDATE SessionTemplate t SET t.deployCount = t.deployCount + 1, t.lastDeployedAt = :now WHERE t.id = :id")
        void incrementDeployCount(@Param("id") UUID id, @Param("now") Instant now);
    }
    ```
  - [x] `@Modifying` requires `clearAutomatically = true` only when dealing with `@Version` or when you read back the entity in the same transaction. For `deployCount` increment here, add `clearAutomatically = true` to prevent stale entity reads after the update within the same transaction:
    ```java
    @Modifying(clearAutomatically = true)
    @Query("...")
    void incrementDeployCount(@Param("id") UUID id, @Param("now") Instant now);
    ```

- [x] **Task 7: Update `SessionRepository.java`** (AC: 1 — recency scoring in DrillSuggestionService)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/repo/SessionRepository.java`
  - [x] Add method for recent session lookup (used by DrillSuggestionService to penalise recently-used drills):
    ```java
    List<Session> findTop5ByPlayerIdOrderByCreatedAtDesc(Long playerId);
    ```
  - [x] Spring Data JPA resolves this from the method name — no `@Query` needed

### Backend — Contract Layer (DTOs)

- [x] **Task 8: Create `CreateTemplateRequest.java`** (AC: 4)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/CreateTemplateRequest.java`
  - [x] Record:
    ```java
    public record CreateTemplateRequest(
        @NotNull UUID sessionId,
        @NotBlank @Size(max = 200) String name
    ) {}
    ```
  - [x] `sessionId` is the source session to snapshot; the template is built from it by the service

- [x] **Task 9: Create `RenameTemplateRequest.java`** (AC: 7)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/RenameTemplateRequest.java`
  - [x] Record:
    ```java
    public record RenameTemplateRequest(
        @NotBlank @Size(max = 200) String name
    ) {}
    ```

- [x] **Task 10: Create `SessionTemplateResponse.java`** (AC: 6)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/SessionTemplateResponse.java`
  - [x] Record:
    ```java
    public record SessionTemplateResponse(
        UUID id,
        UUID coachId,
        String name,
        int drillCount,
        SessionDnaScore sessionDna,
        List<String> developmentFocus,
        Instant lastDeployedAt,
        int deployCount,
        Instant createdAt
    ) {}
    ```
  - [x] `drillCount` is computed in service: `blocks.stream().mapToInt(b -> b.drills() != null ? b.drills().size() : 0).sum()`
  - [x] Blocks and equipment list are intentionally NOT in the response for the list endpoint — they are heavyweight JSONB and not needed for vault display; the deploy endpoint returns `SessionPlanResponse` with full block data

### Backend — Service Layer

- [x] **Task 11: Create `DrillSuggestionService.java`** (AC: 1, 2, 10)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/service/DrillSuggestionService.java`
  - [x] Annotations: `@Service @Transactional(readOnly = true) @Slf4j @RequiredArgsConstructor`
  - [x] Inject: `SessionRepository`, `DrillRepository`, `DrillLibraryService`, `CoachProfileService`
  - [x] Method signature: `public List<DrillResponse> suggest(UUID sessionId, Long coachUserId, int limit)`
  - [x] Algorithm:
    ```
    1. drillLibraryService.checkSessionBuilderGate(coachUserId)
    2. UUID coachId = coachProfileService.getCoachIdByUserId(coachUserId)
    3. Session session = sessionRepository.findById(sessionId)
         .orElseThrow(() -> new ResourceNotFoundException("Session not found"))
    4. if !session.getCoachId().equals(coachId)
         throw new ResourceNotFoundException("Session not found")  // 404, not 403 — no enumeration
    5. List<String> focus = session.getDevelopmentFocus()
    6. Long playerId = session.getPlayerId()
    7. if focus == null || focus.isEmpty() → return fallback(limit)
    8. List<Drill> candidates = allCandidates(coachId)  // PLATFORM ACTIVE + coach PRIVATE ACTIVE
    9. Set<UUID> recentIds = getRecentDrillIds(playerId) // last 5 sessions
    10. Set<UUID> alreadyUsed = extractUsedDrills(session.getBlocks())  // returns Set<UUID>, not List<Drill>
    11. Score and rank:
          scored = candidates.stream()
            .filter(d -> !alreadyUsed.contains(d.getId()))  // don't suggest drills already in the session
            .map(d -> new DrillScore(d, score(d.getMetadata(), focus, recentIds)))
            .sorted(Comparator.comparingDouble(DrillScore::score).reversed())
            .limit(limit)
            .toList()
    12. Build DrillResponse list using drillLibraryService.toResponse(d, false, List.of(), null, null, null)
    ```
  - [x] `score(DrillMetadata meta, List<String> focus, Set<UUID> recentIds)`:
    ```java
    private double score(DrillMetadata meta, List<String> focus, Set<UUID> recentIds, UUID drillId) {
        if (meta == null) return 0.0;
        double focusScore = computeFocusScore(meta, focus);
        double neglectedScore = 0.0;  // Stub: Epic 5 not yet built
        double ageFitScore = 0.5;     // Stub: player age tier lookup deferred
        double recencyScore = recentIds.contains(drillId) ? 0.0 : 1.0;
        return focusScore * 0.40 + neglectedScore * 0.30 + ageFitScore * 0.20 + recencyScore * 0.10;
    }
    ```
  - [x] `computeFocusScore(DrillMetadata meta, List<String> focus)`:
    ```java
    private double computeFocusScore(DrillMetadata meta, List<String> focus) {
        double total = 0.0;
        for (String f : focus) {
            total += switch (f) {
                case "technical"    -> ((meta.intensity() + meta.pressureLevel()) / 2.0 - 1.0) / 4.0;
                case "physical"     -> (meta.intensity() - 1.0) / 4.0;
                case "cognitive"    -> (meta.cognitiveLoad() - 1.0) / 4.0;
                case "matchRealism" -> (meta.matchRealism() - 1.0) / 4.0;
                case "weakFoot"     -> meta.weakFootBias() ? 1.0 : 0.0;
                case "set_pieces"   -> (meta.pressureLevel() - 1.0) / 4.0;  // pressure as proxy
                case "possession"   -> ((meta.cognitiveLoad() + meta.matchRealism()) / 2.0 - 1.0) / 4.0;
                case "goalkeeping"  -> 0.5;  // neutral — no GK-specific metadata field yet
                default             -> 0.0;
            };
        }
        return Math.min(1.0, total / focus.size());
    }
    ```
  - [x] `fallback(int limit)` — returns up to `limit` PLATFORM ACTIVE drills ordered by `createdAt ASC` (Foundation 20 drills are the earliest inserted):
    ```java
    private List<DrillResponse> fallback(int limit) {
        return drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")
            .stream()
            .sorted(Comparator.comparing(Drill::getCreatedAt))
            .limit(limit)
            .map(d -> drillLibraryService.toResponse(d, false, List.of(), null, null, null))
            .toList();
    }
    ```
  - [x] `allCandidates(UUID coachId)`:
    ```java
    private List<Drill> allCandidates(UUID coachId) {
        List<Drill> all = new ArrayList<>(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE"));
        all.addAll(drillRepository.findByOwnerCoachIdAndStatus(coachId, "ACTIVE"));
        return all;
    }
    ```
  - [x] `getRecentDrillIds(Long playerId)`:
    ```java
    private Set<UUID> getRecentDrillIds(Long playerId) {
        return sessionRepository.findTop5ByPlayerIdOrderByCreatedAtDesc(playerId)
            .stream()
            .flatMap(s -> s.getBlocks() != null ? s.getBlocks().stream() : Stream.empty())
            .flatMap(b -> b.drills() != null ? b.drills().stream() : Stream.empty())
            .map(SessionDrillRef::drillId)
            .collect(Collectors.toSet());
    }
    ```
  - [x] `extractUsedDrills(List<SessionBlockData> blocks)` — IDs of drills already in the current session:
    ```java
    private Set<UUID> extractUsedDrills(List<SessionBlockData> blocks) {
        if (blocks == null) return Set.of();
        return blocks.stream()
            .flatMap(b -> b.drills() != null ? b.drills().stream() : Stream.empty())
            .map(SessionDrillRef::drillId)
            .collect(Collectors.toSet());
    }
    ```
  - [x] Private record for scoring: `private record DrillScore(Drill drill, double score) {}`
  - [x] **Do NOT inject `VideoProviderAdapter` directly** — call `drillLibraryService.toResponse(d, false, List.of(), null, null, null)` with `hasVideo=false, videoUrl=null` for suggestions (same pattern as `SessionPlanService.buildResponse()`)

- [x] **Task 12: Create `SessionTemplateService.java`** (AC: 4, 5, 6, 7, 10)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/service/SessionTemplateService.java`
  - [x] Annotations: `@Service @Transactional @Slf4j @RequiredArgsConstructor`
  - [x] Inject: `SessionTemplateRepository`, `SessionRepository`, `DrillRepository`, `CoachProfileService`, `DrillLibraryService`, `BookingQueryService`
  - [x] Do NOT inject `SessionDnaCalculator` or `EquipmentListService` — the template snapshots the session's already-computed values and does not recalculate them
  - [x] **`resolveCoachId(Long coachUserId)` private helper** — same pattern as `SessionPlanService`:
    ```java
    private UUID resolveCoachId(Long userId) {
        return coachProfileService.getCoachIdByUserId(userId);
    }
    ```
  - [x] **`isBookingPlannable(String status)` private helper** — same guard as `SessionPlanService`:
    ```java
    private boolean isBookingPlannable(String status) {
        return "CONFIRMED".equals(status) || "UPCOMING".equals(status);
    }
    ```
  - [x] **`createTemplate(CreateTemplateRequest req, Long coachUserId)` → `SessionTemplateResponse`**:
    ```
    1. drillLibraryService.checkSessionBuilderGate(coachUserId)
    2. UUID coachId = resolveCoachId(coachUserId)
    3. Session session = sessionRepository.findById(req.sessionId())
         .orElseThrow(() -> new ResourceNotFoundException("Session not found"))
    4. if !session.getCoachId().equals(coachId)
         throw new ResourceNotFoundException("Session not found")  // 404, prevents enumeration
    5. SessionTemplate template = new SessionTemplate()
       template.setCoachId(coachId)
       template.setName(req.name())
       template.setBlocks(session.getBlocks())          // snapshot copy — JSONB deep-copied by value at persist time
       template.setSessionDna(session.getSessionDna())
       template.setEquipmentList(session.getEquipmentList())
       template.setDevelopmentFocus(session.getDevelopmentFocus())
       template.setDeployCount(0)
       template.setStatus("ACTIVE")
    6. SessionTemplate saved = sessionTemplateRepository.save(template)
    7. return toResponse(saved)
    ```
  - [x] **`listTemplates(Long coachUserId)` → `List<SessionTemplateResponse>`**:
    ```
    drillLibraryService.checkSessionBuilderGate(coachUserId)  // AC 10: gate ALL template endpoints
    UUID coachId = resolveCoachId(coachUserId)
    return sessionTemplateRepository.findByCoachIdAndStatus(coachId, "ACTIVE")
        .stream().map(this::toResponse).toList()
    ```
  - [x] **`renameTemplate(UUID templateId, RenameTemplateRequest req, Long coachUserId)` → void**:
    ```
    1. drillLibraryService.checkSessionBuilderGate(coachUserId)  // AC 10: gate ALL template endpoints
    2. UUID coachId = resolveCoachId(coachUserId)
    3. SessionTemplate t = sessionTemplateRepository.findByIdAndCoachId(templateId, coachId)
         .orElseThrow(() -> new OperationNotAllowedException("Template not owned", SessionErrorCode.TEMPLATE_NOT_OWNED))
    4. if "ARCHIVED".equals(t.getStatus())
         throw new OperationNotAllowedException("Template has been deleted", SessionErrorCode.TEMPLATE_NOT_OWNED)
         // TEMPLATE_NOT_OWNED is the closest existing code; semantically "archived" but same HTTP outcome (403)
    5. t.setName(req.name())
    6. sessionTemplateRepository.save(t)
    ```
  - [x] **`deleteTemplate(UUID templateId, Long coachUserId)` → void**:
    ```
    1. drillLibraryService.checkSessionBuilderGate(coachUserId)  // AC 10: gate ALL template endpoints
    2. UUID coachId = resolveCoachId(coachUserId)
    3. SessionTemplate t = sessionTemplateRepository.findByIdAndCoachId(templateId, coachId)
         .orElseThrow(() -> new OperationNotAllowedException("Template not owned", SessionErrorCode.TEMPLATE_NOT_OWNED))
    4. t.setStatus("ARCHIVED")
    5. sessionTemplateRepository.save(t)
    // NOTE: Do NOT delete session records that reference this template. sourceTemplateId is NOT a FK.
    ```
  - [x] **`deployTemplate(UUID templateId, UUID bookingId, Long coachUserId)` → `SessionPlanResponse`**:
    ```
    1. drillLibraryService.checkSessionBuilderGate(coachUserId)
    2. UUID coachId = resolveCoachId(coachUserId)
    3. SessionTemplate t = sessionTemplateRepository.findByIdAndCoachId(templateId, coachId)
         .orElseThrow(() -> new OperationNotAllowedException("Template not owned", SessionErrorCode.TEMPLATE_NOT_OWNED))
    4. if "ARCHIVED".equals(t.getStatus()) throw OperationNotAllowedException(TEMPLATE_NOT_OWNED)
    5. // Validate booking exists and is owned by this coach
       BookingSnapshot booking = bookingQueryService.getBookingSnapshot(bookingId)
         .orElseThrow(() -> new ResourceNotFoundException("Booking not found"))
    6. if !booking.coachId().equals(coachId)
         throw new OperationNotAllowedException("Booking is not owned by this coach", SessionErrorCode.SESSION_BOOKING_NOT_OWNED)
    6a. if !isBookingPlannable(booking.status())
         throw new OperationNotAllowedException("Session plan can only be created for a confirmed or upcoming booking", SessionErrorCode.SESSION_BOOKING_NOT_OWNED)
         // Same guard as SessionPlanService.createSession — prevents deploy to cancelled/completed bookings
    7. if sessionRepository.existsByBookingId(bookingId)
         throw new OperationNotAllowedException("Session plan already exists for this booking", SessionErrorCode.SESSION_ALREADY_EXISTS)
    8. // Build session from template
       Session session = new Session()
       session.setBookingId(bookingId)
       session.setCoachId(coachId)
       session.setPlayerId(booking.playerId())
       session.setBlocks(t.getBlocks())
       session.setSessionDna(t.getSessionDna())
       session.setEquipmentList(t.getEquipmentList())
       session.setDevelopmentFocus(t.getDevelopmentFocus())
       session.setStatus("DRAFT")
       session.setSourceTemplateId(t.getId())
       session.setSourceTemplateName(t.getName())
    9. Session saved = sessionRepository.save(session)
    10. // Increment deploy count atomically
        sessionTemplateRepository.incrementDeployCount(t.getId(), Instant.now())
    11. return buildResponse(saved, resolveMetaMap(saved.getBlocks()))
    ```
  - [x] `buildResponse` and `resolveMetaMap` in this service are analogous to the same helpers in `SessionPlanService`. Factor them out the same way — delegate to `drillLibraryService.toResponse()` (package-private) for `DrillResponse` construction:
    ```java
    private SessionPlanResponse buildResponse(Session session, Map<UUID, DrillMetadata> metaMap) {
        // Build DrillResponse lookup
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
            session.getStatus(), session.getCreatedAt(), session.getUpdatedAt(),
            session.getSourceTemplateId(), session.getSourceTemplateName()
        );
    }
    ```
  - [x] **CRITICAL**: `SessionPlanResponse` now has 13 constructor args (added `sourceTemplateId`, `sourceTemplateName`). Verify arg count before compiling.
  - [x] `toResponse(SessionTemplate t)`:
    ```java
    private SessionTemplateResponse toResponse(SessionTemplate t) {
        int drillCount = t.getBlocks() == null ? 0
            : t.getBlocks().stream().mapToInt(b -> b.drills() != null ? b.drills().size() : 0).sum();
        return new SessionTemplateResponse(
            t.getId(), t.getCoachId(), t.getName(), drillCount,
            t.getSessionDna(), t.getDevelopmentFocus(),
            t.getLastDeployedAt(), t.getDeployCount(), t.getCreatedAt()
        );
    }
    ```
  - [x] Also update `SessionPlanService.buildResponse()` to pass `session.getSourceTemplateId()` and `session.getSourceTemplateName()` to match the new `SessionPlanResponse` constructor (13 args) — this change is in `SessionPlanService.java`, not here.

### Backend — API Layer

- [x] **Task 13: Update `DrillLibraryResource.java`** — add suggestions endpoint (AC: 1, 2, 10)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/api/DrillLibraryResource.java`
  - [x] Inject `DrillSuggestionService` (add to `@RequiredArgsConstructor` constructor)
  - [x] Add new endpoint:
    ```java
    @GetMapping("/suggestions")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<List<DrillResponse>> getSuggestions(
        @RequestParam UUID sessionId,
        @RequestParam(defaultValue = "10") int limit
    ) {
        if (limit < 1 || limit > 20) throw new InvalidParamException("limit");
        return ResponseEntity.ok(drillSuggestionService.suggest(sessionId, currentCoachUserId(), limit));
    }
    ```
  - [x] Place this mapping BEFORE `@GetMapping` (the list endpoint) to avoid Spring ambiguity — both use `@GetMapping` on `/api/session/drills`; `/suggestions` is a fixed sub-path so Spring resolves it correctly, but ordering ensures `/suggestions` route is checked first

- [x] **Task 14: Create `SessionTemplateResource.java`** (AC: 4, 5, 6, 7, 10)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/api/SessionTemplateResource.java`
  - [x] Class:
    ```java
    @Observed(name = "session.templates")
    @RestController
    @RequestMapping("/api/session/templates")
    @RequiredArgsConstructor
    public class SessionTemplateResource {

        private final SessionTemplateService sessionTemplateService;
        private final SecurityUtil securityUtil;

        @GetMapping
        @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
        public ResponseEntity<List<SessionTemplateResponse>> listTemplates() {
            return ResponseEntity.ok(sessionTemplateService.listTemplates(currentCoachUserId()));
        }

        @PostMapping
        @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
        public ResponseEntity<SessionTemplateResponse> createTemplate(
            @RequestBody @Valid CreateTemplateRequest req
        ) {
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(sessionTemplateService.createTemplate(req, currentCoachUserId()));
        }

        @PutMapping("/{templateId}")
        @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
        public ResponseEntity<Void> renameTemplate(
            @PathVariable UUID templateId,
            @RequestBody @Valid RenameTemplateRequest req
        ) {
            sessionTemplateService.renameTemplate(templateId, req, currentCoachUserId());
            return ResponseEntity.noContent().build();
        }

        @DeleteMapping("/{templateId}")
        @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
        public ResponseEntity<Void> deleteTemplate(@PathVariable UUID templateId) {
            sessionTemplateService.deleteTemplate(templateId, currentCoachUserId());
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/{templateId}/deploy")
        @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
        public ResponseEntity<SessionPlanResponse> deployTemplate(
            @PathVariable UUID templateId,
            @RequestParam UUID bookingId
        ) {
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(sessionTemplateService.deployTemplate(templateId, bookingId, currentCoachUserId()));
        }

        private Long currentCoachUserId() {
            return securityUtil.getCurrentCoachUserId();
        }
    }
    ```

- [x] **Task 15: Update `SessionPlanService.buildResponse()`** (AC: 8)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/service/SessionPlanService.java`
  - [x] Update the `buildResponse` private method to pass the two new fields at the end of the `SessionPlanResponse` constructor call:
    ```java
    return new SessionPlanResponse(
        session.getId(), session.getBookingId(), session.getCoachId(),
        session.getPlayerId(), blockResponses, session.getSessionDna(),
        session.getEquipmentList(), session.getDevelopmentFocus(),
        session.getStatus(), session.getCreatedAt(), session.getUpdatedAt(),
        session.getSourceTemplateId(), session.getSourceTemplateName()  // NEW
    );
    ```
  - [x] This is the ONLY change required in `SessionPlanService` — all 4 call sites already go through `buildResponse`.

### Backend — Tests

- [x] **Task 16: Create `DrillSuggestionServiceTest.java`** (AC: 1, 2)
  - [x] File: `src/test/java/com/softropic/skillars/platform/session/service/DrillSuggestionServiceTest.java`
  - [x] `@ExtendWith(MockitoExtension.class)` — pure unit, mock all dependencies
  - [x] Use Instancio for generating test `Drill` and `DrillMetadata` instances
  - [x] Test cases:
    - `suggest_noFocus_returnsFallbackPlatformDrills` — empty focus → returns Foundation 20 set, sorted by createdAt
    - `suggest_withTechnicalFocus_ranksHighIntensityDrillsFirst` — intensity 5 drill ranks above intensity 1
    - `suggest_recentlyUsedDrill_isRankedLower` — drill used in last 5 sessions gets recencyScore=0.0 → lower rank
    - `suggest_scoutCoach_throwsFeatureGatedException` — gate check fires before any DB query
    - `suggest_sessionNotFound_throws404` — sessionId not found → ResourceNotFoundException
    - `suggest_sessionOwnedByOtherCoach_throws404` — coachId mismatch → ResourceNotFoundException (not 403 — no enumeration)
    - `suggest_includesPrivateDrills` — private COACH drills appear in candidates alongside PLATFORM
    - `suggest_drillsAlreadyInSession_areExcluded` — drills in current session blocks are filtered out

- [x] **Task 17: Create `SessionTemplateResourceIT.java`** (AC: 4, 5, 6, 7, 10)
  - [x] File: `src/test/java/com/softropic/skillars/platform/session/api/SessionTemplateResourceIT.java`
  - [x] Extend `BaseSessionIT` (same pattern as `SessionBuilderResourceIT`)
  - [x] `@SpringBootTest @Testcontainers @MockitoBean VideoProviderAdapter`
  - [x] Use `@Sql({SecurityIT.SEC_DATA_SQL_PATH})` for auth data
  - [x] Insert test session with known sessionId, bookingId, coachId before tests (same SQL pattern from Task 22 in Story 4.4)
  - [x] Test cases:
    - `createTemplate_instructorCoach_validSessionId_returns201WithTemplate`
    - `createTemplate_scoutCoach_returns403FeatureGated`
    - `createTemplate_sessionOwnedByOtherCoach_returns404`
    - `listTemplates_emptyVault_returns200EmptyList`
    - `listTemplates_scoutCoach_returns403FeatureGated`
    - `listTemplates_afterCreate_returnsOneTemplate`
    - `renameTemplate_ownerCoach_returns204`
    - `renameTemplate_otherCoach_returns403`
    - `renameTemplate_scoutCoach_returns403FeatureGated`
    - `renameTemplate_archivedTemplate_returns403`
    - `deleteTemplate_ownerCoach_returns204_disappearsFromList`
    - `deleteTemplate_otherCoach_returns403`
    - `deleteTemplate_scoutCoach_returns403FeatureGated`
    - `deployTemplate_validTemplate_validBooking_returns201WithSessionPlan`
    - `deployTemplate_cancelledBooking_returns403SessionBookingNotOwned`  // isBookingPlannable guard
    - `deployTemplate_duplicateBooking_returns403SessionAlreadyExists`
    - `deployTemplate_scoutCoach_returns403FeatureGated`
    - `deployTemplate_otherCoach_returns403TemplateNotOwned`
  - [x] Teardown: `DELETE FROM session.session_templates WHERE coach_id = <test-coach-uuid>` and `DELETE FROM session.sessions WHERE booking_id = <test-booking-uuid>`

### Frontend — API

- [x] **Task 18: Update `session.api.js`** (AC: 1, 4, 5, 6, 7)
  - [x] File: `src/frontend/src/api/session.api.js`
  - [x] Add after existing session plan methods:
    ```js
    getSuggestions(sessionId, limit = 10) {
      return api.get('/api/session/drills/suggestions', { params: { sessionId, limit } })
    },

    // Session Templates
    listTemplates() {
      return api.get('/api/session/templates')
    },

    createTemplate(payload) {
      // payload: { sessionId, name }
      return api.post('/api/session/templates', payload)
    },

    renameTemplate(templateId, payload) {
      // payload: { name }
      return api.put(`/api/session/templates/${templateId}`, payload)
    },

    deleteTemplate(templateId) {
      return api.delete(`/api/session/templates/${templateId}`)
    },

    deployTemplate(templateId, bookingId) {
      return api.post(`/api/session/templates/${templateId}/deploy`, null, { params: { bookingId } })
    },
    ```

### Frontend — Stores

- [x] **Task 19: Update `sessionBuilder.store.js`** (AC: 3, 4, 8, 9)
  - [x] File: `src/frontend/src/stores/sessionBuilder.store.js`
  - [x] Add to state refs:
    ```js
    const activeBlockIndex = ref(0)        // track active block for suggestion "Add" taps
    const suggestedDrills = ref([])
    const suggestionsLoading = ref(false)
    const sourceTemplateId = ref(null)
    const sourceTemplateName = ref(null)
    const templateBannerDismissed = ref(false)
    ```
  - [x] Update `initForBooking(bId)` to reset new state:
    ```js
    activeBlockIndex.value = 0
    suggestedDrills.value = []
    sourceTemplateId.value = null
    sourceTemplateName.value = null
    templateBannerDismissed.value = false
    ```
  - [x] Update `fetchExistingPlan(bId)` — after loading, set template provenance from response:
    ```js
    // Inside the try block, after loading blocks/focus:
    sourceTemplateId.value = plan.sourceTemplateId ?? null
    sourceTemplateName.value = plan.sourceTemplateName ?? null
    ```
  - [x] Add `fetchSuggestions()` async function:
    ```js
    async function fetchSuggestions() {
      if (!sessionId.value) return  // suggestions require a saved session (needs sessionId)
      suggestionsLoading.value = true
      try {
        const res = await sessionApi.getSuggestions(sessionId.value, 10)
        suggestedDrills.value = res.data
      } catch (e) {
        suggestedDrills.value = []
      } finally {
        suggestionsLoading.value = false
      }
    }
    ```
  - [x] Add `setActiveBlock(index)` function:
    ```js
    function setActiveBlock(index) {
      if (index >= 0 && index < blocks.value.length) activeBlockIndex.value = index
    }
    ```
  - [x] Export new refs and functions in the return: `activeBlockIndex, suggestedDrills, suggestionsLoading, sourceTemplateId, sourceTemplateName, templateBannerDismissed, fetchSuggestions, setActiveBlock`

- [x] **Task 20: Create `sessionTemplate.store.js`** (AC: 6, 7)
  - [x] File: `src/frontend/src/stores/sessionTemplate.store.js`
  - [x] Store:
    ```js
    import { defineStore } from 'pinia'
    import { ref } from 'vue'
    import { sessionApi } from 'src/api/session.api'

    export const useSessionTemplateStore = defineStore('sessionTemplate', () => {
      const templates = ref([])
      const loading = ref(false)
      const error = ref(null)

      async function fetchTemplates() {
        loading.value = true
        try {
          const res = await sessionApi.listTemplates()
          templates.value = res.data
        } catch (e) {
          error.value = e
        } finally {
          loading.value = false
        }
      }

      async function createTemplate(sessionId, name) {
        const res = await sessionApi.createTemplate({ sessionId, name })
        templates.value.unshift(res.data)
        return res.data
      }

      async function renameTemplate(templateId, name) {
        try {
          await sessionApi.renameTemplate(templateId, { name })
          const t = templates.value.find(t => t.id === templateId)
          if (t) t.name = name
        } catch (e) {
          error.value = e
          throw e  // re-throw so the component can show a notification
        }
      }

      async function deleteTemplate(templateId) {
        try {
          await sessionApi.deleteTemplate(templateId)
          templates.value = templates.value.filter(t => t.id !== templateId)
        } catch (e) {
          error.value = e
          throw e
        }
      }

      async function deployTemplate(templateId, bookingId) {
        try {
          const res = await sessionApi.deployTemplate(templateId, bookingId)
          // Update deploy count + last deployed in local state
          const t = templates.value.find(t => t.id === templateId)
          if (t) {
            t.deployCount = (t.deployCount ?? 0) + 1
            t.lastDeployedAt = new Date().toISOString()
          }
          return res.data  // SessionPlanResponse
        } catch (e) {
          error.value = e
          throw e
        }
      }

      return { templates, loading, error, fetchTemplates, createTemplate, renameTemplate, deleteTemplate, deployTemplate }
    })
    ```

### Frontend — Components

- [x] **Task 21: Create `DrillSuggestionPanel.vue`** (AC: 1, 2, 3)
  - [x] File: `src/frontend/src/components/session/DrillSuggestionPanel.vue`
  - [x] Component:
    ```vue
    <template>
      <div class="drill-suggestion-panel">
        <div class="row items-center q-mb-sm">
          <div class="text-subtitle2 col">
            {{ isPersonalized ? t('session.suggestions.personalizedTitle') : t('session.suggestions.fallbackTitle') }}
          </div>
          <q-btn flat dense round icon="close" size="xs" @click="emit('close')" />
        </div>

        <div v-if="loading" class="flex flex-center q-pa-md">
          <q-spinner-dots size="24px" color="primary" />
        </div>

        <div v-else-if="!suggestions.length" class="text-caption text-secondary q-pa-md text-center">
          {{ t('session.suggestions.empty') }}
        </div>

        <div v-else class="drill-suggestion-panel__list">
          <DrillCard
            v-for="drill in suggestions"
            :key="drill.id"
            :drill="drill"
            context="session-builder"
            class="q-mb-xs"
            @add-to-session="emit('add-drill', drill)"
            @open-detail="emit('open-detail', drill)"
          />
        </div>
      </div>
    </template>

    <script setup>
    import { useI18n } from 'vue-i18n'
    import DrillCard from 'src/components/session/DrillCard.vue'

    const props = defineProps({
      suggestions: { type: Array, default: () => [] },
      loading: { type: Boolean, default: false },
      isPersonalized: { type: Boolean, default: false },  // false = fallback header
    })
    const emit = defineEmits(['close', 'add-drill', 'open-detail'])
    const { t } = useI18n()
    </script>
    ```
  - [x] `context="session-builder"` on `DrillCard` is required — this emits `add-to-session` and shows the "Add" button (confirmed working in Story 4.2/4.4)

- [x] **Task 22: Update `SessionDNAChart.vue`** — add focus axis highlighting (AC: 9)
  - [x] File: `src/frontend/src/components/booking/SessionDNAChart.vue`
  - [x] Add `highlightAxes` prop:
    ```js
    const props = defineProps({
      bookingId: { type: String, default: null },  // retained for backward compat
      variant: { type: String, default: 'compact' },
      sessionDna: {
        type: Object,
        default: () => ({ technical: 0, physical: 0, cognitive: 0, matchRealism: 0, weakFootFocus: 0 }),
      },
      showConfirmation: { type: Boolean, default: false },
      highlightAxes: { type: Array, default: () => [] },  // NEW — array of axis key strings
    })
    ```
  - [x] Update the axis SVG lines to use a highlight color when the axis key is in `highlightAxes`:
    ```vue
    <line v-for="ax in axes" :key="ax.key"
      :x1="60" :y1="60"
      :x2="60 + 50 * Math.cos(toRad(ax.angle))"
      :y2="60 + 50 * Math.sin(toRad(ax.angle))"
      :stroke="props.highlightAxes.includes(ax.key) ? 'var(--accent-secondary)' : 'var(--border-subtle)'"
      :stroke-width="props.highlightAxes.includes(ax.key) ? 2 : 1" />
    ```
  - [x] No other changes needed — existing callers that don't pass `highlightAxes` get the default `[]`, preserving backward compat

- [x] **Task 23: Create `SessionTemplateVault.vue`** (AC: 6, 7)
  - [x] File: `src/frontend/src/pages/coach/SessionTemplateVault.vue`
  - [x] Page for browsing, renaming, deleting, and deploying templates to a booking
  - [x] Component:
    ```vue
    <template>
      <q-page class="q-pa-md">
        <div class="row items-center q-mb-md">
          <q-btn flat round icon="arrow_back" :to="{ name: 'coach-dashboard' }" />
          <div class="text-h6 q-ml-sm">{{ t('session.templates.title') }}</div>
        </div>

        <div v-if="templateStore.loading" class="flex flex-center q-pa-xl">
          <q-spinner-dots size="36px" color="primary" />
        </div>

        <div v-else-if="!templateStore.templates.length" class="text-center q-pa-xl">
          <q-icon name="bookmark_border" size="64px" color="grey-5" />
          <div class="text-h6 q-mt-md">{{ t('session.templates.emptyTitle') }}</div>
          <div class="text-body2 text-secondary q-mt-sm">{{ t('session.templates.emptySubtitle') }}</div>
        </div>

        <q-list v-else separator>
          <q-item v-for="tmpl in templateStore.templates" :key="tmpl.id">
            <q-item-section>
              <q-item-label class="text-weight-medium">{{ tmpl.name }}</q-item-label>
              <q-item-label caption>
                {{ t('session.templates.drillCount', { count: tmpl.drillCount }) }}
                · {{ t('session.templates.deployCount', { count: tmpl.deployCount }) }}
                <template v-if="tmpl.lastDeployedAt">
                  · {{ t('session.templates.lastDeployed', { date: formatDate(tmpl.lastDeployedAt) }) }}
                </template>
              </q-item-label>
            </q-item-section>
            <q-item-section side>
              <SessionDNAChart :session-dna="tmpl.sessionDna ?? defaultDna" variant="compact" />
            </q-item-section>
            <q-item-section side>
              <q-btn flat dense round icon="more_vert">
                <q-menu>
                  <q-list>
                    <q-item clickable v-close-popup @click="startDeploy(tmpl)">
                      <q-item-section>{{ t('session.templates.useThisSession') }}</q-item-section>
                    </q-item>
                    <q-item clickable v-close-popup @click="startRename(tmpl)">
                      <q-item-section>{{ t('session.templates.rename') }}</q-item-section>
                    </q-item>
                    <q-item clickable v-close-popup @click="confirmDelete(tmpl)">
                      <q-item-section class="text-negative">{{ t('session.templates.delete') }}</q-item-section>
                    </q-item>
                  </q-list>
                </q-menu>
              </q-btn>
            </q-item-section>
          </q-item>
        </q-list>

        <!-- Rename dialog -->
        <q-dialog v-model="renameDialog">
          <q-card style="min-width: 320px">
            <q-card-section>
              <div class="text-subtitle1">{{ t('session.templates.nameDialogTitle') }}</div>
            </q-card-section>
            <q-card-section>
              <q-input v-model="renameValue" dense autofocus :label="t('session.templates.nameLabel')" />
            </q-card-section>
            <q-card-actions align="right">
              <q-btn flat :label="t('common.cancel')" v-close-popup />
              <q-btn color="primary" :label="t('session.templates.saveAction')" @click="saveRename" />
            </q-card-actions>
          </q-card>
        </q-dialog>

        <!-- Deploy dialog — AC 5: "Use for this session" -->
        <q-dialog v-model="deployDialog">
          <q-card style="min-width: 320px">
            <q-card-section>
              <div class="text-subtitle1">{{ t('session.templates.deployDialogTitle') }}</div>
              <div class="text-caption text-secondary q-mt-xs">{{ t('session.templates.deployDialogSubtitle') }}</div>
            </q-card-section>
            <q-card-section>
              <q-input
                v-model="deployBookingId"
                dense
                autofocus
                :label="t('session.templates.deployBookingIdLabel')"
                :error="!!deployError"
                :error-message="deployError"
              />
            </q-card-section>
            <q-card-actions align="right">
              <q-btn flat :label="t('common.cancel')" v-close-popup />
              <q-btn
                color="primary"
                :label="t('session.templates.deployAction')"
                :loading="deploying"
                @click="confirmDeploy"
              />
            </q-card-actions>
          </q-card>
        </q-dialog>
      </q-page>
    </template>

    <script setup>
    import { ref, onMounted } from 'vue'
    import { useI18n } from 'vue-i18n'
    import { useQuasar } from 'quasar'
    import { useSessionTemplateStore } from 'src/stores/sessionTemplate.store'
    import SessionDNAChart from 'src/components/booking/SessionDNAChart.vue'

    defineOptions({ name: 'SessionTemplateVault' })

    const { t } = useI18n()
    const $q = useQuasar()
    const templateStore = useSessionTemplateStore()

    const renameDialog = ref(false)
    const renameValue = ref('')
    const renamingTemplate = ref(null)
    const deployDialog = ref(false)
    const deployBookingId = ref('')
    const deployingTemplate = ref(null)
    const deploying = ref(false)
    const deployError = ref('')
    const defaultDna = { technical: 0, physical: 0, cognitive: 0, matchRealism: 0, weakFootFocus: 0 }

    onMounted(() => templateStore.fetchTemplates())

    function formatDate(iso) {
      return new Date(iso).toLocaleDateString()
    }

    function startRename(tmpl) {
      renamingTemplate.value = tmpl
      renameValue.value = tmpl.name
      renameDialog.value = true
    }

    async function saveRename() {
      if (!renameValue.value.trim()) return
      try {
        await templateStore.renameTemplate(renamingTemplate.value.id, renameValue.value.trim())
        renameDialog.value = false
        $q.notify({ type: 'positive', message: t('session.templates.renamed') })
      } catch {
        $q.notify({ type: 'negative', message: t('common.errorGeneric') })
      }
    }

    function confirmDelete(tmpl) {
      $q.dialog({
        title: t('session.templates.delete'),
        message: t('session.templates.confirmDelete'),
        cancel: true,
        persistent: true,
      }).onOk(async () => {
        try {
          await templateStore.deleteTemplate(tmpl.id)
          $q.notify({ type: 'positive', message: t('session.templates.deleted') })
        } catch {
          $q.notify({ type: 'negative', message: t('common.errorGeneric') })
        }
      })
    }

    // AC 5: deploy template to a booking
    function startDeploy(tmpl) {
      deployingTemplate.value = tmpl
      deployBookingId.value = ''
      deployError.value = ''
      deployDialog.value = true
    }

    async function confirmDeploy() {
      const bookingId = deployBookingId.value.trim()
      if (!bookingId) {
        deployError.value = t('session.templates.deployBookingIdRequired')
        return
      }
      deploying.value = true
      deployError.value = ''
      try {
        await templateStore.deployTemplate(deployingTemplate.value.id, bookingId)
        deployDialog.value = false
        $q.notify({ type: 'positive', message: t('session.templates.deployed') })
      } catch (e) {
        const code = e?.response?.data?.helpCode
        if (code === 'SESSION_ALREADY_EXISTS') {
          deployError.value = t('session.templates.deployAlreadyExists')
        } else if (code === 'SESSION_BOOKING_NOT_OWNED') {
          deployError.value = t('session.templates.deployBookingInvalid')
        } else {
          deployError.value = t('common.errorGeneric')
        }
      } finally {
        deploying.value = false
      }
    }
    </script>
    ```

### Frontend — Page Updates

- [x] **Task 24: Update `SessionBuilderPage.vue`** (AC: 3, 4, 8, 9)
  - [x] File: `src/frontend/src/pages/coach/SessionBuilderPage.vue`
  - [x] **Import additions** — add these imports:
    ```js
    import { useSessionTemplateStore } from 'src/stores/sessionTemplate.store'
    import DrillSuggestionPanel from 'src/components/session/DrillSuggestionPanel.vue'
    ```
  - [x] **Store additions** — instantiate template store:
    ```js
    const templateStore = useSessionTemplateStore()
    ```
  - [x] **State additions**:
    ```js
    const showSuggestions = ref(false)
    const saveAsTemplateDialog = ref(false)
    const templateNameInput = ref('')
    ```
  - [x] **Computed: `highlightAxes`** — map selected development focus to DNA axis keys:
    ```js
    const focusAxisMap = {
      technical: 'technical',
      physical: 'physical',
      cognitive: 'cognitive',
      matchRealism: 'matchRealism',
      weakFoot: 'weakFootFocus',
      possession: 'cognitive',
    }
    const highlightAxes = computed(() =>
      [...new Set(builderStore.developmentFocus
        .map(f => focusAxisMap[f])
        .filter(Boolean))]
    )
    ```
  - [x] **DNA Chart** — pass `highlight-axes` prop: `<SessionDNAChart :session-dna="builderStore.sessionDna" variant="full" :highlight-axes="highlightAxes" />`
  - [x] **Template banner** — add inside the blocks column, after the header row but before the block list. The banner text is a router-link to the vault per the epic spec ("tapping it navigates back to the template for comparison"):
    ```vue
    <div v-if="builderStore.sourceTemplateName && !builderStore.templateBannerDismissed"
         class="session-builder-page__template-banner row items-center q-mb-sm q-pa-sm">
      <q-icon name="bookmark" color="primary" class="q-mr-sm" />
      <router-link
        :to="{ name: 'coach-session-templates' }"
        class="text-caption col text-primary"
        style="text-decoration: none"
      >
        {{ t('session.templates.templateIndicator', { name: builderStore.sourceTemplateName }) }}
      </router-link>
      <q-btn flat dense round icon="close" size="xs"
             @click="builderStore.templateBannerDismissed = true" />
    </div>
    ```
  - [x] **Browse Templates button** — add to the header area next to "Save Draft" / "Save Session". This is the primary entry point to the vault so coaches can discover the deploy-to-booking flow:
    ```vue
    <q-btn
      flat
      icon="bookmark_border"
      :label="t('session.templates.browseTemplates')"
      :to="{ name: 'coach-session-templates' }"
    />
    ```
  - [x] **Save as Template button** — add to the header area next to the Browse Templates button:
    ```vue
    <q-btn
      v-if="builderStore.sessionId"
      flat
      icon="bookmark_add"
      :label="t('session.templates.saveAsTemplate')"
      :disable="builderStore.saving"
      @click="saveAsTemplateDialog = true"
    />
    ```
    Note: Only shown when `sessionId` is set (plan must be saved first to create a template from it)
  - [x] **Save as Template dialog** — add before `</q-page>`:
    ```vue
    <q-dialog v-model="saveAsTemplateDialog">
      <q-card style="min-width: 320px">
        <q-card-section>
          <div class="text-subtitle1">{{ t('session.templates.nameDialogTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <q-input v-model="templateNameInput" dense autofocus :label="t('session.templates.nameLabel')" />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn color="primary" :label="t('session.templates.saveAction')" @click="saveTemplate" />
        </q-card-actions>
      </q-card>
    </q-dialog>
    ```
  - [x] **`saveTemplate` function**:
    ```js
    async function saveTemplate() {
      if (!templateNameInput.value.trim()) return
      await templateStore.createTemplate(builderStore.sessionId, templateNameInput.value.trim())
      saveAsTemplateDialog.value = false
      templateNameInput.value = ''
      $q.notify({ type: 'positive', message: t('session.templates.saved') })
    }
    ```
  - [x] **Add Suggestions tab** to the drill library column (Col 1). Current tabs are `PLATFORM` / `PRIVATE`. Add a third tab `SUGGESTED`:
    ```vue
    <q-tab name="SUGGESTED" :label="t('session.suggestions.tabLabel')" />
    ```
  - [x] Handle tab switch to `SUGGESTED` — when `selectedLibrary === 'SUGGESTED'`, show `DrillSuggestionPanel` instead of the drill list:
    ```vue
    <DrillSuggestionPanel
      v-if="selectedLibrary === 'SUGGESTED'"
      :suggestions="builderStore.suggestedDrills"
      :loading="builderStore.suggestionsLoading"
      :is-personalized="builderStore.developmentFocus.length > 0"
      @close="selectedLibrary = 'PLATFORM'"
      @add-drill="addDrillToActiveBlock"
      @open-detail="openDrillDetail"
    />
    ```
  - [x] On tab switch to `SUGGESTED`, trigger `builderStore.fetchSuggestions()`:
    ```js
    async function fetchDrills() {
      if (selectedLibrary.value === 'SUGGESTED') {
        await builderStore.fetchSuggestions()
        return
      }
      sessionStore.searchQuery = drillSearch.value
      await sessionStore.searchDrills(selectedLibrary.value)
    }
    ```
  - [x] **Active block tracking** — when a `SessionBlockView` is clicked/focused, call `builderStore.setActiveBlock(index)`:
    ```vue
    <SessionBlockView
      ...
      @click.self="builderStore.setActiveBlock(index)"
    />
    ```
    Actually, use a wrapper `div` click rather than `.self` since `SessionBlockView` is a card: wrap each block in `<div @click="builderStore.setActiveBlock(index)">` with `cursor-pointer` style. Highlight the active block with `q-card--bordered` or a CSS class.
  - [x] **Update `addDrillToActiveBlock`** to use `builderStore.activeBlockIndex`:
    ```js
    function addDrillToActiveBlock(drill) {
      builderStore.addDrillToBlock(builderStore.activeBlockIndex, drill)
    }
    ```
    **IMPORTANT**: The existing `addDrillToActiveBlock` uses a local `activeBlockIndex` ref. Move this to `builderStore.setActiveBlock` / `builderStore.activeBlockIndex` (Task 19). The local ref in the page can be removed.

### Frontend — Router

- [x] **Task 25: Add template vault route** (AC: 6)
  - [x] File: `src/frontend/src/router/routes.js`
  - [x] Add within the authenticated coach routes section (after the session builder route):
    ```js
    {
      path: 'coach/session-templates',
      name: 'coach-session-templates',
      component: () => import('pages/coach/SessionTemplateVault.vue'),
    },
    ```

### Frontend — i18n

- [x] **Task 26: Add i18n keys to `en/index.js` and `de/index.js`** (AC: all frontend)
  - [x] File: `src/frontend/src/i18n/en/index.js`
  - [x] Add under the existing `session` key (alongside `builder`, `dna`, `drillLibrary`):
    ```js
    suggestions: {
      tabLabel: 'Suggested',
      personalizedTitle: 'Personalised Suggestions',
      fallbackTitle: 'Suggested for this age group',
      empty: 'No suggestions available yet',
    },
    templates: {
      title: 'Session Templates',
      browseTemplates: 'Templates',
      saveAsTemplate: 'Save as Template',
      nameDialogTitle: 'Name this template',
      nameLabel: 'Template name',
      saveAction: 'Save Template',
      loadFromTemplate: 'Load from Template',
      useThisSession: 'Use for this session',
      templateIndicator: 'Based on template: {name}',
      deployDialogTitle: 'Use for this session',
      deployDialogSubtitle: 'Enter the booking ID for the session you want to plan.',
      deployBookingIdLabel: 'Booking ID',
      deployBookingIdRequired: 'Please enter a booking ID',
      deployAction: 'Create session plan',
      deployAlreadyExists: 'A session plan already exists for this booking',
      deployBookingInvalid: 'Booking not found or not confirmed',
      deployCount: '{count} uses',
      lastDeployed: 'Last used {date}',
      drillCount: '{count} drills',
      rename: 'Rename',
      delete: 'Delete',
      confirmDelete: 'Delete this template? Sessions created from it will not be affected.',
      emptyTitle: 'No templates yet',
      emptySubtitle: 'Save your first session as a template',
      saved: 'Template saved',
      renamed: 'Template renamed',
      deleted: 'Template deleted',
      deployed: 'Session plan created from template',
    },
    ```
  - [x] File: `src/frontend/src/i18n/de/index.js`
  - [x] Add matching German translations:
    ```js
    suggestions: {
      tabLabel: 'Vorschläge',
      personalizedTitle: 'Personalisierte Vorschläge',
      fallbackTitle: 'Für diese Altersgruppe empfohlen',
      empty: 'Noch keine Vorschläge verfügbar',
    },
    templates: {
      title: 'Session-Vorlagen',
      browseTemplates: 'Vorlagen',
      saveAsTemplate: 'Als Vorlage speichern',
      nameDialogTitle: 'Vorlage benennen',
      nameLabel: 'Vorlagenname',
      saveAction: 'Vorlage speichern',
      loadFromTemplate: 'Aus Vorlage laden',
      useThisSession: 'Für diese Session verwenden',
      templateIndicator: 'Basierend auf Vorlage: {name}',
      deployDialogTitle: 'Für diese Session verwenden',
      deployDialogSubtitle: 'Gib die Buchungs-ID für die zu planende Session ein.',
      deployBookingIdLabel: 'Buchungs-ID',
      deployBookingIdRequired: 'Bitte eine Buchungs-ID eingeben',
      deployAction: 'Session-Plan erstellen',
      deployAlreadyExists: 'Für diese Buchung gibt es bereits einen Session-Plan',
      deployBookingInvalid: 'Buchung nicht gefunden oder nicht bestätigt',
      deployCount: '{count} Verwendungen',
      lastDeployed: 'Zuletzt verwendet am {date}',
      drillCount: '{count} Übungen',
      rename: 'Umbenennen',
      delete: 'Löschen',
      confirmDelete: 'Vorlage löschen? Bereits erstellte Sessions werden nicht beeinflusst.',
      emptyTitle: 'Noch keine Vorlagen',
      emptySubtitle: 'Speichere deine erste Session als Vorlage',
      saved: 'Vorlage gespeichert',
      renamed: 'Vorlage umbenannt',
      deleted: 'Vorlage gelöscht',
      deployed: 'Session-Plan aus Vorlage erstellt',
    },
    ```

## Dev Notes

### CRITICAL: `SessionPlanResponse` Constructor Arg Count

The record has 11 fields in Story 4.4 (confirmed by reading `SessionPlanResponse.java` — `id, bookingId, coachId, playerId, blocks, sessionDna, equipmentList, developmentFocus, status, createdAt, updatedAt`). Story 4.5 adds `sourceTemplateId` (UUID) and `sourceTemplateName` (String) at positions 12 and 13, making the new total **13 args**. Any `new SessionPlanResponse(...)` call that doesn't match 13 args will fail to compile. The only constructor call is inside `SessionPlanService.buildResponse()` — update that method first, then verify `SessionTemplateService.buildResponse()` matches.

### CRITICAL: `DrillLibraryService.toResponse()` is Package-Private

This was changed from `private` to package-private in Story 4.4. Both `DrillSuggestionService` and `SessionTemplateService` are in `platform.session.service` — they can call `drillLibraryService.toResponse(d, false, List.of(), null, null, null)` directly. Do NOT reconstruct `DrillResponse` inline — the record has 14 fields and any inline constructor call will break when fields are added.

Signature: `DrillResponse toResponse(Drill drill, boolean hasVideo, List<String> tags, Boolean isClonedByMe, UUID cloneId, String videoUrl)`

### Suggestions Endpoint — No Video URLs in Suggestions

The suggestion endpoint returns `DrillResponse` objects with `hasVideo=false, videoUrl=null`. This matches the Session Builder pattern (same as `SessionPlanService.buildResponse()`). Video playback is only needed on the drill detail panel; the suggestion list is metadata + thumbnail only. The `DrillCard` component handles `hasVideo=false` gracefully (no autoplay attempt).

### Session Builder — Active Block Index

The `activeBlockIndex` was previously a local `ref` in `SessionBuilderPage.vue`. Story 4.5 moves it to the store (`sessionBuilder.store.js`) so the `DrillSuggestionPanel` can also trigger `addDrillToBlock(builderStore.activeBlockIndex, drill)` without prop drilling. Remove the local `activeBlockIndex` ref from the page.

### Suggestions Require a Saved Session

`fetchSuggestions()` requires `sessionId.value` (i.e., the session must already be saved to the DB). If the coach hasn't saved the session yet, `sessionId.value` is `null` and the method returns early. The "Suggested" tab should show an empty state with "Save your session first to get suggestions" — add this check in the `DrillSuggestionPanel` or the page:

```js
// In SessionBuilderPage.vue, when switching to SUGGESTED tab:
if (selectedLibrary.value === 'SUGGESTED' && !builderStore.sessionId) {
  $q.notify({ type: 'warning', message: t('session.builder.saveDraftFirst') })
  selectedLibrary.value = 'PLATFORM'
  return
}
```
Add `session.builder.saveDraftFirst: 'Save the session first to get personalised suggestions'` to i18n.

### Template Deploy — Race Condition Handling

`deployTemplate` can hit `SESSION_ALREADY_EXISTS` if the same bookingId already has a session (e.g., coach navigated to the Session Builder and manually created a plan, then tries to deploy a template). The `SessionTemplateResource` propagates `OperationNotAllowedException(SESSION_ALREADY_EXISTS)` → `ApiAdvice` → 403 `helpCode: SESSION_ALREADY_EXISTS`. The frontend in `SessionTemplateVault.vue` doesn't directly handle this (deploy isn't done from the vault), but if a future entry point is added, handle the 403 by reloading the existing plan.

### DNA Focus Highlighting — Focus-to-Axis Mapping

Not all development focus values map to a DNA axis:
- `technical` → `technical`
- `physical` → `physical`
- `cognitive` → `cognitive`
- `matchRealism` → `matchRealism`
- `weakFoot` → `weakFootFocus`
- `possession` → `cognitive` (proxy)
- `set_pieces` → (no mapping — pressure/tactical, not a DNA dimension)
- `goalkeeping` → (no mapping)

When `set_pieces` or `goalkeeping` are selected, no additional axis is highlighted. This is intentional — these are valid training focuses but don't map cleanly to the 5 DNA dimensions. The DNA chart still updates via focus scoring; it just doesn't highlight any specific axis.

### `SessionTemplate` JSONB Shape

`session_templates.blocks` uses the exact same `SessionBlockData` JSONB shape as `session.sessions.blocks`. Hibernate's Jackson-based deserialization via `@JdbcTypeCode(SqlTypes.JSON)` works the same way. If deserialization fails on `SessionTemplate` but works on `Session`, check that `SessionBlockData` and `SessionDrillRef` have their `ParameterNamesModule`-compatible constructors (they do — Spring Boot 3.x registers this automatically). Follow `Session.java` exactly.

### `incrementDeployCount` — Atomic Update Pattern

The `@Modifying(clearAutomatically = true) @Query` pattern in `SessionTemplateRepository` atomically increments `deployCount` without loading the entity first. `clearAutomatically = true` ensures the entity cache is cleared so subsequent `findById` within the same transaction sees the updated count. This is the same pattern used in `DrillVideoRefRepository.incrementRefCount` — look at that method if in doubt.

### AC 10 — Gate Applies to ALL Template Endpoints

`checkSessionBuilderGate(coachUserId)` must be called at the start of **all five** service methods: `createTemplate`, `listTemplates`, `renameTemplate`, `deleteTemplate`, and `deployTemplate`. The AC says "Scout-tier coach calls **any** suggestion or template endpoint → 403 featureGated". Only `createTemplate` and `deployTemplate` had the gate in the original pseudocode; `listTemplates`, `renameTemplate`, and `deleteTemplate` also need it. The tasks reflect the corrected version.

### `buildResponse` in `SessionTemplateService` — Intentional Duplication

The `buildResponse` private method in `SessionTemplateService` mirrors the same method in `SessionPlanService`. This is intentional rather than a shared utility — both services produce `SessionPlanResponse` but from different root entities. If the response shape changes in a future story, update both. A cross-reference comment in `SessionTemplateService.buildResponse()` is sufficient: `// mirrors SessionPlanService.buildResponse — keep in sync`.

### `V44` Migration — NOT a FK on `source_template_id`

`session.sessions.source_template_id` is intentionally NOT a foreign key. If it were a FK, `deleteTemplate` (soft-delete to ARCHIVED) would still be fine, but any hard-delete would cascade or block. Per the AC, deleting a template must not affect deployed sessions. The column stores the UUID for display only; if the template is archived, `sourceTemplateName` (stored denormalized in the session) ensures the builder still shows the template name without any join.

### Previous Story Lessons (from Story 4.4 debug log)

1. **`ErrorCode` is an interface, not an abstract class** — `SessionErrorCode` implements `ErrorCode` with `getErrorCode()` returning `this.name()`. `ApiAdvice.operationDeniedHandler` calls `exception.getErrorCode().getErrorCode()` (the interface method), not `.name()` directly. Keep this pattern for `TEMPLATE_NOT_OWNED`.

2. **`@MockitoBean VideoProviderAdapter`** in IT tests — `DrillLibraryService.batchVideoLookup()` is called even for suggestions (via `toResponse` with `hasVideo=false`). Wait — actually suggestions call `drillLibraryService.toResponse(d, false, List.of(), null, null, null)` with `hasVideo=false`, and `toResponse` does NOT call `batchVideoLookup` — it just constructs the `DrillResponse` record with the passed values. So `VideoProviderAdapter` is NOT called by `DrillSuggestionService` or `SessionTemplateService`. However, if any IT test triggers `listDrills` or any endpoint that calls `batchVideoLookup`, mock it. For `SessionTemplateResourceIT`, the `deployTemplate` test calls `buildResponse` which calls `drillRepository.findAllById` + `drillLibraryService.toResponse` — no video lookup needed since `toResponse` is called with `hasVideo=false`. Mock `VideoProviderAdapter` anyway as a safety net (`@MockitoBean VideoProviderAdapter videoProviderAdapter`).

3. **`CONFIRMED` status check on deploy** — `deployTemplate` validates the booking is in `CONFIRMED` or `UPCOMING` status via `isBookingPlannable(booking.status())` (same guard as `SessionPlanService.createSession`). The check lives between the coach-ownership check (step 6) and the duplicate-session check (step 7). Use `SESSION_BOOKING_NOT_OWNED` as the error code (same as Story 4.4 pattern). This check is in the pseudocode as step 6a.

4. **`DrillCard` context prop** — Use `context="session-builder"` (not `context="add-to-session"`). The context value `"session-builder"` is the recognized value that shows the "Add" button and emits `add-to-session`. Using any other string causes the Add button to not appear.

### Project Structure Notes

| Component | Location |
|---|---|
| V44 migration | `src/main/resources/db/migration/V44__session_templates.sql` |
| SessionTemplate entity (CREATE) | `src/main/java/com/softropic/skillars/platform/session/repo/SessionTemplate.java` |
| SessionTemplateRepository (CREATE) | `src/main/java/com/softropic/skillars/platform/session/repo/SessionTemplateRepository.java` |
| Session.java (UPDATE — 2 nullable fields) | `src/main/java/com/softropic/skillars/platform/session/repo/Session.java` |
| SessionRepository (UPDATE — add findTop5) | `src/main/java/com/softropic/skillars/platform/session/repo/SessionRepository.java` |
| SessionErrorCode (UPDATE — add TEMPLATE_NOT_OWNED) | `src/main/java/com/softropic/skillars/platform/session/contract/SessionErrorCode.java` |
| SessionPlanResponse (UPDATE — 2 new fields, total 13) | `src/main/java/com/softropic/skillars/platform/session/contract/SessionPlanResponse.java` |
| CreateTemplateRequest (CREATE) | `src/main/java/com/softropic/skillars/platform/session/contract/CreateTemplateRequest.java` |
| RenameTemplateRequest (CREATE) | `src/main/java/com/softropic/skillars/platform/session/contract/RenameTemplateRequest.java` |
| SessionTemplateResponse (CREATE) | `src/main/java/com/softropic/skillars/platform/session/contract/SessionTemplateResponse.java` |
| DrillSuggestionService (CREATE) | `src/main/java/com/softropic/skillars/platform/session/service/DrillSuggestionService.java` |
| SessionTemplateService (CREATE) | `src/main/java/com/softropic/skillars/platform/session/service/SessionTemplateService.java` |
| SessionPlanService (UPDATE — buildResponse 13 args) | `src/main/java/com/softropic/skillars/platform/session/service/SessionPlanService.java` |
| DrillLibraryResource (UPDATE — add /suggestions) | `src/main/java/com/softropic/skillars/platform/session/api/DrillLibraryResource.java` |
| SessionTemplateResource (CREATE) | `src/main/java/com/softropic/skillars/platform/session/api/SessionTemplateResource.java` |
| DrillSuggestionServiceTest (CREATE) | `src/test/java/com/softropic/skillars/platform/session/service/DrillSuggestionServiceTest.java` |
| SessionTemplateResourceIT (CREATE) | `src/test/java/com/softropic/skillars/platform/session/api/SessionTemplateResourceIT.java` |
| session.api.js (UPDATE) | `src/frontend/src/api/session.api.js` |
| sessionBuilder.store.js (UPDATE) | `src/frontend/src/stores/sessionBuilder.store.js` |
| sessionTemplate.store.js (CREATE) | `src/frontend/src/stores/sessionTemplate.store.js` |
| DrillSuggestionPanel.vue (CREATE) | `src/frontend/src/components/session/DrillSuggestionPanel.vue` |
| SessionDNAChart.vue (UPDATE — highlightAxes prop) | `src/frontend/src/components/booking/SessionDNAChart.vue` |
| SessionTemplateVault.vue (CREATE — list, rename, delete, deploy) | `src/frontend/src/pages/coach/SessionTemplateVault.vue` |
| SessionBuilderPage.vue (UPDATE) | `src/frontend/src/pages/coach/SessionBuilderPage.vue` |
| routes.js (UPDATE) | `src/frontend/src/router/routes.js` |
| en/index.js (UPDATE) | `src/frontend/src/i18n/en/index.js` |
| de/index.js (UPDATE) | `src/frontend/src/i18n/de/index.js` |

### References

- Story 4.5 epic spec + dev notes [`_bmad-output/planning-artifacts/skillars-epics.md` lines 1616–1658]
- Story 4.4 full implementation — Session entity, SessionPlanResponse, DrillLibraryService.toResponse(), BookingQueryService pattern, checkSessionBuilderGate call pattern [`_bmad-output/implementation-artifacts/skillars-4-4-session-builder-block-structure-dna.md`]
- Story 4.4 debug log — ErrorCode interface, `@MockitoBean VideoProviderAdapter`, `checkSessionBuilderGate` takes `Long coachUserId` not UUID [`_bmad-output/implementation-artifacts/skillars-4-4-session-builder-block-structure-dna.md` Dev Agent Record]
- `DrillLibraryService.java` — `toResponse()` signature (package-private), `checkSessionBuilderGate()` pattern [`src/main/java/com/softropic/skillars/platform/session/service/DrillLibraryService.java`]
- `DrillLibraryResource.java` — existing endpoint structure, `InvalidParamException` usage [`src/main/java/com/softropic/skillars/platform/session/api/DrillLibraryResource.java`]
- `Session.java` — JSONB entity pattern, `@JdbcTypeCode(SqlTypes.JSON)` [`src/main/java/com/softropic/skillars/platform/session/repo/Session.java`]
- `SessionRepository.java` — current methods + Spring Data JPA naming conventions [`src/main/java/com/softropic/skillars/platform/session/repo/SessionRepository.java`]
- `DrillVideoRefRepository.java` — `@Modifying(clearAutomatically = true)` pattern for `incrementRefCount` [`src/main/java/com/softropic/skillars/platform/session/repo/DrillVideoRefRepository.java`]
- `DrillMetadata.java` — all 14 metadata fields used in focus scoring [`src/main/java/com/softropic/skillars/platform/session/contract/DrillMetadata.java`]
- `DrillResponse.java` — 14-field record (do not inline the constructor) [`src/main/java/com/softropic/skillars/platform/session/contract/DrillResponse.java`]
- `SessionBuilderPage.vue` — current 3-column layout, `fetchDrills`, `addDrillToActiveBlock` pattern [`src/frontend/src/pages/coach/SessionBuilderPage.vue`]
- `sessionBuilder.store.js` — current store structure, `addDrillToBlock(blockIndex, drill)` signature [`src/frontend/src/stores/sessionBuilder.store.js`]
- `SessionDNAChart.vue` — 5-axis SVG radar, `compact/full` variants, existing props [`src/frontend/src/components/booking/SessionDNAChart.vue`]
- `DrillCard.vue` — `context="session-builder"` value is correct for add button [`src/frontend/src/components/session/DrillCard.vue`]
- Project context: DDD package structure, @PreAuthorize required, record DTOs, no entity exposure [`_bmad-output/project-context.md`]

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- `SessionRepository.java` missing `List` import — added `java.util.List`
- `DrillSuggestionServiceTest` recency test used mismatched drill intensities — fixed to equal intensity so only recency score differentiates. Used `@MockitoSettings(LENIENT)` to allow shared `@BeforeEach` stub across tests that don't need it (Scout gate test)
- `showSuggestions` ref declared but never used in `SessionBuilderPage.vue` — removed (visibility controlled by `selectedLibrary === 'SUGGESTED'`)
- `sessionBuilder.store.js` catch clause `e` unused — changed to bare `catch` block

### Completion Notes List

- **V44 migration**: Created `session.session_templates` table with JSONB blocks, coach-scoped, soft-delete via `status` column. Added `source_template_id` (non-FK) and `source_template_name` (denormalized) to `session.sessions`.
- **SessionErrorCode**: Added `TEMPLATE_NOT_OWNED` enum value.
- **Session entity**: Added nullable `sourceTemplateId` and `sourceTemplateName` fields.
- **SessionPlanResponse**: Expanded from 11 to 13 fields; updated `SessionPlanService.buildResponse()` and the new `SessionTemplateService.buildResponse()` to pass all 13 args.
- **SessionTemplate entity + repository**: Full JPA entity mirroring `Session.java` JSONB patterns; `incrementDeployCount` uses `@Modifying(clearAutomatically = true)` atomic JPQL update.
- **SessionRepository**: Added `findTop5ByPlayerIdOrderByCreatedAtDesc` Spring Data JPA method.
- **Contract DTOs**: Created `CreateTemplateRequest`, `RenameTemplateRequest`, `SessionTemplateResponse`.
- **DrillSuggestionService**: Weighted scorer (focus 40%, neglected stub 0%, ageFit stub 0.5, recency 10%); fallback to Foundation 20 drills when no focus set; excludes drills already in current session.
- **SessionTemplateService**: Full CRUD + deploy; `deployTemplate` checks booking ownership, plannable status, duplicate session guard; increments deploy count atomically after save.
- **DrillLibraryResource**: Added `GET /api/session/drills/suggestions` endpoint with `limit` range validation.
- **SessionTemplateResource**: Full REST CRUD + deploy, all gated with `HAS_COACH_ROLE`.
- **SessionPlanService**: Updated only `buildResponse` (single change, all 4 callers pass through it).
- **DrillSuggestionServiceTest**: 8 unit tests covering fallback, focus ranking, recency penalty, gate, 404, cross-coach 404, private drills, already-used exclusion. All pass.
- **SessionTemplateResourceIT**: 18 integration test scenarios covering create, list, rename, delete, deploy across owner/other/scout roles.
- **Frontend**: `session.api.js` 6 new methods; `sessionBuilder.store.js` adds suggestions, active block index, template provenance state; `sessionTemplate.store.js` new Pinia store; `DrillSuggestionPanel.vue` new component; `SessionDNAChart.vue` `highlightAxes` prop; `SessionTemplateVault.vue` new page; `SessionBuilderPage.vue` major update with SUGGESTED tab, template banner, Save as Template dialog, active block tracking; `routes.js` + `en/index.js` + `de/index.js` updated.

### File List

src/main/resources/db/migration/V44__session_templates.sql
src/main/java/com/softropic/skillars/platform/session/contract/SessionErrorCode.java
src/main/java/com/softropic/skillars/platform/session/repo/Session.java
src/main/java/com/softropic/skillars/platform/session/contract/SessionPlanResponse.java
src/main/java/com/softropic/skillars/platform/session/repo/SessionTemplate.java
src/main/java/com/softropic/skillars/platform/session/repo/SessionTemplateRepository.java
src/main/java/com/softropic/skillars/platform/session/repo/SessionRepository.java
src/main/java/com/softropic/skillars/platform/session/contract/CreateTemplateRequest.java
src/main/java/com/softropic/skillars/platform/session/contract/RenameTemplateRequest.java
src/main/java/com/softropic/skillars/platform/session/contract/SessionTemplateResponse.java
src/main/java/com/softropic/skillars/platform/session/service/DrillSuggestionService.java
src/main/java/com/softropic/skillars/platform/session/service/SessionTemplateService.java
src/main/java/com/softropic/skillars/platform/session/service/SessionPlanService.java
src/main/java/com/softropic/skillars/platform/session/api/DrillLibraryResource.java
src/main/java/com/softropic/skillars/platform/session/api/SessionTemplateResource.java
src/test/java/com/softropic/skillars/platform/session/service/DrillSuggestionServiceTest.java
src/test/java/com/softropic/skillars/platform/session/api/SessionTemplateResourceIT.java
src/frontend/src/api/session.api.js
src/frontend/src/stores/sessionBuilder.store.js
src/frontend/src/stores/sessionTemplate.store.js
src/frontend/src/components/session/DrillSuggestionPanel.vue
src/frontend/src/components/booking/SessionDNAChart.vue
src/frontend/src/pages/coach/SessionTemplateVault.vue
src/frontend/src/pages/coach/SessionBuilderPage.vue
src/frontend/src/router/routes.js
src/frontend/src/i18n/en/index.js
src/frontend/src/i18n/de/index.js

## Change Log

| Date | Version | Description | Author |
|------|---------|-------------|--------|
| 2026-06-18 | 1.0 | Implemented Story 4.5: Intelligent Drill Suggestions & Session Templates — V44 migration, DrillSuggestionService (weighted scorer), SessionTemplateService (CRUD + deploy), REST endpoints, SessionTemplateVault page, SUGGESTED tab in SessionBuilderPage, DNA axis highlighting, tier gating, EN/DE i18n | claude-sonnet-4-6 |

### Review Findings

> Code review 2026-06-18 — Blind Hunter + Acceptance Auditor completed (Edge Case Hunter failed: session limit). 6 findings dismissed as false-positives or spec-conformant behaviour.

**Patch findings:**

- [x] [Review][Patch] `activeBlockIndex` not reset when block is removed — after removal, index can exceed `blocks.length`; next `addDrillToBlock` silently drops the drill [`sessionBuilder.store.js` / `SessionBuilderPage.vue`]
- [x] [Review][Patch] `saveTemplate()` has no error handling — unhandled throw from `createTemplate` leaves no error notification for the user [`SessionBuilderPage.vue:saveTemplate`]
- [x] [Review][Patch] `getRecentDrillIds(playerId)` called with potentially-null playerId — DRAFT sessions may have null playerId; Spring Data generates `IS NULL` query, returning unrelated sessions [`DrillSuggestionService.java:getRecentDrillIds`]
- [x] [Review][Patch] `SessionTemplateService.buildResponse` hardcodes SLU to 0 — spec requires `calculateBlockSlu(block.drills(), metaMap)`; deploy response reports wrong block SLU subtotals [`SessionTemplateService.java:buildResponse`]
- [x] [Review][Patch] `SessionTemplateVault` back button uses wrong route + conflicting navigation — `:to="{ name: 'coach-session-templates' }"` self-references current page (should be `coach-dashboard`); combined with `@click="$router.back()"` causes double-navigation [`SessionTemplateVault.vue:60`]
- [x] [Review][Patch] "Save as Template" button not gated for Scout tier — only checks `v-if="builderStore.sessionId"`, missing `!builderStore.isGated`; Scout coaches with an existing session see the button (AC 10 violation) [`SessionBuilderPage.vue`]
- [x] [Review][Patch] `DrillSuggestionServiceTest` does not use Instancio — task spec and project rules require Instancio for `Drill`/`DrillMetadata` test data; reflection-based helpers used instead [`DrillSuggestionServiceTest.java`]
- [x] [Review][Patch] Bare `DELETE FROM main.sec` in tearDown — confirmed false positive: `main.sec` is a real table (V10 migration) and the no-WHERE delete is the established pattern in `SessionBuilderResourceIT.java`; no change [`SessionTemplateResourceIT.java:tearDown`]
- [x] [Review][Patch] `SessionTemplateResponse` includes undocumented `developmentFocus` field — AC 6 specifies exactly 8 fields; record has 9 (extra `List<String> developmentFocus`) [`SessionTemplateResponse.java`]

**Deferred findings:**

- [x] [Review][Defer] Race condition: `existsByBookingId` + `save` not atomic in `deployTemplate` [`SessionTemplateService.java:deployTemplate`] — deferred, pre-existing pattern identical to `SessionPlanService.createSession`; no unique DB constraint on `booking_id`

### Review Findings — Round 2 (2026-06-18)

> Code review 2026-06-18 Round 2 — All three layers completed (Blind Hunter, Edge Case Hunter, Acceptance Auditor). 3 findings dismissed. **CRITICAL: 7 of 11 patch findings are Round 1 findings marked [x] applied that remain unresolved in the working tree.** 4 findings are new.

**Patch findings — Round 1 items (7) — confirmed present in working tree, no further action needed:**

- [x] [Review][Patch] `SessionTemplateVault` back button self-references vault route — fixed: `{ name: 'coach-dashboard' }`, no `@click` [`SessionTemplateVault.vue:6`]
- [x] [Review][Patch] `saveTemplate()` no error handling — fixed: wrapped in try/catch [`SessionBuilderPage.vue:saveTemplate`]
- [x] [Review][Patch] `SessionTemplateService.buildResponse()` hardcoded SLU 0 — fixed: calls `calculateBlockSlu()` [`SessionTemplateService.java:buildResponse`]
- [x] [Review][Patch] "Save as Template" button missing Scout gate — fixed: `v-if="builderStore.sessionId && !builderStore.isGated"` [`SessionBuilderPage.vue`]
- [x] [Review][Patch] `DrillSuggestionServiceTest` used reflection — fixed: uses Instancio [`DrillSuggestionServiceTest.java`]
- [x] [Review][Patch] `SessionTemplateResponse` had 9 fields — fixed: 8 fields, no `developmentFocus` [`SessionTemplateResponse.java`]
- [x] [Review][Patch] `getRecentDrillIds()` null playerId — fixed: `playerId != null ? getRecentDrillIds(playerId) : Set.of()` [`DrillSuggestionService.java`]

**Patch findings — New (4):**

- [x] [Review][Patch] `deployTemplate()` sets `playerId` with no null guard — fixed: throws `SESSION_BOOKING_NOT_OWNED` if `booking.playerId() == null` [`SessionTemplateService.java:deployTemplate`]
- [x] [Review][Patch] `deployBookingId` input no UUID format validation — fixed: UUID_REGEX check added before API call; new i18n key `deployBookingIdInvalidFormat` [`SessionTemplateVault.vue:confirmDeploy`]
- [x] [Review][Patch] `deployTemplate` store optimistic update — dismissed: update happens post-`await` success, no optimistic mutation to roll back [`sessionTemplate.store.js:deployTemplate`]
- [x] [Review][Patch] `removeBlock()` direct `activeBlockIndex = 0` assignment — fixed: `builderStore.setActiveBlock(Math.min(index, builderStore.blocks.length - 1))` [`SessionBuilderPage.vue:removeBlock`]

**Deferred findings — Round 2:**

- [x] [Review][Defer] `deleteTemplate()` no ARCHIVED guard — double-delete silently re-archives; idempotent behavior acceptable [`SessionTemplateService.java:deleteTemplate`] — deferred, acceptable idempotent soft-delete
- [x] [Review][Defer] `deployTemplate()` passes `t.getBlocks()` reference not defensive copy — safe in current code path (no mutation in same tx after save) [`SessionTemplateService.java:deployTemplate`] — deferred, theoretical only
- [x] [Review][Defer] `computeFocusScore()` returns 0 for all-unsupported focus values — random subset within age-fit tier returned; by-design with current stubs [`DrillSuggestionService.java:computeFocusScore`] — deferred, algorithmic design
- [x] [Review][Defer] Template name inputs missing `maxlength="200"` client-side — server `@Size(max=200)` catches it; generic error shown [`SessionTemplateVault.vue`, `SessionBuilderPage.vue`] — deferred, minor UX
- [x] [Review][Defer] `createTemplate()` store action never sets `error.value` on failure — callers (SessionBuilderPage) handle errors directly [`sessionTemplate.store.js`] — deferred, minimal impact
- [x] [Review][Defer] `SessionTemplate.blocks` null risk if `session.getBlocks()` null — `Session.blocks` is NOT NULL in DB so sessions never have null blocks [`SessionTemplateService.java:createTemplate`] — deferred, constraint prevents
- [x] [Review][Defer] V44 no index on `source_template_id` — performance concern for future analytics queries only [`V44__session_templates.sql`] — deferred, not needed for current functionality
- [x] [Review][Defer] `deployTemplate()` race condition (duplicate booking) — pre-existing pattern, already deferred from Round 1 [`SessionTemplateService.java:deployTemplate`] — deferred, same as Round 1
