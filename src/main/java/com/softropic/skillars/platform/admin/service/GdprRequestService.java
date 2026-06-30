package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.admin.contract.AdminGdprRequestDto;
import com.softropic.skillars.platform.admin.contract.GdprErasureRequestedEvent;
import com.softropic.skillars.platform.admin.contract.GdprExportRequestedEvent;
import com.softropic.skillars.platform.admin.contract.GdprExportStatusResponse;
import com.softropic.skillars.platform.admin.repo.GdprRequest;
import com.softropic.skillars.platform.admin.repo.GdprRequestRepository;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class GdprRequestService {

    private static final Set<String> VALID_TYPES    = Set.of("EXPORT", "ERASURE");
    private static final Set<String> VALID_STATUSES = Set.of("PENDING", "PROCESSING", "COMPLETED", "FAILED");

    private final GdprRequestRepository gdprRequestRepository;
    private final BookingRepository bookingRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public UUID requestExport(Long userId) {
        if (gdprRequestRepository.existsByUserIdAndRequestTypeAndStatusIn(
                userId, "EXPORT", List.of("PENDING", "PROCESSING"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "gdpr.requestAlreadyPending");
        }
        GdprRequest request = gdprRequestRepository.save(new GdprRequest(userId, "EXPORT", "PENDING"));
        eventPublisher.publishEvent(new GdprExportRequestedEvent(this, request.getId(), userId));
        log.info("[GDPR_EXPORT_REQUESTED] requestId={} userId={}", request.getId(), userId);
        return request.getId();
    }

    @Transactional(readOnly = true)
    public ResponseEntity<?> getExportStatus(UUID requestId, Long userId) {
        GdprRequest request = gdprRequestRepository.findById(requestId)
            .orElseThrow(() -> new ResourceNotFoundException("GdprRequest", requestId.toString()));

        if (!request.getUserId().equals(userId)) {
            throw new OperationNotAllowedException(
                "Cannot access another user's GDPR request", SecurityError.MISSING_RIGHTS);
        }

        if ("COMPLETED".equals(request.getStatus())) {
            if (request.getExpiresAt() == null || !request.getExpiresAt().isAfter(Instant.now())) {
                throw new ResponseStatusException(HttpStatus.GONE, "gdpr.exportExpired");
            }
            String downloadUrl = request.getDownloadUrl();
            if (downloadUrl == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "gdpr.exportUrlMissing");
            }
            return ResponseEntity.status(302).location(URI.create(downloadUrl)).build();
        }

        return ResponseEntity.ok(new GdprExportStatusResponse(requestId, request.getStatus()));
    }

    @Transactional
    public UUID requestErasure(Long userId) {
        if (gdprRequestRepository.existsByUserIdAndRequestTypeAndStatusIn(
                userId, "ERASURE", List.of("PENDING", "PROCESSING"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "gdpr.requestAlreadyPending");
        }
        if (gdprRequestRepository.existsByUserIdAndRequestTypeAndStatusIn(
                userId, "EXPORT", List.of("PENDING", "PROCESSING"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "gdpr.exportInProgress");
        }

        coachProfileRepository.findByUserId(userId).ifPresent(cp -> {
            if (bookingRepository.existsByCoachIdAndStatusIn(cp.getId(), List.of("REQUESTED", "ACCEPTED"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "gdpr.activeBookingsExist");
            }
        });

        GdprRequest request = gdprRequestRepository.save(new GdprRequest(userId, "ERASURE", "PENDING"));
        eventPublisher.publishEvent(new GdprErasureRequestedEvent(this, request.getId(), userId));
        log.info("[GDPR_ERASURE_REQUESTED] requestId={} userId={}", request.getId(), userId);
        return request.getId();
    }

    @Transactional(readOnly = true)
    public Page<AdminGdprRequestDto> listRequests(String type, String status, Pageable pageable) {
        if (type != null && !VALID_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gdpr.invalidFilterValue");
        }
        if (status != null && !VALID_STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gdpr.invalidFilterValue");
        }

        Page<GdprRequest> page;
        if (type != null && status != null) {
            page = gdprRequestRepository.findByRequestTypeAndStatus(type, status, pageable);
        } else if (type != null) {
            page = gdprRequestRepository.findByRequestType(type, pageable);
        } else if (status != null) {
            page = gdprRequestRepository.findByStatus(status, pageable);
        } else {
            page = gdprRequestRepository.findAll(pageable);
        }

        return page.map(r -> new AdminGdprRequestDto(
            r.getId(), r.getUserId(), r.getRequestType(), r.getStatus(),
            r.getCreatedAt(), r.getCompletedAt()));
    }
}
