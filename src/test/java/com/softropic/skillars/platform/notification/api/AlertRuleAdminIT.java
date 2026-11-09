package com.softropic.skillars.platform.notification.api;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.e2e.AdminLogin;
import com.softropic.skillars.platform.security.SecurityIT;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * skillars-deferred-173 (RS11): editing an alert rule updates the same row. It used to delete the rule and then
 * save a new one in a separate transaction, so a body the save rejected (a missing metric name) lost the rule,
 * and even a good edit gave the rule a new id.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class AlertRuleAdminIT extends AbstractIntegrationTest {

    private static final long ADMIN_USER_ID = 9173_000_101L;
    private static final long RULE_ID = 9173_000_201L;

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;

    private final RestTemplate restTemplate = new RestTemplateBuilder()
        .requestFactory(SimpleClientHttpRequestFactory.class).build();
    private HttpHeaders adminHeaders;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute(
                "INSERT INTO main.authority (id, name, status, created_by, created_date, last_modified_by, last_modified_date, request_id) "
                    + "VALUES (6747751741842104908, 'ROLE_ADMIN', 'ACTIVE', 'system', '2016-04-26 20:41:25', 'system', '2016-04-26 20:41:25', '') "
                    + "ON CONFLICT DO NOTHING");
            // Same fixture as ConfigResourceIT: AdminLogin signs in as queb@yahoo.com with this hash's password.
            jdbcTemplate.execute(
                "INSERT INTO main.\"user\" "
                    + "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, "
                    + " status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, phone_type, "
                    + " title, activated, activation_date, activation_key, locked, login, login_id_type, "
                    + " password_hash, reset_expiration, reset_key, otp_enabled) VALUES "
                    + "(" + ADMIN_USER_ID + ", 'anonymousUser', '2025-02-06 16:12:34', 'anonymousUser', '2025-02-06 16:12:35', "
                    + " 'd503b412-b576-48c2-8ead-ec9e10d42881', NULL, 'ACTIVE', '1990-02-20', 'queb@yahoo.com', "
                    + " 'VAYM', 'MALE', 'en', 'FXFUOUQBUO', 'DE', '01724527687', 'MOBILE', NULL, "
                    + " true, NULL, NULL, false, 'queb@yahoo.com', 'EMAIL', "
                    + " '$2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i', NULL, NULL, false) "
                    + "ON CONFLICT DO NOTHING");
            jdbcTemplate.execute(
                "INSERT INTO main.user_authority (user_id, authority_id) VALUES (" + ADMIN_USER_ID
                    + ", (SELECT id FROM main.authority WHERE name = 'ROLE_ADMIN')) ON CONFLICT DO NOTHING");
            jdbcTemplate.update(
                "INSERT INTO main.alert_rule (id, status, metric_name, threshold, window_seconds, notification_channel, "
                    + "enabled, description) VALUES (?, 'ACTIVE', 'FAILURE_RATE', 0.2000, 300, 'LOG', true, 'original')",
                RULE_ID);
        });
        String baseUrl = "http://localhost:" + port;
        adminHeaders = AdminLogin.loginAsAdmin(baseUrl, restTemplate);
        adminHeaders.add("user-agent", AdminLogin.TEST_USER_AGENT);
        adminHeaders.add(HttpHeaders.COOKIE, "fcookie=fingerprintCookie");
        adminHeaders.setContentType(MediaType.APPLICATION_JSON);
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("DELETE FROM main.alert_rule WHERE id = ? OR metric_name = 'FRAUD_SPIKE_RATE'", RULE_ID);
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id = ?", ADMIN_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", ADMIN_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.sec");
        });
    }

    @Test
    void anEdit_updatesTheSameRow_andKeepsItsId() {
        ResponseEntity<Map> response = put(RULE_ID, body("FRAUD_SPIKE_RATE", "0.35", 600, "EMAIL", false, "edited"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(response.getBody().get("id"))).isEqualTo(String.valueOf(RULE_ID));
        Map<String, Object> row = rule();
        assertThat(row.get("metric_name")).isEqualTo("FRAUD_SPIKE_RATE");
        assertThat((BigDecimal) row.get("threshold")).isEqualByComparingTo("0.35");
        assertThat(row.get("window_seconds")).isEqualTo(600);
        assertThat(row.get("notification_channel")).isEqualTo("EMAIL");
        assertThat(row.get("enabled")).isEqualTo(false);
        assertThat(row.get("description")).isEqualTo("edited");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM main.alert_rule WHERE id <> ? "
            + "AND metric_name = 'FRAUD_SPIKE_RATE'", Integer.class, RULE_ID)).isZero();
    }

    @Test
    void anInvalidEdit_answers400_andLeavesTheRuleAsItWas() {
        Map<String, Object> missingMetric = body(null, "0.35", 600, "LOG", true, "x");
        Map<String, Object> thresholdAboveOne = body("FAILURE_RATE", "1.5", 600, "LOG", true, "x");
        Map<String, Object> unknownChannel = body("FAILURE_RATE", "0.35", 600, "SMS", true, "x");
        Map<String, Object> zeroWindow = body("FAILURE_RATE", "0.35", 0, "LOG", true, "x");

        for (Map<String, Object> invalid : new Map[] {missingMetric, thresholdAboveOne, unknownChannel, zeroWindow}) {
            assertThatThrownBy(() -> put(RULE_ID, invalid)).as(invalid.toString())
                .isInstanceOf(HttpClientErrorException.BadRequest.class);
        }

        Map<String, Object> row = rule();
        assertThat(row.get("metric_name")).isEqualTo("FAILURE_RATE");
        assertThat((BigDecimal) row.get("threshold")).isEqualByComparingTo("0.2");
        assertThat(row.get("description")).isEqualTo("original");
    }

    @Test
    void anEditOfAnUnknownRule_answers404() {
        assertThatThrownBy(() -> put(RULE_ID + 1, body("FAILURE_RATE", "0.3", 60, "LOG", true, null)))
            .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    void anInvalidCreate_answers400_andStoresNothing() {
        assertThatThrownBy(() -> restTemplate.exchange("http://localhost:" + port + "/v1/admin/alerts", HttpMethod.POST,
            new HttpEntity<>(body("FRAUD_SPIKE_RATE", null, 60, "LOG", true, null), adminHeaders), Map.class))
            .isInstanceOf(HttpClientErrorException.BadRequest.class);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.alert_rule WHERE metric_name = 'FRAUD_SPIKE_RATE'", Integer.class)).isZero();
    }

    private ResponseEntity<Map> put(long id, Map<String, Object> body) {
        return restTemplate.exchange("http://localhost:" + port + "/v1/admin/alerts/" + id, HttpMethod.PUT,
            new HttpEntity<>(body, adminHeaders), Map.class);
    }

    private static Map<String, Object> body(String metric, String threshold, int window, String channel,
                                            boolean enabled, String description) {
        Map<String, Object> body = new HashMap<>();
        body.put("metricName", metric);
        body.put("threshold", threshold == null ? null : new BigDecimal(threshold));
        body.put("windowSeconds", window);
        body.put("notificationChannel", channel);
        body.put("enabled", enabled);
        body.put("description", description);
        return body;
    }

    private Map<String, Object> rule() {
        return jdbcTemplate.queryForMap("SELECT * FROM main.alert_rule WHERE id = ?", RULE_ID);
    }
}
