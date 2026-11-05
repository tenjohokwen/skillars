package com.softropic.skillars.platform.security.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.RateLimitingService;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.security.SecurityIT;
import com.softropic.skillars.utils.TestMailManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * skillars-deferred-172: the email change over HTTP. The confirm and undo endpoints are public (no session), an
 * unusable link answers 400 {@code email.change.linkInvalid}, and a request inside the undo window answers 409
 * {@code email.change.recentChange} with the window's end as the message.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class EmailChangeResourceIT extends AbstractIntegrationTest {

    private static final String CLIENT_ID = "testClientId";
    private static final String PASSWORD = "CoachPass@123!";
    private static final long USER_ID = 9172000001L;
    private static final String EMAIL = "coach.email-change@skillars-test.com";
    private static final String NEW = "coach.moved@skillars-test.com";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private HttpTestClient httpTestClient;
    @Autowired private TestMailManager testMailManager;
    @Autowired private RateLimitingService rateLimitingService;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        clearRateLimits();
        testMailManager.clear();
        String passwordHash = passwordEncoder.encode(PASSWORD);
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) "
                    + "VALUES (9200, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                "INSERT INTO main.\"user\" (id, created_by, created_date, last_modified_by, last_modified_date, "
                    + "request_id, session_id, status, dob, email, first_name, gender, lang_key, last_name, "
                    + "iso2_country, phone, activated, locked, login, login_id_type, password_hash, otp_enabled, "
                    + "skillars_role, verification_status) "
                    + "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, 'ACTIVE', '1990-03-15', ?, 'Test', "
                    + "'OTHER', 'en', 'Coach', 'DE', '6709172001', true, false, ?, 'EMAIL', ?, false, 'COACH', "
                    + "'BASIC_VERIFIED')",
                USER_ID, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), EMAIL, EMAIL, passwordHash);
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) "
                    + "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                USER_ID);
        });
    }

    @AfterEach
    void tearDown() {
        clearRateLimits();
    }

    @Test
    void requestConfirmUndo_overHttp_withPublicLinks_andTheUndoWindowRefusal() throws Exception {
        String cookies = login(EMAIL);

        ResponseEntity<Map> requested = httpTestClient.makeHttpRequest(baseUrl() + "/api/account/email",
            HttpMethod.PUT, Map.of("oldEmail", EMAIL, "newEmail", "Coach.Moved@Skillars-Test.com", "password", PASSWORD),
            withCookies(cookies), Map.class);
        assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(requested.getBody()).containsEntry("msgKey", "email.change.requested");

        ResponseEntity<Map> confirmed = httpTestClient.makeHttpRequest(baseUrl() + "/api/email-change/confirm",
            HttpMethod.POST, Map.of("token", token(EmailTemplate.EMAIL_CHANGE, "confirmUrl")), clientHeaders(), Map.class);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody()).containsEntry("code", "email.change.confirmed");

        // AC1: the new address signs in in any casing; AC7: a second change inside the window answers 409.
        String newCookies = login("COACH.moved@skillars-test.com");
        HttpClientErrorException refused = catchThrowableOfType(HttpClientErrorException.class, () ->
            httpTestClient.makeHttpRequest(baseUrl() + "/api/account/email", HttpMethod.PUT,
                Map.of("oldEmail", NEW, "newEmail", "third@skillars-test.com", "password", PASSWORD),
                withCookies(newCookies), Map.class));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode error = objectMapper.readTree(refused.getResponseBodyAsString()).path("errorMsg");
        assertThat(error.path("errorKey").asText()).isEqualTo("email.change.recentChange");
        assertThat(Instant.parse(error.path("message").asText())).isAfter(Instant.now());

        ResponseEntity<Map> undone = httpTestClient.makeHttpRequest(baseUrl() + "/api/email-change/undo",
            HttpMethod.POST, Map.of("token", token(EmailTemplate.EMAIL_CHANGED_NOTICE, "undoUrl")), clientHeaders(),
            Map.class);
        assertThat(undone.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(undone.getBody()).containsEntry("code", "email.change.undone").containsEntry("passwordResetSent", true);
        assertThat(jdbcTemplate.queryForObject("SELECT email FROM main.\"user\" WHERE id = ?", String.class, USER_ID))
            .isEqualTo(EMAIL);

        // The undo ended the hold on erasure, and the filter re-checks a token only every 5 minutes, so the session
        // that made the change must be refused by erasure's own check, or it could still make the takeover final.
        HttpClientErrorException erasure = catchThrowableOfType(HttpClientErrorException.class, () ->
            httpTestClient.makeHttpRequest(baseUrl() + "/api/gdpr/erasure", HttpMethod.POST, null,
                withCookies(newCookies), Map.class));
        assertThat(erasure).as("erasure with a session revoked by the undo").isNotNull();
        assertThat(erasure.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM admin.gdpr_requests WHERE user_id = ?",
            Integer.class, USER_ID)).isZero();
    }

    @Test
    void anUnusableLink_answers400WithOneKey_withoutASession() throws Exception {
        for (String path : List.of("/api/email-change/confirm", "/api/email-change/undo")) {
            HttpClientErrorException e = catchThrowableOfType(HttpClientErrorException.class, () ->
                httpTestClient.makeHttpRequest(baseUrl() + path, HttpMethod.POST,
                    Map.of("token", "not-a-real-token"), clientHeaders(), Map.class));
            assertThat(e.getStatusCode()).as(path).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(objectMapper.readTree(e.getResponseBodyAsString()).path("errorMsg").path("errorKey").asText())
                .as(path).isEqualTo("email.change.linkInvalid");
        }
    }

    private String token(EmailTemplate template, String urlKey) {
        Envelope envelope = testMailManager.getEnvelopes().values().stream()
            .filter(e -> e.emailTemplate() == template)
            .reduce((a, b) -> b)
            .orElseThrow(() -> new AssertionError("no " + template + " envelope"));
        String url = (String) envelope.data().get(urlKey);
        return url.substring(url.indexOf("token=") + 6);
    }

    private String login(String email) {
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(baseUrl() + "/api/auth/login", HttpMethod.POST,
            Map.of("email", email, "password", PASSWORD), clientHeaders(), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getHeaders().get("Set-Cookie").stream()
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

    private HttpHeaders withCookies(String cookies) {
        HttpHeaders headers = clientHeaders();
        headers.add(HttpHeaders.COOKIE, cookies);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private void clearRateLimits() {
        ((Map<String, ?>) ReflectionTestUtils.getField(rateLimitingService, "buckets")).clear();
    }
}
