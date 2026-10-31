package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer;
import com.softropic.skillars.infrastructure.security.RateLimitingService;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.api.dto.VerifyEmailResponse;
import com.softropic.skillars.platform.security.contract.SkillarsVerificationStatus;
import com.softropic.skillars.platform.security.contract.event.PlayerOtpEmailEvent;
import com.softropic.skillars.platform.security.contract.exception.OtpVerificationException;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-138 AC2 — no existing unit test covered {@code PlayerRegistrationService.verifyEmail}'s
 * {@code nextStep} branching before this story; the pre-existing IT coverage
 * ({@code PlayerRegistrationResourceIT}) only ever exercised the real seeded
 * {@code security.registration.phone-otp-required = false} value via the (buggy, hardcoded) old
 * behavior. New coverage, not an extension.
 */
@ExtendWith(MockitoExtension.class)
class PlayerRegistrationServiceTest {

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
    @Mock AgePolicyService agePolicyService;
    @Mock RegistrationVerificationTokenService verificationTokenService;
    @Mock ConfigService configService;

    private PlayerRegistrationService service;

    @BeforeEach
    void setUp() {
        service = new PlayerRegistrationService(userRepository, authorityRepository, emailTokenRepository,
            otpTokenRepository, passwordEncoder, publisher, sanitizer, rateLimitingService, otpResendSupport,
            agePolicyService, verificationTokenService, OtpHasherTest.hasher(), configService);
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
        when(verificationTokenService.issuePhoneVerificationToken(USER_ID, "PLAYER")).thenReturn("handle-123");

        VerifyEmailResponse response = service.verifyEmail(token);

        assertThat(response.nextStep()).isEqualTo("verify-phone");
        assertThat(response.verificationToken()).isEqualTo("handle-123");
        verify(otpTokenRepository).deleteByUserIdAndUsedFalse(USER_ID);
        verify(otpTokenRepository).saveAndFlush(any(PhoneOtpToken.class));
        verify(publisher).publishEvent(any(PlayerOtpEmailEvent.class));
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
        verify(publisher, never()).publishEvent(any(PlayerOtpEmailEvent.class));
        verify(verificationTokenService, never()).issuePhoneVerificationToken(anyLong(), any());
    }

    // skillars-deferred-162 AC3.5: the verify-phone limiter is reached only for a live OTP, and is keyed on it.

    private static final Long OTP_ID = 7001L;

    private PhoneOtpToken otpToken(String otp, Instant expiresAt) {
        PhoneOtpToken token = new PhoneOtpToken();
        token.setId(OTP_ID);
        token.setUserId(USER_ID);
        token.setOtpHash(OtpHasherTest.hasher().hash(USER_ID, otp));
        token.setExpiresAt(expiresAt);
        token.setUsed(false);
        return token;
    }

    private User emailVerifiedUser() {
        User user = buildUser();
        user.setVerificationStatus(SkillarsVerificationStatus.EMAIL_VERIFIED);
        return user;
    }

    @Test
    void verifyPhone_missingOtp_rejectsWithoutConsumingTheBudget() {
        when(otpTokenRepository.findFirstByUserIdAndUsedFalseOrderByExpiresAtDesc(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyPhone(USER_ID, "123456"))
            .isInstanceOfSatisfying(OtpVerificationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("security.otpMismatch"));
        verifyNoInteractions(rateLimitingService);
    }

    @Test
    void verifyPhone_expiredOtp_rejectsWithoutConsumingTheBudget() {
        when(otpTokenRepository.findFirstByUserIdAndUsedFalseOrderByExpiresAtDesc(USER_ID))
            .thenReturn(Optional.of(otpToken("123456", Instant.now().minus(1, ChronoUnit.MINUTES))));

        assertThatThrownBy(() -> service.verifyPhone(USER_ID, "123456"))
            .isInstanceOfSatisfying(OtpVerificationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("security.otpMismatch"));
        verifyNoInteractions(rateLimitingService);
    }

    @Test
    void verifyPhone_wrongCodeAgainstLiveOtp_consumesTheBucketForThatOtp() {
        when(otpTokenRepository.findFirstByUserIdAndUsedFalseOrderByExpiresAtDesc(USER_ID))
            .thenReturn(Optional.of(otpToken("123456", Instant.now().plus(5, ChronoUnit.MINUTES))));
        when(rateLimitingService.tryConsume(USER_ID + ":" + OTP_ID, "player_otp_verify", 5, 10, TimeUnit.MINUTES))
            .thenReturn(true);
        when(userRepository.findOneById(USER_ID)).thenReturn(Optional.of(emailVerifiedUser()));

        assertThatThrownBy(() -> service.verifyPhone(USER_ID, "654321"))
            .isInstanceOfSatisfying(OtpVerificationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("security.otpMismatch"));
        verify(rateLimitingService).tryConsume(USER_ID + ":" + OTP_ID, "player_otp_verify", 5, 10, TimeUnit.MINUTES);
        verify(otpTokenRepository, never()).save(any());
        verify(otpTokenRepository).recordFailedGuess(OTP_ID, PhoneOtpToken.MAX_FAILED_ATTEMPTS);
    }

    @Test
    void verifyPhone_exhaustedBucket_rejectsWithOtpMismatchBeforeTheHashCompare() {
        when(otpTokenRepository.findFirstByUserIdAndUsedFalseOrderByExpiresAtDesc(USER_ID))
            .thenReturn(Optional.of(otpToken("123456", Instant.now().plus(5, ChronoUnit.MINUTES))));
        when(rateLimitingService.tryConsume(USER_ID + ":" + OTP_ID, "player_otp_verify", 5, 10, TimeUnit.MINUTES))
            .thenReturn(false);

        assertThatThrownBy(() -> service.verifyPhone(USER_ID, "123456"))
            .isInstanceOfSatisfying(OtpVerificationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("security.otpMismatch"));
        verifyNoInteractions(userRepository);
    }

    @Test
    void verifyPhone_rightCodeAgainstLiveOtp_marksUserBasicVerifiedAndOtpUsed() {
        PhoneOtpToken token = otpToken("123456", Instant.now().plus(5, ChronoUnit.MINUTES));
        User user = emailVerifiedUser();
        when(otpTokenRepository.findFirstByUserIdAndUsedFalseOrderByExpiresAtDesc(USER_ID)).thenReturn(Optional.of(token));
        when(rateLimitingService.tryConsume(USER_ID + ":" + OTP_ID, "player_otp_verify", 5, 10, TimeUnit.MINUTES))
            .thenReturn(true);
        when(userRepository.findOneById(USER_ID)).thenReturn(Optional.of(user));

        service.verifyPhone(USER_ID, "123456");

        assertThat(user.getVerificationStatus()).isEqualTo(SkillarsVerificationStatus.BASIC_VERIFIED);
        assertThat(token.isUsed()).isTrue();
    }
}
