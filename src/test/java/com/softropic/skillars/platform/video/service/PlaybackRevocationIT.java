package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.PlaybackTokenClaims;
import com.softropic.skillars.infrastructure.video.SignedPlaybackUrl;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.Visibility;
import com.softropic.skillars.platform.video.contract.exception.PlaybackDeniedException;
import com.softropic.skillars.platform.video.repo.PlaybackToken;
import com.softropic.skillars.platform.video.repo.PlaybackTokenRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = "app.video.playback.revocation-window-hours=24")
class PlaybackRevocationIT extends BaseVideoIT {

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    @Autowired
    PlaybackService playbackService;

    @Autowired
    VideoRepository videoRepository;

    @Autowired
    PlaybackTokenRepository playbackTokenRepository;

    @BeforeEach
    void setUp() {
        playbackTokenRepository.deleteAll();
        videoRepository.deleteAll();
        when(videoProviderAdapter.generatePlaybackUrl(anyString(), any(PlaybackTokenClaims.class)))
            .thenReturn(new SignedPlaybackUrl("https://bunny-cdn/asset/playlist.m3u8?token=test",
                Instant.now().plusSeconds(900)));
    }

    // --- revokeTokensForViewer ---

    @Test
    void revokeTokensForViewer_revokesActiveNonExpiredTokens() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE);

        playbackService.authorizePlayback(video.getId(), "viewer-A");
        playbackService.authorizePlayback(video.getId(), "viewer-A");

        int count = playbackService.revokeTokensForViewer("viewer-A");

        assertThat(count).isEqualTo(2);
        playbackTokenRepository.findAll().forEach(t ->
            assertThat(t.getRevokedAt()).isNotNull());
    }

    @Test
    void revokeTokensForViewer_doesNotRevokeAlreadyExpiredTokens() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE);

        PlaybackToken expired = new PlaybackToken();
        expired.setVideoId(video.getId());
        expired.setViewerId("viewer-B");
        expired.setExpiresAt(Instant.now().minusSeconds(3600));
        playbackTokenRepository.save(expired);

        int count = playbackService.revokeTokensForViewer("viewer-B");

        assertThat(count).isEqualTo(0);
        PlaybackToken check = playbackTokenRepository.findAll().get(0);
        assertThat(check.getRevokedAt()).isNull();
    }

    @Test
    void revokeTokensForViewer_idempotent_alreadyRevokedNotDoubleUpdated() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE);
        playbackService.authorizePlayback(video.getId(), "viewer-C");

        int first = playbackService.revokeTokensForViewer("viewer-C");
        int second = playbackService.revokeTokensForViewer("viewer-C");

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0);
    }

    // --- validateToken ---

    @Test
    void validateToken_validToken_returnsMetadata() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE);
        playbackService.authorizePlayback(video.getId(), "viewer-D");

        PlaybackToken saved = playbackTokenRepository.findAll().get(0);
        PlaybackToken result = playbackService.validateToken(saved.getId());

        assertThat(result.getViewerId()).isEqualTo("viewer-D");
        assertThat(result.getVideoId()).isEqualTo(video.getId());
        assertThat(result.getRevokedAt()).isNull();
    }

    @Test
    void validateToken_revokedToken_throwsPlaybackDenied() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE);
        playbackService.authorizePlayback(video.getId(), "viewer-E");
        playbackService.revokeTokensForViewer("viewer-E");

        PlaybackToken revoked = playbackTokenRepository.findAll().get(0);
        assertThatThrownBy(() -> playbackService.validateToken(revoked.getId()))
            .isInstanceOf(PlaybackDeniedException.class);
    }

    @Test
    void validateToken_expiredToken_throwsPlaybackDenied() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE);

        PlaybackToken expired = new PlaybackToken();
        expired.setVideoId(video.getId());
        expired.setViewerId("viewer-F");
        expired.setExpiresAt(Instant.now().minusSeconds(1));
        PlaybackToken saved = playbackTokenRepository.save(expired);

        assertThatThrownBy(() -> playbackService.validateToken(saved.getId()))
            .isInstanceOf(PlaybackDeniedException.class);
    }

    @Test
    void validateToken_unknownTokenId_throwsPlaybackDenied() {
        assertThatThrownBy(() -> playbackService.validateToken(UUID.randomUUID()))
            .isInstanceOf(PlaybackDeniedException.class);
    }

    // --- authorizePlayback + revocation window ---

    @Test
    void authorizePlayback_viewerWithRecentRevocation_throwsPlaybackDenied() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE);

        playbackService.authorizePlayback(video.getId(), "viewer-G");
        playbackService.revokeTokensForViewer("viewer-G");

        assertThatThrownBy(() -> playbackService.authorizePlayback(video.getId(), "viewer-G"))
            .isInstanceOf(PlaybackDeniedException.class);
    }

    @Test
    void authorizePlayback_revocationWindowElapsed_allowsNewToken() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE);

        // Seed a token with revokedAt set to 25 hours ago (outside the 24-hour window)
        PlaybackToken oldRevoked = new PlaybackToken();
        oldRevoked.setVideoId(video.getId());
        oldRevoked.setViewerId("viewer-H");
        oldRevoked.setExpiresAt(Instant.now().minusSeconds(3600));
        oldRevoked.setRevokedAt(Instant.now().minusSeconds(25 * 3600));
        playbackTokenRepository.save(oldRevoked);

        // Should succeed — revocation is outside the window
        var response = playbackService.authorizePlayback(video.getId(), "viewer-H");

        assertThat(response.token()).isNotBlank();
        assertThat(response.playbackUrl()).contains("playlist.m3u8");
    }

    private Video seedVideo(OperationalState opState, AccessState accessState) {
        Video video = new Video();
        video.setOwnerId("owner-revocation");
        video.setProvider("bunny");
        video.setProviderAssetId("bunny-asset-revoke-123");
        video.setTitle("test-revocation.mp4");
        video.setOperationalState(opState);
        video.setAccessState(accessState);
        video.setVisibility(Visibility.PRIVATE);
        return videoRepository.save(video);
    }
}
