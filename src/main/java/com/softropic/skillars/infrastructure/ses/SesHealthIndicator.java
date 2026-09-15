package com.softropic.skillars.infrastructure.ses;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.GetAccountResponse;
import software.amazon.awssdk.services.sesv2.model.SendQuota;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Actuator health contributor for the SES v2 transport — story ses-1.3 AC1.
 *
 * <h2>Bean existence is transport-gated, and the health group is declared per profile</h2>
 *
 * This bean exists only under {@code app.email.transport=ses}, exactly as AC1 specifies. That works
 * because {@code management.endpoint.health.group.notification.include} is declared <strong>per
 * profile</strong> ({@code ses} in {@code application-prod.yaml}, {@code smtp} in dev/uat, and the
 * group is simply absent from the base {@code application.yaml}, whose {@code transport=log} has no
 * indicator at all). Spring replaces rather than merges collections across property sources, so each
 * profile's list names exactly the one indicator that profile's transport implies.
 *
 * <p>This shape matters: Spring Boot's {@code HealthEndpointGroupMembershipValidator} resolves every
 * name in an {@code include} list against actually-registered {@code HealthContributor} beans at
 * context refresh, and fails startup with {@code NoSuchHealthContributorException} on a miss. A
 * single profile-independent {@code include: smtp,ses} therefore cannot coexist with transport-gated
 * beans — the two must be declared together, per profile, or not at all. (The validator can be
 * disabled outright via {@code management.endpoint.health.validate-group-membership=false}, but that
 * would also stop protecting the unrelated {@code smoke} group from a typo, so the per-profile
 * declaration is preferred — code review 2026-09-12.)
 *
 * <h2>UP requires all three signals, and an unreadable signal is never UP</h2>
 *
 * {@code sendingEnabled}, {@code productionAccessEnabled}, and a readable {@code enforcementStatus}
 * that is not {@code SHUTDOWN} must all hold. {@code sendingEnabled} alone is <strong>not</strong>
 * sandbox/suspension detection — a sandboxed account still reports {@code sendingEnabled=true} (the
 * SES sandbox restricts recipients, not the sending switch itself); {@code productionAccessEnabled}
 * is SES's actual sandbox signal and {@code enforcementStatus} (HEALTHY/PROBATION/SHUTDOWN) is the
 * actual suspension signal.
 *
 * <p>Every accessor read here is a boxed, nullable type on the SDK model, so a partial
 * {@code GetAccount} response (a real shape — e.g. a hand-rolled WireMock stub) must degrade rather
 * than throw. Two rules follow, and they point the same way:
 * <ul>
 *   <li><strong>Decisions:</strong> {@code Boolean.TRUE.equals(...)} and an explicit null check on
 *       {@code enforcementStatus} — absent means "could not read the account state", which is never
 *       grounds to report UP. All three signals share this polarity.</li>
 *   <li><strong>Details:</strong> written through {@link #addDetailIfPresent} rather than directly.
 *       {@code Health.Builder.withDetail} asserts its value is non-null, so passing a raw nullable
 *       SDK member throws {@code IllegalArgumentException} — which is not an {@code SdkException},
 *       escapes the catch below, and (worse) skips the {@code cache.set(...)} that follows, leaving
 *       the TTL cache permanently unpopulated so every scrape re-issues a live AWS call
 *       (code review 2026-09-12).</li>
 * </ul>
 *
 * <p>A failure to reach AWS never escapes {@link #doHealthCheck}: an {@link SdkException} is caught
 * here (mirroring {@link SesEmailSender}'s own catch scope — a client-side timeout or DNS failure is
 * just as much "can't send" as an explicit account block) so this class controls the exact detail
 * shape below, rather than a raw stack trace ending up in the health payload. This is not about
 * protecting sibling contributors: {@code AbstractHealthIndicator.health()} already wraps
 * {@code doHealthCheck} in its own {@code try/catch (Exception)} and degrades only this one
 * contributor.
 *
 * <p><strong>TTL-cached</strong>, mirroring — in structure only — the double-checked-locking
 * {@code AtomicReference<Cached>} pattern this project's other transport health indicator already
 * uses. Deliberately simpler here: there is exactly one account to check, not N providers, so there
 * is no parallel-probe thread pool. skillars-deferred-111 AC4 (owner decision) gave this indicator
 * the same {@link SesHealthProperties} config surface {@code SmtpHealthProperties} already had,
 * replacing what used to be a single hardcoded {@code CACHE_TTL} constant: a freshly-computed
 * {@code DOWN} result is now cached for {@link SesHealthProperties#getDownTtl()} (default 15s),
 * shorter than an {@code UP} result's {@link SesHealthProperties#getTtl()} (default 60s, matching
 * the old constant), so a recovery becomes visible sooner than a full 60s window would allow. The
 * {@code getAccount} call is bounded by {@code SesConfig}'s shared client-level
 * {@code apiCallTimeout(5s)}.
 *
 * <p><strong>Ops note:</strong> this indicator requires the {@code ses:GetAccount} IAM permission,
 * which is distinct from {@code ses:SendEmail} — see {@code docs/deployment/secrets-reference.md}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.email.transport", havingValue = "ses")
public class SesHealthIndicator extends AbstractHealthIndicator {

    private static final String ENFORCEMENT_STATUS_SHUTDOWN = "SHUTDOWN";

    private final SesV2Client sesV2Client;
    private final SesHealthProperties healthProperties;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    @Autowired
    public SesHealthIndicator(SesV2Client sesV2Client, SesHealthProperties healthProperties) {
        this.sesV2Client = sesV2Client;
        this.healthProperties = healthProperties;
    }

    /** Retains the pre-AC4 single-arg shape for the hermetic unit tests, with default tuning. */
    SesHealthIndicator(SesV2Client sesV2Client) {
        this(sesV2Client, new SesHealthProperties());
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Cached current = cache.get();
        long now = System.nanoTime();
        if (current != null && now - current.computedAtNanos() < current.appliedTtlNanos()) {
            copyInto(builder, current.health());
            return;
        }
        synchronized (cache) {
            // Double-check after acquiring the lock: another thread may have just refreshed the cache.
            current = cache.get();
            now = System.nanoTime();
            if (current != null && now - current.computedAtNanos() < current.appliedTtlNanos()) {
                copyInto(builder, current.health());
                return;
            }
            Health fresh = computeHealth();
            cache.set(new Cached(fresh, System.nanoTime(), ttlFor(fresh)));
            copyInto(builder, fresh);
        }
    }

    /**
     * skillars-deferred-111 AC4 (owner decision): a freshly-computed {@code DOWN} result gets the
     * shorter {@link SesHealthProperties#getDownTtl()}; everything else uses {@link
     * SesHealthProperties#getTtl()}.
     *
     * <p><strong>Code review 2026-09-15 (M4):</strong> this previously claimed the {@code
     * SdkException} catch branch in {@link #computeHealth} used {@code getTtl()} — wrong. That branch
     * also returns {@code Health.down()}, so it is decided by the same {@code Status.DOWN} check
     * below and gets {@code getDownTtl()} like every other {@code DOWN} result.
     */
    private long ttlFor(Health health) {
        Duration ttl = Status.DOWN.equals(health.getStatus()) ? healthProperties.getDownTtl() : healthProperties.getTtl();
        return ttl.toNanos();
    }

    private static void copyInto(Health.Builder builder, Health source) {
        builder.status(source.getStatus());
        source.getDetails().forEach(builder::withDetail);
    }

    private Health computeHealth() {
        try {
            GetAccountResponse response = sesV2Client.getAccount(r -> { });

            String enforcementStatus = response.enforcementStatus();
            boolean sendingEnabled = Boolean.TRUE.equals(response.sendingEnabled());
            boolean productionAccessEnabled = Boolean.TRUE.equals(response.productionAccessEnabled());
            // Absent means "could not read the enforcement state" — same polarity as the two booleans
            // above, which an absent value also resolves to false. Compared case-insensitively: this
            // is a raw SDK String, not an enum constant.
            boolean notShutDown = enforcementStatus != null
                && !ENFORCEMENT_STATUS_SHUTDOWN.equalsIgnoreCase(enforcementStatus);
            boolean up = sendingEnabled && productionAccessEnabled && notShutDown;

            Health.Builder builder = up ? Health.up() : Health.down();
            addDetailIfPresent(builder, "sendingEnabled", response.sendingEnabled());
            addDetailIfPresent(builder, "productionAccessEnabled", response.productionAccessEnabled());
            addDetailIfPresent(builder, "enforcementStatus", enforcementStatus);

            SendQuota quota = response.sendQuota();
            if (quota != null) {
                addDetailIfPresent(builder, "sendQuota.max24HourSend", quota.max24HourSend());
                addDetailIfPresent(builder, "sendQuota.maxSendRate", quota.maxSendRate());
                addDetailIfPresent(builder, "sendQuota.sentLast24Hours", quota.sentLast24Hours());
            }
            return builder.build();
        } catch (SdkException ex) {
            log.warn("SES GetAccount health check failed: {}", ex.getMessage(), ex);
            // getMessage() is nullable on some SdkException subtypes; withDetail would reject null.
            String error = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            return Health.down().withDetail("error", error).build();
        }
    }

    /**
     * {@code Health.Builder.withDetail} does {@code Assert.notNull} on the value, so a nullable SDK
     * member must be filtered rather than passed through. An absent field is simply omitted from the
     * payload — the status itself is decided separately, above.
     */
    private static void addDetailIfPresent(Health.Builder builder, String key, Object value) {
        if (value != null) {
            builder.withDetail(key, value);
        }
    }

    /**
     * Aggregate {@link Health} plus the {@link System#nanoTime()} it was computed at, and the TTL (in
     * nanos) that applied to THIS result at write time — skillars-deferred-111 AC4: {@code DOWN} and
     * {@code UP} results can carry different TTLs, so the applicable one must travel with the cached
     * entry rather than being re-read from current config at check time.
     *
     * <p><strong>Monotonic clock (code review 2026-09-15, M3):</strong> {@link System#nanoTime()}, not
     * {@link System#currentTimeMillis()} — the latter is wall-clock and can step backward (NTP
     * correction, manual adjustment), which would make {@code now - computedAtNanos} negative and the
     * TTL check pass indefinitely, serving a stale {@code DOWN} result forever and defeating AC4's
     * entire point of surfacing recovery sooner.
     */
    private record Cached(Health health, long computedAtNanos, long appliedTtlNanos) {
    }
}
