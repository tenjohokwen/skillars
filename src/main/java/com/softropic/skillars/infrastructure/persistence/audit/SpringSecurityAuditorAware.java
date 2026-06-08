package com.softropic.skillars.infrastructure.persistence.audit;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Implementation of AuditorAware based on Spring Security.
 */
@Component(SpringSecurityAuditorAware.NAME)
public class SpringSecurityAuditorAware implements AuditorAware<String> {

    public static final String NAME = "springSecurityAuditorAware";

    @Override
    public Optional<String> getCurrentAuditor() {
        final SecurityContext securityContext = SecurityContextHolder.getContext();
        final Authentication authentication = securityContext.getAuthentication();
        String userName = null;
        if (authentication != null) {
            if (authentication.getPrincipal() instanceof UserDetails userDetails) {
                userName = userDetails.getUsername();
            } else if (authentication.getPrincipal() instanceof String name) {
                userName = name;
            }
        }
        return Optional.ofNullable(userName != null ? userName : "SYSTEM_ACCOUNT");
    }
}
