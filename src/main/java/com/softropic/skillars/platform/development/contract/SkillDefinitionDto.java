package com.softropic.skillars.platform.development.contract;

public record SkillDefinitionDto(
    String code,
    String displayName,
    Short displayOrder,
    String rubricCriteria
) {}
