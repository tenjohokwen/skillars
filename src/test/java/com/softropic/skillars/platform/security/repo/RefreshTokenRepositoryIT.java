package com.softropic.skillars.platform.security.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-144 AC6.6: {@link RefreshTokenRepository#markUsedByTokenHash(String)}'s
 * {@code @Query} narrowed to also require {@code r.used = false}, so a repeat call against an
 * already-revoked row costs zero rows instead of unconditionally re-writing {@code used} and
 * bumping {@code version} again. Proves the predicate actually narrows the write, not merely that
 * the method still returns without error on a second call.
 */
class RefreshTokenRepositoryIT extends AbstractIntegrationTest {

    private static final long USER_ID = 9144_000001L;
    private static final String TOKEN_HASH =
        "deferred144repotest0000000000000000000000000000000000000000000aa";

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void markUsedByTokenHash_secondCallOnAlreadyUsedRow_isNoOp() {
        transactionTemplate.execute(status -> {
            insertUser(USER_ID, "deferred144.repo-test@skillars-test.com");
            return null;
        });

        RefreshToken token = new RefreshToken();
        token.setUserId(USER_ID);
        token.setTokenHash(TOKEN_HASH);
        token.setExpiresAt(Instant.now().plus(Duration.ofDays(1)));
        token.setUsed(false);
        refreshTokenRepository.save(token);

        refreshTokenRepository.markUsedByTokenHash(TOKEN_HASH);
        assertThat(usedFlagOf(TOKEN_HASH)).isTrue();
        long versionAfterFirstCall = versionOf(TOKEN_HASH);

        refreshTokenRepository.markUsedByTokenHash(TOKEN_HASH);

        assertThat(usedFlagOf(TOKEN_HASH)).isTrue();
        assertThat(versionOf(TOKEN_HASH))
            .as("a second call against an already-used row must not re-write it")
            .isEqualTo(versionAfterFirstCall);
    }

    private boolean usedFlagOf(String tokenHash) {
        return jdbcTemplate.queryForObject(
            "SELECT used FROM main.refresh_tokens WHERE token_hash = ?", Boolean.class, tokenHash);
    }

    private long versionOf(String tokenHash) {
        return jdbcTemplate.queryForObject(
            "SELECT version FROM main.refresh_tokens WHERE token_hash = ?", Long.class, tokenHash);
    }

    /** Minimal fixture row to satisfy refresh_tokens' FK on user_id — mirrors PlayerProfileRepositoryIT's helper. */
    private void insertUser(long id, String email) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1990-01-01', ?, 'Test', 'OTHER', 'en', 'User', 'DE', ?, " +
            "true, false, ?, 'EMAIL', ?, false, " +
            "'PARENT', 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email,
            "69" + (id % 100000000L),
            email, "x"
        );
    }
}
