package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.video.contract.PlayerSubscriptionQueryPort;
import com.softropic.skillars.platform.video.repo.SubscriptionLifecycleOutbox;
import com.softropic.skillars.platform.video.repo.SubscriptionLifecycleOutboxRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-120 code review (2026-09-17, Decision 4) — {@code MAX_PAGES_PER_ENTRY} coverage.
 * No existing plain unit test file covered this class before (only the Testcontainers-backed
 * {@code VideoSubscriptionLifecycleListenerIT}); this one is deliberately narrow — just the
 * page-cap/attempt-accounting behavior, which needs neither a Spring context nor a real database.
 */
@ExtendWith(MockitoExtension.class)
class VideoSubscriptionLifecycleListenerTest {

    private static final long SUBSCRIBER_ID = 5001L;

    @Mock SubscriptionLifecycleOutboxRepository outboxRepository;
    @Mock PlayerSubscriptionQueryPort playerSubscriptionQueryPort;
    @Mock VideoRepository videoRepository;
    @Mock VideoLifecycleService videoLifecycleService;
    @Mock ConfigService configService;

    private VideoSubscriptionLifecycleListener listener;

    @BeforeEach
    void setUp() {
        listener = new VideoSubscriptionLifecycleListener(
            outboxRepository, playerSubscriptionQueryPort, videoRepository, videoLifecycleService, configService);
        lenient().when(configService.getBoundedInt(eq("platform.video.lifecycle.batch_size"), anyInt(), anyInt(), anyInt()))
            .thenReturn(100);
    }

    private SubscriptionLifecycleOutbox entry(int startingAttempts) {
        SubscriptionLifecycleOutbox entry = new SubscriptionLifecycleOutbox();
        entry.setSubscriberId(SUBSCRIBER_ID);
        entry.setSubscriptionTier("MONTHLY");
        entry.setStatus("PENDING");
        entry.setAttempts(startingAttempts);
        return entry;
    }

    /**
     * Simulates an owner whose ACTIVE/READY video count exceeds what
     * {@code MAX_PAGES_PER_ENTRY (10) x batchSize (100)} can drain in one attempt — every page
     * returns a full, non-empty batch, so the {@code do/while} in {@code processEntry} never sees an
     * empty page and can only stop via the cap.
     */
    @Test
    void processAndSaveEntry_pathBExceedsMaxPagesPerEntry_leavesEntryPendingWithoutConsumingAttempt() {
        SubscriptionLifecycleOutbox entry = entry(2); // a prior attempt was already made
        when(playerSubscriptionQueryPort.hasAnyActiveSubscription(SUBSCRIBER_ID)).thenReturn(false);
        Video video = new Video();
        video.setId(UUID.randomUUID());
        when(videoRepository.findActiveReadyByOwner(eq("5001"), anyInt())).thenReturn(List.of(video));

        listener.processAndSaveEntry(entry, 5);

        // Capped, not failed: status stays PENDING (never PROCESSED, never DEAD_LETTER) and the
        // attempts count is exactly what it was before this call — the pre-try increment must be
        // undone, or a legitimately video-heavy subscriber would dead-letter after ~5 ticks purely
        // for needing more scheduler runs, not for anything actually wrong.
        assertThat(entry.getStatus()).isEqualTo("PENDING");
        assertThat(entry.getAttempts()).isEqualTo(2);
        assertThat(entry.getLastError()).isNull();
        verify(outboxRepository).save(entry);
        // Exactly MAX_PAGES_PER_ENTRY (10) pages of 1 video each were blocked before the cap stopped
        // the loop — proves real forward progress happened, not a no-op capped attempt.
        verify(videoLifecycleService, atLeast(10)).blockForSubscriptionExpiry(any(), any());
    }

    /**
     * A page that returns fewer than the cap must still complete normally and mark PROCESSED —
     * proves the cap only engages when genuinely needed, not on every Path B entry.
     */
    @Test
    void processAndSaveEntry_pathBWithinOnePage_marksProcessedAndConsumesAttempt() {
        SubscriptionLifecycleOutbox entry = entry(0);
        when(playerSubscriptionQueryPort.hasAnyActiveSubscription(SUBSCRIBER_ID)).thenReturn(false);
        Video video = new Video();
        video.setId(UUID.randomUUID());
        when(videoRepository.findActiveReadyByOwner(eq("5001"), anyInt()))
            .thenReturn(List.of(video), List.of());

        listener.processAndSaveEntry(entry, 5);

        assertThat(entry.getStatus()).isEqualTo("PROCESSED");
        assertThat(entry.getAttempts()).isEqualTo(1);
        verify(outboxRepository).save(entry);
    }
}
