package com.softropic.skillars.platform.booking.contract;

import jakarta.validation.constraints.Size;

/** skillars-deferred-155 AC5: null/blank clears a previously-set player request. */
public record UpdatePlayerRequestRequest(
    @Size(max = 500) String playerRequest
) {
}
