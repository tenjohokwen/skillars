package com.softropic.skillars.platform.booking.contract;

import java.util.UUID;

public record BatchBookingCreatedResponse(UUID batchId, int bookingCount) {}
