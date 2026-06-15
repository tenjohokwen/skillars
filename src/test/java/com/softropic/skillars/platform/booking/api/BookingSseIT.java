package com.softropic.skillars.platform.booking.api;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.TransitionContext;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
class BookingSseIT {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long SSE_PARENT_ID     = 9600000001L;
    private static final long SSE_PLAYER_ID     = 9600000002L;
    private static final long SSE_COACH_USER_ID = 9600000010L;
    private static final long OTHER_USER_ID     = 9600000099L;

    private static final String SSE_PARENT_EMAIL = "sse.parent@skillars-test.com";
    private static final String SSE_COACH_EMAIL  = "sse.coach@skillars-test.com";
    private static final String OTHER_EMAIL      = "sse.other@skillars-test.com";
    private static final String WINDOW_TZ        = "Europe/Berlin";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private BookingService bookingService;
    @Autowired private RestTemplate restTemplate;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private UUID bookingId;
    private Instant slotStart;
    private Instant slotEnd;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);

        ZonedDateTime nextDaySlot = ZonedDateTime.now(ZoneId.of(WINDOW_TZ)).plusDays(2)
            .withHour(10).withMinute(0).withSecond(0).withNano(0);
        slotStart = nextDaySlot.toInstant();
        slotEnd = nextDaySlot.plusHours(1).toInstant();
        short windowDow = (short) nextDaySlot.getDayOfWeek().getValue();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9600, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9601, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );

            insertUser(SSE_PARENT_ID, SSE_PARENT_EMAIL, passwordHash, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                SSE_PARENT_ID
            );

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'SSE Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                SSE_PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(16)),
                SSE_PARENT_ID, Timestamp.from(Instant.now())
            );

            insertUser(SSE_COACH_USER_ID, SSE_COACH_EMAIL, passwordHash, "COACH");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                SSE_COACH_USER_ID
            );

            coachProfileId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'SSE Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], ?, 'ACTIVE')",
                coachProfileId, SSE_COACH_USER_ID, WINDOW_TZ
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 50.00, 'EUR')",
                coachProfileId
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_availability_windows " +
                "(id, coach_id, day_of_week, start_time, end_time, canonical_timezone) " +
                "VALUES (?, ?, ?, '08:00', '18:00', ?)",
                UUID.randomUUID(), coachProfileId, windowDow, WINDOW_TZ
            );
            jdbcTemplate.update(
                "INSERT INTO booking.session_packs_purchased " +
                "(id, parent_id, player_id, coach_id, session_count, credits_remaining, status, purchased_at) " +
                "VALUES (?, ?, ?, ?, 3, 3, 'ACTIVE', ?)",
                UUID.randomUUID(), SSE_PARENT_ID, SSE_PLAYER_ID, coachProfileId, Timestamp.from(Instant.now())
            );

            // Pre-create a booking in REQUESTED status
            bookingId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, status, canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'REQUESTED', ?, 0, ?, ?)",
                bookingId, SSE_PARENT_ID, SSE_PLAYER_ID, coachProfileId,
                Timestamp.from(slotStart), Timestamp.from(slotEnd),
                WINDOW_TZ, Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
            );

            // Other user — not a party to the booking
            insertUser(OTHER_USER_ID, OTHER_EMAIL, passwordHash, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                OTHER_USER_ID
            );

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE parent_id IN (?, ?)", SSE_PARENT_ID, OTHER_USER_ID);
            jdbcTemplate.update("DELETE FROM booking.session_packs_purchased WHERE parent_id = ?", SSE_PARENT_ID);
            jdbcTemplate.update("DELETE FROM marketplace.coach_availability_windows WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_pricing WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", SSE_PLAYER_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?)",
                SSE_PARENT_ID, SSE_COACH_USER_ID, OTHER_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?)",
                SSE_PARENT_ID, SSE_COACH_USER_ID, OTHER_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9600, 9601)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void subscribeToEvents_authenticatedParty_returns200WithTextEventStream() {
        String cookies = loginAndGetCookies(SSE_PARENT_EMAIL);
        String sseUrl = baseUrl() + "/api/bookings/" + bookingId + "/events";

        AtomicInteger capturedStatus = new AtomicInteger(-1);
        AtomicReference<String> capturedContentType = new AtomicReference<>("");

        // Use execute() with a custom ResponseExtractor to capture headers without consuming the SSE body
        try {
            restTemplate.execute(
                URI.create(sseUrl),
                HttpMethod.GET,
                req -> {
                    req.getHeaders().setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
                    req.getHeaders().add(SecurityConstants.API_KEY_HEADER, CLIENT_ID);
                    req.getHeaders().add(HttpHeaders.COOKIE, cookies);
                },
                response -> {
                    capturedStatus.set(response.getStatusCode().value());
                    if (response.getHeaders().getContentType() != null) {
                        capturedContentType.set(response.getHeaders().getContentType().toString());
                    }
                    return null;
                }
            );
        } catch (Exception e) {
            // SSE body-reading errors are expected; what matters is the status captured above
        }

        assertThat(capturedStatus.get()).isEqualTo(200);
        assertThat(capturedContentType.get()).contains("text/event-stream");
    }

    @Test
    void subscribeToEvents_statusChangeIsDeliveredViaSse() throws Exception {
        String cookies = loginAndGetCookies(SSE_PARENT_EMAIL);
        String sseUrl = baseUrl() + "/api/bookings/" + bookingId + "/events";

        LinkedBlockingQueue<String> receivedStatuses = new LinkedBlockingQueue<>();
        AtomicReference<Throwable> readerError = new AtomicReference<>();

        // Open SSE stream in background and collect data: lines from SSE events
        CompletableFuture<Void> sseReader = CompletableFuture.runAsync(() -> {
            try {
                restTemplate.execute(
                    URI.create(sseUrl),
                    HttpMethod.GET,
                    req -> {
                        req.getHeaders().setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
                        req.getHeaders().add(SecurityConstants.API_KEY_HEADER, CLIENT_ID);
                        req.getHeaders().add(HttpHeaders.COOKIE, cookies);
                    },
                    response -> {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getBody()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.startsWith("data:")) {
                                    receivedStatuses.add(line.substring("data:".length()).trim());
                                }
                            }
                        }
                        return null;
                    }
                );
            } catch (Exception e) {
                readerError.set(e);
            }
        });

        // Wait briefly for the SSE connection to be established (initial status event)
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> !receivedStatuses.isEmpty());

        // Fire a state transition — SSE client should receive the new status after commit
        transactionTemplate.execute(txStatus -> {
            bookingService.transition(bookingId, BookingEvent.ACCEPT,
                new TransitionContext(ActorRole.COACH, SSE_COACH_USER_ID));
            return null;
        });

        // Wait for the ACCEPTED status event to arrive over SSE
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> receivedStatuses.contains("ACCEPTED"));

        sseReader.cancel(true);

        assertThat(readerError.get()).isNull();
        assertThat(receivedStatuses).contains("ACCEPTED");
    }

    @Test
    void subscribeToEvents_unauthenticated_returns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.add(SecurityConstants.API_KEY_HEADER, CLIENT_ID);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/events",
            HttpMethod.GET,
            null,
            headers,
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void subscribeToEvents_nonParty_returns403() {
        String otherCookies = loginAndGetCookies(OTHER_EMAIL);

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.add(SecurityConstants.API_KEY_HEADER, CLIENT_ID);
        headers.add(HttpHeaders.COOKIE, otherCookies);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/events",
            HttpMethod.GET,
            null,
            headers,
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ---- helpers ----

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

    private String baseUrl() {
        return "http://localhost:" + randomServerPort;
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
