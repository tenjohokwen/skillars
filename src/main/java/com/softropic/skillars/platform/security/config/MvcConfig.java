package com.softropic.skillars.platform.security.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.LOCALE_COOKIE;


@Configuration
public class MvcConfig implements WebMvcConfigurer {

    /**
     * <strong>Do not call {@code cookieLocaleResolver.setDefaultLocale(...)} here</strong>
     * (skillars-deferred-92 AC12.5). It reads like hardening and is the opposite.
     *
     * <p>{@code CookieLocaleResolver.determineDefaultLocale(request)} returns {@code this.defaultLocale}
     * <em>if set</em>, and only falls through to {@code request.getLocale()} — i.e. {@code Accept-Language}
     * — when it is {@code null}. Setting it therefore <strong>disables Accept-Language negotiation
     * entirely</strong>: a German browser arriving with no locale cookie gets German today and would get
     * the pinned default afterwards. That is a user-visible regression.
     *
     * <p>Determinism is achieved on the <em>message source</em> instead — see {@link #messageSource()}.
     * Note that {@code AbstractResourceBasedMessageSource} declares an identically-named
     * {@code setDefaultLocale}; that one is safe and carries none of this risk. Two different classes,
     * same method name, opposite consequences.
     */
    @Bean(name = "localeResolver")
    public LocaleResolver localeResolver() {
        final CookieLocaleResolver cookieLocaleResolver = new CookieLocaleResolver();
        cookieLocaleResolver.setCookieName(LOCALE_COOKIE);
        return cookieLocaleResolver;
    }

    /**
     * <p><strong>{@code classpath:/i18n/error-messages} is very nearly vestigial</strong>
     * (skillars-deferred-92 AC12.3, recorded rather than left for a reader to wonder about). Audited
     * 2026-09-04: {@code src/main/resources/i18n/} contains only {@code error-messages.properties} plus
     * {@code messages{,_en,_de,_fr}.properties}, and {@code error-messages.properties} holds exactly
     * <strong>one</strong> key ({@code security.msg.unauthorized}) with <strong>no locale variants at
     * all</strong> — so any non-English client resolving it gets English regardless. It is kept
     * registered because removing a basename is a behaviour change for a key that may still be
     * resolved somewhere, and this story had no mandate to retire it; folding that one key into
     * {@code messages*.properties} and dropping the basename is the obvious follow-up, filed to
     * {@code deferred-work.md}.
     */
    @Bean
    public MessageSource messageSource() {
        final ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasenames("classpath:/i18n/error-messages", "classpath:/i18n/messages");
        messageSource.setDefaultEncoding("UTF-8");
        // skillars-deferred-92 AC12.4. Default is TRUE, which makes fallback resolution depend on the
        // CONTAINER's Locale.getDefault() — environment-dependent and pinned nowhere in this repo, so
        // the same request could resolve differently on two nodes of the same deployment. With it off,
        // a key missing from messages_de/messages_fr resolves deterministically from
        // messages.properties (brought to full parity by AC12) on every host.
        messageSource.setFallbackToSystemLocale(false);
        //messageSource.setCacheSeconds(propertyResolver.getProperty("cache-seconds", Integer.class, -1));
        return messageSource;
    }

    @Override
    public void addInterceptors(final InterceptorRegistry registry) {
        final LocaleChangeInterceptor localeChangeInterceptor = new LocaleChangeInterceptor();
        localeChangeInterceptor.setParamName("language");
        registry.addInterceptor(localeChangeInterceptor);
    }
}
