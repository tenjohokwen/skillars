package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

// IMMUTABLE: append-only — do NOT add delete() or update-path methods
public interface SluRepository extends JpaRepository<PlayerSkillStat, UUID> {

    List<PlayerSkillStat> findByPlayerIdOrderByCalculatedAtDesc(Long playerId);

    List<PlayerSkillStat> findByPlayerIdAndSkillCode(Long playerId, String skillCode);

    List<PlayerSkillStat> findBySessionId(UUID sessionId);
}
