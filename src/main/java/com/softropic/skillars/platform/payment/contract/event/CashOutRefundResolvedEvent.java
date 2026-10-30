package com.softropic.skillars.platform.payment.contract.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * feat/coach-payouts-pack-and-credit: an open credit cash-out settled itself — its refund was confirmed
 * ({@code COMPLETED}) or rejected and the credit restored ({@code REVERSED}). Resolves the
 * {@code CASH_OUT_UNRESOLVED} admin alert, if one was raised, so no admin acts on a cash-out the system
 * already settled (re-crediting or re-refunding it would pay the payer twice).
 */
public class CashOutRefundResolvedEvent extends ApplicationEvent {

    private final UUID cashOutId;
    private final String outcome;
    /** fix/audit3-wallet-cashout-integrity: "leg i/n req &lt;id&gt;" for one leg of a split cash-out, else null. */
    private final String legNote;

    public CashOutRefundResolvedEvent(Object source, UUID cashOutId, String outcome) {
        this(source, cashOutId, outcome, null);
    }

    public CashOutRefundResolvedEvent(Object source, UUID cashOutId, String outcome, String legNote) {
        super(source);
        this.cashOutId = cashOutId;
        this.outcome = outcome;
        this.legNote = legNote;
    }

    public UUID getCashOutId() { return cashOutId; }
    public String getOutcome() { return outcome; }
    public String getLegNote() { return legNote; }
}
