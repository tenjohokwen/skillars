package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.contract.CreditLedgerEntryType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * skillars-deferred-91 AC2: a post-commit credit-wallet {@code BOOKING_REFUND} that
 * {@code CancellationRefundService}'s {@code @TransactionalEventListener(AFTER_COMMIT)} used to
 * write inline — where a failure in the listener's {@code REQUIRES_NEW} transaction rolled the
 * refund back with only a log line and no retry — now goes onto the durable outbox
 * (skillars-deferred-91 AC1) and is re-driven off the request path.
 *
 * <p>{@link #enqueueBookingRefund} is {@code @Transactional(MANDATORY)}: it joins the caller's
 * transaction (the {@code BEFORE_COMMIT} {@code RefundEnqueueListener}, inside the cancellation's own
 * transaction) and fails loudly if there is none, so the outbox row and the drain marker commit
 * atomically with the cancellation. Only the notification / re-drive is deferred, not the decision.
 *
 * <p>Scope: only the {@code writeLedgerEntry("BOOKING_REFUND", …)} calls are routed here. Pack
 * session restores have their own outbox type and per-booking marker ({@link PackRestoreOutboxSupport},
 * fix/payment-session-pack-integrity).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundOutboxSupport {

    public static final String AGGREGATE_TYPE = "CREDIT_WALLET_REFUND";

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    /**
     * Enqueue a booking refund for processing. <strong>Load-bearing contract:</strong> this method
     * MUST be invoked from within an active transaction, specifically from a
     * {@code @TransactionalEventListener(phase = BEFORE_COMMIT)} listener (see
     * {@link RefundEnqueueListener}). The {@code MANDATORY} propagation will throw
     * {@code IllegalTransactionStateException} if called outside a transaction context.
     *
     * <p>Atomicity: the outbox row commits in the same transaction as the booking CANCELLED write,
     * ensuring the refund is durably recorded or the entire event is rolled back to the caller.
     *
     * <p>skillars-deferred-101 AC4: this ensures a committed cancellation always enqueues a refund.
     */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void enqueueBookingRefund(Long parentId, BigDecimal amount, UUID bookingId, String description) {
        enqueueBookingRefund(parentId, amount, bookingId, description, null, null);
    }

    /**
     * fix/payment-dispute-credit-trace-and-lost-dispute: as {@link #enqueueBookingRefund(Long, BigDecimal,
     * UUID, String)}, naming the card charge the refunded money came from and its card part, for a refund
     * whose booking's own {@code booking_payments} row does not say it (V199
     * {@code parent_credit_ledger.source_payment_intent_id}). A later card dispute on that charge then finds
     * the credit and takes back what is unspent.
     */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void enqueueBookingRefund(Long parentId, BigDecimal amount, UUID bookingId, String description,
                                     String sourcePaymentIntentId, BigDecimal sourceCardAmount) {
        enqueue(new BookingRefundPayload(parentId, amount, bookingId, description, sourcePaymentIntentId,
            sourceCardAmount, null, false));
    }

    /**
     * fix/payment-wallet-dispute-integrity: a refund credited as SPEND-ONLY wallet credit — usable for
     * bookings, never cashed out (a parent's >24h cancel of a booking drawn on an expired pack; owner
     * decision 2026-10-29).
     */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void enqueueSpendOnlyBookingRefund(Long parentId, BigDecimal amount, UUID bookingId, String description) {
        enqueue(new BookingRefundPayload(parentId, amount, bookingId, description, null, null, null, true));
    }

    /**
     * fix/payment-wallet-dispute-integrity: a batch booking's charged share credited back because the booking
     * was settled elsewhere — written as {@code BATCH_SHARE_REFUND}, not {@code BOOKING_REFUND}, so it never
     * occupies the booking's one refund slot (a later real cancellation refund of that booking used to be
     * silently skipped, and an {@code UPHOLD_COACH} clawed back the batch share instead).
     */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void enqueueBatchShareRefund(Long parentId, BigDecimal amount, UUID bookingId, String description,
                                        String sourcePaymentIntentId, BigDecimal sourceCardAmount) {
        enqueue(new BookingRefundPayload(parentId, amount, bookingId, description, sourcePaymentIntentId,
            sourceCardAmount, CreditLedgerEntryType.BATCH_SHARE_REFUND, false));
    }

    private void enqueue(BookingRefundPayload payload) {
        Long parentId = payload.parentId();
        BigDecimal amount = payload.amount();
        UUID bookingId = payload.bookingId();
        try {
            String json = objectMapper.writeValueAsString(payload);
            outboxService.enqueue(AGGREGATE_TYPE, json);
            outboxService.requestDrainAfterCommit();
        } catch (JsonProcessingException e) {
            // Four scalar fields; serialisation should never fail. If it does, fall back to a loud
            // log — the refund is not silently dropped, it just needs a human.
            log.error("[CREDIT_WALLET_REFUND_ENQUEUE_FAILED] booking={} parentId={} amount={} — "
                + "refund NOT enqueued, manual credit needed", bookingId, parentId, amount, e);
        }
    }

    /**
     * Outbox payload for a re-drivable credit-wallet BOOKING_REFUND. {@code sourcePaymentIntentId} /
     * {@code sourceCardAmount} are null on payloads enqueued before V199 and for refunds traced through
     * {@code booking_payments}. fix/payment-wallet-dispute-integrity (V208): {@code entryType} is the ledger
     * type to write (null = {@code BOOKING_REFUND}, as on every earlier payload) and {@code spendOnly} credits
     * the whole amount to the spend-only pool (false on earlier payloads).
     */
    public record BookingRefundPayload(Long parentId, BigDecimal amount, UUID bookingId, String description,
                                       String sourcePaymentIntentId, BigDecimal sourceCardAmount,
                                       String entryType, boolean spendOnly) {
        public BookingRefundPayload(Long parentId, BigDecimal amount, UUID bookingId, String description) {
            this(parentId, amount, bookingId, description, null, null, null, false);
        }

        public BookingRefundPayload(Long parentId, BigDecimal amount, UUID bookingId, String description,
                                    String sourcePaymentIntentId, BigDecimal sourceCardAmount) {
            this(parentId, amount, bookingId, description, sourcePaymentIntentId, sourceCardAmount, null, false);
        }

        /** The ledger type this payload writes (derived, not serialised). */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public String ledgerType() {
            return entryType == null ? CreditLedgerEntryType.BOOKING_REFUND : entryType;
        }
    }
}
