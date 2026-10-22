package com.softropic.skillars.platform.booking.contract;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record WrapUpRequest(
    @NotNull Boolean playerAttended,
    @Min(1) @Max(5) Integer effortRating,
    @Min(1) @Max(5) Integer focusRating,
    @Min(1) @Max(5) Integer techniqueRating,
    @Size(max = 2) List<UUID> homeworkDrillIds,
    @NotBlank @Pattern(regexp = "LIVE|QUICK") String mode,
    @NotBlank @Size(max = 500) String recapWhatWeDid,
    @NotBlank @Size(max = 500) String recapWhatsNext,
    // skillars-12-7 AC10: optional on purpose -- required would 400 every deployed client that omits it.
    @Size(max = 500) String recapWhatWentWell
) {}
