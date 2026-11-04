package com.softropic.skillars.platform.security.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer;
import com.softropic.skillars.infrastructure.security.RateLimitingService;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.api.dto.VerifyEmailResponse;
import com.softropic.skillars.platform.security.contract.SkillarsVerificationStatus;
import com.softropic.skillars.platform.security.contract.event.CoachOtpEmailEvent;
import com.softropic.skillars.platform.security.contract.exception.OtpVerificationException;
import com.softropic.skillars.platform.security.contract.CoachRegistrationRequest;
import com.softropic.skillars.platform.security.contract.event.CoachVerificationEmailEvent;
import com.softropic.skillars.platform.security.contract.event.RegistrationDuplicateAttemptEvent;
import com.softropic.skillars.platform.security.contract.event.RegistrationPhoneInUseEvent;
import com.softropic.skillars.platform.security.contract.exception.CoachRegistrationException;
import com.softropic.skillars.platform.security.repo.Authority;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
            verificationTokenService, OtpHasherTest.hasher(), configService);
    }

    // ── Neutral duplicate registration (no email/phone enumeration) ────────────────────────────

    private CoachRegistrationRequest buildRequest() {
        return new CoachRegistrationRequest(
            "Jane", "Doe", "jane.doe@test.com", "password123", "+491701234567", "en");
    }

    private static User existingAccount(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setFirstName("Existing");
        user.setLastName("Owner");
        user.setLangKey("de");
        return user;
    }

    private void assertNeutralNoCreate(User notified) {
        verify(passwordEncoder).encode("password123");
        verify(userRepository, never()).save(any(User.class));
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verifyNoInteractions(emailTokenRepository, authorityRepository);
        verify(publisher, never()).publishEvent(any(CoachVerificationEmailEvent.class));
        verify(publisher).publishEvent(new RegistrationDuplicateAttemptEvent(
            notified.getId(), notified.getEmail(), "Existing", "Owner", "de"));
    }

    private void allowNotice() {
        when(rateLimitingService.tryConsume(anyString(), eq("registration_duplicate_notice"), eq(2L), eq(60L),
            eq(TimeUnit.MINUTES))).thenReturn(true);
    }

    private void registrantNoticeAllowed(boolean allowed) {
        when(rateLimitingService.tryConsume("jane.doe@test.com", "registration_phone_in_use_notice", 2L, 60L,
            TimeUnit.MINUTES)).thenReturn(allowed);
    }

    private User phoneHolderOfTheRequestPhone() {
        User phoneHolder = existingAccount(8L, "holder@test.com");
        when(userRepository.findOneByEmail("jane.doe@test.com")).thenReturn(Optional.empty());
        when(userRepository.findOneByPhoneNumber("+491701234567")).thenReturn(Optional.of(phoneHolder));
        return phoneHolder;
    }

    @Test
    void registerCoach_noticeLimitForThatAccountSpent_returnsNormallyWithoutANotice() {
        // A pool of IPs must not flood one inbox: past the per-account limit the notice is skipped,
        // and the response is still the neutral one.
        User existing = existingAccount(7L, "taken@test.com");
        when(userRepository.findOneByEmail(any())).thenReturn(Optional.of(existing));

        assertThatCode(() -> service.registerCoach(buildRequest())).doesNotThrowAnyException();

        verify(rateLimitingService).tryConsume("7", "registration_duplicate_notice", 2L, 60L, TimeUnit.MINUTES);
        verify(publisher, never()).publishEvent(any(RegistrationDuplicateAttemptEvent.class));
        verify(userRepository, never()).save(any(User.class));
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void registerCoach_emailIsAnExistingLogin_isTreatedAsTaken() {
        // Legacy accounts keep login as typed and email lowercased; the new row's login would hit
        // user_login_key, an error only an existing account produces.
        allowNotice();
        User existing = existingAccount(11L, "legacy@test.com");
        when(userRepository.findOneByEmail(any())).thenReturn(Optional.empty());
        when(userRepository.findOneByLogin(any())).thenReturn(Optional.of(existing));

        assertThatCode(() -> service.registerCoach(buildRequest())).doesNotThrowAnyException();

        verify(userRepository, never()).save(any(User.class));
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(publisher).publishEvent(any(RegistrationDuplicateAttemptEvent.class));
    }

    @Test
    void registerCoach_emailAlreadyRegistered_returnsNormallyAndNotifiesTheExistingAccount() {
        allowNotice();
        User existing = existingAccount(7L, "jane.doe@test.com");
        when(userRepository.findOneByEmail("jane.doe@test.com")).thenReturn(Optional.of(existing));

        assertThatCode(() -> service.registerCoach(buildRequest())).doesNotThrowAnyException();

        assertNeutralNoCreate(existing);
        verify(userRepository, never()).findOneByPhoneNumber(any());
        // An email match tells only the existing account: the typed address IS that account.
        verify(publisher, never()).publishEvent(any(RegistrationPhoneInUseEvent.class));
        verify(rateLimitingService, never()).tryConsume(anyString(), eq("registration_phone_in_use_notice"),
            anyLong(), anyLong(), any());
    }

    @Test
    void registerCoach_phoneAlreadyRegistered_returnsNormallyAndNotifiesThePhoneHolderAndTheTypedAddress() {
        // Phones are not verified at registration: the number may sit on someone else's account, so the
        // registrant is told at the (new) address they typed, next to the phone holder's notice.
        allowNotice();
        registrantNoticeAllowed(true);
        User phoneHolder = phoneHolderOfTheRequestPhone();

        assertThatCode(() -> service.registerCoach(buildRequest())).doesNotThrowAnyException();

        assertNeutralNoCreate(phoneHolder);
        verify(publisher).publishEvent(new RegistrationPhoneInUseEvent("jane.doe@test.com", "en", 8L));
    }

    @Test
    void registerCoach_phoneMatch_registrantLimitSpent_stillNeutralAndTheHolderIsStillTold() {
        allowNotice();
        registrantNoticeAllowed(false);
        User phoneHolder = phoneHolderOfTheRequestPhone();

        assertThatCode(() -> service.registerCoach(buildRequest())).doesNotThrowAnyException();

        assertNeutralNoCreate(phoneHolder);
        verify(publisher, never()).publishEvent(any(RegistrationPhoneInUseEvent.class));
    }

    @Test
    void registerCoach_phoneMatch_holderLimitSpent_theRegistrantIsStillTold() {
        // The two limits are independent: a spent per-account bucket must not silence the registrant.
        when(rateLimitingService.tryConsume("8", "registration_duplicate_notice", 2L, 60L, TimeUnit.MINUTES))
            .thenReturn(false);
        registrantNoticeAllowed(true);
        phoneHolderOfTheRequestPhone();

        assertThatCode(() -> service.registerCoach(buildRequest())).doesNotThrowAnyException();

        verify(publisher, never()).publishEvent(any(RegistrationDuplicateAttemptEvent.class));
        verify(publisher).publishEvent(new RegistrationPhoneInUseEvent("jane.doe@test.com", "en", 8L));
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void registerCoach_newEmailAndPhone_createsTheUserAndSendsOnlyTheVerificationEmail() {
        stubCreatePath();

        service.registerCoach(buildRequest());

        verify(userRepository).findOneByPhoneNumber("+491701234567");
        verify(userRepository).save(any(User.class));
        verify(emailTokenRepository).save(any(EmailVerificationToken.class));
        verify(publisher).publishEvent(any(CoachVerificationEmailEvent.class));
        verify(publisher, never()).publishEvent(any(RegistrationDuplicateAttemptEvent.class));
    }

    // The pre-checks found nothing but a concurrent registration won the race to the insert (and
    // the insert happened to flush inside the try): the narrow race keeps the explicit error.
    @Test
    void registerCoach_uniqueViolationRace_throwsEmailInUse() {
        stubCreatePath();
        when(userRepository.save(any(User.class)))
            .thenThrow(new DataIntegrityViolationException("could not execute statement"));

        assertThatThrownBy(() -> service.registerCoach(buildRequest()))
            .isInstanceOfSatisfying(CoachRegistrationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("security.emailInUse"));

        verify(emailTokenRepository, never()).save(any());
    }

    private void stubCreatePath() {
        when(sanitizer.sanitize(any()))
            .thenAnswer(inv -> new ContactDetailSanitizer.SanitizerResult(inv.getArgument(0), false));
        when(authorityRepository.findOneByName(any())).thenReturn(Optional.of(new Authority()));
        when(passwordEncoder.encode(any())).thenReturn("hashed");
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
        when(rateLimitingService.tryConsume(USER_ID + ":" + OTP_ID, "coach_otp_verify", 5, 10, TimeUnit.MINUTES))
            .thenReturn(true);
        when(userRepository.findOneById(USER_ID)).thenReturn(Optional.of(emailVerifiedUser()));

        assertThatThrownBy(() -> service.verifyPhone(USER_ID, "654321"))
            .isInstanceOfSatisfying(OtpVerificationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("security.otpMismatch"));
        verify(rateLimitingService).tryConsume(USER_ID + ":" + OTP_ID, "coach_otp_verify", 5, 10, TimeUnit.MINUTES);
        verify(otpTokenRepository, never()).save(any());
        verify(otpTokenRepository).recordFailedGuess(OTP_ID, PhoneOtpToken.MAX_FAILED_ATTEMPTS);
    }

    @Test
    void verifyPhone_exhaustedBucket_rejectsWithOtpMismatchBeforeTheHashCompare() {
        when(otpTokenRepository.findFirstByUserIdAndUsedFalseOrderByExpiresAtDesc(USER_ID))
            .thenReturn(Optional.of(otpToken("123456", Instant.now().plus(5, ChronoUnit.MINUTES))));
        when(rateLimitingService.tryConsume(USER_ID + ":" + OTP_ID, "coach_otp_verify", 5, 10, TimeUnit.MINUTES))
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
        when(rateLimitingService.tryConsume(USER_ID + ":" + OTP_ID, "coach_otp_verify", 5, 10, TimeUnit.MINUTES))
            .thenReturn(true);
        when(userRepository.findOneById(USER_ID)).thenReturn(Optional.of(user));

        service.verifyPhone(USER_ID, "123456");

        assertThat(user.getVerificationStatus()).isEqualTo(SkillarsVerificationStatus.BASIC_VERIFIED);
        assertThat(token.isUsed()).isTrue();
    }
}
