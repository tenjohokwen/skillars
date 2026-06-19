package com.softropic.skillars.platform.development.contract;

import java.time.Instant;

public record NeglectedSkillResponse(
    String skillCode,
    Instant detectedAt
) {}
