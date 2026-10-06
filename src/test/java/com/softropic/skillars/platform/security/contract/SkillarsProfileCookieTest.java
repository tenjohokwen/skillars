package com.softropic.skillars.platform.security.contract;

import com.softropic.skillars.infrastructure.security.SecurityConstants;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.HttpCookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-144 AC1/AC7: the first test class for {@link SkillarsProfileCookie}, pinning
 * the wire format it now owns alone instead of leaving it implicit at three duplicated call
 * sites. Supersedes individually patching {@code JwtManagerImplTest}'s two existing
 * {@code skp}-value assertions for the wire-format concern — those stay as role-derivation tests.
 */
class SkillarsProfileCookieTest {

    private static final String SET_COOKIE = "Set-Cookie";

    @Test
    void writeTo_producesQuotedIdJsonPayloadWithExpectedCookieAttributes() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SkillarsProfileCookie("123", "COACH").writeTo(response);

        HttpCookie cookie = soleSkpCookie(response);
        assertThat(cookie.getValue()).isEqualTo("%7B%22id%22%3A%22123%22%2C%22role%22%3A%22COACH%22%7D");
        // The real hazard is Java -> decodeURIComponent, not Java -> java.net.URLDecoder: the
        // latter is form decoding, the exact (and therefore blind) inverse of URLEncoder, so it
        // would still pass even if the '+'-to-'%20' fix above were reverted. decodeURIComponent
        // never treats '+' specially, so decode manually the way the frontend actually does.
        assertThat(decodeAsFrontendWould(cookie.getValue()))
            .isEqualTo("{\"id\":\"123\",\"role\":\"COACH\"}");
        assertThat(cookie.isHttpOnly()).isFalse();
        assertThat(cookie.getMaxAge()).isEqualTo(SecurityConstants.REFRESH_TOKEN_TTL.toSeconds());
        assertThat(cookie.getPath()).isEqualTo("/");

        String rawHeader = response.getHeader(SET_COOKIE);
        assertThat(rawHeader).containsIgnoringCase("SameSite=Lax");
    }

    @Test
    void writeTo_roleContainingSpace_encodesSpaceAsPercent20NotPlus() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Today's real payloads (a numeric id, an enum name) never contain a space — this proves
        // the Finding 1 encoding fix (java.net.URLEncoder is form-encoding, '+' for space; the
        // frontend decodes with decodeURIComponent, which leaves '+' literal) for the next field
        // that might.
        new SkillarsProfileCookie("1", "LTD ADMIN").writeTo(response);

        HttpCookie cookie = soleSkpCookie(response);
        assertThat(cookie.getValue()).contains("%20").doesNotContain("+");
    }

    @Test
    void writeTo_roleContainingQuote_doesNotInjectAnExtraJsonKey() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Code review 2026-10-06: a hand-built "{\"id\":\"" + id + ... string would let this
        // value inject a second "id" key, which JSON.parse's last-key-wins semantics would then
        // let silently overwrite the real id on the frontend. Jackson escapes the quote instead.
        new SkillarsProfileCookie("1", "X\",\"id\":\"999").writeTo(response);

        HttpCookie cookie = soleSkpCookie(response);
        String decoded = decodeAsFrontendWould(cookie.getValue());
        assertThat(decoded).isEqualTo("{\"id\":\"1\",\"role\":\"X\\\",\\\"id\\\":\\\"999\"}");
        assertThat(decoded).containsOnlyOnce("\"id\":\"1\"");
    }

    @Test
    void removeFrom_producesMaxAgeZeroRemovalForSkpSpecifically() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        SkillarsProfileCookie.removeFrom(response);

        HttpCookie cookie = soleSkpCookie(response);
        assertThat(cookie.getMaxAge()).isZero();
    }

    /**
     * Mimics the frontend's {@code decodeURIComponent}, which — unlike {@link URLDecoder}, a
     * form decoder and therefore the exact inverse of {@code writeTo}'s {@code URLEncoder} —
     * never treats a literal {@code +} as a space. Escaping any literal {@code +} to {@code %2B}
     * first makes {@code URLDecoder} behave the same way {@code decodeURIComponent} does.
     */
    private String decodeAsFrontendWould(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private HttpCookie soleSkpCookie(MockHttpServletResponse response) {
        Collection<String> headers = response.getHeaders(SET_COOKIE);
        List<HttpCookie> skpCookies = headers.stream()
            .map(HttpCookie::parse)
            .flatMap(Collection::stream)
            .filter(c -> c.getName().equals(SecurityConstants.SKILLARS_PROFILE_COOKIE))
            .toList();
        assertThat(skpCookies).hasSize(1);
        return skpCookies.get(0);
    }
}
