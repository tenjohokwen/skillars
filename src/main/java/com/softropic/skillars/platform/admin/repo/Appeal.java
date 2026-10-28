package com.softropic.skillars.platform.admin.repo;

import com.softropic.skillars.platform.admin.contract.AppealStatus;
import com.softropic.skillars.platform.admin.contract.AppealSubjectType;
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

/**
 * FR-ADM-004 appeal against one enforcement action (V172). Deliberately no {@code @Version}: the only
 * state transition (OPEN → UPHELD/OVERTURNED) is an atomic conditional UPDATE
 * ({@link AppealRepository#decideIfOpen}), so a concurrent second decision loses deterministically.
 */
@Entity
@Table(schema = "admin", name = "appeals")
@Getter
@Setter
@NoArgsConstructor
public class Appeal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "appellant_user_id", nullable = false, updatable = false)
    private Long appellantUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 30, updatable = false)
    private AppealSubjectType subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    /** When the appealed enforcement happened — pins WHICH enforcement and anchors the 14-day window. */
    @Column(name = "enforced_at", nullable = false, updatable = false)
    private Instant enforcedAt;

    @Column(nullable = false, length = 2000, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private AppealStatus status = AppealStatus.OPEN;

    @Column(name = "decision_note", length = 1000)
    private String decisionNote;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "reversal_applied")
    private Boolean reversalApplied;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
