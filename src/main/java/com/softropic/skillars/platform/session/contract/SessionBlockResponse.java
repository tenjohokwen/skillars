package com.softropic.skillars.platform.session.contract;

import java.util.List;

public record SessionBlockResponse(
    String blockType,
    String blockName,
    int durationMinutes,
    List<SessionBlockDrillResponse> drills,
    int sluSubtotal
) {}
