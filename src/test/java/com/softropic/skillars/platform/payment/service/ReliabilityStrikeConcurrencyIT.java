package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.contract.event.StrikeThresholdReachedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-100 AC1: two concurrent {@link ReliabilityStrikeService#issue} calls for the
 * same coach, each of which crosses the suspension threshold, must publish
 * {@link StrikeThresholdReachedEvent} exactly once and move the coach to
 * {@code PENDING_REVIEW} exactly once — while still persisting both strike rows.
 *
 * <p>Before the fix, {@code issue()} read an unlocked rolling count and an unlocked coach status,
 * so two callers released together both saw {@code count = threshold} and {@code status = ACTIVE},
 * both passed the guard, and both fired the event ({@code CoachProfile} has no {@code @Version}, so
 * there was no optimistic-lock backstop). The fix takes a {@code PESSIMISTIC_WRITE} lock on the
 * coach row (via {@code PessimisticLockRetryer}) before the count/threshold/status decision, so the
 * loser blocks until the winner commits, then re-reads {@code status = PENDING_REVIEW} and its
 * guard suppresses the duplicate.
 *
 * <p><strong>Mutation check:</strong> revert the locked read in {@code ReliabilityStrikeService} to
 * {@code coachProfileRepository.findById(coachId)} and this test fails — both threads publish a
 * {@code StrikeThresholdReachedEvent}, so either the capture below sees two, or (as observed) the
 * second synchronous {@code AdminAlertEventListener.onStrikeThreshold} insert trips the
 * {@code admin_alerts_unique_open_per_ref} partial unique index and one {@code issue()} transaction
 * rolls back with a {@code DataIntegrityViolationException} — losing its strike row too.
 */
@Import(ReliabilityStrikeConcurrencyIT.StrikeEventCapture.class)
class ReliabilityStrikeConcurrencyIT extends BasePaymentIT {

    @Autowired ReliabilityStrikeService reliabilityStrikeService;
    @Autowired CoachProfileRepository coachProfileRepository;
    @Autowired StrikeEventCapture eventCapture;

    private static final long COACH_USER_ID = 70123L;
    private static final String COACH_EMAIL = "coach.strikerace@test.com";

    @Test
    @Timeout(60)
    void concurrentIssue_bothCrossThreshold_publishesThresholdEventOnce() throws Exception {
        UUID coachId = insertTestCoach(COACH_USER_ID, COACH_EMAIL, "Strike Race Coach");
        eventCapture.clear();

        // Suspension threshold default is 5. Seed threshold - 1 = 4 strikes inside the 30-day
        // window so the next two issued strikes each land the rolling count at >= 5.
        transactionTemplate.execute(status -> {
            for (int i = 0; i < 4; i++) {
                seedStrike(coachId);
            }
            return null;
        });

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch bothReady = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> race(startGate, bothReady, coachId));
            Future<?> b = pool.submit(() -> race(startGate, bothReady, coachId));

            assertThat(bothReady.await(10, TimeUnit.SECONDS))
                .as("both worker threads must reach the gate").isTrue();
            startGate.countDown();

            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(eventCapture.forCoach(coachId))
            .as("exactly one StrikeThresholdReachedEvent despite two concurrent threshold-crossing strikes")
            .hasSize(1);

        CoachProfileStatus status = coachProfileRepository.findById(coachId).orElseThrow().getStatus();
        assertThat(status).isEqualTo(CoachProfileStatus.PENDING_REVIEW);

        Long strikeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_reliability_strikes WHERE coach_id = ?",
            Long.class, coachId);
        assertThat(strikeCount)
            .as("both concurrent strike rows persisted on top of the 4 seeded")
            .isEqualTo(6);
    }

    private void race(CountDownLatch startGate, CountDownLatch bothReady, UUID coachId) {
        bothReady.countDown();
        try {
            startGate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        reliabilityStrikeService.issue(coachId, UUID.randomUUID(), "COACH_NO_SHOW");
    }

    private void seedStrike(UUID coachId) {
        jdbcTemplate.update(
            "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, booking_id, reason, acknowledged, created_at) " +
            "VALUES (?, ?, ?, 'COACH_NO_SHOW', false, ?)",
            UUID.randomUUID(), coachId, UUID.randomUUID(),
            Timestamp.from(Instant.now().minusSeconds(3600)));
    }

    @Component
    static class StrikeEventCapture {
        private final List<StrikeThresholdReachedEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(StrikeThresholdReachedEvent event) {
            events.add(event);
        }

        List<StrikeThresholdReachedEvent> forCoach(UUID coachId) {
            return events.stream().filter(e -> coachId.equals(e.getCoachId())).toList();
        }

        void clear() {
            events.clear();
        }
    }
}
