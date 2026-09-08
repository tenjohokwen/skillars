package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.payment.contract.CreditStatementEntryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-102 AC11 — real-DB coverage for the running-balance opening anchor in
 * {@code RevenueReportingService.getCreditStatement}.
 *
 * <p>The bug ({@code skillars-7-5} D1): the opening balance summed rows with a strict
 * {@code createdAt < :before}, so a prior-page row sharing an identical {@code createdAt} with the
 * current page's oldest row was silently dropped, understating the page's running balance.
 *
 * <p>The fix anchors on {@code (createdAt, txId)} — the same order the page query now uses
 * ({@code ORDER BY createdAt DESC, txId DESC}) — via
 * {@code sumByParentIdBeforeAnchor(parentId, createdAt, txId)} with
 * {@code createdAt < :c OR (createdAt = :c AND txId < :txId)}.
 *
 * <p>Fixture: five rows for one parent, three of them at the SAME instant {@code T2}, with explicit
 * {@code tx_id}s so the {@code DESC, DESC} order is deterministic:
 * <pre>
 *   rTop (T3, tx …05)   ─ page 0
 *   rA   (T2, tx …04)   ─ page 0, sorts BEFORE the page's oldest row  → must NOT be in opening balance
 *   rB   (T2, tx …03)   ─ page 0, the oldest row on the page (the anchor)
 *   rC   (T2, tx …02)   ─ page 1, sorts AFTER the anchor at the same instant → MUST be in opening balance
 *   r1   (T1, tx …01)   ─ page 1
 * </pre>
 * Page size 3. Correct opening balance for page 0 = {@code rC + r1 = 5 + 10 = 15}.
 *
 * <ul>
 *   <li><b>no-fix</b> (strict {@code createdAt < T2}) drops {@code rC} → opening 10 → anchor balance 30 (not 35)</li>
 *   <li><b>wrong sign</b> ({@code txId > :txId}) pulls in {@code rA} (same page) → opening 17 → anchor balance 37 (not 35)</li>
 * </ul>
 */
class CreditStatementRunningBalanceAnchorIT extends AbstractIntegrationTest {

    private static final long PARENT_ID = 91_02_11_0001L;

    private static final UUID R1  = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RC  = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID RB  = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID RA  = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID RTOP = UUID.fromString("00000000-0000-0000-0000-000000000005");

    private static final Instant T1 = Instant.parse("2026-06-01T08:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-10T09:00:00Z"); // the shared instant
    private static final Instant T3 = Instant.parse("2026-06-20T10:00:00Z");

    private static final Instant FROM = Instant.parse("2026-05-01T00:00:00Z");
    private static final Instant TO   = Instant.parse("2026-07-01T00:00:00Z");

    @Autowired private RevenueReportingService revenueReportingService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @BeforeEach
    void seed() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM payment.parent_credit_ledger WHERE parent_id = ?", PARENT_ID);
            insert(R1,  T1, "10.00");
            insert(RC,  T2, "5.00");
            insert(RB,  T2, "20.00");
            insert(RA,  T2, "7.00");
            insert(RTOP, T3, "40.00");
            return null;
        });
    }

    @Test
    void openingBalance_includesPriorPageTwinAtSameInstant_notSamePageSibling() {
        Page<CreditStatementEntryDto> page0 =
            revenueReportingService.getCreditStatement(PARENT_ID, FROM, TO, PageRequest.of(0, 3));

        assertThat(page0.getContent()).hasSize(3);
        // DESC order: rTop, rA, rB
        assertThat(page0.getContent().get(0).txId()).isEqualTo(RTOP);
        assertThat(page0.getContent().get(2).txId()).isEqualTo(RB);

        // opening balance = rC(5) + r1(10) = 15
        // rB balance  = 15 + 20 = 35
        // rA balance  = 35 + 7  = 42
        // rTop balance= 42 + 40 = 82
        assertThat(page0.getContent().get(2).runningBalance())
            .as("anchor (oldest-on-page) balance must include the prior-page createdAt-twin rC "
                + "and exclude the same-page sibling rA")
            .isEqualByComparingTo("35.00");
        assertThat(page0.getContent().get(1).runningBalance()).isEqualByComparingTo("42.00");
        assertThat(page0.getContent().get(0).runningBalance()).isEqualByComparingTo("82.00");
    }

    @Test
    void pageQueryOrderIsStableAcrossCalls() {
        var first  = revenueReportingService.getCreditStatement(PARENT_ID, FROM, TO, PageRequest.of(0, 5));
        var second = revenueReportingService.getCreditStatement(PARENT_ID, FROM, TO, PageRequest.of(0, 5));
        assertThat(first.getContent().stream().map(CreditStatementEntryDto::txId).toList())
            .as("createdAt DESC, txId DESC — deterministic tie order")
            .containsExactly(RTOP, RA, RB, RC, R1)
            .isEqualTo(second.getContent().stream().map(CreditStatementEntryDto::txId).toList());
    }

    private void insert(UUID txId, Instant createdAt, String amount) {
        jdbcTemplate.update(
            "INSERT INTO payment.parent_credit_ledger (tx_id, parent_id, amount, type, description, created_at) "
                + "VALUES (?, ?, ?::numeric, 'BOOKING_REFUND', 'anchor-it', ?)",
            txId, PARENT_ID, amount, Timestamp.from(createdAt));
    }
}
