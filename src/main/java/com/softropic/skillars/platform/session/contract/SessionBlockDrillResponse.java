package com.softropic.skillars.platform.session.contract;

import java.util.UUID;

public record SessionBlockDrillResponse(UUID drillId, int order, DrillResponse drill) {}
