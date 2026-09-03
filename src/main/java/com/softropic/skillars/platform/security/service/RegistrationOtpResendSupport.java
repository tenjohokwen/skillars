package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.security.RateLimitingService;
import com.softropic.skillars.platform.security.contract.SkillarsVerificationStatus;
import com.softropic.skillars.platform.security.contract.exception.OtpVerificationException;
import com.softropic.skillars.platform.security.repo.PhoneOtpToken;
import com.softropic.skillars.platform.security.repo.PhoneOtpTokenRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * skillars-deferred-91 AC16: the {@code resendPhoneOtp} bodies in {@code ParentRegistrationService},
 * {@code CoachRegistrationService} and {@code PlayerRegistrationService} were byte-identical bar the
 * per-user rate-limit bucket key and the role's OTP-email event type (skillars-deferred-89 AC7
 * mirrored parent into coach + player). This is the one shared collaborator: each service keeps its
 * {@code @RateLimited(key = "{role}_resend_otp", ...)} annotation (the per-IP bucket) on a one-line
 * delegate and passes in the per-user bucket key + an event factory.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class RegistrationOtpResendSupport {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PhoneOtpTokenRepository otpTokenRepository;
    private final RateLimitingService rateLimitingService;
    private final ApplicationEventPublisher publisher;

    /**
     * Re-issues a phone OTP for {@code userId} and re-sends it by email. Behaviour is exactly what
     * the three registration services used to inline:
     * <ul>
     *   <li>per-user rate cap via {@code perUserBucketKey} (the {@code @RateLimited} IP bucket is
     *       still applied by the caller's annotation) — vague {@code security.otpMismatch} on trip,
     *       no rate-state oracle;</li>
     *   <li>unknown / non-{@code EMAIL_VERIFIED} userId → {@code security.otpMismatch} (not an
     *       enumeration oracle: the endpoint already requires a valid id + status);</li>
     *   <li>locked User rejected before any state change → {@code security.accountLocked}
     *       (skillars-deferred-88 AC11);</li>
     *   <li>{@code saveAndFlush} forces the INSERT now so a {@code uq_pot_one_active_per_user}
     *       collision surfaces synchronously as a {@code DataIntegrityViolationException} → 409
     *       {@code security.otpResendInProgress} via {@code ApiAdvice} (skillars-deferred-88 AC10).</li>
     * </ul>
     *
     * @param userId           the target user
     * @param perUserBucketKey e.g. {@code "parent_resend_otp_user"}
     * @param otpEmailEvent    builds the role's OTP-email event from (toAddress, otp, langKey, firstName)
     */
    public void resendPhoneOtp(Long userId, String perUserBucketKey, OtpEmailEventFactory otpEmailEvent) {
        if (!rateLimitingService.tryConsume(String.valueOf(userId), perUserBucketKey, 3, 30, TimeUnit.MINUTES)) {
            throw new OtpVerificationException("security.otpMismatch");
        }
        User user = userRepository.findOneById(userId)
            .orElseThrow(() -> new OtpVerificationException("security.otpMismatch"));
        if (user.isLocked()) {
            throw new OtpVerificationException("security.accountLocked");
        }
        if (user.getVerificationStatus() != SkillarsVerificationStatus.EMAIL_VERIFIED) {
            throw new OtpVerificationException("security.otpMismatch");
        }
        otpTokenRepository.deleteByUserIdAndUsedFalse(user.getId());
        String otp = generateOtp();
        PhoneOtpToken otpToken = new PhoneOtpToken();
        otpToken.setUserId(user.getId());
        otpToken.setOtpHash(hashOtp(otp, user.getId()));
        otpToken.setExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES));
        otpToken.setUsed(false);
        otpTokenRepository.saveAndFlush(otpToken);
        publisher.publishEvent(otpEmailEvent.create(
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

    /** Factory for a role's OTP-email event (Parent/Coach/PlayerOtpEmailEvent share this shape). */
    @FunctionalInterface
    public interface OtpEmailEventFactory {
        Object create(String toAddress, String otp, String langKey, String firstName);
    }
}
