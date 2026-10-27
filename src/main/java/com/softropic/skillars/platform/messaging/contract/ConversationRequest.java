package com.softropic.skillars.platform.messaging.contract;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * @param coachId the coach's profile id. Optional for a COACH caller only — MessagingResource then
 *                defaults it to the caller's own coach profile; required for everyone else.
 * @param playerId the player-PROFILE id (not a user id).
 */
public record ConversationRequest(
    UUID coachId,
    @NotNull Long playerId
) {}
