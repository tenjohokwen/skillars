package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.development.repo.PlayerSkillStat;
import com.softropic.skillars.platform.development.repo.SkillDefinitionRepository;
import com.softropic.skillars.platform.development.repo.SluRepository;
import com.softropic.skillars.platform.development.repo.SnapshotBatchWriter;
import com.softropic.skillars.platform.session.contract.SessionBlockData;
import com.softropic.skillars.platform.session.contract.SessionDrillRef;
import com.softropic.skillars.platform.session.repo.Drill;
import com.softropic.skillars.platform.session.repo.DrillRepository;
import com.softropic.skillars.platform.session.repo.Session;
import com.softropic.skillars.platform.session.repo.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class SluCalculationService {

    private final SessionRepository sessionRepository;
    private final DrillRepository drillRepository;
    private final SluRepository sluRepository;
    private final SkillDefinitionRepository skillDefinitionRepository;
    private final ConfigService configService;
    private final SnapshotBatchWriter snapshotBatchWriter;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onBookingCompleted(BookingCompletedEvent event) {
        // No-show: player did not attend — SLU is only earned for attended sessions (AC 8)
        if (!event.isPlayerAttended()) {
            log.debug("Player did not attend booking {} — SLU skipped", event.getBookingId());
            return;
        }

        Optional<Session> sessionOpt = sessionRepository.findByBookingId(event.getBookingId());
        if (sessionOpt.isEmpty()) {
            log.debug("No session plan for booking {} — SLU skipped (Quick Complete or no session builder usage)",
                event.getBookingId());
            return;
        }
        Session session = sessionOpt.get();

        // Only SAVED or COMPLETED sessions have meaningfully committed block content (AC 9)
        String sessionStatus = session.getStatus();
        if (!"SAVED".equals(sessionStatus) && !"COMPLETED".equals(sessionStatus)) {
            log.warn("Session {} for booking {} is in status {} — SLU skipped",
                session.getId(), event.getBookingId(), sessionStatus);
            return;
        }

        if (session.getBlocks() == null || session.getBlocks().isEmpty()) {
            log.warn("Session {} for booking {} has no blocks — SLU skipped",
                session.getId(), event.getBookingId());
            return;
        }

        // Idempotency guard — graceful no-op if SLU was already written (handles duplicate event delivery)
        if (!sluRepository.findBySessionId(session.getId()).isEmpty()) {
            log.debug("SLU already recorded for session {} (booking {}) — duplicate event skipped",
                session.getId(), event.getBookingId());
            return;
        }

        // Resolve scale factors per-invocation (never cache in field — ConfigService refreshes on its own schedule)
        BigDecimal intensityScale;
        BigDecimal pressureScale;
        BigDecimal matchRealismScale;
        try {
            intensityScale    = new BigDecimal(configService.getString("slu.intensity.scale"));
            pressureScale     = new BigDecimal(configService.getString("slu.pressure.scale"));
            matchRealismScale = new BigDecimal(configService.getString("slu.matchRealism.scale"));
        } catch (IllegalStateException | NumberFormatException | NullPointerException e) {
            log.error("SLU calculation aborted for booking {} — invalid or missing SLU config: {}",
                event.getBookingId(), e.getMessage());
            return;
        }

        // Collect unique drill IDs for batch loading (separate concern from per-block iteration below)
        Set<UUID> drillIds = new LinkedHashSet<>();
        boolean hasDrills = false;
        for (SessionBlockData block : session.getBlocks()) {
            if (block.drills() == null || block.drills().isEmpty()) continue;
            for (SessionDrillRef ref : block.drills()) {
                drillIds.add(ref.drillId());
                hasDrills = true;
            }
        }

        if (!hasDrills) {
            log.warn("Session {} has blocks but no drill assignments — SLU skipped", session.getId());
            return;
        }

        // Batch-load drills
        Map<UUID, Drill> drillMap = drillRepository.findAllById(drillIds)
            .stream().collect(Collectors.toMap(Drill::getId, d -> d));

        // Accumulate SLU per skill across all (block, drill) pairs.
        // The same drill in multiple blocks contributes independently per block — blocks are iterated
        // directly (not via a deduplicated drill map) so each appearance uses its own block's
        // allocated duration.
        // Load active skill codes once — guards against unknown codes hitting the FK constraint (P: skillCode pre-validation)
        Set<String> activeSkillCodes = skillDefinitionRepository.findAllByActiveTrueOrderByDisplayOrderAsc()
            .stream().map(sd -> sd.getCode()).collect(Collectors.toSet());

        Map<String, BigDecimal> totalSluPerSkill = new HashMap<>();
        for (SessionBlockData block : session.getBlocks()) {
            if (block.drills() == null || block.drills().isEmpty()) continue;
            if (block.durationMinutes() <= 0) continue;
            int allocatedPerDrill = Math.max(1, Math.round((float) block.durationMinutes() / block.drills().size()));
            for (SessionDrillRef ref : block.drills()) {
                Drill drill = drillMap.get(ref.drillId());
                if (drill == null || drill.getMetadata() == null) {
                    log.warn("Drill {} referenced in session {} not found or has no metadata — skipping",
                        ref.drillId(), session.getId());
                    continue;
                }

                Map<String, BigDecimal> drillSlu = SluFormula.calculate(
                    drill.getMetadata(), allocatedPerDrill,
                    intensityScale, pressureScale, matchRealismScale);

                drillSlu.forEach((skill, slu) ->
                    totalSluPerSkill.merge(skill, slu, BigDecimal::add));
            }
        }

        // Write one row per skill with positive SLU
        List<PlayerSkillStat> stats = new ArrayList<>();
        Instant now = Instant.now();
        for (Map.Entry<String, BigDecimal> skillEntry : totalSluPerSkill.entrySet()) {
            if (skillEntry.getValue().compareTo(BigDecimal.ZERO) <= 0) continue;
            if (!activeSkillCodes.contains(skillEntry.getKey())) {
                log.warn("Session {} contains unknown skill code '{}' — skipping to prevent FK violation",
                    session.getId(), skillEntry.getKey());
                continue;
            }
            PlayerSkillStat stat = new PlayerSkillStat();
            stat.setPlayerId(event.getPlayerId());
            stat.setSessionId(session.getId());
            stat.setCoachId(event.getCoachId());
            stat.setSkillCode(skillEntry.getKey());
            stat.setSluValue(skillEntry.getValue());
            stat.setCalculatedAt(now);
            stats.add(stat);
        }

        if (stats.isEmpty()) {
            log.info("Session {} produced no SLU contributions — no rows written",
                session.getId());
            return;
        }

        sluRepository.saveAll(stats);
        log.info("SLU recorded: {} skill entries for session {} player {}",
            stats.size(), session.getId(), event.getPlayerId());

        // Update weekly snapshot for sub-second dashboard queries (NFR-001)
        ZonedDateTime calcWeek = ZonedDateTime.ofInstant(now, ZoneOffset.UTC);
        short isoYear = (short) calcWeek.get(IsoFields.WEEK_BASED_YEAR);
        short isoWeek = (short) calcWeek.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        snapshotBatchWriter.writeAll(stats, isoYear, isoWeek);
        log.debug("Weekly snapshot updated: {} skill entries for player {} week {}/{}",
            stats.size(), event.getPlayerId(), isoYear, isoWeek);
    }
}
