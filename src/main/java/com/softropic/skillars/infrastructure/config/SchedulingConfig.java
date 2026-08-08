package com.softropic.skillars.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's {@code @Scheduled} support, with a switch to turn it off.
 *
 * <p>{@code @EnableScheduling} used to sit unconditionally on {@code AsyncConfig}. That was
 * harmless while the integration suite fragmented into 37 short-lived Spring contexts: a
 * scheduler with a 5-minute delay in a context that lives 90 seconds never fires, so each
 * job's blast radius was capped by its context's lifetime.
 *
 * <p>Consolidating the suite onto a small number of long-lived contexts removes that
 * accidental protection. One context now lives for the entire failsafe run with all 30
 * scheduler threads writing to the same database the tests assert on — including
 * {@code OutboxPollerScheduler} and {@code DeletionSchedulerService} at a 5-second delay.
 * On top of that, the per-test reset backdates every ShedLock row, which makes all 11
 * {@code @SchedulerLock} jobs eligible on every one of ~905 test methods.
 *
 * <p><strong>Why a property and not a delay sweep.</strong> The alternative considered was
 * overriding every job's delay in {@code application-test.yaml}. That cannot work: 16 of the
 * 30 {@code @Scheduled} methods hard-code their delay or cron with no property placeholder
 * ({@code BookingExpiryScheduler}, {@code PaymentPendingSweeper},
 * {@code MessagingEmitterRegistry}, {@code SessionPackForfeitureScheduler}, every
 * {@code cron = "..."} job, and others). Only 14 are property-driven, so a configuration-only
 * sweep is incapable of neutralizing the suite no matter how thorough it is.
 *
 * <p><strong>ShedLock stays enabled.</strong> {@code @EnableSchedulerLock} remains on
 * {@code AsyncConfig} and is deliberately not gated here. Tests invoke scheduled methods
 * directly through the Spring proxy, so the lock advisor must still apply — that is the
 * behaviour {@code BasePaymentIT.releaseSchedulerLock} exists to work with, and disabling it
 * would silently change what those tests exercise.
 *
 * <p>Production and every non-test profile leave this on: {@code matchIfMissing = true} means
 * the property has to be explicitly set to {@code false} to disable scheduling, which
 * {@code src/test/resources/application-test.yaml} does and nothing else does.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
