package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.repo.RefreshToken;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthService#refresh}'s decision branches.
 *
 * <p>skillars-deferred-144 AC6.2 started this class, scoped to the reuse branches calling
 * {@code clearAuthCookies} rather than {@code terminateSession}.
 *
 * <p>skillars-deferred-166 (ledger RS01/RS02) rewrote it. The method now decides inside a
 * {@code TransactionTemplate} block that holds a row lock and returns an outcome, then acts on that
 * outcome after the block commits. Two consequences shape every test here:
 * <ul>
 *   <li>{@code transactionTemplate} is stubbed to run its callback inline, so these tests still exercise
 *       the real decision logic rather than a no-op.</li>
 *   <li>Every revoke is {@code REQUIRES_NEW} and must therefore happen <em>after</em> the block, never
 *       inside it — holding the row lock across one self-deadlocks (55P03, a 409 where a 401 was meant).
 *       {@link #refresh_everyRevokeHappensAfterTheLockedBlockHasCommitted} is the mechanical guard for
 *       that, and the revoke mocks below fail the test if they are called while the block is running.</li>
 * </ul>
 * The grace window is the other half: it now follows {@code replacedById} to the token's own successor
 * instead of querying the account's newest live token, which is what let one device's refresh hand out and
 * then mass-revoke another device's session.
 */
class AuthServiceTest {

    private static final long USER_ID = 42L;
    private static final String RAW_REFRESH_TOKEN = "reused-raw-refresh-token-value";
    private static final long SUCCESSOR_ID = 7001L;

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private LoginRateLimiter loginRateLimiter;
    @Mock private ConfigService configService;
    @Mock private LoginTokenManager loginTokenManager;
    @Mock private SecurityUtil securityUtil;
    @Mock private UserIdentitySignalService userIdentitySignalService;
    @Mock private TransactionTemplate transactionTemplate;

    /**
     * A real registry, not a mock: skillars-deferred-167 AC7 requires the downgraded theft case to be
     * counted, and a counter is only worth asserting if the value actually moves.
     */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private AuthService authService;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    /** True while the {@code transactionTemplate} callback is executing, i.e. while the row lock is held. */
    private final AtomicBoolean insideLockedBlock = new AtomicBoolean(false);
    /** Set if any {@code REQUIRES_NEW} revoke was invoked while {@link #insideLockedBlock} was true. */
    private final AtomicBoolean revokeInsideLockedBlock = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        authService = new AuthService(userRepository, passwordEncoder, refreshTokenRepository,
            loginRateLimiter, configService, loginTokenManager, securityUtil, userIdentitySignalService,
            transactionTemplate, meterRegistry);
        request = new MockHttpServletRequest();
        request.setCookies(new Cookie(SecurityConstants.REFRESH_TOKEN_COOKIE, RAW_REFRESH_TOKEN));
        response = new MockHttpServletResponse();

        // Run the decide-and-rotate callback inline, flagging the window in which the row lock is held.
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            insideLockedBlock.set(true);
            try {
                return callback.doInTransaction(null);
            } finally {
                insideLockedBlock.set(false);
            }
        });

        // Any REQUIRES_NEW revoke called while the lock is held is the 55P03 self-deadlock this design
        // exists to prevent; record it rather than letting it pass silently.
        org.mockito.Mockito.doAnswer(inv -> {
            if (insideLockedBlock.get()) {
                revokeInsideLockedBlock.set(true);
            }
            return null;
        }).when(refreshTokenRepository).markAllUsedByUserId(anyLong());
        org.mockito.Mockito.doAnswer(inv -> {
            if (insideLockedBlock.get()) {
                revokeInsideLockedBlock.set(true);
            }
            return null;
        }).when(refreshTokenRepository).markUsedByTokenHash(anyString());
        // The denial paths' real revoke goes through terminateSession -> revokeChainByTokenHash
        // (REQUIRES_NEW), so it has to be instrumented too: markUsedByTokenHash has no AuthService caller
        // left, and a guard that watched only the two bulk primitives would pass while the revoke that
        // actually runs sat inside the lock.
        org.mockito.Mockito.doAnswer(inv -> {
            if (insideLockedBlock.get()) {
                revokeInsideLockedBlock.set(true);
            }
            return null;
        }).when(securityUtil).terminateSession(any(), any());
        org.mockito.Mockito.doAnswer(inv -> {
            if (insideLockedBlock.get()) {
                revokeInsideLockedBlock.set(true);
            }
            return null;
        }).when(userRepository).invalidateSessionsForUser(anyLong(), any());
    }

    // ---------------------------------------------------------------------------------------------
    // Reuse (theft) branch — carried over from skillars-deferred-144 AC6.2, restubbed for the lock.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("reuse: rotated inside the grace window but the successor link is missing (pre-V224 row)")
    void refresh_rotatedInGraceWindowWithNoSuccessorLink_isReuse() {
        // The V224 deploy-window case, and the one the old newest-live query papered over: a token rotated
        // before the column existed (or by an old instance mid-deploy) has no link to follow, so there is
        // no way to tell a legitimate multi-tab race from a replay. Treated as reuse, deliberately.
        RefreshToken usedToken = presentedToken();
        usedToken.setUsed(true);
        usedToken.setRotatedAt(Instant.now());
        usedToken.setReplacedById(null);
        lockReturns(usedToken);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(liveUser()));

        assertThatThrownBy(() -> authService.refresh(request, response))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessageContaining("reuse");

        verify(refreshTokenRepository).markAllUsedByUserId(USER_ID);
        verify(userRepository).invalidateSessionsForUser(eq(USER_ID), any());
        verify(securityUtil).clearAuthCookies(response);
        verify(securityUtil, never()).terminateSession(any(), any());
        // With no link to follow there is nothing to walk, so the absence of a by-id lookup is the
        // expected OUTCOME here, not evidence that the branch ran. The positive counterpart — the walk
        // firing and resolving a successor — is
        // refresh_staleTokenInGraceWindow_rotatesItsOwnSuccessor below, which verifies findByIdForUpdate
        // was called and that the successor it returned is the row that got consumed.
        verify(refreshTokenRepository, never()).findByIdForUpdate(anyLong());
        assertThat(revokeInsideLockedBlock).isFalse();
    }

    @Test
    @DisplayName("reuse: never rotated at all")
    void refresh_reuseNeverRotated_clearsCookiesOnlyNotTerminateSession() {
        RefreshToken usedToken = presentedToken();
        usedToken.setUsed(true);
        usedToken.setRotatedAt(null);
        lockReturns(usedToken);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(liveUser()));

        assertThatThrownBy(() -> authService.refresh(request, response))
            .isInstanceOf(BadCredentialsException.class);

        verify(refreshTokenRepository).markAllUsedByUserId(USER_ID);
        verify(securityUtil).clearAuthCookies(response);
        verify(securityUtil, never()).terminateSession(any(), any());
        verify(refreshTokenRepository, never()).findByIdForUpdate(anyLong());
        verify(userRepository).invalidateSessionsForUser(eq(USER_ID), any());
    }

    @Test
    @DisplayName("reuse: rotated long before the grace window, with a live successor link")
    void refresh_reuseRotatedLongAgo_clearsCookiesOnlyNotTerminateSession() {
        // The real comparison against a past Instant well outside REFRESH_GRACE_WINDOW (30 s). The
        // successor link is deliberately present: expiry of the WINDOW, not absence of a successor, is what
        // makes this reuse — exactly the distinction the old newest-live-for-the-account query could not
        // express.
        RefreshToken usedToken = presentedToken();
        usedToken.setUsed(true);
        usedToken.setRotatedAt(Instant.now().minusSeconds(3600));
        usedToken.setReplacedById(SUCCESSOR_ID);
        lockReturns(usedToken);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(liveUser()));

        assertThatThrownBy(() -> authService.refresh(request, response))
            .isInstanceOf(BadCredentialsException.class);

        verify(refreshTokenRepository).markAllUsedByUserId(USER_ID);
        verify(securityUtil).clearAuthCookies(response);
        verify(userRepository).invalidateSessionsForUser(eq(USER_ID), any());
        // The chain IS walked even though the window has expired, and that is deliberate: the walk answers
        // "was this chain deliberately revoked?", which decides theft versus a stale replay after a logout
        // and is true whatever the clock says. The window only gates whether a live successor may be handed
        // over — see refresh_staleTokenOutsideTheWindowWhoseChainWasRevoked_isNotTreatedAsTheft.
        verify(refreshTokenRepository).findByIdForUpdate(SUCCESSOR_ID);
    }

    // ---------------------------------------------------------------------------------------------
    // skillars-deferred-166: the successor-only grace window (RS02) and the revoked chain (RS01).
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("RS02: a stale token inside the window is redirected to ITS OWN successor and rotates it")
    void refresh_staleTokenInGraceWindow_rotatesItsOwnSuccessor() {
        RefreshToken stale = presentedToken();
        stale.setUsed(true);
        stale.setRotatedAt(Instant.now());
        stale.setReplacedById(SUCCESSOR_ID);

        RefreshToken successor = new RefreshToken();
        successor.setId(SUCCESSOR_ID);
        successor.setUserId(USER_ID);
        successor.setUsed(false);
        successor.setExpiresAt(Instant.now().plusSeconds(3600));

        lockReturns(stale);
        when(refreshTokenRepository.findByIdForUpdate(SUCCESSOR_ID)).thenReturn(Optional.of(successor));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(liveUser()));
        stubSuccessorIdOnSave();

        authService.refresh(request, response);

        // The successor — the token this one was actually rotated into — is what gets consumed. Before
        // this story the account's newest live token was consumed instead, which on a second device was
        // that device's session.
        assertThat(successor.isUsed()).isTrue();
        assertThat(successor.getReplacedById()).isNotNull();
        // The walk really ran and really resolved THIS successor.
        verify(refreshTokenRepository).findByIdForUpdate(SUCCESSOR_ID);
        verify(refreshTokenRepository, never()).markAllUsedByUserId(anyLong());
        verify(userRepository, never()).invalidateSessionsForUser(anyLong(), any());
        assertThat(revokeInsideLockedBlock).isFalse();
    }

    @Test
    @DisplayName("skillars-deferred-167 AC7: a stale token on a chain that is STILL LIVE, outside the "
        + "window, is 401 with no writes — and is counted")
    void refresh_staleTokenOutsideTheWindowOnALiveChain_is401WithNoRevocation() {
        // The case AC7 downgraded. Two concurrent refreshes of one cookie both set rtkn and the jar keeps
        // whichever arrived last; when that is the winner's, the client is left permanently one hop behind
        // a live chain. Its next refresh past the window used to run markAllUsedByUserId +
        // invalidateSessionsForUser, logging the user out of every device over a double-submitted request.
        //
        // Note what distinguishes this from refresh_reuseRotatedLongAgo above: there the successor lookup
        // is unstubbed and so finds nothing, which is still genuine reuse. Here the chain HAS a live end.
        RefreshToken stale = presentedToken();
        stale.setUsed(true);
        stale.setRotatedAt(Instant.now().minusSeconds(3600));
        stale.setReplacedById(SUCCESSOR_ID);

        RefreshToken liveEnd = new RefreshToken();
        liveEnd.setId(SUCCESSOR_ID);
        liveEnd.setUserId(USER_ID);
        liveEnd.setUsed(false);
        liveEnd.setExpiresAt(Instant.now().plusSeconds(3600));

        lockReturns(stale);
        when(refreshTokenRepository.findByIdForUpdate(SUCCESSOR_ID)).thenReturn(Optional.of(liveEnd));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(liveUser()));

        assertThatThrownBy(() -> authService.refresh(request, response))
            .isInstanceOf(BadCredentialsException.class);

        verify(refreshTokenRepository, never()).markAllUsedByUserId(anyLong());
        verify(userRepository, never()).invalidateSessionsForUser(anyLong(), any());
        verify(securityUtil).clearAuthCookies(response);
        // The live end must survive untouched: it may be a sibling tab's current credential, and the
        // presented row is already used so nobody can trade it in anyway.
        assertThat(liveEnd.isUsed()).isFalse();
        assertThat(liveEnd.getRevokedAt()).isNull();
        // AC7 requires this to be counted, not just logged: it is the only numeric trace of a signal that
        // used to revoke an account, and a warn line alone cannot be alerted on by rate.
        assertThat(meterRegistry.counter("security.refresh.stale_live_chain").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("RS01/F5: a stale token whose successor was logged out is 401, NOT account-wide revocation")
    void refresh_staleTokenWhoseSuccessorWasRevoked_doesNotRevokeTheWholeAccount() {
        // The stale-tab-after-logout case. Logout revokes from the cookie's row forward, so the
        // predecessor a background tab still holds keeps revokedAt = null — only its successor is marked.
        // Reading that as theft would sign the user out of every other device for clicking Logout.
        RefreshToken stale = presentedToken();
        stale.setUsed(true);
        stale.setRotatedAt(Instant.now());
        stale.setReplacedById(SUCCESSOR_ID);

        RefreshToken revokedSuccessor = new RefreshToken();
        revokedSuccessor.setId(SUCCESSOR_ID);
        revokedSuccessor.setUserId(USER_ID);
        revokedSuccessor.setUsed(true);
        revokedSuccessor.setRevokedAt(Instant.now());
        revokedSuccessor.setExpiresAt(Instant.now().plusSeconds(3600));

        lockReturns(stale);
        when(refreshTokenRepository.findByIdForUpdate(SUCCESSOR_ID)).thenReturn(Optional.of(revokedSuccessor));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(liveUser()));

        assertThatThrownBy(() -> authService.refresh(request, response))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessageContaining("Session has ended");

        verify(refreshTokenRepository, never()).markAllUsedByUserId(anyLong());
        verify(userRepository, never()).invalidateSessionsForUser(anyLong(), any());
        verify(securityUtil).clearAuthCookies(response);
        verify(securityUtil, never()).terminateSession(any(), any());
    }

    @Test
    @DisplayName("RS01: a token revoked by logout is 401, NOT account-wide revocation")
    void refresh_revokedToken_isRejectedWithoutRevokingTheAccount() {
        RefreshToken revoked = presentedToken();
        revoked.setUsed(true);
        revoked.setRevokedAt(Instant.now());
        lockReturns(revoked);

        assertThatThrownBy(() -> authService.refresh(request, response))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessageContaining("Session has ended");

        verify(refreshTokenRepository, never()).markAllUsedByUserId(anyLong());
        verify(userRepository, never()).invalidateSessionsForUser(anyLong(), any());
        verify(securityUtil).clearAuthCookies(response);
    }

    @Test
    @DisplayName("AC6: a token older than the account's session epoch is 401 with no second revoke-all")
    void refresh_tokenOlderThanSessionEpoch_isDeniedWithoutRevokeAll() {
        // After a password change / reset / suspension, markAllUsedByUserId has flipped every row to
        // used = true and the epoch is bumped. Reached the old way round, such a token was merely "used
        // and not recently rotated", i.e. the theft branch — so every replay ran a fresh account-wide
        // revocation. The epoch check answers it as the ended session it is.
        RefreshToken token = presentedToken();
        token.setUsed(true);
        lockReturns(token);
        User user = liveUser();
        user.setSecuritySessionInvalidatedAt(Instant.now());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.refresh(request, response))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessageContaining("Session was revoked");

        verify(refreshTokenRepository, never()).markAllUsedByUserId(anyLong());
        verify(userRepository, never()).invalidateSessionsForUser(anyLong(), any());
        // Denials end this one session: terminateSession revokes the chain reachable from the cookie.
        verify(securityUtil).terminateSession(request, response);
    }

    @Test
    @DisplayName("AC6: a null session epoch leaves an otherwise valid refresh untouched")
    void refresh_nullSessionEpoch_rotatesNormally() {
        RefreshToken token = presentedToken();
        lockReturns(token);
        User user = liveUser();
        user.setSecuritySessionInvalidatedAt(null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        stubSuccessorIdOnSave();

        authService.refresh(request, response);

        assertThat(token.isUsed()).isTrue();
        assertThat(token.getRotatedAt()).isNotNull();
        // RS01's precondition: the predecessor now names its successor, so logout can follow the chain.
        assertThat(token.getReplacedById()).isEqualTo(SUCCESSOR_ID);
        verify(securityUtil, never()).terminateSession(any(), any());
    }

    @Test
    @DisplayName("RS02 regression: the walk reaches the live end of a chain several rotations deep")
    void refresh_staleTokenWithAChainSeveralRotationsDeep_stillResolvesTheLiveEnd() {
        // The first version of resolveGraceSuccessor stopped after two hops, which turned three or more
        // clients refreshing the same cookie inside one window into "theft" and revoked the whole account.
        // The query this replaced (findFirstByUserIdAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc)
        // found the live token however deep the chain was, so stopping early was a regression.
        RefreshToken stale = presentedToken();
        stale.setUsed(true);
        stale.setRotatedAt(Instant.now());
        stale.setReplacedById(2L);

        RefreshToken hop1 = rotatedLink(2L, 3L);
        RefreshToken hop2 = rotatedLink(3L, 4L);
        RefreshToken liveEnd = new RefreshToken();
        liveEnd.setId(4L);
        liveEnd.setUserId(USER_ID);
        liveEnd.setUsed(false);
        liveEnd.setExpiresAt(Instant.now().plusSeconds(3600));

        lockReturns(stale);
        when(refreshTokenRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(hop1));
        when(refreshTokenRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(hop2));
        when(refreshTokenRepository.findByIdForUpdate(4L)).thenReturn(Optional.of(liveEnd));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(liveUser()));
        stubSuccessorIdOnSave();

        authService.refresh(request, response);

        assertThat(liveEnd.isUsed()).as("the chain's live end is what gets rotated").isTrue();
        verify(refreshTokenRepository, never()).markAllUsedByUserId(anyLong());
        verify(userRepository, never()).invalidateSessionsForUser(anyLong(), any());
    }

    @Test
    @DisplayName("RS01 regression: a revoked row deep in the chain is 'ended', even outside the window")
    void refresh_staleTokenOutsideTheWindowWhoseChainWasRevoked_isNotTreatedAsTheft() {
        // Logout walks FORWARD from the cookie it was given, so a predecessor another client still holds
        // keeps revoked_at = null. Testing the 30 s window before walking the chain made such a replay look
        // like theft once the window had passed, and revoked every session on the account — the failure AC3
        // exists to prevent, arriving by a path AC3 did not describe.
        RefreshToken stale = presentedToken();
        stale.setUsed(true);
        stale.setRotatedAt(Instant.now().minusSeconds(3600));
        stale.setReplacedById(SUCCESSOR_ID);

        RefreshToken revokedSuccessor = new RefreshToken();
        revokedSuccessor.setId(SUCCESSOR_ID);
        revokedSuccessor.setUserId(USER_ID);
        revokedSuccessor.setUsed(true);
        revokedSuccessor.setRevokedAt(Instant.now());
        revokedSuccessor.setExpiresAt(Instant.now().plusSeconds(3600));

        lockReturns(stale);
        when(refreshTokenRepository.findByIdForUpdate(SUCCESSOR_ID)).thenReturn(Optional.of(revokedSuccessor));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(liveUser()));

        assertThatThrownBy(() -> authService.refresh(request, response))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessageContaining("Session has ended");

        verify(refreshTokenRepository, never()).markAllUsedByUserId(anyLong());
        verify(userRepository, never()).invalidateSessionsForUser(anyLong(), any());
    }

    @Test
    @DisplayName("AC4: no REQUIRES_NEW revoke is issued while the row lock is held")
    void refresh_everyRevokeHappensAfterTheLockedBlockHasCommitted() {
        // The mechanical guard for the design's load-bearing rule. A revoke issued inside the locked
        // block waits for a lock only the surrounding transaction can release; Postgres cannot see the
        // cycle, because the outer side is blocked in Java, and it surfaces as lock_timeout / 55P03 —
        // a 409 instead of a 401. The first version of this story's design did exactly that.
        RefreshToken usedToken = presentedToken();
        usedToken.setUsed(true);
        usedToken.setRotatedAt(Instant.now().minusSeconds(3600));
        lockReturns(usedToken);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(liveUser()));

        assertThatThrownBy(() -> authService.refresh(request, response))
            .isInstanceOf(BadCredentialsException.class);

        assertThat(revokeInsideLockedBlock)
            .as("a REQUIRES_NEW revoke ran while the presented row was locked (55P03 self-deadlock)")
            .isFalse();
        // And it did run, after the block: the assertion above must not pass by nothing happening at all.
        verify(refreshTokenRepository).markAllUsedByUserId(USER_ID);
    }

    @Test
    @DisplayName("an unknown token hash is 401 with no cookie teardown and no revoke")
    void refresh_unknownTokenHash_doesNotClearCookies() {
        when(refreshTokenRepository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(request, response))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessageContaining("Invalid refresh token");

        // Unchanged behaviour, and deliberate: a junk token must not be able to log a victim's browser out.
        verify(securityUtil, never()).clearAuthCookies(any());
        verify(securityUtil, never()).terminateSession(any(), any());
        verify(refreshTokenRepository, never()).markAllUsedByUserId(anyLong());
    }

    // ---------------------------------------------------------------------------------------------
    // login() — unchanged by this story.
    // ---------------------------------------------------------------------------------------------

    // skillars-deferred-149 AC3 code review (2026-10-08): login() deliberately does NOT clear
    // securitySessionInvalidatedAt anymore — a previous implementation did, and that let a
    // victim's own innocent re-login re-arm an attacker's still-live stolen JWT (clearing the
    // account-wide flag undid the denial for every session, not just the one that should have
    // been killed). The column is now a monotonic revocation epoch, write-once via
    // UserRepository.invalidateSessionsForUser; login() must leave it exactly as it found it.
    @Test
    void login_success_doesNotClearPreviouslySetInvalidationFlag() {
        User user = new User();
        user.setId(USER_ID);
        user.setLogin("player@skillars-test.com");
        user.setPassword("hashed-password");
        user.setActivated(true);
        user.setLocked(false);
        Instant invalidatedAt = Instant.now().minusSeconds(60);
        user.setSecuritySessionInvalidatedAt(invalidatedAt);

        when(userRepository.findOneByLogin("player@skillars-test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("raw-password", "hashed-password")).thenReturn(true);

        authService.login("player@skillars-test.com", "raw-password", "127.0.0.1", response);

        assertThat(user.getSecuritySessionInvalidatedAt()).isEqualTo(invalidatedAt);
    }

    // S-14: an unknown login must cost one bcrypt comparison, like a wrong password, so timing does not
    // reveal which logins exist.
    @Test
    void login_unknownUser_stillRunsABcryptComparison() {
        when(userRepository.findOneByLogin("nobody@skillars-test.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("dummy-hash");

        assertThatThrownBy(() -> authService.login("nobody@skillars-test.com", "raw-password", "127.0.0.1", response))
            .isInstanceOf(BadCredentialsException.class);

        verify(passwordEncoder).matches("raw-password", "dummy-hash");
        verify(loginRateLimiter).recordFailure("nobody@skillars-test.com", "127.0.0.1");
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    /** The row the presented cookie hashes to: live, unused, unexpired unless a test says otherwise. */
    private RefreshToken presentedToken() {
        RefreshToken token = new RefreshToken();
        token.setId(6001L);
        token.setUserId(USER_ID);
        token.setUsed(false);
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        return token;
    }

    /** A rotated, unrevoked link in the middle of a chain: used, with a successor to follow. */
    private RefreshToken rotatedLink(long id, long successorId) {
        RefreshToken link = new RefreshToken();
        link.setId(id);
        link.setUserId(USER_ID);
        link.setUsed(true);
        link.setRotatedAt(Instant.now());
        link.setReplacedById(successorId);
        link.setExpiresAt(Instant.now().plusSeconds(3600));
        return link;
    }

    private void lockReturns(RefreshToken token) {
        when(refreshTokenRepository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(token));
    }

    private User liveUser() {
        User user = new User();
        user.setId(USER_ID);
        user.setLogin("player@skillars-test.com");
        user.setPassword("hashed-password");
        user.setActivated(true);
        user.setLocked(false);
        return user;
    }

    /**
     * The real {@code saveAndFlush} assigns the generated id that the predecessor's
     * {@code replaced_by_id} is written from; a bare mock would leave it null and quietly turn the chain
     * link — the whole point of V224 — into a no-op the tests could not see.
     */
    private void stubSuccessorIdOnSave() {
        AtomicLong nextId = new AtomicLong(SUCCESSOR_ID);
        when(refreshTokenRepository.saveAndFlush(any(RefreshToken.class))).thenAnswer(invocation -> {
            RefreshToken saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(nextId.getAndIncrement());
            }
            return saved;
        });
    }
}
