package com.softropic.skillars.platform.development.contract;

public enum CorrelationInsightType {
    HIGH_SLU_IMPROVEMENT,           // high training volume, positive composite change
    HIGH_SLU_NO_IMPROVEMENT,        // high training volume, no/negative composite change
    LOW_SLU_IMPROVEMENT,            // below-average SLU but composite still improved (natural talent)
    LOW_SLU_STABLE                  // below-average SLU, no significant composite change
}
