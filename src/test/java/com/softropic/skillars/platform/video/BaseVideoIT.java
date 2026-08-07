package com.softropic.skillars.platform.video;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.wiremock.spring.InjectWireMock;

/**
 * Base class for Video module integration tests.
 * Starts PostgreSQL (via {@link TestConfig}) and WireMock for Bunny.net stubs.
 * The WireMock server URL is wired into {@code app.video.bunny.cdn-hostname} via
 * {@code application-test.yaml}, which references the auto-registered
 * {@code wiremock.server.bunny-service.baseUrl} property.
 */
public abstract class BaseVideoIT extends AbstractIntegrationTest {


    @InjectWireMock("bunny-service")
    protected WireMockServer wireMockServer;

    @AfterEach
    void tearDownSec() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }
}
