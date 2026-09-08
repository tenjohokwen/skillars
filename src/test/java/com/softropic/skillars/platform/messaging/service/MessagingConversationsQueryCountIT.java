package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.messaging.contract.ConversationSummaryDto;
import com.softropic.skillars.platform.security.SecurityIT;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-102 AC14 — pin the query cost of {@code MessagingService.getConversations}.
 *
 * <p>The ledger ({@code skillars-8-1} D2) flagged an N+1 here. Tracing at HEAD found it was already
 * closed incrementally by {@code skillars-deferred-90} AC13 + {@code skillars-deferred-91} AC19:
 * {@code getConversations} selects the conversation list once, then builds a single
 * {@code SummaryContext} with fully batched reads (player names, coach names, latest-approved
 * message, unread counts), and the 4-arg {@code toSummary} reads only from that context — zero
 * per-row queries.
 *
 * <p>This IT locks that: it measures the JDBC statement count for one {@code getConversations} call
 * (Hibernate statistics enabled programmatically so no {@code @TestPropertySource} forks the shared
 * context — same approach as {@code CoachPublicProfileQueryCountIT}) and asserts the count is
 * <strong>constant</strong> as the conversation count doubles. If a per-row lookup is ever
 * reintroduced, {@code with2N > withN} and this fails.
 *
 * <p>Uses the COACH role: its {@code getConversations} path has no age-policy resolution or
 * caller-profile lookup, so the only reads are the list select + the batched context — exactly what
 * must stay O(1).
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class MessagingConversationsQueryCountIT extends AbstractIntegrationTest {

    private static final long COACH_USER_ID   = 9102000010L;
    private static final long PARENT_USER_ID  = 9102000001L;
    private static final long FIRST_PLAYER_ID = 9102010000L;
    private static final long FIRST_CONV_ID   = 9102100000L;

    @Autowired private MessagingService messagingService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private UUID coachProfileId;
    private Statistics statistics;
    private int seededConversations;

    @BeforeEach
    void seed() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        coachProfileId = UUID.randomUUID();
        seededConversations = 0;
        transactionTemplate.execute(status -> {
            insertUser(COACH_USER_ID, "d102ac14.coach@skillars-test.com", "COACH");
            insertUser(PARENT_USER_ID, "d102ac14.parent@skillars-test.com", "PARENT");
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles "
                    + "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) "
                    + "VALUES (?, ?, 'D102 Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);
            return null;
        });
        seedConversations(3);
    }

    @AfterEach
    void tearDown() {
        statistics.setStatisticsEnabled(false);
    }

    @Test
    void getConversations_queryCountIsConstantAsConversationCountGrows() {
        long withN = measureGetConversations(3);

        seedConversations(3); // now 6

        long with2N = measureGetConversations(6);

        assertThat(with2N)
            .as("no N+1: getConversations must not issue more queries as the conversation list grows")
            .isEqualTo(withN);
    }

    private long measureGetConversations(int expectedRows) {
        statistics.clear();
        List<ConversationSummaryDto> result = transactionTemplate.execute(
            status -> messagingService.getConversations(COACH_USER_ID, "COACH"));
        assertThat(result).hasSize(expectedRows);
        return statistics.getPrepareStatementCount();
    }

    private void seedConversations(int count) {
        transactionTemplate.execute(status -> {
            for (int i = 0; i < count; i++) {
                int idx = seededConversations + i;
                long playerId = FIRST_PLAYER_ID + idx;
                long convId = FIRST_CONV_ID + idx;
                insertPlayer(playerId, "D102 Player " + idx);
                jdbcTemplate.update(
                    "INSERT INTO messaging.conversations "
                        + "(id, coach_id, player_id, parent_id, status, created_at, last_message_at) "
                        + "VALUES (?, ?, ?, ?, 'ACTIVE', now(), now())",
                    convId, coachProfileId, playerId, PARENT_USER_ID);
                jdbcTemplate.update(
                    "INSERT INTO messaging.messages "
                        + "(id, conversation_id, sender_id, sender_role, content, moderation_status, created_at) "
                        + "VALUES (?, ?, ?, 'PARENT', 'hello', 'APPROVED', now())",
                    convId, convId, PARENT_USER_ID);
            }
            return null;
        });
        seededConversations += count;
    }

    private void insertUser(long id, String email, String role) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" "
                + "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, "
                + "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, "
                + "activated, locked, login, login_id_type, password_hash, otp_enabled, "
                + "skillars_role, verification_status) "
                + "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, "
                + "'ACTIVE', '1988-01-01', ?, 'Test', 'OTHER', 'en', 'x', 'DE', ?, "
                + "true, false, ?, 'EMAIL', 'x', false, ?, 'BASIC_VERIFIED')",
            id, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, "70" + (id % 100000000L), email, role);
    }

    private void insertPlayer(long id, String name) {
        jdbcTemplate.update(
            "INSERT INTO main.player_profiles "
                + "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) "
                + "VALUES (?, ?, ?, 'MIDFIELDER', 'AGE_13_17', ?, false, ?, 'system')",
            id, name, Date.valueOf(LocalDate.now().minusYears(15)),
            PARENT_USER_ID, Timestamp.from(Instant.now()));
    }
}
