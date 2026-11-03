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

import io.micrometer.core.instrument.MeterRegistry;

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
     * skillars-deferred-166: how many {@code replacedById} links {@link #resolveGraceSuccessor} will follow
     * looking for the chain's live end. A chain grows by one per rotation inside the window, so this is
     * roughly the number of clients that can refresh the same cookie concurrently and still be treated as a
     * race rather than theft; beyond it the replay is answered as reuse, which revokes the account. Equal to
     * {@code RefreshTokenRevocationService.MAX_CHAIN_HOPS} and counted the same way, so logout always
     * reaches at least as far as the grace window can hand out.
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

    /**
     * skillars-deferred-167 AC7: counts the refresh attempts that are no longer treated as theft, which is
     * the only numeric trace left of a signal that used to revoke an account. See {@link StaleChain}.
     */
    private final MeterRegistry meterRegistry;

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
        // skillars-deferred-167 (AC2): a login starts a new rotation chain, so it mints the chain's id.
        // Every successor copies it (decideAndRotate), and the JWT carries it as a claim so the filter can
        // refuse this session's access token once the chain has no live row left.
        final UUID refreshSessionId = UUID.randomUUID();
        rt.setSessionId(refreshSessionId);
        refreshTokenRepository.save(rt);

        loginTokenManager.createLoginToken(res, principal, refreshSessionId);
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
            //  - pessimistic (CannotAcquireLockException / DeadlockLoserDataAccessException, SQLState
            //    40P01): the locks here WAIT rather than failing fast (a finite lock.timeout is inert on
            //    PostgreSQL — see findByTokenHashForUpdate), so this is a genuine deadlock, not a timeout.
            //    This walk locks the presented row and then its successor, while
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
            // NOTE, changed by skillars-deferred-166: the rotation has already COMMITTED by the time these
            // cookies are written, where before the restructure both happened in one transaction and a
            // failure here rolled the rotation back (the invariant login() still states for itself). So a
            // throw between this point and the Set-Cookie headers — a JWT signing failure, an
            // already-committed response — consumes the presented token without handing the client its
            // successor.
            //
            // Self-healing, and by the same mechanism this story added: the client still holds the OLD
            // cookie, because nothing was written, and that row is now `used` with its `replaced_by_id`
            // pointing at the live successor. A retry inside REFRESH_GRACE_WINDOW is therefore redirected
            // to that successor and issued normally. Outside the window it is reuse, and the user signs in
            // again. Accepted rather than fixed: keeping the cookies inside the transaction would mean
            // holding the row lock across them, which is exactly the shape that deadlocks against every
            // REQUIRES_NEW revoke (see this method's javadoc).
            final LoginResponse response = rotated.response();
            loginTokenManager.createLoginToken(res, rotated.principal(), rotated.refreshSessionId());
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

        if (outcome instanceof StaleChain stale) {
            // AC7: no writes. The warn is the whole of the residual signal — see StaleChain's javadoc for
            // what this branch stopped detecting and why that was accepted.
            log.warn("Refresh presented a token whose chain is still live but was rotated outside the "
                    + "{}s grace window; answering 401 without account-wide revocation. user={} tokenHash={}",
                REFRESH_GRACE_WINDOW.toSeconds(), stale.ownerId(), stale.presentedHash());
            meterRegistry.counter("security.refresh.stale_live_chain").increment();
            securityUtil.clearAuthCookies(res);
            throw new BadCredentialsException("Refresh token is stale — please sign in again");
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
            if (resolution.staleButLive()) {
                return new StaleChain(presented.getUserId(), presentedHash);
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
        // skillars-deferred-167 (AC2): the successor inherits the chain's id from the row being rotated —
        // `token`, which on a grace redirect is the chain's live end rather than the row the client
        // presented. A rotation therefore never starts a new chain.
        //
        // Minting a fresh id when the rotated row has none is the load-bearing half: V225 added the column
        // with no backfill, so every chain that predates it starts null, and COPYING that null would keep
        // it null through every subsequent rotation. A client that refreshes forever (an API or mobile
        // client; the SPA does not call this endpoint) would then never acquire an id, and AC4's check
        // would skip it for the rest of its life. This way such a chain picks one up on its next rotation.
        final UUID chainId = token.getSessionId() != null ? token.getSessionId() : UUID.randomUUID();
        successor.setSessionId(chainId);
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
        return new Rotated(newRaw, principal, chainId,
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
     * <p><strong>This check depends on every revoke-all caller stamping the epoch AFTER its bulk
     * revoke</strong>, which as of skillars-deferred-167 AC8 they all do:
     * {@code UserProfileService.changePassword} and {@code PasswordResetService.completePasswordReset}
     * always did, and {@code AdminUserService.suspendUser}, {@code AgeVerificationService.blockAccount} and
     * {@code AccountSuspensionEventListener.suspend} were reordered by that story. Stamping first left a
     * window in which a racing refresh's successor carried a {@code createdAt} after the epoch instant and
     * so passed this check; stamping afterwards means any row that survived the revoke was inserted before
     * it committed, and is therefore older than the epoch. If you add a revoke-all caller, keep that order.
     *
     * <p><strong>Two residuals remain, and this check does not close them.</strong> First,
     * {@link #decideAndRotate} loads the user with a plain {@code findById}, so it does not block on the
     * revoking transaction's row lock and can still read a {@code null} epoch until that transaction
     * commits; the refresh it allows in that instant is refused on its next attempt, not this one. Second,
     * the comparison is between a {@code createdAt} and an epoch written by potentially different
     * application nodes, so clock skew between them can invert it. Both are narrow and neither is specific
     * to this method — {@code changePassword} has always had the same shape — but "the window is closed" is
     * not the claim to make here.
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
     * <p>Three distinct answers, and skillars-deferred-167 AC7 is why there are three rather than two:
     * <ul>
     *   <li>{@link GraceResolution#of} — a live successor to hand over.</li>
     *   <li>{@link GraceResolution#revokedChain()} — a hop carried {@code revokedAt}; the session was
     *       deliberately ended.</li>
     *   <li>{@link GraceResolution#stale()} — the chain HAS a live end, but the presented row's rotation is
     *       older than the window. Answered as {@code StaleChain}: 401, no writes, <strong>not</strong>
     *       account-wide revocation. An earlier version of this paragraph listed "a rotation older than the
     *       window" under genuine reuse, which is no longer what happens; do not restore a {@code none()}
     *       return there without reading {@link StaleChain}'s javadoc first.</li>
     *   <li>{@link GraceResolution#none()} — genuine reuse, i.e. nothing live to be found: never rotated,
     *       no link at all (a rotation from before V224 — see that migration's accepted deploy-window
     *       trade-off), a cross-account link, a chain whose end is expired, or a chain longer than
     *       {@link #MAX_GRACE_HOPS}.</li>
     * </ul>
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
            // Defence in depth, mirroring RefreshTokenRevocationService: a successor must belong to the
            // SAME account. Nothing enforces that (V224's FK checks existence only and ships NOT VALID), and
            // handing a caller another user's live token would be a cross-account session takeover — the
            // worst possible version of the RS02 defect this method exists to fix.
            if (!successor.getUserId().equals(presented.getUserId())) {
                log.error("refresh_tokens.replaced_by_id {} points at user {} from user {}'s chain; "
                        + "refusing to redirect", successorId, successor.getUserId(), presented.getUserId());
                return GraceResolution.none();
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
        //
        // skillars-deferred-167 AC7: outside the window this is staleButLive, NOT none. The chain is intact
        // and live; all we know is that this caller's copy is old. Answering that with account-wide
        // revocation punished a benign double-refresh with a logout from every device (see the outcome's
        // own javadoc for the full reasoning, and for what detecting it would have cost).
        if (presented.getRotatedAt() == null
            || !now.isBefore(presented.getRotatedAt().plus(REFRESH_GRACE_WINDOW))) {
            return GraceResolution.stale();
        }
        return GraceResolution.of(liveEnd);
    }

    /**
     * The successor a grace-window redirect resolved to, or why it did not resolve one.
     *
     * <p>skillars-deferred-167 (AC7) added {@link #staleButLive()} as a THIRD outcome, because
     * "no successor" was conflating two very different situations: a chain with nothing live at its end
     * (the token really is being replayed — theft, as far as this code can tell) and a chain that is alive
     * and well but whose last rotation is older than the window. Only the first is evidence of theft.
     */
    private record GraceResolution(RefreshToken successor, boolean chainRevoked, boolean staleButLive) {
        static GraceResolution of(RefreshToken successor) {
            return new GraceResolution(successor, false, false);
        }
        static GraceResolution none() { return new GraceResolution(null, false, false); }
        static GraceResolution revokedChain() { return new GraceResolution(null, true, false); }
        /**
         * A live chain, but the presented row was rotated too long ago to hand its successor over.
         *
         * <p>Named {@code stale()} rather than {@code staleButLive()} because a record's component
         * accessor occupies that name — the same clash that made deferred-166 rename this record's
         * {@code chainRevoked} factory to {@link #revokedChain()}.
         */
        static GraceResolution stale() { return new GraceResolution(null, false, true); }
    }

    /**
     * What {@link #decideAndRotate} concluded, carried out of its transaction so that every revoke, cookie
     * clear and throw happens after the row locks are released. See {@link #refresh}.
     */
    private sealed interface RefreshOutcome
        permits Rotated, Unknown, Reuse, Revoked, StaleChain, Denied { }

    /**
     * Rotation succeeded. Everything needed for the response is pre-built; the user row is detached by now.
     *
     * <p>{@code refreshSessionId} (skillars-deferred-167 AC2/AC3) is carried out of the transaction for the
     * same reason {@code principal} is: the rows are detached by the time {@link #refresh} writes cookies,
     * and the JWT minted there needs the chain's id as a claim. Never null on this path — rotation either
     * inherits the chain's id or mints one.
     */
    private record Rotated(String newRawToken, Principal principal, UUID refreshSessionId,
                           LoginResponse response)
        implements RefreshOutcome { }

    /** No row matches the presented hash: 401, and deliberately no cookie teardown. */
    private record Unknown() implements RefreshOutcome { }

    /** A used token that is not a live chain's recent rotation: account-wide theft revocation. */
    private record Reuse(Long ownerId) implements RefreshOutcome { }

    /** The chain was deliberately ended: 401 and cookies, never account-wide revocation. */
    private record Revoked() implements RefreshOutcome { }

    /**
     * skillars-deferred-167 (AC7): the presented token is one hop or more behind a chain that is still
     * intact and live, but its own rotation is older than {@link #REFRESH_GRACE_WINDOW}. 401, cookies
     * cleared for this client, and <strong>nothing written</strong>.
     *
     * <p>Why this is not {@link Reuse}. Two concurrent refreshes of one cookie both answer with
     * {@code Set-Cookie: rtkn}; the browser keeps whichever response arrives last. When that is the
     * winner's, the client is left holding a row that is {@code used} with a live successor — permanently
     * one hop behind, through no fault of its own. Its next refresh more than 30 s later used to land in
     * the theft branch, which revoked every token on the account and bumped the session epoch, logging the
     * user out of every device over a double-submitted request.
     *
     * <p>Why nothing is written. The live end may be a legitimate sibling tab's current credential, so
     * revoking the chain would end that session too; and the presented row is already {@code used}, so it
     * cannot be traded in by anyone, including whoever presented it. There is nothing to revoke that would
     * make the account safer.
     *
     * <p><strong>What this gives up, deliberately — and it is more than the benign case above.</strong>
     * This branch was also the one place a stolen refresh token showed itself: an attacker who steals an
     * {@code rtkn} and rotates it first leaves the victim holding exactly this shape, and the old
     * behaviour revoked the whole account, which killed the attacker's session along with everyone
     * else's. That signal is gone, so the case is counted
     * ({@code security.refresh.stale_live_chain}) and logged at {@code warn} to stay observable even
     * though no state changes.
     *
     * <p>Three limits of this branch that its name does not convey, each verified rather than assumed:
     * <ul>
     *   <li><strong>Any age, not just a recent race.</strong> The window test is the last thing
     *       {@link #resolveGraceSuccessor} evaluates, so a replay of a week-old credential against a live
     *       chain lands here too, not only a double-submit from seconds ago.</li>
     *   <li><strong>Any depth up to {@link #MAX_GRACE_HOPS}.</strong> The presented row may sit up to 8
     *       rotations behind the live end. Beyond the bound the walk finds no live end and the outcome is
     *       {@link Reuse} again, so the theft response depends on how deep the chain happens to be — an
     *       artefact of bounding the walk, not a deliberate policy.</li>
     *   <li><strong>The attacker's chain is not bounded by {@code REFRESH_TOKEN_TTL}.</strong> Every
     *       rotation writes a fresh {@code expiresAt}, so a chain that keeps being rotated never expires.
     *       An earlier draft of this javadoc said "the rest of its 7-day TTL", which is only true of a
     *       chain nobody rotates.</li>
     * </ul>
     * A replay against a chain with NOTHING live at its end is still {@link Reuse} and still escalates.
     *
     * <p><strong>And the 401 takes the victim's remediation with it.</strong> This branch calls
     * {@code clearAuthCookies}, which deletes the {@code rtkn} cookie. That cookie was the only value
     * {@code SecurityUtil.terminateSession} could have hashed to start a chain walk, and the walk is what
     * would have reached the attacker's live successor and revoked it. After this 401 the victim's Logout
     * button therefore revokes nothing, and a fresh login starts an unrelated chain, so the foothold
     * outlives every action the user can take short of a password change (which still revokes account-wide).
     * Before this story the victim's own refresh remediated automatically, because it escalated.
     *
     * <p>Not changed here, deliberately: AC7 specifies {@code 401 + clearAuthCookies}, and whether to keep
     * clearing is part of the AC7 decision already filed in {@code deferred-work.md} — leaving the cookie
     * standing would preserve the Logout path at the cost of a client that believes it is still signed in.
     * Both reviewer arms raised this independently; do not "tidy up" the cookie clearing without reading
     * that entry.
     */
    private record StaleChain(Long ownerId, String presentedHash) implements RefreshOutcome { }

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
