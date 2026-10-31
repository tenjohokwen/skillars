package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.infrastructure.security.RateLimitingService;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
    private final BookingRepository bookingRepository;
    private final RateLimitingService rateLimitingService;

    /** Same switch RateLimitingAspect honours. */
    @Value("${rate.limiting.enabled:true}")
    private boolean rateLimitingEnabled;

    /** S-08: flags one user may raise per hour, across all reviews — enough for honest use, not for a campaign. */
    static final int FLAGS_PER_USER_PER_HOUR = 10;

    /**
     * Bookings that make a user a genuine customer of the coach (mirrors messaging's CONFIRMED_STATES):
     * a flag from someone who has actually trained with, or paid for, the coach counts towards the
     * auto-hold; a flag from a stranger or a competitor is recorded for the admin but does not.
     */
    private static final List<String> QUALIFYING_BOOKING_STATES =
        List.of("CONFIRMED", "UPCOMING", "IN_PROGRESS", "COMPLETED");

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

        // S-08: before any read or lock, so a flag storm costs the rate-limit map and nothing else.
        if (rateLimitingEnabled && !rateLimitingService.tryConsume(String.valueOf(flaggedBy),
                "reviews.flag", FLAGS_PER_USER_PER_HOUR, 1, TimeUnit.HOURS)) {
            throw new OperationNotAllowedException(
                "You are flagging reviews too quickly. Please try again later.",
                ReviewErrorCode.FLAG_RATE_LIMITED);
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
        //
        // S-08: only flags from QUALIFYING users count towards the threshold — see qualifyingOpenFlagCount.
        // The admin still sees every flag (totalFlagCount below, the review_flags rows); the admin
        // approve/block path is unchanged.
        // skillars-deferred-107 AC2: 0 → the first flag on any review auto-holds it. Clamps to 3 + WARN.
        // skillars-deferred-132 AC3 Fix 10 / C5: key, default and bounds all come from the registry entry
        // (BoundedKey overload), so this call site cannot drift from what ConfigService.updateConfig and
        // ConfigStartupAssertion enforce.
        int threshold = configService.getBoundedInt(ConfigBounds.REVIEWS_AUTO_HOLD_FLAG_THRESHOLD);
        long openFlagCount = qualifyingOpenFlagCount(reviewId, review.getCoachId(), threshold);

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

    /**
     * S-08: open flags that count towards the auto-hold: distinct flaggers (the table's unique index
     * already makes each user one flag) who hold a real booking with THIS coach, and who are not
     * themselves a coach (a rival coach's flag is exactly the attack this exists to blunt). Stops
     * counting at {@code stopAt} — the caller only compares against the threshold.
     */
    private long qualifyingOpenFlagCount(UUID reviewId, UUID coachId, int stopAt) {
        long qualifying = 0;
        for (Long flaggerId : reviewFlagRepository.findOpenFlaggerIds(reviewId)) {
            if (flaggerId == null || coachProfileRepository.existsByUserId(flaggerId)) {
                continue;
            }
            if (bookingRepository.existsByCoachIdAndFamilyUserIdAndStatusIn(
                    coachId, flaggerId, QUALIFYING_BOOKING_STATES)) {
                qualifying++;
                if (qualifying >= stopAt) {
                    break;
                }
            }
        }
        return qualifying;
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
