package com.softropic.skillars.platform.security.service;


import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.security.contract.LoginData;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.contract.event.SendMailEvent;
import com.softropic.skillars.platform.security.contract.util.ShortCode;
import com.softropic.skillars.platform.security.repo.LoginInfo;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class TwoFactorLoginService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final LoginInfoService          loginInfoService;
    private final ApplicationEventPublisher publisher;
    private final LoginTokenManager         loginTokenManager;

    public TwoFactorLoginService(LoginInfoService loginInfoService,
                                 ApplicationEventPublisher publisher,
                                 LoginTokenManager loginTokenManager) {
        this.loginInfoService = loginInfoService;
        this.publisher = publisher;
        this.loginTokenManager = loginTokenManager;
    }

    /**
     * Generates a secure 6-digit OTP code.
     * @return 6-digit string with leading zeros preserved
     */
    private static String generateSixDigitOtp() {
        int code = SECURE_RANDOM.nextInt(1_000_000);
        return String.format("%06d", code);
    }

    public record LoginRef(Long id, String sendId){}

    public LoginRef processLogin(Principal principal) {
        //TODO The goal was to obfuscate ids using the seed of the user after login. The issue here is that we will have issues if we want a user to say put stuff in a shopping cart and then login at checkout
        // Or if we want to relog a user and continue after his token had expired. This would complicate stuff
        final String seed = ShortCode.generateSeed();
        final String token = loginTokenManager.generateToken(principal, seed);
        final UUID uuid = UUID.randomUUID();
        final String helpCodeStr = ShortCode.shortenInt(uuid.hashCode());
        final String otp = generateSixDigitOtp();
        final LoginInfo loginInfo = loginInfoService.saveLoginInfo(token, otp, principal.getUsername(), seed, helpCodeStr);
        final Instant deadline = Instant.now(ClockProvider.getClock()).plus(Duration.ofMinutes(10));
        final SendMailEvent sendMailEvent = new SendMailEvent(List.of(Long.parseLong(principal.getBusinessId())),
                                                              EmailTemplate.SEND_OTP,
                                                              deadline,
                                                              Map.of("otpCode", otp, "helpCode", helpCodeStr),
                                                              helpCodeStr);
        publisher.publishEvent(sendMailEvent);
        return new LoginRef(loginInfo.getId(), loginInfo.getSendId());
    }

    public LoginData fetchFor2FA(Long lii, String otp) {
        final LoginData loginData = loginInfoService.fetchValidLoginData(lii, otp);
        loginInfoService.markLoginInfoAsConsumed(lii);
        return loginData;
    }
}
