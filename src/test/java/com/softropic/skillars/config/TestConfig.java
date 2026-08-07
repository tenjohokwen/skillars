package com.softropic.skillars.config;

import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.utils.TestMailManager;
import com.softropic.skillars.utils.sql.EntityFetchAsserter;


import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;



import jakarta.persistence.EntityManagerFactory;

@TestConfiguration(proxyBeanMethods = false)
public class TestConfig {

    /*
     * These two beans replaced @ServiceConnection-annotated container @Beans.
     *
     * A container registered as a bean is Startable, so Boot's
     * TestcontainersLifecycleBeanPostProcessor starts it on context refresh and stops it on
     * context close -- binding container lifetime to the context and turning every context-cache
     * fork into another pair of Docker containers.
     *
     * ConnectionDetails beans are NOT Startable, so the post-processor ignores them entirely, and
     * the containers themselves live in SharedContainers for the life of the JVM. Do not "simplify"
     * this back to returning SharedContainers.postgres() from an @ServiceConnection @Bean: the
     * post-processor would then adopt the shared instance and the first context to close would
     * stop the container every other context is still using.
     */

    @Bean
    JdbcConnectionDetails jdbcConnectionDetails() {
        final PostgreSQLContainer<?> postgres = SharedContainers.postgres();
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return postgres.getUsername();
            }

            @Override
            public String getPassword() {
                return postgres.getPassword();
            }

            @Override
            public String getJdbcUrl() {
                return postgres.getJdbcUrl();
            }
        };
    }

    @Bean
    RedisConnectionDetails redisConnectionDetails() {
        final GenericContainer<?> redis = SharedContainers.redis();
        // Not a lambda: RedisConnectionDetails declares only default methods, so it is not a
        // functional interface.
        return new RedisConnectionDetails() {
            @Override
            public Standalone getStandalone() {
                return Standalone.of(redis.getHost(), redis.getFirstMappedPort());
            }
        };
    }




    @Bean
    public EntityFetchAsserter createAsserter(EntityManagerFactory emf) {
        return new EntityFetchAsserter(emf);
    }


    @Bean
    @Primary
    @ConditionalOnProperty(name = "enable.test.mail", havingValue = "true")
    public MailManager mailManager() {
        return new TestMailManager();
    }

    @Bean
    @Primary
    public PaymentGateway paymentGateway() {
        return new StubPaymentGateway();
    }

    @Bean
    @Primary
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        // Primary bean — wins unqualified RestTemplate injection (e.g. HttpTestClient)
        // noRetryRestTemplate bean (WebhookConfig) requires @Qualifier("noRetryRestTemplate")
        // Cookie management is disabled: Apache HttpClient 5's default cookie store would
        // persist login cookies across tests, causing unauthenticated requests to be
        // silently injected with a previous test's JWT.
        var httpClient = HttpClients.custom().disableCookieManagement().build();
        return builder
            .requestFactory(() -> new HttpComponentsClientHttpRequestFactory(httpClient))
            .build();
    }
}
