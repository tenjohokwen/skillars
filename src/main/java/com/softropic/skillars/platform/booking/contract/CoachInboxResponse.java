package com.softropic.skillars.platform.booking.contract;

import java.util.List;

public record CoachInboxResponse(
    List<BookingResponse> singleBookings,
    List<BatchGroupedBookingResponse> batchGroups
) {}
