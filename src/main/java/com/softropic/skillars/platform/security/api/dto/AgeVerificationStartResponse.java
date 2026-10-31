package com.softropic.skillars.platform.security.api.dto;

/**
 * S-27: {@code url} is the provider-hosted page to send the player to; null when already VERIFIED or while a
 * submitted session is still PENDING (nothing to open).
 */
public record AgeVerificationStartResponse(String state, String url) {
}
