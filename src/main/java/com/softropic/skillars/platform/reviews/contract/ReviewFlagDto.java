package com.softropic.skillars.platform.reviews.contract;

import java.time.Instant;

public record ReviewFlagDto(String reason, String details, Instant createdAt) {}
