package com.softropic.skillars.platform.session.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public interface HomeworkCompletionRepository extends JpaRepository<HomeworkCompletion, UUID> {

    /**
     * Atomic idempotent completion insert — {@code uq_homework_completion} (assignment_id) arbitrates
     * concurrent callers; the loser is a silent no-op rather than a constraint violation.
     *
     * @return 1 if this call recorded the completion, 0 if it already existed
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "INSERT INTO session.homework_completions (id, assignment_id, player_id, completed_at) "
        + "VALUES (:id, :assignmentId, :playerId, now()) ON CONFLICT (assignment_id) DO NOTHING",
        nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("assignmentId") UUID assignmentId,
                       @Param("playerId") Long playerId);

    @Query("SELECT c.assignmentId FROM HomeworkCompletion c WHERE c.playerId = :playerId AND c.assignmentId IN :ids")
    Set<UUID> findAssignmentIdsByPlayerIdAndAssignmentIdIn(@Param("playerId") Long playerId, @Param("ids") Collection<UUID> ids);

    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM HomeworkCompletion h WHERE h.playerId = :playerId")
    int deleteAllByPlayerId(@Param("playerId") Long playerId);
}
