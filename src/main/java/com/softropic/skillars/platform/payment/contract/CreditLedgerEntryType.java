package com.softropic.skillars.platform.payment.contract;

/**
 * The seven values currently written to {@code payment.parent_credit_ledger.type}, enforced by the
 * {@code chk_ledger_type} CHECK constraint ({@code V138__baseline_schema.sql}). Kept here, in
 * {@code payment.contract}, rather than beside the writing service because
 * {@code platform.admin.service.DisputeService} writes {@link #BOOKING_REFUND} too — a cross-module
 * read of this constants holder is the point.
 *
 * <p>Not an enum, mirroring {@link BookingPaymentStatus}'s own documented rationale: the column is a
 * {@code VARCHAR(32)} compared as a string in JPQL (including string-literal concatenation inside
 * {@code @Query} annotations, which only a compile-time-constant {@code String} — not an enum
 * constant — can do) and as a plain outbox-handler method parameter.
 *
 * <p><strong>Adding a value here means a migration widening {@code chk_ledger_type}</strong> (and
 * checking whether {@code chk_ledger_amount_sign}, the neighboring constraint on the same table,
 * also needs revisiting) — the same caution {@link BookingPaymentStatus} documents for
 * {@code chk_bp_status}.
 */
public final class CreditLedgerEntryType {

    /**
     * {@code CreditWalletService.debitForCashOut}: the net amount refunded to the card (requested
     * minus the fee), debited. Rows written before fix/payment-money-integrity hold the gross
     * requested amount instead (and carry no {@code reference_id}).
     */
    public static final String CASH_OUT_DEBIT = "CASH_OUT_DEBIT";

    /** {@code CreditWalletService.debitForCashOut}: the Stripe processing fee, debited. */
    public static final String STRIPE_FEE_DEBIT = "STRIPE_FEE_DEBIT";

    /**
     * {@code CashOutService}: a cash-out's Stripe refund failed — the debited net and fee restored,
     * one row each, {@code reference_id} = the cash-out id of the debits they reverse.
     */
    public static final String CASH_OUT_REVERSAL = "CASH_OUT_REVERSAL";

    /**
     * A refund credited back to a parent: {@code CreditWalletRefundOutboxHandler}'s re-drive and
     * {@code DisputeService}'s admin-resolution credits both write this.
     */
    public static final String BOOKING_REFUND = "BOOKING_REFUND";

    /**
     * feat/payment-forfeit-payouts-and-clawback: an {@code UPHOLD_COACH} dispute resolution taking a
     * {@link #BOOKING_REFUND} back from the payer's wallet — negative, capped at the wallet's balance at the
     * time (never below zero), {@code reference_id} = the booking id (V198: at most one per booking).
     */
    public static final String BOOKING_REFUND_REVERSAL = "BOOKING_REFUND_REVERSAL";

    /** A booking's session price, debited on settlement ({@code BookingPaymentPersistenceService}/{@code PaymentLifecycleService}). */
    public static final String BOOKING_DEDUCTION = "BOOKING_DEDUCTION";

    /** A failed payment's {@link #BOOKING_DEDUCTION} reversed, credit restored to the parent. */
    public static final String BOOKING_DEDUCTION_REVERSAL = "BOOKING_DEDUCTION_REVERSAL";

    private CreditLedgerEntryType() {
    }
}
