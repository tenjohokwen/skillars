package com.softropic.skillars.infrastructure.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;

/**
 * S-28 defence in depth for the limited admin tier. The controller-level {@code HAS_FULL_ADMIN_ROLE}
 * is the primary gate; services that move money, change enforcement or config call
 * {@link #requireFullAdmin()} so a controller-level mistake (or a new caller) cannot expose them to
 * {@code ROLE_LTD_ADMIN}.
 *
 * <p>Deliberately a <em>deny-limited-admin</em> check rather than an allow-list: these services are also
 * reached from listeners, jobs and tests that run with no authenticated principal (or a system one),
 * and those must keep working. Only a caller that holds {@code ROLE_LTD_ADMIN} without
 * {@code ROLE_ADMIN} is refused.
 */
public final class AdminTierGuard {

    private AdminTierGuard() {
    }

    /** @throws AccessDeniedException if the current caller is a limited admin (LTD_ADMIN without ADMIN) */
    public static void requireFullAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && isLimitedAdmin(auth.getAuthorities())) {
            throw new AccessDeniedException("Requires full administrator (ROLE_ADMIN)");
        }
    }

    /** True for an account that holds ROLE_LTD_ADMIN but not ROLE_ADMIN. */
    public static boolean isLimitedAdmin(Collection<? extends GrantedAuthority> authorities) {
        boolean ltd = false;
        boolean full = false;
        for (GrantedAuthority a : authorities) {
            if (SecurityConstants.ROLE_LTD_ADMIN.equals(a.getAuthority())) {
                ltd = true;
            } else if (SecurityConstants.ROLE_ADMIN.equals(a.getAuthority())) {
                full = true;
            }
        }
        return ltd && !full;
    }
}
