package com.softropic.skillars.platform.development.repo;

import com.softropic.skillars.platform.development.contract.PlayerTimelineEventType;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(schema = "development", name = "player_timeline_events")
@Getter @Setter @NoArgsConstructor
public class PlayerTimelineEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "player_id", nullable = false, updatable = false)
    private Long playerId;  // BIGINT — NOT UUID

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50, updatable = false)
    private PlayerTimelineEventType eventType;

    @Column(name = "reference_id", updatable = false)
    private UUID referenceId;

    @Column(name = "reference_module", length = 50, updatable = false)
    private String referenceModule;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Type(JsonType.class)
    @Column(name = "metadata", columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> metadata;
}
