package com.softropic.skillars.infrastructure.config;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Routes each connection acquisition to whichever target {@link RoutingDataSourceContext#get()}
 * names at that moment, falling back to the default target when no key is set — i.e. for every
 * ordinary request/transaction in the app. Exists because Hibernate binds one {@code EntityManagerFactory}
 * to one {@code ConnectionProvider}/{@code DataSource} at bootstrap: sharing that single
 * {@code EntityManagerFactory} across two independently-pooled {@code HikariDataSource}s is not
 * possible by swapping the {@code PlatformTransactionManager} a caller uses (empirically verified —
 * see skillars-deferred-136 AC1's Dev Agent Record) — the target selection has to happen INSIDE the
 * one {@code DataSource} the app's single persistence unit is built against, which is exactly what
 * {@code AbstractRoutingDataSource} does.
 *
 * <p>Every target must point at the SAME physical database — this only isolates connection POOLS,
 * not schemas or data.
 *
 * <p>Registered as a Spring bean but its target {@code DataSource}s deliberately are not (see
 * {@link com.softropic.skillars.infrastructure.config.DataSourceConfig}): this class owns their
 * lifecycle instead ({@link #destroy()}), since {@code AbstractRoutingDataSource} itself has no
 * shutdown hook of its own for the pools it wraps.
 */
public class RoutingDataSource extends AbstractRoutingDataSource implements DisposableBean {

    private final DataSource defaultTarget;
    private final Map<String, DataSource> namedTargets;
    private final Iterable<DataSource> ownedTargets;

    public RoutingDataSource(DataSource defaultTarget, Map<String, DataSource> namedTargets) {
        this.defaultTarget = defaultTarget;
        this.namedTargets = Map.copyOf(namedTargets);
        setDefaultTargetDataSource(defaultTarget);
        setTargetDataSources(new java.util.HashMap<>(this.namedTargets));
        // Deliberately NOT calling afterPropertiesSet() here (story review — a prior version did):
        // this class is itself registered as a Spring bean, so the container already invokes
        // InitializingBean#afterPropertiesSet() during normal bean lifecycle processing, after this
        // constructor returns. Calling it a second time here was harmless (the resolved lookup-key
        // map it builds is simply overwritten by the container's own call) but redundant and easy to
        // misread as load-bearing.
        this.ownedTargets = java.util.stream.Stream.concat(
            java.util.stream.Stream.of(defaultTarget), namedTargets.values().stream()).toList();
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return RoutingDataSourceContext.get();
    }

    /**
     * Test/diagnostic-only accessor to the raw default target — not part of the routing behaviour
     * itself, so kept separate from {@link #determineCurrentLookupKey()}'s own lookup mechanism.
     */
    public DataSource getDefaultTarget() {
        return defaultTarget;
    }

    /** Test/diagnostic-only accessor to a raw named target, keyed exactly as constructed. */
    public DataSource getNamedTarget(String key) {
        DataSource target = namedTargets.get(key);
        if (target == null) {
            throw new IllegalArgumentException("No RoutingDataSource target registered for key: " + key);
        }
        return target;
    }

    @Override
    public void destroy() {
        for (DataSource target : ownedTargets) {
            if (target instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // best-effort shutdown of a pooled DataSource we own; nothing further to do
                }
            }
        }
    }
}
