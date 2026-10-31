package com.softropic.skillars.platform.marketplace.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CoachSafeguardingRequestRepository extends JpaRepository<CoachSafeguardingRequest, UUID> {

    /** The staff queue: pending requests, oldest first. */
    @Query("SELECT r FROM CoachSafeguardingRequest r WHERE r.status = 'PENDING' ORDER BY r.requestedAt ASC")
    Page<CoachSafeguardingRequest> findPending(Pageable pageable);

    /** Requests still holding an upload stored before {@code cutoff}: the sweeper's batch. */
    @Query("SELECT r FROM CoachSafeguardingRequest r WHERE r.uploadKey IS NOT NULL AND r.uploadStoredAt < :cutoff "
           + "ORDER BY r.uploadStoredAt ASC")
    List<CoachSafeguardingRequest> findStaleUploads(@Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * GDPR erasure: deletes the coach's request and returns the upload key it held, in one statement, so the
     * caller can enqueue exactly that blob for deletion. Empty when there is no request; a null element when the
     * request held no upload. {@code @Transactional} (read-write) overrides the read-only default Spring Data
     * applies to query methods when no transaction is active.
     */
    @Transactional
    @Query(value = "DELETE FROM marketplace.coach_safeguarding_requests WHERE coach_id = :coachId "
                   + "RETURNING upload_key", nativeQuery = true)
    List<String> deleteByCoachIdReturningUploadKey(@Param("coachId") UUID coachId);
}
