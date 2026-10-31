package com.softropic.skillars.platform.security.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface PhoneOtpTokenRepository extends JpaRepository<PhoneOtpToken, Long> {

    Optional<PhoneOtpToken> findFirstByUserIdAndUsedFalseOrderByExpiresAtDesc(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PhoneOtpToken t where t.userId = :userId and t.used = false")
    void deleteByUserIdAndUsedFalse(Long userId);

    /**
     * Counts one wrong guess and, on the {@code maxFailedAttempts}-th, marks the token used so it can
     * never be guessed again. REQUIRES_NEW and atomic ({@code failed_attempts + 1} in SQL): the caller
     * throws right after, which would roll an ambient write back together with the very failure it
     * records (same reasoning as {@code RefreshTokenRepository.markUsedByTokenHash}); the caller has
     * only read the row, so the inner transaction takes an uncontended lock.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE PhoneOtpToken t SET t.failedAttempts = t.failedAttempts + 1, t.version = t.version + 1, "
        + "t.used = CASE WHEN t.failedAttempts + 1 >= :maxFailedAttempts THEN true ELSE t.used END "
        + "WHERE t.id = :id AND t.used = false")
    int recordFailedGuess(@Param("id") Long id, @Param("maxFailedAttempts") int maxFailedAttempts);
}
