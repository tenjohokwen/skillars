package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** fix/payment-payouts-disputes-webhooks (V193). See {@link ChargeDispute}. */
public interface ChargeDisputeRepository extends JpaRepository<ChargeDispute, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM ChargeDispute d WHERE d.stripeDisputeId = :id")
    Optional<ChargeDispute> findByIdForUpdate(@Param("id") String id);

    boolean existsByPaymentIntentIdAndStatus(String paymentIntentId, String status);

    /** The distinct dispute statuses recorded against any of {@code paymentIntentIds}. */
    @Query("SELECT DISTINCT d.status FROM ChargeDispute d WHERE d.paymentIntentId IN :paymentIntentIds")
    List<String> findStatusesByPaymentIntentIds(@Param("paymentIntentIds") List<String> paymentIntentIds);

    // --- What a PaymentIntent funded. Read-only lookups into booking_payments / session_pack_purchases,
    // --- kept here so the dispute webhook path owns its own queries.

    /** Bookings charged on the intent — one for a single booking, several for a batch charge. */
    @Query(value = "SELECT booking_id FROM payment.booking_payments WHERE stripe_payment_intent_id = :pi "
        + "ORDER BY booking_id", nativeQuery = true)
    List<java.util.UUID> findBookingIdsByPaymentIntent(@Param("pi") String paymentIntentId);

    /** Session packs bought with the intent. */
    @Query(value = "SELECT purchase_id FROM payment.session_pack_purchases WHERE stripe_payment_intent_id = :pi "
        + "ORDER BY purchase_id", nativeQuery = true)
    List<java.util.UUID> findPackPurchaseIdsByPaymentIntent(@Param("pi") String paymentIntentId);

    /** What the intent charged in total, from our own records (bookings' card portion + packs' amount). */
    @Query(value = """
        SELECT COALESCE((SELECT SUM(stripe_charged) FROM payment.booking_payments
                         WHERE stripe_payment_intent_id = :pi), 0)
             + COALESCE((SELECT SUM(amount_charged) FROM payment.session_pack_purchases
                         WHERE stripe_payment_intent_id = :pi), 0)
        """, nativeQuery = true)
    BigDecimal sumChargedByPaymentIntent(@Param("pi") String paymentIntentId);

    /** The intents that funded one booking: its own charge, or the charge of the pack it drew on. */
    @Query(value = """
        SELECT bp.stripe_payment_intent_id FROM payment.booking_payments bp
        WHERE bp.booking_id = :bookingId AND bp.stripe_payment_intent_id IS NOT NULL
        UNION
        SELECT sp.stripe_payment_intent_id FROM booking.bookings b
        JOIN payment.session_pack_purchases sp ON sp.purchase_id = b.session_pack_purchase_id
        WHERE b.id = :bookingId AND sp.stripe_payment_intent_id IS NOT NULL
        """, nativeQuery = true)
    List<String> findFundingPaymentIntents(@Param("bookingId") java.util.UUID bookingId);
}
