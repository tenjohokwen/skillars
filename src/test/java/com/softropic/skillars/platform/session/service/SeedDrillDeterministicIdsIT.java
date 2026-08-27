package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story Deferred-75 AC10: V111 reassigns the 20 V39 seed drills to fixed, deterministic ids so "the
 * same" platform drill has the same id in every environment.
 */
class SeedDrillDeterministicIdsIT extends AbstractIntegrationTest {

    private static final List<UUID> EXPECTED_IDS = List.of(
        UUID.fromString("00000000-0000-4000-8000-000000000001"),
        UUID.fromString("00000000-0000-4000-8000-000000000002"),
        UUID.fromString("00000000-0000-4000-8000-000000000003"),
        UUID.fromString("00000000-0000-4000-8000-000000000004"),
        UUID.fromString("00000000-0000-4000-8000-000000000005"),
        UUID.fromString("00000000-0000-4000-8000-000000000006"),
        UUID.fromString("00000000-0000-4000-8000-000000000007"),
        UUID.fromString("00000000-0000-4000-8000-000000000008"),
        UUID.fromString("00000000-0000-4000-8000-000000000009"),
        UUID.fromString("00000000-0000-4000-8000-000000000010"),
        UUID.fromString("00000000-0000-4000-8000-000000000011"),
        UUID.fromString("00000000-0000-4000-8000-000000000012"),
        UUID.fromString("00000000-0000-4000-8000-000000000013"),
        UUID.fromString("00000000-0000-4000-8000-000000000014"),
        UUID.fromString("00000000-0000-4000-8000-000000000015"),
        UUID.fromString("00000000-0000-4000-8000-000000000016"),
        UUID.fromString("00000000-0000-4000-8000-000000000017"),
        UUID.fromString("00000000-0000-4000-8000-000000000018"),
        UUID.fromString("00000000-0000-4000-8000-000000000019"),
        UUID.fromString("00000000-0000-4000-8000-000000000020")
    );

    @Test
    void v39SeedDrills_haveDeterministicIds() {
        List<UUID> platformDrillIds = jdbcTemplate.queryForList(
            "SELECT id FROM session.drills WHERE library_type = 'PLATFORM' AND trans_key LIKE 'sessDrill.%'",
            UUID.class
        );

        assertThat(platformDrillIds)
            .as("all 20 V39 seed drills must carry the fixed ids V111 assigned them")
            .hasSize(20)
            .containsExactlyInAnyOrderElementsOf(EXPECTED_IDS);
    }
}
