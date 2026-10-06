package com.softropic.skillars.platform.security.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence for {@link RefreshToken} — the long-lived (7-day, {@code SecurityConstants
 * .REFRESH_TOKEN_TTL}) credential behind the {@code rtkn} cookie that lets a client trade a
 * stale/expired JWT for a new one via {@code POST /api/auth/refresh}, without the user having to
 * log in again. The JWT itself ({@code potc} cookie, 15-min TTL) is stateless and never stored —
 * this table is the only server-side state in the auth flow, which is why every write here is
 * security-sensitive: it is the actual list of "sessions that are still allowed to exist".
 *
 * <p>A row's lifecycle: created {@code used = false} by {@code AuthService.login()}; on a refresh,
 * the presented row is rotated (old one flips to {@code used = true}, a new row is created) by
 * {@code AuthService.refresh()}. {@code used} is a monotonic flag — once {@code true}, a row never
 * goes back to {@code false} — so it doubles as a revocation marker: "used" and "revoked" are the
 * same bit. A row leaves the table only by expiry (never by revocation), via the cleanup sweep
 * below.
 *
 * <p><strong>Who calls what, and why:</strong>
 * <ul>
 *   <li>{@code AuthService.login()}/{@code refresh()} — the only writers of new rows ({@code save}),
 *       and the only callers of {@link #findByTokenHash}, to look up the token presented on a
 *       refresh request.</li>
 *   <li>{@code AuthService.refresh()}'s theft-detection branches, {@code SecurityUtil
 *       .terminateSession()} (logout / forced logout), and {@code GdprErasureService} (account
 *       erasure) all call {@link #markAllUsedByUserId} or {@link #markUsedByTokenHash} to revoke
 *       sessions — the former kills every token for a user, the latter just the one token a single
 *       request is tied to.</li>
 *   <li>{@code AuthService.refresh()} calls {@link
 *       #findFirstByUserIdAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc} within its multi-tab
 *       grace window to find a live successor when a just-rotated (not stolen) token is
 *       re-presented, so a harmless Tab-A-refreshed/Tab-B-still-holds-old-cookie race is redirected
 *       to the successor instead of being treated as theft. (Until skillars-deferred-144 AC8,
 *       {@code JWTAuthorizationFilter} was a second caller, using it to force an early DB re-auth
 *       once a user's tokens were all revoked — removed as a per-request DB query whose only
 *       benefit was catching GDPR erasure / theft-revocation faster than this filter already
 *       tolerated for an admin-locked account, which never had an early-detection shortcut at
 *       all.)</li>
 *   <li>{@code AuthCleanupService} runs {@link #deleteExpiredTokens} on an hourly
 *       {@code @Scheduled} cron job to bulk-delete rows past {@code expiresAt} — housekeeping, not
 *       revocation (an expired row is already useless to {@code AuthService.refresh()}'s own
 *       expiry check; this just keeps the table from growing forever).</li>
 * </ul>
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** Looks up the row for the raw token presented on a refresh request, by its SHA-256 hash. */
    Optional<RefreshToken> findByTokenHash(String hash);

    /**
     * The one still-live (unused, unexpired) token for a user, newest first. {@code AuthService
     * .refresh()} uses it inside its multi-tab grace window to find the live successor of a
     * just-rotated token, so it can redirect a harmless refresh race instead of treating it as
     * theft. (Until skillars-deferred-144 AC8, {@code JWTAuthorizationFilter} was a second caller
     * — see the class-level javadoc above for why that per-request lookup was removed.)
     */
    Optional<RefreshToken> findFirstByUserIdAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            Long userId, Instant now);

    /**
     * skillars-deferred-100 AC3: bulk {@code UPDATE} against a {@code @Version} table
     * ({@link RefreshToken}). skillars-deferred-101 AC11: bumps {@code version} for defence-in-depth.
     * The only column written (besides version), {@code used}, is a monotonic terminal flag: a row
     * is created {@code used = false} and only ever moves to {@code used = true}. Every concurrent
     * managed writer also only ever sets {@code used = true}, so a stale managed {@code save()}
     * racing this bulk update cannot resurrect a revoked token to {@code used = false}. The version
     * bump ensures that any concurrent stale write touching another column fails its optimistic-lock
     * check instead of silently succeeding, preventing unintended state resurrection.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE RefreshToken r SET r.used = true, r.version = r.version + 1 WHERE r.userId = :userId")
    void markAllUsedByUserId(@Param("userId") Long userId);

    /**
     * skillars-deferred-143 AC2: the single-token sibling of {@link #markAllUsedByUserId(Long)},
     * carrying the identical {@code REQUIRES_NEW} propagation for the identical reason — a caller
     * that revokes-then-throws must not have the revocation undone by its own rollback.
     *
     * <p>The concrete case: {@code AuthService.refresh()} is its own outermost transaction boundary
     * ({@code AuthService} is {@code @Transactional}, {@code AuthResource} is not, and
     * {@code spring.jpa.open-in-view} is {@code false}). Its account-liveness rejection throws
     * {@code LockedException}/{@code DisabledException} — both {@code RuntimeException}s — which
     * rolls that transaction back. A plain managed {@code save()} of {@code used = true} on the
     * rejection path would therefore be silently discarded, leaving the presented refresh token
     * alive after the very denial that was supposed to kill it. Committing in a separate
     * transaction is what makes the revocation durable.
     *
     * <p>Safe to call from any context, including no ambient transaction at all (the
     * {@code JWTAuthorizationFilter} forced-logout path has none). The same monotonic-flag
     * reasoning documented on {@link #markAllUsedByUserId(Long)} applies verbatim: {@code used}
     * only ever moves {@code false -> true}, so a concurrent managed writer cannot resurrect a
     * revoked token, and the {@code version} bump fails any concurrent stale write touching
     * another column instead of letting it silently succeed — <strong>except on a row already
     * {@code used = true}</strong> (skillars-deferred-144 AC6.6): the {@code WHERE} clause below
     * now also requires {@code r.used = false}, so a repeat call against an already-revoked row
     * matches zero rows and skips the {@code version} bump entirely, rather than re-writing it. This
     * bounds the cost of a client that ignores {@code Set-Cookie} and keeps replaying the same
     * stale JWT at zero rows per repeat call, instead of needing a separate throttle. The
     * documented optimistic-locking contract above is therefore NOT true for an already-used row:
     * a concurrent stale write touching another column (e.g. {@code rotatedAt}, {@code expiresAt})
     * on a row that is already {@code used = true} will silently succeed rather than fail the
     * version check, since this query no longer touches that row at all. Since {@code used} itself
     * can never move back to {@code false}, the residual risk is cosmetic — no code path re-reads
     * {@code version} to gate on it today — but it is a real, deliberate delta from the contract
     * documented above, not a side effect to discover later.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE RefreshToken r SET r.used = true, r.version = r.version + 1 "
        + "WHERE r.tokenHash = :tokenHash AND r.used = false")
    void markUsedByTokenHash(@Param("tokenHash") String tokenHash);

    @Modifying
    @Transactional
    @Query("DELETE FROM RefreshToken r WHERE r.expiresAt < CURRENT_TIMESTAMP")
    void deleteExpiredTokens();
}
