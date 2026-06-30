package com.softropic.skillars.platform.session.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public interface HomeworkCompletionRepository extends JpaRepository<HomeworkCompletion, UUID> {

    boolean existsByAssignmentId(UUID assignmentId);

    @Query("SELECT c.assignmentId FROM HomeworkCompletion c WHERE c.playerId = :playerId AND c.assignmentId IN :ids")
    Set<UUID> findAssignmentIdsByPlayerIdAndAssignmentIdIn(@Param("playerId") Long playerId, @Param("ids") Collection<UUID> ids);

    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM HomeworkCompletion h WHERE h.playerId = :playerId")
    int deleteAllByPlayerId(@Param("playerId") Long playerId);
}
