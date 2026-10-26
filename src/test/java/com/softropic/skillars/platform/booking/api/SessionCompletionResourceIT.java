package com.softropic.skillars.platform.booking.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
import com.softropic.skillars.platform.session.contract.HomeworkAssignedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class SessionCompletionResourceIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long PARENT_ID      = 9600000001L;
    private static final long PLAYER_ID      = 9600000002L;
    private static final long COACH_USER_ID  = 9600000010L;
    private static final long COACH_2_USER_ID = 9600000011L;

    private static final String PARENT_EMAIL  = "parent.completion@skillars-test.com";
    private static final String COACH_EMAIL   = "coach.completion@skillars-test.com";
    private static final String COACH_2_EMAIL = "coach2.completion@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private com.softropic.skillars.platform.booking.service.QuickCompleteTimeoutService quickCompleteTimeoutService;

    @LocalServerPort private int randomServerPort;

    private UUID coachProfileId;
    private UUID coachProfile2Id;
    private UUID bookingId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId = UUID.randomUUID();
        coachProfile2Id = UUID.randomUUID();
        bookingId = UUID.randomUUID();

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

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_ID
            );

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Completion Player', '2008-01-01', 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
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
                "VALUES (?, ?, 'Completion Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
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

            insertUpcomingBooking(bookingId);
            return null;
        });
    }


    // ---- Tests ----

    @Test
    void startSession_upcomingBooking_returns204AndStatusIsInProgress() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/start",
            HttpMethod.POST, null, authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
        assertThat(status).isEqualTo("IN_PROGRESS");
        // skillars-12-7 code review (Decision 2): recorded so "Resume wrap-up" can reopen the wizard LIVE.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT live_started_at IS NOT NULL FROM booking.bookings WHERE id = ?", Boolean.class, bookingId)).isTrue();
    }

    @Test
    void startSession_unauthenticated_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/start",
            HttpMethod.POST, null, clientHeaders(), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void startSession_parentRole_returns403() {
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/start",
            HttpMethod.POST, null, authenticatedHeaders(parentCookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void endSession_fromInProgress_returns204AndStatusIsCompletedPendingConfirmation() {
        setBookingStatus("IN_PROGRESS");
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/end",
            HttpMethod.POST, null, authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
        assertThat(status).isEqualTo("COMPLETED_PENDING_CONFIRMATION");
    }

    @Test
    void submitWrapUp_liveMode_returns204AndStatusIsCompleted() {
        setBookingStatus("COMPLETED_PENDING_CONFIRMATION");
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/complete",
            HttpMethod.POST,
            Map.of(
                "playerAttended", true,
                "effortRating", 4,
                "focusRating", 3,
                "techniqueRating", 5,
                "mode", "LIVE",
                "recapWhatWeDid", "Worked on first touch",
                "recapWhatsNext", "Continue weak-foot drills"
            ),
            authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
        assertThat(status).isEqualTo("COMPLETED");

        Map<String, Object> recap = jdbcTemplate.queryForMap(
            "SELECT what_we_did, whats_next FROM booking.session_completion_data WHERE booking_id = ?", bookingId);
        assertThat(recap.get("what_we_did")).isEqualTo("Worked on first touch");
        assertThat(recap.get("whats_next")).isEqualTo("Continue weak-foot drills");
    }

    // skillars-12-7 AC10/AC13: what went well is stored; a deployed client still sending the removed
    // voiceNoteText field is ignored (204), and that text is NOT persisted any more.
    @Test
    void submitWrapUp_withWhatWentWellAndLegacyVoiceNoteField_returns204_storesWellIgnoresVoiceNote() {
        setBookingStatus("COMPLETED_PENDING_CONFIRMATION");
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/complete",
            HttpMethod.POST,
            Map.of(
                "playerAttended", true,
                "effortRating", 4,
                "focusRating", 3,
                "techniqueRating", 5,
                "mode", "LIVE",
                "voiceNoteText", "legacy client still sends this",
                "recapWhatWeDid", "Worked on first touch",
                "recapWhatsNext", "Continue weak-foot drills",
                "recapWhatWentWell", "  Great tempo  "
            ),
            authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT what_went_well, voice_note_text FROM booking.session_completion_data WHERE booking_id = ?", bookingId);
        assertThat(row.get("what_went_well")).isEqualTo("Great tempo");
        assertThat(row.get("voice_note_text")).isNull();
    }

    // skillars-12-7 AC7a: the resume path for an abandoned quick-complete -- the booking sits in
    // COMPLETED_PENDING_CONFIRMATION with no SCD row, and a later submitWrapUp must succeed.
    @Test
    void submitWrapUp_afterAbandonedQuickComplete_succeedsAndWritesTheRow() {
        setBookingStatus("COMPLETED_PENDING_CONFIRMATION");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM booking.session_completion_data WHERE booking_id = ?", Integer.class, bookingId))
            .isZero();
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/complete",
            HttpMethod.POST,
            Map.of(
                "playerAttended", true,
                "mode", "QUICK",
                "recapWhatWeDid", "Resumed recap",
                "recapWhatsNext", "Resumed next"
            ),
            authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT what_we_did FROM booking.session_completion_data WHERE booking_id = ?", String.class, bookingId))
            .isEqualTo("Resumed recap");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId))
            .isEqualTo("COMPLETED_PENDING_CONFIRMATION");
    }

    @Test
    void submitWrapUp_quickMode_returns204AndStatusRemainsCompletedPendingConfirmation() {
        setBookingStatus("COMPLETED_PENDING_CONFIRMATION");
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/complete",
            HttpMethod.POST,
            Map.of(
                "playerAttended", true,
                "effortRating", 3,
                "focusRating", 4,
                "techniqueRating", 4,
                "mode", "QUICK",
                "recapWhatWeDid", "Worked on passing accuracy",
                "recapWhatsNext", "Focus on finishing next session"
            ),
            authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
        assertThat(status).isEqualTo("COMPLETED_PENDING_CONFIRMATION");
    }

    // skillars-12-2 AC1: recapWhatWeDid/recapWhatsNext are the first fields on this request a
    // coach can actually violate from the UI — ratings are capped q-rating controls, mode is
    // hardcoded, homeworkDrillIds is capped at 2 by toggleDrill.
    @Test
    void submitWrapUp_blankRecapWhatWeDid_returns400() {
        setBookingStatus("COMPLETED_PENDING_CONFIRMATION");
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/complete",
            HttpMethod.POST,
            Map.of(
                "playerAttended", true,
                "effortRating", 4,
                "focusRating", 3,
                "techniqueRating", 5,
                "mode", "LIVE",
                "recapWhatWeDid", "",
                "recapWhatsNext", "Continue weak-foot drills"
            ),
            authenticatedHeaders(coachCookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // skillars-12-2 code review (P6): Task 1.5 covered blank recapWhatWeDid only — a missing
    // (absent) key and a blank recapWhatsNext are each distinct @NotBlank failure modes on the
    // other side of the same validation and were untested.
    @Test
    void submitWrapUp_missingRecapWhatsNext_returns400() {
        setBookingStatus("COMPLETED_PENDING_CONFIRMATION");
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/complete",
            HttpMethod.POST,
            Map.of(
                "playerAttended", true,
                "effortRating", 4,
                "focusRating", 3,
                "techniqueRating", 5,
                "mode", "LIVE",
                "recapWhatWeDid", "Worked on first touch"
                // recapWhatsNext deliberately omitted
            ),
            authenticatedHeaders(coachCookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void submitWrapUp_blankRecapWhatsNext_returns400() {
        setBookingStatus("COMPLETED_PENDING_CONFIRMATION");
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/complete",
            HttpMethod.POST,
            Map.of(
                "playerAttended", true,
                "effortRating", 4,
                "focusRating", 3,
                "techniqueRating", 5,
                "mode", "LIVE",
                "recapWhatWeDid", "Worked on first touch",
                "recapWhatsNext", ""
            ),
            authenticatedHeaders(coachCookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void confirmCompletion_byOwningParent_returns204AndStatusIsCompleted() {
        setBookingStatus("COMPLETED_PENDING_CONFIRMATION");
        insertCompletionData("QUICK");
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/confirm-completion",
            HttpMethod.PUT, null, authenticatedHeaders(parentCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
        assertThat(status).isEqualTo("COMPLETED");
    }

    /**
     * UAT.5 review finding, superseded by skillars-deferred-69 AC4: this test used to prove a
     * self-registered PLAYER stays rejected at the {@code @PreAuthorize} gate on
     * {@code /confirm-completion} — deliberately correct at the time, since AC4 had not yet widened
     * that gate from {@code HAS_PARENT_ROLE} to {@code HAS_PARENT_OR_PLAYER_ROLE}. Now that a
     * self-booking player IS allowed through the gate, this player (who does not own THIS booking)
     * is instead rejected by {@code BookingCompletionService.confirmCompletion}'s own ownership
     * check, which throws {@code ResourceNotFoundException} (404), not an authorization exception —
     * confirmed by direct read of that method. A self-booking player confirming their OWN booking's
     * completion is exercised by the self-booking parity test suite; this test now proves the
     * narrower "not this player's booking" case instead.
     */
    @Test
    void confirmCompletion_selfRegisteredPlayerDoesNotOwnBooking_returns404() {
        setBookingStatus("COMPLETED_PENDING_CONFIRMATION");
        insertCompletionData("QUICK");
        long selfPlayerUserId = 9600000004L;
        String selfPlayerEmail = "self.player.completion@skillars-test.com";
        transactionTemplate.execute(s -> {
            insertUser(selfPlayerUserId, selfPlayerEmail, passwordEncoder.encode(TEST_PASSWORD), "PLAYER");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PLAYER')) ON CONFLICT DO NOTHING",
                selfPlayerUserId
            );
            return null;
        });
        String playerCookies = loginAndGetCookies(selfPlayerEmail);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/confirm-completion",
            HttpMethod.PUT, null, authenticatedHeaders(playerCookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // skillars-12-2 Task 3.4: exercises the QUICK-mode deferred publish path end-to-end
    // (submitWrapUp → session_completion_data → confirmCompletion → BookingCompletedEvent →
    // async AFTER_COMMIT TimelineEventListener), deliberately NOT the LIVE-mode path, whose
    // publish reads straight from the in-hand request and would stay green even if
    // BookingCompletionService.submitWrapUp's scd.setWhatWeDid/setWhatsNext calls were broken
    // or omitted entirely.
    @Test
    void submitWrapUp_quickModeThenConfirmCompletion_writesRecapToPlayerTimeline() {
        setBookingStatus("COMPLETED_PENDING_CONFIRMATION");
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> wrapUpResponse = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/complete",
            HttpMethod.POST,
            Map.of(
                "playerAttended", true,
                "effortRating", 4,
                "focusRating", 4,
                "techniqueRating", 3,
                "mode", "QUICK",
                "recapWhatWeDid", "Quick-complete recap: dribbling drills",
                "recapWhatsNext", "Quick-complete recap: ball control next",
                "recapWhatWentWell", "Quick-complete recap: sharp first touch"
            ),
            authenticatedHeaders(coachCookies), Void.class
        );
        assertThat(wrapUpResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String parentCookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Void> confirmResponse = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/confirm-completion",
            HttpMethod.PUT, null, authenticatedHeaders(parentCookies), Void.class
        );
        assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Awaitility.await("SESSION_COMPLETED timeline event written asynchronously after commit")
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(100))
            .until(() -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM development.player_timeline_events " +
                "WHERE reference_id = ? AND event_type = 'SESSION_COMPLETED'", Integer.class, bookingId) > 0);

        Map<String, Object> metadata = jdbcTemplate.queryForMap(
            "SELECT metadata->>'whatWeDid' AS what_we_did, metadata->>'whatsNext' AS whats_next, " +
            "metadata->>'whatWentWell' AS what_went_well " +
            "FROM development.player_timeline_events " +
            "WHERE reference_id = ? AND event_type = 'SESSION_COMPLETED'", bookingId);
        assertThat(metadata.get("what_we_did")).isEqualTo("Quick-complete recap: dribbling drills");
        assertThat(metadata.get("whats_next")).isEqualTo("Quick-complete recap: ball control next");
        // skillars-12-7 AC11: the third recap key reaches the timeline through confirmCompletion too.
        assertThat(metadata.get("what_went_well")).isEqualTo("Quick-complete recap: sharp first touch");
    }

    @Test
    void pauseSession_fromInProgress_returns204AndStatusIsPaused() {
        setBookingStatus("IN_PROGRESS");
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/pause",
            HttpMethod.POST, null, authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
        assertThat(status).isEqualTo("PAUSED");
    }

    @Test
    void resumeSession_fromPaused_returns204AndStatusIsInProgress() {
        setBookingStatus("PAUSED");
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/resume",
            HttpMethod.POST, null, authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
        assertThat(status).isEqualTo("IN_PROGRESS");
    }

    @Test
    void pauseSession_unauthenticated_returns401() {
        setBookingStatus("IN_PROGRESS");

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/pause",
            HttpMethod.POST, null, clientHeaders(), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void pauseSession_parentRole_returns403() {
        setBookingStatus("IN_PROGRESS");
        String parentCookies = loginAndGetCookies(PARENT_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/pause",
            HttpMethod.POST, null, authenticatedHeaders(parentCookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void pauseSession_alreadyPaused_returnsForbidden() {
        setBookingStatus("PAUSED");
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/pause",
            HttpMethod.POST, null, authenticatedHeaders(coachCookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void endSession_fromPaused_returns204AndStatusIsCompletedPendingConfirmation() {
        setBookingStatus("PAUSED");
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/end",
            HttpMethod.POST, null, authenticatedHeaders(coachCookies), Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId);
        assertThat(status).isEqualTo("COMPLETED_PENDING_CONFIRMATION");
    }

    @Test
    void pauseSession_wrongCoach_returnsForbidden() {
        setBookingStatus("IN_PROGRESS");
        String coach2Cookies = loginAndGetCookies(COACH_2_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/pause",
            HttpMethod.POST, null, authenticatedHeaders(coach2Cookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void resumeSession_wrongCoach_returnsForbidden() {
        setBookingStatus("PAUSED");
        String coach2Cookies = loginAndGetCookies(COACH_2_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/resume",
            HttpMethod.POST, null, authenticatedHeaders(coach2Cookies), Void.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void getDrillSuggestions_returns200AndEmptyArray() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/session/" + bookingId + "/drills/suggestions",
            HttpMethod.GET, null, authenticatedHeaders(coachCookies), List.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    // skillars-deferred-74 W4: getDrillSuggestions' limit param had no upper bound.
    @Test
    void getDrillSuggestions_limitAboveMax_returns400() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/session/" + bookingId + "/drills/suggestions?limit=11",
            HttpMethod.GET, null, authenticatedHeaders(coachCookies), List.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void getDrillSuggestions_limitBelowMin_returns400() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/session/" + bookingId + "/drills/suggestions?limit=0",
            HttpMethod.GET, null, authenticatedHeaders(coachCookies), List.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void getDrillSuggestions_limitAtMax_returns200() {
        String coachCookies = loginAndGetCookies(COACH_EMAIL);

        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/session/" + bookingId + "/drills/suggestions?limit=10",
            HttpMethod.GET, null, authenticatedHeaders(coachCookies), List.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- skillars-12-8: session content + HOMEWORK_ASSIGNED on the timeline ----

    @Test
    void quickCompleteThenConfirm_putsDrillsAndRatingsOnTimeline_andHomeworkAssignedLandsOnce() throws Exception {
        UUID warmup = insertDrill("Warm-up Rondo");
        UUID passing = insertDrill("Passing Square");
        UUID homework1 = insertDrill("Juggling");
        UUID homework2 = insertDrill("Wall Passes");
        insertPlannedSession(
            "[{\"blockType\":\"WARMUP\",\"blockName\":\"Warm\",\"durationMinutes\":10,\"drills\":[{\"drillId\":\"" + warmup + "\",\"order\":1}]},"
          + "{\"blockType\":\"MAIN\",\"blockName\":\"Main\",\"durationMinutes\":30,\"drills\":[{\"drillId\":\"" + passing + "\",\"order\":1},{\"drillId\":\"" + warmup + "\",\"order\":2}]}]",
            "DRAFT");
        completeViaQuickWrapUp(List.of(homework1, homework2));

        Awaitility.await("SESSION_COMPLETED and HOMEWORK_ASSIGNED written asynchronously")
            .atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
            .until(() -> countTimeline("SESSION_COMPLETED") == 1 && countTimeline("HOMEWORK_ASSIGNED") == 1);

        Map<String, Object> done = jdbcTemplate.queryForMap(
            "SELECT reference_id, reference_module, coach_id, metadata::text AS md FROM development.player_timeline_events "
          + "WHERE reference_id = ? AND event_type = 'SESSION_COMPLETED'", bookingId);
        assertThat(done.get("reference_module")).isEqualTo("booking");
        assertThat(done.get("coach_id")).isNotNull();
        // DRAFT plan accepted (no race with the DRAFT->COMPLETED flip), order = block then ref, de-duplicated.
        assertThat(jdbcTemplate.queryForList(
            "SELECT d->>'name' FROM development.player_timeline_events e, jsonb_array_elements(e.metadata->'drills') d "
          + "WHERE e.reference_id = ? AND e.event_type = 'SESSION_COMPLETED'", String.class, bookingId))
            .containsExactly("Warm-up Rondo", "Passing Square");
        assertThat(done.get("md").toString()).contains("\"effortRating\": 4").contains("\"techniqueRating\": 3");

        Map<String, Object> hw = jdbcTemplate.queryForMap(
            "SELECT coach_id, reference_module, metadata->>'coachId' AS md_coach FROM development.player_timeline_events "
          + "WHERE reference_id = ? AND event_type = 'HOMEWORK_ASSIGNED'", bookingId);
        assertThat(hw.get("coach_id")).as("homework must never feed the coach-access clock").isNull();
        assertThat(hw.get("reference_module")).isEqualTo("booking");
        assertThat(hw.get("md_coach")).isEqualTo(coachProfileId.toString());
        assertThat(jdbcTemplate.queryForList(
            "SELECT d->>'name' FROM development.player_timeline_events e, jsonb_array_elements(e.metadata->'drills') d "
          + "WHERE e.reference_id = ? AND e.event_type = 'HOMEWORK_ASSIGNED'", String.class, bookingId))
            .containsExactlyInAnyOrder("Juggling", "Wall Passes");

        // Visibility: parent sees drills but not ratings; the authoring coach sees both.
        Map<String, Object> parentView = sessionCompletedMetadataViaApi(loginAndGetCookies(PARENT_EMAIL));
        assertThat(parentView).containsKey("drills").doesNotContainKeys("effortRating", "focusRating", "techniqueRating");
        Map<String, Object> coachView = sessionCompletedMetadataViaApi(loginAndGetCookies(COACH_EMAIL));
        assertThat(coachView).containsKeys("drills", "effortRating", "focusRating", "techniqueRating");

        // A recap edit (12-7) must not drop the 12-8 keys.
        ResponseEntity<Void> edit = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/recap", HttpMethod.PUT,
            Map.of("whatWeDid", "Edited what we did", "whatsNext", "Edited next"),
            authenticatedHeaders(loginAndGetCookies(COACH_EMAIL)), Void.class);
        assertThat(edit.getStatusCode().is2xxSuccessful()).isTrue();
        Awaitility.await("recap edit propagated to the timeline row")
            .atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
            .until(() -> "Edited what we did".equals(jdbcTemplate.queryForObject(
                "SELECT metadata->>'whatWeDid' FROM development.player_timeline_events "
              + "WHERE reference_id = ? AND event_type = 'SESSION_COMPLETED'", String.class, bookingId)));
        Map<String, Object> afterEdit = jdbcTemplate.queryForMap(
            "SELECT jsonb_array_length(metadata->'drills') AS n, metadata->>'effortRating' AS e, "
          + "metadata->>'focusRating' AS f, metadata->>'techniqueRating' AS t FROM development.player_timeline_events "
          + "WHERE reference_id = ? AND event_type = 'SESSION_COMPLETED'", bookingId);
        assertThat(afterEdit.get("n")).isEqualTo(2);
        assertThat(afterEdit.get("e")).isEqualTo("4");
        assertThat(afterEdit.get("f")).isEqualTo("4");
        assertThat(afterEdit.get("t")).isEqualTo("3");

        // Last on purpose: replay the booking's BookingCompletedEvent (skillars-deferred-162 AC7.5). It is published
        // inside a transaction, because AFTER_COMMIT listeners are silently dropped without one. It carries ONLY a
        // third, not-yet-assigned homework drill: handleBookingCompleted truncates to 2 ids, so appending it to the
        // original two would drop it. Its new homework_assignments row proves the replay was delivered; the
        // SESSION_COMPLETED count must still be 1 (the ON CONFLICT insert), held for a window because the timeline
        // listener runs on another pool thread.
        UUID homework3 = insertDrill("Cone Weave");
        transactionTemplate.executeWithoutResult(t -> eventPublisher.publishEvent(new BookingCompletedEvent(
            this, bookingId, coachProfileId, PLAYER_ID, PARENT_ID, true, 4, 4, 3, List.of(homework3))));
        Awaitility.await("replayed BookingCompletedEvent delivered (its new homework row lands)")
            .atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
            .until(() -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM session.homework_assignments WHERE booking_id = ? AND drill_id = ?",
                Integer.class, bookingId, homework3) == 1);
        Awaitility.await("the replay writes no second SESSION_COMPLETED and no second HOMEWORK_ASSIGNED row")
            .during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
            .until(() -> countTimeline("SESSION_COMPLETED") == 1 && countTimeline("HOMEWORK_ASSIGNED") == 1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM session.homework_assignments WHERE booking_id = ?", Integer.class, bookingId)).isEqualTo(3);
    }

    // skillars-deferred-162 AC7.6: the same event delivered twice at one commit (two listener invocations racing on
    // the pool) still yields exactly one SESSION_COMPLETED row.
    @Test
    void bookingCompletedEvent_deliveredTwice_writesExactlyOneSessionCompletedRow() {
        BookingCompletedEvent event = new BookingCompletedEvent(
            this, bookingId, coachProfileId, PLAYER_ID, PARENT_ID, true, 4, 4, 3, List.of());
        transactionTemplate.executeWithoutResult(t -> {
            eventPublisher.publishEvent(event);
            eventPublisher.publishEvent(event);
        });

        Awaitility.await("SESSION_COMPLETED written")
            .atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
            .until(() -> countTimeline("SESSION_COMPLETED") >= 1);
        Awaitility.await("and only once")
            .during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
            .until(() -> countTimeline("SESSION_COMPLETED") == 1);
    }

    @Test
    void quickCompleteWithoutHomeworkOrSession_writesNoHomeworkEntryAndNoDrills() throws Exception {
        completeViaQuickWrapUp(List.of());

        Awaitility.await("SESSION_COMPLETED written")
            .atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
            .until(() -> countTimeline("SESSION_COMPLETED") == 1);
        Awaitility.await("no HOMEWORK_ASSIGNED row appears")
            .during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(100))
            .until(() -> countTimeline("HOMEWORK_ASSIGNED") == 0);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT jsonb_exists(metadata, 'drills') FROM development.player_timeline_events "
          + "WHERE reference_id = ? AND event_type = 'SESSION_COMPLETED'", Boolean.class, bookingId)).isFalse();
    }

    @Test
    void homeworkAssignedEvent_deliveredTwice_landsOnce_andALaterEventMergesIntoTheSameRow() {
        UUID drill = insertDrill("Dup Drill");
        UUID laterDrill = insertDrill("Later Drill");
        HomeworkAssignedEvent event = new HomeworkAssignedEvent(bookingId, PLAYER_ID, coachProfileId, List.of(drill));
        transactionTemplate.executeWithoutResult(t -> eventPublisher.publishEvent(event));
        Awaitility.await("first delivery written")
            .atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
            .until(() -> countTimeline("HOMEWORK_ASSIGNED") == 1);

        // Duplicate delivery: must neither add a row nor duplicate the drill entry. Held for a window rather
        // than keyed on an unrelated event, which could finish first on another pool thread.
        transactionTemplate.executeWithoutResult(t -> eventPublisher.publishEvent(event));
        Awaitility.await("duplicate delivery adds nothing")
            .during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
            .until(() -> countTimeline("HOMEWORK_ASSIGNED") == 1 && homeworkDrillNames().equals(List.of("Dup Drill")));

        // A second event for the same booking (publisher announces only newly-persisted ids, e.g. after a
        // mid-loop failure and a replay) is merged into the existing row, never dropped.
        transactionTemplate.executeWithoutResult(t -> eventPublisher.publishEvent(
            new HomeworkAssignedEvent(bookingId, PLAYER_ID, coachProfileId, List.of(laterDrill, drill))));
        Awaitility.await("later event merged into the same row")
            .atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
            .until(() -> homeworkDrillNames().equals(List.of("Dup Drill", "Later Drill")));
        assertThat(countTimeline("HOMEWORK_ASSIGNED")).isEqualTo(1);

        // fallbackExecution = true: an event published with NO transaction must also be consumed.
        UUID otherBooking = UUID.randomUUID();
        eventPublisher.publishEvent(new HomeworkAssignedEvent(otherBooking, PLAYER_ID, coachProfileId, List.of(drill)));
        Awaitility.await("non-transactional publish consumed (fallbackExecution)")
            .atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
            .until(() -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM development.player_timeline_events WHERE reference_id = ? AND event_type = 'HOMEWORK_ASSIGNED'",
                Integer.class, otherBooking) == 1);
    }

    private List<String> homeworkDrillNames() {
        return jdbcTemplate.queryForList(
            "SELECT d->>'name' FROM development.player_timeline_events e, jsonb_array_elements(e.metadata->'drills') d "
          + "WHERE e.reference_id = ? AND e.event_type = 'HOMEWORK_ASSIGNED'", String.class, bookingId);
    }

    // ---- skillars-deferred-159: timeout path keeps homework; wrap-up validates drill ownership ----

    @Test
    void quickCompleteTimeout_withoutParentConfirm_stillAssignsHomeworkAndWritesTimelineRow() {
        UUID homework = insertDrill("Timeout Juggling");
        setBookingStatus("COMPLETED_PENDING_CONFIRMATION");
        assertThat(postQuickWrapUp(List.of(homework), COACH_EMAIL).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // created_at is updatable=false on the entity, so backdate it past the 24h seeded timeout via SQL.
        transactionTemplate.executeWithoutResult(t -> jdbcTemplate.update(
            "UPDATE booking.session_completion_data SET created_at = ? WHERE booking_id = ?",
            Timestamp.from(Instant.now().minus(Duration.ofHours(30))), bookingId));

        quickCompleteTimeoutService.processExpiredQuickCompletes();

        Awaitility.await("homework assigned after the timeout auto-confirm")
            .atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
            .until(() -> countTimeline("HOMEWORK_ASSIGNED") == 1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM session.homework_assignments WHERE booking_id = ? AND drill_id = ?",
            Integer.class, bookingId, homework)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId)).isEqualTo("COMPLETED");
    }

    // skillars-deferred-162 AC5.5: the delayed QUICK path still completes the booking for an erased player, but
    // writes no homework and no timeline rows. Positive control: SessionPlanService's listener on the SAME
    // BookingCompletedEvent flips the planned session to COMPLETED, proving the event was delivered, so the
    // "nothing appears" window below cannot pass vacuously.
    @Test
    void quickCompleteTimeout_erasedPlayer_completesBookingButWritesNoDevelopmentData() {
        UUID homework = insertDrill("Erased Juggling");
        insertPlannedSession("[]", "DRAFT");
        setBookingStatus("COMPLETED_PENDING_CONFIRMATION");
        assertThat(postQuickWrapUp(List.of(homework), COACH_EMAIL).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        transactionTemplate.executeWithoutResult(t -> {
            jdbcTemplate.update(
                "UPDATE booking.session_completion_data SET created_at = ? WHERE booking_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(30))), bookingId);
            jdbcTemplate.update(
                "UPDATE main.player_profiles SET development_data_erased_at = NOW() WHERE id = ?", PLAYER_ID);
        });

        quickCompleteTimeoutService.processExpiredQuickCompletes();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId)).isEqualTo("COMPLETED");
        Awaitility.await("positive control: BookingCompletedEvent delivered (session plan flipped to COMPLETED)")
            .atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
            .until(() -> "COMPLETED".equals(jdbcTemplate.queryForObject(
                "SELECT status FROM session.sessions WHERE booking_id = ?", String.class, bookingId)));
        Awaitility.await("no homework and no timeline rows for the erased player")
            .during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
            .until(() -> jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM session.homework_assignments WHERE booking_id = ?", Integer.class, bookingId) == 0
                && jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM development.player_timeline_events WHERE player_id = ?", Integer.class, PLAYER_ID) == 0);
    }

    @Test
    void wrapUp_withAnotherCoachsPrivateDrill_isRejectedAndNothingIsSaved() {
        UUID foreignPrivate = insertDrill("Foreign Secret", "PRIVATE", coachProfile2Id);
        setBookingStatus("COMPLETED_PENDING_CONFIRMATION");

        assertThatThrownBy(() -> postQuickWrapUp(List.of(foreignPrivate), COACH_EMAIL))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM booking.session_completion_data WHERE booking_id = ?", Integer.class, bookingId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM booking.bookings WHERE id = ?", String.class, bookingId))
            .isEqualTo("COMPLETED_PENDING_CONFIRMATION");
    }

    @Test
    void wrapUp_withOwnPrivateDrill_isAccepted() {
        UUID own = insertDrill("Own Secret", "PRIVATE", coachProfileId);
        setBookingStatus("COMPLETED_PENDING_CONFIRMATION");

        assertThat(postQuickWrapUp(List.of(own), COACH_EMAIL).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private ResponseEntity<Void> postQuickWrapUp(List<UUID> homeworkDrillIds, String coachEmail) {
        return httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/complete", HttpMethod.POST,
            Map.of("playerAttended", true, "effortRating", 4, "focusRating", 4, "techniqueRating", 3,
                "mode", "QUICK", "homeworkDrillIds", homeworkDrillIds,
                "recapWhatWeDid", "Recap did", "recapWhatsNext", "Recap next"),
            authenticatedHeaders(loginAndGetCookies(coachEmail)), Void.class);
    }

    // ---- helpers ----

    private UUID insertDrill(String name) {
        return insertDrill(name, "PLATFORM", null);
    }

    private UUID insertDrill(String name, String libraryType, UUID ownerCoachId) {
        UUID id = UUID.randomUUID();
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO session.drills (id, name, library_type, owner_coach_id, status, metadata, version) "
              + "VALUES (?, ?, ?, ?, 'ACTIVE', '{}'::jsonb, 0)", id, name, libraryType, ownerCoachId);
            return null;
        });
        return id;
    }

    private void insertPlannedSession(String blocksJson, String status) {
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO session.sessions (id, booking_id, coach_id, player_id, blocks, equipment_list, "
              + "development_focus, status, created_at, updated_at) "
              + "VALUES (gen_random_uuid(), ?, ?, ?, ?::jsonb, '[]'::jsonb, '[]'::jsonb, ?, NOW(), NOW())",
                bookingId, coachProfileId, PLAYER_ID, blocksJson, status);
            return null;
        });
    }

    private void completeViaQuickWrapUp(List<UUID> homeworkDrillIds) {
        setBookingStatus("COMPLETED_PENDING_CONFIRMATION");
        ResponseEntity<Void> wrapUp = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/complete", HttpMethod.POST,
            Map.of("playerAttended", true, "effortRating", 4, "focusRating", 4, "techniqueRating", 3,
                "mode", "QUICK", "homeworkDrillIds", homeworkDrillIds,
                "recapWhatWeDid", "Recap did", "recapWhatsNext", "Recap next"),
            authenticatedHeaders(loginAndGetCookies(COACH_EMAIL)), Void.class);
        assertThat(wrapUp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<Void> confirm = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/" + bookingId + "/confirm-completion", HttpMethod.PUT, null,
            authenticatedHeaders(loginAndGetCookies(PARENT_EMAIL)), Void.class);
        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private int countTimeline(String eventType) {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM development.player_timeline_events WHERE reference_id = ? AND event_type = ?",
            Integer.class, bookingId, eventType);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sessionCompletedMetadataViaApi(String cookies) {
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/development/players/" + PLAYER_ID + "/timeline?page=0&size=20",
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> events = (List<Map<String, Object>>) resp.getBody().get("events");
        return events.stream()
            .filter(e -> "SESSION_COMPLETED".equals(e.get("eventType")))
            .map(e -> (Map<String, Object>) e.get("metadata"))
            .findFirst().orElseThrow();
    }

    private void insertUpcomingBooking(UUID id) {
        // skillars-deferred-151 AC6: was Instant.now().plusSeconds(86400) (24h in the future) — the
        // new SESSION_NOT_YET_STARTED time gate on startSession/initiateQuickComplete now rejects a
        // /start call against a future-dated booking. No test in this class reads
        // requestedStartTime back or depends on it being in the future (every other test here jumps
        // straight to a later status via setBookingStatus, bypassing /start entirely) — a past start
        // time is also the semantically correct shape for a "session completion" test suite, whose
        // fixtures represent sessions that have already begun.
        Instant pastStart = Instant.now().minusSeconds(3600);
        jdbcTemplate.update(
            "INSERT INTO booking.bookings " +
            "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, " +
            "status, canonical_timezone, version, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, 'UPCOMING', 'Europe/Berlin', 0, ?, ?)",
            id, PARENT_ID, PLAYER_ID, coachProfileId,
            Timestamp.from(pastStart), Timestamp.from(pastStart.plusSeconds(3600)),
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
        );
    }

    private void setBookingStatus(String status) {
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE booking.bookings SET status = ? WHERE id = ?", status, bookingId);
            return null;
        });
    }

    private void insertCompletionData(String mode) {
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO booking.session_completion_data " +
                "(id, booking_id, coach_id, player_id, player_attended, completion_mode, created_at) " +
                "VALUES (?, ?, ?, ?, true, ?, ?)",
                UUID.randomUUID(), bookingId, coachProfileId, PLAYER_ID, mode, Timestamp.from(Instant.now())
            );
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
            "69" + (id % 100000000),
            email, passwordHash, role
        );
    }
}
