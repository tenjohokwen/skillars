package com.softropic.skillars.platform.notification.api;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.booking.contract.BookingReminderEvent;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.NotificationCategory;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.EmailContentRenderer;
import com.softropic.skillars.platform.notification.service.NotificationPreferenceService;
import com.softropic.skillars.platform.notification.service.UnsubscribeTokenService;
import com.softropic.skillars.platform.security.SecurityIT;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Notification centre (UI-gap chunk C9), end to end against the real security chain and database:
 * inbox list / unread count / mark-read with recipient scoping, email preferences, the public
 * one-click unsubscribe endpoint, recording at the email trigger point (and not on rollback), the
 * send-time preference decision, and the unsubscribe footer render.
 *
 * <p>Reuses {@link AbstractIntegrationTest}'s context verbatim — no extra mocks or properties.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class NotificationCenterIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String BASE = "/api/notifications";
    private static final String CLIENT_ID = "testClientId";
    private static final String TEST_PASSWORD = "TestPass@123!";

    private static final long ALICE_ID = 9175_000_001L;
    private static final long BOB_ID = 9175_000_002L;
    private static final String ALICE_EMAIL = "alice.notif9175@skillars-test.com";
    private static final String BOB_EMAIL = "bob.notif9175@skillars-test.com";

    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private UnsubscribeTokenService unsubscribeTokenService;
    @Autowired private NotificationPreferenceService preferenceService;
    @Autowired private EmailContentRenderer contentRenderer;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            insertAuthority(9175, "ROLE_PARENT");
            insertUser(ALICE_ID, ALICE_EMAIL, passwordHash);
            grantAuthority(ALICE_ID, "ROLE_PARENT");
            insertUser(BOB_ID, BOB_EMAIL, passwordHash);
            grantAuthority(BOB_ID, "ROLE_PARENT");
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM main.user_notifications WHERE recipient_user_id IN (?, ?)", ALICE_ID, BOB_ID);
            jdbcTemplate.update("DELETE FROM main.notification_email_preferences WHERE user_id IN (?, ?)", ALICE_ID, BOB_ID);
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?)", ALICE_ID, BOB_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", ALICE_ID, BOB_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id = 9175");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    // ── inbox ──────────────────────────────────────────────────────────────────

    @Test
    void list_returnsOnlyTheCallersNotifications_newestFirst() {
        UUID older = insertNotification(ALICE_ID, Instant.now().minusSeconds(120));
        UUID newer = insertNotification(ALICE_ID, Instant.now().minusSeconds(10));
        insertNotification(BOB_ID, Instant.now());

        ResponseEntity<Map> resp = get(BASE + "?page=0&size=10", login(ALICE_EMAIL));

        List<Map<String, Object>> content = content(resp);
        assertThat(content).extracting(m -> m.get("id")).containsExactly(newer.toString(), older.toString());
        assertThat(content.get(0))
            .containsEntry("type", "BOOKING_CONFIRMED")
            .containsEntry("category", "BOOKING")
            .containsEntry("deepLink", "/parent/bookings");
        assertThat((Map<String, Object>) content.get(0).get("params")).containsEntry("coachDisplayName", "Coach C");
    }

    @Test
    void unreadCount_andMarkRead_areRecipientScopedAndIdempotent() {
        UUID mine = insertNotification(ALICE_ID, Instant.now());
        UUID bobs = insertNotification(BOB_ID, Instant.now());
        String alice = login(ALICE_EMAIL);

        assertThat(unreadCount(alice)).isEqualTo(1);

        // Alice marking Bob's notification is a silent no-op, not a 404 oracle, and Bob's row is untouched.
        assertThat(post(BASE + "/" + bobs + "/read", alice).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(readAt(bobs)).isNull();

        assertThat(post(BASE + "/" + mine + "/read", alice).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Timestamp firstRead = readAt(mine);
        assertThat(firstRead).isNotNull();
        // Idempotent: a second mark-read does not move read_at.
        post(BASE + "/" + mine + "/read", alice);
        assertThat(readAt(mine)).isEqualTo(firstRead);
        assertThat(unreadCount(alice)).isZero();

        // Unknown id → same 204 no-op.
        assertThat(post(BASE + "/" + UUID.randomUUID() + "/read", alice).getStatusCode())
            .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void markAllRead_onlyTouchesTheCaller() {
        insertNotification(ALICE_ID, Instant.now());
        insertNotification(ALICE_ID, Instant.now());
        UUID bobs = insertNotification(BOB_ID, Instant.now());
        String alice = login(ALICE_EMAIL);

        post(BASE + "/read-all", alice);

        assertThat(unreadCount(alice)).isZero();
        assertThat(readAt(bobs)).isNull();
    }

    @Test
    void inboxEndpoints_requireAuthentication() {
        assertThatThrownBy(() -> RestClient.create().get().uri(baseUrl() + BASE + "/unread-count")
                .retrieve().toBodilessEntity())
            .isInstanceOfSatisfying(RestClientResponseException.class,
                e -> assertThat(e.getStatusCode().value()).isIn(401, 403));
    }

    // ── recording at the email trigger point ────────────────────────────────────

    @Test
    void emailEventInACommittedTransaction_createsTheInboxRow() {
        transactionTemplate.execute(status -> {
            eventPublisher.publishEvent(reminderFor(ALICE_EMAIL));
            return null;
        });

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT type, category, deep_link, params::text AS params FROM main.user_notifications "
                + "WHERE recipient_user_id = ?", ALICE_ID);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
            .containsEntry("type", "BOOKING_REMINDER")
            .containsEntry("category", "REMINDER")
            .containsEntry("deep_link", "/parent/bookings");
        assertThat((String) rows.get(0).get("params")).contains("Coach Reminder").doesNotContain("Url");
    }

    @Test
    void emailEventInARolledBackTransaction_createsNoInboxRow() {
        transactionTemplate.execute(status -> {
            eventPublisher.publishEvent(reminderFor(ALICE_EMAIL));
            status.setRollbackOnly();
            return null;
        });

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.user_notifications WHERE recipient_user_id = ?", Long.class, ALICE_ID))
            .isZero();
    }

    // ── preferences ────────────────────────────────────────────────────────────

    @Test
    void preferences_defaultEnabled_toggleable_andEnforcedAtSendTime() {
        String alice = login(ALICE_EMAIL);

        ResponseEntity<List> initial = httpTestClient.makeHttpRequest(
            baseUrl() + BASE + "/preferences", HttpMethod.GET, null, authHeaders(alice), List.class);
        assertThat(initial.getBody()).containsExactly(
            Map.of("category", "REMINDER", "emailEnabled", true),
            Map.of("category", "DEVELOPMENT", "emailEnabled", true));
        NotificationPreferenceService.EmailDecision before =
            preferenceService.emailDecision(EmailTemplate.BOOKING_REMINDER, ALICE_EMAIL);
        assertThat(before.suppressed()).isFalse();
        assertThat(unsubscribeTokenService.verify(before.unsubscribeToken()))
            .contains(new UnsubscribeTokenService.Claims(ALICE_ID, NotificationCategory.REMINDER));

        httpTestClient.makeHttpRequest(baseUrl() + BASE + "/preferences/REMINDER", HttpMethod.PUT,
            Map.of("emailEnabled", false), authHeaders(alice), Map.class);

        assertThat(preferenceService.emailDecision(EmailTemplate.BOOKING_REMINDER, ALICE_EMAIL).suppressed()).isTrue();
        // Bob's preference is untouched, and transactional email is never suppressed.
        assertThat(preferenceService.emailDecision(EmailTemplate.BOOKING_REMINDER, BOB_EMAIL).suppressed()).isFalse();
        assertThat(preferenceService.emailDecision(EmailTemplate.BOOKING_CONFIRMED, ALICE_EMAIL).suppressed()).isFalse();
    }

    @Test
    void transactionalCategory_cannotBeTurnedOff() {
        String alice = login(ALICE_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(baseUrl() + BASE + "/preferences/BOOKING",
                HttpMethod.PUT, Map.of("emailEnabled", false), authHeaders(alice), Map.class))
            .isInstanceOfSatisfying(HttpClientErrorException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── one-click unsubscribe (public) ─────────────────────────────────────────

    @Test
    void unsubscribe_validToken_unauthenticatedOneClickPost_turnsTheCategoryOff() {
        String token = unsubscribeTokenService.mint(ALICE_ID, NotificationCategory.DEVELOPMENT);

        // Exactly what a mailbox provider sends for RFC 8058: no cookies, no client id header,
        // a form body of "List-Unsubscribe=One-Click", token in the query string.
        ResponseEntity<Map> resp = RestClient.create().post()
            .uri(baseUrl() + BASE + "/unsubscribe?token={t}", token)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("List-Unsubscribe=One-Click")
            .retrieve().toEntity(Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("category", "DEVELOPMENT");
        assertThat(emailEnabled(ALICE_ID, "DEVELOPMENT")).isFalse();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.notification_email_preferences WHERE user_id = ?", Long.class, BOB_ID))
            .isZero();
    }

    @Test
    void unsubscribe_tamperedOrWrongCategoryToken_isRejected_andWritesNothing() {
        String token = unsubscribeTokenService.mint(ALICE_ID, NotificationCategory.REMINDER);
        String[] parts = token.split("\\.");
        String wrongCategory = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString((ALICE_ID + ":DEVELOPMENT").getBytes()) + "." + parts[2];
        String otherUser = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString((BOB_ID + ":REMINDER").getBytes()) + "." + parts[2];
        String badSignature = parts[0] + "." + parts[1] + ".AAAA";

        for (String bad : List.of(wrongCategory, otherUser, badSignature, "not-a-token")) {
            assertThatThrownBy(() -> RestClient.create().post()
                    .uri(baseUrl() + BASE + "/unsubscribe?token={t}", bad)
                    .retrieve().toBodilessEntity())
                .as("token %s", bad)
                .isInstanceOfSatisfying(RestClientResponseException.class,
                    e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        }
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.notification_email_preferences WHERE user_id IN (?, ?)",
            Long.class, ALICE_ID, BOB_ID)).isZero();
    }

    // ── email footer ───────────────────────────────────────────────────────────

    @Test
    void optOutableTemplate_rendersTheUnsubscribeFooterOnlyWhenALinkIsSupplied() {
        Recipient recipient = new Recipient();
        recipient.setEmail(ALICE_EMAIL);
        recipient.setLangKey("en");
        Map<String, Object> values = new HashMap<>();
        values.put("coachDisplayName", "Coach");
        values.put("requestedStartTime", "2026-11-01T10:00:00Z");
        values.put("canonicalTimezone", "UTC");
        values.put("reminderType", "PRIMARY");

        String plain = contentRenderer.render(recipient, EmailTemplate.BOOKING_REMINDER, values).htmlBody();
        values.put("unsubscribeUrl", "https://app.example.com/#/unsubscribe?token=v1.a.b");
        String withLink = contentRenderer.render(recipient, EmailTemplate.BOOKING_REMINDER, values).htmlBody();

        assertThat(plain).doesNotContain("unsubscribe");
        assertThat(withLink).contains("href=\"https://app.example.com/#/unsubscribe?token=v1.a.b\"")
            .contains("Unsubscribe from these emails");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private BookingReminderEvent reminderFor(String parentEmail) {
        return BookingReminderEvent.builder()
            .source(this)
            .bookingId(UUID.randomUUID())
            .parentEmail(parentEmail)
            // BookingEmailListener.onBookingReminder builds List.of(parent, coach) — a null coach
            // email NPEs there. An address with no platform account gets the email but no inbox row.
            .coachEmail("coach.noaccount9175@skillars-test.com")
            .coachDisplayName("Coach Reminder")
            .requestedStartTime(Instant.now().plusSeconds(86_400))
            .canonicalTimezone("UTC")
            .reminderType("PRIMARY")
            .build();
    }

    private UUID insertNotification(long recipient, Instant createdAt) {
        UUID id = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("INSERT INTO main.user_notifications "
                    + "(id, recipient_user_id, category, type, params, deep_link, created_at) "
                    + "VALUES (?, ?, 'BOOKING', 'BOOKING_CONFIRMED', '{\"coachDisplayName\":\"Coach C\"}'::jsonb, "
                    + "'/parent/bookings', ?)",
                id, recipient, Timestamp.from(createdAt));
            return null;
        });
        return id;
    }

    private Timestamp readAt(UUID id) {
        return jdbcTemplate.queryForObject("SELECT read_at FROM main.user_notifications WHERE id = ?",
            Timestamp.class, id);
    }

    private Boolean emailEnabled(long userId, String category) {
        return jdbcTemplate.queryForObject("SELECT email_enabled FROM main.notification_email_preferences "
            + "WHERE user_id = ? AND category = ?", Boolean.class, userId, category);
    }

    private long unreadCount(String cookies) {
        return ((Number) get(BASE + "/unread-count", cookies).getBody().get("count")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> content(ResponseEntity<Map> resp) {
        return (List<Map<String, Object>>) resp.getBody().get("content");
    }

    private ResponseEntity<Map> get(String path, String cookies) {
        return httpTestClient.makeHttpRequest(baseUrl() + path, HttpMethod.GET, null, authHeaders(cookies), Map.class);
    }

    private ResponseEntity<Void> post(String path, String cookies) {
        return httpTestClient.makeHttpRequest(baseUrl() + path, HttpMethod.POST, null, authHeaders(cookies), Void.class);
    }

    private void insertAuthority(int id, String name) {
        jdbcTemplate.update("INSERT INTO main.authority (id, name, status, created_by, created_date) "
            + "VALUES (?, ?, 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING", id, name, Timestamp.from(Instant.now()));
    }

    private void insertUser(long id, String email, String passwordHash) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" "
                + "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, "
                + "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, "
                + "activated, locked, login, login_id_type, password_hash, otp_enabled, "
                + "skillars_role, verification_status) "
                + "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, "
                + "'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', 'Parent', 'DE', ?, "
                + "true, false, ?, 'EMAIL', ?, false, 'PARENT', 'BASIC_VERIFIED')",
            id, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, "917" + (id % 10000000), email, passwordHash);
    }

    private void grantAuthority(long userId, String roleName) {
        jdbcTemplate.update("INSERT INTO main.user_authority (user_id, authority_id) "
            + "VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING", userId, roleName);
    }

    private String login(String email) {
        ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST, Map.of("email", email, "password", TEST_PASSWORD), clientHeaders(), Map.class);
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

    private HttpHeaders authHeaders(String cookies) {
        HttpHeaders headers = clientHeaders();
        headers.add(HttpHeaders.COOKIE, cookies);
        return headers;
    }
}
