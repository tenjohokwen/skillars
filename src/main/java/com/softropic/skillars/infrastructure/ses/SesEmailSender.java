package com.softropic.skillars.infrastructure.ses;

import com.softropic.skillars.infrastructure.email.EmailAddressParser;
import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.infrastructure.email.OutboundEmailRequest;
import com.softropic.skillars.infrastructure.email.OutboundEmailResult;
import com.softropic.skillars.infrastructure.email.OutboundEmailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

import java.util.regex.Pattern;

/**
 * The SES v2 {@link OutboundEmailSender}, replacing the old {@code SesEmailService}/
 * {@code SesEmailServiceImpl} pair (story ses-1.1 AC4). {@code @Profile("!dev")} is dropped —
 * transport selection is now purely {@code app.email.transport}, with no profile dimension.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.email.transport", havingValue = "ses")
public class SesEmailSender implements OutboundEmailSender {

    private static final String CORRELATION_ID_TAG_NAME = "correlationId";

    /**
     * SES {@code MessageTag} values accept only ASCII letters, digits, {@code _} and {@code -}, up
     * to 256 characters. {@link OutboundEmailRequest} deliberately constrains {@code correlationId}
     * for <em>presence</em> only — it is documented as opaque — and {@code LoggingEmailSender}
     * applies its own, different filesystem-oriented filter (which permits {@code .}, where SES
     * does not). Without the same treatment here, a caller whose id contains anything outside this
     * set succeeds on every {@code log}-transport profile and fails <strong>prod only</strong>,
     * where SES rejects the whole {@code SendEmail} with a {@code BadRequestException} that
     * classifies as permanent — the entire email dropped over a tag. Per §6.3, format rules belong
     * with the transport, so the sanitisation lives here rather than in the port.
     */
    private static final Pattern UNSAFE_TAG_VALUE_CHARS = Pattern.compile("[^A-Za-z0-9_-]");
    private static final int MAX_TAG_VALUE_LENGTH = 256;

    private final SesV2Client sesV2Client;
    private final SesProperties props;
    private final EmailAddressParser addressParser;
    private final SesErrorClassifier errorClassifier;
    private final SesSendRateLimiter rateLimiter;

    @Override
    public OutboundEmailResult send(OutboundEmailRequest request) {
        validateRecipient(request.toAddress());
        // Story ses-1.3 AC3: after validation (a malformed address must not consume a permit for a
        // send that was never going to succeed) and before the SDK call.
        rateLimiter.acquireOrThrow();

        try {
            SendEmailResponse response = sesV2Client.sendEmail(r -> {
                r.fromEmailAddress(props.getFromAddress())
                    // The RAW toAddress string is sent to the SDK — the parser above is used for
                    // validation only, never to rewrite the outgoing value.
                    .destination(d -> d.toAddresses(request.toAddress()))
                    .content(c -> c.simple(m -> {
                        m.subject(s -> s.data(request.subject()));
                        m.body(b -> {
                            if (isPresent(request.htmlBody())) {
                                b.html(h -> h.data(request.htmlBody()));
                            }
                            if (isPresent(request.textBody())) {
                                b.text(t -> t.data(request.textBody()));
                            }
                        });
                    }))
                    .emailTags(t -> t.name(CORRELATION_ID_TAG_NAME)
                        .value(sanitiseTagValue(request.correlationId())));

                if (isPresent(props.getConfigurationSet())) {
                    r.configurationSetName(props.getConfigurationSet());
                }
                if (isPresent(props.getReplyToAddress())) {
                    // The raw, unparsed string, so a display-name form is preserved on the wire.
                    r.replyToAddresses(props.getReplyToAddress());
                }
            });
            return new OutboundEmailResult(requireMessageId(response, request));
        } catch (SdkException ex) {
            throw errorClassifier.classify(ex);
        }
    }

    private void validateRecipient(String toAddress) {
        try {
            addressParser.validateSingle(toAddress);
        } catch (IllegalArgumentException ex) {
            throw new EmailTransportPermanentException(
                "invalid recipient address: " + toAddress, ex);
        }
    }

    /**
     * {@link OutboundEmailResult} documents {@code messageId} as never null, and Phase 4 persists
     * it. {@code SendEmailResponse.messageId()} is a nullable SDK member, so a 200 with no
     * {@code MessageId} in the body would quietly break that contract. The send did succeed, so
     * failing here would lose a delivered email; substitute a marker and make the anomaly visible.
     */
    private String requireMessageId(SendEmailResponse response, OutboundEmailRequest request) {
        String messageId = response.messageId();
        if (messageId != null) {
            return messageId;
        }
        log.warn("SES returned no messageId for correlationId={}; substituting a marker",
            request.correlationId());
        return "ses:unknown-message-id";
    }

    private static String sanitiseTagValue(String correlationId) {
        String cleaned = UNSAFE_TAG_VALUE_CHARS.matcher(correlationId).replaceAll("_");
        return cleaned.length() > MAX_TAG_VALUE_LENGTH
            ? cleaned.substring(0, MAX_TAG_VALUE_LENGTH)
            : cleaned;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
