package com.softropic.skillars.platform.development.contract;

import java.util.List;

public record TimelineResponse(
    boolean accessExpired,
    Long accessExpiryDays,              // non-null only when accessExpired=true; null on success path
    List<TimelineEventResponse> events  // empty when accessExpired=true for coaches
) {}
