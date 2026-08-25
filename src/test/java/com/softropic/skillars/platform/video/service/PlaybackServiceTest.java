package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.infrastructure.video.PlaybackTokenClaims;
import com.softropic.skillars.infrastructure.video.SignedPlaybackUrl;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.video.config.VideoProperties;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.exception.PlaybackDeniedException;
import com.softropic.skillars.platform.video.repo.PlaybackToken;
import com.softropic.skillars.platform.video.repo.PlaybackTokenRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// skillars-deferred-40 AC4: targeted coverage of the new incrementBandwidthUsedBytes call site,
// not a full re-test of authorizePlayback's existing behavior (see PlaybackRevocationWindowUnitTest).
@ExtendWith(MockitoExtension.class)
class PlaybackServiceTest {

    @Mock VideoRepository videoRepository;
    @Mock PlaybackTokenRepository playbackTokenRepository;
    @Mock VideoProviderAdapter videoProviderAdapter;
    @Mock VideoMetrics videoMetrics;
    @Mock ConfigService configService;
    @Mock QuotaService quotaService;
    @Mock PessimisticLockRetryer lockRetryer;
    @Mock EntityManager entityManager;

    PlaybackService playbackService;

    @BeforeEach
    void setUp() {
        VideoProperties properties = new VideoProperties();
        properties.getPlayback().setRevocationWindowHours(0);
        properties.getPlayback().setSigningSecret("dGVzdC1wbGF5YmFjay1zaWduaW5nLXNlY3JldC0zMi1ieXRlcyEh");

        // Deferred-64 AC3: withBoundedRetry's Supplier is executed for real, mirroring every other
        // lockRetryer stub in this codebase — entityManager.refresh is a no-op mock, same reasoning
        // as BookingServiceTest/RescheduleServiceTest's own entityManager mock.
        lenient().when(lockRetryer.withBoundedRetry(any()))
            .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());

        playbackService = new PlaybackService(videoRepository, playbackTokenRepository, videoProviderAdapter, properties, videoMetrics, configService, quotaService, lockRetryer, entityManager);
    }

    @Test
    void authorizePlayback_success_incrementsBandwidthByOwnerIdAndStorageBytes() {
        UUID videoId = UUID.randomUUID();
        String viewerId = "viewer-1";
        String ownerId = "owner-1";
        long storageBytes = 123_456L;

        Video video = new Video();
        video.setOwnerId(ownerId);
        video.setOperationalState(OperationalState.READY);
        video.setAccessState(AccessState.ACTIVE);
        video.setProviderAssetId("bunny-asset-1");
        video.setStorageBytes(storageBytes);

        PlaybackToken savedToken = new PlaybackToken();
        savedToken.setId(UUID.randomUUID());
        savedToken.setVideoId(videoId);
        savedToken.setViewerId(viewerId);
        savedToken.setExpiresAt(Instant.now().plusSeconds(900));

        when(configService.getLong("platform.video.playback.signed_url_ttl_minutes", 120L)).thenReturn(120L);
        when(configService.getBoolean("platform.video.playback.ip_binding_enabled", false)).thenReturn(false);
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        when(videoRepository.findByIdForUpdate(videoId)).thenReturn(Optional.of(video));
        when(videoProviderAdapter.generatePlaybackUrl(anyString(), any(PlaybackTokenClaims.class)))
            .thenReturn(new SignedPlaybackUrl("https://cdn/playlist.m3u8?token=test", Instant.now().plusSeconds(900)));
        when(playbackTokenRepository.save(any(PlaybackToken.class))).thenReturn(savedToken);

        var response = playbackService.authorizePlayback(videoId, viewerId);

        assertThat(response.token()).isNotBlank();
        verify(quotaService).incrementBandwidthUsedBytes(ownerId, storageBytes);
    }

    @Test
    void authorizePlayback_ineligibleVideoState_doesNotIncrementBandwidth() {
        UUID videoId = UUID.randomUUID();
        String viewerId = "viewer-1";

        Video video = new Video();
        video.setOwnerId("owner-1");
        video.setOperationalState(OperationalState.HIDDEN);
        video.setAccessState(AccessState.ACTIVE);
        video.setProviderAssetId("bunny-asset-1");
        video.setStorageBytes(123_456L);

        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));

        assertThatThrownBy(() -> playbackService.authorizePlayback(videoId, viewerId))
            .isInstanceOf(PlaybackDeniedException.class);

        verify(quotaService, never()).incrementBandwidthUsedBytes(anyString(), anyLong());
    }

    @Test
    void authorizePlayback_nullStorageBytes_doesNotIncrementBandwidth() {
        UUID videoId = UUID.randomUUID();
        String viewerId = "viewer-1";
        String ownerId = "owner-1";

        Video video = new Video();
        video.setOwnerId(ownerId);
        video.setOperationalState(OperationalState.READY);
        video.setAccessState(AccessState.ACTIVE);
        video.setProviderAssetId("bunny-asset-1");
        video.setStorageBytes(null);

        PlaybackToken savedToken = new PlaybackToken();
        savedToken.setId(UUID.randomUUID());
        savedToken.setVideoId(videoId);
        savedToken.setViewerId(viewerId);
        savedToken.setExpiresAt(Instant.now().plusSeconds(900));

        when(configService.getLong("platform.video.playback.signed_url_ttl_minutes", 120L)).thenReturn(120L);
        when(configService.getBoolean("platform.video.playback.ip_binding_enabled", false)).thenReturn(false);
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        when(videoRepository.findByIdForUpdate(videoId)).thenReturn(Optional.of(video));
        when(videoProviderAdapter.generatePlaybackUrl(anyString(), any(PlaybackTokenClaims.class)))
            .thenReturn(new SignedPlaybackUrl("https://cdn/playlist.m3u8?token=test", Instant.now().plusSeconds(900)));
        when(playbackTokenRepository.save(any(PlaybackToken.class))).thenReturn(savedToken);

        playbackService.authorizePlayback(videoId, viewerId);

        verify(quotaService, never()).incrementBandwidthUsedBytes(anyString(), anyLong());
    }

    // ── Deferred-63 AC3: bandwidth dedup per viewer+video+time-bucket ─────────────

    @Test
    void authorizePlayback_activeTokenExistsForSameViewerAndVideo_skipsBandwidthChargeButStillAuthorizes() {
        UUID videoId = UUID.randomUUID();
        String viewerId = "viewer-1";
        String ownerId = "owner-1";
        long storageBytes = 123_456L;

        Video video = new Video();
        video.setOwnerId(ownerId);
        video.setOperationalState(OperationalState.READY);
        video.setAccessState(AccessState.ACTIVE);
        video.setProviderAssetId("bunny-asset-1");
        video.setStorageBytes(storageBytes);

        PlaybackToken savedToken = new PlaybackToken();
        savedToken.setId(UUID.randomUUID());
        savedToken.setVideoId(videoId);
        savedToken.setViewerId(viewerId);
        savedToken.setExpiresAt(Instant.now().plusSeconds(900));

        when(configService.getLong("platform.video.playback.signed_url_ttl_minutes", 120L)).thenReturn(120L);
        when(configService.getBoolean("platform.video.playback.ip_binding_enabled", false)).thenReturn(false);
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        when(videoRepository.findByIdForUpdate(videoId)).thenReturn(Optional.of(video));
        when(videoProviderAdapter.generatePlaybackUrl(anyString(), any(PlaybackTokenClaims.class)))
            .thenReturn(new SignedPlaybackUrl("https://cdn/playlist.m3u8?token=test", Instant.now().plusSeconds(900)));
        when(playbackTokenRepository.existsActiveForViewerAndVideo(eq(viewerId), eq(videoId), any(Instant.class)))
            .thenReturn(true);
        when(playbackTokenRepository.save(any(PlaybackToken.class))).thenReturn(savedToken);

        var response = playbackService.authorizePlayback(videoId, viewerId);

        assertThat(response.token()).isNotBlank();
        verify(quotaService, never()).incrementBandwidthUsedBytes(anyString(), anyLong());
        // Dedup only skips the bandwidth charge — playback is still authorized and a fresh token issued.
        verify(playbackTokenRepository).save(any(PlaybackToken.class));
    }

    @Test
    void authorizePlayback_noActiveTokenForThisViewerAndVideo_chargesBandwidthNormally() {
        // Covers both "a distinct viewer of the same video still charges independently" and
        // "charging resumes once the prior token has expired/been revoked" — both collapse to the
        // repository query returning false, exactly what it is designed to do once no token is both
        // active (unexpired, unrevoked) and owned by this specific viewer+video pair.
        UUID videoId = UUID.randomUUID();
        String viewerId = "viewer-2";
        String ownerId = "owner-1";
        long storageBytes = 55_000L;

        Video video = new Video();
        video.setOwnerId(ownerId);
        video.setOperationalState(OperationalState.READY);
        video.setAccessState(AccessState.ACTIVE);
        video.setProviderAssetId("bunny-asset-1");
        video.setStorageBytes(storageBytes);

        PlaybackToken savedToken = new PlaybackToken();
        savedToken.setId(UUID.randomUUID());
        savedToken.setVideoId(videoId);
        savedToken.setViewerId(viewerId);
        savedToken.setExpiresAt(Instant.now().plusSeconds(900));

        when(configService.getLong("platform.video.playback.signed_url_ttl_minutes", 120L)).thenReturn(120L);
        when(configService.getBoolean("platform.video.playback.ip_binding_enabled", false)).thenReturn(false);
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        when(videoRepository.findByIdForUpdate(videoId)).thenReturn(Optional.of(video));
        when(videoProviderAdapter.generatePlaybackUrl(anyString(), any(PlaybackTokenClaims.class)))
            .thenReturn(new SignedPlaybackUrl("https://cdn/playlist.m3u8?token=test", Instant.now().plusSeconds(900)));
        when(playbackTokenRepository.existsActiveForViewerAndVideo(eq(viewerId), eq(videoId), any(Instant.class)))
            .thenReturn(false);
        when(playbackTokenRepository.save(any(PlaybackToken.class))).thenReturn(savedToken);

        playbackService.authorizePlayback(videoId, viewerId);

        verify(quotaService).incrementBandwidthUsedBytes(ownerId, storageBytes);
    }
}
