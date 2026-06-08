package com.softropic.skillars.platform.security.service;



import com.softropic.skillars.infrastructure.security.AuthorizationException;
import com.softropic.skillars.infrastructure.security.SecurityError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Collection;
import java.util.Map;


public class DaoAuthProvider extends DaoAuthenticationProvider {

    private static final Logger LOGGER   = LoggerFactory.getLogger(DaoAuthProvider.class);
    public static final String  USERNAME = "username";

    public Authentication authorize(final Authentication authentication,
                                    final Collection<? extends GrantedAuthority> requiredAuthorities) {
        final String username = determineUsername(authentication);
        LOGGER.debug("User name {}" , username);
        UserDetails user;
        try {
            user = retrieveUser(username, (UsernamePasswordAuthenticationToken) authentication);
            LOGGER.debug("User  {}" , user);
            getPreAuthenticationChecks().check(user);
        } catch(InternalAuthenticationServiceException iase) {
            throw new AuthorizationException("During authorization, an unknown issue occurred leading to a InternalAuthenticationServiceException",
                                             iase,
                                             Map.of(USERNAME, username),
                                             SecurityError.UNKNOWN);
        } catch (UsernameNotFoundException unfe) {
            throw new AuthorizationException("The given username was not found",
                                             unfe,
                                             Map.of(USERNAME, username),
                                             SecurityError.USER_NOT_FOUND);
        } catch (AccountStatusException ase) {
            throw new AuthorizationException("The current account status of the user does not permit access",
                                             ase,
                                             Map.of(USERNAME, username),
                                             SecurityError.ACCOUNT_NOT_LOGIN_ABLE);
        } catch (Exception exception) {
            throw new AuthorizationException("Error occurred while trying to pull data from db and perform checks",
                                             exception,
                                             SecurityError.UNKNOWN);
        }
        checkAuthorities(requiredAuthorities, user.getAuthorities());
        return newAuthentication(user);
    }

    /**
     * Checks if @param claimedAuthorities contains at least one of the required authorities
     * @param requiredAuthorities are the authorities permitted for the current request
     * @param claimedAuthorities are the actual authorities got from the database or token. The latest possible should be used.
     */
    public void checkAuthorities(final Collection<? extends GrantedAuthority> requiredAuthorities,
                                 final Collection<? extends GrantedAuthority> claimedAuthorities) {
        if(requiredAuthorities == null || requiredAuthorities.isEmpty()) {
            return;
        }
        if(requiredAuthorities.stream().noneMatch(claimedAuthorities::contains)) {
            throw new AuthorizationException("The user does not have any of the required authorities for this request",
                                             SecurityError.MISSING_RIGHTS);
        }
    }

    private String determineUsername(final Authentication authentication) {
        if(authentication.getPrincipal() == null || authentication.getName() == null) {
            throw new AuthorizationException("The principal has not been provided. Could not determine username",
                                             SecurityError.MISSING_USERNAME);
        }
        return authentication.getName();
    }

    private Authentication newAuthentication(final UserDetails userDetails) {
        final var authenticationToken = new UsernamePasswordAuthenticationToken(userDetails,
                                                                                null,
                                                                                userDetails.getAuthorities());
        authenticationToken.setDetails(userDetails);
        return authenticationToken;
    }

}
