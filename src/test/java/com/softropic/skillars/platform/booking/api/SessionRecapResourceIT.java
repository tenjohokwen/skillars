package com.softropic.skillars.platform.booking.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;

import org.awaitility.Awaitility;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * skillars-12-7 AC1-AC5/AC8: recap read / edit / missing-recap endpoints end to end against real Postgres,
 * including the async propagation onto the existing SESSION_COMPLETED timeline row.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class SessionRecapResourceIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long SELF_PLAYER_USER_ID = 9720000001L;
    private static final long SELF_PLAYER_ID      = 9720000002L;
    private static final long PARENT_USER_ID      = 9720000010L;
    private static final long CHILD_PLAYER_ID     = 9720000011L;
    private static final long OTHER_PARENT_USER_ID = 9720000020L;
    private static final long COACH_USER_ID       = 9720000030L;
    private static final long OTHER_COACH_USER_ID = 9720000040L;

    private static final String SELF_PLAYER_EMAIL  = "self.player.recap@skillars-test.com";
    private static final String PARENT_EMAIL       = "parent.recap@skillars-test.com";
    private static final String OTHER_PARENT_EMAIL = "other.parent.recap@skillars-test.com";
    private static final String COACH_EMAIL        = "coach.recap@skillars-test.com";
    private static final String OTHER_COACH_EMAIL  = "other.coach.recap@skillars-test.com";

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
            jdbcTemplate.update("DELETE FROM development.player_timeline_events WHERE player_id IN (?, ?)",
                SELF_PLAYER_ID, CHILD_PLAYER_ID);
            jdbcTemplate.update("DELETE FROM booking.session_completion_data WHERE coach_id IN (?, ?)",
                coachProfileId, otherCoachProfileId);
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
    void updateRecap_recoversSweeperPlaceholder_updatesScdAndExistingTimelineEventOnly() {
        UUID bookingId = insertCompletedBooking(-2 * DAY);
        insertScd(bookingId, false, null, null, null);
        insertTimelineEvent(bookingId, "{\"coachId\":\"" + coachProfileId + "\"}");

        ResponseEntity<Map> response = putRecap(bookingId, "Passing patterns", "Finishing", "Good tempo", loginAndGetCookies(COACH_EMAIL));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("editable")).isEqualTo(true);
        Map<String, Object> scd = jdbcTemplate.queryForMap(
            "SELECT what_we_did, whats_next, what_went_well, player_attended FROM booking.session_completion_data WHERE booking_id = ?", bookingId);
        assertThat(scd.get("what_we_did")).isEqualTo("Passing patterns");
        assertThat(scd.get("what_went_well")).isEqualTo("Good tempo");
        // The placeholder's attendance default is NOT changed by a recap edit (Open Question 1).
        assertThat(scd.get("player_attended")).isEqualTo(false);

        Awaitility.await("timeline metadata updated asynchronously after commit")
            .atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
            .until(() -> "Good tempo".equals(timelineKey(bookingId, "whatWentWell")));
        assertThat(timelineKey(bookingId, "whatWeDid")).isEqualTo("Passing patterns");
        assertThat(timelineKey(bookingId, "whatsNext")).isEqualTo("Finishing");
        assertThat(timelineKey(bookingId, "coachId")).isEqualTo(coachProfileId.toString());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM development.player_timeline_events WHERE reference_id = ?", Integer.class, bookingId))
            .as("an edit must never create a second SESSION_COMPLETED event").isEqualTo(1);
    }

    @Test
    void updateRecap_blankWhatWentWell_removesTimelineKeyNotEmptyString() {
        UUID bookingId = insertCompletedBooking(-2 * DAY);
        insertScd(bookingId, true, "old did", "old next", "old well");
        insertTimelineEvent(bookingId, "{\"coachId\":\"" + coachProfileId
            + "\",\"whatWeDid\":\"old did\",\"whatsNext\":\"old next\",\"whatWentWell\":\"old well\"}");

        putRecap(bookingId, "new did", "new next", "  ", loginAndGetCookies(COACH_EMAIL));

        Awaitility.await("timeline updated").atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
            .until(() -> "new did".equals(timelineKey(bookingId, "whatWeDid")));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT jsonb_exists(metadata, 'whatWentWell') FROM development.player_timeline_events WHERE reference_id = ?",
            Boolean.class, bookingId)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT what_went_well FROM booking.session_completion_data WHERE booking_id = ?", String.class, bookingId)).isNull();
    }

    @Test
    void getRecap_insideWindow_isEditableWithDeadline() {
        UUID bookingId = insertCompletedBooking(-2 * DAY);
        insertScd(bookingId, true, "did", "next", null);

        ResponseEntity<Map> response = getRecap(bookingId, loginAndGetCookies(COACH_EMAIL));

        assertThat(response.getBody().get("editable")).isEqualTo(true);
        assertThat(response.getBody().get("editableUntil")).isNotNull();
        assertThat(response.getBody().get("whatWeDid")).isEqualTo("did");
    }

    @Test
    void recapOutsideWindow_putRejectedWithDedicatedKey_andGetReportsNotEditable() {
        UUID bookingId = insertCompletedBooking(-40 * DAY);
        insertScd(bookingId, true, "did", "next", null);
        String cookies = loginAndGetCookies(COACH_EMAIL);

        assertForbidden(() -> putRecap(bookingId, "a", "b", null, cookies), "booking.recapEditWindowClosed");
        Map body = getRecap(bookingId, cookies).getBody();
        assertThat(body.get("editable")).isEqualTo(false);
        assertThat(body.get("notEditableReason")).isEqualTo("booking.recapEditWindowClosed");
        assertThat(persistedWhatWeDid(bookingId)).isEqualTo("did");
    }

    @Test
    void updateRecap_otherCoachAndParentAreForbidden_nothingWritten() {
        UUID bookingId = insertCompletedBooking(-2 * DAY);
        insertScd(bookingId, true, "did", "next", null);

        assertForbidden(() -> putRecap(bookingId, "x", "y", null, loginAndGetCookies(OTHER_COACH_EMAIL)), null);
        assertForbidden(() -> putRecap(bookingId, "x", "y", null, loginAndGetCookies(PARENT_EMAIL)), null);
        assertThat(persistedWhatWeDid(bookingId)).isEqualTo("did");
        // AC1: only the owning coach may READ the recap either.
        assertForbidden(() -> getRecap(bookingId, loginAndGetCookies(OTHER_COACH_EMAIL)), null);
    }

    @Test
    void updateRecap_nonCompletedBooking_forbidden_andMissingScd_404() {
        UUID pending = insertBooking("COMPLETED_PENDING_CONFIRMATION", -2 * DAY);
        UUID noScd = insertCompletedBooking(-2 * DAY);
        String cookies = loginAndGetCookies(COACH_EMAIL);

        assertForbidden(() -> putRecap(pending, "x", "y", null, cookies), null);
        assertThatThrownBy(() -> putRecap(noScd, "x", "y", null, cookies))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void updateRecap_validation_blankRequiredFieldsAndOver500_return400() {
        UUID bookingId = insertCompletedBooking(-2 * DAY);
        insertScd(bookingId, true, "did", "next", null);
        String cookies = loginAndGetCookies(COACH_EMAIL);

        for (String[] bad : new String[][] {{"", "n"}, {"d", " "}, {"x".repeat(501), "n"}}) {
            assertThatThrownBy(() -> putRecap(bookingId, bad[0], bad[1], null, cookies))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    /**
     * F1 / AC4: the erased coach's profile still resolves and the JWT fast path does no DB check -- only the
     * explicit guard stops this. Neither copy of the recap (SCD row, timeline metadata) may change.
     */
    @Test
    void updateRecap_afterCoachErasure_rejectedAndNothingChanges() {
        UUID bookingId = insertCompletedBooking(-2 * DAY);
        insertScd(bookingId, true, "did", "next", "well");
        insertTimelineEvent(bookingId, "{\"coachId\":\"" + coachProfileId
            + "\",\"whatWeDid\":\"did\",\"whatsNext\":\"next\",\"whatWentWell\":\"well\"}");
        String cookies = loginAndGetCookies(COACH_EMAIL);
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE main.\"user\" SET locked = true WHERE id = ?", COACH_USER_ID);
            return null;
        });

        assertForbidden(() -> putRecap(bookingId, "x", "y", "z", cookies), "booking.recapNotEditableErased");
        assertRecapUnchanged(bookingId);
    }

    /**
     * Code review (Decision 1(a)): GdprErasureService commits the recap scrub (with the coach_profiles
     * tombstone) BEFORE the user lock, so for a moment only the tombstone is visible. Modelled here as the
     * tombstone without the lock -- the edit must still be refused, and the GET must say why.
     */
    @Test
    void updateRecap_coachRecapTombstoneWithoutUserLock_rejectedAndGetReportsErased() {
        UUID bookingId = insertCompletedBooking(-2 * DAY);
        insertScd(bookingId, true, "did", "next", "well");
        insertTimelineEvent(bookingId, "{\"coachId\":\"" + coachProfileId
            + "\",\"whatWeDid\":\"did\",\"whatsNext\":\"next\",\"whatWentWell\":\"well\"}");
        String cookies = loginAndGetCookies(COACH_EMAIL);
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE marketplace.coach_profiles SET recap_data_erased_at = now() WHERE id = ?", coachProfileId);
            return null;
        });

        assertForbidden(() -> putRecap(bookingId, "x", "y", "z", cookies), "booking.recapNotEditableErased");
        assertRecapUnchanged(bookingId);
        Map body = getRecap(bookingId, cookies).getBody();
        assertThat(body.get("editable")).isEqualTo(false);
        assertThat(body.get("notEditableReason")).isEqualTo("booking.recapNotEditableErased");
    }

    private void assertRecapUnchanged(UUID bookingId) {
        Map<String, Object> scd = jdbcTemplate.queryForMap(
            "SELECT what_we_did, whats_next, what_went_well FROM booking.session_completion_data WHERE booking_id = ?", bookingId);
        assertThat(scd).containsEntry("what_we_did", "did").containsEntry("whats_next", "next").containsEntry("what_went_well", "well");
        assertThat(timelineKey(bookingId, "whatWeDid")).isEqualTo("did");
        assertThat(timelineKey(bookingId, "whatsNext")).isEqualTo("next");
        assertThat(timelineKey(bookingId, "whatWentWell")).isEqualTo("well");
    }

    @Test
    void updateRecap_afterPlayerErasure_rejectedAndNothingResurrected() {
        UUID bookingId = insertCompletedBooking(-2 * DAY);
        insertScd(bookingId, true, null, null, null);
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE main.player_profiles SET development_data_erased_at = now() WHERE id = ?", CHILD_PLAYER_ID);
            return null;
        });

        assertForbidden(() -> putRecap(bookingId, "x", "y", null, loginAndGetCookies(COACH_EMAIL)), "booking.recapNotEditableErased");
        assertThat(persistedWhatWeDid(bookingId)).isNull();
    }

    @Test
    void missingRecaps_countsOnlyOwnInWindowNullRecapSessionsOfNonErasedPlayers() {
        UUID missing = insertCompletedBooking(-2 * DAY);
        insertScd(missing, false, null, null, null);
        UUID hasRecap = insertCompletedBooking(-3 * DAY);
        insertScd(hasRecap, true, "did", "next", null);
        UUID tooOld = insertCompletedBooking(-40 * DAY);
        insertScd(tooOld, false, null, null, null);
        UUID otherCoachs = insertBookingFor(otherCoachProfileId, "COMPLETED", -2 * DAY);
        insertScdFor(otherCoachProfileId, otherCoachs, false, null, null, null);
        // Not COMPLETED: an abandoned-then-submitted quick-complete with a null recap is resumed, not nudged.
        UUID pending = insertBooking("COMPLETED_PENDING_CONFIRMATION", -2 * DAY);
        insertScd(pending, false, null, null, null);

        ResponseEntity<Map> response = getMissing(loginAndGetCookies(COACH_EMAIL));

        assertThat(response.getBody().get("count")).isEqualTo(1);
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("bookingId")).isEqualTo(missing.toString());
        assertThat(items.get(0).get("playerName")).isEqualTo("Child Player");

        // Erasing the player removes the session from the nudge (player erasure nulls recap text too).
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE main.player_profiles SET development_data_erased_at = now() WHERE id = ?", CHILD_PLAYER_ID);
            return null;
        });
        assertThat(getMissing(loginAndGetCookies(COACH_EMAIL)).getBody().get("count")).isEqualTo(0);
    }

    @Test
    void missingRecaps_listCappedAt50_countIsTrueTotal_mostRecentFirst() {
        for (int i = 0; i < 52; i++) {
            UUID b = insertCompletedBooking(-2 * DAY - i * 60L);
            insertScd(b, false, null, null, null);
        }

        Map body = getMissing(loginAndGetCookies(COACH_EMAIL)).getBody();

        assertThat(body.get("count")).isEqualTo(52);
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        assertThat(items).hasSize(50);
        // AC8: deterministic order, most recent session first -- so the two oldest are the ones cut.
        List<Instant> ends = items.stream().map(i -> Instant.parse((String) i.get("sessionDate"))).toList();
        assertThat(ends).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void missingRecaps_nonCoach_403() {
        assertForbidden(() -> getMissing(loginAndGetCookies(PARENT_EMAIL)), null);
    }

    /** AC7/AC7a: the week schedule carries hasRecap / wrapUpSubmitted, from one batched lookup. */
    @Test
    @SuppressWarnings("unchecked")
    void coachSchedule_carriesRecapAndWrapUpFlagsPerBooking() {
        java.time.LocalDate monday = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        long tuesdayNoonOffset = monday.plusDays(1).atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).getEpochSecond()
            - Instant.now().getEpochSecond();
        UUID withRecap = insertCompletedBooking(tuesdayNoonOffset);
        insertScd(withRecap, true, "did", "next", null);
        UUID noRecap = insertCompletedBooking(tuesdayNoonOffset + 7200);
        insertScd(noRecap, false, null, null, null);
        UUID abandoned = insertBooking("COMPLETED_PENDING_CONFIRMATION", tuesdayNoonOffset + 14400);
        UUID abandonedLive = insertBooking("COMPLETED_PENDING_CONFIRMATION", tuesdayNoonOffset + 28800);
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE booking.bookings SET live_started_at = now() WHERE id = ?", abandonedLive);
            return null;
        });
        UUID submitted = insertBooking("COMPLETED_PENDING_CONFIRMATION", tuesdayNoonOffset + 21600);
        insertScd(submitted, true, "did", "next", null);

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/bookings/coaches/me/schedule?weekStart=" + monday,
            HttpMethod.GET, null, authenticatedHeaders(loginAndGetCookies(COACH_EMAIL)), Map.class);

        List<Map<String, Object>> bookings = (List<Map<String, Object>>) response.getBody().get("bookings");
        java.util.function.Function<UUID, Map<String, Object>> byId = id -> bookings.stream()
            .filter(b -> id.toString().equals(b.get("bookingId"))).findFirst().orElseThrow();
        assertThat(byId.apply(withRecap)).containsEntry("hasRecap", true).containsEntry("wrapUpSubmitted", true);
        assertThat(byId.apply(noRecap)).containsEntry("hasRecap", false).containsEntry("wrapUpSubmitted", true);
        assertThat(byId.apply(abandoned)).containsEntry("hasRecap", false).containsEntry("wrapUpSubmitted", false)
            .containsEntry("startedLive", false);
        // Code review (Decision 2): a live-started session is resumed LIVE, not QUICK.
        assertThat(byId.apply(abandonedLive)).containsEntry("wrapUpSubmitted", false).containsEntry("startedLive", true);
        assertThat(byId.apply(submitted)).containsEntry("hasRecap", true).containsEntry("wrapUpSubmitted", true);
    }

    // ---- helpers ----

    private static final long DAY = 86_400L;

    private void assertForbidden(Runnable call, String expectedErrorKey) {
        assertThatThrownBy(call::run)
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> {
                HttpClientErrorException httpEx = (HttpClientErrorException) ex;
                assertThat(httpEx.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                if (expectedErrorKey != null) {
                    assertThat(httpEx.getResponseBodyAsString()).contains("\"errorKey\":\"" + expectedErrorKey + "\"");
                }
            });
    }

    private UUID insertCompletedBooking(long endOffsetSeconds) {
        return insertBooking("COMPLETED", endOffsetSeconds);
    }

    private UUID insertBooking(String status, long endOffsetSeconds) {
        return insertBookingFor(coachProfileId, status, endOffsetSeconds);
    }

    /** End offset in seconds from a SINGLE Instant.now(); the start is one hour before the end (chk_bkg_end_after_start). */
    private UUID insertBookingFor(UUID coach, String status, long endOffsetSeconds) {
        UUID bookingId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant end = now.plusSeconds(endOffsetSeconds);
        transactionTemplate.execute(txStatus -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings " +
                "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, status, " +
                " canonical_timezone, version, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 'Europe/Berlin', 0, ?, ?)",
                bookingId, PARENT_USER_ID, CHILD_PLAYER_ID, coach,
                Timestamp.from(end.minusSeconds(3600)), Timestamp.from(end),
                status, Timestamp.from(now), Timestamp.from(now));
            return null;
        });
        return bookingId;
    }

    private void insertScd(UUID bookingId, boolean attended, String did, String next, String well) {
        insertScdFor(coachProfileId, bookingId, attended, did, next, well);
    }

    private void insertScdFor(UUID coach, UUID bookingId, boolean attended, String did, String next, String well) {
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO booking.session_completion_data " +
                "(id, booking_id, coach_id, player_id, player_attended, completion_mode, what_we_did, whats_next, what_went_well, created_at) " +
                "VALUES (?, ?, ?, ?, ?, 'QUICK', ?, ?, ?, ?)",
                UUID.randomUUID(), bookingId, coach, CHILD_PLAYER_ID, attended, did, next, well, Timestamp.from(Instant.now()));
            return null;
        });
    }

    private void insertTimelineEvent(UUID bookingId, String metadataJson) {
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO development.player_timeline_events " +
                "(id, player_id, event_type, reference_id, reference_module, occurred_at, metadata) " +
                "VALUES (gen_random_uuid(), ?, 'SESSION_COMPLETED', ?, 'booking', ?, ?::jsonb)",
                CHILD_PLAYER_ID, bookingId, Timestamp.from(Instant.now()), metadataJson);
            return null;
        });
    }

    private String timelineKey(UUID bookingId, String key) {
        return jdbcTemplate.queryForObject(
            "SELECT metadata->>? FROM development.player_timeline_events WHERE reference_id = ?",
            String.class, key, bookingId);
    }

    private String persistedWhatWeDid(UUID bookingId) {
        return jdbcTemplate.queryForObject(
            "SELECT what_we_did FROM booking.session_completion_data WHERE booking_id = ?", String.class, bookingId);
    }

    private ResponseEntity<Map> putRecap(UUID bookingId, String did, String next, String well, String cookies) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("whatWeDid", did);
        body.put("whatsNext", next);
        body.put("whatWentWell", well);
        return httpTestClient.makeHttpRequest(baseUrl() + "/api/bookings/" + bookingId + "/recap",
            HttpMethod.PUT, body, authenticatedHeaders(cookies), Map.class);
    }

    private ResponseEntity<Map> getRecap(UUID bookingId, String cookies) {
        return httpTestClient.makeHttpRequest(baseUrl() + "/api/bookings/" + bookingId + "/recap",
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);
    }

    private ResponseEntity<Map> getMissing(String cookies) {
        return httpTestClient.makeHttpRequest(baseUrl() + "/api/bookings/coaches/me/recaps-missing",
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);
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
