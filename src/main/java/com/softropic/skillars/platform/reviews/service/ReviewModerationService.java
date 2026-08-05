package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.infrastructure.gemini.GeminiClient;
import com.softropic.skillars.platform.messaging.contract.ModerationVerdict;
import com.softropic.skillars.platform.reviews.contract.HeldReason;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus;
import com.softropic.skillars.platform.reviews.contract.ReviewSubmittedEvent;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
@Slf4j
public class ReviewModerationService {

    private static final String USER_CONTENT_BEGIN_DELIMITER = "---BEGIN USER CONTENT---";
    private static final String USER_CONTENT_END_DELIMITER = "---END USER CONTENT---";

    private final CoachReviewRepository reviewRepository;
    private final GeminiClient geminiClient;
    private final CoachRatingService coachRatingService;
    // REQUIRES_NEW: suspends the stale TX1 entity manager that is still bound to the thread
    // during AFTER_COMMIT, guaranteeing a fresh EntityManager and active JPA transaction.
    private final TransactionTemplate requiresNewTx;

    @Value("${platform.reviews.moderation.gemini.prompt-template}")
    private String promptTemplate;

    @Value("${platform.reviews.moderation.gemini.max-input-chars:2000}")
    private int maxInputChars;

    @Autowired
    public ReviewModerationService(CoachReviewRepository reviewRepository,
                                   GeminiClient geminiClient,
                                   CoachRatingService coachRatingService,
                                   PlatformTransactionManager txManager) {
        this.reviewRepository = reviewRepository;
        this.geminiClient = geminiClient;
        this.coachRatingService = coachRatingService;
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleReviewSubmitted(ReviewSubmittedEvent event) {
        UUID reviewId = event.reviewId();
        UUID coachId = event.coachId();
        String body = event.body();

        // Compute verdict outside any transaction (Gemini call must not hold a DB connection)
        ReviewModerationStatus status;
        // Single-element array to pass geminiFailure flag into the lambda (Java lambda capture restriction)
        boolean[] geminiFailure = { false };
        if (body == null || body.isBlank()) {
            status = ReviewModerationStatus.APPROVED;
        } else {
            String input = body.length() > maxInputChars ? body.substring(0, maxInputChars) : body;
            ModerationVerdict verdict;
            try {
                // TODO: replace this delimiter convention with real structural separation once
                // GeminiClientImpl/GeminiApiResponse support a systemInstruction + multi-turn role field.
                String sanitizedInput = input
                    .replace(USER_CONTENT_BEGIN_DELIMITER, "")
                    .replace(USER_CONTENT_END_DELIMITER, "");
                String prompt = promptTemplate
                    + "\n\n" + USER_CONTENT_BEGIN_DELIMITER + "\n"
                    + sanitizedInput
                    + "\n" + USER_CONTENT_END_DELIMITER;
                verdict = geminiClient.evaluate(prompt);
            } catch (Exception e) {
                log.warn("Gemini moderation failed for reviewId={}: {}", reviewId, e.getMessage());
                verdict = ModerationVerdict.UNCERTAIN;
                geminiFailure[0] = true;
            }
            status = switch (verdict) {
                case SAFE     -> ReviewModerationStatus.APPROVED;
                case UNSAFE   -> ReviewModerationStatus.BLOCKED;
                default       -> ReviewModerationStatus.UNDER_REVIEW;
            };
        }

        final ReviewModerationStatus finalStatus = status;
        try {
            requiresNewTx.execute(tx -> {
                // findByIdForUpdate, not findById: this verdict must lose to any decision already
                // recorded against the row. The Gemini call above runs outside any transaction and
                // can take seconds, which is ample room for an admin to resolve the review in the
                // meantime; an unlocked read plus an unconditional write would silently revert it.
                // This is the FIRST read of the row in this transaction (REQUIRES_NEW suspends the
                // AFTER_COMMIT thread's stale EntityManager), so the locked query returns fresh DB
                // state and needs no entityManager.refresh — contrast BookingService
                // .createBookingRequest, where an earlier findById forces one.
                reviewRepository.findByIdForUpdate(reviewId).ifPresentOrElse(
                    review -> {
                        // PENDING is the whole guard. ReviewSubmissionService.submitReview and
                        // .updateReview are the only publishers of ReviewSubmittedEvent and both set
                        // PENDING immediately before publishing, so PENDING is the only status this
                        // delivery may claim. Every other writer must win:
                        //   - AdminReviewService.approveReview/blockReview — admin decisions;
                        //   - ReviewFlagService.flag — auto-holds APPROVED -> UNDER_REVIEW at the
                        //     configured flag threshold (only reachable once already resolved);
                        //   - a duplicate delivery of this same event, which this also makes
                        //     idempotent for free.
                        ReviewModerationStatus current = review.getModerationStatus();
                        if (current != ReviewModerationStatus.PENDING) {
                            log.warn("ReviewModerationService: review {} already resolved as {} — "
                                    + "discarding moderation verdict {}",
                                reviewId, current, finalStatus);
                            return;
                        }
                        review.setModerationStatus(finalStatus);
                        if (finalStatus == ReviewModerationStatus.UNDER_REVIEW) {
                            review.setHeldReason(geminiFailure[0]
                                ? HeldReason.GEMINI_FAILURE
                                : HeldReason.GEMINI_UNCERTAIN);
                        }
                        reviewRepository.save(review);
                        // Recompute on APPROVED (new rating added) and BLOCKED (re-edit of previously
                        // APPROVED review must be removed from the aggregate). Inside the guarded
                        // branch, not beside it: a skipped write must not recompute, and neither must
                        // a review that was not found at all.
                        if (finalStatus == ReviewModerationStatus.APPROVED
                                || finalStatus == ReviewModerationStatus.BLOCKED) {
                            coachRatingService.recompute(coachId);
                        }
                    },
                    () -> log.warn("ReviewModerationService: review not found: {}", reviewId)
                );
                return null;
            });
        } catch (Exception e) {
            // Swallow to prevent AFTER_COMMIT exception propagating as HTTP 500.
            // The review was committed; status update will be resolved via admin queue (Epic 10).
            // Note this also swallows a lock-acquisition failure on the read above, which leaves the
            // review PENDING rather than mis-resolved — the safe direction.
            log.error("ReviewModerationService: status write failed for reviewId={}, coachId={}: {}",
                reviewId, coachId, e.getMessage(), e);
        }
    }
}
