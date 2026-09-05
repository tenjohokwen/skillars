package com.softropic.skillars.platform.security.api.dto;

import jakarta.validation.constraints.Size;

/**
 * skillars-deferred-93 AC8 — carries the opaque phone-verification handle from
 * {@link VerifyEmailResponse} instead of a client-set {@code userId}, removing the
 * account-enumeration surface from this {@code permitAll} endpoint.
 *
 * <p>Only an upper {@code @Size} bound is applied: a null / blank / short / garbage handle must reach
 * {@code RegistrationVerificationTokenService.resolveUserId}, which rejects every such case with the
 * one uniform {@code security.verificationLinkInvalid} 400 — a {@code @NotBlank} here would instead
 * surface a distinct bean-validation body for the blank case.
 */
public record ResendOtpRequest(@Size(max = 200) String verificationToken) {}
