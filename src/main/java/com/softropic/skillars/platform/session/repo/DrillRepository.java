package com.softropic.skillars.platform.session.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DrillRepository extends JpaRepository<Drill, UUID> {

    List<Drill> findByLibraryTypeAndStatus(String libraryType, String status);

    List<Drill> findByOwnerCoachIdAndStatus(UUID ownerCoachId, String status);

    interface CloneProjection {
        UUID getSourceId();
        UUID getCloneId();
    }

    @Query("SELECT d.sourceDrillId as sourceId, d.id as cloneId FROM Drill d WHERE d.sourceDrillId IN :sourceIds AND d.ownerCoachId = :coachId")
    List<CloneProjection> findClonesBySourceIdsAndCoach(@Param("sourceIds") List<UUID> sourceIds, @Param("coachId") UUID coachId);
}
