package com.softropic.skillars.platform.outbox;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport;
import com.softropic.skillars.utils.TestMailManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * skillars-deferred-92 AC4 — {@code NotificationOutboxSupport.enqueueEmail} writes its outbox row
 * <em>inside</em> the producing business transaction, so the email and the work that promised it
 * commit together or not at all.
 *
 * <h2>Why the obvious test is not here</h2>
 *
 * AC4.6 is explicit about this, and it is worth restating because this repository has recorded the
 * same antipattern three times before. "The business transaction throws before commit ⇒ zero outbox
 * rows" is <strong>trivially true and passes identically before and after this change</strong>: the
 * old {@code AFTER_COMMIT} listener did not fire on a rollback either. Such a test cannot fail in
 * either direction, so it proves nothing.
 *
 * <p>The assertion that actually distinguishes the two designs is
 * {@link #rowIsWrittenDuringBeforeCommit_thenTheCommitFails_leavesNothing()}: the row must be
 * observably present <em>during</em> {@code beforeCommit}, and gone once that commit is aborted.
 * Under the old {@code REQUIRES_NEW} design the row was written and committed in a suspended
 * transaction of its own, so it would have <em>survived</em> the outer rollback — the exact
 * non-atomicity AC4 exists to close.
 *
 * <p><strong>Chosen mechanism</strong> (AC4.6 offers three; this is the third): a
 * {@link TransactionSynchronization} registered after the enqueue whose own {@code beforeCommit}
 * throws. It needs no schema change and no serialization-conflict choreography. Its one risk is
 * synchronization ordering, and the test closes that itself — the synchronization asserts the row is
 * already there <em>before</em> it throws, so if it ever ran first the test fails loudly instead of
 * passing for the wrong reason.
 */
class NotificationEmailOutboxAtomicityIT extends AbstractIntegrationTest {

    @Autowired private NotificationOutboxSupport notificationOutboxSupport;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TestMailManager testMailManager;

    private final String toAddress = "ac4." + UUID.randomUUID() + "@skillars-test.com";

    private int myRows() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.outbox_messages WHERE aggregate_type = ? AND payload::text LIKE ?",
            Integer.class, NotificationOutboxSupport.AGGREGATE_TYPE, "%" + toAddress + "%");
    }

    private void enqueue() {
        Recipient recipient = new Recipient();
        recipient.setEmail(toAddress);
        recipient.setLangKey("en");
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", "AC4 Coach");
        data.put("requestedStartTime", "2026-09-05T10:00:00Z");
        data.put("canonicalTimezone", "Europe/Berlin");
        data.put("reminderType", "PRIMARY");
        notificationOutboxSupport.enqueueEmail(
            EmailTemplate.BOOKING_REMINDER, recipient, data, UUID.randomUUID().toString());
    }

    @Test
    @DisplayName("a committing transaction leaves exactly one outbox row, and the drain still fires after it")
    void commit_writesExactlyOneRow_andDrains() {
        testMailManager.clear();
        AtomicInteger rowsAtCompletion = new AtomicInteger(-1);

        transactionTemplate.executeWithoutResult(status -> {
            enqueue();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCompletion() {
                    rowsAtCompletion.set(myRows());
                }
            });
        });

        assertThat(rowsAtCompletion.get())
            .as("exactly one row must exist in the producing transaction at commit time")
            .isEqualTo(1);

        // AC4.7. requestDrainAfterCommit() registers an AFTER_COMMIT synchronization, and it is now
        // registered from inside beforeCommit — a new path. Spring's triggerBeforeCommit iterates a
        // *copy* of the synchronization list and triggerAfterCommit re-reads it, so the late
        // registration is picked up; this asserts that rather than assuming it. Under the test
        // profile outboxDrainPool is a SyncTaskExecutor, so the drain has run by the time we return.
        assertThat(testMailManager.getEnvelopes().values())
            .as("the AFTER_COMMIT drain must still fire for a BEFORE_COMMIT enqueue")
            .anyMatch(e -> e.recipients().stream().anyMatch(r -> toAddress.equals(r.getEmail())));
        assertThat(myRows()).as("a drained row is deleted").isZero();
    }

    @Test
    @DisplayName("the row exists during beforeCommit, and a failing commit leaves none of it behind")
    void rowIsWrittenDuringBeforeCommit_thenTheCommitFails_leavesNothing() {
        testMailManager.clear();
        AtomicInteger rowsSeenByTheLaterSynchronization = new AtomicInteger(-1);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            enqueue();
            // Registered AFTER the enqueue, so it runs after it: TransactionSynchronizationManager
            // sorts synchronizations with a STABLE OrderComparator and both are LOWEST_PRECEDENCE,
            // so registration order is preserved. The assertion below is what makes that safe to rely
            // on — if this ever ran first, the count would be 0 and the test would fail.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    rowsSeenByTheLaterSynchronization.set(myRows());
                    throw new IllegalStateException("AC4: deliberate commit-time failure");
                }
            });
        })).isInstanceOf(IllegalStateException.class)
           .hasMessageContaining("deliberate commit-time failure");

        assertThat(rowsSeenByTheLaterSynchronization.get())
            .as("""
                The enqueue must be visible INSIDE the producing transaction — that is the whole \
                claim of AC4. 0 means either the enqueue opened a transaction of its own (the old \
                REQUIRES_NEW design) or this synchronization ran before it, and -1 means beforeCommit \
                never ran at all.""")
            .isEqualTo(1);

        assertThat(myRows())
            .as("""
                The commit failed, so the outbox row must be gone with the business work. Under the \
                old REQUIRES_NEW design it would have survived here, because it had already been \
                committed in a suspended transaction of its own — that surviving row is precisely \
                the non-atomicity this AC removes.""")
            .isZero();
        assertThat(testMailManager.getEnvelopes().values())
            .as("and nothing may have been sent for a transaction that never committed")
            .noneMatch(e -> e.recipients().stream().anyMatch(r -> toAddress.equals(r.getEmail())));
    }

    /**
     * AC4.1 chose {@link org.springframework.transaction.annotation.Propagation#MANDATORY} over
     * {@code REQUIRED} precisely so this fails instead of quietly opening a transaction of its own and
     * reintroducing the window. That choice is only meaningful if something proves it.
     */
    @Test
    @DisplayName("enqueueEmail outside any transaction fails loudly rather than opening its own")
    void enqueueOutsideATransaction_isRejected() {
        assertThatThrownBy(this::enqueue)
            .as("MANDATORY must reject a caller with no ambient business transaction")
            .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(myRows()).isZero();
    }
}
