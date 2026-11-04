package com.softropic.skillars.db;

import com.softropic.skillars.config.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-168 AC10: V226 turns the packs coaches entered in the profile builder
 * ({@code marketplace.session_packs}) into one active {@code payment.session_pack_tiers} row each. Flyway ran it
 * at context start against an empty table, so this re-runs the script's SQL against seeded coaches, in the style
 * of {@link ReviewEligibilityGapNormalizationIT}.
 */
class BuilderPackTierBackfillIT extends AbstractIntegrationTest {

    private static final long USER_BASE = 9_168_000_000L;

    private String loadMigrationSql() throws Exception {
        byte[] bytes = new ClassPathResource("db/migration/V226__backfill_builder_packs_into_session_pack_tiers.sql")
            .getContentAsByteArray();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void runBackfill() throws Exception {
        String sql = loadMigrationSql();
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute(sql);
            return null;
        });
    }

    private UUID coach(int n) {
        UUID coachId = UUID.randomUUID();
        long userId = USER_BASE + n;
        String email = "backfill.coach" + n + "@skillars-test.com";
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.\"user\" (id, created_by, created_date, last_modified_by, "
                + "last_modified_date, request_id, session_id, status, dob, email, first_name, gender, "
                + "lang_key, last_name, iso2_country, phone, activated, locked, login, login_id_type, "
                + "password_hash, otp_enabled, skillars_role, verification_status) VALUES "
                + "(?, 'system', ?, 'system', ?, 'test-req', NULL, 'ACTIVE', '1990-03-15', ?, 'Test', "
                + "'OTHER', 'en', 'Coach', 'DE', ?, true, false, ?, 'EMAIL', 'hash', false, 'COACH', "
                + "'BASIC_VERIFIED')",
                userId, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                email, "67016800" + String.format("%02d", n), email);
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, "
                + "district, canonical_timezone, status, verification_tier, created_at) VALUES "
                + "(?, ?, 'Backfill Coach', 'Bio', 'Berlin', 'Mitte', 'Europe/Berlin', 'ACTIVE', 'BASIC', now())",
                coachId, userId);
            return null;
        });
        return coachId;
    }

    private void builderPack(UUID coachId, int count, String total, String label) {
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "INSERT INTO marketplace.session_packs (coach_id, session_count, total_price, label) VALUES (?, ?, ?, ?)",
            coachId, count, new BigDecimal(total), label));
    }

    private void tier(UUID coachId, boolean active) {
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "INSERT INTO payment.session_pack_tiers (coach_id, label, session_count, total_price, "
            + "price_per_session, is_active) VALUES (?, 'Tier page offer', 4, 100.00, 25.00, ?)",
            coachId, active));
    }

    private List<Map<String, Object>> tiersOf(UUID coachId) {
        return jdbcTemplate.queryForList(
            "SELECT label, session_count, total_price, price_per_session, is_active "
            + "FROM payment.session_pack_tiers WHERE coach_id = ? ORDER BY created_at", coachId);
    }

    @Test
    void lowestValidPackBecomesTheActiveTier_otherPacksAndInvalidRowsAreDiscarded() throws Exception {
        UUID coachId = coach(1);
        builderPack(coachId, 10, "380.00", "Season 10");
        builderPack(coachId, 5, "200.00", "Starter");
        builderPack(coachId, 150, "4000.00", "Too many sessions");
        builderPack(coachId, 3, "0.00", "Free?");

        runBackfill();

        assertThat(tiersOf(coachId)).singleElement().satisfies(t -> {
            assertThat(t.get("label")).isEqualTo("Starter");
            assertThat(t.get("session_count")).isEqualTo(5);
            assertThat((BigDecimal) t.get("total_price")).isEqualByComparingTo("200.00");
            assertThat((BigDecimal) t.get("price_per_session")).isEqualByComparingTo("40.00");
            assertThat(t.get("is_active")).isEqualTo(true);
        });
    }

    @Test
    void perSessionPriceIsRoundedLikeCreateTier() throws Exception {
        UUID coachId = coach(2);
        builderPack(coachId, 3, "100.00", "Three");

        runBackfill();

        assertThat(tiersOf(coachId)).singleElement()
            .satisfies(t -> assertThat((BigDecimal) t.get("price_per_session")).isEqualByComparingTo("33.33"));
    }

    @Test
    void blankLabelsAndLabelsWithContactDetailsGetTheDefault() throws Exception {
        UUID blank = coach(3);
        builderPack(blank, 5, "200.00", "  ");
        UUID nullLabel = coach(4);
        builderPack(nullLabel, 5, "200.00", null);
        UUID phone = coach(5);
        builderPack(phone, 5, "200.00", "call 0791 112 3456");
        UUID email = coach(6);
        builderPack(email, 5, "200.00", "mail me@coach.de");
        UUID url = coach(7);
        builderPack(url, 5, "200.00", "see https://coach.example");
        UUID plain = coach(8);
        builderPack(plain, 10, "350.00", "10-pack, save 10%");

        runBackfill();

        for (UUID coachId : List.of(blank, nullLabel, phone, email, url)) {
            assertThat(tiersOf(coachId)).singleElement()
                .satisfies(t -> assertThat(t.get("label")).isEqualTo("Session pack"));
        }
        assertThat(tiersOf(plain)).singleElement()
            .satisfies(t -> assertThat(t.get("label")).isEqualTo("10-pack, save 10%"));
    }

    @Test
    void coachesWithAnyTierRowOrErasedAreSkipped() throws Exception {
        UUID withdrawn = coach(9);
        builderPack(withdrawn, 5, "200.00", "Starter");
        tier(withdrawn, false);
        UUID selling = coach(10);
        builderPack(selling, 5, "200.00", "Starter");
        tier(selling, true);
        UUID erased = coach(11);
        builderPack(erased, 5, "200.00", "Starter");
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "UPDATE marketplace.coach_profiles SET recap_data_erased_at = now() WHERE id = ?", erased));

        runBackfill();

        assertThat(tiersOf(withdrawn)).singleElement().satisfies(t -> assertThat(t.get("is_active")).isEqualTo(false));
        assertThat(tiersOf(selling)).singleElement()
            .satisfies(t -> assertThat(t.get("label")).isEqualTo("Tier page offer"));
        assertThat(tiersOf(erased)).isEmpty();
    }

    @Test
    void idempotent_aSecondRunAddsNothing() throws Exception {
        UUID coachId = coach(12);
        builderPack(coachId, 5, "200.00", "Starter");

        runBackfill();
        runBackfill();

        assertThat(tiersOf(coachId)).hasSize(1);
    }
}
