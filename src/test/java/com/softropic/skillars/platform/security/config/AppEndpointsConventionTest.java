package com.softropic.skillars.platform.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-91 AC15: {@code permitAll()} patterns such as {@code resend-otp**} /
 * {@code register**} had no {@code /} before the {@code **}, so
 * {@code /api/security/coach/resend-otp**} also matched {@code …/resend-otp-admin} — a future
 * controller under that prefix would be silently public with no review step. Every pattern is now
 * anchored (exact, or a {@code /}-preceded {@code /**} prefix). This test fails the build if a bare
 * in-segment {@code **} is ever reintroduced.
 */
class AppEndpointsConventionTest {

    private static final List<String> ALL_PERMIT_ALL_PATTERNS = concat(
        AppEndpoints.PUBLIC_ENDPOINTS, AppEndpoints.PUBLIC_STATIC_RESOURCES, AppEndpoints.PUBLIC_MGMT_ENDPOINTS);

    private static List<String> concat(List<String>... lists) {
        return java.util.Arrays.stream(lists).flatMap(List::stream).toList();
    }

    @Test
    void noPermitAllPatternEndsInAnUnanchoredWildcard() {
        List<String> offenders = ALL_PERMIT_ALL_PATTERNS.stream()
            .filter(p -> p.endsWith("**") && !p.endsWith("/**"))
            .toList();

        assertThat(offenders)
            .as("permitAll() patterns must end in an exact segment or a '/'-preceded '/**' — a bare "
                + "trailing '**' matches within a segment and silently exposes same-segment siblings")
            .isEmpty();
    }

    @Test
    void anyWildcardMustBeExactlyATrailingSlashStarStar() {
        // The only sanctioned wildcard form is a trailing "/**" (a prefix matcher that cannot cross
        // a '/'). Anything else — "/api/**/foo", "/api/x**/y", a bare trailing "**" — is rejected.
        List<String> offenders = ALL_PERMIT_ALL_PATTERNS.stream()
            .filter(p -> p.contains("**"))
            .filter(p -> !(p.endsWith("/**") && p.indexOf("**") == p.length() - 2))
            .toList();

        assertThat(offenders).isEmpty();
    }

    @Test
    void anchoredResendOtpPattern_matchesTheRealEndpoint_butNotASameSegmentSibling() {
        assertThat(matchesAnyPublicEndpoint("/api/security/coach/resend-otp")).isTrue();
        assertThat(matchesAnyPublicEndpoint("/api/security/coach/resend-otp-admin")).isFalse();
        assertThat(matchesAnyPublicEndpoint("/api/security/coach/register")).isTrue();
        assertThat(matchesAnyPublicEndpoint("/api/security/coach/register-superuser")).isFalse();
    }

    @Test
    void marketplaceCoachesListIsPublic_andIndividualProfilesRemainPublic() {
        assertThat(matchesAnyPublicEndpoint("/api/marketplace/coaches")).isTrue();
        assertThat(matchesAnyPublicEndpoint("/api/marketplace/coaches/abc-123")).isTrue();
    }

    private static boolean matchesAnyPublicEndpoint(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return AppEndpoints.PUBLIC_ENDPOINTS.stream()
            .anyMatch(pattern -> PathPatternRequestMatcher.withDefaults().matcher(pattern).matches(request));
    }
}
