package com.softropic.skillars.platform.security.contract;

import com.softropic.skillars.platform.security.repo.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

// skillars-deferred-149 AC3: instanceFrom's credentialsNonExpired previously hardcoded true, then
// (code review 2026-10-08) was briefly bound to securitySessionInvalidatedAt == null -- reverted
// to hardcoded true after that binding re-armed a stolen JWT on the victim's own next login and
// separately locked the still-live /authenticate endpoint out of a flagged account. The real
// per-JWT revocation check now lives in JWTAuthorizationFilter.assertSessionNotRevoked, which
// reads securitySessionInvalidatedAt off this class's own new getter instead.
class PrincipalTest {

    private User baseUser() {
        User user = new User();
        user.setId(1L);
        user.setLogin("principal.test@skillars-test.com");
        user.setPassword("x");
        user.setActivated(true);
        user.setLocked(false);
        return user;
    }

    @Test
    void instanceFrom_securitySessionInvalidatedAtNull_credentialsNonExpiredStillTrue() {
        User user = baseUser();
        user.setSecuritySessionInvalidatedAt(null);

        Principal principal = Principal.instanceFrom(user);

        assertThat(principal.isCredentialsNonExpired()).isTrue();
        assertThat(principal.getSecuritySessionInvalidatedAt()).isNull();
    }

    @Test
    void instanceFrom_securitySessionInvalidatedAtSet_credentialsNonExpiredStillTrue() {
        // The account-level flag no longer gates credentialsNonExpired at all -- only the
        // per-JWT epoch check (JWTAuthorizationFilter) consults this value now.
        User user = baseUser();
        Instant invalidatedAt = Instant.now().minusSeconds(60);
        user.setSecuritySessionInvalidatedAt(invalidatedAt);

        Principal principal = Principal.instanceFrom(user);

        assertThat(principal.isCredentialsNonExpired()).isTrue();
        assertThat(principal.getSecuritySessionInvalidatedAt()).isEqualTo(invalidatedAt);
    }
}
