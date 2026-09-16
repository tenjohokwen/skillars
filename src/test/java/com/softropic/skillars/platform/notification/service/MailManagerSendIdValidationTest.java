package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.retry.support.RetryTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * skillars-deferred-114 code review (HIGH): {@code MailManager.sendEmailSync} passed
 * {@code envelope.sendId()} straight into {@code acquireSendIdLock}'s native query without a
 * null-check. Confirmed against a real Postgres 16 instance that {@code hashtext(...)} and
 * {@code pg_advisory_xact_lock(...)} are both STRICT: a null key makes the whole call silently
 * return without acquiring any lock, rather than erroring — a null {@code sendId} would silently
 * skip this method's entire skillars-deferred-114 AC1 serialization guarantee. This is a hermetic,
 * non-{@code *IT} unit test (no Testcontainers) because the guard fires before any repository or
 * transactional interaction — {@link MailManager} is hand-constructed with mocks exactly like
 * {@code MailManagerDuplicateSendIdIT} does for its real, container-backed instances.
 */
@DisplayName("MailManager.sendEmailSync — sendId null-guard")
class MailManagerSendIdValidationTest {

    @Test
    void nullSendId_throwsImmediately_beforeAnyRepositoryInteraction() {
        EnvelopeEntityRepository envelopeEntityRepository = mock(EnvelopeEntityRepository.class);
        MailManager mailManager = new MailManager(
            mock(MailService.class), envelopeEntityRepository,
            mock(CircuitBreakerFactory.class), mock(RetryTemplate.class));

        Recipient recipient = new Recipient();
        recipient.setEmail("someone@skillars-test.com");
        recipient.setLangKey("en");
        Envelope envelopeWithNullSendId = new Envelope(
            List.of(recipient), EmailTemplate.COACH_OTP, Instant.now().plusSeconds(60), Map.of(), null);

        assertThatThrownBy(() -> mailManager.sendEmailSync(envelopeWithNullSendId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sendId");

        verifyNoInteractions(envelopeEntityRepository);
    }

    @Test
    void nonNullSendId_stillReachesTheAdvisoryLockCall() {
        EnvelopeEntityRepository envelopeEntityRepository = mock(EnvelopeEntityRepository.class);
        MailManager mailManager = new MailManager(
            mock(MailService.class), envelopeEntityRepository,
            mock(CircuitBreakerFactory.class), mock(RetryTemplate.class));

        Recipient recipient = new Recipient();
        recipient.setEmail("someone@skillars-test.com");
        recipient.setLangKey("en");
        Envelope envelopeWithRealSendId = new Envelope(
            List.of(recipient), EmailTemplate.COACH_OTP, Instant.now().plusSeconds(60), Map.of(), "real-send-id");

        // No real CircuitBreakerFactory is wired here, so the send attempt itself fails further in
        // (a NullPointerException from the mock circuit breaker) — sendEmailSync's own catch(Exception)
        // swallows that into a FAILED envelope rather than rethrowing, so this call does not throw.
        // The point of this test is only that it gets PAST the null-guard and reaches
        // acquireSendIdLock, unlike the null case above (whose verifyNoInteractions would otherwise
        // pass vacuously for the wrong reason if the guard were accidentally too broad).
        mailManager.sendEmailSync(envelopeWithRealSendId);

        verify(envelopeEntityRepository).acquireSendIdLock("real-send-id");
    }
}
