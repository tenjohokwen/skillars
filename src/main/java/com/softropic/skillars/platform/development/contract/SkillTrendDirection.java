package com.softropic.skillars.platform.development.contract;

/**
 * Direction of a player's per-skill weekly SLU trend. Independent of the neglected-skill gating
 * signal — this is a coaching/analytics insight ("is this skill improving or sliding?"), not a
 * threshold that blocks anything. skillars-deferred-98 AC1b (ledger line ~1292).
 */
public enum SkillTrendDirection {
    IMPROVING,
    FLAT,
    DECLINING,
    /** Fewer than the minimum number of weekly data points, or no accumulated SLU to measure against. */
    INSUFFICIENT_DATA
}
