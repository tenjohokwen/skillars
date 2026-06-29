package com.softropic.skillars.platform.reviews.contract;

import java.util.UUID;

public record ReviewSubmittedEvent(
    UUID reviewId,
    UUID coachId,
    Long authorId,
    int rating,
    String body) {}
