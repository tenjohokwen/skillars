package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer;
import com.softropic.skillars.infrastructure.security.RateLimitingService;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.api.dto.VerifyEmailResponse;
import com.softropic.skillars.platform.security.contract.SkillarsVerificationStatus;
import com.softropic.skillars.platform.security.contract.event.CoachOtpEmailEvent;
import com.softropic.skillars.platform.security.repo.AuthorityRepository;
import com.softropic.skillars.platform.security.repo.EmailVerificationToken;
import com.softropic.skillars.platform.security.repo.EmailVerificationTokenRepository;
import com.softropic.skillars.platform.security.repo.PhoneOtpToken;
import com.softropic.skillars.platform.security.repo.PhoneOtpTokenRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-138 AC2 — no existing unit test covered {@code CoachRegistrationService.verifyEmail}'s
 * {@code nextStep} branching before this story; the pre-existing IT coverage
 * ({@code CoachRegistrationResourceIT}) only ever exercised the real seeded
 * {@code security.registration.phone-otp-required = false} value via the (buggy, hardcoded) old
 * behavior. New coverage, not an extension.
 */
@ExtendWith(MockitoExtension.class)
class CoachRegistrationServiceTest {

    private static final Long USER_ID = 42L;
    private static final String CONFIG_KEY = "security.registration.phone-otp-required";

    @Mock UserRepository userRepository;
    @Mock AuthorityRepository authorityRepository;
    @Mock EmailVerificationTokenRepository emailTokenRepository;
    @Mock PhoneOtpTokenRepository otpTokenRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock ApplicationEventPublisher publisher;
    @Mock ContactDetailSanitizer sanitizer;
    @Mock RateLimitingService rateLimitingService;
    @Mock RegistrationOtpResendSupport otpResendSupport;
    @Mock RegistrationVerificationTokenService verificationTokenService;
    @Mock ConfigService configService;

    private CoachRegistrationService service;

    @BeforeEach
    void setUp() {
        service = new CoachRegistrationService(userRepository, authorityRepository, emailTokenRepository,
            otpTokenRepository, passwordEncoder, publisher, sanitizer, rateLimitingService, otpResendSupport,
            verificationTokenService, configService);
    }

    private EmailVerificationToken buildToken(UUID token) {
        EmailVerificationToken evt = new EmailVerificationToken();
        evt.setUserId(USER_ID);
        evt.setToken(token);
        evt.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        evt.setUsed(false);
        return evt;
    }

    private User buildUser() {
        User user = new User();
        user.setId(USER_ID);
        user.setVerificationStatus(SkillarsVerificationStatus.UNVERIFIED);
        user.setLocked(false);
        return user;
    }

    @Test
    void verifyEmail_phoneOtpRequired_generatesOtpAndReturnsVerifyPhoneStep() {
        UUID token = UUID.randomUUID();
        when(emailTokenRepository.findByToken(token)).thenReturn(Optional.of(buildToken(token)));
        when(userRepository.findOneById(USER_ID)).thenReturn(Optional.of(buildUser()));
        when(configService.getBoolean(CONFIG_KEY, true)).thenReturn(true);
        when(verificationTokenService.issuePhoneVerificationToken(USER_ID, "COACH")).thenReturn("handle-123");

        VerifyEmailResponse response = service.verifyEmail(token);

        assertThat(response.nextStep()).isEqualTo("verify-phone");
        assertThat(response.verificationToken()).isEqualTo("handle-123");
        verify(otpTokenRepository).deleteByUserIdAndUsedFalse(USER_ID);
        verify(otpTokenRepository).saveAndFlush(any(PhoneOtpToken.class));
        verify(publisher).publishEvent(any(CoachOtpEmailEvent.class));
    }

    @Test
    void verifyEmail_phoneOtpNotRequired_skipsOtpAndReturnsLoginStep() {
        UUID token = UUID.randomUUID();
        when(emailTokenRepository.findByToken(token)).thenReturn(Optional.of(buildToken(token)));
        when(userRepository.findOneById(USER_ID)).thenReturn(Optional.of(buildUser()));
        when(configService.getBoolean(CONFIG_KEY, true)).thenReturn(false);

        VerifyEmailResponse response = service.verifyEmail(token);

        assertThat(response.nextStep()).isEqualTo("login");
        assertThat(response.verificationToken()).isNull();
        verify(otpTokenRepository, never()).deleteByUserIdAndUsedFalse(any());
        verify(otpTokenRepository, never()).saveAndFlush(any());
        verify(publisher, never()).publishEvent(any(CoachOtpEmailEvent.class));
        verify(verificationTokenService, never()).issuePhoneVerificationToken(anyLong(), any());
    }
}
