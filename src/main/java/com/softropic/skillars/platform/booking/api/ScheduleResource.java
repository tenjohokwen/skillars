package com.softropic.skillars.platform.booking.api;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.contract.CoachScheduleResponse;
import com.softropic.skillars.platform.booking.contract.ParentScheduleResponse;
import com.softropic.skillars.platform.booking.contract.ProjectedRevenueResult;
import com.softropic.skillars.platform.booking.contract.ScheduleBookingItem;
import com.softropic.skillars.platform.booking.service.AvailabilityService;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.booking.service.ProjectedRevenueService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Observed(name = "booking.schedule")
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class ScheduleResource {

    private final BookingService bookingService;
    private final ProjectedRevenueService projectedRevenueService;
    private final AvailabilityService availabilityService;
    private final CoachProfileRepository coachProfileRepository;
    private final SecurityUtil securityUtil;

    // Deferred-78 AC6: AvailabilityResource's equivalent endpoint carries an identical check with
    // its own copy of this property.
    @Value("${booking.availability.weekStartRangeYears:2}")
    private int weekStartRangeYears;

    @GetMapping("/coaches/me/schedule")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<CoachScheduleResponse> getCoachSchedule(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        validateWeekStartRange(weekStart);

        Long coachUserId = resolveCurrentUserId();
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        List<ScheduleBookingItem> bookings = bookingService.getCoachWeekSchedule(coach.getId(), weekStart);

        var availability = availabilityService.getAvailabilityCalendar(coach.getId(), weekStart);

        ProjectedRevenueResult revenue = projectedRevenueService.calculateWeeklyRevenue(coach.getId(), weekStart);

        String coachTimezone = coach.getCanonicalTimezone() != null ? coach.getCanonicalTimezone() : "UTC";

        return ResponseEntity.ok(new CoachScheduleResponse(
            weekStart.toString(),
            coachTimezone,
            bookings,
            availability.windows(),
            availability.blocks(),
            revenue.grossRevenue(),
            revenue.commissionDeduction(),
            revenue.netRevenue(),
            revenue.commissionRate()
        ));
    }

    @GetMapping("/parents/me/schedule")
    @PreAuthorize(SecurityConstants.HAS_PARENT_OR_PLAYER_ROLE)
    public ResponseEntity<ParentScheduleResponse> getParentSchedule(@RequestParam Long playerId) {
        Long parentId = resolveCurrentUserId();
        return ResponseEntity.ok(bookingService.getParentPlayerSchedule(parentId, playerId));
    }

    private Long resolveCurrentUserId() {
        return securityUtil.requireCurrentUserId();
    }

    private void validateWeekStartRange(LocalDate weekStart) {
        LocalDate earliest = LocalDate.now().minusYears(weekStartRangeYears);
        LocalDate latest = LocalDate.now().plusYears(weekStartRangeYears);
        if (weekStart.isBefore(earliest) || weekStart.isAfter(latest)) {
            throw new OperationNotAllowedException("weekStart is outside the allowed range",
                Map.of("weekStart", weekStart, "weekStartRangeYears", weekStartRangeYears),
                BookingError.WEEK_START_OUT_OF_RANGE);
        }
    }
}
