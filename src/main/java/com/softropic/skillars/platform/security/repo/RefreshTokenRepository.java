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
     * ({@link RefreshToken}) that deliberately does <strong>not</strong> bump {@code version}.
     * The only column it writes, {@code used}, is a monotonic terminal flag: a row is created
     * {@code used = false} and only ever moves to {@code used = true} (rotation in
     * {@code AuthService.refresh}, {@code AuthService.logout}, and this revoke-all). Every
     * concurrent managed writer of these rows therefore also only ever sets {@code used = true},
     * so a stale managed {@code save()} racing this bulk update cannot resurrect a revoked token
     * to {@code used = false} — the races converge. Adding {@code version = version + 1} would
     * instead convert those benign convergent races into
     * {@link org.springframework.orm.ObjectOptimisticLockingFailureException}s, which
     * {@code AuthService.logout} does not handle. Listed with this reason in
     * {@code NativeModifyingVersionAuditTest}.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE RefreshToken r SET r.used = true WHERE r.userId = :userId")
    void markAllUsedByUserId(@Param("userId") Long userId);

    @Modifying
    @Transactional
    @Query("DELETE FROM RefreshToken r WHERE r.expiresAt < CURRENT_TIMESTAMP")
    void deleteExpiredTokens();
}
