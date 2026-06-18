package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SkillDefinitionRepository extends JpaRepository<SkillDefinition, String> {
    List<SkillDefinition> findAllByActiveTrueOrderByDisplayOrderAsc();
}
