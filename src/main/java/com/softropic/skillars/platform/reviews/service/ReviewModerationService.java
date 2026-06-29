package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.infrastructure.gemini.GeminiClient;
import com.softropic.skillars.platform.messaging.contract.ModerationVerdict;
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
        if (body == null || body.isBlank()) {
            status = ReviewModerationStatus.APPROVED;
        } else {
            String input = body.length() > maxInputChars ? body.substring(0, maxInputChars) : body;
            ModerationVerdict verdict;
            try {
                verdict = geminiClient.evaluate(promptTemplate + input);
            } catch (Exception e) {
                log.warn("Gemini moderation failed for reviewId={}: {}", reviewId, e.getMessage());
                verdict = ModerationVerdict.UNCERTAIN;
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
                reviewRepository.findById(reviewId).ifPresentOrElse(
                    review -> {
                        review.setModerationStatus(finalStatus);
                        reviewRepository.save(review);
                    },
                    () -> log.warn("ReviewModerationService: review not found: {}", reviewId)
                );
                // Recompute on APPROVED (new rating added) and BLOCKED (re-edit of previously
                // APPROVED review must be removed from the aggregate).
                if (finalStatus == ReviewModerationStatus.APPROVED
                        || finalStatus == ReviewModerationStatus.BLOCKED) {
                    coachRatingService.recompute(coachId);
                }
                return null;
            });
        } catch (Exception e) {
            // Swallow to prevent AFTER_COMMIT exception propagating as HTTP 500.
            // The review was committed; status update will be resolved via admin queue (Epic 10).
            log.error("ReviewModerationService: status write failed for reviewId={}, coachId={}: {}",
                reviewId, coachId, e.getMessage(), e);
        }
    }
}
