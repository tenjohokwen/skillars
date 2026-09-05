package com.softropic.skillars.platform.security.api.dto;

/**
 * skillars-deferred-93 AC8 — {@code verificationToken} is an opaque, server-signed handle the SPA
 * carries into the phone-verification flow. It replaced a raw {@code userId}, which was a
 * client-set query param on {@code permitAll} endpoints and an account-enumeration surface.
 */
public record VerifyEmailResponse(String nextStep, String verificationToken) {}
