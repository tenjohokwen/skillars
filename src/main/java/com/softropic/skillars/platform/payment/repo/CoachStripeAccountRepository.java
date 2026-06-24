package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CoachStripeAccountRepository extends JpaRepository<CoachStripeAccount, UUID> {

    Optional<CoachStripeAccount> findByStripeAccountId(String stripeAccountId);
}
