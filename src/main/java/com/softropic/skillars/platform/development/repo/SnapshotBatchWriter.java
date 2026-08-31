package com.softropic.skillars.platform.development.repo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
        for (PlayerSkillStat stat : stats) {
            UUID sessionId = stat.getSessionId();
            if (sessionId == null) {
                // Not reachable from SluCalculationService.onBookingCompleted (it returns early when
                // there is no session), but the method is public and unit-tested directly. The marker
                // table's session_id is NOT NULL, so skip rather than fail the whole batch.
                log.warn("SnapshotBatchWriter.writeAll: stat for player {} skill {} has null sessionId — "
                        + "skipping snapshot delta (cannot key the idempotency marker)",
                    stat.getPlayerId(), stat.getSkillCode());
                continue;
            }
            snapshotRepository.upsertAddIdempotent(sessionId, stat.getPlayerId(), stat.getSkillCode(),
                isoYear, isoWeek, stat.getSluValue());
        }
    }
}
