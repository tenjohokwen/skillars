package com.softropic.skillars.infrastructure.security;


import com.softropic.skillars.infrastructure.exception.ErrorCode;

public enum SecurityError implements ErrorCode {
    MISSING_RIGHTS, //this is when required rights are not available
    TOKEN_MANIPULATION,
    MISSING_USERNAME,
    INVALID_ACTIVATION_KEY,
    USER_NOT_FOUND,
    ACCOUNT_NOT_LOGIN_ABLE, //The account cannot login. Maybe credentials/account expired, account locked or so
    TOKEN_THEFT,
    MISSING_TOKEN,
    MISSING_CLIENT_ID,
    MISSING_JWT_PRINCIPAL, //JWT should have the principal
    MISSING_JWT_DB_REFRESH_TOKEN,
    MISSING_JWT_SECRET,
    MISSING_JWT_SESSION_CLAIMS,
    FAULTY_JWT_ROLES_CLAIMS,
    JWT_PARSE_ERROR,
    JWT_EXPIRED,
    PWD_RESET_REJECTED, //rejected password reset
    PASSWORD_RESET_FAILED,
    INVALID_LOGIN_INFO_ID, //attempt to fetch loginInfo using a non-existent id, missing in payload from client
    OTP_MISMATCH, //fetched loginInfo has a different otp from the expected otp
    OTP_EXPIRED,
    CLIENT_INFO_MISMATCH,
    OTP_ALREADY_USED,
    INVALID_OTP_PAYLOAD, //When user tries to reuse an OTP
    INVALID_OTP, //missing in payload from client
    LOGIN_ID_MISMATCH,
    EMAIL_OR_PW_MISMATCH,
    TOO_MANY_REQUESTS,
    IO_RESPONSE_ERROR,
    UNKNOWN;

    @Override
    public String getErrorCode() {
        return this.name();
    }
}
