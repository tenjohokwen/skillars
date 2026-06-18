package com.softropic.skillars.platform.session.repo;

import com.softropic.skillars.platform.session.contract.SessionBlockData;
import com.softropic.skillars.platform.session.contract.SessionDnaScore;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(schema = "session", name = "session_templates")
@Getter
@Setter
@NoArgsConstructor
public class SessionTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "coach_id", nullable = false)
    private UUID coachId;

    @Column(nullable = false, length = 200)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<SessionBlockData> blocks;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "session_dna", columnDefinition = "jsonb")
    private SessionDnaScore sessionDna;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "equipment_list", nullable = false, columnDefinition = "jsonb")
    private List<String> equipmentList;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "development_focus", nullable = false, columnDefinition = "jsonb")
    private List<String> developmentFocus;

    @Column(name = "last_deployed_at")
    private Instant lastDeployedAt;

    @Column(name = "deploy_count", nullable = false)
    private int deployCount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        if (status == null) status = "ACTIVE";
    }
}
