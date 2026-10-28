package com.softropic.skillars.platform.payment.contract.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * feat/coach-payouts-pack-and-credit (C3 item 4): a credit cash-out's Stripe refund could not be
 * confirmed — the payer's wallet is debited and Stripe's outcome is unknown, or the record outlived the
 * window in which the refund can be safely retried. Raises a {@code CASH_OUT_UNRESOLVED} admin alert
 * referencing the {@code payment.cash_out_reconciliations} row.
 */
public class CashOutRefundUnresolvedEvent extends ApplicationEvent {

    private final UUID cashOutId;
    private final Long parentId;
    private final String reason;

    public CashOutRefundUnresolvedEvent(Object source, UUID cashOutId, Long parentId, String reason) {
        super(source);
        this.cashOutId = cashOutId;
        this.parentId = parentId;
        this.reason = reason;
    }

    public UUID getCashOutId() { return cashOutId; }
    public Long getParentId() { return parentId; }
    public String getReason() { return reason; }
}
