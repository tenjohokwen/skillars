package com.softropic.skillars.platform.security.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * skillars-deferred-93 AC8 — {@code verificationToken} is the opaque handle from
 * {@link VerifyEmailResponse}; the resource resolves the {@code userId} from it server-side. A
 * raw {@code userId} is no longer accepted on this {@code permitAll} endpoint.
 *
 * <p>Only an upper {@code @Size} bound is applied: a null / blank / short / garbage token must reach
 * {@code RegistrationVerificationTokenService.resolveUserId}, which rejects every such case with the
 * one uniform {@code security.verificationLinkInvalid} 400 — a {@code @NotBlank} here would instead
 * surface a distinct bean-validation body for the blank case.
 */
public record VerifyPhoneRequest(
    @Size(max = 200) String verificationToken,
    @NotBlank @Size(min = 6, max = 6) String otp
) {}
