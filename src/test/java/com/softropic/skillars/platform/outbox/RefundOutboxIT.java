package com.softropic.skillars.platform.outbox;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.outbox.repo.OutboxMessage;
import com.softropic.skillars.platform.outbox.repo.OutboxMessageRepository;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.service.RefundOutboxSupport;
import com.softropic.skillars.platform.security.SecurityIT;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-91 AC2 — a credit-wallet {@code BOOKING_REFUND} routed through the generic
 * outbox lands <strong>exactly once</strong>: a re-drive of an already-applied refund is a no-op
 * (handler existence-check), and a duplicate enqueue collapses to one ledger row (the handler check
 * + the {@code uq_pcl_reference_type} partial unique index).
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class RefundOutboxIT extends AbstractIntegrationTest {

    private static final long PARENT_ID = 9281_000_001L;

    @Autowired private OutboxService outboxService;
    @Autowired private OutboxMessageRepository outboxRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private final UUID bookingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // skillars-deferred-91 code review: scoped, not "DELETE FROM main.outbox_messages". An
        // unscoped truncate of a table the whole application writes to would delete rows other ITs
        // in this shared context/database had just enqueued, and drain() would then run their
        // messages through real handlers. Every row this class creates carries THIS booking id.
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "DELETE FROM main.outbox_messages WHERE aggregate_type = ? AND payload->>'bookingId' = ?",
                RefundOutboxSupport.AGGREGATE_TYPE, bookingId.toString());
            jdbcTemplate.update(
                "DELETE FROM payment.parent_credit_ledger WHERE parent_id = ?", PARENT_ID);
            return null;
        });
    }

    /** Outbox rows belonging to THIS test's booking — never a global count. */
    private long myOutboxRowCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.outbox_messages WHERE aggregate_type = ? AND payload->>'bookingId' = ?",
            Long.class, RefundOutboxSupport.AGGREGATE_TYPE, bookingId.toString());
    }

    private void enqueueRefund(BigDecimal amount) throws Exception {
        var payload = new RefundOutboxSupport.BookingRefundPayload(
            PARENT_ID, amount, bookingId, "Coach no-show — full refund");
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        transactionTemplate.execute(s -> outboxRepository.save(new OutboxMessage(RefundOutboxSupport.AGGREGATE_TYPE, json)));
    }

    private int refundRowCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.parent_credit_ledger WHERE reference_id = ? AND type = 'BOOKING_REFUND'",
            Integer.class, bookingId);
    }

    @Test
    void drain_appliesTheRefundOnce_andRemovesTheRow() throws Exception {
        enqueueRefund(new BigDecimal("50.00"));

        outboxService.drain();

        assertThat(refundRowCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT amount FROM payment.parent_credit_ledger WHERE reference_id = ? AND type = 'BOOKING_REFUND'",
            BigDecimal.class, bookingId)).isEqualByComparingTo("50.00");
        assertThat(myOutboxRowCount()).isZero();
    }

    @Test
    void redrivingTheSameRefund_isANoOp_stillExactlyOneLedgerRow() throws Exception {
        enqueueRefund(new BigDecimal("50.00"));
        outboxService.drain();
        assertThat(refundRowCount()).isEqualTo(1);

        // A second identical refund (e.g. the drain crashed before deleting the first outbox row).
        enqueueRefund(new BigDecimal("50.00"));
        outboxService.drain();

        assertThat(refundRowCount())
            .as("the handler's existence-check makes the re-drive a documented no-op")
            .isEqualTo(1);
        assertThat(myOutboxRowCount()).isZero();
    }

    /**
     * AC1's mandated round trip, through the <strong>producer API</strong> rather than a hand-inserted
     * row: {@code enqueue} + {@code requestDrainAfterCommit()} inside a transaction, commit, and the
     * {@code @TransactionalEventListener(AFTER_COMMIT)} drain does the work. Added by the
     * skillars-deferred-91 code review — both original ITs bypassed this path entirely, so the single
     * most load-bearing piece of AC1 had no integration coverage at all.
     *
     * <p>The listener is {@code @Async}, so the assertion is under Awaitility rather than immediate.
     */
    @Test
    void producerEnqueueThenCommit_firesTheAfterCommitDrain_withNoExplicitDrainCall() throws Exception {
        var payload = new RefundOutboxSupport.BookingRefundPayload(
            PARENT_ID, new BigDecimal("25.00"), bookingId, "Coach no-show — full refund");
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);

        transactionTemplate.execute(s -> {
            outboxService.enqueue(RefundOutboxSupport.AGGREGATE_TYPE, json);
            outboxService.requestDrainAfterCommit();
            return null;
        });

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20))
            .untilAsserted(() -> {
                assertThat(refundRowCount()).isEqualTo(1);
                assertThat(myOutboxRowCount()).isZero();
            });
    }

    /**
     * AC1's mandated forced-failure round trip: a handler failure leaves the row with
     * {@code attempts++} / {@code last_error} and a future {@code next_attempt_at}, and the row is
     * re-driven successfully once that backoff is cleared. Also proves decision D2 — the failure
     * bookkeeping commits even though the failing handler ran in a rolled-back transaction.
     */
    @Test
    void forcedFailure_incrementsAttemptsAndBacksOff_thenRedrivesSuccessfully() {
        // A payload the refund handler cannot deserialise: it throws, the claiming transaction rolls
        // back, and recordFailure writes attempts=1 in its own transaction.
        transactionTemplate.execute(s -> outboxRepository.save(
            new OutboxMessage(RefundOutboxSupport.AGGREGATE_TYPE,
                "{\"bookingId\":\"" + bookingId + "\",\"parentId\":\"not-a-number\"}")));

        outboxService.drain();

        var stuck = jdbcTemplate.queryForMap(
            "SELECT attempts, last_error, next_attempt_at FROM main.outbox_messages "
                + "WHERE aggregate_type = ? AND payload->>'bookingId' = ?",
            RefundOutboxSupport.AGGREGATE_TYPE, bookingId.toString());
        assertThat(((Number) stuck.get("attempts")).intValue())
            .as("attempts must be recorded even though the handler's transaction rolled back")
            .isEqualTo(1);
        assertThat((String) stuck.get("last_error")).isNotBlank();
        assertThat(refundRowCount()).as("nothing was credited").isZero();

        // The row is not immediately re-claimable — that is the D6 backoff doing its job.
        outboxService.drain();
        assertThat(((Number) jdbcTemplate.queryForObject(
            "SELECT attempts FROM main.outbox_messages WHERE aggregate_type = ? AND payload->>'bookingId' = ?",
            Number.class, RefundOutboxSupport.AGGREGATE_TYPE, bookingId.toString())).intValue())
            .as("still 1 — the backoff kept it out of the claim window")
            .isEqualTo(1);

        // Clear the backoff and replace the payload with a valid one: the re-drive now succeeds.
        // Wrapped in transactionTemplate deliberately — this datasource runs with
        // hikari.auto-commit: false (application.yaml:77, so Hibernate can group statements), which
        // means a bare jdbcTemplate write outside a Spring transaction is NEVER committed: the
        // connection goes back to the pool with the change still open and it is silently discarded.
        // update() still reports 1 row affected, so the mistake is invisible without a read-back.
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "UPDATE main.outbox_messages SET next_attempt_at = now() - interval '1 minute', "
                + "payload = ?::jsonb WHERE aggregate_type = ? AND payload->>'bookingId' = ?",
            "{\"parentId\":" + PARENT_ID + ",\"amount\":50.00,\"bookingId\":\"" + bookingId
                + "\",\"description\":\"Coach no-show — full refund\"}",
            RefundOutboxSupport.AGGREGATE_TYPE, bookingId.toString()));

        outboxService.drain();

        assertThat(refundRowCount()).isEqualTo(1);
        assertThat(myOutboxRowCount()).isZero();
    }
}
