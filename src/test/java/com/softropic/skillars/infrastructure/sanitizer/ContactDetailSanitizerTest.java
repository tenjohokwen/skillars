package com.softropic.skillars.infrastructure.sanitizer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContactDetailSanitizerTest {

    private final ContactDetailSanitizer sanitizer = new ContactDetailSanitizer();

    @Test
    void sanitize_email_isRedacted() {
        var result = sanitizer.sanitize("Reach me at coach@example.com for sessions");
        assertThat(result.sanitized()).contains("[contact details removed]");
        assertThat(result.sanitized()).doesNotContain("coach@example.com");
        assertThat(result.wasModified()).isTrue();
    }

    @Test
    void sanitize_internationalPhone_isRedacted() {
        var result = sanitizer.sanitize("Call me on +44 7911 123456 to book");
        assertThat(result.sanitized()).contains("[contact details removed]");
        assertThat(result.wasModified()).isTrue();
    }

    @Test
    void sanitize_europePhone_isRedacted() {
        var result = sanitizer.sanitize("Call me on +49 30 12345678");
        assertThat(result.sanitized()).contains("[contact details removed]");
        assertThat(result.wasModified()).isTrue();
    }

    @Test
    void sanitize_cleanText_passesThrough() {
        String clean = "I am a certified football coach based in Berlin.";
        var result = sanitizer.sanitize(clean);
        assertThat(result.sanitized()).isEqualTo(clean);
        assertThat(result.wasModified()).isFalse();
    }

    @Test
    void sanitize_nullInput_returnsNull() {
        var result = sanitizer.sanitize(null);
        assertThat(result.sanitized()).isNull();
        assertThat(result.wasModified()).isFalse();
    }

    @Test
    void sanitize_multipleContactDetails_allRedacted() {
        var result = sanitizer.sanitize("Email coach@example.com or call +49 30 12345678");
        assertThat(result.sanitized()).doesNotContain("coach@example.com");
        assertThat(result.sanitized()).doesNotContain("+49");
        assertThat(result.wasModified()).isTrue();
    }
}
