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
@Table(schema = "booking", name = "booking_reschedule_requests")
@Getter
@Setter
@NoArgsConstructor
public class BookingRescheduleRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "proposed_by", length = 10, nullable = false)
    private String proposedBy;

    @Column(name = "proposed_start_time", nullable = false)
    private Instant proposedStartTime;

    @Column(name = "proposed_end_time", nullable = false)
    private Instant proposedEndTime;

    @Column(name = "status", length = 10, nullable = false)
    private String status;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = "PENDING";
    }
}
