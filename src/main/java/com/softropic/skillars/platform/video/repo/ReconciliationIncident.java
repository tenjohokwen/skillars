package com.softropic.skillars.platform.video.repo;

import com.softropic.skillars.platform.video.contract.ReconciliationIncidentType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "reconciliation_incidents", schema = "main")
public class ReconciliationIncident {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "video_id")
    private UUID videoId;

    @Enumerated(EnumType.STRING)
    @Column(name = "incident_type", nullable = false)
    private ReconciliationIncidentType incidentType;

    @Column(name = "provider_asset_id")
    private String providerAssetId;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
