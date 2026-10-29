package com.softropic.skillars.platform.admin.contract;

import java.math.BigDecimal;

/**
 * feat/payment-forfeit-payouts-and-clawback: what an {@code UPHOLD_COACH} dispute resolution did, for the
 * outcome notices.
 *
 * @param refunded          the payer's refund being reversed (a pack session's share of the pack price for
 *                          a restored session)
 * @param recovered         what was taken back from the payer
 * @param absorbed          the rest — absorbed by the platform, not recovered from the payer
 * @param coachNet          the coach's net paid for the session
 * @param packSession       the refund was a restored pack session, not wallet credit
 * @param sessionRededucted that session was deducted from the pack again
 */
public record UpholdCoachOutcome(BigDecimal refunded, BigDecimal recovered, BigDecimal absorbed,
                                 BigDecimal coachNet, boolean packSession, boolean sessionRededucted) {

    /** The notice's reader: the payer's side or the coach. */
    public enum Audience { PARENT, COACH }
}
