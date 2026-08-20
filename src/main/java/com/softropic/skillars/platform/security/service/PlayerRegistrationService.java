package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.persistence.EntityStatus;
import com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer;
import com.softropic.skillars.infrastructure.security.RateLimitingService;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.infrastructure.validation.PhoneNumber;
import com.softropic.skillars.platform.security.api.dto.VerifyEmailResponse;
import com.softropic.skillars.platform.security.contract.Gender;
import com.softropic.skillars.platform.security.contract.LoginIdType;
import com.softropic.skillars.platform.security.contract.PlayerRegistrationRequest;
import com.softropic.skillars.platform.security.contract.SkillarsRole;
import com.softropic.skillars.platform.security.contract.SkillarsVerificationStatus;
import com.softropic.skillars.platform.security.contract.event.PlayerOtpEmailEvent;
import com.softropic.skillars.platform.security.contract.event.PlayerVerificationEmailEvent;
import com.softropic.skillars.platform.security.contract.exception.EmailTokenException;
import com.softropic.skillars.platform.security.contract.exception.OtpVerificationException;
import com.softropic.skillars.platform.security.contract.exception.PlayerRegistrationException;
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
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Registration for adult (18+) players who self-register rather than being created by a parent.
 * Mirrors {@link CoachRegistrationService}/{@code ParentRegistrationService} step-for-step
 * (register -&gt; email verify -&gt; phone OTP verify -&gt; BASIC_VERIFIED), reusing the same
 * role-agnostic email_verification_tokens/phone_otp_tokens tables.
 *
 * <p>Unlike Coach/Parent registration, this collects a real date of birth (Coach/Parent use a
 * placeholder, since DOB is irrelevant to those roles) in order to enforce the 18+ gate up front.
 * Minors are rejected outright, not silently downgraded — they're expected to have a parent
 * register on their behalf via the existing shadow-account flow instead.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class PlayerRegistrationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final AuthorityRepository authorityRepository;
    private final EmailVerificationTokenRepository emailTokenRepository;
    private final PhoneOtpTokenRepository otpTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher publisher;
    private final ContactDetailSanitizer sanitizer;
    private final RateLimitingService rateLimitingService;
    private final AgePolicyService agePolicyService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @RateLimited(key = "player_register", capacity = 3, duration = 60)
    public void registerPlayer(PlayerRegistrationRequest req) {
        if (agePolicyService.isMinor(req.dateOfBirth())) {
            throw new PlayerRegistrationException("security.playerMustBeAdult",
                "Players under 18 cannot register directly — ask a parent to register and add you as a player");
        }
        if (userRepository.findOneByEmail(req.email()).isPresent()) {
            throw new PlayerRegistrationException("security.emailInUse", "Email already registered");
        }

        String sanitizedFirst = sanitizer.sanitize(req.firstName()).sanitized();
        String sanitizedLast = sanitizer.sanitize(req.lastName()).sanitized();

        Authority playerAuthority = authorityRepository.findOneByName(SecurityConstants.ROLE_PLAYER)
            .orElseThrow(() -> new IllegalStateException("ROLE_PLAYER authority not found"));

        User user = new User();
        user.setLogin(req.email());
        user.setLoginIdType(LoginIdType.EMAIL);
        user.setPassword(passwordEncoder.encode(req.password()));
        user.setFirstName(sanitizedFirst);
        user.setLastName(sanitizedLast);
        user.setEmail(req.email());
        user.setPhone(new PhoneNumber(req.phone(), "XX"));
        user.setGender(Gender.OTHER);
        user.setDateOfBirth(req.dateOfBirth());
        user.setLangKey(req.langKey() != null && !req.langKey().isBlank() ? req.langKey() : "en");
        user.setActivated(false);
        user.setStatus(EntityStatus.INACTIVE);
        user.setSkillarsRole(SkillarsRole.PLAYER);
        user.setVerificationStatus(SkillarsVerificationStatus.UNVERIFIED);
        user.setAuthorities(Set.of(playerAuthority));

        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            throw new PlayerRegistrationException("security.emailInUse", "Email already registered");
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
        if (!rateLimitingService.tryConsume(String.valueOf(userId), "player_otp_verify", 5, 10, TimeUnit.MINUTES)) {
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

    @RateLimited(key = "player_resend_verification", capacity = 3, duration = 30)
    public void resendVerificationEmail(String email) {
        userRepository.findOneByEmail(email).ifPresent(user -> {
            if (user.getVerificationStatus() == null ||
                user.getVerificationStatus() == SkillarsVerificationStatus.UNVERIFIED) {
                emailTokenRepository.deleteByUserIdAndUsedFalse(user.getId());
                log.atInfo()
                   .addKeyValue("First name", user.getFirstName())
                   .addKeyValue("Last Name", user.getLastName())
                   .addKeyValue("Current verification status", user.getVerificationStatus())
                   .setMessage("About to resend verification email").log();
                sendVerificationEmail(user);
                return;
            }
            log.atInfo()
               .addKeyValue("First name", user.getFirstName())
               .addKeyValue("Last Name", user.getLastName())
               .addKeyValue("Current verification status", user.getVerificationStatus())
               .setMessage("User's verification status needs to be null/blank or unverified for a resend verification email to be sent").log();
        });
    }

    private void sendVerificationEmail(User user) {
        EmailVerificationToken token = new EmailVerificationToken();
        token.setUserId(user.getId());
        token.setToken(UUID.randomUUID());
        token.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        token.setUsed(false);
        emailTokenRepository.save(token);

        String verifyUrl = frontendUrl + "/#/player/verify-email?token=" + token.getToken() +
            "&email=" + URLEncoder.encode(user.getEmail(), StandardCharsets.UTF_8);
        publisher.publishEvent(new PlayerVerificationEmailEvent(
            user.getEmail(), verifyUrl, user.getLangKey(), user.getFirstName()));
    }

    private void sendOtpEmail(User user, String otp) {
        publisher.publishEvent(new PlayerOtpEmailEvent(
            user.getEmail(), otp, user.getLangKey(), user.getFirstName()));
    }

    private String generateOtp() {
        int code = 100000 + SECURE_RANDOM.nextInt(900000);
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
