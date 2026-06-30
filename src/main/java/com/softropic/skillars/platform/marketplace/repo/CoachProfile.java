package com.softropic.skillars.platform.marketplace.repo;

import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import io.hypersistence.utils.hibernate.type.array.ListArrayType;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(schema = "marketplace", name = "coach_profiles")
@Getter
@Setter
@NoArgsConstructor
public class CoachProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String bio;

    private String city;
    private String district;

    @Type(ListArrayType.class)
    @Column(columnDefinition = "varchar[]")
    private List<String> languages = new ArrayList<>();

    @Column(name = "canonical_timezone", nullable = false)
    private String canonicalTimezone;

    @Column(name = "photo_url")
    private String photoUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CoachProfileStatus status = CoachProfileStatus.DRAFT;

    @Column(name = "verification_tier", nullable = false)
    private String verificationTier = "BASIC";

    @Column(name = "average_rating", columnDefinition = "NUMERIC(3,1)")
    private Double averageRating;

    @Column(name = "review_count", nullable = false)
    private int reviewCount;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
