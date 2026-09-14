package com.softropic.skillars.platform.notification.infrastructure;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntity;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.platform.notification.service.MailService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

/**
 * Story ses-1.4 AC4, reframed per code review 2026-09-12 (B2/B3): {@code sendId} uniqueness dedupes
 * the {@code EnvelopeEntity} bookkeeping row, not the underlying email dispatch — this proves a
 * genuine collision is a clear, propagating error and leaves exactly one row, not that duplicate
 * sends are prevented.
 *
 * <h2>Why a real two-thread race against real Postgres</h2>
 *
 * {@code EnvelopeEntity}'s {@code @Id} is hand-assigned (no {@code @GeneratedValue}), so
 * {@code envelopeEntityRepository.findBySendId(...)} finding nothing is what routes
 * {@code MailManager.sendEmailSync} to {@code saveAndFlush(...)} instead of updating an existing row.
 * Two calls for the same {@code sendId} only collide if <em>both</em> see no existing row before
 * either commits — a single-threaded test cannot reach that window, since the first call's own
 * {@code saveAndFlush} commits (or the test observes it) before the second call's
 * {@code findBySendId} runs. This drives two real transactions so the second's INSERT genuinely
 * blocks on Postgres's unique-index conflict check against the first's still-open transaction, then
 * fails once the first commits — reproducing the collision the review found unreachable by any single
 * in-transaction recovery attempt.
 *
 * <h2>Why {@code MailManager} is hand-wired rather than autowired</h2>
 *
 * Mirrors {@code VideoModerationAdminAlertEnvelopeIT}: {@code enable.test.mail=true}
 * (application-test.yaml) makes {@code TestMailManager} the universal {@code MailManager} bean, which
 * persists nothing. The real {@code EnvelopeEntityRepository}, {@code CircuitBreakerFactory} and
 * {@code RetryTemplate} are the actual container-backed beans; only {@code MailService} (the SMTP
 * seam) is mocked, and each thread supplies its own {@link TransactionTemplate} boundary explicitly —
 * a hand-built {@code MailManager} has no Spring proxy, so its {@code @Transactional} annotations do
 * not apply on their own.
 *
 * <p>Named {@code *IT}, not {@code *Test}: it needs the real Postgres-backed
 * {@code EnvelopeEntityRepository} and a real unique-constraint conflict, which
 * {@code IntegrationTestConventionTest} requires be filed under the {@code *IT} naming so Failsafe
 * (not Surefire) owns it and its base-class usage is governed like every other integration test.
 *
 * <h2>What this test does — and does not — prove about {@code save()} vs {@code saveAndFlush()}
 * (code review 2026-09-12, revisited)</h2>
 *
 * A first attempt at this test tried to move thread B's {@code catch} inside the transaction, around
 * only {@code sendEmailSync}, on the theory that {@code saveAndFlush} would then surface the
 * collision synchronously while a reverted {@code save()} would let it through to
 * {@code executeWithoutResult}'s own commit. <strong>That does not hold in practice</strong>: with
 * {@code hibernate.jdbc.batch_size: 15} configured (application.yaml) the pending insert from
 * {@code merge()} is still batched, and — reproduced against the real container, not assumed — the
 * unique-constraint violation surfaces at the surrounding transaction's commit either way, not inside
 * the {@code saveAndFlush()} call itself. Narrowing the catch scope therefore made this test flaky in
 * the wrong direction (green on the correct code, red on it) rather than mutation-sensitive to the
 * fix. The catch stays scoped to the whole {@link TransactionTemplate#executeWithoutResult}
 * accordingly. What this test does prove, and is the right shape for: the collision genuinely occurs
 * under the described race, exactly one row survives it, and the loser's failure propagates as a
 * real, typed exception rather than being silently swallowed or merged into the winner's row.
 * Whether {@code MailManager} calls {@code save(...)} or {@code saveAndFlush(...)} specifically is
 * covered separately and directly by {@code MailManagerResilienceTest}'s mock-based
 * {@code verify(envelopeEntityRepository).saveAndFlush(...)} assertions, which go red immediately if
 * that call reverts to {@code save(...)}.
 */
class MailManagerDuplicateSendIdIT extends AbstractIntegrationTest {

    @Autowired private EnvelopeEntityRepository envelopeEntityRepository;
    @Autowired private CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    @Autowired private RetryTemplate retryTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private static final long LOCK_HOLD_MILLIS = 1200;

    private Envelope envelope(String sendId, String email) {
        Recipient recipient = new Recipient();
        recipient.setEmail(email);
        recipient.setLangKey("en");
        return new Envelope(List.of(recipient), EmailTemplate.COACH_OTP,
            Instant.now().plusSeconds(300), Map.of("otpCode", "123456"), sendId);
    }

    private long rowCountForSendId(String sendId) {
        List<EnvelopeEntity> all = new TransactionTemplate(transactionManager)
            .execute(status -> envelopeEntityRepository.findAll());
        return all.stream().filter(e -> sendId.equals(e.getSendId())).count();
    }

    @Test
    void racingSendsWithTheSameSendId_leaveExactlyOneRow_andTheLoserThrowsInsteadOfSilentlyMerging() throws Exception {
        String sendId = "ac4-dup-" + UUID.randomUUID();
        String emailA = "ac4.a." + UUID.randomUUID() + "@skillars-test.com";
        String emailB = "ac4.b." + UUID.randomUUID() + "@skillars-test.com";

        MailService seamMailServiceA = mock(MailService.class);
        doNothing().when(seamMailServiceA).sendEmailFromTemplate(any(), any(), any());
        MailManager mailManagerA = new MailManager(seamMailServiceA, envelopeEntityRepository, circuitBreakerFactory, retryTemplate);

        MailService seamMailServiceB = mock(MailService.class);
        doNothing().when(seamMailServiceB).sendEmailFromTemplate(any(), any(), any());
        MailManager mailManagerB = new MailManager(seamMailServiceB, envelopeEntityRepository, circuitBreakerFactory, retryTemplate);

        TransactionTemplate requiresNewA = new TransactionTemplate(transactionManager);
        requiresNewA.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionTemplate requiresNewB = new TransactionTemplate(transactionManager);
        requiresNewB.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        CountDownLatch firstInsertedButNotCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<RuntimeException> secondOutcome = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                try {
                    requiresNewA.executeWithoutResult(status -> {
                        mailManagerA.sendEmailSync(envelope(sendId, emailA));
                        firstInsertedButNotCommitted.countDown();
                        try {
                            Thread.sleep(LOCK_HOLD_MILLIS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("Interrupted while holding the uncommitted insert", e);
                        }
                    });
                } catch (Throwable t) {
                    firstFailure.set(t);
                }
            });

            // Wraps the whole transaction, not just sendEmailSync — see class javadoc: the
            // collision surfaces at this commit regardless of save() vs saveAndFlush(), so a
            // narrower scope here would not be mutation-sensitive and would just be fragile.
            Future<?> second = executor.submit(() -> {
                try {
                    firstInsertedButNotCommitted.await(10, TimeUnit.SECONDS);
                    requiresNewB.executeWithoutResult(status ->
                        mailManagerB.sendEmailSync(envelope(sendId, emailB)));
                } catch (RuntimeException e) {
                    secondOutcome.set(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while waiting for the first send's insert", e);
                }
            });

            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);

            if (firstFailure.get() != null) {
                throw new AssertionError("The first (winning) send must not fail", firstFailure.get());
            }

            assertThat(secondOutcome.get())
                .as("""
                    the sendId unique-constraint violation must surface as a real, typed exception \
                    and propagate rather than being silently swallowed or merged into the winner's \
                    row — per AC4, no in-method recovery is attempted, and both real callers \
                    (EmailRetryScheduler.sendAll, NotificationEmailOutboxHandler.handle) already \
                    handle an uncaught exception here correctly.""")
                .isNotNull()
                .isInstanceOfAny(DataIntegrityViolationException.class, TransactionSystemException.class);

            assertThat(rowCountForSendId(sendId))
                .as("sendId uniqueness dedupes the bookkeeping row: exactly one EnvelopeEntity must exist "
                    + "for this sendId, no matter which side of the race it came from")
                .isEqualTo(1);
        } finally {
            // A timed-out first.get()/second.get() above must not leak a thread still holding an
            // uncommitted row lock for the rest of this Failsafe run.
            executor.shutdownNow();
        }
    }
}
