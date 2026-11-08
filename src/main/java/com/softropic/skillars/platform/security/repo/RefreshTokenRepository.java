package com.softropic.skillars.platform.security.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

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
     * skillars-deferred-166: "is this chain already revoked?" as a SCALAR, for
     * {@code RefreshTokenRevocationService}'s fast path.
     *
     * <p>Deliberately not {@code findByTokenHash(...).getRevokedAt() != null}: loading the entity would put
     * it in the persistence context, and a later {@code findByTokenHashForUpdate} in the same transaction
     * would then return that already-managed instance with its ORIGINAL field values. Hibernate takes the
     * row lock but does not overwrite a managed entity's state from the new query, so the write that
     * followed used a stale {@code @Version} and failed with
     * {@code ObjectOptimisticLockingFailureException} — surfacing as a 409 on logout and, worse, meaning
     * the lock protected nothing. A projection touches no entity state, so the locked read stays the first
     * and only load of that row.
     */
    boolean existsByTokenHashAndRevokedAtIsNotNull(String tokenHash);

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
     *
     * <p><strong>This lock WAITS, deliberately, and carries no timeout hint.</strong> The other
     * {@code *ForUpdate} queries here pair {@code lock.timeout = 0} with {@code PessimisticLockRetryer};
     * that is {@code NO_WAIT}, the only value Hibernate's {@code PostgreSQLDialect} special-cases — a
     * finite timeout is silently ignored on PostgreSQL, as {@code CoachProfileRepository},
     * {@code PlayerProfileRepository} and {@code VideoRepository} all record. (A 5 s hint was tried here
     * first and was inert: the emitted SQL was a plain {@code FOR UPDATE} and the javadoc promising a
     * bounded wait was simply false.)
     *
     * <p>{@code NO_WAIT} is wrong for these two call sites. Each is a whole transaction, not a read that a
     * savepoint can retry in place, and failing instantly would turn the ordinary logout-versus-refresh
     * overlap — the exact race ledger RS01 is about — into a logout that does not revoke. Waiting is also
     * what the rows were doing before this story, when the same contention queued behind
     * {@code markUsedByTokenHash}'s plain {@code UPDATE}; the holder is a refresh or a chain walk that
     * finishes in milliseconds, so the queue drains. The residual — an unbounded queue on two
     * {@code permitAll} endpoints — is unchanged by this story and filed in {@code deferred-work.md}.
     * A genuine deadlock (SQLState 40P01) still surfaces as {@code PessimisticLockingFailureException},
     * which {@code AuthService.refresh} answers with a 401 and {@code SecurityUtil.terminateSession}
     * retries.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefreshToken r WHERE r.tokenHash = :hash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("hash") String hash);

    /**
     * skillars-deferred-166: by-id lookup with the same {@code PESSIMISTIC_WRITE} lock, the same waiting
     * behaviour and the same never-across-a-{@code REQUIRES_NEW}-write rule as
     * {@link #findByTokenHashForUpdate}. Used to walk {@code replaced_by_id} from a rotated token to its
     * successor.
     *
     * <p>This lookup is by primary key and needs no index of its own. {@code replaced_by_id} is indexed
     * (V224, partial) for a different reason entirely: the FK's {@code ON DELETE SET NULL} action searches
     * that column once per row {@code deleteExpiredTokens} removes.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefreshToken r WHERE r.id = :id")
    Optional<RefreshToken> findByIdForUpdate(@Param("id") Long id);

    /**
     * Kills every refresh token of a user: the account-wide revocation behind a password change or reset, a
     * suspension, an email change, a theft response and a GDPR erasure. skillars-deferred-179: it now stamps
     * {@code revoked_at} as well as {@code used}, by delegating to {@link #markAllRevokedByUserId}, so a
     * token it killed reads as <em>revoked</em>. Before, it set {@code used} alone, and a row left
     * {@code used = true, revoked_at = NULL} reads as <em>rotated, successor unknown</em>: a replay of one
     * reached {@code AuthService.refresh}'s reuse branch and ran a second account-wide revocation, where a
     * deliberately ended session should get the plain {@code Revoked} 401 (the skillars-deferred-166
     * residual). Same {@code REQUIRES_NEW} propagation and {@code version} bump as before (both are on the
     * method it delegates to); rows already revoked are left as they are, so an earlier {@code revoked_at}
     * is never moved forward. The name is kept for its ten callers.
     *
     * <p>A Spring Data default method runs against the repository proxy, so the delegated call keeps its
     * transaction.
     */
    default void markAllUsedByUserId(Long userId) {
        markAllRevokedByUserId(userId, Instant.now(com.softropic.skillars.infrastructure.util.ClockProvider.getClock()));
    }

    /**
     * skillars-deferred-166: {@link #markAllUsedByUserId} that also stamps {@code revoked_at}, for
     * {@code RefreshTokenRevocationService.revokeAllForUser} — the fallback when a logout's chain is longer
     * than the walk will follow.
     *
     * <p>Since skillars-deferred-179 {@link #markAllUsedByUserId} delegates here, so every account-wide
     * revocation stamps both columns. The reasoning that made this method necessary in deferred-166 still
     * stands: {@code used} no longer means "revoked" on its own. Rows left {@code used = true} with
     * {@code revoked_at = NULL} read as <em>rotated, successor unknown</em>, so the next refresh presenting
     * one takes {@code AuthService}'s reuse branch — a second account-wide revocation plus a
     * {@code security_session_invalidated_at} bump and a "token reuse detected" 401 — where a deliberately
     * ended session should get the plain {@code Revoked} 401. Stamping both columns keeps the fallback
     * consistent with what the chain walk writes.
     *
     * <p>Only touches rows that are not already revoked, so a repeat call is zero rows rather than a
     * rewrite that would move an earlier {@code revoked_at} forward. Bumps {@code version} for the same
     * reason the bulk revocations always have (skillars-deferred-101 AC11), which is also what
     * {@code NativeModifyingVersionAuditTest} requires of a bulk write against a {@code @Version} table.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE RefreshToken r SET r.used = true, r.revokedAt = :revokedAt, r.version = r.version + 1 "
        + "WHERE r.userId = :userId AND r.revokedAt IS NULL")
    void markAllRevokedByUserId(@Param("userId") Long userId, @Param("revokedAt") Instant revokedAt);

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
     *
     * <p><strong>skillars-deferred-166: no production caller remains.</strong>
     * {@code SecurityUtil.terminateSession} — its last one — now calls
     * {@code RefreshTokenRevocationService.revokeChainByTokenHash}, because revoking one row could not end
     * a session whose token had just been rotated (ledger RS01). Kept because it is the single-row
     * primitive the durability ITs and {@code NativeModifyingVersionAuditTest} are written against, and
     * because "revoke exactly this row" is a reasonable thing for a future caller to want. If it is still
     * unused when someone next reads this, delete it rather than inventing a caller.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE RefreshToken r SET r.used = true, r.version = r.version + 1 "
        + "WHERE r.tokenHash = :tokenHash AND r.used = false")
    void markUsedByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * skillars-deferred-167 (AC4): does this rotation chain still have a token that could be traded in for
     * a session? Live means {@code used = false AND revoked_at IS NULL AND expires_at >= :now} — the same
     * three conditions {@code AuthService.decideAndRotate} applies before it rotates. The bound is
     * inclusive because that method treats {@code expiresAt == now} as still live
     * ({@code expiresAt.isBefore(now)} is false), and the two must not disagree about the same row.
     *
     * <p><strong>This does cap a web session at {@code REFRESH_TOKEN_TTL} after login, and three attempts
     * to avoid that were all withdrawn.</strong> The SPA has no caller for
     * {@code POST /api/auth/refresh}, so its chain is the single row written at login and keeps that
     * deadline. Recorded here because each attempt failed for a different reason and none should be
     * retried blind:
     * <ol>
     *   <li>Rotating from the SPA: its only trigger was the idle-warning dialog, which a polling tab
     *       never reaches, so it missed the very population it was for — and a failed rotation returns
     *       cookie-deletion headers, so it could sign the user out instead of extending them.</li>
     *   <li>Exempting rows with no {@code rotated_at}/{@code replaced_by_id} from the expiry test: dead on
     *       arrival, because a rotated chain's LIVE END is itself always such a row
     *       ({@code decideAndRotate} creates the successor with both null), so it exempted every chain.
     *       Keying on sibling rows fails too — the sweep deletes the expired predecessors that would be
     *       the evidence.</li>
     *   <li>Sliding {@code expires_at} for an active session: it broke logout. The {@code rtkn} cookie's
     *       max-age IS {@code REFRESH_TOKEN_TTL}, so past that point the browser has dropped it while the
     *       slid row stayed live — and {@code SecurityUtil.terminateSession} revokes only when that cookie
     *       is present, so Logout silently revoked nothing and this check could never answer false again.
     *       It re-opened the hole AC4 exists to close.</li>
     * </ol>
     * The general rule those add up to: <strong>a session may not outlive the cookie that identifies
     * it</strong>. Extending the row's life requires issuing a fresh {@code Set-Cookie}, which only
     * rotation does, so there is no server-only fix — removing the cap needs the SPA to rotate on a
     * trigger an active session actually reaches, which is its own piece of frontend work.
     *
     * <p>This is the whole of the per-session JWT check, and it is deliberately a scalar
     * {@code exists}/count rather than a fetch: {@code JWTAuthorizationFilter} calls it on a request that
     * has just authorized the user, and loading a managed {@code RefreshToken} there would put a row in
     * the persistence context that a later locked read in the same request would return WITHOUT refreshing
     * (the trap that cost skillars-deferred-166 a 409 on logout). Nothing in the filter needs the row.
     *
     * <p><strong>Never call this with a null {@code sessionId}.</strong> {@code session_id = NULL} matches
     * no row, so a null argument would report "no live token" for every pre-V225 session and reject it —
     * the exact opposite of AC5's deliberate fail-open. The caller checks for null first.
     *
     * <p>Scoped to {@code userId} as defence in depth, not because a bypass exists today: the claim can
     * only have come from a token this server signed off that user's own row, and AC3 deliberately removed
     * the one path that would have read a chain id from the request. But the claim is copied through
     * {@code renewLoginToken} unvalidated, so without this predicate the only thing standing between a
     * {@code potc} from one account and another account's live chain is that no mint path currently takes
     * the value from the request. That is a property of today's call sites, not an invariant, so the
     * question this answers is "is THIS user's chain live", never "is some chain live".
     *
     * <p>{@code :now} is a parameter rather than {@code CURRENT_TIMESTAMP} so the caller supplies
     * {@code Instant.now(ClockProvider.getClock())}, keeping this consistent with every other expiry
     * comparison in the auth code and testable without touching the database clock.
     */
    @Query("SELECT COUNT(r) > 0 FROM RefreshToken r WHERE r.sessionId = :sessionId "
        + "AND r.userId = :userId AND r.used = false AND r.revokedAt IS NULL AND r.expiresAt >= :now")
    boolean existsLiveBySessionId(@Param("sessionId") UUID sessionId, @Param("userId") Long userId,
                                  @Param("now") Instant now);

    /**
     * skillars-deferred-167 round 2: the chain id of the row this token hash names, but only if the row
     * belongs to {@code userId}.
     *
     * <p>Exists to REPAIR a lost claim, which is a real state rather than a hypothetical one. During a
     * rolling deploy (and after a rollback) an instance running the previous release serves requests for
     * JWTs minted by an upgraded one; its {@code extendTtlOfToken} rebuilds the claim map from
     * {@code toClaims} and re-puts only the claims it knows, so it drops {@code rtsid} and re-signs
     * without it. The new code then passes the missing value straight through, and that session would be
     * unenforceable for the rest of its life. Re-deriving on the DB-reauth branch is the only thing that
     * puts the claim back.
     *
     * <p>Scalar, so nothing is loaded into the persistence context on a request that has other work to do;
     * and scoped to {@code userId} because the value comes from a cookie on the request. Without that
     * predicate a caller pairing their own {@code rtkn} with a victim's {@code potc} could attach their
     * own live chain's id to the victim's token and keep it passing the liveness check. Returns
     * {@code null} for an unknown hash, a row owned by someone else, or a row whose own
     * {@code session_id} is still null (a chain predating V225).
     */
    @Query("SELECT r.sessionId FROM RefreshToken r WHERE r.tokenHash = :tokenHash AND r.userId = :userId")
    UUID findSessionIdByTokenHashAndUserId(@Param("tokenHash") String tokenHash,
                                           @Param("userId") Long userId);

    /**
     * Deletes every expired row, acquiring them in ascending {@code id} order.
     *
     * <p><strong>skillars-deferred-167 (AC9): the ordering is the point.</strong> This used to be a plain
     * JPQL bulk {@code DELETE}, which cannot carry an {@code ORDER BY}, so PostgreSQL locked rows in
     * whatever order its scan produced. Meanwhile both chain walks —
     * {@code AuthService.resolveGraceSuccessor} and
     * {@code RefreshTokenRevocationService.revokeChainByTokenHash} — take {@code PESSIMISTIC_WRITE} locks
     * along {@code replaced_by_id}, which is ascending {@code id} in practice (a successor is always
     * created after its predecessor, and {@code BaseEntity}'s {@code @Tsid} ids are monotonic within a
     * JVM). <strong>In practice, not by construction:</strong> a TSID's low bits are a per-JVM node and
     * counter component, so a predecessor and successor written by two different application instances
     * inside the same millisecond could invert. A rotation reads the predecessor under a row lock and
     * inserts the successor after that commit, so the gap is a database round trip and this is
     * unreachable rather than impossible. If it ever happens the walk locks descending while this sweep
     * locks ascending, the cycle returns, and the guard on
     * {@code AuthCleanupService.purgeExpiredRefreshTokens} logs a skipped run instead of a resolved one. An
     * expired chain being walked while the hourly sweep ran could therefore cycle: the walk holding a
     * predecessor and waiting for a successor the {@code DELETE} already held. PostgreSQL kills one side,
     * and before deferred-166 logout took a single-row lock that could wait but never cycle.
     *
     * <p>The {@code FOR UPDATE} subselect is what imposes the order: PostgreSQL does not pull a locking
     * subquery up into the outer statement, so it runs as its own plan with {@code LockRows} above
     * {@code Sort} and takes every row lock in {@code id} order before the delete proceeds. Native,
     * because JPQL has no way to express either the ordering or {@code FOR UPDATE} in a bulk delete.
     *
     * <p><strong>The cutoff is the DATABASE's clock, deliberately.</strong> An earlier version of this
     * method took an {@code Instant} parameter so the caller could pass
     * {@code Instant.now(ClockProvider.getClock())}, for consistency with every other expiry comparison in
     * the auth code. That was wrong for a sweep: the cutoff decides what gets DELETED, and on a
     * multi-instance deployment whichever node wins the {@code @SchedulerLock} would impose its own clock,
     * so a node running fast would delete rows the database still considers live. Since
     * skillars-deferred-167 AC4 that is no longer invisible — a deleted row makes
     * {@link #existsLiveBySessionId} false and refuses the owner's standing JWT on its next DB-reauth
     * cycle, so clock skew would read as a mass logout. {@code now()} is single-sourced and immune to it.
     * The liveness check keeps the application clock on purpose: it must agree with
     * {@code AuthService.decideAndRotate} about whether a row is live, and being a few seconds out there
     * costs one early or late refusal rather than a deletion.
     *
     * <p><strong>What this does NOT fix:</strong> {@link #markAllUsedByUserId(Long)} and
     * {@link #markAllRevokedByUserId(Long, Instant)} are still unordered bulk {@code UPDATE}s over one
     * user's rows, so they can still deadlock against this sweep. That shape predates deferred-167 and is
     * documented as a deadlock source on {@code AuthService.refresh}, which converts it to a 401.
     *
     * <p><strong>And one hole in the ordering itself, latent rather than reachable.</strong> V224's FK is
     * {@code ON DELETE SET NULL}, so deleting a row makes PostgreSQL update its PREDECESSOR's
     * {@code replaced_by_id} — a lower-id row that is not in this statement's locked, ordered set when it
     * is not itself expired. That would be a lower-id lock taken while holding higher-id locks, the very
     * cycle shape this ordering removes. It is unreachable in production: {@code expires_at} is only ever
     * written as {@code now + REFRESH_TOKEN_TTL}, at login and at each rotation, and a predecessor is
     * always created before its successor, so a predecessor always expires FIRST and is already in the
     * set. Only a test that rewrites {@code expires_at} directly can construct the inverse — which
     * {@code AuthResourceIT.expiredTokenSweep_deletesInIdOrder_andNullsThePredecessorsSuccessorLink} does
     * deliberately, to cover the referential action. Do not restate the ordering guarantee as absolute.
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM main.refresh_tokens WHERE id IN ("
        + "SELECT id FROM main.refresh_tokens WHERE expires_at < now() ORDER BY id FOR UPDATE)",
        nativeQuery = true)
    void deleteExpiredTokens();
}
