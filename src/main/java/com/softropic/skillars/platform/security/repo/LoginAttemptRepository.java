package com.softropic.skillars.platform.security.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {

    long countByIdentifierAndAttemptedAtAfter(String identifier, Instant windowStart);

    Optional<LoginAttempt> findFirstByIdentifierOrderByAttemptedAtAsc(String identifier);

    /**
     * skillars-deferred-120 code review (2026-09-17, Decision 2): this was previously a
     * <em>derived</em> delete method (no {@code @Query}), which Spring Data JPA executes as a
     * {@code SELECT} of every matching row into the persistence context followed by one
     * {@code DELETE} per entity — not the single bulk statement {@code AuthCleanupService}'s Javadoc
     * assumed. Under 24h of high-volume login attempts, that loads the entire purge set into memory
     * per run. Converted to a genuine bulk {@code @Query} DELETE, matching
     * {@link RefreshTokenRepository#deleteExpiredTokens()}'s already-correct shape.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM LoginAttempt a WHERE a.attemptedAt < :cutoff")
    void deleteByAttemptedAtBefore(@Param("cutoff") Instant cutoff);
}
