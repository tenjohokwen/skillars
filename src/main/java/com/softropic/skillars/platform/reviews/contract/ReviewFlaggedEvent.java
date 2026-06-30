package com.softropic.skillars.platform.reviews.contract;

import java.util.UUID;

public record ReviewFlaggedEvent(UUID reviewId, UUID coachId, long flagCount, boolean autoHeld) {}
