package com.softropic.skillars.platform.development.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.filestorage.service.FileStorageService;
import com.softropic.skillars.platform.security.SecurityIT;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class PlayerTimelineResourceIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private RestTemplate restTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @LocalServerPort private int randomServerPort;

    @MockitoBean VideoProviderAdapter videoProviderAdapter;
    @MockitoBean FileStorageService fileStorageService;

    private static final long COACH_USER_ID   = 9600000001L;
    private static final long ACADEMY_USER_ID = 9600000002L;
    private static final long SCOUT_USER_ID   = 9600000003L;
    private static final long PARENT_USER_ID  = 9600000010L;
    private static final long PLAYER_ID       = 9600000020L;

    private static final String COACH_EMAIL   = "coach.timeline@skillars-test.com";
    private static final String ACADEMY_EMAIL = "academy.timeline@skillars-test.com";
    private static final String SCOUT_EMAIL   = "scout.timeline@skillars-test.com";
    private static final String PARENT_EMAIL  = "parent.timeline@skillars-test.com";

    private UUID coachProfileId;
    private UUID academyProfileId;
    private UUID scoutProfileId;

    // skillars-deferred-153 AC4 code review (2026-10-17): see insertTimelineEvent's Javadoc. Base is
    // truncated to whole seconds so the seeded instants round-trip exactly through the JSON response.
    private final Instant seedBase = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    private int timelineSeedSequence;

    @BeforeEach
    void setUp() {
        when(fileStorageService.storeBytes(any(), anyString(), anyString())).thenReturn("reports/test/report.pdf");
        when(fileStorageService.signedDownloadUrl(anyString())).thenReturn("https://s3.test/signed-url");

        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            insertAuthority(9600, "ROLE_COACH");
            insertAuthority(9601, "ROLE_PARENT");

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantRole(COACH_USER_ID, "ROLE_COACH");
            coachProfileId = insertCoachProfile(COACH_USER_ID);
            insertSubscription(coachProfileId, "INSTRUCTOR");

            insertUser(ACADEMY_USER_ID, ACADEMY_EMAIL, passwordHash, "COACH");
            grantRole(ACADEMY_USER_ID, "ROLE_COACH");
            academyProfileId = insertCoachProfile(ACADEMY_USER_ID);
            insertSubscription(academyProfileId, "ACADEMY");

            insertUser(SCOUT_USER_ID, SCOUT_EMAIL, passwordHash, "COACH");
            grantRole(SCOUT_USER_ID, "ROLE_COACH");
            scoutProfileId = insertCoachProfile(SCOUT_USER_ID);
            insertSubscription(scoutProfileId, "SCOUT");

            insertUser(PARENT_USER_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantRole(PARENT_USER_ID, "ROLE_PARENT");

            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Timeline Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(10)),
                PARENT_USER_ID, Timestamp.from(Instant.now())
            );
            insertCompletedBooking(coachProfileId, PARENT_USER_ID, PLAYER_ID);
            insertCompletedBooking(academyProfileId, PARENT_USER_ID, PLAYER_ID);
            insertCompletedBooking(scoutProfileId, PARENT_USER_ID, PLAYER_ID);
            return null;
        });
    }

    private void insertCompletedBooking(UUID coachId, Long parentId, Long playerId) {
        jdbcTemplate.update(
            "INSERT INTO booking.bookings " +
            "(id, parent_id, player_id, coach_id, requested_start_time, requested_end_time, status, " +
            " canonical_timezone, version, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED', 'Europe/Berlin', 0, ?, ?)",
            UUID.randomUUID(), parentId, playerId, coachId,
            Timestamp.from(Instant.now().minusSeconds(7200)),
            Timestamp.from(Instant.now().minusSeconds(3600)),
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
        );
    }


    // ── Timeline Tests ──────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void getTimeline_asCoach_withActiveAccess_returns200WithEvents() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.player_skill_stats " +
                "(id, player_id, coach_id, skill_code, slu_value, session_id, calculated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, 'PAC', 10.00, gen_random_uuid(), NOW())",
                PLAYER_ID, coachProfileId
            );
            insertTimelineEvent(PLAYER_ID, "SESSION_COMPLETED", "booking");
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            timelineUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) response.getBody().get("accessExpired")).isFalse();
        assertThat((List<?>) response.getBody().get("events")).isNotEmpty();
    }

    // skillars-deferred-153 AC4: standard offset pagination — hasMore=true while a further page
    // exists, false once the last page is reached. Uses the PARENT role (isCoach=false) so the
    // access-expiry gate (a separate, unrelated concern) never enters into it.
    //
    // skillars-deferred-153 code review (2026-10-17): the seeded events now carry DISTINCT,
    // explicitly-ordered occurred_at values (see insertTimelineEvent), so the page CONTENTS are
    // assertable too — not just their size. Under the old transaction-constant NOW() seeding, all
    // three events shared one timestamp and these tests passed for any row order whatsoever.
    @Test
    @SuppressWarnings("unchecked")
    void getTimeline_asParent_firstPageOfThree_returnsHasMoreTrue() {
        List<String> newestFirstIds = seedTimelineEvents(3);

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            timelineUrl(0, 2), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) response.getBody().get("events")).hasSize(2);
        assertThat((Boolean) response.getBody().get("hasMore")).isTrue();
        // Newest first, and specifically the two newest — not just "two of them".
        assertThat(idsOf(response)).containsExactly(newestFirstIds.get(0), newestFirstIds.get(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTimeline_asParent_lastPageOfThree_returnsHasMoreFalse() {
        List<String> newestFirstIds = seedTimelineEvents(3);

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            timelineUrl(1, 2), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) response.getBody().get("events")).hasSize(1);
        assertThat((Boolean) response.getBody().get("hasMore")).isFalse();
        // ...and it is the OLDEST of the three, i.e. the page boundary really fell where it should.
        assertThat(idsOf(response)).containsExactly(newestFirstIds.get(2));
    }

    // skillars-deferred-153 AC4 code review (2026-10-17): NEW — walks EVERY page and asserts each
    // event is returned exactly once, with no duplicate and no gap. This is the property offset
    // pagination over a non-unique sort key actually breaks, and nothing asserted it before.
    @Test
    @SuppressWarnings("unchecked")
    void getTimeline_asParent_pagingAcrossEveryPage_returnsEachEventExactlyOnce() {
        List<String> newestFirstIds = seedTimelineEvents(5);

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        List<String> seenIds = new java.util.ArrayList<>();
        List<Integer> pageSizes = new java.util.ArrayList<>();
        List<Boolean> hasMoreFlags = new java.util.ArrayList<>();

        for (int page = 0; page < 3; page++) {
            ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
                timelineUrl(page, 2), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            seenIds.addAll(idsOf(response));
            pageSizes.add(idsOf(response).size());
            hasMoreFlags.add((Boolean) response.getBody().get("hasMore"));
        }

        assertThat(pageSizes).containsExactly(2, 2, 1);
        assertThat(hasMoreFlags).containsExactly(true, true, false);
        // No duplicate and no gap: every seeded event came back exactly once, in newest-first order.
        assertThat(seenIds).doesNotHaveDuplicates();
        assertThat(seenIds).containsExactlyElementsOf(newestFirstIds);
    }

    // skillars-deferred-153 code review (2026-10-17): NEW — the actual regression test for the
    // ...OrderByOccurredAtDescIdDesc tie-break. All four events share ONE occurred_at (precisely what
    // the old NOW()-based seeding produced for every event in a transaction, and what production
    // produces whenever several events are written for one player in one transaction). Without the
    // `id DESC` tie-break the sort key is non-unique, Postgres is free to return a different row
    // order per LIMIT/OFFSET query, and a row can appear on two consecutive pages or be skipped
    // entirely — which the frontend renders as a duplicate :key or a silently missing event.
    //
    // Deliberately NOT asserting the DIRECTION of the id ordering: Postgres orders `uuid` by
    // unsigned bytes while Java's UUID.compareTo compares the two halves as SIGNED longs, so the two
    // disagree. "Exactly once, no duplicate, no gap" is the property that matters and is portable.
    @Test
    @SuppressWarnings("unchecked")
    void getTimeline_asParent_eventsSharingOneOccurredAt_pageWithoutDuplicateOrGap() {
        Instant shared = seedBase.minusSeconds(3600);
        transactionTemplate.execute(status -> {
            for (int i = 0; i < 4; i++) {
                insertTimelineEvent(PLAYER_ID, "SESSION_COMPLETED", "booking", shared);
            }
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        List<String> seenIds = new java.util.ArrayList<>();
        for (int page = 0; page < 2; page++) {
            ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
                timelineUrl(page, 2), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((List<?>) response.getBody().get("events")).hasSize(2);
            seenIds.addAll(idsOf(response));
        }

        assertThat(seenIds).hasSize(4).doesNotHaveDuplicates();
        // Sanity: the sort key really was non-unique for this fixture.
        Integer distinctTimestamps = jdbcTemplate.queryForObject(
            "SELECT COUNT(DISTINCT occurred_at) FROM development.player_timeline_events WHERE player_id = ?",
            Integer.class, PLAYER_ID);
        assertThat(distinctTimestamps).isEqualTo(1);
    }

    // skillars-12-2 AC4: the recap ("whatWeDid"/"whatsNext") written into a SESSION_COMPLETED
    // event's metadata is visible only to its authoring coach.
    @Test
    @SuppressWarnings("unchecked")
    void getTimeline_asAuthoringCoach_includesRecapFields() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.player_skill_stats " +
                "(id, player_id, coach_id, skill_code, slu_value, session_id, calculated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, 'PAC', 10.00, gen_random_uuid(), NOW())",
                PLAYER_ID, coachProfileId
            );
            insertSessionCompletedEventWithRecap(PLAYER_ID, coachProfileId,
                "Worked on first touch", "Continue weak-foot drills");
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            timelineUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> events = (List<Map<String, Object>>) response.getBody().get("events");
        Map<String, Object> metadata = (Map<String, Object>) events.get(0).get("metadata");
        assertThat(metadata.get("whatWeDid")).isEqualTo("Worked on first touch");
        assertThat(metadata.get("whatsNext")).isEqualTo("Continue weak-foot drills");
        // skillars-12-7 AC11: the third recap field is visible to the authoring coach...
        assertThat(metadata.get("whatWentWell")).isEqualTo("Great tempo");
        // skillars-12-8 AC2: ...and so are the ratings and the drills.
        assertThat(metadata).containsEntry("effortRating", 4).containsEntry("focusRating", 3)
            .containsEntry("techniqueRating", 5).containsKey("drills");
    }

    // A second coach with otherwise-valid (non-expired) access must not see the recap — but
    // must still see the event itself. This coach needs a real player_skill_stats row for THEIR
    // OWN coach-player pair (not just a booking), or TimelineQueryService's expiry check returns
    // an empty event list before ever reaching redaction, making the assertion vacuous.
    @Test
    @SuppressWarnings("unchecked")
    void getTimeline_asSecondCoachWithActiveAccess_excludesRecapFieldsButIncludesEvent() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.player_skill_stats " +
                "(id, player_id, coach_id, skill_code, slu_value, session_id, calculated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, 'PAC', 10.00, gen_random_uuid(), NOW())",
                PLAYER_ID, academyProfileId
            );
            insertSessionCompletedEventWithRecap(PLAYER_ID, coachProfileId,
                "Worked on first touch", "Continue weak-foot drills");
            return null;
        });

        String cookies = loginAndGetCookies(ACADEMY_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            timelineUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) response.getBody().get("accessExpired")).isFalse();
        List<Map<String, Object>> events = (List<Map<String, Object>>) response.getBody().get("events");
        assertThat(events).isNotEmpty();
        Map<String, Object> event = events.get(0);
        assertThat(event.get("eventType")).isEqualTo("SESSION_COMPLETED");
        Map<String, Object> metadata = (Map<String, Object>) event.get("metadata");
        assertThat(metadata.get("coachId")).isEqualTo(coachProfileId.toString());
        assertThat(metadata).doesNotContainKeys("whatWeDid", "whatsNext", "whatWentWell");
        // skillars-12-8 AC2: ratings are authoring-coach only; drills stay visible (owner decision 2026-10-23).
        assertThat(metadata).doesNotContainKeys("effortRating", "focusRating", "techniqueRating").containsKey("drills");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTimeline_asParent_excludesRecapFields() {
        transactionTemplate.execute(status -> {
            insertSessionCompletedEventWithRecap(PLAYER_ID, coachProfileId,
                "Worked on first touch", "Continue weak-foot drills");
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            timelineUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> events = (List<Map<String, Object>>) response.getBody().get("events");
        Map<String, Object> metadata = (Map<String, Object>) events.get(0).get("metadata");
        assertThat(metadata).doesNotContainKeys("whatWeDid", "whatsNext", "whatWentWell");
        // skillars-12-8 AC2: no ratings for the parent; drills are visible.
        assertThat(metadata).doesNotContainKeys("effortRating", "focusRating", "techniqueRating").containsKey("drills");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTimeline_asCoach_withExpiredAccess_returns200WithAccessExpired() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.player_skill_stats " +
                "(id, player_id, coach_id, skill_code, slu_value, session_id, calculated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, 'PAC', 10.00, gen_random_uuid(), NOW() - INTERVAL '100 days')",
                PLAYER_ID, coachProfileId
            );
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            timelineUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) response.getBody().get("accessExpired")).isTrue();
        assertThat((List<?>) response.getBody().get("events")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTimeline_asCoach_withNoSluHistory_returns200WithAccessExpired() {
        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            timelineUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) response.getBody().get("accessExpired")).isTrue();
    }

    // skillars-12-3 AC1/AC8 headline test: a Quick-Complete-only coach (zero player_skill_stats
    // rows) with one attended SESSION_COMPLETED timeline event carrying their own populated
    // coach_id must no longer read accessExpired=true, and must be able to read their own recap
    // back.
    @Test
    @SuppressWarnings("unchecked")
    void getTimeline_asCoach_withOnlyAttendedTimelineEvent_noSluRows_returnsAccessNotExpiredAndRecapReadable() {
        transactionTemplate.execute(status -> {
            insertSessionCompletedEventWithRecap(PLAYER_ID, coachProfileId,
                "Worked on first touch", "Continue weak-foot drills", true);
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            timelineUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) response.getBody().get("accessExpired")).isFalse();
        List<Map<String, Object>> events = (List<Map<String, Object>>) response.getBody().get("events");
        assertThat(events).isNotEmpty();
        Map<String, Object> metadata = (Map<String, Object>) events.get(0).get("metadata");
        assertThat(metadata.get("whatWeDid")).isEqualTo("Worked on first touch");
        assertThat(metadata.get("whatsNext")).isEqualTo("Continue weak-foot drills");
    }

    // skillars-12-3 AC1's attendance gate (Task 2.3 negative case): a non-attended
    // SESSION_COMPLETED event is written with coach_id left NULL (TimelineEventListener's
    // attendance gate, Task 1.3) and must NOT advance the coach-access clock.
    @Test
    @SuppressWarnings("unchecked")
    void getTimeline_asCoach_withOnlyNonAttendedTimelineEvent_noSluRows_returnsAccessExpired() {
        transactionTemplate.execute(status -> {
            insertSessionCompletedEventWithRecap(PLAYER_ID, coachProfileId,
                "Worked on first touch", "Continue weak-foot drills", false);
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            timelineUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) response.getBody().get("accessExpired")).isTrue();
    }

    // skillars-12-3 AC2: the merge is additive — a coach with only a qualifying SLU row and no
    // qualifying timeline row must see no change from today's passing behavior.
    @Test
    @SuppressWarnings("unchecked")
    void getTimeline_asCoach_withOnlySluRow_noQualifyingTimelineRow_stillReturnsAccessNotExpired() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.player_skill_stats " +
                "(id, player_id, coach_id, skill_code, slu_value, session_id, calculated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, 'PAC', 10.00, gen_random_uuid(), NOW())",
                PLAYER_ID, coachProfileId
            );
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            timelineUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) response.getBody().get("accessExpired")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTimeline_asParent_returns200WithFullTimeline() {
        transactionTemplate.execute(status -> {
            insertTimelineEvent(PLAYER_ID, "SESSION_COMPLETED", "booking");
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            timelineUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) response.getBody().get("accessExpired")).isFalse();
        assertThat((List<?>) response.getBody().get("events")).isNotEmpty();
    }

    // skillars-deferred-147: PlayerOwnershipGuard.check previously only ever checked parent
    // ownership (existsByIdAndParentId), so a self-registered adult player — parent_id NULL,
    // chk_pp_owner — was unconditionally denied this endpoint about their OWN profile. Found via
    // manual testing of the new Player Development Dashboard nav link, not inferred: the real
    // response was 403 security.unauthorized despite a real player_profiles row existing.
    @Test
    @SuppressWarnings("unchecked")
    void getTimeline_asSelfRegisteredPlayer_returns200WithFullTimeline() {
        long selfPlayerUserId = 9600000030L;
        long selfPlayerProfileId = 9600000031L;
        String selfPlayerEmail = "self.player.timeline@skillars-test.com";
        transactionTemplate.execute(status -> {
            insertUser(selfPlayerUserId, selfPlayerEmail,
                passwordEncoder.encode(TEST_PASSWORD), "PLAYER");
            grantRole(selfPlayerUserId, "ROLE_PLAYER");
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, user_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Self Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                selfPlayerProfileId, Date.valueOf(LocalDate.now().minusYears(25)),
                selfPlayerUserId, Timestamp.from(Instant.now())
            );
            insertTimelineEvent(selfPlayerProfileId, "SESSION_COMPLETED", "booking");
            // skillars-12-8 AC2: a recap+ratings event so the player-viewer leak check below is not vacuous.
            insertSessionCompletedEventWithRecap(selfPlayerProfileId, coachProfileId,
                "Worked on first touch", "Continue weak-foot drills");
            return null;
        });
        try {
            String cookies = loginAndGetCookies(selfPlayerEmail);
            String selfPlayerUrl = baseUrl() + "/api/development/players/" + selfPlayerProfileId + "/timeline";
            ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
                selfPlayerUrl, HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((Boolean) response.getBody().get("accessExpired")).isFalse();
            assertThat((List<?>) response.getBody().get("events")).isNotEmpty();
            // skillars-12-8 AC2: the player sees drills but neither the recap nor the ratings.
            Map<String, Object> recapEvent = eventsOf(response).stream()
                .filter(e -> e.get("metadata") != null).findFirst().orElseThrow();
            assertThat((Map<String, Object>) recapEvent.get("metadata"))
                .doesNotContainKeys("whatWeDid", "whatsNext", "whatWentWell",
                    "effortRating", "focusRating", "techniqueRating")
                .containsKey("drills");
            // Mutation: drop PlayerOwnershipGuard's `|| existsByIdAndUserId(...)` disjunct → this
            // reproduces the exact 403 security.unauthorized the manual test hit.
        } finally {
            transactionTemplate.execute(status -> {
                jdbcTemplate.update("DELETE FROM development.player_timeline_events WHERE player_id = ?", selfPlayerProfileId);
                jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", selfPlayerProfileId);
                jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id = ?", selfPlayerUserId);
                jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", selfPlayerUserId);
                return null;
            });
        }
    }

    @Test
    void getTimeline_asParentForUnlinkedPlayer_returns403() {
        // Insert a different parent's player
        long otherParentId = 9600000099L;
        long otherPlayerId = 9600000098L;
        transactionTemplate.execute(status -> {
            insertUser(otherParentId, "other.parent.timeline@skillars-test.com",
                passwordEncoder.encode(TEST_PASSWORD), "PARENT");
            grantRole(otherParentId, "ROLE_PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Other Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                otherPlayerId, Date.valueOf(LocalDate.now().minusYears(10)),
                otherParentId, Timestamp.from(Instant.now())
            );
            return null;
        });
        try {
            String cookies = loginAndGetCookies(PARENT_EMAIL);
            String otherPlayerUrl = baseUrl() + "/api/development/players/" + otherPlayerId + "/timeline";

            assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
                otherPlayerUrl, HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
        } finally {
            transactionTemplate.execute(status -> {
                jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", otherPlayerId);
                jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id = ?", otherParentId);
                jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", otherParentId);
                return null;
            });
        }
    }

    @Test
    void getTimeline_unauthenticated_returns401() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            timelineUrl(), HttpMethod.GET, null, clientHeaders(), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // ── Report Tests ──────────────────────────────────────────────────────────

    @Test
    void generateReport_asInstructorCoach_returns204() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.player_skill_stats " +
                "(id, player_id, coach_id, skill_code, slu_value, session_id, calculated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, 'PAC', 10.00, gen_random_uuid(), NOW())",
                PLAYER_ID, coachProfileId
            );
            return null;
        });

        String cookies = loginAndGetCookies(COACH_EMAIL);
        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            reportsUrl(), HttpMethod.POST,
            Map.of("nextSteps", "Keep working on your first touch and ball control."),
            authenticatedHeaders(cookies), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        int count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.performance_reports WHERE player_id = ?",
            Integer.class, PLAYER_ID);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void generateReport_asScoutCoach_returns403WithFeatureGatedCode() {
        String cookies = loginAndGetCookies(SCOUT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            reportsUrl(), HttpMethod.POST,
            Map.of("nextSteps", "Keep it up!"),
            authenticatedHeaders(cookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(ex -> {
                HttpClientErrorException httpEx = (HttpClientErrorException) ex;
                assertThat(httpEx.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(httpEx.getResponseBodyAsString()).contains("security.featureGated");
            });
    }

    @Test
    @SuppressWarnings("unchecked")
    void listReports_asParent_returns200WithReports() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO development.performance_reports " +
                "(id, coach_id, player_id, generated_at, storage_key, next_steps, version) " +
                "VALUES (gen_random_uuid(), ?, ?, NOW(), 'reports/test/report.pdf', 'Keep it up', 1)",
                coachProfileId, PLAYER_ID
            );
            return null;
        });

        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            reportsUrl(), HttpMethod.GET, null, authenticatedHeaders(cookies), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void listReports_asParentForUnlinkedPlayer_returns403() {
        long otherParentId = 9600000097L;
        long otherPlayerId = 9600000096L;
        transactionTemplate.execute(status -> {
            insertUser(otherParentId, "other.parent2.timeline@skillars-test.com",
                passwordEncoder.encode(TEST_PASSWORD), "PARENT");
            grantRole(otherParentId, "ROLE_PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Other Player 2', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                otherPlayerId, Date.valueOf(LocalDate.now().minusYears(10)),
                otherParentId, Timestamp.from(Instant.now())
            );
            return null;
        });
        try {
            String cookies = loginAndGetCookies(PARENT_EMAIL);
            String otherPlayerReportsUrl = baseUrl() + "/api/development/players/" + otherPlayerId + "/reports";

            assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
                otherPlayerReportsUrl, HttpMethod.GET, null, authenticatedHeaders(cookies), List.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
        } finally {
            transactionTemplate.execute(status -> {
                jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", otherPlayerId);
                jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id = ?", otherParentId);
                jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", otherParentId);
                return null;
            });
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String timelineUrl() {
        return baseUrl() + "/api/development/players/" + PLAYER_ID + "/timeline";
    }

    private String timelineUrl(int page, int size) {
        return timelineUrl() + "?page=" + page + "&size=" + size;
    }

    private String reportsUrl() {
        return baseUrl() + "/api/development/players/" + PLAYER_ID + "/reports";
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

    /**
     * skillars-deferred-153 AC4 code review (2026-10-17): seeds DISTINCT, explicitly-ordered
     * {@code occurred_at} values.
     *
     * <p>This helper used {@code NOW()}, which is <strong>transaction-constant</strong> in Postgres:
     * every event seeded inside one {@code transactionTemplate.execute} block landed on the
     * identical timestamp. The paging tests below therefore passed regardless of row order, and the
     * new {@code ...OrderByOccurredAtDescIdDesc} tie-break had nothing to assert against. Each call
     * now lands one minute older than the previous one, so <em>seeding order is newest-first order</em>
     * — exactly the order the endpoint returns — and page boundaries become genuinely assertable.
     *
     * <p>Computed in Java rather than in SQL on purpose: an in-statement {@code NOW() - (? * INTERVAL
     * '1 minute')} would leave the parameter's type for Postgres to infer, and the sequence has to
     * keep advancing across separate transactions within one test anyway.
     */
    private void insertTimelineEvent(long playerId, String eventType, String referenceModule) {
        insertTimelineEvent(playerId, eventType, referenceModule, nextSeedOccurredAt());
    }

    /** Explicit-timestamp overload — lets a test seed several events on the IDENTICAL instant. */
    private void insertTimelineEvent(long playerId, String eventType, String referenceModule, Instant occurredAt) {
        jdbcTemplate.update(
            "INSERT INTO development.player_timeline_events " +
            "(id, player_id, event_type, reference_module, occurred_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?)",
            playerId, eventType, referenceModule, Timestamp.from(occurredAt)
        );
    }

    /** One minute older on every call. JUnit builds a fresh test instance per method, so the
     *  sequence restarts at 0 for each test without any explicit reset. */
    private Instant nextSeedOccurredAt() {
        return seedBase.minusSeconds(60L * timelineSeedSequence++);
    }

    /**
     * Seeds {@code count} SESSION_COMPLETED events one minute apart and returns their ids in
     * newest-first order — i.e. the exact order the endpoint is expected to return them in.
     *
     * <p>Asserting on ids read back from the DB rather than on the serialized {@code occurredAt}
     * strings keeps the assertion independent of Jackson's Instant rendering and of any
     * timestamptz/JVM-timezone round-tripping, while still pinning order, page boundaries and
     * one-row-per-event exactly.
     */
    private List<String> seedTimelineEvents(int count) {
        transactionTemplate.execute(status -> {
            for (int i = 0; i < count; i++) {
                insertTimelineEvent(PLAYER_ID, "SESSION_COMPLETED", "booking");
            }
            return null;
        });
        return jdbcTemplate.queryForList(
            "SELECT id::text FROM development.player_timeline_events " +
            "WHERE player_id = ? ORDER BY occurred_at DESC",
            String.class, PLAYER_ID);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> eventsOf(ResponseEntity<Map> response) {
        return (List<Map<String, Object>>) response.getBody().get("events");
    }

    private List<String> idsOf(ResponseEntity<Map> response) {
        return eventsOf(response).stream().map(e -> (String) e.get("id")).toList();
    }


    // skillars-12-2 Task 3.3: insertTimelineEvent above never sets metadata, so a dedicated
    // seeding helper is needed to cover AC4's coach-only recap redaction. Defaults to attended=true
    // (today's passing behavior: the clock-advancing coach_id column is populated).
    private void insertSessionCompletedEventWithRecap(long playerId, UUID coachId, String whatWeDid, String whatsNext) {
        insertSessionCompletedEventWithRecap(playerId, coachId, whatWeDid, whatsNext, true);
    }

    // skillars-12-3 Task 2.3: widened to set coach_id, mirroring TimelineEventListener's own
    // attendance gate (Task 1.3) — attended=false leaves coach_id NULL, exactly as production code
    // would for a non-attended wrap-up, while the metadata.coachId string (used only by
    // TimelineQueryService's redaction rule) is still always set to the authoring coach.
    private void insertSessionCompletedEventWithRecap(
            long playerId, UUID coachId, String whatWeDid, String whatsNext, boolean attended) {
        String metadataJson = String.format(
            // skillars-12-8: also carries the drills snapshot (every viewer) and the three ratings (authoring
            // coach only) so the redaction tests below cover the full coach-private denylist.
            "{\"coachId\":\"%s\",\"whatWeDid\":\"%s\",\"whatsNext\":\"%s\",\"whatWentWell\":\"Great tempo\","
                + "\"drills\":[{\"drillId\":\"00000000-0000-0000-0000-000000000128\",\"name\":\"Rondo\"}],"
                + "\"effortRating\":4,\"focusRating\":3,\"techniqueRating\":5}",
            coachId, whatWeDid, whatsNext);
        // skillars-deferred-153 AC4 code review (2026-10-17): shares insertTimelineEvent's
        // distinct-timestamp sequence instead of the transaction-constant NOW(), so two recap events
        // seeded in one transaction can never collide on occurred_at either.
        jdbcTemplate.update(
            "INSERT INTO development.player_timeline_events " +
            "(id, player_id, event_type, reference_module, occurred_at, metadata, coach_id) " +
            "VALUES (gen_random_uuid(), ?, 'SESSION_COMPLETED', 'booking', ?, ?::jsonb, ?)",
            playerId, Timestamp.from(nextSeedOccurredAt()), metadataJson, attended ? coachId : null
        );
    }
}
