package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StripeCustomerRepository extends JpaRepository<StripeCustomer, Long> {

    /**
     * skillars-deferred-133 AC3: deliberately {@code List}, not {@code Optional} —
     * {@code payment.stripe_customers} ({@code V138__baseline_schema.sql}) has {@code PRIMARY KEY
     * (parent_id)} but no unique constraint or index on {@code stripe_customer_id}, so an
     * {@code Optional}/single-result finder risks {@code IncorrectResultSizeDataAccessException} if two
     * {@code parent_id} rows ever share a Stripe customer id — which, inside
     * {@code StripeWebhookService.handleEventAtomically}'s own {@code @Transactional}, would roll back
     * the idempotency record and put Stripe into a retry loop. Callers take the first result if present.
     */
    List<StripeCustomer> findByStripeCustomerId(String stripeCustomerId);
}
