package com.softropic.skillars.platform.messaging.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByCoachIdAndPlayerId(UUID coachId, Long playerId);

    @Query("SELECT c FROM Conversation c WHERE c.coachId = :coachId AND c.status != 'BLOCKED'")
    List<Conversation> findActiveByCoachId(@Param("coachId") UUID coachId);

    @Query("SELECT c FROM Conversation c WHERE c.parentId = :parentId AND c.status != 'BLOCKED'")
    List<Conversation> findActiveByParentId(@Param("parentId") Long parentId);

    @Query("SELECT c FROM Conversation c WHERE c.playerId = :playerId AND c.status != 'BLOCKED'")
    List<Conversation> findActiveByPlayerId(@Param("playerId") Long playerId);
}
