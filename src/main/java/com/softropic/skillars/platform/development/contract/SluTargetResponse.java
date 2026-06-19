package com.softropic.skillars.platform.development.contract;

import java.math.BigDecimal;
import java.time.Instant;

public record SluTargetResponse(
    String skillCode,
    BigDecimal weeklyTargetSlu,
    Instant updatedAt
) {}
