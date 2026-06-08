package com.softropic.skillars.platform.security.infrastructure.jwt.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.infrastructure.message.Success;
import com.softropic.skillars.infrastructure.validation.InputValidator;
import com.softropic.skillars.infrastructure.security.event.AuthenticationAction;
import com.softropic.skillars.infrastructure.security.event.AuthEvent;
import com.softropic.skillars.infrastructure.security.event.PreAuthEvent;
import com.softropic.skillars.platform.security.service.LoginTokenManager;
import com.softropic.skillars.infrastructure.security.CookieUtil;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.contract.LoginIdType;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import com.softropic.skillars.platform.security.contract.util.ShortCode;
import com.softropic.skillars.platform.security.service.TwoFactorLoginService;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.JWT_SESSION_COOKIE;
import static com.softropic.skillars.infrastructure.security.SecurityError.IO_RESPONSE_ERROR;


public class JWTAuthenticationFilter extends AbstractAuthenticationProcessingFilter {

    private final AuthenticationManager     authenticationManager;
    private final ApplicationEventPublisher publisher;
    private final HandlerExceptionResolver handlerExceptionResolver;
    private final TwoFactorLoginService twoFactorLoginManager;
    private final LoginTokenManager     loginTokenManager;

    public JWTAuthenticationFilter(AuthenticationManager authenticationManager,
                                   ApplicationEventPublisher publisher,
                                   HandlerExceptionResolver resolver,
                                   TwoFactorLoginService twoFactorLoginManager,
                                   LoginTokenManager loginTokenManager
    ) {
        super("/authenticate");
        this.authenticationManager = authenticationManager;
        this.publisher = publisher;
        this.handlerExceptionResolver = resolver;
        this.twoFactorLoginManager = twoFactorLoginManager;
        this.loginTokenManager = loginTokenManager;
    }

    @Override
    public Authentication attemptAuthentication(final HttpServletRequest req,
                                                final HttpServletResponse res) {
        if(req.getMethod().equals("POST")) {
            loginTokenManager.ensureClientHasPreLoginId();
            //The assumption here is that only login info is passed in the body
            final UserDetails userDetails = buildUserDetails(req);
            final UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.unauthenticated(
                    userDetails,
                    userDetails.getPassword());
            publisher.publishEvent(new PreAuthEvent(authentication, AuthenticationAction.PRE_AUTHENTICATION));
            return authenticationManager.authenticate(authentication);
        }
        throw new AuthenticationServiceException("Authentication method not supported: %s".formatted(req.getMethod()));
    }

    @Override
    protected void successfulAuthentication(final HttpServletRequest req,
                                            final HttpServletResponse res,
                                            final FilterChain chain,
                                            final Authentication auth) {
        final Principal principal = (Principal) auth.getPrincipal();
        // This will help to be able to trace how user logged and all actions. Session id should be stored in JWT.
        // The auditTrail also needs it.
        CookieUtil.addCookie(res, JWT_SESSION_COOKIE, RequestMetadataProvider.getClientInfo().getSessionId(), true, -1);

        if(principal.isOtpEnabled()) { //At the moment the flow will always enter here
            final TwoFactorLoginService.LoginRef loginRef = twoFactorLoginManager.processLogin(principal);
            final String obfuscatedLii = ShortCode.shortenUsingDefault(loginRef.id());
            loginTokenManager.addLoginInfoIdCookie(res, obfuscatedLii);
            otpResponse(res, obfuscatedLii, loginRef.sendId());
            publisher.publishEvent(new AuthEvent(auth, AuthenticationAction.SUCCESSFUL_PRE_2FA));
        } else {
            loginTokenManager.createLoginToken(res, principal);
            jwtResponse(res);
            publisher.publishEvent(new AuthEvent(auth, AuthenticationAction.SUCCESSFUL_AUTHENTICATION));
        }
    }

    private static void otpResponse(HttpServletResponse res, String loginInfoId, String helpCode) {
        final Success success = new Success(helpCode,
                                            "check.otp",
                                            "Check your email for the otp",
                                            Map.of("loginInfoId", loginInfoId));
        buildResponse(res, success);
    }

    private static void jwtResponse(HttpServletResponse res) {
        final String code = "JWT_CREATED";
        final Success success = new Success(code, "jwt.created", "JWT token has been created", Map.of());
        buildResponse(res, success);
    }

    private static void buildResponse(HttpServletResponse res, Success success) {
        try(PrintWriter writer = res.getWriter()) {
            res.setStatus(HttpServletResponse.SC_OK);
            res.setContentType("application/json");
            res.setCharacterEncoding("UTF-8");
            writer.print(new ObjectMapper().writeValueAsString(success));
            writer.flush();
        } catch (IOException ioe) {
            throw new ApplicationException("IOError occurred while writing to response",
                                           Map.of("response", success),
                                           IO_RESPONSE_ERROR);
        }
    }

    @Override
    protected void unsuccessfulAuthentication(final HttpServletRequest request,
                                              final HttpServletResponse response,
                                              final AuthenticationException authException) {
        //this sends exceptions to the controllerAdvice
        handlerExceptionResolver.resolveException(request, response, null, authException);
    }


    private UserDetails buildUserDetails(final HttpServletRequest req) {
        final Credentials creds = toCreds(readInputStreamAsMap(req));
        validateCredentials(creds);
        final Principal.Builder builder = new Principal.Builder().password(creds.password())
                                                                 .enabled(true)
                                                                 .otpEnabled(true)
                                                                 .authorities(List.of());
        builder.username(creds.id());
        if(creds.loginIdType == LoginIdType.EMAIL) {
            builder.loginType(LoginIdType.EMAIL);
        }
        else if (creds.loginIdType == LoginIdType.PHONE){
            builder.phone(creds.id);
            builder.loginType(LoginIdType.PHONE);
        }
        else {
            builder.loginType(LoginIdType.USERNAME);
        }
        return builder.build();
    }

    private Credentials toCreds(Map<String, String> credentials) {
        final Integer code = getLoginCode(credentials);
        final LoginIdType loginIdType = LoginIdType.fromCode(code).orElse(LoginIdType.EMAIL);
        if(loginIdType == LoginIdType.EMAIL) {
            return new Credentials(credentials.get(SecurityConstants.LOGIN_ID), credentials.get(SecurityConstants.PASSWORD_KEY), LoginIdType.EMAIL);
        }
        if(loginIdType == LoginIdType.PHONE) {
            return  new Credentials(credentials.get(SecurityConstants.PHONE), credentials.get(SecurityConstants.PASSWORD_KEY), LoginIdType.PHONE);
        }
        if(loginIdType == LoginIdType.USERNAME) {
            return  new Credentials(credentials.get(SecurityConstants.USERNAME), credentials.get(SecurityConstants.PASSWORD_KEY), LoginIdType.USERNAME);
        }
        return null;
    }

    private Integer getLoginCode(Map<String, String> credentials) {
        try {
            //This code looks a bit convoluted but it is to handle the case where the value login code is actually an integer. Somehow it is passed as an integer.
            Object code = credentials.get(SecurityConstants.LOGIN_CODE);
            code = Objects.isNull(code) ? 1 : code;
            if(code instanceof Integer) {
                return (Integer) code;
            }
            return Integer.valueOf(code.toString());
        }
        catch (NumberFormatException e) {
            // LoginIdType.EMAIL is the default
            return LoginIdType.EMAIL.getCode();
        }
    }


    private Map<String, String> readInputStreamAsMap(final HttpServletRequest req) {
        final Map<String, String> credentials;
        try {
            credentials = new ObjectMapper().readValue(req.getInputStream(), Map.class);
        }
        catch (IOException | IllegalArgumentException exc) {
            throw new BadCredentialsException(
                    "Error occured while reading request input stream during authentication.",
                    exc);
        }
        return credentials;
    }

    private void validateCredentials(Credentials credentials) {
        if(credentials != null) {
            if (credentials.loginIdType == LoginIdType.EMAIL && !InputValidator.isValidEmail(credentials.id)) { //TODO unify email validation. This and @Email should have the same regex
                final String msg = String.format("Invalid email email: '%s'", credentials.id);
                throw new BadCredentialsException(msg);
            }
            if (credentials.loginIdType == LoginIdType.PHONE && StringUtils.isNotBlank(credentials.id)) {
                final String msg = String.format("Invalid phone: '%s'", credentials.id);
                throw new BadCredentialsException(msg);
            }
            if (StringUtils.isBlank(credentials.password)) {
                throw new BadCredentialsException("The password should not be blank");
            }
        } else {
            throw new BadCredentialsException("No credentials found");
        }
    }

    record Credentials(String id, String password, LoginIdType loginIdType) {}
}
