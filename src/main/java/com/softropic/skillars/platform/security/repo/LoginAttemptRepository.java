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

    Optional<LoginAttempt> findFirstByIdentifierAndAttemptedAtAfterOrderByAttemptedAtAsc(String identifier,
                                                                                         Instant windowStart);

    /** V171: failed attempts for one account (any IP) since {@code windowStart} — admin read-out only. */
    long countBySubjectHashAndAttemptedAtAfter(String subjectHash, Instant windowStart);

    /**
     * V171: admin "unlock login" — removes every recorded failed attempt for one account across all
     * IPs, which is what lifts {@code AuthService.login}'s per-(email, ip) rate limit for that account.
     * A bulk statement on a non-versioned-in-practice, append-only table (rows are only ever inserted
     * and bulk-deleted, never updated), so no version bump is involved.
     */
    @Modifying
    @Query("DELETE FROM LoginAttempt a WHERE a.subjectHash = :subjectHash")
    int deleteBySubjectHash(@Param("subjectHash") String subjectHash);

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
