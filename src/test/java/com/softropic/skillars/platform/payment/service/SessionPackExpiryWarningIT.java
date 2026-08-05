package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.utils.TestMailManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story Deferred-15 AC6. Task 7 wrote the first test here as a PROBE against unfixed code, to
 * establish what the notifier actually does today before changing it (the ledger item claimed
 * "up to 14 emails"; story creation predicted zero). Task 8 then flipped it to the fixed
 * behaviour and added the dedupe half.
 */
class SessionPackExpiryWarningIT extends BasePaymentIT {

    private static final long PARENT_ID = 96101L;
    private static final long PLAYER_ID = 96103L;
    private static final long COACH_USER_ID = 96102L;

    @Autowired SessionPackExpiryNotifier notifier;
    @Autowired MailManager mailManager;

    private UUID coachId;

    @BeforeEach
    void setUpFixtures() {
        coachId = insertTestCoach(COACH_USER_ID, "warn_coach@test.com", "Warn Coach");
        insertTestParent(PARENT_ID, "warn_parent@test.com");
        insertTestPlayer(PLAYER_ID, PARENT_ID);
        testMail().clear();
    }

    private TestMailManager testMail() {
        return (TestMailManager) mailManager;
    }

    private UUID seedExpiringPack(int daysToExpiry, int remainingSessions) {
        UUID tierId = UUID.randomUUID();
        UUID purchaseId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_tiers (pack_tier_id, coach_id, label, session_count, " +
                "total_price, price_per_session, is_active) VALUES (?, ?, 'Warn Tier', 10, 300.00, 30.00, false)",
                tierId, coachId);
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_purchases (purchase_id, parent_id, player_id, coach_id, " +
                "pack_tier_id, price_per_session, remaining_sessions, expires_at) VALUES (?, ?, ?, ?, ?, 30.00, ?, ?)",
                purchaseId, PARENT_ID, PLAYER_ID, coachId, tierId, remainingSessions,
                Timestamp.from(Instant.now().plus(daysToExpiry, ChronoUnit.DAYS)));
            return null;
        });
        return purchaseId;
    }

    /** Always via this helper — see BasePaymentIT.releaseSchedulerLock for why. */
    private void notifyRun() {
        releaseSchedulerLock("SessionPackExpiryNotifier_warn");
        notifier.notifyExpiringPacks();
    }

    private long warningEmailCount() {
        return testMail().getEnvelopes().values().stream()
            .filter(e -> e.emailTemplate() == EmailTemplate.SESSION_PACK_EXPIRY_WARNING)
            .count();
    }

    @Test
    void packInsideWarningWindow_sendsExactlyOneWarningEmail() {
        seedExpiringPack(7, 4);

        notifyRun();

        assertThat(warningEmailCount())
            .as("the warning event must reach SessionPackEmailListener — it only does so when published "
                + "inside an active transaction, since the listener is @TransactionalEventListener(AFTER_COMMIT)")
            .isEqualTo(1);
    }

    @Test
    void packAlreadyWarned_secondRunSendsNothing() {
        UUID purchaseId = seedExpiringPack(7, 4);

        notifyRun();
        assertThat(warningEmailCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT expiry_warned_at FROM payment.session_pack_purchases WHERE purchase_id = ?",
            Timestamp.class, purchaseId))
            .as("the stamp must be written in the same transaction that publishes")
            .isNotNull();

        testMail().clear();
        notifyRun();

        assertThat(warningEmailCount())
            .as("daily cron over a 14-day window: without the expiry_warned_at predicate this pack "
                + "is re-selected every morning for two weeks")
            .isZero();
    }
}
