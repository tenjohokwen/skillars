package com.softropic.skillars.platform.payment.api;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** fix/payment-payout-recovery (payouts L3): the revenue endpoints clamp their paging parameters. */
class RevenueResourcePageRequestTest {

    @Test
    void hugeSizeIsCapped_negativePageAndSizeAreClamped() {
        PageRequest huge = RevenueResource.pageRequest(3, 1_000_000);
        assertThat(huge.getPageNumber()).isEqualTo(3);
        assertThat(huge.getPageSize()).isEqualTo(RevenueResource.MAX_PAGE_SIZE);

        PageRequest negative = RevenueResource.pageRequest(-1, -5);
        assertThat(negative.getPageNumber()).isZero();
        assertThat(negative.getPageSize()).isEqualTo(1);

        assertThat(RevenueResource.pageRequest(0, 20).getPageSize()).isEqualTo(20);
    }
}
