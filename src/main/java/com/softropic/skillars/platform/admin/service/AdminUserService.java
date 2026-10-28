package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.admin.contract.AdminActionType;
import com.softropic.skillars.platform.admin.contract.AdminUserActionDto;
import com.softropic.skillars.platform.admin.contract.AdminUserDetailDto;
import com.softropic.skillars.platform.admin.contract.AdminUserSummaryDto;
import com.softropic.skillars.platform.admin.repo.AdminActionLog;
import com.softropic.skillars.platform.admin.repo.AdminActionLogRepository;
import com.softropic.skillars.platform.admin.repo.GdprRequestRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.SkillarsRole;
import com.softropic.skillars.platform.security.contract.SkillarsVerificationStatus;
import com.softropic.skillars.platform.security.repo.Authority;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserAdminSummaryView;
import com.softropic.skillars.platform.security.repo.UserRepository;
import com.softropic.skillars.platform.security.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Admin console account enforcement (feat/admin-moderation-and-enforcement D7 / A10): search users,
 * suspend / reactivate any non-admin account, and clear a user's failed-login rate limit.
 *
 * <p><strong>What "suspended" is.</strong> No new state: it is the existing {@code main."user".locked}
 * flag {@code UserAdminService.lockUserAccount} and GDPR erasure already use, which every
 * authentication path already enforces — {@code AuthService.login}/{@code refresh} via
 * {@code ensureAccountIsLive}, and {@code JWTAuthorizationFilter}'s DB re-auth cycle via
 * {@code daoAuthProvider.authorize} (Spring's {@code accountNonLocked}). Coach marketplace suspension
 * ({@code AdminCoachEnforcementService}) is a separate, coach-profile-level status and is untouched.
 *
 * <p><strong>Killing live sessions on suspend</strong> reuses the existing revocation mechanisms, in
 * this order, all after the row lock and the {@code locked} write:
 * <ol>
 *   <li>{@code securitySessionInvalidatedAt} is stamped (in THIS transaction — see
 *       {@link UserRepository#invalidateSessionsForUserInCurrentTransaction}), so every JWT issued
 *       before the suspension is denied at its next DB re-auth even if a stale managed save of the
 *       user row were ever to write {@code locked} back to false (that column is not updatable through
 *       the entity, so no stale save can undo the stamp).</li>
 *   <li>{@code RefreshTokenRepository.markAllUsedByUserId} — every refresh token is revoked. It is
 *       {@code REQUIRES_NEW}, but on {@code refresh_tokens}, not this row: an UPDATE of non-key columns
 *       there needs no lock on the parent user row, so it cannot self-deadlock against the row lock
 *       this transaction holds. If this transaction later rolls back, the revocation stays committed
 *       — the safe direction (the user just has to log in again).</li>
 * </ol>
 * The residual window is the pre-existing one for any locked account: a still-valid JWT keeps working
 * on the filter's no-DB fast path until its next DB re-auth ({@code DB_REFRESH_TOKEN_INTERVAL}, 5 min).
 *
 * <p><strong>Concurrency.</strong> Suspend and reactivate both load the user under its row lock
 * ({@code UserRepository.findByIdForUpdate}, NO_WAIT, retried by {@link PessimisticLockRetryer}), and make every decision (privileged target, already suspended, erased) on that locked
 * state, so two concurrent admin actions on one user serialise and the loser sees the winner's result
 * (idempotent no-op, no duplicate audit row).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUserService {

    private static final int PAGE_SIZE = 20;
    private static final int MAX_QUERY_LENGTH = 100;

    private static final Set<String> PRIVILEGED_AUTHORITIES =
        Set.of(SecurityConstants.ROLE_ADMIN, SecurityConstants.ROLE_LTD_ADMIN);

    private static final List<AdminActionType> USER_ACTION_TYPES = List.of(
        AdminActionType.USER_SUSPEND, AdminActionType.USER_REACTIVATE, AdminActionType.USER_LOGIN_UNLOCK);

    /** Any GDPR erasure request in these states means the account is (being) anonymised. */
    private static final List<String> ERASURE_STATUSES = List.of("PENDING", "PROCESSING", "COMPLETED", "FAILED");

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AdminActionLogRepository adminActionLogRepository;
    private final GdprRequestRepository gdprRequestRepository;
    private final AuthService authService;
    private final PessimisticLockRetryer lockRetryer;
    private final CoachProfileRepository coachProfileRepository;
    private final AdminCoachEnforcementService coachEnforcementService;

    @Transactional(readOnly = true)
    public Page<AdminUserSummaryDto> searchUsers(String query, int page) {
        Page<UserAdminSummaryView> rows = userRepository.searchForAdmin(
            toLikePattern(query), PageRequest.of(Math.max(0, page), PAGE_SIZE));
        return rows.map(v -> new AdminUserSummaryDto(
            v.getId(), v.getEmail(), v.getFirstName(), v.getLastName(),
            v.getSkillarsRole() != null ? v.getSkillarsRole().name() : null,
            Boolean.TRUE.equals(v.getActivated()),
            Boolean.TRUE.equals(v.getLocked()),
            v.getVerificationStatus() != null ? v.getVerificationStatus().name() : null,
            v.getCreatedDate()));
    }

    @Transactional(readOnly = true)
    public AdminUserDetailDto getUserDetail(Long userId) {
        User user = userRepository.findOneById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found", "user"));
        List<AdminUserActionDto> history = adminActionLogRepository
            .findTop50ByReferenceIdAndActionTypeInOrderByCreatedAtDesc(String.valueOf(userId), USER_ACTION_TYPES)
            .stream()
            .map(a -> new AdminUserActionDto(a.getActionType().name(), a.getAdminId(), a.getReason(), a.getCreatedAt()))
            .toList();
        return new AdminUserDetailDto(
            user.getId(), user.getLogin(), user.getEmail(), user.getFirstName(), user.getLastName(),
            user.getSkillarsRole() != null ? user.getSkillarsRole().name() : null,
            user.isActivated(), user.isLocked(),
            user.getVerificationStatus() != null ? user.getVerificationStatus().name() : null,
            user.getCreatedDate(),
            isPrivileged(user),
            countRecentFailedAttempts(user),
            history);
    }

    /**
     * Suspends (locks) a non-admin account and revokes its sessions. Idempotent: suspending an
     * already-suspended account is a no-op that writes no second audit row.
     *
     * @param cancelUpcomingBookings for a COACH account: also cancel the coach's upcoming bookings with
     *                               a full refund (C2; the default), see
     *                               {@link AdminCoachEnforcementService#suspendCoach}
     * @return {@code true} if this call suspended the account, {@code false} if it already was
     */
    @Transactional
    public boolean suspendUser(Long userId, String reason, Long adminId, boolean cancelUpcomingBookings) {
        if (userId.equals(adminId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "admin.users.cannotSuspendSelf");
        }
        User user = lockAndLoad(userId, "AdminUserService.suspendUser");
        if (isPrivileged(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "admin.users.cannotSuspendAdmin");
        }
        if (user.isLocked()) {
            // Already locked (by an earlier suspension, or by another path such as the legacy
            // UserAdminService.lockUserAccount): no second USER_SUSPEND row, but still make sure a
            // coach's marketplace profile is not left bookable. suspendCoach is itself idempotent
            // under its own row lock and writes its own COACH_SUSPEND audit row when it acts.
            suspendCoachProfileIfAny(userId, reason, adminId, cancelUpcomingBookings);
            return false;
        }

        user.lock();
        // Legacy remember-me tokens, cleared exactly as GdprErasureService does on erasure.
        user.getPersistentTokens().clear();
        userRepository.saveAndFlush(user);

        // ClockProvider, not Instant.now(): JWT SESSION_ISSUED_AT claims are minted from the same clock.
        userRepository.invalidateSessionsForUserInCurrentTransaction(userId, Instant.now(ClockProvider.getClock()));
        refreshTokenRepository.markAllUsedByUserId(userId);

        suspendCoachProfileIfAny(userId, reason, adminId, cancelUpcomingBookings);

        audit(adminId, AdminActionType.USER_SUSPEND, userId, reason);
        log.info("User account suspended by admin: userId={} adminId={}", userId, adminId);
        return true;
    }

    /**
     * Lifts an admin suspension. Idempotent: reactivating an account that is not locked is a no-op.
     * Refused for an account with a GDPR erasure request: erasure locks the account as part of
     * anonymising it, and unlocking it would hand a scrubbed placeholder identity back its login.
     *
     * @return {@code true} if this call reactivated the account, {@code false} if it was not suspended
     */
    @Transactional
    public boolean reactivateUser(Long userId, String reason, Long adminId) {
        User user = lockAndLoad(userId, "AdminUserService.reactivateUser");
        // Mirrors suspendUser: this console never acts on ADMIN / LTD_ADMIN accounts (an LTD_ADMIN
        // must not be able to undo a lock placed on a more-privileged account).
        if (isPrivileged(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "admin.users.cannotModifyAdmin");
        }
        // A safety suspension (CSAM match, AccountSuspensionEventListener) is not lifted from this
        // console: it is a child-safety/legal hold, not an admin moderation lock. Checked before the
        // not-locked no-op so an account stuck SUSPENDED-but-unlocked is reported, not "reactivated".
        if (user.getVerificationStatus() == SkillarsVerificationStatus.SUSPENDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "admin.users.safetyHold");
        }
        if (!user.isLocked()) {
            return false;
        }
        // Self-scoped only (player_id IS NULL, V172): a parent's erasure of one of their CHILD profiles
        // does not erase the parent's own account and must not block reactivating it.
        if (gdprRequestRepository.existsByUserIdAndPlayerIdIsNullAndRequestTypeAndStatusIn(
                userId, "ERASURE", ERASURE_STATUSES)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "admin.users.erased");
        }

        user.unlock();
        userRepository.saveAndFlush(user);

        audit(adminId, AdminActionType.USER_REACTIVATE, userId, reason);
        log.info("User account reactivated by admin: userId={} adminId={}", userId, adminId);
        return true;
    }

    /**
     * A10: clears the failed-login rate limit for this account — the one DB-backed limiter both
     * {@code /api/auth/login} enforces (LoginRateLimiter) —
     * and records who did it. Does not touch {@code locked}: a suspended account stays suspended.
     */
    @Transactional
    public int unlockLogin(Long userId, Long adminId) {
        User user = userRepository.findOneById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found", "user"));
        if (isPrivileged(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "admin.users.cannotModifyAdmin");
        }
        String login = user.getLogin();
        int cleared = authService.clearLoginRateLimit(login);
        if (user.getEmail() != null && !user.getEmail().equalsIgnoreCase(login)) {
            cleared += authService.clearLoginRateLimit(user.getEmail());
        }
        audit(adminId, AdminActionType.USER_LOGIN_UNLOCK, userId, "Cleared " + cleared + " failed login attempt(s)");
        log.info("User login rate limit cleared by admin: userId={} adminId={} clearedAttempts={}",
            userId, adminId, cleared);
        return cleared;
    }

    /**
     * A suspended COACH account must not stay bookable: an ACTIVE marketplace profile would keep
     * taking booking requests for a coach who can no longer log in to accept or deliver them. Delegates
     * to the existing coach suspension (marketplace status SUSPENDED + REQUESTED bookings cancelled, and
     * by default the upcoming ones too, with full refunds — C2),
     * in this same transaction, so a failure rolls the account suspension back with it. Lock order is
     * user row then coach_profiles row; the coach lock is NO_WAIT + bounded retry, so contention
     * surfaces as a 409, never a hang. Reactivating the account deliberately does NOT reinstate the
     * profile: that stays an explicit decision on the coach enforcement page.
     *
     * <p>Only the profile ID is read here (a scalar query), never the entity: loading an unlocked
     * CoachProfile into the persistence context first would make suspendCoach's later
     * findByIdForUpdate return that same pre-lock snapshot, so its SUSPENDED check and its
     * full-row save() would run on stale state and could revert a concurrently committed profile
     * edit. suspendCoach does its own SUSPENDED no-op check under the lock.
     */
    private void suspendCoachProfileIfAny(Long userId, String reason, Long adminId, boolean cancelUpcomingBookings) {
        coachProfileRepository.findIdByUserId(userId)
            .ifPresent(coachId -> coachEnforcementService.suspendCoach(
                coachId, truncate("Account suspended: " + reason), false, adminId, cancelUpcomingBookings));
    }

    private long countRecentFailedAttempts(User user) {
        long count = authService.countRecentFailedAttempts(user.getLogin());
        if (user.getEmail() != null && !user.getEmail().equalsIgnoreCase(user.getLogin())) {
            count += authService.countRecentFailedAttempts(user.getEmail());
        }
        return count;
    }

    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500);
    }

    private User lockAndLoad(Long userId, String lockName) {
        // Loaded WITH the row lock, so every decision the caller makes reads committed, locked state.
        return lockRetryer.withBoundedRetry(lockName, () -> userRepository.findByIdForUpdate(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found", "user")));
    }

    private static boolean isPrivileged(User user) {
        if (user.getSkillarsRole() == SkillarsRole.ADMIN) {
            return true;
        }
        return user.getAuthorities().stream()
            .map(Authority::getName)
            .anyMatch(PRIVILEGED_AUTHORITIES::contains);
    }

    private void audit(Long adminId, AdminActionType type, Long userId, String reason) {
        AdminActionLog entry = new AdminActionLog();
        entry.setAdminId(adminId);
        entry.setActionType(type);
        entry.setReferenceId(String.valueOf(userId));
        entry.setReason(reason);
        adminActionLogRepository.save(entry);
    }

    /** Lower-cased, LIKE-escaped (escape char {@code !}) {@code %term%}; {@code "%"} lists everyone. */
    static String toLikePattern(String query) {
        if (query == null || query.isBlank()) {
            return "%";
        }
        String trimmed = query.trim().toLowerCase(Locale.ROOT);
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            trimmed = trimmed.substring(0, MAX_QUERY_LENGTH);
        }
        String escaped = trimmed.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }
}
