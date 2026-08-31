package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.contract.SkillDefinitionDto;
import com.softropic.skillars.platform.development.contract.SkillDefinitionMapper;
import com.softropic.skillars.platform.development.repo.SkillDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SkillDefinitionService {

    private final SkillDefinitionRepository skillDefinitionRepository;
    private final SkillDefinitionMapper skillDefinitionMapper;

    public List<SkillDefinitionDto> getActiveSkillDefinitions() {
        return skillDefinitionRepository.findAllByActiveTrueOrderByDisplayOrderAsc()
            .stream()
            .map(skillDefinitionMapper::toDto)
            .toList();
    }
}
