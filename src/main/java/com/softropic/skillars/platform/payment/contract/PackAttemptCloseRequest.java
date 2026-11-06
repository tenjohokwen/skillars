package com.softropic.skillars.platform.payment.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** skillars-deferred-174 AC5: the admin close action's body; the reason is kept on the attempt row ({@code close_reason}). */
public record PackAttemptCloseRequest(
    @NotBlank @Size(max = 500) String reason
) {
}
