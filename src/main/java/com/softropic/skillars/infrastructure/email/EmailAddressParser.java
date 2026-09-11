package com.softropic.skillars.infrastructure.email;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.stereotype.Component;

/**
 * The single implementation used everywhere an email address string needs validating: the SES
 * adapter's recipient check (AC4) and {@code SesPropertiesValidator}'s {@code fromAddress}/
 * {@code replyToAddress} checks (AC7). No second regex anywhere.
 *
 * <p>Accepts both RFC 5322 forms — {@code user@domain} and {@code Display Name <user@domain>} —
 * and returns the bare address. Built on {@code jakarta.mail.internet.InternetAddress}, already on
 * the classpath via {@code spring-boot-starter-mail} and staying there until Phase 6.
 *
 * <p><strong>Uses strict parsing plus an explicit {@code .validate()} call, not the bare
 * constructor.</strong> {@code new InternetAddress(raw)} does not throw for garbage like
 * {@code "not-an-address"} — RFC 822 syntax checking only happens via {@link InternetAddress#validate()}
 * or {@link InternetAddress#parse(String, boolean)} with {@code strict = true}. Getting this wrong
 * makes this class's own malformed-input tests pass vacuously.
 *
 * <p>A value that parses to <strong>more than one</strong> address (a comma-separated string, e.g.
 * {@code "victim@x.com, attacker@y.com"}) is rejected outright, not silently narrowed to the first
 * — a single request field must not smuggle a second recipient past validation.
 *
 * <p><strong>RFC 822 group syntax is rejected explicitly, because the length check alone does not
 * catch it.</strong> {@code InternetAddress.parse("undisclosed: victim@x.com, attacker@y.com;",
 * true)} returns an array of length <strong>one</strong> whose single element has
 * {@code isGroup() == true} and whose {@code getAddress()} is the entire group string — verified
 * against the resolved {@code angus-mail} jar. Without the {@link InternetAddress#isGroup()} guard
 * below, {@code parsed.length != 1} never fires for this input class and the multi-recipient
 * promise above is false.
 *
 * <p><strong>The raw value must already be canonical.</strong> Callers send the raw string on the
 * wire while using this method only for validation (see {@code SesEmailSender}), so a value that
 * passes only <em>because</em> the parser normalised it away would be validated in one form and
 * transmitted in another. {@code "  a@b.com  "}, {@code "a@b.com\n"} and {@code "a@b.com,"} all
 * parse clean and normalise to {@code a@b.com} — and then fail at the transport, as a permanent
 * error, on a value this class reported as valid. Leading/trailing whitespace, embedded CR/LF and
 * a dangling comma are therefore rejected up front.
 */
@Component
public class EmailAddressParser {

    /**
     * Validates {@code raw} as exactly one RFC 5322 address and returns its bare form (no display
     * name). Never rewrites the value the caller sends on the wire — this method is for validation
     * only.
     *
     * @throws IllegalArgumentException if {@code raw} is not a single, syntactically valid address
     */
    public String validateSingle(String raw) {
        if (raw == null) {
            // InternetAddress.parse dereferences before any syntax check, so without this guard a
            // null escapes as an NPE — past SesEmailSender's IllegalArgumentException-only
            // conversion, past OutboundEmailSender's declared taxonomy, and out of an AFTER_COMMIT
            // listener. The javadoc promises IllegalArgumentException; keep that promise.
            throw new IllegalArgumentException("email address must not be null");
        }
        rejectNonCanonical(raw);

        InternetAddress[] parsed;
        try {
            parsed = InternetAddress.parse(raw, true);
        } catch (AddressException ex) {
            throw new IllegalArgumentException("malformed email address: " + raw, ex);
        }
        if (parsed.length != 1) {
            throw new IllegalArgumentException(
                "expected exactly one email address, got " + parsed.length + ": " + raw);
        }
        InternetAddress address = parsed[0];
        if (address.isGroup()) {
            throw new IllegalArgumentException(
                "RFC 822 group syntax is not a single recipient: " + raw);
        }
        try {
            address.validate();
        } catch (AddressException ex) {
            throw new IllegalArgumentException("malformed email address: " + raw, ex);
        }
        return address.getAddress();
    }

    /**
     * Rejects values the parser would otherwise accept only by normalising them, so that what is
     * validated here is what the caller puts on the wire. See the class javadoc.
     */
    private static void rejectNonCanonical(String raw) {
        if (raw.indexOf('\r') >= 0 || raw.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(
                "email address must not contain CR or LF: " + raw.replace("\r", "\\r").replace("\n", "\\n"));
        }
        if (!raw.equals(raw.strip())) {
            throw new IllegalArgumentException(
                "email address must not have leading or trailing whitespace: '" + raw + "'");
        }
        if (raw.startsWith(",") || raw.endsWith(",")) {
            throw new IllegalArgumentException("email address must not have a dangling comma: " + raw);
        }
    }
}
