package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.admin.contract.AdminAlertReferenceType;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import com.softropic.skillars.platform.admin.repo.AdminAlert;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByParentEvent;
import com.softropic.skillars.platform.booking.contract.CoachNoShowEvent;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S-07: the informational repeat no-show claimant alert. */
@ExtendWith(MockitoExtension.class)
class CoachNoShowRepeatClaimantAlertListenerTest {

    private static final Long PAYER = 98532L;
    private static final UUID BOOKING = UUID.randomUUID();

    @Mock BookingRepository bookingRepository;
    @Mock AdminAlertRepository adminAlertRepository;
    @Mock ConfigService configService;
    @InjectMocks CoachNoShowRepeatClaimantAlertListener listener;

    @BeforeEach
    void setUp() {
        lenient().when(configService.getBoundedLong(ConfigBounds.BOOKING_COACH_NO_SHOW_REPEAT_CLAIM_ALERT_THRESHOLD)).thenReturn(3L);
        lenient().when(configService.getBoundedLong(ConfigBounds.BOOKING_COACH_NO_SHOW_REPEAT_CLAIM_ALERT_WINDOW_DAYS)).thenReturn(90L);
    }

    private CoachNoShowEvent report() {
        return new CoachNoShowEvent(this, BOOKING, PAYER, UUID.randomUUID(), null, new BigDecimal("50.00"), false,
            "p@test.com", Instant.now(), "UTC");
    }

    private BookingCancelledByParentEvent cancel(boolean coachNoShow) {
        return new BookingCancelledByParentEvent(this, BOOKING, PAYER, UUID.randomUUID(), null, -1L, true,
            new BigDecimal("50.00"), "p@test.com", "c@test.com", Instant.now(), "UTC", true, false, coachNoShow);
    }

    @Test
    void belowTheThreshold_raisesNothing() {
        when(bookingRepository.countByParentIdAndCoachNoShowClaimedAtAfter(eq(PAYER), any())).thenReturn(2L);

        listener.onCoachNoShow(report());

        verify(adminAlertRepository, never()).save(any());
    }

    @Test
    void atTheThreshold_raisesOneInformationalAlertNamingThePayerAndTheCount() {
        when(bookingRepository.countByParentIdAndCoachNoShowClaimedAtAfter(eq(PAYER), any())).thenReturn(3L);
        when(adminAlertRepository.countRepeatClaimantAlertsSince(eq("payer 98532:%"), any())).thenReturn(0L);

        listener.onCoachNoShow(report());

        ArgumentCaptor<AdminAlert> alert = ArgumentCaptor.forClass(AdminAlert.class);
        verify(adminAlertRepository).acquireRepeatClaimantLock("payer:98532");
        verify(adminAlertRepository).save(alert.capture());
        assertThat(alert.getValue().getType()).isEqualTo(AdminAlertType.REPEAT_NO_SHOW_CLAIMANT);
        assertThat(alert.getValue().getReferenceType()).isEqualTo(AdminAlertReferenceType.BOOKING);
        assertThat(alert.getValue().getReferenceId()).isEqualTo(BOOKING.toString());
        assertThat(alert.getValue().getReason()).isEqualTo("payer 98532: 3 claims in 90d");
    }

    @Test
    void onePerPayerPerWindow_aFurtherClaimRaisesNoSecondAlert() {
        when(bookingRepository.countByParentIdAndCoachNoShowClaimedAtAfter(eq(PAYER), any())).thenReturn(4L);
        when(adminAlertRepository.countRepeatClaimantAlertsSince(anyString(), any())).thenReturn(1L);

        listener.onCoachNoShow(report());

        verify(adminAlertRepository, never()).save(any());
    }

    @Test
    void aLateCancelClaimCounts_anOrdinaryCancelDoesNot() {
        when(bookingRepository.countByParentIdAndCoachNoShowClaimedAtAfter(eq(PAYER), any())).thenReturn(3L);
        when(adminAlertRepository.countRepeatClaimantAlertsSince(anyString(), any())).thenReturn(0L);

        listener.onBookingCancelledByParent(cancel(false));
        verify(bookingRepository, never()).countByParentIdAndCoachNoShowClaimedAtAfter(any(), any());

        listener.onBookingCancelledByParent(cancel(true));
        verify(adminAlertRepository).save(any());
    }

    @Test
    void aFailure_isSwallowed_neverSurfacingIntoTheCommittedClaim() {
        when(bookingRepository.countByParentIdAndCoachNoShowClaimedAtAfter(any(), any()))
            .thenThrow(new IllegalStateException("db down"));

        listener.onCoachNoShow(report());

        verify(adminAlertRepository, never()).save(any());
    }
}
