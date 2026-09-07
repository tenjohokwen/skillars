package com.softropic.skillars.platform.notification.contract;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * skillars-deferred-99 AC5 — tuning for {@code SmtpHealthIndicator}: how long an aggregate result is
 * served from cache, and the overall wall-clock budget for one round of parallel per-provider probes.
 *
 * <p>Passive value holder (contract layer). Registered via {@code @EnableConfigurationProperties} in
 * {@code notification.config.ComponentConfig}.
 */
@ConfigurationProperties(prefix = "app.notification.smtp-health")
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
