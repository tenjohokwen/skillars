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

    @Modifying
    @Transactional
    @Query("DELETE FROM RefreshToken r WHERE r.expiresAt < CURRENT_TIMESTAMP")
    void deleteExpiredTokens();
}
