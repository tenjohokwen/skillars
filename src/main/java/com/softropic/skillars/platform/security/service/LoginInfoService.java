package com.softropic.skillars.platform.security.service;



import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.security.contract.LoginData;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.contract.exception.SecException;
import com.softropic.skillars.infrastructure.security.RequestMetadata;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import com.softropic.skillars.platform.security.repo.LoginInfo;
import com.softropic.skillars.platform.security.repo.LoginInfoRepository;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.softropic.skillars.infrastructure.security.SecurityError.CLIENT_INFO_MISMATCH;
import static com.softropic.skillars.infrastructure.security.SecurityError.INVALID_LOGIN_INFO_ID;
import static com.softropic.skillars.infrastructure.security.SecurityError.OTP_ALREADY_USED;
import static com.softropic.skillars.infrastructure.security.SecurityError.OTP_EXPIRED;
import static com.softropic.skillars.infrastructure.security.SecurityError.OTP_MISMATCH;


@Service
@Transactional
public class LoginInfoService {
    private final LoginInfoRepository loginInfoRepository;

    public LoginInfoService(LoginInfoRepository loginInfoRepository) {this.loginInfoRepository = loginInfoRepository;}

    public LoginInfo saveLoginInfo(String token, String otp, String loginId, String sqidSeed, String sendId) {
        final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();
        final LoginInfo loginInfo = new LoginInfo();
        loginInfo.setToken(token);
        loginInfo.setOtp(otp);
        loginInfo.setLoginId(loginId);
        loginInfo.setSqidSeed(sqidSeed);
        final Instant now = Instant.now(ClockProvider.getClock());
        loginInfo.setCreationDate(now);
        loginInfo.setExpirationDate(now.plus(SecurityConstants.OTP_TTL));
        loginInfo.setRequestId(clientInfo.getRequestId());
        loginInfo.setClientId(clientInfo.getClientIdentifier());
        loginInfo.setIpAddress(clientInfo.getIpAddress());
        loginInfo.setSendId(sendId);
        loginInfo.setSessionId(clientInfo.getSessionId());
        return loginInfoRepository.save(loginInfo);
    }

    public LoginData fetchValidLoginData(Long loginInfoId, String otp) {
        final Optional<LoginData> loginDataOpt = loginInfoRepository.findOneById(loginInfoId);
        final Map<String, Object> ctx = Map.of("loginInfoId", loginInfoId,
                                               "otp", otp);
        //TODO test the scenarios
        if(loginDataOpt.isEmpty()) {
            //throw exception //throw exception indicating that no match was found. Also create an event and log (this endpoint could be misused
            throw new SecException("loginInfo could not be found in the database", ctx, INVALID_LOGIN_INFO_ID);
        }
        final LoginData loginData = loginDataOpt.get();
        if(!StringUtils.equals(loginData.getOtp(), otp)) {
            final Map<String, Object> ctxWithFetchedOtp = new HashMap<>(ctx);
            ctxWithFetchedOtp.put("fetchedOTP", loginData.getOtp());
            throw new SecException("otp of loginInfo does not match the expected otp",
                                   ctxWithFetchedOtp,
                                   OTP_MISMATCH);
        }
        if(loginData.getExpirationDate().isBefore(Instant.now(ClockProvider.getClock()))) {
            final Map<String, Object> ctxWithExpiration = new HashMap<>(ctx);
            ctxWithExpiration.put("expiration", loginData.getExpirationDate());
            throw new SecException("The validity of the otp has expired",
                                   ctxWithExpiration,
                                   OTP_EXPIRED);
        }
        final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();
        if(!StringUtils.equals(loginData.getClientId(), clientInfo.getClientIdentifier()) ||
                !StringUtils.equals(loginData.getSessionId(), clientInfo.getSessionId())) {
            //throw fraud alert
            final Map<String, Object> ctxWithClientId = new HashMap<>(ctx);
            ctxWithClientId.put("initial clientId", loginData.getClientId());
            ctxWithClientId.put("current clientId", clientInfo.getClientIdentifier());
            ctxWithClientId.put("initial sessionId", loginData.getSessionId());
            ctxWithClientId.put("current sessionId", clientInfo.getSessionId());
            throw new SecException("Check the client id and sessionId. They have to match. See context variables",
                                   ctxWithClientId,
                                   CLIENT_INFO_MISMATCH);
        }
        if(loginData.getVerificationDate() != null) {
            //
            throw new SecException("otp has already been used", ctx, OTP_ALREADY_USED);
        }
        return loginData;
    }

    public boolean markLoginInfoAsConsumed(Long loginInfoId) {
        //loginInfo is likely in the session so call whole object and update instead of making an update query
        return loginInfoRepository.findById(loginInfoId)
                                  .map(loginInfo -> {
                                      loginInfo.setVerificationDate(Instant.now(ClockProvider.getClock()));
                                      return loginInfo;
                                  }).isPresent();
    }


}
