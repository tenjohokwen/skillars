package com.softropic.skillars.platform.security.contract;

import java.time.LocalDate;

public record PlayerProfileResponse(
    Long id,
    String name,
    LocalDate dateOfBirth,
    PlayerPosition position,
    AgeTier ageTier,
    String ageTierLabel,
    boolean independentAccountAllowed
) {}
