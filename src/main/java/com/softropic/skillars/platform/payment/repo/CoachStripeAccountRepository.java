package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CoachStripeAccountRepository extends JpaRepository<CoachStripeAccount, UUID> {

    Optional<CoachStripeAccount> findByStripeAccountId(String stripeAccountId);

    /**
     * fix/prod-readiness-gaps: one keyset page (by coach id, after {@code afterCoachId}) of accounts in
     * {@code status} that are not disconnected, for {@code CoachStripeAccountResyncScheduler}.
     */
    @Query("""
        SELECT a FROM CoachStripeAccount a
        WHERE a.onboardingStatus = :status
          AND (a.transfersCapability IS NULL OR a.transfersCapability <> :deauthorized)
          AND a.coachId > :afterCoachId
        ORDER BY a.coachId
        """)
    List<CoachStripeAccount> findConnectedByStatusAfter(@Param("status") String status,
                                                        @Param("deauthorized") String deauthorized,
                                                        @Param("afterCoachId") UUID afterCoachId,
                                                        Pageable page);
}
