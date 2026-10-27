package com.softropic.skillars.platform.development.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.booking.repo.SessionCompletionData;
import com.softropic.skillars.platform.session.contract.SessionBlockData;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * skillars-deferred-163 AC2.4 (code review 2026-10-27, Decision 1): the ONE reader of
 * {@code session_completion_data.plan_blocks_snapshot}, shared by {@link TimelineEventListener} and
 * {@link SluCalculationService} so the timeline's drill list and SLU are always computed from the same
 * blocks for a QUICK completion.
 *
 * <p>Empty means "no snapshot applies — use the live plan": LIVE mode (the plan was frozen by the
 * completion itself), a pre-V170 row, or a snapshot that fails to parse. Both readers take that same
 * fallback, so even a corrupt snapshot cannot make them disagree. A present, empty list means the
 * QUICK wrap-up had no plan at all — nothing was planned, so nothing is shown or earned.
 */
@Slf4j
final class QuickPlanSnapshot {

    private static final TypeReference<List<SessionBlockData>> BLOCKS = new TypeReference<>() {};

    private QuickPlanSnapshot() {
    }

    static Optional<List<SessionBlockData>> blocksOf(SessionCompletionData scd, ObjectMapper objectMapper) {
        if (scd == null || !"QUICK".equals(scd.getCompletionMode()) || scd.getPlanBlocksSnapshot() == null) {
            return Optional.empty();
        }
        try {
            List<SessionBlockData> blocks = objectMapper.readValue(scd.getPlanBlocksSnapshot(), BLOCKS);
            return Optional.of(blocks == null ? List.of() : blocks);
        } catch (Exception e) {
            log.warn("Unreadable plan_blocks_snapshot for booking {} -- falling back to the live plan: {}",
                scd.getBookingId(), e.getMessage());
            return Optional.empty();
        }
    }
}
