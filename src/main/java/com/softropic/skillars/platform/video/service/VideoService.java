package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.security.RateLimitingService;
import com.softropic.skillars.infrastructure.video.UploadCredentials;
import com.softropic.skillars.infrastructure.video.VideoMetadata;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.config.VideoProperties;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.InitializeUploadRequest;
import com.softropic.skillars.platform.video.contract.InitializeUploadResponse;
import com.softropic.skillars.platform.video.contract.RetryUploadRequest;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.QuotaProvider;
import com.softropic.skillars.platform.video.contract.UploadSessionStatus;
import com.softropic.skillars.platform.video.contract.UploadValidationRequest;
import com.softropic.skillars.platform.video.contract.Visibility;
import com.softropic.skillars.platform.video.contract.ConfirmUploadResponse;
import com.softropic.skillars.platform.video.contract.event.VideoPublishedEvent;
import com.softropic.skillars.platform.video.contract.event.VideoUploadedEvent;
import com.softropic.skillars.platform.video.contract.exception.QuotaExceededException;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.contract.exception.VideoSessionExpiredException;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import com.softropic.skillars.platform.video.repo.UploadSession;
import com.softropic.skillars.platform.video.repo.UploadSessionRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoService {

    private final VideoValidationChain validationChain;
    private final QuotaProvider quotaProvider;
    private final VideoProviderAdapter videoProviderAdapter;
    private final VideoRepository videoRepository;
    private final UploadSessionRepository uploadSessionRepository;
    private final VideoProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final RateLimitingService rateLimitingService;
    private final VideoMetrics videoMetrics;
    private final VideoLifecycleService videoLifecycleService;
    private final ApplicationEventPublisher publisher;
    private final VideoTypeConstraints videoTypeConstraints;

    @Transactional(readOnly = true)
    public Video findById(UUID videoId) {
        return videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));
    }

    @Observed(name = "video.upload.confirm")
    @Transactional
    public ConfirmUploadResponse confirmUpload(UUID videoId) {
        MDC.put("operation", "confirm_upload");
        MDC.put("videoId", videoId.toString());
        long start = System.nanoTime();
        boolean success = false;
        try {
            Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));

            MDC.put("provider", video.getProvider());

            UploadSession session = uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)
                .orElseThrow(() -> new VideoValidationException("No active upload session exists for this video"));

            if (session.getStatus() == UploadSessionStatus.COMMITTED) {
                success = true;
                return new ConfirmUploadResponse(videoId, video.getOperationalState());
            }

            if (session.getStatus() == UploadSessionStatus.EXPIRED) {
                throw new VideoSessionExpiredException(session.getId());
            }

            session.setStatus(UploadSessionStatus.COMMITTED);
            uploadSessionRepository.save(session);

            video.setOperationalState(OperationalState.PROCESSING);
            videoRepository.save(video);

            quotaProvider.commit(session.getReservationHandle());

            success = true;
            return new ConfirmUploadResponse(videoId, OperationalState.PROCESSING);
        } finally {
            videoMetrics.recordUploadConfirmLatency(success ? "success" : "error", System.nanoTime() - start);
            MDC.remove("operation");
            MDC.remove("videoId");
            MDC.remove("provider");
        }
    }

    @Observed(name = "video.upload.retry")
    public InitializeUploadResponse retryUpload(RetryUploadRequest request) {
        MDC.put("operation", "retry_upload");
        MDC.put("ownerId", request.ownerId());
        MDC.put("videoId", request.videoId().toString());
        MDC.put("provider", properties.getProvider());

        Video video = videoRepository.findById(request.videoId())
            .orElseThrow(() -> new VideoNotFoundException(request.videoId()));

        if (video.getOperationalState() != OperationalState.FAILED) {
            throw new VideoValidationException("Retry is only permitted for videos in FAILED state");
        }

        // Enforce video-type-specific size limits on retry
        if (video.getVideoType() != null) {
            videoTypeConstraints.validate(video.getVideoType(), request.fileSizeBytes(), 0);
        }

        String ext = video.getTitle().contains(".")
            ? video.getTitle().substring(video.getTitle().lastIndexOf('.') + 1)
            : "";
        validationChain.validate(
            new UploadValidationRequest(video.getTitle(), request.fileSizeBytes(), null, ext.toUpperCase()));

        if (!quotaProvider.check(request.ownerId(), request.fileSizeBytes())) {
            throw new QuotaExceededException(request.ownerId(), 0L, request.fileSizeBytes());
        }

        String reservationHandle = null;
        boolean success = false;
        long start = System.nanoTime();
        try {
            reservationHandle = quotaProvider.reserve(request.ownerId(), request.fileSizeBytes(),
                video.getVideoType());

            final String handle = reservationHandle;

            UUID[] ids = Objects.requireNonNull(transactionTemplate.execute(status -> {
                UploadSession session = new UploadSession();
                session.setVideoId(request.videoId());
                session.setStatus(UploadSessionStatus.PENDING);
                session.setReservedBytes(request.fileSizeBytes());
                session.setReservationHandle(handle);
                // Placeholder to satisfy NOT NULL constraint; overwritten in second TX with
                // the exact credential expiry from the provider response
                session.setExpiresAt(Instant.now().plus(
                    properties.getUpload().getSessionTtlMinutes(), ChronoUnit.MINUTES));
                UploadSession saved = uploadSessionRepository.save(session);
                return new UUID[]{request.videoId(), saved.getId()};
            }));

            UUID videoId = ids[0];
            UUID sessionId = ids[1];

            UploadCredentials credentials = videoProviderAdapter.initializeUpload(
                video.getTitle(), request.fileSizeBytes());

            // expiresAt derived from TUS credential expiry to avoid clock-drift between the two values
            Instant expiresAt = Instant.ofEpochSecond(credentials.tusAuthorizationExpire());

            transactionTemplate.execute(status -> {
                Video v = videoRepository.findById(videoId).orElseThrow();
                v.setProviderAssetId(credentials.providerUploadId());
                videoRepository.save(v);

                UploadSession s = uploadSessionRepository.findById(sessionId).orElseThrow();
                s.setProviderUploadId(credentials.providerUploadId());
                s.setExpiresAt(expiresAt);
                uploadSessionRepository.save(s);
                return null;
            });

            success = true;
            return new InitializeUploadResponse(
                videoId, sessionId,
                credentials.providerUploadId(), credentials.signedUploadUrl(),
                expiresAt,
                credentials.tusAuthorizationSignature(),
                credentials.tusAuthorizationExpire(),
                credentials.tusLibraryId());

        } finally {
            if (!success && reservationHandle != null) {
                try {
                    quotaProvider.release(reservationHandle);
                } catch (Exception e) {
                    log.warn("Failed to release quota reservation after retry failure", e);
                }
            }
            videoMetrics.recordUploadInitLatency(properties.getProvider(), success ? "success" : "error", System.nanoTime() - start);
            MDC.remove("operation");
            MDC.remove("ownerId");
            MDC.remove("videoId");
            MDC.remove("provider");
        }
    }

    @Observed(name = "video.upload.init")
    public InitializeUploadResponse initializeUpload(InitializeUploadRequest request) {
        MDC.put("operation", "initialize_upload");
        MDC.put("ownerId", request.ownerId());
        MDC.put("provider", properties.getProvider());

        // 1. Rate limit check — per ownerId, before any other work
        int rpm = properties.getUpload().getRateLimit().getRequestsPerMinute();
        if (!rateLimitingService.tryConsume(request.ownerId(), "video.upload.init", rpm, 1, TimeUnit.MINUTES)) {
            throw new QuotaExceededException(request.ownerId(), "rate limit exceeded");
        }

        // 2. Validate file metadata
        String ext = request.fileName().contains(".")
            ? request.fileName().substring(request.fileName().lastIndexOf('.') + 1)
            : "";
        validationChain.validate(
            new UploadValidationRequest(request.fileName(), request.fileSizeBytes(),
                                        request.mimeType(), ext.toUpperCase()));

        // 3. Enforce video-type-specific size limits (e.g., COACH_REVIEW ≤ 1 GB, HOMEWORK ≤ 250 MB)
        if (request.videoType() != null) {
            videoTypeConstraints.validate(request.videoType(), request.fileSizeBytes(), 0);
        }

        // 4. Quota availability check (no reservation yet)
        if (!quotaProvider.check(request.ownerId(), request.fileSizeBytes())) {
            throw new QuotaExceededException(request.ownerId(), 0L, request.fileSizeBytes());
        }

        String reservationHandle = null;
        boolean success = false;
        long start = System.nanoTime();
        try {
            // 5. Reserve quota with videoType
            reservationHandle = quotaProvider.reserve(request.ownerId(), request.fileSizeBytes(),
                request.videoType());

            final String handle = reservationHandle;

            // 6. Commit Video + UploadSession to DB before calling provider
            UUID[] ids = Objects.requireNonNull(transactionTemplate.execute(status -> {
                Video video = new Video();
                video.setOwnerId(request.ownerId());
                video.setProvider(properties.getProvider());
                video.setTitle(request.fileName());
                video.setOperationalState(OperationalState.UPLOADING);
                video.setAccessState(AccessState.ACTIVE);
                video.setVisibility(Visibility.PRIVATE);
                video.setVideoType(request.videoType());
                Video savedVideo = videoRepository.save(video);

                UploadSession session = new UploadSession();
                session.setVideoId(savedVideo.getId());
                session.setStatus(UploadSessionStatus.PENDING);
                session.setReservedBytes(request.fileSizeBytes());
                session.setReservationHandle(handle);
                // Placeholder to satisfy NOT NULL constraint; overwritten in second TX with
                // the exact credential expiry from the provider response
                session.setExpiresAt(Instant.now().plus(
                    properties.getUpload().getSessionTtlMinutes(), ChronoUnit.MINUTES));
                uploadSessionRepository.save(session);

                MDC.put("videoId", savedVideo.getId().toString());
                return new UUID[]{savedVideo.getId(), session.getId()};
            }));

            UUID videoId = ids[0];
            UUID sessionId = ids[1];

            // 7. Call provider — outside any transaction
            UploadCredentials credentials = videoProviderAdapter.initializeUpload(
                request.fileName(), request.fileSizeBytes());

            // expiresAt derived from TUS credential expiry to avoid drift between two separate Instant.now() calls
            Instant expiresAt = Instant.ofEpochSecond(credentials.tusAuthorizationExpire());

            // 8. Persist provider IDs now that we have them
            transactionTemplate.execute(status -> {
                Video video = videoRepository.findById(videoId).orElseThrow();
                video.setProviderAssetId(credentials.providerUploadId());
                videoRepository.save(video);

                UploadSession session = uploadSessionRepository.findById(sessionId).orElseThrow();
                session.setProviderUploadId(credentials.providerUploadId());
                session.setExpiresAt(expiresAt);
                uploadSessionRepository.save(session);
                return null;
            });

            success = true;
            return new InitializeUploadResponse(
                videoId, sessionId,
                credentials.providerUploadId(), credentials.signedUploadUrl(),
                expiresAt,
                credentials.tusAuthorizationSignature(),
                credentials.tusAuthorizationExpire(),
                credentials.tusLibraryId());

        } finally {
            if (!success && reservationHandle != null) {
                try {
                    quotaProvider.release(reservationHandle);
                } catch (Exception e) {
                    log.warn("Failed to release quota reservation after failure", e);
                }
            }
            videoMetrics.recordUploadInitLatency(properties.getProvider(), success ? "success" : "error", System.nanoTime() - start);
            MDC.remove("operation");
            MDC.remove("ownerId");
            MDC.remove("videoId");
            MDC.remove("provider");
        }
    }

    @Observed(name = "video.transcoding.complete")
    public void completeTranscoding(UUID videoId) {
        // Phase 1: read providerAssetId in a short transaction, release the connection immediately
        String providerAssetId = transactionTemplate.execute(status ->
            videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId))
                .getProviderAssetId());

        // Phase 2: fetch metadata from Bunny with NO active DB transaction.
        // Avoids holding a connection from the pool during the 30-second RestTemplate read timeout.
        VideoMetadata meta = null;
        if (providerAssetId != null) {
            try {
                meta = videoProviderAdapter.getVideoMetadata(providerAssetId);
            } catch (Exception e) {
                log.warn("Could not fetch metadata from provider for videoId={}: {}", videoId, e.getMessage());
                // Non-fatal: video still advances to READY; metadata can be reconciled later
            }
        }

        // Phase 3: write in a single transaction — metadata, state transition, quota commit, event
        // IMPORTANT: VideoLifecycleService.transitionOperationalState() uses findById()+save(entity),
        // which reuses the L1-cached entity. The durationMs/storageBytes mutations above are flushed
        // when the transaction commits via JPA dirty-checking. Verified: transitionOperationalState()
        // calls findById()+save(), NOT a @Modifying @Query.
        final VideoMetadata finalMeta = meta;
        transactionTemplate.execute(status -> {
            Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));
            if (finalMeta != null) {
                if (finalMeta.durationMs() > 0) video.setDurationMs(finalMeta.durationMs());
                if (finalMeta.storageBytes() > 0) video.setStorageBytes(finalMeta.storageBytes());
            }
            videoRepository.save(video); // explicit save so mutations persist before lifecycle transition
            videoLifecycleService.transitionOperationalState(videoId, OperationalState.READY);

            // Anchor to providerAssetId so retries don't cause the wrong session's handle to be committed
            UploadSession session = (providerAssetId != null)
                ? uploadSessionRepository.findFirstByVideoIdAndProviderUploadIdOrderByCreatedAtDesc(videoId, providerAssetId).orElse(null)
                : uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId).orElse(null);
            if (session != null && session.getReservationHandle() != null) {
                quotaProvider.commit(session.getReservationHandle());
            } else {
                log.warn("No reservation handle found for videoId={} during transcoding commit — quota not committed", videoId);
            }
            // Publish inside the TX so @TransactionalEventListener(AFTER_COMMIT) fires after commit
            // Note: VideoUploadedEvent is intentionally NOT published here — it would re-trigger the
            // moderation pipeline for an already-READY video, causing TerminalStateViolationException.
            publisher.publishEvent(new VideoPublishedEvent(videoId, video.getOwnerId()));
            return null;
        });
    }

    @Observed(name = "video.transcoding.failed")
    @Transactional
    public void failTranscoding(UUID videoId) {
        // Read providerAssetId before state transition so we can anchor session lookup below
        String providerAssetId = videoRepository.findById(videoId)
            .map(Video::getProviderAssetId)
            .orElse(null);

        videoLifecycleService.transitionOperationalState(videoId, OperationalState.FAILED);

        // Anchor to providerAssetId to avoid releasing the retry session's quota when a late
        // webhook fires for the original failed upload after a retryUpload() has started
        UploadSession session = (providerAssetId != null)
            ? uploadSessionRepository.findFirstByVideoIdAndProviderUploadIdOrderByCreatedAtDesc(videoId, providerAssetId).orElse(null)
            : uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId).orElse(null);
        if (session != null && session.getReservationHandle() != null) {
            quotaProvider.release(session.getReservationHandle());
        } else {
            log.warn("No reservation handle found for videoId={} during transcoding failure — quota not released", videoId);
        }
    }
}
