package com.softropic.skillars.platform.messaging.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;


import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// ID range: 9350x — reserved for messaging retention repository tests
class MessageRetentionRepositoryIT extends AbstractIntegrationTest {

    private static final long CONVERSATION_ID           = 9350_000_001L;
    private static final long MESSAGE_WITH_REPORT_ID    = 9350_000_002L;
    private static final long MESSAGE_WITHOUT_REPORT_ID = 9350_000_003L;
    private static final long MESSAGE_REPORT_ID         = 9350_000_004L;
    private static final long ORPHAN_CONVERSATION_ID    = 9350_000_005L;
    private static final long REPORTED_ORPHAN_CONVERSATION_ID = 9350_000_006L;
    private static final long CONVERSATION_REPORT_ID    = 9350_000_007L;
    private static final long SENDER_ID                 = 9350_000_010L;
    private static final long REPORTER_ID                = 9350_000_011L;

    @Autowired private MessageRepository messageRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;


    @Test
    void retention_skipsMessagesWithOpenReports() {
        Instant cutoff = Instant.now().minus(400, ChronoUnit.DAYS);
        Instant oldCreatedAt = cutoff.minus(10, ChronoUnit.DAYS);

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO messaging.conversations (id, coach_id, player_id, parent_id, status, created_at, last_message_at) " +
                    "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                CONVERSATION_ID, UUID.randomUUID(), SENDER_ID, REPORTER_ID,
                Timestamp.from(oldCreatedAt), Timestamp.from(oldCreatedAt));

            jdbcTemplate.update(
                "INSERT INTO messaging.messages (id, conversation_id, sender_id, sender_role, content, moderation_status, created_at) " +
                    "VALUES (?, ?, ?, 'COACH', 'Reported content', 'UNDER_REVIEW', ?)",
                MESSAGE_WITH_REPORT_ID, CONVERSATION_ID, SENDER_ID, Timestamp.from(oldCreatedAt));

            jdbcTemplate.update(
                "INSERT INTO messaging.messages (id, conversation_id, sender_id, sender_role, content, moderation_status, created_at) " +
                    "VALUES (?, ?, ?, 'COACH', 'Unreported content', 'APPROVED', ?)",
                MESSAGE_WITHOUT_REPORT_ID, CONVERSATION_ID, SENDER_ID, Timestamp.from(oldCreatedAt));

            jdbcTemplate.update(
                "INSERT INTO messaging.message_reports (id, message_id, reported_by, reason, status, created_at) " +
                    "VALUES (?, ?, ?, 'HARASSMENT', 'OPEN', ?)",
                MESSAGE_REPORT_ID, MESSAGE_WITH_REPORT_ID, REPORTER_ID, Timestamp.from(oldCreatedAt));

            return null;
        });

        int deleted = transactionTemplate.execute(status ->
            messageRepository.deleteOldMessagesWithNoOpenReports(cutoff));

        assertThat(deleted).isEqualTo(1);
        assertThat(messageRepository.findById(MESSAGE_WITH_REPORT_ID)).isPresent();
        assertThat(messageRepository.findById(MESSAGE_WITHOUT_REPORT_ID)).isEmpty();
    }

    @Test
    void orphanConversationCleanup_deletesNullLastMessageAt() {
        Instant cutoff = Instant.now().minus(400, ChronoUnit.DAYS);
        Instant oldCreatedAt = cutoff.minus(10, ChronoUnit.DAYS);

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO messaging.conversations (id, coach_id, player_id, parent_id, status, created_at, last_message_at) " +
                    "VALUES (?, ?, ?, ?, 'ACTIVE', ?, NULL)",
                ORPHAN_CONVERSATION_ID, UUID.randomUUID(), SENDER_ID, REPORTER_ID, Timestamp.from(oldCreatedAt));
            return null;
        });

        int deleted = transactionTemplate.execute(status ->
            conversationRepository.deleteOrphanConversations(cutoff));

        assertThat(deleted).isEqualTo(1);
        assertThat(conversationRepository.findById(ORPHAN_CONVERSATION_ID)).isEmpty();
    }

    @Test
    void orphanConversationCleanup_skipsConversationsWithOpenReports() {
        Instant cutoff = Instant.now().minus(400, ChronoUnit.DAYS);
        Instant oldCreatedAt = cutoff.minus(10, ChronoUnit.DAYS);

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO messaging.conversations (id, coach_id, player_id, parent_id, status, created_at, last_message_at) " +
                    "VALUES (?, ?, ?, ?, 'ACTIVE', ?, NULL)",
                REPORTED_ORPHAN_CONVERSATION_ID, UUID.randomUUID(), SENDER_ID, REPORTER_ID, Timestamp.from(oldCreatedAt));

            jdbcTemplate.update(
                "INSERT INTO messaging.conversation_reports (id, conversation_id, reported_by, reason, status, created_at) " +
                    "VALUES (?, ?, ?, 'HARASSMENT', 'OPEN', ?)",
                CONVERSATION_REPORT_ID, REPORTED_ORPHAN_CONVERSATION_ID, REPORTER_ID, Timestamp.from(oldCreatedAt));

            return null;
        });

        int deleted = transactionTemplate.execute(status ->
            conversationRepository.deleteOrphanConversations(cutoff));

        assertThat(deleted).isEqualTo(0);
        assertThat(conversationRepository.findById(REPORTED_ORPHAN_CONVERSATION_ID)).isPresent();
    }
}
