package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.admin.contract.AdminAlertStatus;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.messaging.contract.MessageReportReason;
import com.softropic.skillars.platform.messaging.repo.MessageReportRepository;
import com.softropic.skillars.platform.messaging.service.MessagingReportService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-134 AC1: proves {@link AdminAlertEventListener#onMessageReported} (via its
 * shared private {@code insertAlert} helper) no longer lets a genuinely concurrent duplicate-alert
 * race take down the caller's own primary write — the bug this AC fixes (an uncaught {@code
 * DataIntegrityViolationException} surfacing at the enclosing transaction's commit, because a plain
 * {@code save()} never flushes {@code AdminAlert}'s in-memory-generated {@code UUID} id in time to
 * be caught) is order-INDEPENDENT: either racer may "win" the {@code admin_alerts_unique_open_per_ref}
 * slot, and both outcomes are correct as long as neither racer's own {@code MessageReport} row is
 * lost. This is the same shape {@code QuotaServiceConcurrencyIT} uses for its own order-independent
 * race (a simultaneous-release {@link CountDownLatch}, not a deterministic holder-thread
 * interleaving) — unlike {@code AdminCoachEnforcementConcurrencyIT}'s order-DEPENDENT races, there is
 * no "stale vs. fresh decision" to force a specific interleaving for here.
 *
 * <h2>Why {@code MessagingReportService.reportMessage}, not {@code ReviewFlagService.flag}</h2>
 *
 * The story's own drafted test plan named {@code ReviewFlagService.flag()} (via a real call, not a
 * bare {@code ApplicationEventPublisher.publishEvent}) as the fixture, reasoning that it is a
 * {@code REQUIRED}-propagation caller already used in {@code AdminQueueIT}'s seed data. That
 * reasoning turned out to be unsound for this specific race, found during implementation, not
 * assumed: {@code flag()} (skillars-deferred-132 AC2 Fix 7) takes the target {@code CoachReview}
 * row's own pessimistic lock via {@code PessimisticLockRetryer}, which retries <em>in place</em> on
 * the caller's own held connection (see that class's own Javadoc) — two {@code flag()} calls for the
 * SAME {@code reviewId} are therefore fully serialized by that lock, and the second caller's
 * {@code insertAlert} call can never run concurrently with the first's; by the time it runs, the
 * first's alert row is already committed and visible to its own {@code isPresent()} pre-check, so no
 * unique-index collision — and therefore no rollback-only race — can ever actually occur through that
 * fixture. {@link MessagingReportService#reportMessage} has no equivalent per-message lock (a plain
 * unlocked {@code findById}), is genuinely {@code @Transactional} ({@code REQUIRED} propagation,
 * class-level, same as {@link AdminAlertEventListener#onMessageReported}'s own annotation it joins),
 * and two different reporters reporting the same message is exactly the real-world shape of the race
 * this AC protects against — a strictly better fixture for proving this specific fix.
 *
 * <p><strong>Mutation-checked by hand (not committed as a second test, matching this project's own
 * established discipline for {@code GdprErasureService}'s equivalent fix):</strong>
 * <ol>
 *   <li>Reverted {@code saveAndFlush} back to {@code save} (isolation left in place) — this test then
 *       fails intermittently/deterministically depending on interleaving because the duplicate is no
 *       longer detected synchronously inside the {@code REQUIRES_NEW} transaction's own try/catch;</li>
 *   <li>Kept {@code saveAndFlush} but called the write-and-catch block directly, without the
 *       {@code requiresNewTemplate.executeWithoutResult(...)} isolation — this test then fails
 *       reliably on assertion (b) below: the loser's own transaction (joined to
 *       {@code onMessageReported}'s caller, {@code reportMessage}'s own {@code REQUIRED} transaction)
 *       is marked rollback-only by the caught-but-still-poisoning exception, so one of the two
 *       {@code MessageReport} rows never actually commits.</li>
 * </ol>
 */
class AdminAlertEventListenerConcurrencyIT extends AbstractIntegrationTest {

    @Autowired MessagingReportService messagingReportService;
    @Autowired AdminAlertRepository adminAlertRepository;
    @Autowired MessageReportRepository messageReportRepository;

    private static final long PARENT_USER_ID = 96601001L;
    private static final long COACH_USER_ID = 96601002L;
    private static final long CONVERSATION_ID = 96601003L;
    private static final long MESSAGE_ID = 96601004L;

    private UUID coachProfileId;

    @BeforeEach
    void setUp() {
        coachProfileId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            insertUser(PARENT_USER_ID, "alertconcurrency.parent@skillars-test.com", "PARENT");
            insertUser(COACH_USER_ID, "alertconcurrency.coach@skillars-test.com", "COACH");

            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Alert Concurrency Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], 'Europe/Berlin', 'ACTIVE')",
                coachProfileId, COACH_USER_ID);

            jdbcTemplate.update(
                "INSERT INTO messaging.conversations (id, coach_id, player_id, parent_id, status, created_at, last_message_at) " +
                "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                CONVERSATION_ID, coachProfileId, 96601099L, PARENT_USER_ID,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));

            jdbcTemplate.update(
                "INSERT INTO messaging.messages (id, conversation_id, sender_id, sender_role, content, moderation_status, created_at) " +
                "VALUES (?, ?, ?, 'COACH', 'Concurrency test message', 'APPROVED', ?)",
                MESSAGE_ID, CONVERSATION_ID, COACH_USER_ID, Timestamp.from(Instant.now()));
            return null;
        });
    }

    private void insertUser(long id, String email, String skillarsRole) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1985-06-01', ?, 'Concurrency', 'OTHER', 'en', 'User', 'DE', ?, " +
            "true, false, ?, 'EMAIL', 'hash', false, " +
            "?, 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email,
            "70" + (id % 100000000),
            email,
            skillarsRole
        );
    }

    /**
     * Two different, genuinely valid parties to the same conversation (the parent and the coach)
     * report the same message at the same moment. Pre-fix, whichever {@code reportMessage} call's
     * event-triggered {@code insertAlert} loses the {@code admin_alerts_unique_open_per_ref} race
     * would mark ITS OWN caller's transaction rollback-only — silently losing that reporter's own
     * {@code MessageReport} row (or surfacing an unexpected 500), even though their report was
     * otherwise entirely valid. Post-fix, {@code insertAlert}'s write is isolated in its own
     * {@code REQUIRES_NEW} transaction, so a losing racer's caught {@code DataIntegrityViolationException}
     * cannot poison {@code reportMessage}'s own transaction.
     */
    @Test
    @Timeout(30)
    void reportMessage_concurrentReportsSameMessage_bothReportsSurviveAndExactlyOneAlertOpen() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> parentReport = pool.submit(() -> {
                startLatch.await();
                return messagingReportService.reportMessage(
                    CONVERSATION_ID, MESSAGE_ID, PARENT_USER_ID, "PARENT",
                    MessageReportReason.SPAM, "parent report");
            });
            Future<?> coachReport = pool.submit(() -> {
                startLatch.await();
                return messagingReportService.reportMessage(
                    CONVERSATION_ID, MESSAGE_ID, COACH_USER_ID, "COACH",
                    MessageReportReason.INAPPROPRIATE_CONTENT, "coach report");
            });

            startLatch.countDown();

            // (a) both calls return normally -- no exception escapes either caller.
            assertNoException(parentReport);
            assertNoException(coachReport);
        } finally {
            pool.shutdownNow();
        }

        // (b) both callers' own primary writes are durably persisted -- re-read from the database,
        // not from the in-memory return value.
        assertThat(messageReportRepository.existsByMessageIdAndReportedBy(MESSAGE_ID, PARENT_USER_ID))
            .as("the parent's own MessageReport row must survive even if it lost the alert-insert race")
            .isTrue();
        assertThat(messageReportRepository.existsByMessageIdAndReportedBy(MESSAGE_ID, COACH_USER_ID))
            .as("the coach's own MessageReport row must survive even if it lost the alert-insert race")
            .isTrue();

        // (c) exactly one OPEN admin_alerts row exists for this (referenceId, type).
        assertThat(adminAlertRepository.countOpenByReferenceId(String.valueOf(MESSAGE_ID)))
            .as("the unique index must have suppressed the loser's duplicate insert, not left two OPEN rows")
            .isEqualTo(1L);
        assertThat(adminAlertRepository
                .findFirstByReferenceIdAndTypeAndStatus(String.valueOf(MESSAGE_ID),
                    AdminAlertType.MESSAGE_REPORT, AdminAlertStatus.OPEN))
            .isPresent();
    }

    private void assertNoException(Future<?> future) throws InterruptedException, TimeoutException {
        try {
            future.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("expected reportMessage to complete without throwing", e.getCause());
        }
    }
}
