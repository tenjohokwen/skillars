package com.softropic.skillars.platform.messaging.contract;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ConversationRequest(
    @NotNull UUID coachId,
    @NotNull Long playerId
) {}
