package com.softropic.skillars.platform.development.contract;

import java.util.List;

/**
 * The calling coach's own player roster, sorted by {@code attentionScore} descending.
 * skillars-12-1 AC1/AC5.
 */
public record RosterResponse(List<RosterEntryResponse> players) {}
