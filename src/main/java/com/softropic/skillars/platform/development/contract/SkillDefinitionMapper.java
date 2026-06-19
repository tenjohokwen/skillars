package com.softropic.skillars.platform.development.contract;

import com.softropic.skillars.platform.development.repo.SkillDefinition;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface SkillDefinitionMapper {

    SkillDefinitionDto toDto(SkillDefinition entity);
}
