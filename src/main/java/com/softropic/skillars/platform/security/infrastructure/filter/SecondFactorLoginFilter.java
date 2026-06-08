package com.softropic.skillars.platform.security.infrastructure.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.infrastructure.message.Success;
import com.softropic.skillars.platform.security.contract.LoginData;
import com.softropic.skillars.infrastructure.security.event.AuthenticationAction;
import com.softropic.skillars.infrastructure.security.event.AuthEvent;
import com.softropic.skillars.platform.security.service.LoginTokenManager;
import com.softropic.skillars.platform.security.contract.exception.SecException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.infrastructure.security.RequestMetadata;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import com.softropic.skillars.platform.security.contract.util.ShortCode;
import com.softropic.skillars.platform.security.service.TwoFactorLoginService;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.softropic.skillars.infrastructure.security.SecurityError.INVALID_LOGIN_INFO_ID;
import static com.softropic.skillars.infrastructure.security.SecurityError.INVALID_OTP;


public class SecondFactorLoginFilter extends OncePerRequestFilter {

    private static final String OTP = "otp";
    private static final RequestMatcher ENDPOINT_MATCHER = PathPatternRequestMatcher.withDefaults().matcher("/otp");
    public static final String                ENDPOINT = "endpoint";
    private final       TwoFactorLoginService twoFactorLoginManager;
    private final       LoginTokenManager     loginTokenManager;
    private final ApplicationEventPublisher publisher;

    public SecondFactorLoginFilter(TwoFactorLoginService twoFactorLoginManager,
                                   LoginTokenManager loginTokenManager, ApplicationEventPublisher publisher) {
        this.twoFactorLoginManager = twoFactorLoginManager;
        this.loginTokenManager = loginTokenManager;
        this.publisher = publisher;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws IOException {
        final String loginInfoIdCookie = loginTokenManager.extractLoginInfoIdCookie(request);
        final Map<String, String> twoFactorData = readInputStreamAsMap(request);
        final String loginInfoId = twoFactorData.get("loginInfoId");
        final String otp = twoFactorData.get(OTP);
        if(StringUtils.isBlank(loginInfoId)) {
            throw new SecException("LoginInfoId not found in payload", Map.of(ENDPOINT, OTP), INVALID_LOGIN_INFO_ID);
        }
        if(StringUtils.isBlank(otp)) {
            throw new SecException("otp not found in payload", Map.of(ENDPOINT, OTP), INVALID_OTP);
        }
        if(StringUtils.equals(loginInfoIdCookie, loginInfoId) ) {
            final Long lii = ShortCode.revertUsingDefault(loginInfoId);
            //Having access to the entity here is a possible violation.
            // On the other hand this access is from the same module and the transaction is already terminated.
            // Adding internal dtos would lead to over-engineering
            final LoginData loginData = twoFactorLoginManager.fetchFor2FA(lii, otp);
            final String dbClientId = loginData.getClientId();
            final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();
            if(StringUtils.equals(dbClientId, clientInfo.getClientIdentifier()) &&
                    StringUtils.equals(loginData.getSessionId(), clientInfo.getSessionId())) {
                final Authentication auth = loginTokenManager.refreshLoginToken(response, loginData.getToken());
                //maybe this should not be removed? Just let it expire or else you may be revealing secrets?
                loginTokenManager.removeTwoFactorCookie(response);
                successResponse(response);
                publisher.publishEvent(new AuthEvent(auth, AuthenticationAction.SUCCESSFUL_2FA));
            } else {
                final String msg = "Current client info and that at initial authentication are not identical";
                final Map<String, Object> ctx = Map.of("initial clientId", dbClientId,
                                                       "current clientId", clientInfo.getClientIdentifier(),
                                                       "initial sessionId", loginData.getSessionId(),
                                                       "current sessionId", clientInfo.getSessionId());
                throw new SecException(msg, ctx, SecurityError.CLIENT_INFO_MISMATCH);
            }
        } else {
            final String msg = "Current loginInfoId is not identical with cookie value";
            final Map<String, Object> ctx = Map.of("cookie loginInfoId", loginInfoIdCookie,
                                                   "current loginInfoId", loginInfoId);
            throw new SecException(msg, ctx, SecurityError.LOGIN_ID_MISMATCH);
        }
    }

    private static void successResponse(HttpServletResponse res) throws IOException {
        final Success response = new Success("", "login.success", "Login was successful", Map.of());
        try(final PrintWriter writer = res.getWriter()) {
            res.setContentType("application/json");
            res.setCharacterEncoding("UTF-8");
            res.setStatus(HttpServletResponse.SC_OK); //TODO 201? 200? 202?
            writer.print(new ObjectMapper().writeValueAsString(response));
            writer.flush(); //writer is then closed by the container
        }
    }

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        return !ENDPOINT_MATCHER.matches(request) || StringUtils.isBlank(loginTokenManager.extractLoginInfoIdCookie(request));
    }

    private Map readInputStreamAsMap(final HttpServletRequest req) {
        final Map twoFactorData;
        try {
            twoFactorData = new ObjectMapper().readValue(req.getInputStream(), Map.class);
        }
        catch (IOException exc) {
            throw new SecException(
                    "Error occured while reading request input stream for otp.",
                    exc, Map.of(ENDPOINT, OTP), SecurityError.INVALID_OTP_PAYLOAD);
        }
        return twoFactorData;
    }
}
