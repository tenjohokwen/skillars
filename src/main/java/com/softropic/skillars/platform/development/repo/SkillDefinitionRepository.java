package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Set;

public interface SkillDefinitionRepository extends JpaRepository<SkillDefinition, String> {
    List<SkillDefinition> findAllByActiveTrueOrderByDisplayOrderAsc();
    List<SkillDefinition> findAllByCodeIn(Set<String> codes);
}
