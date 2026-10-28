package com.softropic.skillars.platform.security.infrastructure.listener;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.security.contract.SkillarsVerificationStatus;
import com.softropic.skillars.platform.security.contract.event.AccountSuspensionRequestedEvent;
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

// Uses @EventListener (not @TransactionalEventListener) because the event is published outside
// an active TX from ModerationOrchestrationService. @Transactional on the handler gives the
// suspension DB write its own TX.
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountSuspensionEventListener {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
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
        Long userId = userRepository.findIdByLogin(ownerId)
            .or(() -> userRepository.findIdByEmail(ownerId))
            .orElse(null);
        if (userId == null) {
            log.error("CRITICAL: Cannot suspend account — user not found: ownerId={} videoId={}",
                      ownerId, event.videoId());
            return;
        }

        // Row lock first, so the status check and the writes below act on committed, locked state
        // (same lock AdminUserService.suspendUser/reactivateUser take on this row).
        User user = lockRetryer.withBoundedRetry("AccountSuspensionEventListener.suspend",
            () -> userRepository.findByIdForUpdate(userId)).orElse(null);
        if (user == null) {
            log.error("CRITICAL: Cannot suspend account — user vanished: userId={} videoId={}",
                      userId, event.videoId());
            return;
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
        log.error("Account suspended: ownerId={} userId={} triggeredByVideoId={} verificationStatus=SUSPENDED "
                  + "locked=true sessionsRevoked=true alreadySuspended={}",
                  ownerId, userId, event.videoId(), alreadySuspended);
    }
}
