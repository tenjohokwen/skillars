package com.softropic.skillars.platform.session.repo;

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
@Table(schema = "session", name = "homework_assignments")
@Getter
@Setter
@NoArgsConstructor
public class HomeworkAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "coach_id", nullable = false)
    private UUID coachId;

    @Column(name = "drill_id", nullable = false)
    private UUID drillId;

    @Column(name = "pack_id")
    private UUID packId;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @PrePersist
    void onCreate() { assignedAt = Instant.now(); }
}
