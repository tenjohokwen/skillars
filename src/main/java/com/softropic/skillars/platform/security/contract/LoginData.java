package com.softropic.skillars.platform.security.contract;

import java.time.Instant;

public interface LoginData {
    Instant getCreationDate();
    Instant getVerificationDate();
    Instant getTerminationDate();
    Instant getExpirationDate();
    String getToken();
    String getOtp();
    String getLoginId();
    String getClientId();
    String getIpAddress();
    String getRequestId();
    String getSqidSeed();
    String getSendId();
    String getSessionId();
}
