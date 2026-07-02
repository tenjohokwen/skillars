package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.contract.AssessmentType;
import com.softropic.skillars.platform.session.contract.InvalidParamException;
import com.softropic.skillars.platform.development.contract.RadarAssessmentEntryResponse;
import com.softropic.skillars.platform.development.contract.RadarAssessmentListResponse;
import com.softropic.skillars.platform.development.contract.RadarAssessmentRequest;
import com.softropic.skillars.platform.development.contract.RadarEntrySubmittedEvent;
import com.softropic.skillars.platform.development.contract.SkillScoreItem;
import com.softropic.skillars.platform.development.repo.RadarAssessmentEntry;
import com.softropic.skillars.platform.development.repo.RadarAssessmentRepository;
import com.softropic.skillars.platform.development.repo.SkillDefinitionRepository;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.exception.FeatureGatedException;
import com.softropic.skillars.platform.marketplace.service.PlayerProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RadarAssessmentService {

    private static final Set<CoachSubscriptionTier> RADAR_ALLOWED_TIERS =
        EnumSet.of(CoachSubscriptionTier.INSTRUCTOR, CoachSubscriptionTier.ACADEMY);

    private final RadarAssessmentRepository radarRepository;
    private final CoachProfileService coachProfileService;
    private final PlayerProfileService playerProfileService;
    private final SkillDefinitionRepository skillDefinitionRepository;
    private final ApplicationEventPublisher publisher;
    private final CoachPlayerAuthorizationService coachPlayerAuthorizationService;

    @Transactional
    public void submitAssessment(Long coachUserId, Long playerId, RadarAssessmentRequest req) {
        coachPlayerAuthorizationService.requireCoachPlayerRelationship(coachUserId, playerId);
        UUID coachId = coachProfileService.getCoachIdByUserId(coachUserId);

        // Tier gate first — prevents Scout coaches from probing player existence via 404 vs 403 difference
        CoachSubscriptionTier tier = coachProfileService.getCoachSubscriptionTier(coachId);
        if (!RADAR_ALLOWED_TIERS.contains(tier)) {
            throw new FeatureGatedException("skills_radar", "INSTRUCTOR");
        }

        Long parentId = playerProfileService.getParentIdByPlayerId(playerId);

        List<SkillScoreItem> entryList = req.entries();
        Set<String> submittedCodes = entryList.stream()
            .map(SkillScoreItem::skillCode)
            .collect(Collectors.toSet());

        if (submittedCodes.size() != entryList.size()) {
            throw new InvalidParamException("entries[].skillCode");
        }

        long validCount = skillDefinitionRepository.findAllByCodeIn(submittedCodes).size();
        if (validCount != submittedCodes.size()) {
            throw new InvalidParamException("entries[].skillCode");
        }

        List<RadarAssessmentEntry> entries = req.entries().stream().map(item -> {
            RadarAssessmentEntry e = new RadarAssessmentEntry();
            e.setAssessmentGroupId(req.assessmentGroupId());
            e.setCoachId(coachId);
            e.setPlayerId(playerId);
            e.setSkillCode(item.skillCode());
            e.setScore(item.score().shortValue());
            e.setAssessmentDate(req.assessmentDate());
            e.setAssessmentType(req.assessmentType());
            e.setNotes(item.notes());
            e.setCreatedAt(Instant.now());
            return e;
        }).toList();

        radarRepository.saveAll(entries);
        log.info("Radar assessment saved: {} skills for player {} coach {}", entries.size(), playerId, coachId);

        publisher.publishEvent(new RadarEntrySubmittedEvent(playerId, parentId, submittedCodes));
    }

    @Transactional(readOnly = true)
    public RadarAssessmentListResponse getMyEntries(Long coachUserId, Long playerId) {
        coachPlayerAuthorizationService.requireCoachPlayerRelationship(coachUserId, playerId);
        UUID coachId = coachProfileService.getCoachIdByUserId(coachUserId);

        CoachSubscriptionTier tier = coachProfileService.getCoachSubscriptionTier(coachId);
        if (!RADAR_ALLOWED_TIERS.contains(tier)) {
            throw new FeatureGatedException("skills_radar", "INSTRUCTOR");
        }

        Long parentId = playerProfileService.getParentIdByPlayerId(playerId);

        List<RadarAssessmentEntry> mine = radarRepository
            .findByPlayerIdAndCoachIdOrderByAssessmentDateDesc(playerId, parentId, coachId);

        List<RadarAssessmentEntryResponse> entryResponses = mine.stream()
            .map(e -> new RadarAssessmentEntryResponse(
                e.getAssessmentGroupId(), e.getSkillCode(), e.getScore(),
                e.getAssessmentType(), e.getAssessmentDate(), e.getNotes(), e.getCreatedAt()))
            .toList();

        Map<String, Long> otherCounts = radarRepository
            .countDistinctOtherCoachesBySkill(playerId, parentId, coachId)
            .stream()
            .collect(Collectors.toMap(r -> (String) r[0], r -> ((Number) r[1]).longValue()));

        return new RadarAssessmentListResponse(entryResponses, otherCounts);
    }
}
