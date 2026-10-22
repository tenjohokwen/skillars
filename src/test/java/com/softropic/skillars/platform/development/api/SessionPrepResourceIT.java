package com.softropic.skillars.platform.development.api;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
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

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * skillars-12-3 AC3/AC4/AC8, Task 3.6. {@link com.softropic.skillars.platform.development.service.SessionFocusThreadCalculator}
 * is unit-tested in isolation (see {@code SessionFocusThreadCalculatorTest}) — this class exercises
 * the real HTTP/security layer (404/403 split, booking-ownership authorization) and genuine
 * Postgres behavior (the merged clock, the recap-consistency rule).
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class SessionPrepResourceIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final long COACH_USER_ID       = 9800000001L;
    private static final long OTHER_COACH_USER_ID = 9800000002L;
    private static final long PARENT_USER_ID       = 9800000010L;

    private static final String COACH_EMAIL       = "coach.sessionprep@skillars-test.com";
    private static final String OTHER_COACH_EMAIL = "other.coach.sessionprep@skillars-test.com";
    private static final String PARENT_EMAIL      = "parent.sessionprep@skillars-test.com";

    private UUID coachProfileId;
    private UUID otherCoachProfileId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            insertAuthority(9800, "ROLE_COACH");

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantRole(COACH_USER_ID, "ROLE_COACH");
            coachProfileId = insertCoachProfile(COACH_USER_ID);
            insertSubscription(coachProfileId, "INSTRUCTOR");

            insertUser(OTHER_COACH_USER_ID, OTHER_COACH_EMAIL, passwordHash, "COACH");
            grantRole(OTHER_COACH_USER_ID, "ROLE_COACH");
            otherCoachProfileId = insertCoachProfile(OTHER_COACH_USER_ID);
            insertSubscription(otherCoachProfileId, "INSTRUCTOR");

            insertAuthority(9801, "ROLE_PARENT");
            insertUser(PARENT_USER_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantRole(PARENT_USER_ID, "ROLE_PARENT");
            return null;
        });
    }

    @Test
    void getPrep_unknownBookingId_returns404() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            prepUrl(UUID.randomUUID()), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // skillars-12-3 code review (2026-10-15) Patch: PARENT_USER_ID/ROLE_PARENT were seeded in setUp
    // but no test ever authenticated as that user — AC3's "ROLE_COACH-gated" claim had no coverage
    // proving a non-coach is actually rejected.
    @Test
    void getPrep_asParent_returns403() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            prepUrl(UUID.randomUUID()), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void getPrep_bookingOwnedByAnotherCoach_returns403() {
        long playerId = 9800000020L;
        UUID[] bookingIdHolder = new UUID[1];
        transactionTemplate.execute(status -> {
            insertPlayer(playerId, "Other Coach's Player");
            bookingIdHolder[0] = insertBooking(otherCoachProfileId, playerId, "UPCOMING", Instant.now());
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            prepUrl(bookingIdHolder[0]), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getPrep_brandNewPlayerNoHistory_returns200WithAllFieldsEmpty() {
        long playerId = 9800000021L;
        UUID[] bookingIdHolder = new UUID[1];
        transactionTemplate.execute(status -> {
            insertPlayer(playerId, "Brand New Player");
            bookingIdHolder[0] = insertBooking(coachProfileId, playerId, "UPCOMING", Instant.now());
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            prepUrl(bookingIdHolder[0]), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("lastSessionDate")).isNull();
        assertThat(body.get("lastWhatWeDid")).isNull();
        assertThat(body.get("lastWhatsNext")).isNull();
        assertThat((List<?>) body.get("focusThreadSkillCodes")).isEmpty();
        assertThat((Integer) body.get("openNeglectedSkillCount")).isZero();
    }

    // skillars-12-3 AC3's consistency rule: a newer SLU-only (session-builder, no matching recap)
    // session must win lastSessionDate over an older attended timeline recap, and the recap fields
    // must be null rather than misattributed to the older session.
    @Test
    @SuppressWarnings("unchecked")
    void getPrep_newerSluOnlySessionThanTimelineRecap_recapFieldsNullDespiteNonNullLastSessionDate() {
        long playerId = 9800000022L;
        UUID[] bookingIdHolder = new UUID[1];
        Instant olderRecapTime = Instant.now().minusSeconds(20L * 24 * 3600);
        Instant newerSluTime = Instant.now().minusSeconds(24 * 3600);
        transactionTemplate.execute(status -> {
            insertPlayer(playerId, "Consistency Rule Player");
            bookingIdHolder[0] = insertBooking(coachProfileId, playerId, "UPCOMING", Instant.now());
            insertSessionCompletedEventWithRecap(playerId, coachProfileId,
                "Old session work", "Old session next step", olderRecapTime);
            insertSluRecord(playerId, coachProfileId, "PAC", newerSluTime);
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            prepUrl(bookingIdHolder[0]), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("lastSessionDate")).isNotNull();
        assertThat(Instant.parse((String) body.get("lastSessionDate")))
            .isCloseTo(newerSluTime, within(2, ChronoUnit.SECONDS));
        assertThat(body.get("lastWhatWeDid")).isNull();
        assertThat(body.get("lastWhatsNext")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getPrep_fullyPopulatedCase_returns200WithAllFourFields() {
        long playerId = 9800000023L;
        UUID[] bookingIdHolder = new UUID[1];
        Instant recapTime = Instant.now().minusSeconds(24 * 3600);
        transactionTemplate.execute(status -> {
            insertPlayer(playerId, "Fully Populated Player");
            bookingIdHolder[0] = insertBooking(coachProfileId, playerId, "UPCOMING", Instant.now());
            insertSessionCompletedEventWithRecap(playerId, coachProfileId,
                "Worked on first touch", "Continue weak-foot drills", recapTime);
            insertSluRecord(playerId, coachProfileId, "PAC", recapTime);
            insertSession(playerId, coachProfileId, List.of("PAC", "DRI"), "COMPLETED", recapTime);
            insertSession(playerId, coachProfileId, List.of("PAC"), "SAVED", recapTime.minusSeconds(3600));
            insertNeglectedSkillFlag(playerId, "SHO");
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            prepUrl(bookingIdHolder[0]), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("lastSessionDate")).isNotNull();
        assertThat(body.get("lastWhatWeDid")).isEqualTo("Worked on first touch");
        assertThat(body.get("lastWhatsNext")).isEqualTo("Continue weak-foot drills");
        assertThat((List<String>) body.get("focusThreadSkillCodes")).containsExactly("PAC", "DRI");
        assertThat((Integer) body.get("openNeglectedSkillCount")).isEqualTo(1);
    }

    // skillars-12-3 code review (2026-10-15) Patch, owner decision: reproduces the clock-skew bug
    // this story's fix targets. TimelineEventListener stamps occurredAt almost immediately after the
    // booking-completion transaction commits; SluCalculationService stamps calculated_at only after
    // loading the session/drills and computing SLU — both are separate @Async AFTER_COMMIT listeners
    // dispatched off the same commit, so for the SAME attended builder session, calculated_at ends up
    // LATER than occurredAt as the NORMAL ordering. A naive timestamp-only comparison misread this as
    // "a newer, different session exists" and wrongly nulled the recap — masked by the other tests in
    // this class, which all seed the SLU and timeline signals at either an identical instant (the
    // fully-populated case, landing on the comparison's equality boundary) or deliberately far apart
    // (the consistency-rule case). This test seeds the two signals a few seconds apart for the SAME
    // booking, matching the real production ordering, and proves the recap still shows.
    @Test
    @SuppressWarnings("unchecked")
    void getPrep_sameSessionWriteClockSkew_recapStillAttributedDespiteSluTimestampWinning() {
        long playerId = 9800000024L;
        UUID[] bookingIdHolder = new UUID[1];
        Instant occurredAt = Instant.now().minusSeconds(3600);
        Instant calculatedAfter = occurredAt.plusSeconds(5); // SLU stamped a few seconds later, SAME session
        transactionTemplate.execute(status -> {
            insertPlayer(playerId, "Clock Skew Player");
            bookingIdHolder[0] = insertBooking(coachProfileId, playerId, "UPCOMING", Instant.now());
            insertSessionCompletedEventWithRecapForBooking(bookingIdHolder[0], playerId, coachProfileId,
                "Worked on first touch", "Continue weak-foot drills", occurredAt);
            UUID sessionId = insertSessionForBooking(bookingIdHolder[0], playerId, coachProfileId, occurredAt);
            insertSluRecordForSession(playerId, coachProfileId, "PAC", calculatedAfter, sessionId);
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            prepUrl(bookingIdHolder[0]), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("lastWhatWeDid")).isEqualTo("Worked on first touch");
        assertThat(body.get("lastWhatsNext")).isEqualTo("Continue weak-foot drills");
    }

    // skillars-deferred-155 AC6: the player/parent-initiated session-prep request, read directly off
    // the booking row — no new query, so this proves the wiring through SessionPrepService end to end.
    @Test
    void getPrep_bookingHasPlayerRequest_includesItInResponse() {
        long playerId = 9800000025L;
        UUID[] bookingIdHolder = new UUID[1];
        transactionTemplate.execute(status -> {
            insertPlayer(playerId, "Player With Request");
            bookingIdHolder[0] = insertBooking(coachProfileId, playerId, "CONFIRMED", Instant.now());
            jdbcTemplate.update("UPDATE booking.bookings SET player_request = ? WHERE id = ?",
                "Can we work on free kicks?", bookingIdHolder[0]);
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            prepUrl(bookingIdHolder[0]), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("playerRequest")).isEqualTo("Can we work on free kicks?");
    }

    @Test
    void getPrep_bookingHasNoPlayerRequest_returnsNull() {
        long playerId = 9800000026L;
        UUID[] bookingIdHolder = new UUID[1];
        transactionTemplate.execute(status -> {
            insertPlayer(playerId, "Player Without Request");
            bookingIdHolder[0] = insertBooking(coachProfileId, playerId, "CONFIRMED", Instant.now());
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            prepUrl(bookingIdHolder[0]), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("playerRequest")).isNull();
    }

    // ---- skillars-12-6: plan note, days-since, neglected names, feed-forward suggestion, CONFIRMED ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> getPrepBody(UUID bookingId) {
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            prepUrl(bookingId), HttpMethod.GET, null, authenticatedHeaders(loginAndGetCookies(COACH_EMAIL)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private UUID seedBooking(long playerId, String name, String status) {
        return seedBooking(playerId, name, status, 0);
    }

    /** {@code slotOffsetHours} keeps two bookings of the same coach clear of excl_bkg_coach_slot_overlap. */
    private UUID seedBooking(long playerId, String name, String status, int slotOffsetHours) {
        UUID[] holder = new UUID[1];
        transactionTemplate.execute(txStatus -> {
            insertPlayer(playerId, name);
            holder[0] = insertBooking(coachProfileId, playerId, status, Instant.now().plus(slotOffsetHours, ChronoUnit.HOURS));
            return null;
        });
        return holder[0];
    }

    /** G6: prep has no status gate, so a CONFIRMED booking returns a full response. */
    @Test
    void getPrep_confirmedBooking_returns200WithNewFieldsAtDefaults() {
        UUID bookingId = seedBooking(9800000031L, "Confirmed Player", "CONFIRMED");

        Map<String, Object> body = getPrepBody(bookingId);

        assertThat(body.get("coachPlanNote")).isNull();
        assertThat(body.get("coachPlanNoteEditable")).isEqualTo(true);
        assertThat(body.get("daysSinceLastSession")).isNull();
        assertThat((List<?>) body.get("openNeglectedSkillCodes")).isEmpty();
        assertThat(body.get("suggestedWhatsNext")).isNull();
        assertThat(body.get("suggestedWhatsNextSessionDate")).isNull();
    }

    @Test
    void getPrep_bookingHasCoachPlanNote_roundTripsAndIsEditableOnlyInEditableStatus() {
        UUID confirmed = seedBooking(9800000032L, "Noted Player", "CONFIRMED");
        UUID inProgress = seedBooking(9800000033L, "Started Player", "IN_PROGRESS", 5);
        transactionTemplate.execute(txStatus -> {
            jdbcTemplate.update("UPDATE booking.bookings SET coach_plan_note = ? WHERE id IN (?, ?)",
                "Work on weak foot", confirmed, inProgress);
            return null;
        });

        Map<String, Object> editable = getPrepBody(confirmed);
        Map<String, Object> notEditable = getPrepBody(inProgress);

        assertThat(editable.get("coachPlanNote")).isEqualTo("Work on weak foot");
        assertThat(editable.get("coachPlanNoteEditable")).isEqualTo(true);
        assertThat(notEditable.get("coachPlanNote")).isEqualTo("Work on weak foot");
        assertThat(notEditable.get("coachPlanNoteEditable")).isEqualTo(false);
    }

    @Test
    void getPrep_attendedSessionTwoDaysAgo_exposesDaysSinceLastSession() {
        long playerId = 9800000034L;
        UUID bookingId = seedBooking(playerId, "Recent Player", "CONFIRMED");
        transactionTemplate.execute(txStatus -> {
            insertSessionCompletedEventWithRecap(playerId, coachProfileId, "drills", "shooting",
                Instant.now().minus(53, ChronoUnit.HOURS));
            return null;
        });

        Map<String, Object> body = getPrepBody(bookingId);

        // Long fields are serialised as JSON strings project-wide (like playerId).
        assertThat(Long.parseLong(String.valueOf(body.get("daysSinceLastSession")))).isEqualTo(2L);
    }

    @Test
    void getPrep_openNeglectedFlags_returnedByNameSortedAndCountKept() {
        long playerId = 9800000035L;
        UUID bookingId = seedBooking(playerId, "Neglect Player", "CONFIRMED");
        transactionTemplate.execute(txStatus -> {
            insertNeglectedSkillFlag(playerId, "SHO");
            insertNeglectedSkillFlag(playerId, "PAS");
            return null;
        });

        Map<String, Object> body = getPrepBody(bookingId);

        assertThat((List<String>) body.get("openNeglectedSkillCodes")).containsExactly("PAS", "SHO");
        assertThat(((Number) body.get("openNeglectedSkillCount")).intValue()).isEqualTo(2);
    }

    @Test
    void getPrep_latestSessionHasNoWhatsNext_suggestionFallsBackToOlderSession() {
        long playerId = 9800000036L;
        UUID bookingId = seedBooking(playerId, "Feed Forward Player", "CONFIRMED");
        Instant older = Instant.now().minus(20, ChronoUnit.DAYS);
        transactionTemplate.execute(txStatus -> {
            insertSessionCompletedEventWithRecap(playerId, coachProfileId, "old work", "Work on first touch", older);
            // Newest session: attended, but whatsNext is blank.
            insertSessionCompletedEventWithRecap(playerId, coachProfileId, "new work", "  ",
                Instant.now().minus(3, ChronoUnit.DAYS));
            return null;
        });

        Map<String, Object> body = getPrepBody(bookingId);

        assertThat(body.get("suggestedWhatsNext")).isEqualTo("Work on first touch");
        assertThat(body.get("suggestedWhatsNextSessionDate")).isNotNull();
    }

    @Test
    void getPrep_suggestionIgnoresUnattendedRecapAndOtherCoachesAndOwnBooking() {
        long playerId = 9800000037L;
        UUID bookingId = seedBooking(playerId, "Filtered Player", "CONFIRMED");
        transactionTemplate.execute(txStatus -> {
            // Another coach's attended session with this player: must not leak into this coach's prep.
            insertSessionCompletedEventWithRecap(playerId, otherCoachProfileId, "x", "OTHER COACH NEXT",
                Instant.now().minus(5, ChronoUnit.DAYS));
            // An event belonging to the booking being prepped itself.
            insertSessionCompletedEventWithRecapForBooking(bookingId, playerId, coachProfileId, "y", "OWN BOOKING NEXT",
                Instant.now().minus(4, ChronoUnit.DAYS));
            // Unattended recap: metadata carries this coach but the coach_id column is null.
            jdbcTemplate.update(
                "INSERT INTO development.player_timeline_events " +
                "(id, player_id, event_type, reference_module, occurred_at, metadata) " +
                "VALUES (gen_random_uuid(), ?, 'SESSION_COMPLETED', 'booking', ?, ?::jsonb)",
                playerId, Timestamp.from(Instant.now().minus(2, ChronoUnit.DAYS)),
                String.format("{\"coachId\":\"%s\",\"whatsNext\":\"UNATTENDED NEXT\"}", coachProfileId));
            return null;
        });

        assertThat(getPrepBody(bookingId).get("suggestedWhatsNext")).isNull();
    }

    @Test
    void getPrep_suggestionIsSanitisedAndCappedAt500() {
        long playerId = 9800000038L;
        UUID bookingId = seedBooking(playerId, "Sanitised Player", "CONFIRMED");
        transactionTemplate.execute(txStatus -> {
            insertSessionCompletedEventWithRecap(playerId, coachProfileId, "w", "call test@example.com now",
                Instant.now().minus(2, ChronoUnit.DAYS));
            return null;
        });

        String suggestion = (String) getPrepBody(bookingId).get("suggestedWhatsNext");

        assertThat(suggestion).doesNotContain("test@example.com").contains("[contact details removed]");
        assertThat(suggestion.length()).isLessThanOrEqualTo(500);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String prepUrl(UUID bookingId) {
        return baseUrl() + "/api/development/bookings/" + bookingId + "/prep";
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

    private UUID insertBooking(UUID coachId, long playerId, String status, Instant requestedStartTime) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO booking.bookings " +
            "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, status, " +
            " canonical_timezone, version, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 'Europe/Berlin', 0, ?, ?)",
            bookingId, PARENT_USER_ID, playerId, coachId,
            Timestamp.from(requestedStartTime),
            Timestamp.from(requestedStartTime.plusSeconds(3600)),
            status,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
        );
        return bookingId;
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

    // skillars-12-3 Task 2.3: widened to set coach_id (the attendance-gated clock signal) alongside
    // the pre-existing metadata.coachId string.
    private void insertSessionCompletedEventWithRecap(
            long playerId, UUID coachId, String whatWeDid, String whatsNext, Instant occurredAt) {
        String metadataJson = String.format(
            "{\"coachId\":\"%s\",\"whatWeDid\":\"%s\",\"whatsNext\":\"%s\"}",
            coachId, whatWeDid, whatsNext);
        jdbcTemplate.update(
            "INSERT INTO development.player_timeline_events " +
            "(id, player_id, event_type, reference_module, occurred_at, metadata, coach_id) " +
            "VALUES (gen_random_uuid(), ?, 'SESSION_COMPLETED', 'booking', ?, ?::jsonb, ?)",
            playerId, Timestamp.from(occurredAt), metadataJson, coachId
        );
    }

    // skillars-12-3 code review (2026-10-15) Patch: insertSessionCompletedEventWithRecap above never
    // sets reference_id (the other tests in this class never needed it) — this overload sets it to a
    // real bookingId, reproducing TimelineEventListener's actual write shape for the clock-skew
    // regression test.
    private void insertSessionCompletedEventWithRecapForBooking(
            UUID bookingId, long playerId, UUID coachId, String whatWeDid, String whatsNext, Instant occurredAt) {
        String metadataJson = String.format(
            "{\"coachId\":\"%s\",\"whatWeDid\":\"%s\",\"whatsNext\":\"%s\"}",
            coachId, whatWeDid, whatsNext);
        jdbcTemplate.update(
            "INSERT INTO development.player_timeline_events " +
            "(id, player_id, event_type, reference_id, reference_module, occurred_at, metadata, coach_id) " +
            "VALUES (gen_random_uuid(), ?, 'SESSION_COMPLETED', ?, 'booking', ?, ?::jsonb, ?)",
            playerId, bookingId, Timestamp.from(occurredAt), metadataJson, coachId
        );
    }

    // skillars-12-3 code review (2026-10-15) Patch: a real session.sessions row (unlike insertSession
    // below, which uses a random, dangling booking_id) so SluRepository.findLastSessionBookingId's
    // join can actually resolve a bookingId for the clock-skew regression test.
    private UUID insertSessionForBooking(UUID bookingId, long playerId, UUID coachId, Instant createdAt) {
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO session.sessions " +
            "(id, booking_id, coach_id, player_id, status, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?)",
            sessionId, bookingId, coachId, playerId, Timestamp.from(createdAt), Timestamp.from(createdAt)
        );
        return sessionId;
    }

    // skillars-12-3 code review (2026-10-15) Patch: like insertSluRecord below but with an explicit,
    // real session_id (not gen_random_uuid()) so it correlates with insertSessionForBooking's row.
    private void insertSluRecordForSession(long playerId, UUID coachId, String skillCode, Instant calculatedAt, UUID sessionId) {
        jdbcTemplate.update(
            "INSERT INTO development.player_skill_stats " +
            "(id, player_id, coach_id, skill_code, slu_value, session_id, calculated_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 10.00, ?, ?)",
            playerId, coachId, skillCode, sessionId, Timestamp.from(calculatedAt)
        );
    }

    private void insertSession(long playerId, UUID coachId, List<String> developmentFocus, String status, Instant createdAt) {
        String focusJson = developmentFocus.stream()
            .map(code -> "\"" + code + "\"")
            .reduce((a, b) -> a + "," + b)
            .map(joined -> "[" + joined + "]")
            .orElse("[]");
        jdbcTemplate.update(
            "INSERT INTO session.sessions " +
            "(id, booking_id, coach_id, player_id, development_focus, status, created_at, updated_at) " +
            "VALUES (gen_random_uuid(), gen_random_uuid(), ?, ?, ?::jsonb, ?, ?, ?)",
            coachId, playerId, focusJson, status, Timestamp.from(createdAt), Timestamp.from(createdAt)
        );
    }
}
