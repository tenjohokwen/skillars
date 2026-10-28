package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * feat/coach-payouts-pack-and-credit (V186, C3 item 4): the durable record of one credit cash-out's
 * Stripe refund. Written {@link #PENDING} in the SAME transaction as the wallet debit
 * ({@code CreditWalletService.debitForCashOut}), so a crash between the debit and the refund — or a
 * refund whose outcome Stripe never confirmed — always leaves a row an operator and the re-check job
 * ({@code CashOutReconciliationService}) can act on, instead of only an error log.
 *
 * <pre>
 *   PENDING  --refund accepted-------------------> COMPLETED
 *   PENDING  --refund rejected (4xx): credit restored-> REVERSED
 *   PENDING  --outcome unknown after retries------> UNKNOWN   (admin alert CASH_OUT_UNRESOLVED)
 *   UNKNOWN / stale PENDING --re-check job, same idempotency key--> COMPLETED / REVERSED
 *   UNKNOWN  --operator settles it by hand (SQL)---> RESOLVED
 * </pre>
 * The Stripe idempotency key is always {@code cashout-{cashOutId}}.
 */
@Entity
@Table(schema = "payment", name = "cash_out_reconciliations")
@Getter
@Setter
@NoArgsConstructor
public class CashOutReconciliation {

    public static final String PENDING = "PENDING";
    public static final String COMPLETED = "COMPLETED";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String REVERSED = "REVERSED";
    public static final String RESOLVED = "RESOLVED";

    @Id
    @Column(name = "cash_out_id", nullable = false, updatable = false)
    private UUID cashOutId;

    @Column(name = "parent_id", nullable = false, updatable = false)
    private Long parentId;

    @Column(name = "payment_intent_id", nullable = false, updatable = false)
    private String paymentIntentId;

    @Column(name = "net_amount", nullable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "fee_amount", nullable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal feeAmount;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public static CashOutReconciliation pending(UUID cashOutId, Long parentId, String paymentIntentId,
                                                BigDecimal netAmount, BigDecimal feeAmount) {
        CashOutReconciliation r = new CashOutReconciliation();
        r.cashOutId = cashOutId;
        r.parentId = parentId;
        r.paymentIntentId = paymentIntentId;
        r.netAmount = netAmount;
        r.feeAmount = feeAmount;
        r.status = PENDING;
        return r;
    }

    /** {@code cashout-{cashOutId}} — the one Stripe idempotency key every attempt of this refund uses. */
    public String idempotencyKey() {
        return "cashout-" + cashOutId;
    }

    public boolean isOpen() {
        return PENDING.equals(status) || UNKNOWN.equals(status);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
