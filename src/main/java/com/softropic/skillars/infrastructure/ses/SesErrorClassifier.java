package com.softropic.skillars.infrastructure.ses;

import com.softropic.skillars.infrastructure.email.EmailTransportException;
import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.infrastructure.email.EmailTransportTransientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.model.AccountSuspendedException;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.LimitExceededException;
import software.amazon.awssdk.services.sesv2.model.MailFromDomainNotVerifiedException;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendingPausedException;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;

/**
 * Classifies any {@link SdkException} raised by an SES v2 call into the transport-neutral
 * exception taxonomy, per {@code requirements/ses-email-consolidation.md#3.2}:
 *
 * <table>
 *   <caption>Classification table</caption>
 *   <tr><td>{@link TooManyRequestsException} / {@link LimitExceededException}</td><td>Transient</td></tr>
 *   <tr><td>{@link SendingPausedException}</td><td>Transient</td></tr>
 *   <tr><td>HTTP 5xx / {@link SdkClientException} / {@code ApiCallTimeoutException}</td><td>Transient</td></tr>
 *   <tr><td>{@link MessageRejectedException}</td><td>Permanent</td></tr>
 *   <tr><td>{@link MailFromDomainNotVerifiedException} / {@link AccountSuspendedException}</td><td>Transient (see below)</td></tr>
 *   <tr><td>SDK-recognised throttling / clock skew (any status)</td><td>Transient</td></tr>
 *   <tr><td>{@link BadRequestException} / other 4xx</td><td>Permanent</td></tr>
 *   <tr><td>anything else (unrecognised)</td><td>Transient (see below)</td></tr>
 * </table>
 *
 * <p><strong>Why an account-level block ({@code MailFromDomainNotVerifiedException},
 * {@code AccountSuspendedException}) classifies as Transient, not Permanent:</strong> Transient on
 * purpose — the message is fine, the account is blocked; envelopes stay retryable so the scheduler
 * drains them on recovery. Only a block outlasting the envelope deadline loses mail. (This
 * envelope/scheduler language is a Phase 4 concept — for Phase 1 this classifier exists and is
 * tested on its own, since {@code MailManager} doesn't consume it yet.)
 *
 * <p><strong>Why {@code isThrottlingException()}/{@code isClockSkewException()} are consulted
 * before the status-code buckets:</strong> the three typed throttle exceptions above only cover
 * throttles SES models explicitly. A throttle that arrives as a plain {@code SesV2Exception} with
 * an unmodelled error code, or a 429 injected by a proxy/WAF in front of the endpoint, carries a
 * 4xx status and would otherwise land in the "other 4xx ⇒ Permanent" bucket — dropping mail for
 * exactly the condition a retry exists to survive. The same applies to a {@code
 * RequestTimeTooSkewed} 403 on a host with NTP drift, which would otherwise classify every single
 * send as permanent until someone reads the logs. The SDK already ships both predicates on
 * {@link AwsServiceException}; use them rather than re-deriving the error codes here.
 *
 * <p><strong>Why an unrecognised exception also defaults to Transient</strong> — this is a
 * <em>separate</em> design decision from the account-blocked case above, not the same reasoning
 * restated: defaulting to Permanent silently drops mail that might have succeeded on retry;
 * defaulting to Transient costs at most a wasted retry attempt, which is the safe direction to err
 * in when a new (or unmapped) SDK exception type is encountered.
 */
@Slf4j
@Component
public class SesErrorClassifier {

    public EmailTransportException classify(SdkException ex) {
        if (ex instanceof TooManyRequestsException
            || ex instanceof LimitExceededException
            || ex instanceof SendingPausedException) {
            return transientException(ex);
        }
        if (ex instanceof MessageRejectedException) {
            return permanentException(ex);
        }
        if (ex instanceof MailFromDomainNotVerifiedException || ex instanceof AccountSuspendedException) {
            return transientException(ex);
        }
        if (ex instanceof BadRequestException) {
            return permanentException(ex);
        }
        if (ex instanceof SdkClientException) {
            // Covers ApiCallTimeoutException/ApiCallAttemptTimeoutException, connect/read
            // timeouts and DNS failures — all client-side, all worth retrying.
            return transientException(ex);
        }
        if (ex instanceof AwsServiceException aws) {
            if (aws.isThrottlingException() || aws.isClockSkewException()) {
                return transientException(ex);
            }
            int statusCode = aws.statusCode();
            if (statusCode >= 500) {
                return transientException(ex);
            }
            if (statusCode >= 400) {
                return permanentException(ex);
            }
        }
        log.warn("Unrecognised SES SDK exception type {}; defaulting to transient", ex.getClass().getName(), ex);
        return transientException(ex);
    }

    private EmailTransportTransientException transientException(SdkException ex) {
        return new EmailTransportTransientException("SES send failed transiently: " + ex.getMessage(), ex);
    }

    private EmailTransportPermanentException permanentException(SdkException ex) {
        return new EmailTransportPermanentException("SES send failed permanently: " + ex.getMessage(), ex);
    }
}
