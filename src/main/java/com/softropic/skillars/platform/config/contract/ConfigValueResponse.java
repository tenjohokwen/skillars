package com.softropic.skillars.platform.config.contract;

import java.time.Instant;

public record ConfigValueResponse(String key, String value, String valueType, Instant updatedAt) {
}
