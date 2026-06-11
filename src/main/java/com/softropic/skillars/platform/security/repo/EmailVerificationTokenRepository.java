package com.softropic.skillars.platform.security.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {

    Optional<EmailVerificationToken> findByToken(UUID token);

    @Modifying(clearAutomatically = true)
    @Query("delete from EmailVerificationToken t where t.userId = :userId and t.used = false")
    void deleteByUserIdAndUsedFalse(Long userId);
}
