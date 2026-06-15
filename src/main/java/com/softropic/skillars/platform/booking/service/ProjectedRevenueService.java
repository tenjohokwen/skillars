package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.ProjectedRevenueResult;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectedRevenueService {

    private final BookingRepository bookingRepository;
    private final CoachPricingRepository coachPricingRepository;
    private final ConfigService configService;

    @Transactional(readOnly = true)
    public ProjectedRevenueResult calculateWeeklyRevenue(UUID coachId, LocalDate weekStart) {
        Instant wkStart = weekStart.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant wkEnd = weekStart.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<Booking> bookings = bookingRepository.findByCoachIdAndStatusInAndTimeBetween(
            coachId, List.of("CONFIRMED", "UPCOMING"), wkStart, wkEnd);

        BigDecimal perSessionPrice = coachPricingRepository.findByCoachId(coachId)
            .map(p -> p.getPerSessionPrice())
            .orElseGet(() -> {
                log.warn("No pricing entry found for coachId={}; returning zero revenue", coachId);
                return BigDecimal.ZERO;
            });

        BigDecimal gross = perSessionPrice.multiply(BigDecimal.valueOf(bookings.size()));
        BigDecimal commissionRate;
        try {
            String rateStr = configService.getString("platform.commission_rate");
            if (rateStr == null || rateStr.isBlank()) {
                throw new NumberFormatException("blank");
            }
            commissionRate = new BigDecimal(rateStr);
        } catch (NumberFormatException e) {
            log.error("Invalid or missing config 'platform.commission_rate'; defaulting to 0 commission");
            commissionRate = BigDecimal.ZERO;
        }
        BigDecimal commission = gross.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP);

        return new ProjectedRevenueResult(gross, commission, gross.subtract(commission), commissionRate);
    }
}
