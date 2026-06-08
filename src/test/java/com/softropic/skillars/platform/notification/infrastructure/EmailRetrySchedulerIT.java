package com.softropic.skillars.platform.notification.infrastructure;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.platform.notification.contract.EmailDeliveryStatus;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntity;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;
import com.softropic.skillars.platform.notification.repo.RecipientEntity;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.utils.TestMailManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link EmailRetryScheduler}.
 *
 * Verifies the full retry flow against a real PostgreSQL instance:
 * <ul>
 *   <li>Only rows that qualify (FAILED, retry=true, live deadline, attempts below threshold)
 *       are forwarded to the mail sender.</li>
 *   <li>Rows past their deadline are marked {@link EmailDeliveryStatus#DEADLINE_EXPIRED}
 *       and never sent.</li>
 *   <li>Rows at or beyond {@link EmailRetryScheduler#MAX_RETRY_ATTEMPTS} are marked
 *       {@link EmailDeliveryStatus#ATTEMPTS_EXHAUSTED} and never sent.</li>
 *   <li>{@code SELECT FOR UPDATE SKIP LOCKED} prevents two concurrent transactions from
 *       picking up the same row.</li>
 * </ul>
 *
 * {@link TestMailManager} replaces the real mail sender; sent envelopes are captured in memory.
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "enable.test.mail=true",
                "spring.cloud.compatibility-verifier.enabled=false",
                "email.retry.enabled=true"
        }
)
@Import(TestConfig.class)
class EmailRetrySchedulerIT {

    @Autowired private EmailRetryScheduler scheduler;
    @Autowired private EnvelopeEntityRepository envelopeEntityRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MailManager mailManager;   // bound to TestMailManager

    @AfterEach
    void cleanUp() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.envelope_entity_recipients");
            jdbcTemplate.execute("DELETE FROM main.envelope_entity");
            return null;
        });
        ((TestMailManager) mailManager).clear();
    }

    // -----------------------------------------------------------------
    // Row-filtering: what must be sent
    // -----------------------------------------------------------------

    @Test
    void retryFailedEmails_processesEligibleRow() {
        String sendId = randomId();
        save(failed(sendId, 1, Instant.now().plus(Duration.ofDays(1))));

        scheduler.retryFailedEmails();

        assertThat(tm().getEnvelope(sendId))
                .as("eligible FAILED row must be retried")
                .isNotNull();
    }

    @Test
    void retryFailedEmails_processesOnlyEligibleAmongMixedRows() {
        String eligibleId   = randomId();
        String expiredId    = randomId();
        String noRetryId    = randomId();
        String exhaustedId  = randomId();

        save(failed(eligibleId,  1, Instant.now().plus(Duration.ofDays(1))));
        save(failed(expiredId,   1, Instant.now().minus(Duration.ofSeconds(1))));
        save(failed(noRetryId,   1, Instant.now().plus(Duration.ofDays(1)), false));
        save(failed(exhaustedId, (int) EmailRetryScheduler.MAX_RETRY_ATTEMPTS,
                    Instant.now().plus(Duration.ofDays(1))));

        scheduler.retryFailedEmails();

        assertThat(tm().getEnvelope(eligibleId)).isNotNull();
        assertThat(tm().getEnvelope(expiredId)).isNull();
        assertThat(tm().getEnvelope(noRetryId)).isNull();
        assertThat(tm().getEnvelope(exhaustedId)).isNull();
    }

    // -----------------------------------------------------------------
    // DEADLINE_EXPIRED
    // -----------------------------------------------------------------

    @Test
    void retryFailedEmails_marksDeadlineExpiredAndDoesNotSend() {
        String sendId = randomId();
        save(failed(sendId, 1, Instant.now().minus(Duration.ofSeconds(1))));

        scheduler.retryFailedEmails();

        assertThat(tm().getEnvelope(sendId)).as("expired row must not be sent").isNull();

        EnvelopeEntity persisted = findBySendId(sendId);
        assertThat(persisted.getStatus()).isEqualTo(EmailDeliveryStatus.DEADLINE_EXPIRED);
        assertThat(persisted.isRetry()).isFalse();
    }

    @Test
    void retryFailedEmails_expiredRowIsNotFetchedOnSubsequentRun() {
        String sendId = randomId();
        save(failed(sendId, 1, Instant.now().minus(Duration.ofSeconds(1))));

        scheduler.retryFailedEmails(); // marks DEADLINE_EXPIRED, retry=false
        tm().clear();
        scheduler.retryFailedEmails(); // second run — must not touch the row

        assertThat(tm().getEnvelope(sendId)).isNull();
    }

    // -----------------------------------------------------------------
    // ATTEMPTS_EXHAUSTED
    // -----------------------------------------------------------------

    @Test
    void retryFailedEmails_marksAttemptsExhaustedAtThresholdAndDoesNotSend() {
        String sendId = randomId();
        save(failed(sendId, (int) EmailRetryScheduler.MAX_RETRY_ATTEMPTS,
                    Instant.now().plus(Duration.ofDays(1))));

        scheduler.retryFailedEmails();

        assertThat(tm().getEnvelope(sendId)).as("exhausted row must not be sent").isNull();

        EnvelopeEntity persisted = findBySendId(sendId);
        assertThat(persisted.getStatus()).isEqualTo(EmailDeliveryStatus.ATTEMPTS_EXHAUSTED);
        assertThat(persisted.isRetry()).isFalse();
    }

    @Test
    void retryFailedEmails_exhaustedRowIsNotFetchedOnSubsequentRun() {
        String sendId = randomId();
        save(failed(sendId, (int) EmailRetryScheduler.MAX_RETRY_ATTEMPTS,
                    Instant.now().plus(Duration.ofDays(1))));

        scheduler.retryFailedEmails();
        tm().clear();
        scheduler.retryFailedEmails();

        assertThat(tm().getEnvelope(sendId)).isNull();
    }

    @Test
    void retryFailedEmails_doesNotExhaustWhenAttemptsOneBelowThreshold() {
        // attempts = MAX - 1: still one retry remaining
        String sendId = randomId();
        save(failed(sendId, (int) (EmailRetryScheduler.MAX_RETRY_ATTEMPTS - 1),
                    Instant.now().plus(Duration.ofDays(1))));

        scheduler.retryFailedEmails();

        assertThat(tm().getEnvelope(sendId))
                .as("row below threshold must still be retried")
                .isNotNull();
    }

    // -----------------------------------------------------------------
    // SELECT FOR UPDATE SKIP LOCKED — concurrent-access safety
    // -----------------------------------------------------------------

    /**
     * Thread A holds a transaction that locks the row via {@code SELECT FOR UPDATE SKIP LOCKED}.
     * Thread B's identical query must return nothing while A holds the lock.
     * After A commits the row becomes visible again.
     */
    @Test
    void skipLocked_secondTransactionSkipsRowLockedByFirst() throws Exception {
        String sendId = randomId();
        save(failed(sendId, 1, Instant.now().plus(Duration.ofDays(1))));

        CountDownLatch rowLocked   = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor   = Executors.newFixedThreadPool(2);

        Future<List<EnvelopeEntity>> txA = executor.submit(() ->
                transactionTemplate.execute(status -> {
                    List<EnvelopeEntity> locked = envelopeEntityRepository.fetchFailedEmails();
                    rowLocked.countDown();
                    try { releaseLock.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return locked;
                })
        );

        rowLocked.await(5, TimeUnit.SECONDS);

        List<EnvelopeEntity> skipped = transactionTemplate.execute(status ->
                envelopeEntityRepository.fetchFailedEmails()
        );
        assertThat(skipped)
                .as("SKIP LOCKED must return empty while the row is held by another transaction")
                .isEmpty();

        releaseLock.countDown();
        List<EnvelopeEntity> lockedByA = txA.get(5, TimeUnit.SECONDS);
        assertThat(lockedByA).hasSize(1);
        assertThat(lockedByA.get(0).getSendId()).isEqualTo(sendId);

        // After A commits the row is accessible again
        List<EnvelopeEntity> afterRelease = transactionTemplate.execute(status ->
                envelopeEntityRepository.fetchFailedEmails()
        );
        assertThat(afterRelease).hasSize(1);

        executor.shutdown();
    }

    @Test
    void skipLocked_twoTransactionsClaimDisjointRows() throws Exception {
        save(failed(randomId(), 1, Instant.now().plus(Duration.ofDays(1))));
        save(failed(randomId(), 1, Instant.now().plus(Duration.ofDays(1))));

        CountDownLatch rowsLocked  = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor   = Executors.newFixedThreadPool(2);

        Future<List<EnvelopeEntity>> txA = executor.submit(() ->
                transactionTemplate.execute(status -> {
                    List<EnvelopeEntity> locked = envelopeEntityRepository.fetchFailedEmails();
                    rowsLocked.countDown();
                    try { releaseLock.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return locked;
                })
        );

        rowsLocked.await(5, TimeUnit.SECONDS);

        List<EnvelopeEntity> skipped = transactionTemplate.execute(status ->
                envelopeEntityRepository.fetchFailedEmails()
        );
        assertThat(skipped)
                .as("no rows should be visible to Thread B while Thread A holds all locks")
                .isEmpty();

        releaseLock.countDown();
        assertThat(txA.get(5, TimeUnit.SECONDS)).hasSize(2);

        executor.shutdown();
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private TestMailManager tm() {
        return (TestMailManager) mailManager;
    }

    private String randomId() {
        return UUID.randomUUID().toString();
    }

    private void save(EnvelopeEntity entity) {
        transactionTemplate.execute(status -> {
            envelopeEntityRepository.save(entity);
            return null;
        });
    }

    private EnvelopeEntity findBySendId(String sendId) {
        return transactionTemplate.execute(status ->
                envelopeEntityRepository.findBySendId(sendId)
        );
    }

    private EnvelopeEntity failed(String sendId, int attempts, Instant deadline) {
        return failed(sendId, attempts, deadline, true);
    }

    private EnvelopeEntity failed(String sendId, int attempts, Instant deadline, boolean retry) {
        RecipientEntity recipient = new RecipientEntity();
        recipient.setEmail("user@example.com");
        recipient.setLangKey("en");

        EnvelopeEntity entity = new EnvelopeEntity();
        entity.setId(UUID.randomUUID());
        entity.setSendId(sendId);
        entity.setEmailTemplate(EmailTemplate.ACTIVATION);
        entity.setDeadline(deadline);
        entity.setData(Map.of("activationKey", "test123"));
        entity.setRecipients(List.of(recipient));
        entity.setStatus(EmailDeliveryStatus.FAILED);
        entity.setRetry(retry);
        entity.setAttempts(attempts);
        return entity;
    }
}
