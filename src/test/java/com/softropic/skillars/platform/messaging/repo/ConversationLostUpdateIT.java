package com.softropic.skillars.platform.messaging.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.messaging.contract.ConversationStatus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-173 (RS10): two transactions that load the same conversation and each change different
 * columns must not undo each other. Each case loads the row, lets another transaction commit a change to a
 * different column, then saves and commits its own change, the order in which getMessages / sendMessage raced
 * reportConversation.
 */
class ConversationLostUpdateIT extends AbstractIntegrationTest {

    private static final long CONVERSATION_ID = 9173_000_001L;
    private static final long PLAYER_ID = 9173_000_002L;
    private static final long PARENT_ID = 9173_000_003L;

    @Autowired private ConversationRepository conversationRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate otherTransaction;

    @BeforeEach
    void setUp() {
        otherTransaction = new TransactionTemplate(transactionManager);
        otherTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactionTemplate.executeWithoutResult(s -> jdbcTemplate.update(
            "INSERT INTO messaging.conversations (id, coach_id, player_id, parent_id, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
            CONVERSATION_ID, UUID.randomUUID(), PLAYER_ID, PARENT_ID));
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(s ->
            jdbcTemplate.update("DELETE FROM messaging.conversations WHERE id = ?", CONVERSATION_ID));
    }

    @Test
    void aReadThatLoadedActive_doesNotUndoABlockCommittedBeforeItSaves() {
        Instant readAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        loadThenSave(c -> c.setParentLastReadAt(readAt), "UPDATE messaging.conversations SET status = 'BLOCKED' WHERE id = ?");

        Map<String, Object> row = row();
        assertThat(row.get("status")).isEqualTo(ConversationStatus.BLOCKED.name());
        assertThat(((Timestamp) row.get("parent_last_read_at")).toInstant()).isEqualTo(readAt);
    }

    @Test
    void aReport_doesNotUndoALastMessageAtCommittedBeforeItSaves() {
        Instant sentAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        loadThenSave(c -> c.setStatus(ConversationStatus.BLOCKED),
            "UPDATE messaging.conversations SET last_message_at = ? WHERE id = ?", Timestamp.from(sentAt));

        Map<String, Object> row = row();
        assertThat(row.get("status")).isEqualTo(ConversationStatus.BLOCKED.name());
        assertThat(((Timestamp) row.get("last_message_at")).toInstant()).isEqualTo(sentAt);
    }

    @Test
    void theCoachsAndTheParentsReadMarks_bothSurvive() {
        Instant coachRead = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant parentRead = coachRead.minusSeconds(5);

        loadThenSave(c -> c.setCoachLastReadAt(coachRead),
            "UPDATE messaging.conversations SET parent_last_read_at = ? WHERE id = ?", Timestamp.from(parentRead));

        Map<String, Object> row = row();
        assertThat(((Timestamp) row.get("coach_last_read_at")).toInstant()).isEqualTo(coachRead);
        assertThat(((Timestamp) row.get("parent_last_read_at")).toInstant()).isEqualTo(parentRead);
        assertThat(row.get("status")).isEqualTo(ConversationStatus.ACTIVE.name());
    }

    /** Loads the row, commits {@code concurrentSql} in another transaction, then applies {@code change} and saves. */
    private void loadThenSave(Consumer<Conversation> change, String concurrentSql, Object... concurrentArgs) {
        transactionTemplate.executeWithoutResult(s -> {
            Conversation c = conversationRepository.findById(CONVERSATION_ID).orElseThrow();
            Object[] args = new Object[concurrentArgs.length + 1];
            System.arraycopy(concurrentArgs, 0, args, 0, concurrentArgs.length);
            args[concurrentArgs.length] = CONVERSATION_ID;
            otherTransaction.executeWithoutResult(o -> jdbcTemplate.update(concurrentSql, args));
            change.accept(c);
            conversationRepository.save(c);
        });
    }

    private Map<String, Object> row() {
        return jdbcTemplate.queryForMap(
            "SELECT status, last_message_at, coach_last_read_at, parent_last_read_at FROM messaging.conversations WHERE id = ?",
            CONVERSATION_ID);
    }
}
