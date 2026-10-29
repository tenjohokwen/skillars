package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.contract.CoachPayoutTopUpPayload;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.event.PaymentIntegrityAlertEvent;
import com.softropic.skillars.platform.payment.contract.exception.CoachPayoutTransferException;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** fix/payment-payout-recovery (payouts H1): the UPHOLD_COACH top-up transfer. */
@ExtendWith(MockitoExtension.class)
class CoachPayoutTopUpHandlerTest {

    @Mock CoachPayoutRepository coachPayoutRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CoachPayoutTopUpHandler handler;
    private final UUID bookingId = UUID.randomUUID();
    private final UUID coachId = UUID.randomUUID();
    private final UUID topUpId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        handler = new CoachPayoutTopUpHandler(coachPayoutRepository, paymentGateway, new SimpleMeterRegistry(),
            objectMapper, eventPublisher);
    }

    private String payload(String amount) throws Exception {
        return objectMapper.writeValueAsString(new CoachPayoutTopUpPayload(bookingId, new BigDecimal(amount), topUpId));
    }

    private CoachPayout row(String status, String net, String gross, String reversed) {
        CoachPayout r = new CoachPayout();
        r.setBookingId(bookingId);
        r.setCoachId(coachId);
        r.setCurrency("eur");
        r.setStatus(status);
        r.setStripeTransferId("tr_orig");
        r.setOriginalNetAmount(new BigDecimal("45.00"));
        r.setOriginalGrossAmount(new BigDecimal("50.00"));
        r.setOriginalCommissionAmount(new BigDecimal("5.00"));
        r.setNetAmount(new BigDecimal(net));
        r.setGrossAmount(new BigDecimal(gross));
        r.setCommissionAmount(new BigDecimal(gross).subtract(new BigDecimal(net)));
        r.setReversedAmount(new BigDecimal(reversed));
        return r;
    }

    @Test
    void reversedPayout_topUpUndoesTheReversal_andReleasesItAgain() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.REVERSED, "45.00", "50.00", "45.00");
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));
        when(paymentGateway.transferToCoach(topUpId, coachId, new BigDecimal("45.00"), "eur")).thenReturn("tr_topup");

        handler.handle(payload("45.00"));

        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.RELEASED);
        assertThat(r.getReversedAmount()).isEqualByComparingTo("0");
        assertThat(r.getEffectiveNetAmount()).isEqualByComparingTo("45.00");
        assertThat(r.getTopupAmount()).isEqualByComparingTo("45.00");
        assertThat(r.getTopupTransferId()).isEqualTo("tr_topup");
        assertThat(r.getStripeTransferId()).as("the original transfer stays the reference").isEqualTo("tr_orig");
    }

    @Test
    void releasedAfterAPendingCut_topUpRaisesTheNetBackToTheOriginal() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.RELEASED, "27.00", "30.00", "0");
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));
        when(paymentGateway.transferToCoach(topUpId, coachId, new BigDecimal("18.00"), "eur")).thenReturn("tr_topup");

        handler.handle(payload("18.00"));

        assertThat(r.getNetAmount()).isEqualByComparingTo("45.00");
        assertThat(r.getGrossAmount()).isEqualByComparingTo("50.00");
        assertThat(r.getCommissionAmount()).isEqualByComparingTo("5.00");
    }

    @Test
    void duplicateMessage_afterTheTopUpLanded_isANoOp() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.RELEASED, "45.00", "50.00", "0");
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));

        handler.handle(payload("45.00"));

        verify(paymentGateway, never()).transferToCoach(any(), any(), any(), any());
    }

    @Test
    void amountIsCappedAtWhatIsStillOwed() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.RELEASED, "45.00", "50.00", "10.00");
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));
        when(paymentGateway.transferToCoach(topUpId, coachId, new BigDecimal("10.00"), "eur")).thenReturn("tr_topup");

        handler.handle(payload("45.00"));

        assertThat(r.getEffectiveNetAmount()).isEqualByComparingTo("45.00");
    }

    @Test
    void accountCannotReceive_alertsAndIsReDriven() throws Exception {
        CoachPayout r = row(CoachPayoutStatus.REVERSED, "45.00", "50.00", "45.00");
        when(coachPayoutRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(r));
        when(paymentGateway.transferToCoach(any(), any(), any(), any())).thenThrow(new CoachPayoutTransferException(
            "payment.coachStripeNotConfigured", false, "ACCOUNT_NOT_READY", new IllegalStateException("x")));

        assertThatThrownBy(() -> handler.handle(payload("45.00"))).isInstanceOf(CoachPayoutTransferException.class);
        verify(eventPublisher).publishEvent(any(PaymentIntegrityAlertEvent.class));
        assertThat(r.getStatus()).isEqualTo(CoachPayoutStatus.REVERSED);
    }
}
