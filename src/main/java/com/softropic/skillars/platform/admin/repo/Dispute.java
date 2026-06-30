package com.softropic.skillars.platform.admin.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "admin", name = "disputes")
@Getter
@Setter
@NoArgsConstructor
public class Dispute {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "raised_by", nullable = false)
    private Long raisedBy;

    @Column(name = "raised_by_role", nullable = false, length = 10)
    private String raisedByRole;

    @Column(nullable = false, length = 30)
    private String reason;

    @Column(nullable = false, length = 2000)
    private String details;

    @Column(nullable = false, length = 15)
    private String status = "OPEN";

    @Column(length = 20)
    private String resolution;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    @Column(name = "credit_amount", precision = 10, scale = 2)
    private BigDecimal creditAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Version
    private Long version;
}
