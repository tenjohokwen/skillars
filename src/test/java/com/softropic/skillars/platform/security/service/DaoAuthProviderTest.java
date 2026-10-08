package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.repo.User;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;

import java.time.Instant;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-149 AC3 code review (2026-10-08): an earlier draft bound {@code
 * Principal.instanceFrom}'s {@code credentialsNonExpired} to {@code
 * User.securitySessionInvalidatedAt}, making {@code DaoAuthProvider.authorize()} throw for a
 * flagged user — that binding is what let a victim's own re-login re-arm a stolen JWT (clearing
 * the account-wide flag undid the denial for every session, not just the attacker's) and
 * separately locked the still-live {@code /authenticate} endpoint out of a flagged account, since
 * Spring's {@code preAuthenticationChecks} runs here too, before the password check. The real
 * per-JWT revocation check now lives entirely in {@code
 * JWTAuthorizationFilter.assertSessionNotRevoked}, comparing the JWT's own claim against this
 * same column — see that method's own tests. This class now pins the opposite: {@code authorize()}
 * must succeed regardless of {@code securitySessionInvalidatedAt}'s value, proving the
 * account-level gate was fully removed from this layer and did not silently come back.
 */
class DaoAuthProviderTest {

    private DaoAuthProvider newAuthProvider(UserDetailsService userDetailsService) {
        DaoAuthProvider authProvider = new DaoAuthProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(NoOpPasswordEncoder.getInstance());
        authProvider.setPreAuthenticationChecks(new AccountStatusUserDetailsChecker());
        return authProvider;
    }

    private User userWith(boolean sessionInvalidated) {
        User user = new User();
        user.setId(1L);
        user.setLogin("flagged@skillars-test.com");
        user.setPassword("x");
        user.setActivated(true);
        user.setLocked(false);
        if (sessionInvalidated) {
            user.setSecuritySessionInvalidatedAt(Instant.now().minusSeconds(60));
        }
        return user;
    }

    @Test
    void authorize_securitySessionInvalidated_stillSucceeds() {
        UserDetails principal = Principal.instanceFrom(userWith(true));
        DaoAuthProvider authProvider = newAuthProvider(username -> principal);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            "flagged@skillars-test.com", "x");

        Authentication result = authProvider.authorize(authentication, Collections.emptyList());

        assertThat(result.isAuthenticated()).isTrue();
    }

    @Test
    void authorize_securitySessionNotInvalidated_succeeds() {
        UserDetails principal = Principal.instanceFrom(userWith(false));
        DaoAuthProvider authProvider = newAuthProvider(username -> principal);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            "flagged@skillars-test.com", "x");

        Authentication result = authProvider.authorize(authentication, Collections.emptyList());

        assertThat(result.isAuthenticated()).isTrue();
    }
}
