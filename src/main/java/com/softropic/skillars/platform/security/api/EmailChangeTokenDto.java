package com.softropic.skillars.platform.security.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The token from an email-change confirm or undo link (skillars-deferred-172). */
public record EmailChangeTokenDto(@NotBlank @Size(max = 64) String token) {
}
