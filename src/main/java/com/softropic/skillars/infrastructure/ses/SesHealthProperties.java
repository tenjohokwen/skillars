package com.softropic.skillars.infrastructure.ses;

import jakarta.validation.constraints.AssertTrue;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * skillars-deferred-111 AC4 (owner decision) — tuning for {@link SesHealthIndicator}'s TTL cache,
 * mirroring the SMTP transport's own equivalent {@code app.email.smtp.health} config shape (bound
 * instead under {@code app.ses.health}, since {@link SesProperties} already owns {@code app.ses}).
 * Before this class, {@code SesHealthIndicator.CACHE_TTL} was a hardcoded 60s constant shared by
 * both {@code UP} and {@code DOWN} results, with no config surface at all — pulled out here so both
 * transport health indicators can be tuned the same way, and so a {@code DOWN} result can use a
 * shorter TTL than an {@code UP} one for faster recovery visibility.
 *
 * <p>Passive value holder, registered via {@code @EnableConfigurationProperties} in {@link
 * SesConfig}.
 *
 * <p><strong>Validated (code review 2026-09-15, M2):</strong> see {@code SmtpHealthProperties}'s
 * equivalent javadoc — an unvalidated {@code down-ttl: 0s} defeats TTL caching entirely, and an
 * unvalidated {@code down-ttl > ttl} inverts AC4's intent.
 */
@Validated
@ConfigurationProperties(prefix = "app.ses.health")
public class SesHealthProperties {

    /** Aggregate {@code Health} is recomputed at most once per this window. Default 60s. */
    @DurationMin(millis = 100)
    private Duration ttl = Duration.ofSeconds(60);

    /**
     * How long a freshly-computed {@code DOWN} result is served from cache before the next scrape
     * re-probes, shorter than {@link #ttl} for the same reason {@code SmtpHealthProperties.downTtl}
     * exists. Default 15s.
     */
    @DurationMin(millis = 100)
    private Duration downTtl = Duration.ofSeconds(15);

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

    /** Code review 2026-09-15 (M2) — see {@code SmtpHealthProperties}'s equivalent constraint. */
    @AssertTrue(message = "app.ses.health.down-ttl must not exceed ttl")
    private boolean isDownTtlWithinTtl() {
        return ttl == null || downTtl == null || downTtl.compareTo(ttl) <= 0;
    }
}
