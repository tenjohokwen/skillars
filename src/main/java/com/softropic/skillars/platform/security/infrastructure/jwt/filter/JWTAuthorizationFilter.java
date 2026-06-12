package com.softropic.skillars.platform.security.infrastructure.jwt.filter;


import com.softropic.skillars.infrastructure.security.event.AuthenticationAction;
import com.softropic.skillars.infrastructure.security.event.PreAuthEvent;
import com.softropic.skillars.platform.security.service.LoginTokenManager;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.infrastructure.security.AuthorizationException;
import com.softropic.skillars.platform.security.contract.exception.JWTTheftException;
import com.softropic.skillars.platform.security.contract.exception.MissingAuthenticationException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.security.infrastructure.SecuredHttpEndpointGuard;
import com.softropic.skillars.platform.security.service.DaoAuthProvider;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


/**
 * Acts as an access decision maker. If the JWT is available, it uses it to determine whether the secured request is allowed or not.
 * Could have used an accessDecisionVoter, but it performs the following as well
 * <ul>
 *     <li>Refreshes the JWT</li>
 *     <li>Sets the authentication object on the securityContext</li>
 *     <li>Verifies if token theft has occurred</li>
 * </ul>
 * It is important to note that just authentication exceptions are thrown in this class as opposed to authorization exceptions.
 *
 * An Exception is thrown in the following cases:
 * <ul>
 *     <li>The JWT is not valid or forged</li>
 *     <li>An id theft is detected</li>
 *     <li>The client is not whitelisted</li>
 * </ul>
 */
public class JWTAuthorizationFilter extends OncePerRequestFilter {
    private final ApplicationEventPublisher publisher;
    private final DaoAuthProvider          daoAuthProvider;
    private final SecuredHttpEndpointGuard httpEndpointGuard;
    private final LoginTokenManager loginTokenManager;
    private final SecurityUtil     securityUtil;
    private final Environment       env;

    public JWTAuthorizationFilter(DaoAuthProvider daoAuthProvider,
                                  ApplicationEventPublisher applicationEventPublisher,
                                  SecuredHttpEndpointGuard httpEndpointGuard,
                                  LoginTokenManager loginTokenManager,
                                  SecurityUtil securityUtil,
                                  Environment env
    ) {
        super();
        this.publisher = applicationEventPublisher;
        this.daoAuthProvider = daoAuthProvider;
        this.httpEndpointGuard = httpEndpointGuard;
        this.loginTokenManager = loginTokenManager;
        this.securityUtil = securityUtil;
        this.env = env;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest req,
                                    final HttpServletResponse res,
                                    final FilterChain chain) throws IOException, ServletException {
        if(env.getProperty("activate.security", Boolean.class, true)) {
            if(!httpEndpointGuard.isUnrestricted(req)) {
                try {
                    attemptAuthorization(req, res);
                }
                catch (AccountStatusException | AuthorizationException | AccessDeniedException e) {
                    //includes AccountExpiredException, CredentialsExpiredException, LockedException, InvalidJWTDataException, JWTTheftException, JWTExpiredException, AccessDeniedException (missing token)
                    securityUtil.logout(res);
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
            } else {
                //Put anonymous authentication token
                final UsernamePasswordAuthenticationToken authentication = getAuthentication(req);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        chain.doFilter(req, res);
    }


    private void attemptAuthorization(final HttpServletRequest req, final HttpServletResponse res) {
        loginTokenManager.ensureClientHasPostLoginId();
        loginTokenManager.ensureAuthTokenPresent(req);
        final UsernamePasswordAuthenticationToken authentication = getAuthentication(req);

        SecurityContextHolder.getContext().setAuthentication(authentication);
        publisher.publishEvent(new PreAuthEvent(authentication, AuthenticationAction.PRE_AUTHORIZATION));
        if(loginTokenManager.isTokenFixed(req)) {
            throw new JWTTheftException("An attempt to use a token from a different client detected.");
        }
        //The underlying JWT impl will throw an exception if the JWT itself has expired when trying to read the token
        // Check if db refresh token has expired. If so load credentials from the db
        if (loginTokenManager.hasDbRefreshTokenExpired(req)) {
            //An exception would be thrown if the user account is locked or not enabled
            final Authentication auth = daoAuthProvider.authorize(authentication,
                                                                  httpEndpointGuard.requiredAuthorities(req));
            //JWT with new db refresh token and new expiration
            //JWTUtil.e
            loginTokenManager.renewLoginToken(res, (Principal) auth.getDetails());
        } else {
            final Principal principal = loginTokenManager.extractPrincipal(req);
            var authorities = CollectionUtils.emptyIfNull(principal == null ? null : principal.getAuthorities());
            daoAuthProvider.checkAuthorities(httpEndpointGuard.requiredAuthorities(req), authorities);
            //Emulates HttpSession ttl extension in which each request renews the ttl by X minutes
            // Just the ttl is extended. The dbRefreshToken is not touched.
            //Once the db refresh token expires, a call to the db is done whereas it is not done here.
            loginTokenManager.extendTtlOfToken(req, res);
        }
    }

    private UsernamePasswordAuthenticationToken getAuthentication(final HttpServletRequest request) {
        final UserDetails userDetails = loginTokenManager.extractPrincipal(request);
        if(userDetails == null) {
            //this is an accessDeniedException and not an AuthenticationException because the user does not have any token
            throw new MissingAuthenticationException("Cannot find access token cookie.", SecurityError.MISSING_TOKEN);
        }
        final var authenticationToken = new UsernamePasswordAuthenticationToken(userDetails,
                                                                                null,
                                                                                userDetails.getAuthorities());
        authenticationToken.setDetails(userDetails);
        return authenticationToken;
    }

}
