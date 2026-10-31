package com.softropic.skillars.platform.security.contract;

import com.softropic.skillars.infrastructure.exception.ErrorCode;

/** S-27: wire codes shared by every gate (booking, chat) so the SPA branches on one code. */
public enum AgeVerificationErrorCode implements ErrorCode {
    /** The caller is a self-registered player who must verify their age first. The SPA sends them to the flow. */
    AGE_VERIFICATION_REQUIRED("security.ageVerificationRequired"),
    /** The other party is a self-registered player who has not verified yet (coach side; reveals nothing more). */
    PLAYER_NOT_AGE_VERIFIED("security.playerNotAgeVerified"),
    /** Fallback mode (verification unavailable): chat opens only after a completed session with this coach. */
    COMPLETED_SESSION_REQUIRED("security.completedSessionRequired"),
    /** Daily cap on verification attempts reached. */
    ATTEMPTS_EXCEEDED("security.ageVerificationAttemptsExceeded"),
    /** The feature is switched off or Stripe Identity is not configured. */
    UNAVAILABLE("security.ageVerificationUnavailable");

    private final String code;

    AgeVerificationErrorCode(String code) { this.code = code; }

    @Override
    public String getErrorCode() { return code; }
}
