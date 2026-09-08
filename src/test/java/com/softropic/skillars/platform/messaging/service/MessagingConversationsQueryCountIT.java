package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.security.SecurityIT;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-102 AC14 — measure and pin {@code MessagingService.getConversations}.
 *
 * The ledger flagged an N+1 query issue but verification during story creation found it was already
 * closed: {@code getConversations} is O(1) queries since deferred-90 AC13 + deferred-91 AC19
 * (batched context building). This IT locks that constant query count regardless of conversation count.
 *
 * Test seeds a parent with K conversations, captures the JDBC statement count, then doubles K and
 * asserts the statement count is unchanged (constant time).
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class MessagingConversationsQueryCountIT extends AbstractIntegrationTest {

    /** Expected constant query count for getConversations, regardless of conversation count. */
    private static final int EXPECTED_QUERY_COUNT = 5; // Exact count from tracing; adjust if implementation changes

    private static final long PLAYER_USER_ID = 9270_100_001L;

    @Autowired private MessagingService messagingService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private Long playerId;
    private Statistics statistics;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        playerId = 1000_000_001L;
        transactionTemplate.execute(status -> {
            // Create player profile
            jdbcTemplate.update(
                "INSERT INTO main.player (id, user_id, first_name, status, created_date) "
                + "VALUES (?, ?, 'Test', 'ACTIVE', ?) ON CONFLICT DO NOTHING",
                playerId, PLAYER_USER_ID, Timestamp.from(Instant.now()));
            return null;
        });
    }

    @Test
    void getConversations_queriesRemainConstantAcrossConversationCounts() {
        int k = 5;
        seedConversations(k);

        // Measure query count with K conversations
        statistics.clear();
        transactionTemplate.execute(status -> {
            messagingService.getConversations(playerId, PageRequest.of(0, 10));
            return null;
        });
        long countK = statistics.getPreparedStatementCount();

        // Measure query count with 2K conversations
        seedConversations(k); // Add K more
        statistics.clear();
        transactionTemplate.execute(status -> {
            messagingService.getConversations(playerId, PageRequest.of(0, 10));
            return null;
        });
        long count2K = statistics.getPreparedStatementCount();

        // Assert counts are identical (constant query complexity)
        assertThat(countK).as("query count should be constant regardless of conversation count")
            .isEqualTo(count2K)
            .isEqualTo(EXPECTED_QUERY_COUNT);
    }

    private void seedConversations(int count) {
        for (int i = 0; i < count; i++) {
            UUID conversationId = UUID.randomUUID();
            long otherId = 2000_000_000L + i;
            transactionTemplate.execute(status -> {
                // Create conversation
                jdbcTemplate.update(
                    "INSERT INTO messaging.conversation (id, player_id, other_player_id, other_coach_id, created_date) "
                    + "VALUES (?, ?, ?, NULL, ?)",
                    conversationId.toString(), playerId, otherId, Timestamp.from(Instant.now()));
                // Add a message
                jdbcTemplate.update(
                    "INSERT INTO messaging.message (id, conversation_id, sender_player_id, approved_at, created_date) "
                    + "VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), conversationId.toString(), otherId,
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
                return null;
            });
        }
    }
}
