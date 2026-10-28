package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.service.OutboxService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * fix/payment-session-pack-integrity: a pack session given back on a cancellation / no-show / pause goes
 * onto the durable outbox, the same way {@link RefundOutboxSupport} carries a wallet refund. It used to be
 * restored inline in {@code CancellationRefundService}'s AFTER_COMMIT {@code REQUIRES_NEW} listeners, where a
 * lock timeout or DB error lost the session for good with only a log line.
 *
 * <p>{@code MANDATORY}: called from {@link RefundEnqueueListener}'s BEFORE_COMMIT listeners, so the outbox row
 * commits atomically with the cancellation. {@link PackSessionRestoreOutboxHandler} applies it exactly once
 * per booking (the V195 {@code pack_session_restores} marker).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PackRestoreOutboxSupport {

    public static final String AGGREGATE_TYPE = "PACK_SESSION_RESTORE";

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueuePackRestore(UUID purchaseId, UUID bookingId, String reason) {
        final String json;
        try {
            json = objectMapper.writeValueAsString(new PackRestorePayload(purchaseId, bookingId, reason));
        } catch (JsonProcessingException e) {
            // Three scalar fields; cannot realistically fail. Throw rather than log: this runs BEFORE_COMMIT,
            // so the cancellation rolls back instead of committing without its restore.
            throw new IllegalStateException("[PACK_SESSION_RESTORE_ENQUEUE_FAILED] booking=" + bookingId
                + " pack=" + purchaseId, e);
        }
        outboxService.enqueue(AGGREGATE_TYPE, json);
        outboxService.requestDrainAfterCommit();
    }

    /** Outbox payload for a re-drivable pack session restore. */
    public record PackRestorePayload(UUID purchaseId, UUID bookingId, String reason) {
    }
}
