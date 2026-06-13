package com.softropic.skillars.platform.booking.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "booking", name = "session_packs_purchased")
@Getter
@Setter
@NoArgsConstructor
public class SessionPackPurchased {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "parent_id", nullable = false)
    private Long parentId;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "coach_id", nullable = false)
    private UUID coachId;

    @Column(name = "session_count", nullable = false)
    private int sessionCount;

    @Column(name = "credits_remaining", nullable = false)
    private int creditsRemaining;

    @Column(name = "purchased_at", nullable = false, updatable = false)
    private Instant purchasedAt;

    @Column(nullable = false, length = 20)
    private String status;

    @PrePersist
    void onCreate() {
        if (this.purchasedAt == null) this.purchasedAt = Instant.now();
        if (this.status == null) this.status = "ACTIVE";
    }
}
