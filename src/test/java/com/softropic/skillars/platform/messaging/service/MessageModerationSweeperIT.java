package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.config.TestConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story deferred-16 AC2. Real Testcontainers integration: seeds messages directly via jdbcTemplate
 * (backdating created_at after insert, since the entity stamps it at send time) and drives the real
 * scheduled bean end-to-end, including the AC3 admin-alert side effect via the real event listener.
 *
 * <p><strong>ShedLock testing trap:</strong> invoking a {@code @SchedulerLock} method from a test
 * goes through the Spring proxy, so with {@code lockAtLeastFor = PT2M} every call after the first in
 * this class would be silently skipped. {@link #releaseSchedulerLock} (copied from
 * {@code BasePaymentIT}, since messaging ITs have no shared base class) is called before every
 * {@code sweep()} invocation to guard against that.
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class MessageModerationSweeperIT {

    private static final String LOCK_NAME = "MessageModerationSweeper_sweep";

    @Autowired private MessageModerationSweeper sweeper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    /**
     * Spied, not mocked — every test but the select/sweep-race case below uses the real query. Only
     * that one stubs {@code findPendingOlderThan}, to hand the sweeper the stale snapshot a real
     * race would have produced.
     */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.softropic.skillars.platform.messaging.repo.MessageRepository messageRepository;

    private static final long CONVERSATION_ID = 9870_000_001L;
    private static final UUID COACH_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO messaging.conversations (id, coach_id, player_id, parent_id, status, created_at, last_message_at) " +
                "VALUES (?, ?, 9870, 9871, 'ACTIVE', ?, ?)",
                CONVERSATION_ID, COACH_ID, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM admin.admin_alerts WHERE reference_type = 'MESSAGE' AND reference_id IN " +
                "(SELECT id::text FROM messaging.messages WHERE conversation_id = ?)", CONVERSATION_ID);
            jdbcTemplate.update("DELETE FROM messaging.messages WHERE conversation_id = ?", CONVERSATION_ID);
            jdbcTemplate.update("DELETE FROM messaging.conversations WHERE id = ?", CONVERSATION_ID);
            return null;
        });
    }

    private static final java.util.concurrent.atomic.AtomicLong NEXT_MESSAGE_ID =
        new java.util.concurrent.atomic.AtomicLong(9870_100_001L);

    private long seedMessage(String moderationStatus, Instant createdAt, Instant deletedAt) {
        long id = NEXT_MESSAGE_ID.getAndIncrement();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO messaging.messages (id, conversation_id, sender_id, sender_role, content, " +
                "moderation_status, created_at, deleted_at) VALUES (?, ?, 9872, 'COACH', 'sweeper test content', ?, ?, ?)",
                id, CONVERSATION_ID, moderationStatus, Timestamp.from(createdAt),
                deletedAt != null ? Timestamp.from(deletedAt) : null);
            return null;
        });
        return id;
    }

    private String moderationStatusOf(long messageId) {
        return jdbcTemplate.queryForObject(
            "SELECT moderation_status FROM messaging.messages WHERE id = ?", String.class, messageId);
    }

    /**
     * Queried by reference id rather than by paging {@code findByTypeAndStatus}. Page 0 of 100 over
     * a table every other IT writes to can push the row under test off the page, which would make
     * the negative assertions below pass for the wrong reason — silently disarming them.
     */
    private long openAlertCountFor(long messageId) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin.admin_alerts WHERE reference_id = ? AND type = 'MODERATION_UNRESOLVED' "
            + "AND status = 'OPEN'", Long.class, String.valueOf(messageId));
        return count == null ? 0 : count;
    }

    private String alertReasonFor(long messageId) {
        return jdbcTemplate.queryForObject(
            "SELECT reason FROM admin.admin_alerts WHERE reference_id = ? AND type = 'MODERATION_UNRESOLVED'",
            String.class, String.valueOf(messageId));
    }

    private void releaseSchedulerLock(String lockName) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE main.shedlock SET lock_until = now() - interval '1 minute' WHERE name = ?", lockName);
            return null;
        });
    }

    @Test
    void sweep_pendingPastGrace_becomesUnderReview_raisesOneAlert_notReselectedOnSecondRun() {
        long strandedId = seedMessage("PENDING", Instant.now().minusSeconds(3600), null);

        releaseSchedulerLock(LOCK_NAME);
        sweeper.sweep();

        assertThat(moderationStatusOf(strandedId)).isEqualTo("UNDER_REVIEW");
        assertThat(openAlertCountFor(strandedId)).isEqualTo(1);
        // The reason reaches the admin queue summary and tells the reviewer this content was never
        // assessed at all, as opposed to assessed-and-uncertain.
        assertThat(alertReasonFor(strandedId)).isEqualTo(MessageModerationSweeper.HELD_REASON_SWEPT);

        // Second run must not re-alert or re-touch an already-resolved (now UNDER_REVIEW, not PENDING) message
        releaseSchedulerLock(LOCK_NAME);
        sweeper.sweep();
        assertThat(openAlertCountFor(strandedId)).isEqualTo(1);
    }

    /**
     * Task 1's AC2 probe, which the story required be captured but which no shipped test asserted:
     * a message stranded in PENDING is <em>asymmetrically</em> visible. The conversation read path
     * returns it — so its sender sees it as sent — while the recipient's unread count and the
     * conversation preview both skip it, because those filter on APPROVED. That asymmetry, not the
     * status value on its own, is the harm the sweeper exists to end, and it is what makes
     * "recover to UNDER_REVIEW" the right answer rather than "leave it".
     */
    @Test
    void strandedPending_isVisibleToSenderButInvisibleToRecipient_beforeSweep() {
        long strandedId = seedMessage("PENDING", Instant.now().minusSeconds(3600), null);
        var page = org.springframework.data.domain.PageRequest.of(0, 20);

        // Sender's view of the thread: present, with content.
        assertThat(messageRepository.findByConversationIdAndNotDeleted(CONVERSATION_ID, page).getContent())
            .extracting(com.softropic.skillars.platform.messaging.repo.Message::getId)
            .contains(strandedId);

        // Recipient's view: nothing to read, nothing to preview.
        assertThat(messageRepository.countUnread(CONVERSATION_ID, 9870L, Instant.now().minusSeconds(7200)))
            .isZero();
        assertThat(messageRepository.findLastApproved(CONVERSATION_ID, page).getContent()).isEmpty();

        // After the sweep it is UNDER_REVIEW — still not delivered, but now a queue item a human
        // can resolve rather than a row nothing in the system will ever look at again.
        releaseSchedulerLock(LOCK_NAME);
        sweeper.sweep();
        assertThat(moderationStatusOf(strandedId)).isEqualTo("UNDER_REVIEW");
        assertThat(openAlertCountFor(strandedId)).isEqualTo(1);
    }

    @Test
    void sweep_pendingInsideGrace_untouched() {
        long freshId = seedMessage("PENDING", Instant.now().minusSeconds(60), null);

        releaseSchedulerLock(LOCK_NAME);
        sweeper.sweep();

        assertThat(moderationStatusOf(freshId)).isEqualTo("PENDING");
    }

    @Test
    void sweep_alreadyApprovedPastGrace_untouched_noAlert() {
        long approvedId = seedMessage("APPROVED", Instant.now().minusSeconds(3600), null);

        releaseSchedulerLock(LOCK_NAME);
        sweeper.sweep();

        assertThat(moderationStatusOf(approvedId)).isEqualTo("APPROVED");
        assertThat(openAlertCountFor(approvedId)).isZero();
    }

    /**
     * Task 4 case (d) — the message resolves between the candidate select and the per-message
     * sweep. Previously covered only as a mocked unit case, where the "flip" was a second stub
     * object rather than a real row, so nothing exercised the pessimistic re-read against the
     * database. Here the candidate select returns the stale PENDING snapshot it genuinely saw a
     * moment earlier while the row itself is already APPROVED; only the locked re-read can tell
     * the difference. Reverting {@code sweepOne}'s guard makes this fail with UNDER_REVIEW.
     */
    @Test
    void sweep_messageFlipsToApprovedBetweenSelectAndSweep_guardedReReadLeavesItApproved() {
        long id = seedMessage("PENDING", Instant.now().minusSeconds(3600), null);

        com.softropic.skillars.platform.messaging.repo.Message staleSnapshot =
            new com.softropic.skillars.platform.messaging.repo.Message();
        staleSnapshot.setId(id);
        staleSnapshot.setConversationId(CONVERSATION_ID);
        staleSnapshot.setModerationStatus(
            com.softropic.skillars.platform.messaging.contract.MessageModerationStatus.PENDING);

        // The row resolves after the select returned it — an admin approve, or a straggling verdict.
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "UPDATE messaging.messages SET moderation_status = 'APPROVED' WHERE id = ?", id));

        org.mockito.Mockito.doReturn(List.of(staleSnapshot))
            .when(messageRepository).findPendingOlderThan(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        releaseSchedulerLock(LOCK_NAME);
        sweeper.sweep();

        assertThat(moderationStatusOf(id)).isEqualTo("APPROVED");
        assertThat(openAlertCountFor(id)).isZero();
    }

    @Test
    void sweep_softDeletedPendingPastGrace_untouched_noAlert_notReselected() {
        long deletedId = seedMessage("PENDING", Instant.now().minusSeconds(3600), Instant.now());

        releaseSchedulerLock(LOCK_NAME);
        sweeper.sweep();

        assertThat(moderationStatusOf(deletedId)).isEqualTo("PENDING");
        assertThat(openAlertCountFor(deletedId)).isZero();

        // Second run: still untouched, still no alert — the deleted_at IS NULL predicate in
        // findPendingOlderThan means it is never re-selected, not merely re-skipped by the guard.
        releaseSchedulerLock(LOCK_NAME);
        sweeper.sweep();
        assertThat(moderationStatusOf(deletedId)).isEqualTo("PENDING");
    }
}
