package com.softropic.skillars.platform.marketplace.contract;

import java.util.UUID;

public record ProfileBuilderStatusResponse(
    UUID coachId,
    CoachProfileStatus status,
    int lastCompletedStep,
    boolean profileComplete
) {}
