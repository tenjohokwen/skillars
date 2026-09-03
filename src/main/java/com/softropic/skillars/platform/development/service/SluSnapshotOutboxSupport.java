package com.softropic.skillars.platform.development.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.development.repo.PlayerSkillStat;
import com.softropic.skillars.platform.outbox.service.OutboxService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * skillars-deferred-91 AC4: when {@code SnapshotPersistenceRetrier.writeAllWithRetry} exhausts its
 * retries, {@code player_slu_weekly_snapshot} under-reports for that {@code (session, iso-week)}
 * bucket and — before this — nothing reconciled it. This support bean enqueues the failed write
 * onto the generic outbox (skillars-deferred-91 AC1) so it is re-driven off the request path; the
 * re-drive goes through the {@code V119} idempotency marker, so it applies only the missing deltas
 * and cannot over-report.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SluSnapshotOutboxSupport {

    public static final String AGGREGATE_TYPE = "SLU_SNAPSHOT_WRITE";

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    /** The {@code @Recover} path calls this from the {@code sluRetryExecutor} thread (no ambient tx). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueueFailedSnapshotWrite(List<PlayerSkillStat> stats, short isoYear, short isoWeek) {
        if (stats == null || stats.isEmpty()) {
            return;
        }
        List<SluDeltaDto> deltas = stats.stream()
            .map(s -> new SluDeltaDto(s.getSessionId(), s.getPlayerId(), s.getSkillCode(), s.getSluValue()))
            .toList();
        try {
            String json = objectMapper.writeValueAsString(new SluSnapshotWritePayload(isoYear, isoWeek, deltas));
            outboxService.enqueue(AGGREGATE_TYPE, json);
            outboxService.requestDrainAfterCommit();
            log.warn("[SLU_SNAPSHOT_OUTBOX_ENQUEUED] session={} bucket={}-W{} deltas={} — re-drive scheduled",
                deltas.isEmpty() ? null : deltas.get(0).sessionId(), isoYear, isoWeek, deltas.size());
        } catch (JsonProcessingException e) {
            // Serialisation of four scalar fields should never fail; if it does the ERROR from the
            // caller's @Recover still stands and the loss is visible.
            log.error("[SLU_SNAPSHOT_OUTBOX_ENQUEUE_FAILED] could not serialise {} delta(s) for {}-W{}",
                deltas.size(), isoYear, isoWeek, e);
        }
    }

    /** Outbox payload for a re-drivable weekly-snapshot write. */
    public record SluSnapshotWritePayload(short isoYear, short isoWeek, List<SluDeltaDto> deltas) {
    }

    public record SluDeltaDto(UUID sessionId, Long playerId, String skillCode, BigDecimal sluValue) {
    }
}
