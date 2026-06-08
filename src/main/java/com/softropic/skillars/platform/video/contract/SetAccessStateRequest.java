package com.softropic.skillars.platform.video.contract;

import jakarta.validation.constraints.NotNull;

public record SetAccessStateRequest(@NotNull AccessState newAccessState) {}
