package com.softropic.skillars.platform.security.contract.exception;

/**
 * skillars-deferred-93 AC8 — a {@code /verify-phone} or {@code /resend-otp} request carried a
 * phone-verification handle that is malformed, has a bad signature, is expired, or is for the wrong
 * purpose.
 *
 * <p>Extends {@link OtpVerificationException} so {@code ApiAdvice.otpVerificationExceptionHandler}
 * maps it to a clean {@code 400} with the carried error key — never the {@code 500} that a raw
 * parse/verify failure on these {@code permitAll} endpoints would otherwise produce (the exact
 * "garbage param → raw 500" class of bug skillars-deferred-92 kept closing). The message is uniform
 * for every failure mode so it cannot become an oracle.
 */
public class RegistrationVerificationTokenException extends OtpVerificationException {

    public RegistrationVerificationTokenException() {
        super("security.verificationLinkInvalid");
    }
}
