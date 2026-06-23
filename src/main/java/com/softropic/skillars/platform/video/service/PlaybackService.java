package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.PlaybackTokenClaims;
import com.softropic.skillars.infrastructure.video.SignedPlaybackUrl;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.video.config.VideoProperties;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.PlaybackAuthorizationResponse;
import com.softropic.skillars.platform.video.contract.VideoType;
import com.softropic.skillars.platform.video.contract.exception.PlaybackDeniedException;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import jakarta.annotation.Nullable;
import com.softropic.skillars.platform.video.repo.PlaybackToken;
import com.softropic.skillars.platform.video.repo.PlaybackTokenRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaybackService {

    private final VideoRepository videoRepository;
    private final PlaybackTokenRepository playbackTokenRepository;
    private final VideoProviderAdapter videoProviderAdapter;
    private final VideoProperties properties;
    private final VideoMetrics videoMetrics;
    private final ConfigService configService;

    @Observed(name = "video.playback.authorize")
    @Transactional
    public PlaybackAuthorizationResponse authorizePlayback(UUID videoId, String viewerId, @Nullable String clientIp) {
        long start = System.nanoTime();
        boolean success = false;
        MDC.put("videoId", videoId.toString());
        MDC.put("viewerId", viewerId);
        MDC.put("operation", "authorize_playback");
        try {
            Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));

            // Story 6.5 will expand access to coaches/admins via @videoAccessGuard.
            boolean ineligible =
                video.getOperationalState() == OperationalState.LOCKED
                || video.getOperationalState() == OperationalState.HIDDEN
                || video.getOperationalState() == OperationalState.DELETED
                || video.getAccessState() == AccessState.BLOCKED
                || video.getAccessState() == AccessState.ARCHIVED
                || !(video.getOperationalState() == OperationalState.READY
                     && video.getAccessState() == AccessState.ACTIVE);

            if (ineligible) {
                throw new PlaybackDeniedException(videoId, viewerId,
                    video.getOperationalState(), video.getAccessState());
            }

            int windowHours = properties.getPlayback().getRevocationWindowHours();
            if (windowHours > 0) {
                Instant windowStart = Instant.now().minus(windowHours, ChronoUnit.HOURS);
                if (playbackTokenRepository.hasRecentRevocation(viewerId, windowStart)) {
                    throw new PlaybackDeniedException(videoId, viewerId);
                }
            }

            long ttlMinutes = Math.min(
                configService.getLong("platform.video.playback.signed_url_ttl_minutes", 120L),
                properties.getPlayback().getTokenMaxTtlMinutes());
            Instant expiresAt = Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES);

            boolean ipBindingEnabled = configService.getBoolean("platform.video.playback.ip_binding_enabled", false);
            String boundIp = ipBindingEnabled ? clientIp : null;

            SignedPlaybackUrl signedUrl = videoProviderAdapter.generatePlaybackUrl(
                video.getProviderAssetId(),
                new PlaybackTokenClaims(viewerId, expiresAt, boundIp));

            // Generate download URL for owner of downloadable video types (HOMEWORK, DRILL_DEMO).
            // COACH_REVIEW: no download URL per FR-VID-009 — only signedHlsUrl is returned.
            // See AC 4 note: confirm DRILL_DEMO ownership model with platform.session before enabling.
            String downloadUrl = null;
            if (video.getVideoType() != VideoType.COACH_REVIEW && viewerId.equals(video.getOwnerId())) {
                downloadUrl = videoProviderAdapter
                    .generateDownloadUrl(video.getProviderAssetId(), new PlaybackTokenClaims(viewerId, expiresAt, boundIp))
                    .orElse(null);
            }

            // Retain playback token write: revocation window check still works, and tokens serve as audit trail.
            PlaybackToken token = new PlaybackToken();
            token.setVideoId(videoId);
            token.setViewerId(viewerId);
            token.setExpiresAt(expiresAt);
            PlaybackToken saved = playbackTokenRepository.save(token);

            String jwt = buildJwt(saved.getId(), videoId, viewerId, expiresAt);

            log.debug("Playback token issued: tokenId={}", saved.getId());
            success = true;
            return new PlaybackAuthorizationResponse(jwt, signedUrl.url(), expiresAt, downloadUrl);
        } finally {
            videoMetrics.recordPlaybackAuthorizeLatency(success ? "success" : "error", System.nanoTime() - start);
            MDC.remove("videoId");
            MDC.remove("viewerId");
            MDC.remove("operation");
        }
    }

    @Observed(name = "video.playback.authorize")
    @Transactional
    public PlaybackAuthorizationResponse authorizePlayback(UUID videoId, String viewerId) {
        return authorizePlayback(videoId, viewerId, null);
    }

    @Observed(name = "video.playback.revokeTokens")
    @Transactional
    public int revokeTokensForViewer(String viewerId) {
        MDC.put("viewerId", viewerId);
        MDC.put("operation", "revoke_tokens");
        try {
            Instant now = Instant.now();
            int count = playbackTokenRepository.revokeActiveTokensForViewer(viewerId, now, now);
            log.info("Revoked {} active playback token(s) for viewer", count);
            return count;
        } finally {
            MDC.remove("viewerId");
            MDC.remove("operation");
        }
    }

    @Observed(name = "video.playback.validateToken")
    public PlaybackToken validateToken(UUID tokenId) {
        MDC.put("operation", "validate_token");
        try {
            PlaybackToken token = playbackTokenRepository.findById(tokenId)
                .orElseThrow(() -> new PlaybackDeniedException(tokenId, "unknown"));
            if (token.getRevokedAt() != null) {
                throw new PlaybackDeniedException(token.getVideoId(), token.getViewerId());
            }
            if (token.getExpiresAt().isBefore(Instant.now())) {
                throw new PlaybackDeniedException(token.getVideoId(), token.getViewerId());
            }
            return token;
        } finally {
            MDC.remove("operation");
        }
    }

    private String buildJwt(UUID tokenId, UUID videoId, String viewerId, Instant expiresAt) {
        SecretKey key = Keys.hmacShaKeyFor(
            Base64.getDecoder().decode(properties.getPlayback().getSigningSecret()));
        return Jwts.builder()
            .id(tokenId.toString())
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(expiresAt))
            .subject(viewerId)
            .claim("vid", videoId.toString())
            .signWith(key)
            .compact();
    }
}
