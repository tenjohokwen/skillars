package com.softropic.skillars.platform.security.service;



import com.softropic.skillars.platform.security.contract.Gender;
import com.softropic.skillars.platform.security.contract.util.AuthoritiesConstants;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import com.softropic.skillars.platform.security.service.LoginTokenManager;
import com.softropic.skillars.platform.security.contract.Principal;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


/**
 * Utility class for Spring Security.
 */
@Slf4j
@Component
public final class SecurityUtil {

    private final LoginTokenManager loginTokenManager;


    public SecurityUtil(LoginTokenManager loginTokenManager) {
        this.loginTokenManager = loginTokenManager;
    }

    /**
     * Get the login of the current user.
     * @return user name
     */
    public String getCurrentUserName() {
        final SecurityContext securityContext = SecurityContextHolder.getContext();
        final Authentication authentication = securityContext.getAuthentication();
        String userName = null;
        if (authentication != null) {
            if (authentication.getPrincipal() instanceof UserDetails userDetails) {
                userName = userDetails.getUsername();
            }
            else if (authentication.getPrincipal() instanceof String name) {
                userName = name;
            }
        }
        else {
            userName = getRequest().map(loginTokenManager::extractUserNameSilently).orElse(null);
        }
        return userName;
    }

    /**
     * Check if a user is authenticated.
     *
     * @return true if the user is authenticated, false otherwise
     */
    public boolean isAuthenticated() {
        //TODO refine this impl. There is a session cookie but note that you cannot trust it
        final SecurityContext securityContext = SecurityContextHolder.getContext();
        final Authentication authentication = securityContext.getAuthentication();
        if(authentication == null) {
            return getSessionId().isPresent();
        }
        final Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        if (authorities != null) {
            for (final GrantedAuthority authority : authorities) {
                if (authority.getAuthority().equals(AuthoritiesConstants.ANONYMOUS)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Return the current user, or throws an exception, if the user is not
     * authenticated yet.
     *
     * @return the current user
     */
    public  User getCurrentUser() {
        final SecurityContext securityContext = SecurityContextHolder.getContext();
        var authentication = securityContext.getAuthentication();
        if (authentication instanceof UsernamePasswordAuthenticationToken token
                && token.getDetails() instanceof Principal principal) {
            return principal;
        }
        throw new IllegalStateException("User not found!");
    }

    /**
     *
     * If the current user has a specific authority (security role).
     *
     * <p>The name of this method comes from the isUserInRole() method in the Servlet API</p>
     * @param authority granted to the user
     * @return true if user has role else false
     */
    public boolean isCurrentUserInRole(final String authority) {
        final SecurityContext securityContext = SecurityContextHolder.getContext();
        final Authentication authentication = securityContext.getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserDetails userDetails) {
            return userDetails.getAuthorities().contains(new SimpleGrantedAuthority(authority));
        }
        return false;
    }

    public boolean isAdmin() {
        return isCurrentUserInRole(AuthoritiesConstants.ADMIN);
    }


    public List<String> getCurrentUserRoles() {
        SecurityContext context = SecurityContextHolder.getContext();
        Authentication authentication = context.getAuthentication();
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }


    public void logout(final HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        loginTokenManager.deleteLoginToken(response);
    }

    public Authentication getCurrentOrDefaultAuth() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            //TODO try to find out when this can occur. When resources are loaded?
            final Principal.Builder builder = new Principal.Builder();
            final Principal principal = builder.username("N/A")
                                           .password("N/A")
                                           .authorities(List.of())
                                           .gender(Gender.MALE)
                                           .businessId("")
                                           .phone(" ")
                                           .build();
            var authToken = new UsernamePasswordAuthenticationToken(principal.getUsername(),
                                                                    null,
                                                                    principal.getAuthorities());
            authToken.setDetails(principal);
        }
        return authentication;
    }

    public Long getCurrentCoachUserId() {
        User user = getCurrentUser();
        if (!(user instanceof Principal principal) || principal.getBusinessId() == null || principal.getBusinessId().isBlank()) {
            throw new InsufficientAuthenticationException("Principal has no business ID");
        }
        try {
            return Long.parseLong(principal.getBusinessId());
        } catch (NumberFormatException e) {
            throw new InsufficientAuthenticationException("Invalid business ID format in principal");
        }
    }

    public boolean hasClientIdentifier() {
        return StringUtils.isNotBlank(RequestMetadataProvider.getClientInfo().getClientIdentifier());
    }

    /**
     * Gets the session id of the current session.
     * This method takes into account the current security mechanism, which may not even use an HttpSession object
     * @return optional UUID
     */
    public Optional<String> getSessionId() {
        final Optional<HttpServletRequest> httpServletRequestOpt = getRequest();
        return httpServletRequestOpt.flatMap(loginTokenManager::extractSessionIdSilently);
    }

    private Optional<HttpServletRequest> getRequest() {
        try {
            final HttpServletRequest request = (HttpServletRequest) RequestContextHolder.currentRequestAttributes()
                                                                                        .resolveReference("request");
            return Optional.ofNullable(request);
        }
        catch (Exception e) {
            //log.warn("Could not obtain HttpServletRequest object from context", e);
        }
        return Optional.empty();
    }
}
