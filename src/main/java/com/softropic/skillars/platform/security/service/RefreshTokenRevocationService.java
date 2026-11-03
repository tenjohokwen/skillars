package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.security.repo.RefreshToken;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * skillars-deferred-166 (ledger RS01): ends a session by revoking the presented refresh token
 * <em>and every token it was rotated into</em>.
 *
 * <p><strong>Why this exists.</strong> {@code SecurityUtil.terminateSession} used to revoke exactly one row,
 * via {@link RefreshTokenRepository#markUsedByTokenHash}, whose {@code WHERE} clause requires
 * {@code used = false} (skillars-deferred-144 AC6.6). A refresh that rotated the presented token a moment
 * before logout therefore left the row already {@code used}, the single-row update matched nothing, and the
 * successor that refresh had just minted stayed live for the rest of its 7-day TTL. Logging out did not end
 * the session. Waiting on an in-flight rotation did not help either: Postgres re-evaluates the predicate
 * after the lock is released and still matches zero rows.
 *
 * <p>Since V224 a rotated row points at its successor ({@code replacedById}), so the fix is to walk the
 * chain. The walk runs under {@code PESSIMISTIC_WRITE} row locks, which is what makes it correct against a
 * concurrent rotation rather than merely likely to win: {@code AuthService.refresh} takes the same lock on
 * the presented row before it decides anything, so the two serialise. Refresh first, and this walk finds the
 * committed {@code replacedById} and revokes the successor; logout first, and refresh sees
 * {@code revokedAt != null} and refuses without minting anything.
 *
 * <p><strong>Propagation is {@code REQUIRES_NEW}, deliberately</strong>, for the same reason
 * skillars-deferred-143 gave {@link RefreshTokenRepository#markUsedByTokenHash} the same treatment: callers
 * revoke and then throw. {@code AuthService.refresh}'s denial paths throw {@code BadCredentialsException} /
 * {@code LockedException} / {@code DisabledException}, and {@code JWTAuthorizationFilter}'s teardown runs
 * with no ambient transaction at all. An ambient-transaction write would be rolled back by the throw,
 * leaving the token alive after the very denial meant to kill it.
 *
 * <p><strong>Never call this while holding a lock on a row it will touch.</strong> It would wait for a lock
 * only its caller can release — a cycle Postgres cannot see, surfacing as {@code lock_timeout} / SQLState
 * 55P03 and a 409 instead of a 401. {@code AuthService.refresh} is structured around that rule: it decides
 * inside a locked block that only ever writes a rotation, and calls this afterwards, once that block has
 * committed and released its locks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenRevocationService {

    /**
     * skillars-deferred-167 (AC4): can the rotation chain identified by {@code refreshSessionId} still be
     * traded in for a session? True while it has at least one row that is unused, unrevoked and unexpired.
     *
     * <p>This is the whole of the per-session JWT check that {@code JWTAuthorizationFilter} runs on its
     * DB-reauth branch. False means every token in the chain is spent, revoked or expired — a logout, a
     * forced logout, a revoke-all, or simply a chain that ran out its 7-day TTL — so the access token minted
     * for that chain must stop working too.
     *
     * <p>Read-only and NOT {@code REQUIRES_NEW}, unlike everything else on this service: it writes nothing,
     * so it has no rollback to protect itself from, and it must be able to join the caller's transaction (or
     * run with none at all, which is the filter's case) rather than forcing a second one per request.
     *
     * <p>Rejects a {@code null} id by returning {@code true} — "cannot tell, so do not block". A null
     * {@code session_id} matches no row, so asking the database would answer "nothing live" for every
     * pre-V225 session and log those users out, which is the precise opposite of AC5's deliberate
     * fail-open. The filter also checks for null before calling, so this is defence in depth, not the
     * primary guard.
     */
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public boolean hasLiveTokenForSession(final UUID refreshSessionId) {
        if (refreshSessionId == null) {
            return true;
        }
        return refreshTokenRepository.existsLiveBySessionId(
            refreshSessionId, Instant.now(ClockProvider.getClock()));
    }

    /**
     * Links ({@code replaced_by_id} hops) the chain walk will follow before giving up, so it revokes the
     * presented row plus up to this many successors. Deliberately the same number as
     * {@code AuthService.MAX_GRACE_HOPS} and counted the same way, so the grace window can never hand over
     * a token that a logout of the same chain would have refused to reach. A chain grows by one per
     * rotation, and only rotations after the holder's cookie was minted are ahead of it, so in practice the
     * walk is one or two links. Exceeding it is reported to the caller, which revokes the whole account
     * instead — see the hop-bound comment below for why that cannot happen here.
     */
    private static final int MAX_CHAIN_HOPS = 8;

    private final RefreshTokenRepository refreshTokenRepository;

    /**
     * Revokes the row for {@code tokenHash} and every successor reachable through {@code replacedById},
     * setting {@code used = true} and {@code revokedAt = now} on each. Returns the number of rows this call
     * actually revoked.
     *
     * <p>Idempotent and cheap to repeat: an already-revoked presented row returns immediately from an
     * UNLOCKED read, without walking or locking anything. That fast path is load-bearing, not an
     * optimisation — {@code JWTAuthorizationFilter}'s denial teardown calls {@code terminateSession} on
     * <em>every</em> request from a client that ignores {@code Set-Cookie} and keeps replaying a dead JWT,
     * and without it each such request would take up to {@link #MAX_CHAIN_HOPS} row locks and hold them for
     * its whole transaction, serialising those requests against each other and against real traffic on the
     * same rows. A revoked row is safe to trust unlocked: whoever set {@code revokedAt} walked the rest of
     * the chain in the same transaction, and {@code revokedAt} is never cleared. (skillars-deferred-144
     * AC6.6 bounded the same client with its {@code used = false} predicate on the single-row primitive.)
     *
     * <p>Writes go through the managed entities rather than a bulk {@code @Modifying} {@code UPDATE}, so
     * Hibernate bumps {@code @Version} itself and the rows stay outside
     * {@code NativeModifyingVersionAuditTest}'s remit. An unknown hash revokes nothing and is not an error:
     * a logout carrying a cookie whose row has already been swept by {@code deleteExpiredTokens} is
     * ordinary, and the caller clears the cookies either way.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChainRevocation revokeChainByTokenHash(final String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return ChainRevocation.none();
        }
        // Unlocked pre-check: nothing to do, and nothing to lock, for a chain already revoked. See the
        // javadoc above for why this is required rather than merely cheaper — and
        // RefreshTokenRepository.existsByTokenHashAndRevokedAtIsNotNull for why it must be a projection
        // rather than a findByTokenHash: loading the entity here would make the locked read below return
        // that same managed instance with stale state, so the write would fail its version check and the
        // lock would protect nothing.
        if (refreshTokenRepository.existsByTokenHashAndRevokedAtIsNotNull(tokenHash)) {
            return ChainRevocation.none();
        }
        final Instant now = Instant.now(ClockProvider.getClock());
        Optional<RefreshToken> cursor = refreshTokenRepository.findByTokenHashForUpdate(tokenHash);
        final Long ownerId = cursor.map(RefreshToken::getUserId).orElse(null);
        int revoked = 0;
        int links = 0;
        while (cursor.isPresent()) {
            final RefreshToken row = cursor.get();
            if (row.getRevokedAt() == null) {
                row.setUsed(true);
                row.setRevokedAt(now);
                refreshTokenRepository.save(row);
                revoked++;
            }
            final Long successorId = row.getReplacedById();
            if (successorId == null) {
                return new ChainRevocation(revoked, null);
            }
            if (links == MAX_CHAIN_HOPS) {
                // Fail SAFE, not open — but the fallback CANNOT run here. Returning plainly would leave the
                // rows beyond the bound `used = false, revoked_at = null`, i.e. ordinary live tokens that
                // AuthService.refresh will keep rotating, so the ended session would continue indefinitely
                // (ledger RS01 again, reached through the bound). The account-wide revoke that fixes that is
                // markAllUsedByUserId, a REQUIRES_NEW bulk UPDATE over this user's rows — including the
                // MAX_CHAIN_HOPS rows this transaction currently holds locked. Calling it from here waits
                // for locks only this transaction can release, with PostgreSQL unable to see the cycle
                // because this side is blocked in Java: it hangs rather than erroring, which is exactly what
                // it did when first written this way. So the decision is reported to the caller and acted on
                // after this transaction has committed — the same decide-inside/act-outside rule
                // AuthService.refresh follows, for the same reason.
                log.warn("Refresh-token chain for user {} is longer than {} links (revoked {} row(s), "
                        + "successor {} still live); the caller must revoke the account's remaining tokens",
                    row.getUserId(), MAX_CHAIN_HOPS, revoked, successorId);
                return new ChainRevocation(revoked, row.getUserId());
            }
            links++;
            final Optional<RefreshToken> successor = refreshTokenRepository.findByIdForUpdate(successorId);
            // Defence in depth: replaced_by_id must name a token of the SAME account. Nothing enforces
            // that today — V224's FK checks existence only, and ships NOT VALID — so a bad manual data
            // fix, a partial restore or any future writer of the column could point a chain at another
            // user's row. Revoking it would be bad; AuthService.resolveGraceSuccessor making the mirror
            // mistake would be a cross-account session takeover, so both walks check. Logged at error
            // because a mismatch means the table is corrupt, not that a request was unusual.
            if (successor.isPresent() && !successor.get().getUserId().equals(ownerId)) {
                log.error("refresh_tokens.replaced_by_id {} points at user {} from user {}'s chain; "
                        + "stopping the walk", successorId, successor.get().getUserId(), ownerId);
                return new ChainRevocation(revoked, ownerId);
            }
            cursor = successor;
        }
        return new ChainRevocation(revoked, null);
    }

    /**
     * Revokes every refresh token the account has. The fallback for a chain longer than
     * {@link #MAX_CHAIN_HOPS}, called by {@code SecurityUtil.terminateSession} <em>after</em>
     * {@link #revokeChainByTokenHash}'s transaction has committed and released its row locks — never from
     * inside it, see that method's hop-bound comment.
     *
     * <p>Blunter than the walk: it ends the user's other sessions too. Accepted at this depth, because a
     * cookie more than {@link #MAX_CHAIN_HOPS} rotations behind the chain's live end is already
     * pathological, and losing the other sessions is strictly better than leaving the logged-out one
     * tradeable for the rest of its 7-day TTL.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllForUser(final Long userId) {
        // markAllRevokedByUserId, not markAllUsedByUserId: this is a revocation, so it must stamp
        // revoked_at. Rows left `used` with revoked_at NULL read as "rotated, successor unknown", and the
        // next refresh presenting one would take the reuse branch — a SECOND account-wide revocation plus a
        // session-epoch bump and a "token reuse detected" 401 — instead of the plain 401 a deliberately
        // ended session gets.
        refreshTokenRepository.markAllRevokedByUserId(userId, Instant.now(ClockProvider.getClock()));
    }

    /**
     * What a chain walk did, and whether the caller still has work to do.
     *
     * @param revoked                rows this call actually revoked
     * @param boundExceededForUserId the user whose chain outran {@link #MAX_CHAIN_HOPS}, or {@code null}
     *                               when the walk reached the end. Non-null means the caller must follow up
     *                               with {@link #revokeAllForUser} once this transaction has committed.
     */
    public record ChainRevocation(int revoked, Long boundExceededForUserId) {

        static ChainRevocation none() {
            return new ChainRevocation(0, null);
        }

        public boolean boundExceeded() {
            return boundExceededForUserId != null;
        }
    }
}
