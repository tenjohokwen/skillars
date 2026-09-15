package com.softropic.skillars.infrastructure.email.smtp;

import com.softropic.skillars.infrastructure.email.EmailPiiSanitizer;
import com.softropic.skillars.infrastructure.email.EmailTransportException;
import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.infrastructure.email.EmailTransportTransientException;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.ParseException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.springframework.mail.MailAuthenticationException;
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
        MailParseException.class, MailPreparationException.class, AddressException.class, ParseException.class,
        // skillars-deferred-111 AC9: JavaMailSenderImpl.doSend catches jakarta.mail's
        // AuthenticationFailedException at connect time and rethrows Spring's MailAuthenticationException
        // (a MailException, not a MailSendException) with it as the cause — confirmed by disassembling
        // spring-context-support 6.2.19's doSend bytecode. Neither type was listed here before, so a wrong
        // or expired SMTP password (the exact scenario docker-compose.local.yml's bogus
        // ${GMX_PASSWORD:dev_gmx_password} default produces) took the plain isPermanentByCauseChain branch,
        // matched nothing, and was retried forever — 3 in-process RetryTemplate attempts plus up to 6
        // EmailRetryScheduler re-drives against a credential that can never succeed. Both listed: the
        // former is what actually propagates (confirmed), the latter as a direct-cause-position safety
        // net if a future JavaMail version ever surfaces it unwrapped.
        MailAuthenticationException.class, AuthenticationFailedException.class);

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

    /**
     * skillars-deferred-111 AC3: a RCPT TO rejection does not surface as a typed
     * {@link SMTPAddressFailedException} directly — confirmed by disassembling the pinned
     * {@code org.eclipse.angus:jakarta.mail:2.0.5} jar's {@code SMTPTransport.rcptTo()}: after the
     * per-recipient response loop, a failure throws a plain {@link SendFailedException}
     * ({@code new SendFailedException("Invalid Addresses", mex, ...)}) whose {@code getCause()} is
     * the real typed exception ({@code mex}) carrying the actual reply code. Only a MAIL FROM
     * rejection ({@code mailFrom()}, thrown as a bare {@link SMTPSendFailedException}) is ever the
     * direct exception type. Before this fix, {@code smtpReturnCode} only inspected the outer
     * exception's own type — for a RCPT TO rejection that outer type is always the generic {@link
     * SendFailedException}, which matches none of the three {@code instanceof} checks, so this
     * method returned empty and every real single-recipient RCPT TO rejection fell through to
     * {@link #isPermanentByCauseChain}, whose {@link #NON_REPAIRABLE_ERRORS} list has no SMTP-
     * specific entry either — misclassifying it transient and retrying it forever. Caught by
     * skillars-deferred-111 AC3's real-adapter test, which drives an actual RCPT TO 550 through a
     * real {@link SmtpEmailSender} end to end, something no prior test in this suite did (every
     * existing case here hand-constructs the typed exception directly as the failed-message value).
     * One level of cause-unwrap is enough: {@code SendFailedException}'s own cause is never itself
     * wrapped further for this shape.
     */
    private static OptionalInt smtpReturnCode(Throwable t) {
        OptionalInt direct = typedSmtpReturnCode(t);
        if (direct.isPresent()) {
            return direct;
        }
        Throwable cause = t.getCause();
        return cause != null && cause != t ? typedSmtpReturnCode(cause) : OptionalInt.empty();
    }

    private static OptionalInt typedSmtpReturnCode(Throwable t) {
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

    /**
     * skillars-deferred-111 AC11 (owner decision: full sanitizer): {@code message} may be a
     * per-recipient failure's own message, which can embed the recipient address — masked here
     * before it becomes part of this returned exception's own message (surfaced by {@code
     * MailManager}'s logging and persisted into {@code envelope_entity.error}). {@code cause} is
     * left as the original, unsanitized throwable — {@code MailManager} masks the FULL rendered
     * stack trace (this exception's message plus every cause's own message) at its own log/persist
     * sites, which is the choke point that actually controls what reaches a log line or the DB.
     */
    private static EmailTransportException build(boolean permanent, String message, Throwable cause) {
        String sanitizedMessage = EmailPiiSanitizer.sanitize(message);
        return permanent
            ? new EmailTransportPermanentException("SMTP send failed permanently: " + sanitizedMessage, cause)
            : new EmailTransportTransientException("SMTP send failed transiently: " + sanitizedMessage, cause);
    }
}
