package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.contract.NeglectedSkillResponse;
import com.softropic.skillars.platform.development.contract.SluTargetRequest;
import com.softropic.skillars.platform.development.contract.SluTargetResponse;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlagRepository;
import com.softropic.skillars.platform.development.repo.PlayerSluTarget;
import com.softropic.skillars.platform.development.repo.SluTargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SluTargetService {

    private final SluTargetRepository sluTargetRepository;
    private final NeglectedSkillFlagRepository flagRepository;
    private final CoachPlayerAuthorizationService coachPlayerAuthorizationService;

    @Transactional(readOnly = true)
    public List<SluTargetResponse> getTargets(UUID coachId, Long playerId) {
        coachPlayerAuthorizationService.requireCoachPlayerRelationship(coachId, playerId);
        return sluTargetRepository.findByIdCoachIdAndIdPlayerId(coachId, playerId)
            .stream()
            .map(t -> new SluTargetResponse(t.getId().getSkillCode(), t.getWeeklyTargetSlu(), t.getUpdatedAt()))
            .toList();
    }

    @Transactional
    public List<SluTargetResponse> setTargets(UUID coachId, Long playerId, List<SluTargetRequest> requests) {
        coachPlayerAuthorizationService.requireCoachPlayerRelationship(coachId, playerId);
        for (SluTargetRequest req : requests) {
            if (req.weeklyTargetSlu() == null) {
                PlayerSluTarget.PlayerSluTargetId pk = new PlayerSluTarget.PlayerSluTargetId();
                pk.setCoachId(coachId);
                pk.setPlayerId(playerId);
                pk.setSkillCode(req.skillCode());
                sluTargetRepository.deleteById(pk);
            } else {
                sluTargetRepository.upsert(coachId, playerId, req.skillCode(),
                    req.weeklyTargetSlu(), Instant.now());
            }
        }
        return getTargets(coachId, playerId);
    }

    @Transactional(readOnly = true)
    public List<NeglectedSkillResponse> getNeglectedSkills(Long playerId) {
        return flagRepository.findByPlayerIdAndResolvedAtIsNull(playerId)
            .stream()
            .map(f -> new NeglectedSkillResponse(f.getSkillCode(), f.getDetectedAt()))
            .toList();
    }
}
