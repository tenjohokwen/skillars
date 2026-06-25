package com.softropic.skillars.platform.payment;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.skillars.config.TestConfig;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Testcontainers
@Import(TestConfig.class)
@ActiveProfiles({"dev", "test"})
@EnableWireMock(@ConfigureWireMock(name = "stripe-service"))
public abstract class BasePaymentIT {

    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired protected TransactionTemplate transactionTemplate;

    @InjectWireMock("stripe-service")
    protected WireMockServer wireMockServer;

    @AfterEach
    void cleanPaymentData() {
        transactionTemplate.execute(status -> {
            // Delete in FK-safe order: children before parents
            jdbcTemplate.execute("DELETE FROM payment.booking_payments");
            jdbcTemplate.execute("DELETE FROM payment.parent_credit_ledger");
            // booking.bookings references payment.session_pack_purchases — clean bookings first
            jdbcTemplate.execute("DELETE FROM booking.booking_reschedule_requests");
            jdbcTemplate.execute("DELETE FROM booking.session_completion_data");
            jdbcTemplate.execute("DELETE FROM booking.bookings");
            jdbcTemplate.execute("DELETE FROM payment.session_pack_purchases");
            jdbcTemplate.execute("DELETE FROM payment.session_pack_tiers");
            jdbcTemplate.execute("DELETE FROM payment.stripe_customers");
            jdbcTemplate.execute("DELETE FROM marketplace.coach_pricing");
            // P8: coach_profiles and users were not cleaned, causing insertTestCoach to silently
            // return a stale UUID via ON CONFLICT DO NOTHING on repeated test-class runs
            jdbcTemplate.execute("DELETE FROM marketplace.coach_profiles");
            jdbcTemplate.execute("DELETE FROM main.\"user\" WHERE login LIKE '%@test.com'");
            return null;
        });
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
