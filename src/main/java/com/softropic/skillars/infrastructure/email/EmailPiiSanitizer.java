package com.softropic.skillars.infrastructure.email;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * skillars-deferred-111 AC11 (owner decision: full sanitizer, applied to both logs and the
 * persisted record) — a shared, transport-neutral mask for recipient addresses that would
 * otherwise ride along in an SMTP/SES failure's exception message or stack trace.
 *
 * <p><strong>Root cause this closes:</strong> {@code SmtpErrorClassifier}'s exception messages and
 * {@code MailManager}'s {@code logger.error(..., exception)} / persisted {@code
 * envelope_entity.error} stacktrace all carry recipient addresses verbatim — in tension with the
 * recipient masking {@code skillars-deferred-110}/{@code LoggingEmailSender.maskAddress} already
 * apply to the log-transport's own happy-path INFO line. {@code MailManager.loggableData(...)}
 * masks the interpolated {@code data={}} argument, but the separately-logged {@code exception}
 * argument on the same line bypasses it entirely, since SLF4J renders a trailing {@link Throwable}
 * argument's own stack trace directly, not through any of this class's masking.
 *
 * <p><strong>Scope, honestly stated:</strong> this masks email-address-shaped substrings only. It
 * does <em>not</em> attempt to detect an OTP code echoed into a message by an unrelated failure
 * (e.g. a Jackson serialisation error echoing partially-written JSON, or a JDBC error echoing bound
 * statement parameters) — masking every bare 6-digit number in an error message would collide with
 * legitimate content (amounts, ids, timestamps) far too often to be a safe general rule. That gap
 * needs the JSON/JDBC boundary itself, not a text-pattern sanitizer applied after the fact, and
 * stays explicitly open per this story's own AC11 scoping note.
 */
public final class EmailPiiSanitizer {

    /**
     * Deliberately permissive, not a validating parser (see {@link
     * com.softropic.skillars.infrastructure.email.EmailAddressParser} for that) — this only needs
     * to find address-shaped substrings inside arbitrary free-text exception messages/stack traces
     * and mask them, not confirm RFC 5322 validity.
     */
    private static final Pattern EMAIL_LIKE = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private EmailPiiSanitizer() {
    }

    /**
     * Masks every email-address-shaped substring in {@code text} (via {@link #maskAddress}),
     * leaving everything else untouched. Null/blank input is returned as-is.
     */
    public static String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        Matcher matcher = EMAIL_LIKE.matcher(text);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            result.append(text, lastEnd, matcher.start());
            result.append(maskAddress(matcher.group()));
            lastEnd = matcher.end();
        }
        result.append(text, lastEnd, text.length());
        return result.toString();
    }

    /**
     * Same masking shape {@code LoggingEmailSender.maskAddress} already established: first
     * character, {@code ***}, then the domain — enough to keep an address unreadable while leaving
     * the domain visible for triage (e.g. "was this a gmail.com bounce or an internal one").
     */
    public static String maskAddress(String address) {
        int at = address.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return address.charAt(0) + "***" + address.substring(at);
    }
}
