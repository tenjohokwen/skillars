package com.softropic.skillars.platform.reviews.contract;

/**
 * {@code reasonCode} is the same string {@link ReviewErrorCode#getErrorCode()} would produce for
 * the submit-time rejection this pre-check predicts ({@code reviews.noQualifyingSession} /
 * {@code reviews.activeDispute}), so the frontend can render it with the exact i18n key it already
 * uses for the post-submit error, instead of maintaining a second copy of the same message. Null
 * when {@code eligible} is {@code true}.
 */
public record ReviewEligibilityDto(boolean eligible, String reasonCode) {}
