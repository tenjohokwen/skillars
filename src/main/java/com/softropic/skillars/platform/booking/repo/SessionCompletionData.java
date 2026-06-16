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
@Table(schema = "booking", name = "session_completion_data")
@Getter
@Setter
@NoArgsConstructor
public class SessionCompletionData {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "booking_id", nullable = false, unique = true)
    private UUID bookingId;

    @Column(name = "coach_id", nullable = false)
    private UUID coachId;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "player_attended", nullable = false)
    private boolean playerAttended = true;

    @Column(name = "effort_rating")
    private Integer effortRating;

    @Column(name = "focus_rating")
    private Integer focusRating;

    @Column(name = "technique_rating")
    private Integer techniqueRating;

    @Column(name = "voice_note_text", length = 2000)
    private String voiceNoteText;

    @Column(name = "homework_drill_ids")
    private String homeworkDrillIds;

    @Column(name = "completion_mode", nullable = false, length = 10)
    private String completionMode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
