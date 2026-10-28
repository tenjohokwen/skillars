package com.softropic.skillars.platform.config.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.config.config.ConfigProperties;
import com.softropic.skillars.platform.config.contract.AdminConfigEntryDto;
import com.softropic.skillars.platform.config.contract.ConfigValueResponse;
import com.softropic.skillars.platform.config.contract.ConfigValueType;
import com.softropic.skillars.platform.config.repo.PlatformConfig;
import com.softropic.skillars.platform.config.repo.PlatformConfigRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class ConfigService {

    private static final String MISCONFIGURED_COUNTER = "config.value.misconfigured";

    // skillars-deferred-149 AC5: kept in sync with
    // ConfigStartupAssertion.MIN_REVIEW_ELIGIBILITY_WINDOW_GAP_DAYS — both guards read the same
    // stored config independently and must agree on the rule they fail-fast/reject on.
    private static final long MIN_REVIEW_ELIGIBILITY_WINDOW_GAP_DAYS = 7;

    private final PlatformConfigRepository configRepository;
    private final ConfigProperties configProperties;
    private final ConfigMapper configMapper;
    private final MeterRegistry meterRegistry;

    // skillars-deferred-163 AC1.4: a single volatile reference to an immutable map, swapped
    // atomically by load() — no reader can ever observe a cross-key mix of old and new values.
    private volatile Map<String, PlatformConfig> cache = Map.of();
    private volatile Instant lastRefreshed = Instant.MIN;

    // skillars-deferred-161 AC6: refresh bookkeeping. invalidate() bumps invalidationEpoch; a load stamps
    // loadedEpoch with the epoch it observed BEFORE reading the DB, so an invalidate() that lands mid-load
    // leaves loadedEpoch < invalidationEpoch and the next reader reloads (the old `lastRefreshed = now()`
    // after findAll() silently swallowed it for a full TTL).
    private final AtomicLong invalidationEpoch = new AtomicLong();
    private volatile long loadedEpoch = -1;
    private final ReentrantLock refreshLock = new ReentrantLock();

    // skillars-deferred-163 AC1.1: bound on how long a must-be-fresh reader waits on an in-flight
    // load before giving up on that attempt — a fraction of Hikari's own connection-timeout
    // (application.yaml:187, 30000ms), not equal to or longer than it.
    private static final Duration MUST_BE_FRESH_LOCK_TIMEOUT = Duration.ofMillis(4_000);
    // How recent a recorded must-be-fresh failure must be for a timed-out waiter to rethrow it
    // instead of making its own bounded attempt — deliberately wider than the lock timeout above so
    // a waiter that just finished waiting out a failed leader's attempt still treats it as "recent".
    private static final Duration MUST_BE_FRESH_FAILURE_BACKOFF = Duration.ofMillis(15_000);

    private record RefreshFailure(RuntimeException cause, Instant failedAt) {}

    // skillars-deferred-163 AC1.1: populated by whichever thread's must-be-fresh load fails, so a
    // waiter whose own bounded tryLock times out can rethrow the same failure instead of every
    // waiter running its own sequential findAll() once the lock frees up (N waiters would otherwise
    // mean up to N sequential Hikari connection-timeout waits).
    private volatile RefreshFailure lastMustBeFreshFailure;

    public ConfigService(PlatformConfigRepository configRepository,
                         ConfigProperties configProperties,
                         ConfigMapper configMapper,
                         MeterRegistry meterRegistry) {
        this.configRepository = configRepository;
        this.configProperties = configProperties;
        this.configMapper = configMapper;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        refreshCache();
    }

    /**
     * skillars-deferred-163 AC1.2: non-blocking by construction — tries the lock once and skips
     * (logs) the tick if a refresh is already in flight, rather than parking one of this pool's 8
     * shared {@code @Scheduled} threads (application.yaml:117-119) behind a concurrent
     * must-be-fresh load.
     */
    @Scheduled(fixedDelayString = "${app.config.cache-ttl-seconds:300}", timeUnit = TimeUnit.SECONDS)
    public void scheduledRefresh() {
        if (!isStale()) {
            return;
        }
        if (!refreshLock.tryLock()) {
            log.debug("Platform config refresh tick skipped; a refresh is already in flight");
            return;
        }
        try {
            runLoadIfStale(mustBeFresh());
        } finally {
            refreshLock.unlock();
        }
    }

    public String getString(String key) {
        ensureFresh();
        PlatformConfig entry = cache.get(key);
        if (entry == null) {
            throw new IllegalStateException("Missing platform config key: " + key);
        }
        return entry.getValue();
    }

    public long getLong(String key) {
        String raw = getString(key);
        try {
            // trim() to match getLong(key, default) and ConfigStartupAssertion — a whitespace-padded
            // value must not pass boot validation and then throw here at every bounded call site
            // (skillars-deferred-107 code review).
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Config key '" + key + "' is not a valid long: " + raw);
        }
    }

    public long getLong(String key, long defaultValue) {
        return find(key)
            .filter(v -> !v.isBlank())
            .map(v -> {
                try { return Long.parseLong(v.trim()); }
                catch (NumberFormatException e) {
                    log.warn("Config key '{}' has non-numeric value '{}' — using default {}", key, v, defaultValue);
                    return defaultValue;
                }
            })
            .orElseGet(() -> {
                log.warn("Config key '{}' is absent or blank — using default {}", key, defaultValue);
                return defaultValue;
            });
    }

    public int getInt(String key, int defaultValue) {
        return (int) getLong(key, defaultValue);
    }

    /**
     * Like {@link #getLong(String, long)}, but also rejects values outside [min, max],
     * falling back to {@code defaultValue} with a WARN log. Use for config keys where a
     * syntactically valid but out-of-range number (negative, zero, or absurdly large) would
     * silently corrupt scheduler or business-rule behaviour.
     */
    public long getBoundedLong(String key, long defaultValue, long min, long max) {
        long value = getLong(key, defaultValue);
        if (value < min || value > max) {
            log.warn("Config key '{}' has out-of-range value {} (expected [{}, {}]) — using default {}",
                key, value, min, max, defaultValue);
            return defaultValue;
        }
        return value;
    }

    /**
     * No-default bounded read for the 1-arg {@link #getLong(String)} call sites — a <em>missing</em>
     * key still throws {@link IllegalStateException} (the intended "operator must set this" contract),
     * but a present-yet-out-of-range value is <strong>clamped to the nearest bound</strong> with a
     * WARN rather than flowing through. Clamp, not throw: fail-fast-on-bad-value is
     * {@code ConfigStartupAssertion}'s job (one decision per key), not an accident of whether a
     * given call site happens to pass a default.
     */
    public long getBoundedLong(String key, long min, long max) {
        long value = getLong(key);
        if (value < min) {
            log.warn("Config key '{}' has out-of-range value {} (expected [{}, {}]) — clamping to {}",
                key, value, min, max, min);
            return min;
        }
        if (value > max) {
            log.warn("Config key '{}' has out-of-range value {} (expected [{}, {}]) — clamping to {}",
                key, value, min, max, max);
            return max;
        }
        return value;
    }

    /**
     * {@code int} counterpart of {@link #getBoundedLong(String, long, long, long)} — mirrors the
     * existing {@link #getInt(String, int)} narrowing. Out-of-range (or non-numeric / absent) values
     * fall back to {@code defaultValue} with a WARN.
     */
    public int getBoundedInt(String key, int defaultValue, int min, int max) {
        return (int) getBoundedLong(key, defaultValue, min, max);
    }

    public boolean getBoolean(String key) {
        return find(key)
            .map(v -> parseBoolean(key, v, ""))
            .orElseGet(() -> {
                log.warn("Feature gate config key '{}' not found in platform config; defaulting to false", key);
                Counter.builder(MISCONFIGURED_COUNTER)
                    .tag("key", key)
                    .tag("reason", "missing")
                    .register(meterRegistry)
                    .increment();
                return false;
            });
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return find(key)
            .map(v -> parseBoolean(key, v, " (requested default " + defaultValue + " not used)"))
            .orElse(defaultValue);
    }

    /**
     * Shared by both {@code getBoolean} overloads: warns once when a present value is not
     * (case-insensitively) {@code "true"} or {@code "false"}, then returns the same {@code false}
     * result either overload already returned for such a value. {@code logSuffix} lets the
     * 2-arg overload additionally name the ignored default in its warning.
     */
    private boolean parseBoolean(String key, String value, String logSuffix) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            log.warn("Config key '{}' has non-boolean value '{}' — treating as false{}", key, value, logSuffix);
            Counter.builder(MISCONFIGURED_COUNTER)
                .tag("key", key)
                .tag("reason", "non_boolean")
                .register(meterRegistry)
                .increment();
        }
        return "true".equalsIgnoreCase(value);
    }

    public Optional<String> find(String key) {
        ensureFresh();
        return Optional.ofNullable(cache.get(key)).map(PlatformConfig::getValue);
    }

    public ConfigValueResponse findResponse(String key) {
        ensureFresh();
        PlatformConfig entry = cache.get(key);
        if (entry == null) {
            throw new ResourceNotFoundException("ConfigEntry", key);
        }
        return configMapper.toResponse(entry);
    }

    /**
     * Admin config editor (FR-ADM-006): every stored {@code platform_config} row plus every
     * {@link ConfigBounds#HAS_CODE_DEFAULT} key that has no row yet (those are writable through
     * {@link #updateConfig}'s upsert path, so the editor must be able to reach them), each annotated
     * with the {@link ConfigBounds} range {@link #updateConfig} enforces. Sorted by key.
     *
     * <p>Reads the table directly rather than the cache: this is an operator view, and showing the
     * committed value (not a possibly TTL-stale snapshot from this node) is the point of it. Read-only,
     * low-frequency and admin-only; the table holds a few hundred rows at most.
     */
    public List<AdminConfigEntryDto> listForAdmin() {
        Map<String, ConfigBounds.BoundedKey> bounds = new HashMap<>();
        ConfigBounds.ALL.forEach(b -> bounds.put(b.key(), b));

        List<AdminConfigEntryDto> result = new ArrayList<>();
        Set<String> storedKeys = new HashSet<>();
        for (PlatformConfig pc : configRepository.findAll()) {
            storedKeys.add(pc.getKey());
            result.add(toAdminEntry(pc.getKey(), pc.getValue(),
                pc.getValueType() != null ? pc.getValueType().name() : null,
                pc.getDescription(), pc.getUpdatedAt(), true, bounds.get(pc.getKey())));
        }
        for (String key : ConfigBounds.HAS_CODE_DEFAULT) {
            if (!storedKeys.contains(key)) {
                result.add(toAdminEntry(key, null, ConfigValueType.LONG.name(), null, null, false,
                    bounds.get(key)));
            }
        }
        result.sort(Comparator.comparing(AdminConfigEntryDto::key));
        return result;
    }

    private static AdminConfigEntryDto toAdminEntry(String key, String value, String valueType,
                                                    String description, Instant updatedAt, boolean stored,
                                                    ConfigBounds.BoundedKey bound) {
        Long codeDefault = bound != null && ConfigBounds.HAS_CODE_DEFAULT.contains(key)
            ? bound.defaultValue() : null;
        return new AdminConfigEntryDto(key, value, valueType, description, updatedAt,
            bound != null ? bound.min() : null,
            bound != null ? bound.max() : null,
            bound != null ? bound.note() : null,
            bound != null && bound.failFast(),
            stored,
            codeDefault);
    }

    /**
     * skillars-deferred-127 AC2: a {@link ConfigBounds#HAS_CODE_DEFAULT} key with no existing
     * {@code platform_config} row is upserted (a new row is created) rather than 404ing — every
     * {@code HAS_CODE_DEFAULT} key's own Javadoc documents "an operator can widen this at runtime" as
     * its {@code max}-bound rationale, which this write path previously contradicted for the 4 of
     * those 17 keys that {@code V139__baseline_seed_data.sql} never seeded. A key that is absent AND
     * not in {@code HAS_CODE_DEFAULT} (a {@code failFast} key, or simply unrecognized) keeps the
     * existing 404 behavior exactly — this does not widen the write surface to arbitrary unknown keys.
     */
    public ConfigValueResponse updateConfig(String key, String newValue) {
        Optional<PlatformConfig> existing = configRepository.findByKey(key);
        if (existing.isEmpty() && !ConfigBounds.HAS_CODE_DEFAULT.contains(key)) {
            // Preserves the original 404-before-range-check ordering for a key that is neither
            // present nor upsertable — matching this method's pre-AC2 behavior exactly.
            throw new ResourceNotFoundException("ConfigEntry", key);
        }
        rejectOutOfRange(key, newValue);
        rejectReviewEligibilityWindowOrdering(key, newValue);

        if (existing.isPresent()) {
            PlatformConfig entity = existing.get();
            entity.setValue(newValue);
            entity.setUpdatedAt(Instant.now());
            configRepository.save(entity);
            invalidate();
            return configMapper.toResponse(entity);
        }

        PlatformConfig entity = createOrReadBack(key, newValue);
        invalidate();
        return configMapper.toResponse(entity);
    }

    /**
     * skillars-deferred-127 code review (2026-09-21, owner decision taken live via
     * {@code AskUserQuestion}): this method is a read-then-insert TOCTOU with no
     * {@code @Transactional} — two concurrent first-ever writes for the same unseeded {@code
     * HAS_CODE_DEFAULT} key can both observe {@link #updateConfig}'s own {@code
     * configRepository.findByKey(key)} as empty and both attempt to create the row here; only one
     * {@code INSERT} can win against {@code uq_platform_config_key} (
     * {@code V138__baseline_schema.sql}). Rather than let the loser's constraint violation surface as
     * a raw {@code DataIntegrityViolationException} → HTTP 500, catch it and re-read the winner's
     * now-committed row, applying this call's own requested value to it instead — turning the race
     * into correct last-writer-wins semantics matching every other concurrent-write path in this
     * codebase, rather than a 500 with the request's value silently dropped.
     */
    private PlatformConfig createOrReadBack(String key, String newValue) {
        PlatformConfig created = PlatformConfig.builder()
            .key(key)
            .value(newValue)
            .valueType(ConfigValueType.LONG)
            .updatedAt(Instant.now())
            .build();
        try {
            configRepository.save(created);
            return created;
        } catch (DataIntegrityViolationException e) {
            PlatformConfig winner = configRepository.findByKey(key)
                .orElseThrow(() -> e);
            winner.setValue(newValue);
            winner.setUpdatedAt(Instant.now());
            configRepository.save(winner);
            return winner;
        }
    }

    /**
     * skillars-deferred-107 code review: the read-side clamp and {@code ConfigStartupAssertion}
     * only ever saw values that were bad <em>at the last restart</em> — the {@code PUT /api/config}
     * path operators actually use wrote the raw string with no check. For a key that
     * {@link ConfigBounds} bounds, reject a non-numeric or out-of-range write with 400 rather than
     * let it go live on this node before the response returns.
     */
    private void rejectOutOfRange(String key, String newValue) {
        ConfigBounds.ALL.stream()
            .filter(b -> b.key().equals(key))
            .findFirst()
            .ifPresent(b -> {
                long value;
                try {
                    value = Long.parseLong(newValue.trim());
                } catch (NumberFormatException e) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Config key '" + key + "' must be an integer in range [" + b.min() + ", " + b.max() + "]");
                }
                if (value < b.min() || value > b.max()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Config key '" + key + "' = " + value + " is outside the required range ["
                            + b.min() + ", " + b.max() + "] — " + b.note());
                }
            });
    }

    /**
     * skillars-deferred-145 code review (D3, 2026-10-06): PUT-time half of the cross-field ordering
     * guard — {@code ConfigStartupAssertion} only catches a bad combination at the next restart, and
     * {@link #rejectOutOfRange} above only ever sees the single key being written, never the pair's
     * relationship. Mirrors the boot check's own invariant exactly (reviews.updateCooldownDays must
     * be at least {@link #MIN_REVIEW_ELIGIBILITY_WINDOW_GAP_DAYS} days greater than
     * reviews.minSessionAgeDays, or the maturity floor silently supersedes the cooldown), reading the
     * OTHER key's current effective value the same way every call site does.
     *
     * <p>skillars-deferred-149 AC5: widened from strict ordering (gap &gt; 0) to a minimum 7-day
     * gap — every pair the old condition rejected (gap &lt;= 0) is a subset of every pair this one
     * rejects (gap &lt; 7), so this replaces the old condition without losing any coverage.
     */
    private void rejectReviewEligibilityWindowOrdering(String key, String newValue) {
        boolean isMinAge = ConfigBounds.REVIEWS_MIN_SESSION_AGE_DAYS.key().equals(key);
        boolean isCooldown = ConfigBounds.REVIEWS_UPDATE_COOLDOWN_DAYS.key().equals(key);
        if (!isMinAge && !isCooldown) {
            return;
        }
        long newValueParsed;
        try {
            newValueParsed = Long.parseLong(newValue.trim());
        } catch (NumberFormatException e) {
            return; // already rejected by rejectOutOfRange above
        }
        long minSessionAgeDays = isMinAge ? newValueParsed
            : readStoredBoundedInt(ConfigBounds.REVIEWS_MIN_SESSION_AGE_DAYS);
        long updateCooldownDays = isCooldown ? newValueParsed
            : readStoredBoundedInt(ConfigBounds.REVIEWS_UPDATE_COOLDOWN_DAYS);
        if (updateCooldownDays - minSessionAgeDays < MIN_REVIEW_ELIGIBILITY_WINDOW_GAP_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "reviews.minSessionAgeDays (" + minSessionAgeDays + ") must be at least "
                    + MIN_REVIEW_ELIGIBILITY_WINDOW_GAP_DAYS + " days less than reviews.updateCooldownDays ("
                    + updateCooldownDays + ") — the cooldown would be silently superseded by the "
                    + "maturity floor");
        }
    }

    /**
     * Round-2 code review (R2): the cross-key guard above MUST NOT read the other key through
     * {@link #find} / {@link #getBoundedInt}. Those go via {@link #ensureFresh()}, which only
     * refreshes after {@code app.config.cache-ttl-seconds} (300), and {@link #invalidate()} clears
     * only the cache of the JVM that handled the write — {@code ConfigResource}'s own javadoc states
     * this explicitly ("a load-balanced retry can land on a different node each time"). With a cached
     * read, two sequential {@code PUT}s landing on different nodes inside the TTL each see a stale
     * partner value and each return 200, together committing a pair that violates the invariant;
     * nothing re-checks it at runtime and {@code ConfigStartupAssertion} then refuses to boot outside
     * the {@code dev} profile, so an accepted admin write silently arms a startup failure. Reading the
     * row directly closes that window. Falls back to the key's registry default exactly as the 4-arg
     * {@code getBoundedInt} would for an absent, blank, non-numeric or out-of-range stored value, so
     * this guard and the boot assertion still agree on every such case.
     */
    private long readStoredBoundedInt(ConfigBounds.BoundedKey boundedKey) {
        return configRepository.findByKey(boundedKey.key())
            .map(PlatformConfig::getValue)
            .filter(v -> v != null && !v.isBlank())
            .map(v -> {
                try {
                    long parsed = Long.parseLong(v.trim());
                    return (parsed < boundedKey.min() || parsed > boundedKey.max())
                        ? boundedKey.defaultValue() : parsed;
                } catch (NumberFormatException e) {
                    return boundedKey.defaultValue();
                }
            })
            .orElse(boundedKey.defaultValue());
    }

    /**
     * skillars-deferred-163 AC1.3: if a transaction is active, defers the invalidation to after it
     * commits — following {@code EmailRetryScheduler.dispatchAfterCommit}'s pattern, not
     * {@code OutboxService}'s unrelated {@code afterCompletion}-based dedup unbind — so a caller
     * whose write later rolls back never invalidates a cache that still reflects the committed
     * truth. This is defensive/future-proofing: {@link #updateConfig} (this method's only current
     * production caller) is not {@code @Transactional} today, so the immediate-invalidate path below
     * is what actually runs in production right now.
     */
    public void invalidate() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidateNow();
                }
            });
        } else {
            invalidateNow();
        }
    }

    private void invalidateNow() {
        invalidationEpoch.incrementAndGet();
    }

    private void ensureFresh() {
        if (isStale()) {
            refreshCache();
        }
    }

    private boolean isStale() {
        return Duration.between(lastRefreshed, Instant.now()).toSeconds() >= configProperties.getCacheTtlSeconds()
            || loadedEpoch < invalidationEpoch.get();
    }

    /**
     * Single-flight refresh that never makes a TTL-expiry reader wait on another thread's DB read.
     *
     * <p>skillars-deferred-161 AC6: this used to be {@code synchronized} around {@code findAll()}, so one thread
     * waiting for a Hikari connection stalled every other {@code ConfigService} caller in the app. Now:
     * <ul>
     *   <li>exactly one thread loads at a time ({@code refreshLock.tryLock()}), others do not run a second
     *       {@code findAll()};</li>
     *   <li>a reader that is stale only because the TTL expired, while a load is in flight, serves the
     *       already-cached (never empty) snapshot immediately instead of waiting;</li>
     *   <li>a reader after {@link #invalidate()}, or one on a cache that has never loaded, must see fresh data
     *       and so waits for the lock, then re-checks (a load that began before the invalidate does not count);</li>
     *   <li>a load that fails while the staleness is TTL-only is logged at WARN and the cached snapshot is
     *       served, exactly as the concurrent TTL-only readers are; {@code lastRefreshed} is left unchanged so
     *       the next reader retries. A must-be-fresh load failure still propagates. (If the caller is inside a
     *       transaction, the repository's own interceptor has already marked it rollback-only; this only
     *       spares non-transactional callers.)</li>
     *   <li>the load is deliberately <em>not</em> run in {@code REQUIRES_NEW}: callers already inside a
     *       transaction would hold two connections each.</li>
     * </ul>
     */
    private void refreshCache() {
        // Code review 2026-10-27: the failure (if any) already on record when this reader arrived. A
        // DIFFERENT one recorded later can only be the attempt this reader just waited out — see below.
        RefreshFailure failureAtEntry = lastMustBeFreshFailure;
        while (isStale()) {
            boolean mustBeFreshNow = mustBeFresh();

            if (!mustBeFreshNow) {
                if (refreshLock.tryLock()) {
                    try {
                        runLoadIfStale(false);
                    } finally {
                        refreshLock.unlock();
                    }
                }
                return; // TTL-only staleness: serve the (never-empty) cached snapshot either way
            }

            boolean acquired;
            try {
                acquired = refreshLock.tryLock(MUST_BE_FRESH_LOCK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConfigRefreshException("Interrupted while waiting for a platform config refresh", e);
            }
            if (acquired) {
                try {
                    // Code review 2026-10-27 (AC1.1): a waiter that ACQUIRES the lock right after the
                    // leader's must-be-fresh load failed must not run its own findAll() — in an outage that
                    // is exactly the N-sequential-Hikari-timeouts pile-up AC1.1 removes. It rethrows the
                    // failure it waited out instead. A reader arriving after that failure (failureAtEntry
                    // is the same record) still makes its own attempt, so recovery is never blocked.
                    RefreshFailure waitedOut = lastMustBeFreshFailure;
                    if (waitedOut != null && waitedOut != failureAtEntry && isStale()) {
                        throw new ConfigRefreshException(
                            "Platform config refresh failed while this reader waited; not retrying", waitedOut.cause());
                    }
                    runLoadIfStale(true);
                } finally {
                    refreshLock.unlock();
                }
                return;
            }

            // Bounded wait timed out while another thread holds the lock. Rather than let every
            // waiter run its own sequential findAll() once the lock frees up (N waiters would
            // otherwise mean up to N sequential Hikari connection-timeout waits), rethrow a
            // recently-recorded failure from the leader instead of attempting another DB call.
            RefreshFailure recorded = lastMustBeFreshFailure;
            if (recorded != null && Duration.between(recorded.failedAt(), Instant.now())
                    .compareTo(MUST_BE_FRESH_FAILURE_BACKOFF) <= 0) {
                throw new ConfigRefreshException("Platform config refresh recently failed; not retrying yet",
                    recorded.cause());
            }
            // No recent failure recorded: loop back for one more bounded attempt of our own.
        }
    }

    /** True after {@link #invalidate()} or before the first successful load: a cached snapshot is not acceptable. */
    private boolean mustBeFresh() {
        return loadedEpoch < invalidationEpoch.get() || lastRefreshed == Instant.MIN;
    }

    /**
     * Re-checks staleness under the lock (another thread may have just finished loading) and, if
     * still stale, loads. A must-be-fresh failure is recorded for waiters and rethrown; a TTL-only
     * failure is logged at WARN and swallowed, serving the stale cached snapshot.
     */
    private void runLoadIfStale(boolean mustBeFreshNow) {
        if (!isStale()) {
            return;
        }
        try {
            load();
            lastMustBeFreshFailure = null;
        } catch (RuntimeException e) {
            if (!mustBeFreshNow) {
                log.warn("Platform config cache refresh failed; serving the cached snapshot", e);
                return;
            }
            lastMustBeFreshFailure = new RefreshFailure(e, Instant.now());
            throw e;
        }
    }

    private void load() {
        long epochAtStart = invalidationEpoch.get();
        List<PlatformConfig> all = configRepository.findAll();
        Map<String, PlatformConfig> newData = new HashMap<>(all.size());
        all.forEach(pc -> newData.put(pc.getKey(), pc));
        // skillars-deferred-163 AC1.4: one atomic reference swap to an immutable map — no reader
        // can ever observe a cross-key mix of old and new values mid-load.
        cache = Map.copyOf(newData);
        loadedEpoch = epochAtStart;
        lastRefreshed = Instant.now();
        log.debug("Platform config cache refreshed: {} entries", cache.size());
    }
}
