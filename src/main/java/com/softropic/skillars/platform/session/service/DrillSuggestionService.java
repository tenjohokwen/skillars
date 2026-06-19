package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlag;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlagRepository;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.session.contract.DrillMetadata;
import com.softropic.skillars.platform.session.contract.DrillResponse;
import com.softropic.skillars.platform.session.contract.SessionBlockData;
import com.softropic.skillars.platform.session.contract.SessionDrillRef;
import com.softropic.skillars.platform.session.repo.Drill;
import com.softropic.skillars.platform.session.repo.DrillRepository;
import com.softropic.skillars.platform.session.repo.Session;
import com.softropic.skillars.platform.session.repo.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class DrillSuggestionService {

    private final SessionRepository sessionRepository;
    private final DrillRepository drillRepository;
    private final DrillLibraryService drillLibraryService;
    private final CoachProfileService coachProfileService;
    private final NeglectedSkillFlagRepository neglectedSkillFlagRepository;

    public List<DrillResponse> suggest(UUID sessionId, Long coachUserId, int limit) {
        drillLibraryService.checkSessionBuilderGate(coachUserId);
        UUID coachId = coachProfileService.getCoachIdByUserId(coachUserId);

        Session session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResourceNotFoundException("Session not found", "session"));

        if (!session.getCoachId().equals(coachId)) {
            throw new ResourceNotFoundException("Session not found", "session");
        }

        List<String> focus = session.getDevelopmentFocus();
        Long playerId = session.getPlayerId();

        Set<String> neglectedCodes = playerId != null
            ? neglectedSkillFlagRepository.findByPlayerIdAndResolvedAtIsNull(playerId)
                  .stream().map(NeglectedSkillFlag::getSkillCode).collect(Collectors.toSet())
            : Set.of();

        if (focus == null || focus.isEmpty()) {
            return fallback(limit, neglectedCodes);
        }

        List<Drill> candidates = allCandidates(coachId);
        Set<UUID> recentIds = playerId != null ? getRecentDrillIds(playerId) : Set.of();
        Set<UUID> alreadyUsed = extractUsedDrills(session.getBlocks());

        List<DrillResponse> result = candidates.stream()
            .filter(d -> !alreadyUsed.contains(d.getId()))
            .map(d -> new DrillScore(d, score(d.getMetadata(), focus, recentIds, d.getId(), neglectedCodes)))
            .sorted(Comparator.comparingDouble(DrillScore::score).reversed())
            .limit(limit)
            .map(ds -> {
                boolean addressesNeglected = !neglectedCodes.isEmpty()
                    && ds.drill().getMetadata() != null
                    && ds.drill().getMetadata().skillWeighting() != null
                    && ds.drill().getMetadata().skillWeighting().entrySet().stream()
                        .anyMatch(e -> e.getValue() != null && e.getValue() > 0
                            && neglectedCodes.contains(e.getKey()));
                return drillLibraryService.toResponse(
                    ds.drill(), false, List.of(), null, null, null, addressesNeglected);
            })
            .toList();

        return result;
    }

    private double score(DrillMetadata meta, List<String> focus, Set<UUID> recentIds, UUID drillId,
                         Set<String> neglectedCodes) {
        if (meta == null) return 0.0;
        double focusScore = computeFocusScore(meta, focus);
        double neglectedScore = 0.0;
        if (!neglectedCodes.isEmpty() && meta.skillWeighting() != null) {
            neglectedScore = meta.skillWeighting().entrySet().stream()
                .anyMatch(e -> e.getValue() != null && e.getValue() > 0
                    && neglectedCodes.contains(e.getKey())) ? 1.0 : 0.0;
        }
        double ageFitScore = 0.5;
        double recencyScore = recentIds.contains(drillId) ? 0.0 : 1.0;
        return focusScore * 0.40 + neglectedScore * 0.30 + ageFitScore * 0.20 + recencyScore * 0.10;
    }

    private double computeFocusScore(DrillMetadata meta, List<String> focus) {
        double total = 0.0;
        for (String f : focus) {
            total += switch (f) {
                case "technical"    -> ((meta.intensity() + meta.pressureLevel()) / 2.0 - 1.0) / 4.0;
                case "physical"     -> (meta.intensity() - 1.0) / 4.0;
                case "cognitive"    -> (meta.cognitiveLoad() - 1.0) / 4.0;
                case "matchRealism" -> (meta.matchRealism() - 1.0) / 4.0;
                case "weakFoot"     -> meta.weakFootBias() ? 1.0 : 0.0;
                case "set_pieces"   -> (meta.pressureLevel() - 1.0) / 4.0;
                case "possession"   -> ((meta.cognitiveLoad() + meta.matchRealism()) / 2.0 - 1.0) / 4.0;
                case "goalkeeping"  -> 0.5;
                default             -> 0.0;
            };
        }
        return Math.min(1.0, total / focus.size());
    }

    private List<DrillResponse> fallback(int limit, Set<String> neglectedCodes) {
        return drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")
            .stream()
            .sorted(Comparator.comparing(Drill::getCreatedAt))
            .limit(limit)
            .map(d -> {
                boolean addressesNeglected = !neglectedCodes.isEmpty()
                    && d.getMetadata() != null
                    && d.getMetadata().skillWeighting() != null
                    && d.getMetadata().skillWeighting().entrySet().stream()
                        .anyMatch(e -> e.getValue() != null && e.getValue() > 0
                            && neglectedCodes.contains(e.getKey()));
                return drillLibraryService.toResponse(d, false, List.of(), null, null, null, addressesNeglected);
            })
            .toList();
    }

    private List<Drill> allCandidates(UUID coachId) {
        List<Drill> all = new ArrayList<>(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE"));
        all.addAll(drillRepository.findByOwnerCoachIdAndStatus(coachId, "ACTIVE"));
        return all;
    }

    private Set<UUID> getRecentDrillIds(Long playerId) {
        return sessionRepository.findTop5ByPlayerIdOrderByCreatedAtDesc(playerId)
            .stream()
            .flatMap(s -> s.getBlocks() != null ? s.getBlocks().stream() : Stream.empty())
            .flatMap(b -> b.drills() != null ? b.drills().stream() : Stream.empty())
            .map(SessionDrillRef::drillId)
            .collect(Collectors.toSet());
    }

    private Set<UUID> extractUsedDrills(List<SessionBlockData> blocks) {
        if (blocks == null) return Set.of();
        return blocks.stream()
            .flatMap(b -> b.drills() != null ? b.drills().stream() : Stream.empty())
            .map(SessionDrillRef::drillId)
            .collect(Collectors.toSet());
    }

    private record DrillScore(Drill drill, double score) {}
}
