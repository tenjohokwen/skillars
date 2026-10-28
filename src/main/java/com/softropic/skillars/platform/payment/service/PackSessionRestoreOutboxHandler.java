package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.contract.OutboxMessageHandler;
import com.softropic.skillars.platform.payment.service.PackRestoreOutboxSupport.PackRestorePayload;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;

/**
 * fix/payment-session-pack-integrity: re-drives a pack session restore enqueued by
 * {@link PackRestoreOutboxSupport}. Idempotent per booking — {@link PackSessionService#restoreSessionForBooking}
 * claims the V195 {@code pack_session_restores} marker in the same transaction as the increment, so a repeat
 * delivery is a no-op. A lock timeout throws, leaving the row for the next drain instead of losing the session.
 */
@Component
@RequiredArgsConstructor
public class PackSessionRestoreOutboxHandler implements OutboxMessageHandler {

    private final PackSessionService packSessionService;
    private final ObjectMapper objectMapper;

    @Override
    public String aggregateType() {
        return PackRestoreOutboxSupport.AGGREGATE_TYPE;
    }

    @Override
    public void handle(String payload) {
        final PackRestorePayload p;
        try {
            p = objectMapper.readValue(payload, PackRestorePayload.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        packSessionService.restoreSessionForBooking(p.purchaseId(), p.bookingId(), p.reason());
    }
}
