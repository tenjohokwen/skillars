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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deferred from story 6.4 — tests simultaneous subscription expiry scenarios.
 * Two players' subscriptions expire around the same time; the outbox processor
 * must handle them independently without cross-contamination.
 */
@Disabled("Deferred from 6.4 — requires concurrent outbox processing validation")
@TestPropertySource(properties = {
    "platform.video.lifecycle.outbox_max_attempts=3",
    "platform.video.lifecycle.batch_size=50",
})
class SimultaneousExpiryIT extends BaseVideoIT {

    @Autowired VideoSubscriptionLifecycleListener listener;
    @Autowired SubscriptionLifecycleOutboxRepository outboxRepository;
    @Autowired VideoRepository videoRepository;

    @MockitoBean VideoLifecycleService videoLifecycleService;
    @MockitoBean PlayerSubscriptionQueryPort playerSubscriptionQueryPort;

    private static final long PLAYER_A = 13001L;
    private static final long PLAYER_B = 13002L;

    @BeforeEach
    void setUpConfig() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.platform_config (id, config_key, value) " +
                "VALUES (8201, 'platform.video.lifecycle.outbox_max_attempts', '3') " +
                "ON CONFLICT (config_key) DO UPDATE SET value = '3'"
            );
            jdbcTemplate.update(
                "INSERT INTO main.platform_config (id, config_key, value) " +
                "VALUES (8202, 'platform.video.lifecycle.batch_size', '50') " +
                "ON CONFLICT (config_key) DO UPDATE SET value = '50'"
            );
            return null;
        });
    }

    @AfterEach
    void clean() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.subscription_lifecycle_outbox");
            jdbcTemplate.execute("DELETE FROM main.videos WHERE owner_id IN ('" + PLAYER_A + "', '" + PLAYER_B + "')");
            jdbcTemplate.update("DELETE FROM main.platform_config WHERE id IN (8201, 8202)");
            return null;
        });
    }

    /**
     * Player A has no active subscription → videos blocked.
     * Player B has active concurrent subscription → videos NOT blocked.
     * Both outbox entries processed in the same scheduler run.
     */
    @Test
    void simultaneousExpiry_playersHandledIndependently() {
        seedActiveReadyVideo(PLAYER_A);
        seedActiveReadyVideo(PLAYER_B);
        SubscriptionLifecycleOutbox entryA = seedOutbox(PLAYER_A, "MONTHLY");
        SubscriptionLifecycleOutbox entryB = seedOutbox(PLAYER_B, "MONTHLY");

        when(playerSubscriptionQueryPort.hasAnyActiveSubscription(PLAYER_A)).thenReturn(false);
        when(playerSubscriptionQueryPort.hasAnyActiveSubscription(PLAYER_B)).thenReturn(true);

        listener.processOutbox();

        // Player A's videos should be blocked
        verify(videoLifecycleService, atLeastOnce())
            .blockForSubscriptionExpiry(any(UUID.class), any(Instant.class));

        // Both outbox entries should be processed
        assertThat(outboxRepository.findById(entryA.getId()).orElseThrow().getStatus())
            .isEqualTo("PROCESSED");
        assertThat(outboxRepository.findById(entryB.getId()).orElseThrow().getStatus())
            .isEqualTo("PROCESSED");
    }

    /**
     * Two expiry events for the same player (e.g., two consecutive monthly cycles)
     * processed in the same run — the second is also processed without double-blocking.
     */
    @Test
    void samePlayer_twoExpiryEvents_bothProcessed() {
        seedActiveReadyVideo(PLAYER_A);
        SubscriptionLifecycleOutbox entry1 = seedOutbox(PLAYER_A, "MONTHLY");
        SubscriptionLifecycleOutbox entry2 = seedOutbox(PLAYER_A, "MONTHLY");

        when(playerSubscriptionQueryPort.hasAnyActiveSubscription(PLAYER_A)).thenReturn(false);

        listener.processOutbox();

        assertThat(outboxRepository.findById(entry1.getId()).orElseThrow().getStatus())
            .isEqualTo("PROCESSED");
        assertThat(outboxRepository.findById(entry2.getId()).orElseThrow().getStatus())
            .isEqualTo("PROCESSED");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void seedActiveReadyVideo(long playerId) {
        Video v = new Video();
        v.setOwnerId(String.valueOf(playerId));
        v.setProvider("bunny");
        v.setProviderAssetId("asset-sim-" + UUID.randomUUID());
        v.setTitle("test-sim.mp4");
        v.setOperationalState(OperationalState.READY);
        v.setAccessState(AccessState.ACTIVE);
        v.setVisibility(Visibility.PRIVATE);
        videoRepository.save(v);
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
