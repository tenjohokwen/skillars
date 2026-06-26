package com.softropic.skillars.platform.messaging.contract;

import java.time.Instant;

public record ModerationResult(MessageModerationStatus moderationStatus, Instant deliveredAt) {}
