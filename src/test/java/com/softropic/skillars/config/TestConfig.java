package com.softropic.skillars.config;

import com.softropic.skillars.infrastructure.config.DataSourceConfig;
import com.softropic.skillars.infrastructure.config.RoutingDataSource;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.utils.TestMailManager;
import com.softropic.skillars.utils.sql.EntityFetchAsserter;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;


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

import javax.sql.DataSource;
import java.util.Map;

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

    // skillars-deferred-136 AC1: no longer what builds the primary DataSource in the test path (the
    // explicit dataSource() bean below does, which backs off Boot's DataSourceAutoConfiguration) — kept
    // for any other ConnectionDetails-consuming auto-configuration and as this bean's own established
    // access point to SharedContainers.postgres()'s coordinates.
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

    /**
     * skillars-deferred-136 AC1: test-side equivalent of {@link DataSourceConfig}'s own
     * {@code dataSource}/{@code gdprErasureHikariConfig} pair — an explicit {@code DataSource} bean,
     * conditioned the same way (opposite {@code datasource.container} value) so exactly one of the two
     * is ever active. Providing this bean makes Boot's own {@code DataSourceAutoConfiguration} back off
     * (it is {@code @ConditionalOnMissingBean(DataSource.class)}), so {@link #jdbcConnectionDetails()}
     * is no longer what builds the primary pool — both pools here read the same container coordinates
     * that bean already exposed, directly from {@link SharedContainers#postgres()}, so
     * {@code GdprErasureIT} exercises a genuinely separate, independently-pooled dedicated
     * {@code DataSource} rather than a silently-skipped no-op.
     */
    @Bean
    @ConditionalOnProperty(name = "datasource.container", havingValue = "true")
    DataSource dataSource() {
        final PostgreSQLContainer<?> postgres = SharedContainers.postgres();
        return new RoutingDataSource(containerHikariDataSource(postgres, "hikari-db-pool", 25, 30_000),
            Map.of(DataSourceConfig.GDPR_ERASURE_DATASOURCE_KEY,
                containerHikariDataSource(postgres, "gdpr-erasure-pool", 3, 10_000)));
    }

    private static HikariDataSource containerHikariDataSource(
            PostgreSQLContainer<?> postgres, String poolName, int maximumPoolSize, long connectionTimeoutMs) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(postgres.getJdbcUrl());
        cfg.setUsername(postgres.getUsername());
        cfg.setPassword(postgres.getPassword());
        cfg.setPoolName(poolName);
        cfg.setMaximumPoolSize(maximumPoolSize);
        cfg.setConnectionTimeout(connectionTimeoutMs);
        cfg.setAutoCommit(false);
        cfg.setConnectionInitSql("SET TIME ZONE 'UTC'");
        return new HikariDataSource(cfg);
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
