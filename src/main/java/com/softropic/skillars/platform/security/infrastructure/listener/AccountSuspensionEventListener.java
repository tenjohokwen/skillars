package com.softropic.skillars.platform.security.infrastructure.listener;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.security.contract.SkillarsVerificationStatus;
import com.softropic.skillars.platform.security.contract.event.AccountSuspensionRequestedEvent;
import com.softropic.skillars.platform.security.contract.event.UserSuspensionRequestedEvent;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

// Uses @EventListener (not @TransactionalEventListener) because the event is published outside
// an active TX from ModerationOrchestrationService. @Transactional on the handler gives the
// suspension DB write its own TX.
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountSuspensionEventListener {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final PessimisticLockRetryer lockRetryer;

    /**
     * Safety suspension (CSAM match on an uploaded video). Mirrors the session half of
     * {@code AdminUserService.suspendUser}: the account is marked SUSPENDED AND locked, every standing
     * JWT is invalidated ({@code securitySessionInvalidatedAt}) and every refresh token is revoked,
     * so the owner loses all sessions immediately and can neither refresh nor log in again
     * ({@code AuthService.ensureAccountIsLive} and {@code Principal.instanceFrom} both refuse a
     * locked or SUSPENDED account). Before this, only {@code verificationStatus} was set: every open
     * session kept working and refreshing, and login was only refused while
     * {@code security.registration.phone-otp-required} happened to be true.
     *
     * <p>Idempotent and re-runnable: the revocation is re-applied even when the account is already
     * SUSPENDED, so an account suspended before this fix (status set, sessions left alive) is
     * closed out by the next matching event.
     */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAccountSuspensionRequested(AccountSuspensionRequestedEvent event) {
        String ownerId = event.ownerId();
        log.error("Processing account suspension: ownerId={} triggeredByVideoId={}", ownerId, event.videoId());

        // Scalar id only — an entity loaded here would be returned, stale, by the locked query below.
        Long userId = resolveAccountId(ownerId);
        if (userId == null) {
            // Throw, not just log: ModerationOrchestrationService catches this and raises an urgent
            // "CSAM match — account suspension FAILED" admin alert, so a human acts on it.
            throw new IllegalStateException("CRITICAL: Cannot suspend account — user not found: ownerId="
                + ownerId + " videoId=" + event.videoId());
        }

        suspend(userId, ownerId, String.valueOf(event.videoId()));
    }

    /**
     * S-18: safety suspension for a trigger that knows the USER id (an abuse-media match on an uploaded image).
     * Same effect, same idempotence, as {@link #onAccountSuspensionRequested}. Throws when the user is gone so the
     * caller can raise its "suspension FAILED" alert.
     */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserSuspensionRequested(UserSuspensionRequestedEvent event) {
        log.error("Processing account suspension: userId={} reason={}", event.userId(), event.reason());
        suspend(event.userId(), String.valueOf(event.userId()), event.reason());
    }

    private void suspend(Long userId, String ownerId, String trigger) {
        // Row lock first, so the status check and the writes below act on committed, locked state
        // (same lock AdminUserService.suspendUser/reactivateUser take on this row).
        User user = lockRetryer.withBoundedRetry("AccountSuspensionEventListener.suspend",
            () -> userRepository.findByIdForUpdate(userId)).orElse(null);
        if (user == null) {
            throw new IllegalStateException("CRITICAL: Cannot suspend account — user vanished: userId="
                + userId + " trigger=" + trigger);
        }

        boolean alreadySuspended = user.getVerificationStatus() == SkillarsVerificationStatus.SUSPENDED
            && user.isLocked();
        user.setVerificationStatus(SkillarsVerificationStatus.SUSPENDED);
        user.lock();
        user.getPersistentTokens().clear();
        userRepository.saveAndFlush(user);

        // ClockProvider, not Instant.now(): JWT SESSION_ISSUED_AT claims are minted from the same clock.
        userRepository.invalidateSessionsForUserInCurrentTransaction(userId, Instant.now(ClockProvider.getClock()));
        // REQUIRES_NEW on refresh_tokens rows this transaction has not touched: no self-deadlock.
        refreshTokenRepository.markAllUsedByUserId(userId);

        // Durable audit link: suspension ← CSAM scan. A dedicated account_suspension_audits
        // table is Story 10 scope; at minimum log at ERROR level so it appears in audit trail.
        log.error("Account suspended: ownerId={} userId={} trigger={} verificationStatus=SUSPENDED "
                  + "locked=true sessionsRevoked=true alreadySuspended={}",
                  ownerId, userId, trigger, alreadySuspended);
    }

    /**
     * Maps the event's {@code ownerId} — a {@code Video.ownerId} — to the account to suspend:
     * <ul>
     *   <li>a UUID is a CoachProfile id (coach uploads and drill videos) → {@code coach_profiles.user_id};</li>
     *   <li>a number is a PlayerProfile id (player HOMEWORK uploads) → {@code PlayerProfile.userId}, or,
     *       for a parent-managed minor profile with no login of its own, {@code PlayerProfile.parentId}.
     *       Today only a self-registered player can upload (VideoResource resolves the uploader's OWN
     *       profile via userId), so the parent branch is defensive: if a minor's profile ever owns a
     *       CSAM-matched video, the parent account is the only one that controls that profile, so it
     *       is the one that must lose access;</li>
     *   <li>anything else falls back to login / email (no current publisher passes one).</li>
     * </ul>
     */
    private Long resolveAccountId(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return null;
        }
        UUID coachProfileId = parseUuid(ownerId);
        if (coachProfileId != null) {
            return userRepository.findUserIdByCoachProfileId(coachProfileId).orElse(null);
        }
        if (ownerId.chars().allMatch(Character::isDigit)) {
            return playerProfileRepository.findById(Long.parseLong(ownerId))
                .map(p -> p.getUserId() != null ? p.getUserId() : p.getParentId())
                .orElse(null);
        }
        return userRepository.findIdByLogin(ownerId)
            .or(() -> userRepository.findIdByEmail(ownerId))
            .orElse(null);
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
