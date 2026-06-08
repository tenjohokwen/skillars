package com.softropic.skillars.platform.security.infrastructure.filter;



import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


/**
 * At the moment this class puts and removes metadata in addition to the exception delegation it performs
 * This class prevents errors that may occur in security filters from leaking out to the client.
 * By default,
 * the stack trace is returned to the client by the server when an exception occurs at this level
 * When using tomcat, {@link org.apache.tomcat.util.ExceptionUtils} throws this out.
 * Note that the handling here is just at the filter level. Spring (the dispatcher servlet) handles those at the controller level
 * see {@link org.springframework.web.servlet.DispatcherServlet}
 * By default, any class annotated with @ControllerAdvice/@RestControllerAdvice will handle exceptions at the controller level.
 * However, a filter could use a HandlerExceptionResolver to delegate handling to a class annotated with @ControllerAdvice/@RestControllerAdvice
 */
@Slf4j
@Component
public class SecurityAdviceFilter extends OncePerRequestFilter {

    private final HandlerExceptionResolver handlerExceptionResolver;
    private final LocaleResolver   localeResolver;
    private final JwtSecretService jwtSecretService;


    public SecurityAdviceFilter(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver,
                                LocaleResolver localeResolver,
                                JwtSecretService jwtSecretService) {
        super();
        this.handlerExceptionResolver = resolver;
        this.localeResolver = localeResolver;
        this.jwtSecretService = jwtSecretService;
    }


    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) {
        try {
            RequestMetadataProvider.initRequestMetadata(request);
            jwtSecretService.addSecretToThread();
            final Locale locale = localeResolver.resolveLocale(request);
            RequestMetadataProvider.setChosenLang(locale.getDisplayLanguage());
            filterChain.doFilter(request, response);
        } catch (Exception exception) {
            //let declared exception handler handle. In this case a class annotated with @ControllerAdvice
            handlerExceptionResolver.resolveException(request, response, null, exception);
        } finally {
            executeSafely(SecurityContextHolder::clearContext, "Could not clear security context");
            executeSafely(RequestMetadataProvider::cleanup, "Could not clean up  RequestMetadataProvider");
            executeSafely(jwtSecretService::removeSecretFromThread, "Could not remove secret from thread");
        }
    }

    public void executeSafely(Runnable runnable, String failureMsg) {
        try {
            runnable.run();
        } catch (Exception exception) {
            log.error(failureMsg, exception);
        }
    }
}
