package com.softropic.skillars.platform.notification.config;

import com.softropic.skillars.infrastructure.email.EmailTransportRateLimitedException;
import com.softropic.skillars.infrastructure.email.EmailTransportTransientException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story ses-1.3 AC4/Task 4 — proves the <strong>real</strong> {@link ComponentConfig#retryTemplate()}
 * bean, not {@code MailManagerResilienceTest}'s independently hand-rolled template. That test builds
 * its own {@code RetryTemplate.builder()...build()} inline in its {@code setUp()} and never consumes
 * this bean at all — adding a case only there would still retry 3× (its own template has no
 * rate-limit-aware policy) and pass vacuously, proving nothing about this fix.
 */
@DisplayName("ComponentConfig.retryTemplate()")
class ComponentConfigRetryTemplateTest {

    private final RetryTemplate retryTemplate = new ComponentConfig().retryTemplate();

    @Test
    @DisplayName("an EmailTransportRateLimitedException is not retried — exactly one invocation")
    void rateLimitedException_isNotRetried() {
        AtomicInteger invocations = new AtomicInteger();

        assertThatThrownBy(() -> retryTemplate.execute(context -> {
            invocations.incrementAndGet();
            throw new EmailTransportRateLimitedException("rate limited");
        })).isInstanceOf(EmailTransportRateLimitedException.class);

        assertThat(invocations.get()).isEqualTo(1);
    }

    /**
     * {@code MailManager.sendEmailSync}'s own {@code retryTemplate.execute(...)} catches whatever it
     * catches and rewraps it in one level of {@code RuntimeException} before rethrowing — this is
     * exactly why {@code .traversingCauses()} is part of the fix; without it this case would still
     * retry 3× because the classifier would never see past the wrapper.
     */
    @Test
    @DisplayName("an EmailTransportRateLimitedException wrapped one level deep is still not retried")
    void rateLimitedException_traversedThroughOneWrappingLevel_isNotRetried() {
        AtomicInteger invocations = new AtomicInteger();

        assertThatThrownBy(() -> retryTemplate.execute(context -> {
            invocations.incrementAndGet();
            throw new RuntimeException("wrapped", new EmailTransportRateLimitedException("rate limited"));
        })).isInstanceOf(RuntimeException.class);

        assertThat(invocations.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("an ordinary EmailTransportTransientException still retries 3 times (regression guard: the fix is additive, not global)")
    void ordinaryTransientException_stillRetriesThreeTimes() {
        AtomicInteger invocations = new AtomicInteger();

        assertThatThrownBy(() -> retryTemplate.execute(context -> {
            invocations.incrementAndGet();
            throw new EmailTransportTransientException("transient");
        })).isInstanceOf(EmailTransportTransientException.class);

        assertThat(invocations.get()).isEqualTo(3);
    }
}
