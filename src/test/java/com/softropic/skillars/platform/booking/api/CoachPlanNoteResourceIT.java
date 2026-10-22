package com.softropic.skillars.platform.booking.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * skillars-12-6 AC2/AC15: {@code PATCH /api/bookings/{id}/coach-plan-note} end to end — real coach
 * ownership resolution, real editable guard, real {@code ContactDetailSanitizer}. The
 * optimistic-lock case is unit-tested in {@code BookingServiceTest} (a sequential-HTTP request
 * carries no version and cannot reproduce it).
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class CoachPlanNoteResourceIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long SELF_PLAYER_USER_ID = 9710000001L;
    private static final long SELF_PLAYER_ID      = 9710000002L;
    private static final long PARENT_USER_ID      = 9710000010L;
    private static final long CHILD_PLAYER_ID     = 9710000011L;
    private static final long OTHER_PARENT_USER_ID = 9710000020L;
    private static final long COACH_USER_ID       = 9710000030L;
    private static final long OTHER_COACH_USER_ID = 9710000040L;

    private static final String SELF_PLAYER_EMAIL  = "self.player.coachplannote@skillars-test.com";
    private static final String PARENT_EMAIL       = "parent.coachplannote@skillars-test.com";
    private static final String OTHER_PARENT_EMAIL = "other.parent.coachplannote@skillars-test.com";
    private static final String COACH_EMAIL        = "coach.coachplannote@skillars-test.com";
    private static final String OTHER_COACH_EMAIL  = "other.coach.coachplannote@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private UUID otherCoachProfileId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);

        transactionTemplate.execute(status -> {
            insertUser(SELF_PLAYER_USER_ID, SELF_PLAYER_EMAIL, passwordHash, "PLAYER");
            grantRole(SELF_PLAYER_USER_ID, "ROLE_PLAYER");
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, user_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Self Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                SELF_PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(20)),
                SELF_PLAYER_USER_ID, Timestamp.from(Instant.now())
            );

            insertUser(PARENT_USER_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantRole(PARENT_USER_ID, "ROLE_PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Child Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                CHILD_PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(10)),
                PARENT_USER_ID, Timestamp.from(Instant.now())
            );

            insertUser(OTHER_PARENT_USER_ID, OTHER_PARENT_EMAIL, passwordHash, "PARENT");
            grantRole(OTHER_PARENT_USER_ID, "ROLE_PARENT");

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantRole(COACH_USER_ID, "ROLE_COACH");
            coachProfileId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Request Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID
            );
            insertUser(OTHER_COACH_USER_ID, OTHER_COACH_EMAIL, passwordHash, "COACH");
            grantRole(OTHER_COACH_USER_ID, "ROLE_COACH");
            otherCoachProfileId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Other Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                otherCoachProfileId, OTHER_COACH_USER_ID
            );
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE coach_id IN (?, ?)",
                coachProfileId, otherCoachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id IN (?, ?)", coachProfileId, otherCoachProfileId);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id IN (?, ?)",
                SELF_PLAYER_ID, CHILD_PLAYER_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?, ?, ?)",
                SELF_PLAYER_USER_ID, PARENT_USER_ID, OTHER_PARENT_USER_ID, COACH_USER_ID, OTHER_COACH_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?, ?, ?)",
                SELF_PLAYER_USER_ID, PARENT_USER_ID, OTHER_PARENT_USER_ID, COACH_USER_ID, OTHER_COACH_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void setCoachPlanNote_owningCoach_returns200AndPersists() {
        UUID bookingId = insertBooking("CONFIRMED", 3600, 7200);

        ResponseEntity<Map> response = patchNote(bookingId, "Work on first touch", loginAndGetCookies(COACH_EMAIL));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("planNote")).isEqualTo("Work on first touch");
        assertThat(persistedNote(bookingId)).isEqualTo("Work on first touch");
    }

    @Test
    void setCoachPlanNote_upcomingBooking_isEditable() {
        UUID bookingId = insertBooking("UPCOMING", 3600, 7200);

        patchNote(bookingId, "upcoming note", loginAndGetCookies(COACH_EMAIL));

        assertThat(persistedNote(bookingId)).isEqualTo("upcoming note");
    }

    @Test
    void setCoachPlanNote_blankClearsExistingNote() {
        UUID bookingId = insertBooking("CONFIRMED", 3600, 7200);
        String cookies = loginAndGetCookies(COACH_EMAIL);
        patchNote(bookingId, "first", cookies);

        patchNote(bookingId, "", cookies);

        assertThat(persistedNote(bookingId)).isNull();
    }

    @Test
    void setCoachPlanNote_contactDetails_realSanitizerRedactsThem() {
        UUID bookingId = insertBooking("CONFIRMED", 3600, 7200);

        patchNote(bookingId, "email me at test@example.com please", loginAndGetCookies(COACH_EMAIL));

        assertThat(persistedNote(bookingId))
            .doesNotContain("test@example.com")
            .contains("[contact details removed]");
    }

    @Test
    void setCoachPlanNote_sanitizerExpandsInputPastColumnBound_stillSavesTruncated() {
        UUID bookingId = insertBooking("CONFIRMED", 3600, 7200);
        String expandingInput = "1234567890 ".repeat(45).trim();

        patchNote(bookingId, expandingInput, loginAndGetCookies(COACH_EMAIL));

        assertThat(persistedNote(bookingId)).isNotNull().hasSizeLessThanOrEqualTo(500)
            .contains("[contact details removed]").doesNotContain("1234567890");
    }

    @Test
    void setCoachPlanNote_otherCoach_returns403() {
        UUID bookingId = insertBooking("CONFIRMED", 3600, 7200);

        assertForbidden(bookingId, loginAndGetCookies(OTHER_COACH_EMAIL));
        assertThat(persistedNote(bookingId)).isNull();
    }

    @Test
    void setCoachPlanNote_parentToken_returns403() {
        UUID bookingId = insertBooking("CONFIRMED", 3600, 7200);

        assertForbidden(bookingId, loginAndGetCookies(PARENT_EMAIL));
        assertThat(persistedNote(bookingId)).isNull();
    }

    @Test
    void setCoachPlanNote_statusNotEditable_returns403() {
        UUID bookingId = insertBooking("IN_PROGRESS", 3600, 7200);

        assertForbidden(bookingId, loginAndGetCookies(COACH_EMAIL), "booking.notEditable");
    }

    @Test
    void setCoachPlanNote_sessionAlreadyEnded_returns403() {
        UUID bookingId = insertBooking("UPCOMING", -7200, -3600);

        assertForbidden(bookingId, loginAndGetCookies(COACH_EMAIL), "booking.sessionAlreadyEnded");
        assertThat(persistedNote(bookingId)).isNull();
    }

    @Test
    void setCoachPlanNote_unknownBooking_returns404() {
        String cookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> patchNote(UUID.randomUUID(), "x", cookies))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    /** AC4: the coach's private note never reaches the parent's own booking GET. */
    @Test
    void parentGetBooking_neverContainsCoachPlanNote() {
        UUID bookingId = insertBooking("CONFIRMED", 3600, 7200);
        patchNote(bookingId, "PRIVATE-COACH-PLAN-NOTE", loginAndGetCookies(COACH_EMAIL));

        ResponseEntity<String> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/requests", HttpMethod.GET, null,
            authenticatedHeaders(loginAndGetCookies(PARENT_EMAIL)), String.class);

        // Non-vacuous: the booking that carries the note must actually be in the parent's list.
        assertThat(response.getBody()).contains(bookingId.toString());
        assertThat(response.getBody()).doesNotContain("PRIVATE-COACH-PLAN-NOTE").doesNotContain("coachPlanNote");
    }

    // ---- helpers ----

    private void assertForbidden(UUID bookingId, String cookies) {
        assertThatThrownBy(() -> patchNote(bookingId, "nope", cookies))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    /** Also pins the errorKey, so the not-editable and already-ended guards cannot mask each other. */
    private void assertForbidden(UUID bookingId, String cookies, String expectedErrorKey) {
        assertThatThrownBy(() -> patchNote(bookingId, "nope", cookies))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> {
                HttpClientErrorException httpEx = (HttpClientErrorException) ex;
                assertThat(httpEx.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(httpEx.getResponseBodyAsString()).contains("\"errorKey\":\"" + expectedErrorKey + "\"");
            });
    }

    /** Offsets in seconds from a SINGLE Instant.now() (chk_bkg_end_after_start). */
    private UUID insertBooking(String status, long startOffsetSeconds, long endOffsetSeconds) {
        UUID bookingId = UUID.randomUUID();
        Instant now = Instant.now();
        transactionTemplate.execute(txStatus -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, status, " +
                " canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 'Europe/Berlin', 0, ?, ?)",
                bookingId, PARENT_USER_ID, CHILD_PLAYER_ID, coachProfileId,
                Timestamp.from(now.plusSeconds(startOffsetSeconds)),
                Timestamp.from(now.plusSeconds(endOffsetSeconds)),
                status, Timestamp.from(now), Timestamp.from(now));
            return null;
        });
        return bookingId;
    }

    private String persistedNote(UUID bookingId) {
        return jdbcTemplate.queryForObject(
            "SELECT coach_plan_note FROM booking.bookings WHERE id = ?", String.class, bookingId);
    }

    private ResponseEntity<Map> patchNote(UUID bookingId, String planNote, String cookies) {
        return httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/coach-plan-note",
            HttpMethod.PATCH,
            Map.of("planNote", planNote),
            authenticatedHeaders(cookies),
            Map.class
        );
    }

    private String loginAndGetCookies(String email) {
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", email, "password", TEST_PASSWORD),
            clientHeaders(),
            Map.class
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

    private void grantRole(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) " +
            "VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName
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
            "'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', ?, 'DE', ?, " +
            "true, false, ?, 'EMAIL', ?, false, " +
            "?, 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, role,
            "69" + (id % 100000000),
            email, passwordHash, role
        );
    }
}
