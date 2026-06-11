package com.softropic.skillars.platform.security.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface PhoneOtpTokenRepository extends JpaRepository<PhoneOtpToken, Long> {

    Optional<PhoneOtpToken> findFirstByUserIdAndUsedFalseOrderByExpiresAtDesc(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PhoneOtpToken t where t.userId = :userId and t.used = false")
    void deleteByUserIdAndUsedFalse(Long userId);
}
