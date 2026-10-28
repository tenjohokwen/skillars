package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

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

    /**
     * fix/payment-money-integrity: the per-payer credit-wallet lock for cash-out. The ledger is
     * append-only and has no balance row to lock, and a cash-out cannot happen without this row
     * (it carries the PaymentIntent the refund goes to), so {@code CreditWalletService.debitForCashOut}
     * serializes the balance check and the debit on it. NOWAIT ({@code lock.timeout = 0}) — callers
     * wrap it in {@code PessimisticLockRetryer.withBoundedRetry}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT c FROM StripeCustomer c WHERE c.parentId = :parentId")
    Optional<StripeCustomer> findByIdForUpdate(@Param("parentId") Long parentId);
}
