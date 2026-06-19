package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.contract.CoachRadarPreferenceRequest;
import com.softropic.skillars.platform.development.contract.CoachRadarPreferenceResponse;
import com.softropic.skillars.platform.development.contract.RadarDisplayResponse;
import com.softropic.skillars.platform.development.contract.SkillRadarEntry;
import com.softropic.skillars.platform.development.repo.CoachRadarPreference;
import com.softropic.skillars.platform.development.repo.CoachRadarPreferenceId;
import com.softropic.skillars.platform.development.repo.CoachRadarPreferenceRepository;
import com.softropic.skillars.platform.development.repo.PlayerRadarBaseline;
import com.softropic.skillars.platform.development.repo.PlayerRadarBaselineRepository;
import com.softropic.skillars.platform.development.repo.PlayerRadarComposite;
import com.softropic.skillars.platform.development.repo.PlayerRadarCompositeRepository;
import com.softropic.skillars.platform.development.repo.SkillDefinition;
import com.softropic.skillars.platform.development.repo.SkillDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RadarDisplayService {

    private final PlayerRadarCompositeRepository compositeRepository;
    private final PlayerRadarBaselineRepository baselineRepository;
    private final SkillDefinitionRepository skillDefinitionRepository;
    private final CoachRadarPreferenceRepository preferenceRepository;

    public RadarDisplayResponse getRadarDisplay(Long playerId) {
        List<SkillDefinition> allSkills =
            skillDefinitionRepository.findAllByActiveTrueOrderByDisplayOrderAsc();
        Map<String, PlayerRadarComposite> compositeMap = compositeRepository
            .findByIdPlayerId(playerId).stream()
            .collect(Collectors.toMap(c -> c.getId().getSkillCode(), c -> c));
        Map<String, PlayerRadarBaseline> baselineMap = baselineRepository
            .findByIdPlayerId(playerId).stream()
            .collect(Collectors.toMap(b -> b.getId().getSkillCode(), b -> b));

        List<SkillRadarEntry> skills = allSkills.stream().map(def -> {
            PlayerRadarComposite comp = compositeMap.get(def.getCode());
            PlayerRadarBaseline  base = baselineMap.get(def.getCode());
            return new SkillRadarEntry(
                def.getCode(),
                def.getDisplayName(),
                comp != null ? comp.getCompositeScore() : null,
                base != null ? base.getBaselineScore()  : null,
                comp != null ? comp.getEntryCount()     : null,
                comp != null ? comp.getLastUpdatedAt()  : null
            );
        }).toList();

        return new RadarDisplayResponse(skills);
    }

    public CoachRadarPreferenceResponse getPreferences(UUID coachId, Long playerId) {
        return preferenceRepository.findByIdCoachIdAndIdPlayerId(coachId, playerId)
            .map(p -> new CoachRadarPreferenceResponse(p.getSelectedSkills()))
            .orElse(new CoachRadarPreferenceResponse(List.of()));
    }

    @Transactional
    public CoachRadarPreferenceResponse savePreferences(
            UUID coachId, Long playerId, CoachRadarPreferenceRequest request) {
        CoachRadarPreference pref = preferenceRepository
            .findByIdCoachIdAndIdPlayerId(coachId, playerId)
            .orElseGet(() -> {
                CoachRadarPreference p = new CoachRadarPreference();
                CoachRadarPreferenceId pk = new CoachRadarPreferenceId();
                pk.setCoachId(coachId);
                pk.setPlayerId(playerId);
                p.setId(pk);
                return p;
            });
        List<String> deduped = request.selectedSkillCodes().stream().distinct().collect(Collectors.toList());
        pref.setSelectedSkills(deduped);
        pref.setUpdatedAt(Instant.now());
        return new CoachRadarPreferenceResponse(
            preferenceRepository.save(pref).getSelectedSkills());
    }
}
