package com.softropic.skillars.platform.development.repo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotBatchWriter {

    private final SluWeeklySnapshotRepository snapshotRepository;

    /**
     * Applies each stat's SLU delta to the weekly snapshot via
     * {@link SluWeeklySnapshotRepository#upsertAddIdempotent} — a single CTE statement that gates the
     * additive upsert on a write-once marker per {@code (session_id, player_id, skill_code, iso_year,
     * iso_week)}. A whole-method retry after an ambiguous-commit {@code TransactionSystemException}
     * (skillars-deferred-86 AC1) therefore re-applies nothing: the marker rows from the first,
     * actually-committed attempt make every delta a no-op.
     */
    @Transactional
    public void writeAll(List<PlayerSkillStat> stats, short isoYear, short isoWeek) {
        writeAllDeltas(stats.stream()
            .map(s -> new SluDelta(s.getSessionId(), s.getPlayerId(), s.getSkillCode(), s.getSluValue()))
            .toList(), isoYear, isoWeek);
    }

    /**
     * skillars-deferred-91 AC4: the detached-value form of {@link #writeAll}, so a failed weekly
     * snapshot write can be re-driven from a durable outbox row (which carries only these four
     * fields, not the full {@code PlayerSkillStat} entity). Same {@code (session, weekly-bucket)}
     * marker gate — a re-drive after a partial first success applies only the missing deltas and
     * cannot over-report (skillars-deferred-89 AC2 / the {@code V119} marker).
     */
    @Transactional
    public void writeAllDeltas(List<SluDelta> deltas, short isoYear, short isoWeek) {
        for (SluDelta d : deltas) {
            if (d.sessionId() == null) {
                // The marker table's session_id is NOT NULL, so skip rather than fail the whole batch.
                log.warn("SnapshotBatchWriter: delta for player {} skill {} has null sessionId — "
                        + "skipping snapshot delta (cannot key the idempotency marker)",
                    d.playerId(), d.skillCode());
                continue;
            }
            snapshotRepository.upsertAddIdempotent(d.sessionId(), d.playerId(), d.skillCode(),
                isoYear, isoWeek, d.sluValue());
        }
    }

    /** One additive SLU delta for the weekly snapshot, keyed for the {@code V119} idempotency marker. */
    public record SluDelta(UUID sessionId, Long playerId, String skillCode, BigDecimal sluValue) {
    }
}
