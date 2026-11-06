package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttemptRepository;
import com.softropic.skillars.platform.payment.repo.StripePendingSubscriptionEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * skillars-deferred-169, against a real database: the partial unique index is the concurrency control for RS06 ("one open
 * attempt per parent"), the status writes are compare-and-set, and the parked-event / ungranted-upgrade writers commit
 * INDEPENDENTLY of the caller's transaction on the payment routed pool (RS05, RS07). No Stripe; uses no new mock beans.
 */
class PackPurchaseAttemptIT extends BasePaymentIT {

    private static final long PARENT = 97601L;
    private static final long OTHER_PARENT = 97602L;

    @Autowired SessionPackPurchaseAttemptRepository attempts;
    @Autowired StripePendingSubscriptionEventRepository pendingEvents;
    @Autowired PaymentRecoveryRecorder recorder;

    @BeforeEach
    @AfterEach
    void clean() {
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("DELETE FROM payment.session_pack_purchase_attempts WHERE parent_id IN (?, ?)", PARENT, OTHER_PARENT);
            jdbcTemplate.update("DELETE FROM payment.stripe_pending_subscription_events WHERE stripe_subscription_id LIKE 'sub_it169_%'");
            jdbcTemplate.update("DELETE FROM payment.subscription_upgrade_reconciliations WHERE stripe_subscription_id LIKE 'sub_it169_%'");
            return null;
        });
    }

    private SessionPackPurchaseAttempt newAttempt(long parent, String key) {
        return new SessionPackPurchaseAttempt(parent, key, UUID.randomUUID(), 5L, "pm_old", "pm_new", true);
    }

    @Test
    void theIndexAdmitsOneOpenAttemptPerParent_differentKeysIncluded() {
        attempts.saveAndFlush(newAttempt(PARENT, "key-aaaaaaaa"));

        assertThatThrownBy(() -> attempts.saveAndFlush(newAttempt(PARENT, "key-bbbbbbbb")))
            .isInstanceOf(DataIntegrityViolationException.class);
        // another parent is unaffected
        attempts.saveAndFlush(newAttempt(OTHER_PARENT, "key-aaaaaaaa"));
    }

    @Test
    void aTerminalAttemptDoesNotBlockTheNextOne_andOpenUnknownStillDoes() {
        SessionPackPurchaseAttempt first = attempts.saveAndFlush(newAttempt(PARENT, "key-aaaaaaaa"));

        assertThat(attempts.transition(first.getId(), Status.UNKNOWN, null, EnumSet.of(Status.IN_PROGRESS))).isTrue();
        assertThatThrownBy(() -> attempts.saveAndFlush(newAttempt(PARENT, "key-bbbbbbbb")))
            .as("UNKNOWN is open").isInstanceOf(DataIntegrityViolationException.class);

        assertThat(attempts.transition(first.getId(), Status.NO_CHARGE, null, EnumSet.of(Status.UNKNOWN))).isTrue();
        attempts.saveAndFlush(newAttempt(PARENT, "key-bbbbbbbb"));
    }

    @Test
    void theSameKeyTwiceViolatesTheUniqueConstraint() {
        attempts.saveAndFlush(newAttempt(PARENT, "key-aaaaaaaa"));
        attempts.transition(attempts.findByParentIdAndAttemptKey(PARENT, "key-aaaaaaaa").orElseThrow().getId(),
            Status.NO_CHARGE, null, EnumSet.of(Status.IN_PROGRESS));

        assertThatThrownBy(() -> attempts.saveAndFlush(newAttempt(PARENT, "key-aaaaaaaa")))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void statusWritesAreCompareAndSet_aStaleWriterNeverOverwritesATerminalState() {
        Long id = attempts.saveAndFlush(newAttempt(PARENT, "key-aaaaaaaa")).getId();

        assertThat(attempts.transition(id, Status.SUCCEEDED, "pi_1", EnumSet.of(Status.IN_PROGRESS))).isTrue();
        // a resolver that read IN_PROGRESS earlier now loses
        assertThat(attempts.transition(id, Status.NO_CHARGE, null, EnumSet.of(Status.IN_PROGRESS, Status.UNKNOWN))).isFalse();

        SessionPackPurchaseAttempt row = attempts.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(Status.SUCCEEDED);
        assertThat(row.getPaymentIntentId()).isEqualTo("pi_1");
    }

    @Test
    void proofOfCapture_mayMoveNoChargeToSucceeded() {
        Long id = attempts.saveAndFlush(newAttempt(PARENT, "key-aaaaaaaa")).getId();
        attempts.transition(id, Status.NO_CHARGE, null, EnumSet.of(Status.IN_PROGRESS));

        assertThat(attempts.transition(id, Status.SUCCEEDED, "pi_late",
            EnumSet.of(Status.IN_PROGRESS, Status.UNKNOWN, Status.UNRECONCILED, Status.NO_CHARGE))).isTrue();
        assertThat(attempts.findById(id).orElseThrow().getStatus()).isEqualTo(Status.SUCCEEDED);
    }

    @Test
    void purge_removesOldTerminalAttempts_andNeverAnOpenOne() {
        Long terminal = attempts.saveAndFlush(newAttempt(PARENT, "key-aaaaaaaa")).getId();
        attempts.transition(terminal, Status.SUCCEEDED, "pi_1", EnumSet.of(Status.IN_PROGRESS));
        Long open = attempts.saveAndFlush(newAttempt(OTHER_PARENT, "key-aaaaaaaa")).getId();
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE payment.session_pack_purchase_attempts SET updated_at = now() - interval '90 days' WHERE parent_id IN (?, ?)",
                PARENT, OTHER_PARENT);
            return null;
        });

        attempts.purgeTerminalOlderThan(java.time.Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS));

        assertThat(attempts.findById(terminal)).isEmpty();
        assertThat(attempts.findById(open)).isPresent();
    }

    @Test
    void erasure_nullsTheCardIds_butKeepsTheRow() {
        Long id = attempts.saveAndFlush(newAttempt(PARENT, "key-aaaaaaaa")).getId();

        attempts.nullCardsForParent(PARENT);

        SessionPackPurchaseAttempt row = attempts.findById(id).orElseThrow();
        assertThat(row.getPreviousCard()).isNull();
        assertThat(row.getAttemptedCard()).isNull();
    }

    // ── skillars-deferred-174 AC5/AC6 (RS06b, RS06e): the needs-operator mark and the admin close ──

    @Autowired SessionPackPaymentService sessionPackPaymentService;

    private void ageAttempts(long parent, String interval) {
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE payment.session_pack_purchase_attempts SET created_at = now() - CAST(? AS interval) "
                + "WHERE parent_id = ?", interval, parent);
            return null;
        });
    }

    @Test
    void needsOperator_isSetOnce_onlyOnAnOpenRow_andTheJobsWorkListSkipsIt() {
        Long marked = attempts.saveAndFlush(newAttempt(PARENT, "key-aaaaaaaa")).getId();
        Long other = attempts.saveAndFlush(newAttempt(OTHER_PARENT, "key-aaaaaaaa")).getId();
        ageAttempts(PARENT, "2 hours");
        ageAttempts(OTHER_PARENT, "2 hours");
        var open = Status.OPEN.stream().map(Enum::name).toList();

        assertThat(attempts.markNeedsOperator(marked, open)).isEqualTo(1);
        assertThat(attempts.markNeedsOperator(marked, open)).as("one-shot").isZero();
        assertThat(attempts.findById(marked).orElseThrow().getNeedsOperatorSince()).isNotNull();

        assertThat(attempts.findOpenOlderThan(Status.OPEN, java.time.Instant.now()))
            .extracting(SessionPackPurchaseAttempt::getId).contains(other).doesNotContain(marked);
        // still open: it keeps holding the parent's one open attempt
        assertThatThrownBy(() -> attempts.saveAndFlush(newAttempt(PARENT, "key-bbbbbbbb")))
            .isInstanceOf(DataIntegrityViolationException.class);

        attempts.transition(other, Status.SUCCEEDED, "pi_1", EnumSet.of(Status.IN_PROGRESS));
        assertThat(attempts.markNeedsOperator(other, open)).as("never on a closed row").isZero();
    }

    @Test
    void adminClose_nothingChargedPastTheAbsenceAge_closesAsNoCharge_recordsWhoAndWhy_andFreesTheParent() {
        Long id = attempts.saveAndFlush(newAttempt(PARENT, "key-aaaaaaaa")).getId();
        ageAttempts(PARENT, "2 hours");
        attempts.markNeedsOperator(id, Status.OPEN.stream().map(Enum::name).toList());

        var response = sessionPackPaymentService.closeAttemptByAdmin(id, 42L, "parent blocked; nothing in Stripe");

        assertThat(response.status()).isEqualTo("NO_CHARGE");
        SessionPackPurchaseAttempt row = attempts.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(Status.NO_CHARGE);
        assertThat(row.getClosedBy()).isEqualTo(42L);
        assertThat(row.getCloseReason()).isEqualTo("parent blocked; nothing in Stripe");
        assertThat(row.getNeedsOperatorSince()).as("cleared by the close").isNull();
        // AC5e: the partial unique index no longer holds the parent
        attempts.saveAndFlush(newAttempt(PARENT, "key-bbbbbbbb"));
    }

    @Test
    void adminClose_tooRecentToTrustTheAbsence_isRefused_rowUnchanged() {
        Long id = attempts.saveAndFlush(newAttempt(PARENT, "key-aaaaaaaa")).getId();

        assertThatThrownBy(() -> sessionPackPaymentService.closeAttemptByAdmin(id, 42L, "stuck"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("payment.packAttempt.tooRecent");

        SessionPackPurchaseAttempt row = attempts.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(Status.IN_PROGRESS);
        assertThat(row.getClosedBy()).isNull();
    }

    // ── RS05: the parked event commits independently of the webhook's own transaction ──

    private PaymentRecoveryRecorder.ParkedSubscriptionEvent parked(String eventId, String subId, long created) {
        return new PaymentRecoveryRecorder.ParkedSubscriptionEvent(eventId, subId, "customer.subscription.updated", created,
            "active", 1_800_000_000L, false, false);
    }

    @Test
    void aParkedEvent_survivesTheOuterTransactionRollingBack() {
        transactionTemplate.execute(status -> {
            assertThat(recorder.parkSubscriptionEvent(parked("evt_it169_1", "sub_it169_a", 100L))).isTrue();
            status.setRollbackOnly();
            return null;
        });

        assertThat(pendingEvents.findBySubscriptionOrdered("sub_it169_a")).hasSize(1);
    }

    @Test
    void parkingTheSameEventTwice_isOneRow_andDrainOrderIsOldestFirst() {
        recorder.parkSubscriptionEvent(parked("evt_it169_2", "sub_it169_b", 300L));
        recorder.parkSubscriptionEvent(parked("evt_it169_2", "sub_it169_b", 300L));
        recorder.parkSubscriptionEvent(parked("evt_it169_3", "sub_it169_b", 200L));

        var rows = pendingEvents.findBySubscriptionOrdered("sub_it169_b");

        assertThat(rows).extracting(r -> r.getEventId()).containsExactly("evt_it169_3", "evt_it169_2");
    }

    @Test
    void claim_isExactlyOnce() {
        recorder.parkSubscriptionEvent(parked("evt_it169_4", "sub_it169_c", 100L));
        Long id = pendingEvents.findBySubscriptionOrdered("sub_it169_c").get(0).getId();

        assertThat(pendingEvents.claim(id)).isEqualTo(1);
        assertThat(pendingEvents.claim(id)).isEqualTo(0);
    }

    // ── RS07: the ungranted-upgrade record cannot be rolled back with the failed request ──

    @Test
    void anUngrantedUpgradeRecord_survivesTheOuterTransactionRollingBack() {
        transactionTemplate.execute(status -> {
            recorder.recordUngrantedUpgrade("COACH", "coach-1", "sub_it169_d", "ACADEMY", "in_1");
            status.setRollbackOnly();
            return null;
        });

        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM payment.subscription_upgrade_reconciliations WHERE stripe_subscription_id = 'sub_it169_d'", Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
