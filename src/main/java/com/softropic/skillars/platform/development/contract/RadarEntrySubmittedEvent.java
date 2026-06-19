package com.softropic.skillars.platform.development.contract;

import java.util.Set;

public record RadarEntrySubmittedEvent(Long playerId, Long parentId, Set<String> skillCodes) {}
