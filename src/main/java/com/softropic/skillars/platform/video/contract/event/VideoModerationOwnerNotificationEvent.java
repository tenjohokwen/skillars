package com.softropic.skillars.platform.video.contract.event;

import java.util.UUID;

/**
 * @param ownerId          the video's owner_id (coach-profile UUID or PlayerProfile id) — NOT an address
 * @param recipientEmail   where the notice goes — the owner account's email, resolved by the
 *                         publisher; null = nobody reachable
 * @param recipientLangKey that account's language, may be null
 */
public record VideoModerationOwnerNotificationEvent(UUID videoId, String ownerId, String message,
                                                    String recipientEmail, String recipientLangKey) {}
