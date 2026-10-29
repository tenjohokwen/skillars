package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.admin.contract.CoachSuspendedEvent;
import com.softropic.skillars.platform.outbox.service.OutboxService;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * fix/payment-pack-integrity: a coach suspension (admin suspension or child-safety hold) makes the payer whole
 * for the coach's session packs too — {@link PackSessionService#refundUnusedSessionsForSuspendedCoach} refunds
 * every unexpired pack's unused sessions to the payer's wallet at the pack's locked price and revokes the pack.
 *
 * <p>The suspension only enqueues it here (BEFORE_COMMIT, onto the durable outbox, atomic with the suspension);
 * {@link SuspendedCoachPackRefundOutboxHandler} runs the refund after commit. Doing it inline would put every
 * pack row lock on the suspension's critical path — contention on one pack would roll the suspension back, and
 * for a safety hold the takedown must never be undone that way. The handler is idempotent (a revoked pack is
 * skipped) and a no-op once the coach has been reinstated.
 */
@Component
@RequiredArgsConstructor
public class SuspendedCoachPackRefundOutbox {

    public static final String AGGREGATE_TYPE = "PACK_SUSPENSION_REFUND";

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onCoachSuspended(CoachSuspendedEvent event) {
        final String json;
        try {
            json = objectMapper.writeValueAsString(new PackSuspensionRefundPayload(event.getCoachId()));
        } catch (JsonProcessingException e) {
            // One scalar field; cannot realistically fail. Throw so the suspension rolls back rather than
            // committing without the refund of its packs.
            throw new IllegalStateException("[PACK_SUSPENSION_REFUND_ENQUEUE_FAILED] coach=" + event.getCoachId(), e);
        }
        outboxService.enqueue(AGGREGATE_TYPE, json);
        outboxService.requestDrainAfterCommit();
    }

    /** Outbox payload: the suspended coach whose packs are refunded. */
    public record PackSuspensionRefundPayload(UUID coachId) {
    }
}
