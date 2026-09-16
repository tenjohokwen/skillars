package com.softropic.skillars.platform.notification.infrastructure;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntity;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;
import com.softropic.skillars.platform.notification.repo.RecipientEntity;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.platform.notification.service.MailService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Story ses-1.4 AC4 established that {@code sendId} uniqueness dedupes the {@code EnvelopeEntity}
 * bookkeeping row, not the underlying email dispatch. Before {@code skillars-deferred-114} AC1, a
 * genuine collision on the FIRST-EVER send for a given {@code sendId} (no row exists yet — nothing
 * for {@code findBySendId}'s {@code PESSIMISTIC_WRITE} lock to serialize against) let both racing
 * calls actually dispatch before one lost loudly on the DB unique-constraint at commit.
 *
 * <p>{@code skillars-deferred-114} AC1 closes that gap with a Postgres advisory transaction lock
 * ({@code pg_advisory_xact_lock(hashtext(sendId))}), acquired as the first statement inside
 * {@code sendEmailSync}'s existing {@code @Transactional(REQUIRES_NEW)} boundary. This class now
 * covers three scenarios against that fix:
 * <ul>
 *   <li>{@link #racingSendsWithTheSameSendIdAndSameRecipients_realProductionShape_exactlyOneRealSendReachesTheRecipient()} —
 *       the real production shape: two calls race the identical envelope (same {@code sendId}, same
 *       recipients). The advisory lock serializes them; the second call's {@code findBySendId} then
 *       sees the first call's already-delivered recipient and skips re-sending to it.</li>
 *   <li>{@link #racingSendsWithDifferentRecipients_callerMisuse_secondCallNowBlocksThenUpdatesSilentlyInsteadOfThrowing()} —
 *       the pre-fix scenario this class originally covered (two DIFFERENT envelopes sharing a
 *       hand-authored duplicate {@code sendId} — a caller-misuse case, not the real production
 *       shape). Documents the AC1 Design Consideration: this no longer throws a DB-collision
 *       exception: the second call now blocks on the advisory lock, then silently overwrites the
 *       first call's persisted recipient list with its own.</li>
 *   <li>{@link #firstCallRollsBack_secondUnblockedCallRunsAsCleanFirstAttempt()} — the first call in
 *       a serialized pair fails outright (rolled back, not just rate-limited); the second,
 *       now-unblocked call must not mistake itself for an "existing row" update, since no row was
 *       actually committed by the first call.</li>
 *   <li>{@link #existingRowWithPessimisticLock_concurrentCallersBlock_thenBothCompleteCleanly()} —
 *       skillars-deferred-114 code review (LOW): the three scenarios above all create the
 *       {@code EnvelopeEntity} row as part of the race itself. This scenario instead pre-persists the
 *       row in its own, already-committed transaction first, THEN races two callers against it —
 *       isolating whether the advisory lock and {@code findBySendId}'s pre-existing
 *       {@code PESSIMISTIC_WRITE} lock coexist without deadlocking once a row already exists, rather
 *       than relying on that interaction only being exercised implicitly, mid-race, by scenario 1.</li>
 * </ul>
 *
 * <h2>Why a real two-thread race against real Postgres</h2>
 *
 * The advisory lock is a genuine cluster-wide Postgres primitive — its serialization behavior (who
 * blocks, when the lock releases) cannot be reproduced by mocking or by a single-threaded test. This
 * also doubles as this story's required proof that {@code pg_advisory_xact_lock}'s {@code void}
 * return type maps cleanly through {@code EnvelopeEntityRepository.acquireSendIdLock}'s plain
 * (non-{@code @Modifying}) native query against this project's pinned Hibernate/pgjdbc versions — if
 * that mapping did not "just work," every test below would fail at the {@code acquireSendIdLock}
 * call, not just the assertions after it.
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
 * {@code EnvelopeEntityRepository}, a real advisory lock, and (in the caller-misuse case) a real
 * unique-constraint-adjacent race, which {@code IntegrationTestConventionTest} requires be filed under
 * the {@code *IT} naming so Failsafe (not Surefire) owns it and its base-class usage is governed like
 * every other integration test.
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

    /**
     * Reads the persisted recipient emails for {@code sendId} inside a transaction — {@code
     * EnvelopeEntity.recipients} is a lazy {@code @ElementCollection} that cannot be read once the
     * entity is detached (same constraint {@code MailManagerRateLimitIT} documents).
     */
    private List<String> persistedRecipientEmails(String sendId) {
        TransactionTemplate readTx = new TransactionTemplate(transactionManager);
        readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return readTx.execute(status -> {
            EnvelopeEntity entity = envelopeEntityRepository.findBySendId(sendId);
            assertThat(entity).as("an EnvelopeEntity row must exist for sendId " + sendId).isNotNull();
            return entity.getRecipients().stream().map(RecipientEntity::getEmail).toList();
        });
    }

    /**
     * skillars-deferred-114 AC1: the real production shape — two concurrent callers racing the
     * IDENTICAL envelope (same {@code sendId}, same recipients) for a {@code sendId} with no existing
     * row. The advisory lock serializes them cluster-wide; whichever call loses the race blocks until
     * the winner's transaction commits, then finds the recipient already marked delivered and skips
     * re-sending to it. Asserts exactly one real send reaches the recipient (the mocked {@code
     * MailService} seam) and neither call throws.
     *
     * <p>Mutation check performed manually during development (not committed as an automated
     * assertion, since it requires editing production code): with {@code
     * MailManager.sendEmailSync}'s {@code acquireSendIdLock} call temporarily removed, this test goes
     * red — {@code seamMailService} is invoked twice for the same recipient (both calls race
     * {@code findBySendId} before either commits, so neither sees the other's delivery), reproducing
     * a real duplicate send. Restored immediately after confirming the red result.
     */
    @Test
    void racingSendsWithTheSameSendIdAndSameRecipients_realProductionShape_exactlyOneRealSendReachesTheRecipient() throws Exception {
        String sendId = "ac1-same-envelope-" + UUID.randomUUID();
        String email = "ac1.same." + UUID.randomUUID() + "@skillars-test.com";

        MailService seamMailService = mock(MailService.class);
        doNothing().when(seamMailService).sendEmailFromTemplate(any(), any(), any());
        MailManager mailManagerA = new MailManager(seamMailService, envelopeEntityRepository, circuitBreakerFactory, retryTemplate);
        MailManager mailManagerB = new MailManager(seamMailService, envelopeEntityRepository, circuitBreakerFactory, retryTemplate);

        TransactionTemplate requiresNewA = new TransactionTemplate(transactionManager);
        requiresNewA.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionTemplate requiresNewB = new TransactionTemplate(transactionManager);
        requiresNewB.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        CountDownLatch firstLockAcquiredNotCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                try {
                    requiresNewA.executeWithoutResult(status -> {
                        mailManagerA.sendEmailSync(envelope(sendId, email));
                        firstLockAcquiredNotCommitted.countDown();
                        try {
                            Thread.sleep(LOCK_HOLD_MILLIS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("Interrupted while holding the advisory lock open", e);
                        }
                    });
                } catch (Throwable t) {
                    firstFailure.set(t);
                }
            });

            Future<?> second = executor.submit(() -> {
                try {
                    firstLockAcquiredNotCommitted.await(10, TimeUnit.SECONDS);
                    requiresNewB.executeWithoutResult(status -> mailManagerB.sendEmailSync(envelope(sendId, email)));
                } catch (Throwable t) {
                    secondFailure.set(t);
                } finally {
                    // no-op: interruption handled via the outer Throwable capture above
                }
            });

            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);

            assertThat(firstFailure.get()).as("the first (winning) send must not fail").isNull();
            assertThat(secondFailure.get())
                .as("the second (blocked) send must complete cleanly once unblocked — it finds the "
                    + "recipient already delivered and skips re-sending, it does not race an INSERT")
                .isNull();

            verify(seamMailService, times(1))
                .sendEmailFromTemplate(argThat(r -> email.equals(r.getEmail())), any(), any());
            assertThat(rowCountForSendId(sendId))
                .as("exactly one EnvelopeEntity row for this sendId")
                .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * skillars-deferred-114 AC1 Design Consideration: this is the scenario the class originally
     * covered pre-fix — two DIFFERENT envelopes (different recipients) sharing a hand-authored
     * duplicate {@code sendId}, a caller-misuse case no real producer triggers (every real redrive
     * sends the identical envelope). Before the advisory lock, both calls raced an INSERT and the
     * loser threw a real {@code DataIntegrityViolationException}/{@code TransactionSystemException}.
     * After the advisory lock, the second call instead blocks until the first commits, then takes the
     * "existing row" update branch and silently overwrites the first call's persisted recipient list
     * with its own — exactly the same silent path a SEQUENTIAL (non-racing) second call for this
     * sendId already took even before this story. This is an explicit, documented trade (see the
     * inline comment at {@code MailManager}'s {@code applyDeliveryFlags} call site), not a surprise:
     * this test pins it rather than leaving it undiscovered.
     */
    @Test
    void racingSendsWithDifferentRecipients_callerMisuse_secondCallNowBlocksThenUpdatesSilentlyInsteadOfThrowing() throws Exception {
        String sendId = "ac1-dup-" + UUID.randomUUID();
        String emailA = "ac1.a." + UUID.randomUUID() + "@skillars-test.com";
        String emailB = "ac1.b." + UUID.randomUUID() + "@skillars-test.com";

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

        CountDownLatch firstLockAcquiredNotCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                try {
                    requiresNewA.executeWithoutResult(status -> {
                        mailManagerA.sendEmailSync(envelope(sendId, emailA));
                        firstLockAcquiredNotCommitted.countDown();
                        try {
                            Thread.sleep(LOCK_HOLD_MILLIS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("Interrupted while holding the advisory lock open", e);
                        }
                    });
                } catch (Throwable t) {
                    firstFailure.set(t);
                }
            });

            Future<?> second = executor.submit(() -> {
                try {
                    firstLockAcquiredNotCommitted.await(10, TimeUnit.SECONDS);
                    requiresNewB.executeWithoutResult(status ->
                        mailManagerB.sendEmailSync(envelope(sendId, emailB)));
                } catch (Throwable t) {
                    secondFailure.set(t);
                }
            });

            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);

            assertThat(firstFailure.get()).as("the first (winning) send must not fail").isNull();
            assertThat(secondFailure.get())
                .as("""
                    post-AC1, the second call no longer races an INSERT and no longer throws — it \
                    blocks on the advisory lock until the first commits, then silently updates the \
                    now-existing row. This is a documented trade, not the pre-fix loud DB collision.""")
                .isNull();

            assertThat(rowCountForSendId(sendId))
                .as("sendId uniqueness still dedupes the bookkeeping row: exactly one EnvelopeEntity "
                    + "must exist for this sendId")
                .isEqualTo(1);

            assertThat(persistedRecipientEmails(sendId))
                .as("the second call's recipient list silently overwrote the first's — the persisted "
                    + "row reflects only the second (later) call's envelope")
                .containsExactly(emailB);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * skillars-deferred-114 AC1: the first call in a serialized pair fails outright (rolled back, not
     * merely rate-limited) — no row is ever committed by it. The second, now-unblocked call must not
     * mistake itself for an "existing row" update: {@code findBySendId} finds nothing (the first
     * call's insert was rolled back), so the second call must take the clean "no existing row" first-
     * attempt branch, exactly as if it were the only caller.
     */
    @Test
    void firstCallRollsBack_secondUnblockedCallRunsAsCleanFirstAttempt() throws Exception {
        String sendId = "ac1-rollback-" + UUID.randomUUID();
        String email = "ac1.rollback." + UUID.randomUUID() + "@skillars-test.com";

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

        CountDownLatch firstLockAcquiredNotYetRolledBack = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                try {
                    requiresNewA.executeWithoutResult(status -> {
                        mailManagerA.sendEmailSync(envelope(sendId, email));
                        // Simulate "fails outright" — force the whole transaction (advisory lock
                        // included) to roll back at the end of this callback, undoing whatever
                        // sendEmailSync persisted, instead of letting it commit.
                        status.setRollbackOnly();
                        firstLockAcquiredNotYetRolledBack.countDown();
                        try {
                            Thread.sleep(LOCK_HOLD_MILLIS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("Interrupted while holding the advisory lock open", e);
                        }
                    });
                } catch (Throwable t) {
                    firstFailure.set(t);
                }
            });

            Future<?> second = executor.submit(() -> {
                try {
                    firstLockAcquiredNotYetRolledBack.await(10, TimeUnit.SECONDS);
                    requiresNewB.executeWithoutResult(status -> mailManagerB.sendEmailSync(envelope(sendId, email)));
                } catch (Throwable t) {
                    secondFailure.set(t);
                }
            });

            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);

            assertThat(firstFailure.get())
                .as("marking the transaction rollback-only must not itself throw out of executeWithoutResult")
                .isNull();
            assertThat(secondFailure.get())
                .as("the second call must run as a clean first attempt, not mistakenly treat a rolled-"
                    + "back row as an existing one")
                .isNull();

            assertThat(rowCountForSendId(sendId))
                .as("the first call's row was rolled back; only the second call's row survives")
                .isEqualTo(1);
            verify(seamMailServiceB, times(1))
                .sendEmailFromTemplate(argThat(r -> email.equals(r.getEmail())), any(), any());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * skillars-deferred-114 code review (LOW): the row for {@code sendId} is pre-persisted and
     * committed BEFORE either racing call starts — unlike the scenarios above, where the row is
     * created by the race itself. Two callers then race a redrive-shaped call against that
     * already-existing row: the advisory lock still serializes them, and the second (now-unblocked)
     * caller must find the recipient already delivered via {@code findBySendId}'s pre-existing
     * {@code PESSIMISTIC_WRITE} lock and skip re-sending — proving the two locking mechanisms coexist
     * cleanly for an already-persisted row, not only for one created mid-race.
     */
    @Test
    void existingRowWithPessimisticLock_concurrentCallersBlock_thenBothCompleteCleanly() throws Exception {
        String sendId = "ac1-existing-row-" + UUID.randomUUID();
        String email = "ac1.existing." + UUID.randomUUID() + "@skillars-test.com";

        MailService seamMailServicePrePersist = mock(MailService.class);
        doNothing().when(seamMailServicePrePersist).sendEmailFromTemplate(any(), any(), any());
        MailManager mailManagerPrePersist =
            new MailManager(seamMailServicePrePersist, envelopeEntityRepository, circuitBreakerFactory, retryTemplate);
        new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> mailManagerPrePersist.sendEmailSync(envelope(sendId, email)));
        assertThat(rowCountForSendId(sendId))
            .as("pre-persist must commit its own row before the race below starts")
            .isEqualTo(1);

        MailService seamMailServiceB = mock(MailService.class);
        doNothing().when(seamMailServiceB).sendEmailFromTemplate(any(), any(), any());
        MailManager mailManagerB = new MailManager(seamMailServiceB, envelopeEntityRepository, circuitBreakerFactory, retryTemplate);

        MailService seamMailServiceC = mock(MailService.class);
        doNothing().when(seamMailServiceC).sendEmailFromTemplate(any(), any(), any());
        MailManager mailManagerC = new MailManager(seamMailServiceC, envelopeEntityRepository, circuitBreakerFactory, retryTemplate);

        TransactionTemplate requiresNewB = new TransactionTemplate(transactionManager);
        requiresNewB.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionTemplate requiresNewC = new TransactionTemplate(transactionManager);
        requiresNewC.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        CountDownLatch bLockAcquiredNotCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> bFailure = new AtomicReference<>();
        AtomicReference<Throwable> cFailure = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> b = executor.submit(() -> {
                try {
                    requiresNewB.executeWithoutResult(status -> {
                        mailManagerB.sendEmailSync(envelope(sendId, email));
                        bLockAcquiredNotCommitted.countDown();
                        try {
                            Thread.sleep(LOCK_HOLD_MILLIS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("Interrupted while holding the advisory lock open", e);
                        }
                    });
                } catch (Throwable t) {
                    bFailure.set(t);
                }
            });

            Future<?> c = executor.submit(() -> {
                try {
                    bLockAcquiredNotCommitted.await(10, TimeUnit.SECONDS);
                    requiresNewC.executeWithoutResult(status -> mailManagerC.sendEmailSync(envelope(sendId, email)));
                } catch (Throwable t) {
                    cFailure.set(t);
                }
            });

            b.get(30, TimeUnit.SECONDS);
            c.get(30, TimeUnit.SECONDS);

            assertThat(bFailure.get()).as("the first racing call against the pre-existing row must not fail").isNull();
            assertThat(cFailure.get())
                .as("the second, blocked call must complete cleanly once unblocked — advisory lock and "
                    + "PESSIMISTIC_WRITE row locking must not deadlock against each other")
                .isNull();

            assertThat(rowCountForSendId(sendId)).as("still exactly one row for this sendId").isEqualTo(1);
            verify(seamMailServiceB, times(0))
                .sendEmailFromTemplate(argThat(r -> email.equals(r.getEmail())), any(), any());
            verify(seamMailServiceC, times(0))
                .sendEmailFromTemplate(argThat(r -> email.equals(r.getEmail())), any(), any());
        } finally {
            executor.shutdownNow();
        }
    }
}
