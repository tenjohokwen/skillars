package com.softropic.skillars.platform.booking.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.booking.service.RescheduleService;
import com.softropic.skillars.platform.security.SecurityIT;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class RescheduleResourceIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long PARENT_ID       = 9700000001L;
    private static final long PLAYER_ID       = 9700000002L;
    private static final long COACH_USER_ID   = 9700000010L;
    private static final long COACH_2_USER_ID = 9700000011L;

    private static final String PARENT_EMAIL  = "parent.reschedule@skillars-test.com";
    private static final String COACH_EMAIL   = "coach.reschedule@skillars-test.com";
    private static final String COACH_2_EMAIL = "coach2.reschedule@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RescheduleService rescheduleService;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private UUID coachProfile2Id;
    private UUID bookingId;
    private UUID packTierId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId  = UUID.randomUUID();
        coachProfile2Id = UUID.randomUUID();
        bookingId       = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9700, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9701, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
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
                "VALUES (?, 'Reschedule Player', '2010-01-01', 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                PLAYER_ID, PARENT_ID, Timestamp.from(Instant.now())
            );

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                COACH_USER_ID
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Reschedule Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 50.00, 'EUR')",
                coachProfileId
            );

            insertUser(COACH_2_USER_ID, COACH_2_EMAIL, passwordHash, "COACH");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                COACH_2_USER_ID
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Other Coach', 'Bio', 'Munich', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfile2Id, COACH_2_USER_ID
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 50.00, 'EUR')",
                coachProfile2Id
            );

            // Story 11.2: BookingDuplicationService's pack-eligibility check now queries
            // payment.session_pack_purchases exclusively (PackSessionService.hasActivePack),
            // not the legacy table above — an active pack is needed there too.
            packTierId = UUID.randomUUID();
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
                UUID.randomUUID(), PARENT_ID, PLAYER_ID, coachProfileId, packTierId,
                Timestamp.from(Instant.now().plus(180, ChronoUnit.DAYS))
            );

            insertConfirmedBooking(bookingId);
            return null;
        });
    }


    // ---- Tests ----

    @Test
    void requestReschedule_asParentWithConfirmedBooking_returns204AndCreatesRecord() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        Instant proposedStart = Instant.now().plus(5, ChronoUnit.DAYS);
        Instant proposedEnd   = proposedStart.plus(1, ChronoUnit.HOURS);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/reschedule",
            HttpMethod.POST,
            Map.of("proposedStartTime", proposedStart.toString(), "proposedEndTime", proposedEnd.toString()),
            authenticatedHeaders(parentCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking.booking_reschedule_requests WHERE booking_id = ? AND status = 'PENDING'",
            Integer.class, bookingId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void requestReschedule_unauthenticated_returns401() {
        Instant proposedStart = Instant.now().plus(5, ChronoUnit.DAYS);
        Instant proposedEnd   = proposedStart.plus(1, ChronoUnit.HOURS);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/reschedule",
            HttpMethod.POST,
            Map.of("proposedStartTime", proposedStart.toString(), "proposedEndTime", proposedEnd.toString()),
            clientHeaders(), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void requestReschedule_asCoach_returns403() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        Instant proposedStart = Instant.now().plus(5, ChronoUnit.DAYS);
        Instant proposedEnd   = proposedStart.plus(1, ChronoUnit.HOURS);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/reschedule",
            HttpMethod.POST,
            Map.of("proposedStartTime", proposedStart.toString(), "proposedEndTime", proposedEnd.toString()),
            authenticatedHeaders(coachCookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void requestReschedule_wrongParent_returns403() {
        // Create a second parent with their own booking — then try to reschedule bookingId as them
        long otherParentId = 9700000003L;
        String otherParentEmail = "other.parent.reschedule@skillars-test.com";
        transactionTemplate.execute(s -> {
            insertUser(otherParentId, otherParentEmail, passwordEncoder.encode(TEST_PASSWORD), "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                otherParentId
            );
            return null;
        });
        try {
            String otherCookies = loginAndGetCookies(otherParentEmail);
            Instant proposedStart = Instant.now().plus(5, ChronoUnit.DAYS);

            assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
                baseUrl() + "/api/bookings/" + bookingId + "/reschedule",
                HttpMethod.POST,
                Map.of("proposedStartTime", proposedStart.toString(),
                       "proposedEndTime", proposedStart.plus(1, ChronoUnit.HOURS).toString()),
                authenticatedHeaders(otherCookies), Void.class
            ))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        } finally {
            transactionTemplate.execute(s -> {
                jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id = ?", otherParentId);
                jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", otherParentId);
                return null;
            });
        }
    }

    @Test
    void acceptReschedule_asOwningCoach_returns204AndUpdatesBookingAndStatus() {
        UUID rescheduleId = insertPendingReschedule();
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/reschedule/" + rescheduleId + "/accept",
            HttpMethod.PUT, null, authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String rescheduleStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.booking_reschedule_requests WHERE id = ?",
            String.class, rescheduleId);
        assertThat(rescheduleStatus).isEqualTo("ACCEPTED");

        Timestamp proposedStart = jdbcTemplate.queryForObject(
            "SELECT proposed_start_time FROM booking.booking_reschedule_requests WHERE id = ?",
            Timestamp.class, rescheduleId);
        Timestamp newStart = jdbcTemplate.queryForObject(
            "SELECT requested_start_time FROM booking.bookings WHERE id = ?",
            Timestamp.class, bookingId);
        assertThat(newStart).isEqualTo(proposedStart);
    }

    /**
     * Deferred-14 AC4. acceptReschedule rewrites the booking's time window; before this story it did
     * so with no overlap check, so a collision was caught only by the V87 exclusion constraint at
     * commit — an unmapped 500 rather than the clean booking.slotUnavailable every other accept path
     * returns. 403 (not 409) is deliberate: it matches createBookingRequest and acceptBooking, which
     * BookingServiceConcurrencyIT pins.
     */
    @Test
    void acceptReschedule_proposedSlotTakenByAnotherBooking_returns403AndLeavesBookingUnchanged() {
        UUID rescheduleId = insertPendingReschedule();
        // insertPendingReschedule proposes now+7d for 1h — occupy exactly that window.
        Instant proposed = Instant.now().plus(7, ChronoUnit.DAYS);
        UUID blockerId = UUID.randomUUID();
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, " +
                "status, canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', 'Europe/Berlin', 0, ?, ?)",
                blockerId, PARENT_ID, PLAYER_ID, coachProfileId,
                Timestamp.from(proposed), Timestamp.from(proposed.plus(1, ChronoUnit.HOURS)),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
            );
            return null;
        });

        Timestamp originalStart = jdbcTemplate.queryForObject(
            "SELECT requested_start_time FROM booking.bookings WHERE id = ?", Timestamp.class, bookingId);

        String coachCookies = loginAndGetCookies(COACH_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/reschedule/" + rescheduleId + "/accept",
            HttpMethod.PUT, null, authenticatedHeaders(coachCookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getResponseBodyAsString()).contains("booking.slotUnavailable");
            });

        assertThat(jdbcTemplate.queryForObject(
            "SELECT requested_start_time FROM booking.bookings WHERE id = ?", Timestamp.class, bookingId))
            .as("booking window must be untouched")
            .isEqualTo(originalStart);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM booking.booking_reschedule_requests WHERE id = ?", String.class, rescheduleId))
            .as("reschedule request must stay PENDING")
            .isEqualTo("PENDING");
    }

    @Test
    void declineReschedule_asOwningCoach_returns204AndSetsDeclined() {
        UUID rescheduleId = insertPendingReschedule();
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/reschedule/" + rescheduleId + "/decline",
            HttpMethod.PUT, null, authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String rescheduleStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.booking_reschedule_requests WHERE id = ?",
            String.class, rescheduleId);
        assertThat(rescheduleStatus).isEqualTo("DECLINED");
    }

    @Test
    void acceptReschedule_wrongCoach_returns403() {
        UUID rescheduleId = insertPendingReschedule();
        String coach2Cookies = loginAndGetCookies(COACH_2_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/reschedule/" + rescheduleId + "/accept",
            HttpMethod.PUT, null, authenticatedHeaders(coach2Cookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void duplicateNextWeek_asOwningCoachWithCompletedBooking_returns204() {
        setBookingStatus("COMPLETED");
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/duplicate-next-week",
            HttpMethod.POST, null, authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Integer newBookingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking.bookings WHERE parent_id = ? AND id != ?",
            Integer.class, PARENT_ID, bookingId);
        assertThat(newBookingCount).isEqualTo(1);
    }

    @Test
    void duplicateNextWeek_asParent_returns403() {
        setBookingStatus("COMPLETED");
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/duplicate-next-week",
            HttpMethod.POST, null, authenticatedHeaders(parentCookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ---- Helpers ----

    /**
     * Deferred-15 AC3: accept and decline are mutually exclusive.
     *
     * <p>The race is driven deterministically rather than by luck, following
     * BookingServiceConcurrencyIT: a second connection takes {@code SELECT … FOR UPDATE} on the
     * reschedule row and flips it to DECLINED without committing. The accepting thread's unlocked
     * read at the top of acceptReschedule therefore still observes PENDING (READ COMMITTED) — the
     * exact stale view a caller holds while parked — and then blocks on findByIdForUpdate. Once the
     * declining transaction commits, the accept acquires the lock over a row that is now DECLINED.
     *
     * <p>The assertion is on FINAL STORED STATE, not on whether the accept waited: per deferred-13,
     * barrier tests routinely pass against unfixed code because waiting is not the discriminator.
     * Mutation-verified in both directions — remove either the post-lock PENDING re-check or the
     * entityManager.refresh that precedes it and the accept overwrites the decline with ACCEPTED.
     */
    @Test
    void acceptReschedule_declineCommitsWhileAcceptWaitsOnTheLock_acceptFailsAndDeclineStands() throws Exception {
        Instant proposedStart = Instant.now().plus(9, ChronoUnit.DAYS);
        Instant proposedEnd = proposedStart.plus(1, ChronoUnit.HOURS);
        UUID rescheduleId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO booking.booking_reschedule_requests (id, booking_id, proposed_by, " +
                "proposed_start_time, proposed_end_time, status) VALUES (?, ?, 'PARENT', ?, ?, 'PENDING')",
                rescheduleId, bookingId, Timestamp.from(proposedStart), Timestamp.from(proposedEnd));
            return null;
        });
        Instant originalStart = jdbcTemplate.queryForObject(
            "SELECT requested_start_time FROM booking.bookings WHERE id = ?", Timestamp.class, bookingId).toInstant();

        CountDownLatch declineStagedAndLockHeld = new CountDownLatch(1);
        AtomicReference<Throwable> declinerFailure = new AtomicReference<>();
        AtomicReference<Throwable> acceptOutcome = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> decliner = executor.submit(() -> {
            try {
                transactionTemplate.execute(status -> {
                    jdbcTemplate.queryForObject(
                        "SELECT status FROM booking.booking_reschedule_requests WHERE id = ? FOR UPDATE",
                        String.class, rescheduleId);
                    jdbcTemplate.update(
                        "UPDATE booking.booking_reschedule_requests SET status = 'DECLINED' WHERE id = ?",
                        rescheduleId);
                    declineStagedAndLockHeld.countDown();
                    // Hold the lock long enough for the accept to pass its unlocked read and block
                    // on findByIdForUpdate; the repository's lock timeout is 5s.
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } catch (Throwable t) {
                declinerFailure.set(t);
            }
        });

        Future<?> accepter = executor.submit(() -> {
            try {
                declineStagedAndLockHeld.await(10, TimeUnit.SECONDS);
                rescheduleService.acceptReschedule(bookingId, rescheduleId, COACH_USER_ID);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                acceptOutcome.set(t);
            }
        });

        decliner.get(30, TimeUnit.SECONDS);
        accepter.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        if (declinerFailure.get() != null) {
            throw new AssertionError("Declining thread failed", declinerFailure.get());
        }

        assertThat(acceptOutcome.get())
            .as("the accept must fail once its locked re-read sees the committed decline")
            .isInstanceOf(OperationNotAllowedException.class);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM booking.booking_reschedule_requests WHERE id = ?", String.class, rescheduleId))
            .as("the coach's decline must stand — this is the discriminator, not whether the accept waited")
            .isEqualTo("DECLINED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT requested_start_time FROM booking.bookings WHERE id = ?", Timestamp.class, bookingId).toInstant())
            .as("a silently overwritten decline also rewrites the booking's times")
            .isEqualTo(originalStart);
    }

    /**
     * Deferred-15 AC3, review follow-up — the mirror image of the test above, and the only thing
     * that exercises declineReschedule's own lock.
     *
     * <p>The accept-side test stages its contending decline with raw {@code SELECT … FOR UPDATE}
     * SQL, which is a lock-shaped stand-in: it proves acceptReschedule re-reads under a lock, but it
     * would pass just as well if declineReschedule had kept its plain findById. Mutual exclusion
     * needs BOTH sides to take the lock, so both sides need a test. Here the real
     * declineReschedule is the method under test and the accept is the stand-in.
     *
     * <p>Same discriminator as before — not whether the decline waits, but what it observes once
     * unblocked. A locked read taken after the concurrent accept commits sees ACCEPTED and refuses;
     * a plain findById holds the stale PENDING snapshot and silently overwrites the accept with
     * DECLINED, leaving the booking's rewritten times behind with a DECLINED request beside them.
     */
    @Test
    void declineReschedule_acceptCommitsWhileDeclineWaitsOnTheLock_declineFailsAndAcceptStands() throws Exception {
        Instant proposedStart = Instant.now().plus(11, ChronoUnit.DAYS);
        Instant proposedEnd = proposedStart.plus(1, ChronoUnit.HOURS);
        UUID rescheduleId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO booking.booking_reschedule_requests (id, booking_id, proposed_by, " +
                "proposed_start_time, proposed_end_time, status) VALUES (?, ?, 'PARENT', ?, ?, 'PENDING')",
                rescheduleId, bookingId, Timestamp.from(proposedStart), Timestamp.from(proposedEnd));
            return null;
        });

        CountDownLatch acceptStagedAndLockHeld = new CountDownLatch(1);
        AtomicReference<Throwable> stagerFailure = new AtomicReference<>();
        AtomicReference<Throwable> declineOutcome = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> stager = executor.submit(() -> {
            try {
                transactionTemplate.execute(status -> {
                    jdbcTemplate.queryForObject(
                        "SELECT status FROM booking.booking_reschedule_requests WHERE id = ? FOR UPDATE",
                        String.class, rescheduleId);
                    jdbcTemplate.update(
                        "UPDATE booking.booking_reschedule_requests SET status = 'ACCEPTED' WHERE id = ?",
                        rescheduleId);
                    acceptStagedAndLockHeld.countDown();
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } catch (Throwable t) {
                stagerFailure.set(t);
            }
        });

        Future<?> decliner = executor.submit(() -> {
            try {
                acceptStagedAndLockHeld.await(10, TimeUnit.SECONDS);
                rescheduleService.declineReschedule(bookingId, rescheduleId, COACH_USER_ID);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                declineOutcome.set(t);
            }
        });

        stager.get(30, TimeUnit.SECONDS);
        decliner.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        if (stagerFailure.get() != null) {
            throw new AssertionError("Staging thread failed", stagerFailure.get());
        }

        assertThat(declineOutcome.get())
            .as("the decline must fail once its locked re-read sees the committed accept")
            .isInstanceOf(OperationNotAllowedException.class);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM booking.booking_reschedule_requests WHERE id = ?", String.class, rescheduleId))
            .as("the accept must stand — a stale-read decline overwrites it silently")
            .isEqualTo("ACCEPTED");
    }

    private void insertConfirmedBooking(UUID id) {
        Instant futureStart = Instant.now().plus(2, ChronoUnit.DAYS);
        jdbcTemplate.update(
            "INSERT INTO booking.bookings " +
            "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, " +
            "status, canonical_timezone, version, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', 'Europe/Berlin', 0, ?, ?)",
            id, PARENT_ID, PLAYER_ID, coachProfileId,
            Timestamp.from(futureStart), Timestamp.from(futureStart.plus(1, ChronoUnit.HOURS)),
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
        );
    }

    private UUID insertPendingReschedule() {
        UUID id = UUID.randomUUID();
        Instant proposed = Instant.now().plus(7, ChronoUnit.DAYS);
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO booking.booking_reschedule_requests " +
                "(id, booking_id, proposed_by, proposed_start_time, proposed_end_time, status, created_at) " +
                "VALUES (?, ?, 'PARENT', ?, ?, 'PENDING', ?)",
                id, bookingId, Timestamp.from(proposed),
                Timestamp.from(proposed.plus(1, ChronoUnit.HOURS)),
                Timestamp.from(Instant.now())
            );
            return null;
        });
        return id;
    }

    private void setBookingStatus(String status) {
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE booking.bookings SET status = ?, requested_start_time = ? WHERE id = ?",
                status, Timestamp.from(Instant.now().minus(2, ChronoUnit.DAYS)), bookingId);
            return null;
        });
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
            "70" + (id % 100000000),
            email, passwordHash, role
        );
    }
}
