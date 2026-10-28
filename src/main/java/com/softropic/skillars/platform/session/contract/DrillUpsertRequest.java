package com.softropic.skillars.platform.session.contract;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Create / full-replace body for a coach's own PRIVATE drill (D1). Mirrors the {@link DrillMetadata} shape the
 * clone path copies, minus {@code setupDiagram}: that field is rendered as an {@code <img src>} and only ever
 * comes from seed data, so a coach cannot supply an arbitrary URL through it (an update preserves the existing
 * value instead).
 *
 * <p>{@code skillWeighting} is optional: when absent the service derives it from the primary/secondary skill
 * lists (or keeps the drill's existing weighting on update when it still covers exactly the same skills), so
 * the frontend does not have to ask a coach for percentages.
 */
public record DrillUpsertRequest(
    @NotBlank @Size(max = 200) String name,
    @Size(max = 2000) String description,
    @NotEmpty @Size(max = 5) List<@NotBlank @Size(max = 40) String> primarySkills,
    @Size(max = 5) List<@NotBlank @Size(max = 40) String> secondarySkills,
    @Size(max = 10) Map<@NotBlank @Size(max = 40) String, @NotNull @Min(0) @Max(100) Integer> skillWeighting,
    @Min(0) @Max(200) int repDensity,
    @Min(0) @Max(10) int intensity,
    @Min(0) @Max(10) int pressureLevel,
    @Min(0) @Max(10) int cognitiveLoad,
    @Min(0) @Max(10) int matchRealism,
    boolean weakFootBias,
    @NotBlank @Pattern(regexp = "U8|U10|U12|U14|U16|U18|Adult") String difficultyTier,
    @Size(max = 10) List<@NotBlank @Size(max = 40) String> equipmentRequired,
    @Size(max = 20) String recommendedGroupSize,
    @Size(max = 8) List<@NotBlank @Size(max = 300) String> coachingPoints
) {}
