package com.softropic.skillars.platform.booking.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** skillars-12-7 AC2. {@code whatWentWell} is optional; blank is stored as null. */
public record UpdateSessionRecapRequest(
    @NotBlank @Size(max = 500) String whatWeDid,
    @NotBlank @Size(max = 500) String whatsNext,
    @Size(max = 500) String whatWentWell
) {}
