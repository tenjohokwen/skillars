package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.reviews.contract.HeldReason;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Recovers reviews stranded in PENDING because their async moderation verdict never landed:
 * {@code ReviewModerationService.handleReviewSubmitted} runs AFTER_COMMIT and swallows every failure
 * of its status write (lock-retry exhaustion, a DB error, the JVM dying between the review's commit
 * and the listener). Before this job such a review stayed PENDING forever: invisible on the public
 * list, uneditable-in-practice for its author (re-PENDs only), and absent from the admin queue,
 * which lists only UNDER_REVIEW.
 *
 * <p><strong>Fail-closed, mirroring {@code MessageModerationSweeper}.</strong> Never approve and
 * never re-call Gemini: this job does not know why the first verdict never landed, and a SAFE
 * result from a blind retry would publish content no successful moderation pass ever saw. The
 * review is moved to UNDER_REVIEW with {@link HeldReason#MODERATION_TIMEOUT}, which puts it in the
 * existing admin review queue for a human decision.
 *
 * <p>Concurrency: the per-row write is an atomic conditional UPDATE
 * ({@link CoachReviewRepository#holdIfStillPending}) that re-checks PENDING and the age threshold
 * under the row lock, so a late Gemini verdict, an admin decision or an author edit that commits
 * between the select and the update wins and this becomes a no-op. A late verdict arriving AFTER
 * the hold sees UNDER_REVIEW and is discarded by the listener's own PENDING guard.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewModerationSweeper {

    private static final String SWEPT_COUNTER = "reviews.moderation.swept";

    /** Max reviews held per run; the remainder is picked up on the next 10-minute tick. */
    static final int MAX_BATCH = 500;

    private final CoachReviewRepository reviewRepository;
    private final ConfigService configService;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    // Same cadence and lock margins as MessageModerationSweeper (lockAtMostFor 2x the delay).
    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "ReviewModerationSweeper_sweep", lockAtMostFor = "PT20M", lockAtLeastFor = "PT2M")
    public void sweep() {
        long graceMinutes = configService.getBoundedLong(ConfigBounds.REVIEWS_MODERATION_ORPHAN_GRACE_MINUTES);
        Instant threshold = Instant.now().minus(Duration.ofMinutes(graceMinutes));

        List<UUID> stranded = transactionTemplate.execute(status -> reviewRepository.findPendingIdsOlderThan(
            ReviewModerationStatus.PENDING, threshold, PageRequest.of(0, MAX_BATCH)));
        if (stranded == null || stranded.isEmpty()) {
            return;
        }
        if (stranded.size() == MAX_BATCH) {
            log.warn("ReviewModerationSweeper: batch cap of {} reached — more stranded reviews remain "
                + "and will be picked up by the next run", MAX_BATCH);
        }

        int held = 0;
        for (UUID reviewId : stranded) {
            try {
                Integer updated = transactionTemplate.execute(status -> reviewRepository.holdIfStillPending(
                    reviewId, ReviewModerationStatus.PENDING, ReviewModerationStatus.UNDER_REVIEW,
                    HeldReason.MODERATION_TIMEOUT, threshold));
                if (updated != null && updated > 0) {
                    held++;
                    // Counted after commit, not inside the transaction: a rollback must not count.
                    Counter.builder(SWEPT_COUNTER).register(meterRegistry).increment();
                    log.info("Held stranded PENDING review for admin moderation: reviewId={}", reviewId);
                }
            } catch (Exception e) {
                log.error("ReviewModerationSweeper failed on review {}", reviewId, e);
            }
        }
        log.info("ReviewModerationSweeper: {} of {} PENDING review(s) older than {} minutes held as "
            + "UNDER_REVIEW/MODERATION_TIMEOUT", held, stranded.size(), graceMinutes);
    }
}
