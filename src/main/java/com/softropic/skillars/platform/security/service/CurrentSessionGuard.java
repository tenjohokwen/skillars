package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.security.AuthorizationException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * A database check of the caller's session, for irreversible actions (skillars-deferred-172, review round 2).
 *
 * <p>The JWT filter compares a token with the account's session epoch ({@code securitySessionInvalidatedAt})
 * only on its DB re-auth path, every {@code DB_REFRESH_TOKEN_INTERVAL} (5 min); between those it trusts the
 * token. So after a revocation (an email-change undo, a password change, a suspension) a token issued before it
 * keeps working for up to 5 minutes. For most endpoints that is the accepted cost of the fast path; for an
 * action that cannot be taken back it is not: after an undo, the party who had taken the account could still
 * erase it. Such endpoints call {@link #assertNotRevoked} first.
 */
@Service
@RequiredArgsConstructor
public class CurrentSessionGuard {

    private final UserRepository userRepository;
    private final LoginTokenManager loginTokenManager;

    /**
     * Refuses (401, like the filter) when the account has been locked, or its sessions revoked after the
     * request's token was issued. A token without the session-issued-at claim counts as predating it (fail closed,
     * as in the filter).
     */
    @Transactional(readOnly = true)
    public void assertNotRevoked(final HttpServletRequest request, final Long userId) {
        final User user = userRepository.findOneById(userId)
            .orElseThrow(() -> refused("Account no longer exists"));
        if (user.isLocked()) {
            throw refused("Account is locked");
        }
        final Instant invalidatedAt = user.getSecuritySessionInvalidatedAt();
        if (invalidatedAt == null) {
            return;
        }
        final Long sessionIssuedAt = loginTokenManager.extractSessionIssuedAt(request);
        if (sessionIssuedAt == null || sessionIssuedAt < invalidatedAt.toEpochMilli()) {
            throw refused("Session predates the account's last session revocation");
        }
    }

    private static AuthorizationException refused(final String message) {
        return new AuthorizationException(message, SecurityError.ACCOUNT_NOT_LOGIN_ABLE);
    }
}
