package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.persistence.EntityStatus;
import com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer;
import com.softropic.skillars.infrastructure.security.RateLimitingService;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.infrastructure.validation.PhoneNumber;
import com.softropic.skillars.platform.security.api.dto.VerifyEmailResponse;
import com.softropic.skillars.platform.security.contract.CoachRegistrationRequest;
import com.softropic.skillars.platform.security.contract.Gender;
import com.softropic.skillars.platform.security.contract.LoginIdType;
import com.softropic.skillars.platform.security.contract.SkillarsRole;
import com.softropic.skillars.platform.security.contract.SkillarsVerificationStatus;
import com.softropic.skillars.platform.security.contract.event.CoachOtpEmailEvent;
import com.softropic.skillars.platform.security.contract.event.CoachVerificationEmailEvent;
import com.softropic.skillars.platform.security.contract.exception.CoachRegistrationException;
import com.softropic.skillars.platform.security.contract.exception.EmailTokenException;
import com.softropic.skillars.platform.security.contract.exception.OtpVerificationException;
import com.softropic.skillars.platform.security.repo.Authority;
import com.softropic.skillars.platform.security.repo.AuthorityRepository;
import com.softropic.skillars.platform.security.repo.EmailVerificationToken;
import com.softropic.skillars.platform.security.repo.EmailVerificationTokenRepository;
import com.softropic.skillars.platform.security.repo.PhoneOtpToken;
import com.softropic.skillars.platform.security.repo.PhoneOtpTokenRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import com.softropic.skillars.infrastructure.security.RateLimited;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CoachRegistrationService {

    private final UserRepository userRepository;
    private final AuthorityRepository authorityRepository;
    private final EmailVerificationTokenRepository emailTokenRepository;
    private final PhoneOtpTokenRepository otpTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher publisher;
    private final ContactDetailSanitizer sanitizer;
    private final RateLimitingService rateLimitingService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @RateLimited(key = "coach_register", capacity = 3, duration = 60)
    public void registerCoach(CoachRegistrationRequest req) {
        if (userRepository.findOneByEmail(req.email()).isPresent()) {
            throw new CoachRegistrationException("security.emailInUse", "Email already registered");
        }

        String sanitizedFirst = sanitizer.sanitize(req.firstName()).sanitized();
        String sanitizedLast = sanitizer.sanitize(req.lastName()).sanitized();

        Authority coachAuthority = authorityRepository.findOneByName(SecurityConstants.ROLE_COACH)
            .orElseThrow(() -> new IllegalStateException("ROLE_COACH authority not found"));

        User user = new User();
        user.setLogin(req.email());
        user.setLoginIdType(LoginIdType.EMAIL);
        user.setPassword(passwordEncoder.encode(req.password()));
        user.setFirstName(sanitizedFirst);
        user.setLastName(sanitizedLast);
        user.setEmail(req.email());
        user.setPhone(new PhoneNumber(req.phone(), "XX"));
        user.setGender(Gender.OTHER);
        user.setDateOfBirth(LocalDate.of(1900, 1, 1));
        user.setLangKey(req.langKey() != null && !req.langKey().isBlank() ? req.langKey() : "en");
        user.setActivated(false);
        user.setStatus(EntityStatus.INACTIVE);
        user.setSkillarsRole(SkillarsRole.COACH);
        user.setVerificationStatus(SkillarsVerificationStatus.UNVERIFIED);
        user.setAuthorities(Set.of(coachAuthority));

        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            throw new CoachRegistrationException("security.emailInUse", "Email already registered");
        }

        sendVerificationEmail(user);
    }

    public VerifyEmailResponse verifyEmail(UUID token) {
        EmailVerificationToken evt = emailTokenRepository.findByToken(token)
            .orElseThrow(() -> new EmailTokenException("security.emailTokenInvalid", true));

        if (evt.isUsed()) {
            throw new EmailTokenException("security.emailTokenUsed", true);
        }
        if (Instant.now().isAfter(evt.getExpiresAt())) {
            throw new EmailTokenException("security.emailTokenExpired", true);
        }

        User user = userRepository.findOneById(evt.getUserId())
            .orElseThrow(() -> new EmailTokenException("security.emailTokenInvalid", false));

        if (user.getVerificationStatus() != SkillarsVerificationStatus.UNVERIFIED) {
            throw new EmailTokenException("security.emailTokenInvalid", false);
        }

        user.setVerificationStatus(SkillarsVerificationStatus.EMAIL_VERIFIED);
        user.setActivated(true);
        userRepository.save(user);

        evt.setUsed(true);
        try {
            emailTokenRepository.save(evt);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            throw new EmailTokenException("security.emailTokenUsed", true);
        }

        String otp = generateOtp();
        otpTokenRepository.deleteByUserIdAndUsedFalse(user.getId());

        PhoneOtpToken otpToken = new PhoneOtpToken();
        otpToken.setUserId(user.getId());
        otpToken.setOtpHash(hashOtp(otp, user.getId()));
        otpToken.setExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES));
        otpToken.setUsed(false);
        otpTokenRepository.save(otpToken);

        sendOtpEmail(user, otp);

        return new VerifyEmailResponse("verify-phone", user.getId());
    }

    public void verifyPhone(Long userId, String otp) {
        if (!rateLimitingService.tryConsume(String.valueOf(userId), "coach_otp_verify", 5, 10, TimeUnit.MINUTES)) {
            throw new OtpVerificationException("security.otpMismatch");
        }

        PhoneOtpToken otpToken = otpTokenRepository
            .findFirstByUserIdAndUsedFalseOrderByExpiresAtDesc(userId)
            .orElseThrow(() -> new OtpVerificationException("security.otpMismatch"));

        if (Instant.now().isAfter(otpToken.getExpiresAt())) {
            throw new OtpVerificationException("security.otpMismatch");
        }

        User user = userRepository.findOneById(userId)
            .orElseThrow(() -> new OtpVerificationException("security.otpMismatch"));

        if (user.getVerificationStatus() != SkillarsVerificationStatus.EMAIL_VERIFIED) {
            throw new OtpVerificationException("security.otpMismatch");
        }

        String expectedHash = hashOtp(otp, userId);
        if (!expectedHash.equals(otpToken.getOtpHash())) {
            throw new OtpVerificationException("security.otpMismatch");
        }

        user.setVerificationStatus(SkillarsVerificationStatus.BASIC_VERIFIED);
        userRepository.save(user);

        otpToken.setUsed(true);
        otpTokenRepository.save(otpToken);
    }

    @RateLimited(key = "coach_resend_verification", capacity = 3, duration = 30)
    public void resendVerificationEmail(String email) {
        userRepository.findOneByEmail(email).ifPresent(user -> {
            if (user.getVerificationStatus() == null ||
                user.getVerificationStatus() == SkillarsVerificationStatus.UNVERIFIED) {
                emailTokenRepository.deleteByUserIdAndUsedFalse(user.getId());
                sendVerificationEmail(user);
            }
        });
    }

    private void sendVerificationEmail(User user) {
        EmailVerificationToken token = new EmailVerificationToken();
        token.setUserId(user.getId());
        token.setToken(UUID.randomUUID());
        token.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        token.setUsed(false);
        emailTokenRepository.save(token);

        String verifyUrl = frontendUrl + "/coach/verify-email?token=" + token.getToken() +
            "&email=" + URLEncoder.encode(user.getEmail(), StandardCharsets.UTF_8);
        publisher.publishEvent(new CoachVerificationEmailEvent(
            user.getEmail(), verifyUrl, user.getLangKey(), user.getFirstName()));
    }

    private void sendOtpEmail(User user, String otp) {
        publisher.publishEvent(new CoachOtpEmailEvent(
            user.getEmail(), otp, user.getLangKey(), user.getFirstName()));
    }

    private String generateOtp() {
        SecureRandom random = new SecureRandom();
        int code = 100000 + random.nextInt(900000);
        return String.valueOf(code);
    }

    private String hashOtp(String otp, Long userId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = otp + userId.toString();
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
