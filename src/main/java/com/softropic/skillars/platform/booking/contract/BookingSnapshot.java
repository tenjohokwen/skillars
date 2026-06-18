package com.softropic.skillars.platform.booking.contract;

import java.util.UUID;

public record BookingSnapshot(UUID id, UUID coachId, Long playerId, String status) {}
