package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
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

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "rate.limiting.enabled=false"
})
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class CoachRegistrationResourceIT {

    private static final String REGISTER_ENDPOINT = "/api/security/coach/register";
    private static final String VERIFY_EMAIL_ENDPOINT = "/api/security/coach/verify-email";
    private static final String VERIFY_PHONE_ENDPOINT = "/api/security/coach/verify-phone";
    private static final String RESEND_ENDPOINT = "/api/security/coach/resend-verification";
    private static final String CLIENT_ID = "myClientId";
    private static final String TEST_EMAIL = "coach.test@skillars.com";
    private static final String TEST_PASSWORD = "Coach@123!";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private HttpTestClient httpTestClient;

    @LocalServerPort
    private int randomServerPort;

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (100, 'ROLE_COACH', 'ACTIVE', 'system', ?) " +
                "ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.phone_otp_tokens");
            jdbcTemplate.execute("DELETE FROM main.email_verification_tokens");
            jdbcTemplate.execute("DELETE FROM main.user_authority");
            jdbcTemplate.execute("DELETE FROM main.\"user\"");
            jdbcTemplate.execute("DELETE FROM main.authority");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void registerCoach_validData_returns200AndUserIsUnverified() {
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
        assertThat(user.get("skillars_role")).isEqualTo("COACH");
        assertThat(user.get("verification_status")).isEqualTo("UNVERIFIED");
        assertThat(user.get("activated")).isEqualTo(false);
    }

    @Test
    void registerCoach_duplicateEmail_returns409() {
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
    void registerCoach_missingRequiredField_returns400() {
        Map<String, Object> body = Map.of(
            "lastName", "Coach",
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
    void verifyEmail_validToken_setsEmailVerifiedAndReturnsUserId() {
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
        assertThat(response.getBody()).containsKey("userId");

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
            userId, expiredToken, Timestamp.from(Instant.now().minus(2, ChronoUnit.HOURS))) ;

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

        // Advance user to EMAIL_VERIFIED and insert OTP token in a committed transaction.
        // HikariCP is configured with auto-commit=false, so bare jdbcTemplate calls don't commit;
        // the server won't see the data unless it is committed via transactionTemplate.
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
            Map.of("userId", userId, "otp", knownOtp),
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
            Map.of("userId", userId, "otp", "000000"),
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
    void registerCoach_withLangKey_storesLangKeyOnUser() {
        Map<String, Object> body = new java.util.HashMap<>(registrationBody(TEST_EMAIL));
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
    void registerCoach_noLangKey_defaultsToEn() {
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

    private String baseUrl() {
        return "http://localhost:" + randomServerPort;
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
            "lastName", "Coach",
            "email", email,
            "password", TEST_PASSWORD,
            "phone", "1234567890"
        );
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


