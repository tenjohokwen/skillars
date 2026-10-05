package com.softropic.skillars.platform.security.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String hash);

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
     * another column instead of letting it silently succeed.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE RefreshToken r SET r.used = true, r.version = r.version + 1 WHERE r.tokenHash = :tokenHash")
    void markUsedByTokenHash(@Param("tokenHash") String tokenHash);

    @Modifying
    @Transactional
    @Query("DELETE FROM RefreshToken r WHERE r.expiresAt < CURRENT_TIMESTAMP")
    void deleteExpiredTokens();
}
