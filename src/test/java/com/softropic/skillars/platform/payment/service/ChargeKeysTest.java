package com.softropic.skillars.platform.payment.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fix/payment-charge-idempotency (H1): a batch charge key names the exact reserved set, so two concurrent
 * accepts of one batch (disjoint sets) never share a Stripe idempotency key.
 */
class ChargeKeysTest {

    private static final UUID BATCH = UUID.randomUUID();
    private static final UUID B1 = UUID.randomUUID();
    private static final UUID B2 = UUID.randomUUID();
    private static final UUID B3 = UUID.randomUUID();

    @Test
    void batchKey_isDeterministicAndOrderInsensitive() {
        assertThat(ChargeKeys.forBatch(BATCH, List.of(B1, B2))).isEqualTo(ChargeKeys.forBatch(BATCH, List.of(B2, B1)));
    }

    @Test
    void batchKey_disjointSetsOfOneBatch_differ() {
        assertThat(ChargeKeys.forBatch(BATCH, List.of(B1))).isNotEqualTo(ChargeKeys.forBatch(BATCH, List.of(B2)));
        assertThat(ChargeKeys.forBatch(BATCH, List.of(B1, B2))).isNotEqualTo(ChargeKeys.forBatch(BATCH, List.of(B1, B3)));
    }

    @Test
    void batchKey_fitsTheColumn_andBookingKeyHasNoTimeComponent() {
        assertThat(ChargeKeys.forBatch(BATCH, List.of(B1, B2, B3)))
            .startsWith("pi-batch-" + BATCH + "-")
            .hasSizeLessThanOrEqualTo(128);
        assertThat(ChargeKeys.forBooking(B1)).isEqualTo("pi-booking-" + B1);
    }
}
