package com.softropic.skillars.platform.security.service;


import com.softropic.skillars.infrastructure.security.CookieUtil;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import com.softropic.skillars.platform.security.contract.Gender;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.contract.SkillarsProfileCookie;
import com.softropic.skillars.platform.security.contract.util.AuthoritiesConstants;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;

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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.REFRESH_TOKEN_COOKIE;


/**
 * Utility class for Spring Security.
 */
@Slf4j
@Component
public final class SecurityUtil {

    private final LoginTokenManager loginTokenManager;
    private final RefreshTokenRepository refreshTokenRepository;


    public SecurityUtil(LoginTokenManager loginTokenManager,
                        RefreshTokenRepository refreshTokenRepository) {
        this.loginTokenManager = loginTokenManager;
        this.refreshTokenRepository = refreshTokenRepository;
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


    /**
     * skillars-deferred-143: the single implementation of "end this session completely", used by
     * every caller that needs it — {@code JWTAuthorizationFilter}'s forced logout on a genuine
     * denial, {@code AuthService.logout()}'s voluntary logout, and
     * {@code AuthService.refresh()}'s rejection branches.
     *
     * <p>Replaces the previous {@code logout(HttpServletResponse)}, which cleared only the six
     * cookies {@code LoginTokenManager#deleteLoginToken} handled at the time ({@code potc},
     * {@code bcookie}, {@code user}, {@code admin}, {@code ION}, {@code rint} — {@code skp} is a
     * seventh, added to {@code deleteLoginToken} by skillars-deferred-144 AC2) and left both
     * {@code rtkn} and {@code skp} standing, with the underlying {@code refresh_tokens} row still
     * unused — so a forcibly denied session kept a live credential that {@code POST
     * /api/auth/refresh} could trade back in for a new one.
     *
     * <p>Callable from any transactional context, including none at all (the filter has no ambient
     * transaction): {@link RefreshTokenRepository#markUsedByTokenHash(String)} commits in its own
     * {@code REQUIRES_NEW} transaction regardless of caller, which is what makes the revocation
     * durable even when the calling method's own transaction rolls back. {@code SecurityUtil}
     * therefore needs no {@code @Transactional} of its own — and could not carry one anyway, being
     * a {@code final} class implementing no interface, so Spring cannot proxy it.
     */
    public void terminateSession(final HttpServletRequest request, final HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        final String rawToken = CookieUtil.getCookieValue(request, REFRESH_TOKEN_COOKIE);
        if (rawToken != null && !rawToken.isBlank()) {
            // skillars-deferred-144 AC6.4: this write is now unconditional on every caller,
            // including AuthService.logout() — pre-skillars-deferred-143, that path only revoked
            // if the token wasn't already used. Now it always runs, but costs nothing extra on an
            // already-used token: skillars-deferred-144 AC6.6 narrowed the underlying @Query
            // (RefreshTokenRepository#markUsedByTokenHash) to also require r.used = false, so a
            // repeat call against an already-revoked row matches zero rows rather than issuing a
            // redundant UPDATE — an accepted cost of the bulk-update redesign that avoids the
            // self-deadlock documented on AuthService.refresh(), never previously stated as a
            // tradeoff here.
            refreshTokenRepository.markUsedByTokenHash(sha256Hex(rawToken));
        }
        clearAuthCookies(response);
    }

    /**
     * The cookie half of {@link #terminateSession} on its own: drops the seven cookies
     * {@code deleteLoginToken} owns (including {@code skp}, as of skillars-deferred-144 AC2) plus
     * {@code rtkn}, without touching the database. The explicit {@code skp} removal below is
     * therefore redundant with {@code deleteLoginToken}'s own new removal on every caller of this
     * method — harmless (a duplicate {@code Set-Cookie: skp=; Max-Age=0} header) and kept
     * deliberately: {@code SecurityUtilTest.terminateSession_clearsRefreshTokenAndProfileCookies}
     * mocks {@code loginTokenManager}, so it can only observe THIS method's own direct removal
     * calls, not whatever the mocked {@code deleteLoginToken} would have done.
     *
     * <p>Callers, each with its own reason to skip {@link #terminateSession}'s DB revocation:
     * <ul>
     *     <li>{@link #terminateSession} itself, as its own last step — it has already issued its
     *         own {@code markUsedByTokenHash} revocation above, so this call exists purely for the
     *         cookie-clearing side; this is the one caller skipping the revocation because it is
     *         not this method's caller to skip at all, but the author of it.</li>
     *     <li>{@code AuthService.refresh()}'s optimistic-lock-loser branch — runs after its own
     *         transaction has already issued an {@code UPDATE} against the token row, so a
     *         {@code REQUIRES_NEW} revocation here would self-deadlock against it.</li>
     *     <li>{@code AuthService.refresh()}'s two reuse-detection branches (skillars-deferred-144
     *         AC6.2) — {@code refreshTokenRepository.markAllUsedByUserId} has already revoked
     *         every token for the user immediately before either call, so
     *         {@code terminateSession}'s own per-token {@code markUsedByTokenHash} would be a
     *         redundant extra write.</li>
     * </ul>
     * This is not a general-purpose "log out" and does not end the session's server-side
     * credential by itself — it relies on the caller having already revoked (or never needing to
     * revoke) the underlying {@code refresh_tokens} row.
     */
    public void clearAuthCookies(final HttpServletResponse response) {
        loginTokenManager.deleteLoginToken(response);
        CookieUtil.removeCookie(REFRESH_TOKEN_COOKIE, response, true, "Lax");
        SkillarsProfileCookie.removeFrom(response);
    }

    /**
     * Duplicated from {@code AuthService}'s identical private helper rather than extracted: this
     * project has no shared crypto-helper home, and one static four-line method with two call sites
     * does not justify inventing one.
     */
    private static String sha256Hex(final String raw) {
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256")
                                             .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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

    /**
     * Safely resolves the current authenticated user's business ID.
     *
     * @return the current user's business ID
     * @throws InsufficientAuthenticationException if the current principal is not a
     *         {@link Principal} or its business ID is not a valid numeric ID
     */
    public Long requireCurrentUserId() {
        Object principal = getCurrentUser();
        if (!(principal instanceof Principal p)) {
            throw new InsufficientAuthenticationException("Unexpected security principal type");
        }
        try {
            return Long.parseLong(p.getBusinessId());
        } catch (NumberFormatException e) {
            throw new InsufficientAuthenticationException("Principal businessId is not a valid user ID");
        }
    }

    /**
     * Safely resolves the current authenticated user's raw business ID, without requiring it
     * to be numeric. Use this instead of {@link #requireCurrentUserId()} when the business ID
     * is compared/stored as an opaque owner key rather than parsed as a user ID (e.g. file
     * storage ownership, which also accepts non-numeric owner keys like tenant identifiers).
     *
     * @return the current user's raw business ID
     * @throws InsufficientAuthenticationException if the current principal is not a
     *         {@link Principal} or has no business ID
     */
    public String requireCurrentBusinessId() {
        Object principal = getCurrentUser();
        if (!(principal instanceof Principal p) || p.getBusinessId() == null || p.getBusinessId().isBlank()) {
            throw new InsufficientAuthenticationException("Unexpected security principal type or missing business ID");
        }
        return p.getBusinessId();
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
