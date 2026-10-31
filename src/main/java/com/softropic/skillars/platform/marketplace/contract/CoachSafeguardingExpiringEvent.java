package com.softropic.skillars.platform.marketplace.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * S-09: published inside the reminder job's per-coach transaction, ~30 days before a safeguarding check lapses.
 * The notification module's {@code CoachSafeguardingEmailListener} turns it into the reminder email (BEFORE_COMMIT,
 * so the outbox row commits atomically with {@code reminder_sent_at}). Carries everything the email needs so the
 * listener holds no repository.
 *
 * @param email    the coach's own address, never blank
 * @param langKey  the coach's language key, "en" when unknown
 * @param timezone the coach's canonical timezone, used to render the date
 */
public record CoachSafeguardingExpiringEvent(
    UUID coachId,
    String coachDisplayName,
    String email,
    String langKey,
    String timezone,
    Instant expiresAt
) {
}
