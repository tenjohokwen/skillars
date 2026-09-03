package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.service.OutboxService;

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
 * <p>No {@code @Transactional} here: this is called from inside the listener's
 * {@code @Transactional(REQUIRES_NEW)}, so the outbox row and the drain marker commit atomically
 * with the listener's other work (strike issuance, cancellation history). Only the notification /
 * re-drive is deferred, not the decision.
 *
 * <p>Scope: only the {@code writeLedgerEntry("BOOKING_REFUND", …)} calls are routed here. Pack
 * session restores ({@code packSessionService.restoreSession}) stay inline — a different
 * idempotency concern, out of scope for AC2.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundOutboxSupport {

    public static final String AGGREGATE_TYPE = "CREDIT_WALLET_REFUND";

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    /** MUST be called inside the producing listener's transaction. */
    public void enqueueBookingRefund(Long parentId, BigDecimal amount, UUID bookingId, String description) {
        try {
            String json = objectMapper.writeValueAsString(
                new BookingRefundPayload(parentId, amount, bookingId, description));
            outboxService.enqueue(AGGREGATE_TYPE, json);
            outboxService.requestDrainAfterCommit();
        } catch (JsonProcessingException e) {
            // Four scalar fields; serialisation should never fail. If it does, fall back to a loud
            // log — the refund is not silently dropped, it just needs a human.
            log.error("[CREDIT_WALLET_REFUND_ENQUEUE_FAILED] booking={} parentId={} amount={} — "
                + "refund NOT enqueued, manual credit needed", bookingId, parentId, amount, e);
        }
    }

    /** Outbox payload for a re-drivable credit-wallet BOOKING_REFUND. */
    public record BookingRefundPayload(Long parentId, BigDecimal amount, UUID bookingId, String description) {
    }
}
