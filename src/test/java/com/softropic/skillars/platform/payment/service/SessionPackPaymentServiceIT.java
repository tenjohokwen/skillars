package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.repo.SessionPackTier;
import com.softropic.skillars.platform.payment.repo.SessionPackTierRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-103 AC1 — the {@code createTier} deactivate-then-insert path and the partial
 * unique index {@code idx_spt_one_active_per_coach} (V62:58).
 *
 * <p>The 409-mapping half of AC1 ({@code DataIntegrityViolationException} on that index →
 * {@code 409 payment.tierRaceConflict}, not a raw 500) is covered deterministically by
 * {@code ApiAdviceTest.integrityViolationHandler_sessionPackTierRace_returns409TierRaceConflict}.
 * This IT covers the database half: that under real concurrency the index leaves <em>exactly one</em>
 * active tier per coach and the losing transaction fails with a translated Spring
 * {@link DataAccessException} rather than a silent double-insert.
 */
class SessionPackPaymentServiceIT extends BasePaymentIT {

    private static final AtomicLong USER_ID_SEQ = new AtomicLong(880_000L);

    @Autowired
    private SessionPackPaymentService sessionPackPaymentService;

    @Autowired
    private SessionPackTierRepository sessionPackTierRepository;

    private UUID newCoach() {
        long userId = USER_ID_SEQ.incrementAndGet();
        return insertTestCoach(userId, "coach" + userId + "@it.local", "Test Coach " + userId);
    }

    @Test
    void createTier_firstTier_isActive() {
        UUID coachId = newCoach();

        sessionPackPaymentService.createTier(coachId, "Standard", 10, new BigDecimal("100.00"));

        List<SessionPackTier> tiers = sessionPackTierRepository.findAllByCoachId(coachId);
        assertThat(tiers).hasSize(1);
        assertThat(tiers.get(0).isActive()).isTrue();
        assertThat(tiers.get(0).getLabel()).isEqualTo("Standard");
    }

    @Test
    void createTier_secondTier_deactivatesThePrevious() {
        UUID coachId = newCoach();

        sessionPackPaymentService.createTier(coachId, "Tier1", 10, new BigDecimal("100.00"));
        sessionPackPaymentService.createTier(coachId, "Tier2", 20, new BigDecimal("200.00"));

        assertThat(sessionPackTierRepository.findAllByCoachIdAndIsActiveTrue(coachId))
            .extracting(SessionPackTier::getLabel)
            .containsExactly("Tier2");
        assertThat(sessionPackTierRepository.findAllByCoachId(coachId)).hasSize(2);
    }

    @Test
    void createTier_concurrentCreationForSameCoach_leavesExactlyOneActive_loserGetsTranslatedException()
            throws InterruptedException {
        UUID coachId = newCoach();

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Throwable> ex1 = new AtomicReference<>();
        AtomicReference<Throwable> ex2 = new AtomicReference<>();

        Thread t1 = new Thread(() -> runCreateTier(barrier, coachId, "Tier1", new BigDecimal("100.00"), ex1));
        Thread t2 = new Thread(() -> runCreateTier(barrier, coachId, "Tier2", new BigDecimal("200.00"), ex2));

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // The partial unique index is the invariant under test — regardless of how the two
        // transactions interleave, at most (and, since at least one always commits, exactly) one
        // active tier may remain.
        assertThat(sessionPackTierRepository.findAllByCoachIdAndIsActiveTrue(coachId)).hasSize(1);
        assertThat(sessionPackTierRepository.findAllByCoachId(coachId)).hasSizeBetween(1, 2);

        // If the transactions genuinely overlapped, the loser surfaces as a translated Spring
        // exception — a DataAccessException (DataIntegrityViolationException for the 23505, or a
        // lock-family exception), or a TransactionException wrapping one when the violation lands at
        // commit — never an untranslated raw error. If they happened to serialize, neither throws
        // and that is also valid.
        for (Throwable t : List.of(ex1, ex2).stream().map(AtomicReference::get).filter(x -> x != null).toList()) {
            assertThat(t).isInstanceOfAny(DataAccessException.class, TransactionException.class);
        }
    }

    private void runCreateTier(CyclicBarrier barrier, UUID coachId, String label, BigDecimal price,
                               AtomicReference<Throwable> sink) {
        try {
            barrier.await();
            sessionPackPaymentService.createTier(coachId, label, 10, price);
        } catch (Throwable t) {
            sink.set(t);
        }
    }
}
