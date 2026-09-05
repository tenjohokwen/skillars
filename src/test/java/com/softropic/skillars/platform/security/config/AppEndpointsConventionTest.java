package com.softropic.skillars.platform.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-91 AC15: {@code permitAll()} patterns such as {@code resend-otp**} /
 * {@code register**} had no {@code /} before the {@code **}, so
 * {@code /api/security/coach/resend-otp**} also matched {@code …/resend-otp-admin} — a future
 * controller under that prefix would be silently public with no review step. Every pattern is now
 * anchored (exact, or a {@code /}-preceded {@code /**} prefix). This test fails the build if a bare
 * in-segment {@code **} is ever reintroduced.
 *
 * <h2>Which matcher production actually uses (skillars-deferred-92 AC15.1)</h2>
 *
 * This is the load-bearing fact, so it is recorded here rather than left to be re-derived. The
 * concern was real: this test evaluates patterns with {@link PathPatternRequestMatcher} while
 * {@code SecurityConfiguration:231} passes raw strings to {@code requestMatchers(String...)}, so the
 * test could have been proving something about a matcher the application never uses.
 *
 * <p><strong>Resolution rule, read from {@code spring-security-config-6.5.11-sources.jar}</strong>
 * ({@code AbstractRequestMatcherRegistry#requestMatchers(HttpMethod, String...)}, lines 201-231):
 * <ol>
 *   <li>no Spring MVC on the classpath, or the context is not a {@code WebApplicationContext}, or
 *       there is no {@code ServletContext} — {@code AntPathRequestMatcher};</li>
 *   <li>otherwise, if {@code RequestMatcherFactory.usesPathPatterns()} —
 *       {@link PathPatternRequestMatcher}. That returns true exactly when a <em>unique</em>
 *       {@code PathPatternRequestMatcher.Builder} bean exists in the context
 *       ({@code RequestMatcherFactory} lines 36-42);</li>
 *   <li>otherwise a {@code DeferredRequestMatcher} choosing between {@code AntPathRequestMatcher}
 *       and {@code MvcRequestMatcher} once the servlet registrations are known.</li>
 * </ol>
 *
 * <p><strong>The answer therefore depends on a bean this test cannot see</strong> without starting a
 * Spring context, and this test deliberately starts none (the project gates its context count).
 * Rather than guess, {@link #everyPatternMatchesIdenticallyUnderBothSemantics()} asserts that
 * <em>both</em> candidate semantics agree on every pattern and probe path. While they agree the
 * guarantee holds whichever Spring picks, and the question stops mattering. If they ever diverge
 * that assertion fails and names the pattern — AC15.3's "live security-surface finding", surfaced
 * automatically rather than depending on someone re-checking.
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

    /**
     * AC15.2/AC15.3. Every permitAll pattern under both candidate semantics, against probes that
     * include the exact endpoint, the same-segment sibling the original bug exposed, a deeper child
     * and a trailing-slash variant.
     *
     * <p>A disagreement is not a maintenance chore: it means the set of PUBLIC urls differs
     * depending on which matcher Spring resolved, so the assertions above would be describing a
     * different security surface from the one the application enforces.
     */
    @Test
    void everyPatternMatchesIdenticallyUnderBothSemantics() {
        List<String> probes = List.of(
            "/api/security/coach/resend-otp",
            "/api/security/coach/resend-otp-admin",
            "/api/security/coach/resend-otp/extra",
            "/api/security/coach/register",
            "/api/security/coach/register-superuser",
            "/api/marketplace/coaches",
            "/api/marketplace/coaches/abc-123",
            "/api/marketplace/coaches/abc-123/reviews",
            "/manage/health",
            "/api/admin/users",
            "/");

        List<String> disagreements = new java.util.ArrayList<>();
        for (String pattern : ALL_PERMIT_ALL_PATTERNS) {
            for (String probe : probes) {
                MockHttpServletRequest request = new MockHttpServletRequest("GET", probe);
                request.setServletPath(probe);

                boolean pathPattern;
                try {
                    pathPattern = PathPatternRequestMatcher.withDefaults().matcher(pattern).matches(request);
                } catch (RuntimeException ex) {
                    // A pattern PathPattern cannot even parse is itself the finding: production must
                    // then be matching with Ant semantics, and this class's other assertions would be
                    // describing a matcher that could never have been used.
                    disagreements.add(pattern + " is not a valid PathPattern: " + ex.getMessage());
                    break;
                }
                boolean ant = new AntPathRequestMatcher(pattern).matches(request);

                if (pathPattern != ant) {
                    disagreements.add(String.format(
                        "pattern '%s' vs path '%s': PathPattern=%s, Ant=%s", pattern, probe, pathPattern, ant));
                }
            }
        }

        assertThat(disagreements)
            .as("A permitAll pattern means something different under PathPattern and Ant semantics. "
                + "requestMatchers(String...) picks between them on whether a "
                + "PathPatternRequestMatcher.Builder bean exists (see this class's javadoc), so until "
                + "they agree the set of PUBLIC urls depends on that bean. That is a live "
                + "security-surface finding, not a test failure to paper over.")
            .isEmpty();
    }

    private static boolean matchesAnyPublicEndpoint(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return AppEndpoints.PUBLIC_ENDPOINTS.stream()
            .anyMatch(pattern -> PathPatternRequestMatcher.withDefaults().matcher(pattern).matches(request));
    }
}
