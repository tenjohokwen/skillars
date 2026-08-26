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

    // ---- skillars-deferred-72 AC2: phone-regex false-positive regressions ----

    @Test
    void sanitize_yearRange_isNotRedacted() {
        String clean = "I coach ages 8-14 years old, available 2020-2026";
        var result = sanitizer.sanitize(clean);
        assertThat(result.sanitized()).isEqualTo(clean);
        assertThat(result.wasModified()).isFalse();
    }

    @Test
    void sanitize_timeRange_isNotRedacted() {
        String clean = "Available Mon-Fri 09.00-17.00";
        var result = sanitizer.sanitize(clean);
        assertThat(result.sanitized()).isEqualTo(clean);
        assertThat(result.wasModified()).isFalse();
    }

    @Test
    void sanitize_licenseNumber_isNotRedacted() {
        String clean = "My coaching license number is 2023-04-15-001";
        var result = sanitizer.sanitize(clean);
        assertThat(result.sanitized()).isEqualTo(clean);
        assertThat(result.wasModified()).isFalse();
    }

    @Test
    void sanitize_referenceId_isNotRedacted() {
        String clean = "Reference ID: 100-200-300";
        var result = sanitizer.sanitize(clean);
        assertThat(result.sanitized()).isEqualTo(clean);
        assertThat(result.wasModified()).isFalse();
    }

    /**
     * Proves the 5+-digit-run filter checks each MATCHED CANDIDATE for an unbroken run, not the
     * whole candidate for being one unbroken run — a grouped domestic-style number is still redacted
     * even though the surrounding text has spaces, as long as one group alone clears the threshold.
     */
    @Test
    void sanitize_groupedDomesticNumberWithFiveDigitGroup_isRedacted() {
        var result = sanitizer.sanitize("Call 030 123456 for questions");
        assertThat(result.sanitized()).contains("[contact details removed]");
        assertThat(result.wasModified()).isTrue();
    }
}
