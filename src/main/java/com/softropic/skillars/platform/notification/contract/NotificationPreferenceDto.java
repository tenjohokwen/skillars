package com.softropic.skillars.platform.notification.contract;

/** One opt-out-able email category and whether the current user receives it by email. */
public record NotificationPreferenceDto(NotificationCategory category, boolean emailEnabled) {
}
