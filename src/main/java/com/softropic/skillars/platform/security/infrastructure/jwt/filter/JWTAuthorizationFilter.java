package com.softropic.skillars.platform.security.infrastructure.jwt.filter;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.infrastructure.message.ErrorDto;
import com.softropic.skillars.infrastructure.message.ErrorLog;
import com.softropic.skillars.infrastructure.message.ErrorMsg;
import com.softropic.skillars.infrastructure.security.CookieUtil;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import com.softropic.skillars.infrastructure.security.event.AuthenticationAction;
import com.softropic.skillars.infrastructure.security.event.PreAuthEvent;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;
import com.softropic.skillars.platform.security.service.LoginTokenManager;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.infrastructure.security.AuthorizationException;
import com.softropic.skillars.platform.security.contract.event.SecurityAlertEvent;
import com.softropic.skillars.platform.security.contract.exception.InvalidJWTDataException;
import com.softropic.skillars.platform.security.contract.exception.JWTExpiredException;
import com.softropic.skillars.platform.security.contract.exception.JWTTheftException;
import com.softropic.skillars.platform.security.contract.exception.MissingAuthenticationException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.security.infrastructure.SecuredHttpEndpointGuard;
import com.softropic.skillars.platform.security.service.DaoAuthProvider;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Instant;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.REFRESH_TOKEN_COOKIE;


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
 *
 * <h2>Sliding-window session keep-alive</h2>
 * On every authenticated request this filter re-issues the JWT with a <b>fresh full TTL</b>
 * ({@code SecurityConstants.JWT_TTL}, 15 min) rather than counting down from login:
 * <ul>
 *     <li><b>Fast path</b> &mdash; DB refresh token still valid and no revocation:
 *         {@code daoAuthProvider.checkAuthorities(...)} against the existing claims, then
 *         {@code loginTokenManager.extendTtlOfToken(req, res)} stamps a new expiry. This path does
 *         <i>not</i> re-load the account, but it still issues one {@code refresh_tokens} lookup
 *         ({@code isRefreshTokenRevoked}) whenever an {@code rtkn} cookie is present.</li>
 *     <li><b>DB re-auth path</b> &mdash; DB refresh token expired (checked every
 *         {@code DB_REFRESH_TOKEN_INTERVAL} = 5 min) <i>or</i> all refresh tokens for the user have
 *         been revoked: {@code daoAuthProvider.authorize(...)} re-checks the account against the DB
 *         so locked / deactivated / force-logged-out users are caught, then
 *         {@code renewLoginToken(...)} mints the fresh token. ({@code renewLoginToken} itself touches
 *         no repository &mdash; the DB hit is {@code daoAuthProvider.authorize}.)</li>
 * </ul>
 * Either path also rewrites the {@code user}, {@code potc} and {@code rint} cookies (see
 * {@code JwtManagerImpl.createLoginCookies}). Because the TTL is always reset to the full 15 min,
 * {@code rint} is effectively constant and the client, not the server, tracks the countdown.
 * <p>
 * This is why {@code GET /refresh} keeps a session alive: the request is a secured endpoint, so it
 * passes through this filter (which extends the token) <i>before</i> reaching
 * {@link com.softropic.skillars.platform.security.infrastructure.filter.SessionRefreshFilter},
 * which merely returns 200 (wired in {@code SecurityConfiguration#filterChain}, the
 * {@code addFilterAfter(new SessionRefreshFilter(...), JWTAuthorizationFilter.class)} line).
 * For full token rotation (issuing a new refresh-token pair) use {@code POST /api/auth/refresh}
 * ({@link com.softropic.skillars.platform.security.api.AuthResource}) instead.
 */
@Slf4j
public class JWTAuthorizationFilter extends OncePerRequestFilter {
    private final ApplicationEventPublisher publisher;
    private final DaoAuthProvider          daoAuthProvider;
    private final SecuredHttpEndpointGuard httpEndpointGuard;
    private final LoginTokenManager loginTokenManager;
    private final SecurityUtil     securityUtil;
    private final Environment       env;
    private final RefreshTokenRepository refreshTokenRepository;
    private final MessageSource     messageSource;
    private final ObjectMapper      objectMapper;
    /**
     * Code review (3-layer run): {@link SecurityAlertEvent} writes one {@code AuditTrail} DB row per
     * event. AC5/F22 scoped it away from the two highest-volume causes, but the remaining three are
     * still per-request: a tampered JWT cookie is attacker-drivable at any rate, and a
     * locked/disabled account emits one on EVERY request once its ~15-min DB refresh token lapses.
     * Collapse a repeating denial state to one row per window per client+cause.
     */
    private final SecurityAlertThrottle alertThrottle = new SecurityAlertThrottle();

    public JWTAuthorizationFilter(DaoAuthProvider daoAuthProvider,
                                  ApplicationEventPublisher applicationEventPublisher,
                                  SecuredHttpEndpointGuard httpEndpointGuard,
                                  LoginTokenManager loginTokenManager,
                                  SecurityUtil securityUtil,
                                  Environment env,
                                  RefreshTokenRepository refreshTokenRepository,
                                  MessageSource messageSource,
                                  ObjectMapper objectMapper
    ) {
        super();
        this.publisher = applicationEventPublisher;
        this.daoAuthProvider = daoAuthProvider;
        this.httpEndpointGuard = httpEndpointGuard;
        this.loginTokenManager = loginTokenManager;
        this.securityUtil = securityUtil;
        this.env = env;
        this.refreshTokenRepository = refreshTokenRepository;
        this.messageSource = messageSource;
        this.objectMapper = objectMapper;
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
                    // Emit an ErrorDto body (not a bare sendError) so the SPA's axios interceptor
                    // can read errorMsg.errorKey and route to /login. Status stays 401 for every
                    // caught type; the key is 'security.sessionExpired' only for an expired JWT.
                    // Code review (3-layer run): mint the helpCode and publish the alert BEFORE the
                    // response is written. Publishing afterwards let a slow or throwing synchronous
                    // listener act on an already-committed response, and its exception propagated
                    // out of doFilterInternal with the 401 body half-sent.
                    final String helpCode = mintHelpCode(e);
                    maybePublishSecurityAlert(e, helpCode);
                    writeUnauthorized(res, e, helpCode);
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
            // If the request carries a refresh-token cookie but all tokens for this user
            // have been revoked (e.g. GDPR erasure, force-logout), skip the TTL extension
            // and force a DB re-auth so the locked/deactivated state is detected immediately.
            if (isRefreshTokenRevoked(req, principal)) {
                final Authentication auth = daoAuthProvider.authorize(authentication,
                                                                      httpEndpointGuard.requiredAuthorities(req));
                loginTokenManager.renewLoginToken(res, (Principal) auth.getDetails());
            } else {
                var authorities = CollectionUtils.emptyIfNull(principal == null ? null : principal.getAuthorities());
                daoAuthProvider.checkAuthorities(httpEndpointGuard.requiredAuthorities(req), authorities);
                //Emulates HttpSession ttl extension in which each request renews the ttl by X minutes
                // Just the ttl is extended. The dbRefreshToken is not touched.
                //Once the db refresh token expires, a call to the db is done whereas it is not done here.
                loginTokenManager.extendTtlOfToken(req, res);
            }
        }
    }

    private boolean isRefreshTokenRevoked(HttpServletRequest req, Principal principal) {
        final String rtkn = CookieUtil.getCookieValue(req, REFRESH_TOKEN_COOKIE);
        if (rtkn == null || rtkn.isBlank() || principal == null) {
            return false;
        }
        try {
            Long userId = Long.parseLong(principal.getBusinessId());
            return refreshTokenRepository
                    .findFirstByUserIdAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(userId, Instant.now())
                    .isEmpty();
        } catch (NumberFormatException e) {
            return false;
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

    /**
     * Writes a 401 response whose body matches the application {@link ErrorDto} shape
     * ({@code {helpCode, errorMsg: {errorKey, message}, fieldErrors: []}}) so the SPA's axios
     * interceptor can gate on {@code errorMsg.errorKey}. An expired JWT yields
     * {@code security.sessionExpired} (aligned with {@code ApiAdvice.jwtExpirationHandler});
     * anything else caught here yields {@code security.unauthorized}. The status is 401 in all
     * cases &mdash; this filter deliberately does not defer to {@code @RestControllerAdvice}
     * (which would remap some of these to 403).
     */
    /**
     * Logs {@code cause} and returns its support help code, without touching the response.
     * Split out of {@link #writeUnauthorized} so the {@code SecurityAlertEvent} can carry the code
     * and be published before the response is committed.
     */
    private String mintHelpCode(final Exception cause) {
        final boolean expired = cause instanceof JWTExpiredException;
        final String errorKey = expired ? "security.sessionExpired" : "security.unauthorized";
        // skillars-deferred-90 AC5: route through the same ErrorLog mechanism @RestControllerAdvice
        // uses, so a filter-origin 401 also gets a log line and a support helpCode (was null).
        //
        // Code review D2: level is split by cause. A tokenless request (MissingAuthenticationException
        // — crawlers, stale bookmarks, pre-login SPA routes) and an ordinary idle-out
        // (JWTExpiredException) are expected traffic on an unauthenticated, unrate-limited path;
        // ERROR + a full stack trace for those is the same volume problem that made AC5 scope
        // SecurityAlertEvent away from them (maybePublishSecurityAlert below). Genuine denials keep
        // ERROR + stack trace. The helpCode is minted and returned either way.
        final String logMsg = "Filter-origin 401 (" + errorKey + ")";
        final boolean expectedTraffic = cause instanceof MissingAuthenticationException
                || cause instanceof JWTExpiredException;
        return expectedTraffic
                ? ErrorLog.logExpected(log, cause, logMsg)
                : ErrorLog.logError(log, cause, logMsg);
    }

    private void writeUnauthorized(final HttpServletResponse res, final Exception cause,
                                   final String helpCode) throws IOException {
        final boolean expired = cause instanceof JWTExpiredException;
        final String errorKey = expired ? "security.sessionExpired" : "security.unauthorized";
        final String defaultMsg = expired
                ? "Your session is no longer valid. You need to sign-in again"
                : "Unauthorized Access."; // matches ApiAdvice.handleAuthException's default for this key
        final String lang = RequestMetadataProvider.getClientInfo().getChosenLang();
        final Locale locale = StringUtils.isNotBlank(lang) ? Locale.forLanguageTag(lang) : Locale.getDefault();
        final String message = messageSource.getMessage(errorKey, null, defaultMsg, locale);

        final ErrorDto body = new ErrorDto(helpCode, new ErrorMsg(errorKey, message));
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(res.getWriter(), body);
    }

    /**
     * skillars-deferred-90 AC5 (F22): a {@link SecurityAlertEvent} writes one {@code AuditTrail} DB
     * row per event, so it is fired ONLY for genuine denial signals. It is deliberately NOT fired
     * for {@link MissingAuthenticationException} (thrown for every tokenless request to a secured
     * URL — crawlers, stale bookmarks, lapsed 15-min sessions, pre-login SPA routes) nor for
     * {@link JWTExpiredException} (every idle-out) — alerting on those would turn an
     * unauthenticated, unrate-limited path into an audit-trail flood / DB-write amplifier.
     */
    private void maybePublishSecurityAlert(final Exception cause, final String helpCode) {
        final boolean genuineDenial = cause instanceof JWTTheftException
                || cause instanceof InvalidJWTDataException
                || cause instanceof AccountStatusException;
        if (genuineDenial && alertThrottle.shouldPublish(cause)) {
            publisher.publishEvent(new SecurityAlertEvent(cause, helpCode));
        }
    }

    /**
     * Per-client, per-cause dedup window for {@link SecurityAlertEvent}. Bounded, self-evicting,
     * and deliberately tiny — this is volume control, not a security decision: every denial is still
     * logged by {@link #mintHelpCode}, only the audit-trail row is collapsed.
     */
    static final class SecurityAlertThrottle {

        private static final long WINDOW_MS = 60_000L;
        private static final int MAX_TRACKED = 10_000;

        private final java.util.concurrent.ConcurrentHashMap<String, Long> lastPublished =
                new java.util.concurrent.ConcurrentHashMap<>();

        boolean shouldPublish(final Exception cause) {
            final String client = StringUtils.defaultIfBlank(
                    RequestMetadataProvider.getClientInfo().getClientIdentifier(),
                    StringUtils.defaultString(RequestMetadataProvider.getClientInfo().getIpAddress(), "unknown"));
            final String key = cause.getClass().getSimpleName() + "|" + client;
            final long now = System.currentTimeMillis();

            // Unbounded growth guard: a rotating-client attacker would otherwise mint a new key per
            // request. Dropping the map costs at most one extra audit row per tracked client.
            if (lastPublished.size() > MAX_TRACKED) {
                lastPublished.clear();
            }

            final Long previous = lastPublished.merge(key, now,
                    (prev, candidate) -> candidate - prev >= WINDOW_MS ? candidate : prev);
            return previous == now;
        }
    }

}
