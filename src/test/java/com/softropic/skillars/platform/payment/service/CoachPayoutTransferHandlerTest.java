package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.contract.CoachPayoutTransferPayload;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-106 AC7 / AC8 / AC15.1: {@code CoachPayoutTransferHandler} idempotency + the
 * retryable-vs-HOLD split.
 */
@ExtendWith(MockitoExtension.class)
class CoachPayoutTransferHandlerTest {

    @Mock CoachPayoutRepository coachPayoutRepository;
    @Mock PaymentGateway paymentGateway;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private CoachPayoutTransferHandler handler;
    private final UUID bookingId = UUID.randomUUID();
    private final UUID coachId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        handler = new CoachPayoutTransferHandler(coachPayoutRepository, paymentGateway, meterRegistry, objectMapper);
    }

    private String payload() throws Exception {
        return objectMapper.writeValueAsString(new CoachPayoutTransferPayload(bookingId));
    }

    private CoachPayout row(String status, Instant releaseAfter) {
        CoachPayout r = new CoachPayout();
        r.setBookingId(bookingId);
        r.setCoachId(coachId);
        r.setCurrency("eur");
        r.setGrossAmount(new BigDecimal("50.00"));
        r.setCommissionAmount(new BigDecimal("5.00"));
        r.setNetAmount(new BigDecimal("45.00"));
        r.setReleaseAfter(releaseAfter);
        r.setStatus(status);
        return r;
    }

    @Test
    void happyPath_pendingRelease_pastHold_transfersOnceAndReleases() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.PENDING_RELEASE, Instant.now().minus(1, ChronoUnit.HOURS));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));
        when(paymentGateway.transferToCoach(bookingId, coachId, new BigDecimal("45.00"), "eur"))
            .thenReturn("tr_1");

        handler.handle(payload());

        verify(paymentGateway, times(1)).transferToCoach(any(), any(), any(), any());
        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        assertThat(r.getStripeTransferId()).isEqualTo("tr_1");
        assertThat(r.getReleasedAt()).isNotNull();
    }

    @Test
    void reDriveOfReleasedRow_isNoOp() throws Exception {
        when(coachPayoutRepository.findByIdForUpdate(bookingId))
            .thenReturn(Optional.of(row(CoachPayoutStatus.RELEASED, Instant.now().minusSeconds(1))));

        handler.handle(payload());

        verify(paymentGateway, never()).transferToCoach(any(), any(), any(), any());
    }

    @Test
    void reDriveOfHoldRow_isNoOp() throws Exception {
        when(coachPayoutRepository.findByIdForUpdate(bookingId))
            .thenReturn(Optional.of(row(CoachPayoutStatus.HOLD, Instant.now().minusSeconds(1))));

        handler.handle(payload());

        verify(paymentGateway, never()).transferToCoach(any(), any(), any(), any());
    }

    @Test
    void reDriveOfCancelledRow_isNoOp() throws Exception {
        when(coachPayoutRepository.findByIdForUpdate(bookingId))
            .thenReturn(Optional.of(row(CoachPayoutStatus.CANCELLED, Instant.now().minusSeconds(1))));

        handler.handle(payload());

        verify(paymentGateway, never()).transferToCoach(any(), any(), any(), any());
    }

    @Test
    void reDriveOfFailedPermanentRow_isNoOp() throws Exception {
        when(coachPayoutRepository.findByIdForUpdate(bookingId))
            .thenReturn(Optional.of(row(CoachPayoutStatus.FAILED_PERMANENT, Instant.now().minusSeconds(1))));

        handler.handle(payload());

        verify(paymentGateway, never()).transferToCoach(any(), any(), any(), any());
    }

    @Test
    void missingRow_isNoOp() throws Exception {
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.empty());

        handler.handle(payload());

        verify(paymentGateway, never()).transferToCoach(any(), any(), any(), any());
    }

    @Test
    void releaseAfterInFuture_throwsSoOutboxDefers() throws Exception {
        when(coachPayoutRepository.findByIdForUpdate(bookingId))
            .thenReturn(Optional.of(row(CoachPayoutStatus.PENDING_RELEASE, Instant.now().plus(2, ChronoUnit.HOURS))));

        assertThatThrownBy(() -> handler.handle(payload())).isInstanceOf(IllegalStateException.class);

        verify(paymentGateway, never()).transferToCoach(any(), any(), any(), any());
    }

    @Test
    void retryableTransferFailure_isRethrown_rowStaysPendingRelease() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.PENDING_RELEASE, Instant.now().minusSeconds(1));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));
        when(paymentGateway.transferToCoach(any(), any(), any(), any()))
            .thenThrow(new CoachPayoutTransferException("payment.coachTransferFailed", true, "NETWORK", null));

        assertThatThrownBy(() -> handler.handle(payload())).isInstanceOf(CoachPayoutTransferException.class);

        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.PENDING_RELEASE);
    }

    @Test
    void nonRetryableTransferFailure_movesRowToHold_incrementsMetric_doesNotThrow() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.PENDING_RELEASE, Instant.now().minusSeconds(1));
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));
        when(paymentGateway.transferToCoach(any(), any(), any(), any()))
            .thenThrow(new CoachPayoutTransferException(
                "payment.coachTransferFailed", false, "INVALID_DESTINATION", new RuntimeException("acct gone")));

        handler.handle(payload()); // must not throw

        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.HOLD);
        assertThat(r.getLastError()).contains("INVALID_DESTINATION");
        assertThat(meterRegistry.get(CoachPayoutTransferHandler.METRIC_HELD)
            .tag("reason", "INVALID_DESTINATION").counter().count()).isEqualTo(1.0);
    }
}
