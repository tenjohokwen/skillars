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
@Table(schema = "session", name = "homework_completions")
@Getter
@Setter
@NoArgsConstructor
public class HomeworkCompletion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "assignment_id", nullable = false, unique = true)
    private UUID assignmentId;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "completed_at", nullable = false, updatable = false)
    private Instant completedAt;

    @PrePersist
    void onCreate() { completedAt = Instant.now(); }
}
