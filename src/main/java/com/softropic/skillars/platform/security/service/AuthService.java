package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.security.AdminTierGuard;
import com.softropic.skillars.infrastructure.security.CookieUtil;
import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.contract.LoginResponse;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.contract.SkillarsProfileCookie;
import com.softropic.skillars.platform.security.contract.SkillarsVerificationStatus;
import com.softropic.skillars.platform.security.contract.exception.LoginRateLimitedException;
import com.softropic.skillars.platform.security.contract.exception.SkillarsAccountNotVerifiedException;
import com.softropic.skillars.platform.security.repo.RefreshToken;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.REFRESH_TOKEN_COOKIE;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.REFRESH_TOKEN_TTL;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    // A recently-rotated token presented within this window is treated as a legitimate
    // multi-tab refresh race, not a theft event — the caller is redirected to the successor.
    private static final Duration REFRESH_GRACE_WINDOW = Duration.ofSeconds(30);

    /**
     * skillars-deferred-166: how far {@link #resolveGraceSuccessor} will follow {@code replacedById} looking
     * for the chain's live end. A chain grows by one per rotation inside the window, so this is the number
     * of clients that can refresh the same cookie concurrently and still be treated as a race rather than
     * theft. Matches {@code RefreshTokenRevocationService}'s own bound so logout can always revoke at least
     * as far as the grace window can reach.
     */
    private static final int MAX_GRACE_HOPS = 8;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final LoginRateLimiter loginRateLimiter;
    private final ConfigService configService;
    private final LoginTokenManager loginTokenManager;
    private final SecurityUtil securityUtil;
    private final UserIdentitySignalService userIdentitySignalService;
    /**
     * skillars-deferred-166: the refresh decision runs in its own short transaction rather than the
     * class-level one, so the row lock it holds is released before any {@code REQUIRES_NEW} revoke. A
     * {@code TransactionTemplate} and not a second {@code @Transactional} method on this bean, because
     * Spring's proxy does not intercept self-invocation. Precedent: {@code MessagingService}.
     */
    private final TransactionTemplate transactionTemplate;

    /** Lazily encoded with the live encoder so its cost factor always matches real users' hashes. */
    private volatile String dummyPasswordHash;

    // noRollbackFor BadCredentialsException: the failed-attempt row loginRateLimiter.recordFailure() writes (joining this transaction) just before
    // that throw is the ONLY thing this rate limiter counts. With the class-level @Transactional's
    // default rollback-on-RuntimeException it was rolled back together with the very failure it
    // recorded, so countByIdentifierAndAttemptedAtAfter never grew past what tests seeded directly and
    // the limiter could never engage. Nothing else is written on a BadCredentialsException path (the
    // refresh-token insert happens only after the password check), so committing on it is safe.
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public LoginResponse login(String email, String rawPassword, String clientIp, HttpServletResponse res) {
        // Rate limit keyed on SHA-256(email|ip) — see LoginRateLimiter. checkLimited also claims a
        // transaction-scoped advisory lock on that key (try-lock: a concurrent attempt for the same
        // (email, ip) is refused with 429 instead of queued), so a burst of parallel wrong passwords
        // cannot all pass the count before any of them records its failure. The lock is released
        // when this transaction ends, after the failure row below is committed.
        loginRateLimiter.checkLimited(email, clientIp).ifPresent(limited -> {
            throw new LoginRateLimitedException(
                "Too many failed login attempts",
                Map.of("attempts", limited.recentAttempts()),
                limited.retryAfterSeconds());
        });

        var user = userRepository.findOneByLogin(email.toLowerCase()).orElseThrow(() -> {
            // Burn one bcrypt comparison so an unknown login takes as long as a wrong password,
            // otherwise response time tells an attacker which logins exist.
            passwordEncoder.matches(rawPassword, dummyPasswordHash());
            loginRateLimiter.recordFailure(email, clientIp);
            return new BadCredentialsException("Invalid credentials");
        });

        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            loginRateLimiter.recordFailure(email, clientIp);
            throw new BadCredentialsException("Invalid credentials");
        }

        ensureAccountIsLive(user);

        // skillars-deferred-149 AC3 code review (2026-10-08): login() deliberately does NOT clear
        // securitySessionInvalidatedAt anymore. It is a monotonic, account-wide "last theft
        // revocation" epoch, not a per-login flag -- clearing it here let a victim's own innocent
        // re-login re-arm an attacker's still-live stolen JWT (that JWT's own SESSION_ISSUED_AT
        // claim still predates the real revocation; what mattered was never whether the column was
        // null, it is whether THIS JWT's claim is older than it). A fresh login's own new JWT
        // naturally carries a fresh SESSION_ISSUED_AT (see JwtManagerImpl.createLoginToken), which
        // is never older than this column however it is set, so no clearing is needed for a
        // legitimate login to succeed. See SecurityConstants.SESSION_ISSUED_AT's own javadoc.

        boolean phoneOtpRequired = configService.getBoolean("security.registration.phone-otp-required", true);
        if (user.getSkillarsRole() != null && phoneOtpRequired &&
            user.getVerificationStatus() != SkillarsVerificationStatus.BASIC_VERIFIED) {
            throw new SkillarsAccountNotVerifiedException();
        }

        Principal principal = Principal.instanceFrom(user);

        // feat/security-s06-related-party: this browser and this account's contact details are identity signals for
        // related-party detection (best-effort; nothing here can fail a login).
        userIdentitySignalService.recordCurrentBrowser(user.getId());
        userIdentitySignalService.recordContact(user);

        // Persist the refresh token before writing any response cookies — ensures DB state
        // is committed (or rolled back) before the client receives auth credentials.
        String rawToken = UUID.randomUUID().toString();
        RefreshToken rt = new RefreshToken();
        rt.setUserId(user.getId());
        rt.setTokenHash(sha256Hex(rawToken));
        rt.setExpiresAt(Instant.now(ClockProvider.getClock()).plus(REFRESH_TOKEN_TTL));
        rt.setUsed(false);
        refreshTokenRepository.save(rt);

        loginTokenManager.createLoginToken(res, principal);
        CookieUtil.addCookie(res, REFRESH_TOKEN_COOKIE, rawToken,
            true, (int) REFRESH_TOKEN_TTL.toSeconds(), "Lax");

        String role = user.getSkillarsRole() != null ? user.getSkillarsRole().name() : "ADMIN";
        boolean limitedAdmin = AdminTierGuard.isLimitedAdmin(principal.getAuthorities());
        new SkillarsProfileCookie(String.valueOf(user.getId()), role, limitedAdmin).writeTo(res);

        return new LoginResponse(user.getId(), role, principal.getDisplayName(), limitedAdmin);
    }

    private String dummyPasswordHash() {
        String hash = dummyPasswordHash;
        if (hash == null) {
            hash = passwordEncoder.encode(UUID.randomUUID().toString());
            dummyPasswordHash = hash;
        }
        return hash;
    }

    /**
     * Trades the {@code rtkn} refresh-token cookie for a fresh JWT + refresh-token pair.
     *
     * <p><strong>skillars-deferred-166 (RS01/RS02) restructured this method.</strong> It now decides
     * everything inside one short {@code TransactionTemplate} block that holds a {@code PESSIMISTIC_WRITE}
     * lock on the presented row, returns a {@link RefreshOutcome} instead of throwing, and writes only when
     * it rotates. Every revoke, cookie clear and throw happens after that block has committed and released
     * its locks.
     *
     * <p>That shape is forced by two constraints that pull in opposite directions:
     * <ul>
     *   <li><strong>A lock is needed</strong> so a concurrent logout and refresh on the same token
     *       serialise. Without it, logout's revoke and refresh's rotation interleave and the successor
     *       outlives the logout (RS01), and the grace window has nothing trustworthy to redirect along.</li>
     *   <li><strong>The lock must not be held across a revoke.</strong> Every revoke here is
     *       {@code REQUIRES_NEW} — it has to be, because each denial path throws and would otherwise have
     *       its own write rolled back (skillars-deferred-143 documented this on
     *       {@code RefreshTokenRepository.markUsedByTokenHash}). A {@code REQUIRES_NEW} write against a row
     *       the outer transaction holds locked waits for a lock only that outer transaction can release,
     *       and Postgres cannot see the cycle because the outer side is blocked in Java, not in the
     *       database: it surfaces as {@code lock_timeout} / SQLState 55P03, i.e. a 409 where a 401 was
     *       meant. {@code markAllUsedByUserId} (a bulk {@code UPDATE} with no {@code used} predicate, so it
     *       matches the locked row too) and {@code terminateSession} are both on that list.</li>
     * </ul>
     * Deciding under the lock and acting after it satisfies both without needing {@code noRollbackFor} or
     * an in-transaction twin of every revoke (a {@code MANDATORY} revoke would simply be rolled back by the
     * throw that follows it).
     *
     * <p>The {@code NOT_SUPPORTED} propagation is deliberate: this class is {@code @Transactional}, and an
     * ambient transaction spanning the whole method would re-create exactly the hazard above.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LoginResponse refresh(HttpServletRequest req, HttpServletResponse res) {
        String rawToken = CookieUtil.getCookieValue(req, REFRESH_TOKEN_COOKIE);
        if (rawToken == null || rawToken.isBlank()) {
            throw new BadCredentialsException("Missing refresh token");
        }

        final String presentedHash = sha256Hex(rawToken);

        final RefreshOutcome outcome;
        try {
            outcome = transactionTemplate.execute(status -> decideAndRotate(presentedHash));
        } catch (ObjectOptimisticLockingFailureException | PessimisticLockingFailureException ex) {
            // Backstop for every "someone else got there first" shape, all of which must end as a 401 the
            // client can act on rather than a 500:
            //  - optimistic: a stale managed copy written from elsewhere. Rare now that decideAndRotate
            //    takes a row lock, so the loser of a same-token race reads committed state and takes the
            //    grace redirect instead of failing a version check.
            //  - pessimistic (CannotAcquireLockException / DeadlockLoserDataAccessException, SQLState 40P01
            //    or 55P03): this walk locks the presented row and then its successor, while
            //    markAllUsedByUserId is a bulk UPDATE over the same user's rows in scan order, so a theft
            //    revocation, password change, reset or suspension running concurrently with a grace-window
            //    redirect can genuinely deadlock. Postgres kills one side; without this branch that side
            //    surfaced as a 500.
            // The template's transaction has already rolled back and released its locks, so cookies-only
            // teardown is safe and sufficient: nothing was written, and whoever won left the row `used`,
            // which is monotonic.
            securityUtil.clearAuthCookies(res);
            throw new BadCredentialsException("Concurrent refresh detected — please sign in again");
        }

        if (outcome instanceof Rotated rotated) {
            final LoginResponse response = rotated.response();
            loginTokenManager.createLoginToken(res, rotated.principal());
            CookieUtil.addCookie(res, REFRESH_TOKEN_COOKIE, rotated.newRawToken(),
                true, (int) REFRESH_TOKEN_TTL.toSeconds(), "Lax");
            new SkillarsProfileCookie(String.valueOf(response.userId()), response.role(),
                response.limitedAdmin()).writeTo(res);
            return response;
        }

        if (outcome instanceof Unknown) {
            // Unchanged: no cookie teardown for a hash that matches no row at all. There is no session to
            // end, and clearing cookies here would let an attacker with a junk token log a victim's browser
            // out. (An expired row already swept by deleteExpiredTokens lands here too.)
            throw new BadCredentialsException("Invalid refresh token");
        }

        if (outcome instanceof Reuse reuse) {
            // Unchanged theft response: a used token that is NOT a recent rotation of a still-live chain.
            // skillars-deferred-144 AC6.2: cookies only, because markAllUsedByUserId has already revoked
            // every row for the user, so terminateSession's per-token write would be redundant.
            // skillars-deferred-149 AC3: the bulk update never touches the user row, so a standing JWT
            // would keep self-extending via the filter's no-DB-hit fast path — hence the epoch bump.
            refreshTokenRepository.markAllUsedByUserId(reuse.ownerId());
            userRepository.invalidateSessionsForUser(reuse.ownerId(), Instant.now(ClockProvider.getClock()));
            securityUtil.clearAuthCookies(res);
            throw new BadCredentialsException("Token reuse detected — all sessions revoked");
        }

        if (outcome instanceof Revoked) {
            // skillars-deferred-166: a token whose chain was deliberately ended (logout, forced logout, a
            // denial) is NOT theft, and must not escalate to account-wide revocation. Before this story a
            // stale tab replaying a logged-out cookie took the branch above and signed the user out of
            // every other device. Nothing to revoke either: whoever set revokedAt walked the whole chain.
            securityUtil.clearAuthCookies(res);
            throw new BadCredentialsException("Session has ended — please sign in again");
        }

        final Denied denied = (Denied) outcome;
        // Expiry, missing user, stale session epoch and a non-live account all end this one session and
        // leave the rest of the account alone. terminateSession revokes the chain reachable from the
        // COOKIE's row (skillars-deferred-166 made it a chain walk), which is why the explicit
        // markUsedByTokenHash(resolvedTokenHash) that used to sit on the missing-user and account-liveness
        // paths is gone: that call existed because terminateSession could only reach the stale cookie row
        // while the live credential was the successor the grace window had resolved to (the
        // skillars-deferred-143 code-review patch). The walk reaches the successor from the predecessor
        // now, so the extra write has nothing left to cover.
        securityUtil.terminateSession(req, res);
        throw denied.failure();
    }

    /**
     * The whole refresh decision, run inside {@link #transactionTemplate} with a
     * {@code PESSIMISTIC_WRITE} lock on the presented row. Never throws for a business outcome and never
     * writes except when it rotates — see {@link #refresh} for why both properties matter.
     */
    private RefreshOutcome decideAndRotate(final String presentedHash) {
        final Instant now = Instant.now(ClockProvider.getClock());

        final RefreshToken presented = refreshTokenRepository.findByTokenHashForUpdate(presentedHash)
            .orElse(null);
        if (presented == null) {
            return new Unknown();
        }
        if (presented.getRevokedAt() != null) {
            return new Revoked();
        }

        // The user row is loaded BEFORE the used/grace decision, earlier than it used to be, so that the
        // session-epoch check below can answer a token invalidated by a revoke-all (password change, reset,
        // suspension) with a plain 401. Reached the old way round, such a token is merely `used` with no
        // recent rotation, so it fell into the theft branch and ran a second account-wide revocation on
        // every replay. One extra query on the happy path, where the user is loaded regardless.
        final User user = userRepository.findById(presented.getUserId()).orElse(null);
        if (user == null) {
            return new Denied(new BadCredentialsException("User not found for refresh token"));
        }
        if (isBeforeSessionEpoch(presented, user)) {
            return new Denied(new BadCredentialsException("Session was revoked — please sign in again"));
        }

        RefreshToken token = presented;
        if (presented.isUsed()) {
            final GraceResolution resolution = resolveGraceSuccessor(presented, now);
            if (resolution.chainRevoked()) {
                return new Revoked();
            }
            if (resolution.successor() == null) {
                return new Reuse(presented.getUserId());
            }
            token = resolution.successor();
            if (isBeforeSessionEpoch(token, user)) {
                return new Denied(new BadCredentialsException("Session was revoked — please sign in again"));
            }
        }

        if (token.getExpiresAt().isBefore(now)) {
            return new Denied(new BadCredentialsException("Refresh token has expired"));
        }

        // skillars-deferred-143 AC2: liveness is checked before the rotation write below, never after.
        try {
            ensureAccountIsLive(user);
        } catch (DisabledException | LockedException e) {
            return new Denied(e);
        }

        // Rotate. The successor is persisted and flushed first so its generated id is available to write
        // into the predecessor's replaced_by_id, which is what makes the chain walkable (RS01) and gives
        // the grace window something to follow (RS02). Both writes are in this transaction, under the lock.
        final String newRaw = UUID.randomUUID().toString();
        final RefreshToken successor = new RefreshToken();
        successor.setUserId(user.getId());
        successor.setTokenHash(sha256Hex(newRaw));
        successor.setExpiresAt(now.plus(REFRESH_TOKEN_TTL));
        successor.setUsed(false);
        refreshTokenRepository.saveAndFlush(successor);

        token.setUsed(true);
        token.setRotatedAt(now);
        token.setReplacedById(successor.getId());
        refreshTokenRepository.saveAndFlush(token);

        // Built here, not by the caller: User.authorities is LAZY and spring.jpa.open-in-view is false, so
        // Principal.instanceFrom on a detached row would throw LazyInitializationException. Everything the
        // response and the skp cookie need is assembled while the row is still managed.
        final Principal principal = Principal.instanceFrom(user);
        final String role = user.getSkillarsRole() != null ? user.getSkillarsRole().name() : "ADMIN";
        final boolean limitedAdmin = AdminTierGuard.isLimitedAdmin(principal.getAuthorities());
        return new Rotated(newRaw, principal,
            new LoginResponse(user.getId(), role, principal.getDisplayName(), limitedAdmin));
    }

    /**
     * skillars-deferred-166 AC6: true when this token predates the account's last session-wide revocation
     * ({@code security_session_invalidated_at}, V158). A null epoch — the normal case — skips the check.
     *
     * <p>This closes for refresh tokens the same gap V158 closed for JWTs: {@code markAllUsedByUserId} is a
     * bulk {@code UPDATE} whose snapshot cannot include a successor row that a concurrent refresh commits
     * after the statement starts, so a revoke-all could leave one live token behind. A token minted after
     * the epoch is unaffected, which is what lets a legitimate re-login work immediately.
     *
     * <p><strong>It closes that gap only where the epoch is stamped AFTER the bulk revoke</strong>, which is
     * {@code UserProfileService.changePassword} and {@code PasswordResetService.completePasswordReset}.
     * {@code AdminUserService.suspendUser}, {@code AgeVerificationService} and
     * {@code AccountSuspensionEventListener} stamp it first, so a successor committed between their two
     * statements carries a {@code createdAt} after the epoch and passes this check. Those three also set
     * {@code locked = true}, so {@code ensureAccountIsLive} refuses such a token for as long as the account
     * stays suspended — but an admin reactivation clears {@code locked} without revoking anything, which
     * would restore it. Narrow (the window is two statements of one transaction) and pre-existing, so it is
     * filed in {@code deferred-work.md} rather than fixed here by reordering three services this story does
     * not otherwise touch. Do not widen this javadoc's claim without fixing that ordering.
     *
     * <p>Fails CLOSED on a null {@code createdAt}: once an account carries a revocation epoch, a token that
     * cannot prove it was minted after it is refused. {@code refresh_tokens.created_at} is {@code NOT NULL}
     * (V138) and assigned by {@link RefreshToken}'s {@code @PrePersist}, so no row loaded from the database
     * can reach that branch — but a token whose age is unknown must not be the one thing that survives a
     * deliberate account-wide revocation, and "unreachable" is a poor reason to fail open.
     */
    private boolean isBeforeSessionEpoch(final RefreshToken token, final User user) {
        final Instant epoch = user.getSecuritySessionInvalidatedAt();
        if (epoch == null) {
            return false;
        }
        return token.getCreatedAt() == null || token.getCreatedAt().isBefore(epoch);
    }

    /**
     * skillars-deferred-166 (RS02): resolves a used token to the successor it was rotated into, following
     * {@code replacedById} and nothing else.
     *
     * <p>What this replaces: the grace window used to call
     * {@code findFirstByUserIdAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc}, i.e. the newest live token
     * of the whole ACCOUNT. With two devices that is routinely the other device's session: the stale
     * request was handed device B's credential and rotated it, B's cookie became a used token, and B's next
     * refresh tripped theft detection and logged the user out everywhere. It also let anyone replaying a
     * just-rotated token within the 30 s window obtain a working credential for whichever session was
     * newest.
     *
     * <p>The walk runs to the chain's live end, not one hop: that old query found the live token however
     * many rotations deep it was, and three or more clients refreshing the same cookie inside one window
     * build a chain that deep. Stopping early would answer a legitimate race with account-wide theft
     * revocation — a regression this method's first version shipped with and
     * {@code refresh_threeConcurrentRefreshesOfTheSameToken_neverTripTheftDetection} now guards.
     *
     * <p>The walk also runs <strong>before</strong> the grace-window test, because the two questions are
     * different. "Was any token in this chain deliberately revoked?" decides whether this is a stale replay
     * after a logout (401, no escalation) and is true whatever the clock says; logout walks forward from
     * the cookie it was given, so a predecessor another client still holds keeps {@code revokedAt == null}
     * and only the chain tells you. "May this caller be handed the live successor?" is what the 30 s window
     * gates, and it is asked of the presented token alone. Testing the window first made a predecessor
     * replayed 31 s after a logout look like theft and revoke every session on the account — exactly what
     * AC3 exists to prevent, reached by a path AC3 did not describe.
     *
     * <p>Returns a null successor with {@code chainRevoked == false} for genuine reuse: never rotated, no
     * link at all (a rotation from before V224 — see that migration's accepted deploy-window trade-off), a
     * chain whose end is expired, a chain longer than {@link #MAX_GRACE_HOPS}, or a rotation older than the
     * window.
     */
    private GraceResolution resolveGraceSuccessor(final RefreshToken presented, final Instant now) {
        RefreshToken liveEnd = null;
        RefreshToken cursor = presented;
        for (int hop = 0; hop < MAX_GRACE_HOPS; hop++) {
            final Long successorId = cursor.getReplacedById();
            if (successorId == null) {
                break;
            }
            final RefreshToken successor = refreshTokenRepository.findByIdForUpdate(successorId).orElse(null);
            if (successor == null) {
                break;
            }
            if (successor.getRevokedAt() != null) {
                return GraceResolution.revokedChain();
            }
            if (!successor.isUsed()) {
                if (!successor.getExpiresAt().isBefore(now)) {
                    liveEnd = successor;
                }
                break;
            }
            cursor = successor;
        }
        if (liveEnd == null) {
            return GraceResolution.none();
        }
        // Only now does the clock matter: the presented token must have been rotated recently enough for
        // this to be a plausible in-flight race rather than a replay of an old credential.
        if (presented.getRotatedAt() == null
            || !now.isBefore(presented.getRotatedAt().plus(REFRESH_GRACE_WINDOW))) {
            return GraceResolution.none();
        }
        return GraceResolution.of(liveEnd);
    }

    /** The successor a grace-window redirect resolved to, or why it did not resolve one. */
    private record GraceResolution(RefreshToken successor, boolean chainRevoked) {
        static GraceResolution of(RefreshToken successor) { return new GraceResolution(successor, false); }
        static GraceResolution none() { return new GraceResolution(null, false); }
        static GraceResolution revokedChain() { return new GraceResolution(null, true); }
    }

    /**
     * What {@link #decideAndRotate} concluded, carried out of its transaction so that every revoke, cookie
     * clear and throw happens after the row locks are released. See {@link #refresh}.
     */
    private sealed interface RefreshOutcome
        permits Rotated, Unknown, Reuse, Revoked, Denied { }

    /** Rotation succeeded. Everything needed for the response is pre-built; the user row is detached by now. */
    private record Rotated(String newRawToken, Principal principal, LoginResponse response)
        implements RefreshOutcome { }

    /** No row matches the presented hash: 401, and deliberately no cookie teardown. */
    private record Unknown() implements RefreshOutcome { }

    /** A used token that is not a live chain's recent rotation: account-wide theft revocation. */
    private record Reuse(Long ownerId) implements RefreshOutcome { }

    /** The chain was deliberately ended: 401 and cookies, never account-wide revocation. */
    private record Revoked() implements RefreshOutcome { }

    /** This one session is refused and revoked; {@code failure} is thrown after the teardown. */
    private record Denied(RuntimeException failure) implements RefreshOutcome { }

    public void logout(HttpServletRequest req, HttpServletResponse res) {
        securityUtil.terminateSession(req, res);
    }

    /**
     * skillars-deferred-143 AC1/AC2: the single account-liveness gate for both hand-rolled
     * authentication entry points. {@link #login} previously checked only {@code isActivated()} and
     * {@link #refresh} checked nothing at all, so {@code UserAdminService.lockUserAccount()} — which
     * sets {@code locked = true} without deactivating — was a silent no-op against this login path.
     *
     * <p>Mirrors the existing reject-a-locked-user shape used by the registration services (e.g.
     * {@code CoachRegistrationService}), and needs no new error-handling code: {@code ApiAdvice}
     * already maps {@link DisabledException} to a 401 {@code security.accNotEnabled} and
     * {@link LockedException} to a 401 {@code security.accLocked}, both already translated in all
     * three locales.
     *
     * <p>Deactivation is checked first so a GDPR-erased account (which
     * {@code GdprErasureService.eraseTransactional} leaves {@code activated = false} AND
     * {@code locked = true}) keeps reporting the key it reports today.
     */
    private void ensureAccountIsLive(User user) {
        if (!user.isActivated()) {
            throw new DisabledException("Account is not activated");
        }
        if (user.isLocked()) {
            throw new LockedException("Account is locked");
        }
        // A safety suspension (AccountSuspensionEventListener, CSAM match) also locks the account,
        // but the status is checked on its own as well: an account must never get a session while
        // it is SUSPENDED, whatever later touches `locked` and whatever
        // security.registration.phone-otp-required is set to (the only check that caught SUSPENDED
        // before, and only while that flag was true).
        if (user.getVerificationStatus() == SkillarsVerificationStatus.SUSPENDED) {
            throw new LockedException("Account is suspended");
        }
    }

    /**
     * Admin "unlock login" (V171): deletes every recorded failed attempt for this account across all
     * IPs, lifting the shared {@link LoginRateLimiter} limit for it immediately instead of waiting
     * out the lock window. Returns the number of attempt rows removed.
     */
    public int clearLoginRateLimit(String login) {
        return loginRateLimiter.clear(login);
    }

    /** Admin read-out (V171): failed attempts for this account, any IP, inside the current lock window. */
    @Transactional(readOnly = true)
    public long countRecentFailedAttempts(String login) {
        return loginRateLimiter.countRecentFailures(login);
    }

    private static String sha256Hex(String raw) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                                       .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
