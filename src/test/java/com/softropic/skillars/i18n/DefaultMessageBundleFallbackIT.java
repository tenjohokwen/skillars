package com.softropic.skillars.i18n;

import com.softropic.skillars.config.AbstractIntegrationTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.LocaleResolver;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * skillars-deferred-92 AC12.6 — a client whose resolved locale is neither {@code de}, {@code fr} nor
 * {@code en} must get clean English, not a 500.
 *
 * <h2>The bug this pins</h2>
 *
 * {@code messages.properties} — the bundle {@code ReloadableResourceBundleMessageSource} falls back
 * to — held 86 of {@code messages_en}'s 130 keys. {@code CookieLocaleResolver} falls through to
 * {@code Accept-Language} when no locale cookie is set, so a Spanish or Italian client resolving
 * {@code security.accountLocked} hit a {@code NoSuchMessageException}: HTTP 500 on the
 * account-lockout response, and a template failure on every {@code email.*} key.
 *
 * <p>The assertions below are deliberately split. Resolving through the real {@code MessageSource}
 * bean is what actually proves the hole is closed — a missing key throws, so "does not throw" is the
 * property, and it can be asserted for every one of the 46 keys rather than for whichever one an
 * endpoint happens to reach. The HTTP case then confirms the surrounding wiring
 * ({@code Accept-Language} → {@code LocaleContextHolder} → {@code ApiAdvice}) really does carry a
 * non-{@code de}/{@code fr}/{@code en} locale through to that message source.
 */
class DefaultMessageBundleFallbackIT extends AbstractIntegrationTest {

    /** Locales with no bundle of their own — every one of them lands on messages.properties. */
    private static final List<Locale> UNSUPPORTED = List.of(
        Locale.forLanguageTag("es-ES"), Locale.forLanguageTag("it-IT"), Locale.forLanguageTag("pt-BR"));

    /** A representative slice of the 46 keys that were missing, one from each affected family. */
    private static final List<String> PREVIOUSLY_MISSING = List.of(
        "security.accountLocked",
        "security.otpResendInProgress",
        "security.emailTokenExpired",
        "security.emailTokenInvalid",
        "security.emailTokenUsed",
        "email.coach.otp.title",
        "email.parent.verify.text1",
        "email.player.otp.intro",
        "email.booking.reminder.title",
        "email.profile_change.password");

    @Autowired private MessageSource messageSource;
    @Autowired private LocaleResolver localeResolver;
    @Autowired private RestTemplate testRestTemplate;

    @Test
    @DisplayName("every previously-missing key resolves for an unsupported locale instead of throwing")
    void unsupportedLocale_resolvesFromTheDefaultBundle() {
        for (Locale locale : UNSUPPORTED) {
            for (String key : PREVIOUSLY_MISSING) {
                assertThatCode(() -> messageSource.getMessage(key, null, locale))
                    .as("""
                        '%s' must resolve for %s. Before skillars-deferred-92 AC12 this threw \
                        NoSuchMessageException, which is a 500 on the account-lockout response and a \
                        template failure on every transactional email.""", key, locale)
                    .doesNotThrowAnyException();

                assertThat(messageSource.getMessage(key, null, locale))
                    .as("'%s' for %s must be real text, not an empty placeholder", key, locale)
                    .isNotBlank();
            }
        }
    }

    /**
     * The AC12.5 regression guard. Pinning determinism was supposed to happen on the <em>message
     * source</em> ({@code setFallbackToSystemLocale(false)}), never on the <em>resolver</em>: a
     * {@code CookieLocaleResolver} with a {@code defaultLocale} stops consulting
     * {@code Accept-Language} altogether, so a German browser with no locale cookie would silently
     * start getting English. Same method name on two classes, opposite consequences.
     */
    @Test
    @DisplayName("a German client with no locale cookie still negotiates German")
    void germanAcceptLanguage_withNoCookie_stillResolvesGerman() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addPreferredLocale(Locale.GERMANY);   // Accept-Language: de-DE
        request.setCookies();                          // explicitly no locale cookie

        Locale resolved = localeResolver.resolveLocale(request);

        assertThat(resolved.getLanguage())
            .as("""
                CookieLocaleResolver must still fall through to Accept-Language. If this is 'en', \
                someone called setDefaultLocale on the RESOLVER — that disables Accept-Language \
                negotiation entirely and is a user-visible regression, not hardening. Pin determinism \
                on the message source instead (MvcConfig#messageSource).""")
            .isEqualTo("de");

        assertThat(messageSource.getMessage("security.accountLocked", null, Locale.GERMANY))
            .as("and the German bundle must still be the one that answers for a German locale")
            .isNotEqualTo(messageSource.getMessage("security.accountLocked", null, Locale.ENGLISH));
    }

    /**
     * End to end: a real request from a Spanish client must come back as a clean localized error, not
     * a 500. Any authenticated endpoint will do — the point is that the whole
     * {@code Accept-Language} → {@code LocaleContextHolder} → {@code ApiAdvice} → {@code messageSource}
     * chain carries an unsupported locale without falling over.
     */
    @Test
    @DisplayName("an es-ES request to a protected endpoint gets a localized 401, never a 500")
    void spanishAcceptLanguage_protectedEndpoint_returnsCleanUnauthorized() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAcceptLanguageAsLocales(List.of(Locale.forLanguageTag("es-ES")));

        // The shared RestTemplate throws on any 4xx, and 401 is the expected outcome here — the
        // question this test asks is whether the status is 401 rather than 500, so the exception IS
        // the result. Catching it beats swapping in a no-op error handler, which would change the
        // bean for every other test sharing this context.
        HttpStatusCodeException failure = catchThrowableOfType(
            () -> testRestTemplate.exchange(baseUrl() + "/api/account/me", HttpMethod.GET,
                new HttpEntity<>(headers), String.class),
            HttpStatusCodeException.class);

        assertThat(failure)
            .as("a protected endpoint must reject an anonymous request")
            .isNotNull();
        assertThat(failure.getStatusCode())
            .as("""
                A Spanish client must get the ordinary auth failure. A 5xx here is the AC12 bug:                 message resolution for a locale with no bundle of its own threw                 NoSuchMessageException inside the error handler itself.""")
            .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(failure.getResponseBodyAsString())
            .as("and the body must be a resolved, localized error rather than an empty or raw one")
            .contains("errorKey")
            .contains("message");
    }
}
