package com.softropic.skillars.platform.booking.contract;

import java.math.BigDecimal;
import java.util.List;

public record CoachScheduleResponse(
    String weekStart,
    String coachTimezone,
    List<ScheduleBookingItem> bookings,
    List<AvailabilityWindowResponse> availabilityWindows,
    List<AvailabilityBlockResponse> availabilityBlocks,
    BigDecimal projectedGrossRevenue,
    BigDecimal commissionDeduction,
    BigDecimal projectedNetRevenue,
    BigDecimal commissionRate
) {}
