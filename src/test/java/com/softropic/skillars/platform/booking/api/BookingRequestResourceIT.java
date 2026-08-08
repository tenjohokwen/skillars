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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class BookingRequestResourceIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT    = "/api/auth/login";
    private static final String BOOKINGS_BASE     = "/api/bookings/requests";
    private static final String CLIENT_ID         = "testClientId";
    private static final String TEST_PASSWORD     = "TestPass@123!";

    private static final long PARENT_ID      = 9500000001L;
    private static final long PLAYER_ID      = 9500000002L;
    private static final long COACH_USER_ID  = 9500000010L;
    private static final long COACH_USER_ID2 = 9500000020L;

    private static final String PARENT_EMAIL = "parent.booking@skillars-test.com";
    private static final String COACH_EMAIL  = "coach.booking@skillars-test.com";
    private static final String COACH_EMAIL2 = "coach2.booking@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private UUID coachProfileId2;

    // Availability window covering next day 08:00–18:00 Europe/Berlin
    private static final String WINDOW_TZ = "Europe/Berlin";
    /**
     * coach_profiles.canonical_timezone — a separate, independently-writable column from
     * coach_availability_windows.canonical_timezone (WINDOW_TZ). They hold the same value in this
     * fixture, but a booking's canonicalTimezone is sourced from THIS one (deferred-17 AC3), so
     * assertions on a booking's zone must name this constant. Anything that diverges the two
     * columns then changes only the fixture, not the assertions.
     */
    private static final String COACH_PROFILE_TZ = WINDOW_TZ;
    private Instant slotStart;
    private Instant slotEnd;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);

        ZonedDateTime nextDaySlot = ZonedDateTime.now(ZoneId.of(WINDOW_TZ)).plusDays(1)
            .withHour(10).withMinute(0).withSecond(0).withNano(0);
        slotStart = nextDaySlot.toInstant();
        slotEnd = nextDaySlot.plusHours(1).toInstant();
        short windowDow = (short) nextDaySlot.getDayOfWeek().getValue();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9500, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9501, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_ID
            );

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Booking Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(16)),
                PARENT_ID, Timestamp.from(Instant.now())
            );

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                COACH_USER_ID
            );

            coachProfileId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Book Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], ?, 'ACTIVE')",
                coachProfileId, COACH_USER_ID, COACH_PROFILE_TZ
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 50.00, 'EUR')",
                coachProfileId
            );

            // Availability window covering the test slot
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_availability_windows " +
                "(id, coach_id, day_of_week, start_time, end_time, canonical_timezone) " +
                "VALUES (?, ?, ?, '08:00', '18:00', ?)",
                UUID.randomUUID(), coachProfileId, windowDow, WINDOW_TZ
            );

            // Second coach (for wrong-coach tests)
            insertUser(COACH_USER_ID2, COACH_EMAIL2, passwordHash, "COACH");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                COACH_USER_ID2
            );
            coachProfileId2 = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Book Coach 2', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId2, COACH_USER_ID2
            );

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM payment.booking_payments WHERE booking_id IN (SELECT id FROM booking.bookings WHERE parent_id = ?)", PARENT_ID);
            // parent_credit_ledger is append-only (V79 triggers); bypass for test cleanup only
            jdbcTemplate.execute("SET SESSION session_replication_role = 'replica'");
            jdbcTemplate.update("DELETE FROM payment.parent_credit_ledger WHERE parent_id = ?", PARENT_ID);
            jdbcTemplate.execute("SET SESSION session_replication_role = 'origin'");
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE parent_id = ?", PARENT_ID);
            jdbcTemplate.update("DELETE FROM marketplace.coach_availability_windows WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_pricing WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id IN (?, ?)", coachProfileId, coachProfileId2);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", PLAYER_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?)", PARENT_ID, COACH_USER_ID, COACH_USER_ID2);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?)", PARENT_ID, COACH_USER_ID, COACH_USER_ID2);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (9500, 9501)");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void createBookingRequest_validRequest_returns201AndBookingResponse() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE,
            HttpMethod.POST,
            Map.of(
                "coachId", coachProfileId.toString(),
                "playerId", PLAYER_ID,
                "requestedStartTime", slotStart.toString(),
                "requestedEndTime", slotEnd.toString()
            ),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKey("id");
        assertThat(response.getBody().get("status")).isEqualTo("REQUESTED");
        // Story 11.2: effectiveCreditsRemaining was removed from BookingResponse — the
        // coach+player-wide legacy credit rollup has no equivalent on the new payment-path
        // schema; the frontend now reads pack data from GET /api/payment/session-packs instead.
        assertThat(response.getBody()).doesNotContainKey("effectiveCreditsRemaining");
    }

    /**
     * Deferred-17 AC3. The coach fixture's canonical_timezone is COACH_PROFILE_TZ ("Europe/Berlin"); this
     * sends a deliberately different, and now-unknown, client-supplied canonicalTimezone
     * ("America/New_York" — CreateBookingRequest no longer has this field, so Spring's
     * ignore-unknown-JSON-properties default silently drops it) and asserts on the actual response
     * body value, not just the status code, per Task 1's warning that a status-only assertion
     * cannot discriminate the fix from a no-op. Pre-fix this test failed: the client's value won.
     */
    @Test
    void createBookingRequest_ignoresClientTimezone_usesCoachProfileTimezone() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE,
            HttpMethod.POST,
            Map.of(
                "coachId", coachProfileId.toString(),
                "playerId", PLAYER_ID,
                "requestedStartTime", slotStart.toString(),
                "requestedEndTime", slotEnd.toString(),
                "canonicalTimezone", "America/New_York"
            ),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("canonicalTimezone")).isEqualTo(COACH_PROFILE_TZ);
    }

    /**
     * Deferred-14 AC6. An end-before-start window is a malformed request, not a security violation.
     * Before this story the service layer caught it with SecurityError.MISSING_RIGHTS and the caller
     * saw 403; bean validation now short-circuits it to 400 before the service is reached.
     */
    @Test
    void createBookingRequest_endBeforeStart_returns400NotForbidden() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE,
            HttpMethod.POST,
            Map.of(
                "coachId", coachProfileId.toString(),
                "playerId", PLAYER_ID,
                "requestedStartTime", slotEnd.toString(),
                "requestedEndTime", slotStart.toString()
            ),
            authenticatedHeaders(cookies),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /** Equal start and end is also rejected — the window must be strictly positive. */
    @Test
    void createBookingRequest_zeroLengthWindow_returns400() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE,
            HttpMethod.POST,
            Map.of(
                "coachId", coachProfileId.toString(),
                "playerId", PLAYER_ID,
                "requestedStartTime", slotStart.toString(),
                "requestedEndTime", slotStart.toString()
            ),
            authenticatedHeaders(cookies),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void createBookingRequest_noCredits_succeeds_paymentDeferred() {
        // Sprint 7.2: credit depletion no longer blocks booking
        String cookies = loginAndGetCookies(PARENT_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE,
            HttpMethod.POST,
            Map.of(
                "coachId", coachProfileId.toString(),
                "playerId", PLAYER_ID,
                "requestedStartTime", slotStart.toString(),
                "requestedEndTime", slotEnd.toString()
            ),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("REQUESTED");
    }

    @Test
    void createBookingRequest_secondRequestWhenCreditExhausted_succeeds_paymentDeferred() {
        // Sprint 7.2: second booking when credits exhausted is allowed; payment is deferred to accept time
        transactionTemplate.execute(status -> {
            // Insert an in-flight booking to simulate credit exhaustion
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, status, canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'REQUESTED', ?, 0, ?, ?)",
                UUID.randomUUID(), PARENT_ID, PLAYER_ID, coachProfileId,
                Timestamp.from(slotStart.plusSeconds(7200)), Timestamp.from(slotEnd.plusSeconds(7200)),
                COACH_PROFILE_TZ, Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
            );
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE,
            HttpMethod.POST,
            Map.of(
                "coachId", coachProfileId.toString(),
                "playerId", PLAYER_ID,
                "requestedStartTime", slotStart.toString(),
                "requestedEndTime", slotEnd.toString()
            ),
            authenticatedHeaders(cookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("REQUESTED");
    }

    @Test
    void createBookingRequest_playerNotOwnedByParent_returns403() {
        // Create a second parent
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9502, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            insertUser(9500000099L, "other.parent@skillars-test.com", passwordEncoder.encode(TEST_PASSWORD), "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (9500000099, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING"
            );
            return null;
        });

        try {
            String cookies = loginAndGetCookies("other.parent@skillars-test.com");

            assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
                baseUrl() + BOOKINGS_BASE,
                HttpMethod.POST,
                Map.of(
                    "coachId", coachProfileId.toString(),
                    "playerId", PLAYER_ID,  // owned by PARENT_ID, not this parent
                    "requestedStartTime", slotStart.toString(),
                    "requestedEndTime", slotEnd.toString()
                ),
                authenticatedHeaders(cookies),
                Map.class
            ))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        } finally {
            transactionTemplate.execute(status -> {
                jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
                jdbcTemplate.execute("DELETE FROM main.login_attempts");
                jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id = 9500000099");
                jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = 9500000099");
                return null;
            });
        }
    }

    @Test
    void createBookingRequest_otherParentsSessionPack_returns403() {
        long otherParentId = 9500000098L;
        UUID packTierId = UUID.randomUUID();
        UUID purchaseId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            insertUser(otherParentId, "other.pack.parent@skillars-test.com", passwordEncoder.encode(TEST_PASSWORD), "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                otherParentId
            );
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_tiers " +
                "(pack_tier_id, coach_id, label, session_count, total_price, price_per_session, is_active, version, created_at) " +
                "VALUES (?, ?, '5-Pack', 5, 150.00, 30.00, true, 0, now())",
                packTierId, coachProfileId
            );
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_purchases " +
                "(purchase_id, parent_id, player_id, coach_id, pack_tier_id, price_per_session, remaining_sessions, expires_at, version, created_at) " +
                "VALUES (?, ?, ?, ?, ?, 30.00, 5, ?, 0, now())",
                purchaseId, otherParentId, PLAYER_ID, coachProfileId, packTierId,
                Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS))
            );
            return null;
        });

        try {
            String cookies = loginAndGetCookies(PARENT_EMAIL);

            assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
                baseUrl() + BOOKINGS_BASE,
                HttpMethod.POST,
                Map.of(
                    "coachId", coachProfileId.toString(),
                    "playerId", PLAYER_ID,
                    "requestedStartTime", slotStart.toString(),
                    "requestedEndTime", slotEnd.toString(),
                    "sessionPackPurchaseId", purchaseId.toString()
                ),
                authenticatedHeaders(cookies),
                Map.class
            ))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        } finally {
            transactionTemplate.execute(status -> {
                jdbcTemplate.update("DELETE FROM payment.session_pack_purchases WHERE purchase_id = ?", purchaseId);
                jdbcTemplate.update("DELETE FROM payment.session_pack_tiers WHERE pack_tier_id = ?", packTierId);
                jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
                jdbcTemplate.execute("DELETE FROM main.login_attempts");
                jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id = ?", otherParentId);
                jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", otherParentId);
                return null;
            });
        }
    }

    @Test
    void createBookingRequest_coachInDraftStatus_returns422() {
        // Set coach to DRAFT status
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("UPDATE marketplace.coach_profiles SET status = 'DRAFT' WHERE id = ?", coachProfileId);
            return null;
        });

        try {
            String cookies = loginAndGetCookies(PARENT_EMAIL);

            assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
                baseUrl() + BOOKINGS_BASE,
                HttpMethod.POST,
                Map.of(
                    "coachId", coachProfileId.toString(),
                    "playerId", PLAYER_ID,
                    "requestedStartTime", slotStart.toString(),
                    "requestedEndTime", slotEnd.toString()
                ),
                authenticatedHeaders(cookies),
                Map.class
            ))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                    .isIn(HttpStatus.FORBIDDEN, HttpStatus.UNPROCESSABLE_ENTITY));
        } finally {
            transactionTemplate.execute(status -> {
                jdbcTemplate.update("UPDATE marketplace.coach_profiles SET status = 'ACTIVE' WHERE id = ?", coachProfileId);
                return null;
            });
        }
    }

    @Test
    void createBookingRequest_slotOutsideAvailabilityWindow_returns422() {
        // Request a time 3 weeks in the future — far outside a next-day window
        Instant farFuture = Instant.now().plusSeconds(21 * 24 * 3600);
        String cookies = loginAndGetCookies(PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE,
            HttpMethod.POST,
            Map.of(
                "coachId", coachProfileId.toString(),
                "playerId", PLAYER_ID,
                "requestedStartTime", farFuture.toString(),
                "requestedEndTime", farFuture.plusSeconds(3600).toString()
            ),
            authenticatedHeaders(cookies),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void acceptBooking_validCoach_returns200AndConfirmsBooking() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        // Create booking first
        ResponseEntity<Map> createResp = httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE,
            HttpMethod.POST,
            Map.of(
                "coachId", coachProfileId.toString(),
                "playerId", PLAYER_ID,
                "requestedStartTime", slotStart.toString(),
                "requestedEndTime", slotEnd.toString()
            ),
            authenticatedHeaders(parentCookies),
            Map.class
        );
        String bookingId = (String) createResp.getBody().get("id");

        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> acceptResp = httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE + "/" + bookingId + "/accept",
            HttpMethod.PUT,
            null,
            authenticatedHeaders(coachCookies),
            Map.class
        );

        assertThat(acceptResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(acceptResp.getBody().get("status")).isEqualTo("CONFIRMED");
    }

    @Test
    void acceptBooking_wrongCoach_returns403() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> createResp = httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE,
            HttpMethod.POST,
            Map.of(
                "coachId", coachProfileId.toString(),
                "playerId", PLAYER_ID,
                "requestedStartTime", slotStart.toString(),
                "requestedEndTime", slotEnd.toString()
            ),
            authenticatedHeaders(parentCookies),
            Map.class
        );
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String bookingId = (String) createResp.getBody().get("id");

        // Second coach tries to accept
        String coach2Cookies = loginAndGetCookies(COACH_EMAIL2);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE + "/" + bookingId + "/accept",
            HttpMethod.PUT,
            null,
            authenticatedHeaders(coach2Cookies),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void declineBooking_wrongCoach_returns403() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> createResp = httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE,
            HttpMethod.POST,
            Map.of(
                "coachId", coachProfileId.toString(),
                "playerId", PLAYER_ID,
                "requestedStartTime", slotStart.toString(),
                "requestedEndTime", slotEnd.toString()
            ),
            authenticatedHeaders(parentCookies),
            Map.class
        );
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String bookingId = (String) createResp.getBody().get("id");

        // Second coach tries to decline
        String coach2Cookies = loginAndGetCookies(COACH_EMAIL2);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE + "/" + bookingId + "/decline",
            HttpMethod.PUT,
            null,
            authenticatedHeaders(coach2Cookies),
            Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void declineBooking_validCoach_returns204() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> createResp = httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE,
            HttpMethod.POST,
            Map.of(
                "coachId", coachProfileId.toString(),
                "playerId", PLAYER_ID,
                "requestedStartTime", slotStart.toString(),
                "requestedEndTime", slotEnd.toString()
            ),
            authenticatedHeaders(parentCookies),
            Map.class
        );
        String bookingId = (String) createResp.getBody().get("id");

        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Void> declineResp = httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE + "/" + bookingId + "/decline",
            HttpMethod.PUT,
            null,
            authenticatedHeaders(coachCookies),
            Void.class
        );

        assertThat(declineResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void getParentBookings_returnsListSortedByStartTime() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);

        // Booking 1, via the API, at the near-term slot
        httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE,
            HttpMethod.POST,
            Map.of(
                "coachId", coachProfileId.toString(),
                "playerId", PLAYER_ID,
                "requestedStartTime", slotStart.toString(),
                "requestedEndTime", slotEnd.toString()
            ),
            authenticatedHeaders(cookies),
            Map.class
        );

        // Booking 2, inserted directly further in the future, so the two rows exist
        // out of natural insertion order relative to requestedStartTime
        Instant laterStart = slotStart.plus(4, ChronoUnit.DAYS);
        Instant laterEnd = laterStart.plusSeconds(3600);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, status, canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'REQUESTED', ?, 0, ?, ?)",
                UUID.randomUUID(), PARENT_ID, PLAYER_ID, coachProfileId,
                Timestamp.from(laterStart), Timestamp.from(laterEnd),
                COACH_PROFILE_TZ, Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
            );
            return null;
        });

        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE,
            HttpMethod.GET,
            null,
            authenticatedHeaders(cookies),
            List.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> bookings = response.getBody();
        assertThat(bookings).hasSizeGreaterThanOrEqualTo(2);
        // requestedStartTime is serialized as an ISO-8601 string (WRITE_DATES_AS_TIMESTAMPS: false);
        // parse to Instant rather than comparing strings, since lexicographic order isn't
        // guaranteed to match chronological order across differing fractional-second precision
        List<Instant> startTimes = bookings.stream()
            .map(b -> Instant.parse((String) b.get("requestedStartTime")))
            .toList();
        assertThat(startTimes).isSortedAccordingTo(Comparator.naturalOrder());
    }

    @Test
    void getCoachBookingRequests_returnsOnlyRequestedBookingsForThisCoach() {
        // Create a booking first
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE,
            HttpMethod.POST,
            Map.of(
                "coachId", coachProfileId.toString(),
                "playerId", PLAYER_ID,
                "requestedStartTime", slotStart.toString(),
                "requestedEndTime", slotEnd.toString()
            ),
            authenticatedHeaders(parentCookies),
            Map.class
        );

        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + BOOKINGS_BASE + "/coach",
            HttpMethod.GET,
            null,
            authenticatedHeaders(coachCookies),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> singleBookings = (List<?>) response.getBody().get("singleBookings");
        assertThat(singleBookings).isNotEmpty();
        Map<?, ?> booking = (Map<?, ?>) singleBookings.get(0);
        assertThat(booking.get("status")).isEqualTo("REQUESTED");
        assertThat(booking.get("parentName"))
            .as("parentName (AC 8 of Story 3.3)")
            .isNotNull()
            .isNotEqualTo("");
    }

    // ---- helpers ----

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
