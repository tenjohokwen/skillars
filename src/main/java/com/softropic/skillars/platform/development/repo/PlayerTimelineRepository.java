package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlayerTimelineRepository extends JpaRepository<PlayerTimelineEvent, UUID> {
    List<PlayerTimelineEvent> findByPlayerIdOrderByOccurredAtDesc(Long playerId);
    void deleteByPlayerId(Long playerId);   // GDPR erasure only
}
