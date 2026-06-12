package com.softropic.skillars.platform.marketplace.repo;

import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(schema = "marketplace", name = "coach_subscriptions")
@Getter
@Setter
@NoArgsConstructor
public class CoachSubscription {

    @Id
    @Column(name = "coach_id")
    private UUID coachId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CoachSubscriptionTier tier = CoachSubscriptionTier.SCOUT;

    @Column(name = "active_since", nullable = false)
    private OffsetDateTime activeSince = OffsetDateTime.now();
}
