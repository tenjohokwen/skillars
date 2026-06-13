package com.softropic.skillars.platform.booking.contract;

import java.util.List;

public record CoachAvailabilityResponse(
    List<AvailabilityWindowResponse> windows,
    List<AvailabilityBlockResponse> blocks,
    List<AvailableSlotResponse> computedSlots
) {}
