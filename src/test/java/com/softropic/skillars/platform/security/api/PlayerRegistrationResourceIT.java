package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;
import com.softropic.skillars.platform.security.service.RegistrationVerificationTokenService;

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

    @Autowired
    private RegistrationVerificationTokenService verificationTokenService;

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
            Map.of("verificationToken", tokenFor(userId), "otp", knownOtp), jsonHeaders(), Void.class))
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

    // ── skillars-deferred-89 AC7: /resend-otp parity with parent ─────────────────────────────────
    // Mirrors ParentRegistrationResourceIT's /resend-otp cases exactly: HTTP 200 happy path, 400
    // security.otpMismatch for a bad userId / non-EMAIL_VERIFIED user, 400 security.accountLocked for
    // a locked user, and the V121 uq_pot_one_active_per_user 409 proven spy-free at the DB
    // (skillars-deferred-88 AC10 shape) — the ApiAdvice 409 security.otpResendInProgress mapping
    // rides that same DataIntegrityViolationException handler.

    private static final String RESEND_OTP_ENDPOINT = "/api/security/player/resend-otp";

    @Test
    void resendOtp_emailVerifiedUser_returns200_replacesActiveOtpToken() {
        httpTestClient.makeHttpRequest(baseUrl() + REGISTER_ENDPOINT, HttpMethod.POST,
            registrationBody(TEST_EMAIL), jsonHeaders(), Void.class);
        Long userId = jdbcTemplate.queryForObject(
            "SELECT id FROM main.\"user\" WHERE email = ?", Long.class, TEST_EMAIL);
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE main.\"user\" SET verification_status = 'EMAIL_VERIFIED', "
                + "activated = true WHERE id = ?", userId);
            jdbcTemplate.update(
                "INSERT INTO main.phone_otp_tokens (id, version, user_id, otp_hash, expires_at, used) " +
                "VALUES (999999999999781, 0, ?, 'stale-hash', ?, false)",
                userId, Timestamp.from(Instant.now().plus(10, ChronoUnit.MINUTES)));
            return null;
        });

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + RESEND_OTP_ENDPOINT, HttpMethod.POST, Map.of("verificationToken", tokenFor(userId)), jsonHeaders(), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.phone_otp_tokens WHERE user_id = ? AND used = false", Integer.class, userId))
            .as("the stale active token is deleted and exactly one fresh one issued")
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT otp_hash FROM main.phone_otp_tokens WHERE user_id = ? AND used = false", String.class, userId))
            .isNotEqualTo("stale-hash");
    }

    @Test
    void resendOtp_unknownUserId_returns400_otpMismatch() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + RESEND_OTP_ENDPOINT, HttpMethod.POST, Map.of("verificationToken", tokenFor(987654321L)), jsonHeaders(), Void.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(ex.getResponseBodyAsString()).contains("security.otpMismatch");
            });
    }

    @Test
    void resendOtp_lockedUser_returns400_accountLocked_noTokenInserted() {
        httpTestClient.makeHttpRequest(baseUrl() + REGISTER_ENDPOINT, HttpMethod.POST,
            registrationBody(TEST_EMAIL), jsonHeaders(), Void.class);
        Long userId = jdbcTemplate.queryForObject(
            "SELECT id FROM main.\"user\" WHERE email = ?", Long.class, TEST_EMAIL);
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE main.\"user\" SET verification_status = 'EMAIL_VERIFIED', "
                + "activated = true, locked = true WHERE id = ?", userId);
            return null;
        });

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + RESEND_OTP_ENDPOINT, HttpMethod.POST, Map.of("verificationToken", tokenFor(userId)), jsonHeaders(), Void.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(ex.getResponseBodyAsString()).contains("security.accountLocked");
            });
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.phone_otp_tokens WHERE user_id = ?", Integer.class, userId))
            .as("a locked user's resend-otp must not have inserted a token")
            .isEqualTo(0);
    }

    @Test
    void resendOtp_userNotEmailVerified_returns400_otpMismatch() {
        httpTestClient.makeHttpRequest(baseUrl() + REGISTER_ENDPOINT, HttpMethod.POST,
            registrationBody(TEST_EMAIL), jsonHeaders(), Void.class);
        Long userId = jdbcTemplate.queryForObject(
            "SELECT id FROM main.\"user\" WHERE email = ?", Long.class, TEST_EMAIL);
        // user is still UNVERIFIED (verify-email not called)

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + RESEND_OTP_ENDPOINT, HttpMethod.POST, Map.of("verificationToken", tokenFor(userId)), jsonHeaders(), Void.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(ex.getResponseBodyAsString()).contains("security.otpMismatch");
            });
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.phone_otp_tokens WHERE user_id = ? AND used = false", Integer.class, userId))
            .isEqualTo(0);
    }

    @Test
    void resendOtp_perUserRateLimit_fourthCallForSameUserIsRejected() {
        // skillars-deferred-89 code review: the class-level @RateLimited buckets per client IP only
        // (and is disabled under the test profile). resendPhoneOtp additionally consumes a per-user
        // bucket (capacity 3 / 30 min, keyed on userId) so a distributed caller who knows a victim's
        // userId cannot keep deleting their in-flight OTP from many IPs. 4th call for one user → 400.
        httpTestClient.makeHttpRequest(baseUrl() + REGISTER_ENDPOINT, HttpMethod.POST,
            registrationBody(TEST_EMAIL), jsonHeaders(), Void.class);
        Long userId = jdbcTemplate.queryForObject(
            "SELECT id FROM main.\"user\" WHERE email = ?", Long.class, TEST_EMAIL);
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE main.\"user\" SET verification_status = 'EMAIL_VERIFIED', "
                + "activated = true WHERE id = ?", userId);
            return null;
        });

        for (int i = 0; i < 3; i++) {
            ResponseEntity<Void> ok = httpTestClient.makeHttpRequest(
                baseUrl() + RESEND_OTP_ENDPOINT, HttpMethod.POST, Map.of("verificationToken", tokenFor(userId)), jsonHeaders(), Void.class);
            assertThat(ok.getStatusCode()).as("call %s within the per-user cap", i + 1).isEqualTo(HttpStatus.OK);
        }

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + RESEND_OTP_ENDPOINT, HttpMethod.POST, Map.of("verificationToken", tokenFor(userId)), jsonHeaders(), Void.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void phoneOtpTokens_secondActiveRowForSameUser_rejectedByUniqueIndex() {
        // skillars-deferred-88 AC10 shape — deterministic proof that V121's uq_pot_one_active_per_user
        // enforces "one live phone OTP per user" at the DB, which is what maps to 409
        // security.otpResendInProgress on a concurrent resend race. No @MockitoSpyBean (context ceiling).
        httpTestClient.makeHttpRequest(baseUrl() + REGISTER_ENDPOINT, HttpMethod.POST,
            registrationBody(TEST_EMAIL), jsonHeaders(), Void.class);
        Long userId = jdbcTemplate.queryForObject(
            "SELECT id FROM main.\"user\" WHERE email = ?", Long.class, TEST_EMAIL);
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO main.phone_otp_tokens (id, version, user_id, otp_hash, expires_at, used) " +
                "VALUES (999999999999771, 0, ?, 'h1', ?, false)",
                userId, Timestamp.from(Instant.now().plus(10, ChronoUnit.MINUTES)));
            return null;
        });

        assertThatThrownBy(() -> transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO main.phone_otp_tokens (id, version, user_id, otp_hash, expires_at, used) " +
            "VALUES (999999999999772, 0, ?, 'h2', ?, false)",
            userId, Timestamp.from(Instant.now().plus(10, ChronoUnit.MINUTES)))))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.phone_otp_tokens WHERE user_id = ? AND used = false", Integer.class, userId))
            .isEqualTo(1);
    }

    // ── skillars-deferred-93 AC8: opaque phone-verification handle replaces the raw userId ──────

    private String tokenFor(long userId) {
        return verificationTokenService.issuePhoneVerificationToken(userId);
    }

    @Test
    void verifyPhone_malformedHandle_returns400_notServerError() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + VERIFY_PHONE_ENDPOINT, HttpMethod.POST,
            Map.of("verificationToken", "not.a.real.handle", "otp", "123456"), jsonHeaders(), Void.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(ex.getResponseBodyAsString()).contains("security.verificationLinkInvalid");
            });
    }

    @Test
    void resendOtp_tamperedHandleSignature_returns400_notServerError() {
        String valid = tokenFor(123456789L);
        String tampered = valid.substring(0, valid.length() - 1)
            + (valid.endsWith("A") ? "B" : "A");

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + RESEND_OTP_ENDPOINT, HttpMethod.POST,
            Map.of("verificationToken", tampered), jsonHeaders(), Void.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(ex.getResponseBodyAsString()).contains("security.verificationLinkInvalid");
            });
    }

    @Test
    void resendOtp_blankHandle_returns400() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + RESEND_OTP_ENDPOINT, HttpMethod.POST,
            Map.of("verificationToken", ""), jsonHeaders(), Void.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException httpException = (HttpClientErrorException) e;
                assertThat(httpException.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(httpException.getResponseBodyAsString()).contains("security.verificationLinkInvalid");
            });
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
