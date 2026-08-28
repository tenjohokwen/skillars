package com.softropic.skillars.platform.booking.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deferred-78 AC5: {@code BookingEventResource.verifyIsParty} used to return the identical generic
 * 403 whether the caller was a genuine unauthorized third party or a coach whose {@code
 * CoachProfile} row simply doesn't match the booking — both invisible in the logs. These two tests
 * prove the new distinguishing WARN log actually fires down each of the two reachable branches
 * (parent/admin never reach the throw at all — see {@code verifyIsParty}'s own comment), mirroring
 * {@code BookingBatchResourceIT}'s {@code ListAppender}-based log-capture technique.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class BookingEventResourceIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long PARENT_ID          = 9610000001L;
    private static final long PLAYER_ID          = 9610000002L;
    private static final long COACH_USER_ID      = 9610000010L;
    private static final long NO_PROFILE_USER_ID = 9610000020L;
    private static final long OTHER_COACH_USER_ID = 9610000030L;

    private static final String PARENT_EMAIL       = "eventres.parent@skillars-test.com";
    private static final String COACH_EMAIL        = "eventres.coach@skillars-test.com";
    private static final String NO_PROFILE_EMAIL   = "eventres.noprofile@skillars-test.com";
    private static final String OTHER_COACH_EMAIL  = "eventres.othercoach@skillars-test.com";
    private static final String WINDOW_TZ           = "Europe/Berlin";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    private UUID bookingId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);

        ZonedDateTime nextDaySlot = ZonedDateTime.now(ZoneId.of(WINDOW_TZ)).plusDays(2)
            .withHour(10).withMinute(0).withSecond(0).withNano(0);
        Instant slotStart = nextDaySlot.toInstant();
        Instant slotEnd = nextDaySlot.plusHours(1).toInstant();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9610, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9611, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            linkAuthority(PARENT_ID, "ROLE_PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Event Res Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(16)),
                PARENT_ID, Timestamp.from(Instant.now())
            );

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            linkAuthority(COACH_USER_ID, "ROLE_COACH");
            UUID coachProfileId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Event Res Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], ?, 'ACTIVE')",
                coachProfileId, COACH_USER_ID, WINDOW_TZ
            );

            bookingId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, status, canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'REQUESTED', ?, 0, ?, ?)",
                bookingId, PARENT_ID, PLAYER_ID, coachProfileId,
                Timestamp.from(slotStart), Timestamp.from(slotEnd),
                WINDOW_TZ, Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
            );

            // Case (a): authenticated, but zero coach_profiles row for this user at all.
            insertUser(NO_PROFILE_USER_ID, NO_PROFILE_EMAIL, passwordHash, "PARENT");
            linkAuthority(NO_PROFILE_USER_ID, "ROLE_PARENT");

            // Case (b): a real coach profile that exists but doesn't own this booking.
            insertUser(OTHER_COACH_USER_ID, OTHER_COACH_EMAIL, passwordHash, "COACH");
            linkAuthority(OTHER_COACH_USER_ID, "ROLE_COACH");
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Other Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], ?, 'ACTIVE')",
                UUID.randomUUID(), OTHER_COACH_USER_ID, WINDOW_TZ
            );

            return null;
        });
    }

    @Test
    void getBooking_actorHasNoCoachProfile_logsNoCoachProfileWarnAndReturns403() {
        Logger resourceLogger = (Logger) LoggerFactory.getLogger(BookingEventResource.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();

        String cookies = loginAndGetCookies(NO_PROFILE_EMAIL);
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.add(SecurityConstants.API_KEY_HEADER, CLIENT_ID);
        headers.add(HttpHeaders.COOKIE, cookies);

        try {
            resourceLogger.addAppender(logCapture);

            assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
                baseUrl() + "/api/bookings/" + bookingId,
                HttpMethod.GET, null, headers, Map.class
            ))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

            assertThat(logCapture.list)
                .as("must log the no-coach-profile branch, distinguishing it from a mismatched-profile rejection")
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("actor has no coach profile"));
        } finally {
            resourceLogger.detachAppender(logCapture);
        }
    }

    @Test
    void getBooking_actorCoachProfileDoesNotMatchBookingCoach_logsMismatchWarnAndReturns403() {
        Logger resourceLogger = (Logger) LoggerFactory.getLogger(BookingEventResource.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();

        String cookies = loginAndGetCookies(OTHER_COACH_EMAIL);
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.add(SecurityConstants.API_KEY_HEADER, CLIENT_ID);
        headers.add(HttpHeaders.COOKIE, cookies);

        try {
            resourceLogger.addAppender(logCapture);

            assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
                baseUrl() + "/api/bookings/" + bookingId,
                HttpMethod.GET, null, headers, Map.class
            ))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

            assertThat(logCapture.list)
                .as("must log the mismatched-profile branch, distinguishing it from a no-profile-at-all rejection")
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("actor coach profile does not match booking coach"));
        } finally {
            resourceLogger.detachAppender(logCapture);
        }
    }

    // ---- helpers ----

    private void linkAuthority(long userId, String authorityName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) " +
            "VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, authorityName
        );
    }

    private String loginAndGetCookies(String email) {
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        loginHeaders.add(SecurityConstants.API_KEY_HEADER, CLIENT_ID);

        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", email, "password", TEST_PASSWORD),
            loginHeaders,
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
