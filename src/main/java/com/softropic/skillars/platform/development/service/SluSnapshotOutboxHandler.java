package com.softropic.skillars.platform.development.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.development.repo.SnapshotBatchWriter;
import com.softropic.skillars.platform.development.service.SluSnapshotOutboxSupport.SluSnapshotWritePayload;
import com.softropic.skillars.platform.outbox.contract.OutboxMessageHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;

/**
 * skillars-deferred-91 AC4: re-drives a weekly SLU snapshot write that
 * {@code SnapshotPersistenceRetrier}'s retries exhausted. Idempotent by construction —
 * {@link SnapshotBatchWriter#writeAllDeltas} gates every additive delta on the {@code V119}
 * {@code player_slu_weekly_snapshot_applied} marker, so a re-drive after a partial first success
 * applies only the missing deltas and a re-drive after a full success is a complete no-op.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SluSnapshotOutboxHandler implements OutboxMessageHandler {

    private final SnapshotBatchWriter snapshotBatchWriter;
    private final ObjectMapper objectMapper;

    @Override
    public String aggregateType() {
        return SluSnapshotOutboxSupport.AGGREGATE_TYPE;
    }

    @Override
    public void handle(String payload) {
        final SluSnapshotWritePayload p;
        try {
            p = objectMapper.readValue(payload, SluSnapshotWritePayload.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // A malformed payload cannot be re-driven; rethrow so the row keeps attempts++/last_error
            // and the [OUTBOX_STUCK] alert eventually surfaces it rather than the row being dropped.
            throw new UncheckedIOException(e);
        }
        snapshotBatchWriter.writeAllDeltas(
            p.deltas().stream()
                .map(d -> new SnapshotBatchWriter.SluDelta(d.sessionId(), d.playerId(), d.skillCode(), d.sluValue()))
                .toList(),
            p.isoYear(), p.isoWeek());
        log.info("[SLU_SNAPSHOT_OUTBOX_REDRIVEN] bucket={}-W{} deltas={} re-applied through the V119 marker",
            p.isoYear(), p.isoWeek(), p.deltas().size());
    }
}
