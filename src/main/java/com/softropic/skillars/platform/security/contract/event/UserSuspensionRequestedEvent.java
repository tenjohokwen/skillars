package com.softropic.skillars.platform.security.contract.event;

// S-18: the same safety suspension as AccountSuspensionRequestedEvent, for a trigger that already holds the account's
// USER id (an abuse-media match on an uploaded image; file_storage_objects.owner_id is the user id). The video
// event's ownerId is a coach-profile UUID or a PlayerProfile id and is resolved differently, so a user id must not
// be passed through it. reason is a short audit label, never user content.
public record UserSuspensionRequestedEvent(long userId, String reason) {}
