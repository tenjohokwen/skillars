package com.softropic.skillars.platform.security.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * skillars-deferred-93 AC8 — carries the opaque phone-verification handle from
 * {@link VerifyEmailResponse} instead of a client-set {@code userId}, removing the
 * account-enumeration surface from this {@code permitAll} endpoint.
 */
public record ResendOtpRequest(@NotBlank @Size(min = 50, max = 200) String verificationToken) {}
