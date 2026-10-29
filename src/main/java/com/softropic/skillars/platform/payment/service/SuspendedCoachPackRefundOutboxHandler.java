package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.contract.OutboxMessageHandler;
import com.softropic.skillars.platform.payment.service.SuspendedCoachPackRefundOutbox.PackSuspensionRefundPayload;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;

/**
 * fix/payment-pack-integrity: runs the pack refund a coach suspension enqueued ({@link SuspendedCoachPackRefundOutbox}).
 * Idempotent — {@link PackSessionService#refundUnusedSessionsForSuspendedCoach} skips a revoked pack — so a
 * re-driven row is a no-op; a lock timeout throws and leaves the row for the next drain.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuspendedCoachPackRefundOutboxHandler implements OutboxMessageHandler {

    private final PackSessionService packSessionService;
    private final ObjectMapper objectMapper;

    @Override
    public String aggregateType() {
        return SuspendedCoachPackRefundOutbox.AGGREGATE_TYPE;
    }

    @Override
    public void handle(String payload) {
        final PackSuspensionRefundPayload p;
        try {
            p = objectMapper.readValue(payload, PackSuspensionRefundPayload.class);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        int refunded = packSessionService.refundUnusedSessionsForSuspendedCoach(p.coachId());
        log.info("[PACK_SUSPENSION_REFUND] coach={} — {} pack(s) refunded", p.coachId(), refunded);
    }
}
