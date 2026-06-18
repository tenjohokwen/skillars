package com.softropic.skillars.platform.config.api;

import com.softropic.skillars.config.E2ESecurityConfig;
import com.softropic.skillars.config.PostgresContainerConfig;
import com.softropic.skillars.config.RedisContainerConfig;
import com.softropic.skillars.config.TestMailConfig;
import com.softropic.skillars.e2e.AdminLogin;
import com.softropic.skillars.platform.config.contract.ConfigValueResponse;
import com.softropic.skillars.platform.config.contract.UpdateConfigRequest;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.service.LoginAttemptsService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Testcontainers
@Import({PostgresContainerConfig.class, RedisContainerConfig.class,
         E2ESecurityConfig.class, TestMailConfig.class, ConfigResourceIT.TestConfig.class})
@ActiveProfiles({"dev", "test"})
@TestPropertySource(properties = {"spring.cloud.compatibility-verifier.enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ConfigResourceIT {

    // Stable fixture IDs used only in this test class
    private static final long ADMIN_USER_ID    = 675373350208068096L;
    private static final long NONADMIN_USER_ID = 675373350208068097L;
    private static final long ROLE_ADMIN_ID    = 6747751741842104908L;
    private static final long ROLE_USER_ID     = 5418719445932238328L;

    private static final String NONADMIN_LOGIN    = "nonadmin@configtest.com";
    private static final String NONADMIN_PASSWORD = "admin*123!";
    // Same bcrypt hash as the admin fixture (same password, self-contained salt)
    private static final String NONADMIN_PASSWORD_HASH =
            "$2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        RestTemplate restTemplate(RestTemplateBuilder builder) {
            return builder.build();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    RestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    LoginAttemptsService loginAttemptsService;

    @Autowired
    ConfigService configService;

    private HttpHeaders adminHeaders;
    private HttpHeaders userHeaders;

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            // Authorities (idempotent)
            jdbcTemplate.execute(
                "INSERT INTO main.authority (id, name, status, created_by, created_date, last_modified_by, last_modified_date, request_id) " +
                "VALUES (" + ROLE_ADMIN_ID + ", 'ROLE_ADMIN', 'ACTIVE', 'system', '2016-04-26 20:41:25', 'system', '2016-04-26 20:41:25', '') " +
                "ON CONFLICT DO NOTHING");
            jdbcTemplate.execute(
                "INSERT INTO main.authority (id, name, status, created_by, created_date, last_modified_by, last_modified_date, request_id) " +
                "VALUES (" + ROLE_USER_ID + ", 'ROLE_USER', 'ACTIVE', 'system', '2016-04-26 20:41:25', 'system', '2016-04-26 20:41:25', '') " +
                "ON CONFLICT DO NOTHING");

            // Admin user with ROLE_ADMIN + ROLE_USER
            jdbcTemplate.execute(
                "INSERT INTO main.\"user\" " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
                " status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, phone_type, " +
                " title, activated, activation_date, activation_key, locked, login, login_id_type, " +
                " password_hash, reset_expiration, reset_key, otp_enabled) " +
                "VALUES " +
                "(" + ADMIN_USER_ID + ", 'anonymousUser', '2025-02-06 16:12:34.516705', 'anonymousUser', '2025-02-06 16:12:35.198266', " +
                " 'd503b412-b576-48c2-8ead-ec9e10d42880', NULL, 'ACTIVE', '1990-02-20', 'queb@yahoo.com', " +
                " 'VAYM', 'MALE', 'en', 'FXFUOUQBUO', 'DE', '01724527687', 'MOBILE', NULL, " +
                " true, NULL, NULL, false, 'queb@yahoo.com', 'EMAIL', " +
                " '$2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i', NULL, NULL, false) " +
                "ON CONFLICT DO NOTHING");
            jdbcTemplate.execute(
                "INSERT INTO main.user_authority (user_id, authority_id) VALUES (" + ADMIN_USER_ID + ", " + ROLE_USER_ID + ") " +
                "ON CONFLICT DO NOTHING");
            jdbcTemplate.execute(
                "INSERT INTO main.user_authority (user_id, authority_id) VALUES (" + ADMIN_USER_ID + ", " + ROLE_ADMIN_ID + ") " +
                "ON CONFLICT DO NOTHING");

            // Non-admin user with ROLE_USER only
            jdbcTemplate.execute(
                "INSERT INTO main.\"user\" " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
                " status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, phone_type, " +
                " title, activated, activation_date, activation_key, locked, login, login_id_type, " +
                " password_hash, reset_expiration, reset_key, otp_enabled) " +
                "VALUES " +
                "(" + NONADMIN_USER_ID + ", 'anonymousUser', '2025-02-06 16:12:34.516705', 'anonymousUser', '2025-02-06 16:12:35.198266', " +
                " 'e604c523-c687-59d3-9fad-fd0f21e53981', NULL, 'ACTIVE', '1990-05-15', '" + NONADMIN_LOGIN + "', " +
                " 'TEST', 'MALE', 'en', 'USER', 'DE', '01724527688', 'MOBILE', NULL, " +
                " true, NULL, NULL, false, '" + NONADMIN_LOGIN + "', 'EMAIL', " +
                " '" + NONADMIN_PASSWORD_HASH + "', NULL, NULL, false) " +
                "ON CONFLICT DO NOTHING");
            jdbcTemplate.execute(
                "INSERT INTO main.user_authority (user_id, authority_id) VALUES (" + NONADMIN_USER_ID + ", " + ROLE_USER_ID + ") " +
                "ON CONFLICT DO NOTHING");

            return null;
        });

        loginAttemptsService.resetLoginRecording();

        RestTemplate noRetryRestTemplate = new RestTemplateBuilder()
                .requestFactory(SimpleClientHttpRequestFactory.class)
                .build();
        String authUrl = "http://localhost:" + port + "/authenticate";

        adminHeaders = AdminLogin.loginAsAdmin(authUrl, noRetryRestTemplate);
        adminHeaders.add("user-agent", AdminLogin.TEST_USER_AGENT);
        adminHeaders.add(HttpHeaders.COOKIE, "fcookie=fingerprintCookie");
        adminHeaders.setContentType(MediaType.APPLICATION_JSON);

        userHeaders = loginAs(NONADMIN_LOGIN, NONADMIN_PASSWORD, authUrl, noRetryRestTemplate);
        userHeaders.add("user-agent", AdminLogin.TEST_USER_AGENT);
        userHeaders.add(HttpHeaders.COOKIE, "fcookie=fingerprintCookie");
        userHeaders.setContentType(MediaType.APPLICATION_JSON);
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            // Reset any config rows modified during tests back to seed values
            jdbcTemplate.update(
                "UPDATE main.platform_config SET value = '0.08', updated_at = NOW() WHERE key = 'platform.commission_rate'");

            // Remove non-admin test user
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id = " + NONADMIN_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = " + NONADMIN_USER_ID);

            // Remove admin test user
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id = " + ADMIN_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = " + ADMIN_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.sec");

            return null;
        });

        // Force the service cache to reload seed values on next access
        configService.invalidate();
    }

    @Test
    void getExistingKey_returns200WithCorrectValue() {
        String url = "http://localhost:" + port + "/api/config/values/coach.tier.scout.drill_library";

        ResponseEntity<ConfigValueResponse> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(adminHeaders), ConfigValueResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().key()).isEqualTo("coach.tier.scout.drill_library");
        assertThat(response.getBody().value()).isEqualTo("true");
    }

    @Test
    void getUnknownKey_returns404() {
        String url = "http://localhost:" + port + "/api/config/values/does.not.exist";

        assertThatThrownBy(() -> restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(adminHeaders), ConfigValueResponse.class))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    void putAsAdmin_updatesValue_subsequentGetReturnsNewValue() {
        String key = "platform.commission_rate";
        String url = "http://localhost:" + port + "/api/config/values/" + key;

        UpdateConfigRequest updateRequest = new UpdateConfigRequest("0.10");
        HttpEntity<UpdateConfigRequest> putEntity = new HttpEntity<>(updateRequest, adminHeaders);

        ResponseEntity<ConfigValueResponse> putResponse = restTemplate.exchange(
                url, HttpMethod.PUT, putEntity, ConfigValueResponse.class);

        assertThat(putResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(putResponse.getBody()).isNotNull();
        assertThat(putResponse.getBody().value()).isEqualTo("0.10");

        ResponseEntity<ConfigValueResponse> getResponse = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(adminHeaders), ConfigValueResponse.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().value()).isEqualTo("0.10");
    }

    @Test
    void putAsNonAdmin_returns403() {
        String url = "http://localhost:" + port + "/api/config/values/platform.commission_rate";

        UpdateConfigRequest updateRequest = new UpdateConfigRequest("0.99");

        assertThatThrownBy(() -> restTemplate.exchange(
                url, HttpMethod.PUT,
                new HttpEntity<>(updateRequest, userHeaders),
                ConfigValueResponse.class))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    private HttpHeaders loginAs(String login, String password, String authUrl, RestTemplate restTemplate) {
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        loginHeaders.add(HttpHeaders.COOKIE, "fcookie=fingerprintCookie");
        loginHeaders.add("user-agent", AdminLogin.TEST_USER_AGENT);

        Map<String, String> credentials = Map.of("id", login, "password", password);
        ResponseEntity<Map> loginResponse = restTemplate.exchange(
                authUrl, HttpMethod.POST,
                new HttpEntity<>(credentials, loginHeaders),
                Map.class);

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<String> setCookies = loginResponse.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotNull().isNotEmpty();

        String cookieHeader = String.join("; ",
                setCookies.stream().map(c -> c.split(";", 2)[0]).toList());

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, cookieHeader);
        return headers;
    }
}
