package com.softropic.skillars.platform.reviews.repo;

import com.softropic.skillars.platform.reviews.contract.AuthorRole;
import com.softropic.skillars.platform.reviews.contract.HeldReason;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus;
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
@Table(schema = "reviews", name = "coach_reviews")
@Getter
@Setter
@NoArgsConstructor
public class CoachReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "review_id", updatable = false, nullable = false)
    private UUID reviewId;

    @Column(name = "coach_id", nullable = false)
    private UUID coachId;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_role", nullable = false, length = 10)
    private AuthorRole authorRole;

    @Column(nullable = false)
    private Integer rating;

    @Column(length = 1000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", nullable = false, length = 15)
    private ReviewModerationStatus moderationStatus = ReviewModerationStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "held_reason", length = 20)
    private HeldReason heldReason;

    @Column(name = "coach_response_body", length = 500)
    private String coachResponseBody;

    @Column(name = "coach_response_at")
    private Instant coachResponseAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_modified_at", nullable = false)
    private Instant lastModifiedAt = Instant.now();

    // skillars-deferred-145 code review (D1, 2026-10-06): lastModifiedAt has three non-author
    // writers (AdminReviewService.approveReview/blockReview, ReviewFlagService's auto-hold), so
    // ReviewSubmissionService's eligibility re-check anchors on this field instead -- written ONLY
    // from updateReview and from submitReview (submission is itself an author write).
    // Nullable only for rows that predate V157: the migration backfills them (created_at when
    // moderation_epoch = 0, i.e. never author-edited; last_modified_at otherwise), so a NULL here
    // means a row written before that migration ran. ReviewSubmissionService falls back to
    // createdAt for those. Every row created after this story ships carries a real value.
    @Column(name = "author_last_edited_at")
    private Instant authorLastEditedAt;

    // Story skillars-deferred-88 AC1: monotonic per-review counter. submitReview leaves it at 0;
    // updateReview bumps it under the findByIdForUpdate row lock; ReviewModerationService discards a
    // verdict whose ReviewSubmittedEvent epoch != this row's current epoch. Primitive long mirrors
    // the entity's plain-field style; @Getter/@Setter are class-level via Lombok.
    @Column(name = "moderation_epoch", nullable = false)
    private long moderationEpoch = 0L;
}
