package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Code review of skillars-deferred-90 (P3): the blank-display-name sentinel is a contract shared
 * across the Java/JavaScript boundary — the backend writes it into the {@code user} cookie
 * ({@code JwtManagerImpl.createLoginCookies}) and the SPA maps it back to "no display name"
 * ({@code utils/sessionCookies.js}). Nothing linked the two values, so changing the Java constant
 * would silently start rendering the raw sentinel as the dashboard greeting.
 *
 * <p>No Spring context, no browser — a plain text assertion that the two literals still agree.
 */
class SessionCookieContractTest {

    private static final Path SESSION_COOKIES_JS =
        Path.of("src", "frontend", "src", "utils", "sessionCookies.js");

    private static final Pattern JS_SENTINEL =
        Pattern.compile("export\\s+const\\s+USER_COOKIE_SENTINEL\\s*=\\s*'([^']*)'");

    @Test
    void frontendSentinelMatchesSecurityConstants() throws IOException {
        final String js = Files.readString(SESSION_COOKIES_JS, StandardCharsets.UTF_8);
        final Matcher m = JS_SENTINEL.matcher(js);

        assertThat(m.find())
            .as("USER_COOKIE_SENTINEL not found in %s — if it was renamed, update this test and "
                + "keep it pinned to SecurityConstants.BLANK_DISPLAY_NAME_SENTINEL", SESSION_COOKIES_JS)
            .isTrue();

        assertThat(m.group(1))
            .as("utils/sessionCookies.js USER_COOKIE_SENTINEL must equal "
                + "SecurityConstants.BLANK_DISPLAY_NAME_SENTINEL — otherwise a user with a blank "
                + "display name is greeted by the raw sentinel instead of the generic fallback")
            .isEqualTo(SecurityConstants.BLANK_DISPLAY_NAME_SENTINEL);
    }
}
