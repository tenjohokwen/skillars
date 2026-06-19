package com.softropic.skillars.platform.development.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "development", name = "coach_branding")
@Getter @Setter @NoArgsConstructor
public class CoachBranding {

    @Id
    private UUID coachId;

    @Column(name = "logo_key", length = 500)
    private String logoKey;       // nullable storage key

    @Column(name = "brand_colour", length = 7)
    private String brandColour;   // nullable hex e.g. '#3B82F6'

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
