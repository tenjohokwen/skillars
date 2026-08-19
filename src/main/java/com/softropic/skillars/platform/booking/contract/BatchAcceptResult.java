package com.softropic.skillars.platform.booking.contract;

import java.util.UUID;

public record BatchAcceptResult(UUID bookingId, boolean accepted, String errorKey) {}
