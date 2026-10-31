package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.api.dto.AgeVerificationStartResponse;
import com.softropic.skillars.platform.security.api.dto.AgeVerificationStatusResponse;
import com.softropic.skillars.platform.security.contract.AgeTier;
import com.softropic.skillars.platform.security.contract.AgeVerificationErrorCode;
import com.softropic.skillars.platform.security.contract.AgeVerificationGateway;
import com.softropic.skillars.platform.security.contract.AgeVerificationStatus;
import com.softropic.skillars.platform.security.contract.SkillarsRole;
import com.softropic.skillars.platform.security.contract.event.AgeVerificationConcludedEvent;
import com.softropic.skillars.platform.security.contract.exception.AgeVerificationException;
import com.softropic.skillars.platform.security.contract.exception.UserNotFoundException;
import com.softropic.skillars.platform.security.repo.AgeVerification;
import com.softropic.skillars.platform.security.repo.AgeVerificationRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * S-27: proves a self-registered player is 18+ with a Stripe Identity document check, because the date of birth they
 * typed at signup is only a claim. Triggered lazily by the first chat or first booking (the gates call
 * {@link #requireVerifiedToBook}; the messaging gate uses {@link #isVerified}/{@link #isEnabled}), never at signup.
 *
 * <p>Only the outcome is stored (see {@link AgeVerification}). On a verified outcome the verified date of birth
 * replaces the declared one; a verified age under 18 locks the account and revokes its sessions.
 *
 * <p>When the feature is off or Stripe Identity is not configured ({@link #isEnabled()} false) no gate here refuses
 * anything — the messaging module's fallback (chat only after a completed session) is then the only restriction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgeVerificationService {

    /** platform_config switch. Code default false: nothing is billed and the fallback applies until an operator opts in. */
    public static final String ENABLED_KEY = "identity.ageVerification.enabled";

    /** Frontend route Stripe returns the player to (resolved against app.frontend-url by the gateway). */
    static final String RETURN_PATH = "/player/age-verification";

    /** A session that was started but not submitted is handed back instead of paying for another one. */
    private static final Duration RESUME_WINDOW = Duration.ofHours(1);
    private static final Duration ATTEMPT_WINDOW = Duration.ofHours(24);

    private final AgeVerificationRepository verificationRepository;
    private final UserRepository userRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AgePolicyService agePolicyService;
    private final ConfigService configService;
    private final AgeVerificationGateway gateway;
    private final ApplicationEventPublisher publisher;
    private final PessimisticLockRetryer lockRetryer;

    /** True when verification is switched on AND the provider is configured. */
    public boolean isEnabled() {
        return configService.getBoolean(ENABLED_KEY, false) && gateway.isConfigured();
    }

    public boolean isVerified(Long userId) {
        return verificationRepository.existsByUserIdAndStatus(userId, AgeVerificationStatus.VERIFIED);
    }

    /**
     * Booking gate for a self-registered player. A no-op when the feature is off (fallback mode allows booking and
     * paying) or the player is already verified.
     */
    public void requireVerifiedToBook(Long userId) {
        if (isEnabled() && !isVerified(userId)) {
            throw new AgeVerificationException(AgeVerificationErrorCode.AGE_VERIFICATION_REQUIRED,
                "Age verification is required before booking");
        }
    }

    @Transactional(readOnly = true)
    public AgeVerificationStatusResponse getStatus(Long userId) {
        User user = userRepository.findOneById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        boolean applicable = user.getSkillarsRole() == SkillarsRole.PLAYER;
        boolean enabled = isEnabled();
        String state = "NOT_STARTED";
        if (isVerified(userId)) {
            state = "VERIFIED";
        } else {
            Optional<AgeVerification> latest = verificationRepository.findFirstByUserIdOrderByCreatedAtDesc(userId);
            if (latest.isPresent()) {
                state = switch (latest.get().getStatus()) {
                    case CREATED, PROCESSING -> "PENDING";
                    case REQUIRES_INPUT -> "FAILED";
                    case UNDERAGE -> "BLOCKED";
                    case VERIFIED -> "VERIFIED";
                    case CANCELED -> "NOT_STARTED";
                };
            }
        }
        return new AgeVerificationStatusResponse(applicable, enabled, state, attemptsRemaining(userId));
    }

    /**
     * Starts (or resumes) a verification session. The user row is locked for the duration so two concurrent calls
     * cannot both pass the daily cap; the provider call runs under that lock, bounded by the Stripe client timeouts.
     */
    @Transactional
    public AgeVerificationStartResponse start(Long userId) {
        if (!isEnabled()) {
            throw new AgeVerificationException(AgeVerificationErrorCode.UNAVAILABLE,
                "Age verification is not available");
        }
        User user = lockAndLoad(userId);
        if (user.getSkillarsRole() != SkillarsRole.PLAYER || user.isLocked()) {
            throw new AgeVerificationException(AgeVerificationErrorCode.UNAVAILABLE,
                "Age verification does not apply to this account");
        }
        if (isVerified(userId)) {
            return new AgeVerificationStartResponse("VERIFIED", null);
        }

        Instant now = Instant.now(ClockProvider.getClock());
        Optional<AgeVerification> latest = verificationRepository.findFirstByUserIdOrderByCreatedAtDesc(userId);
        if (latest.isPresent() && latest.get().getStatus() == AgeVerificationStatus.PROCESSING) {
            return new AgeVerificationStartResponse("PENDING", null);
        }
        if (latest.isPresent() && latest.get().getStatus() == AgeVerificationStatus.CREATED
                && latest.get().getCreatedAt().isAfter(now.minus(RESUME_WINDOW))) {
            Optional<String> url = gateway.resumeUrl(latest.get().getStripeSessionId());
            if (url.isPresent()) {
                return new AgeVerificationStartResponse("PENDING", url.get());
            }
        }

        // Cost control: Stripe Identity bills per verification, so a fresh session is bounded per user per day.
        if (attemptsRemaining(userId) <= 0) {
            throw new AgeVerificationException(AgeVerificationErrorCode.ATTEMPTS_EXCEEDED,
                "Too many verification attempts today");
        }

        // The attempt number keys the provider call: a retry of THIS attempt (its row failed to commit) replays the
        // same session instead of creating, and paying for, a second one.
        String idempotencyKey = "age-verification-" + userId + "-" + verificationRepository.countByUserId(userId);
        AgeVerificationGateway.Session session = gateway.createSession(userId, idempotencyKey, RETURN_PATH);

        AgeVerification row = new AgeVerification();
        row.setUserId(userId);
        row.setStripeSessionId(session.sessionId());
        row.setStatus(AgeVerificationStatus.CREATED);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        verificationRepository.save(row);
        return new AgeVerificationStartResponse("PENDING", session.url());
    }

    /**
     * Applies a provider outcome. Idempotent and order-tolerant: a terminal row is never changed, so a replayed or
     * late event is a no-op, and an unknown session id (not ours) is ignored.
     *
     * @param incoming    the status the event maps to (PROCESSING, REQUIRES_INPUT, CANCELED, VERIFIED)
     * @param verifiedDob the date of birth read from the provider for a VERIFIED event, else null
     */
    @Transactional
    public void applyOutcome(String sessionId, AgeVerificationStatus incoming, LocalDate verifiedDob) {
        Optional<AgeVerification> found = verificationRepository.findByStripeSessionId(sessionId);
        if (found.isEmpty()) {
            log.warn("[AGE_VERIFICATION_UNKNOWN_SESSION sessionId={}]", sessionId);
            return;
        }
        AgeVerification row = found.get();
        if (row.getStatus().isTerminal()) {
            return;
        }
        Instant now = Instant.now(ClockProvider.getClock());
        switch (incoming) {
            case PROCESSING -> row.setStatus(AgeVerificationStatus.PROCESSING);
            case REQUIRES_INPUT -> {
                row.setStatus(AgeVerificationStatus.REQUIRES_INPUT);
                // Resubmitting on the same session would be billed again and bypass the daily cap: end it.
                publisher.publishEvent(new AgeVerificationConcludedEvent(
                    sessionId, AgeVerificationConcludedEvent.Action.CANCEL));
            }
            case CANCELED -> {
                // Our own cancel after REQUIRES_INPUT echoes back as canceled: keep the more informative status.
                if (row.getStatus() == AgeVerificationStatus.CREATED
                        || row.getStatus() == AgeVerificationStatus.PROCESSING) {
                    row.setStatus(AgeVerificationStatus.CANCELED);
                }
            }
            case VERIFIED -> concludeVerified(row, verifiedDob, now);
            default -> throw new IllegalArgumentException("Not a provider outcome: " + incoming);
        }
        row.setUpdatedAt(now);
        verificationRepository.save(row);
    }

    /**
     * GDPR erasure: removes the user's verification rows and asks the provider (after commit) to redact every session
     * it ever held for them. Redaction normally already happened when each session concluded; repeating it here also
     * covers a conclusion whose redaction failed and sessions that never concluded.
     */
    @Transactional
    public void eraseForUser(Long userId) {
        for (AgeVerification row : verificationRepository.findAllByUserId(userId)) {
            publisher.publishEvent(new AgeVerificationConcludedEvent(
                row.getStripeSessionId(), AgeVerificationConcludedEvent.Action.REDACT));
        }
        verificationRepository.deleteAllByUserId(userId);
    }

    private void concludeVerified(AgeVerification row, LocalDate verifiedDob, Instant now) {
        if (verifiedDob == null) {
            // Surfaces as a webhook 5xx so Stripe retries; a persistent cause is a restricted API key that cannot
            // read verified_outputs (see the provisioning checklist).
            throw new IllegalStateException("Verified session " + row.getStripeSessionId() + " carried no date of birth");
        }
        User user = lockAndLoad(row.getUserId());
        AgeTier tier = agePolicyService.getAgeTier(verifiedDob);
        row.setVerifiedTier(tier);
        row.setVerifiedAt(now);
        if (agePolicyService.isMinor(tier)) {
            row.setStatus(AgeVerificationStatus.UNDERAGE);
            blockAccount(user);
            log.warn("[AGE_VERIFICATION_UNDERAGE userId={} tier={}] self-registered player verified as a minor; "
                + "account locked and sessions revoked", user.getId(), tier);
        } else {
            row.setStatus(AgeVerificationStatus.VERIFIED);
            boolean corrected = !verifiedDob.equals(user.getDateOfBirth());
            if (corrected) {
                user.setDateOfBirth(verifiedDob);
                userRepository.save(user);
                playerProfileRepository.findByUserId(user.getId()).ifPresent(profile -> {
                    profile.setDateOfBirth(verifiedDob);
                    profile.setAgeTier(tier);
                    playerProfileRepository.save(profile);
                });
            }
            log.info("[AGE_VERIFICATION_VERIFIED userId={} dobCorrected={}]", user.getId(), corrected);
        }
        publisher.publishEvent(new AgeVerificationConcludedEvent(
            row.getStripeSessionId(), AgeVerificationConcludedEvent.Action.REDACT));
    }

    /**
     * Same effect as {@code AdminUserService.suspendUser} for a non-coach account (the user.locked flag, legacy
     * tokens, JWT sessions, refresh tokens), minus the admin audit row — there is no acting admin. The security
     * module cannot depend on the admin module, so the three revocation steps are repeated here.
     */
    private void blockAccount(User user) {
        user.lock();
        user.getPersistentTokens().clear();
        userRepository.saveAndFlush(user);
        userRepository.invalidateSessionsForUserInCurrentTransaction(
            user.getId(), Instant.now(ClockProvider.getClock()));
        refreshTokenRepository.markAllUsedByUserId(user.getId());
    }

    private int attemptsRemaining(Long userId) {
        int cap = configService.getBoundedInt(ConfigBounds.IDENTITY_AGE_VERIFICATION_MAX_ATTEMPTS_PER_DAY);
        long used = verificationRepository.countByUserIdAndCreatedAtAfter(
            userId, Instant.now(ClockProvider.getClock()).minus(ATTEMPT_WINDOW));
        return (int) Math.max(0, cap - used);
    }

    private User lockAndLoad(Long userId) {
        return lockRetryer.withBoundedRetry("AgeVerificationService", () ->
            userRepository.findByIdForUpdate(userId).orElseThrow(() -> new UserNotFoundException(userId)));
    }
}
