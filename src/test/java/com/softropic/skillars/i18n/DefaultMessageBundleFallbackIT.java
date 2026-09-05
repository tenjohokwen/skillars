package com.softropic.skillars.i18n;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;

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
 * to — held 84 of {@code messages_en}'s 130 keys (84 − 2 foreign {@code platform_config_changed}
 * keys + 46 added = 130; two javadocs previously said 86, off by the two foreign keys they double
 * counted — skillars-deferred-92 code review, chunk 3). {@code CookieLocaleResolver} falls through
 * to {@code Accept-Language} when no locale cookie is set, so a Spanish or Italian client resolving
 * any of the 46 missing keys hit a {@code NoSuchMessageException} <em>where something still calls
 * the throwing three-arg {@code getMessage}</em> — in this codebase that is
 * {@link com.softropic.skillars.platform.notification.service.MailService#sendEmailFromTemplate},
 * not any HTTP error path (every {@code ApiAdvice} handler uses the four-arg, non-throwing
 * {@code getMessage(key, args, defaultMessage, locale)} — see the honesty note on
 * {@link #spanishAcceptLanguage_protectedEndpoint_returnsCleanUnauthorized()} below). So the actual
 * production incident this bundle held open was a template failure on every transactional email for
 * a non-{@code de}/{@code fr}/{@code en} recipient, not a 500 on account lockout.
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

    /**
     * skillars-deferred-92 code review, chunk 3: the javadoc above claims "it can be asserted for
     * every one of the 46 keys", but this list previously hand-picked only 10 — the two-argument
     * key ({@code email.profile_change.email}) and all four {@code email.booking.*.title} keys were
     * untested. This is the full 46, reproduced from {@code git diff be9a761^..be9a761 --
     * src/main/resources/i18n/messages.properties}, so the javadoc's claim is now actually true.
     */
    private static final List<String> PREVIOUSLY_MISSING = List.of(
        "email.booking.confirmed.title",
        "email.booking.declined.title",
        "email.booking.expired.title",
        "email.booking.reminder.title",
        "email.booking.requested.title",
        "email.coach.otp.expiry",
        "email.coach.otp.ignore",
        "email.coach.otp.intro",
        "email.coach.otp.title",
        "email.coach.verify.expiry",
        "email.coach.verify.ignore",
        "email.coach.verify.linkText",
        "email.coach.verify.text1",
        "email.coach.verify.title",
        "email.parent.otp.expiry",
        "email.parent.otp.ignore",
        "email.parent.otp.intro",
        "email.parent.otp.title",
        "email.parent.verify.expiry",
        "email.parent.verify.ignore",
        "email.parent.verify.linkText",
        "email.parent.verify.text1",
        "email.parent.verify.title",
        "email.player.otp.expiry",
        "email.player.otp.ignore",
        "email.player.otp.intro",
        "email.player.otp.title",
        "email.player.verify.expiry",
        "email.player.verify.ignore",
        "email.player.verify.linkText",
        "email.player.verify.text1",
        "email.player.verify.title",
        "email.profile_change.2fa_disabled",
        "email.profile_change.2fa_enabled",
        "email.profile_change.address",
        "email.profile_change.email",
        "email.profile_change.generic",
        "email.profile_change.not_you",
        "email.profile_change.password",
        "email.profile_change.phone",
        "email.profile_change.title",
        "security.accountLocked",
        "security.emailTokenExpired",
        "security.emailTokenInvalid",
        "security.emailTokenUsed",
        "security.otpResendInProgress");

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
     * skillars-deferred-92 code review, chunk 3: the German-only version of this guard would stay
     * green even if someone pinned {@code cookieLocaleResolver.setDefaultLocale(Locale.GERMANY)} —
     * German would keep "resolving to German" for the wrong reason while every French and English
     * browser silently received German. Asserting a second, differently-preferred locale is what
     * actually pins that {@code determineDefaultLocale} is still consulting the request rather than
     * a fixed default.
     */
    @Test
    @DisplayName("a French client with no locale cookie still negotiates French, not the German default")
    void frenchAcceptLanguage_withNoCookie_stillResolvesFrench() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addPreferredLocale(Locale.FRANCE);     // Accept-Language: fr-FR
        request.setCookies();                          // explicitly no locale cookie

        Locale resolved = localeResolver.resolveLocale(request);

        assertThat(resolved.getLanguage())
            .as("a French Accept-Language header must resolve to French, not silently to German")
            .isEqualTo("fr");
    }

    /**
     * End to end: a real request from a Spanish client must come back as a clean localized error, not
     * a 500 — and that much is true, verified below. <strong>What it does not prove, corrected here
     * per Dev Notes item 2 rather than silently re-asserted (skillars-deferred-92 code review, chunk
     * 3):</strong> {@code /api/account/me} resolves {@code security.unauthorized}, a key that
     * predates AC12 and was never one of the 46 missing ones, and every {@code ApiAdvice} handler —
     * this one included — resolves through the four-argument, non-throwing
     * {@code getMessage(key, args, defaultMessage, locale)}. Reverting all 46 AC12 keys would not
     * fail this test: the HTTP error path was never the code path that could throw. The genuine
     * throwing call site is {@link com.softropic.skillars.platform.notification.service.MailService
     * #sendEmailFromTemplate}'s three-argument {@code getMessage}, which
     * {@link #unsupportedLocale_resolvesFromTheDefaultBundle()} above exercises directly against the
     * real {@code MessageSource} bean. This test earns its place for a narrower, still-real reason:
     * it is the only coverage that the {@code Accept-Language} → {@code LocaleContextHolder} →
     * {@code ApiAdvice} wiring itself carries an unsupported locale through cleanly, independent of
     * bundle completeness.
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

    /**
     * skillars-deferred-92 code review, chunk 3 — the finding that motivated this class's honesty
     * corrections above. {@code MailService#sendEmailFromTemplate} resolves its subject with the
     * <strong>throwing</strong> three-argument {@code getMessage(subjectKey, null, locale)}, the one
     * call site in this codebase that can actually raise {@code NoSuchMessageException} in
     * production. 17 of {@link EmailTemplate}'s 39 {@code subjectKey()} values existed in no bundle
     * at all before this pass — 9 of them behind a live Thymeleaf template, so those sends were
     * silently failing (caught by {@code MailManager}, marked the outbox envelope {@code FAILED}) in
     * every locale, not only an unsupported one. This is the direct regression guard the missing-key
     * bug actually needed.
     */
    @Test
    @DisplayName("every EmailTemplate subject key resolves, in every supported locale")
    void everyEmailTemplateSubjectKey_resolvesInEverySupportedLocale() {
        final List<Locale> supported = List.of(Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH);
        for (EmailTemplate template : EmailTemplate.values()) {
            if (EmailTemplate.NONE.equals(template)) {
                continue; // carries no subject key — MailService special-cases it before any lookup.
            }
            for (Locale locale : supported) {
                assertThatCode(() -> messageSource.getMessage(template.subjectKey(), null, locale))
                    .as("EmailTemplate.%s's subjectKey '%s' must resolve for %s — MailService uses the "
                        + "throwing getMessage(key, null, locale) to build every email subject",
                        template.name(), template.subjectKey(), locale)
                    .doesNotThrowAnyException();
            }
        }
    }
}
