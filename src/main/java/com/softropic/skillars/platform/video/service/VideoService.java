package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.security.RateLimitingService;
import com.softropic.skillars.infrastructure.video.UploadCredentials;
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
import com.softropic.skillars.platform.video.contract.exception.QuotaExceededException;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.contract.exception.VideoSessionExpiredException;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
import io.micrometer.observation.annotation.Observed;
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
            reservationHandle = quotaProvider.reserve(request.ownerId(), request.fileSizeBytes());

            Instant expiresAt = Instant.now().plus(
                properties.getUpload().getSessionTtlMinutes(), ChronoUnit.MINUTES);
            final String handle = reservationHandle;

            UUID[] ids = Objects.requireNonNull(transactionTemplate.execute(status -> {
                UploadSession session = new UploadSession();
                session.setVideoId(request.videoId());
                session.setStatus(UploadSessionStatus.PENDING);
                session.setReservedBytes(request.fileSizeBytes());
                session.setReservationHandle(handle);
                session.setExpiresAt(expiresAt);
                UploadSession saved = uploadSessionRepository.save(session);
                return new UUID[]{request.videoId(), saved.getId()};
            }));

            UUID videoId = ids[0];
            UUID sessionId = ids[1];

            UploadCredentials credentials = videoProviderAdapter.initializeUpload(
                video.getTitle(), request.fileSizeBytes());

            transactionTemplate.execute(status -> {
                Video v = videoRepository.findById(videoId).orElseThrow();
                v.setProviderAssetId(credentials.providerUploadId());
                videoRepository.save(v);

                UploadSession s = uploadSessionRepository.findById(sessionId).orElseThrow();
                s.setProviderUploadId(credentials.providerUploadId());
                uploadSessionRepository.save(s);
                return null;
            });

            success = true;
            return new InitializeUploadResponse(
                videoId, sessionId,
                credentials.providerUploadId(), credentials.signedUploadUrl(),
                expiresAt);

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

        // 3. Quota availability check (no reservation yet)
        if (!quotaProvider.check(request.ownerId(), request.fileSizeBytes())) {
            throw new QuotaExceededException(request.ownerId(), 0L, request.fileSizeBytes());
        }

        String reservationHandle = null;
        boolean success = false;
        long start = System.nanoTime();
        try {
            // 4. Reserve quota
            reservationHandle = quotaProvider.reserve(request.ownerId(), request.fileSizeBytes());

            Instant expiresAt = Instant.now().plus(
                properties.getUpload().getSessionTtlMinutes(), ChronoUnit.MINUTES);
            final String handle = reservationHandle;

            // 5. Commit Video + UploadSession to DB before calling provider
            //    (AC-6: orphaned records stay if provider fails — expiry scheduler handles them in Story 2.3)
            UUID[] ids = Objects.requireNonNull(transactionTemplate.execute(status -> {
                Video video = new Video();
                video.setOwnerId(request.ownerId());
                video.setProvider(properties.getProvider());
                video.setTitle(request.fileName());
                video.setOperationalState(OperationalState.UPLOADING);
                video.setAccessState(AccessState.ACTIVE);
                video.setVisibility(Visibility.PRIVATE);
                Video savedVideo = videoRepository.save(video);

                UploadSession session = new UploadSession();
                session.setVideoId(savedVideo.getId());
                session.setStatus(UploadSessionStatus.PENDING);
                session.setReservedBytes(request.fileSizeBytes());
                session.setReservationHandle(handle);
                session.setExpiresAt(expiresAt);
                uploadSessionRepository.save(session);

                MDC.put("videoId", savedVideo.getId().toString());
                return new UUID[]{savedVideo.getId(), session.getId()};
            }));

            UUID videoId = ids[0];
            UUID sessionId = ids[1];

            // 6. Call provider — outside any transaction
            UploadCredentials credentials = videoProviderAdapter.initializeUpload(
                request.fileName(), request.fileSizeBytes());

            // 7. Persist provider IDs now that we have them
            transactionTemplate.execute(status -> {
                Video video = videoRepository.findById(videoId).orElseThrow();
                video.setProviderAssetId(credentials.providerUploadId());
                videoRepository.save(video);

                UploadSession session = uploadSessionRepository.findById(sessionId).orElseThrow();
                session.setProviderUploadId(credentials.providerUploadId());
                uploadSessionRepository.save(session);
                return null;
            });

            success = true;
            return new InitializeUploadResponse(
                videoId, sessionId,
                credentials.providerUploadId(), credentials.signedUploadUrl(),
                expiresAt);

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
}
