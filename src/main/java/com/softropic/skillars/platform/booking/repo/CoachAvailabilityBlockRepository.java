package com.softropic.skillars.platform.booking.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CoachAvailabilityBlockRepository extends JpaRepository<CoachAvailabilityBlock, UUID> {
    List<CoachAvailabilityBlock> findByCoachIdAndEndDatetimeAfterAndStartDatetimeBefore(
        UUID coachId, Instant weekStart, Instant weekEnd);
    Optional<CoachAvailabilityBlock> findByIdAndCoachId(UUID id, UUID coachId);

    // skillars-deferred-157 AC3: GDPR coach erasure — reason is the block's only coach-authored free
    // text; the rows (the anonymised coach's own calendar) are kept. A plain bulk UPDATE: the entity
    // has no @Version and no @Audited, so no version bump and no audit copy to worry about.
    @Modifying
    @Query("UPDATE CoachAvailabilityBlock b SET b.reason = NULL WHERE b.coachId = :coachId")
    void anonymizeReasonByCoachId(@Param("coachId") UUID coachId);
}
