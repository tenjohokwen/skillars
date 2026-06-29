package com.softropic.skillars.platform.reviews.contract;

import java.util.List;

public record CoachOwnReviewListResponse(
    List<CoachOwnReviewDto> reviews,
    int page,
    int totalPages,
    long totalElements,
    boolean hasNext
) {}
