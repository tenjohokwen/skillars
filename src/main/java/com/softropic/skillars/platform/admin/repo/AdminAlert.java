package com.softropic.skillars.platform.admin.repo;

import com.softropic.skillars.platform.admin.contract.AdminAlertReferenceType;
import com.softropic.skillars.platform.admin.contract.AdminAlertStatus;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(schema = "admin", name = "admin_alerts")
@Getter
@Setter
@NoArgsConstructor
public class AdminAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "alert_id")
    private UUID alertId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private AdminAlertType type;

    @Column(name = "reference_id", nullable = false, length = 36)
    private String referenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, length = 15)
    private AdminAlertReferenceType referenceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private AdminAlertStatus status = AdminAlertStatus.OPEN;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private Long resolvedBy;
}
