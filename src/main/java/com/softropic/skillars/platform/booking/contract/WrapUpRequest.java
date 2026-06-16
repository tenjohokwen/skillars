package com.softropic.skillars.platform.booking.contract;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.UUID;

public record WrapUpRequest(
    @NotNull Boolean playerAttended,
    @Min(1) @Max(5) Integer effortRating,
    @Min(1) @Max(5) Integer focusRating,
    @Min(1) @Max(5) Integer techniqueRating,
    String voiceNoteText,
    List<UUID> homeworkDrillIds,
    @NotBlank @Pattern(regexp = "LIVE|QUICK") String mode
) {}
