package com.softropic.skillars.platform.development.repo;

import com.softropic.skillars.platform.development.contract.ReportStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "development", name = "performance_reports")
@Getter @Setter @NoArgsConstructor
public class PerformanceReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "coach_id", nullable = false, updatable = false)
    private UUID coachId;

    @Column(name = "player_id", nullable = false, updatable = false)
    private Long playerId;  // BIGINT — NOT UUID

    @Column(name = "generated_at", nullable = false, updatable = false)
    private Instant generatedAt;

    // Null while status=PENDING_UPLOAD — the async post-commit handler sets this once the PDF is
    // actually in S3, only then flipping status to READY.
    @Column(name = "storage_key", length = 500)
    private String storageKey;

    @Column(name = "next_steps", nullable = false, updatable = false, length = 500)
    private String nextSteps;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private ReportStatus status = ReportStatus.PENDING_UPLOAD;
}
