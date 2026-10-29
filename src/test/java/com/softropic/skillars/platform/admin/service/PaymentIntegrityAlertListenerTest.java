package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.admin.contract.AdminAlertReferenceType;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import com.softropic.skillars.platform.admin.repo.AdminAlert;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.payment.contract.event.PaymentIntegrityAlertEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** fix/payment-payouts-disputes-webhooks item 8: one OPEN alert per (reference, type), its reason refreshed. */
@ExtendWith(MockitoExtension.class)
class PaymentIntegrityAlertListenerTest {

    @Mock AdminAlertRepository adminAlertRepository;
    @Mock PlatformTransactionManager txManager;
    @Mock TransactionStatus transactionStatus;
    @InjectMocks AdminAlertEventListener listener;

    @BeforeEach
    void setUp() {
        lenient().when(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(any(), any(), any()))
            .thenReturn(Optional.empty());
        lenient().when(txManager.getTransaction(any())).thenReturn(transactionStatus);
        listener.initTemplate();
    }

    @Test
    void firstEvent_insertsAlertWithReference() {
        listener.onPaymentIntegrityAlert(new PaymentIntegrityAlertEvent(this,
            PaymentIntegrityAlertEvent.Kind.PAYOUT_HELD, "b-1", PaymentIntegrityAlertEvent.Reference.BOOKING,
            "ACCOUNT_NOT_READY"));

        ArgumentCaptor<AdminAlert> c = ArgumentCaptor.forClass(AdminAlert.class);
        verify(adminAlertRepository).saveAndFlush(c.capture());
        assertThat(c.getValue().getType()).isEqualTo(AdminAlertType.PAYOUT_HELD);
        assertThat(c.getValue().getReferenceType()).isEqualTo(AdminAlertReferenceType.BOOKING);
        assertThat(c.getValue().getReason()).isEqualTo("ACCOUNT_NOT_READY");
    }

    @Test
    void coachReference_mapsToCoachReferenceType() {
        // fix/payment-stripe-connect-webhook-endpoint: account.application.deauthorized
        listener.onPaymentIntegrityAlert(new PaymentIntegrityAlertEvent(this,
            PaymentIntegrityAlertEvent.Kind.PAYOUT_HELD, "coach-1", PaymentIntegrityAlertEvent.Reference.COACH,
            "STRIPE_DEAUTHORIZED acct_1"));

        ArgumentCaptor<AdminAlert> c = ArgumentCaptor.forClass(AdminAlert.class);
        verify(adminAlertRepository).saveAndFlush(c.capture());
        assertThat(c.getValue().getReferenceType()).isEqualTo(AdminAlertReferenceType.COACH);
        assertThat(c.getValue().getReferenceId()).isEqualTo("coach-1");
    }

    @Test
    void laterEvent_refreshesTheOpenAlertsReason_noSecondAlert() {
        when(adminAlertRepository.updateOpenAlertReason("pi_1", "CHARGEBACK", "LOST 50.00 eur")).thenReturn(1);

        listener.onPaymentIntegrityAlert(new PaymentIntegrityAlertEvent(this,
            PaymentIntegrityAlertEvent.Kind.CHARGEBACK, "pi_1", PaymentIntegrityAlertEvent.Reference.PAYMENT_INTENT,
            "LOST 50.00 eur"));

        verify(adminAlertRepository, never()).saveAndFlush(any());
    }
}
