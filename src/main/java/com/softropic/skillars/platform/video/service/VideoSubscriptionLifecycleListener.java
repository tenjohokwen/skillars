package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.payment.contract.event.SubscriptionExpiredEvent;
import com.softropic.skillars.platform.video.contract.PlayerSubscriptionQueryPort;
import com.softropic.skillars.platform.video.repo.SubscriptionLifecycleOutbox;
import com.softropic.skillars.platform.video.repo.SubscriptionLifecycleOutboxRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoSubscriptionLifecycleListener {

    /**
     * skillars-deferred-120 code review (2026-09-17, Decision 4): Path B's {@code do/while} in
     * {@link #processEntry} drains <em>every</em> ACTIVE/READY video the subscriber owns, not "up to
     * one page" — video count per owner is bounded only by storage quota (bytes), not a video-count
     * limit, so it is genuinely unbounded. This scheduler is the one place in the codebase where
     * {@code @SchedulerLock} is the <em>only</em> double-processing protection (no locking select, no
     * claim flag, no {@code @Version} on {@link SubscriptionLifecycleOutbox}) — exceeding {@code
     * lockAtMostFor} restores the full AC1 bug with zero backstop.
     *
     * <p><strong>Hitting this cap must NOT count as a failed attempt.</strong> {@link
     * #processAndSaveEntry} increments {@code attempts} before entering its {@code try} block, and
     * {@code platform.video.lifecycle.outbox_max_attempts} seeds at 5
     * ({@code V139__baseline_seed_data.sql}) — so a subscriber whose video count genuinely needs more
     * than one attempt's worth of pages would dead-letter after ~5 scheduler ticks if a capped
     * attempt were treated as a failure, exactly as if something were actually wrong. {@link
     * #processEntry} therefore returns a {@code boolean} ("fully completed this attempt?") rather
     * than throwing when the cap is hit; {@link #processAndSaveEntry} un-counts the attempt and
     * leaves the entry {@code PENDING} in that case, so a capped attempt costs the subscriber nothing
     * and the next tick resumes real forward progress — {@code findActiveReadyByOwner} excludes
     * already-blocked videos, so no page of work is repeated.
     */
    private static final int MAX_PAGES_PER_ENTRY = 10;

    private final SubscriptionLifecycleOutboxRepository outboxRepository;
    private final PlayerSubscriptionQueryPort playerSubscriptionQueryPort;
    private final VideoRepository videoRepository;
    private final VideoLifecycleService videoLifecycleService;
    private final ConfigService configService;

    /** Self-reference so each entry's @Transactional(REQUIRES_NEW) is applied via the Spring proxy. */
    @Autowired @Lazy
    private VideoSubscriptionLifecycleListener self;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSubscriptionExpired(SubscriptionExpiredEvent event) {
        if (event.getSubscriberId() == null) {
            log.warn("[SUB_LIFECYCLE] null subscriberId — skipping");
            return;
        }
        String tier = event.getSubscriptionTier();
        if (tier == null) {
            log.warn("[SUB_LIFECYCLE] null subscriptionTier for subscriberId={} — treating as non-YEARLY (Path B)",
                event.getSubscriberId());
        }
        SubscriptionLifecycleOutbox entry = new SubscriptionLifecycleOutbox();
        entry.setSubscriberId(event.getSubscriberId());
        entry.setSubscriptionTier(tier != null ? tier : "MONTHLY");
        entry.setExpiredAt(event.getExpiredAt());
        entry.setStatus("PENDING");
        outboxRepository.save(entry);
    }

    /**
     * skillars-deferred-120 AC1 sizing basis: {@code processOutbox} is the only outbox-shaped
     * scheduler in the codebase with no locking select, no claim/status-flip, and a backing entity
     * ({@link SubscriptionLifecycleOutbox}) with no {@code @Version} column — two concurrent
     * invocations (only reachable once this deployment scales to multiple instances; {@code
     * fixedDelay} cannot overlap itself within one instance) can each {@code merge} a stale detached
     * snapshot over the other's already-committed {@code DEAD_LETTER}/{@code PROCESSED} status.
     * {@code @SchedulerLock} closes this entirely, mirroring {@code skillars-deferred-118} AC3's
     * identical reasoning for {@code RadarCompositeDlqProcessor}'s same bug class.
     *
     * <p>Worst case (corrected by code review 2026-09-17, Decision 4 — an earlier draft assumed at
     * most one page of Path B work per entry, which is false; see {@link #MAX_PAGES_PER_ENTRY}): 100
     * entries ({@code findTop100By...}) × {@code MAX_PAGES_PER_ENTRY} (10) × one full page (default
     * {@code platform.video.lifecycle.batch_size} = 100) of Path B's {@code
     * blockForSubscriptionExpiry} calls = 100,000 calls; each is a single {@code findById}+
     * {@code save} DB round trip with no external I/O — 30ms/call generous worst case under lock
     * contention gives 100,000 × 0.03s = 3,000s (50 min). {@code PT1H} gives real margin above that.
     * {@code lockAtLeastFor} is deliberately NOT the {@code PT2M} used by the 5-minute-{@code
     * fixedDelay} siblings — this scheduler's own cadence is 60s, so {@code PT30S} (mirroring
     * {@code RadarCompositeDlqProcessor}'s identical 60s-cadence reasoning) sits comfortably below it
     * while still guarding the pathological fast-fail-and-immediately-refire edge case.
     */
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "VideoSubscriptionLifecycleListener_processOutbox",
                   lockAtMostFor = "PT1H", lockAtLeastFor = "PT30S")
    public void processOutbox() {
        // skillars-deferred-107 AC2: 0/neg → outbox never drains or attempt math underflows.
        int maxAttempts = (int) configService.getBoundedLong("platform.video.lifecycle.outbox_max_attempts", 1L, 100L);

        List<SubscriptionLifecycleOutbox> pending =
            outboxRepository.findTop100ByStatusAndAttemptsLessThanOrderByCreatedAtAsc("PENDING", maxAttempts);

        for (SubscriptionLifecycleOutbox entry : pending) {
            // Each entry commits independently — one failure never rolls back sibling entries
            self.processAndSaveEntry(entry, maxAttempts);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processAndSaveEntry(SubscriptionLifecycleOutbox entry, int maxAttempts) {
        // skillars-deferred-107 AC2: 0 → processEntry makes no progress; huge → load spike.
        // skillars-deferred-107 code review: default 100 (matches VideoLifecycleScheduler) so a
        // missing seed falls back rather than throwing IllegalStateException here — same key, one contract.
        int batchSize = configService.getBoundedInt("platform.video.lifecycle.batch_size", 100, 1, 10000);
        entry.setAttempts(entry.getAttempts() + 1);
        try {
            boolean fullyCompleted = processEntry(entry, batchSize);
            if (fullyCompleted) {
                entry.setStatus("PROCESSED");
                entry.setProcessedAt(Instant.now());
            } else {
                // skillars-deferred-120 code review (2026-09-17, Decision 4): MAX_PAGES_PER_ENTRY was
                // hit — not a failure. Undo the attempt increment above and leave status untouched
                // (still PENDING) so this attempt costs the subscriber nothing toward maxAttempts;
                // see MAX_PAGES_PER_ENTRY's own Javadoc for why counting it would be wrong.
                entry.setAttempts(entry.getAttempts() - 1);
            }
        } catch (Exception e) {
            log.error("[SUB_LIFECYCLE_FAILED id={} attempt={}]", entry.getId(), entry.getAttempts(), e);
            if (entry.getAttempts() >= maxAttempts) {
                entry.setStatus("DEAD_LETTER");
                entry.setLastError(e.getMessage());
                log.error("[SUB_LIFECYCLE_DEAD_LETTER id={} subscriberId={}] Max attempts reached — manual remediation required",
                    entry.getId(), entry.getSubscriberId());
            }
        }
        outboxRepository.save(entry);
    }

    /**
     * @return {@code true} if this attempt fully completed the entry's work; {@code false} if
     *     {@link #MAX_PAGES_PER_ENTRY} was hit and Path B work remains for a future attempt (see that
     *     constant's own Javadoc — this must NOT be treated as a failure by the caller).
     */
    private boolean processEntry(SubscriptionLifecycleOutbox entry, int batchSize) {
        boolean isYearly = "YEARLY".equalsIgnoreCase(entry.getSubscriptionTier());
        // player video ownerId is stored as Long.toString() decimal string
        String ownerId = entry.getSubscriberId().toString();

        if (isYearly) {
            // Path A: bulk-reset lifecycle_locked_at on all BLOCKED videos so the ARCHIVED clock pauses.
            // findBlockedReadyByOwner filters only by access_state='BLOCKED' and does not filter on
            // lifecycle_locked_at, so a paginated loop here would never terminate. The bulk reset is
            // sufficient to achieve FR-PAY-008 (pause the ARCHIVED clock for all BLOCKED videos).
            videoRepository.resetLifecycleLockedAt(ownerId);
            log.info("[SUB_LIFECYCLE_PATH_A_RESET subscriberId={}]", entry.getSubscriberId());
            return true;
        } else {
            // Path B: block ACTIVE/READY videos if no other active subscription covers the player
            if (!playerSubscriptionQueryPort.hasAnyActiveSubscription(entry.getSubscriberId())) {
                List<com.softropic.skillars.platform.video.repo.Video> active;
                int pages = 0;
                do {
                    // skillars-deferred-120 code review (2026-09-17, Decision 4): bound this
                    // subscriber's per-attempt work so lockAtMostFor has a real worst case (see
                    // MAX_PAGES_PER_ENTRY's own Javadoc for why this returns false rather than
                    // throwing — throwing would wrongly consume one of only 5 default attempts).
                    if (pages >= MAX_PAGES_PER_ENTRY) {
                        log.warn("[SUB_LIFECYCLE_PATH_B_CAPPED subscriberId={}] hit MAX_PAGES_PER_ENTRY "
                            + "({}) — {} page(s) blocked this attempt, remainder left PENDING for the "
                            + "next scheduler tick without consuming an attempt",
                            entry.getSubscriberId(), MAX_PAGES_PER_ENTRY, pages);
                        return false;
                    }
                    pages++;
                    active = videoRepository.findActiveReadyByOwner(ownerId, batchSize);
                    for (com.softropic.skillars.platform.video.repo.Video video : active) {
                        videoLifecycleService.blockForSubscriptionExpiry(video.getId(), Instant.now());
                    }
                } while (!active.isEmpty());
                return true;
            } else {
                log.debug("[SUB_LIFECYCLE_SKIPPED_CONCURRENT_SUB subscriberId={}]", entry.getSubscriberId());
                return true;
            }
        }
    }
}
