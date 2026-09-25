package com.softropic.skillars.platform.admin.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "admin", name = "gdpr_requests")
@Getter
@Setter
@NoArgsConstructor
public class GdprRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "request_type", nullable = false, length = 10)
    private String requestType;

    @Column(nullable = false, length = 15)
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "download_url", length = 2048)
    private String downloadUrl;

    @Column(name = "expires_at")
    private Instant expiresAt;

    // skillars-deferred-136 AC2: how many times the scheduled sweep has already re-driven this row —
    // bounds the retry cap (see GdprErasureRetryScheduler).
    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    // skillars-deferred-136 AC2: when this row most recently became FAILED — NOT createdAt (set once
    // at construction, see that field's own comment), so the grace window measures time since the
    // actual failure, not time since the row was first created. Set by markFailedStatusUpdate.
    @Column(name = "failed_at")
    private Instant failedAt;

    public GdprRequest(Long userId, String requestType, String status) {
        this.userId = userId;
        this.requestType = requestType;
        this.status = status;
    }
}
