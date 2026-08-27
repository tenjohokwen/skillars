package com.softropic.skillars.platform.session.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;
import java.util.Map;

// Story Deferred-76 AC10: @PositiveOrZero on the five rating fields below is inert today — no live
// endpoint accepts user-supplied DrillMetadata (the only new Drill() call site,
// DrillLibraryService.cloneDrill, copies metadata verbatim from an already-existing seed-migration
// drill), so nothing currently invokes @Valid on this record. Kept as documentation of the expected
// range for whenever a real creation/update endpoint exists. The functionally-effective guard against
// negative values today is in SluFormula.calculate.
public record DrillMetadata(
    List<String> primarySkills,
    List<String> secondarySkills,
    Map<String, Integer> skillWeighting,
    @PositiveOrZero int repDensity,
    @PositiveOrZero int intensity,
    @PositiveOrZero int pressureLevel,
    @PositiveOrZero int cognitiveLoad,
    @PositiveOrZero int matchRealism,
    boolean weakFootBias,
    String difficultyTier,
    List<String> equipmentRequired,
    String recommendedGroupSize,
    List<String> coachingPoints,
    @JsonProperty("setupDiagram") String setupDiagram
) {}
