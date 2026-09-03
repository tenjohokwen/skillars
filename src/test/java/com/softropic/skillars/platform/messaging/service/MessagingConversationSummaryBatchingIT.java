package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.messaging.contract.ConversationSummaryDto;
import com.softropic.skillars.platform.security.SecurityIT;

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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-90 AC13 (3-layer review): real-database, multi-row coverage for the two native
 * queries that replaced the per-conversation N+1 in {@code MessagingService.toSummary} —
 * {@code MessageRepository.findLatestApprovedPerConversation} ({@code SELECT DISTINCT ON …}) and
 * {@code countUnreadPerConversation} (the {@code CASE :role … COALESCE(…, epoch)} per-role unread
 * count). {@code MessagingServiceTest} covers the batched-call structure with Mockito
 * ({@code verify(times(1))} / {@code verify(never())}); this pins the SQL's <em>results</em> against
 * a fixture with more than one conversation, both roles, and every row-exclusion the queries apply.
 *
 * <p>The O(1)-in-row-count assertion stays in {@code MessagingServiceTest}: proving it here would
 * need {@code hibernate.generate_statistics=true}, i.e. a {@code @TestPropertySource} that
 * {@code IntegrationTestConventionTest} fails the build on because it forks the shared context.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class MessagingConversationSummaryBatchingIT extends AbstractIntegrationTest {

    @Autowired private MessagingService messagingService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private static final long PARENT_USER_ID = 9600000001L;
    private static final long COACH_USER_ID  = 9600000010L;
    private static final long PLAYER1_ID     = 9600000002L;
    private static final long PLAYER2_ID     = 9600000003L;

    private static final long CONV1_ID = 9600001L; // coach ↔ player1
    private static final long CONV2_ID = 9600002L; // coach ↔ player2

    // Anchor + offsets so every timestamp below is unambiguous relative to the *_last_read_at marks.
    // Truncated to millis: Postgres timestamptz keeps only microseconds, so an un-truncated
    // Instant.now() would not round-trip equal to the DTO's lastMessageAt.
    private final Instant base = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MILLIS);

    // CONV1: coach has read up to `base`; parent has never read (→ COALESCE epoch branch for PARENT).
    private final Instant c1m1 = base.minusSeconds(600); // parent, APPROVED, before coach read  → read
    private final Instant c1m2 = base.plusSeconds(60);   // parent, APPROVED, after coach read   → coach-unread
    private final Instant c1m3 = base.plusSeconds(120);  // parent, APPROVED, after coach read   → coach-unread
    private final Instant c1m4 = base.plusSeconds(180);  // coach,  APPROVED  → latest approved; coach's own (not unread for coach)
    private final Instant c1m5 = base.plusSeconds(240);  // parent, PENDING   → excluded (not APPROVED)
    private final Instant c1m6 = base.plusSeconds(300);  // parent, APPROVED but deleted_at set → excluded

    // CONV2: coach has never read (→ COALESCE epoch branch for COACH); parent has read past the end.
    private final Instant c2m1 = base.plusSeconds(400);  // parent, APPROVED → coach-unread
    private final Instant c2m2 = base.plusSeconds(500);  // parent, APPROVED → coach-unread
    private final Instant c2m3 = base.plusSeconds(600);  // coach,  APPROVED → latest approved
    private final Instant c1LastMessageAt = c1m4;
    private final Instant c2LastMessageAt = c2m3;
    private final Instant parentReadConv2 = base.plusSeconds(10_000); // after every CONV2 message

    private UUID coachProfileId;

    @BeforeEach
    void seed() {
        coachProfileId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            insertUser(PARENT_USER_ID, "d90ac13.parent@skillars-test.com", "PARENT");
            insertUser(COACH_USER_ID, "d90ac13.coach@skillars-test.com", "COACH");

            // 15-year-old players → AgePolicyService resolves SUPERVISED (parent has oversight
            // access), so the PARENT branch of getConversations keeps both conversations.
            insertPlayer(PLAYER1_ID, "D90 Player One");
            insertPlayer(PLAYER2_ID, "D90 Player Two");

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles "
                    + "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) "
                    + "VALUES (?, ?, 'D90 Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            insertConversation(CONV1_ID, PLAYER1_ID, c1LastMessageAt, base, null);
            insertConversation(CONV2_ID, PLAYER2_ID, c2LastMessageAt, null, parentReadConv2);

            insertMessage(1, CONV1_ID, PARENT_USER_ID, "PARENT", "c1 parent read", "APPROVED", c1m1, null);
            insertMessage(2, CONV1_ID, PARENT_USER_ID, "PARENT", "c1 parent unread A", "APPROVED", c1m2, null);
            insertMessage(3, CONV1_ID, PARENT_USER_ID, "PARENT", "c1 parent unread B", "APPROVED", c1m3, null);
            insertMessage(4, CONV1_ID, COACH_USER_ID, "COACH", "c1 coach latest", "APPROVED", c1m4, null);
            insertMessage(5, CONV1_ID, PARENT_USER_ID, "PARENT", "c1 pending", "PENDING", c1m5, null);
            insertMessage(6, CONV1_ID, PARENT_USER_ID, "PARENT", "c1 deleted", "APPROVED", c1m6, c1m6);

            insertMessage(7, CONV2_ID, PARENT_USER_ID, "PARENT", "c2 parent A", "APPROVED", c2m1, null);
            insertMessage(8, CONV2_ID, PARENT_USER_ID, "PARENT", "c2 parent B", "APPROVED", c2m2, null);
            insertMessage(9, CONV2_ID, COACH_USER_ID, "COACH", "c2 coach latest", "APPROVED", c2m3, null);
            return null;
        });
    }

    @Test
    void getConversations_coachRole_multipleConversations_batchedNativeQueriesMatchPerRowSemantics() {
        Map<Long, ConversationSummaryDto> byId = index(messagingService.getConversations(COACH_USER_ID, "COACH"));

        assertThat(byId).containsOnlyKeys(CONV1_ID, CONV2_ID);

        // findLatestApprovedPerConversation (DISTINCT ON): newest APPROVED, non-deleted message per
        // conversation — NOT the PENDING one, NOT the deleted one, and independently per conversation.
        assertThat(byId.get(CONV1_ID).lastMessagePreview()).isEqualTo("c1 coach latest");
        assertThat(byId.get(CONV2_ID).lastMessagePreview()).isEqualTo("c2 coach latest");
        // lastMessageAt is the conversation column, not the message.
        assertThat(byId.get(CONV1_ID).lastMessageAt()).isEqualTo(c1LastMessageAt);
        assertThat(byId.get(CONV2_ID).lastMessageAt()).isEqualTo(c2LastMessageAt);

        // countUnreadPerConversation for role COACH: APPROVED, not deleted, sender <> COACH_USER_ID,
        // created_at > coach_last_read_at (CONV1) / > epoch when the column is NULL (CONV2).
        assertThat(byId.get(CONV1_ID).unreadCount()).isEqualTo(2L); // c1m2, c1m3 (not c1m4 own, not c1m5 pending, not c1m6 deleted)
        assertThat(byId.get(CONV2_ID).unreadCount()).isEqualTo(2L); // c2m1, c2m2 (coach_last_read_at NULL → epoch)
    }

    @Test
    void getConversations_parentRole_usesParentLastReadColumn_andEpochWhenNull() {
        Map<Long, ConversationSummaryDto> byId = index(messagingService.getConversations(PARENT_USER_ID, "PARENT"));

        assertThat(byId).containsOnlyKeys(CONV1_ID, CONV2_ID);
        assertThat(byId.get(CONV1_ID).lastMessagePreview()).isEqualTo("c1 coach latest");
        assertThat(byId.get(CONV2_ID).lastMessagePreview()).isEqualTo("c2 coach latest");

        // CASE :role WHEN 'PARENT' → parent_last_read_at. CONV1: NULL → epoch → every APPROVED,
        // non-deleted message not sent by the parent counts → only c1m4 (coach). CONV2:
        // parent_last_read_at is after every message → 0.
        assertThat(byId.get(CONV1_ID).unreadCount()).isEqualTo(1L);
        assertThat(byId.get(CONV2_ID).unreadCount()).isEqualTo(0L);
    }

    // ── helpers ──

    private static Map<Long, ConversationSummaryDto> index(List<ConversationSummaryDto> list) {
        return list.stream().collect(java.util.stream.Collectors.toMap(ConversationSummaryDto::conversationId, d -> d));
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

    private void insertConversation(long id, long playerId, Instant lastMessageAt,
                                    Instant coachLastReadAt, Instant parentLastReadAt) {
        jdbcTemplate.update(
            "INSERT INTO messaging.conversations "
                + "(id, coach_id, player_id, parent_id, status, created_at, last_message_at, "
                + " coach_last_read_at, parent_last_read_at, player_last_read_at) "
                + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, NULL)",
            id, coachProfileId, playerId, PARENT_USER_ID,
            Timestamp.from(base.minusSeconds(1000)),
            Timestamp.from(lastMessageAt),
            coachLastReadAt == null ? null : Timestamp.from(coachLastReadAt),
            parentLastReadAt == null ? null : Timestamp.from(parentLastReadAt));
    }

    private void insertMessage(long id, long conversationId, long senderId, String senderRole,
                               String content, String moderationStatus, Instant createdAt, Instant deletedAt) {
        jdbcTemplate.update(
            "INSERT INTO messaging.messages "
                + "(id, conversation_id, sender_id, sender_role, content, moderation_status, "
                + " delivered_at, created_at, deleted_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, conversationId, senderId, senderRole, content, moderationStatus,
            Timestamp.from(createdAt), Timestamp.from(createdAt),
            deletedAt == null ? null : Timestamp.from(deletedAt));
    }
}
