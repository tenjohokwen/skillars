package com.softropic.skillars.platform.security.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.util.Locale;

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
        // skillars-deferred-92 code review, chunk 3. isRejectInvalidCookies() defaults to true, and
        // CookieLocaleResolver throws IllegalStateException from DispatcherServlet.buildLocaleContext
        // for a malformed `lang` cookie — outside doDispatch, so ApiAdvice never sees it and the
        // container returns a raw 500 for every request carrying it. The cookie is client-held, so
        // nothing server-side can rewrite it away.
        cookieLocaleResolver.setRejectInvalidCookies(false);
        // A request with no Accept-Language header falls through to request.getLocale(), which Tomcat
        // answers from the container's Locale.getDefault() — exactly the environment dependence
        // #messageSource()'s setFallbackToSystemLocale(false) was meant to remove, one layer up. This
        // pins a deterministic default ONLY for the header-less case (health probes, curl,
        // server-to-server); a real Accept-Language header is still negotiated by request.getLocale()
        // exactly as before, so the "do not call setDefaultLocale" warning above does not apply to
        // this narrower, request-conditional function.
        cookieLocaleResolver.setDefaultLocaleFunction(request ->
            request.getHeader(HttpHeaders.ACCEPT_LANGUAGE) != null ? request.getLocale() : Locale.ENGLISH);
        return cookieLocaleResolver;
    }

    @Bean
    public MessageSource messageSource() {
        final ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasenames("classpath:/i18n/messages");
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
        // skillars-deferred-92 code review, chunk 3. isIgnoreInvalidLocale() defaults to false, so
        // preHandle rethrows IllegalArgumentException for `?language=<garbage>` on any mapped URL —
        // an unauthenticated query parameter turning into a 500 via ApiAdvice's
        // @ExceptionHandler(Throwable.class).
        localeChangeInterceptor.setIgnoreInvalidLocale(true);
        registry.addInterceptor(localeChangeInterceptor);
    }
}
