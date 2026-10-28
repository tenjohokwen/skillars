package com.softropic.skillars.platform.security.contract.event;

import com.softropic.skillars.platform.security.contract.AccountRole;

import java.util.List;

/**
 * Published by platform.security (Story 10.4) when an account is permanently deleted.
 * userId must use the same String format stored in videos.owner_id:
 *   - Coach: coach profile UUID string
 *   - Player: PlayerProfile id as string (player videos are owned by the profile, Story 6.6 — NOT the user id)
 *   - Parent: Long user ID as string (parents own no videos of their own)
 * linkedPlayerIds: PlayerProfile ids of the children managed by a parent account (empty for coaches and
 * players) — the same id space as their videos' owner_id.
 */
public record AccountDeletionRequestedEvent(String userId, AccountRole userRole, List<Long> linkedPlayerIds) {}
