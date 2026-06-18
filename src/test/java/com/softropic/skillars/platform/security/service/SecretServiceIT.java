package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.persistence.EntityStatus;
import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.platform.security.repo.Secret;
import com.softropic.skillars.utils.DbCleaner;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles({"dev", "test"})
//TODO take note "webEnvironment=WebEnvironment.RANDOM_PORT" is needed so as to configure TestRestTemplate
@SpringBootTest(webEnvironment= SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"logging.level.org.springframework.security=TRACE"})
@Import(TestConfig.class)
class SecretServiceIT {
    public static final String V_1 = "v1";
    public static final String JWT = "jwt";

    @Autowired
    private SecretService secretService;

    @Autowired
    private DbCleaner dbCleaner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void beforeAll() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Key", "Value");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void cleanup() {
        dbCleaner.cleanDb();
    }

    @AfterAll
    static void afterAll() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void createInactiveSecret() {
        final Secret secret = secretService.createInactiveSecret(V_1, JWT);
        final byte[] bytes = secretService.fetchSecretAsBytes(V_1, JWT);
        assertThat(Arrays.equals(bytes, secret.getSecretBytes())).isTrue();
        assertThat(secret.getStatus()).isEqualTo(EntityStatus.INACTIVE);
        final String query = "SELECT status, value, version FROM main.sec where version = '%s' AND bus_id = '%s'";
        final Map<String, Object> results = transactionTemplate.execute(status ->
            jdbcTemplate.queryForMap(String.format(query, V_1, JWT)));
        assertThat(results).containsEntry("status","INACTIVE");
    }

    @Test
    void createActiveSecret() {
        final Secret secret = secretService.createActiveSecret(V_1, JWT);
        final byte[] bytes = secretService.fetchSecretAsBytes(V_1, JWT);
        assertThat(Arrays.equals(bytes, secret.getSecretBytes())).isTrue();
        assertThat(secret.getStatus()).isEqualTo(EntityStatus.ACTIVE);
        final String query = "SELECT status, value, version FROM main.sec where version = '%s' AND bus_id = '%s'";
        final Map<String, Object> results = transactionTemplate.execute(status ->
            jdbcTemplate.queryForMap(String.format(query, V_1, JWT)));
        assertThat(results).containsEntry("status","ACTIVE");
    }

    @Test
    void whenCreateWithSameVersionAndBusId_expectConstraintViolation() {
        secretService.createActiveSecret(V_1, JWT);
        assertThatThrownBy(() -> secretService.createActiveSecret(V_1, JWT)).isInstanceOf(
                DataIntegrityViolationException.class);
    }

}