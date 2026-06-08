package com.softropic.skillars.platform.security;

import com.softropic.skillars.platform.security.contract.Principal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithMockPrincipalFactory.class)
public @interface WithMockPrincipal {
    String username() default "user@example.com";
    String businessId() default "12345";
}

class WithMockPrincipalFactory implements WithSecurityContextFactory<WithMockPrincipal> {
    @Override
    public org.springframework.security.core.context.SecurityContext createSecurityContext(WithMockPrincipal annotation) {
        Principal principal = new Principal.Builder()
            .username(annotation.username())
            .password("password")
            .enabled(true)
            .businessId(annotation.businessId())
            .otpEnabled(false)
            .build();

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            principal, principal.getPassword(), principal.getAuthorities());
        auth.setDetails(principal);
        org.springframework.security.core.context.SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        return context;
    }
}
