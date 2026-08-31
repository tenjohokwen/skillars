package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.contract.SkillDefinitionDto;
import com.softropic.skillars.platform.development.contract.SkillDefinitionMapper;
import com.softropic.skillars.platform.development.repo.SkillDefinition;
import com.softropic.skillars.platform.development.repo.SkillDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillDefinitionServiceTest {

    @Mock private SkillDefinitionRepository skillDefinitionRepository;
    @Mock private SkillDefinitionMapper skillDefinitionMapper;

    private SkillDefinitionService service;

    @BeforeEach
    void setUp() {
        service = new SkillDefinitionService(skillDefinitionRepository, skillDefinitionMapper);
    }

    @Test
    void getActiveSkillDefinitions_delegatesToRepositoryAndMapsEachResult() {
        SkillDefinition pac = new SkillDefinition();
        SkillDefinition sho = new SkillDefinition();
        when(skillDefinitionRepository.findAllByActiveTrueOrderByDisplayOrderAsc())
            .thenReturn(List.of(pac, sho));

        SkillDefinitionDto pacDto = new SkillDefinitionDto("PAC", "Pace", (short) 1, null);
        SkillDefinitionDto shoDto = new SkillDefinitionDto("SHO", "Shooting", (short) 2, null);
        when(skillDefinitionMapper.toDto(pac)).thenReturn(pacDto);
        when(skillDefinitionMapper.toDto(sho)).thenReturn(shoDto);

        List<SkillDefinitionDto> result = service.getActiveSkillDefinitions();

        assertThat(result).containsExactly(pacDto, shoDto);
        verify(skillDefinitionRepository).findAllByActiveTrueOrderByDisplayOrderAsc();
        verify(skillDefinitionMapper).toDto(pac);
        verify(skillDefinitionMapper).toDto(sho);
    }
}
