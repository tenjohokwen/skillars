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
 * skillars-deferred-155 AC5/AC8: {@code PATCH /api/bookings/{id}/player-request} end to end — real
 * ownership check, real editable-status guard, real {@code ContactDetailSanitizer} bean. The
 * concurrent-modification and sanitizer-wiring/truncation cases are unit-tested in
 * {@code BookingServiceTest} instead, matching this project's own established convention for
 * {@code OptimisticLockingFailureException} coverage (never a real concurrency-race IT anywhere in
 * this module).
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class PlayerRequestResourceIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long SELF_PLAYER_USER_ID = 9700000001L;
    private static final long SELF_PLAYER_ID      = 9700000002L;
    private static final long PARENT_USER_ID      = 9700000010L;
    private static final long CHILD_PLAYER_ID     = 9700000011L;
    private static final long OTHER_PARENT_USER_ID = 9700000020L;
    private static final long COACH_USER_ID       = 9700000030L;

    private static final String SELF_PLAYER_EMAIL  = "self.player.playerrequest@skillars-test.com";
    private static final String PARENT_EMAIL       = "parent.playerrequest@skillars-test.com";
    private static final String OTHER_PARENT_EMAIL = "other.parent.playerrequest@skillars-test.com";
    private static final String COACH_EMAIL        = "coach.playerrequest@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;

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
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE parent_id IN (?, ?)",
                SELF_PLAYER_USER_ID, PARENT_USER_ID);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id IN (?, ?)",
                SELF_PLAYER_ID, CHILD_PLAYER_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?, ?)",
                SELF_PLAYER_USER_ID, PARENT_USER_ID, OTHER_PARENT_USER_ID, COACH_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?, ?)",
                SELF_PLAYER_USER_ID, PARENT_USER_ID, OTHER_PARENT_USER_ID, COACH_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void setPlayerRequest_selfRegisteredPlayerOwnsBooking_returns200AndPersists() {
        UUID bookingId = insertBooking(SELF_PLAYER_USER_ID, SELF_PLAYER_ID, "CONFIRMED");
        String cookies = loginAndGetCookies(SELF_PLAYER_EMAIL);

        ResponseEntity<Map> response = patchPlayerRequest(bookingId, "Work on free kicks", cookies);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("playerRequest")).isEqualTo("Work on free kicks");
        assertThat(persistedPlayerRequest(bookingId)).isEqualTo("Work on free kicks");
    }

    @Test
    void setPlayerRequest_parentOfChildPlayer_returns200AndPersists() {
        UUID bookingId = insertBooking(PARENT_USER_ID, CHILD_PLAYER_ID, "UPCOMING");
        String cookies = loginAndGetCookies(PARENT_EMAIL);

        ResponseEntity<Map> response = patchPlayerRequest(bookingId, "Shooting drills next time", cookies);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(persistedPlayerRequest(bookingId)).isEqualTo("Shooting drills next time");
    }

    @Test
    void setPlayerRequest_blankValueClearsExistingRequest() {
        UUID bookingId = insertBooking(SELF_PLAYER_USER_ID, SELF_PLAYER_ID, "CONFIRMED");
        String cookies = loginAndGetCookies(SELF_PLAYER_EMAIL);
        patchPlayerRequest(bookingId, "first value", cookies);

        patchPlayerRequest(bookingId, "", cookies);

        assertThat(persistedPlayerRequest(bookingId)).isNull();
    }

    @Test
    void setPlayerRequest_differentParentDoesNotOwnBooking_returns403() {
        UUID bookingId = insertBooking(PARENT_USER_ID, CHILD_PLAYER_ID, "CONFIRMED");
        String cookies = loginAndGetCookies(OTHER_PARENT_EMAIL);

        assertThatThrownBy(() -> patchPlayerRequest(bookingId, "not mine", cookies))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(persistedPlayerRequest(bookingId)).isNull();
    }

    @Test
    void setPlayerRequest_bookingStatusOutsideEditableWindow_returns403() {
        UUID bookingId = insertBooking(SELF_PLAYER_USER_ID, SELF_PLAYER_ID, "REQUESTED");
        String cookies = loginAndGetCookies(SELF_PLAYER_EMAIL);

        assertThatThrownBy(() -> patchPlayerRequest(bookingId, "too early", cookies))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void setPlayerRequest_contactDetailsInInput_realSanitizerRedactsThem() {
        UUID bookingId = insertBooking(SELF_PLAYER_USER_ID, SELF_PLAYER_ID, "CONFIRMED");
        String cookies = loginAndGetCookies(SELF_PLAYER_EMAIL);

        patchPlayerRequest(bookingId, "email me at test@example.com please", cookies);

        assertThat(persistedPlayerRequest(bookingId))
            .doesNotContain("test@example.com")
            .contains("[contact details removed]");
    }

    /**
     * skillars-deferred-155 code review: AC8 asked for a sanitizer-expansion payload that "still saves
     * successfully", proving the truncation guard. The only coverage was a unit test that stubbed the
     * sanitizer to return {@code "x".repeat(600)} and asserted {@code hasSize(500)} against a mocked
     * repository — so the truncation arithmetic was proven but nothing ever reached the real
     * {@code varchar(500)} column, and the real sanitizer's actual expansion was never exercised.
     *
     * <p>45 ten-digit runs pass {@code @Size(max = 500)} at 495 characters, then each matches
     * ContactDetailSanitizer's PHONE_DIGIT_RUN and is replaced by the 25-character
     * {@code "[contact details removed]"} — expanding the string well past the column bound. Without
     * the post-sanitize truncate this is a SQLSTATE 22001 insert failure surfacing as an opaque 400.
     */
    @Test
    void setPlayerRequest_sanitizerExpandsInputPastColumnBound_stillSavesTruncated() {
        UUID bookingId = insertBooking(SELF_PLAYER_USER_ID, SELF_PLAYER_ID, "CONFIRMED");
        String cookies = loginAndGetCookies(SELF_PLAYER_EMAIL);
        String expandingInput = "1234567890 ".repeat(45).trim();
        assertThat(expandingInput.length()).isLessThanOrEqualTo(500);

        patchPlayerRequest(bookingId, expandingInput, cookies);

        String persisted = persistedPlayerRequest(bookingId);
        assertThat(persisted)
            .isNotNull()
            .hasSizeLessThanOrEqualTo(500)
            .contains("[contact details removed]")
            .doesNotContain("1234567890");
    }

    // ---- helpers ----

    private UUID insertBooking(long parentId, long playerId, String status) {
        UUID bookingId = UUID.randomUUID();
        // Bare jdbcTemplate writes in an IT method body never commit (Hikari auto-commit=false) —
        // the real HTTP request below runs on a separate connection/thread and would see nothing.
        transactionTemplate.execute(txStatus -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, status, " +
                " canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 'Europe/Berlin', 0, ?, ?)",
                bookingId, parentId, playerId, coachProfileId,
                Timestamp.from(Instant.now().plusSeconds(3600)),
                Timestamp.from(Instant.now().plusSeconds(7200)),
                status,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
            );
            return null;
        });
        return bookingId;
    }

    private String persistedPlayerRequest(UUID bookingId) {
        return jdbcTemplate.queryForObject(
            "SELECT player_request FROM booking.bookings WHERE id = ?", String.class, bookingId);
    }

    private ResponseEntity<Map> patchPlayerRequest(UUID bookingId, String playerRequest, String cookies) {
        return httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/player-request",
            HttpMethod.PATCH,
            Map.of("playerRequest", playerRequest),
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
