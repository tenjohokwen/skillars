package com.softropic.skillars.platform.reviews.repo;

import com.softropic.skillars.platform.reviews.contract.ReviewFlagReason;
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

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "reviews", name = "review_flags")
@Getter
@Setter
@NoArgsConstructor
public class ReviewFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "flag_id")
    private UUID flagId;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @Column(name = "flagged_by", nullable = false)
    private Long flaggedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReviewFlagReason reason;

    @Column(length = 500)
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
