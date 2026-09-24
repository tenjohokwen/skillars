package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.reviews.contract.HeldReason;
import com.softropic.skillars.platform.reviews.contract.ReviewErrorCode;
import com.softropic.skillars.platform.reviews.contract.ReviewFlagReason;
import com.softropic.skillars.platform.reviews.contract.ReviewFlaggedEvent;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus;
import com.softropic.skillars.platform.reviews.repo.CoachReview;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import com.softropic.skillars.platform.reviews.repo.ReviewFlag;
import com.softropic.skillars.platform.reviews.repo.ReviewFlagRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ReviewFlagService {

    private final CoachReviewRepository reviewRepository;
    private final ReviewFlagRepository reviewFlagRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final CoachRatingService coachRatingService;
    private final ConfigService configService;
    private final ApplicationEventPublisher eventPublisher;
    private final PessimisticLockRetryer lockRetryer;

    /**
     * skillars-deferred-132 AC2 Fix 7: {@code @Transactional(REQUIRES_NEW)}, overriding this class's
     * own class-level default for this method only — the byte-for-byte identical rollback-only trap as
     * {@code ReviewSubmissionService.submitReview} (this method's own {@code saveAndFlush}/catch-
     * {@code DataIntegrityViolationException}/translate-to-{@code ALREADY_FLAGGED} shape is
     * deliberately mirrored from that method, per that method's own comment), so it gets the identical
     * fix for the identical reason. See {@code ReviewSubmissionService.submitReview}'s own Javadoc for
     * the accepted tradeoffs.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID flag(UUID reviewId, Long flaggedBy, ReviewFlagReason reason, String details) {
        // skillars-deferred-131 AC2 Fix 7: unreachable from REST today (resolveUserId() guards
        // against a null caller before this method is invoked) — defensive hardening against an NPE
        // on the .equals(...) self-flag check below, not a live bug. Checked before the lock
        // acquisition, mirroring the other four write-independent guards this fix restructures.
        if (flaggedBy == null) {
            throw new OperationNotAllowedException(
                "Flagger identity is required", ReviewErrorCode.INVALID_FLAGGER);
        }

        // skillars-deferred-131 AC2 Fix 5: unlocked scalar projection for the four write-independent
        // guards below (self-flag, missing coach profile, coach-flags-own-profile, ALREADY_FLAGGED) —
        // none of them write anything, yet all five previously ran under the row's exclusive
        // FOR UPDATE lock (skillars-deferred-130 AC1 Fix 1), serializing admin moderation
        // (AdminReviewService.approveReview/blockReview) behind a call that was always going to no-op
        // on a repeat flag. Deliberately NOT reviewRepository.findById(...) here: an earlier unlocked
        // entity load would put CoachReview in the persistence context via Hibernate's identity map,
        // so the LATER findByIdForUpdate below would take the DB lock but return the already-managed
        // instance without refreshing its fields — the exact stale-read bug
        // ReviewSubmissionService.updateReview/AdminReviewService.approveReview both guard against.
        // A scalar/projection query never manages an entity, so nothing enters the persistence
        // context before the locked read below, which stays genuinely this transaction's first load
        // of the CoachReview entity.
        List<Object[]> authorAndCoachIdRows = reviewRepository.findAuthorAndCoachIdByReviewId(reviewId);
        if (authorAndCoachIdRows.isEmpty()) {
            throw new OperationNotAllowedException(
                "Review not found", ReviewErrorCode.REVIEW_NOT_FOUND);
        }
        Object[] authorAndCoachId = authorAndCoachIdRows.get(0);
        Long authorId = (Long) authorAndCoachId[0];
        UUID coachId = (UUID) authorAndCoachId[1];

        if (authorId.equals(flaggedBy)) {
            throw new OperationNotAllowedException(
                "Cannot flag your own review", ReviewErrorCode.CANNOT_FLAG_OWN_REVIEW);
        }

        CoachProfile coachProfile = coachProfileRepository.findById(coachId)
            .orElseThrow(() -> new OperationNotAllowedException(
                "Coach profile not found for review", ReviewErrorCode.COACH_PROFILE_MISSING));
        if (flaggedBy.equals(coachProfile.getUserId())) {
            throw new OperationNotAllowedException(
                "Coach cannot flag reviews of their own profile",
                ReviewErrorCode.CANNOT_FLAG_OWN_COACHED_REVIEW);
        }

        if (reviewFlagRepository.existsByReviewIdAndFlaggedBy(reviewId, flaggedBy)) {
            throw new OperationNotAllowedException(
                "You have already flagged this review", ReviewErrorCode.ALREADY_FLAGGED);
        }

        // skillars-deferred-131 AC2 Fix 5: only now, after all four guards pass, is the CoachReview
        // entity loaded for the first time — this comment's "first read of the row" claim (dating
        // from skillars-deferred-130 AC1 Fix 1) therefore stays true verbatim.
        //
        // skillars-deferred-132 AC1 Fix 2: NOWAIT + PessimisticLockRetryer, not the shared blocking
        // findByIdForUpdate — a 3rd-time-raised decision to convert this method's own lock away from
        // an unbounded blocking wait, mirroring the module's established NOWAIT+retry convention
        // (CoachProfileRepository, PlayerProfileRepository, etc). The other five findByIdForUpdate
        // call sites in this module are untouched and stay genuinely blocking (see
        // CoachReviewRepository.findByIdForUpdateNoWait's own comment for why converting the shared
        // method in place would have been a regression).
        CoachReview review = lockRetryer.withBoundedRetry("ReviewFlagService.flag",
            () -> reviewRepository.findByIdForUpdateNoWait(reviewId)
                .orElseThrow(() -> new OperationNotAllowedException(
                    "Review not found", ReviewErrorCode.REVIEW_NOT_FOUND)));

        ReviewFlag flag = new ReviewFlag();
        flag.setReviewId(reviewId);
        flag.setFlaggedBy(flaggedBy);
        flag.setReason(reason);
        flag.setDetails(details);
        try {
            reviewFlagRepository.saveAndFlush(flag);
        } catch (DataIntegrityViolationException e) {
            // skillars-deferred-131 AC2 Fix 6: only review_flags_unique_flagger(review_id, flagged_by)
            // means "already flagged" — review_flags' other constraints (review_flags_review_id_fkey,
            // review_flags_pkey, NOT NULL reason, details length) can fail concurrently too and none of
            // those mean that. Low severity (unreachable from REST today given upstream validation),
            // but worth precision while this method is already being touched.
            if (isUniqueFlaggerViolation(e)) {
                throw new OperationNotAllowedException(
                    "You have already flagged this review", ReviewErrorCode.ALREADY_FLAGGED);
            }
            throw e;
        }

        // The status guard below runs on the row locked above (skillars-deferred-130 AC1 Fix 1). The
        // count read here is NOT itself locked (it reads reviews.review_flags, a different table from
        // the coach_reviews row this method holds FOR UPDATE) — its consistency is convention-based,
        // not mechanically enforced: it holds only because this method and its two siblings
        // (AdminReviewService.approveReview/blockReview) all happen to take the coach_reviews lock
        // before touching review_flags, so nothing else can be resolving flags concurrently while this
        // count runs. A stale pre-lock count could otherwise justify an auto-hold the fresh count no
        // longer supports (e.g. an admin resolved the flags in the window between an unlocked read and
        // this lock) — code review 2026-09-23.
        long openFlagCount = reviewFlagRepository.countByReviewIdAndResolvedAtIsNull(reviewId);
        // skillars-deferred-107 AC2: 0 → the first flag on any review auto-holds it. Clamps to 3 + WARN.
        // skillars-deferred-132 AC3 Fix 10: references the existing ConfigBounds constant's key
        // instead of the raw string literal — future typo-drift protection at this call site (a typo'd
        // literal would silently create an unrelated, always-defaulted key with no compile-time signal;
        // ConfigStartupAssertion boot-protects by string lookup either way, so this is not a fail-fast
        // fix). Bounds (1, 1000) stay re-typed as literals, per this codebase's own documented
        // drift-detector convention.
        int threshold = configService.getBoundedInt(
            ConfigBounds.REVIEWS_AUTO_HOLD_FLAG_THRESHOLD.key(), 3, 1, 1000);

        boolean autoHeld = false;
        if (openFlagCount >= threshold && review.getModerationStatus() == ReviewModerationStatus.APPROVED) {
            review.setModerationStatus(ReviewModerationStatus.UNDER_REVIEW);
            review.setHeldReason(HeldReason.FLAG_THRESHOLD);
            review.setLastModifiedAt(Instant.now());
            reviewRepository.save(review);
            coachRatingService.recompute(review.getCoachId());
            autoHeld = true;
        }

        long totalFlagCount = reviewFlagRepository.countByReviewId(reviewId);
        eventPublisher.publishEvent(
            new ReviewFlaggedEvent(reviewId, review.getCoachId(), totalFlagCount, autoHeld));

        return flag.getFlagId();
    }

    private static final String UNIQUE_FLAGGER_INDEX = "review_flags_unique_flagger";

    /**
     * skillars-deferred-131 AC2 Fix 6. {@code review_flags_unique_flagger} is a unique INDEX, not a
     * named table constraint ({@code V138__baseline_schema.sql:3931-3934}) — Postgres still reports
     * an index name in a {@code 23505} violation's {@code constraint} field the same way it reports a
     * named constraint, so this name-match works identically to
     * {@code SluPersistenceRetrier.isSessionSkillUniqueViolation}'s own pattern (single-level unwrap,
     * mirroring {@code ApiAdvice.integrityViolationHandler}).
     */
    private static boolean isUniqueFlaggerViolation(DataIntegrityViolationException ex) {
        return ex.getCause() instanceof org.hibernate.exception.ConstraintViolationException cve
            && UNIQUE_FLAGGER_INDEX.equals(cve.getConstraintName());
    }
}
