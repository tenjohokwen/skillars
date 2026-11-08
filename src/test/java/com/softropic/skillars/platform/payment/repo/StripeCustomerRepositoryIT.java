package com.softropic.skillars.platform.payment.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// AC3 (skillars-deferred-57): no format-guard CHECK constraint existed on
// payment.stripe_customers.stripe_customer_id — V100/V101 add one. This IT proves the constraint
// actually rejects a non-"cus_"-prefixed value and allows a valid one.
class StripeCustomerRepositoryIT extends AbstractIntegrationTest {

    @Autowired private StripeCustomerRepository stripeCustomerRepository;

    @Test
    void validCusPrefixedId_saves() {
        StripeCustomer sc = new StripeCustomer();
        sc.setParentId(9_640_000_001L);
        sc.setStripeCustomerId("cus_valid_test_id");
        sc.setCreatedAt(Instant.now());

        try {
            assertThatCode(() -> stripeCustomerRepository.saveAndFlush(sc)).doesNotThrowAnyException();
        } finally {
            if (stripeCustomerRepository.existsById(9_640_000_001L)) {
                stripeCustomerRepository.deleteById(9_640_000_001L);
            }
        }
    }

    @Test
    void nonCusPrefixedId_throwsDataIntegrity() {
        StripeCustomer sc = new StripeCustomer();
        sc.setParentId(9_640_000_002L);
        sc.setStripeCustomerId("acct_wrong_prefix");
        sc.setCreatedAt(Instant.now());

        assertThatThrownBy(() -> stripeCustomerRepository.saveAndFlush(sc))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── skillars-deferred-174 AC2/AC4: targeted card-column writes ─────────────────────────────────────────────

    private static final long PAYER = 9_640_000_003L;

    private void savePayer(String card) {
        StripeCustomer sc = new StripeCustomer();
        sc.setParentId(PAYER);
        sc.setStripeCustomerId("cus_payer_174");
        sc.setStripePaymentMethodId(card);
        stripeCustomerRepository.saveAndFlush(sc);
    }

    @org.junit.jupiter.api.AfterEach
    void removePayer() {
        if (stripeCustomerRepository.existsById(PAYER)) {
            stripeCustomerRepository.deleteById(PAYER);
        }
    }

    private String cardOf() {
        return stripeCustomerRepository.findById(PAYER).orElseThrow().getStripePaymentMethodId();
    }

    /** AC2: a card saved between the gateway's read and its post-capture write survives (a full-row save undid it). */
    @Test
    void lastIntentUpdate_keepsACardSavedSinceTheGatewayReadTheRow() {
        savePayer("pm_charged");
        StripeCustomer readByTheGateway = stripeCustomerRepository.findById(PAYER).orElseThrow();
        stripeCustomerRepository.revertPaymentMethod(PAYER, "pm_saved_meanwhile", "pm_charged"); // any concurrent card write

        stripeCustomerRepository.updateLastPaymentIntentId(readByTheGateway.getParentId(), "pi_captured");

        StripeCustomer row = stripeCustomerRepository.findById(PAYER).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(row.getStripePaymentMethodId()).isEqualTo("pm_saved_meanwhile");
        org.assertj.core.api.Assertions.assertThat(row.getLastPaymentIntentId()).isEqualTo("pi_captured");
    }

    /** The defect AC2 fixes, shown on the old write path: saving the entity read before the change brings the old card back. */
    @Test
    void fullRowSaveOfAStaleEntity_wouldUndoTheCardSavedMeanwhile() {
        savePayer("pm_charged");
        StripeCustomer readByTheGateway = stripeCustomerRepository.findById(PAYER).orElseThrow();
        stripeCustomerRepository.revertPaymentMethod(PAYER, "pm_saved_meanwhile", "pm_charged");

        readByTheGateway.setLastPaymentIntentId("pi_captured");
        stripeCustomerRepository.save(readByTheGateway);

        org.assertj.core.api.Assertions.assertThat(cardOf()).isEqualTo("pm_charged");
    }

    /** AC4: the success-path re-apply accepts the attempted or the previous card, and keeps any other. */
    @Test
    void reapply_acceptsTheAttemptedOrThePreviousCard_andKeepsAThirdOne() {
        java.util.function.IntSupplier reapply = () -> transactionTemplate.execute(
            s -> stripeCustomerRepository.reapplyPaymentMethod(PAYER, "pm_old", "pm_new"));

        savePayer("pm_new"); // normal success: the purchase wrote it before the charge
        org.assertj.core.api.Assertions.assertThat(reapply.getAsInt()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(cardOf()).isEqualTo("pm_new");
        removePayer();

        savePayer("pm_old"); // the resolver reverted it meanwhile
        org.assertj.core.api.Assertions.assertThat(reapply.getAsInt()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(cardOf()).isEqualTo("pm_new");
        removePayer();

        savePayer("pm_third"); // the payer saved another card during the purchase
        org.assertj.core.api.Assertions.assertThat(reapply.getAsInt()).isZero();
        org.assertj.core.api.Assertions.assertThat(cardOf()).isEqualTo("pm_third");
    }

    @Test
    void reapply_withNoPreviousCard_matchesAnEmptyColumn() {
        savePayer(null);

        int updated = transactionTemplate.execute(s -> stripeCustomerRepository.reapplyPaymentMethod(PAYER, null, "pm_new"));

        org.assertj.core.api.Assertions.assertThat(updated).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(cardOf()).isEqualTo("pm_new");
    }

    // ── skillars-deferred-176 review round 5: the card sync's lock key is read without caching the row ─────────────

    /** What the card sync's superseded check reads, inside one transaction, after another transaction saved a newer card. */
    private String cardSeenAfterAConcurrentSave(java.util.function.Consumer<Long> readBeforeTheLock) throws Exception {
        savePayer("pm_old");
        return transactionTemplate.execute(s -> {
            readBeforeTheLock.accept(PAYER);
            java.util.concurrent.CompletableFuture.runAsync(
                () -> commit("UPDATE payment.stripe_customers SET stripe_payment_method_id = 'pm_newer' WHERE parent_id = ?", PAYER))
                .join();
            return cardOf();
        });
    }

    @Test
    void theLockKeyQuery_cachesNoRow_soTheCheckAfterTheLockSeesTheNewerCard() throws Exception {
        org.assertj.core.api.Assertions.assertThat(
            cardSeenAfterAConcurrentSave(stripeCustomerRepository::findStripeCustomerIdByParentId)).isEqualTo("pm_newer");
    }

    /** The defect: loading the entity for the lock key left the pre-wait copy for the check, so the older card won. */
    @Test
    void loadingTheEntityForTheLockKey_wouldLeaveTheCheckAStaleCard() throws Exception {
        org.assertj.core.api.Assertions.assertThat(
            cardSeenAfterAConcurrentSave(stripeCustomerRepository::findById)).isEqualTo("pm_old");
    }

    // ── skillars-deferred-176 AC3 (RS09h): a card reapply racing a GDPR erasure ────────────────────────────────

    private static final long ERASED = 9_640_000_176L;

    @Autowired private com.softropic.skillars.platform.payment.service.SubscriptionService subscriptionService;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private void commit(String sql, Object... args) {
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(sql, args));
    }

    @Autowired private com.softropic.skillars.platform.payment.service.StripeCustomerErasureOutboxSupport erasureSupport;

    /**
     * Round-1 V2: the real {@code scheduleErasure} on a card that is already NULL must still lock the row (a save of the
     * unchanged entity issued no UPDATE), so a reapply waits on it, then sees the tombstone and writes no card.
     */
    @Test
    void aReapplyRacingTheRealErasureOfAnAlreadyEmptyCard_waits_andWritesNoCard() throws Exception {
        commit("INSERT INTO main.\"user\" (id, login, login_id_type, password_hash, activated, first_name, last_name, gender, dob, email) "
            + "VALUES (?, 'it176@test.com', 'EMAIL', '{noop}x', true, 'T', 'P', 'MALE', '1985-01-01', 'it176@test.com')", ERASED);
        commit("INSERT INTO payment.stripe_customers (parent_id, stripe_customer_id) VALUES (?, 'cus_it176_erased')", ERASED);
        java.util.concurrent.CountDownLatch erasing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        String tombstone = "deleted." + ERASED + "@erased.io";
        try {
            java.util.concurrent.Future<?> erasure = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.update("UPDATE main.\"user\" SET email = ?, login = ? WHERE id = ?", tombstone, tombstone, ERASED);
                erasureSupport.scheduleErasure(ERASED);
                erasing.countDown();
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            erasing.await(10, java.util.concurrent.TimeUnit.SECONDS);
            java.util.concurrent.Future<Boolean> reapply = pool.submit(
                () -> subscriptionService.reapplyPaymentMethodAndScheduleSync(ERASED, null, "pm_after_erasure"));
            waitUntilABackendWaitsOnALock();

            release.countDown();
            erasure.get(10, java.util.concurrent.TimeUnit.SECONDS);

            org.assertj.core.api.Assertions.assertThat(reapply.get(10, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
            org.assertj.core.api.Assertions.assertThat(
                stripeCustomerRepository.findById(ERASED).orElseThrow().getStripePaymentMethodId()).isNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
            commit("DELETE FROM main.outbox_messages WHERE aggregate_type = ? AND payload->>'stripeCustomerId' = ?",
                com.softropic.skillars.platform.payment.service.StripeCustomerErasureOutboxSupport.AGGREGATE_TYPE, "cus_it176_erased");
            commit("DELETE FROM payment.stripe_customers WHERE parent_id = ?", ERASED);
            commit("DELETE FROM main.\"user\" WHERE id = ?", ERASED);
        }
    }

    /**
     * The erasure NULLs the card and sets the tombstone in one transaction. A reapply with a NULL previous card that
     * blocks on that row would match the NULL afterwards; it must instead see the committed tombstone and write nothing.
     */
    @Test
    void aReapplyWaitingOnAnErasure_seesTheTombstone_andWritesNoCard() throws Exception {
        commit("INSERT INTO main.\"user\" (id, login, login_id_type, password_hash, activated, first_name, last_name, gender, dob, email) "
            + "VALUES (?, 'it176@test.com', 'EMAIL', '{noop}x', true, 'T', 'P', 'MALE', '1985-01-01', 'it176@test.com')", ERASED);
        commit("INSERT INTO payment.stripe_customers (parent_id, stripe_customer_id) VALUES (?, 'cus_it176_erased')", ERASED);
        java.util.concurrent.CountDownLatch erasing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        String tombstone = "deleted." + ERASED + "@erased.io";
        try {
            java.util.concurrent.Future<?> erasure = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.update("UPDATE main.\"user\" SET email = ?, login = ? WHERE id = ?", tombstone, tombstone, ERASED);
                jdbcTemplate.update("UPDATE payment.stripe_customers SET stripe_payment_method_id = NULL WHERE parent_id = ?", ERASED);
                erasing.countDown();
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            erasing.await(10, java.util.concurrent.TimeUnit.SECONDS);
            java.util.concurrent.Future<Boolean> reapply = pool.submit(
                () -> subscriptionService.reapplyPaymentMethodAndScheduleSync(ERASED, null, "pm_after_erasure"));
            waitUntilABackendWaitsOnALock();

            release.countDown();
            erasure.get(10, java.util.concurrent.TimeUnit.SECONDS);

            org.assertj.core.api.Assertions.assertThat(reapply.get(10, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
            org.assertj.core.api.Assertions.assertThat(
                stripeCustomerRepository.findById(ERASED).orElseThrow().getStripePaymentMethodId()).isNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
            commit("DELETE FROM payment.stripe_customers WHERE parent_id = ?", ERASED);
            commit("DELETE FROM main.\"user\" WHERE id = ?", ERASED);
        }
    }

    // ── skillars-deferred-176 review round 6 ───────────────────────────────────────────────────────────────────

    @Autowired private com.softropic.skillars.platform.payment.service.StripeCustomerEmailSyncOutboxSupport emailSyncSupport;

    private static final String TOMBSTONE = "deleted." + ERASED + "@erased.io";

    private void insertErasableUser() {
        commit("INSERT INTO main.\"user\" (id, login, login_id_type, password_hash, activated, first_name, last_name, gender, dob, email) "
            + "VALUES (?, 'it176@test.com', 'EMAIL', '{noop}x', true, 'T', 'P', 'MALE', '1985-01-01', 'it176@test.com')", ERASED);
    }

    /** An erasure that has written the tombstone and run {@code scheduleErasure}, held open until {@code release}. */
    private java.util.concurrent.Future<?> openErasure(java.util.concurrent.ExecutorService pool,
                                                       java.util.concurrent.CountDownLatch erasing,
                                                       java.util.concurrent.CountDownLatch release) {
        return pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("UPDATE main.\"user\" SET email = ?, login = ? WHERE id = ?", TOMBSTONE, TOMBSTONE, ERASED);
            erasureSupport.scheduleErasure(ERASED);
            erasing.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    private void removeErasableUser(String stripeCustomerId) {
        commit("DELETE FROM main.outbox_messages WHERE aggregate_type = ? AND payload->>'stripeCustomerId' = ?",
            com.softropic.skillars.platform.payment.service.StripeCustomerErasureOutboxSupport.AGGREGATE_TYPE, stripeCustomerId);
        commit("DELETE FROM main.outbox_messages WHERE aggregate_type = ? AND payload->>'userId' = ?",
            com.softropic.skillars.platform.payment.service.PaymentMethodSyncOutboxSupport.AGGREGATE_TYPE, String.valueOf(ERASED));
        commit("DELETE FROM payment.stripe_customers WHERE parent_id = ?", ERASED);
        commit("DELETE FROM main.\"user\" WHERE id = ?", ERASED);
    }

    /** V1: the save-card endpoint's write waits on an erasure, then sees the tombstone and writes no card and no sync. */
    @Test
    void aCardSaveRacingAnErasure_waits_andWritesNoCardOrSync() throws Exception {
        insertErasableUser();
        commit("INSERT INTO payment.stripe_customers (parent_id, stripe_customer_id, stripe_payment_method_id) "
            + "VALUES (?, 'cus_it176_erased', 'pm_before')", ERASED);
        java.util.concurrent.CountDownLatch erasing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> erasure = openErasure(pool, erasing, release);
            erasing.await(10, java.util.concurrent.TimeUnit.SECONDS);
            java.util.concurrent.Future<?> save = pool.submit(
                () -> subscriptionService.savePaymentMethodAndScheduleSync(ERASED, "pm_after_erasure"));
            waitUntilABackendWaitsOnALock();

            release.countDown();
            erasure.get(10, java.util.concurrent.TimeUnit.SECONDS);
            save.get(10, java.util.concurrent.TimeUnit.SECONDS);

            org.assertj.core.api.Assertions.assertThat(
                stripeCustomerRepository.findById(ERASED).orElseThrow().getStripePaymentMethodId()).isNull();
            org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM main.outbox_messages WHERE aggregate_type = ? AND payload->>'userId' = ?", Integer.class,
                com.softropic.skillars.platform.payment.service.PaymentMethodSyncOutboxSupport.AGGREGATE_TYPE,
                String.valueOf(ERASED))).isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
            removeErasableUser("cus_it176_erased");
        }
    }

    /**
     * V2: the erasure found no customer row, then a first customer is created while it is still open. The post-creation
     * check waits for the erasure, sees the tombstone, and schedules the Stripe erasure the erasure itself could not.
     */
    @Test
    void aCustomerCreatedDuringAnErasure_getsItsStripeErasureScheduled() throws Exception {
        insertErasableUser();
        java.util.concurrent.CountDownLatch erasing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> erasure = openErasure(pool, erasing, release);
            erasing.await(10, java.util.concurrent.TimeUnit.SECONDS);
            commit("INSERT INTO payment.stripe_customers (parent_id, stripe_customer_id) VALUES (?, 'cus_it176_late')", ERASED);
            java.util.concurrent.Future<?> check = pool.submit(
                () -> emailSyncSupport.syncIfEmailChangedSince(ERASED, "it176@test.com"));
            waitUntilABackendWaitsOnALock();

            release.countDown();
            erasure.get(10, java.util.concurrent.TimeUnit.SECONDS);
            check.get(10, java.util.concurrent.TimeUnit.SECONDS);

            org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM main.outbox_messages WHERE aggregate_type = ? AND payload->>'stripeCustomerId' = ?",
                Integer.class, com.softropic.skillars.platform.payment.service.StripeCustomerErasureOutboxSupport.AGGREGATE_TYPE,
                "cus_it176_late")).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
            removeErasableUser("cus_it176_late");
        }
    }

    // ── skillars-deferred-177 (RS03i): the sweep schedules an erasure that never reached the outbox ─────────────

    @Autowired private com.softropic.skillars.platform.payment.service.StripeCustomerErasureSweep erasureSweep;

    private int erasureRowsFor(String stripeCustomerId) {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.outbox_messages WHERE aggregate_type = ? AND payload->>'stripeCustomerId' = ?",
            Integer.class, com.softropic.skillars.platform.payment.service.StripeCustomerErasureOutboxSupport.AGGREGATE_TYPE,
            stripeCustomerId);
    }

    @Test
    void theSweep_schedulesTheErasureOfAnErasedPayerWhoseRowWasNeverMarked_once() {
        commit("INSERT INTO main.\"user\" (id, login, login_id_type, password_hash, activated, first_name, last_name, gender, dob, email) "
            + "VALUES (?, ?, 'EMAIL', '{noop}x', false, 'Deleted', 'User', 'MALE', '1970-01-01', ?)", ERASED, TOMBSTONE, TOMBSTONE);
        commit("INSERT INTO payment.stripe_customers (parent_id, stripe_customer_id, stripe_payment_method_id) "
            + "VALUES (?, 'cus_it177_missed', 'pm_left_behind')", ERASED);
        try {
            erasureSweep.scheduleMissedErasures();

            org.assertj.core.api.Assertions.assertThat(erasureRowsFor("cus_it177_missed")).isEqualTo(1);
            StripeCustomer row = stripeCustomerRepository.findById(ERASED).orElseThrow();
            org.assertj.core.api.Assertions.assertThat(row.getErasureScheduledAt()).isNotNull();
            org.assertj.core.api.Assertions.assertThat(row.getStripePaymentMethodId()).isNull();

            erasureSweep.scheduleMissedErasures(); // marked now: not enqueued again
            org.assertj.core.api.Assertions.assertThat(erasureRowsFor("cus_it177_missed")).isEqualTo(1);
        } finally {
            removeErasableUser("cus_it177_missed");
        }
    }

    @Test
    void theSweep_leavesALivePayerAlone() {
        insertErasableUser();
        commit("INSERT INTO payment.stripe_customers (parent_id, stripe_customer_id) VALUES (?, 'cus_it177_live')", ERASED);
        try {
            erasureSweep.scheduleMissedErasures();

            org.assertj.core.api.Assertions.assertThat(erasureRowsFor("cus_it177_live")).isZero();
            org.assertj.core.api.Assertions.assertThat(
                stripeCustomerRepository.findById(ERASED).orElseThrow().getErasureScheduledAt()).isNull();
        } finally {
            removeErasableUser("cus_it177_live");
        }
    }

    private void waitUntilABackendWaitsOnALock() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer waiting = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the reapply never waited on the row lock");
    }
}
