package com.softropic.skillars.platform.video.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Stub entity — full schema added by Story 6.6.
 * Required here so VideoDeletionService can cancel pending approval requests
 * when a video is deleted. Cancellation is guarded by platform.video.approvalCancellation.enabled
 * config flag (default false until Story 6.6 deploys and flips it to true).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "video_approval_requests", schema = "main")
public class VideoApprovalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "video_id", nullable = false)
    private UUID videoId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;
}
