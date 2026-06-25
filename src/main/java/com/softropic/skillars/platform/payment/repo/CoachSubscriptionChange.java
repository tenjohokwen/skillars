package com.softropic.skillars.platform.payment.repo;

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
@Table(schema = "payment", name = "coach_subscription_changes")
@Getter
@Setter
@NoArgsConstructor
public class CoachSubscriptionChange {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "change_id")
    private UUID changeId;

    @Column(name = "coach_id", nullable = false)
    private UUID coachId;

    @Column(name = "from_tier", nullable = false)
    private String fromTier;

    @Column(name = "to_tier", nullable = false)
    private String toTier;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(nullable = false)
    private boolean applied = false;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "trigger_source", nullable = false)
    private String triggerSource = "SCHEDULED";
}
