package com.softropic.skillars.platform.booking.contract;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** S-07: the conflict-zone split's rounding rule — the payer's half is floored, the coach takes the odd cent. */
class CoachNoShowEventTest {

    @Test
    void splitRefundShare_isHalfRoundedDown_andTheCoachTakesTheRemainder() {
        assertThat(CoachNoShowEvent.splitRefundShare(new BigDecimal("50.00"))).isEqualByComparingTo("25.00");
        assertThat(CoachNoShowEvent.splitRefundShare(new BigDecimal("33.33"))).isEqualByComparingTo("16.66");
        assertThat(CoachNoShowEvent.splitRefundShare(new BigDecimal("0.01"))).isEqualByComparingTo("0.00");
        assertThat(CoachNoShowEvent.splitRefundShare(new BigDecimal("0.03"))).isEqualByComparingTo("0.01");
        assertThat(CoachNoShowEvent.splitRefundShare(null)).isEqualByComparingTo("0");
        BigDecimal paid = new BigDecimal("66.67");
        assertThat(CoachNoShowEvent.splitRefundShare(paid).add(paid.subtract(CoachNoShowEvent.splitRefundShare(paid))))
            .isEqualByComparingTo(paid);
    }

    @Test
    void aSplit_refundsMoneyAndNeverRestoresAPackSession() {
        UUID packId = UUID.randomUUID();
        CoachNoShowEvent split = new CoachNoShowEvent(this, UUID.randomUUID(), 1L, UUID.randomUUID(), packId,
            new BigDecimal("30.00"), false, "p@test.com", Instant.now(), "UTC", true);
        assertThat(split.isCoachSplit()).isTrue();
        assertThat(split.isWalletRefund()).isTrue();
        assertThat(split.isPackRestore()).isFalse();

        CoachNoShowEvent full = new CoachNoShowEvent(this, UUID.randomUUID(), 1L, UUID.randomUUID(), packId,
            new BigDecimal("30.00"), false, "p@test.com", Instant.now(), "UTC");
        assertThat(full.isCoachSplit()).isFalse();
        assertThat(full.isPackRestore()).isTrue();
        assertThat(full.isWalletRefund()).isFalse();
    }
}
