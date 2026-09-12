package com.softropic.skillars.infrastructure.ses;

import com.softropic.skillars.infrastructure.email.EmailAddressParser;
import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.infrastructure.email.EmailTransportTransientException;
import com.softropic.skillars.infrastructure.email.OutboundEmailRequest;
import com.softropic.skillars.infrastructure.email.OutboundEmailResult;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story ses-1.1 AC4/AC5 — {@link SesEmailSender}'s request-shape mapping for all three body
 * combinations, the returned {@code messageId}, timeout classification, and the unknown-exception
 * WARN-before-wrap behaviour.
 *
 * <p>{@link SesV2Client#sendEmail(java.util.function.Consumer)} is a default method that builds a
 * real {@link SendEmailRequest} and delegates to the abstract {@code sendEmail(SendEmailRequest)} —
 * which itself is a default method whose body simply throws {@code UnsupportedOperationException}
 * (the real implementation lives only in the SDK's internal client class). So the mock is left on
 * Mockito's normal default answer (not {@code CALLS_REAL_METHODS}, which would trip that
 * unimplemented default and throw during stub setup) and only the {@code Consumer} overload is told
 * to call its real method — that real method then re-enters the mock via the
 * {@code sendEmail(SendEmailRequest)} overload, which is stubbed normally, letting the actually-built
 * request be captured and inspected.
 */
class SesEmailSenderTest {

    private SesV2Client client;
    private SesProperties props;
    private SesEmailSender sender;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger senderLogger;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(SesV2Client.class);
        when(client.sendEmail(any(java.util.function.Consumer.class))).thenCallRealMethod();
        props = new SesProperties();
        props.setFromAddress("noreply@example.com");
        // Story ses-1.3 AC3: SesEmailSender's 5th constructor argument. A mock's default no-op
        // acquireOrThrow() never rejects — these tests are about request-shape mapping, not rate
        // limiting, which SesSendRateLimiterTest covers on its own.
        sender = new SesEmailSender(
            client, props, new EmailAddressParser(), new SesErrorClassifier(), mock(SesSendRateLimiter.class));

        senderLogger = (Logger) LoggerFactory.getLogger(SesErrorClassifier.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        senderLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        senderLogger.detachAppender(logAppender);
    }

    private void stubResponse(String messageId) {
        when(client.sendEmail(any(SendEmailRequest.class)))
            .thenReturn(SendEmailResponse.builder().messageId(messageId).build());
    }

    private SendEmailRequest capturedRequest() {
        org.mockito.ArgumentCaptor<SendEmailRequest> captor =
            org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        org.mockito.Mockito.verify(client).sendEmail(captor.capture());
        return captor.getValue();
    }

    @Test
    void htmlOnly_buildsHtmlPartAndReturnsMessageId() {
        stubResponse("mid-html");
        OutboundEmailRequest request = new OutboundEmailRequest(
            "to@example.com", "subject", "<html/>", null, "cid-1");

        OutboundEmailResult result = sender.send(request);

        assertThat(result).isEqualTo(new OutboundEmailResult("mid-html"));
        SendEmailRequest sent = capturedRequest();
        assertThat(sent.content().simple().body().html().data()).isEqualTo("<html/>");
        assertThat(sent.content().simple().body().text()).isNull();
        assertThat(sent.destination().toAddresses()).containsExactly("to@example.com");
        assertThat(sent.emailTags()).anySatisfy(tag -> {
            assertThat(tag.name()).isEqualTo("correlationId");
            assertThat(tag.value()).isEqualTo("cid-1");
        });
    }

    @Test
    void textOnly_buildsTextPartOnly() {
        stubResponse("mid-text");
        OutboundEmailRequest request = new OutboundEmailRequest(
            "to@example.com", "subject", null, "plain text", "cid-2");

        sender.send(request);

        SendEmailRequest sent = capturedRequest();
        assertThat(sent.content().simple().body().text().data()).isEqualTo("plain text");
        assertThat(sent.content().simple().body().html()).isNull();
    }

    @Test
    void bothBodiesPresent_buildsBothParts() {
        stubResponse("mid-both");
        OutboundEmailRequest request = new OutboundEmailRequest(
            "to@example.com", "subject", "<html/>", "plain text", "cid-3");

        sender.send(request);

        SendEmailRequest sent = capturedRequest();
        assertThat(sent.content().simple().body().html().data()).isEqualTo("<html/>");
        assertThat(sent.content().simple().body().text().data()).isEqualTo("plain text");
    }

    @Test
    void configurationSetAndReplyTo_areSetWhenConfigured() {
        props.setConfigurationSet("my-config-set");
        props.setReplyToAddress("Display Name <reply@example.com>");
        stubResponse("mid-cfg");

        sender.send(new OutboundEmailRequest("to@example.com", "subject", "<html/>", null, "cid-4"));

        SendEmailRequest sent = capturedRequest();
        assertThat(sent.configurationSetName()).isEqualTo("my-config-set");
        // The raw, unparsed reply-to string is preserved on the wire — the parser is validation-only.
        assertThat(sent.replyToAddresses()).containsExactly("Display Name <reply@example.com>");
    }

    @Test
    void malformedRecipient_isRejectedWithZeroSdkInteraction() {
        OutboundEmailRequest request = new OutboundEmailRequest(
            "not-an-address", "subject", "<html/>", null, "cid-5");

        assertThatThrownBy(() -> sender.send(request))
            .isInstanceOf(EmailTransportPermanentException.class);

        verifyNoInteractions(client);
    }

    @Test
    void apiCallTimeout_surfacesAsTransientNotUncaughtSdkType() {
        when(client.sendEmail(any(SendEmailRequest.class)))
            .thenThrow(ApiCallTimeoutException.builder().message("timed out").build());

        assertThatThrownBy(() -> sender.send(
            new OutboundEmailRequest("to@example.com", "subject", "<html/>", null, "cid-6")))
            .isInstanceOf(EmailTransportTransientException.class)
            .isNotInstanceOf(SdkException.class);
    }

    @Test
    void unknownSdkException_logsWarnBeforeWrapping() {
        SdkException unknown = SdkException.builder().message("brand new exception type").build();
        when(client.sendEmail(any(SendEmailRequest.class))).thenThrow(unknown);

        assertThatThrownBy(() -> sender.send(
            new OutboundEmailRequest("to@example.com", "subject", "<html/>", null, "cid-7")))
            .isInstanceOf(EmailTransportTransientException.class);

        assertThat(logAppender.list)
            .anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains(SdkException.class.getName());
            });
    }

    /**
     * SES {@code MessageTag} values accept only ASCII letters, digits, {@code _} and {@code -}.
     * {@code OutboundEmailRequest} constrains {@code correlationId} for presence only and documents
     * it as opaque, and {@code LoggingEmailSender} applies a different, filesystem-oriented filter
     * that permits {@code .} — so an id containing anything else used to succeed on every
     * log-transport profile and fail <strong>prod only</strong>, where SES rejects the whole
     * SendEmail with a BadRequestException that classifies as permanent: the entire email dropped
     * over a tag (code review 2026-09-11).
     */
    @Test
    void correlationIdWithTagUnsafeCharacters_isSanitisedBeforeReachingSes() {
        stubResponse("mid");

        sender.send(new OutboundEmailRequest(
            "to@example.com", "subject", "<html/>", null, "envelope:1234/attempt 2.x"));

        SendEmailRequest captured = capturedRequest();
        assertThat(captured.emailTags()).hasSize(1);
        assertThat(captured.emailTags().get(0).value())
            .isEqualTo("envelope_1234_attempt_2_x")
            .matches("[A-Za-z0-9_-]+");
    }

    @Test
    void correlationIdLongerThanTheTagLimit_isTruncated() {
        stubResponse("mid");

        sender.send(new OutboundEmailRequest(
            "to@example.com", "subject", "<html/>", null, "a".repeat(400)));

        assertThat(capturedRequest().emailTags().get(0).value()).hasSize(256);
    }

    /**
     * {@code OutboundEmailResult} documents {@code messageId} as never null and Phase 4 persists it,
     * but {@code SendEmailResponse.messageId()} is a nullable SDK member. The send succeeded, so
     * failing here would lose a delivered email — substitute a marker and warn instead.
     */
    @Test
    void responseWithoutMessageId_doesNotProduceANullResult() {
        // The class-level appender is attached to SesErrorClassifier's logger; this WARN comes from
        // SesEmailSender itself, so it needs its own appender.
        Logger thisSenderLogger = (Logger) LoggerFactory.getLogger(SesEmailSender.class);
        ListAppender<ILoggingEvent> ownAppender = new ListAppender<>();
        ownAppender.start();
        thisSenderLogger.addAppender(ownAppender);
        try {
            when(client.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().build());

            OutboundEmailResult result = sender.send(
                new OutboundEmailRequest("to@example.com", "subject", "<html/>", null, "cid"));

            assertThat(result.messageId()).isNotNull().isEqualTo("ses:unknown-message-id");
            assertThat(ownAppender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("no messageId");
            });
        } finally {
            thisSenderLogger.detachAppender(ownAppender);
        }
    }
}