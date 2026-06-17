package com.softropic.skillars.platform.session.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record DrillMetadata(
    List<String> primarySkills,
    List<String> secondarySkills,
    Map<String, Integer> skillWeighting,
    int repDensity,
    int intensity,
    int pressureLevel,
    int cognitiveLoad,
    int matchRealism,
    boolean weakFootBias,
    String difficultyTier,
    List<String> equipmentRequired,
    String recommendedGroupSize,
    List<String> coachingPoints,
    @JsonProperty("setupDiagram") String setupDiagram
) {}
