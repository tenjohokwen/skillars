package com.softropic.skillars.platform.booking.contract;

import java.util.List;

public record ParentScheduleResponse(
    Long playerId,
    List<ParentScheduleItem> sessions
) {}
