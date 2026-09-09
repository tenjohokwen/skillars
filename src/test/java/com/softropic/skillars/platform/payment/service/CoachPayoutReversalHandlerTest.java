package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.payment.contract.CoachPayoutReversalPayload;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.exception.CoachPayoutTransferException;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** skillars-deferred-106 AC8.3 / AC10.2. */
@ExtendWith(MockitoExtension.class)
class CoachPayoutReversalHandlerTest {

    @Mock CoachPayoutRepository coachPayoutRepository;
    @Mock PaymentGateway paymentGateway;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private CoachPayoutReversalHandler handler;

    private final UUID bookingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        handler = new CoachPayoutReversalHandler(coachPayoutRepository, paymentGateway, meterRegistry, objectMapper);
    }

    private String payload() throws Exception {
        return objectMapper.writeValueAsString(new CoachPayoutReversalPayload(bookingId, new BigDecimal("45.00")));
    }

    private CoachPayout row(String status, String transferId) {
        CoachPayout r = new CoachPayout();
        r.setBookingId(bookingId);
        r.setCoachId(UUID.randomUUID());
        r.setStatus(status);
        r.setStripeTransferId(transferId);
        r.setNetAmount(new BigDecimal("45.00"));
        return r;
    }

    @Test
    void releasedRow_reversesAndMarksReversed() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.RELEASED, "tr_1");
        when(coachPayoutRepository.findById(bookingId)).thenReturn(Optional.of(r));

        handler.handle(payload());

        verify(paymentGateway).reverseTransfer("tr_1", new BigDecimal("45.00"));
        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.REVERSED);
        assertThat(r.getReversedAt()).isNotNull();
    }

    @Test
    void nonReleasedRow_isNoOp() throws Exception {
        when(coachPayoutRepository.findById(bookingId))
            .thenReturn(Optional.of(row(CoachPayoutStatus.CANCELLED, null)));

        handler.handle(payload());

        verify(paymentGateway, never()).reverseTransfer(any(), any());
    }

    @Test
    void releasedRowWithoutTransferId_marksReversalFailed_noStripeCall() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.RELEASED, null);
        when(coachPayoutRepository.findById(bookingId)).thenReturn(Optional.of(r));

        handler.handle(payload());

        verify(paymentGateway, never()).reverseTransfer(any(), any());
        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.REVERSAL_FAILED);
    }

    @Test
    void retryableReversalFailure_isRethrown() throws Exception {
        when(coachPayoutRepository.findById(bookingId)).thenReturn(Optional.of(row(CoachPayoutStatus.RELEASED, "tr_1")));
        doThrow(new CoachPayoutTransferException("payment.coachTransferReversalFailed", true, "NETWORK", null))
            .when(paymentGateway).reverseTransfer(any(), any());

        assertThatThrownBy(() -> handler.handle(payload())).isInstanceOf(CoachPayoutTransferException.class);
    }

    @Test
    void nonRetryableReversalFailure_marksReversalFailed_incrementsMetric_doesNotThrow() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.RELEASED, "tr_1");
        when(coachPayoutRepository.findById(bookingId)).thenReturn(Optional.of(r));
        doThrow(new CoachPayoutTransferException(
            "payment.coachTransferReversalFailed", false, "BALANCE_GONE", new RuntimeException("no balance")))
            .when(paymentGateway).reverseTransfer(any(), any());

        handler.handle(payload());

        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.REVERSAL_FAILED);
        assertThat(meterRegistry.get(CoachPayoutReversalHandler.METRIC_REVERSAL_FAILED)
            .tag("reason", "BALANCE_GONE").counter().count()).isEqualTo(1.0);
    }
}
