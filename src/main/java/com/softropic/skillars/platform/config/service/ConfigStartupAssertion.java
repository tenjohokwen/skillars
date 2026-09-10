package com.softropic.skillars.platform.config.service;

import com.softropic.skillars.infrastructure.exception.AppSetupException;
import com.softropic.skillars.platform.config.service.ConfigBounds.BoundedKey;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

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

        // Logged before the fail-fast throw so a blocked boot still records what was checked.
        log.info("ConfigStartupAssertion: {} bounded platform config keys checked, {} fail-fast violation(s)",
            checked, failFastViolations.size());

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
