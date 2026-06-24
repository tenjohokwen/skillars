package com.softropic.skillars.platform.video.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoApprovalRequestRepository extends JpaRepository<VideoApprovalRequest, UUID> {

    // Story 6.5 — preserve these cancel queries; called by VideoDeletionService
    @Modifying
    @Transactional
    @Query("UPDATE VideoApprovalRequest var SET var.status = 'CANCELLED' WHERE var.videoId = :videoId AND var.status = 'PENDING'")
    void cancelAllPendingForVideo(@Param("videoId") UUID videoId);

    @Modifying
    @Transactional
    @Query("""
        UPDATE VideoApprovalRequest var SET var.status = 'CANCELLED'
        WHERE var.status = 'PENDING'
          AND var.videoId IN (SELECT v.id FROM Video v WHERE v.ownerId IN :ownerIds)
        """)
    void cancelAllPendingForOwners(@Param("ownerIds") List<String> ownerIds);

    // Story 6.6 — parent view: all PENDING approvals for this parent's players
    List<VideoApprovalRequest> findByParentIdAndStatus(Long parentId, String status);

    // Parent ownership check — throws empty if approval does not belong to this parent
    Optional<VideoApprovalRequest> findByIdAndParentId(UUID id, Long parentId);

    // Pessimistic write lock — used by approveVideo() to prevent concurrent double-trigger of Bunny encoding
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT var FROM VideoApprovalRequest var WHERE var.id = :id AND var.parentId = :parentId")
    Optional<VideoApprovalRequest> findByIdAndParentIdForUpdate(@Param("id") UUID id, @Param("parentId") Long parentId);

    // Minor gate idempotency check — prevent duplicate PENDING rows for the same video
    Optional<VideoApprovalRequest> findByVideoIdAndStatus(UUID videoId, String status);

    // Future auto-reject (NOT WIRED — no scheduler calls this; do not call directly)
    @Modifying
    @Transactional
    @Query("UPDATE VideoApprovalRequest var SET var.status = 'REJECTED', var.resolvedAt = current_timestamp WHERE var.status = 'PENDING' AND var.createdAt < :cutoff")
    int autoRejectExpired(@Param("cutoff") Instant cutoff);
}
