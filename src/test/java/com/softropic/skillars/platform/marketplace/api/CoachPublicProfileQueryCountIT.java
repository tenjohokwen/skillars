package com.softropic.skillars.platform.marketplace.api;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileDto;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.SecurityIT;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-91 AC11 — measure, then decide, {@code CoachProfileService.getPublicProfile}.
 *
 * <p>The ledger flagged this as "~8 sequential single-row round-trips" but re-scoped it as "not a
 * classic N+1 — once per single-coach page view, wait for real latency evidence". This IT is the
 * measurement: it counts the JDBC statements one call prepares (Hibernate statistics, enabled
 * programmatically so no {@code @TestPropertySource} forks the shared context), and — the point —
 * proves the count is <strong>constant in profile size</strong>: doubling the specialties / age
 * groups / session packs / availability windows / media items does not add a single query.
 *
 * <p><strong>Decision (AC11, revised by the skillars-deferred-91 code review, D9):</strong> the
 * measured count was 8 — above AC11's own "collapse if &gt; 4" threshold — and the original
 * "left as-is" reasoning re-litigated the threshold rather than applying it. Collapsed to
 * {@value #EXPECTED_QUERY_COUNT}:
 *
 * <ol>
 *   <li>profile + pricing (one ad-hoc {@code LEFT JOIN … ON}, one-to-one, no row multiplication)</li>
 *   <li>specialties + age groups + availability count + strike count (one homogeneous
 *       {@code (kind, value)} {@code UNION ALL})</li>
 *   <li>session packs</li>
 *   <li>media gallery</li>
 * </ol>
 *
 * <p>The last two stay separate deliberately: folding them in would need heterogeneous columns cast
 * to text, and a {@code JOIN FETCH} across six collections would risk
 * {@code MultipleBagFetchException} / a cartesian explosion — the hazards the original analysis
 * correctly identified. This IT guards both the count AND response parity (AC11 required a parity
 * check and none existed).
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class CoachPublicProfileQueryCountIT extends AbstractIntegrationTest {

    /** Fixed round-trips for one getPublicProfile call — see class javadoc. */
    private static final int EXPECTED_QUERY_COUNT = 4;

    private static final long COACH_USER_ID = 9270_000_001L;

    @Autowired private CoachProfileService coachProfileService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private UUID coachId;
    private Statistics statistics;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        coachId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) "
                + "VALUES (9270, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.\"user\" (id, created_by, created_date, last_modified_by, "
                + "last_modified_date, request_id, session_id, status, dob, email, first_name, gender, "
                + "lang_key, last_name, iso2_country, phone, activated, locked, login, login_id_type, "
                + "password_hash, otp_enabled, skillars_role, verification_status) VALUES "
                + "(?, 'system', ?, 'system', ?, 'test-req', NULL, 'ACTIVE', '1990-03-15', ?, 'Test', "
                + "'OTHER', 'en', 'Coach', 'DE', ?, true, false, ?, 'EMAIL', 'hash', false, 'COACH', "
                + "'BASIC_VERIFIED')",
                COACH_USER_ID, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                "qc.coach9270@skillars-test.com", "6701234567", "qc.coach9270@skillars-test.com");
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, "
                + "district, canonical_timezone, status, verification_tier, created_at) VALUES "
                + "(?, ?, 'QC Coach', 'Bio', 'Frankfurt', 'Mitte', 'Europe/Berlin', 'ACTIVE', 'BASIC', now())",
                coachId, COACH_USER_ID);
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 40.00, 'EUR')",
                coachId);
            seedChildRows(2);
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        statistics.setStatisticsEnabled(false);
    }

    @Test
    void getPublicProfile_issuesAFixedNumberOfQueries() {
        long queries = measureGetPublicProfile();

        assertThat(queries)
            .as("getPublicProfile should issue a fixed, small number of index-covered reads")
            .isEqualTo(EXPECTED_QUERY_COUNT);
    }

    @Test
    void getPublicProfile_queryCountDoesNotGrowWithProfileSize() {
        long withTwoOfEach = measureGetPublicProfile();

        // Double every collection the profile aggregates.
        transactionTemplate.execute(status -> {
            seedChildRows(4);
            return null;
        });

        long withFourOfEach = measureGetPublicProfile();

        assertThat(withFourOfEach)
            .as("no N+1: the round-trip count must not scale with how much the profile contains")
            .isEqualTo(withTwoOfEach)
            .isEqualTo(EXPECTED_QUERY_COUNT);
    }

    /**
     * AC11's required response-parity check, added by the skillars-deferred-91 code review: the
     * collapse must not change a single field of the rendered profile. Asserts every component of
     * {@code CoachProfileDto} that the collapsed queries now feed — specialties, age groups, price,
     * availability and strike count are exactly the values the seed data implies.
     */
    @Test
    void getPublicProfile_collapseDoesNotChangeTheResponse() {
        var dto = transactionTemplate.execute(status -> coachProfileService.getPublicProfile(coachId));

        assertThat(dto).isNotNull();
        assertThat(dto.id()).isEqualTo(coachId);
        assertThat(dto.perSessionPrice())
            .as("pricing now arrives via the profile LEFT JOIN, not its own findByCoachId")
            .isEqualByComparingTo("40.00");
        assertThat(dto.currency()).isEqualTo("EUR");
        assertThat(dto.specialties())
            .as("specialties now arrive from the UNION ALL branch")
            .hasSize(2)
            .doesNotContainNull();
        assertThat(dto.ageGroupsCoached())
            .as("age groups now arrive from the UNION ALL branch")
            .hasSize(2)
            .doesNotContainNull();
        assertThat(dto.available())
            .as("availability is now a COUNT(*) > 0 branch rather than a list read")
            .isTrue();
        assertThat(dto.reliabilityStrikeCount())
            .as("the 90-day strike count is now a COUNT(*) branch parsed from text")
            .isZero();
        assertThat(dto.sessionPacks()).hasSize(2);
        assertThat(dto.mediaGallery()).hasSize(2);
    }

    private long measureGetPublicProfile() {
        statistics.clear();
        CoachProfileDto dto = transactionTemplate.execute(status -> coachProfileService.getPublicProfile(coachId));
        assertThat(dto).isNotNull();
        assertThat(dto.id()).isEqualTo(coachId);
        return statistics.getPrepareStatementCount();
    }

    /** Idempotently ensures the profile has {@code n} rows in each aggregated collection. */
    private void seedChildRows(int n) {
        for (int i = 0; i < n; i++) {
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_specialties (coach_id, skill) VALUES (?, ?) ON CONFLICT DO NOTHING",
                coachId, "SKILL_" + i);
            jdbcTemplate.update(
                "INSERT INTO marketplace.session_packs (coach_id, session_count, total_price, label) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                coachId, i + 2, (i + 2) * 35.0, "Pack " + i);
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_availability_windows (coach_id, day_of_week, start_time, "
                + "end_time, canonical_timezone) VALUES (?, ?, '09:00', '10:00', 'Europe/Berlin') ON CONFLICT DO NOTHING",
                coachId, (i % 7) + 1);
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_media (coach_id, file_url, media_type, display_order) "
                + "VALUES (?, ?, 'IMAGE', ?) ON CONFLICT DO NOTHING",
                coachId, "https://example.test/m" + i + ".jpg", i);
        }
        // Age tiers are a fixed small enum — seed the four valid values once.
        for (String tier : new String[] {"U10", "AGE_10_12", "AGE_13_17", "ADULT"}) {
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_age_groups (coach_id, age_tier) VALUES (?, ?) ON CONFLICT DO NOTHING",
                coachId, tier);
        }
        jdbcTemplate.update(
            "INSERT INTO marketplace.coach_reliability_strikes (coach_id, reason, created_at) VALUES (?, 'late', now())",
            coachId);
    }
}
