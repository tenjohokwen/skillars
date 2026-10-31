package com.softropic.skillars.platform.security.contract.exception;

import com.softropic.skillars.platform.security.contract.AgeVerificationErrorCode;

/**
 * S-27: a gate refused because of age-verification state (or the verification flow itself was refused). A business
 * outcome the SPA acts on, deliberately not an {@link OperationNotAllowedException}: that one raises a security
 * alert per hit, and "please verify your age" is the expected path for a new player, not an incident.
 */
public class AgeVerificationException extends RuntimeException {

    private final AgeVerificationErrorCode errorCode;

    public AgeVerificationException(AgeVerificationErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AgeVerificationErrorCode getErrorCode() {
        return errorCode;
    }
}
