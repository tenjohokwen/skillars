package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscriptionRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-113 AC3: {@code QuotaConfigService.resolveTierKey} previously mapped every
 * non-UUID (player) {@code ownerId} to a bare {@code "athlete"}, leaving the
 * {@code video.quota.semiPro.*}/{@code .pro.*} rows seeded by {@code V139__baseline_seed_data.sql}
 * (skillars-deferred-109 AC10) unreachable regardless of a player's actual subscription tier.
 *
 * <p>Drives the real {@link QuotaConfigService} against the real, container-backed
 * {@link PaymentPlayerSubscriptionRepository} and the real {@code video.quota.*} rows from the
 * baseline seed data — not a mocked repository or a stubbed config value — so this proves both the
 * tier-to-segment mapping AND that the seeded quota amounts are actually reachable end to end.
 */
class QuotaConfigServicePlayerTierIT extends AbstractIntegrationTest {

    @Autowired private QuotaConfigService quotaConfigService;
    @Autowired private PaymentPlayerSubscriptionRepository paymentPlayerSubscriptionRepository;

    /**
     * {@code player_subscriptions.player_id} FK-references {@code main.player_profiles.id}, which
     * in turn FK-references {@code main."user".id} as its parent — both inserted via raw JDBC
     * (mirrors {@code BasePaymentIT.insertTestParent}/{@code insertTestPlayer}; not reused directly
     * since that base class lives in the payment-module test tree and pulls in a WireMock server
     * annotation this test doesn't need). {@code billing_interval=YEARLY} satisfies
     * {@code chk_pps_pro_yearly}/{@code chk_pps_semi_pro_yearly} — PRO/SEMI_PRO tiers are
     * yearly-only by schema constraint; ATHLETE has no such restriction but YEARLY is valid for it
     * too, so one insert shape covers all three tiers.
     */
    private long persistSubscription(String tier) {
        long playerId = System.nanoTime();
        long parentUserId = playerId + 1;
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.\"user\" (id, login, login_id_type, password_hash, activated, "
                    + "first_name, last_name, gender, dob, email) "
                    + "VALUES (?, ?, 'EMAIL', '{noop}test', true, 'Test', 'Parent', 'MALE', '1985-01-01', ?) "
                    + "ON CONFLICT (id) DO NOTHING",
                parentUserId, "quota-tier-parent-" + parentUserId + "@skillars-test.com",
                "quota-tier-parent-" + parentUserId + "@skillars-test.com");
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles "
                    + "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, "
                    + "created_at, created_by) "
                    + "VALUES (?, 'Quota Tier Test Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system') "
                    + "ON CONFLICT (id) DO NOTHING",
                playerId, Date.valueOf(LocalDate.now().minusYears(16)), parentUserId,
                java.sql.Timestamp.from(Instant.now()));
            return null;
        });

        PaymentPlayerSubscription subscription = new PaymentPlayerSubscription();
        subscription.setPlayerId(playerId);
        subscription.setTier(tier);
        subscription.setBillingInterval("YEARLY");
        paymentPlayerSubscriptionRepository.saveAndFlush(subscription);
        return playerId;
    }

    @Test
    void semiProPlayer_getsSemiProQuota() {
        long playerId = persistSubscription("SEMI_PRO");

        assertThat(quotaConfigService.getStorageQuotaBytes(String.valueOf(playerId)))
            .as("Semi-Pro: video storage quota (4 GiB), V139__baseline_seed_data.sql:176")
            .isEqualTo(4_294_967_296L);
        assertThat(quotaConfigService.getBandwidthQuotaBytesMonthly(String.valueOf(playerId)))
            .as("Semi-Pro: monthly bandwidth (25 GiB), V139__baseline_seed_data.sql:175")
            .isEqualTo(26_843_545_600L);
    }

    @Test
    void proPlayer_getsProQuota() {
        long playerId = persistSubscription("PRO");

        assertThat(quotaConfigService.getStorageQuotaBytes(String.valueOf(playerId)))
            .as("Pro: video storage quota (7 GiB), V139__baseline_seed_data.sql:172")
            .isEqualTo(7_516_192_768L);
        assertThat(quotaConfigService.getBandwidthQuotaBytesMonthly(String.valueOf(playerId)))
            .as("Pro: monthly bandwidth (50 GiB), V139__baseline_seed_data.sql:171")
            .isEqualTo(53_687_091_200L);
    }

    @Test
    void athletePlayer_getsAthleteQuota() {
        long playerId = persistSubscription("ATHLETE");

        assertThat(quotaConfigService.getStorageQuotaBytes(String.valueOf(playerId)))
            .as("Athlete: video storage quota (2 GiB), V139__baseline_seed_data.sql:168")
            .isEqualTo(2_147_483_648L);
    }

    // Mutation: revert resolveTierKey's non-UUID branch back to a bare `return "athlete"` → this
    // test still passes coincidentally (SEMI_PRO's real quota differs from athlete's, so a
    // regression here would actually show as a FAILED assertion, not a false green) but this case
    // makes the "unmapped player" fail-open path explicit and independently verified.
    @Test
    void playerWithNoSubscriptionRow_fallsBackToAthleteQuota() {
        long unregisteredPlayerId = System.nanoTime();

        assertThat(quotaConfigService.getStorageQuotaBytes(String.valueOf(unregisteredPlayerId)))
            .isEqualTo(2_147_483_648L);
    }
}
