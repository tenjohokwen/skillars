package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.infrastructure.email.EmailTransportRateLimitedException;
import com.softropic.skillars.platform.notification.contract.EmailDeliveryStatus;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntity;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;


public class MailManager {

    private static final Logger logger = LoggerFactory.getLogger(MailManager.class);

    private final MailService mailService;
    private final EnvelopeEntityRepository envelopeEntityRepository;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    private final RetryTemplate retryTemplate;

    // Story ses-1.2 AC4: transport-neutral now — every OutboundEmailSender implementation already
    // classifies its own failures into this taxonomy (SesErrorClassifier, SmtpErrorClassifier), so
    // MailManager no longer needs to know about any transport-specific mail-library exception type.
    private static final List<Class<? extends Exception>> NON_REPAIRABLE_ERRORS =
        List.of(EmailTransportPermanentException.class);

    public MailManager(final MailService mailService,
                       final EnvelopeEntityRepository envelopeEntityRepository,
                       final CircuitBreakerFactory<?, ?> circuitBreakerFactory,
                       final RetryTemplate retryTemplate) {
        this.mailService = mailService;
        this.envelopeEntityRepository = envelopeEntityRepository;
        this.circuitBreakerFactory = circuitBreakerFactory;
        this.retryTemplate = retryTemplate;
    }

    @Async("sendMailPool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendEmailFromTemplate(final Envelope envelope) {
        sendEmailSync(envelope);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendEmailSync(final Envelope envelope) {
        logger.info("sendEmailFrom template called:  Envelope {}", envelope);
        final List<Recipient> recipients = envelope.recipients();
        if (recipients == null || recipients.isEmpty()) {
            throw new IllegalStateException("Recipient is missing. Cannot process email send request");
        }

        final CircuitBreaker circuitBreaker = circuitBreakerFactory.create("emailService");

        EnvelopeEntity envelopeEntity;
        // Story ses-1.3, code review 2026-09-12: a rate-limit rejection must not spend a delivery
        // attempt. The send never reached SES — the limiter refused it locally — so counting it
        // would let a burst exhaust MAX_RETRY_ATTEMPTS and mark perfectly deliverable envelopes
        // ATTEMPTS_EXHAUSTED (retry=false, never fetched again) without a single real transport
        // failure. Most acute at the 1/s rate SesPropertiesValidator prescribes for a sandboxed
        // account, where one 10-envelope scheduler batch yields ~9 rejections per tick. The
        // envelope's deadline remains the terminal bound: DEADLINE_EXPIRED still applies.
        boolean rateLimited = false;
        try {
            circuitBreaker.run(() -> {
                for (Recipient recipient : recipients) {
                    final Map<String, Object> data = new HashMap<>(envelope.data());
                    data.put("sendId", envelope.sendId());

                    retryTemplate.execute(context -> {
                        try {
                            mailService.sendEmailFromTemplate(recipient, envelope.emailTemplate(), data);
                        } catch (Exception e) {
                            if (isRetryable(e)) {
                                throw new RuntimeException("Unexpected retryable email error", e);
                            }
                            throw new RuntimeException("Unexpected non-retryable email error", e);
                        }
                        return null;
                    });
                }
                return null;
            }, throwable -> {
                if (throwable instanceof RuntimeException && throwable.getCause() != null) {
                    throw (RuntimeException) throwable;
                }
                throw new RuntimeException("Email sending failed via Circuit Breaker", throwable);
            });
            envelopeEntity = toEnvelopeEntity(envelope, null);
        } catch (Exception exception) {
            rateLimited = EmailTransportRateLimitedException.isPresentIn(exception);
            envelopeEntity = toEnvelopeEntity(envelope, exception);
            logger.error("Could not send email after retries and circuit breaker protection. {}", envelopeEntity, exception);
        }
        final EnvelopeEntity entityBySendId = envelopeEntityRepository.findBySendId(envelopeEntity.getSendId());
        if (entityBySendId != null) {
            if (!rateLimited) {
                entityBySendId.setAttempts(entityBySendId.getAttempts() + 1);
            }
            entityBySendId.setStatus(envelopeEntity.getStatus());
            entityBySendId.setError(envelopeEntity.getError());
            entityBySendId.setRetry(envelopeEntity.isRetry());
        } else {
            envelopeEntityRepository.save(envelopeEntity);
        }
    }

    private EnvelopeEntity toEnvelopeEntity(final Envelope envelope, final Exception exception) {
        final EnvelopeEntity envelopeEntity = EnvelopeMapper.toEntity(envelope);
        long attempts = envelopeEntity.getAttempts();
        // A null exception is the success path, which always counts. See sendEmailSync for why a
        // rate-limit rejection does not.
        if (!EmailTransportRateLimitedException.isPresentIn(exception)) {
            envelopeEntity.setAttempts(++attempts);
        }
        if (exception != null) {
            final String stacktrace = ExceptionUtils.getStackTrace(exception);
            envelopeEntity.setError(stacktrace);
            envelopeEntity.setStatus(EmailDeliveryStatus.FAILED);
            envelopeEntity.setRetry(isRetryable(exception));
        } else {
            envelopeEntity.setRetry(false);
            envelopeEntity.setStatus(EmailDeliveryStatus.SENT);
        }
        return envelopeEntity;
    }

    private boolean isRetryable(final Exception unknownException) {
        // isRetryable is called at two different wrapping depths: directly on the caught EmailTransportException
        // inside the retry loop (cause is 0-1 levels down — an OutboundEmailSender's own classifier, e.g.
        // SmtpErrorClassifier/SesErrorClassifier, already did its wrapping), and on the RuntimeException wrapper
        // the circuit breaker/retry-template layer throws before it reaches toEnvelopeEntity (cause is 1-2 levels
        // down: RuntimeException -> EmailTransportPermanentException). Bound the check to those two known depths
        // instead of walking the exception's full, unbounded chain, so an unrelated non-repairable type buried
        // deeper in some other exception's chain can't be misclassified.
        Throwable direct = unknownException;
        Throwable cause = unknownException.getCause();
        Throwable causeOfCause = cause != null ? cause.getCause() : null;

        return Stream.of(direct, cause, causeOfCause)
                .filter(Objects::nonNull)
                .noneMatch(t -> NON_REPAIRABLE_ERRORS.stream().anyMatch(exceptionClass -> exceptionClass.isInstance(t)));
    }
}
