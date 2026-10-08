package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.payment.contract.PlayerSubscriptionResponse;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * skillars-deferred-149 AC1: {@code PlayerSubscriptionOwnershipIT} already asserts {@code 200} for a
 * self-registered player's own-subscription call, but that class is {@code @WebMvcTest} with
 * {@code @MockitoBean SubscriptionService} — {@code assertPlayerOwnership}'s real logic never runs
 * there, so it could not and did not catch the bug this story fixes. This class autowires the genuine
 * {@code SubscriptionService} against a real Postgres, mirroring {@code
 * SubscriptionServiceConcurrencyIT}'s pattern, to prove the service layer itself — not just the
 * controller-level {@code @PreAuthorize} guard — accepts a self-registered adult player calling about
 * their own subscription.
 */
class SubscriptionServiceOwnershipIT extends AbstractIntegrationTest {

    @Autowired private SubscriptionService subscriptionService;

    private static final long SELF_PLAYER_USER_ID = 9149_000_001L;
    private static final long SELF_PLAYER_PROFILE_ID = 9149_000_002L;
    private static final long UNRELATED_PLAYER_PROFILE_ID = 9149_000_003L;

    @Test
    void getPlayerSubscription_selfRegisteredPlayerOwnProfile_returnsSubscription() {
        transactionTemplate.execute(status -> {
            insertUser(SELF_PLAYER_USER_ID, "subownership.selfplayer@skillars-test.com");
            insertSelfRegisteredPlayerProfile(SELF_PLAYER_PROFILE_ID, SELF_PLAYER_USER_ID);
            return null;
        });

        PlayerSubscriptionResponse response =
            subscriptionService.getPlayerSubscription(SELF_PLAYER_USER_ID, SELF_PLAYER_PROFILE_ID);

        assertThat(response.playerId()).isEqualTo(SELF_PLAYER_PROFILE_ID);
        assertThat(response.tier()).isEqualTo("ATHLETE");
    }

    @Test
    void getPlayerSubscription_selfRegisteredPlayerNotOwnProfile_rejectedWithPlayerOwnership() {
        transactionTemplate.execute(status -> {
            insertUser(SELF_PLAYER_USER_ID, "subownership.selfplayer2@skillars-test.com");
            insertUser(SELF_PLAYER_USER_ID + 1, "subownership.unrelated@skillars-test.com");
            // Owned by a DIFFERENT self-registered player — not parent-linked either — so the caller
            // must still be rejected.
            insertSelfRegisteredPlayerProfile(UNRELATED_PLAYER_PROFILE_ID, SELF_PLAYER_USER_ID + 1);
            return null;
        });

        assertThatThrownBy(() ->
            subscriptionService.getPlayerSubscription(SELF_PLAYER_USER_ID, UNRELATED_PLAYER_PROFILE_ID))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessage("payment.subscription.playerOwnership");
    }

    private void insertSelfRegisteredPlayerProfile(long playerId, long userId) {
        jdbcTemplate.update(
            "INSERT INTO main.player_profiles " +
            "(id, name, date_of_birth, position, age_tier, user_id, independent_account_allowed, created_at, created_by) " +
            "VALUES (?, 'Self Registered Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
            playerId, Date.valueOf(LocalDate.now().minusYears(20)),
            userId, Timestamp.from(Instant.now()));
    }

    private void insertUser(long id, String email) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', ?, ?, 'Test', 'OTHER', 'en', 'Player', 'DE', ?, " +
            "true, false, ?, 'EMAIL', 'x', false, " +
            "'PLAYER', 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            Date.valueOf(LocalDate.of(2000, 3, 15)),
            email,
            "914" + (id % 10000000),
            email);
    }
}
