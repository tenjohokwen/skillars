package com.softropic.skillars.platform.marketplace.contract;

import java.util.List;

public record CoachSearchResponse(
    List<CoachCardDto> coaches,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean hasNext
) {}
