package com.softropic.skillars.platform.reviews.contract;

import java.util.List;

public record ReviewListResponse(
    List<ReviewDto> reviews,
    int page,
    int totalPages,
    long totalElements,
    boolean hasNext
) {}
