package com.softropic.skillars.infrastructure.ses;

import com.softropic.skillars.infrastructure.email.EmailTransportException;
import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.infrastructure.email.EmailTransportTransientException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.model.AccountSuspendedException;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.LimitExceededException;
import software.amazon.awssdk.services.sesv2.model.MailFromDomainNotVerifiedException;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.SendingPausedException;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story ses-1.1 AC5 — table-driven per the documented classification, including the
 * unrecognised-{@code SdkClientException}-defaults-Transient case.
 */
class SesErrorClassifierTest {

    private final SesErrorClassifier classifier = new SesErrorClassifier();

    static Stream<Object[]> cases() {
        return Stream.of(
            new Object[]{TooManyRequestsException.builder().message("throttled").statusCode(429).build(),
                EmailTransportTransientException.class},
            new Object[]{LimitExceededException.builder().message("limit").statusCode(400).build(),
                EmailTransportTransientException.class},
            new Object[]{SendingPausedException.builder().message("paused").statusCode(400).build(),
                EmailTransportTransientException.class},
            new Object[]{BadRequestException.builder().message("bad request").statusCode(400).build(),
                EmailTransportPermanentException.class},
            new Object[]{NotFoundException.builder().message("not found").statusCode(404).build(),
                EmailTransportPermanentException.class},
            new Object[]{MessageRejectedException.builder().message("rejected").statusCode(400).build(),
                EmailTransportPermanentException.class},
            new Object[]{MailFromDomainNotVerifiedException.builder().message("unverified").statusCode(400).build(),
                EmailTransportTransientException.class},
            new Object[]{AccountSuspendedException.builder().message("suspended").statusCode(400).build(),
                EmailTransportTransientException.class},
            new Object[]{SdkClientException.builder().message("connect timeout").build(),
                EmailTransportTransientException.class},
            new Object[]{ApiCallTimeoutException.builder().message("api call timeout").build(),
                EmailTransportTransientException.class},

            // --- The AwsServiceException status-code branch. Before these cases nothing reached it:
            // every entry above is a *modelled* SES type matched by an earlier `instanceof`, so the
            // whole `statusCode()` block could be deleted (5xx would fall through to the transient
            // default) or inverted (`>= 500` -> permanent) with the suite still green.
            new Object[]{genericAwsException(500, "InternalServiceError"),
                EmailTransportTransientException.class},
            new Object[]{genericAwsException(503, "ServiceUnavailable"),
                EmailTransportTransientException.class},
            new Object[]{genericAwsException(404, "NoSuchEndpoint"),
                EmailTransportPermanentException.class},

            // An unmodelled throttle (SES error code SES does not map to TooManyRequestsException,
            // or a 429 injected by a proxy/WAF in front of the endpoint) must NOT land in the
            // "other 4xx => permanent" bucket — that drops mail for the one condition a retry
            // exists to survive. Same for clock skew on a host with NTP drift, which would
            // otherwise classify 100% of sends as permanent.
            new Object[]{genericAwsException(429, "RequestThrottled"),
                EmailTransportTransientException.class},
            new Object[]{genericAwsException(400, "Throttling"),
                EmailTransportTransientException.class},
            new Object[]{genericAwsException(403, "RequestTimeTooSkewed"),
                EmailTransportTransientException.class}
        );
    }

    /**
     * A plain {@link AwsServiceException} carrying a status code and error code, matched by none of
     * the modelled-type {@code instanceof} checks, so it reaches the status-code branch.
     */
    private static AwsServiceException genericAwsException(int statusCode, String errorCode) {
        return AwsServiceException.builder()
            .message(errorCode + " (" + statusCode + ")")
            .statusCode(statusCode)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).build())
            .build();
    }

    @ParameterizedTest
    @MethodSource("cases")
    void classifiesPerTable(SdkException ex, Class<? extends EmailTransportException> expected) {
        assertThat(classifier.classify(ex)).isInstanceOf(expected);
    }

    @ParameterizedTest
    @MethodSource("cases")
    void neverLeaksTheSdkException(SdkException ex, Class<? extends EmailTransportException> expected) {
        EmailTransportException classified = classifier.classify(ex);
        assertThat(classified).isNotInstanceOf(SdkException.class);
        assertThat(classified.getCause()).isSameAs(ex);
    }

    /**
     * A bare {@link SdkException} is neither an {@code AwsServiceException} nor an
     * {@code SdkClientException} — it represents a genuinely unrecognised SDK exception type (e.g.
     * one introduced by a future SDK upgrade). Defaulting this to Transient, not Permanent, is a
     * deliberate, separate decision from the account-blocked mapping above: defaulting to Permanent
     * would silently drop mail that might have succeeded on retry.
     */
    @org.junit.jupiter.api.Test
    void unrecognisedSdkException_defaultsToTransient() {
        SdkException unknown = SdkException.builder().message("never seen this before").build();
        assertThat(classifier.classify(unknown)).isInstanceOf(EmailTransportTransientException.class);
    }
}
