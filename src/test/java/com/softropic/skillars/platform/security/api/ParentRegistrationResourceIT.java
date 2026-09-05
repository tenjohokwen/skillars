package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;
import com.softropic.skillars.platform.security.service.RegistrationVerificationTokenService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
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
class ParentRegistrationResourceIT extends AbstractIntegrationTest {

    private static final String REGISTER_ENDPOINT = "/api/security/parent/register";
    private static final String VERIFY_EMAIL_ENDPOINT = "/api/security/parent/verify-email";
    private static final String VERIFY_PHONE_ENDPOINT = "/api/security/parent/verify-phone";
    private static final String RESEND_ENDPOINT = "/api/security/parent/resend-verification";
    private static final String CLIENT_ID = "myClientId";
    private static final String TEST_EMAIL = "parent.test@skillars.com";
    private static final String TEST_PASSWORD = "Parent@123!";

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

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (101, 'ROLE_PARENT', 'ACTIVE', 'system', ?) " +
                "ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            return null;
        });
    }


    @Test
    void registerParent_validData_returns200AndUserIsUnverified() {
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
        assertThat(user.get("skillars_role")).isEqualTo("PARENT");
        assertThat(user.get("verification_status")).isEqualTo("UNVERIFIED");
        assertThat(user.get("activated")).isEqualTo(false);
    }

    @Test
    void registerParent_duplicateEmail_returns409() {
        httpTestClient.makeHttpRequest(
            baseUrl() + REGISTER_ENDPOINT,
            HttpMethod.POST,
            registrationBody(TEST_EMAIL),
            jsonHeaders(),
            Void.class
        );

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + REGISTER_ENDPOINT,
            HttpMethod.POST,
            registrationBody(TEST_EMAIL),
            jsonHeaders(),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ex.getResponseBodyAsString()).contains("security.emailInUse");
            });
    }

    @Test
    void registerParent_missingRequiredField_returns400() {
        Map<String, Object> body = Map.of(
            "lastName", "Parent",
            "email", TEST_EMAIL,
            "password", TEST_PASSWORD,
            "phone", "1234567890"
            // firstName omitted — triggers @NotBlank
        );

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + REGISTER_ENDPOINT,
            HttpMethod.POST,
            body,
            jsonHeaders(),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @SuppressWarnings("unchecked")
    void verifyEmail_validToken_setsEmailVerifiedAndReturnsVerificationToken() {
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
        assertThat(response.getBody()).containsKey("verificationToken");
        assertThat(verificationTokenService.resolveUserId(
            (String) response.getBody().get("verificationToken"), "PARENT"))
            .isEqualTo(jdbcTemplate.queryForObject(
                "SELECT id FROM main.\"user\" WHERE email = ?", Long.class, TEST_EMAIL));

        String status = jdbcTemplate.queryForObject(
            "SELECT verification_status FROM main.\"user\" WHERE email = ?",
            String.class,
            TEST_EMAIL
        );
        assertThat(status).isEqualTo("EMAIL_VERIFIED");
    }

    @Test
    void verifyEmail_expiredToken_returns400WithCanResend() {
        httpTestClient.makeHttpRequest(
            baseUrl() + REGISTER_ENDPOINT,
            HttpMethod.POST,
            registrationBody(TEST_EMAIL),
            jsonHeaders(),
            Void.class
        );

        Long userId = jdbcTemplate.queryForObject(
            "SELECT id FROM main.\"user\" WHERE email = ?",
            Long.class,
            TEST_EMAIL
        );

        UUID expiredToken = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO main.email_verification_tokens (id, user_id, token, expires_at, used) " +
            "VALUES (999999999999995, ?, ?, ?, false)",
            userId, expiredToken, Timestamp.from(Instant.now().minus(2, ChronoUnit.HOURS))
        );

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + VERIFY_EMAIL_ENDPOINT + "?token=" + expiredToken,
            HttpMethod.GET,
            null,
            jsonHeaders(),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                HttpClientErrorException ex = (HttpClientErrorException) e;
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(ex.getResponseBodyAsString()).contains("canResend");
                assertThat(ex.getResponseBodyAsString()).contains("true");
            });
    }

    @Test
    void verifyEmail_usedToken_returns400() {
        httpTestClient.makeHttpRequest(
            baseUrl() + REGISTER_ENDPOINT,
            HttpMethod.POST,
            registrationBody(TEST_EMAIL),
            jsonHeaders(),
            Void.class
        );

        Long userId = jdbcTemplate.queryForObject(
            "SELECT id FROM main.\"user\" WHERE email = ?",
            Long.class,
            TEST_EMAIL
        );

        UUID usedToken = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO main.email_verification_tokens (id, user_id, token, expires_at, used) " +
            "VALUES (999999999999994, ?, ?, ?, true)",
            userId, usedToken, Timestamp.from(Instant.now().plus(24, ChronoUnit.HOURS))
        );

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + VERIFY_EMAIL_ENDPOINT + "?token=" + usedToken,
            HttpMethod.GET,
            null,
            jsonHeaders(),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void verifyPhone_correctOtp_setsBasicVerified() {
        httpTestClient.makeHttpRequest(
            baseUrl() + REGISTER_ENDPOINT,
            HttpMethod.POST,
            registrationBody(TEST_EMAIL),
            jsonHeaders(),
            Void.class
        );

        Long userId = jdbcTemplate.queryForObject(
            "SELECT id FROM main.\"user\" WHERE email = ?",
            Long.class,
            TEST_EMAIL
        );

        String knownOtp = "123456";
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE main.\"user\" SET verification_status = 'EMAIL_VERIFIED', activated = true WHERE id = ?",
                userId
            );
            jdbcTemplate.update(
                "INSERT INTO main.phone_otp_tokens (id, version, user_id, otp_hash, expires_at, used) " +
                "VALUES (999999999999993, 0, ?, ?, ?, false)",
                userId, hashOtp(knownOtp, userId), Timestamp.from(Instant.now().plus(10, ChronoUnit.MINUTES))
            );
            return null;
        });

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + VERIFY_PHONE_ENDPOINT,
            HttpMethod.POST,
            Map.of("verificationToken", tokenFor(userId), "otp", knownOtp),
            jsonHeaders(),
            Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        String status = jdbcTemplate.queryForObject(
            "SELECT verification_status FROM main.\"user\" WHERE id = ?",
            String.class,
            userId
        );
        assertThat(status).isEqualTo("BASIC_VERIFIED");
    }

    @Test
    void verifyPhone_wrongOtp_returns400() {
        httpTestClient.makeHttpRequest(
            baseUrl() + REGISTER_ENDPOINT,
            HttpMethod.POST,
            registrationBody(TEST_EMAIL),
            jsonHeaders(),
            Void.class
        );

        Long userId = jdbcTemplate.queryForObject(
            "SELECT id FROM main.\"user\" WHERE email = ?",
            Long.class,
            TEST_EMAIL
        );

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE main.\"user\" SET verification_status = 'EMAIL_VERIFIED', activated = true WHERE id = ?",
                userId
            );
            jdbcTemplate.update(
                "INSERT INTO main.phone_otp_tokens (id, version, user_id, otp_hash, expires_at, used) " +
                "VALUES (999999999999992, 0, ?, ?, ?, false)",
                userId, hashOtp("999999", userId), Timestamp.from(Instant.now().plus(10, ChronoUnit.MINUTES))
            );
            return null;
        });

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + VERIFY_PHONE_ENDPOINT,
            HttpMethod.POST,
            Map.of("verificationToken", tokenFor(userId), "otp", "000000"),
            jsonHeaders(),
            Map.class
        ))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void resendVerification_alwaysReturns200_noAccountEnumeration() {
        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + RESEND_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", "nonexistent@nowhere.com"),
            jsonHeaders(),
            Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void registerParent_withLangKey_storesLangKeyOnUser() {
        java.util.HashMap<String, Object> body = new java.util.HashMap<>(registrationBody(TEST_EMAIL));
        body.put("langKey", "de");

        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + REGISTER_ENDPOINT,
            HttpMethod.POST,
            body,
            jsonHeaders(),
            Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        String storedLangKey = jdbcTemplate.queryForObject(
            "SELECT lang_key FROM main.\"user\" WHERE email = ?",
            String.class,
            TEST_EMAIL
        );
        assertThat(storedLangKey).isEqualTo("de");
    }

    @Test
    void registerParent_noLangKey_defaultsToEn() {
        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + REGISTER_ENDPOINT,
            HttpMethod.POST,
            registrationBody(TEST_EMAIL),
            jsonHeaders(),
            Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        String storedLangKey = jdbcTemplate.queryForObject(
            "SELECT lang_key FROM main.\"user\" WHERE email = ?",
            String.class,
            TEST_EMAIL
        );
        assertThat(storedLangKey).isEqualTo("en");
    }


    // ── skillars-deferred-88 AC11: locked User cannot complete verification (parent endpoints) ──

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
                "VALUES (999999999999681, 0, ?, ?, ?, false)",
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

    /**
     * skillars-deferred-88 AC10 — deterministic proof that {@code uq_pot_one_active_per_user}
     * (V121) enforces the "one live phone OTP per user" invariant at the database, so a second
     * active-token insert for the same user cannot commit even if the delete-before-insert the
     * three registration services run in code is skipped or lost to a race.
     *
     * <p>A 6-thread barrier race was tried first but does not reliably contend (the threads
     * serialise, zero losers, and the test would pass even with V121 dropped — code-review
     * finding). A {@code @MockitoSpyBean} neutralising {@code deleteByUserIdAndUsedFalse} was the
     * next attempt but forks an extra Spring context (context-count ceiling — Dev Notes). This
     * exercises the constraint directly: one active row is seeded and committed, then a second
     * {@code used = false} row for the same user is inserted in its own transaction and must be
     * rejected by the partial unique index.
     *
     * <p>Asserts: the second insert throws {@code DataIntegrityViolationException} (Spring
     * translates PG {@code 23505}); exactly one active row survives; {@code verification_status}
     * is untouched. The {@code ApiAdvice} 409 {@code security.otpResendInProgress} mapping for
     * this constraint name rides the same already-tested {@code DataIntegrityViolationException}
     * handler path as the other entries in {@code CONSTRAINT_MAPPINGS}. Mutation check: drop
     * V121's unique index → the second insert succeeds, two active rows → the first two
     * assertions fail.
     */
    @Test
    void secondActiveOtpInsert_forSameUser_isRejectedByPartialUniqueIndex() {
        httpTestClient.makeHttpRequest(baseUrl() + REGISTER_ENDPOINT, HttpMethod.POST,
            registrationBody(TEST_EMAIL), jsonHeaders(), Void.class);
        Long userId = jdbcTemplate.queryForObject(
            "SELECT id FROM main.\"user\" WHERE email = ?", Long.class, TEST_EMAIL);
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE main.\"user\" SET verification_status = 'EMAIL_VERIFIED', "
                + "activated = true WHERE id = ?", userId);
            jdbcTemplate.update(
                "INSERT INTO main.phone_otp_tokens (id, version, user_id, otp_hash, expires_at, used) " +
                "VALUES (999999999999691, 0, ?, 'seed', ?, false)",
                userId, Timestamp.from(Instant.now().plus(10, ChronoUnit.MINUTES)));
            return null;
        });

        assertThatThrownBy(() -> transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO main.phone_otp_tokens (id, version, user_id, otp_hash, expires_at, used) " +
            "VALUES (999999999999692, 0, ?, 'seed2', ?, false)",
            userId, Timestamp.from(Instant.now().plus(10, ChronoUnit.MINUTES)))))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.phone_otp_tokens WHERE user_id = ? AND used = false", Integer.class, userId))
            .as("the partial unique index must leave exactly one active OTP row")
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT verification_status FROM main.\"user\" WHERE id = ?", String.class, userId))
            .as("a rejected OTP insert must not corrupt the outer verification state")
            .isEqualTo("EMAIL_VERIFIED");
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
            "lastName", "Parent",
            "email", email,
            "password", TEST_PASSWORD,
            "phone", "1234567890"
        );
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
            baseUrl() + RESEND_ENDPOINT, HttpMethod.POST,
            Map.of("email", TEST_EMAIL), jsonHeaders(), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID tokenAfter = jdbcTemplate.queryForObject(
            "SELECT evt.token FROM main.email_verification_tokens evt " +
            "JOIN main.\"user\" u ON u.id = evt.user_id WHERE u.email = ?", UUID.class, TEST_EMAIL);
        assertThat(tokenAfter).isEqualTo(tokenBefore);
    }

    @Test
    void resendPhoneOtp_lockedUser_rejected() {
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
            baseUrl() + "/api/security/parent/resend-otp", HttpMethod.POST,
            Map.of("verificationToken", tokenFor(userId)), jsonHeaders(), Void.class))
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
    void resendPhoneOtp_perUserRateLimit_fourthCallForSameUserIsRejected() {
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
                baseUrl() + "/api/security/parent/resend-otp", HttpMethod.POST,
                Map.of("verificationToken", tokenFor(userId)), jsonHeaders(), Void.class);
            assertThat(ok.getStatusCode()).as("call %s within the per-user cap", i + 1).isEqualTo(HttpStatus.OK);
        }

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/security/parent/resend-otp", HttpMethod.POST,
            Map.of("verificationToken", tokenFor(userId)), jsonHeaders(), Void.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── skillars-deferred-93 AC8: opaque phone-verification handle replaces the raw userId ──────

    private String tokenFor(long userId) {
        return verificationTokenService.issuePhoneVerificationToken(userId, "PARENT");
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
            baseUrl() + "/api/security/parent/resend-otp", HttpMethod.POST,
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
            baseUrl() + "/api/security/parent/resend-otp", HttpMethod.POST,
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
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
