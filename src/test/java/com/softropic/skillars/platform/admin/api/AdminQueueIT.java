package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.messaging.service.MessageModerationSweeper;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class AdminQueueIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String QUEUE_URL      = "/api/admin/queue";
    private static final String SUMMARY_URL    = "/api/admin/queue/summary";
    private static final String CLIENT_ID      = "testClientId";
    private static final String TEST_PASSWORD  = "TestPass@123!";

    private static final long ADMIN_ID       = 9000_000_100L;
    private static final long PARENT_ID      = 9000_000_001L;
    private static final long PLAYER_ID      = 9000_000_002L;
    private static final long COACH_USER_ID  = 9000_000_010L;

    private static final String ADMIN_EMAIL  = "admin.queue9000@skillars-test.com";
    private static final String PARENT_EMAIL = "parent.queue9000@skillars-test.com";
    private static final String COACH_EMAIL  = "coach.queue9000@skillars-test.com";


    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MessageModerationSweeper sweeper;

    @LocalServerPort private int randomServerPort;

    private static final long CONVERSATION_ID  = 9000_001_001L;
    private static final long MESSAGE_ID       = 9000_001_002L;

    private static final long MODERATION_HELD_MESSAGE_ID = 9000_001_003L;

    private UUID coachProfileId;
    private UUID messageAlertId;
    private UUID reviewAlertId;
    private UUID moderationHeldAlertId;
    private UUID reviewId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        coachProfileId = UUID.randomUUID();
        reviewId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9000, 'ROLE_PARENT', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9001, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES (9002, 'ROLE_ADMIN', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));

            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            grantAuthority(PARENT_ID, "ROLE_PARENT");

            insertUser(COACH_USER_ID, COACH_EMAIL, passwordHash, "COACH");
            grantAuthority(COACH_USER_ID, "ROLE_COACH");

            insertUser(ADMIN_ID, ADMIN_EMAIL, passwordHash, "ADMIN");
            grantAuthority(ADMIN_ID, "ROLE_ADMIN");

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, languages, canonical_timezone, status) VALUES (?, ?, 'Queue Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            jdbcTemplate.update(
                "INSERT INTO messaging.conversations (id, coach_id, player_id, parent_id, status, created_at, last_message_at) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                CONVERSATION_ID, coachProfileId, PLAYER_ID, PARENT_ID, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));

            jdbcTemplate.update(
                "INSERT INTO messaging.messages (id, conversation_id, sender_id, sender_role, content, moderation_status, created_at) VALUES (?, ?, ?, 'COACH', 'Test message content for admin queue', 'UNDER_REVIEW', ?)",
                MESSAGE_ID, CONVERSATION_ID, COACH_USER_ID, Timestamp.from(Instant.now()));

            messageAlertId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO admin.admin_alerts (alert_id, type, reference_id, reference_type, status, created_at) VALUES (?, 'MESSAGE_REPORT', ?, 'MESSAGE', 'OPEN', ?)",
                messageAlertId, String.valueOf(MESSAGE_ID), Timestamp.from(Instant.now()));

            reviewAlertId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO admin.admin_alerts (alert_id, type, reference_id, reference_type, status, created_at) VALUES (?, 'REVIEW_FLAG', ?, 'REVIEW', 'OPEN', ?)",
                reviewAlertId, reviewId.toString(), Timestamp.from(Instant.now()));

            // AC3: a message swept/held for moderation review raises its own alert type
            jdbcTemplate.update(
                "INSERT INTO messaging.messages (id, conversation_id, sender_id, sender_role, content, moderation_status, created_at) VALUES (?, ?, ?, 'COACH', 'Held for moderation review', 'UNDER_REVIEW', ?)",
                MODERATION_HELD_MESSAGE_ID, CONVERSATION_ID, COACH_USER_ID, Timestamp.from(Instant.now()));
            moderationHeldAlertId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO admin.admin_alerts (alert_id, type, reference_id, reference_type, status, created_at) VALUES (?, 'MODERATION_UNRESOLVED', ?, 'MESSAGE', 'OPEN', ?)",
                moderationHeldAlertId, String.valueOf(MODERATION_HELD_MESSAGE_ID), Timestamp.from(Instant.now()));

            return null;
        });
    }


    @Test
    void adminCanViewQueue_returnsAlerts() {
        String cookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + QUEUE_URL,
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        assertThat(content).isNotEmpty();
        Map<String, Object> entry = content.stream()
            .filter(e -> messageAlertId.toString().equals(e.get("alertId")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Message alert not found"));
        assertThat(entry.get("type")).isEqualTo("MESSAGE_REPORT");
        assertThat(entry.get("referenceId")).isEqualTo(String.valueOf(MESSAGE_ID));
        assertThat(entry.get("summary")).isNotNull();
    }

    @Test
    void nonAdminCannotViewQueue_returns403() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + QUEUE_URL, HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void filterByType_returnsOnlyMatchingAlerts() {
        String cookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + QUEUE_URL + "?type=MESSAGE_REPORT",
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        assertThat(content).isNotEmpty();
        content.forEach(e -> assertThat(e.get("type")).isEqualTo("MESSAGE_REPORT"));
    }

    @Test
    void queueSummary_returnsCountByType() {
        String cookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + SUMMARY_URL,
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("messageReports")).longValue()).isGreaterThanOrEqualTo(1L);
        assertThat(((Number) resp.getBody().get("moderationHolds")).longValue()).isGreaterThanOrEqualTo(1L);
        assertThat(((Number) resp.getBody().get("total")).longValue()).isGreaterThanOrEqualTo(3L);
    }

    // AC3: a held message must reach the admin queue under its own alert type, with a content preview
    @Test
    void filterByModerationUnresolved_returnsHeldMessageWithContentPreview() {
        String cookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + QUEUE_URL + "?type=MODERATION_UNRESOLVED",
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        assertThat(content).isNotEmpty();
        Map<String, Object> entry = content.stream()
            .filter(e -> moderationHeldAlertId.toString().equals(e.get("alertId")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Moderation-held alert not found"));
        assertThat(entry.get("referenceId")).isEqualTo(String.valueOf(MODERATION_HELD_MESSAGE_ID));
        assertThat(entry.get("summary")).isEqualTo("Held for moderation review");
    }

    // ── Code review 2026-08-05 ──

    @Test
    void moderationUnresolvedSummary_leadsWithTheReason() {
        // The reason distinguishes "the classifier ran and was unsure" from "nothing ever assessed
        // this content", which is the difference an admin triages on.
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "UPDATE admin.admin_alerts SET reason = 'MODERATION_ORPHAN_SWEPT' WHERE alert_id = ?",
            moderationHeldAlertId));

        String cookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + QUEUE_URL + "?type=MODERATION_UNRESOLVED",
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        Map<String, Object> entry = content.stream()
            .filter(e -> moderationHeldAlertId.toString().equals(e.get("alertId")))
            .findFirst().orElseThrow(() -> new AssertionError("Moderation-held alert not found"));
        assertThat(entry.get("summary")).isEqualTo("MODERATION_ORPHAN_SWEPT: Held for moderation review");
    }

    @Test
    void queuePageSurvivesContentThatWouldSplitASurrogatePairAtTheTruncationPoint() {
        // "a" + 60 emoji is 61 CODE POINTS but 121 UTF-16 chars. The old code tested length() > 100,
        // saw 121, and cut at char index 100 — the high surrogate of the 50th emoji — producing an
        // unpaired surrogate that is not encodable as UTF-8, which fails the whole page in Jackson
        // rather than corrupting one field. The fix counts code points, sees 61, and truncates
        // nothing. Messages accept up to 2000 code points, so this is reachable content.
        String astral = "a" + "😀".repeat(60);
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "UPDATE messaging.messages SET content = ? WHERE id = ?", astral, MODERATION_HELD_MESSAGE_ID));

        String cookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + QUEUE_URL + "?type=MODERATION_UNRESOLVED",
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        String summary = (String) content.stream()
            .filter(e -> moderationHeldAlertId.toString().equals(e.get("alertId")))
            .findFirst().orElseThrow(() -> new AssertionError("Moderation-held alert not found"))
            .get("summary");

        // Under the code-point limit, so it comes back whole and intact.
        assertThat(summary).isEqualTo(astral);
        assertThat(Character.isHighSurrogate(summary.charAt(summary.length() - 1))).isFalse();
    }

    // Deferred-16 code review D7: no test previously drove this chain past a hand-inserted alert row.
    @Test
    void sweepThenApprove_endToEndChain_alertAppearsInQueueThenResolves() {
        long sweptMessageId = 9000_001_004L; // next id in this file's own 9000_001_xxx local sequence
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO messaging.messages (id, conversation_id, sender_id, sender_role, content, "
                + "moderation_status, created_at) VALUES (?, ?, ?, 'COACH', 'Stranded content', 'PENDING', ?)",
                sweptMessageId, CONVERSATION_ID, COACH_USER_ID,
                Timestamp.from(Instant.now().minusSeconds(3600)));
            return null;
        });

        releaseSchedulerLock("MessageModerationSweeper_sweep");
        sweeper.sweep();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT moderation_status FROM messaging.messages WHERE id = ?", String.class, sweptMessageId))
            .isEqualTo("UNDER_REVIEW");

        String cookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> queueResp = httpTestClient.makeHttpRequest(
            baseUrl() + QUEUE_URL + "?type=MODERATION_UNRESOLVED",
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);
        assertThat(queueResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) queueResp.getBody().get("content");
        Map<String, Object> entry = content.stream()
            .filter(e -> String.valueOf(sweptMessageId).equals(e.get("referenceId")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Swept message's alert not found in the real queue response"));
        assertThat(entry.get("summary")).isEqualTo("MODERATION_ORPHAN_SWEPT: Stranded content");

        ResponseEntity<Void> approveResp = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/admin/messages/" + sweptMessageId + "/approve",
            HttpMethod.POST, null, authenticatedHeaders(cookies), Void.class);
        assertThat(approveResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT moderation_status FROM messaging.messages WHERE id = ?", String.class, sweptMessageId))
            .isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM admin.admin_alerts WHERE reference_id = ? AND type = 'MODERATION_UNRESOLVED'",
            String.class, String.valueOf(sweptMessageId)))
            .isEqualTo("RESOLVED");
    }

    @Test
    void queuePreviewTruncatesAstralContentOnACodePointBoundary() {
        // Now past the limit: "a" + 120 emoji is 121 code points / 241 chars. The cut lands at code
        // point 100 (char offset 199), never mid-pair — where the old char-index cut at 100 would
        // have split the 50th emoji.
        String astral = "a" + "😀".repeat(120);
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "UPDATE messaging.messages SET content = ? WHERE id = ?", astral, MODERATION_HELD_MESSAGE_ID));

        String cookies = loginAndGetCookies(ADMIN_EMAIL);
        ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
            baseUrl() + QUEUE_URL + "?type=MODERATION_UNRESOLVED",
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        String summary = (String) content.stream()
            .filter(e -> moderationHeldAlertId.toString().equals(e.get("alertId")))
            .findFirst().orElseThrow(() -> new AssertionError("Moderation-held alert not found"))
            .get("summary");

        assertThat(summary.codePointCount(0, summary.length())).isEqualTo(100);
        assertThat(Character.isHighSurrogate(summary.charAt(summary.length() - 1))).isFalse();
        assertThat(summary).isEqualTo("a" + "😀".repeat(99));
    }

    // ── skillars-deferred-91 AC6 (skillars-deferred-16 D4): rolling-deploy enum-read tolerance ──────

    /**
     * Read from the catalog, never hardcoded. skillars-deferred-91 code review: this used to restore
     * a hand-typed copy of the CHECK definition, so any drift between that literal and the migrations
     * would silently leave every later IT in this shared container running against a different schema.
     */
    private static final String READ_TYPE_CHECK_DEF =
        "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
        + "WHERE conname = 'admin_alerts_type_check' AND conrelid = 'admin.admin_alerts'::regclass";

    @Test
    void queue_withRowCarryingAnUnknownAlertType_returns200_rendersEveryMappableRow() {
        UUID futureAlertId = UUID.randomUUID();
        // AC6 also requires proof that the skip was OBSERVABLE, not silent. Added by the
        // skillars-deferred-91 code review — neither original IT asserted the WARN.
        ch.qos.logback.classic.Logger serviceLogger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(
                com.softropic.skillars.platform.admin.service.AdminQueueService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);

        withUnknownAlertTypeRow(futureAlertId, () -> {
            String cookies = loginAndGetCookies(ADMIN_EMAIL);
            ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
                baseUrl() + QUEUE_URL, HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

            // The whole page still renders (not a 500), and every alert this instance can map is there.
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
            assertThat(content).anyMatch(e -> messageAlertId.toString().equals(e.get("alertId")));
            assertThat(content).anyMatch(e -> moderationHeldAlertId.toString().equals(e.get("alertId")));
            // …but the unmappable row is skipped, not rendered.
            assertThat(content).noneMatch(e -> futureAlertId.toString().equals(e.get("alertId")));

            // The total must not count the row the page dropped, or pagination arithmetic is wrong
            // (skillars-deferred-91 code review). Compared against the raw OPEN count rather than
            // content.size(), which would only coincide on a single, non-full page.
            long total = ((Number) resp.getBody().get("totalElements")).longValue();
            long openRowsInDb = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM admin.admin_alerts WHERE status = 'OPEN'", Long.class);
            assertThat(total)
                .as("totalElements must exclude the one row this instance could not map")
                .isEqualTo(openRowsInDb - 1);
        });

        serviceLogger.detachAppender(appender);
        assertThat(appender.list)
            .as("AC6 requires the skip to be observable")
            .anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                assertThat(event.getFormattedMessage()).contains("ADMIN_QUEUE_UNKNOWN_ALERT_TYPE");
            });
    }

    @Test
    void queueSummary_withRowCarryingAnUnknownAlertType_returns200_countsOnlyMappableTypes() {
        UUID futureAlertId = UUID.randomUUID();
        withUnknownAlertTypeRow(futureAlertId, () -> {
            String cookies = loginAndGetCookies(ADMIN_EMAIL);
            ResponseEntity<Map> resp = httpTestClient.makeHttpRequest(
                baseUrl() + SUMMARY_URL, HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(((Number) resp.getBody().get("messageReports")).longValue()).isGreaterThanOrEqualTo(1L);
            assertThat(((Number) resp.getBody().get("moderationHolds")).longValue()).isGreaterThanOrEqualTo(1L);
        });
    }

    /**
     * Simulates a rolling deploy: a newer instance widened the {@code admin_alerts_type_check} CHECK
     * and wrote a row with an {@code alert_type} value this (older) instance's {@code AdminAlertType}
     * enum does not have. Restores the real CHECK + removes the row afterwards.
     */
    private void withUnknownAlertTypeRow(UUID alertId, Runnable body) {
        // Capture the REAL definition before touching anything, so the restore is byte-identical to
        // whatever the migrations produced rather than a literal that can drift away from them.
        final String originalDef = jdbcTemplate.queryForObject(READ_TYPE_CHECK_DEF, String.class);
        assertThat(originalDef)
            .as("admin_alerts_type_check must exist before this test manipulates it")
            .isNotBlank();

        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("ALTER TABLE admin.admin_alerts DROP CONSTRAINT admin_alerts_type_check");
            jdbcTemplate.update(
                "INSERT INTO admin.admin_alerts (alert_id, type, reference_id, reference_type, status, created_at) "
                + "VALUES (?, 'FUTURE_ALERT_TYPE', ?, 'MESSAGE', 'OPEN', ?)",
                alertId, String.valueOf(MESSAGE_ID), Timestamp.from(Instant.now()));
            return null;
        });
        try {
            body.run();
        } finally {
            // Restore in its own transaction so a failure inside body.run() cannot leave the shared
            // schema without its CHECK for every subsequent IT in this container.
            transactionTemplate.execute(status -> {
                jdbcTemplate.update("DELETE FROM admin.admin_alerts WHERE alert_id = ?", alertId);
                jdbcTemplate.execute("ALTER TABLE admin.admin_alerts DROP CONSTRAINT IF EXISTS admin_alerts_type_check");
                jdbcTemplate.execute(
                    "ALTER TABLE admin.admin_alerts ADD CONSTRAINT admin_alerts_type_check " + originalDef);
                return null;
            });
        }
    }

    // ── helpers ──

    private String loginAndGetCookies(String email) {
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", email, "password", TEST_PASSWORD),
            clientHeaders(), Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> setCookies = loginResponse.getHeaders().get("Set-Cookie");
        assertThat(setCookies).isNotNull();
        return setCookies.stream().map(c -> c.split(";")[0]).reduce((a, b) -> a + "; " + b).orElseThrow();
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
            "INSERT INTO main.\"user\" (id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, activated, locked, login, login_id_type, password_hash, otp_enabled, skillars_role, verification_status) VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, 'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', ?, 'DE', ?, true, false, ?, 'EMAIL', ?, false, ?, 'BASIC_VERIFIED')",
            id, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, role, "900" + (id % 10000000), email, passwordHash, role);
    }

    private void grantAuthority(long userId, String roleName) {
        jdbcTemplate.update(
            "INSERT INTO main.user_authority (user_id, authority_id) VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING",
            userId, roleName);
    }
}
