package com.softropic.skillars.platform.booking.contract;

import java.util.List;
import java.util.UUID;

public record BatchGroupedBookingResponse(
    UUID batchId,
    String parentName,
    int totalCount,
    List<BookingResponse> bookings
) {}
