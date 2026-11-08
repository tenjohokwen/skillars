package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * feat/coach-payouts-pack-and-credit: the same per-payer wallet lock, but BLOCKING (no NOWAIT) — for
     * booking settlement only ({@code CreditWalletService.lockAndGetBalance}). Settlement runs in
     * AFTER_COMMIT listeners with no retry of their own, and the lock can legitimately be held for the
     * length of another settlement's Stripe charge; a NOWAIT budget (~3s) running out there would strand
     * an accepted booking in PAYMENT_PENDING. Waiting is safe: every holder of this row (cash-out,
     * settlement) takes it first and holds no other lock a waiter could need.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM StripeCustomer c WHERE c.parentId = :parentId")
    Optional<StripeCustomer> findByIdForUpdateWaiting(@Param("parentId") Long parentId);

    /**
     * skillars-deferred-169 AC8: restore the saved card a declined pack purchase overwrote. A conditional column update, never
     * load-modify-save ({@code StripeCustomer} has no {@code @Version}, and the gateway saves the same entity), and only while
     * the column still holds the attempted card, so a card saved meanwhile is not clobbered. {@code purchasePack} is
     * non-transactional, hence {@code @Transactional} here.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("UPDATE StripeCustomer c SET c.stripePaymentMethodId = :previous "
        + "WHERE c.parentId = :parentId AND c.stripePaymentMethodId = :attempted")
    int revertPaymentMethod(@Param("parentId") Long parentId, @Param("previous") String previous,
                            @Param("attempted") String attempted);

    /**
     * skillars-deferred-174 AC4 (RS09c): a successful pack purchase re-applies its card only while the column holds that
     * card (the normal case: it was written before the charge) or the previous one (the resolver reverted it meanwhile).
     * Any other value is a card saved since, which is kept. Joins the caller's transaction, which enqueues the
     * subscription card sync with it.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = "UPDATE payment.stripe_customers SET stripe_payment_method_id = :attempted WHERE parent_id = :parentId "
        + "AND (stripe_payment_method_id = :attempted "
        + "OR stripe_payment_method_id IS NOT DISTINCT FROM CAST(:previous AS varchar))", nativeQuery = true)
    int reapplyPaymentMethod(@Param("parentId") Long parentId, @Param("previous") String previous,
                             @Param("attempted") String attempted);

    /**
     * skillars-deferred-174 AC2: the gateway's post-capture bookkeeping writes this one column. A load-and-save of the
     * entity read before the charge would write every column back, the card included, undoing a card saved meanwhile.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("UPDATE StripeCustomer c SET c.lastPaymentIntentId = :intentId WHERE c.parentId = :parentId")
    int updateLastPaymentIntentId(@Param("parentId") Long parentId, @Param("intentId") String intentId);

    /**
     * skillars-deferred-176 AC3 (RS09g): a pack purchase's card on an existing row, as a one-column UPDATE (no full-row save
     * of an entity read earlier). Called by {@code StripeCustomerCardWrites} after it locked the row and checked the payer.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("UPDATE StripeCustomer c SET c.stripePaymentMethodId = :paymentMethodId WHERE c.parentId = :parentId")
    int updatePaymentMethod(@Param("parentId") Long parentId, @Param("paymentMethodId") String paymentMethodId);

    /**
     * skillars-deferred-176 (round-1 V2): the erasure drops the saved card with an UPDATE, which takes the row lock even when
     * the column is already NULL (a load-modify-save of an unchanged entity issues no statement). Joins the erasure
     * transaction; no {@code clearAutomatically}, which would detach the entities that transaction is still writing.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE StripeCustomer c SET c.stripePaymentMethodId = NULL WHERE c.parentId = :parentId")
    int clearPaymentMethod(@Param("parentId") Long parentId);

    /**
     * skillars-deferred-176 AC1 (RS03f): serialises the outbox handlers that write a Stripe customer's email
     * ({@code STRIPE_CUSTOMER_EMAIL_SYNC}) or erase it ({@code STRIPE_CUSTOMER_ERASURE}). Outbox rows drain in parallel on
     * every pod, so without it a sync that read the user before an undo or an erasure committed could reach Stripe after
     * them. Taken inside the outbox claim transaction before the handler reads anything, and held until that transaction
     * ends, so whichever handler runs second reads what the first one's trigger committed. Blocking: a waiter holds its
     * outbox thread for at most the holder's Stripe call. The key must be non-blank ({@code hashtext(NULL)} locks nothing).
     */
    @Query(value = "SELECT pg_advisory_xact_lock(" + CUSTOMER_OUTBOX_LOCK_NAMESPACE + ", hashtext(:stripeCustomerId))",
        nativeQuery = true)
    void acquireCustomerOutboxLock(@Param("stripeCustomerId") String stripeCustomerId);

    /**
     * skillars-deferred-176 review round 5: the Stripe customer id only, for a handler that needs the
     * {@link #acquireCustomerOutboxLock} key before it reads the row. A scalar query puts no entity in the persistence
     * context, so a {@code findById} after the lock wait reads what committed meanwhile instead of a cached copy.
     */
    @Query("SELECT c.stripeCustomerId FROM StripeCustomer c WHERE c.parentId = :parentId")
    Optional<String> findStripeCustomerIdByParentId(@Param("parentId") Long parentId);

    /** {@code pg_advisory_xact_lock(int, int)} namespace for {@link #acquireCustomerOutboxLock} ("SCUL"). */
    int CUSTOMER_OUTBOX_LOCK_NAMESPACE = 0x5343554C;
}
