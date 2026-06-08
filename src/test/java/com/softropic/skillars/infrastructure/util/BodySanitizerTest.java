package com.softropic.skillars.infrastructure.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

public class BodySanitizerTest {

    @Test
    void testSanitize_RedactsSensitiveFields() {
        String rawJson = "{\"username\":\"bob\",\"password\":\"secret123\",\"nested\":{\"otp\":\"123456\"}}";
        byte[] content = rawJson.getBytes(StandardCharsets.UTF_8);
        
        String sanitized = BodySanitizer.sanitize(content, "application/json");
        
        assertThat(sanitized).contains("\"username\":\"bob\"");
        assertThat(sanitized).contains("\"password\":\"[REDACTED]\"");
        assertThat(sanitized).contains("\"otp\":\"[REDACTED]\"");
        assertThat(sanitized).doesNotContain("secret123");
        assertThat(sanitized).doesNotContain("123456");
    }

    @Test
    void testSanitize_HandlesArrays() {
        String rawJson = "[{\"password\":\"p1\"},{\"password\":\"p2\"}]";
        byte[] content = rawJson.getBytes(StandardCharsets.UTF_8);
        
        String sanitized = BodySanitizer.sanitize(content, "application/json");
        
        assertThat(sanitized).isEqualTo("[{\"password\":\"[REDACTED]\"},{\"password\":\"[REDACTED]\"}]");
    }

    @Test
    void testSanitize_NonJson_ReturnsRaw() {
        String raw = "Just some text";
        byte[] content = raw.getBytes(StandardCharsets.UTF_8);
        
        String sanitized = BodySanitizer.sanitize(content, "text/plain");
        
        assertThat(sanitized).isEqualTo(raw);
    }

    @Test
    void testSanitize_MalformedJson_ReturnsRaw() {
        String raw = "{\"invalid\": json";
        byte[] content = raw.getBytes(StandardCharsets.UTF_8);
        
        String sanitized = BodySanitizer.sanitize(content, "application/json");
        
        assertThat(sanitized).isEqualTo(raw);
    }
}
