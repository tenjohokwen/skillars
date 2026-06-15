package com.softropic.skillars.platform.booking.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "booking", name = "bookings")
@Getter
@Setter
@NoArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "parent_id", nullable = false)
    private Long parentId;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "coach_id", nullable = false)
    private UUID coachId;

    @Column(name = "requested_start_time", nullable = false)
    private Instant requestedStartTime;

    @Column(name = "requested_end_time", nullable = false)
    private Instant requestedEndTime;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "canonical_timezone", nullable = false, length = 50)
    private String canonicalTimezone;

    @Column(length = 500)
    private String notes;

    @Version
    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "primary_reminder_sent_at")
    private Instant primaryReminderSentAt;

    @Column(name = "secondary_reminder_sent_at")
    private Instant secondaryReminderSentAt;

    @Column(name = "refund_eligibility", length = 10)
    private String refundEligibility;

    @Column(name = "refund_amount", precision = 10, scale = 2)
    private BigDecimal refundAmount;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = Instant.now();
        if (status == null) status = "REQUESTED";
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
