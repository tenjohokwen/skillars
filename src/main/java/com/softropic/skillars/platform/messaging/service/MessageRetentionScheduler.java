package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.messaging.contract.MessagesPurgedEvent;
import com.softropic.skillars.platform.messaging.repo.ConversationRepository;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Scheduled(cron = "0 0 2 * * *")
    public void runRetention() {
        int retentionMonths = configService.getInt("platform.message_retention_months", 24);
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
