package com.softropic.skillars.config;

import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.utils.TestMailManager;
import com.softropic.skillars.utils.sql.EntityFetchAsserter;
import com.softropic.skillars.utils.sql.QueryRecorderListener;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import net.ttddyy.dsproxy.listener.ChainListener;
import net.ttddyy.dsproxy.listener.DataSourceQueryCountListener;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.listener.logging.SystemOutQueryLoggingListener;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;

@TestConfiguration(proxyBeanMethods = false)
public class TestConfig {

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer(@Value("${spring.application.name}") String dbName) {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:14.18"))
                .withDatabaseName(dbName)
                .withPassword("postgres")
                .withUsername("postgres")
                .withInitScript("sql/createSchema.sql");
    }

    @Bean
    @ConditionalOnProperty(name="log.database.spy", havingValue="true")
    DataSource spyDataSource(HikariConfig hikariConfig) {
        // https://jdbc-observations.github.io/datasource-proxy/docs/snapshot/user-guide/index.html
        final DataSource dataSourceSpy = new HikariDataSource(hikariConfig);
        SystemOutQueryLoggingListener listener = new SystemOutQueryLoggingListener();
        return ProxyDataSourceBuilder.create(dataSourceSpy)
                                     .name("DS-Proxy")
                                     .listener(listener)
                                     .multiline()
                                     .countQuery() //metric collection
                                     .logQueryToSysOut()
                                     .retrieveIsolation()
                                     .writeIsolation()
                                     .logSlowQueryToSysOut(1, TimeUnit.SECONDS)
                                     .build();
    }

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    @ConditionalOnProperty(name="datasource.container", havingValue="true")
    HikariConfig hikariConfig(JdbcConnectionDetails jdbcConnectionDetails) {
        final HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPassword(jdbcConnectionDetails.getPassword());
        hikariConfig.setUsername(jdbcConnectionDetails.getUsername());
        hikariConfig.setJdbcUrl(jdbcConnectionDetails.getJdbcUrl());
        return hikariConfig;
    }


    @Bean
    public EntityFetchAsserter createAsserter(EntityManagerFactory emf) {
        return new EntityFetchAsserter(emf);
    }

    private QueryExecutionListener provideListener() {
        final ChainListener chainListener = new ChainListener();
        chainListener.addListener(new DataSourceQueryCountListener());
        chainListener.addListener(new QueryRecorderListener());
        return chainListener;
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "enable.test.mail", havingValue = "true")
    public MailManager mailManager() {
        return new TestMailManager();
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
