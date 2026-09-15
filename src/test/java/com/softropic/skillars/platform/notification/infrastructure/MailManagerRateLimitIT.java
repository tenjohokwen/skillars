package com.softropic.skillars.platform.notification.infrastructure;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.infrastructure.email.EmailTransportRateLimitedException;
import com.softropic.skillars.platform.notification.contract.EmailDeliveryStatus;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntity;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;
import com.softropic.skillars.platform.notification.repo.RecipientEntity;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.platform.notification.service.MailService;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.retry.support.RetryTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

/**
 * skillars-deferred-113 AC1 (CRITICAL): before this fix, {@code MailManager.sendEmailSync}'s
 * recipient loop had no durable record of which individual recipients within a multi-recipient
 * envelope had already been sent to. A mid-loop rate-limit rejection at recipient k of n aborted
 * the whole send; {@code EmailRetryScheduler}'s next tick re-drove the SAME {@code Envelope} (same
 * {@code sendId}, same full recipients list, reconstructed from the persisted {@code
 * EnvelopeEntity}) and the loop restarted at recipient 1 — re-sending to every recipient before k a
 * second time.
 *
 * <p>Drives a real, container-backed {@link EnvelopeEntityRepository}, a real {@code
 * CircuitBreakerFactory} and a real, production-shaped {@link RetryTemplate} (mirrors {@code
 * ComponentConfig.retryTemplate()}'s {@code notRetryOn(EmailTransportRateLimitedException.class)},
 * so a rate-limit rejection fails its send attempt immediately, exactly like production) through a
 * hand-wired {@link MailManager} — only {@link MailService} (the SMTP/SES seam) is mocked, same
 * pattern as {@code MailManagerDuplicateSendIdIT}/{@code VideoModerationAdminAlertEnvelopeIT}.
 */
class MailManagerRateLimitIT extends AbstractIntegrationTest {

    @Autowired private EnvelopeEntityRepository envelopeEntityRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    /**
     * A hand-built {@link MailManager} has no Spring proxy, so its own
     * {@code @Transactional(propagation = REQUIRES_NEW)} does not apply — reproduced explicitly here,
     * same pattern as {@code MailManagerDuplicateSendIdIT}/{@code VideoModerationAdminAlertEnvelopeIT}.
     * This matters for AC1 specifically: the update-path in {@code sendEmailSync} (an existing
     * {@code EnvelopeEntity} found by {@code findBySendId}) relies on that entity staying managed for
     * the rest of the method so plain field mutations are picked up by dirty-checking at commit —
     * without an explicit transaction boundary here, {@code findBySendId}'s own per-method
     * transaction (Spring Data's repository-method default) would close and detach the entity before
     * the delivered-flag update ever ran.
     */
    private void sendWithinTransaction(MailManager mailManager, Envelope envelope) {
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.executeWithoutResult(status -> mailManager.sendEmailSync(envelope));
    }

    /**
     * {@code findBySendId} carries {@code @Lock(PESSIMISTIC_WRITE)}, which Hibernate refuses to
     * take outside an active transaction ({@code TransactionRequiredException}), and
     * {@code EnvelopeEntity.recipients} is a lazy {@code @ElementCollection} that cannot be read
     * once the entity is detached — so the whole read, including the recipients extraction, must
     * happen inside one transaction. Returns an eager snapshot rather than the entity itself.
     */
    private record EnvelopeSnapshot(EmailDeliveryStatus status, boolean retry, List<String> deliveredEmails) {
    }

    private EnvelopeSnapshot readSnapshot(String sendId) {
        TransactionTemplate readTx = new TransactionTemplate(transactionManager);
        readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return readTx.execute(status -> {
            EnvelopeEntity entity = envelopeEntityRepository.findBySendId(sendId);
            assertThat(entity).as("an EnvelopeEntity row must exist for sendId " + sendId).isNotNull();
            List<String> delivered = entity.getRecipients().stream()
                .filter(RecipientEntity::isDelivered)
                .map(RecipientEntity::getEmail)
                .toList();
            return new EnvelopeSnapshot(entity.getStatus(), entity.isRetry(), delivered);
        });
    }

    private static RetryTemplate productionShapedRetryTemplate() {
        return RetryTemplate.builder()
            .maxAttempts(3)
            .notRetryOn(List.of(EmailTransportRateLimitedException.class, EmailTransportPermanentException.class))
            .traversingCauses()
            .fixedBackoff(Duration.ofMillis(1))
            .build();
    }

    private static CircuitBreakerFactory<?, ?> freshCircuitBreakerFactory() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .minimumNumberOfCalls(10)
            .failureRateThreshold(90.0f)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .build());
        return new Resilience4JCircuitBreakerFactory(registry, TimeLimiterRegistry.ofDefaults(), null);
    }

    private static Recipient recipient(String email) {
        Recipient recipient = new Recipient();
        recipient.setEmail(email);
        recipient.setLangKey("en");
        return recipient;
    }

    @Test
    void multiRecipientRateLimitMidLoop_allRecipientsDeliveredExactlyOnce() {
        String sendId = "ac1-ratelimit-" + UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        List<Recipient> recipients = List.of(
            recipient("r1." + suffix + "@skillars-test.com"),
            recipient("r2." + suffix + "@skillars-test.com"),
            recipient("r3." + suffix + "@skillars-test.com"),
            recipient("r4." + suffix + "@skillars-test.com"),
            recipient("r5." + suffix + "@skillars-test.com"));
        Envelope envelope = new Envelope(recipients, EmailTemplate.NONE,
            Instant.now().plus(Duration.ofDays(1)), Map.of("subject", "rl", "body", "rl"), sendId);

        // recipient 3 (index 2) rate-limits exactly once — the FIRST attempt only. A retry that
        // reaches recipient 3 a second time must succeed, simulating the limiter's window refreshing.
        String rateLimitedOnceFor = recipients.get(2).getEmail();
        AtomicBoolean rateLimitConsumed = new AtomicBoolean(false);
        Map<String, Integer> callCounts = new ConcurrentHashMap<>();

        MailService seamMailService = mock(MailService.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            Recipient recipient = invocation.getArgument(0);
            callCounts.merge(recipient.getEmail(), 1, Integer::sum);
            if (recipient.getEmail().equals(rateLimitedOnceFor) && rateLimitConsumed.compareAndSet(false, true)) {
                throw new EmailTransportRateLimitedException("rate limited at recipient 3 of 5");
            }
            return null;
        }).when(seamMailService).sendEmailFromTemplate(any(), any(), any());

        MailManager mailManager = new MailManager(seamMailService, envelopeEntityRepository,
            freshCircuitBreakerFactory(), productionShapedRetryTemplate());

        // --- First attempt: recipients 1,2 delivered, recipient 3 rate-limited, 4/5 never reached ---
        sendWithinTransaction(mailManager, envelope);

        EnvelopeSnapshot afterFirstAttempt = readSnapshot(sendId);
        assertThat(afterFirstAttempt.status()).isEqualTo(EmailDeliveryStatus.FAILED);
        assertThat(afterFirstAttempt.retry())
            .as("a rate-limit rejection is retryable — EmailRetryScheduler must re-drive this envelope")
            .isTrue();
        assertThat(afterFirstAttempt.deliveredEmails())
            .as("only the recipients actually accepted by the transport before the rate-limit rejection")
            .containsExactlyInAnyOrder(recipients.get(0).getEmail(), recipients.get(1).getEmail());
        assertThat(callCounts).containsOnlyKeys(
            recipients.get(0).getEmail(), recipients.get(1).getEmail(), recipients.get(2).getEmail());
        assertThat(callCounts.get(recipients.get(3).getEmail())).as("recipient 4 never reached on attempt 1").isNull();
        assertThat(callCounts.get(recipients.get(4).getEmail())).as("recipient 5 never reached on attempt 1").isNull();

        // --- Second attempt (EmailRetryScheduler re-drive): same Envelope, same sendId ---
        sendWithinTransaction(mailManager, envelope);

        EnvelopeSnapshot afterSecondAttempt = readSnapshot(sendId);
        assertThat(afterSecondAttempt.status()).isEqualTo(EmailDeliveryStatus.SENT);
        assertThat(afterSecondAttempt.retry()).isFalse();
        assertThat(afterSecondAttempt.deliveredEmails())
            .as("all five recipients eventually delivered")
            .containsExactlyInAnyOrderElementsOf(recipients.stream().map(Recipient::getEmail).toList());

        // The crux of AC1: recipients 1 and 2 — already delivered on attempt 1 — must NOT have been
        // sent to again on attempt 2. Recipient 3 is sent to twice (rejected, then accepted); 4 and
        // 5 are sent to exactly once (only reached on attempt 2).
        assertThat(callCounts.get(recipients.get(0).getEmail())).as("recipient 1 sent exactly once, never duplicated").isEqualTo(1);
        assertThat(callCounts.get(recipients.get(1).getEmail())).as("recipient 2 sent exactly once, never duplicated").isEqualTo(1);
        assertThat(callCounts.get(recipients.get(2).getEmail())).as("recipient 3: rejected once, then accepted once").isEqualTo(2);
        assertThat(callCounts.get(recipients.get(3).getEmail())).as("recipient 4 sent exactly once, on attempt 2").isEqualTo(1);
        assertThat(callCounts.get(recipients.get(4).getEmail())).as("recipient 5 sent exactly once, on attempt 2").isEqualTo(1);
        // Mutation: revert sendEmailSync's recipient loop to iterate the envelope's full recipient
        // list unconditionally (drop the pendingRecipients filter) → recipients 1 and 2's call count
        // above becomes 2, not 1 — this test goes red.
    }

    /**
     * skillars-deferred-113 AC1 regression check: a single-recipient envelope's behaviour is
     * unchanged — no persisted entity exists yet, so {@code alreadyDelivered} is empty and every
     * recipient (the one recipient) is sent to, exactly as before this story.
     */
    @Test
    void singleRecipientEnvelope_unaffectedByPerRecipientTracking() {
        String sendId = "ac1-single-" + UUID.randomUUID();
        Recipient recipient = recipient("single." + UUID.randomUUID() + "@skillars-test.com");
        Envelope envelope = new Envelope(List.of(recipient), EmailTemplate.NONE,
            Instant.now().plus(Duration.ofDays(1)), Map.of("subject", "single", "body", "single"), sendId);

        MailService seamMailService = mock(MailService.class);
        MailManager mailManager = new MailManager(seamMailService, envelopeEntityRepository,
            freshCircuitBreakerFactory(), productionShapedRetryTemplate());

        sendWithinTransaction(mailManager, envelope);

        org.mockito.Mockito.verify(seamMailService, org.mockito.Mockito.times(1))
            .sendEmailFromTemplate(any(), any(), any());
        EnvelopeSnapshot snapshot = readSnapshot(sendId);
        assertThat(snapshot.status()).isEqualTo(EmailDeliveryStatus.SENT);
        assertThat(snapshot.deliveredEmails()).containsExactly(recipient.getEmail());
    }
}
