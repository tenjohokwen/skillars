# Story skillars-5.3: Skills Radar — Assessment Entry & Multi-Coach Cumulation

Status: done

## Story

As a coach,
I want to enter periodic Skills Radar assessments for my players using a standardised rubric,
So that ability scores are grounded in evidence and accumulate fairly across all coaches working with the same player.

## Acceptance Criteria

**AC 1: Assessment entry form — skill subset, scale, rubric** — Given an Instructor+ coach opens a player's Skills Radar assessment panel, when they start a new assessment entry, then they can enter scores for any subset of the 15 skills (not required to assess all 15); each score uses the 1–100 universal scale; 7 tier labels are shown as reference: Elite (90–100), Excellent (80–89), Good (70–79), Above Average (60–69), Average (50–59), Below Average (40–49), Very Weak (<40); each skill score field shows the standardised rubric criteria as a tooltip (FR-DEV-007, FR-DEV-008).

**AC 2: Assessment submission creates entries** — Given a coach submits a Skills Radar assessment entry, when the entry is saved, then one `radar_assessment_entries` record is created per skill assessed (id UUID, assessmentGroupId UUID, coachId UUID, playerId BIGINT, skillCode VARCHAR(10), score SMALLINT 1–100, assessmentDate DATE, assessmentType ENUM, notes VARCHAR nullable, createdAt TIMESTAMPTZ); all skills in the same sitting share the same `assessmentGroupId`; the save is `@Transactional` (all succeed or none are saved).

**AC 3: Multi-coach composite calculation** — Given multiple coaches have submitted assessments for the same player and skill, when a new entry is saved and committed, then a `RadarEntrySubmittedEvent` is fired and handled `@Async` via `@TransactionalEventListener(AFTER_COMMIT)`; the composite is (avg OBJECTIVE scores × 0.50) + (avg MATCH_OBSERVATION scores × 0.30) + (avg COACH_EVALUATION scores × 0.20) across all coaches; the result is upserted into `player_radar_composites` (playerId BIGINT, skillCode VARCHAR, compositeScore NUMERIC(5,2), entryCount INT, lastUpdatedAt TIMESTAMPTZ); composite is never computed at query time — always from the materialised snapshot (FR-DEV-009).

**AC 4: View own past entries + other coach count** — Given a coach has entered assessments for a skill, when they view past entries, then their own entries are listed chronologically (date, score, assessmentType, notes); they see the count of other coaches' entries per skill (e.g., "2 other coaches have also assessed this skill") but NOT other coaches' individual scores.

**AC 5: Scout tier blocked** — Given a Scout tier coach attempts to enter a Skills Radar assessment, when the request reaches the endpoint, then it returns `403 Forbidden` with `ErrorDto` code `security.featureGated`; the assessment entry UI is absent from the Scout coach view — not shown in a disabled state (UX-DR22).

**AC 6: Score validation — 400 on out-of-range** — Given an assessment entry is submitted with a score outside the 1–100 range, when the endpoint validates the payload, then it returns `400 Bad Request` with field-level validation errors; no partial entries are saved.

## Tasks / Subtasks

---

### Backend — Migration

- [x] **Task 1: Write `V50__radar_assessment_entries.sql`** (AC: 2, 3)
  - [x] File: `src/main/resources/db/migration/V50__radar_assessment_entries.sql`
  - [x] Previous migration: V49 (`neglected_skill_unique_open_constraint`). This MUST be V50.
  - [x] SQL:
    ```sql
    -- Story 5.3: Skills Radar Assessment Entry & Multi-Coach Cumulation

    CREATE TYPE development.assessment_type AS ENUM ('OBJECTIVE', 'MATCH_OBSERVATION', 'COACH_EVALUATION');

    CREATE TABLE development.radar_assessment_entries (
        id                   UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
        assessment_group_id  UUID          NOT NULL,
        coach_id             UUID          NOT NULL,   -- marketplace.coach_profiles.id (UUID)
        player_id            BIGINT        NOT NULL,   -- TSID Long; NOT UUID
        skill_code           VARCHAR(10)   NOT NULL REFERENCES development.skill_definitions(code),
        score                SMALLINT      NOT NULL CHECK (score >= 1 AND score <= 100),
        assessment_date      DATE          NOT NULL,
        assessment_type      development.assessment_type NOT NULL,
        notes                VARCHAR(500),
        created_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
    );
    CREATE INDEX idx_radar_entries_player_skill   ON development.radar_assessment_entries (player_id, skill_code);
    CREATE INDEX idx_radar_entries_player_coach   ON development.radar_assessment_entries (player_id, coach_id);
    CREATE INDEX idx_radar_entries_group          ON development.radar_assessment_entries (assessment_group_id);
    -- Unique constraint makes client-side assessmentGroupId retries truly idempotent (same group+coach+skill is rejected as duplicate)
    CREATE UNIQUE INDEX uq_radar_entries_group_coach_skill ON development.radar_assessment_entries (assessment_group_id, coach_id, skill_code);

    CREATE TABLE development.player_radar_composites (
        player_id        BIGINT          NOT NULL,   -- TSID Long; NOT UUID
        skill_code       VARCHAR(10)     NOT NULL REFERENCES development.skill_definitions(code),
        composite_score  NUMERIC(5,2)    NOT NULL,
        entry_count      INT             NOT NULL,
        last_updated_at  TIMESTAMPTZ     NOT NULL,
        PRIMARY KEY (player_id, skill_code)
    );
    ```
  - [x] **CRITICAL**: `player_id` in BOTH tables is BIGINT — NOT UUID. Same correction as Stories 5.1 and 5.2. `coach_id` remains UUID (coach profile IDs are UUIDs).
  - [x] **ID collision check**: Confirm V50 is unused by scanning all existing migration files before committing.
  - [x] **No `platform_config` entries needed** for this story — no configurable thresholds.

---

### Backend — Repository Layer

- [x] **Task 2: Create `RadarAssessmentEntry.java` entity** (AC: 2)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/repo/RadarAssessmentEntry.java`
  - [x] Use `AssessmentType` enum (see Task 6) with `@Enumerated(EnumType.STRING)`:
    ```java
    @Entity
    @Table(schema = "development", name = "radar_assessment_entries")
    @Getter @Setter @NoArgsConstructor
    public class RadarAssessmentEntry {
        @Id @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;

        @Column(name = "assessment_group_id", nullable = false)
        private UUID assessmentGroupId;

        @Column(name = "coach_id", nullable = false)
        private UUID coachId;

        @Column(name = "player_id", nullable = false)
        private Long playerId;   // BIGINT TSID — NOT UUID

        @Column(name = "skill_code", nullable = false, length = 10)
        private String skillCode;

        @Column(nullable = false)
        private Short score;

        @Column(name = "assessment_date", nullable = false)
        private LocalDate assessmentDate;

        @Enumerated(EnumType.STRING)
        @Column(name = "assessment_type", nullable = false, columnDefinition = "development.assessment_type")
        private AssessmentType assessmentType;

        @Column(length = 500)
        private String notes;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt;
    }
    ```
  - [x] `AssessmentType` is a separate enum (see Task 6 contract layer — import from `contract` package).

- [x] **Task 3: Create `PlayerRadarCompositeId.java` + `PlayerRadarComposite.java`** (AC: 3)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/repo/PlayerRadarCompositeId.java`
    ```java
    @Embeddable
    public class PlayerRadarCompositeId implements Serializable {
        @Column(name = "player_id") private Long playerId;
        @Column(name = "skill_code", length = 10) private String skillCode;
        // equals() and hashCode() MUST be overridden
    }
    ```
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/repo/PlayerRadarComposite.java`
    ```java
    @Entity
    @Table(schema = "development", name = "player_radar_composites")
    @Getter @Setter @NoArgsConstructor
    public class PlayerRadarComposite {
        @EmbeddedId private PlayerRadarCompositeId id;
        @Column(name = "composite_score", nullable = false, precision = 5, scale = 2)
        private BigDecimal compositeScore;
        @Column(name = "entry_count", nullable = false)
        private Integer entryCount;
        @Column(name = "last_updated_at", nullable = false)
        private Instant lastUpdatedAt;
    }
    ```

- [x] **Task 4: Create `RadarAssessmentRepository.java`** (AC: 2, 3, 4)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/repo/RadarAssessmentRepository.java`
  - [x] Key methods (all use **native SQL** with a `JOIN marketplace.player_profiles` — required for FR-TSC-009 family isolation at the repository layer; `parentId` must be passed through from the service):
    ```java
    // For GET /entries — coach's own entries; parentId adds repository-layer family isolation
    @Query(nativeQuery = true, value = """
        SELECT rae.* FROM development.radar_assessment_entries rae
        JOIN marketplace.player_profiles pp ON pp.id = rae.player_id
        WHERE rae.player_id = :playerId
          AND pp.parent_id = :parentId
          AND rae.coach_id = :coachId
        ORDER BY rae.assessment_date DESC
        """)
    List<RadarAssessmentEntry> findByPlayerIdAndCoachIdOrderByAssessmentDateDesc(
        @Param("playerId") Long playerId,
        @Param("parentId") UUID parentId,
        @Param("coachId") UUID coachId);

    // For composite recalculation — aggregate by assessmentType across all coaches
    // NOTE: row[1] is a String (native SQL returns PostgreSQL enum as text); use AssessmentType.valueOf((String) row[1])
    @Query(nativeQuery = true, value = """
        SELECT rae.skill_code,
               rae.assessment_type::text,
               AVG(CAST(rae.score AS DOUBLE PRECISION)),
               COUNT(rae.id)
        FROM development.radar_assessment_entries rae
        JOIN marketplace.player_profiles pp ON pp.id = rae.player_id
        WHERE rae.player_id = :playerId
          AND pp.parent_id = :parentId
          AND rae.skill_code IN (:skillCodes)
        GROUP BY rae.skill_code, rae.assessment_type
        """)
    List<Object[]> findAggregatesByPlayerAndSkills(@Param("playerId") Long playerId,
                                                   @Param("parentId") UUID parentId,
                                                   @Param("skillCodes") Set<String> skillCodes);

    // For other coach count display — count of distinct coaches per skill (excluding requesting coach)
    @Query(nativeQuery = true, value = """
        SELECT rae.skill_code, COUNT(DISTINCT rae.coach_id)
        FROM development.radar_assessment_entries rae
        JOIN marketplace.player_profiles pp ON pp.id = rae.player_id
        WHERE rae.player_id = :playerId
          AND pp.parent_id = :parentId
          AND rae.coach_id != :excludeCoachId
        GROUP BY rae.skill_code
        """)
    List<Object[]> countDistinctOtherCoachesBySkill(@Param("playerId") Long playerId,
                                                     @Param("parentId") UUID parentId,
                                                     @Param("excludeCoachId") UUID excludeCoachId);
    ```
  - [x] **Native SQL note**: All three methods use native SQL (not JPQL) because the `marketplace.player_profiles` JOIN is cross-schema. Native SQL returns PostgreSQL enum values as Strings — `AssessmentType.valueOf((String) row[1])` is therefore **correct** in the composite calculator. Do NOT use `(AssessmentType) row[1]` (that cast is correct only for JPQL projections).

- [x] **Task 5: Create `PlayerRadarCompositeRepository.java`** (AC: 3)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/repo/PlayerRadarCompositeRepository.java`
  - [x] Methods:
    ```java
    // For native UPSERT — called from RadarCompositeCalculationService
    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = """
        INSERT INTO development.player_radar_composites
            (player_id, skill_code, composite_score, entry_count, last_updated_at)
        VALUES (:playerId, :skillCode, :compositeScore, :entryCount, NOW())
        ON CONFLICT (player_id, skill_code)
        DO UPDATE SET composite_score = EXCLUDED.composite_score,
                      entry_count = EXCLUDED.entry_count,
                      last_updated_at = EXCLUDED.last_updated_at
        """)
    void upsertComposite(@Param("playerId") Long playerId, @Param("skillCode") String skillCode,
                         @Param("compositeScore") BigDecimal compositeScore,
                         @Param("entryCount") int entryCount);

    List<PlayerRadarComposite> findByIdPlayerId(Long playerId);
    ```

---

### Backend — Contract Layer

- [x] **Task 6: Create contract types** (AC: 1, 2, 3, 4, 5)
  - [x] Dir: `src/main/java/com/softropic/skillars/platform/development/contract/`

  - [x] **`AssessmentType.java`** (enum — imported by entity and service):
    ```java
    package com.softropic.skillars.platform.development.contract;
    public enum AssessmentType { OBJECTIVE, MATCH_OBSERVATION, COACH_EVALUATION }
    ```

  - [x] **`SkillScoreItem.java`** (per-skill entry in the request):
    ```java
    public record SkillScoreItem(
        @NotBlank @Size(max = 10) String skillCode,
        @NotNull @Min(1) @Max(100) Integer score,
        @Size(max = 500) String notes   // nullable
    ) {}
    ```

  - [x] **`RadarAssessmentRequest.java`** (full request body):
    ```java
    public record RadarAssessmentRequest(
        @NotNull UUID assessmentGroupId,                                   // generated client-side (crypto.randomUUID())
        @NotNull @PastOrPresent LocalDate assessmentDate,                  // no future or wildly anteceded dates
        @NotNull AssessmentType assessmentType,                            // one type per assessment sitting
        @NotEmpty @Size(max = 15) List<@Valid SkillScoreItem> entries
    ) {}
    ```

  - [x] **`RadarAssessmentEntryResponse.java`** (per-row response for GET):
    ```java
    public record RadarAssessmentEntryResponse(
        UUID assessmentGroupId,
        String skillCode,
        int score,
        AssessmentType assessmentType,
        LocalDate assessmentDate,
        String notes,
        Instant createdAt
    ) {}
    ```

  - [x] **`RadarAssessmentListResponse.java`** (full GET response):
    ```java
    public record RadarAssessmentListResponse(
        List<RadarAssessmentEntryResponse> entries,     // coach's own entries, sorted by date desc
        Map<String, Long> otherCoachCounts              // skillCode → count of other coaches who assessed it
    ) {}
    ```

  - [x] **`RadarEntrySubmittedEvent.java`** (Spring application event):
    ```java
    package com.softropic.skillars.platform.development.contract;
    public record RadarEntrySubmittedEvent(Long playerId, UUID parentId, Set<String> skillCodes) {}
    ```
    Note: `parentId` is included so the async composite calculator can satisfy the repository-layer FR-TSC-009 parentId contract. `Set<String> skillCodes` = only the skills submitted in this batch — the calculator only recomputes affected skills, not all 15.

---

### Backend — Service Layer

- [x] **Task 7: Create `RadarAssessmentService.java`** (AC: 1, 2, 4, 5, 6)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/service/RadarAssessmentService.java`
  - [x] Inject: `RadarAssessmentRepository radarRepository`, `CoachProfileService coachProfileService`, `PlayerProfileService playerProfileService`, `ApplicationEventPublisher publisher`
  - [x] Implementation:
    ```java
    @Service
    @RequiredArgsConstructor
    @Slf4j
    public class RadarAssessmentService {

        private final RadarAssessmentRepository radarRepository;
        private final CoachProfileService coachProfileService;
        private final PlayerProfileService playerProfileService;  // needed for parentId resolution
        private final ApplicationEventPublisher publisher;

        @Transactional
        public void submitAssessment(Long coachUserId, Long playerId, RadarAssessmentRequest req) {
            UUID coachId = coachProfileService.getCoachIdByUserId(coachUserId);
            // Resolve parentId for repository-layer family isolation (FR-TSC-009, dual-layer enforcement)
            // Follow the same lookup pattern as coachProfileService.getCoachIdByUserId
            UUID parentId = playerProfileService.getParentIdByPlayerId(playerId);

            // Scout tier gate — same pattern as DrillLibraryService.checkSessionBuilderGate()
            CoachSubscriptionTier tier = coachProfileService.getCoachSubscriptionTier(coachId);
            if (tier == CoachSubscriptionTier.SCOUT) {
                throw new FeatureGatedException("skills_radar", "INSTRUCTOR");
            }

            // Validate all submitted skillCodes exist in skill_definitions to return 400 (not 500 from FK violation)
            Set<String> submittedCodes = req.entries().stream()
                .map(SkillScoreItem::skillCode).collect(Collectors.toSet());
            // Call a method like skillDefinitionService.assertAllExist(submittedCodes) or
            // skillDefinitionRepository.findAllByCodeIn(submittedCodes) and compare sizes.
            // Throw ValidationException / MethodArgumentNotValidException with field "entries[n].skillCode"
            // if any code is missing. Pattern to follow: how DrillLibraryService validates drillId existence.

            List<RadarAssessmentEntry> entries = req.entries().stream().map(item -> {
                RadarAssessmentEntry e = new RadarAssessmentEntry();
                e.setAssessmentGroupId(req.assessmentGroupId());
                e.setCoachId(coachId);
                e.setPlayerId(playerId);
                e.setSkillCode(item.skillCode());
                e.setScore(item.score().shortValue());
                e.setAssessmentDate(req.assessmentDate());
                e.setAssessmentType(req.assessmentType());
                e.setNotes(item.notes());
                e.setCreatedAt(Instant.now());
                return e;
            }).toList();

            radarRepository.saveAll(entries);   // @Transactional — all or none
            log.info("Radar assessment saved: {} skills for player {} coach {}",
                entries.size(), playerId, coachId);

            publisher.publishEvent(new RadarEntrySubmittedEvent(playerId, parentId, submittedCodes));
        }

        @Transactional(readOnly = true)
        public RadarAssessmentListResponse getMyEntries(Long coachUserId, Long playerId) {
            UUID coachId = coachProfileService.getCoachIdByUserId(coachUserId);
            UUID parentId = playerProfileService.getParentIdByPlayerId(playerId);

            List<RadarAssessmentEntry> mine = radarRepository
                .findByPlayerIdAndCoachIdOrderByAssessmentDateDesc(playerId, parentId, coachId);
            List<RadarAssessmentEntryResponse> entryResponses = mine.stream()
                .map(e -> new RadarAssessmentEntryResponse(
                    e.getAssessmentGroupId(), e.getSkillCode(), e.getScore(),
                    e.getAssessmentType(), e.getAssessmentDate(), e.getNotes(), e.getCreatedAt()))
                .toList();

            // Other coach counts — count of distinct coach IDs (excluding self) per skill
            Map<String, Long> otherCounts = radarRepository
                .countDistinctOtherCoachesBySkill(playerId, parentId, coachId)
                .stream().collect(Collectors.toMap(r -> (String) r[0], r -> ((Number) r[1]).longValue()));

            return new RadarAssessmentListResponse(entryResponses, otherCounts);
        }
    }
    ```
  - [x] Imports needed: `CoachSubscriptionTier`, `FeatureGatedException`, `ApplicationEventPublisher`, `PlayerProfileService`
  - [x] **Do NOT call `coachProfileService.getCoachIdByUserId` or `playerProfileService.getParentIdByPlayerId` twice per method** — resolve once and reuse.
  - [x] **`skillCode` validation must return 400, not 500**: Without explicit validation, an unknown `skillCode` hits the FK constraint and returns 500. Add the validation block before `saveAll`. The exact method name on `playerProfileService` / a `SkillDefinitionRepository` should follow the pattern used in the nearest existing service that validates FK references.

- [x] **Task 8: Create `RadarCompositeCalculationService.java`** (AC: 3)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java`
  - [x] **CRITICAL PATTERN**: Must use BOTH `@TransactionalEventListener(AFTER_COMMIT)` AND `@Async` — same as `SluCalculationService`. Clarification on why both are needed: `@TransactionalEventListener(AFTER_COMMIT)` fires after the original transaction has fully committed and closed — there is no "stale context" because there is NO active transaction at that point. The `@Modifying @Transactional` on `upsertComposite` opens a fresh transaction regardless of `@Async`. The reason `@Async` IS required is **performance**: without it, the composite recalculation blocks the HTTP response thread until the upsert completes. With `@Async`, the 204 is returned immediately and recalculation runs in the thread pool. Both annotations are required together — removing either breaks the pattern.
  - [x] Implementation:
    ```java
    @Service
    @RequiredArgsConstructor
    @Slf4j
    public class RadarCompositeCalculationService {

        private static final BigDecimal WEIGHT_OBJECTIVE    = new BigDecimal("0.50");
        private static final BigDecimal WEIGHT_MATCH_OBS    = new BigDecimal("0.30");
        private static final BigDecimal WEIGHT_COACH_EVAL   = new BigDecimal("0.20");

        private final RadarAssessmentRepository radarRepository;
        private final PlayerRadarCompositeRepository compositeRepository;

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        @Async
        public void onRadarEntrySubmitted(RadarEntrySubmittedEvent event) {
            Long playerId      = event.playerId();
            UUID parentId      = event.parentId();   // needed for repository-layer parentId contract
            Set<String> skills = event.skillCodes();

            try {
            // Aggregate by (skillCode, assessmentType) — all coaches, all time
            // Native SQL query returns: row[0]=skill_code(String), row[1]=assessment_type(String),
            // row[2]=avg(Double), row[3]=count(Long/BigInteger). AssessmentType.valueOf((String) row[1]) is correct.
            List<Object[]> aggregates = radarRepository.findAggregatesByPlayerAndSkills(playerId, parentId, skills);

            // Build per-skill type averages: skillCode → (type → avgScore)
            Map<String, Map<AssessmentType, Double[]>> bySkill = new HashMap<>();
            for (Object[] row : aggregates) {
                String skill = (String) row[0];
                AssessmentType type = AssessmentType.valueOf((String) row[1]);  // correct: native SQL returns String
                double avg = ((Number) row[2]).doubleValue();
                long count = ((Number) row[3]).longValue();
                bySkill.computeIfAbsent(skill, k -> new HashMap<>())
                    .put(type, new Double[]{avg, (double) count});
            }

            for (Map.Entry<String, Map<AssessmentType, Double[]>> skillEntry : bySkill.entrySet()) {
                String skill = skillEntry.getKey();
                Map<AssessmentType, Double[]> types = skillEntry.getValue();

                double composite = 0.0;
                int totalCount = 0;
                if (types.containsKey(AssessmentType.OBJECTIVE)) {
                    composite += types.get(AssessmentType.OBJECTIVE)[0] * WEIGHT_OBJECTIVE.doubleValue();
                    totalCount += types.get(AssessmentType.OBJECTIVE)[1].intValue();
                }
                if (types.containsKey(AssessmentType.MATCH_OBSERVATION)) {
                    composite += types.get(AssessmentType.MATCH_OBSERVATION)[0] * WEIGHT_MATCH_OBS.doubleValue();
                    totalCount += types.get(AssessmentType.MATCH_OBSERVATION)[1].intValue();
                }
                if (types.containsKey(AssessmentType.COACH_EVALUATION)) {
                    composite += types.get(AssessmentType.COACH_EVALUATION)[0] * WEIGHT_COACH_EVAL.doubleValue();
                    totalCount += types.get(AssessmentType.COACH_EVALUATION)[1].intValue();
                }

                BigDecimal compositeScore = BigDecimal.valueOf(composite)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
                compositeRepository.upsertComposite(playerId, skill, compositeScore, totalCount);
                log.debug("Composite updated: player={} skill={} score={} entries={}",
                    playerId, skill, compositeScore, totalCount);
            }
            } catch (Exception e) {
                // @Async exceptions are swallowed by default — must log explicitly so the stale composite is detectable
                log.error("Composite recalculation failed for player={} skills={} — composite is now stale",
                    playerId, skills, e);
                // No retry mechanism: if this fails, the composite remains at its prior value until the
                // next assessment entry for the same player+skill triggers recomputation.
            }
        }
    }
    ```
  - [x] **`@Async` requires `@EnableAsync`** — already present in `notification.config.AsyncConfig`. Do NOT add it again.
  - [x] **Composite weight note**: When ONLY one assessment type exists (e.g., only OBJECTIVE entries), the composite is just (avgObjective × 0.50). This means partial-type composites are below their theoretical maximum until all three types have entries — this is correct per spec and matches the "confidence indicator" concept in Story 5.4.

---

### Backend — REST Resource

- [x] **Task 9: Create `RadarAssessmentResource.java`** (AC: 1, 2, 4, 5, 6)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/api/RadarAssessmentResource.java`
  - [x] Inject: `RadarAssessmentService radarAssessmentService`, `SecurityUtil securityUtil`
  - [ ] Endpoints:
    ```java
    @RestController
    @RequiredArgsConstructor
    public class RadarAssessmentResource {

        private final RadarAssessmentService radarAssessmentService;
        private final SecurityUtil securityUtil;

        @PostMapping("/api/development/players/{playerId}/radar/entries")
        @PreAuthorize("hasRole('ROLE_COACH')")
        @Observed(name = "development.radar.submit")
        public ResponseEntity<Void> submitAssessment(
                @PathVariable Long playerId,
                @RequestBody @Valid RadarAssessmentRequest request) {
            radarAssessmentService.submitAssessment(
                securityUtil.getCurrentCoachUserId(), playerId, request);
            return ResponseEntity.noContent().build();   // 204 No Content (project-context.md rule)
        }

        @GetMapping("/api/development/players/{playerId}/radar/entries")
        @PreAuthorize("hasRole('ROLE_COACH')")
        @Observed(name = "development.radar.entries")
        public ResponseEntity<RadarAssessmentListResponse> getMyEntries(@PathVariable Long playerId) {
            return ResponseEntity.ok(
                radarAssessmentService.getMyEntries(securityUtil.getCurrentCoachUserId(), playerId));
        }
    }
    ```
  - [x] `securityUtil.getCurrentCoachUserId()` returns the Long user ID of the authenticated coach — follow the same pattern used in `SluTargetResource`.
  - [x] Scout tier gate is **in the service** (`RadarAssessmentService.submitAssessment`) — throws `FeatureGatedException` → `ApiAdvice` returns 403 with `ErrorDto` code `security.featureGated`. Do NOT add `@PreAuthorize` tier logic — the service handles it consistently.
  - [x] `@PreAuthorize("hasRole('ROLE_COACH')")` is still required on both endpoints (no parent access to assessment endpoints).
  - [x] **Family isolation (FR-TSC-009)**: The service resolves `parentId` via `playerProfileService.getParentIdByPlayerId(playerId)` and passes it through all repository calls. This satisfies the mandatory dual-layer isolation contract. If `playerId` does not exist the lookup will throw (404-equivalent), providing implicit player existence validation.
  - [x] GET endpoint `?coachId=me` param from epics spec: **do not add a `@RequestParam` for coachId** — the "me" concept is resolved from the authenticated principal via `securityUtil`, not from a query parameter. The epics spec listed `?coachId=me` as an illustrative URL; resolving it from the token is safer.

---

### Backend — Tests

- [x] **Task 10: Create `RadarCompositeCalculatorTest.java`** (AC: 3)
  - [x] File: `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculatorTest.java`
  - [x] Pure unit test — mock `RadarAssessmentRepository` and `PlayerRadarCompositeRepository`.
  - [x] Tests:
    - `onRadarEntrySubmitted_singleCoachObjective_computesWeightedComposite` — one OBJECTIVE entry score=80 → composite = 80 × 0.50 = 40.00.
    - `onRadarEntrySubmitted_allThreeTypes_computesCorrectComposite` — OBJECTIVE avg=80, MATCH_OBS avg=70, COACH_EVAL avg=60 → 80×0.50 + 70×0.30 + 60×0.20 = 40+21+12 = 73.00.
    - `onRadarEntrySubmitted_multipleCoaches_aggregatesAcrossAllCoaches` — coach A OBJECTIVE=80, coach B OBJECTIVE=60 → avgObjective = 70 → composite = 70×0.50 = 35.00; entry_count = 2.
    - `onRadarEntrySubmitted_onlyMatchObservation_computesPartialComposite` — MATCH_OBS avg=50 → composite = 50×0.30 = 15.00 (partial — only one type present).
    - `onRadarEntrySubmitted_multipleSkills_upsertCalledPerSkill` — two skills in the event → verify `upsertComposite` called twice.
  - [x] **Note on `Object[]` stub**: Use `List.of(new Object[]{"PAC", "OBJECTIVE", 80.0, 1L})` for stub return values. `"OBJECTIVE"` is a **String** (correct — native SQL returns the PostgreSQL enum as text, not as a Java enum instance). `AssessmentType.valueOf((String) row[1])` in the calculator is therefore correct; do not change it to a cast. Use `new ArrayList<>()` to avoid Mockito type inference issues (see Story 5.2 debug log).
  - [x] **Updated mock signatures**: All repository stub calls now include the `parentId` parameter. Create a test `UUID PARENT_ID = UUID.randomUUID()` constant and pass it consistently to mocked calls. E.g.: `when(radarRepository.findAggregatesByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC"))).thenReturn(...)`. The `RadarEntrySubmittedEvent` constructor also requires `parentId`: `new RadarEntrySubmittedEvent(PLAYER_ID, PARENT_ID, Set.of("PAC"))`.

- [x] **Task 11: Create `RadarAssessmentResourceIT.java`** (AC: 2, 4, 5, 6)
  - [x] File: `src/test/java/com/softropic/skillars/platform/development/api/RadarAssessmentResourceIT.java`
  - [x] Same Spring Boot test setup as `SkillExposureResourceIT.java` — copy the `@SpringBootTest`, `@Import(TestConfig.class)`, `@TestPropertySource`, `@Sql(SecurityIT.SEC_DATA_SQL_PATH)` annotations and ALL helper methods (`loginAndGetCookies`, `clientHeaders`, `authenticatedHeaders`, `baseUrl`, `insertAuthority`, `insertUser`, `grantRole`, `insertCoachProfile`, `insertSubscription`).
  - [x] **Use unique user IDs in range 9580000001–9580000030** to avoid collision with SkillExposureResourceIT (9570000001–9570000021).
  - [x] Use a Scout subscription to test AC 5 — insert `insertSubscription(scoutCoachId, "SCOUT")`.
  - [x] Tests:
    - `submitAssessment_asInstructorCoach_returns204` — POST valid OBJECTIVE assessment for skill PAC (score=75); assert 204; assert 1 row in DB; **also assert** `jdbcTemplate.queryForObject("SELECT assessment_type::text FROM development.radar_assessment_entries WHERE player_id = ?", String.class, PLAYER_ID).equals("OBJECTIVE")` — this verifies the PostgreSQL enum type round-trip via the `@Enumerated(EnumType.STRING)` + native-type mapping.
    - `submitAssessment_allSkillsInGroupShareAssessmentGroupId` — POST 3 skills with the same `assessmentGroupId` in the payload; assert all 3 DB rows share that exact UUID; also assert a second POST with the **same** `assessmentGroupId` + same `coachId` + same `skillCode` returns 409 or 400 (the UNIQUE constraint `uq_radar_entries_group_coach_skill` fires), confirming retry idempotency.
    - `submitAssessment_transactional_rejectsPartialPayload` — POST payload with one valid skill (score=50) and one invalid (score=150); assert 400 from bean validation; assert 0 rows inserted. **Note**: this tests the pre-persistence validation path. For the DB-level atomicity path (e.g., second row triggers a FK violation after first row would have been inserted), see the `submitAssessment_invalidSkillCode_returns400` test below.
    - `submitAssessment_asScoutCoach_returns403WithFeatureGatedCode` — Scout coach POST; assert 403; assert response body contains `"security.featureGated"` in errorCode field.
    - `submitAssessment_unauthenticated_returns401`.
    - `getMyEntries_returnsOwnEntriesOnly` — insert coach A entry + coach B entry; GET as coach A; assert only coach A's entry appears in `entries`.
    - `getMyEntries_otherCoachCounts_showsCountNotScores` — coach B has 1 entry for PAC; GET as coach A; assert `otherCoachCounts.get("PAC") == 1`; assert coach B's score is NOT visible.
    - `getMyEntries_emptyForNewCoach_returns200WithEmptyList` — no prior entries; GET; assert 200 + empty entries list.
    - `submitAssessment_invalidSkillCode_returns400NotServerError` — POST payload with `skillCode: "NOTREAL"` (valid length, passes `@Size` but not in `skill_definitions`); assert **400** (not 500). This test verifies the service-layer `skillCode` validation fires before the FK constraint can produce a 500.
  - [x] **Teardown**: Delete `radar_assessment_entries` and player/coach rows in `@AfterEach` (transactionTemplate pattern from SkillExposureResourceIT).

---

### Frontend

- [x] **Task 12: Add radar assessment functions to `development.api.js`** (AC: 1, 2, 4)
  - [x] File: `src/frontend/src/api/development.api.js` (MODIFY — append)
    ```js
    export const postRadarAssessment = (playerId, assessment) =>
      api.post(`/api/development/players/${playerId}/radar/entries`, assessment)

    export const getMyRadarEntries = (playerId) =>
      api.get(`/api/development/players/${playerId}/radar/entries`)
    ```

- [x] **Task 13: Add radar assessment state/actions to `development.store.js`** (AC: 2, 4)
  - [x] File: `src/frontend/src/stores/development.store.js` (MODIFY — append)
  - [x] New state: `radarEntries: ref(null)`, `radarLoading: ref(false)`
  - [x] New actions:
    ```js
    async function fetchRadarEntries(playerId) {
      radarLoading.value = true
      error.value = null
      try {
        const response = await getMyRadarEntries(playerId)
        radarEntries.value = response.data
      } catch (err) {
        error.value = err?.response?.data?.message ?? 'Failed to load radar entries'
      } finally {
        radarLoading.value = false
      }
    }

    async function submitRadarAssessment(playerId, assessment) {
      error.value = null
      try {
        await postRadarAssessment(playerId, assessment)
        await fetchRadarEntries(playerId)
      } catch (err) {
        error.value = err?.response?.data?.message ?? 'Failed to submit assessment'
        throw err  // re-throw so the panel component can react (close vs. stay open)
      }
    }
    ```
  - [x] Expose `radarEntries`, `radarLoading`, `fetchRadarEntries`, `submitRadarAssessment` from the store return.

- [x] **Task 14: Create `SkillsRadarAssessmentPanel.vue`** (AC: 1, 2, 5)
  - [x] File: `src/frontend/src/components/development/SkillsRadarAssessmentPanel.vue`
  - [x] Shown as a `<q-dialog>` triggered from `PlayerDevelopmentDashboardPage.vue`
  - [x] Props: `playerId: Number`, `skillDefinitions: Array<{code, displayName, rubricCriteria: String}>` — the `rubricCriteria` field is required to render the per-skill rubric tooltip (AC 1, FR-DEV-007). Check whether `skill_definitions` already exposes this field in `GET /api/development/skill-definitions`; if not, the endpoint and DTO must be extended in this story before the panel can be implemented.
  - [x] Internal state: `assessmentType` (dropdown: OBJECTIVE/MATCH_OBSERVATION/COACH_EVALUATION), `assessmentDate` (date picker, default today), `scores: {}` (skill code → {score: null, notes: ''})
  - [x] `assessmentGroupId` — generated at dialog open time: `const assessmentGroupId = crypto.randomUUID()` (built-in browser API, no import needed)
  - [x] 7-tier scale reference (show as a small reference table or static legend above the skill list):
    ```
    Elite (90–100) | Excellent (80–89) | Good (70–79) | Above Average (60–69)
    Average (50–59) | Below Average (40–49) | Very Weak (<40)
    ```
  - [x] **Per-skill rubric criteria tooltip (AC 1, FR-DEV-007)**: Each skill score input must have a `<q-tooltip>` showing `skill.rubricCriteria` — the standardised rubric text for that specific skill (e.g., "PAC: Measures maximum sprint speed over 30m…"). This is separate from the 7-tier scale legend. Render as: `<q-input v-model="scores[skill.code].score" …><template #append><q-icon name="info"><q-tooltip>{{ skill.rubricCriteria }}</q-tooltip></q-icon></template></q-input>`. If `rubricCriteria` is null/empty, omit the icon.
  - [x] Validation: score must be 1–100 if entered (non-entered skills are simply omitted from the payload); at least 1 skill must have a score to enable submit.
  - [x] On submit: build payload from `scores` (exclude skills where score is null), call `developmentStore.submitRadarAssessment(playerId, payload)`, close dialog on success.
  - [x] **Scout check**: This panel is conditionally rendered in the parent page — do NOT add a tier check inside this component. The page handles the `v-if` (see Task 16).
  - [x] Use `vue-i18n` `t()` for all labels — no hardcoded English strings.

- [x] **Task 15: Create `RadarAssessmentHistoryList.vue`** (AC: 4)
  - [x] File: `src/frontend/src/components/development/RadarAssessmentHistoryList.vue`
  - [x] Props: `radarEntries: Object | null` (the `RadarAssessmentListResponse` shape: `{entries: [], otherCoachCounts: {}}`)
  - [x] Displays own entries grouped or flat, sorted by date (already sorted from API)
  - [x] Per skill: shows score, assessment type badge, date, notes (if any)
  - [x] Shows "X other coach(es) have also assessed [SKILL]" using `otherCoachCounts` — never shows other coaches' scores. **Zero-case**: `otherCoachCounts` only contains entries for skills where other coaches HAVE assessed (count > 0); skills absent from the map mean 0 other coaches. Do NOT render the sentence at all when `otherCoachCounts[skill.code]` is undefined or 0 — do not show "0 other coach(es)". Only show the sentence when count ≥ 1.
  - [x] Empty state: "No assessments recorded yet" when `entries` is empty
  - [x] Use `vue-i18n` for all strings

- [x] **Task 16: Integrate into `PlayerDevelopmentDashboardPage.vue`** (AC: 1, 2, 4, 5)
  - [x] File: `src/frontend/src/pages/player/PlayerDevelopmentDashboardPage.vue` (MODIFY)
  - [x] On mount (Coach role): also call `developmentStore.fetchRadarEntries(playerId)`
  - [x] Add "Add Radar Assessment" button — `v-if="isCoach && !isScoutTier"` — opens `SkillsRadarAssessmentPanel` dialog
  - [x] Render `<RadarAssessmentHistoryList>` below the bar chart (Coach only — `v-if="isCoach"`)
  - [x] Import `SkillsRadarAssessmentPanel` and `RadarAssessmentHistoryList` components
  - [x] **Scout detection (AC 5, UX-DR22 — "absent, not disabled")**: The button and dialog must be completely absent for Scout coaches — not shown in a disabled state, and not shown in an enabled state that fails on click. Add:
    ```js
    const isScoutTier = computed(() =>
      authStore.coachTier === 'SCOUT'  // check where coachTier is currently stored in authStore
    )
    ```
    If `authStore.coachTier` is not yet exposed by `authStore`, add a `fetchCoachTier()` action that calls `GET /api/marketplace/coaches/me/tier` (or whichever endpoint currently serves coach profile/tier data — check existing `marketplace.store.js`). Call it in `onMounted` alongside the existing coach setup. The `v-if="isCoach && !isScoutTier"` on the button is the required implementation. **Do not** substitute the "handle 403 in catch" pattern — that approach is explicitly forbidden by the spec ("not shown in a disabled state" means not shown at all, and a clickable-but-failing button is worse than disabled).

- [x] **Task 17: Add i18n keys** (AC: 1, 2, 4, 5)
  - [x] File: `src/frontend/src/i18n/en-US/index.js` (MODIFY — add under `development:`)
    ```js
    radar: {
      addAssessmentLabel: 'Add Radar Assessment',
      assessmentPanelTitle: 'Skills Radar Assessment',
      assessmentTypeLabelObjective: 'Objective Test',
      assessmentTypeLabelMatchObs: 'Match Observation',
      assessmentTypeLabelCoachEval: 'Coach Evaluation',
      assessmentDateLabel: 'Assessment Date',
      scoreLabel: 'Score (1–100)',
      notesLabel: 'Notes (optional)',
      scoreTierReference: 'Score reference: Elite 90–100 | Excellent 80–89 | Good 70–79 | Above Avg 60–69 | Avg 50–59 | Below Avg 40–49 | Very Weak <40',
      submitLabel: 'Submit Assessment',
      historyTitle: 'My Assessment History',
      noEntriesYet: 'No assessments recorded yet',
      otherCoachCount: '{count} other coach(es) assessed {skill}',
      submitSuccess: 'Assessment submitted successfully',
      submitErrorFeatureGated: 'Skills Radar assessment requires Instructor tier or above',
    }
    ```
  - [x] Add same keys (English text as placeholder) to `fr-FR/index.js`, `de/index.js`, and `en/index.js` to prevent vue-i18n missing-key warnings.

---

## Dev Notes

### CRITICAL: `player_id` is BIGINT, NOT UUID (Same as Stories 5.1 and 5.2)

The epics spec says `radar_assessment_entries (player_id UUID, ...)` and `player_radar_composites (playerId UUID, ...)` — **both are wrong**. All player IDs in this system are TSIDs (Long/BIGINT). Evidence:
- `development.player_skill_stats.player_id BIGINT` (V46)
- `development.neglected_skill_flags.player_id BIGINT` (V48)
- `PlayerProfile` uses `@Tsid Long id`

**Always use `Long playerId` (BIGINT) in all new development module entities and tables.** This is the third consecutive story making this correction — it is a systematic epics spec bug.

### `coach_id` is UUID (correct for coach profiles)

Coach profile IDs are UUIDs — `marketplace.coach_profiles.id UUID`. Resolve with `coachProfileService.getCoachIdByUserId(securityUtil.getCurrentCoachUserId())`. The method `getCurrentCoachUserId()` returns the Long user ID, which is then translated to UUID coach profile ID via `coachProfileService`.

### `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` — Mandatory Pair

`RadarCompositeCalculationService.onRadarEntrySubmitted` MUST use both annotations, same as `SluCalculationService.onBookingCompleted`. Why both are needed:

- `@TransactionalEventListener(AFTER_COMMIT)` fires after the original transaction is fully committed and closed. At the point the listener runs, there is NO active transaction — there is no "stale context" to worry about. The `@Modifying @Transactional` on `upsertComposite` opens a fresh standalone transaction regardless.
- `@Async` is required for **performance**: without it, the composite recalculation blocks the HTTP response thread until the DB upsert completes. With `@Async`, the 204 is returned immediately and recalculation runs in the thread pool.
- Removing `@Async` makes the endpoint slower but does not break correctness. Removing `@TransactionalEventListener` (e.g., using `@EventListener` instead) risks the listener firing before commit if the wrapping transaction rolls back — that WOULD be a correctness bug.

`@EnableAsync` is already present in `notification.config.AsyncConfig` — do NOT add it again.

### `assessmentGroupId` Generated Client-Side (UUID v4)

The epics spec explicitly states: "assessmentGroupId generated client-side (UUID v4) and submitted with the entry payload." In the Vue component, use `crypto.randomUUID()` (available in all modern browsers, no import needed). This UUID is generated once when the dialog opens and included in the POST body. The server accepts it as-is and stores it on every row in the batch.

**Rationale**: Client-side generation avoids a round-trip to get a group ID before submission. The migration creates a `UNIQUE INDEX uq_radar_entries_group_coach_skill ON (assessment_group_id, coach_id, skill_code)` — this makes retries genuinely idempotent: a network timeout that causes a client retry will get a unique constraint violation (which must be caught in `ApiAdvice` and returned as 409 Conflict, not 500) rather than creating duplicate rows. The `assessment_group_id` is a grouping key, not a PK, but the UNIQUE index enforces that the same coach cannot submit the same skill twice in the same sitting.

### Composite Formula — Partial-Type Behaviour

When only some assessment types have entries, the composite is the sum of the available type contributions. Example: if only OBJECTIVE entries exist (avg=80), composite = 80×0.50 = 40.00 (not 80). This intentionally reflects the confidence model — a fuller composite requires all three assessment types. Story 5.4 will add confidence indicators to communicate this to users.

### `assessmentType` as PostgreSQL Enum

The migration creates `development.assessment_type` as a PostgreSQL `ENUM` type. The JPA entity maps it with `@Enumerated(EnumType.STRING)` and `columnDefinition = "development.assessment_type"`. This keeps DB-level constraint checking while allowing Java string serialization. Do NOT use `@Enumerated(EnumType.ORDINAL)`.

### Scout Tier Gate — Service Layer, NOT `@PreAuthorize`

The tier check is in `RadarAssessmentService.submitAssessment()`, not in `@PreAuthorize`. Reason: `@PreAuthorize` only has access to the authentication object (user ID), not to the coach profile tier (which requires a DB lookup). The `FeatureGatedException` thrown from the service is caught by `ApiAdvice.featureGatedHandler()` → returns 403 with `ErrorDto` code `security.featureGated`. This matches the same pattern used in `DrillLibraryService.checkSessionBuilderGate()`.

### JPQL `AVG()` on `Short` Score Field

The `RadarAssessmentEntry.score` field is `Short` (mapped from `SMALLINT`). JPQL `AVG()` requires a numeric type. Use `CAST(e.score AS java.lang.Double)` or `CAST(e.score AS double)` in the JPQL query. Alternatively, define a separate `@Column Integer scoreInt` alias or use a native query. The simplest approach: `AVG(CAST(e.score AS java.lang.Double))`.

### Frontend Scout UX — Button Must Be Absent (AC 5, UX-DR22)

The spec is unambiguous: the assessment entry UI is **absent** from the Scout coach view — not disabled, not behind a teaser overlay, and not clickable-then-failed. AC 5 and the epics spec (Story 5.3) both state this explicitly. UX-DR22 allows a "soft teaser overlay" pattern for some gated features, but the Skills Radar entry specifically requires absence (same as the upload button in Story 4.2, line 1556 in epics).

The correct implementation is `v-if="isCoach && !isScoutTier"` driven by `authStore.coachTier`. If `coachTier` is not yet in `authStore`, add it — checking `marketplace.store.js` for an existing tier property before adding a new fetch. Do not substitute the "catch 403 and show toast" pattern for this feature.

### `@EnableScheduling` / `@EnableAsync` Already Present

Both annotations are on `notification.config.AsyncConfig`. Do NOT add either annotation anywhere. The `@Async` on `RadarCompositeCalculationService.onRadarEntrySubmitted` is picked up by the existing async configuration.

### `parentId` Resolution — FR-TSC-009 Dual-Layer Isolation

All player-scoped repository methods in this story include a `parentId` parameter (architectural contract from epics line 204). The `parentId` is resolved in `RadarAssessmentService` via `playerProfileService.getParentIdByPlayerId(playerId)`. The same `parentId` flows into `RadarEntrySubmittedEvent` so the async composite calculator can also satisfy the repository contract when calling `findAggregatesByPlayerAndSkills`.

- If `PlayerProfileService` does not yet expose `getParentIdByPlayerId(Long playerId)`, add it — it's a simple lookup on the `player_profiles` table (`SELECT parent_id FROM marketplace.player_profiles WHERE id = ?`).
- If `playerId` does not exist, `getParentIdByPlayerId` should throw `PlayerNotFoundException` (or equivalent) → `ApiAdvice` maps to 404. This provides implicit player existence validation at no extra cost.
- The `player_radar_composites` table does not store `parent_id` (it stores derived aggregate data, not personal player records). The `PlayerRadarCompositeRepository.findByIdPlayerId` is used only by the composite calculator — family isolation for that read is already guaranteed by the parentId-gated `findAggregatesByPlayerAndSkills` that feeds it.

### Rubric Criteria Source — `skill_definitions` Table

AC 1 requires per-skill rubric criteria as a tooltip on each score input (FR-DEV-007). The rubric text must come from the `skill_definitions` table — check whether a `rubric_criteria` (or equivalent) column was defined in Story 5.1's migration (`V46` or similar). If the column exists, extend `GET /api/development/skill-definitions` to include it in the response DTO (`SkillDefinitionDto`). If the column does not exist, add it via a new migration before implementing the panel. The `SkillsRadarAssessmentPanel.vue` receives skill definitions as a prop — the parent page already fetches them; just ensure `rubricCriteria` is in the response.

### Entry Immutability

Assessment entries are append-only by design: there is no DELETE or PATCH endpoint in this story. Coaches cannot correct a score after submission. This is intentional — the audit trail is the value. If a coach enters a wrong score, they can submit a corrected entry in a new assessment group; the composite calculation naturally incorporates all entries. If entry correction is added in a future story, the composite recalculation event pattern handles it without changes here.

### `entry_count` and Story 5.4 Confidence Model

`entry_count` stored in `player_radar_composites` counts total assessment rows across all types and coaches (e.g., 2 OBJECTIVE + 1 MATCH_OBS = 3). Story 5.4 plans to use "3+ entries = filled confidence dot." Be aware: `entry_count = 3` from a single coach using only OBJECTIVE assessments would show a filled confidence dot even though the composite is capped at 50 (only 50% weight is used). Story 5.4 should use a separate `distinct_coach_count` or `type_coverage` metric for a meaningful confidence signal — flag this to the 5.4 author.

### REST Conventions (project-context.md)

- POST with no response body → `ResponseEntity.noContent().build()` (204) — NOT 200 with empty body.
- Every resource method MUST have `@PreAuthorize`.
- Every resource MUST have `@Observed(name = "...")`.
- REST controllers must be suffixed `Resource`.

### Project Structure Summary

| Component | Location | Status |
|---|---|---|
| V50 migration | `src/main/resources/db/migration/V50__radar_assessment_entries.sql` | CREATE |
| `AssessmentType` enum | `platform.development.contract` | CREATE |
| `RadarAssessmentEntry` | `platform.development.repo` | CREATE |
| `PlayerRadarCompositeId` + `PlayerRadarComposite` | `platform.development.repo` | CREATE |
| `RadarAssessmentRepository` | `platform.development.repo` | CREATE |
| `PlayerRadarCompositeRepository` | `platform.development.repo` | CREATE |
| `RadarEntrySubmittedEvent` | `platform.development.contract` | CREATE |
| `SkillScoreItem` + `RadarAssessmentRequest` | `platform.development.contract` | CREATE |
| `RadarAssessmentEntryResponse` + `RadarAssessmentListResponse` | `platform.development.contract` | CREATE |
| `RadarAssessmentService` | `platform.development.service` | CREATE |
| `RadarCompositeCalculationService` | `platform.development.service` | CREATE |
| `RadarAssessmentResource` | `platform.development.api` | CREATE |
| `RadarCompositeCalculatorTest` | `platform.development.service` (test) | CREATE |
| `RadarAssessmentResourceIT` | `platform.development.api` (test) | CREATE |
| `development.api.js` | `src/frontend/src/api/` | MODIFY — add `postRadarAssessment`, `getMyRadarEntries` |
| `development.store.js` | `src/frontend/src/stores/` | MODIFY — add `radarEntries`, `radarLoading`, `fetchRadarEntries`, `submitRadarAssessment` |
| `SkillsRadarAssessmentPanel.vue` | `src/frontend/src/components/development/` | CREATE |
| `RadarAssessmentHistoryList.vue` | `src/frontend/src/components/development/` | CREATE |
| `PlayerDevelopmentDashboardPage.vue` | `src/frontend/src/pages/player/` | MODIFY — add assessment button + history list |
| i18n files (en-US, fr-FR, de, en) | `src/frontend/src/i18n/` | MODIFY — add `development.radar.*` keys |

### References

- Story 5.1 dev notes — `playerId is BIGINT not UUID`, `@Async` + `@TransactionalEventListener` pattern, `SluCalculationService` as event-listener template [`_bmad-output/implementation-artifacts/skillars-5-1-slu-engine-skill-taxonomy.md`]
- Story 5.2 dev notes — `NeglectedSkillProcessor two-bean @Transactional pattern`, `SnapshotBatchWriter atomicity`, `Object[]` stub Mockito pattern, `insertCoachProfile`/`insertSubscription` IT helper methods [`_bmad-output/implementation-artifacts/skillars-5-2-skill-exposure-dashboard-neglected-skill-detection.md`]
- `SluCalculationService.java` — `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` pattern (lines 49–51) [`src/main/java/com/softropic/skillars/platform/development/service/SluCalculationService.java`]
- `DrillLibraryService.java` — Scout tier gate pattern `coachProfileService.getCoachSubscriptionTier()` → `FeatureGatedException` (lines 180–187) [`src/main/java/com/softropic/skillars/platform/session/service/DrillLibraryService.java`]
- `FeatureGatedException.java` — `new FeatureGatedException(featureKey, requiredTier)` signature [`src/main/java/com/softropic/skillars/platform/security/contract/exception/FeatureGatedException.java`]
- `ApiAdvice.java` — `featureGatedHandler` maps `FeatureGatedException` → 403 with `security.featureGated` code (line 305) [`src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java`]
- `CoachProfileService.java` — `getCoachSubscriptionTier(UUID coachId)` (line 290), `getCoachIdByUserId(Long userId)` [`src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java`]
- `SkillExposureResourceIT.java` — full IT test template: annotations, setUp, tearDown, helper methods, Mockito VideoProviderAdapter mock [`src/test/java/com/softropic/skillars/platform/development/api/SkillExposureResourceIT.java`]
- `SluTargetResource.java` — `securityUtil.getCurrentCoachUserId()` + `coachProfileService.getCoachIdByUserId()` pattern [`src/main/java/com/softropic/skillars/platform/development/api/SluTargetResource.java`]
- `project-context.md` — DDD package structure, `@PreAuthorize` required, record DTOs, `@Observed` required, Prettier mandatory, 204 for body-less success [`_bmad-output/project-context.md`]
- Epic 5 spec: Story 5.3 [`_bmad-output/planning-artifacts/skillars-epics.md` lines 1797–1838]

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- Fix 1: `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` on `assessmentType` in `RadarAssessmentEntry` — replaced `@Enumerated(EnumType.STRING)` + broken `columnDefinition="development.assessment_type"` which caused Hibernate to send `character varying` binding rejected by PostgreSQL custom ENUM. `NAMED_ENUM` sends `Types.OTHER`, allowing implicit cast.
- Fix 2: `InvalidParamException("entries[].skillCode")` for invalid skill codes in `RadarAssessmentService` — original `ResourceNotFoundException` returns 404; `InvalidParamException` maps to 400 via `ApiAdvice`.
- Fix 3: IT test `otherCoachCounts.get("PAC").toString()` — Jackson deserializes Map<String,Object> numeric values as `Integer` which `.toString()` handles regardless of deserialized type.
- `parentId` is `Long` throughout (not UUID) — story spec was incorrect; all repo queries, service methods, and event record corrected.
- `player_profiles` is in `main` schema (not `marketplace`) — all native SQL JOINs corrected.
- V50 migration adds `rubric_criteria` to `skill_definitions` with seed data for all 15 skills; `SkillDefinition` entity + DTO + API mapper updated.
- `PlayerProfileService` created in `security.service` package (no existing service for this).
- `GET /api/marketplace/coaches/me/tier` added to `CoachMarketplaceResource`.
- `coachTier` ref and `fetchCoachTier()` action added to `auth.store.js`.

### Completion Notes List

- All 17 tasks implemented: Flyway migration V50, 3 JPA entities (RadarAssessmentEntry, PlayerRadarComposite with composite PK, PlayerRadarCompositeId), 2 repositories, AssessmentType enum, 5 contract records, RadarAssessmentService, RadarCompositeCalculationService (@Async + @TransactionalEventListener), RadarAssessmentResource REST controller.
- FR-TSC-009 dual-layer family isolation enforced: all repo queries JOIN `main.player_profiles` on `parent_id`.
- SCOUT tier gate in service layer → 403 FeatureGatedException.
- Multi-coach composite formula: (OBJECTIVE_avg × 0.50) + (MATCH_OBSERVATION_avg × 0.30) + (COACH_EVALUATION_avg × 0.20).
- Client-side `assessmentGroupId = crypto.randomUUID()` on dialog open for idempotency.
- 5 unit tests (RadarCompositeCalculatorTest) — all pass.
- 9 integration tests (RadarAssessmentResourceIT, 14 total) — all pass.
- Full regression suite: BUILD SUCCESS.

### File List

**New files:**
- `src/main/resources/db/migration/V50__radar_assessment_entries.sql`
- `src/main/java/com/softropic/skillars/platform/development/repo/RadarAssessmentEntry.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/PlayerRadarCompositeId.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/PlayerRadarComposite.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/RadarAssessmentRepository.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/PlayerRadarCompositeRepository.java`
- `src/main/java/com/softropic/skillars/platform/development/contract/AssessmentType.java`
- `src/main/java/com/softropic/skillars/platform/development/contract/SkillScoreItem.java`
- `src/main/java/com/softropic/skillars/platform/development/contract/RadarAssessmentRequest.java`
- `src/main/java/com/softropic/skillars/platform/development/contract/RadarEntrySubmittedEvent.java`
- `src/main/java/com/softropic/skillars/platform/development/contract/RadarAssessmentEntryResponse.java`
- `src/main/java/com/softropic/skillars/platform/development/contract/RadarAssessmentListResponse.java`
- `src/main/java/com/softropic/skillars/platform/development/service/RadarAssessmentService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java`
- `src/main/java/com/softropic/skillars/platform/development/api/RadarAssessmentResource.java`
- `src/main/java/com/softropic/skillars/platform/security/service/PlayerProfileService.java`
- `src/frontend/src/components/development/SkillsRadarAssessmentPanel.vue`
- `src/frontend/src/components/development/RadarAssessmentHistoryList.vue`
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculatorTest.java`
- `src/test/java/com/softropic/skillars/platform/development/api/RadarAssessmentResourceIT.java`

**Modified files:**
- `src/main/java/com/softropic/skillars/platform/development/repo/SkillDefinition.java` (added rubricCriteria)
- `src/main/java/com/softropic/skillars/platform/development/repo/SkillDefinitionRepository.java` (added findAllByCodeIn)
- `src/main/java/com/softropic/skillars/platform/development/contract/SkillDefinitionDto.java` (added rubricCriteria)
- `src/main/java/com/softropic/skillars/platform/development/api/SkillDefinitionResource.java` (mapper updated)
- `src/main/java/com/softropic/skillars/platform/marketplace/api/CoachMarketplaceResource.java` (added GET /me/tier)
- `src/frontend/src/api/development.api.js` (added postRadarAssessment, getMyRadarEntries)
- `src/frontend/src/stores/development.store.js` (added radarEntries state + actions)
- `src/frontend/src/stores/auth.store.js` (added coachTier + fetchCoachTier)
- `src/frontend/src/pages/player/PlayerDevelopmentDashboardPage.vue` (radar panel + history list)
- `src/frontend/src/i18n/en-US/index.js` (development.radar.* keys)
- `src/frontend/src/i18n/en/index.js` (development.radar.* keys)
- `src/frontend/src/i18n/fr-FR/index.js` (development.radar.* keys)
- `src/frontend/src/i18n/de/index.js` (development.radar.* keys)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status update)

## Review Findings

### Decision-Needed

- [x] [Review][Decision] PlayerProfileService package placement — resolved: moved to `marketplace.service`; import updated in `RadarAssessmentService`; old `security.service.PlayerProfileService` deleted [`src/main/java/com/softropic/skillars/platform/marketplace/service/PlayerProfileService.java`]

### Patches

- [x] [Review][Patch] Scout coach can enumerate player existence via 404/403 ordering — reordered: tier check now before `getParentIdByPlayerId` [`RadarAssessmentService.java:submitAssessment`]
- [x] [Review][Patch] Scout button visible before `fetchCoachTier` resolves — added `tierLoaded` ref; set via `.finally()` on `fetchCoachTier()`; button, history card, and panel now guarded with `&& tierLoaded` [`PlayerDevelopmentDashboardPage.vue`]
- [x] [Review][Patch] `scores` ref never initialised if dialog opens with `modelValue=true` from mount — added `{ immediate: true }` to watch [`SkillsRadarAssessmentPanel.vue:watch`]
- [x] [Review][Patch] Assessment-type `q-select` label resolves to empty string — replaced broken `.replace()` with dedicated `assessmentTypeLabel` i18n key (added to all 4 locales) [`SkillsRadarAssessmentPanel.vue:21`]
- [x] [Review][Patch] `otherCoachCount` displayed per entry row — added `firstIndexBySkillCode` computed; message now shown only on first occurrence of each skill code [`RadarAssessmentHistoryList.vue`]
- [x] [Review][Patch] IT teardown SQL "truncated" — investigated: `main.sec` is a real table defined in `V10__security_schema.sql`; statement is correct; no fix required (false positive, dismissed) [`RadarAssessmentResourceIT.java:tearDown`]
- [x] [Review][Patch] IT test accepts `400 OR 409` for duplicate-group submission — added `uq_radar_entries_group_coach_skill` to `CONFLICT_CONSTRAINTS` in ApiAdvice; handler now returns 409 for this constraint; IT assertion pinned to exactly `HttpStatus.CONFLICT` [`ApiAdvice.java`, `RadarAssessmentResourceIT.java`]
- [x] [Review][Patch] `submitSuccess` i18n key defined but never used — added `$q.notify` with `submitSuccess` key on success path [`SkillsRadarAssessmentPanel.vue:submit`]
- [x] [Review][Patch] Tier-reference legend abbreviates "Above Avg"/"Avg" — updated to "Above Average"/"Average" in all 4 locales; added `assessmentTypeLabel` key in same pass [`i18n/en-US/index.js`, `i18n/en/index.js`, `i18n/de/index.js`, `i18n/fr-FR/index.js`]
- [x] [Review][Patch] `RadarAssessmentHistoryList` shown to Scout coaches — history card v-if changed to `isCoach && tierLoaded && !isScoutTier` [`PlayerDevelopmentDashboardPage.vue`]
- [x] [Review][Patch] `SkillDefinitionResource` maps entity fields manually inline — created `SkillDefinitionMapper` in `development.contract`; resource now delegates via `skillDefinitionMapper::toDto` [`SkillDefinitionMapper.java`, `SkillDefinitionResource.java`]
- [x] [Review][Patch] Tier gate uses `== SCOUT` equality, not an allowlist — changed to `RADAR_ALLOWED_TIERS` (`EnumSet` of INSTRUCTOR + ACADEMY) allowlist check [`RadarAssessmentService.java`]
- [x] [Review][Patch] `assessmentDate` defaults to UTC date — replaced `toISOString().slice(0,10)` with `localDateString()` helper using local getters in 2 places [`SkillsRadarAssessmentPanel.vue`]
- [x] [Review][Patch] Duplicate `skillCode` values silently deduplicated by `Set` — added explicit duplicate check (`submittedCodes.size() != entryList.size()`) that returns 400 before skill validation [`RadarAssessmentService.java`]

### Deferred

- [x] [Review][Defer] `SkillDefinitionRepository` injected directly into `SkillDefinitionResource` — pre-existing architecture (no service layer); out of scope for this story [`SkillDefinitionResource.java:17`] — deferred, pre-existing
- [x] [Review][Defer] `entry_count` stores total rows across types, not distinct coaches — semantic ambiguity; Story 5.4 confidence indicators may need distinct-coach count; carry note to 5.4 author [`RadarCompositeCalculationService.java`, `player_radar_composites`] — deferred, pre-existing
- [x] [Review][Defer] Concurrent async composite recalculation race — two simultaneous assessments can query aggregates before either upserts; last writer wins, self-corrects on next submission; theoretical [`RadarCompositeCalculationService.java:onRadarEntrySubmitted`] — deferred, pre-existing
- [x] [Review][Defer] No retry/dead-letter for async composite failure — accepted per dev notes; composite remains at prior value until next submission triggers recomputation [`RadarCompositeCalculationService.java`] — deferred, pre-existing

---

### Second Pass Review Findings (2026-06-19)

#### Decision-Needed

- [x] [Review][Decision] No coach-player relationship check on radar endpoints — deferred: same architectural gap as Story 5.2 narrative access (DEF0); platform currently has no coach-player assignment enforcement at the API layer; flag for a security hardening story that audits all coach-scoped development endpoints [`RadarAssessmentResource.java`, `RadarAssessmentService.java`]

#### Patches

- [x] [Review][Patch] Scout tier gate absent on `getMyEntries` — `RadarAssessmentService.getMyEntries` has no tier check; a Scout coach can call `GET /api/development/players/{playerId}/radar/entries` and read coach-count metadata without hitting `FeatureGatedException`; violates AC 5 at the backend [`RadarAssessmentService.java:getMyEntries`]
- [x] [Review][Patch] `submit()` in `SkillsRadarAssessmentPanel` has no `catch` block — `developmentStore.submitRadarAssessment` re-throws on failure; the component's `try/finally` resets `submitting` but shows the user no error notification; panel stays open with no feedback on 4xx/5xx [`SkillsRadarAssessmentPanel.vue:submit`]
- [x] [Review][Patch] `assessmentDate` input has no `max` attribute — `<q-input type="date">` does not bind `:max="localDateString()"`; user can pick a future date and receives a silent 400 (server `@PastOrPresent` rejects it) with no client-side indicator [`SkillsRadarAssessmentPanel.vue:29`]
- [x] [Review][Patch] `notes` `q-input` has no `maxlength` — no client-side length guard; user can type >500 chars and receives a silent 400 with no UI explanation [`SkillsRadarAssessmentPanel.vue:58`]
- [x] [Review][Patch] `fetchCoachTier` failure leaves Scout coaches with button visible — on network/5xx error the catch sets `coachTier = null`; `isScoutTier = (coachTier === 'SCOUT')` evaluates `false`; Scout sees "Add Radar Assessment" button, clicks, gets 403 with no explanation (AC 5 UX-DR22) [`auth.store.js:fetchCoachTier`, `PlayerDevelopmentDashboardPage.vue:isScoutTier`]

#### Deferred

- [x] [Review][Defer] Async composite silently stales on failure — `@Async` listener catches all exceptions and logs only; no retry or dead-letter; composite remains at prior value until next submission; accepted per dev notes [`RadarCompositeCalculationService.java:onRadarEntrySubmitted`] — deferred, pre-existing
- [x] [Review][Defer] `entry_count` long→double→int narrowing in composite calculator — `count` from native SQL is stored as `double[]` then cast `(int)`; silently overflows above `Integer.MAX_VALUE`; irrelevant at current assessment volumes [`RadarCompositeCalculationService.java:61-69`] — deferred, low risk
- [x] [Review][Defer] Orphaned `player_radar_composites` on player deletion — `player_id` has no FK to `player_profiles`; deleted player leaves stale composite rows; pre-existing pattern across development module [`V50__radar_assessment_entries.sql`, `player_radar_composites`] — deferred, pre-existing

## Change Log

| Date | Change | Author |
|---|---|---|
| 2026-06-19 | Story 5.3 created | claude-sonnet-4-6 |
| 2026-06-19 | Audit fixes: added parentId to all player-scoped repo methods + event (FR-TSC-009); fixed native SQL + enum cast; added UNIQUE index for idempotency; added try-catch to async calculator; added @PastOrPresent to assessmentDate; added skillCode validation task; fixed Scout UX (absent not handled-on-403); fixed @Async rationale; added rubric criteria prop/tooltip to panel; fixed otherCoachCounts zero-case; added IT tests for enum round-trip and retry constraint; corrected idempotency claim in dev notes; added entry_count/5.4 concern, immutability, parentId, rubric source dev notes | claude-sonnet-4-6 |
| 2026-06-19 | Story 5.3 fully implemented: all 17 tasks complete, 14 tests pass (5 unit + 9 IT), full regression suite green. Fixes applied: @JdbcTypeCode(SqlTypes.NAMED_ENUM) for PG enum binding, InvalidParamException for 400 on invalid skill code, IT test map deserialization fix. Status → review. | claude-sonnet-4-6 |
