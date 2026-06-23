package com.softropic.skillars.platform.security.contract.event;

import com.softropic.skillars.platform.security.contract.AccountRole;

import java.util.List;

/**
 * Published by platform.security (Story 10.4) when an account is permanently deleted.
 * userId must use the same String format stored in videos.owner_id:
 *   - Coach: coach profile UUID string
 *   - Parent/Player: Long user ID as string
 * linkedPlayerIds: Long user IDs of minor players managed by a parent account (empty for coaches and players).
 */
public record AccountDeletionRequestedEvent(String userId, AccountRole userRole, List<Long> linkedPlayerIds) {}
