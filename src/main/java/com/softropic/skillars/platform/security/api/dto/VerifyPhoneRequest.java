package com.softropic.skillars.platform.security.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * skillars-deferred-93 AC8 — {@code verificationToken} is the opaque handle from
 * {@link VerifyEmailResponse}; the resource resolves the {@code userId} from it server-side. A
 * raw {@code userId} is no longer accepted on this {@code permitAll} endpoint.
 */
public record VerifyPhoneRequest(
    @NotBlank @Size(min = 50, max = 200) String verificationToken,
    @NotBlank @Size(min = 6, max = 6) String otp
) {}
