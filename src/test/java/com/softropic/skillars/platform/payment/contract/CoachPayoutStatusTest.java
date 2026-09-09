package com.softropic.skillars.platform.payment.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** skillars-deferred-106 AC3.3. */
class CoachPayoutStatusTest {

    @Test
    void isPayable_onlyPendingRelease() {
        assertThat(CoachPayoutStatus.isPayable(CoachPayoutStatus.PENDING_RELEASE)).isTrue();
        assertThat(CoachPayoutStatus.isPayable(CoachPayoutStatus.RELEASED)).isFalse();
        assertThat(CoachPayoutStatus.isPayable(CoachPayoutStatus.HOLD)).isFalse();
        assertThat(CoachPayoutStatus.isPayable(CoachPayoutStatus.CANCELLED)).isFalse();
        assertThat(CoachPayoutStatus.isPayable(CoachPayoutStatus.REVERSED)).isFalse();
        assertThat(CoachPayoutStatus.isPayable(CoachPayoutStatus.REVERSAL_FAILED)).isFalse();
        assertThat(CoachPayoutStatus.isPayable(CoachPayoutStatus.FAILED_PERMANENT)).isFalse();
        assertThat(CoachPayoutStatus.isPayable(null)).isFalse();
    }

    @Test
    void isTerminal_releasedIsNotTerminal_becauseADisputeCanStillReverseIt() {
        assertThat(CoachPayoutStatus.isTerminal(CoachPayoutStatus.RELEASED)).isFalse();
        assertThat(CoachPayoutStatus.isTerminal(CoachPayoutStatus.PENDING_RELEASE)).isFalse();
        assertThat(CoachPayoutStatus.isTerminal(CoachPayoutStatus.HOLD)).isFalse();
        assertThat(CoachPayoutStatus.isTerminal(CoachPayoutStatus.REVERSAL_FAILED)).isFalse();

        assertThat(CoachPayoutStatus.isTerminal(CoachPayoutStatus.REVERSED)).isTrue();
        assertThat(CoachPayoutStatus.isTerminal(CoachPayoutStatus.CANCELLED)).isTrue();
        assertThat(CoachPayoutStatus.isTerminal(CoachPayoutStatus.FAILED_PERMANENT)).isTrue();
    }
}
