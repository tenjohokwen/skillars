package com.softropic.skillars.platform.security.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import jakarta.persistence.LockModeType;

/**
 * Persistence for {@link RefreshToken} — the long-lived (7-day, {@code SecurityConstants
 * .REFRESH_TOKEN_TTL}) credential behind the {@code rtkn} cookie that lets a client trade a
 * stale/expired JWT for a new one via {@code POST /api/auth/refresh}, without the user having to
 * log in again. The JWT itself ({@code potc} cookie, 15-min TTL) is stateless and never stored —
 * this table is the only server-side state in the auth flow, which is why every write here is
 * security-sensitive: it is the actual list of "sessions that are still allowed to exist".
 *
 * <p>A row's lifecycle: created {@code used = false} by {@code AuthService.login()}; on a refresh,
 * the presented row is rotated (old one flips to {@code used = true}, a new row is created, and the old
 * row's {@code replacedById} is pointed at the new one) by {@code AuthService.refresh()}. {@code used} is
 * a monotonic flag — once {@code true}, a row never goes back to {@code false}. A row leaves the table
 * only by expiry (never by revocation), via the cleanup sweep below.
 *
 * <p><strong>skillars-deferred-166 (RS01/RS02): "used" and "revoked" are no longer the same bit.</strong>
 * {@code used} is set by rotation <em>and</em> by revocation, so on its own it cannot tell a token that was
 * replaced (successor live) from one whose session was ended. {@code revokedAt} is what does, and
 * {@code replacedById} is what lets a reader find the successor instead of guessing at it:
 * <ul>
 *   <li>{@code used = false} — live.</li>
 *   <li>{@code used = true, revokedAt = null} — rotated; {@code replacedById} names the successor, and a
 *       stale replay inside {@code AuthService.REFRESH_GRACE_WINDOW} is redirected along that link.</li>
 *   <li>{@code revokedAt != null} — the session was ended deliberately; refresh answers 401 without
 *       account-wide revocation.</li>
 * </ul>
 * Before V224 there was no link, so logout revoked only the presented row while it was still unused
 * (leaving a just-rotated successor live for its whole TTL — ledger RS01) and the grace window redirected
 * to the newest live token of the whole <em>account</em>, i.e. possibly another device's session (RS02).
 *
 * <p><strong>Who calls what, and why:</strong>
 * <ul>
 *   <li>{@code AuthService.login()}/{@code refresh()} — the only writers of new rows ({@code save}),
 *       and the only callers of {@link #findByTokenHash}, to look up the token presented on a
 *       refresh request.</li>
 *   <li>{@code AuthService.refresh()}'s theft-detection branch and {@code GdprErasureService} (account
 *       erasure) call {@link #markAllUsedByUserId} to kill every token for a user. {@code
 *       RefreshTokenRevocationService.revokeChainByTokenHash} (skillars-deferred-166) is what
 *       {@code SecurityUtil.terminateSession()} (logout / forced logout) and {@code AuthService
 *       .refresh()}'s per-session denials use instead of {@link #markUsedByTokenHash}: it revokes the
 *       presented row <em>and every successor it was rotated into</em>, which is the whole point of
 *       {@code replacedById}. {@link #markUsedByTokenHash} remains for callers that genuinely mean "this
 *       one row" and is still the single-row primitive the audit tests cover.</li>
 *   <li>{@code AuthService.refresh()} uses {@link #findByTokenHashForUpdate} to take a
 *       {@code PESSIMISTIC_WRITE} lock on the presented row for the whole of its decide-and-rotate block,
 *       and {@link #findByIdForUpdate} to walk {@code replacedById} to the successor under the same lock.
 *       That serialisation is what makes a concurrent logout and refresh safe in either order, and it is
 *       why the lock must never be held across a {@code REQUIRES_NEW} revoke (55P03 self-deadlock — see
 *       {@code AuthService.refresh}'s own comments). (Until skillars-deferred-144 AC8,
 *       {@code JWTAuthorizationFilter} also queried this table per request to force an early DB re-auth
 *       once a user's tokens were all revoked — removed as a per-request DB query whose only benefit was
 *       catching GDPR erasure / theft-revocation faster than this filter already tolerated for an
 *       admin-locked account, which never had an early-detection shortcut at all.)</li>
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
     * skillars-deferred-166: {@link #findByTokenHash} with a {@code PESSIMISTIC_WRITE} row lock, held until
     * the calling transaction ends. {@code AuthService.refresh} and
     * {@code RefreshTokenRevocationService.revokeChainByTokenHash} both enter through this, which is what
     * serialises a concurrent refresh and logout on the same token: whichever takes the lock first finishes
     * its decision, so the other sees committed state (a rotation with its {@code replacedById} already
     * written, or a {@code revokedAt}) instead of racing it.
     *
     * <p><strong>Never hold this lock across a {@code REQUIRES_NEW} write to the same row.</strong> The
     * inner transaction would wait for a lock only the outer one can release, a cycle Postgres cannot see
     * (the outer transaction is blocked in Java, not in the database), which surfaces as
     * {@code lock_timeout} / SQLState 55P03 — a 409 instead of the intended 401. This is why
     * {@code AuthService.refresh} decides inside the locked block but performs every revoke after it has
     * committed. skillars-deferred-143 hit the same hazard from the other direction and documented it on
     * {@link #markUsedByTokenHash}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefreshToken r WHERE r.tokenHash = :hash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("hash") String hash);

    /**
     * skillars-deferred-166: by-id lookup with the same {@code PESSIMISTIC_WRITE} lock and the same
     * never-across-a-{@code REQUIRES_NEW}-write rule as {@link #findByTokenHashForUpdate}. Used to walk
     * {@code replacedById} from a rotated token to its successor — by primary key, which is why neither
     * new column carries an index.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefreshToken r WHERE r.id = :id")
    Optional<RefreshToken> findByIdForUpdate(@Param("id") Long id);

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
