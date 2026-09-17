package com.softropic.skillars.infrastructure.security;

import com.softropic.skillars.platform.config.service.ConfigService;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for managing rate limit buckets using Bucket4j.
 *
 * <p><strong>skillars-deferred-117 AC4:</strong> every distinct {@code limitKey:identifier} pair
 * (an IP address for {@code @RateLimited}-annotated endpoints, or a numeric user id for the direct
 * {@code tryConsume} call sites) gets its own entry in {@link #buckets}, and nothing previously ever
 * removed one — an unbounded, monotonically-growing map for the lifetime of the JVM process. Each
 * entry now also carries a {@code lastAccess} timestamp, swept on a schedule by
 * {@link #evictIdleBuckets()}: an idle-past-TTL bucket is evicted and, if the client is seen again,
 * simply recreated fresh — behaviorally identical to leaving it in place, since every {@code Bucket4j}
 * bucket fully refills once its own configured {@code duration} elapses (the TTL default is chosen
 * with wide margin above the longest {@code duration} in use anywhere in this codebase today, 60
 * minutes — see {@code ConfigBounds.RATE_LIMIT_BUCKET_TTL_HOURS}).
 *
 * <p><strong>Deliberately out of scope:</strong> this fix does not make rate limiting cluster-safe.
 * The map is per-JVM-instance; a deployment running more than one {@code app} instance would let each
 * instance enforce its own independent limit against the same client. This deployment runs a single
 * instance today (confirmed via the {@code docker-compose} service stack) — revisit only if/when the
 * app is horizontally scaled. The eviction sweep added here fixes a distinct, separate problem (an
 * unbounded in-process memory leak) and does not change that limitation either way.
 */
@Slf4j
@Service
public class RateLimitingService {

    private final ConfigService configService;

    /**
     * Package-private (not final) so {@code RateLimitingServiceTest} can inject a fixed/advancing
     * clock without a real sleep. {@code volatile} for the same request-thread/scheduler-thread
     * visibility reason as {@link BucketEntry#lastAccessMillis} — in production this is written once
     * at construction and never reassigned, so the risk is purely theoretical there, but the test
     * harness reassigns it from the test thread while {@link #tryConsume}/{@link #sweepIdleBuckets}
     * could in principle run concurrently (code review).
     */
    volatile Clock clock = Clock.systemUTC();

    private final Map<String, BucketEntry> buckets = new ConcurrentHashMap<>();

    public RateLimitingService(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * One rate-limit bucket plus the wall-clock time it was last accessed. {@code lastAccessMillis}
     * is an {@link AtomicLong}, not a bare {@code long} — the request thread that updates it on every
     * {@link #tryConsume} call and the scheduler thread that reads it during {@link #evictIdleBuckets}
     * are different threads, and a bare {@code long} risks a compiler-reordering/visibility surprise
     * across that boundary (pre-implementation quality review).
     */
    private static final class BucketEntry {
        private final Bucket bucket;
        private final AtomicLong lastAccessMillis;

        BucketEntry(Bucket bucket, long nowMillis) {
            this.bucket = bucket;
            this.lastAccessMillis = new AtomicLong(nowMillis);
        }
    }

    /**
     * Attempts to consume a token from the bucket associated with the given identifier.
     *
     * @param identifier unique identifier for the client (e.g., IP address)
     * @param limitKey   unique key for the rate limit type (e.g., "registration")
     * @param capacity   maximum tokens in the bucket
     * @param duration   duration for tokens refill
     * @param unit       time unit for duration
     * @return true if a token was consumed, false if rate limit was exceeded
     */
    public boolean tryConsume(String identifier, String limitKey, long capacity, long duration, TimeUnit unit) {
        String bucketKey = limitKey + ":" + identifier;
        long now = clock.millis();
        BucketEntry entry = buckets.computeIfAbsent(bucketKey, k -> new BucketEntry(createBucket(capacity, duration, unit), now));
        entry.lastAccessMillis.set(now);
        return entry.bucket.tryConsume(1);
    }

    private Bucket createBucket(long capacity, long duration, TimeUnit unit) {
        Refill refill = Refill.intervally(capacity, Duration.of(duration, unit.toChronoUnit()));
        Bandwidth limit = Bandwidth.classic(capacity, refill);
        return Bucket.builder()
                .addLimit(limit)
                .build();
    }

    /**
     * skillars-deferred-117 AC4: evicts every bucket whose {@code lastAccess} is older than the
     * configured TTL. Runs hourly — frequent enough that a long-running process never accumulates an
     * unbounded backlog of idle entries between sweeps, cheap enough (a single map scan) that hourly
     * cadence costs nothing worth tuning further.
     */
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    public void evictIdleBuckets() {
        sweepIdleBuckets();
    }

    /**
     * The sweep itself, exposed package-private so {@code RateLimitingServiceTest} can invoke it
     * directly (with an injected {@link #clock}) rather than waiting on the real {@code @Scheduled}
     * cadence or sleeping real wall-clock time.
     */
    void sweepIdleBuckets() {
        long ttlHours = configService.getBoundedLong(
            "security.rate_limiting.bucket_ttl_hours", 24L, 1L, 8760L);
        long cutoff = clock.millis() - Duration.ofHours(ttlHours).toMillis();
        // Code review: a plain `entrySet().removeIf(e -> e.getValue().lastAccessMillis.get() < cutoff)`
        // reads lastAccessMillis once to decide, then unconditionally removes the entry — a concurrent
        // tryConsume() that refreshes the SAME key's lastAccessMillis between that read and the actual
        // removal would still get evicted. computeIfPresent's remapping function re-reads
        // lastAccessMillis atomically with the removal decision, closing that window: a key touched
        // concurrently survives even if this sweep's outer iteration first observed it as idle.
        int evicted = 0;
        for (String key : buckets.keySet()) {
            boolean[] wasEvicted = {false};
            buckets.computeIfPresent(key, (k, entry) -> {
                if (entry.lastAccessMillis.get() < cutoff) {
                    wasEvicted[0] = true;
                    return null; // removes the mapping
                }
                return entry;
            });
            if (wasEvicted[0]) {
                evicted++;
            }
        }
        if (evicted > 0) {
            log.debug("[RATE_LIMIT_BUCKET_SWEEP] evicted {} idle bucket(s), {} remaining", evicted, buckets.size());
        }
    }
}
