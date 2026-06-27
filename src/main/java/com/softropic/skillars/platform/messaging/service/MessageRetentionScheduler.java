package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.messaging.repo.ConversationRepository;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final TransactionTemplate transactionTemplate;

    @Scheduled(cron = "0 0 2 * * *")
    public void runRetention() {
        int retentionMonths = configService.getInt("platform.message_retention_months", 24);
        Instant cutoff = Instant.now().atZone(ZoneOffset.UTC).minusMonths(retentionMonths).toInstant();

        int messageCount;
        try {
            messageCount = transactionTemplate.execute(status ->
                messageRepository.deleteExpiredMessages(cutoff));
        } catch (Exception e) {
            log.error("Retention scheduler: message deletion failed", e);
            return;
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
