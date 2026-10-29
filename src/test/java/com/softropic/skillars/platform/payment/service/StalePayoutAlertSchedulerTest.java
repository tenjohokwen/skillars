package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.contract.event.PaymentIntegrityAlertEvent;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** fix/payment-dispute-credit-trace-and-lost-dispute: no payout sits in FROZEN / HOLD silently. */
@ExtendWith(MockitoExtension.class)
class StalePayoutAlertSchedulerTest {

    @Mock CoachPayoutRepository coachPayoutRepository;
    @Mock ConfigService configService;
    @Mock ApplicationEventPublisher eventPublisher;

    @Test
    void stalePayout_raisesAPayoutHeldAlertOnItsBooking_withTheConfiguredAge() {
        StalePayoutAlertScheduler scheduler = new StalePayoutAlertScheduler(coachPayoutRepository, configService,
            eventPublisher);
        when(configService.getBoundedLong(eq(StalePayoutAlertScheduler.CONFIG_KEY), eq(14L), eq(1L), eq(365L)))
            .thenReturn(10L);
        CoachPayout row = new CoachPayout();
        row.setBookingId(UUID.randomUUID());
        row.setStatus(CoachPayoutStatus.FROZEN);
        row.setLastError("CHARGEBACK_OPEN");
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        when(coachPayoutRepository.findStaleUnreleased(cutoff.capture(), any(Pageable.class))).thenReturn(List.of(row));

        scheduler.alertStalePayouts();

        assertThat(cutoff.getValue()).isCloseTo(Instant.now().minus(Duration.ofDays(10)), within(1, java.time.temporal.ChronoUnit.MINUTES));
        ArgumentCaptor<PaymentIntegrityAlertEvent> alert = ArgumentCaptor.forClass(PaymentIntegrityAlertEvent.class);
        verify(eventPublisher).publishEvent(alert.capture());
        assertThat(alert.getValue().getKind()).isEqualTo(PaymentIntegrityAlertEvent.Kind.PAYOUT_HELD);
        assertThat(alert.getValue().getReference()).isEqualTo(PaymentIntegrityAlertEvent.Reference.BOOKING);
        assertThat(alert.getValue().getReferenceId()).isEqualTo(row.getBookingId().toString());
        assertThat(alert.getValue().getReason()).startsWith("STALE_FROZEN").endsWith("CHARGEBACK_OPEN");
    }

    @Test
    void nothingStale_noAlert() {
        StalePayoutAlertScheduler scheduler = new StalePayoutAlertScheduler(coachPayoutRepository, configService,
            eventPublisher);
        when(configService.getBoundedLong(any(), eq(14L), eq(1L), eq(365L))).thenReturn(14L);
        when(coachPayoutRepository.findStaleUnreleased(any(), any())).thenReturn(List.of());

        scheduler.alertStalePayouts();

        verify(eventPublisher, never()).publishEvent(any());
    }
}
