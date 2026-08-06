package com.softropic.skillars.platform.video;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.skillars.config.TestConfig;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

/**
 * Base class for Video module integration tests.
 * Starts PostgreSQL (via {@link TestConfig}) and WireMock for Bunny.net stubs.
 * The WireMock server URL is wired into {@code app.video.bunny.cdn-hostname} via
 * {@code application-test.yaml}, which references the auto-registered
 * {@code wiremock.server.bunny-service.baseUrl} property.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@ActiveProfiles({"dev", "test"})
@EnableWireMock(@ConfigureWireMock(name = "bunny-service"))
public abstract class BaseVideoIT {

    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired protected TransactionTemplate transactionTemplate;

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
