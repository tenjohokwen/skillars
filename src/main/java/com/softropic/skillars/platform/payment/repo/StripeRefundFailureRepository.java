package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StripeRefundFailureRepository extends JpaRepository<StripeRefundFailure, Long> {

    Optional<StripeRefundFailure> findByPaymentIntentId(String paymentIntentId);

    boolean existsByPaymentIntentId(String paymentIntentId);
}
