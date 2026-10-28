package com.softropic.skillars.platform.notification.contract;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One in-app inbox entry. {@code type} is the {@link EmailTemplate} name; the frontend renders the
 * i18n key {@code notifications.types.<type>} with {@code params}. {@code deepLink} is a hash-router
 * path without the {@code #} (e.g. {@code /parent/bookings}), or {@code null}.
 */
public record NotificationDto(UUID id,
                              String category,
                              String type,
                              Map<String, String> params,
                              String deepLink,
                              Instant createdAt,
                              Instant readAt) {
}
