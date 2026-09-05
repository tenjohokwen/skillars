package com.softropic.skillars.platform.security.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * skillars-deferred-93 AC8 — carries the opaque phone-verification handle from
 * {@link VerifyEmailResponse} instead of a client-set {@code userId}, removing the
 * account-enumeration surface from this {@code permitAll} endpoint.
 */
public record ResendOtpRequest(@NotBlank String verificationToken) {}
