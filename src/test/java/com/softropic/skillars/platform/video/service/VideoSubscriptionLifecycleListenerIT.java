package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.payment.contract.event.SubscriptionExpiredEvent;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = {
    // Prevent background schedulers from racing test assertions
    "platform.video.lifecycle.outbox_max_attempts=2",
    "platform.video.lifecycle.batch_size=50",
})
class VideoSubscriptionLifecycleListenerIT extends BaseVideoIT {

    @Autowired VideoSubscriptionLifecycleListener listener;
    @Autowired SubscriptionLifecycleOutboxRepository outboxRepository;
    @Autowired VideoRepository videoRepository;
    @Autowired ConfigService configService;

    @MockitoBean VideoLifecycleService videoLifecycleService;
    @MockitoBean PlayerSubscriptionQueryPort playerSubscriptionQueryPort;

    private static final long PLAYER_ID = 11001L;

    @BeforeEach
    void setUpConfigValues() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE main.platform_config SET value = '2' WHERE key = 'platform.video.lifecycle.outbox_max_attempts'"
            );
            jdbcTemplate.update(
                "UPDATE main.platform_config SET value = '50' WHERE key = 'platform.video.lifecycle.batch_size'"
            );
            return null;
        });
        configService.invalidate();
    }

    @AfterEach
    void cleanVideoLifecycleData() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.subscription_lifecycle_outbox");
            jdbcTemplate.execute("DELETE FROM main.videos WHERE owner_id = '" + PLAYER_ID + "'");
            jdbcTemplate.update(
                "UPDATE main.platform_config SET value = '5' WHERE key = 'platform.video.lifecycle.outbox_max_attempts'"
            );
            jdbcTemplate.update(
                "UPDATE main.platform_config SET value = '100' WHERE key = 'platform.video.lifecycle.batch_size'"
            );
            return null;
        });
        configService.invalidate();
    }

    // ─── Path B: MONTHLY, no other subscription → block videos ──────────────

    @Test
    void monthly_noOtherActiveSub_videosAreBlocked() {
        seedActiveReadyVideo(PLAYER_ID);
        SubscriptionLifecycleOutbox entry = seedOutbox(PLAYER_ID, "MONTHLY");

        when(playerSubscriptionQueryPort.hasAnyActiveSubscription(PLAYER_ID)).thenReturn(false);
        doAnswer(invocation -> {
            UUID videoId = invocation.getArgument(0);
            jdbcTemplate.update("UPDATE main.videos SET access_state = 'BLOCKED', lifecycle_locked_at = NOW() WHERE id = ?", videoId);
            return null;
        }).when(videoLifecycleService).blockForSubscriptionExpiry(any(UUID.class), any(Instant.class));

        listener.processOutbox();

        verify(videoLifecycleService, atLeastOnce())
            .blockForSubscriptionExpiry(any(UUID.class), any(Instant.class));

        SubscriptionLifecycleOutbox processed = outboxRepository.findById(entry.getId()).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo("PROCESSED");
    }

    // ─── Path B: MONTHLY, concurrent active subscription → skip blocking ─────

    @Test
    void monthly_hasActiveConcurrentSub_videosNotBlocked() {
        seedActiveReadyVideo(PLAYER_ID);
        SubscriptionLifecycleOutbox entry = seedOutbox(PLAYER_ID, "MONTHLY");

        when(playerSubscriptionQueryPort.hasAnyActiveSubscription(PLAYER_ID)).thenReturn(true);

        listener.processOutbox();

        verify(videoLifecycleService, never())
            .blockForSubscriptionExpiry(any(UUID.class), any(Instant.class));

        SubscriptionLifecycleOutbox processed = outboxRepository.findById(entry.getId()).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo("PROCESSED");
    }

    // ─── Path A: YEARLY → reset lifecycle clock ───────────────────────────────

    @Test
    void yearly_resetsLifecycleLockedAt_forBlockedVideos() {
        seedBlockedReadyVideo(PLAYER_ID);
        SubscriptionLifecycleOutbox entry = seedOutbox(PLAYER_ID, "YEARLY");

        listener.processOutbox();

        // resetLifecycleLockedAt refreshes the timestamp to CURRENT_TIMESTAMP — verify via DB
        // (setting to null would violate the CHECK constraint; CURRENT_TIMESTAMP resets the 30-day clock)
        long notRefreshed = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.videos WHERE owner_id = ? AND lifecycle_locked_at < NOW() - INTERVAL '5 seconds'",
            Long.class, String.valueOf(PLAYER_ID)
        );
        assertThat(notRefreshed).isZero();

        verify(videoLifecycleService, never())
            .blockForSubscriptionExpiry(any(UUID.class), any(Instant.class));

        SubscriptionLifecycleOutbox processed = outboxRepository.findById(entry.getId()).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo("PROCESSED");
    }

    // ─── At-least-once: retry on failure ─────────────────────────────────────

    @Test
    void processOutbox_failureOnFirstAttempt_retriedOnSecondCall() {
        seedActiveReadyVideo(PLAYER_ID);
        SubscriptionLifecycleOutbox entry = seedOutbox(PLAYER_ID, "MONTHLY");

        when(playerSubscriptionQueryPort.hasAnyActiveSubscription(PLAYER_ID))
            .thenReturn(false);
        doThrow(new RuntimeException("transient error"))
            .doAnswer(invocation -> {
                UUID videoId = invocation.getArgument(0);
                jdbcTemplate.update("UPDATE main.videos SET access_state = 'BLOCKED', lifecycle_locked_at = NOW() WHERE id = ?", videoId);
                return null;
            })
            .when(videoLifecycleService).blockForSubscriptionExpiry(any(), any());

        listener.processOutbox(); // first attempt — fails, but status stays PENDING

        SubscriptionLifecycleOutbox afterFirst = outboxRepository.findById(entry.getId()).orElseThrow();
        assertThat(afterFirst.getAttempts()).isEqualTo(1);
        assertThat(afterFirst.getStatus()).isEqualTo("PENDING");

        // Seed a new video so the second attempt can find it
        seedActiveReadyVideo(PLAYER_ID);

        listener.processOutbox(); // second attempt — succeeds
        SubscriptionLifecycleOutbox afterSecond = outboxRepository.findById(entry.getId()).orElseThrow();
        assertThat(afterSecond.getStatus()).isEqualTo("PROCESSED");
    }

    // ─── Dead-letter after maxAttempts ───────────────────────────────────────

    @Test
    void processOutbox_maxAttemptsReached_markedDeadLetter() {
        SubscriptionLifecycleOutbox entry = seedOutbox(PLAYER_ID, "MONTHLY");

        when(playerSubscriptionQueryPort.hasAnyActiveSubscription(PLAYER_ID)).thenReturn(false);
        doThrow(new RuntimeException("persistent error"))
            .when(videoLifecycleService).blockForSubscriptionExpiry(any(), any());

        // Need videos to trigger the exception path
        seedActiveReadyVideo(PLAYER_ID);
        listener.processOutbox(); // attempt 1

        seedActiveReadyVideo(PLAYER_ID);
        listener.processOutbox(); // attempt 2 >= maxAttempts → DEAD_LETTER

        SubscriptionLifecycleOutbox afterMax = outboxRepository.findById(entry.getId()).orElseThrow();
        assertThat(afterMax.getStatus()).isEqualTo("DEAD_LETTER");
        assertThat(afterMax.getLastError()).isNotBlank();
        assertThat(afterMax.getAttempts()).isGreaterThanOrEqualTo(2);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Video seedActiveReadyVideo(long playerId) {
        Video v = new Video();
        v.setOwnerId(String.valueOf(playerId));
        v.setProvider("bunny");
        v.setProviderAssetId("asset-" + UUID.randomUUID());
        v.setTitle("test-lifecycle.mp4");
        v.setOperationalState(OperationalState.READY);
        v.setAccessState(AccessState.ACTIVE);
        v.setVisibility(Visibility.PRIVATE);
        return videoRepository.save(v);
    }

    private Video seedBlockedReadyVideo(long playerId) {
        Video v = new Video();
        v.setOwnerId(String.valueOf(playerId));
        v.setProvider("bunny");
        v.setProviderAssetId("asset-blocked-" + UUID.randomUUID());
        v.setTitle("test-blocked.mp4");
        v.setOperationalState(OperationalState.READY);
        v.setAccessState(AccessState.BLOCKED);
        v.setVisibility(Visibility.PRIVATE);
        v.setLifecycleLockedAt(Instant.now());
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
