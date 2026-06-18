package com.softropic.skillars.platform.session.contract;

import java.util.List;

public record SessionBlockData(
    String blockType,
    String blockName,
    int durationMinutes,
    List<SessionDrillRef> drills
) {}
