package com.softropic.skillars.infrastructure.sanitizer;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ContactDetailSanitizer {

    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE_PATTERN =
        Pattern.compile("(?:\\+?[\\d][\\d\\s\\-().]{6,14}[\\d])");
    // Real phone numbers carry at least one unbroken run of 5+ digits (an area/subscriber block) even
    // when grouped with spaces — "+44 7911 123456" has runs of 4 and 6; "+49 30 12345678" has an
    // 8-digit run. Date ranges, time ranges, and reference/license numbers (e.g. "2020-2026",
    // "09.00-17.00", "2023-04-15-001") break into runs no longer than 4 digits, since each dash/dot-
    // separated segment is itself a short date/time/id component, not a phone subscriber block.
    private static final Pattern PHONE_DIGIT_RUN = Pattern.compile("\\d{5,}");
    private static final String REDACTION = "[contact details removed]";

    public SanitizerResult sanitize(String input) {
        if (input == null) return new SanitizerResult(null, false);
        String result = EMAIL_PATTERN.matcher(input).replaceAll(REDACTION);
        result = redactPhoneLikeSequences(result);
        return new SanitizerResult(result, !result.equals(input));
    }

    private String redactPhoneLikeSequences(String input) {
        Matcher m = PHONE_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String candidate = m.group();
            String replacement = PHONE_DIGIT_RUN.matcher(candidate).find() ? REDACTION : candidate;
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public record SanitizerResult(String sanitized, boolean wasModified) {}
}
