package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
import com.softropic.skillars.platform.development.repo.PlayerSkillStat;
import com.softropic.skillars.platform.development.repo.SluRepository;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "rate.limiting.enabled=false",
    "allowed.clients=testClientId"
})
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class SluCalculationServiceIT {

    private static final long   TEST_PLAYER_ID = 9900000001L;
    private static final UUID   TEST_COACH_ID  = UUID.fromString("99000000-0000-0000-0000-000000000001");

    // Drill metadata JSON using valid skill_definitions codes (PAC, SHO — seeded by V46 migration)
    private static final String DRILL_METADATA_JSON =
        "{\"primarySkills\":[\"PAC\"],\"secondarySkills\":[\"SHO\"]," +
        "\"skillWeighting\":{\"PAC\":5,\"SHO\":3}," +
        "\"repDensity\":8,\"intensity\":7,\"pressureLevel\":6,\"cognitiveLoad\":3,\"matchRealism\":5," +
        "\"weakFootBias\":false,\"difficultyTier\":\"U14\",\"equipmentRequired\":[\"ball\"]," +
        "\"recommendedGroupSize\":\"2\",\"coachingPoints\":[],\"setupDiagram\":null}";

    private static final String ZERO_WEIGHT_METADATA_JSON =
        "{\"primarySkills\":[],\"secondarySkills\":[]," +
        "\"skillWeighting\":{\"PAC\":0,\"SHO\":0}," +
        "\"repDensity\":5,\"intensity\":5,\"pressureLevel\":5,\"cognitiveLoad\":3,\"matchRealism\":5," +
        "\"weakFootBias\":false,\"difficultyTier\":\"U12\",\"equipmentRequired\":[]," +
        "\"recommendedGroupSize\":\"1\",\"coachingPoints\":[],\"setupDiagram\":null}";

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private SluRepository sluRepository;

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    @AfterEach
    void cleanUp() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM development.player_skill_stats WHERE player_id = ?", TEST_PLAYER_ID);
            jdbcTemplate.update("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void onBookingCompleted_withStructuredSession_writesPlayerSkillStatRows() {
        UUID drillId   = insertDrill(DRILL_METADATA_JSON);
        UUID bookingId = UUID.randomUUID();
        UUID sessionId = insertSession(bookingId, drillId, "SAVED", 10, 1);

        publishEventInTransaction(bookingId, true);

        await().atMost(3, SECONDS).until(() -> countStats() > 0);

        List<PlayerSkillStat> stats = sluRepository.findByPlayerIdOrderByCalculatedAtDesc(TEST_PLAYER_ID);
        assertThat(stats).isNotEmpty();
        assertThat(stats).allMatch(s -> s.getSessionId().equals(sessionId));
        assertThat(stats).allMatch(s -> s.getCoachId().equals(TEST_COACH_ID));
        assertThat(stats).allMatch(s -> s.getPlayerId().equals(TEST_PLAYER_ID));
        assertThat(stats.stream().map(PlayerSkillStat::getSkillCode).toList())
            .containsAnyOf("PAC", "SHO");

        cleanDrill(drillId);
        cleanSession(bookingId);
    }

    @Test
    void onBookingCompleted_completedSession_writesPlayerSkillStatRows() {
        UUID drillId   = insertDrill(DRILL_METADATA_JSON);
        UUID bookingId = UUID.randomUUID();
        UUID sessionId = insertSession(bookingId, drillId, "COMPLETED", 10, 1);

        publishEventInTransaction(bookingId, true);

        await().atMost(3, SECONDS).until(() -> countStats() > 0);

        List<PlayerSkillStat> stats = sluRepository.findByPlayerIdOrderByCalculatedAtDesc(TEST_PLAYER_ID);
        assertThat(stats).isNotEmpty();
        assertThat(stats).allMatch(s -> s.getSessionId().equals(sessionId));

        cleanDrill(drillId);
        cleanSession(bookingId);
    }

    @Test
    void onBookingCompleted_playerNotAttended_writesNoRows() throws Exception {
        UUID drillId   = insertDrill(DRILL_METADATA_JSON);
        UUID bookingId = UUID.randomUUID();
        insertSession(bookingId, drillId, "SAVED", 10, 1);

        publishEventInTransaction(bookingId, false);

        Thread.sleep(1000L);

        assertThat(countStats()).isZero();

        cleanDrill(drillId);
        cleanSession(bookingId);
    }

    @Test
    void onBookingCompleted_draftSession_writesNoRows() throws Exception {
        UUID drillId   = insertDrill(DRILL_METADATA_JSON);
        UUID bookingId = UUID.randomUUID();
        insertSession(bookingId, drillId, "DRAFT", 10, 1);

        publishEventInTransaction(bookingId, true);

        Thread.sleep(1000L);

        assertThat(countStats()).isZero();

        cleanDrill(drillId);
        cleanSession(bookingId);
    }

    @Test
    void onBookingCompleted_noSessionForBooking_writesNoRows() throws Exception {
        UUID bookingId = UUID.randomUUID(); // no session created for this booking

        publishEventInTransaction(bookingId, true);

        Thread.sleep(1000L);

        assertThat(countStats()).isZero();
    }

    @Test
    void onBookingCompleted_emptyBlocks_writesNoRows() throws Exception {
        UUID bookingId = UUID.randomUUID();
        // Insert session with empty blocks list
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO session.sessions (id, booking_id, coach_id, player_id, blocks, " +
                "equipment_list, development_focus, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, 'SAVED', NOW(), NOW())",
                bookingId, TEST_COACH_ID, TEST_PLAYER_ID
            );
            return null;
        });

        publishEventInTransaction(bookingId, true);

        Thread.sleep(1000L);

        assertThat(countStats()).isZero();

        cleanSession(bookingId);
    }

    @Test
    void onBookingCompleted_blocksWithEmptyDrillsList_writesNoRows() throws Exception {
        // AC 7 second clause: blocks exist but all drill lists are empty
        UUID bookingId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO session.sessions (id, booking_id, coach_id, player_id, blocks, " +
                "equipment_list, development_focus, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, '[{\"blockType\":\"MAIN\",\"blockName\":\"Main\"," +
                "\"durationMinutes\":10,\"drills\":[]}]'::jsonb, '[]'::jsonb, '[]'::jsonb, 'SAVED', NOW(), NOW())",
                bookingId, TEST_COACH_ID, TEST_PLAYER_ID
            );
            return null;
        });

        publishEventInTransaction(bookingId, true);

        Thread.sleep(1000L);

        assertThat(countStats()).isZero();

        cleanSession(bookingId);
    }

    @Test
    void onBookingCompleted_allZeroWeights_writesNoRows() throws Exception {
        UUID drillId   = insertDrill(ZERO_WEIGHT_METADATA_JSON);
        UUID bookingId = UUID.randomUUID();
        insertSession(bookingId, drillId, "SAVED", 10, 1);

        publishEventInTransaction(bookingId, true);

        Thread.sleep(1000L);

        assertThat(countStats()).isZero();

        cleanDrill(drillId);
        cleanSession(bookingId);
    }

    @Test
    void onBookingCompleted_drillRepeatedInMultipleBlocks_accumulatesSluFromBothBlocks() {
        UUID drillId   = insertDrill(DRILL_METADATA_JSON);
        UUID bookingId = UUID.randomUUID();
        // Two blocks, each containing the same drill (5 min each)
        String blocksJson = String.format(
            "[{\"blockType\":\"WARM_UP\",\"blockName\":\"Block A\",\"durationMinutes\":5," +
            "\"drills\":[{\"drillId\":\"%s\",\"order\":0}]}," +
            "{\"blockType\":\"MAIN\",\"blockName\":\"Block B\",\"durationMinutes\":5," +
            "\"drills\":[{\"drillId\":\"%s\",\"order\":0}]}]",
            drillId, drillId
        );
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO session.sessions (id, booking_id, coach_id, player_id, blocks, " +
                "equipment_list, development_focus, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, ?::jsonb, '[]'::jsonb, '[]'::jsonb, 'SAVED', NOW(), NOW())",
                bookingId, TEST_COACH_ID, TEST_PLAYER_ID, blocksJson
            );
            return null;
        });

        publishEventInTransaction(bookingId, true);

        await().atMost(3, SECONDS).until(() -> countStats() > 0);

        // Single-block single-drill SLU for reference — repDensity=8, weight=5, intensity=7,
        // pressure=6, matchRealism=5, intensityScale=0.10, pressureScale=0.10, matchRealismScale=0.10
        // duration=5min per block → slu_PAC = 8×5×0.7×0.6×0.5×5 = 42.0 per block → 84.0 total
        List<PlayerSkillStat> stats = sluRepository.findByPlayerIdOrderByCalculatedAtDesc(TEST_PLAYER_ID);
        PlayerSkillStat pac = stats.stream()
            .filter(s -> "PAC".equals(s.getSkillCode()))
            .findFirst()
            .orElse(null);
        assertThat(pac).isNotNull();
        // Two block appearances × 42.0 each = 84.0
        assertThat(pac.getSluValue()).isEqualByComparingTo(new BigDecimal("84.0000"));

        cleanDrill(drillId);
        cleanSession(bookingId);
    }

    @Test
    void onBookingCompleted_missingDrill_skipsAndContinues() {
        UUID missingDrillId = UUID.randomUUID();
        UUID realDrillId    = insertDrill(DRILL_METADATA_JSON);
        UUID bookingId      = UUID.randomUUID();

        String blocksJson = String.format(
            "[{\"blockType\":\"MAIN\",\"blockName\":\"Main\",\"durationMinutes\":10," +
            "\"drills\":[{\"drillId\":\"%s\",\"order\":0},{\"drillId\":\"%s\",\"order\":1}]}]",
            missingDrillId, realDrillId
        );
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO session.sessions (id, booking_id, coach_id, player_id, blocks, " +
                "equipment_list, development_focus, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, ?::jsonb, '[]'::jsonb, '[]'::jsonb, 'SAVED', NOW(), NOW())",
                bookingId, TEST_COACH_ID, TEST_PLAYER_ID, blocksJson
            );
            return null;
        });

        publishEventInTransaction(bookingId, true);

        // Real drill should still produce rows even though the first drill was missing
        await().atMost(3, SECONDS).until(() -> countStats() > 0);

        assertThat(countStats()).isPositive();

        cleanDrill(realDrillId);
        cleanSession(bookingId);
    }

    @Test
    void onBookingCompleted_sluValuesAreImmutable_existingRowNotUpdated() {
        // Save an initial stat row with slu_value = 1.0
        PlayerSkillStat original = new PlayerSkillStat();
        original.setPlayerId(TEST_PLAYER_ID);
        original.setSessionId(null);
        original.setCoachId(TEST_COACH_ID);
        original.setSkillCode("PAC");
        original.setSluValue(new BigDecimal("1.0000"));
        original.setCalculatedAt(Instant.now());
        PlayerSkillStat saved = sluRepository.saveAndFlush(original);

        // Load and attempt to modify the immutable fields
        PlayerSkillStat loaded = sluRepository.findById(saved.getId()).orElseThrow();
        loaded.setSluValue(new BigDecimal("999.0000"));
        sluRepository.saveAndFlush(loaded);

        // Re-query from DB — updatable=false prevents the UPDATE SQL; DB value unchanged
        BigDecimal dbValue = jdbcTemplate.queryForObject(
            "SELECT slu_value FROM development.player_skill_stats WHERE id = ?",
            BigDecimal.class, saved.getId()
        );
        assertThat(dbValue).isEqualByComparingTo(new BigDecimal("1.0000"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID insertDrill(String metadataJson) {
        UUID drillId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO session.drills (id, name, library_type, owner_coach_id, status, metadata, version) " +
                "VALUES (?, 'SLU Test Drill', 'PLATFORM', NULL, 'ACTIVE', ?::jsonb, 0)",
                drillId, metadataJson
            );
            return null;
        });
        return drillId;
    }

    private UUID insertSession(UUID bookingId, UUID drillId, String status, int blockDuration, int numDrillsInBlock) {
        UUID sessionId = UUID.randomUUID();
        StringBuilder drillsArr = new StringBuilder("[");
        for (int i = 0; i < numDrillsInBlock; i++) {
            if (i > 0) drillsArr.append(",");
            drillsArr.append(String.format("{\"drillId\":\"%s\",\"order\":%d}", drillId, i));
        }
        drillsArr.append("]");
        String blocksJson = String.format(
            "[{\"blockType\":\"MAIN\",\"blockName\":\"Main\",\"durationMinutes\":%d,\"drills\":%s}]",
            blockDuration, drillsArr
        );
        transactionTemplate.execute(txStatus -> {
            jdbcTemplate.update(
                "INSERT INTO session.sessions (id, booking_id, coach_id, player_id, blocks, " +
                "equipment_list, development_focus, status, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?::jsonb, '[]'::jsonb, '[]'::jsonb, ?, NOW(), NOW())",
                sessionId, bookingId, TEST_COACH_ID, TEST_PLAYER_ID, blocksJson, status
            );
            return null;
        });
        return sessionId;
    }

    private void publishEventInTransaction(UUID bookingId, boolean playerAttended) {
        transactionTemplate.execute(status -> {
            eventPublisher.publishEvent(new BookingCompletedEvent(
                this, bookingId, TEST_COACH_ID, TEST_PLAYER_ID,
                null, playerAttended, null, null, null, List.of()
            ));
            return null;
        });
    }

    private int countStats() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.player_skill_stats WHERE player_id = ?",
            Integer.class, TEST_PLAYER_ID
        );
        return count != null ? count : 0;
    }

    private void cleanDrill(UUID drillId) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM session.drills WHERE id = ?", drillId);
            return null;
        });
    }

    private void cleanSession(UUID bookingId) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM session.sessions WHERE booking_id = ?", bookingId);
            return null;
        });
    }
}
