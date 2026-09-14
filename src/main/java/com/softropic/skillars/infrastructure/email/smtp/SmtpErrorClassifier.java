package com.softropic.skillars.infrastructure.email.smtp;

import com.softropic.skillars.infrastructure.email.EmailTransportException;
import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.infrastructure.email.EmailTransportTransientException;

import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.ParseException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

/**
 * Story ses-1.2 AC2: preserves {@code MailManager.NON_REPAIRABLE_ERRORS}'s exact classification —
 * {@link MailParseException}, {@link MailPreparationException}, {@link AddressException},
 * {@link ParseException} → {@link EmailTransportPermanentException}; every other SMTP failure →
 * {@link EmailTransportTransientException} — relocated to where the SMTP failure actually
 * originates. This is a pure relocation: SMTP retry semantics for dev/uat must not change.
 *
 * <p>skillars-deferred-110 AC2, re-scoped by its own code review (2026-09-14, owner decision D-1):
 * {@link MailSendException} (thrown by {@code JavaMailSenderImpl.doSend} via its {@code
 * MailSendException(Map)} constructor, which leaves {@link MailSendException#getCause()} {@code
 * null} — confirmed by direct execution) never surfaces to the direct/cause/cause-of-cause walk
 * below; its real failure detail lives in {@link MailSendException#getFailedMessages()}.
 *
 * <p><strong>Classification for a failed message keys off the SMTP reply code, not address-array
 * presence.</strong> The original AC2 fix classified a {@link SendFailedException} permanent when
 * {@link SendFailedException#getValidUnsentAddresses()} was empty — this looked reasonable but was
 * wrong in both directions, confirmed by disassembling the pinned {@code org.eclipse.angus:
 * jakarta.mail:2.0.5} implementation:
 * <ul>
 *   <li>{@code SMTPTransport.sendMessage} pre-populates its {@code validUnsentAddr} field with the
 *       <strong>full</strong> recipient array before {@code mailFrom()} even runs (bytecode offset
 *       133, before the {@code mailFrom()} call at offset 276). A hard {@code MAIL FROM} rejection
 *       (550/551/553/501/503) throws {@link SMTPSendFailedException} carrying that full array as
 *       {@code validUnsent} — so the old logic classified a permanent sender-address rejection
 *       <strong>transient</strong>, and {@code EmailRetryScheduler} re-drove it forever.</li>
 *   <li>{@code readServerResponse()} returns {@code -1} (not an exception) on a dropped connection
 *       (EOF from {@code readLine()}). Inside {@code rcptTo()}'s per-recipient response switch,
 *       {@code -1} matches no known code and falls to the "unexpected response" branch, which
 *       immediately throws {@link SMTPAddressFailedException} — whose {@code getValidUnsentAddresses()}
 *       is always {@code null} (its constructor only calls the message-only {@code super(msg)}). The
 *       old logic therefore classified a routine dropped connection during {@code RCPT TO}
 *       <strong>permanent</strong> — a transient network blip silently and permanently lost the
 *       email. This was the review's CRITICAL finding.</li>
 * </ul>
 * The reply code itself does not lie about either failure mode: a genuine 5xx is permanent, a 4xx
 * (or an unreadable/-1 "no response at all") is not. {@link #smtpReturnCode(Throwable)} reads it
 * from whichever of {@link SMTPSendFailedException}, {@link SMTPAddressFailedException} or {@link
 * SMTPSenderFailedException} the failure actually is (none share a common typed supertype beyond
 * {@link SendFailedException} itself, so each is checked explicitly) — a code outside those three
 * types, or a missing/non-5xx code, classifies transient (never lose mail to an unrecognised shape;
 * fall through to the cause-chain walk against {@link #NON_REPAIRABLE_ERRORS} only for a non-{@link
 * SendFailedException}, e.g. a checked {@code MessagingException} carrying an {@link
 * AddressException}).
 *
 * <p>When classifying a {@link MailSendException}, the representative failure used for both the
 * verdict's message and its {@code cause} is the first <em>permanent</em> entry in {@code
 * getFailedMessages()} if one exists, else the first entry overall — never the outer {@code
 * MailSendException} itself (whose own cause is always {@code null}, so using it as the returned
 * exception's cause would silently discard the real underlying failure from the cause chain,
 * confirmed by the code review). In practice this codebase only ever sends one recipient per
 * {@code javaMailSender.send(mimeMessage)} call ({@link SmtpEmailSender} builds one {@code
 * MimeMessage} per {@link com.softropic.skillars.infrastructure.email.OutboundEmailRequest}), so
 * {@code getFailedMessages()} holds exactly one entry today — the "any permanent among several"
 * semantics exist for correctness if that ever changes, not because it is currently exercised.
 */
@Component
public class SmtpErrorClassifier {

    private static final List<Class<? extends Exception>> NON_REPAIRABLE_ERRORS = List.of(
        MailParseException.class, MailPreparationException.class, AddressException.class, ParseException.class);

    /**
     * Classifies any exception raised while building/sending the SMTP message. For a {@link
     * MailSendException}, inspects {@link MailSendException#getFailedMessages()} (see class
     * javadoc). For every other exception, walks the same two levels of cause {@code
     * MailManager.isRetryable} used to walk (direct, cause, cause-of-cause) — the known wrapping
     * depths a JavaMail/Spring-mail failure can arrive at (e.g. a checked {@code
     * MessagingException} carrying an {@link AddressException} as its cause).
     */
    public EmailTransportException classify(Exception ex) {
        if (ex instanceof MailSendException mailSendException) {
            return classifyMailSendException(mailSendException);
        }
        return build(isPermanentByCauseChain(ex), ex.getMessage(), ex);
    }

    private EmailTransportException classifyMailSendException(MailSendException mailSendException) {
        Collection<Exception> failedMessages = mailSendException.getFailedMessages().values();
        if (failedMessages.isEmpty()) {
            // Defensive: no failed-message detail to inspect (e.g. the (String, Throwable)
            // constructor, never used by JavaMailSenderImpl.doSend for a per-recipient failure but
            // not structurally impossible for a future caller). Fall back to the cause chain, same
            // as any other exception.
            return build(isPermanentByCauseChain(mailSendException), mailSendException.getMessage(), mailSendException);
        }

        Optional<Exception> permanentFailure = failedMessages.stream()
            .filter(this::isPermanentFailedMessage)
            .findFirst();
        Exception representative = permanentFailure.orElseGet(() -> failedMessages.iterator().next());

        return build(permanentFailure.isPresent(), representative.getMessage(), representative);
    }

    private boolean isPermanentFailedMessage(Exception failedMessageException) {
        OptionalInt returnCode = smtpReturnCode(failedMessageException);
        if (returnCode.isPresent()) {
            int code = returnCode.getAsInt();
            return code >= 500 && code <= 599;
        }
        return isPermanentByCauseChain(failedMessageException);
    }

    private static OptionalInt smtpReturnCode(Throwable t) {
        if (t instanceof SMTPSendFailedException e) {
            return OptionalInt.of(e.getReturnCode());
        }
        if (t instanceof SMTPAddressFailedException e) {
            return OptionalInt.of(e.getReturnCode());
        }
        if (t instanceof SMTPSenderFailedException e) {
            return OptionalInt.of(e.getReturnCode());
        }
        return OptionalInt.empty();
    }

    private boolean isPermanentByCauseChain(Throwable ex) {
        Throwable direct = ex;
        Throwable cause = ex.getCause();
        Throwable causeOfCause = cause != null ? cause.getCause() : null;

        return Stream.of(direct, cause, causeOfCause)
            .filter(Objects::nonNull)
            .anyMatch(t -> NON_REPAIRABLE_ERRORS.stream().anyMatch(c -> c.isInstance(t)));
    }

    private static EmailTransportException build(boolean permanent, String message, Throwable cause) {
        return permanent
            ? new EmailTransportPermanentException("SMTP send failed permanently: " + message, cause)
            : new EmailTransportTransientException("SMTP send failed transiently: " + message, cause);
    }
}
