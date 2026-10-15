package com.softropic.skillars.platform.development.api;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * skillars-12-1 AC1/3/4/5/6/7, Task 5.1. {@link com.softropic.skillars.platform.development.service.CoachRosterService}
 * is also unit-tested in isolation directly (see {@code CoachRosterServiceTest}) — code review
 * (2026-10-15) Patch: an earlier version of this Javadoc wrongly claimed the service "cannot be
 * unit-tested in isolation" because it "resolves the coach from a live coach principal via
 * SecurityUtil"; it does not — {@code getRoster(UUID coachId)} takes the coach id as a plain
 * parameter and has no {@code SecurityUtil} dependency at all. This class instead exercises what a
 * unit test cannot: the real HTTP/security layer (role gating, the 403/401 cases), and genuine
 * Postgres behavior (status-list breadth, the recency floor, config read/write/invalidate).
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class CoachRosterResourceIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";
    private static final String DORMANT_AFTER_DAYS_KEY = "development.roster.dormantAfterDays";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ConfigService configService;

    private static final long COACH_USER_ID       = 9700000001L;
    private static final long OTHER_COACH_USER_ID = 9700000002L;
    private static final long PARENT_USER_ID      = 9700000010L;

    private static final long DORMANT_NEGLECTED_PLAYER_ID = 9700000020L;
    private static final long ACTIVE_CLEAN_PLAYER_ID      = 9700000021L;
    private static final long FRESH_PLAYER_ID             = 9700000022L;
    private static final long OTHER_COACH_PLAYER_ID       = 9700000023L;

    private static final String COACH_EMAIL       = "coach.roster@skillars-test.com";
    private static final String OTHER_COACH_EMAIL = "other.coach.roster@skillars-test.com";
    private static final String PARENT_EMAIL      = "parent.roster@skillars-test.com";

    private UUID coachProfileId;
    private UUID otherCoachProfileId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            insertAuthority(9700, "ROLE_COACH");
            insertAuthority(9701, "ROLE_PARENT");

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantRole(COACH_USER_ID, "ROLE_COACH");
            coachProfileId = insertCoachProfile(COACH_USER_ID);
            insertSubscription(coachProfileId, "INSTRUCTOR");

            insertUser(OTHER_COACH_USER_ID, OTHER_COACH_EMAIL, passwordHash, "COACH");
            grantRole(OTHER_COACH_USER_ID, "ROLE_COACH");
            otherCoachProfileId = insertCoachProfile(OTHER_COACH_USER_ID);
            insertSubscription(otherCoachProfileId, "INSTRUCTOR");

            insertUser(PARENT_USER_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantRole(PARENT_USER_ID, "ROLE_PARENT");

            insertPlayer(DORMANT_NEGLECTED_PLAYER_ID, "Dormant Neglected");
            insertPlayer(ACTIVE_CLEAN_PLAYER_ID, "Active Clean");
            insertPlayer(FRESH_PLAYER_ID, "Fresh Signing");
            insertPlayer(OTHER_COACH_PLAYER_ID, "Other Coach Player");

            // Dormant/neglected: booking 60 days ago, two open neglected-skill flags.
            insertBooking(coachProfileId, DORMANT_NEGLECTED_PLAYER_ID, "COMPLETED",
                Instant.now().minusSeconds(60L * 24 * 3600));
            insertNeglectedSkillFlag(DORMANT_NEGLECTED_PLAYER_ID, "PAC");
            insertNeglectedSkillFlag(DORMANT_NEGLECTED_PLAYER_ID, "DRI");

            // Active/clean: booking yesterday, no neglected flags.
            insertBooking(coachProfileId, ACTIVE_CLEAN_PLAYER_ID, "COMPLETED",
                Instant.now().minusSeconds(24 * 3600));

            // Freshly booked: booking moments ago, no SLU/neglected/trend history at all.
            insertBooking(coachProfileId, FRESH_PLAYER_ID, "ACCEPTED", Instant.now());

            // Belongs only to the other coach — must never appear on this coach's roster.
            insertBooking(otherCoachProfileId, OTHER_COACH_PLAYER_ID, "COMPLETED", Instant.now());
            return null;
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void getRoster_asCoach_returnsOnlyThatCoachsPlayers() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            rosterUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> players = (List<Map<String, Object>>) response.getBody().get("players");
        List<Long> playerIds = players.stream().map(p -> Long.parseLong((String) p.get("playerId"))).toList();

        assertThat(playerIds).containsExactlyInAnyOrder(
            DORMANT_NEGLECTED_PLAYER_ID, ACTIVE_CLEAN_PLAYER_ID, FRESH_PLAYER_ID);
        assertThat(playerIds).doesNotContain(OTHER_COACH_PLAYER_ID);
    }

    @Test
    void getRoster_asNonCoach_returns403() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            rosterUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void getRoster_unauthenticated_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            rosterUrl(), HttpMethod.GET, null, clientHeaders(), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getRoster_dormancyFlipsAcrossConfiguredThreshold() {
        setDormantAfterDays("90");
        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Map> wideThreshold = httpTestClient.makeHttpRequest(
            rosterUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);
        assertThat(entryFor(wideThreshold, DORMANT_NEGLECTED_PLAYER_ID).get("dormant")).isEqualTo(false);

        setDormantAfterDays("5");
        ResponseEntity<Map> narrowThreshold = httpTestClient.makeHttpRequest(
            rosterUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);
        assertThat(entryFor(narrowThreshold, DORMANT_NEGLECTED_PLAYER_ID).get("dormant")).isEqualTo(true);
        assertThat(entryFor(narrowThreshold, ACTIVE_CLEAN_PLAYER_ID).get("dormant")).isEqualTo(false);
    }

    // skillars-12-1 code review (2026-10-15) Patch (closes the Dev Notes "Bounds at the extremes"
    // doc error too): the 4-arg getBoundedLong(key, defaultValue, min, max) FALLS BACK to defaultValue
    // (30) on an out-of-range stored value — it does NOT clamp to the nearest bound, unlike the 3-arg
    // overload. A stored "99999" (above max 3650) must read as 30, not 3650 — distinguishing this
    // from the (wrong) clamp assumption requires asserting against the default specifically: at 30
    // days the 60-day-stale DORMANT_NEGLECTED_PLAYER is dormant; clamped to 3650 it would not be.
    @Test
    void getRoster_outOfRangeDormantAfterDaysConfig_fallsBackToDefaultNotClamped() {
        setDormantAfterDays("99999");
        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            rosterUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(entryFor(response, DORMANT_NEGLECTED_PLAYER_ID).get("dormant")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getRoster_ordersDormantNeglectedPlayerAboveActiveCleanPlayer() {
        setDormantAfterDays("30");
        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            rosterUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);
        List<Map<String, Object>> players = (List<Map<String, Object>>) response.getBody().get("players");
        List<Long> orderedIds = players.stream().map(p -> Long.parseLong((String) p.get("playerId"))).toList();

        int dormantNeglectedRank = orderedIds.indexOf(DORMANT_NEGLECTED_PLAYER_ID);
        int activeCleanRank = orderedIds.indexOf(ACTIVE_CLEAN_PLAYER_ID);
        assertThat(dormantNeglectedRank).isLessThan(activeCleanRank);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getRoster_freshlyBookedPlayerWithNoHistory_appearsWithoutErrorAndIsNotTopRanked() {
        setDormantAfterDays("30");
        String cookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            rosterUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);
        List<Map<String, Object>> players = (List<Map<String, Object>>) response.getBody().get("players");
        List<Long> orderedIds = players.stream().map(p -> Long.parseLong((String) p.get("playerId"))).toList();

        Map<String, Object> fresh = entryFor(response, FRESH_PLAYER_ID);
        assertThat(fresh.get("dormant")).isEqualTo(false);
        assertThat(fresh.get("lastSessionDate")).isNull();
        assertThat(fresh.get("openNeglectedSkillCount")).isEqualTo(0);
        // AC6: must not be pinned to the top from null-as-maximum-urgency treatment — it should
        // rank below the dormant/neglected player, not above.
        assertThat(orderedIds.indexOf(FRESH_PLAYER_ID)).isGreaterThan(orderedIds.indexOf(DORMANT_NEGLECTED_PLAYER_ID));
    }

    // skillars-12-1 code review (2026-10-15) Patch: closes the "IT seeds zero SLU rows and zero
    // weekly snapshots" gap — every other test in this class has lastSessionDate=null and a
    // vacuously-zero declining-trend term by construction. This test seeds a real
    // development.player_skill_stats row (so lastSessionDate is non-null and assertable) and a real
    // 3-week declining development.player_slu_weekly_snapshot series for one player but not another,
    // otherwise-identical player — proving AC5's declining-trend clause end-to-end, not just at the
    // RosterAttentionCalculator unit level.
    @Test
    @SuppressWarnings("unchecked")
    void getRoster_computesLastSessionDateAndDecliningTrendFromRealData() {
        long decliningPlayerId = 9700000030L;
        long flatPlayerId = 9700000031L;
        Instant sessionDate = Instant.now().minusSeconds(2L * 24 * 3600);
        transactionTemplate.execute(status -> {
            insertPlayer(decliningPlayerId, "Declining Trend");
            insertPlayer(flatPlayerId, "Flat Trend");
            // Distinct, non-overlapping slots — excl_bkg_coach_slot_overlap forbids two bookings
            // for the same coach with overlapping time ranges.
            insertBooking(coachProfileId, decliningPlayerId, "COMPLETED", sessionDate);
            insertBooking(coachProfileId, flatPlayerId, "COMPLETED", sessionDate.plusSeconds(7200));
            insertSluRecord(decliningPlayerId, coachProfileId, "PAC", sessionDate);
            insertSluRecord(flatPlayerId, coachProfileId, "PAC", sessionDate);
            insertWeeklySnapshot(decliningPlayerId, "PAC", 2, "10.00");
            insertWeeklySnapshot(decliningPlayerId, "PAC", 1, "8.00");
            insertWeeklySnapshot(decliningPlayerId, "PAC", 0, "6.00");
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            rosterUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        Map<String, Object> declining = entryFor(response, decliningPlayerId);
        Map<String, Object> flat = entryFor(response, flatPlayerId);
        assertThat((String) declining.get("lastSessionDate")).isNotNull();
        assertThat(Instant.parse((String) declining.get("lastSessionDate")))
            .isCloseTo(sessionDate, org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));
        assertThat((Double) declining.get("attentionScore")).isGreaterThan((Double) flat.get("attentionScore"));
    }

    // skillars-12-1 code review (2026-10-15) Patch: AC1's ten-status breadth (now twelve, see the
    // DISPUTED/REFUND_PENDING decision below) had no coverage beyond COMPLETED/ACCEPTED, both of
    // which are also in the forbidden ACTIVE_RELATIONSHIP_STATUSES — a regression narrowing the list
    // to those four would have kept this suite green. Seeds one player per remaining status.
    //
    // DISPUTED and REFUND_PENDING added per code-review owner decision (2026-10-15): both are
    // reachable from COMPLETED (BookingStateMachine) and represent a session that genuinely
    // happened, so dropping the player mid-dispute/mid-refund is wrong. REFUNDED stays excluded — a
    // processed refund is the engagement being formally unwound.
    @Test
    @SuppressWarnings("unchecked")
    void getRoster_includesEveryRosterMembershipStatus_andExcludesRefunded() {
        List<String> statuses = List.of(
            "PAYMENT_PENDING", "CONFIRMED", "UPCOMING", "IN_PROGRESS", "PAUSED",
            "COMPLETED_PENDING_CONFIRMATION", "NO_SHOW_PLAYER", "NO_SHOW_COACH",
            "DISPUTED", "REFUND_PENDING");
        long firstPlayerId = 9700000040L;
        long refundedPlayerId = 9700000050L;
        List<Long> expectedPlayerIds = new java.util.ArrayList<>();

        // Base time is 2 days in the future, well clear of setUp()'s fixtures (-60 days, -1 day,
        // and "now" through "now + 1h" for FRESH_PLAYER_ID) — excl_bkg_coach_slot_overlap forbids
        // any two bookings for the same coach with overlapping time ranges, and each entry below
        // gets its own 2-hour slot so they don't collide with each other either.
        Instant baseTime = Instant.now().plusSeconds(2L * 24 * 3600);
        transactionTemplate.execute(status -> {
            for (int i = 0; i < statuses.size(); i++) {
                long playerId = firstPlayerId + i;
                expectedPlayerIds.add(playerId);
                insertPlayer(playerId, "Status " + statuses.get(i));
                insertBooking(coachProfileId, playerId, statuses.get(i), baseTime.plusSeconds(i * 7200L));
            }
            insertPlayer(refundedPlayerId, "Refunded Player");
            insertBooking(coachProfileId, refundedPlayerId, "REFUNDED", baseTime.plusSeconds(statuses.size() * 7200L));
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            rosterUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);
        List<Map<String, Object>> players = (List<Map<String, Object>>) response.getBody().get("players");
        List<Long> playerIds = players.stream().map(p -> Long.parseLong((String) p.get("playerId"))).toList();

        assertThat(playerIds).containsAll(expectedPlayerIds);
        assertThat(playerIds).doesNotContain(refundedPlayerId);
    }

    // skillars-12-1 code review (2026-10-15) Patch: AC1's 730-day recency floor had no test coverage
    // — every seeded booking in this class is recent, so a regression dropping the floor entirely
    // would have kept this suite green.
    @Test
    @SuppressWarnings("unchecked")
    void getRoster_excludesBookingBeyondThe730DayRecencyFloor() {
        long beyondFloorPlayerId = 9700000060L;
        transactionTemplate.execute(status -> {
            insertPlayer(beyondFloorPlayerId, "Beyond Floor");
            insertBooking(coachProfileId, beyondFloorPlayerId, "COMPLETED",
                Instant.now().minusSeconds(800L * 24 * 3600));
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            rosterUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);
        List<Map<String, Object>> players = (List<Map<String, Object>>) response.getBody().get("players");
        List<Long> playerIds = players.stream().map(p -> Long.parseLong((String) p.get("playerId"))).toList();

        assertThat(playerIds).doesNotContain(beyondFloorPlayerId);
    }

    private Map<String, Object> entryFor(ResponseEntity<Map> response, long playerId) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> players = (List<Map<String, Object>>) response.getBody().get("players");
        return players.stream()
            .filter(p -> Long.parseLong((String) p.get("playerId")) == playerId)
            .findFirst()
            .orElseThrow();
    }

    private void setDormantAfterDays(String value) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.platform_config (id, key, value, value_type) " +
                "VALUES (9700, ?, ?, 'LONG') ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
                DORMANT_AFTER_DAYS_KEY, value);
            return null;
        });
        // ensureFresh() otherwise holds the stale cached value for up to cache-ttl-seconds — see
        // SubscriptionLifecycleIT's identical config-flip precedent.
        configService.invalidate();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String rosterUrl() {
        return baseUrl() + "/api/development/coaches/me/roster";
    }

    private String loginAndGetCookies(String email) {
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", email, "password", TEST_PASSWORD),
            clientHeaders(), Map.class
        );
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> setCookies = loginResponse.getHeaders().get("Set-Cookie");
        assertThat(setCookies).isNotNull();
        return setCookies.stream()
            .map(c -> c.split(";")[0])
            .reduce((a, b) -> a + "; " + b)
            .orElseThrow();
    }

    private HttpHeaders clientHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(SecurityConstants.API_KEY_HEADER, CLIENT_ID);
        return headers;
    }

    private HttpHeaders authenticatedHeaders(String cookieValue) {
        HttpHeaders headers = clientHeaders();
        headers.add(HttpHeaders.COOKIE, cookieValue);
        return headers;
    }

    private void insertAuthority(int id, String name) {
        jdbcTemplate.update(
            "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
            "VALUES (?, ?, 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
            id, name, Timestamp.from(Instant.now())
        );
    }

    private void insertUser(long id, String email, String passwordHash, String role) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1990-01-01', ?, 'Test', 'OTHER', 'en', ?, 'DE', ?, " +
            "true, false, ?, 'EMAIL', ?, false, " +
            "?, 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, role,
            "69" + (id % 100000000L),
            email, passwordHash, role
        );
    }

    private void grantRole(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) " +
            "VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName
        );
    }

    private UUID insertCoachProfile(long userId) {
        UUID profileId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO marketplace.coach_profiles " +
            "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
            "VALUES (?, ?, 'Test Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
            profileId, userId
        );
        return profileId;
    }

    private void insertSubscription(UUID coachId, String tier) {
        jdbcTemplate.update(
            "INSERT INTO marketplace.coach_subscriptions (coach_id, tier, active_since) " +
            "VALUES (?, ?, NOW()) ON CONFLICT DO NOTHING",
            coachId, tier
        );
    }

    private void insertPlayer(long playerId, String name) {
        jdbcTemplate.update(
            "INSERT INTO main.player_profiles " +
            "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
            "VALUES (?, ?, ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
            playerId, name, Date.valueOf(LocalDate.now().minusYears(10)),
            PARENT_USER_ID, Timestamp.from(Instant.now())
        );
    }

    private void insertBooking(UUID coachId, long playerId, String status, Instant requestedStartTime) {
        jdbcTemplate.update(
            "INSERT INTO booking.bookings " +
            "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, status, " +
            " canonical_timezone, version, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 'Europe/Berlin', 0, ?, ?)",
            UUID.randomUUID(), PARENT_USER_ID, playerId, coachId,
            Timestamp.from(requestedStartTime),
            Timestamp.from(requestedStartTime.plusSeconds(3600)),
            status,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
        );
    }

    private void insertNeglectedSkillFlag(long playerId, String skillCode) {
        jdbcTemplate.update(
            "INSERT INTO development.neglected_skill_flags (id, player_id, skill_code, detected_at) " +
            "VALUES (gen_random_uuid(), ?, ?, NOW())",
            playerId, skillCode
        );
    }

    private void insertSluRecord(long playerId, UUID coachId, String skillCode, Instant calculatedAt) {
        jdbcTemplate.update(
            "INSERT INTO development.player_skill_stats " +
            "(id, player_id, coach_id, skill_code, slu_value, session_id, calculated_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 10.00, gen_random_uuid(), ?)",
            playerId, coachId, skillCode, Timestamp.from(calculatedAt)
        );
    }

    private void insertWeeklySnapshot(long playerId, String skillCode, int weeksAgo, String totalSlu) {
        ZonedDateTime at = ZonedDateTime.now(ZoneOffset.UTC).minusWeeks(weeksAgo);
        short isoYear = (short) at.get(IsoFields.WEEK_BASED_YEAR);
        short isoWeek = (short) at.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        jdbcTemplate.update(
            "INSERT INTO development.player_slu_weekly_snapshot (player_id, skill_code, iso_year, iso_week, total_slu) " +
            "VALUES (?, ?, ?, ?, ?) " +
            "ON CONFLICT (player_id, skill_code, iso_year, iso_week) DO UPDATE SET total_slu = EXCLUDED.total_slu",
            playerId, skillCode, isoYear, isoWeek, new BigDecimal(totalSlu)
        );
    }
}
