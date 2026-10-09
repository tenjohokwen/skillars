package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.admin.repo.DisputeRepository;
import com.softropic.skillars.platform.booking.repo.BookingReviewEligibilityProjection;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.reviews.contract.AuthorRole;
import com.softropic.skillars.platform.reviews.contract.ReviewEligibilityDto;
import com.softropic.skillars.platform.reviews.contract.ReviewErrorCode;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus;
import com.softropic.skillars.platform.reviews.contract.ReviewSubmittedEvent;
import com.softropic.skillars.platform.reviews.contract.SubmitReviewResponse;
import com.softropic.skillars.platform.reviews.repo.CoachReview;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ReviewSubmissionService {

    private final CoachReviewRepository coachReviewRepository;
    private final BookingRepository bookingRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ConfigService configService;
    private final EntityManager entityManager;
    // skillars-deferred-135 AC3: converts updateReview/submitCoachResponse's own findByIdForUpdate
    // calls below to NOWAIT + bounded retry, mirroring ReviewFlagService.flag()'s own shipped pattern.
    private final PessimisticLockRetryer lockRetryer;
    private final PlayerProfileRepository playerProfileRepository;
    private final DisputeRepository disputeRepository;

    /**
     * skillars-deferred-132 AC2 Fix 7: {@code @Transactional(REQUIRES_NEW)}, overriding this class's
     * own default (class-level {@code @Transactional}, {@code REQUIRED} propagation) for this method
     * only. This method's own catch below translates a {@code DataIntegrityViolationException} at
     * {@code saveAndFlush} into a clean {@code ALREADY_SUBMITTED} 4xx — safe only because Postgres has
     * already marked the underlying transaction rollback-only by the time that catch runs. Today that
     * is harmless because {@code ReviewResource} never wraps this call in its own transaction, making
     * this method's own {@code @Transactional} the outermost boundary; REQUIRES_NEW makes that true
     * unconditionally; regardless of what any future caller does, so a caught-and-translated DIVE can
     * never surface as {@code UnexpectedRollbackException} at an outer boundary. See this fix's own
     * story for the accepted tradeoffs (a second pooled connection per call; the row surviving an outer
     * rollback; a self-deadlock risk this method's own regression test is built to avoid).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SubmitReviewResponse submitReview(UUID coachId, Long authorId, String authorRoleStr,
                                             Integer rating, String body) {
        if (!coachProfileRepository.existsById(coachId)) {
            throw new ResourceNotFoundException("Coach", coachId.toString());
        }
        checkEligibility(coachId, authorId, Instant.EPOCH);
        if (coachReviewRepository.existsByAuthorIdAndCoachId(authorId, coachId)) {
            throw new OperationNotAllowedException(
                "Review already submitted for this coach",
                ReviewErrorCode.ALREADY_SUBMITTED);
        }
        AuthorRole authorRole;
        try {
            authorRole = AuthorRole.valueOf(authorRoleStr);
        } catch (IllegalArgumentException e) {
            throw new OperationNotAllowedException(
                "Role '" + authorRoleStr + "' is not permitted to submit reviews",
                ReviewErrorCode.AUTHOR_ROLE_NOT_ALLOWED);
        }
        CoachReview review = new CoachReview();
        review.setCoachId(coachId);
        review.setAuthorId(authorId);
        review.setAuthorRole(authorRole);
        review.setRating(rating);
        review.setBody(body);
        review.setModerationStatus(ReviewModerationStatus.PENDING);
        // Round-2 code review (R4): set authorLastEditedAt at submission too. Submitting IS an
        // author write, so "last author write" is well defined from creation onward; without this
        // every freshly-created review carried NULL and relied on updateReview's createdAt
        // fallback, which made the fallback (not the column) govern the commonest real sequence
        // -- submit, then edit -- and left the column's own javadoc describing the wrong
        // population as nullable. The fallback now serves only rows backfilled by V157.
        Instant createdNow = Instant.now();
        review.setLastModifiedAt(createdNow);
        review.setAuthorLastEditedAt(createdNow);
        try {
            review = coachReviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            // Code review 2026-09-23: mirrors ReviewFlagService.flag's identical Fix 6 —
            // uq_coach_reviews_author_coach is the only constraint on this insert that means "already
            // submitted" (V138__baseline_schema.sql:3080-3084); coach_reviews_rating_check and any
            // NOT NULL violation are different failures and must not be mislabeled as ALREADY_SUBMITTED.
            if (isAlreadySubmittedViolation(e)) {
                throw new OperationNotAllowedException(
                    "Review already submitted for this coach",
                    ReviewErrorCode.ALREADY_SUBMITTED);
            }
            throw e;
        }
        // AC1 (skillars-deferred-88): a freshly-created review keeps the default moderationEpoch = 0.
        eventPublisher.publishEvent(new ReviewSubmittedEvent(
            review.getReviewId(), coachId, authorId, rating, body, review.getModerationEpoch()));
        return new SubmitReviewResponse(review.getReviewId());
    }

    public void updateReview(UUID reviewId, Long authorId, Integer rating, String body) {
        CoachReview review = coachReviewRepository.findByReviewIdAndAuthorId(reviewId, authorId)
            .orElseThrow(() -> new OperationNotAllowedException(
                "Review not found or caller is not the author",
                ReviewErrorCode.AUTHOR_MISMATCH));

        // skillars-deferred-148 Finding 5: status checked before cooldown so a concurrent
        // moderation block always reports the more actionable EDIT_NOT_PERMITTED, not
        // UPDATE_TOO_SOON, when both conditions are independently true.
        ReviewModerationStatus status = review.getModerationStatus();
        if (status == ReviewModerationStatus.BLOCKED || status == ReviewModerationStatus.UNDER_REVIEW) {
            throw new OperationNotAllowedException(
                "Review cannot be edited in its current moderation status",
                ReviewErrorCode.EDIT_NOT_PERMITTED);
        }
        int cooldownDays = configService.getBoundedInt(
            ConfigBounds.REVIEWS_UPDATE_COOLDOWN_DAYS.key(), 30, 1, 365);
        if (review.getLastModifiedAt().isAfter(Instant.now().minus(cooldownDays, ChronoUnit.DAYS))) {
            throw new OperationNotAllowedException(
                "Review was modified within the cooldown window",
                ReviewErrorCode.UPDATE_TOO_SOON);
        }
        // skillars-deferred-145 code review (D1, 2026-10-06): sinceAfter anchors on
        // authorLastEditedAt, not lastModifiedAt. lastModifiedAt has three non-author writers
        // (AdminReviewService.approveReview/blockReview, ReviewFlagService's auto-hold) that would
        // otherwise retroactively void an already-earned qualifying session every time any of them
        // fires. authorLastEditedAt is written ONLY from this method (below), falling back to
        // createdAt for a pre-migration row that has never been edited since.
        Instant sinceAfter = review.getAuthorLastEditedAt() != null
            ? review.getAuthorLastEditedAt() : review.getCreatedAt();
        checkEligibility(review.getCoachId(), authorId, sinceAfter);

        // AC1 (skillars-deferred-88): serialise the moderation-epoch bump. The findByReviewIdAndAuthorId
        // load above is unlocked and only backs the author-match / cooldown / status pre-checks, so an
        // unauthorised caller still gets AUTHOR_MISMATCH without ever taking a row lock (same
        // order-of-operations rationale as MessagingService.softDeleteMessage). Every mutation below
        // runs on the locked instance. Author identity is already confirmed by the pre-check, so a
        // missing row here can only mean a concurrent delete — a not-found condition, not an authz one.
        // skillars-deferred-135 AC3: NOWAIT + bounded retry, not a genuinely blocking wait — mirrors
        // ReviewFlagService.flag()'s own shipped pattern exactly.
        CoachReview locked = lockRetryer.withBoundedRetry("ReviewSubmissionService.updateReview",
            () -> coachReviewRepository.findByIdForUpdateNoWait(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review", reviewId.toString())));
        // findByIdForUpdateNoWait is a JPQL query and the row is already managed from the unlocked load
        // above, so Hibernate takes the DB lock but returns the existing instance without refreshing
        // its fields. Without this refresh, two near-simultaneous edits would both read epoch N off a
        // stale instance and both publish N+1 (a lost update). Mirrors MessagingService.softDeleteMessage
        // / BookingService.createBookingRequest for the identical Hibernate identity-map gotcha.
        // Safe to call plain (no lock-timeout hint) after the NOWAIT read above: this re-requests the
        // SAME row lock this SAME transaction already holds — Postgres row locks are transaction-scoped,
        // so a same-transaction re-request never blocks, regardless of any other transaction's own
        // contention for the row.
        entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE);

        // Re-run the cooldown AND moderation-status guards on the FRESH locked instance (code review
        // 2026-10-06). The pre-checks above ran on the stale unlocked read; a concurrent caller that
        // wins the race, commits its own edit (bumping lastModifiedAt to now) and releases the lock
        // must not let THIS caller, which only re-checked moderation status after refresh, silently
        // overwrite that fresh edit a moment later — defeating the cooldown entirely for any burst of
        // near-simultaneous PATCHes. Mirrors MessagingService.softDeleteMessage re-checking
        // getDeletedAt() after its own refresh.
        ReviewModerationStatus lockedStatus = locked.getModerationStatus();
        if (lockedStatus == ReviewModerationStatus.BLOCKED
                || lockedStatus == ReviewModerationStatus.UNDER_REVIEW) {
            throw new OperationNotAllowedException(
                "Review cannot be edited in its current moderation status",
                ReviewErrorCode.EDIT_NOT_PERMITTED);
        }
        int lockedCooldownDays = configService.getBoundedInt(
            ConfigBounds.REVIEWS_UPDATE_COOLDOWN_DAYS.key(), 30, 1, 365);
        if (locked.getLastModifiedAt().isAfter(Instant.now().minus(lockedCooldownDays, ChronoUnit.DAYS))) {
            throw new OperationNotAllowedException(
                "Review was modified within the cooldown window",
                ReviewErrorCode.UPDATE_TOO_SOON);
        }
        // skillars-deferred-150 AC4: re-derive sinceAfter from the FRESH locked instance and
        // re-run eligibility too, alongside the status/cooldown re-checks above. Latent today, not
        // reachable: updateReview is the only normal-API writer of authorLastEditedAt besides
        // submitReview's own creation-time write, and it always writes authorLastEditedAt together
        // with lastModifiedAt from the same local now() -- so any concurrent edit that would
        // invalidate this caller's sinceAfter also trips the cooldown guard above first. This
        // closes the gap for a future change that decouples the two timestamp writes.
        //
        // Cost, not just correctness (code review 2026-10-09): this is checkEligibility's full
        // evaluateEligibility work -- findQualifyingCompletedBookings plus one PlayerProfile
        // lookup per distinct qualifying playerId, plus the dispute check -- running a SECOND time
        // per call, and this second run happens WHILE HOLDING the PESSIMISTIC_WRITE row lock taken
        // above, extending the lock-hold window by however long that query takes. Accepted
        // deliberately: the status/cooldown re-checks already run inside this same lock window for
        // the identical reason (closing the stale-refresh gap those guards exist for), and
        // eligibility is latent/unreachable today exactly like them -- see the story's own Review
        // Findings for why doing it any other way (e.g. before the lock) would reopen the gap.
        Instant lockedSinceAfter = locked.getAuthorLastEditedAt() != null
            ? locked.getAuthorLastEditedAt() : locked.getCreatedAt();
        checkEligibility(locked.getCoachId(), authorId, lockedSinceAfter);

        locked.setRating(rating);
        locked.setBody(body);
        locked.setModerationStatus(ReviewModerationStatus.PENDING);
        Instant now = Instant.now();
        locked.setLastModifiedAt(now);
        locked.setAuthorLastEditedAt(now);
        locked.setCoachResponseBody(null);
        locked.setCoachResponseAt(null);
        locked.setModerationEpoch(locked.getModerationEpoch() + 1);
        coachReviewRepository.save(locked);
        eventPublisher.publishEvent(new ReviewSubmittedEvent(
            locked.getReviewId(), locked.getCoachId(), authorId, rating, body, locked.getModerationEpoch()));
    }

    public void submitCoachResponse(UUID reviewId, UUID coachId, String responseBody) {
        // skillars-deferred-135 AC3: NOWAIT + bounded retry — see updateReview's own comment above.
        CoachReview review = lockRetryer.withBoundedRetry("ReviewSubmissionService.submitCoachResponse",
            () -> coachReviewRepository.findByIdForUpdateNoWait(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review", reviewId.toString())));
        if (!review.getCoachId().equals(coachId)) {
            throw new OperationNotAllowedException(
                "Authenticated coach does not own this review",
                ReviewErrorCode.COACH_MISMATCH);
        }
        if (review.getModerationStatus() != ReviewModerationStatus.APPROVED) {
            throw new OperationNotAllowedException(
                "Coach response is only permitted on approved reviews",
                ReviewErrorCode.REVIEW_NOT_APPROVED);
        }
        if (review.getCoachResponseBody() != null) {
            throw new OperationNotAllowedException(
                "A response has already been submitted for this review",
                ReviewErrorCode.RESPONSE_ALREADY_SUBMITTED);
        }
        review.setCoachResponseBody(responseBody);
        review.setCoachResponseAt(Instant.now());
        coachReviewRepository.save(review);
    }

    private static final String UNIQUE_AUTHOR_COACH_CONSTRAINT = "uq_coach_reviews_author_coach";

    /** Mirrors {@code ReviewFlagService.isUniqueFlaggerViolation}'s single-level unwrap pattern. */
    private static boolean isAlreadySubmittedViolation(DataIntegrityViolationException ex) {
        return ex.getCause() instanceof org.hibernate.exception.ConstraintViolationException cve
            && UNIQUE_AUTHOR_COACH_CONSTRAINT.equals(cve.getConstraintName());
    }

    /**
     * Read-only "can I write a new review for this coach" pre-check, consumed by {@code
     * GET /api/reviews/coaches/{coachId}/eligibility} so the frontend can disable/explain the
     * "Write a Review" button before the user fills out the form, instead of only discovering
     * ineligibility from a submit-time 403 (bug report, 2026-10-08: the button was unconditionally
     * active for any parent/player, regardless of whether they had a qualifying session). Mirrors
     * {@code submitReview}'s own {@code checkEligibility(coachId, authorId, Instant.EPOCH)} call
     * exactly — same sinceAfter, since this answers the "write", not "edit", question.
     */
    public ReviewEligibilityDto checkWriteEligibility(UUID coachId, Long authorId) {
        if (!coachProfileRepository.existsById(coachId)) {
            throw new ResourceNotFoundException("Coach", coachId.toString());
        }
        return evaluateEligibility(coachId, authorId, Instant.EPOCH);
    }

    private void checkEligibility(UUID coachId, Long authorId, Instant sinceAfter) {
        ReviewEligibilityDto result = evaluateEligibility(coachId, authorId, sinceAfter);
        if (result.eligible()) {
            return;
        }
        if (ReviewErrorCode.ACTIVE_DISPUTE.getErrorCode().equals(result.reasonCode())) {
            throw new OperationNotAllowedException(
                "An active dispute exists between this author and coach",
                ReviewErrorCode.ACTIVE_DISPUTE);
        }
        throw new OperationNotAllowedException(
            "No qualifying completed session with this coach",
            ReviewErrorCode.NO_QUALIFYING_SESSION);
    }

    // skillars-deferred-150 AC3: must match BookingRepository.findQualifyingCompletedBookings'
    // own HQL-literal `limit` exactly -- Java has no way to read that literal back, so this is a
    // second hand, not a shared constant. Used only to log an observable signal if the cap is
    // ever actually hit (code review 2026-10-09: the cap+ordering combination was unreachable
    // in practice at review time, by design, but that made it silent-by-default too -- this log
    // line is the one thing standing between "unreachable in practice" and "unreachable, but we
    // would never find out if that stopped being true").
    private static final int QUALIFYING_BOOKINGS_CAP = 50;

    private ReviewEligibilityDto evaluateEligibility(UUID coachId, Long authorId, Instant sinceAfter) {
        int minAgeDays = configService.getBoundedInt(
            ConfigBounds.REVIEWS_MIN_SESSION_AGE_DAYS.key(), 7, 1, 365);
        Instant maturedBefore = Instant.now().minus(minAgeDays, ChronoUnit.DAYS);

        List<BookingReviewEligibilityProjection> qualifying =
            bookingRepository.findQualifyingCompletedBookings(coachId, authorId, maturedBefore, sinceAfter);
        if (qualifying.size() >= QUALIFYING_BOOKINGS_CAP) {
            log.warn("findQualifyingCompletedBookings hit its {}-row cap for coachId={}, authorId={} -- "
                    + "the owned playerId may have been truncated if it sorts above the cap",
                QUALIFYING_BOOKINGS_CAP, coachId, authorId);
        }

        boolean eligible = false;
        for (BookingReviewEligibilityProjection booking : qualifying) {
            PlayerProfile player = playerProfileRepository.findById(booking.getPlayerId()).orElse(null);
            if (player == null) {
                continue; // orphaned playerId -- contributes to neither outcome, see Dev Notes F5
            }
            // skillars-deferred-145 code review (D2, 2026-10-06): the age-tier discriminator was
            // removed entirely -- "so long as a player is under a parent account, the parent should
            // be the reviewer," regardless of the player's age. No flow ever transfers an existing
            // parent-linked PlayerProfile to self-owned (chk_pp_owner forces exactly one of
            // parent_id/user_id for life; ShadowAccountService.createSelfOwnedPlayerProfile only ever
            // creates a brand-new self-owned profile), so an age check here made a parent-linked
            // player's session permanently unreviewable by anyone the moment that player turned 18.
            if (authorId.equals(player.getUserId()) || authorId.equals(player.getParentId())) {
                eligible = true; // the author is reviewing their own session, or their linked player's
                break;
            }
        }

        if (!eligible) {
            return new ReviewEligibilityDto(false, ReviewErrorCode.NO_QUALIFYING_SESSION.getErrorCode());
        }

        if (disputeRepository.existsActiveDisputeByAuthor(coachId, authorId)) {
            return new ReviewEligibilityDto(false, ReviewErrorCode.ACTIVE_DISPUTE.getErrorCode());
        }

        return new ReviewEligibilityDto(true, null);
    }
}
