package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class PlayerRegistrationResourceIT extends AbstractIntegrationTest {

    private static final String REGISTER_ENDPOINT = "/api/security/player/register";
    private static final String VERIFY_EMAIL_ENDPOINT = "/api/security/player/verify-email";
    private static final String VERIFY_PHONE_ENDPOINT = "/api/security/player/verify-phone";
    private static final String CLIENT_ID = "myClientId";
    private static final String TEST_EMAIL = "player.test@skillars.com";
    private static final String TEST_PASSWORD = "Player@123!";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private HttpTestClient httpTestClient;

    @LocalServerPort
    private int randomServerPort;

    @Test
    void registerPlayer_validData_returns200AndUserIsUnverified() {
        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + REGISTER_ENDPOINT,
            HttpMethod.POST,
            registrationBody(TEST_EMAIL),
            jsonHeaders(),
            Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> user = jdbcTemplate.queryForMap(
            "SELECT skillars_role, verification_status, activated FROM main.\"user\" WHERE email = ?",
            TEST_EMAIL
        );
        assertThat(user.get("skillars_role")).isEqualTo("PLAYER");
        assertThat(user.get("verification_status")).isEqualTo("UNVERIFIED");
        assertThat(user.get("activated")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void verifyEmail_validToken_issuesOtpAndSetsEmailVerified() {
        httpTestClient.makeHttpRequest(
            baseUrl() + REGISTER_ENDPOINT,
            HttpMethod.POST,
            registrationBody(TEST_EMAIL),
            jsonHeaders(),
            Void.class
        );

        UUID token = jdbcTemplate.queryForObject(
            "SELECT evt.token FROM main.email_verification_tokens evt " +
            "JOIN main.\"user\" u ON u.id = evt.user_id WHERE u.email = ?",
            UUID.class,
            TEST_EMAIL
        );
        assertThat(token).isNotNull();

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + VERIFY_EMAIL_ENDPOINT + "?token=" + token,
            HttpMethod.GET,
            null,
            jsonHeaders(),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("nextStep", "verify-phone");

        String status = jdbcTemplate.queryForObject(
            "SELECT verification_status FROM main.\"user\" WHERE email = ?",
            String.class,
            TEST_EMAIL
        );
        assertThat(status).isEqualTo("EMAIL_VERIFIED");

        Map<String, Object> otpToken = jdbcTemplate.queryForMap(
            "SELECT pot.used, pot.otp_hash, pot.expires_at FROM main.phone_otp_tokens pot " +
            "JOIN main.\"user\" u ON u.id = pot.user_id WHERE u.email = ?",
            TEST_EMAIL
        );
        assertThat(otpToken.get("used")).isEqualTo(false);
        assertThat(otpToken.get("otp_hash")).isNotNull();
        assertThat(otpToken.get("expires_at")).isNotNull();
    }

    // ── skillars-deferred-88 AC11: locked User cannot complete verification (player endpoints) ──

    @Test
    void verifyEmail_lockedUser_rejectedAndStatusNotAdvanced() {
        httpTestClient.makeHttpRequest(baseUrl() + REGISTER_ENDPOINT, HttpMethod.POST,
            registrationBody(TEST_EMAIL), jsonHeaders(), Void.class);
        UUID token = jdbcTemplate.queryForObject(
            "SELECT evt.token FROM main.email_verification_tokens evt " +
            "JOIN main.\"user\" u ON u.id = evt.user_id WHERE u.email = ?", UUID.class, TEST_EMAIL);
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE main.\"user\" SET locked = true WHERE email = ?", TEST_EMAIL);
            return null;
        });

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + VERIFY_EMAIL_ENDPOINT + "?token=" + token, HttpMethod.GET, null, jsonHeaders(), Map.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(ex.getResponseBodyAsString()).contains("security.accountLocked");
            });
        assertThat(jdbcTemplate.queryForObject(
            "SELECT verification_status FROM main.\"user\" WHERE email = ?", String.class, TEST_EMAIL))
            .isEqualTo("UNVERIFIED");
    }

    @Test
    void verifyPhone_lockedUser_rejectedAndStatusNotAdvanced() {
        httpTestClient.makeHttpRequest(baseUrl() + REGISTER_ENDPOINT, HttpMethod.POST,
            registrationBody(TEST_EMAIL), jsonHeaders(), Void.class);
        Long userId = jdbcTemplate.queryForObject(
            "SELECT id FROM main.\"user\" WHERE email = ?", Long.class, TEST_EMAIL);
        String knownOtp = "123456";
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE main.\"user\" SET verification_status = 'EMAIL_VERIFIED', "
                + "activated = true, locked = true WHERE id = ?", userId);
            jdbcTemplate.update(
                "INSERT INTO main.phone_otp_tokens (id, version, user_id, otp_hash, expires_at, used) " +
                "VALUES (999999999999481, 0, ?, ?, ?, false)",
                userId, hashOtp(knownOtp, userId), Timestamp.from(Instant.now().plus(10, ChronoUnit.MINUTES)));
            return null;
        });

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + VERIFY_PHONE_ENDPOINT, HttpMethod.POST,
            Map.of("userId", userId, "otp", knownOtp), jsonHeaders(), Void.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(ex.getResponseBodyAsString()).contains("security.accountLocked");
            });
        assertThat(jdbcTemplate.queryForObject(
            "SELECT verification_status FROM main.\"user\" WHERE id = ?", String.class, userId))
            .isEqualTo("EMAIL_VERIFIED");
    }

    @Test
    void resendVerificationEmail_lockedUser_isSilentNoOp() {
        httpTestClient.makeHttpRequest(baseUrl() + REGISTER_ENDPOINT, HttpMethod.POST,
            registrationBody(TEST_EMAIL), jsonHeaders(), Void.class);
        UUID tokenBefore = jdbcTemplate.queryForObject(
            "SELECT evt.token FROM main.email_verification_tokens evt " +
            "JOIN main.\"user\" u ON u.id = evt.user_id WHERE u.email = ?", UUID.class, TEST_EMAIL);
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE main.\"user\" SET locked = true WHERE email = ?", TEST_EMAIL);
            return null;
        });

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/security/player/resend-verification", HttpMethod.POST,
            Map.of("email", TEST_EMAIL), jsonHeaders(), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID tokenAfter = jdbcTemplate.queryForObject(
            "SELECT evt.token FROM main.email_verification_tokens evt " +
            "JOIN main.\"user\" u ON u.id = evt.user_id WHERE u.email = ?", UUID.class, TEST_EMAIL);
        assertThat(tokenAfter).isEqualTo(tokenBefore);
    }

    private String hashOtp(String otp, Long userId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((otp + userId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        headers.add(SecurityConstants.API_KEY_HEADER, CLIENT_ID);
        return headers;
    }

    private Map<String, Object> registrationBody(String email) {
        return Map.of(
            "firstName", "Jane",
            "lastName", "Player",
            "email", email,
            "password", TEST_PASSWORD,
            "phone", "1234567890",
            "dateOfBirth", "1995-06-15"
        );
    }
}
