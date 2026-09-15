package com.softropic.skillars.infrastructure.email.smtp;

import com.softropic.skillars.infrastructure.email.EmailTransportException;
import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.infrastructure.email.EmailTransportTransientException;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.ParseException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story ses-1.2 AC2 — {@link SmtpErrorClassifier} must preserve {@code
 * MailManager.NON_REPAIRABLE_ERRORS}'s exact pre-story classification: {@link MailParseException},
 * {@link MailPreparationException}, {@link AddressException}, {@link ParseException} → permanent;
 * everything else → transient. This is a pure relocation, so these cases mirror the ones {@code
 * MailManagerResilienceTest} used to pin directly.
 *
 * <p>skillars-deferred-110 AC2, re-scoped by its own code review (owner decision D-1): the
 * {@code MailSendException}/reply-code cases below use the real
 * {@code org.eclipse.angus.mail.smtp} exception types the pinned SMTP provider actually throws
 * (confirmed by disassembling the jar — see {@link SmtpErrorClassifier}'s own javadoc), not a
 * hand-constructed {@code jakarta.mail.SendFailedException} — the earlier version of these tests
 * used the latter and would have stayed green under a full revert to address-array-based
 * classification, proving nothing about the actual fix.
 */
@DisplayName("SmtpErrorClassifier")
class SmtpErrorClassifierTest {

    private final SmtpErrorClassifier classifier = new SmtpErrorClassifier();

    private static MailSendException mailSendExceptionWith(Exception failure) {
        Map<Object, Exception> failedMessages = new LinkedHashMap<>();
        failedMessages.put("original-message", failure);
        return new MailSendException(failedMessages);
    }

    @Test
    @DisplayName("a direct MailParseException classifies as permanent")
    void directMailParseException_isPermanent() {
        EmailTransportException result = classifier.classify(new MailParseException("bad template"));

        assertThat(result).isInstanceOf(EmailTransportPermanentException.class);
    }

    @Test
    @DisplayName("a direct MailPreparationException classifies as permanent")
    void directMailPreparationException_isPermanent() {
        EmailTransportException result = classifier.classify(new MailPreparationException("bad prep"));

        assertThat(result).isInstanceOf(EmailTransportPermanentException.class);
    }

    @Test
    @DisplayName("a MessagingException wrapping an AddressException classifies as permanent")
    void wrappedAddressException_isPermanent() {
        MessagingException wrapped = new MessagingException("bad recipient", new AddressException("not-an-email"));

        EmailTransportException result = classifier.classify(wrapped);

        assertThat(result).isInstanceOf(EmailTransportPermanentException.class);
    }

    @Test
    @DisplayName("a MessagingException wrapping a jakarta.mail.internet.ParseException classifies as permanent")
    void wrappedJakartaParseException_isPermanent() {
        MessagingException wrapped = new MessagingException("malformed header", new ParseException("bad header"));

        EmailTransportException result = classifier.classify(wrapped);

        assertThat(result).isInstanceOf(EmailTransportPermanentException.class);
    }

    @Test
    @DisplayName("a bare MessagingException (e.g. connection timeout) classifies as transient")
    void bareMessagingException_isTransient() {
        EmailTransportException result = classifier.classify(new MessagingException("Connection timed out"));

        assertThat(result).isInstanceOf(EmailTransportTransientException.class);
    }

    @Test
    @DisplayName("the classified exception carries the original as its cause")
    void classifiedException_carriesOriginalAsCause() {
        MessagingException original = new MessagingException("Connection timed out");

        EmailTransportException result = classifier.classify(original);

        assertThat(result.getCause()).isSameAs(original);
    }

    /**
     * The review's own D-1 finding: {@code SMTPTransport.sendMessage} pre-populates
     * {@code validUnsentAddr} with the FULL recipient array before {@code mailFrom()} even runs, so
     * a hard {@code MAIL FROM} 550 throws {@link SMTPSendFailedException} with a non-empty
     * {@code validUnsent} — the old address-array-based classifier misread this as transient.
     * Mutation: reverting to {@code getValidUnsentAddresses().length == 0} turns this RED (the
     * array here is non-empty by construction, matching the real shape).
     */
    @Test
    @DisplayName("a MAIL FROM 550 (SMTPSendFailedException) classifies as permanent, by reply code")
    void mailFrom550_isPermanent() throws AddressException {
        InternetAddress recipient = new InternetAddress("player@example.com");
        SMTPSendFailedException mailFrom550 = new SMTPSendFailedException(
            "MAIL", 550, "550 5.1.0 Sender address rejected", null,
            null, new jakarta.mail.Address[]{recipient}, null);

        EmailTransportException result = classifier.classify(mailSendExceptionWith(mailFrom550));

        assertThat(result).isInstanceOf(EmailTransportPermanentException.class);
    }

    /**
     * A 4xx MAIL FROM rejection (e.g. greylisting, temporary local problem) must stay transient —
     * the counterpart assertion that makes the 550 case above non-vacuous (a "reply code >= 500"
     * check could otherwise be satisfied by "any non-2xx code").
     */
    @Test
    @DisplayName("a MAIL FROM 421 (SMTPSendFailedException) classifies as transient, by reply code")
    void mailFrom421_isTransient() throws AddressException {
        InternetAddress recipient = new InternetAddress("player@example.com");
        SMTPSendFailedException mailFrom421 = new SMTPSendFailedException(
            "MAIL", 421, "421 4.7.0 Service not available", null,
            null, new jakarta.mail.Address[]{recipient}, null);

        EmailTransportException result = classifier.classify(mailSendExceptionWith(mailFrom421));

        assertThat(result).isInstanceOf(EmailTransportTransientException.class);
    }

    /**
     * The review's own CRITICAL finding: {@code readServerResponse()} returns {@code -1} (not an
     * exception) on a dropped connection. {@code rcptTo()}'s per-recipient switch has no case for
     * -1, so it falls to the "unexpected response" branch and throws {@link
     * SMTPAddressFailedException} immediately — whose {@code getValidUnsentAddresses()} is always
     * {@code null} (its constructor never sets it). The old classifier read a null/empty
     * {@code validUnsent} as "nothing left to retry" → permanent, silently and permanently losing
     * mail on an ordinary network blip. Mutation: reverting to the address-array check turns this
     * RED (this exception's {@code validUnsent} is always null, so the old logic always called it
     * permanent here, regardless of the actual reply code).
     */
    @Test
    @DisplayName("a dropped connection during RCPT TO (SMTPAddressFailedException, code -1) classifies as "
        + "transient — CRITICAL regression guard, must never silently lose mail on a network blip")
    void rcptToDroppedConnection_isTransient() throws AddressException {
        InternetAddress recipient = new InternetAddress("player@example.com");
        SMTPAddressFailedException droppedConnection =
            new SMTPAddressFailedException(recipient, "RCPT", -1, "[EOF]");

        EmailTransportException result = classifier.classify(mailSendExceptionWith(droppedConnection));

        assertThat(result).isInstanceOf(EmailTransportTransientException.class);
    }

    @Test
    @DisplayName("a RCPT TO 550 (SMTPAddressFailedException) classifies as permanent, by reply code")
    void rcptTo550_isPermanent() throws AddressException {
        InternetAddress recipient = new InternetAddress("no-such-user@example.com");
        SMTPAddressFailedException rcptTo550 =
            new SMTPAddressFailedException(recipient, "RCPT", 550, "550 5.1.1 No such user");

        EmailTransportException result = classifier.classify(mailSendExceptionWith(rcptTo550));

        assertThat(result).isInstanceOf(EmailTransportPermanentException.class);
    }

    @Test
    @DisplayName("a RCPT TO 450 (SMTPAddressFailedException, mailbox temporarily unavailable) classifies as "
        + "transient, by reply code")
    void rcptTo450_isTransient() throws AddressException {
        InternetAddress recipient = new InternetAddress("player@example.com");
        SMTPAddressFailedException rcptTo450 =
            new SMTPAddressFailedException(recipient, "RCPT", 450, "450 4.2.0 Mailbox temporarily unavailable");

        EmailTransportException result = classifier.classify(mailSendExceptionWith(rcptTo450));

        assertThat(result).isInstanceOf(EmailTransportTransientException.class);
    }

    /**
     * skillars-deferred-111 AC3: the real shape a RCPT TO rejection actually arrives in — confirmed
     * by disassembling {@code SMTPTransport.rcptTo()} in the pinned angus-mail jar, and by
     * {@code AdapterWrapDepthTest} driving a real fake-SMTP-server 550 through the real adapter end
     * to end. {@code rcptTo()} never throws {@link SMTPAddressFailedException} directly — it throws
     * a generic {@link jakarta.mail.SendFailedException} ({@code "Invalid Addresses"}) whose {@code
     * getCause()} is the typed exception above. Every other test in this class hand-constructs the
     * typed exception as the failed-message value directly, which is what {@code
     * MailSendException.getFailedMessages()} would hold for a MAIL FROM rejection, but never for a
     * RCPT TO one. {@code // Mutation:} reverting {@code smtpReturnCode}'s one-level cause-unwrap
     * turns this red (falls through to {@code isPermanentByCauseChain}, which has no SMTP-specific
     * entry, and misclassifies transient).
     */
    @Test
    @DisplayName("a RCPT TO 550, in the generic SendFailedException wrapper rcptTo() actually throws it in, "
        + "still classifies as permanent")
    void rcptTo550_wrappedInGenericSendFailedException_isPermanent() throws AddressException {
        InternetAddress recipient = new InternetAddress("no-such-user@example.com");
        SMTPAddressFailedException typed =
            new SMTPAddressFailedException(recipient, "RCPT", 550, "550 5.1.1 No such user");
        jakarta.mail.SendFailedException wrapped = new jakarta.mail.SendFailedException("Invalid Addresses", typed);

        EmailTransportException result = classifier.classify(mailSendExceptionWith(wrapped));

        assertThat(result).isInstanceOf(EmailTransportPermanentException.class);
    }

    /**
     * The transient counterpart, making the case above non-vacuous: a genuinely transient RCPT TO
     * code, wrapped the same real way, must not flip to permanent just because unwrapping happens.
     */
    @Test
    @DisplayName("a RCPT TO 450, in the generic SendFailedException wrapper rcptTo() actually throws it in, "
        + "still classifies as transient")
    void rcptTo450_wrappedInGenericSendFailedException_isTransient() throws AddressException {
        InternetAddress recipient = new InternetAddress("player@example.com");
        SMTPAddressFailedException typed =
            new SMTPAddressFailedException(recipient, "RCPT", 450, "450 4.2.0 Mailbox temporarily unavailable");
        jakarta.mail.SendFailedException wrapped = new jakarta.mail.SendFailedException("Invalid Addresses", typed);

        EmailTransportException result = classifier.classify(mailSendExceptionWith(wrapped));

        assertThat(result).isInstanceOf(EmailTransportTransientException.class);
    }

    @Test
    @DisplayName("a permanent SMTPSenderFailedException (chained, single-address form) classifies as "
        + "permanent, by reply code")
    void smtpSenderFailedException_permanentCode_isPermanent() throws AddressException {
        InternetAddress sender = new InternetAddress("noreply@skillars.com");
        SMTPSenderFailedException senderFailed =
            new SMTPSenderFailedException(sender, "MAIL", 553, "553 5.1.8 Sender address rejected");

        EmailTransportException result = classifier.classify(mailSendExceptionWith(senderFailed));

        assertThat(result).isInstanceOf(EmailTransportPermanentException.class);
    }

    @Test
    @DisplayName("a generic SendFailedException with no recognised SMTP return code falls back to the "
        + "cause-chain walk, not a default-permanent guess")
    void unrecognisedSendFailedExceptionShape_fallsBackToTransient() {
        jakarta.mail.SendFailedException unrecognised = new jakarta.mail.SendFailedException("unknown failure");

        EmailTransportException result = classifier.classify(mailSendExceptionWith(unrecognised));

        assertThat(result).isInstanceOf(EmailTransportTransientException.class);
    }

    /**
     * skillars-deferred-111 AC9: constructed the way {@code JavaMailSenderImpl.doSend} actually
     * throws it — {@code new MailAuthenticationException(authFailure)}, at connect time, never
     * wrapped in a {@code MailSendException} (confirmed by disassembling spring-context-support
     * 6.2.19's {@code doSend} bytecode: {@code AuthenticationFailedException} is caught in its own
     * dedicated handler, separate from the per-message loop that populates the failed-messages map).
     * {@code // Mutation:} removing {@code MailAuthenticationException} from {@code
     * NON_REPAIRABLE_ERRORS} turns this red (falls through to transient — the exact bug: a wrong or
     * expired password retried forever).
     */
    @Test
    @DisplayName("a wrong/expired SMTP password (MailAuthenticationException, as JavaMailSenderImpl.doSend "
        + "actually throws it) classifies as permanent")
    void mailAuthenticationException_isPermanent() {
        jakarta.mail.AuthenticationFailedException authFailure =
            new jakarta.mail.AuthenticationFailedException("535 5.7.8 Authentication failed");
        MailAuthenticationException wrapped = new MailAuthenticationException(authFailure);

        EmailTransportException result = classifier.classify(wrapped);

        assertThat(result).isInstanceOf(EmailTransportPermanentException.class);
    }

    @Test
    @DisplayName("a bare jakarta.mail.AuthenticationFailedException (defensive, unwrapped) also classifies as "
        + "permanent")
    void bareAuthenticationFailedException_isPermanent() {
        EmailTransportException result =
            classifier.classify(new jakarta.mail.AuthenticationFailedException("535 5.7.8 Authentication failed"));

        assertThat(result).isInstanceOf(EmailTransportPermanentException.class);
    }

    @Test
    @DisplayName("classifying a MailSendException attaches the real underlying failure as the cause, not the "
        + "outer MailSendException (whose own cause is always null)")
    void mailSendException_causeChainPreservesTheRealFailure() throws AddressException {
        InternetAddress recipient = new InternetAddress("no-such-user@example.com");
        SMTPAddressFailedException rcptTo550 =
            new SMTPAddressFailedException(recipient, "RCPT", 550, "550 5.1.1 No such user");
        MailSendException mailSendException = mailSendExceptionWith(rcptTo550);

        EmailTransportException result = classifier.classify(mailSendException);

        assertThat(result.getCause())
            .as("the cause must be the real per-recipient failure, not the outer MailSendException "
                + "(whose getCause() is always null, per JavaMailSenderImpl.doSend's MailSendException(Map) path)")
            .isSameAs(rcptTo550);
    }
}
