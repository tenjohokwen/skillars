package com.softropic.skillars.infrastructure.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S-28: the service-layer backstop refuses only the limited admin tier; system and full-admin callers pass. */
class AdminTierGuardTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "p", roles));
    }

    @Test
    void limitedAdminIsRefused() {
        authenticateAs("ROLE_LTD_ADMIN");
        assertThatThrownBy(AdminTierGuard::requireFullAdmin).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void fullAdminPasses() {
        authenticateAs("ROLE_ADMIN");
        assertThatCode(AdminTierGuard::requireFullAdmin).doesNotThrowAnyException();
    }

    @Test
    void holdingBothRolesCountsAsFullAdmin() {
        authenticateAs("ROLE_LTD_ADMIN", "ROLE_ADMIN");
        assertThatCode(AdminTierGuard::requireFullAdmin).doesNotThrowAnyException();
    }

    @Test
    void noAuthenticationPasses_listenersJobsAndTestsRunWithoutOne() {
        assertThatCode(AdminTierGuard::requireFullAdmin).doesNotThrowAnyException();
    }

    @Test
    void isLimitedAdminOnlyForLtdWithoutAdmin() {
        authenticateAs("ROLE_LTD_ADMIN");
        assertThat(AdminTierGuard.isLimitedAdmin(SecurityContextHolder.getContext().getAuthentication().getAuthorities())).isTrue();
        authenticateAs("ROLE_COACH");
        assertThat(AdminTierGuard.isLimitedAdmin(SecurityContextHolder.getContext().getAuthentication().getAuthorities())).isFalse();
    }
}
