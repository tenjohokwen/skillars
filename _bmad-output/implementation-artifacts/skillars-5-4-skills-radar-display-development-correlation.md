# Story skillars-5.4: Skills Radar Display & Development Correlation

Status: done

## Story

As a coach or parent,
I want to view a player's unified Skills Radar with baseline comparison, confidence indicators, and — on Academy tier — insight into how training volume correlates with ability gains,
So that development decisions are evidence-based and progress is visible at a glance.

## Acceptance Criteria

**AC 1: Skills Radar chart renders with node anatomy** — Given a coach or parent views a player's Skills Radar, when the `SkillsRadarChart` component renders, then an SVG polygon is displayed on concentric reference circles; each node shows: skill code label, current composite score badge, delta indicator ("↑ +6 since first assessment") when a baseline exists, and a confidence dot (filled = 3+ entries, half-filled = 1–2 entries, empty = no entries); a last-updated tooltip appears on hover/tap showing the date of the most recent entry for that skill (UX-DR9).

**AC 2: Dynamic axis geometry for skill subset** — Given the radar chart renders with a coach-selected skill subset, when the coach selects 5 of the 15 skills to display, then the SVG polygon geometry adjusts dynamically — axis count matches the selected subset; deselected skills are hidden from the polygon but their scores remain in the underlying data; the selection is persisted per coach-player pair so it restores on next visit.

**AC 3: Baseline comparison with ghost polygon** — Given a baseline assessment exists (the player's first recorded composite score for a skill), when subsequent assessments are entered and composites update, then the delta indicator shows change from baseline: positive delta in `--accent-primary`, negative in `--accent-danger`, zero in `--text-secondary`; a "Compare to baseline" toggle shows both current polygon and a ghost polygon at baseline values simultaneously.

**AC 4: Accessible screen reader alternative** — Given an accessible screen reader navigates the radar chart, when the chart is focused, then a `<table>` element with all node values (skill code, current score, baseline score, delta) is present in the DOM as an accessible alternative — visually hidden but readable by screen readers (UX-DR9, UX-DR18).

**AC 5: Academy tier — Development Correlation Engine** — Given an Academy tier coach views the Development Correlation Engine panel, when there is sufficient history (minimum configurable session count, read from ConfigService key `development.correlation.minSessionCount`), then for each skill the panel shows: cumulative SLU for that skill, Skills Radar composite score, and a correlation insight: "High SLU → Score improvement" / "High SLU → No improvement (technique issue?)" / "Low SLU → Score stable (natural ability?)"; insights are plain-English sentences, not raw numbers; if insufficient history exists, the panel shows "Not enough data yet — keep logging sessions" with the minimum required session count (FR-DEV-011).

**AC 6: Non-Academy teaser for Correlation Engine** — Given a coach below Academy tier views the Correlation Engine section, when the panel would render, then it is shown as a blurred teaser with "Academy feature — upgrade to unlock" CTA (UX-DR22) — never a 403 error in the UI.

## Tasks / Subtasks

---

### Backend — Migration

- [x] **Task 1: Write `V51__radar_display_correlation.sql`** (AC: 1, 2, 3, 5)
  - [x] File: `src/main/resources/db/migration/V51__radar_display_correlation.sql`
  - [x] Previous migration: V50 (`radar_assessment_entries`). This MUST be V51.
  - [x] **ID collision check**: Confirm V51 is unused by scanning all migration files.
  - [x] SQL:
    ```sql
    -- Story 5.4: Skills Radar Display & Development Correlation

    -- Baseline snapshot: written ONCE per player+skill on first composite calculation — never overwritten
    CREATE TABLE development.player_radar_baselines (
        player_id       BIGINT          NOT NULL,   -- TSID Long; NOT UUID
        skill_code      VARCHAR(10)     NOT NULL REFERENCES development.skill_definitions(code),
        baseline_score  NUMERIC(5,2)    NOT NULL,
        recorded_at     TIMESTAMPTZ     NOT NULL,
        PRIMARY KEY (player_id, skill_code)
    );

    -- Per coach-player skill subset selection; persists chart view preference
    CREATE TABLE development.coach_radar_preferences (
        coach_id         UUID            NOT NULL,   -- marketplace.coach_profiles.id (UUID)
        player_id        BIGINT          NOT NULL,   -- TSID Long; NOT UUID
        selected_skills  VARCHAR(10)[]   NOT NULL DEFAULT '{}',
        updated_at       TIMESTAMPTZ     NOT NULL,
        PRIMARY KEY (coach_id, player_id)
    );

    -- ConfigService key for the Development Correlation Engine minimum session threshold
    INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at) VALUES
        (115, 'development.correlation.minSessionCount', '5', 'LONG',
         'Minimum distinct completed sessions required before the Development Correlation Engine shows insights', NOW())
    ON CONFLICT (key) DO NOTHING;
    ```
  - [x] **CRITICAL**: `player_id` in BOTH tables is BIGINT — NOT UUID. Consistent with Stories 5.1–5.3.
  - [x] `coach_id` in `coach_radar_preferences` is UUID (coach profile IDs are UUIDs — unchanged).
  - [x] `selected_skills VARCHAR(10)[]` uses a PostgreSQL array — JPA mapping requires `@Type(ListArrayType.class)` + `@Column(columnDefinition = "varchar[]")` same pattern as `CoachProfile.languages` (line 46–48).
  - [x] No FK from `player_radar_baselines.player_id` to player_profiles (same accepted limitation as composites — see Story 5.3 deferred review item).

---

### Backend — Repository Layer

- [x] **Task 2: Create `PlayerRadarBaselineId.java` + `PlayerRadarBaseline.java`** (AC: 3)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/repo/PlayerRadarBaselineId.java`
    ```java
    @Embeddable
    @Getter @Setter @NoArgsConstructor @EqualsAndHashCode
    public class PlayerRadarBaselineId implements Serializable {
        @Column(name = "player_id") private Long playerId;
        @Column(name = "skill_code", length = 10) private String skillCode;
    }
    ```
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/repo/PlayerRadarBaseline.java`
    ```java
    @Entity
    @Table(schema = "development", name = "player_radar_baselines")
    @Getter @Setter @NoArgsConstructor
    public class PlayerRadarBaseline {
        @EmbeddedId private PlayerRadarBaselineId id;
        @Column(name = "baseline_score", nullable = false, precision = 5, scale = 2)
        private BigDecimal baselineScore;
        @Column(name = "recorded_at", nullable = false)
        private Instant recordedAt;
    }
    ```
  - [x] Pattern mirrors `PlayerRadarComposite` exactly (same module, same PK structure).

- [x] **Task 3: Create `PlayerRadarBaselineRepository.java`** (AC: 3)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/repo/PlayerRadarBaselineRepository.java`
  - [x] Key methods:
    ```java
    List<PlayerRadarBaseline> findByIdPlayerId(Long playerId);

    // INSERT ... ON CONFLICT DO NOTHING — writes baseline only once, never overwrites
    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = """
        INSERT INTO development.player_radar_baselines
            (player_id, skill_code, baseline_score, recorded_at)
        VALUES (:playerId, :skillCode, :baselineScore, NOW())
        ON CONFLICT (player_id, skill_code) DO NOTHING
        """)
    void insertBaselineIfAbsent(
        @Param("playerId") Long playerId,
        @Param("skillCode") String skillCode,
        @Param("baselineScore") BigDecimal baselineScore);
    ```
  - [x] `ON CONFLICT DO NOTHING` is the immutability guarantee — the baseline is the first composite ever written for a skill; subsequent calls must not overwrite it.

- [x] **Task 4: Create `CoachRadarPreferenceId.java` + `CoachRadarPreference.java`** (AC: 2)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/repo/CoachRadarPreferenceId.java`
    ```java
    @Embeddable
    @Getter @Setter @NoArgsConstructor @EqualsAndHashCode
    public class CoachRadarPreferenceId implements Serializable {
        @Column(name = "coach_id") private UUID coachId;
        @Column(name = "player_id") private Long playerId;   // BIGINT — NOT UUID
    }
    ```
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/repo/CoachRadarPreference.java`
    ```java
    @Entity
    @Table(schema = "development", name = "coach_radar_preferences")
    @TypeDef(name = "list-array", typeClass = ListArrayType.class)
    @Getter @Setter @NoArgsConstructor
    public class CoachRadarPreference {
        @EmbeddedId private CoachRadarPreferenceId id;

        @Type(ListArrayType.class)
        @Column(name = "selected_skills", columnDefinition = "varchar[]", nullable = false)
        private List<String> selectedSkills = new ArrayList<>();

        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt;
    }
    ```
  - [x] **CRITICAL**: `@Type(ListArrayType.class)` import from `io.hypersistence.utils.hibernate.type.array.ListArrayType` — same library used in `CoachProfile.java` (line 46). Do NOT use `@Column(columnDefinition = "text[]")` without `@Type` — JPA will fail to bind the array.
  - [x] **`@TypeDef` is NOT required** — `CoachProfile.java` (lines 46–48) uses only `@Type(ListArrayType.class)` without a class-level `@TypeDef`, confirming that `hypersistence-utils` registers the type globally. Do NOT add `@TypeDef` to `CoachRadarPreference.java`; `@Type(ListArrayType.class)` alone is sufficient.

- [x] **Task 5: Create `CoachRadarPreferenceRepository.java`** (AC: 2)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/repo/CoachRadarPreferenceRepository.java`
    ```java
    public interface CoachRadarPreferenceRepository
            extends JpaRepository<CoachRadarPreference, CoachRadarPreferenceId> {

        Optional<CoachRadarPreference> findByIdCoachIdAndIdPlayerId(UUID coachId, Long playerId);
    }
    ```
  - [x] UPSERT is handled by the service via `findAndUpdate` + `save`, not a native query — the entity has a composite PK that JPA can merge directly.

---

### Backend — Contract Layer

- [x] **Task 6: Create contract types for display and correlation** (AC: 1, 2, 3, 4, 5, 6)
  - [x] Dir: `src/main/java/com/softropic/skillars/platform/development/contract/`

  - [x] **`SkillRadarEntry.java`** (per-skill data in display response):
    ```java
    public record SkillRadarEntry(
        String skillCode,
        BigDecimal compositeScore,     // null if no assessments yet
        BigDecimal baselineScore,      // null if no baseline recorded yet
        Integer entryCount,            // total assessment rows (from player_radar_composites.entry_count)
        Instant lastUpdatedAt          // from player_radar_composites.last_updated_at; null if no assessments
    ) {}
    ```

  - [x] **`RadarDisplayResponse.java`** (full GET display response):
    ```java
    public record RadarDisplayResponse(
        List<SkillRadarEntry> skills   // one entry per skill_definition (all 15, nulls where no data)
    ) {}
    ```

  - [x] **`CoachRadarPreferenceRequest.java`** (PUT request body):
    ```java
    public record CoachRadarPreferenceRequest(
        @NotNull @Size(min = 0, max = 15) List<@NotBlank @Size(max = 10) String> selectedSkillCodes
    ) {}
    ```
  - [x] **`min = 0` is intentional**: an empty list means "reset to default — show all skills with data." The `coach_radar_preferences` table allows an empty array via `DEFAULT '{}'`. The `CoachRadarPreferenceResponse` already returns `[]` for no-preference, so the frontend already handles the empty-list-means-all-skills case.
  - [x] **Skill code existence NOT validated at the API layer** — invalid codes produce an empty polygon on the frontend (no crash). Acceptable for a preference store; a future story can add a custom validator against `SkillDefinitionRepository.findAllCodes()`.

  - [x] **`CoachRadarPreferenceResponse.java`** (GET/PUT preference response):
    ```java
    public record CoachRadarPreferenceResponse(
        List<String> selectedSkillCodes  // empty list = no stored preference (frontend uses all 15)
    ) {}
    ```

  - [x] **`CorrelationInsightType.java`** (correlation insight classification enum):
    ```java
    public enum CorrelationInsightType {
        HIGH_SLU_IMPROVEMENT,           // high training volume, positive composite change
        HIGH_SLU_NO_IMPROVEMENT,        // high training volume, no/negative composite change
        LOW_SLU_IMPROVEMENT,            // below-average SLU but composite still improved (natural talent)
        LOW_SLU_STABLE                  // below-average SLU, no significant composite change
    }
    ```

  - [x] **`CorrelationInsight.java`** (per-skill correlation result):
    ```java
    public record CorrelationInsight(
        String skillCode,
        BigDecimal cumulativeSlu,         // total SLU ever for this skill for this player
        BigDecimal compositeScore,        // current composite from player_radar_composites
        CorrelationInsightType insightType,
        String insightTextKey             // i18n key for the plain-English sentence
    ) {}
    ```

  - [x] **`CorrelationResponse.java`** (full GET correlation response):
    ```java
    public record CorrelationResponse(
        boolean insufficientData,          // true when session count < minSessionCount
        long minimumSessionCount,          // value from ConfigService (for UI messaging)
        List<CorrelationInsight> insights, // empty when insufficientData = true
        int excludedSkillCount             // skills with SLU data but no radar composite — shown in UI
    ) {}
    ```

---

### Backend — Service Layer

- [x] **Task 7: Update `RadarCompositeCalculationService.java` — write baselines** (AC: 3)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java` (MODIFY)
  - [x] Inject `PlayerRadarBaselineRepository baselineRepository` (add to constructor).
  - [x] In `onRadarEntrySubmitted`, **after** each `compositeRepository.upsertComposite(...)` call, add:
    ```java
    baselineRepository.insertBaselineIfAbsent(playerId, skill, compositeScore);
    ```
  - [x] `insertBaselineIfAbsent` uses `ON CONFLICT DO NOTHING` — it is safe to call every time the composite is calculated; the DB guarantees the first write wins and subsequent calls are no-ops.
  - [x] **Order matters**: upsert composite THEN insert baseline. If baseline runs first, it may record a score before the composite is saved (race condition in future parallel writes). Always composite → baseline.
  - [x] The `@Async` exception catch block is already present — `baselineRepository.insertBaselineIfAbsent` failure is included in the same catch.

- [x] **Task 8: Create `RadarDisplayService.java`** (AC: 1, 2, 3, 4)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/service/RadarDisplayService.java`
  - [x] Inject: `PlayerRadarCompositeRepository compositeRepository`, `PlayerRadarBaselineRepository baselineRepository`, `SkillDefinitionRepository skillDefinitionRepository`, `CoachRadarPreferenceRepository preferenceRepository`, `CoachProfileService coachProfileService`
  - [x] Implementation:
    ```java
    @Service
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public class RadarDisplayService {

        private final PlayerRadarCompositeRepository compositeRepository;
        private final PlayerRadarBaselineRepository baselineRepository;
        private final SkillDefinitionRepository skillDefinitionRepository;
        private final CoachRadarPreferenceRepository preferenceRepository;
        private final CoachProfileService coachProfileService;

        public RadarDisplayResponse getRadarDisplay(Long playerId) {
            List<SkillDefinition> allSkills =
                skillDefinitionRepository.findAllByActiveTrueOrderByDisplayOrderAsc();
            Map<String, PlayerRadarComposite> compositeMap = compositeRepository
                .findByIdPlayerId(playerId).stream()
                .collect(Collectors.toMap(c -> c.getId().getSkillCode(), c -> c));
            Map<String, PlayerRadarBaseline> baselineMap = baselineRepository
                .findByIdPlayerId(playerId).stream()
                .collect(Collectors.toMap(b -> b.getId().getSkillCode(), b -> b));

            List<SkillRadarEntry> skills = allSkills.stream().map(def -> {
                PlayerRadarComposite comp = compositeMap.get(def.getCode());
                PlayerRadarBaseline  base = baselineMap.get(def.getCode());
                return new SkillRadarEntry(
                    def.getCode(),
                    comp != null ? comp.getCompositeScore()   : null,
                    base != null ? base.getBaselineScore()    : null,
                    comp != null ? comp.getEntryCount()       : null,
                    comp != null ? comp.getLastUpdatedAt()    : null
                );
            }).toList();

            return new RadarDisplayResponse(skills);
        }

        public CoachRadarPreferenceResponse getPreferences(UUID coachId, Long playerId) {
            return preferenceRepository.findByIdCoachIdAndIdPlayerId(coachId, playerId)
                .map(p -> new CoachRadarPreferenceResponse(p.getSelectedSkills()))
                .orElse(new CoachRadarPreferenceResponse(List.of()));
        }

        @Transactional
        public CoachRadarPreferenceResponse savePreferences(
                UUID coachId, Long playerId, CoachRadarPreferenceRequest request) {
            CoachRadarPreference pref = preferenceRepository
                .findByIdCoachIdAndIdPlayerId(coachId, playerId)
                .orElseGet(() -> {
                    CoachRadarPreference p = new CoachRadarPreference();
                    CoachRadarPreferenceId pk = new CoachRadarPreferenceId();
                    pk.setCoachId(coachId);
                    pk.setPlayerId(playerId);
                    p.setId(pk);
                    return p;
                });
            pref.setSelectedSkills(new ArrayList<>(request.selectedSkillCodes()));
            pref.setUpdatedAt(Instant.now());
            return new CoachRadarPreferenceResponse(
                preferenceRepository.save(pref).getSelectedSkills());
        }
    }
    ```
  - [x] `getRadarDisplay` returns ALL 15 skills; frontend filters to the `selectedSkillCodes` from preferences for the polygon, but the full data is always returned so the accessible `<table>` can show all 15.
  - [x] No `parentId` parameter — access control is at the endpoint via `@playerOwnershipGuard.check(...)` (same pattern as `SkillExposureResource`).

- [x] **Task 9: Create `DevelopmentCorrelationService.java`** (AC: 5, 6)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/service/DevelopmentCorrelationService.java`
  - [x] Inject: `SluRepository sluRepository`, `PlayerRadarCompositeRepository compositeRepository`, `PlayerRadarBaselineRepository baselineRepository`, `CoachProfileService coachProfileService`, `ConfigService configService`
  - [x] **Insight classification algorithm** (implement exactly as described):
    1. Gate: if `CoachSubscriptionTier != ACADEMY`, throw `FeatureGatedException("development.correlation", "ACADEMY")`
    2. Count distinct `session_id` values in `player_skill_stats` for this `playerId`. If count < `configService.getLong("development.correlation.minSessionCount")`, return `CorrelationResponse(insufficientData=true, minimumSessionCount, insights=[])`
    3. Sum `slu_value` per `skill_code` from `player_skill_stats` where `player_id = :playerId` → `Map<String, BigDecimal> sluBySkill`
    4. Compute cross-skill mean SLU: `totalSlu / sluBySkill.size()` (player's own average)
    5. Load composites and baselines for the player
    6. For each skill with SLU data:
       - **Guard**: if `compositeMap.get(skillCode) == null` (player has SLU for this skill but no radar assessment yet), increment `excludedSkillCount` and skip — cannot classify without a composite score. Do NOT throw; just skip.
       - `sluHigh = skillSlu.compareTo(meanSlu) > 0`
       - `compositeImprovement = currentCompositeScore - baselineScore` (use `BigDecimal.ZERO` if no baseline row exists)
       - `compositeImproved = compositeImprovement.compareTo(BigDecimal.valueOf(3.0)) > 0`
       - Classify:
         - `sluHigh && compositeImproved` → `HIGH_SLU_IMPROVEMENT`
         - `sluHigh && !compositeImproved` → `HIGH_SLU_NO_IMPROVEMENT`
         - `!sluHigh && compositeImproved` → `LOW_SLU_IMPROVEMENT` (score improved despite low training — natural talent signal)
         - `!sluHigh && !compositeImproved` → `LOW_SLU_STABLE`
       - Build `CorrelationInsight(skillCode, cumulativeSlu, compositeScore, insightType, i18nKey)`
         - i18n key mapping: `HIGH_SLU_IMPROVEMENT` → `"development.correlation.insight.highSluImprovement"`, `HIGH_SLU_NO_IMPROVEMENT` → `"development.correlation.insight.highSluNoImprovement"`, `LOW_SLU_IMPROVEMENT` → `"development.correlation.insight.lowSluImprovement"`, `LOW_SLU_STABLE` → `"development.correlation.insight.lowSluStable"`
    7. Return `CorrelationResponse(insufficientData=false, minimumSessionCount, insights, excludedSkillCount)` sorted by `cumulativeSlu DESC`
  - [x] **SLU query** — add native query to `SluRepository`:
    ```java
    @Query(nativeQuery = true, value = """
        SELECT skill_code, SUM(slu_value) as total_slu
        FROM development.player_skill_stats
        WHERE player_id = :playerId
        GROUP BY skill_code
        """)
    List<Object[]> sumSluBySkill(@Param("playerId") Long playerId);

    @Query(nativeQuery = true, value = """
        SELECT COUNT(DISTINCT session_id)
        FROM development.player_skill_stats
        WHERE player_id = :playerId AND session_id IS NOT NULL
        """)
    Long countDistinctSessions(@Param("playerId") Long playerId);
    ```
  - [x] `session_id IS NOT NULL` required — Quick Complete sessions have null `session_id` (per V46 schema comment).
  - [x] **ConfigService usage**: call `configService.getLong("development.correlation.minSessionCount")` — NOT cached in a field; called per-invocation (per project-context.md rules).
  - [x] Academy tier gate is in the **service** (NOT in `@PreAuthorize`), same as `RadarAssessmentService` — throws `FeatureGatedException("development.correlation", "ACADEMY")`.

---

### Backend — REST Resource

- [x] **Task 10: Create `RadarDisplayResource.java`** (AC: 1, 2, 3, 4, 5, 6)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/api/RadarDisplayResource.java`
  - [x] Inject: `RadarDisplayService radarDisplayService`, `DevelopmentCorrelationService correlationService`, `SecurityUtil securityUtil`, `CoachProfileService coachProfileService`
  - [x] Implementation:
    ```java
    @RestController
    @RequiredArgsConstructor
    public class RadarDisplayResource {

        private final RadarDisplayService radarDisplayService;
        private final DevelopmentCorrelationService correlationService;
        private final SecurityUtil securityUtil;
        private final CoachProfileService coachProfileService;

        // Coach AND parent access — parent guarded via playerOwnershipGuard (same pattern as SkillExposureResource)
        @GetMapping("/api/development/players/{playerId}/radar/display")
        @PreAuthorize("hasRole('ROLE_COACH') or @playerOwnershipGuard.check(authentication, #playerId)")
        @Observed(name = "development.radar.display")
        public ResponseEntity<RadarDisplayResponse> getRadarDisplay(@PathVariable Long playerId) {
            return ResponseEntity.ok(radarDisplayService.getRadarDisplay(playerId));
        }

        // Coach only — persists the selected skill subset per coach-player pair
        @GetMapping("/api/development/players/{playerId}/radar/preferences")
        @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
        @Observed(name = "development.radar.preferences.get")
        public ResponseEntity<CoachRadarPreferenceResponse> getPreferences(@PathVariable Long playerId) {
            UUID coachId = coachProfileService.getCoachIdByUserId(securityUtil.getCurrentCoachUserId());
            return ResponseEntity.ok(radarDisplayService.getPreferences(coachId, playerId));
        }

        // Coach only — 204 on success (project-context.md: @PatchMapping or @PutMapping + 204 for no response body)
        @PutMapping("/api/development/players/{playerId}/radar/preferences")
        @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
        @Observed(name = "development.radar.preferences.put")
        public ResponseEntity<Void> savePreferences(
                @PathVariable Long playerId,
                @RequestBody @Valid CoachRadarPreferenceRequest request) {
            UUID coachId = coachProfileService.getCoachIdByUserId(securityUtil.getCurrentCoachUserId());
            radarDisplayService.savePreferences(coachId, playerId, request);
            return ResponseEntity.noContent().build();
        }

        // Coach only — Academy gate enforced in service layer
        @GetMapping("/api/development/players/{playerId}/radar/correlation")
        @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
        @Observed(name = "development.radar.correlation")
        public ResponseEntity<CorrelationResponse> getCorrelation(@PathVariable Long playerId) {
            UUID coachId = coachProfileService.getCoachIdByUserId(securityUtil.getCurrentCoachUserId());
            return ResponseEntity.ok(correlationService.getInsights(playerId, coachId));
        }
    }
    ```
  - [x] **Parent access pattern**: `@playerOwnershipGuard.check(authentication, #playerId)` — verified by `PlayerOwnershipGuard.check()` which resolves the parent's `businessId` and checks it against `player_profiles.parent_id`. No `parentId` param needed in the service.
  - [x] **No `HAS_COACH_OR_PARENT_ROLE` constant** — use the existing SpEL expression with `@playerOwnershipGuard` (same as `SkillExposureResource`). Do NOT add a new SecurityConstants field just for this.
  - [x] `coachProfileService.getCoachIdByUserId(securityUtil.getCurrentCoachUserId())` called once per request in the coach-only endpoints. Do NOT call it twice.
  - [x] For `getCorrelation`: the Academy gate is in `DevelopmentCorrelationService.getInsights()` — the `FeatureGatedException` maps to 403 via `ApiAdvice.featureGatedHandler`. The frontend MUST call the endpoint and handle the response (not call at all for non-Academy) — the endpoint is available to all coaches; the service gates it.

---

### Backend — Tests

- [x] **Task 11: Create `DevelopmentCorrelationServiceTest.java`** (AC: 5)
  - [x] File: `src/test/java/com/softropic/skillars/platform/development/service/DevelopmentCorrelationServiceTest.java`
  - [x] Pure unit test — mock `SluRepository`, `PlayerRadarCompositeRepository`, `PlayerRadarBaselineRepository`, `CoachProfileService`, `ConfigService`.
  - [x] Tests:
    - `getInsights_nonAcademyCoach_throwsFeatureGatedException` — tier=INSTRUCTOR; assert `FeatureGatedException` thrown.
    - `getInsights_insufficientSessions_returnsInsufficiencyResponse` — countDistinctSessions returns 3, minSessionCount=5; assert `insufficientData=true`, `insights=[]`, `minimumSessionCount=5`.
    - `getInsights_highSluImprovedComposite_returnsHighSluImprovement` — skill PAC has SLU above mean, composite improved by 10 from baseline 50 to 60; assert `insightType=HIGH_SLU_IMPROVEMENT`.
    - `getInsights_highSluNoImprovement_returnsHighSluNoImprovement` — skill SHO has SLU above mean, composite baseline 70, current 70 (no change); assert `insightType=HIGH_SLU_NO_IMPROVEMENT`.
    - `getInsights_lowSluStable_returnsLowSluStable` — skill WEF has SLU below mean, composite unchanged; assert `insightType=LOW_SLU_STABLE`.
    - `getInsights_lowSluImprovement_returnsLowSluImprovement` — skill DRI has SLU below mean, composite improved by 10 from baseline 40 to 50; assert `insightType=LOW_SLU_IMPROVEMENT` (NOT `LOW_SLU_STABLE`).
    - `getInsights_noBaselineForSkill_treatsImprovementAsZero` — skill has no baseline entry; `compositeImprovement` defaults to `BigDecimal.ZERO`; with high SLU → `HIGH_SLU_NO_IMPROVEMENT` (improvement threshold 3.0 not met).
    - `getInsights_skillWithSluButNoComposite_isExcluded` — skill has SLU data but `compositeMap` has no entry for that skill code; assert skill is absent from `insights` list and `excludedSkillCount = 1`. This guards against NPE on `compositeMap.get()` returning null.
    - `getInsights_sortedBySluDescending` — three skills with SLU 100, 20, 50; assert insights ordered 100 → 50 → 20.
    - `getInsights_excludedSkillCountZero_whenAllSkillsHaveComposites` — all SLU skills have corresponding composites; assert `excludedSkillCount = 0`.
  - [x] **Stub pattern for SluRepository**: `List.of(new Object[]{"PAC", new BigDecimal("150.00")})` — `row[0]` is skill_code (String), `row[1]` is total_slu (BigDecimal from PostgreSQL NUMERIC).
  - [x] Instantiate `DevelopmentCorrelationService` manually (constructor injection) to avoid Spring context overhead.

- [x] **Task 12: Create `RadarDisplayResourceIT.java`** (AC: 1, 2, 3, 5, 6)
  - [x] File: `src/test/java/com/softropic/skillars/platform/development/api/RadarDisplayResourceIT.java`
  - [x] Same Spring Boot test setup as `RadarAssessmentResourceIT.java` — copy all annotations, helper methods (`loginAndGetCookies`, `insertAuthority`, `insertUser`, `grantRole`, `insertCoachProfile`, `insertSubscription`).
  - [x] **Use unique user IDs in range 9590000001–9590000030** to avoid collision with:
    - Story 5.2 IT: `9570000001–9570000021`
    - Story 5.3 IT: `9580000001–9580000020`
  - [x] Test data setup constants:
    ```java
    private static final long COACH_USER_ID    = 9590000001L;
    private static final long ACADEMY_USER_ID  = 9590000002L;
    private static final long PARENT_USER_ID   = 9590000010L;
    private static final long PLAYER_ID        = 9590000020L;
    ```
  - [x] Insert coach with INSTRUCTOR subscription + Academy coach with ACADEMY subscription + parent + player linked to parent.
  - [x] Insert `radar_assessment_entries` and trigger composite + baseline seeding via direct SQL (rather than calling the API) to control test data precisely.
  - [x] Insert `player_skill_stats` rows with distinct `session_id` values to satisfy the `minSessionCount` gate (insert 6+ rows with distinct session UUIDs to exceed the default threshold of 5).
  - [x] Tests:
    - `getDisplay_asCoach_returns200WithAllSkills` — GET display; assert 200; assert response contains 15 skill entries; skills with no composite have `compositeScore=null`.
    - `getDisplay_asParent_returns200` — login as parent; GET display; assert 200.
    - `getDisplay_asParentForOtherPlayersChild_returns403` — parent whose `businessId` is NOT linked to `PLAYER_ID`; assert 403 (ownership guard blocks it).
    - `getDisplay_unauthenticated_returns401`.
    - `getDisplay_newPlayer_returnsAllNullScores` — no composite or baseline rows seeded for `PLAYER_ID`; GET display; assert 200; assert response contains exactly 15 skill entries; assert every entry has `compositeScore=null`, `baselineScore=null`, `entryCount=null`, `lastUpdatedAt=null`. This is the first-visit baseline case.
    - `getDisplay_compositeAndBaselinePresent_returnsBothScores` — seed a composite row (score=65) and baseline row (baseline=50) for PAC; GET display; assert `compositeScore=65`, `baselineScore=50`. Delta computation is frontend-only — do NOT assert a delta field in the response.
    - `savePreferences_persistsSelectedSkills` — PUT preferences with `["PAC","SHO","PAS"]`; assert 204; GET preferences; assert `selectedSkillCodes=["PAC","SHO","PAS"]`.
    - `savePreferences_overwritesPreviousPreference` — save `["PAC"]` then save `["SHO","DRI"]`; GET; assert only second list returned.
    - `getPreferences_noPreviousPreference_returnsEmptyList` — no preference row; GET; assert `selectedSkillCodes=[]`.
    - `getCorrelation_academyCoach_sufficientData_returnsInsights` — insert 6 distinct session SLU rows for PAC and WEF; assert 200, `insufficientData=false`, `insights` non-empty.
    - `getCorrelation_academyCoach_insufficientData_returnsInsufficientDataResponse` — no SLU data; assert 200, `insufficientData=true`, `minimumSessionCount=5`.
    - `getCorrelation_nonAcademyCoach_returns403WithFeatureGatedCode` — INSTRUCTOR coach; assert 403, body contains `"security.featureGated"`.
    - `getCorrelation_unauthenticated_returns401`.
  - [x] **Teardown**: Delete radar display test data in `@AfterEach` via `transactionTemplate`. Use cascading deletes or explicit table-level deletes in FK dependency order.

---

### Frontend

- [x] **Task 13: Add radar display/correlation functions to `development.api.js`** (AC: 1, 2, 5)
  - [x] File: `src/frontend/src/api/development.api.js` (MODIFY — append)
    ```js
    export const getRadarDisplay = (playerId) =>
      api.get(`/api/development/players/${playerId}/radar/display`)

    export const getRadarPreferences = (playerId) =>
      api.get(`/api/development/players/${playerId}/radar/preferences`)

    export const putRadarPreferences = (playerId, selectedSkillCodes) =>
      api.put(`/api/development/players/${playerId}/radar/preferences`, { selectedSkillCodes })

    export const getCorrelationInsights = (playerId) =>
      api.get(`/api/development/players/${playerId}/radar/correlation`)
    ```

- [x] **Task 14: Add display/correlation state and actions to `development.store.js`** (AC: 1, 2, 5)
  - [x] File: `src/frontend/src/stores/development.store.js` (MODIFY — append)
  - [x] New state:
    ```js
    const radarDisplay = ref(null)           // RadarDisplayResponse | null
    const radarPreferences = ref(null)       // CoachRadarPreferenceResponse | null
    const correlationInsights = ref(null)    // CorrelationResponse | null
    const radarDisplayLoading = ref(false)
    const correlationLoading = ref(false)    // distinct from null — differentiates "loading" from "error/empty"
    ```
  - [x] New actions:
    ```js
    async function fetchRadarDisplay(playerId) {
      radarDisplayLoading.value = true
      error.value = null
      try {
        const response = await getRadarDisplay(playerId)
        radarDisplay.value = response.data
      } catch (err) {
        error.value = err?.response?.data?.message ?? 'Failed to load radar display'
      } finally {
        radarDisplayLoading.value = false
      }
    }

    async function fetchRadarPreferences(playerId) {
      try {
        const response = await getRadarPreferences(playerId)
        radarPreferences.value = response.data
      } catch {
        radarPreferences.value = { selectedSkillCodes: [] }
      }
    }

    async function saveRadarPreferences(playerId, selectedSkillCodes) {
      try {
        await putRadarPreferences(playerId, selectedSkillCodes)
        radarPreferences.value = { selectedSkillCodes }
      } catch (err) {
        error.value = err?.response?.data?.message ?? 'Failed to save preferences'
      }
    }

    async function fetchCorrelationInsights(playerId) {
      correlationLoading.value = true
      try {
        const response = await getCorrelationInsights(playerId)
        correlationInsights.value = response.data
      } catch (err) {
        // Non-Academy coaches get 403 here — store null silently; UI shows teaser
        if (err?.response?.status !== 403) {
          error.value = err?.response?.data?.message ?? 'Failed to load correlation insights'
        }
        correlationInsights.value = null
      } finally {
        correlationLoading.value = false
      }
    }
    ```
  - [x] Expose `radarDisplay`, `radarPreferences`, `correlationInsights`, `radarDisplayLoading`, `correlationLoading`, `fetchRadarDisplay`, `fetchRadarPreferences`, `saveRadarPreferences`, `fetchCorrelationInsights` from store return.
  - [x] **403 handling in `fetchCorrelationInsights`**: Non-Academy coaches receive 403; the store catches it silently, sets `correlationInsights = null`, and `correlationLoading = false`. The `DevelopmentCorrelationPanel` uses `correlationLoading` to distinguish "still loading" from "null after error/403". Do NOT use `correlationInsights === null` alone as the loading signal — that conflates loading and error states.

- [x] **Task 15: Create `SkillsRadarChart.vue`** (AC: 1, 2, 3, 4)
  - [x] File: `src/frontend/src/components/development/SkillsRadarChart.vue`
  - [x] Props — use `toRefs` so computed functions can reference `.value` correctly:
    ```js
    const props = defineProps({
      skills: { type: Array, required: true },          // SkillRadarEntry[] — all 15 from API
      selectedSkillCodes: { type: Array, default: () => [] }, // coach preference; empty = show all
      showBaseline: { type: Boolean, default: false }   // "Compare to baseline" toggle state
    })
    const { skills, selectedSkillCodes, showBaseline } = toRefs(props)
    ```
  - [x] **CRITICAL**: in Vue 3 `<script setup>`, props are NOT refs — without `toRefs`, expressions like `selectedSkillCodes.value` return `undefined` and crash at render time. Always destructure via `toRefs(props)` before using in computeds.
  - [x] **SVG polygon geometry** — the heart of this component:
    ```js
    const activeSkills = computed(() => {
      return selectedSkillCodes.value.length > 0
        ? skills.value.filter(s => selectedSkillCodes.value.includes(s.skillCode))
        : skills.value.filter(s => s.compositeScore !== null)  // if no selection, show all with data
    })

    const CENTER_X = 200, CENTER_Y = 200, RADIUS = 160

    function toPoint(index, total, scoreOrNull, maxScore = 100) {
      const angle = (2 * Math.PI * index) / total - Math.PI / 2  // start at top
      // Clamp score to [0, maxScore] — guards against out-of-range composites plotting outside the boundary
      const clamped = scoreOrNull !== null ? Math.max(0, Math.min(scoreOrNull, maxScore)) : 0
      const r = (clamped / maxScore) * RADIUS
      return {
        x: CENTER_X + r * Math.cos(angle),
        y: CENTER_Y + r * Math.sin(angle)
      }
    }

    const polygonPoints = computed(() =>
      activeSkills.value
        .map((s, i) => {
          const pt = toPoint(i, activeSkills.value.length, s.compositeScore)
          return `${pt.x},${pt.y}`
        })
        .join(' ')
    )

    const baselinePoints = computed(() =>
      activeSkills.value
        .map((s, i) => {
          const pt = toPoint(i, activeSkills.value.length, s.baselineScore)
          return `${pt.x},${pt.y}`
        })
        .join(' ')
    )

    // Node label positions (on the outer ring)
    const nodePositions = computed(() =>
      activeSkills.value.map((s, i) => {
        const angle = (2 * Math.PI * i) / activeSkills.value.length - Math.PI / 2
        return {
          x: CENTER_X + (RADIUS + 20) * Math.cos(angle),
          y: CENTER_Y + (RADIUS + 20) * Math.sin(angle),
          skill: s
        }
      })
    )
    ```
  - [x] **Concentric reference circles**: render 4 circles at RADIUS × 0.25, 0.50, 0.75, 1.0 as `<circle>` elements with `stroke="var(--glass-border)"` and no fill. Add axis lines from center to each node.
  - [x] **Confidence dot** logic (per node):
    ```js
    function confidenceDotFill(entryCount) {
      if (!entryCount || entryCount === 0) return 'empty'
      if (entryCount <= 2) return 'half'
      return 'filled'
    }
    ```
    Render as a `<circle>` near the score badge: filled = full `--accent-primary` fill, half = half-fill with clip-path or stroke + partial fill, empty = only stroke. Use simple CSS classes to avoid SVG complexity.
  - [x] **Delta indicator**: `deltaText(s)` = `s.baselineScore !== null ? (s.compositeScore - s.baselineScore).toFixed(0) : null`. Render with `↑` prefix when positive, `↓` when negative, arrow color class.
  - [x] **Last-updated tooltip**: render `<q-tooltip>` on the score badge element ONLY when `s.lastUpdatedAt !== null`. Binding `new Date(null)` returns `1970-01-01`. Use: `<q-tooltip v-if="s.lastUpdatedAt">{{ new Date(s.lastUpdatedAt).toLocaleDateString() }}</q-tooltip>`.
  - [x] **Ghost polygon** (`v-if="showBaseline && hasBaseline"`): `<polygon :points="baselinePoints" fill="none" stroke="var(--text-secondary)" stroke-width="1" stroke-dasharray="4,2" opacity="0.5" />`
  - [x] **Accessible `<table>`** (AC 4, UX-DR18): iterate `skills` (all 15), NOT `activeSkills`. Screen reader users receive the full dataset regardless of which polygon subset is displayed — the chart filter is a visual concern, not an accessibility one.
    ```html
    <table class="sr-only" aria-label="Skills Radar Data">
      <thead>
        <tr>
          <th>Skill</th><th>Current Score</th><th>Baseline Score</th><th>Delta</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="s in skills" :key="s.skillCode">
          <td>{{ s.skillCode }}</td>
          <td>{{ s.compositeScore ?? '—' }}</td>
          <td>{{ s.baselineScore ?? '—' }}</td>
          <td>{{ deltaText(s) ?? '—' }}</td>
        </tr>
      </tbody>
    </table>
    ```
    Add `.sr-only` to the Quasar SCSS globals (or global CSS): `position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap;`
  - [x] **Emit** `update:selectedSkillCodes` when a skill axis is clicked (toggle include/exclude from selection — coach-only interaction). `emits(['update:selectedSkillCodes'])`.
  - [x] Use `vue-i18n` `t()` for any text labels in the component. Avoid hardcoded English strings.
  - [x] **Empty state**: when `activeSkills.length === 0`, show: "No assessments recorded yet" centered in the SVG area.

- [x] **Task 16: Create `DevelopmentCorrelationPanel.vue`** (AC: 5, 6)
  - [x] File: `src/frontend/src/components/development/DevelopmentCorrelationPanel.vue`
  - [x] Props — `correlationLoading` is passed separately so the panel can distinguish loading from post-error null:
    ```js
    defineProps({
      correlationData: { type: Object, default: null },  // CorrelationResponse | null
      isAcademyTier: { type: Boolean, required: true },
      correlationLoading: { type: Boolean, default: false }
    })
    ```
  - [x] Structure (evaluate in this order):
    - If `!isAcademyTier`: show blurred teaser (see UX-DR22 pattern — a `<div class="correlation-teaser">` with `filter: blur(4px)` over a fake insight list, overlaid with a `<div class="teaser-overlay">` containing the "Academy feature — upgrade to unlock" CTA card). **NEVER** attempt to call the API when `!isAcademyTier` — the teaser is pure UI.
    - If `isAcademyTier && correlationLoading`: show `<q-inner-loading>`. Do NOT use `correlationData === null` as the loading signal — that also covers post-error state and would show a permanent spinner if the API returns a non-403 error.
    - If `isAcademyTier && !correlationLoading && correlationData === null`: show a generic error state — "Could not load correlation data. Try refreshing."
    - If `isAcademyTier && correlationData?.insufficientData`: show:
      ```html
      {{ $t('development.radar.correlation.insufficientData', { count: correlationData.minimumSessionCount }) }}
      ```
      Note: the `{count}` placeholder must be passed as a parameter to `$t()` — not interpolated directly.
    - If `isAcademyTier && correlationData?.insights?.length > 0`: show a `<q-list>` with one row per insight:
      - Left: skill code badge
      - Middle: `$t('development.radar.correlation.insight.' + insight.insightType)` — resolves to the plain-English sentence
      - Right: SLU total and composite score as small stats
    - If `correlationData?.excludedSkillCount > 0`: below the insights list, show a footnote:
      ```html
      {{ $t('development.radar.correlation.excludedSkills', { count: correlationData.excludedSkillCount }) }}
      ```
      e.g. "3 skills not shown — no training data recorded yet." This prevents coaches from wondering why not all radar skills appear.
  - [x] **Teaser content** (shown blurred behind overlay): render 3 placeholder rows with static dummy data to make the blur meaningful.
  - [x] The teaser overlay uses a glassmorphism card (per design system — `glass-card` class, per UX-DR22 pattern: semi-transparent overlay over blurred content, never a full-screen block).
  - [x] Use `vue-i18n` for all text. No hardcoded English strings.

- [x] **Task 17: Integrate into `PlayerDevelopmentDashboardPage.vue`** (AC: 1, 2, 3, 5, 6)
  - [x] File: `src/frontend/src/pages/player/PlayerDevelopmentDashboardPage.vue` (MODIFY)
  - [x] Import new components:
    ```js
    import SkillsRadarChart from 'src/components/development/SkillsRadarChart.vue'
    import DevelopmentCorrelationPanel from 'src/components/development/DevelopmentCorrelationPanel.vue'
    ```
  - [x] New refs:
    ```js
    const showBaseline = ref(false)
    // VERIFY: confirm the exact field name in authStore before implementing.
    // From auth module usage in Stories 5.1–5.3, look up how the coach's subscription tier
    // is exposed — it may be authStore.coachTier, authStore.subscriptionTier, or authStore.tier.
    // The value must equal the string 'ACADEMY' (SubscriptionTier enum serialised).
    const isAcademyTier = computed(() => authStore.coachTier === 'ACADEMY')
    // isCoach: confirmed as an existing computed ref from earlier stories
    // (e.g., from PlayerDevelopmentDashboardPage added in Story 5.1 or 5.2 — check before adding a duplicate)
    ```
  - [x] In `onMounted`, for Coach role: also call:
    ```js
    store.fetchRadarDisplay(playerId.value),
    store.fetchRadarPreferences(playerId.value),
    store.fetchCorrelationInsights(playerId.value),   // 403 is handled silently by store
    ```
  - [x] For Parent role: also call `store.fetchRadarDisplay(playerId.value)`.
  - [x] Add the Skills Radar card **above** the skill exposure bar chart (radar is the hero per UX-DR9, and Story 5.6 confirms radar is hero element):
    ```html
    <q-card class="q-mb-md">
      <q-card-section>
        <div class="text-subtitle1">{{ $t('development.radar.displayTitle') }}</div>
        <q-toggle v-if="hasBaseline" v-model="showBaseline"
          :label="$t('development.radar.compareBaselineLabel')" />
      </q-card-section>
      <q-card-section>
        <SkillsRadarChart
          :skills="store.radarDisplay?.skills ?? []"
          :selected-skill-codes="store.radarPreferences?.selectedSkillCodes ?? []"
          :show-baseline="showBaseline"
          @update:selected-skill-codes="onSkillSelectionChange"
        />
      </q-card-section>
    </q-card>
    ```
  - [x] `hasBaseline` computed: `store.radarDisplay?.skills?.some(s => s.baselineScore !== null) ?? false`
  - [x] `onSkillSelectionChange(codes)` handler:
    ```js
    async function onSkillSelectionChange(codes) {
      if (isCoach.value) {
        await store.saveRadarPreferences(playerId.value, codes)
      }
    }
    ```
    Parents cannot change preferences (read-only view, no preferences endpoint for parents).
  - [x] Add the Correlation Panel below the radar card (Coach only):
    ```html
    <q-card v-if="isCoach" class="q-mt-md">
      <q-card-section>
        <div class="text-subtitle1">{{ $t('development.radar.correlationTitle') }}</div>
      </q-card-section>
      <q-card-section>
        <DevelopmentCorrelationPanel
          :correlation-data="store.correlationInsights"
          :is-academy-tier="isAcademyTier"
          :correlation-loading="store.correlationLoading"
        />
      </q-card-section>
    </q-card>
    ```
  - [x] **Guard**: `v-if="isCoach"` — parents do not see the Correlation Engine panel at all (not even the teaser). The teaser is only for non-Academy coaches.

- [x] **Task 18: Add i18n keys** (AC: 1, 2, 3, 5, 6)
  - [x] File: `src/frontend/src/i18n/en-US/index.js` (MODIFY — add under `development.radar:`)
    ```js
    // Under development.radar:
    displayTitle: 'Skills Radar',
    compareBaselineLabel: 'Compare to baseline',
    noAssessmentsYet: 'No assessments recorded yet',
    correlationTitle: 'Development Correlation Engine',
    confidenceFilled: 'High confidence (3+ assessments)',
    confidenceHalf: 'Low confidence (1–2 assessments)',
    confidenceEmpty: 'No assessments yet',
    lastUpdatedTooltip: 'Last assessed: {date}',
    correlationError: 'Could not load correlation data. Try refreshing.',
    // Under development.radar.correlation: (new sub-key)
    correlation: {
      // {count} is a vue-i18n named parameter — pass as $t('...insufficientData', { count: N })
      insufficientData: 'Not enough data yet — keep logging sessions. Minimum {count} sessions required.',
      academyFeatureTeaser: 'Academy feature — upgrade to unlock',
      // {count} is a vue-i18n named parameter — pass as $t('...excludedSkills', { count: N })
      excludedSkills: '{count} skill | {count} skills not shown — no training data recorded yet.',
      insight: {
        highSluImprovement: 'High training volume → Score improving',
        highSluNoImprovement: 'High training volume → No improvement yet (technique focus needed?)',
        lowSluImprovement: 'Low training volume → Score improving (natural talent!)',
        lowSluStable: 'Low training volume → Score stable (natural ability?)',
      },
    },
    ```
  - [x] Add same keys (with English placeholders) to `fr-FR/index.js`, `de/index.js`, and `en/index.js` to prevent vue-i18n missing-key warnings.

- [x] **Task 19: Create `SkillsRadarChartSpec.js`** (AC: 1, 2)
  - [x] File: `src/frontend/src/components/development/__tests__/SkillsRadarChartSpec.js`
  - [x] Required by epics dev notes. Use `@vue/test-utils` + Vitest (same pattern as other frontend component tests in the project).
  - [x] Helper: build a `makeSkill(code, score, baseline)` factory returning a `SkillRadarEntry`-shaped object.
  - [x] Tests:
    - `renders empty state when no skills have scores` — mount with all-null `compositeScore`; assert "No assessments recorded yet" text is visible.
    - `polygon has N points for N-skill selection` — mount with `skills` = 15 entries (all with scores), `selectedSkillCodes = ['PAC','SHO','PAS','DRI','WEF']`; assert `polygonPoints` computed produces a string with exactly 5 coordinate pairs.
    - `polygon uses all scored skills when selection is empty` — mount with 10 skills having non-null scores and 5 null; `selectedSkillCodes = []`; assert polygon has 10 points.
    - `polygon uses all 15 skills when all have scores and selection is empty` — assert polygon has 15 points.
    - `baseline ghost polygon rendered when showBaseline is true and baseline exists` — mount with one skill that has `baselineScore`; pass `showBaseline=true`; assert SVG contains a `<polygon>` with `stroke-dasharray`.
    - `baseline ghost polygon not rendered when showBaseline is false` — assert no dashed polygon in DOM.
    - `accessible table always contains all 15 skills regardless of selection` — mount with `selectedSkillCodes = ['PAC']`; assert the `<table>` contains 15 `<tr>` rows in `<tbody>`.
    - `confidence dot shows filled for entryCount >= 3` — assert correct CSS class applied to node with `entryCount=3`.
    - `toPoint clamps score above 100 within radius` — assert `compositeScore=150` does not produce `x` or `y` outside `[CENTER_X - RADIUS, CENTER_X + RADIUS]`.

---

## Dev Notes

### CRITICAL: `player_id` is BIGINT, NOT UUID (Consistent with Stories 5.1–5.3)

All three stories corrected the same epics spec bug. In Story 5.4:
- `development.player_radar_baselines.player_id` — BIGINT (not UUID)
- `development.coach_radar_preferences.player_id` — BIGINT (not UUID)
- All service methods taking `playerId` — `Long`, not `UUID`

Evidence: `PlayerProfile` uses `@Tsid Long id`, all existing `development.*` tables use BIGINT for `player_id`.

### `parentId` is Long (not UUID)

`RadarEntrySubmittedEvent` uses `Long parentId` (confirmed from the actual source file). The `PlayerOwnershipGuard` also resolves `parentId` as `Long.parseLong(principal.getBusinessId())`. The `PlayerProfileService.getParentIdByPlayerId(Long)` returns `Long`.

### Parent Access — `@playerOwnershipGuard` Pattern

The radar display endpoint uses `@PreAuthorize("hasRole('ROLE_COACH') or @playerOwnershipGuard.check(authentication, #playerId)")` — exactly the same pattern as `SkillExposureResource` and `NeglectedSkillResource`. The guard resolves parent ownership by joining `player_profiles` on `parent_id = authenticated_parent_id`. No `parentId` parameter flows into the service for display data — the composite/baseline tables do not store `parent_id`.

Do NOT use the explicit `parentId`-parameterised query pattern (from Stories 5.1–5.3 for write paths) on read-only display queries. The ownership guard at the endpoint layer is sufficient for read paths that don't require cross-table JOINs at query time.

### `player_profiles` is in `main` Schema (Not `marketplace`)

All existing `main.player_profiles` JOINs use `main.player_profiles pp` (confirmed in Story 5.3 debug log and `PlayerOwnershipGuard` which queries via `PlayerProfileRepository.findByIdAndParentId`). The `PlayerProfileRepository` is in `com.softropic.skillars.platform.security.repo` and queries `main.player_profiles`.

### `CoachRadarPreference.selectedSkills` — `ListArrayType`

The `coach_radar_preferences.selected_skills` column is a PostgreSQL `VARCHAR(10)[]` array. JPA entity must use:
```java
import io.hypersistence.utils.hibernate.type.array.ListArrayType;
@Type(ListArrayType.class)
@Column(name = "selected_skills", columnDefinition = "varchar[]", nullable = false)
private List<String> selectedSkills = new ArrayList<>();
```
This is the exact same pattern as `CoachProfile.languages` (lines 46–48). The `hibernate-types-60` library (`com.vladmihalcea`) is already in `pom.xml`. Do NOT use a comma-delimited String or JSONB — the `VARCHAR[]` native array type is used by the existing pattern.

### Baseline Immutability — `ON CONFLICT DO NOTHING`

`PlayerRadarBaselineRepository.insertBaselineIfAbsent` uses:
```sql
INSERT INTO development.player_radar_baselines (player_id, skill_code, baseline_score, recorded_at)
VALUES (:playerId, :skillCode, :baselineScore, NOW())
ON CONFLICT (player_id, skill_code) DO NOTHING
```
The baseline is the FIRST composite ever calculated for a skill. `ON CONFLICT DO NOTHING` guarantees idempotency — even if `RadarCompositeCalculationService.onRadarEntrySubmitted` is called repeatedly (e.g., two concurrent assessments), only the first write survives. Do NOT use `ON CONFLICT DO UPDATE` — that would overwrite the baseline.

**Known MVP limitation**: if the composite write succeeds but `insertBaselineIfAbsent` fails on the first-ever assessment (e.g., transient DB timeout), the next invocation will pass the updated composite (which now includes a second assessment's weighting) to `insertBaselineIfAbsent`. The baseline will then capture that score rather than the true first composite. `ON CONFLICT DO NOTHING` prevents overwrites but cannot retroactively recover the original first score. This scenario requires a DB failure strictly between the composite upsert and the baseline insert on the first-ever entry for a skill — low probability, accepted for MVP. A future story can harden this by writing baseline to a separate immutable event table at the point of `RadarEntrySubmittedEvent` dispatch, before any composite calculation.

### `RadarCompositeCalculationService` Modification Order

In `onRadarEntrySubmitted`:
1. `compositeRepository.upsertComposite(playerId, skill, compositeScore, totalCount)` — composite first
2. `baselineRepository.insertBaselineIfAbsent(playerId, skill, compositeScore)` — baseline second

The composite is the source of truth for the baseline. Writing the composite first ensures the baseline is always derived from a valid, fully-calculated composite. Both calls are inside the existing `try { ... } catch (Exception e) { log.error(...) }` block — if baseline write fails, the composite upsert remains valid (the baseline simply won't be written until the next invocation, which will again try `ON CONFLICT DO NOTHING`).

### Correlation Service — Cross-Skill SLU Mean Algorithm

The `DevelopmentCorrelationService` classifies insights using the player's own cross-skill mean SLU:
- `sluHigh` = `skillSlu.compareTo(crossSkillMeanSlu) > 0`
- `compositeImproved` = `(currentComposite - baselineScore).compareTo(BigDecimal.valueOf(3.0)) > 0`

The four classification branches are:
| `sluHigh` | `compositeImproved` | Result |
|---|---|---|
| true | true | `HIGH_SLU_IMPROVEMENT` |
| true | false | `HIGH_SLU_NO_IMPROVEMENT` |
| false | true | `LOW_SLU_IMPROVEMENT` — do NOT collapse to `LOW_SLU_STABLE`; "stable" is factually wrong when the score improved |
| false | false | `LOW_SLU_STABLE` |

The 3.0 point improvement threshold is hardcoded (not configurable) — small enough to be meaningful but large enough to exclude floating-point noise. If a future story makes this configurable, it belongs in `platform_config`.

If a skill has no baseline (no previous assessment), `compositeImprovement` defaults to `BigDecimal.ZERO` — treated as no improvement. This is intentional: the correlation is between training volume and measured ability *change*; without a baseline, change cannot be computed.

Skills with zero SLU are excluded from the insights list (you cannot classify training volume patterns for a skill that has never been trained).

**Skills with SLU but no radar composite**: a player may have `player_skill_stats` rows for a skill they've never had a radar assessment on. These skills have a composite score of null — do NOT attempt arithmetic on a null composite. Skip these skills, increment `excludedSkillCount`, and surface the count to the frontend via `CorrelationResponse.excludedSkillCount`.

### Academy Tier Gate — Service Layer Only

`DevelopmentCorrelationService.getInsights()` throws `FeatureGatedException("development.correlation", "ACADEMY")` for non-Academy coaches. This maps to 403 via `ApiAdvice.featureGatedHandler` → returns `ErrorDto` with code `"security.featureGated"`.

The frontend handles this 403 silently in `fetchCorrelationInsights` — `correlationInsights` remains `null`, and `DevelopmentCorrelationPanel` shows the teaser when `!isAcademyTier`. The teaser must never say "Access denied" — it's a soft upsell, not a block.

The endpoint `GET /api/development/players/{playerId}/radar/correlation` is declared with `@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)` only — any authenticated coach can call it. The Academy gate is inside the service, not on the route. This is consistent with `RadarAssessmentService` (Scout gate in service, not on route).

### Entry Count and 5.4 Confidence Model — Known Limitation

From Story 5.3 deferred review: `entry_count` in `player_radar_composites` counts total assessment rows across all types and coaches (e.g., 2 OBJECTIVE + 1 MATCH_OBS = 3). Story 5.4 uses `entryCount >= 3` for "filled confidence dot." Consequence: a single coach submitting 3 OBJECTIVE-only assessments shows a filled dot even though the composite is capped at 50 (only 0.50 weight applied). This is accepted as-is — the confidence dot signals assessment volume, not composite completeness. Document this limitation in a `<!-- NOTE -->` comment inside `SkillsRadarChart.vue`.

### Accessible Table — All 15 Skills, Not Just Active Skills

The accessible `<table>` iterates `skills` (all 15 from the API), NOT `activeSkills`. The polygon subset filter is a visual concern; screen reader users should receive the full dataset regardless of which skills the coach has selected for display. `RadarDisplayService.getRadarDisplay` always returns all 15 skill entries (with null scores for unassessed skills), so the `skills` prop always carries the full set. Do NOT bind `v-for` to `activeSkills` in the accessible table.

### `sluRepository.countDistinctSessions` — `session_id IS NOT NULL`

Quick Complete sessions write `player_skill_stats` rows with `session_id = NULL` (per V46 schema: `session_id UUID` is nullable). The minimum session count for the Correlation Engine must count only structured sessions (with a `session_id`). Always use `WHERE player_id = :playerId AND session_id IS NOT NULL`.

### SVG Geometry — Trigonometric Notes

For N skills equally spaced around the circle:
```
angle_i = (2π × i / N) - π/2   // subtract π/2 so the first axis points up (12 o'clock)
x_i = cx + r × cos(angle_i)
y_i = cy + r × sin(angle_i)
```
Where `r = (score / 100) × RADIUS` for a scaled point, or just `RADIUS` for the outer node label position (using `RADIUS + 20` for label clearance).

SVG `viewBox="0 0 400 400"` with `cx=cy=200, RADIUS=160` gives good proportions. Use `preserveAspectRatio="xMidYMid meet"` so the chart scales responsively.

### Frontend Scout Detection for Preferences Endpoint

The `GET /PUT /api/development/players/{playerId}/radar/preferences` endpoints are `@PreAuthorize(HAS_COACH_ROLE)` — no Scout tier gate. Scout coaches CAN call these endpoints and store preferences. The preferences endpoint is not feature-gated — it's just a UI state store. The Correlation Engine is gated (Academy), not preferences. Do NOT add a tier check to the preferences API.

### REST Conventions (project-context.md)

- `GET /radar/display` → 200 with `RadarDisplayResponse`
- `GET /radar/preferences` → 200 with `CoachRadarPreferenceResponse` (empty list if none)
- `PUT /radar/preferences` → 204 No Content (body-less success per project-context.md)
- `GET /radar/correlation` → 200 with `CorrelationResponse` (200 for insufficient data — it's a valid business response, not an error)
- Every resource method **MUST** have `@PreAuthorize` and `@Observed(name = "...")`.

### Project Structure Summary

| Component | Location | Status |
|---|---|---|
| V51 migration | `src/main/resources/db/migration/V51__radar_display_correlation.sql` | CREATE |
| `PlayerRadarBaselineId` | `platform.development.repo` | CREATE |
| `PlayerRadarBaseline` | `platform.development.repo` | CREATE |
| `PlayerRadarBaselineRepository` | `platform.development.repo` | CREATE |
| `CoachRadarPreferenceId` | `platform.development.repo` | CREATE |
| `CoachRadarPreference` | `platform.development.repo` | CREATE |
| `CoachRadarPreferenceRepository` | `platform.development.repo` | CREATE |
| `SkillRadarEntry` | `platform.development.contract` | CREATE |
| `RadarDisplayResponse` | `platform.development.contract` | CREATE |
| `CoachRadarPreferenceRequest` | `platform.development.contract` | CREATE |
| `CoachRadarPreferenceResponse` | `platform.development.contract` | CREATE |
| `CorrelationInsightType` | `platform.development.contract` | CREATE |
| `CorrelationInsight` | `platform.development.contract` | CREATE |
| `CorrelationResponse` | `platform.development.contract` | CREATE |
| `RadarDisplayService` | `platform.development.service` | CREATE |
| `DevelopmentCorrelationService` | `platform.development.service` | CREATE |
| `RadarDisplayResource` | `platform.development.api` | CREATE |
| `RadarCompositeCalculationService` | `platform.development.service` | MODIFY — inject `baselineRepository`, write baseline after each composite upsert |
| `SluRepository` | `platform.development.repo` | MODIFY — add `sumSluBySkill`, `countDistinctSessions` native queries |
| `DevelopmentCorrelationServiceTest` | `platform.development.service` (test) | CREATE |
| `RadarDisplayResourceIT` | `platform.development.api` (test) | CREATE |
| `development.api.js` | `src/frontend/src/api/` | MODIFY — add 4 new API functions |
| `development.store.js` | `src/frontend/src/stores/` | MODIFY — add display/prefs/correlation state + actions |
| `SkillsRadarChart.vue` | `src/frontend/src/components/development/` | CREATE |
| `SkillsRadarChartSpec.js` | `src/frontend/src/components/development/__tests__/` | CREATE |
| `DevelopmentCorrelationPanel.vue` | `src/frontend/src/components/development/` | CREATE |
| `PlayerDevelopmentDashboardPage.vue` | `src/frontend/src/pages/player/` | MODIFY — add radar chart + correlation panel, fetch calls |
| i18n files (en-US, fr-FR, de, en) | `src/frontend/src/i18n/` | MODIFY — add `development.radar.display*`, `development.radar.correlation*`, `development.correlation.*` keys |

### References

- `RadarCompositeCalculationService.java` — `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` pattern to be extended [`src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java`]
- `PlayerRadarCompositeRepository.java` — `upsertComposite` native INSERT ON CONFLICT pattern to mirror for baseline [`src/main/java/com/softropic/skillars/platform/development/repo/PlayerRadarCompositeRepository.java`]
- `CoachProfile.java` — `@Type(ListArrayType.class)` + `@Column(columnDefinition = "varchar[]")` pattern for `selectedSkills` (lines 46–48) [`src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfile.java`]
- `SkillExposureResource.java` — `@playerOwnershipGuard.check(authentication, #playerId)` for coach-or-parent access (lines 24, 34) [`src/main/java/com/softropic/skillars/platform/development/api/SkillExposureResource.java`]
- `PlayerOwnershipGuard.java` — guard implementation: resolves `parentId = Long.parseLong(businessId)`, checks `player_profiles` ownership [`src/main/java/com/softropic/skillars/platform/security/service/PlayerOwnershipGuard.java`]
- `RadarAssessmentService.java` — Academy/Scout tier gate via `RADAR_ALLOWED_TIERS` EnumSet, `FeatureGatedException` pattern [`src/main/java/com/softropic/skillars/platform/development/service/RadarAssessmentService.java`]
- `SluCalculationService.java` — ConfigService per-invocation read pattern [`src/main/java/com/softropic/skillars/platform/development/service/SluCalculationService.java`]
- `RadarAssessmentResourceIT.java` — full IT test template: annotations, user IDs 9580000001–9580000020 (avoid collision), helper methods, MockitoBean VideoProviderAdapter [`src/test/java/com/softropic/skillars/platform/development/api/RadarAssessmentResourceIT.java`]
- Story 5.3 dev notes — `player_id BIGINT`, `parentId Long`, `player_profiles` in `main` schema, `@Async` + `@TransactionalEventListener` pattern [`_bmad-output/implementation-artifacts/skillars-5-3-skills-radar-assessment-entry-multi-coach-cumulation.md`]
- Epic 5 spec: Story 5.4 [`_bmad-output/planning-artifacts/skillars-epics.md` lines 1840–1879]

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- Fixed `t` unused-var lint error in `SkillsRadarChart.vue` (removed `useI18n` import; templates use global `$t`)
- Fixed `RadarCompositeCalculatorTest` constructor call — added `baselineRepository` mock after service gained third constructor arg
- Fixed `List<?>` raw-type varargs in `RadarDisplayResourceIT` — cast to `List<String>` to satisfy AssertJ's typed varargs
- Corrected "no baseline" behavior in `DevelopmentCorrelationService`: sets `compositeImprovement = ZERO` (not `currentScore - 0`) when no baseline exists, so a skill with no baseline is treated as "no improvement" per test spec

### Completion Notes List

- **Task 1** (V51 migration): Created `player_radar_baselines` (BIGINT player_id PK), `coach_radar_preferences` (UUID coach_id + BIGINT player_id PK, `selected_skills VARCHAR(10)[]`), and seeded `development.correlation.minSessionCount=5` platform config.
- **Tasks 2–5** (repo layer): Created `PlayerRadarBaselineId`, `PlayerRadarBaseline`, `PlayerRadarBaselineRepository` (with `insertBaselineIfAbsent` ON CONFLICT DO NOTHING), `CoachRadarPreferenceId`, `CoachRadarPreference` (with `@Type(ListArrayType.class)` for `selected_skills`), `CoachRadarPreferenceRepository`.
- **Task 6** (contracts): Created 7 contract types — `SkillRadarEntry`, `RadarDisplayResponse`, `CoachRadarPreferenceRequest`, `CoachRadarPreferenceResponse`, `CorrelationInsightType` (enum), `CorrelationInsight`, `CorrelationResponse`.
- **Task 7** (`RadarCompositeCalculationService` update): Injected `PlayerRadarBaselineRepository`; added `baselineRepository.insertBaselineIfAbsent(...)` call after each composite upsert. Updated existing `RadarCompositeCalculatorTest` to pass new mock.
- **Task 8** (`RadarDisplayService`): Returns all active skill definitions with joined composite + baseline data; handles coach preferences UPSERT via JPA `save`.
- **Task 9** (`DevelopmentCorrelationService`): Academy gate → session count gate → cross-skill mean SLU algorithm → 4-bucket classification → `excludedSkillCount` for skills with SLU but no composite → sorted by SLU descending. Added `sumSluBySkill` and `countDistinctSessions` native queries to `SluRepository`.
- **Task 10** (`RadarDisplayResource`): 4 endpoints — GET display (coach+parent via `@playerOwnershipGuard`), GET/PUT preferences (coach only, 204 for PUT), GET correlation (coach only, Academy gate in service).
- **Task 11** (`DevelopmentCorrelationServiceTest`): 10 pure unit tests covering all 4 insight types, non-Academy gate, insufficient sessions, no-baseline-treats-as-zero, excluded skill count, and sort order.
- **Task 12** (`RadarDisplayResourceIT`): 13 integration tests covering all endpoints, parent ownership guard, Academy/non-Academy correlation gate, preference persistence, and null-score baseline case.
- **Tasks 13–14** (frontend API + store): Added 4 API functions to `development.api.js`; added 5 state refs + 4 async actions to `development.store.js`.
- **Task 15** (`SkillsRadarChart.vue`): Full SVG radar with concentric circles, axis lines, score polygon, ghost baseline polygon, node badges, confidence dots, delta indicators, last-updated tooltips, `sr-only` accessible table (iterates all 15 skills), and `update:selectedSkillCodes` emit for coach interaction.
- **Task 16** (`DevelopmentCorrelationPanel.vue`): Blurred teaser for non-Academy (with 3 static placeholder rows), `q-inner-loading` for loading state, error banner for null post-load, insufficient-data message, insights `q-list`, and excluded-skills footnote.
- **Task 17** (`PlayerDevelopmentDashboardPage.vue`): Added `SkillsRadarChart` card above exposure chart, `DevelopmentCorrelationPanel` card (coach only), fetch calls in `onMounted` for both coach and parent, `showBaseline` toggle, `hasBaseline` computed, `isAcademyTier` computed, `onSkillSelectionChange` handler.
- **Task 18** (i18n): Added 20+ new keys under `development.radar` (display, baseline, confidence, correlation, accessible table, insight text) to all 4 locale files (en-US, fr-FR, de, en).
- **Task 19** (`SkillsRadarChartSpec.js`): 9 Vitest unit tests for polygon geometry, empty state, accessible table coverage, confidence dots, and score clamping. Note: Vitest/`@vue/test-utils` not yet installed in this project — spec file is ready to run once configured.

### File List

- `src/main/resources/db/migration/V51__radar_display_correlation.sql`
- `src/main/java/com/softropic/skillars/platform/development/repo/PlayerRadarBaselineId.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/PlayerRadarBaseline.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/PlayerRadarBaselineRepository.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/CoachRadarPreferenceId.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/CoachRadarPreference.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/CoachRadarPreferenceRepository.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/SluRepository.java` (modified — added `sumSluBySkill`, `countDistinctSessions`)
- `src/main/java/com/softropic/skillars/platform/development/contract/SkillRadarEntry.java`
- `src/main/java/com/softropic/skillars/platform/development/contract/RadarDisplayResponse.java`
- `src/main/java/com/softropic/skillars/platform/development/contract/CoachRadarPreferenceRequest.java`
- `src/main/java/com/softropic/skillars/platform/development/contract/CoachRadarPreferenceResponse.java`
- `src/main/java/com/softropic/skillars/platform/development/contract/CorrelationInsightType.java`
- `src/main/java/com/softropic/skillars/platform/development/contract/CorrelationInsight.java`
- `src/main/java/com/softropic/skillars/platform/development/contract/CorrelationResponse.java`
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java` (modified — injected `baselineRepository`, added baseline write after composite upsert)
- `src/main/java/com/softropic/skillars/platform/development/service/RadarDisplayService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/DevelopmentCorrelationService.java`
- `src/main/java/com/softropic/skillars/platform/development/api/RadarDisplayResource.java`
- `src/test/java/com/softropic/skillars/platform/development/service/DevelopmentCorrelationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculatorTest.java` (modified — added `baselineRepository` mock)
- `src/test/java/com/softropic/skillars/platform/development/api/RadarDisplayResourceIT.java`
- `src/frontend/src/api/development.api.js` (modified — added 4 functions)
- `src/frontend/src/stores/development.store.js` (modified — added 5 state refs + 4 actions)
- `src/frontend/src/components/development/SkillsRadarChart.vue`
- `src/frontend/src/components/development/DevelopmentCorrelationPanel.vue`
- `src/frontend/src/components/development/__tests__/SkillsRadarChartSpec.js`
- `src/frontend/src/pages/player/PlayerDevelopmentDashboardPage.vue` (modified — added radar chart, correlation panel, fetch calls, handlers)
- `src/frontend/src/i18n/en-US/index.js` (modified — added radar display + correlation keys)
- `src/frontend/src/i18n/fr-FR/index.js` (modified — added radar display + correlation keys)
- `src/frontend/src/i18n/de/index.js` (modified — added radar display + correlation keys)
- `src/frontend/src/i18n/en/index.js` (modified — added radar display + correlation keys)

## Review Findings

### Decision-Needed

- [x] [Review][Decision] Non-Academy teaser has no clickable upgrade CTA — AC6/UX-DR22 says "Academy feature — upgrade to unlock CTA" but the panel only shows a lock icon and label text with no button, link, or router-push. What route/action should the CTA target? [`DevelopmentCorrelationPanel.vue:19-27`]

### Patches

- [x] [Review][Patch] `DevelopmentCorrelationPanel` missing render branch for "sufficient data but zero insights" — when `insufficientData=false` and `insights.length=0` (all SLU skills excluded because none have composites), the panel silently renders nothing; the `excludedSkills` footnote is also hidden since it lives inside the insights branch [`DevelopmentCorrelationPanel.vue:46`]
- [x] [Review][Patch] Ghost polygon plots null-baseline active skills at chart center + `hasBaseline` toggle mismatch — `baselinePoints` maps null-baseline skills to `toPoint(…, null)` → plots at CENTER_X,CENTER_Y; separately, dashboard `hasBaseline` checks all 15 skills while chart `hasBaseline` checks `activeSkills` only — toggle can show when no selected skill has a baseline, clicking it silently does nothing [`SkillsRadarChart.vue:190,212-218`; `PlayerDevelopmentDashboardPage.vue:143`]
- [x] [Review][Patch] 1-skill or 2-skill polygon is degenerate — SVG `<polygon>` with 1 point renders invisible; with 2 points renders a line; no guard or user message for coaches who select ≤2 skills from preferences [`SkillsRadarChart.vue:203-209`]
- [x] [Review][Patch] Duplicate skill codes accepted in `CoachRadarPreferenceRequest` — `@Size(max=15)` allows `["PAC","PAC",…]` (15 copies); stored verbatim in `selected_skills[]`; frontend filter deduplicates silently but DB holds corrupt data [`CoachRadarPreferenceRequest.java:9`]
- [x] [Review][Patch] `DevelopmentCorrelationService.I18N_KEYS` uses wrong i18n path — backend stores `"development.correlation.insight.highSluImprovement"` but actual i18n key is `"development.radar.correlation.insight.highSluImprovement"` (missing `radar.` segment); frontend ignores `insightTextKey` and reconstructs from `insightType` so no runtime failure, but the contract field is wrong and any future consumer would get an unresolvable key [`DevelopmentCorrelationService.java:36-39`]
- [x] [Review][Patch] Parent sees `cursor: pointer` on radar nodes that silently no-op on click — `.radar-node { cursor: pointer }` applied unconditionally; `onSkillSelectionChange` guards `isCoach` but the misleading cursor remains for parents [`SkillsRadarChart.vue:273`]

### Deferred

- [x] [Review][Defer] No FK from `player_radar_baselines.player_id` / `coach_radar_preferences.player_id` to `main.player_profiles` [`V51__radar_display_correlation.sql`] — deferred, pre-existing accepted limitation per spec dev notes; consistent with Stories 5.1–5.3 no-FK pattern across `development.*` tables
- [x] [Review][Defer] Rapid skill-toggle fires a PUT per click — no debounce; last-write-wins for fast toggling; low risk [`PlayerDevelopmentDashboardPage.vue`] — deferred, pre-existing UX pattern for preference saves
- [x] [Review][Defer] `insertBaselineIfAbsent` `@Transactional` participates in outer transaction — `ON CONFLICT DO NOTHING` cannot protect across a rollback on first-ever baseline write [`PlayerRadarBaselineRepository.java`] — deferred, documented MVP limitation in spec dev notes
- [x] [Review][Defer] Skill deactivation silently drops baseline from display — `findAllByActiveTrueOrderByDisplayOrderAsc` excludes inactive skills; baseline data re-appears on reactivation [`RadarDisplayService.java:39`] — deferred, established skill-lifecycle pattern across the module
- [x] [Review][Defer] IT `assertThat(minimumSessionCount).isEqualTo(5L)` hardcodes config value — `ON CONFLICT DO NOTHING` in migration means a non-fresh DB could diverge [`RadarDisplayResourceIT.java:333`] — deferred, low risk with Testcontainers fresh-container pattern
- [x] [Review][Defer] Any ACADEMY coach can call `GET /radar/correlation` for any player — no player-coach ownership check; consistent with `GET /radar/display` which also lacks it [`RadarDisplayResource.java:64-69`] — deferred, architectural pattern consistent with existing coach-scoped endpoints; see DEF5 in deferred-work.md
- [x] [Review][Defer] `IMPROVEMENT_THRESHOLD = 3.0` hardcoded — exactly-3-point improvement classified as "no improvement"; not configurable without a deploy [`DevelopmentCorrelationService.java:33`] — deferred, explicitly accepted in spec dev notes
- [x] [Review][Defer] `(int)` cast on `totalCount` in `RadarCompositeCalculationService` — pre-existing silent overflow for very high entry counts — deferred, pre-existing code not modified in this diff; tracked in prior deferred items
- [x] [Review][Defer] `SkillsRadarChartSpec.js` tests cannot run — vitest / `@vue/test-utils` not installed in the project [`src/frontend/src/components/development/__tests__/SkillsRadarChartSpec.js`] — deferred, explicitly accepted in story completion notes; frontend test-runner setup is a separate initiative

## Change Log

| Date | Change | Author |
|---|---|---|
| 2026-06-19 | Story 5.4 created | claude-sonnet-4-6 |
| 2026-06-19 | Story 5.4 fully implemented — V51 migration, 7 repo/entity classes, 7 contract types, 2 services, 1 REST resource, 2 test classes (10+13 tests), 4 Vue components/specs, 4 i18n locale files; Maven build + Quasar build green | claude-sonnet-4-6 |
