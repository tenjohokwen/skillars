package com.softropic.skillars.platform.video.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Stub repository — full implementation provided by Story 6.6.
 * Cancellation queries are only called when platform.video.approvalCancellation.enabled = true.
 */
public interface VideoApprovalRequestRepository extends JpaRepository<VideoApprovalRequest, UUID> {

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
}
