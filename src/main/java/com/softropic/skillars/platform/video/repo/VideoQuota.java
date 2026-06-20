package com.softropic.skillars.platform.video.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "video_quotas", schema = "main")
public class VideoQuota {

    @Id
    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "storage_used_bytes", nullable = false)
    private long storageUsedBytes;

    @Column(name = "bandwidth_used_bytes", nullable = false)
    private long bandwidthUsedBytes;

    @Column(name = "bandwidth_period_start", nullable = false)
    private Instant bandwidthPeriodStart;
}
