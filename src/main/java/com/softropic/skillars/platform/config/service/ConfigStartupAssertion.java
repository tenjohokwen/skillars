package com.softropic.skillars.platform.config.service;

import com.softropic.skillars.infrastructure.exception.AppSetupException;
import com.softropic.skillars.platform.config.service.ConfigBounds.BoundedKey;
import com.softropic.skillars.platform.payment.service.ReliabilityStrikeConfig;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Fail-fast (in non-{@code dev}) startup check that the numeric {@code platform_config} keys in
 * {@link ConfigBounds} hold values inside their required range — skillars-deferred-107 AC3.
 *
 * <p>{@code ConfigService.getBoundedLong(...)} already clamps an out-of-range value at read time, so
 * a fat-fingered {@code platform_config} row no longer silently disables a flow. But that clamp is
 * <em>invisible</em> to the operator who set the bad value. This assertion makes it visible: on
 * {@link ApplicationReadyEvent} it reads the <strong>actual stored</strong> values (raw
 * {@link ConfigService#find}, not the clamped accessor) and, for every {@link BoundedKey} out of
 * range, logs an ERROR and increments {@code config.value.misconfigured}. For the
 * {@link BoundedKey#failFast() failFast} subset — where a bad value causes data loss or halts a core
 * flow entirely — it then throws {@link AppSetupException}, refusing to boot until the operator
 * corrects the row.
 *
 * <p><strong>Deviation from {@code TlsStartupAssertion}:</strong> that assertion skips the whole
 * check under the {@code dev} profile because {@code checkCertificate:false} is a <em>legitimate</em>
 * dev setting. There is no legitimate reason for a {@code platform_config} value to be out of range
 * in any environment (the values are migration-seeded), so this assertion runs the ERROR + metric in
 * <strong>all</strong> profiles and gates only the boot-blocking {@code throw} to non-{@code dev} — a
 * local developer who hand-edits a row to an out-of-range value for a test sees the loud ERROR and
 * the metric, but is not blocked from booting.
 *
 * <p><strong>What blocks boot</strong> (non-{@code dev}, {@code failFast} key only): an out-of-range
 * value; a present-but-non-numeric value ({@code "abc"}); an absent/blank value <em>unless</em> the
 * key is in {@link ConfigBounds#HAS_CODE_DEFAULT} (a 2-arg call site whose code default covers the
 * gap). Everything else is ERROR + {@code config.value.misconfigured} metric only.
 *
 * <p><strong>Known limitation:</strong> {@code @Scheduled} tasks are registered during context
 * refresh, before {@link ApplicationReadyEvent}, so a scheduler can tick once against the
 * <em>clamped</em> (safe) value before this assertion refuses the boot. The read-time clamp is the
 * primary protection; this assertion is the "make the operator fix it" backstop.
 *
 * <p>Fires on {@link ApplicationReadyEvent} (after full wiring), matching {@code TlsStartupAssertion}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigStartupAssertion implements ApplicationListener<ApplicationReadyEvent> {

    private final ConfigService configService;
    private final MeterRegistry meterRegistry;
    private final Environment env;
    // skillars-deferred-123 code review 2026-09-18 (Decision 4): needed to read @SchedulerLock
    // annotations off the live beans, so the ordering check has no second source of truth to drift
    // from — see assertSchedulerLockOrdering's Javadoc.
    private final ApplicationContext applicationContext;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        boolean devProfile = Arrays.asList(env.getActiveProfiles()).contains("dev");

        List<String> failFastViolations = new ArrayList<>();
        int checked = 0;

        for (BoundedKey bk : ConfigBounds.ALL) {
            checked++;
            Optional<String> raw = configService.find(bk.key());

            if (raw.isEmpty() || raw.get().isBlank()) {
                if (ConfigBounds.HAS_CODE_DEFAULT.contains(bk.key())) {
                    // 2-arg call site (or a caught IllegalStateException) — the code default covers
                    // an absent value, so this is expected for a key with no Flyway seed.
                    log.debug("Platform config '{}' is absent — call site has a code default, no action", bk.key());
                } else {
                    log.error("Platform config '{}' is absent or blank — a 1-arg call site will throw "
                            + "IllegalStateException at runtime; expected a numeric value in [{}, {}] ({})",
                        bk.key(), bk.min(), bk.max(), bk.note());
                    increment(bk, "missing");
                    if (bk.failFast()) {
                        failFastViolations.add(bk.key() + " is absent/blank (expected ["
                            + bk.min() + ", " + bk.max() + "])");
                    }
                }
                continue;
            }

            long value;
            try {
                value = Long.parseLong(raw.get().trim());
            } catch (NumberFormatException e) {
                log.error("Platform config '{}' = '{}' is not a valid integer — expected a value in [{}, {}] ({})",
                    bk.key(), raw.get(), bk.min(), bk.max(), bk.note());
                increment(bk, "non_numeric");
                if (bk.failFast()) {
                    failFastViolations.add(bk.key() + " = '" + raw.get() + "' is not numeric (expected ["
                        + bk.min() + ", " + bk.max() + "])");
                }
                continue;
            }

            if (value < bk.min() || value > bk.max()) {
                log.error("Platform config '{}' = {} is outside the required range [{}, {}] — {}. "
                        + "A read-time clamp keeps the flow alive, but correct the stored value.",
                    bk.key(), value, bk.min(), bk.max(), bk.note());
                increment(bk, "out_of_range");
                if (bk.failFast()) {
                    failFastViolations.add(
                        bk.key() + " = " + value + " (expected [" + bk.min() + ", " + bk.max() + "])");
                }
            }
        }

        // skillars-deferred-122 AC4: cross-field ordering check. Neither threshold key is a
        // BoundedKey in ConfigBounds (so it is deliberately NOT counted in `checked` below, which
        // reports against ConfigBounds.ALL's registry size) — each is bounded independently against
        // its own fixed [1, Long.MAX_VALUE] range at its two call sites (ReliabilityStrikeService,
        // AdminCoachEnforcementService), so this relationship cannot be expressed as a single
        // BoundedKey.
        //
        // Code review 2026-09-18 corrected this check's original rationale: AdminCoachEnforcementService
        // .deleteStrike evaluates its suspension tier before its visibility tier (`count >=
        // suspensionThreshold` short-circuits before `count >= visibilityThreshold` is ever reached),
        // so a misconfigured visibilityThreshold > suspensionThreshold cannot actually cause a
        // wrongful revert/reduce — that tier ordering alone already makes it structurally impossible,
        // independent of this check or of any read-time clamp. What a misconfigured pair actually
        // does: it makes deleteStrike's REDUCED tier permanently unreachable (`count >=
        // visibilityThreshold` can never be true once `count < suspensionThreshold`, when
        // visibilityThreshold > suspensionThreshold), so a coach's visibility silently never reduces
        // to REDUCED. That is still a real data-integrity issue worth the existing failFast bar, just
        // a different one than originally documented here.
        long suspensionThreshold = configService.getBoundedLong(
            ReliabilityStrikeConfig.SUSPENSION_THRESHOLD_KEY, ReliabilityStrikeConfig.DEFAULT_SUSPENSION_THRESHOLD, 1L, Long.MAX_VALUE);
        long visibilityThreshold = configService.getBoundedLong(
            ReliabilityStrikeConfig.VISIBILITY_THRESHOLD_KEY, ReliabilityStrikeConfig.DEFAULT_VISIBILITY_THRESHOLD, 1L, Long.MAX_VALUE);
        if (visibilityThreshold > suspensionThreshold) {
            log.error("Platform config '{}' = {} exceeds '{}' = {} — deleteStrike's REDUCED tier "
                    + "becomes permanently unreachable (a coach's visibility would silently never "
                    + "reduce). Correct the stored values.",
                ReliabilityStrikeConfig.VISIBILITY_THRESHOLD_KEY, visibilityThreshold,
                ReliabilityStrikeConfig.SUSPENSION_THRESHOLD_KEY, suspensionThreshold);
            Counter.builder("config.value.misconfigured")
                .tag("key", "reliability.strike.threshold_ordering")
                .tag("reason", "cross_field_ordering")
                .register(meterRegistry)
                .increment();
            failFastViolations.add(ReliabilityStrikeConfig.VISIBILITY_THRESHOLD_KEY + " = " + visibilityThreshold
                + " must not exceed " + ReliabilityStrikeConfig.SUSPENSION_THRESHOLD_KEY + " = " + suspensionThreshold);
        }

        int locksChecked = assertSchedulerLockOrdering(failFastViolations);

        // Logged before the fail-fast throw so a blocked boot still records what was checked.
        log.info("ConfigStartupAssertion: {} bounded platform config keys and {} @SchedulerLock pair(s) "
                + "checked, {} fail-fast violation(s)",
            checked, locksChecked, failFastViolations.size());

        if (!failFastViolations.isEmpty()) {
            String message = "Platform config values would silently disable core flows: "
                + String.join("; ", failFastViolations)
                + ". Correct the platform_config rows and restart.";
            if (devProfile) {
                log.error("{} — startup NOT blocked (dev profile active)", message);
            } else {
                throw new AppSetupException(message);
            }
        }
    }

    /**
     * skillars-deferred-123 code review 2026-09-18 (Decision 4): fail-fast cross-field check that no
     * scheduler's {@code lockAtLeastFor} exceeds its own {@code lockAtMostFor}.
     *
     * <p><strong>This deliberately overrides AC4's stated decision</strong> ("There is deliberately no
     * boot-time check, no {@code failFast}, no new record type, and no second source of truth in this
     * AC"). That decision was reasonable when both attributes were compile-time literals and the pair
     * could not be wrong. AC4 itself changed that: making {@code lockAtLeastFor} operator-settable
     * against a ceiling that mostly stayed a hardcoded literal created a new, reachable
     * misconfiguration with an unusually bad failure mode. {@code shedlock-core:7.10.1}'s
     * {@code LockConfiguration} constructor throws {@code IllegalArgumentException} when
     * {@code lockAtLeastFor > lockAtMostFor}, and {@code SpringLockConfigurationExtractor} builds that
     * object <em>per invocation</em>, not at startup — so the app boots cleanly and then every single
     * tick throws before the method body runs. Spring's {@code LOG_AND_SUPPRESS_ERROR_HANDLER} swallows
     * it and reschedules, so the job simply never runs again: failed emails never retried, deletion
     * outbox never drained, DLQ never replayed. No metric, no health signal, one log line per tick.
     * That clears this class's existing {@code failFast} bar ("a bad value causes data loss or halts a
     * core flow entirely") comfortably.
     *
     * <p><strong>No second source of truth.</strong> AC4's objection to a boot check was that it would
     * duplicate the annotation values somewhere they could drift. This check does not: it reads the
     * {@code @SchedulerLock} annotations themselves off the live beans and resolves their {@code ${...}}
     * expressions through the same {@link Environment} ShedLock will use, so the annotation stays the
     * only place the values are written. It also therefore covers every scheduler in the application,
     * not just the ten AC4 converted, and any added later for free.
     *
     * <p>Durations are parsed with {@link DurationStyle#detectAndParse}, which accepts both the
     * ISO-8601 ({@code PT30S}) and Spring shorthand ({@code 30s}) forms that ShedLock's own
     * {@code StringToDurationConverter} accepts. An unparseable or negative value is reported too — it
     * fails the same way, per invocation, for the same reason.
     *
     * @return the number of {@code @SchedulerLock} methods inspected
     */
    private int assertSchedulerLockOrdering(List<String> failFastViolations) {
        int inspected = 0;

        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> beanType;
            try {
                beanType = applicationContext.getType(beanName);
            } catch (RuntimeException e) {
                // A bean whose type cannot be resolved cannot carry a @SchedulerLock we can read.
                continue;
            }
            if (beanType == null) {
                continue;
            }
            Class<?> targetClass = ClassUtils.getUserClass(beanType);

            for (Method method : ReflectionUtils.getAllDeclaredMethods(targetClass)) {
                SchedulerLock lock = AnnotatedElementUtils.findMergedAnnotation(method, SchedulerLock.class);
                if (lock == null) {
                    continue;
                }
                inspected++;

                String lockName = lock.name().isBlank()
                    ? targetClass.getSimpleName() + "." + method.getName()
                    : lock.name();

                Duration atLeast = parseLockDuration(lock.lockAtLeastFor(), lockName, "lockAtLeastFor", failFastViolations);
                Duration atMost = parseLockDuration(lock.lockAtMostFor(), lockName, "lockAtMostFor", failFastViolations);
                if (atLeast == null || atMost == null) {
                    continue;
                }

                if (atLeast.compareTo(atMost) > 0) {
                    log.error("@SchedulerLock '{}' has lockAtLeastFor ({}) greater than lockAtMostFor ({}) — "
                            + "ShedLock throws IllegalArgumentException on EVERY invocation, so this job would "
                            + "never run again and the failure would be swallowed by Spring's scheduler error "
                            + "handler. Resolved from lockAtLeastFor='{}', lockAtMostFor='{}'.",
                        lockName, atLeast, atMost, lock.lockAtLeastFor(), lock.lockAtMostFor());
                    incrementLockViolation(lockName, "lock_at_least_exceeds_at_most");
                    failFastViolations.add("@SchedulerLock " + lockName + " has lockAtLeastFor " + atLeast
                        + " > lockAtMostFor " + atMost);
                }
            }
        }
        return inspected;
    }

    /**
     * Resolves a {@code @SchedulerLock} duration attribute through the {@link Environment} and parses
     * it. Returns {@code null} (after recording a violation) when the value cannot be used, so the
     * caller skips the ordering comparison rather than comparing against a bogus duration.
     */
    private Duration parseLockDuration(String rawValue, String lockName, String attribute,
                                       List<String> failFastViolations) {
        String resolved = env.resolvePlaceholders(rawValue);
        if (resolved.isBlank()) {
            // ShedLock treats an empty attribute as "not set" and falls back to its own defaults —
            // nothing to validate, and not a misconfiguration.
            return null;
        }
        try {
            Duration parsed = DurationStyle.detectAndParse(resolved);
            if (parsed.isNegative()) {
                log.error("@SchedulerLock '{}' attribute {} resolved to a negative duration ({} -> {}) — "
                        + "ShedLock rejects this on every invocation.", lockName, attribute, rawValue, parsed);
                incrementLockViolation(lockName, "negative_duration");
                failFastViolations.add("@SchedulerLock " + lockName + " " + attribute + " is negative (" + parsed + ")");
                return null;
            }
            return parsed;
        } catch (RuntimeException e) {
            log.error("@SchedulerLock '{}' attribute {} = '{}' resolved to '{}', which is not a valid duration — "
                    + "ShedLock throws on every invocation and the job would never run.",
                lockName, attribute, rawValue, resolved);
            incrementLockViolation(lockName, "unparseable_duration");
            failFastViolations.add("@SchedulerLock " + lockName + " " + attribute + " = '" + resolved
                + "' is not a valid duration");
            return null;
        }
    }

    private void incrementLockViolation(String lockName, String reason) {
        Counter.builder("config.value.misconfigured")
            .tag("key", "scheduler.lock." + lockName)
            .tag("reason", reason)
            .register(meterRegistry)
            .increment();
    }

    /**
     * Same {@code config.value.misconfigured} counter and {@code {key, reason}} tag scheme that
     * {@code ConfigService} already registers for feature-gate misconfiguration — the range in
     * {@code [min, max]} form is in the ERROR log line above, not a metric tag, because
     * {@code PrometheusMeterRegistry} rejects a second registration of the same counter name with a
     * different tag-key set (skillars-deferred-107 code review).
     */
    private void increment(BoundedKey bk, String reason) {
        Counter.builder("config.value.misconfigured")
            .tag("key", bk.key())
            .tag("reason", reason)
            .register(meterRegistry)
            .increment();
    }
}
