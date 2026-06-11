package com.softropic.skillars.infrastructure.sanitizer;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class ContactDetailSanitizer {

    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE_PATTERN =
        Pattern.compile("(?:\\+?[\\d][\\d\\s\\-().]{6,14}[\\d])");
    private static final String REDACTION = "[contact details removed]";

    public SanitizerResult sanitize(String input) {
        if (input == null) return new SanitizerResult(null, false);
        String result = EMAIL_PATTERN.matcher(input).replaceAll(REDACTION);
        result = PHONE_PATTERN.matcher(result).replaceAll(REDACTION);
        return new SanitizerResult(result, !result.equals(input));
    }

    public record SanitizerResult(String sanitized, boolean wasModified) {}
}
