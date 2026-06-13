package com.softropic.skillars.platform.marketplace.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(schema = "marketplace", name = "coach_media")
@Getter @Setter @NoArgsConstructor
public class CoachMediaItem {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "coach_id", nullable = false)
    private UUID coachId;
    @Column(name = "file_url", nullable = false)
    private String fileUrl;
    @Column(name = "media_type", nullable = false)
    private String mediaType;  // "IMAGE" | "VIDEO"
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private OffsetDateTime uploadedAt = OffsetDateTime.now();
}
