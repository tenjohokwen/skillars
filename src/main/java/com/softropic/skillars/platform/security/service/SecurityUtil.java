package com.softropic.skillars.platform.security.service;


import com.softropic.skillars.infrastructure.security.CookieUtil;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import com.softropic.skillars.platform.security.contract.Gender;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.contract.SkillarsProfileCookie;
import com.softropic.skillars.platform.security.contract.util.AuthoritiesConstants;

import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.PessimisticLockingFailureException;
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
import java.util.UUID;

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

    /** Attempts {@link #revokeChainWithBoundedRetry} makes before giving up and logging at error. */
    private static final int REVOKE_ATTEMPTS = 3;

    private final LoginTokenManager loginTokenManager;
    private final RefreshTokenRevocationService refreshTokenRevocationService;


    public SecurityUtil(LoginTokenManager loginTokenManager,
                        RefreshTokenRevocationService refreshTokenRevocationService) {
        this.loginTokenManager = loginTokenManager;
        this.refreshTokenRevocationService = refreshTokenRevocationService;
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
     * skillars-deferred-167 (AC4): does this refresh-token rotation chain still have a live token?
     *
     * <p>A thin delegate, for the same reason {@link #terminateSession} delegates its chain walk:
     * {@code JWTAuthorizationFilter} depends on this class and not on the repository or the revocation
     * service, and threading a new dependency into that filter's constructor (and into everything that
     * builds it) to ask one boolean would be the larger change. The answer itself, and why a null id
     * answers {@code true}, are documented on
     * {@link RefreshTokenRevocationService#hasLiveTokenForSession(UUID, Long)}.
     */
    public boolean hasLiveRefreshTokenForSession(final UUID refreshSessionId, final Long userId) {
        return refreshTokenRevocationService.hasLiveTokenForSession(refreshSessionId, userId);
    }

    /**
     * skillars-deferred-167 round 2: the chain id for the request's own {@code rtkn} cookie, or
     * {@code null} if there is no cookie, no matching row, or the row belongs to someone else.
     *
     * <p>Called only when the JWT carries no chain claim, to put back one that a rolling deploy stripped
     * (an instance on the previous release re-signs without the claim it does not know about, and the new
     * code then passes the absence through for the rest of that session's life). It is therefore one extra
     * query on an already-exceptional path, not a per-request cost — which is why AC4's "exactly one
     * query" still holds for every normal request.
     */
    public UUID refreshSessionIdForRequest(final HttpServletRequest request, final Long userId) {
        final String rawToken = CookieUtil.getCookieValue(request, REFRESH_TOKEN_COOKIE);
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        // Hashed here because this class already owns the hashing the rest of the auth code uses; the
        // service never sees a raw token, matching how terminateSession hands it a hash too.
        return refreshTokenRevocationService.sessionIdForTokenHash(sha256Hex(rawToken), userId);
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
     * transaction): {@link RefreshTokenRevocationService#revokeChainByTokenHash(String)} commits in its own
     * {@code REQUIRES_NEW} transaction regardless of caller, which is what makes the revocation
     * durable even when the calling method's own transaction rolls back. The one rule it imposes on
     * callers is the converse: never call this while holding a row lock on a token it will touch, or the
     * inner transaction waits for a lock only the caller can release (55P03). {@code AuthService.refresh}
     * is structured around that. {@code SecurityUtil}
     * therefore needs no {@code @Transactional} of its own — and could not carry one anyway, being
     * a {@code final} class implementing no interface, so Spring cannot proxy it.
     */
    public void terminateSession(final HttpServletRequest request, final HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        final String rawToken = CookieUtil.getCookieValue(request, REFRESH_TOKEN_COOKIE);
        if (rawToken != null && !rawToken.isBlank()) {
            // skillars-deferred-144 AC6.4: this write is unconditional on every caller, including
            // AuthService.logout() — pre-skillars-deferred-143, that path only revoked if the token
            // wasn't already used.
            //
            // skillars-deferred-166 (ledger RS01): it revokes the whole ROTATION CHAIN, not just the row
            // the cookie hashes to. markUsedByTokenHash could not end a session that had just been
            // refreshed: its WHERE clause requires `used = false` (skillars-deferred-144 AC6.6), so a
            // token rotated a moment before logout matched zero rows and the successor that rotation had
            // minted stayed live for the rest of its 7-day TTL. Waiting on the in-flight rotation would not
            // have helped either — Postgres re-checks the predicate after the lock is released and still
            // matches nothing. The walk follows `replaced_by_id` (V224) under row locks, which is what
            // makes it correct against a concurrent rotation rather than merely likely to win.
            //
            // Still cheap to repeat, which the JWT filter's per-request teardown depends on: a row that
            // already carries `revoked_at` is skipped, so a client replaying a dead JWT costs reads, not
            // writes — the same bound AC6.6's `used = false` predicate provided.
            revokeChainWithBoundedRetry(sha256Hex(rawToken));
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
     *         own chain revocation above (skillars-deferred-166; it was {@code markUsedByTokenHash}
     *         until that story), so this call exists purely for the
     *         cookie-clearing side; this is the one caller skipping the revocation because it is
     *         not this method's caller to skip at all, but the author of it.</li>
     *     <li>{@code AuthService.refresh()}'s optimistic-lock-loser branch — runs after its own
     *         transaction has already issued an {@code UPDATE} against the token row, so a
     *         {@code REQUIRES_NEW} revocation here would self-deadlock against it.</li>
     *     <li>{@code AuthService.refresh()}'s reuse-detection branch (skillars-deferred-144
     *         AC6.2) — {@code refreshTokenRepository.markAllUsedByUserId} has already revoked
     *         every token for the user immediately before the call, so
     *         {@code terminateSession}'s own chain revoke would be a redundant extra write.</li>
     *     <li>{@code AuthService.refresh()}'s revoked-chain branch (skillars-deferred-166) — the row
     *         already carries a {@code revoked_at}, written by whoever ended the session while walking
     *         the whole chain, so there is nothing left to revoke.</li>
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
     * skillars-deferred-166: the chain revoke, retried a bounded number of times if it loses a row lock.
     *
     * <p>{@code findByTokenHashForUpdate} waits for the row rather than failing fast (see its javadoc for
     * why {@code NO_WAIT} is wrong here), so the failure this retries is a genuine deadlock — SQLState
     * 40P01, which PostgreSQL resolves by killing one side, most plausibly against
     * {@code markAllUsedByUserId}'s bulk {@code UPDATE} over the same user's rows. A logout must not give
     * up on that: the likeliest contender is a concurrent refresh on the same chain, which is the exact
     * race ledger RS01 is about, and losing the revoke there is the defect this story exists to fix. Each
     * attempt is a fresh
     * {@code REQUIRES_NEW} transaction, because this class is not transactional and the call crosses the
     * service's proxy — so retrying here really does re-take the lock rather than re-run inside a
     * transaction PostgreSQL has already aborted.
     *
     * <p>If every attempt loses, the cookies are still cleared and the caller still succeeds: the client's
     * session is over from its own point of view. Logged at error because the server-side credential may
     * have survived, which is a real (if remote) revocation gap and must not be silent.
     */
    private void revokeChainWithBoundedRetry(final String tokenHash) {
        // The two steps retry SEPARATELY, deliberately. Retrying them as one block loses the fallback: the
        // walk's first attempt has already committed revoked_at on the presented row, so a re-run hits
        // revokeChainByTokenHash's own already-revoked fast path, reports "nothing to do" with no bound
        // exceeded, and the method returns as if it had succeeded while the rows past the bound stay live —
        // ledger RS01 again, and logged only at WARN.
        final RefreshTokenRevocationService.ChainRevocation result =
            retrying("chain revoke", () -> refreshTokenRevocationService.revokeChainByTokenHash(tokenHash));
        if (result != null && result.boundExceeded()) {
            // Deliberately out here: the walk's transaction has committed and dropped its row locks, so
            // this bulk UPDATE over the same user's rows can take them. Inside the walk it would wait for
            // locks only the walk could release and hang (see revokeChainByTokenHash's hop-bound comment).
            retrying("account-wide revoke", () -> {
                refreshTokenRevocationService.revokeAllForUser(result.boundExceededForUserId());
                return Boolean.TRUE;
            });
        }
    }

    /**
     * Runs one revocation step, retrying a lock conflict up to {@link #REVOKE_ATTEMPTS} times, and returns
     * {@code null} if every attempt lost.
     *
     * <p>Each attempt is a fresh {@code REQUIRES_NEW} transaction, because this class is not transactional
     * and the call crosses the service's proxy — so a retry really does re-take the lock rather than re-run
     * inside a transaction PostgreSQL has already aborted. Exhausting the attempts is logged at ERROR, not
     * thrown: the caller is ending a session and must still clear its cookies, so the client's session is
     * over from its own point of view, but a server-side credential may have survived and that must not be
     * silent.
     */
    private <T> T retrying(final String step, final java.util.function.Supplier<T> action) {
        for (int attempt = 1; attempt <= REVOKE_ATTEMPTS; attempt++) {
            try {
                return action.get();
            } catch (PessimisticLockingFailureException e) {
                if (attempt == REVOKE_ATTEMPTS) {
                    log.error("Refresh-token {} failed after {} attempts (lock conflict). Cookies are "
                        + "cleared, but a server-side credential may still be live.",
                        step, REVOKE_ATTEMPTS, e);
                    return null;
                }
                log.warn("Refresh-token {} lost a lock conflict (attempt {} of {}); retrying",
                    step, attempt, REVOKE_ATTEMPTS);
            }
        }
        return null;
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
