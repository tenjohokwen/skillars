package com.softropic.skillars.platform.development.repo;

import com.softropic.skillars.platform.development.contract.AssessmentType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(schema = "development", name = "radar_assessment_entries")
@Getter
@Setter
@NoArgsConstructor
public class RadarAssessmentEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "assessment_group_id", nullable = false)
    private UUID assessmentGroupId;

    @Column(name = "coach_id", nullable = false)
    private UUID coachId;

    @Column(name = "player_id", nullable = false)
    private Long playerId;   // BIGINT TSID — NOT UUID

    @Column(name = "skill_code", nullable = false, length = 10)
    private String skillCode;

    @Column(nullable = false)
    private Short score;

    @Column(name = "assessment_date", nullable = false)
    private LocalDate assessmentDate;

    // @JdbcTypeCode(SqlTypes.NAMED_ENUM) tells Hibernate 6 to bind as Types.OTHER (PostgreSQL-compatible),
    // which allows implicit casting to the PostgreSQL custom enum type development.assessment_type.
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "assessment_type", nullable = false)
    private AssessmentType assessmentType;

    @Column(length = 500)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
