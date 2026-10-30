package com.softropic.skillars.platform.development.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
import com.softropic.skillars.platform.booking.repo.SessionCompletionDataRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.development.repo.PlayerSkillStat;
import com.softropic.skillars.platform.development.repo.SkillDefinitionRepository;
import com.softropic.skillars.platform.development.repo.SluRepository;
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
    private final SluPersistenceDispatcher sluPersistenceDispatcher;
    private final SessionCompletionDataRepository completionDataRepository;
    private final ObjectMapper objectMapper;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("taskExecutor")
    public void onBookingCompleted(BookingCompletedEvent event) {
        // fix/audit3-completion-dispute-integrity: a family confirm before the wrap-up has no attendance or plan
        // data yet — the coach's later wrap-up publishes the development side (DEVELOPMENT_ONLY).
        if (!event.isDevelopment()) {
            return;
        }
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

        // skillars-deferred-163 AC2.4 (code review 2026-10-27, Decision 1): a QUICK-mode booking's plan
        // stays editable until this delayed event fires, so SLU is computed from the blocks snapshotted at
        // wrap-up (drills AND per-block durations) — the same blocks TimelineEventListener names on the
        // timeline, via the same QuickPlanSnapshot reader, so the two cannot disagree. LIVE mode and
        // pre-V170 rows have no snapshot and keep using the live plan.
        List<SessionBlockData> blocks = QuickPlanSnapshot.blocksOf(
                completionDataRepository.findByBookingId(event.getBookingId()).orElse(null), objectMapper)
            .orElse(session.getBlocks());

        if (blocks == null || blocks.isEmpty()) {
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
        for (SessionBlockData block : blocks) {
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
        for (SessionBlockData block : blocks) {
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

        // Weekly snapshot bucket for sub-second dashboard queries (NFR-001)
        ZonedDateTime calcWeek = ZonedDateTime.ofInstant(now, ZoneOffset.UTC);
        short isoYear = (short) calcWeek.get(IsoFields.WEEK_BASED_YEAR);
        short isoWeek = (short) calcWeek.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);

        // AC3 (skillars-deferred-86): dispatch the SLU + snapshot persistence chain onto the
        // dedicated bounded sluRetryExecutor. The retry @Backoff sleeps happen there, never on this
        // @Async listener thread, which returns immediately. The retriers log their own outcome, so
        // this line records dispatch — not completion.
        sluPersistenceDispatcher.dispatchSluPersistence(stats, isoYear, isoWeek);
        log.info("SLU + weekly-snapshot persistence dispatched for session {} player {} ({} skill entries)",
            session.getId(), event.getPlayerId(), stats.size());
    }
}
