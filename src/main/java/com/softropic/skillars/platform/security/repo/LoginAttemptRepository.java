package com.softropic.skillars.platform.security.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {

    long countByIdentifierAndAttemptedAtAfter(String identifier, Instant windowStart);

    Optional<LoginAttempt> findFirstByIdentifierOrderByAttemptedAtAsc(String identifier);

    @Modifying
    @Transactional
    void deleteByAttemptedAtBefore(Instant cutoff);
}
