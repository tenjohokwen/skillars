package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.PlayerSubscriptionQueryPort;
import com.softropic.skillars.platform.video.contract.Visibility;
import com.softropic.skillars.platform.video.repo.SubscriptionLifecycleOutbox;
import com.softropic.skillars.platform.video.repo.SubscriptionLifecycleOutboxRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Deferred from story 6.4 — tests the YEARLY subscription renewal flow.
 * When a YEARLY subscription renews, blocked videos must have their lifecycle clock reset
 * and access restored.
 */
@Disabled("Deferred from 6.4 — requires subscription renewal webhook path")
@TestPropertySource(properties = {
    "platform.video.lifecycle.outbox_max_attempts=3",
    "platform.video.lifecycle.batch_size=50",
})
class YearlyExemptionRenewalIT extends BaseVideoIT {

    @Autowired VideoSubscriptionLifecycleListener listener;
    @Autowired SubscriptionLifecycleOutboxRepository outboxRepository;
    @Autowired VideoRepository videoRepository;

    @MockitoBean VideoLifecycleService videoLifecycleService;
    @MockitoBean PlayerSubscriptionQueryPort playerSubscriptionQueryPort;

    private static final long PLAYER_ID = 12001L;

    @BeforeEach
    void setUpConfig() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.platform_config (id, config_key, value) " +
                "VALUES (8101, 'platform.video.lifecycle.outbox_max_attempts', '3') " +
                "ON CONFLICT (config_key) DO UPDATE SET value = '3'"
            );
            jdbcTemplate.update(
                "INSERT INTO main.platform_config (id, config_key, value) " +
                "VALUES (8102, 'platform.video.lifecycle.batch_size', '50') " +
                "ON CONFLICT (config_key) DO UPDATE SET value = '50'"
            );
            return null;
        });
    }

    @AfterEach
    void clean() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.subscription_lifecycle_outbox");
            jdbcTemplate.execute("DELETE FROM main.videos WHERE owner_id = '" + PLAYER_ID + "'");
            jdbcTemplate.update("DELETE FROM main.platform_config WHERE id IN (8101, 8102)");
            return null;
        });
    }

    /**
     * When a YEARLY subscription expires, videos should have lifecycle_locked_at reset.
     * If the player subsequently renews, the outbox with YEARLY tier causes Path A execution,
     * confirming that the lifecycle clock is cleared (enabling renewal to restore access).
     */
    @Test
    void yearlyRenewal_resetsLifecycleClock_onBlockedVideos() {
        Video video = seedBlockedReadyVideoWithLockedAt(PLAYER_ID);
        SubscriptionLifecycleOutbox entry = seedOutbox(PLAYER_ID, "YEARLY");

        when(playerSubscriptionQueryPort.hasAnyActiveSubscription(PLAYER_ID)).thenReturn(true);

        listener.processOutbox();

        // lifecycleLockedAt should be null after yearly Path A processing
        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getLifecycleLockedAt()).isNull();

        SubscriptionLifecycleOutbox processed = outboxRepository.findById(entry.getId()).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo("PROCESSED");
    }

    @Test
    void yearlyRenewal_multipleBlockedVideos_allClocksReset() {
        for (int i = 0; i < 3; i++) {
            seedBlockedReadyVideoWithLockedAt(PLAYER_ID);
        }
        SubscriptionLifecycleOutbox entry = seedOutbox(PLAYER_ID, "YEARLY");

        listener.processOutbox();

        long stillLocked = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.videos WHERE owner_id = ? AND lifecycle_locked_at IS NOT NULL",
            Long.class, String.valueOf(PLAYER_ID)
        );
        assertThat(stillLocked).isZero();

        SubscriptionLifecycleOutbox processed = outboxRepository.findById(entry.getId()).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo("PROCESSED");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Video seedBlockedReadyVideoWithLockedAt(long playerId) {
        Video v = new Video();
        v.setOwnerId(String.valueOf(playerId));
        v.setProvider("bunny");
        v.setProviderAssetId("asset-yearly-" + UUID.randomUUID());
        v.setTitle("test-yearly.mp4");
        v.setOperationalState(OperationalState.READY);
        v.setAccessState(AccessState.BLOCKED);
        v.setVisibility(Visibility.PRIVATE);
        v.setLifecycleLockedAt(Instant.now().minusSeconds(86400));
        return videoRepository.save(v);
    }

    private SubscriptionLifecycleOutbox seedOutbox(long subscriberId, String tier) {
        SubscriptionLifecycleOutbox entry = new SubscriptionLifecycleOutbox();
        entry.setSubscriberId(subscriberId);
        entry.setSubscriptionTier(tier);
        entry.setExpiredAt(Instant.now());
        entry.setStatus("PENDING");
        return outboxRepository.save(entry);
    }
}
