package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.session.contract.SessionBlockData;
import com.softropic.skillars.platform.session.contract.SessionDrillRef;
import com.softropic.skillars.platform.session.repo.Drill;
import com.softropic.skillars.platform.session.repo.DrillRepository;
import com.softropic.skillars.platform.session.repo.Session;
import com.softropic.skillars.platform.session.repo.SessionRepository;
import com.softropic.skillars.platform.session.repo.SessionStatus;
import com.softropic.skillars.platform.session.service.DrillUsagePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * skillars-12-8: builds the {@code drills} timeline metadata ({@code [{drillId, name}]}) as a NAME SNAPSHOT
 * taken at completion time, so a later rename/archive of a drill never rewrites history.
 *
 * <p>Enrichment is best-effort by contract: every method returns an empty list on failure (WARN) rather
 * than throwing, because the caller writes a SESSION_COMPLETED row that feeds the coach-access clock, the
 * prep card and reports -- losing that row to a failed drill lookup would be far worse than missing drills.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class TimelineDrillSnapshotResolver {

    static final int MAX_DRILLS = 20;
    static final String KEY_DRILL_ID = "drillId";
    static final String KEY_NAME = "name";

    private final SessionRepository sessionRepository;
    private final DrillRepository drillRepository;

    /**
     * Drills planned for the booking's session, block order then {@link SessionDrillRef#order()},
     * de-duplicated (first wins), capped at {@link #MAX_DRILLS}.
     * Status gate: anything except CANCELLED. Deliberately NOT the SLU gate (SAVED/COMPLETED): a DRAFT plan
     * is flipped to COMPLETED by SessionPlanService.handleBookingCompleted on the same executor pool, so a
     * SAVED/COMPLETED gate would make the result depend on which listener commits first.
     */
    List<Map<String, Object>> forBooking(UUID bookingId) {
        try {
            Session session = sessionRepository.findByBookingId(bookingId).orElse(null);
            if (session == null) {
                log.debug("No session plan for booking {} -- no drills on timeline (QUICK mode or no builder use)", bookingId);
                return List.of();
            }
            if (SessionStatus.CANCELLED.equals(session.getStatus()) || session.getBlocks() == null) {
                log.debug("Session {} for booking {} is {} or has no blocks -- no drills on timeline",
                    session.getId(), bookingId, session.getStatus());
                return List.of();
            }
            return forBlocks(session.getBlocks(), session.getCoachId());
        } catch (Exception e) {
            log.warn("Could not resolve session drills for booking {} -- continuing without them", bookingId, e);
            return List.of();
        }
    }

    /**
     * Name snapshot for the drills in {@code blocks}, gathered exactly as {@link #forBooking} does (block order
     * then {@link SessionDrillRef#order()}, de-duplicated first-wins). skillars-deferred-163 AC2.4: the entry
     * point for a QUICK completion's wrap-up-time {@code plan_blocks_snapshot}, so it and the live-plan path
     * can never gather ids differently.
     */
    List<Map<String, Object>> forBlocks(List<SessionBlockData> blocks, UUID coachId) {
        try {
            if (blocks == null) return List.of();
            Set<UUID> ids = new LinkedHashSet<>();
            for (SessionBlockData block : blocks) {
                if (block == null || block.drills() == null) continue;
                block.drills().stream()
                    .filter(java.util.Objects::nonNull)
                    .sorted(java.util.Comparator.comparingInt(SessionDrillRef::order))
                    .forEach(ref -> ids.add(ref.drillId()));
            }
            return snapshot(new ArrayList<>(ids), coachId);
        } catch (Exception e) {
            log.warn("Could not resolve plan drills -- continuing without them", e);
            return List.of();
        }
    }

    /**
     * Name snapshot for the given drill ids (input order kept, null/duplicate ids dropped, capped).
     * skillars-deferred-162 AC4.3: only drills {@code coachId} may use are named. The {@code drills} key is
     * visible to every timeline viewer, so another coach's PRIVATE drill (from a plan saved before the
     * Session Builder validated ownership) is skipped, never snapshotted.
     */
    List<Map<String, Object>> snapshot(List<UUID> drillIds, UUID coachId) {
        try {
            if (drillIds == null || drillIds.isEmpty()) return List.of();
            List<UUID> ids = drillIds.stream().filter(java.util.Objects::nonNull).distinct().limit(MAX_DRILLS).toList();
            Map<UUID, Drill> byId = drillRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Drill::getId, Function.identity(), (a, b) -> a));
            List<Map<String, Object>> out = new ArrayList<>();
            for (UUID id : ids) {
                Drill d = byId.get(id);
                if (d == null) {
                    log.warn("Drill {} no longer resolves -- skipped in timeline snapshot", id);
                    continue;
                }
                if (!DrillUsagePolicy.isUsableBy(d, coachId)) {
                    log.warn("Drill {} is not usable by coach {} -- skipped in timeline snapshot", id, coachId);
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put(KEY_DRILL_ID, id.toString());
                entry.put(KEY_NAME, d.getName());
                out.add(entry);
            }
            return out;
        } catch (Exception e) {
            log.warn("Could not snapshot drill names -- continuing without them", e);
            return List.of();
        }
    }
}
