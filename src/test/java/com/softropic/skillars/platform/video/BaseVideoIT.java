package com.softropic.skillars.platform.video;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.skillars.config.PostgresContainerConfig;
import com.softropic.skillars.config.RedisContainerConfig;
import com.softropic.skillars.config.TestMailConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

/**
 * Base class for Video module integration tests.
 * Starts PostgreSQL (via {@link PostgresContainerConfig}) and WireMock for Bunny.net stubs.
 * The WireMock server URL is wired into {@code app.video.bunny.cdn-hostname} via
 * {@code application-test.yaml}, which references the auto-registered
 * {@code wiremock.server.bunny-service.baseUrl} property.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Testcontainers
@Import({PostgresContainerConfig.class, RedisContainerConfig.class,
         TestMailConfig.class, BaseVideoIT.VideoTestConfig.class})
@ActiveProfiles({"dev", "test"})
@EnableWireMock(@ConfigureWireMock(name = "bunny-service"))
public abstract class BaseVideoIT {

    @InjectWireMock("bunny-service")
    protected WireMockServer wireMockServer;

    @TestConfiguration(proxyBeanMethods = false)
    static class VideoTestConfig {

        @Bean
        @Primary
        RestTemplate restTemplate(RestTemplateBuilder builder) {
            return builder.build();
        }
    }
}
