# Story skillars-5.1: SLU Engine & Skill Taxonomy

Status: done

## Story

As a platform operator,
I want SLU (Skill Load Units) automatically calculated and recorded as an immutable snapshot when a session completes,
so that player development data is accurate, tamper-proof, and never affected by future drill metadata changes.

## Acceptance Criteria

**AC 1: `platform.development` module initialised — schema and taxonomy** — Given the Flyway migration runs, then a `development` schema exists; a `skill_definitions` table exists (code VARCHAR PK, displayName VARCHAR, displayOrder SMALLINT, active BOOLEAN DEFAULT true) seeded with all 15 skills: PAC, SHO, PAS, DRI, PHY, DEF, WEF, F1T, FIN, 1V1, HED, CRO, IBS, OBS, FKI; new skill codes can be inserted into `skill_definitions` without altering existing `player_skill_stats` rows — extensibility is structural (FR-DEV-001).

**AC 2: SLU rows written on session completion** — Given a session transitions to `COMPLETED` and `BookingCompletedEvent` is published (AFTER_COMMIT), when the `@TransactionalEventListener` in `SluCalculationService` fires, then the service resolves the `Session` from `bookingId` via `SessionRepository.findByBookingId`, iterates all blocks and their drill refs, batch-loads `Drill` entities, and for each skill with a non-zero SLU contribution, writes one `player_skill_stats` row.

**AC 3: SLU formula** — Given `SluFormula.calculate(drillMetadata, durationMinutes, intensityScale, pressureScale, matchRealismScale)` is called, then the contribution per skill is: `repDensity × skillWeighting[skill] × (intensity × intensityScale) × (pressureLevel × pressureScale) × (matchRealism × matchRealismScale) × durationMinutes`; only skills with a positive result are included in the returned map; modifier scaling factors are read from ConfigService (`slu.intensity.scale`, `slu.pressure.scale`, `slu.matchRealism.scale`) per-invocation (never field-cached).

**AC 4: `player_skill_stats` rows are immutable** — Given `player_skill_stats` rows are written, when any code path attempts to update or delete an existing row, then there is no such code path — `SluRepository` exposes only `save()`, `saveAll()`, and read methods; no `update()`, `deleteById()`, or `delete()` method is declared; the migration includes a `COMMENT` documenting immutability (FR-DEV-003).

**AC 5: Historical data unaffected by drill metadata changes** — Given a drill's `metadata` JSONB is updated after a session completes, when historical `player_skill_stats` are queried, then those rows are unchanged — SLU values baked at completion time remain as-is; this is structural (rows are written at completion, never recalculated).

**AC 6: No session plan → no SLU** — Given `BookingCompletedEvent` fires for a Quick Complete booking that had no Session Builder session (no `session.sessions` row for that `bookingId`), then `SessionRepository.findByBookingId` returns empty, the listener logs a debug message and exits without writing any `player_skill_stats` rows; no exception is thrown; the booking's `COMPLETED` status is unaffected.

**AC 7: Session with no drills → no SLU** — Given a session record exists but its `blocks` list is empty or all blocks contain no `SessionDrillRef` entries, when the listener fires, then a warning is logged and no rows are written.

**AC 8: Player did not attend → no SLU** — Given `BookingCompletedEvent` fires with `playerAttended = false` (the player no-showed), regardless of whether a session plan exists, when the listener fires, then a debug message is logged and no `player_skill_stats` rows are written; this check runs before any DB lookup.

**AC 9: DRAFT session → no SLU** — Given a session record exists but its `status` is `DRAFT` (coach opened Session Builder but did not save before the booking was completed), when the listener fires, then a warning is logged and no rows are written; only `SAVED` or `COMPLETED` sessions produce SLU.

## Tasks / Subtasks

### Backend — Database Migration

- [x] **Task 1: Write `V46__development_module_init.sql`** (AC: 1, 2, 4)
  - [x] File: `src/main/resources/db/migration/V46__development_module_init.sql`
  - [x] Previous migration: V45 (`homework_assignments`). This must be V46.
  - [x] SQL:
    ```sql
    -- Story 5.1: development module — SLU engine & skill taxonomy

    -- Schema
    CREATE SCHEMA IF NOT EXISTS development;

    -- Skill taxonomy (extensible: new codes can be inserted without schema change)
    CREATE TABLE development.skill_definitions (
        code            VARCHAR(10)  PRIMARY KEY,
        display_name    VARCHAR(100) NOT NULL,
        display_order   SMALLINT     NOT NULL,
        active          BOOLEAN      NOT NULL DEFAULT true
    );

    -- IMMUTABLE: player_skill_stats rows are append-only — no update path exists.
    -- Rows bake in SLU values at session completion time; historical data is tamper-proof.
    -- Do not add UPDATE or DELETE operations to SluRepository.
    CREATE TABLE development.player_skill_stats (
        id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
        player_id       BIGINT        NOT NULL,   -- TSID Long; NOT UUID despite epics spec wording
        session_id      UUID,                      -- nullable (Quick Complete has no session plan)
        coach_id        UUID          NOT NULL,
        skill_code      VARCHAR(10)   NOT NULL REFERENCES development.skill_definitions(code),
        slu_value       NUMERIC(10,4) NOT NULL,
        calculated_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
    );

    CREATE INDEX idx_player_skill_stats_player_id    ON development.player_skill_stats (player_id);
    CREATE INDEX idx_player_skill_stats_session_id   ON development.player_skill_stats (session_id);
    CREATE INDEX idx_player_skill_stats_player_skill ON development.player_skill_stats (player_id, skill_code);

    -- Seed 15 skill definitions (FR-DEV-001)
    INSERT INTO development.skill_definitions (code, display_name, display_order) VALUES
        ('PAC', 'Pace',               1),
        ('SHO', 'Shooting',           2),
        ('PAS', 'Passing',            3),
        ('DRI', 'Dribbling',          4),
        ('PHY', 'Physicality',        5),
        ('DEF', 'Defending',          6),
        ('WEF', 'Weak Foot',          7),
        ('F1T', 'First Touch',        8),
        ('FIN', 'Finishing',          9),
        ('1V1', 'One vs One',        10),
        ('HED', 'Heading',           11),
        ('CRO', 'Crossing',          12),
        ('IBS', 'In Behind Runs',    13),
        ('OBS', 'Off-Ball Scanning', 14),
        ('FKI', 'Free Kick Inst.',   15);

    -- SLU modifier scaling factors (tunable via admin config panel)
    -- Next available platform_config IDs after V42 (last: 67): using 70-72
    INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at) VALUES
        (70, 'slu.intensity.scale',    '0.10', 'STRING', 'SLU intensity modifier scale (intensity × scale = multiplier)', NOW()),
        (71, 'slu.pressure.scale',     '0.10', 'STRING', 'SLU pressure modifier scale (pressureLevel × scale = multiplier)', NOW()),
        (72, 'slu.matchRealism.scale', '0.10', 'STRING', 'SLU match realism modifier scale (matchRealism × scale = multiplier)', NOW())
    ON CONFLICT (key) DO NOTHING;
    ```
  - [x] **CRITICAL**: `player_id` is BIGINT — NOT UUID. The epics spec says "playerId UUID" but this is incorrect. All player IDs in this system are TSIDs (Long/BIGINT). See `session.sessions.player_id BIGINT`, `booking.bookings.player_id BIGINT`, story 4.6 dev notes.
  - [x] **ID collision check**: IDs 68 and 69 are not used in any migration yet; IDs 70–72 are safe. If the developer finds a collision after checking all migrations, use the next available IDs and update the task spec.

### Backend — Module Config

- [x] **Task 2: Create `DevelopmentConfig.java`** (AC: 1)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/config/DevelopmentConfig.java`
  - [ ] Class:
    ```java
    package com.softropic.skillars.platform.development.config;

    import org.springframework.context.annotation.Configuration;

    @Configuration
    public class DevelopmentConfig {
    }
    ```

### Backend — Repository Layer

- [x] **Task 3: Create `SkillDefinition.java` entity** (AC: 1)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/repo/SkillDefinition.java`
  - [ ] Entity:
    ```java
    package com.softropic.skillars.platform.development.repo;

    import jakarta.persistence.*;
    import lombok.Getter;
    import lombok.NoArgsConstructor;
    import lombok.Setter;

    @Entity
    @Table(schema = "development", name = "skill_definitions")
    @Getter
    @Setter
    @NoArgsConstructor
    public class SkillDefinition {

        @Id
        @Column(length = 10)
        private String code;

        @Column(name = "display_name", nullable = false, length = 100)
        private String displayName;

        @Column(name = "display_order", nullable = false)
        private Short displayOrder;

        @Column(nullable = false)
        private Boolean active = true;
    }
    ```

- [x] **Task 4: Create `SkillDefinitionRepository.java`** (AC: 1)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/repo/SkillDefinitionRepository.java`
  - [ ] Interface:
    ```java
    package com.softropic.skillars.platform.development.repo;

    import org.springframework.data.jpa.repository.JpaRepository;
    import java.util.List;

    public interface SkillDefinitionRepository extends JpaRepository<SkillDefinition, String> {
        List<SkillDefinition> findAllByActiveTrueOrderByDisplayOrderAsc();
    }
    ```

- [x] **Task 5: Create `PlayerSkillStat.java` entity** (AC: 2, 4)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/repo/PlayerSkillStat.java`
  - [ ] Entity:
    ```java
    package com.softropic.skillars.platform.development.repo;

    import jakarta.persistence.*;
    import lombok.Getter;
    import lombok.NoArgsConstructor;
    import lombok.Setter;

    import java.math.BigDecimal;
    import java.time.Instant;
    import java.util.UUID;

    // IMMUTABLE: append-only — never update or delete rows once written
    @Entity
    @Table(schema = "development", name = "player_skill_stats")
    @Getter
    @Setter
    @NoArgsConstructor
    public class PlayerSkillStat {

        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;

        @Column(name = "player_id", nullable = false, updatable = false)
        private Long playerId;  // TSID Long — NOT UUID despite epics spec

        @Column(name = "session_id", updatable = false)
        private UUID sessionId;

        @Column(name = "coach_id", nullable = false, updatable = false)
        private UUID coachId;

        @Column(name = "skill_code", nullable = false, length = 10, updatable = false)
        private String skillCode;

        @Column(name = "slu_value", nullable = false, precision = 10, scale = 4, updatable = false)
        private BigDecimal sluValue;

        @Column(name = "calculated_at", nullable = false, updatable = false)
        private Instant calculatedAt;
    }
    ```

- [x] **Task 6: Create `SluRepository.java`** (AC: 2, 4)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/repo/SluRepository.java`
  - [ ] Interface — save and reads ONLY:
    ```java
    package com.softropic.skillars.platform.development.repo;

    import org.springframework.data.jpa.repository.JpaRepository;
    import java.util.List;
    import java.util.UUID;

    // IMMUTABLE: append-only — do NOT add delete() or update-path methods
    public interface SluRepository extends JpaRepository<PlayerSkillStat, UUID> {

        List<PlayerSkillStat> findByPlayerIdOrderByCalculatedAtDesc(Long playerId);

        List<PlayerSkillStat> findByPlayerIdAndSkillCode(Long playerId, String skillCode);

        List<PlayerSkillStat> findBySessionId(UUID sessionId);
    }
    ```
  - [x] **Do NOT declare** `delete`, `deleteById`, `deleteAll`, `deleteAllById`, or any update-returning method. The inherited `JpaRepository` signatures for these exist but the intent is that no code path calls them. Add a `// IMMUTABLE` comment at the interface level.

### Backend — Service Layer (Pure Formula)

- [x] **Task 7: Create `SluFormula.java`** (AC: 3)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/service/SluFormula.java`
  - [ ] Class — pure Java, no Spring beans:
    ```java
    package com.softropic.skillars.platform.development.service;

    import com.softropic.skillars.platform.session.contract.DrillMetadata;

    import java.math.BigDecimal;
    import java.math.RoundingMode;
    import java.util.HashMap;
    import java.util.Map;

    public final class SluFormula {

        private SluFormula() {}

        /**
         * Calculates SLU contribution per skill for a single drill.
         *
         * Formula per skill:
         *   slu = repDensity × weight × (intensity × intensityScale)
         *              × (pressureLevel × pressureScale)
         *              × (matchRealism × matchRealismScale)
         *              × durationMinutes
         *
         * @param metadata       drill metadata snapshot at session time
         * @param durationMinutes allocated time for this drill (blockDuration / drillsInBlock)
         * @param intensityScale  from ConfigService key "slu.intensity.scale"
         * @param pressureScale   from ConfigService key "slu.pressure.scale"
         * @param matchRealismScale from ConfigService key "slu.matchRealism.scale"
         * @return map of skillCode → SLU value; only skills with positive contribution included
         */
        public static Map<String, BigDecimal> calculate(
                DrillMetadata metadata,
                int durationMinutes,
                BigDecimal intensityScale,
                BigDecimal pressureScale,
                BigDecimal matchRealismScale) {

            Map<String, BigDecimal> result = new HashMap<>();
            if (metadata == null
                    || metadata.skillWeighting() == null
                    || metadata.skillWeighting().isEmpty()
                    || durationMinutes <= 0) {
                return result;
            }

            BigDecimal intensityM = BigDecimal.valueOf(metadata.intensity()).multiply(intensityScale);
            BigDecimal pressureM  = BigDecimal.valueOf(metadata.pressureLevel()).multiply(pressureScale);
            BigDecimal matchM     = BigDecimal.valueOf(metadata.matchRealism()).multiply(matchRealismScale);
            BigDecimal duration   = BigDecimal.valueOf(durationMinutes);
            BigDecimal repD       = BigDecimal.valueOf(metadata.repDensity());

            for (Map.Entry<String, Integer> entry : metadata.skillWeighting().entrySet()) {
                int weight = entry.getValue();
                if (weight <= 0) continue;

                BigDecimal slu = repD
                    .multiply(BigDecimal.valueOf(weight))
                    .multiply(intensityM)
                    .multiply(pressureM)
                    .multiply(matchM)
                    .multiply(duration)
                    .setScale(4, RoundingMode.HALF_UP);

                if (slu.compareTo(BigDecimal.ZERO) > 0) {
                    result.put(entry.getKey(), slu);
                }
            }
            return result;
        }
    }
    ```
  - [x] **No Spring annotations** — `SluFormula` is a pure static utility class. `SluCalculationService` reads config values then passes them as parameters. This makes the formula independently testable without Spring context.

### Backend — Service Layer (Event Listener)

- [x] **Task 8: Create `SluCalculationService.java`** (AC: 2, 3, 5, 6, 7, 8, 9)
  - [x] File: `src/main/java/com/softropic/skillars/platform/development/service/SluCalculationService.java`
  - [ ] Class:
    ```java
    package com.softropic.skillars.platform.development.service;

    import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
    import com.softropic.skillars.platform.config.service.ConfigService;
    import com.softropic.skillars.platform.development.repo.PlayerSkillStat;
    import com.softropic.skillars.platform.development.repo.SluRepository;
    import com.softropic.skillars.platform.session.contract.SessionBlockData;
    import com.softropic.skillars.platform.session.contract.SessionDrillRef;
    import com.softropic.skillars.platform.session.repo.Drill;
    import com.softropic.skillars.platform.session.repo.DrillRepository;
    import com.softropic.skillars.platform.session.repo.Session;
    import com.softropic.skillars.platform.session.repo.SessionRepository;
    import lombok.RequiredArgsConstructor;
    import lombok.extern.slf4j.Slf4j;
    import org.springframework.scheduling.annotation.Async;
    import org.springframework.stereotype.Service;
    import org.springframework.transaction.event.TransactionPhase;
    import org.springframework.transaction.event.TransactionalEventListener;

    import java.math.BigDecimal;
    import java.time.Instant;
    import java.util.ArrayList;
    import java.util.HashMap;
    import java.util.LinkedHashSet;
    import java.util.List;
    import java.util.Map;
    import java.util.Optional;
    import java.util.Set;
    import java.util.UUID;
    import java.util.stream.Collectors;

    @Service
    @Slf4j
    @RequiredArgsConstructor
    public class SluCalculationService {

        private final SessionRepository sessionRepository;
        private final DrillRepository drillRepository;
        private final SluRepository sluRepository;
        private final ConfigService configService;

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        @Async
        public void onBookingCompleted(BookingCompletedEvent event) {
            // No-show: player did not attend — SLU is only earned for attended sessions (AC 8)
            if (!event.isPlayerAttended()) {
                log.debug("Player did not attend booking {} — SLU skipped", event.getBookingId());
                return;
            }

            Optional<Session> sessionOpt = sessionRepository.findByBookingId(event.getBookingId());
            if (sessionOpt.isEmpty()) {
                log.debug("No session plan for booking {} — SLU skipped (Quick Complete or no session builder usage)",
                    event.getBookingId());
                return;
            }
            Session session = sessionOpt.get();

            // Only SAVED or COMPLETED sessions have meaningfully committed block content (AC 9)
            String sessionStatus = session.getStatus();
            if (!"SAVED".equals(sessionStatus) && !"COMPLETED".equals(sessionStatus)) {
                log.warn("Session {} for booking {} is in status {} — SLU skipped",
                    session.getId(), event.getBookingId(), sessionStatus);
                return;
            }

            if (session.getBlocks() == null || session.getBlocks().isEmpty()) {
                log.warn("Session {} for booking {} has no blocks — SLU skipped",
                    session.getId(), event.getBookingId());
                return;
            }

            // Resolve scale factors per-invocation (never cache in field — ConfigService refreshes on its own schedule)
            BigDecimal intensityScale;
            BigDecimal pressureScale;
            BigDecimal matchRealismScale;
            try {
                intensityScale    = new BigDecimal(configService.getString("slu.intensity.scale"));
                pressureScale     = new BigDecimal(configService.getString("slu.pressure.scale"));
                matchRealismScale = new BigDecimal(configService.getString("slu.matchRealism.scale"));
            } catch (IllegalStateException | NumberFormatException e) {
                log.error("SLU calculation aborted for booking {} — invalid or missing SLU config: {}",
                    event.getBookingId(), e.getMessage());
                return;
            }

            // Collect unique drill IDs for batch loading (separate concern from per-block iteration below)
            Set<UUID> drillIds = new LinkedHashSet<>();
            boolean hasDrills = false;
            for (SessionBlockData block : session.getBlocks()) {
                if (block.drills() == null || block.drills().isEmpty()) continue;
                for (SessionDrillRef ref : block.drills()) {
                    drillIds.add(ref.drillId());
                    hasDrills = true;
                }
            }

            if (!hasDrills) {
                log.warn("Session {} has blocks but no drill assignments — SLU skipped", session.getId());
                return;
            }

            // Batch-load drills
            Map<UUID, Drill> drillMap = drillRepository.findAllById(drillIds)
                .stream().collect(Collectors.toMap(Drill::getId, d -> d));

            // Accumulate SLU per skill across all (block, drill) pairs.
            // The same drill in multiple blocks contributes independently per block — blocks are iterated
            // directly (not via a deduplicated drill map) so each appearance uses its own block's
            // allocated duration.
            Map<String, BigDecimal> totalSluPerSkill = new HashMap<>();
            for (SessionBlockData block : session.getBlocks()) {
                if (block.drills() == null || block.drills().isEmpty()) continue;
                int allocatedPerDrill = Math.max(1, Math.round((float) block.durationMinutes() / block.drills().size()));
                for (SessionDrillRef ref : block.drills()) {
                    Drill drill = drillMap.get(ref.drillId());
                    if (drill == null || drill.getMetadata() == null) {
                        log.warn("Drill {} referenced in session {} not found or has no metadata — skipping",
                            ref.drillId(), session.getId());
                        continue;
                    }

                    Map<String, BigDecimal> drillSlu = SluFormula.calculate(
                        drill.getMetadata(), allocatedPerDrill,
                        intensityScale, pressureScale, matchRealismScale);

                    drillSlu.forEach((skill, slu) ->
                        totalSluPerSkill.merge(skill, slu, BigDecimal::add));
                }
            }

            // Write one row per skill with positive SLU
            List<PlayerSkillStat> stats = new ArrayList<>();
            Instant now = Instant.now();
            for (Map.Entry<String, BigDecimal> skillEntry : totalSluPerSkill.entrySet()) {
                if (skillEntry.getValue().compareTo(BigDecimal.ZERO) <= 0) continue;
                PlayerSkillStat stat = new PlayerSkillStat();
                stat.setPlayerId(event.getPlayerId());
                stat.setSessionId(session.getId());
                stat.setCoachId(event.getCoachId());
                stat.setSkillCode(skillEntry.getKey());
                stat.setSluValue(skillEntry.getValue());
                stat.setCalculatedAt(now);
                stats.add(stat);
            }

            if (stats.isEmpty()) {
                log.info("Session {} produced zero SLU contributions (all zero weights) — no rows written",
                    session.getId());
                return;
            }

            sluRepository.saveAll(stats);
            log.info("SLU recorded: {} skill entries for session {} player {}",
                stats.size(), session.getId(), event.getPlayerId());
        }
    }
    ```
  - [x] **`@Async` is required** — `@TransactionalEventListener(AFTER_COMMIT)` runs in the committing thread by default; `@Async` offloads to a task executor so the booking completion transaction thread is not blocked. `@EnableAsync` is already present in the project (confirmed in `AsyncConfig` during story 4.6).
  - [x] **Cross-module dependencies** (acceptable in monolith; all direct DB access, no HTTP):
    - `platform.booking.contract.BookingCompletedEvent` — event source
    - `platform.session.repo.SessionRepository` — resolves session from bookingId
    - `platform.session.repo.DrillRepository` — batch-loads drill entities
    - `platform.session.repo.Drill` — drill entity
    - `platform.session.contract.DrillMetadata` — nested in `Drill.metadata`
    - `platform.session.contract.SessionBlockData` — nested in `Session.blocks`
    - `platform.session.contract.SessionDrillRef` — nested in `SessionBlockData.drills`
    - `platform.config.service.ConfigService` — reads SLU scale factors

### Backend — Tests

- [x] **Task 9: Create `SluFormulaTest.java`** (AC: 3)
  - [x] File: `src/test/java/com/softropic/skillars/platform/development/service/SluFormulaTest.java`
  - [x] Pure unit test — no Spring context:
    ```java
    @Test
    void calculate_withValidMetadata_returnsNonZeroSluForWeightedSkills() { ... }

    @Test
    void calculate_withZeroWeight_excludesSkillFromResult() { ... }

    @Test
    void calculate_withZeroDuration_returnsEmptyMap() { ... }

    @Test
    void calculate_withNullSkillWeighting_returnsEmptyMap() { ... }

    @Test
    void calculate_allFifteenSkills_scalesWithDuration() { ... }

    @Test
    void calculate_largerDuration_proportionallyIncreasesAllSlu() { ... }
    ```
  - [x] Use `DrillMetadata` constructor directly with known values; assert exact `BigDecimal` results using `AssertJ`'s `isEqualByComparingTo`.
  - [x] Cover all 15 skill codes to ensure formula handles `1V1` (numeric-prefixed code) correctly.

- [x] **Task 10: Create `SluCalculationServiceIT.java`** (AC: 2, 6, 7)
  - [x] File: `src/test/java/com/softropic/skillars/platform/development/service/SluCalculationServiceIT.java`
  - [x] `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` + `@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)` + `@MockitoBean VideoProviderAdapter`
  - [x] Inject: `ApplicationEventPublisher`, `JdbcTemplate`, `SessionRepository`, `DrillRepository`
  - [x] Test cases:
    - `onBookingCompleted_withStructuredSession_writesPlayerSkillStatRows` — insert a drill with non-zero skillWeighting; insert a session with one block and one drill ref; publish event with `playerAttended=true`; wait (Awaitility 3 seconds); assert `player_skill_stats` rows exist with correct `player_id`, `session_id`, `coach_id`, `skill_code`
    - `onBookingCompleted_playerNotAttended_writesNoRows` — publish event with `playerAttended=false`; session plan exists; assert zero `player_skill_stats` rows written
    - `onBookingCompleted_draftSession_writesNoRows` — insert session with `status = 'DRAFT'`; publish event with `playerAttended=true`; assert zero rows written
    - `onBookingCompleted_noSessionForBooking_writesNoRows` — publish event for booking with no `session.sessions` row; assert zero rows written
    - `onBookingCompleted_emptyBlocks_writesNoRows` — insert session with empty blocks list; assert zero rows
    - `onBookingCompleted_allZeroWeights_writesNoRows` — drill with `skillWeighting = {}` or all zero values; assert zero rows
    - `onBookingCompleted_drillRepeatedInMultipleBlocks_accumulatesSluFromBothBlocks` — insert drill D1 with non-zero weighting; insert session with two blocks both containing D1; publish event; assert that total SLU for each skill equals the sum of contributions from both block appearances (not just one block's worth)
    - `onBookingCompleted_missingDrill_skipsAndContinues` — insert session referencing a drill UUID that does not exist in `drill.drills`; assert no exception is thrown and any other valid drills in the session still produce rows
    - `onBookingCompleted_sluValuesAreImmutable_existingRowNotUpdated` — write a `PlayerSkillStat` row via `sluRepository.save()`; retrieve it; set a different `sluValue` on the returned entity; call `sluRepository.save()` again with the **same UUID**; flush and re-query from DB; assert the persisted `slu_value` is the **original** value — Hibernate's `updatable = false` on all `@Column` annotations prevents UPDATE SQL generation for existing rows
  - [x] Setup/teardown: use `@Sql` or direct JdbcTemplate inserts; `@AfterEach` cleans `development.player_skill_stats WHERE player_id = <test-player-id>`.
  - [x] **Awaitility usage** — since `@Async` runs the listener on a task executor thread, use `Awaitility.await().atMost(3, SECONDS).until(() -> countRows(...) > 0)`.

### Backend — Security Registration

- [x] **Task 11: Register `platform.development` API path in security configuration** (AC: 1)
  - [x] Search for `SecurityConstants` in the project (or the equivalent security filter chain configuration class that registers module API path patterns — follow the pattern used for `platform.session` and `platform.booking`)
  - [x] Add a path constant for `/api/development/**` (development module REST endpoints are introduced in Story 5.2; registering the path now ensures the security filter chain is correctly configured before any endpoint goes live)
  - [x] Apply the same auth requirements as other module API paths (authenticated + role-scoped via `@PreAuthorize` at method level; the filter chain entry should not open the path without auth)
  - [x] **This is a MODIFY, not a CREATE** — find the existing security configuration class rather than creating a new one

## Dev Notes

### playerId is BIGINT, NOT UUID — Epic spec bug

The epics dev note for `player_skill_stats` says `playerId UUID` — **this is wrong**. All player IDs in this system are TSIDs (Long/BIGINT). Evidence:
- `session.sessions.player_id BIGINT` (V43 migration)
- `booking.bookings.player_id BIGINT` (V31 migration)
- `PlayerProfile` uses `@Tsid Long id`
- Story 4.6 dev notes explicitly corrected the same epic spec error for `homework_assignments`

**Always use `Long playerId` (BIGINT) in `PlayerSkillStat` and all development module entities.**

### New Module Initialization

`platform.development` does not exist yet. The developer must create ALL directories and files from scratch:
```
src/main/java/com/softropic/skillars/platform/development/
  config/DevelopmentConfig.java
  repo/SkillDefinition.java
  repo/SkillDefinitionRepository.java
  repo/PlayerSkillStat.java
  repo/SluRepository.java
  service/SluFormula.java
  service/SluCalculationService.java

src/test/java/com/softropic/skillars/platform/development/
  service/SluFormulaTest.java
  service/SluCalculationServiceIT.java
```

### `@TransactionalEventListener(AFTER_COMMIT) + @Async` Pattern

Identical to `HomeworkAssignmentService.handleBookingCompleted()` in story 4.6. The booking completion transaction commits first, then Spring fires the `@TransactionalEventListener(AFTER_COMMIT)` callback. `@Async` moves the execution off the committing thread into a task executor, so the caller is not blocked. `@EnableAsync` was confirmed to exist in `AsyncConfig` during story 4.6 — **do NOT add it again**.

### `playerAttended` Guard — Must Run Before Any DB Lookup

`BookingCompletedEvent` carries `playerAttended: boolean`. When `false`, the player was registered but did not attend. SLU must not be recorded — the player earned nothing. The guard runs **first**, before the `SessionRepository` lookup, because it is cheaper than a DB query and semantically correct: attendance is a prerequisite for all SLU regardless of what session plan exists (AC 8).

### Session Status Guard — DRAFT Sessions Do Not Produce SLU

`session.sessions.status` has three valid values: `DRAFT`, `SAVED`, `COMPLETED`. A `DRAFT` session means the coach opened Session Builder but never committed the plan. Only `SAVED` and `COMPLETED` sessions have blocks that were deliberately finalized. The status check (`!"SAVED".equals(status) && !"COMPLETED".equals(status)`) guards against partial block lists being used for SLU calculation (AC 9).

### Drill Metadata Snapshot Gap — Acknowledged Limitation

AC 5 guarantees SLU rows are unaffected by drill metadata changes *after session completion*. However, SLU is computed from the drill's **current** metadata at the moment the event fires — not the metadata when the session was built. A drill's `repDensity` or `skillWeighting` edited between session creation and booking completion will silently affect the SLU. Snapshotting metadata into `SessionDrillRef` at build time would require schema changes; this is accepted for now and deferred to a future story if precision becomes a requirement.

### Same Drill in Multiple Blocks — Each Appearance Contributes Independently

`SluCalculationService` iterates `(block, drillRef)` pairs directly — it does **not** build a deduplicated `Map<drillId, duration>`. If drill D1 appears in Block A (10 min, 2 drills → 5 min each) and Block B (15 min, 3 drills → 5 min each), D1 contributes SLU for 5 min from Block A **and** 5 min from Block B, independently. The batch-load step still uses a deduplicated `Set<UUID>` for the DB query; only the SLU accumulation loop iterates pairs directly.

### Config Error Handling — Fail-Safe on Bad Values

`configService.getString(key)` throws `IllegalStateException` for missing keys; `new BigDecimal(value)` throws `NumberFormatException` if an admin sets a non-numeric value via the config panel. Both are caught in `onBookingCompleted`. The method logs at ERROR (with the bookingId for traceability) and returns without writing rows. The booking's `COMPLETED` status is unaffected. Recovery path: `SELECT s.id FROM session.sessions s WHERE NOT EXISTS (SELECT 1 FROM development.player_skill_stats p WHERE p.session_id = s.id)` identifies sessions with missing SLU for manual replay.

### No Automatic Retry on Transient DB Failure

`@Async` event listeners do not participate in Spring Retry by default. If `sluRepository.saveAll()` fails on a transient DB error, the thread logs the exception and exits — SLU rows for that session are permanently lost unless manually replayed. The recovery query above can identify affected sessions. Automatic retry is deferred to a future story.

### `SessionRepository.findByBookingId` Already Exists

The method `Optional<Session> findByBookingId(UUID bookingId)` was confirmed present in `SessionRepository` (used by `SessionPlanService` and story 4.6). **Do NOT add a duplicate declaration.**

### `DrillRepository.findAllById` — Inherited

`DrillRepository extends JpaRepository<Drill, UUID>` — `findAllById(Iterable<UUID> ids)` is inherited from `JpaRepository`. No new query method needed for batch drill loading.

### Session Data Structure for SLU Traversal

```
Session.blocks: List<SessionBlockData>
  SessionBlockData:
    blockType: String
    blockName: String
    durationMinutes: int
    drills: List<SessionDrillRef>
      SessionDrillRef:
        drillId: UUID
        order: int

Drill.metadata: DrillMetadata
  DrillMetadata:
    skillWeighting: Map<String, Integer>  ← skill code → weight (1–10)
    repDensity: int                        ← reps per unit time
    intensity: int                         ← 1–10
    pressureLevel: int                     ← 1–10
    matchRealism: int                      ← 1–10
    ...
```

Duration per drill = `Math.max(1, Math.round((float) block.durationMinutes() / block.drills().size()))` (rounded integer, minimum 1 minute — avoids systematic undercount from pure integer truncation).

### ConfigService — getString Only (No getBigDecimal)

`ConfigService` has only `getString(key)`, `getLong(key)`, `getBoolean(key)`. There is **no** `getBigDecimal()` or `getDouble()` method. For SLU scale factors (decimal values like `0.10`), use:
```java
BigDecimal intensityScale = new BigDecimal(configService.getString("slu.intensity.scale"));
```

### platform_config ID Namespace

Config IDs 60–67 are used by V38 (60–62) and V42 (63–67). IDs 68–69 are unused; IDs 70–72 are assigned to SLU scale factors in V46. If a collision is found when running migrations, use the next available sequence block.

### Cross-Module Dependency Direction

```
platform.development → platform.session.repo  (SessionRepository, DrillRepository, Drill, DrillMetadata, SessionBlockData, SessionDrillRef)
platform.development → platform.booking.contract  (BookingCompletedEvent)
platform.development → platform.config.service    (ConfigService)
```

No circular dependency: `platform.session` does NOT import from `platform.development`. The direction is safe.

### SluFormula — Numeric Ranges

With default scale values of `0.10`:
- Example: `repDensity=8, skillWeight=5, intensity=7, pressureLevel=6, matchRealism=5, duration=5min`
- `slu = 8 × 5 × (7×0.10) × (6×0.10) × (5×0.10) × 5 = 8 × 5 × 0.7 × 0.6 × 0.5 × 5 = 42.0 SLU`

Values will typically range 5–500 per drill per skill, which is the intended order of magnitude. Scale factors can be tuned via admin config if values need recalibrating.

### AC 5 (Historical Immutability) — Structural, Not Code-Enforced

The immutability of `player_skill_stats` is achieved structurally (no update path in `SluRepository`), not via DB triggers or Hibernate interceptors. The `updatable = false` on all `@Column` annotations in `PlayerSkillStat` ensures Hibernate never generates an UPDATE SQL for these fields. The migration comment documents intent. The test `onBookingCompleted_sluValuesAreImmutable_noUpdatePath` verifies this at the repository level.

### Project Structure Summary

| Component | Location | Status |
|---|---|---|
| V46 migration | `src/main/resources/db/migration/V46__development_module_init.sql` | CREATE |
| DevelopmentConfig | `src/main/java/com/softropic/skillars/platform/development/config/DevelopmentConfig.java` | CREATE |
| SkillDefinition entity | `src/main/java/com/softropic/skillars/platform/development/repo/SkillDefinition.java` | CREATE |
| SkillDefinitionRepository | `src/main/java/com/softropic/skillars/platform/development/repo/SkillDefinitionRepository.java` | CREATE |
| PlayerSkillStat entity | `src/main/java/com/softropic/skillars/platform/development/repo/PlayerSkillStat.java` | CREATE |
| SluRepository | `src/main/java/com/softropic/skillars/platform/development/repo/SluRepository.java` | CREATE |
| SluFormula (pure Java) | `src/main/java/com/softropic/skillars/platform/development/service/SluFormula.java` | CREATE |
| SluCalculationService | `src/main/java/com/softropic/skillars/platform/development/service/SluCalculationService.java` | CREATE |
| SluFormulaTest | `src/test/java/com/softropic/skillars/platform/development/service/SluFormulaTest.java` | CREATE |
| SluCalculationServiceIT | `src/test/java/com/softropic/skillars/platform/development/service/SluCalculationServiceIT.java` | CREATE |
| SecurityConstants (or equivalent) | locate via `grep -r "platform.session" src/main/java --include="*.java" -l` | MODIFY |

### References

- Epic 5 spec: Story 5.1 [`_bmad-output/planning-artifacts/skillars-epics.md` lines 1710–1749]
- Story 4.6 dev notes — `playerId is BIGINT not UUID`, `@Async` on `@TransactionalEventListener`, `@EnableAsync` in `AsyncConfig` [`_bmad-output/implementation-artifacts/skillars-4-6-homework-assignment-player-locker-room.md`]
- `BookingCompletedEvent.java` — fields: bookingId, coachId, playerId (Long), parentId, playerAttended (boolean), effortRating (Integer), focusRating (Integer), techniqueRating (Integer), homeworkDrillIds (List&lt;UUID&gt;) [`src/main/java/com/softropic/skillars/platform/booking/contract/BookingCompletedEvent.java`]
- `Session.java` — `blocks: List<SessionBlockData>` JSONB field [`src/main/java/com/softropic/skillars/platform/session/repo/Session.java`]
- `SessionBlockData.java` — `(blockType, blockName, durationMinutes, List<SessionDrillRef>)` [`src/main/java/com/softropic/skillars/platform/session/contract/SessionBlockData.java`]
- `SessionDrillRef.java` — `(UUID drillId, int order)` [`src/main/java/com/softropic/skillars/platform/session/contract/SessionDrillRef.java`]
- `DrillMetadata.java` — `skillWeighting: Map<String, Integer>`, `repDensity`, `intensity`, `pressureLevel`, `matchRealism` [`src/main/java/com/softropic/skillars/platform/session/contract/DrillMetadata.java`]
- `SessionRepository.java` — `findByBookingId(UUID)` already exists [`src/main/java/com/softropic/skillars/platform/session/repo/SessionRepository.java`]
- `DrillRepository.java` — `findAllById` inherited from `JpaRepository` [`src/main/java/com/softropic/skillars/platform/session/repo/DrillRepository.java`]
- `ConfigService.java` — `getString(key)` method; use `new BigDecimal(getString(...))` for decimal config values [`src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java`]
- `V38__session_module_init.sql` — pattern for schema creation + platform_config inserts [`src/main/resources/db/migration/V38__session_module_init.sql`]
- `BookingConfig.java` — minimal `@Configuration` module config pattern [`src/main/java/com/softropic/skillars/platform/booking/config/BookingConfig.java`]
- `BaseSessionIT.java` — integration test base pattern [`src/test/java/com/softropic/skillars/platform/session/api/BaseSessionIT.java`]
- `project-context.md` — DDD package structure, `@PreAuthorize` required, record DTOs, BIGINT for playerId [`_bmad-output/project-context.md`]

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

N/A — no significant debugging required. The only issue encountered was a duplicate `main.sec` PK error in the IT test caused by missing `@AfterEach` cleanup, fixed by adding `DELETE FROM main.sec` alongside the player_skill_stats cleanup.

### Completion Notes List

- All 11 tasks implemented end-to-end: migration V46, DevelopmentConfig, SkillDefinition entity, SkillDefinitionRepository, PlayerSkillStat entity, SluRepository, SluFormula (pure static), SluCalculationService (transactional event listener + async), SluFormulaTest (6 unit tests, all pass), SluCalculationServiceIT (9 integration tests, all pass), AppEndpoints DEVELOPMENT_API constant.
- `playerId` confirmed BIGINT (Long) throughout — epic spec says UUID but that is wrong as documented in Dev Notes.
- `@EnableAsync` was NOT added — confirmed it already exists in `notification.config.AsyncConfig`.
- `SluFormula.calculate()` verified: example `repDensity=8, weight=5, intensity=7, pressure=6, matchRealism=5, duration=5min, scale=0.10` → SLU=42.0 — matches dev notes.
- IT immutability test confirmed: Hibernate `updatable=false` on `PlayerSkillStat` columns prevents UPDATE SQL generation; re-querying from DB after a re-save returns the original value.
- Two-block accumulation test verified: same drill appearing in two blocks accumulates SLU from both (84.0 vs 42.0 for one block).

### File List

#### New Files
- `src/main/resources/db/migration/V46__development_module_init.sql`
- `src/main/java/com/softropic/skillars/platform/development/config/DevelopmentConfig.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/SkillDefinition.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/SkillDefinitionRepository.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/PlayerSkillStat.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/SluRepository.java`
- `src/main/java/com/softropic/skillars/platform/development/service/SluFormula.java`
- `src/main/java/com/softropic/skillars/platform/development/service/SluCalculationService.java`
- `src/test/java/com/softropic/skillars/platform/development/service/SluFormulaTest.java`
- `src/test/java/com/softropic/skillars/platform/development/service/SluCalculationServiceIT.java`

#### Modified Files
- `src/main/java/com/softropic/skillars/platform/security/config/AppEndpoints.java`

### Review Findings

#### Decision Needed

- [x] [Review][Decision] **Duration rounding: Math.round overcount** — Accepted as intentional approximation; minor overcount is an acknowledged tradeoff per dev notes. No change required. [SluCalculationService.java:114]
- [x] [Review][Decision → Patch] **Unknown skillCode from drill hits FK constraint, silently drops all session SLU** — Resolution: pre-validate skill codes against `SkillDefinitionRepository` before building the stats list; skip unknown codes with a WARN log; proceed with the valid subset. Fixed in `SluCalculationService.java`. [SluCalculationService.java:130]

#### Patches

- [x] [Review][Patch] **No AsyncUncaughtExceptionHandler + taskExecutor AbortPolicy silently drops SLU** — Fixed: `infrastructure.config.AsyncConfig` now implements `AsyncConfigurer` and registers an `AsyncUncaughtExceptionHandler` (logs at ERROR); `taskExecutor` bean now sets `CallerRunsPolicy` so the committing thread runs SLU if the pool is saturated. [AsyncConfig.java]
- [x] [Review][Patch] **Zero-duration block + Math.max(1,...) produces phantom SLU** — Fixed: added `if (block.durationMinutes() <= 0) continue;` guard before `allocatedPerDrill` calculation. [SluCalculationService.java:114]
- [x] [Review][Patch] **No uniqueness constraint on (session_id, skill_code) — duplicate rows on event re-delivery** — Fixed: new migration `V47__player_skill_stats_unique_constraint.sql` adds `UNIQUE (session_id, skill_code)` to `player_skill_stats`. [V47__player_skill_stats_unique_constraint.sql]
- [x] [Review][Patch] **insertSession() helper ignores numDrillsInBlock parameter** — Fixed: drills array now built dynamically; `numDrillsInBlock` drills are generated in the JSON template. [SluCalculationServiceIT.java:295]
- [x] [Review][Patch] **No IT test for COMPLETED session status producing SLU** — Fixed: added `onBookingCompleted_completedSession_writesPlayerSkillStatRows` test. [SluCalculationServiceIT.java]
- [x] [Review][Patch] **SluFormulaTest missing null-metadata test case** — Fixed: added `calculate_withNullMetadata_returnsEmptyMap` test. [SluFormulaTest.java]
- [x] [Review][Patch] **Negative weight path untested** — Fixed: added `calculate_withNegativeWeight_excludesSkillFromResult` test. [SluFormulaTest.java]

#### Deferred

- [x] [Review][Defer] **Negative metadata fields (repDensity/intensity/etc.) can produce corrupt SLU via double-negative** — Even number of negative metadata fields produces a false-positive SLU that passes the `> 0` guard and is written. Pre-existing validation gap at drill creation level, not in scope of this story. [SluFormula.java:45-66] — deferred, pre-existing drill validation gap
- [x] [Review][Defer] **@Async executor naming ambiguity** — ECH suggested SimpleAsyncTaskExecutor; BH found the bounded taskExecutor bean in AsyncConfig. Explicit qualifier (`@Async("taskExecutor")`) would eliminate ambiguity; largely covered by the AsyncUncaughtExceptionHandler patch above. [SluCalculationService.java:43] — deferred, covered by patch F-P1
- [x] [Review][Defer] **configService.getString whitespace/empty causes silent SLU abort** — Blank config value causes NumberFormatException at event time; already caught and logged at ERROR. Fix belongs at config write validation, which is a ConfigService concern outside this story. [SluCalculationService.java:78-85] — deferred, pre-existing ConfigService limitation
- [x] [Review][Defer] **Thread.sleep in negative-path IT tests** — Negative async tests use `Thread.sleep(1000L)` instead of Awaitility. Acceptable pattern when there is no positive signal to await; risk is trivial-pass on very slow CI. [SluCalculationServiceIT.java:107,125,135,171] — deferred, acceptable for negative-path async tests
- [x] [Review][Defer] **Platform config IDs 70-72 skip 68-69** — No migration uses 68-69; gap is documented in the migration comment. ON CONFLICT DO NOTHING prevents failures. [V46__development_module_init.sql:51-55] — deferred, intentional gap, no migration uses 68-69
- [x] [Review][Defer] **player_id and coach_id have no FK constraints** — Intentional design for an append-only audit table; cascading deletes would corrupt historical SLU on coach/player account deletion. [V46__development_module_init.sql:19,21] — deferred, intentional for immutable audit rows

### Review Findings (Second Pass — 2026-06-18)

#### Patches

- [x] [Review][Patch] **NPE escapes config-parse catch — `new BigDecimal(null)` not caught** — Added `NullPointerException` to the multi-catch. [SluCalculationService.java:83]
- [x] [Review][Patch] **V47 UNIQUE constraint permits duplicate rows when `session_id IS NULL`** — Changed to partial index: `UNIQUE (session_id, skill_code) WHERE session_id IS NOT NULL`. [V47__player_skill_stats_unique_constraint.sql]
- [x] [Review][Patch] **No idempotency pre-check — duplicate `BookingCompletedEvent` throws `DataIntegrityViolationException` instead of graceful skip** — Added `findBySessionId` guard before stats-building loop; duplicate events now log debug and return. [SluCalculationService.java]
- [x] [Review][Patch] **Misleading "all zero weights" log when actual cause is missing/filtered drills** — Changed to "produced no SLU contributions — no rows written". [SluCalculationService.java]
- [x] [Review][Patch] **`COMMENT ON TABLE` not added — immutability is a SQL line comment, invisible to `pg_description`** — Added `COMMENT ON TABLE development.player_skill_stats IS '...'` to V46. [V46__development_module_init.sql]
- [x] [Review][Patch] **No IT test for "blocks present but all drill lists empty" path (AC 7, second clause)** — Added `onBookingCompleted_blocksWithEmptyDrillsList_writesNoRows` test. [SluCalculationServiceIT.java]

#### Deferred

- [x] [Review][Defer] **No retry on `saveAll` failure — SLU permanently lost on transient DB error** — dev notes explicitly acknowledge and provide a recovery query; infrastructure-wide limitation [SluCalculationService.java:165] — deferred, acknowledged in dev notes
- [x] [Review][Defer] **`CallerRunsPolicy` can block HTTP thread under executor saturation** — prior review explicitly chose CallerRunsPolicy over AbortPolicy to avoid silent SLU loss; blocking is the accepted tradeoff [AsyncConfig.java:40] — deferred, explicit prior review decision
- [x] [Review][Defer] **Duration rounding over/under-counts block time** — prior review accepted as intentional approximation [SluCalculationService.java:121] — deferred, accepted in prior review
- [x] [Review][Defer] **`Thread.sleep` in negative-path IT tests** — prior review explicitly deferred; acceptable for negative async tests with no positive signal [SluCalculationServiceIT.java] — deferred, accepted in prior review
- [x] [Review][Defer] **No `booking_id` stored in `player_skill_stats` — no DB-level idempotency anchor** — schema design; behavioral gap covered by the idempotency pre-check patch above; column addition is out of story scope [V46__development_module_init.sql] — deferred, out of scope
- [x] [Review][Defer] **No guard on zero/negative `repDensity`/`intensity` metadata fields** — prior review deferred negative metadata case; zero repDensity produces no SLU silently [SluFormula.java] — deferred, pre-existing drill creation gap
- [x] [Review][Defer] **`NUMERIC(10,4)` overflow at extreme session attribute values** — theoretical at realistic gameplay values with default 0.10 scales [SluFormula.java, V46__development_module_init.sql] — deferred, theoretical at realistic values
- [x] [Review][Defer] **`SluRepository` inherits `deleteAll`/`deleteById` — no runtime enforcement of immutability** — AC 4 met ("no method declared"); comment warns developers; override-to-throw is defense-in-depth only [SluRepository.java] — deferred, spec met, improvement only
- [x] [Review][Defer] **Skill code case-sensitivity — lowercase `skillWeighting` keys silently dropped** — pre-existing drill creation validation gap; normalizing case here masks upstream data quality issues [SluCalculationService.java] — deferred, pre-existing drill creation gap

## Change Log

| Date | Version | Description | Author |
|------|---------|-------------|--------|
| 2026-06-18 | 1.0 | Story created — SLU Engine & Skill Taxonomy | claude-sonnet-4-6 |
| 2026-06-18 | 1.1 | Audit fixes: added AC 8 (playerAttended guard), AC 9 (DRAFT session guard), Task 11 (SecurityConstants); fixed duplicate-drill SLU bug in SluCalculationService (now iterates block-drill pairs directly); added try-catch on config loading; fixed integer division to use Math.round; added 4 new IT test cases; fixed immutability test; updated dev notes with metadata gap, retry limitation, config error handling | claude-sonnet-4-6 |
| 2026-06-18 | 1.2 | Full implementation: all 11 tasks complete. V46 migration, development module scaffold, SLU formula + service, 15 tests (6 unit + 9 IT) all passing. DEVELOPMENT_API constant added to AppEndpoints. | claude-sonnet-4-6 |
| 2026-06-18 | 1.3 | Code review complete — 2 decision-needed, 7 patches, 6 deferred, 5 dismissed | claude-sonnet-4-6 |
| 2026-06-18 | 1.4 | Second code review pass — 6 patches applied: NPE catch widened, V47 partial index, idempotency pre-check, log message clarity, COMMENT ON TABLE, AC 7 IT test; 9 deferred, 1 dismissed | claude-sonnet-4-6 |
