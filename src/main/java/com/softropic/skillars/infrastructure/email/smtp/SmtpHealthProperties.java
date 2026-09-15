package com.softropic.skillars.infrastructure.email.smtp;

import jakarta.validation.constraints.AssertTrue;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * skillars-deferred-99 AC5 — tuning for {@link SmtpHealthIndicator}: how long an aggregate result is
 * served from cache, and the overall wall-clock budget for one round of parallel per-provider probes.
 *
 * <p>Story ses-1.2 AC2: moved from {@code platform.notification.contract.SmtpHealthProperties} and
 * rebound to {@code app.email.smtp.health}, matching {@code SmtpProperties}'s new home.
 *
 * <p>Passive value holder. Registered via {@code @EnableConfigurationProperties} in this package's
 * own {@code @Configuration}.
 *
 * <p><strong>Validated (code review 2026-09-15, M2):</strong> an unvalidated {@code down-ttl: 0s}
 * makes {@code now - computedAtNanos < 0} always false, so {@link SmtpHealthIndicator} re-probes on
 * every single scrape — opening a socket per Prometheus scrape instead of once per TTL window. An
 * unvalidated {@code down-ttl} greater than {@link #ttl} would also invert AC4's intent (a
 * {@code DOWN} result cached longer than an {@code UP} one). {@code @Validated} triggers Spring
 * Boot's JSR-303 binding validation for this {@code @ConfigurationProperties} bean.
 */
@Validated
@ConfigurationProperties(prefix = "app.email.smtp.health")
public class SmtpHealthProperties {

    /** Aggregate {@code Health} is recomputed at most once per this window. Default 60s. */
    @DurationMin(millis = 100)
    private Duration ttl = Duration.ofSeconds(60);

    /**
     * skillars-deferred-111 AC4 (owner decision): how long a freshly-computed {@code DOWN} result is
     * served from cache before the next scrape re-probes, shorter than {@link #ttl} so a recovery is
     * visible sooner than a full {@code UP} cache window would allow. Default 15s — a quarter of the
     * default {@link #ttl}, chosen so Docker's 30s-interval/3-retry healthcheck sees a re-probe on
     * every retry rather than serving the same stale {@code DOWN} for the whole 60s window. An
     * {@code UP} result is unaffected and keeps using {@link #ttl}.
     */
    @DurationMin(millis = 100)
    private Duration downTtl = Duration.ofSeconds(15);

    /**
     * Wall-clock budget for one round of probes. When {@code null} the indicator uses
     * {@code max(connectTimeout + socketTimeout) + 1s} — i.e. one provider's worst case plus a
     * fixed scheduling slack, since probes run in parallel.
     */
    private Duration overallTimeout;

    /** Max threads used to probe providers in parallel. Default 4. */
    private int probePoolSize = 4;

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration getDownTtl() {
        return downTtl;
    }

    public void setDownTtl(Duration downTtl) {
        this.downTtl = downTtl;
    }

    public Duration getOverallTimeout() {
        return overallTimeout;
    }

    public void setOverallTimeout(Duration overallTimeout) {
        this.overallTimeout = overallTimeout;
    }

    public int getProbePoolSize() {
        return probePoolSize;
    }

    public void setProbePoolSize(int probePoolSize) {
        this.probePoolSize = probePoolSize;
    }

    /**
     * Code review 2026-09-15 (M2): {@code down-ttl} caching longer than {@code ttl} would mean a
     * DOWN result outlives an UP one in cache — the opposite of AC4's "surface recovery sooner" goal.
     * Null-tolerant so {@link DurationMin} alone reports a missing/negative value; this only enforces
     * the relationship between two otherwise-valid durations.
     */
    @AssertTrue(message = "app.email.smtp.health.down-ttl must not exceed ttl")
    private boolean isDownTtlWithinTtl() {
        return ttl == null || downTtl == null || downTtl.compareTo(ttl) <= 0;
    }
}
