package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.infrastructure.email.EmailTransportRateLimitedException;
import com.softropic.skillars.platform.notification.contract.EmailDeliveryStatus;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    // Story ses-1.4 AC7 (option a): the smallest, template-scoped fix for the new sensitivity class
    // an OTP code / verification-URL token introduces. logger.info("... Envelope {}", envelope) and
    // the failure-path error log both otherwise print `data` in full at INFO/ERROR — fine for
    // booking/session-pack data, not fine for a value a user types directly to authenticate. This is
    // NOT a general answer for every template that carries a secret (ACTIVATION/PASSWORD_RESET/
    // EMAIL_CHANGE/PROFILE_CHANGE already flow through here today with the same exposure — pre-
    // existing, out of scope for this story per AC7) — only the six registration templates this
    // story newly routes through this class, plus SEND_OTP.
    //
    // SEND_OTP correction (code review 2026-09-12): AC7's premise — "this story is the first time an
    // OTP code joins the MailManager logging path" — was factually wrong. TwoFactorLoginService's
    // login-2FA flow already builds a SendMailEvent(EmailTemplate.SEND_OTP, Map.of("otpCode", otp,
    // "helpCode", helpCodeStr)) that SendMailListener republishes as an Envelope, which this class's
    // own @TransactionalEventListener(AFTER_COMMIT) sendEmailFromTemplate already receives and logs
    // unmasked today. Added here to close that pre-existing gap at the same time.
    private static final Set<EmailTemplate> SENSITIVE_DATA_TEMPLATES = Set.of(
        EmailTemplate.COACH_EMAIL_VERIFY, EmailTemplate.COACH_OTP,
        EmailTemplate.PARENT_EMAIL_VERIFY, EmailTemplate.PARENT_OTP,
        EmailTemplate.PLAYER_EMAIL_VERIFY, EmailTemplate.PLAYER_OTP,
        EmailTemplate.SEND_OTP);

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
        logger.info("sendEmailFrom template called:  Envelope {}", loggableEnvelope(envelope));
        final List<Recipient> recipients = envelope.recipients();
        if (recipients == null || recipients.isEmpty()) {
            throw new IllegalStateException("Recipient is missing. Cannot process email send request");
        }

        EnvelopeEntity envelopeEntity;
        // Story ses-1.4 AC5 (D-8, code review L3): derived from the envelope's own template rather
        // than a signature change to this method — five call sites across MailManager,
        // EmailRetryScheduler, VideoModerationEmailListener, AlertNotificationListener and
        // NotificationEmailOutboxHandler would otherwise all need updating for one new parameter.
        // Placed inside the try (rather than before it, where the pre-ses-1.4 hardcoded
        // circuitBreakerFactory.create("emailService") call sat) so a null emailTemplate — not
        // observed today from any of the three producers that take it as a method parameter rather
        // than a hardcoded enum constant, but not structurally prevented either — is recorded as a
        // failed envelope like everything else instead of NPEing out of this method uncaught and
        // unrecorded.
        // Story ses-1.3, code review 2026-09-12: a rate-limit rejection must not spend a delivery
        // attempt. The send never reached SES — the limiter refused it locally — so counting it
        // would let a burst exhaust MAX_RETRY_ATTEMPTS and mark perfectly deliverable envelopes
        // ATTEMPTS_EXHAUSTED (retry=false, never fetched again) without a single real transport
        // failure. Most acute at the 1/s rate SesPropertiesValidator prescribes for a sandboxed
        // account, where one 10-envelope scheduler batch yields ~9 rejections per tick. The
        // envelope's deadline remains the terminal bound: DEADLINE_EXPIRED still applies.
        boolean rateLimited = false;
        try {
            final String breakerName = envelope.emailTemplate() != null
                ? envelope.emailTemplate().circuitBreakerName()
                : "emailService";
            final CircuitBreaker circuitBreaker = circuitBreakerFactory.create(breakerName);
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
            logger.error("Could not send email after retries and circuit breaker protection. "
                    + "template={} sendId={} status={} attempts={} data={}",
                envelopeEntity.getEmailTemplate(), envelopeEntity.getSendId(), envelopeEntity.getStatus(),
                envelopeEntity.getAttempts(), loggableData(envelopeEntity.getEmailTemplate(), envelopeEntity.getData()),
                exception);
        }
        final EnvelopeEntity entityBySendId = envelopeEntityRepository.findBySendId(envelopeEntity.getSendId());
        if (entityBySendId != null) {
            if (!rateLimited) {
                entityBySendId.setAttempts(entityBySendId.getAttempts() + 1);
            }
            entityBySendId.setStatus(envelopeEntity.getStatus());
            entityBySendId.setError(envelopeEntity.getError());
            entityBySendId.setRetry(envelopeEntity.isRetry());
            scrubDataIfSentAndSensitive(entityBySendId);
        } else {
            // Story ses-1.4 AC4 (code review B2/B3): EnvelopeEntity's @Id is hand-assigned
            // (EnvelopeMapper.toEntity) with no @GeneratedValue, and @Version is a primitive, so
            // Spring Data's isNew() check falls back to "id is non-null => not new" and save() calls
            // merge(), which does not flush. A sendId collision's actual INSERT then fired at this
            // method's REQUIRES_NEW commit — after this method had already returned, so no catch here
            // could ever see it. saveAndFlush() surfaces the DataIntegrityViolationException
            // synchronously instead, and it is left to propagate uncaught: the flush failure already
            // marks this transaction rollback-only, so an in-method recovery (re-query + update)
            // could not commit anyway. Two of the three real callers already handle an uncaught
            // exception here correctly and unchanged — EmailRetryScheduler.sendAll's catch (Exception)
            // logs it at ERROR with the sendId, and NotificationEmailOutboxHandler.handle lets it
            // propagate to OutboxRowProcessor's own per-row retry/backoff. The third,
            // sendEmailFromTemplate's @Async self-invocation of this method (bypasses the proxy, so
            // this method's own @Transactional never opens a nested transaction — the whole body runs
            // inside sendEmailFromTemplate's), has no outbox row to retry: the exception rolls back
            // that lone REQUIRES_NEW transaction and is logged by AsyncConfig's
            // getAsyncUncaughtExceptionHandler(), same as it was pre-story when the equivalent failure
            // surfaced at save()'s deferred flush on commit instead — unchanged behaviour, not a new
            // gap this story introduces.
            scrubDataIfSentAndSensitive(envelopeEntity);
            envelopeEntityRepository.saveAndFlush(envelopeEntity);
        }
    }

    /**
     * Story ses-1.4 AC7 (option a). {@code data} is a bearer secret for the six registration/OTP
     * templates (an OTP code, or a verification-URL token) — mask it before it reaches this class's
     * INFO/ERROR logs. Every other template's data is unaffected.
     */
    private static Envelope loggableEnvelope(final Envelope envelope) {
        if (envelope.emailTemplate() == null || !SENSITIVE_DATA_TEMPLATES.contains(envelope.emailTemplate())) {
            return envelope;
        }
        return new Envelope(envelope.recipients(), envelope.emailTemplate(), envelope.deadline(),
            maskedData(envelope.data()), envelope.sendId());
    }

    private static Object loggableData(final EmailTemplate template, final Map<String, Object> data) {
        return template != null && SENSITIVE_DATA_TEMPLATES.contains(template) ? maskedData(data) : data;
    }

    /**
     * Story ses-1.4 AC7, code review 2026-09-12 (redaction erases key names). Masks every value while
     * preserving the key set, so {@code data={otpCode=[REDACTED]}} still shows an OTP was present (or
     * that {@code data} was unexpectedly empty) instead of collapsing everything into one opaque
     * {@code data={data=[REDACTED]}} entry regardless of what the original map held.
     */
    private static Map<String, Object> maskedData(final Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        final Map<String, Object> masked = new LinkedHashMap<>();
        for (final String key : data.keySet()) {
            masked.put(key, "[REDACTED]");
        }
        return masked;
    }

    /**
     * Story ses-1.4 AC7, code review 2026-09-12 (B/blind finding): AC7 masked the two log statements
     * but left the larger, indefinite at-rest exposure untouched — {@code EnvelopeEntity.data} is a
     * persisted {@code jsonb} column with no purge or retention job anywhere in the repo. Once a
     * sensitive-template send reaches its terminal success state, the OTP code / verification token
     * no longer serves any purpose (a re-drive of a {@code SENT} row does not happen via the normal
     * {@code FAILED}-only retry paths), so it is cleared here rather than left indefinitely queryable.
     * Retained while {@code FAILED}, since a re-drive still needs it. Rows that end
     * terminal-but-failed ({@code DEADLINE_EXPIRED} / {@code ATTEMPTS_EXHAUSTED}, both set by
     * {@code EmailRetryScheduler}, not here) keep their {@code data} — closing that too is a
     * follow-up, not part of this decision.
     */
    private static void scrubDataIfSentAndSensitive(final EnvelopeEntity entity) {
        if (entity.getStatus() == EmailDeliveryStatus.SENT
                && SENSITIVE_DATA_TEMPLATES.contains(entity.getEmailTemplate())) {
            entity.setData(Map.of());
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
