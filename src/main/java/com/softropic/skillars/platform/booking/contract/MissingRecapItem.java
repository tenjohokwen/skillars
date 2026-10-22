package com.softropic.skillars.platform.booking.contract;

import java.time.Instant;
import java.util.UUID;

public record MissingRecapItem(UUID bookingId, Long playerId, String playerName, Instant sessionDate) {}
