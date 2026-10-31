package com.softropic.skillars.platform.security.repo;

import com.softropic.skillars.platform.security.contract.AgeVerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AgeVerificationRepository extends JpaRepository<AgeVerification, Long> {

    boolean existsByUserIdAndStatus(Long userId, AgeVerificationStatus status);

    Optional<AgeVerification> findByStripeSessionId(String stripeSessionId);

    Optional<AgeVerification> findFirstByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndCreatedAtAfter(Long userId, Instant after);

    long countByUserId(Long userId);

    List<AgeVerification> findAllByUserId(Long userId);

    /** GDPR erasure: the user's verification outcomes go with the account. */
    @Modifying
    @Query("delete from AgeVerification a where a.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}
