package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.security.repo.RefreshToken;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

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
     * Hops the chain walk will follow before giving up. A chain grows by one per rotation, and only
     * rotations that happened after the holder's current cookie was minted are ahead of it, so in practice
     * the walk is one or two hops: the presented row plus whatever a concurrent refresh just created. Eight
     * leaves room for a client refreshing in a tight loop while logging out, and bounds the work a
     * replayed stale cookie can ask for. Hitting it is logged rather than retried: the rows beyond the bound
     * are still live, which is a real (if remote) revocation gap, and a silent one would be worse.
     */
    private static final int MAX_CHAIN_HOPS = 8;

    private final RefreshTokenRepository refreshTokenRepository;

    /**
     * Revokes the row for {@code tokenHash} and every successor reachable through {@code replacedById},
     * setting {@code used = true} and {@code revokedAt = now} on each. Returns the number of rows this call
     * actually revoked.
     *
     * <p>Idempotent and cheap to repeat: a row that already has a {@code revokedAt} is skipped, and the walk
     * continues past it in case a later successor is still live. This matters because
     * {@code JWTAuthorizationFilter}'s denial teardown calls {@code terminateSession} on <em>every</em>
     * request from a client that ignores {@code Set-Cookie} and keeps replaying a dead JWT; such a client
     * costs reads, not writes. (That was the point of skillars-deferred-144 AC6.6's {@code used = false}
     * predicate on the single-row primitive, and it is preserved here by the {@code revokedAt} guard.)
     *
     * <p>Writes go through the managed entities rather than a bulk {@code @Modifying} {@code UPDATE}, so
     * Hibernate bumps {@code @Version} itself and the rows stay outside
     * {@code NativeModifyingVersionAuditTest}'s remit. An unknown hash revokes nothing and is not an error:
     * a logout carrying a cookie whose row has already been swept by {@code deleteExpiredTokens} is
     * ordinary, and the caller clears the cookies either way.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeChainByTokenHash(final String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return 0;
        }
        final Instant now = Instant.now(ClockProvider.getClock());
        Optional<RefreshToken> cursor = refreshTokenRepository.findByTokenHashForUpdate(tokenHash);
        int revoked = 0;
        int hops = 0;
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
                return revoked;
            }
            if (++hops >= MAX_CHAIN_HOPS) {
                // Deliberately not a throw: the caller is ending a session and must still clear its
                // cookies. Logged at warn because any row beyond the bound is a token that outlived the
                // logout meant to kill it.
                log.warn("Refresh-token chain walk stopped at {} hops for user {}; revoked {} row(s), "
                        + "successor {} and anything beyond it remain live", MAX_CHAIN_HOPS,
                    row.getUserId(), revoked, successorId);
                return revoked;
            }
            cursor = refreshTokenRepository.findByIdForUpdate(successorId);
        }
        return revoked;
    }
}
