package com.softropic.skillars.platform.notification.contract;

import jakarta.validation.constraints.NotNull;

public record UpdateNotificationPreferenceRequest(@NotNull Boolean emailEnabled) {
}
