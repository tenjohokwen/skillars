package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.ProjectedRevenueResult;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachPricing;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectedRevenueServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private CoachPricingRepository coachPricingRepository;
    @Mock private ConfigService configService;

    private ProjectedRevenueService service;

    private final UUID coachId = UUID.randomUUID();
    private final LocalDate weekStart = LocalDate.of(2026, 6, 9);

    @BeforeEach
    void setUp() {
        service = new ProjectedRevenueService(bookingRepository, coachPricingRepository, configService);
        when(configService.getString("platform.commission_rate")).thenReturn("0.08");
    }

    @Test
    void noBookingsThisWeek_returnsAllZeroRevenue() {
        when(bookingRepository.findByCoachIdAndStatusInAndTimeBetween(
            eq(coachId), anyList(), any(Instant.class), any(Instant.class)))
            .thenReturn(List.of());
        CoachPricing pricing = new CoachPricing();
        pricing.setPerSessionPrice(new BigDecimal("50.00"));
        when(coachPricingRepository.findByCoachId(coachId)).thenReturn(Optional.of(pricing));

        ProjectedRevenueResult result = service.calculateWeeklyRevenue(coachId, weekStart);

        assertThat(result.grossRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.commissionDeduction()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.netRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void threeConfirmedBookings_perSessionPrice50_commissionRate8pct_correctRevenue() {
        List<Booking> bookings = List.of(new Booking(), new Booking(), new Booking());
        when(bookingRepository.findByCoachIdAndStatusInAndTimeBetween(
            eq(coachId), anyList(), any(Instant.class), any(Instant.class)))
            .thenReturn(bookings);
        CoachPricing pricing = new CoachPricing();
        pricing.setPerSessionPrice(new BigDecimal("50.00"));
        when(coachPricingRepository.findByCoachId(coachId)).thenReturn(Optional.of(pricing));

        ProjectedRevenueResult result = service.calculateWeeklyRevenue(coachId, weekStart);

        assertThat(result.grossRevenue()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(result.commissionDeduction()).isEqualByComparingTo(new BigDecimal("12.00"));
        assertThat(result.netRevenue()).isEqualByComparingTo(new BigDecimal("138.00"));
    }

    @Test
    void mixedConfirmedAndUpcomingBookings_onlyThoseStatusesSummed() {
        // The repository is called with the statuses list — we verify the actual call uses
        // only CONFIRMED and UPCOMING by checking what is passed into the mock
        List<Booking> bookings = List.of(new Booking(), new Booking());
        when(bookingRepository.findByCoachIdAndStatusInAndTimeBetween(
            eq(coachId), eq(List.of("CONFIRMED", "UPCOMING")), any(Instant.class), any(Instant.class)))
            .thenReturn(bookings);
        CoachPricing pricing = new CoachPricing();
        pricing.setPerSessionPrice(new BigDecimal("60.00"));
        when(coachPricingRepository.findByCoachId(coachId)).thenReturn(Optional.of(pricing));

        ProjectedRevenueResult result = service.calculateWeeklyRevenue(coachId, weekStart);

        assertThat(result.grossRevenue()).isEqualByComparingTo(new BigDecimal("120.00"));
    }

    @Test
    void coachHasNoPricingEntry_returnsAllZeroWithNoException() {
        when(bookingRepository.findByCoachIdAndStatusInAndTimeBetween(
            eq(coachId), anyList(), any(Instant.class), any(Instant.class)))
            .thenReturn(List.of(new Booking()));
        when(coachPricingRepository.findByCoachId(coachId)).thenReturn(Optional.empty());

        ProjectedRevenueResult result = service.calculateWeeklyRevenue(coachId, weekStart);

        assertThat(result.grossRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.commissionDeduction()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.netRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
