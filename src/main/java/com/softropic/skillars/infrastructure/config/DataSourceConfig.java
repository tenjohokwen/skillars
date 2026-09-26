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

    /**
     * skillars-deferred-137 AC2: the {@link RoutingDataSourceContext} key {@code
     * BookingPaymentPersistenceService} sets around its three {@code REQUIRES_NEW} connection
     * acquisitions ({@code reserveCapture}, {@code persistPaymentFailure}, {@code declineBatchBooking})
     * — the second named target on the SAME {@link RoutingDataSource} bean {@link #GDPR_ERASURE_DATASOURCE_KEY}
     * already uses, per that mechanism's own Javadoc ("business-agnostic, reusable by any future module
     * needing a second dedicated pool").
     */
    public static final String PAYMENT_REQUIRES_NEW_DATASOURCE_KEY = "payment-requires-new";

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
            @Qualifier("gdprErasureHikariConfig") HikariConfig gdprErasureHikariConfig,
            @Qualifier("paymentRequiresNewHikariConfig") HikariConfig paymentRequiresNewHikariConfig) {
        return new RoutingDataSource(new HikariDataSource(hikariConfig),
            Map.of(GDPR_ERASURE_DATASOURCE_KEY, new HikariDataSource(gdprErasureHikariConfig),
                PAYMENT_REQUIRES_NEW_DATASOURCE_KEY, new HikariDataSource(paymentRequiresNewHikariConfig)));
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

    /**
     * skillars-deferred-137 AC2: small, dedicated pool for {@code BookingPaymentPersistenceService}'s
     * three {@code REQUIRES_NEW} connection acquisitions ({@code reserveCapture}, {@code
     * persistPaymentFailure}, {@code declineBatchBooking}) — reuses the exact mechanism
     * skillars-deferred-136 built for {@link #gdprErasureHikariConfig} (its own Javadoc explicitly
     * invites this: "business-agnostic, reusable by any future module needing a second dedicated
     * pool"), extending the SAME {@link RoutingDataSource} bean with a second named target rather than
     * building a parallel routing mechanism.
     *
     * <p><strong>Sizing, derived independently of the GDPR pool's own {@code max 3}/{@code 10s}:</strong>
     * these three methods run on live booking-accept/settle/batch-listener request-thread traffic, not
     * GDPR's admin-only trigger, so a pool-pressure gap here is more production-realistic (flagged
     * twice, 2026-06-25 and 2026-08-24, never fixed). {@code maximumPoolSize = 10} accounts for
     * concurrent booking-accept/settle/decline operations from MULTIPLE independent request threads —
     * not just the {@code booking.batch.maxSize = 5} concurrency within a single batch-accept listener
     * invocation (where these three methods are called SEQUENTIALLY per-booking). The real concurrent
     * draw on this pool is driven by how many DIFFERENT concurrent booking-accept/settle/decline
     * requests are in flight at once across the request thread pool (e.g., 5 batch operations + 5
     * non-batch direct calls = 10 concurrent draw), not by the sequential per-booking calls within one
     * batch. Sizing conservatively at 2x batch.maxSize (10) provides headroom while staying far below
     * the primary pool's own 25. {@code minimumIdle = 1} (unlike the GDPR pool's {@code 0}): this pool
     * sees regular production traffic, not a rare admin trigger, so keeping one warm connection avoids
     * paying a cold-open penalty on every booking accept. {@code connectionTimeout = 5_000} (half the
     * GDPR pool's 10s): {@code onBookingAccepted}/{@code onBatchBookingAccepted} are non-{@code @Async}
     * {@code AFTER_COMMIT} listeners that run SYNCHRONOUSLY on the same request thread that just
     * accepted the booking, so a slow acquisition here directly extends that HTTP response — failing
     * faster keeps the accept responsive and defers cleanly to the already-existing
     * {@code PaymentPendingSweeper} reconciliation path for a dropped reservation, exactly as
     * {@link com.softropic.skillars.platform.payment.service.PaymentLifecycleService#reserveOrReport}
     * already treats any {@code reserveCapture} failure today.
     */
    @Bean
    @ConditionalOnProperty(name = "datasource.container", havingValue = "false", matchIfMissing = true)
    HikariConfig paymentRequiresNewHikariConfig(DataSourceProperties dataSourceProperties,
            @Value("${spring.datasource.hikari.auto-commit}") boolean autoCommit,
            @Value("${spring.datasource.hikari.connection-init-sql}") String connectionInitSql) {
        final HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPassword(dataSourceProperties.getPassword());
        hikariConfig.setUsername(dataSourceProperties.getUsername());
        hikariConfig.setJdbcUrl(dataSourceProperties.getUrl());
        hikariConfig.setPoolName("payment-requires-new-pool");
        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setMinimumIdle(1);
        hikariConfig.setConnectionTimeout(5_000);
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
