package com.softropic.skillars.platform.payment;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.wiremock.spring.InjectWireMock;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Base class for payment module integration tests.
 * <p>
 * Provides PostgreSQL (via {@link TestConfig}), Redis, and a WireMock server
 * for the Stripe API ({@code wiremock.server.stripe-service.baseUrl}).
 * <p>
 * Subclasses that need to stub Stripe calls can inject {@link #wireMockServer} and
 * add WireMock stubs directly, OR use {@code @MockitoBean StripeClient} to mock at the
 * SDK-wrapper layer without HTTP stubs.
 */
public abstract class BasePaymentIT extends AbstractIntegrationTest {


    @InjectWireMock("stripe-service")
    protected WireMockServer wireMockServer;


    /**
     * Inserts a minimal parent user row (required as the FK target for player_profiles.parent_id).
     */
    protected void insertTestParent(long userId, String email) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.\"user\" (id, login, login_id_type, password_hash, activated, " +
                "first_name, last_name, gender, dob, email) " +
                "VALUES (?, ?, 'EMAIL', '{noop}test', true, 'Test', 'Parent', 'MALE', '1985-01-01', ?) " +
                "ON CONFLICT (id) DO NOTHING",
                userId, email, email
            );
            return null;
        });
    }

    /**
     * Inserts a minimal player profile owned by the given parent.
     * Returns the player profile id.
     */
    protected Long insertTestPlayer(long playerId, long parentId) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Test Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system') " +
                "ON CONFLICT (id) DO NOTHING",
                playerId, Date.valueOf(LocalDate.now().minusYears(16)), parentId,
                java.sql.Timestamp.from(Instant.now())
            );
            return null;
        });
        return playerId;
    }

    /**
     * Inserts a minimal user + coach_profile pair for testing.
     * Returns the coach profile UUID.
     */
    protected UUID insertTestCoach(long userId, String email, String displayName) {
        return transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.\"user\" (id, login, login_id_type, password_hash, activated, " +
                "first_name, last_name, gender, dob, email) " +
                "VALUES (?, ?, 'EMAIL', '{noop}test', true, 'Test', 'Coach', 'MALE', '1990-01-01', ?) " +
                "ON CONFLICT (id) DO NOTHING",
                userId, email, email
            );
            // Upsert with explicit conflict target and RETURNING so we always get the UUID,
            // regardless of whether the row was just inserted or already existed.
            return jdbcTemplate.queryForObject(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, canonical_timezone, status) " +
                "VALUES (gen_random_uuid(), ?, ?, 'UTC', 'ACTIVE') " +
                "ON CONFLICT (user_id) DO UPDATE SET display_name = EXCLUDED.display_name " +
                "RETURNING id",
                UUID.class,
                userId, displayName
            );
        });
    }
}
