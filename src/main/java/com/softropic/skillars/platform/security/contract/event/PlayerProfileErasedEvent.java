package com.softropic.skillars.platform.security.contract.event;

/**
 * Published by {@code GdprErasureService.eraseChildTransactional} (inside the erasure transaction, so
 * AFTER_COMMIT listeners see the committed scrub) when a parent-requested GDPR erasure of ONE child
 * profile ran. {@code playerId} is the PlayerProfile id. The account-level erasures (PLAYER / PARENT)
 * signal the same thing through {@link AccountDeletionRequestedEvent}; this event covers the child scope,
 * where no account is deleted. Consumed by the video module to purge coach videos about the child (V184).
 */
public record PlayerProfileErasedEvent(Long playerId) {}
