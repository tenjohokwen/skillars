package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for session pack extension logic.
 * AC 6: Coach can extend an active pack within the 14-day window once only.
 */
class PackExtensionIT extends BasePaymentIT {

    @Autowired SessionPackPaymentService sessionPackPaymentService;

    private UUID coachId;
    private long coachUserId = 90001L;

    @BeforeEach
    void setUpCoach() {
        coachId = insertTestCoach(coachUserId, "ext_coach@test.com", "Extension Coach");
    }

    @Test
    void extendPack_withinWindow_succeeds() {
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS); // 7 days out — within 14-day window
        UUID purchaseId = insertTestPurchase(coachId, expiresAt);

        sessionPackPaymentService.extendPack(coachUserId, purchaseId);

        Instant newExpiry = jdbcTemplate.queryForObject(
            "SELECT expires_at FROM payment.session_pack_purchases WHERE purchase_id = ?",
            Instant.class, purchaseId
        );
        assert newExpiry != null;
        assert newExpiry.isAfter(expiresAt.plus(29, ChronoUnit.DAYS));
    }

    @Test
    void extendPack_beforeWindow_rejected() {
        // Expires 30 days out — not yet within 14-day window
        Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
        UUID purchaseId = insertTestPurchase(coachId, expiresAt);

        assertThatThrownBy(() -> sessionPackPaymentService.extendPack(coachUserId, purchaseId))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.packExtensionNotEligible");
    }

    @Test
    void extendPack_afterExpiry_rejected() {
        // Already expired
        Instant expiresAt = Instant.now().minus(1, ChronoUnit.DAYS);
        UUID purchaseId = insertTestPurchase(coachId, expiresAt);

        assertThatThrownBy(() -> sessionPackPaymentService.extendPack(coachUserId, purchaseId))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.packExtensionNotEligible");
    }

    @Test
    void extendPack_doubleExtension_rejected() {
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        UUID purchaseId = insertTestPurchase(coachId, expiresAt);

        sessionPackPaymentService.extendPack(coachUserId, purchaseId);

        // Second extension should be rejected 409 (extendedAt is now set)
        assertThatThrownBy(() -> sessionPackPaymentService.extendPack(coachUserId, purchaseId))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);
    }

    @Test
    void extendPack_wrongCoach_rejected() {
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        UUID purchaseId = insertTestPurchase(coachId, expiresAt);

        // Different coach user
        long otherCoachUserId = 90002L;
        insertTestCoach(otherCoachUserId, "other_coach@test.com", "Other Coach");

        assertThatThrownBy(() -> sessionPackPaymentService.extendPack(otherCoachUserId, purchaseId))
            .hasMessageContaining("Coach does not own this session pack");
    }

    @Test
    void extendPack_concurrentRequests_noDuplicateExtension() throws Exception {
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS); // within 14-day window
        UUID purchaseId = insertTestPurchase(coachId, expiresAt);

        // Two threads behind a CyclicBarrier — both call extendPack(purchaseId) simultaneously.
        // findByIdForUpdate's PESSIMISTIC_WRITE serializes them: the loser blocks until the winner
        // commits, then reads extendedAt != null and is rejected with 409 — no double extension.
        int THREADS = 2;
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                sessionPackPaymentService.extendPack(coachUserId, purchaseId);
                return null;
            }));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS))
            .as("Both extendPack threads must complete within 60 seconds")
            .isTrue();

        int successes = 0;
        int conflictRejections = 0;
        for (Future<Void> f : futures) {
            try {
                f.get();
                successes++;
            } catch (ExecutionException ex) {
                Throwable root = ex.getCause();
                if (root instanceof ResponseStatusException rse && rse.getStatusCode() == HttpStatus.CONFLICT) {
                    conflictRejections++;
                } else {
                    throw ex; // unexpected exception — fail loudly
                }
            }
        }

        assertThat(successes).as("Exactly one extendPack() call must succeed").isEqualTo(1);
        assertThat(conflictRejections)
            .as("Exactly one extendPack() call must be rejected 409 as already-extended")
            .isEqualTo(1);

        Instant newExpiry = jdbcTemplate.queryForObject(
            "SELECT expires_at FROM payment.session_pack_purchases WHERE purchase_id = ?",
            Instant.class, purchaseId
        );
        assertThat(newExpiry).isNotNull();
        // Extended exactly once (+30 days), not twice (+60 days)
        assertThat(newExpiry).isAfter(expiresAt.plus(29, ChronoUnit.DAYS));
        assertThat(newExpiry).isBefore(expiresAt.plus(31, ChronoUnit.DAYS));
    }

    private UUID insertTestPurchase(UUID forCoachId, Instant expiresAt) {
        UUID tierId = UUID.randomUUID();
        UUID purchaseId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_tiers " +
                "(pack_tier_id, coach_id, label, session_count, total_price, price_per_session, is_active, version, created_at) " +
                "VALUES (?, ?, '5-Pack', 5, 150.00, 30.00, true, 0, now())",
                tierId, forCoachId
            );
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_purchases " +
                "(purchase_id, parent_id, coach_id, pack_tier_id, price_per_session, remaining_sessions, expires_at, version, created_at) " +
                "VALUES (?, 88001, ?, ?, 30.00, 5, ?, 0, now())",
                purchaseId, forCoachId, tierId, Timestamp.from(expiresAt)
            );
            return null;
        });
        return purchaseId;
    }
}
