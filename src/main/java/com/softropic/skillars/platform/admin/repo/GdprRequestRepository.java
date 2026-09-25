package com.softropic.skillars.platform.admin.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface GdprRequestRepository extends JpaRepository<GdprRequest, UUID> {

    boolean existsByUserIdAndRequestTypeAndStatusIn(Long userId, String requestType, List<String> statuses);

    Page<GdprRequest> findByRequestTypeAndStatus(String requestType, String status, Pageable pageable);

    Page<GdprRequest> findByRequestType(String requestType, Pageable pageable);

    Page<GdprRequest> findByStatus(String status, Pageable pageable);

    // skillars-deferred-136 AC2: candidates for GdprErasureService.retryFailedErasures's sweep — past
    // the grace window (failedAt before the caller's cutoff) and under the retry cap. requestType is
    // filtered explicitly (story review) rather than relying on the currently-true-but-undocumented
    // invariant that only ERASURE failures ever get a failedAt stamp (GdprExportService's own failure
    // path never calls markFailed) — that invariant spans two files and is easy to silently break later.
    Page<GdprRequest> findByRequestTypeAndStatusAndFailedAtBeforeAndRetryCountLessThan(
        String requestType, String status, Instant failedBefore, int retryCountLessThan, Pageable pageable);

    List<GdprRequest> findByUserIdAndRequestTypeAndStatus(Long userId, String requestType, String status);

    @Modifying
    @Query("DELETE FROM GdprRequest r WHERE r.userId = :userId AND r.createdAt < :cutoff")
    int deleteExpiredByUserId(@Param("userId") Long userId, @Param("cutoff") Instant cutoff);
}
