package com.softropic.skillars.infrastructure.email.smtp;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
 */
@ConfigurationProperties(prefix = "app.email.smtp.health")
public class SmtpHealthProperties {

    /** Aggregate {@code Health} is recomputed at most once per this window. Default 60s. */
    private Duration ttl = Duration.ofSeconds(60);

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
}
