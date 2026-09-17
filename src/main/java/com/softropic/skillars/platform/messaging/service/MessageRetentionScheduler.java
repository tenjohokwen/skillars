package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.messaging.contract.MessagesPurgedEvent;
import com.softropic.skillars.platform.messaging.repo.ConversationRepository;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneOffset;

@Component
@RequiredArgsConstructor
@Slf4j
public class MessageRetentionScheduler {

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final ConfigService configService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    /**
     * skillars-deferred-118 AC3 sizing basis: unlike the 5-minute-cadence siblings, this job's own
     * work is two single bulk {@code DELETE ... WHERE ... NOT IN (subquery)} statements (see
     * {@link MessageRepository#deleteOldMessagesWithNoOpenReports} /
     * {@link ConversationRepository#deleteOrphanConversations}) — no per-row loop, so sizing is
     * bounded by table-scan + delete duration under contention, not row-count × per-item latency.
     * {@code lockAtMostFor = "PT30M"} gives generous margin for a heavy bulk delete against a large,
     * indexed-on-cutoff messages table under real production load. {@code lockAtLeastFor} is
     * deliberately re-derived rather than copied from the {@code PT2M} used by every 5-minute-
     * {@code fixedDelay} sibling (per this story's own Dev Notes): with a once-daily cron there is no
     * tight fixed-delay window to protect against, so {@code PT1M} is sized purely as a defensive
     * floor against the pathological fast-fail-and-immediately-refire edge case, with real margin
     * over this job's typical sub-second runtime, rather than blocking a legitimate manual re-trigger
     * for a full two minutes.
     */
    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "MessageRetentionScheduler_runRetention",
                   lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void runRetention() {
        // skillars-deferred-107 AC2: a stored 0/negative would make the cutoff Instant.now() (or the
        // future), so deleteOldMessagesWithNoOpenReports would delete EVERY message with no open
        // report on the next run. Data-destructive → clamps to 24 + WARN here, and
        // ConfigStartupAssertion refuses to boot on a bad stored value (failFast key).
        int retentionMonths = configService.getBoundedInt("platform.message_retention_months", 24, 1, 600);
        Instant cutoff = Instant.now().atZone(ZoneOffset.UTC).minusMonths(retentionMonths).toInstant();

        int messageCount;
        try {
            messageCount = transactionTemplate.execute(status ->
                messageRepository.deleteOldMessagesWithNoOpenReports(cutoff));
        } catch (Exception e) {
            log.error("Retention scheduler: message deletion failed", e);
            return;
        }

        // Let admin close any alert now pointing at a deleted message. Retention's own predicate
        // preserves messages carrying an open report, so MESSAGE_REPORT alerts are safe — but a
        // MODERATION_UNRESOLVED alert has no report row protecting it and would sit OPEN forever.
        // Published rather than called directly: messaging must not import platform.admin.
        if (messageCount > 0) {
            try {
                eventPublisher.publishEvent(new MessagesPurgedEvent(messageCount));
            } catch (Exception e) {
                log.error("Retention scheduler: purge notification failed after deleting {} messages "
                    + "— orphaned admin alerts may remain OPEN", messageCount, e);
            }
        }

        int conversationCount = 0;
        try {
            conversationCount = transactionTemplate.execute(status ->
                conversationRepository.deleteOrphanConversations(cutoff));
        } catch (Exception e) {
            log.error("Retention scheduler: conversation cleanup failed", e);
        }

        log.info("Retention run complete: deleted {} messages, {} conversations",
            messageCount, conversationCount);
    }
}
