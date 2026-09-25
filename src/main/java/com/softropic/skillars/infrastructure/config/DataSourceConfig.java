package com.softropic.skillars.infrastructure.config;


import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.ttddyy.dsproxy.listener.logging.SLF4JLogLevel;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
public class DataSourceConfig {

    /**
     * skillars-deferred-136 AC1: the {@link RoutingDataSourceContext} key {@code GdprErasureService}
     * sets around its two {@code REQUIRES_NEW} connection acquisitions to route them to
     * {@link #gdprErasureHikariConfig}'s dedicated pool instead of the primary one — see this
     * bean's own Javadoc on {@link #dataSource} for why a routing DataSource, not a second
     * {@code PlatformTransactionManager}, is the mechanism (empirically verified single-persistence-unit
     * constraint, see the story's Dev Agent Record).
     */
    public static final String GDPR_ERASURE_DATASOURCE_KEY = "gdpr-erasure";

    // Skipped when datasource.container=true (tests/dev) — Boot auto-configures from @ServiceConnection
    // instead (see TestConfig's own equivalent routing bean, conditioned the opposite way).
    //
    // skillars-deferred-136 AC1: this app has exactly one EntityManagerFactory (no @EnableJpaRepositories
    // exists anywhere in this codebase), and Hibernate binds an EntityManagerFactory to one
    // ConnectionProvider/DataSource at bootstrap — a second HikariDataSource cannot be given its own
    // pool-isolated connection acquisitions merely by wrapping it in a second PlatformTransactionManager
    // that still points at the SAME EntityManagerFactory (empirically disproved: a JpaTransactionManager
    // built against an unreachable DataSource but the app's real, shared EntityManagerFactory still
    // executed a JPA query successfully — the DataSource it was given was never consulted). The one
    // mechanism that genuinely isolates connection acquisition while keeping a single EntityManagerFactory
    // (and therefore zero Spring Data JPA repository re-registration) is routing INSIDE the single
    // DataSource bean the persistence unit is built against — hence RoutingDataSource here, not a second
    // transaction manager.
    @Bean
    @ConditionalOnProperty(name = "datasource.container", havingValue = "false", matchIfMissing = true)
    DataSource dataSource(@Qualifier("hikariConfig") HikariConfig hikariConfig,
            @Qualifier("gdprErasureHikariConfig") HikariConfig gdprErasureHikariConfig) {
        return new RoutingDataSource(new HikariDataSource(hikariConfig),
            Map.of(GDPR_ERASURE_DATASOURCE_KEY, new HikariDataSource(gdprErasureHikariConfig)));
    }

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    @ConditionalOnProperty(name = "datasource.container", havingValue = "false", matchIfMissing = true)
    HikariConfig hikariConfig(DataSourceProperties dataSourceProperties) {
        final HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPassword(dataSourceProperties.getPassword());
        hikariConfig.setUsername(dataSourceProperties.getUsername());
        hikariConfig.setJdbcUrl(dataSourceProperties.getUrl());
        return hikariConfig;
    }

    /**
     * skillars-deferred-136 AC1: small, dedicated pool for {@code GdprErasureService}'s two
     * {@code REQUIRES_NEW} connection acquisitions (closes ledger "Hazard 2") — same JDBC coordinates
     * as the primary pool, but a separately-configured, deliberately SHORTER {@code connection-timeout}
     * (10s vs. the primary's 30s, {@code spring.datasource.hikari.connection-timeout}) so a saturated
     * primary pool cannot make these two acquisitions wait as long, and this pool's own exhaustion fails
     * fast on its own terms. Deliberately NOT bound via {@code @ConfigurationProperties} to
     * {@code spring.datasource.hikari.*} like {@link #hikariConfig} — that prefix's
     * {@code maximum-pool-size: 25} is sized for the whole app's ordinary traffic, not this pool's
     * narrow purpose. {@code auto-commit} and {@code connection-init-sql} are read from the SAME
     * {@code spring.datasource.hikari.*} keys the primary pool's own {@link #hikariConfig} binds via
     * {@code @ConfigurationProperties} (story review — previously hardcoded literal duplicates here, a
     * drift risk if the primary pool's own config ever changed without this one being updated to match).
     * Matching auto-commit specifically matters: Hibernate's
     * {@code connection.provider_disables_autocommit: true} setting is global and tells Hibernate to
     * SKIP its own explicit autocommit-disable call, trusting every pool it might acquire a connection
     * from to already hand out autocommit-off connections — a pool that didn't match this would silently
     * run GDPR erasure statements outside a real transaction.
     */
    @Bean
    @ConditionalOnProperty(name = "datasource.container", havingValue = "false", matchIfMissing = true)
    HikariConfig gdprErasureHikariConfig(DataSourceProperties dataSourceProperties,
            @Value("${spring.datasource.hikari.auto-commit}") boolean autoCommit,
            @Value("${spring.datasource.hikari.connection-init-sql}") String connectionInitSql) {
        final HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPassword(dataSourceProperties.getPassword());
        hikariConfig.setUsername(dataSourceProperties.getUsername());
        hikariConfig.setJdbcUrl(dataSourceProperties.getUrl());
        hikariConfig.setPoolName("gdpr-erasure-pool");
        hikariConfig.setMaximumPoolSize(3);
        hikariConfig.setMinimumIdle(0);
        hikariConfig.setConnectionTimeout(10_000);
        hikariConfig.setIdleTimeout(300_000);
        hikariConfig.setMaxLifetime(900_000);
        hikariConfig.setAutoCommit(autoCommit);
        hikariConfig.setConnectionInitSql(connectionInitSql);
        return hikariConfig;
    }

    // Wraps the primary dataSource bean with a SQL-logging proxy when log.database.spy=true.
    // Works regardless of whether the DataSource came from DataSourceConfig or Boot's @ServiceConnection auto-config.
    @Bean
    @ConditionalOnProperty(name = "log.database.spy", havingValue = "true")
    static BeanPostProcessor dataSourceSpyPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if ("dataSource".equals(beanName) && bean instanceof DataSource ds) {
                    return ProxyDataSourceBuilder.create(ds)
                        .name("skillars-spy")
                        .logQueryBySlf4j(SLF4JLogLevel.DEBUG)
                        .build();
                }
                return bean;
            }
        };
    }
}
