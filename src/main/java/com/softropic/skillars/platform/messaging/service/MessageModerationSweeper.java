package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.messaging.contract.MessageHeldForReviewEvent;
import com.softropic.skillars.platform.messaging.contract.MessageModerationStatus;
import com.softropic.skillars.platform.messaging.repo.Message;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Recovers messages stranded in PENDING when the moderation verdict never landed —
 * {@code ModerationResultApplier.applyResult} throwing, or the JVM dying between the PENDING
 * commit and the verdict write. Nothing else reads PENDING: {@code MessageRetentionScheduler}
 * is the module's only other scheduler and deletes by age, not status.
 *
 * <p><strong>Fail-closed by design. Never approve, and never re-call Gemini.</strong> This class
 * does not know why the first verdict never landed — a retry would re-run a call that already
 * failed once with no diagnosis, and a SAFE result from it would deliver content that no human
 * and no successful moderation pass ever saw to a recipient who may be a minor. UNDER_REVIEW is
 * the correct terminal state: it is exactly what {@code applyResult} already writes for an
 * UNCERTAIN verdict, and the only status {@code AdminMessageService.approveMessage}/
 * {@code blockMessage} will act on, so a swept message becomes a queue item a human resolves.
 *
 * <p>A soft-deleted PENDING message is left alone, permanently — deletion already removes it
 * from every user-facing and moderation-facing read, so there is nothing to recover.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MessageModerationSweeper {

    static final String GRACE_MINUTES_KEY = "platform.messaging.moderation_orphan_grace_minutes";
    private static final long GRACE_DEFAULT_MINUTES = 30L;
    private static final long GRACE_MIN_MINUTES = 5L;
    private static final long GRACE_MAX_MINUTES = 1440L;

    private static final String SWEPT_COUNTER = "messaging.moderation.swept";

    /** Max messages recovered per run; the remainder is picked up on the next 10-minute tick. */
    static final int MAX_BATCH = 500;

    /** Alert reason for a message recovered by this sweeper — never moderated at all. */
    static final String HELD_REASON_SWEPT = "MODERATION_ORPHAN_SWEPT";

    private final MessageRepository messageRepository;
    private final ConfigService configService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    // lockAtMostFor is deliberately 2x the fixedDelay, not equal to it — see PaymentPendingSweeper
    // for the margin rationale (a run that merely reached its own next tick must not have its
    // lock expire mid-execution and let a second instance start an overlapping sweep).
    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "MessageModerationSweeper_sweep", lockAtMostFor = "PT20M", lockAtLeastFor = "PT2M")
    public void sweep() {
        long graceMinutes = configService.getBoundedLong(
            GRACE_MINUTES_KEY, GRACE_DEFAULT_MINUTES, GRACE_MIN_MINUTES, GRACE_MAX_MINUTES);
        Instant threshold = Instant.now().minus(Duration.ofMinutes(graceMinutes));

        // Capped, unlike PaymentPendingSweeper's unbounded select which this otherwise mirrors.
        // The scenario this class exists for — a sustained moderation outage — is exactly the one
        // that produces a large PENDING backlog, and here every swept row also raises an admin
        // alert. Uncapped, one run would load the whole backlog into heap, flood the moderation
        // queue, and risk outliving lockAtMostFor (letting a second instance start an overlapping
        // sweep). The 10-minute cadence drains whatever this run leaves behind.
        List<Message> stranded = transactionTemplate.execute(
            status -> messageRepository.findPendingOlderThan(threshold, PageRequest.of(0, MAX_BATCH)));
        if (stranded == null || stranded.isEmpty()) return;

        if (stranded.size() == MAX_BATCH) {
            log.warn("MessageModerationSweeper: batch cap of {} reached — more stranded messages "
                + "remain and will be picked up by the next run", MAX_BATCH);
        }
        log.info("MessageModerationSweeper: {} message(s) in PENDING older than {} minutes",
            stranded.size(), graceMinutes);

        for (Message candidate : stranded) {
            boolean swept = false;
            try {
                swept = Boolean.TRUE.equals(
                    transactionTemplate.execute(status -> sweepOne(candidate.getId())));
            } catch (Exception e) {
                log.error("MessageModerationSweeper failed on message {}", candidate.getId(), e);
            }
            // Counted after the transaction returns, not inside it: a rollback would otherwise
            // count a sweep that did not happen, and count the same message again on every retry —
            // over-reporting exactly during the outage this metric is watched for.
            if (swept) {
                Counter.builder(SWEPT_COUNTER).register(meterRegistry).increment();
            }
        }
    }

    /** @return true if this call actually transitioned the message to UNDER_REVIEW. */
    private boolean sweepOne(Long messageId) {
        // Re-read through findByIdForUpdate and re-assert both PENDING and undeleted: the query's
        // predicate is not enough on its own, since the row can change between select and sweep —
        // resolved by applyResult, or soft-deleted by the sender — in the window between the
        // select above and this transaction opening.
        Message message = messageRepository.findByIdForUpdate(messageId).orElse(null);
        if (message == null) return false;
        if (message.getModerationStatus() != MessageModerationStatus.PENDING || message.getDeletedAt() != null) {
            return false;
        }

        message.setModerationStatus(MessageModerationStatus.UNDER_REVIEW);
        messageRepository.save(message);

        // The listener runs REQUIRES_NEW, so the alert commits in its own transaction inside this
        // call; catching here keeps a failed alert from rolling back the recovery itself and
        // bouncing the message straight back to PENDING — the stranding this class exists to end.
        try {
            eventPublisher.publishEvent(new MessageHeldForReviewEvent(
                message.getId(), message.getConversationId(), HELD_REASON_SWEPT));
        } catch (Exception e) {
            log.error("Swept message {} to UNDER_REVIEW but failed to raise its "
                + "MODERATION_UNRESOLVED alert — it is recovered but not yet visible to admins",
                message.getId(), e);
        }

        log.info("Swept stranded PENDING message to UNDER_REVIEW: messageId={} conversationId={} "
                + "senderId={} createdAt={}",
            message.getId(), message.getConversationId(), message.getSenderId(), message.getCreatedAt());
        return true;
    }
}
