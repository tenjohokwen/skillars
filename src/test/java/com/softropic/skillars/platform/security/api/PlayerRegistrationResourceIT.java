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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class PlayerRegistrationResourceIT extends AbstractIntegrationTest {

    private static final String REGISTER_ENDPOINT = "/api/security/player/register";
    private static final String VERIFY_EMAIL_ENDPOINT = "/api/security/player/verify-email";
    private static final String CLIENT_ID = "myClientId";
    private static final String TEST_EMAIL = "player.test@skillars.com";
    private static final String TEST_PASSWORD = "Player@123!";

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
