package com.softropic.skillars.platform.session.repo;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DrillRepository extends JpaRepository<Drill, UUID> {

    List<Drill> findByLibraryTypeAndStatus(String libraryType, String status);

    List<Drill> findByOwnerCoachIdAndStatus(UUID ownerCoachId, String status);

    // Story Deferred-75 AC5: mirrors CoachProfileRepository.findByIdForUpdate's NO_WAIT + retry pattern,
    // closing the DrillUploadService initiateUpload/deleteVideo TOCTOU races.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT d FROM Drill d WHERE d.id = :id")
    Optional<Drill> findByIdForUpdate(@Param("id") UUID id);

    interface CloneProjection {
        UUID getSourceId();
        UUID getCloneId();
    }

    @Query("SELECT d.sourceDrillId as sourceId, d.id as cloneId FROM Drill d WHERE d.sourceDrillId IN :sourceIds AND d.ownerCoachId = :coachId")
    List<CloneProjection> findClonesBySourceIdsAndCoach(@Param("sourceIds") List<UUID> sourceIds, @Param("coachId") UUID coachId);
}
