package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.admin.contract.AdminGdprRequestDto;
import com.softropic.skillars.platform.admin.contract.GdprErasureRequestedEvent;
import com.softropic.skillars.platform.admin.contract.GdprExportRequestedEvent;
import com.softropic.skillars.platform.admin.contract.GdprExportStatusResponse;
import com.softropic.skillars.platform.admin.contract.GdprRequestStatusDto;
import com.softropic.skillars.platform.admin.repo.GdprRequest;
import com.softropic.skillars.platform.admin.repo.GdprRequestRepository;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
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
    private static final List<String> ACTIVE_STATUSES = List.of("PENDING", "PROCESSING");

    /**
     * Child erasure is refused while the child has any booking that is still live (a session or money
     * in flight). Terminal bookings (COMPLETED, DECLINED, CANCELLED*, NO_SHOW_*, REFUNDED) keep their
     * rows for the parent's financial record; the erasure only anonymises their free text.
     */
    static final List<String> CHILD_ERASURE_BLOCKING_BOOKING_STATUSES = List.of(
        "REQUESTED", "ACCEPTED", "PAYMENT_PENDING", "CONFIRMED", "UPCOMING", "IN_PROGRESS", "PAUSED",
        "COMPLETED_PENDING_CONFIRMATION", "DISPUTED", "REFUND_PENDING");

    private final GdprRequestRepository gdprRequestRepository;
    private final BookingRepository bookingRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public UUID requestExport(Long userId) {
        if (gdprRequestRepository.existsByUserIdAndPlayerIdIsNullAndRequestTypeAndStatusIn(
                userId, "EXPORT", ACTIVE_STATUSES)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "gdpr.requestAlreadyPending");
        }
        GdprRequest request = insertOrConflict(new GdprRequest(userId, "EXPORT", "PENDING"));
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
        if (gdprRequestRepository.existsByUserIdAndPlayerIdIsNullAndRequestTypeAndStatusIn(
                userId, "ERASURE", ACTIVE_STATUSES)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "gdpr.requestAlreadyPending");
        }
        if (gdprRequestRepository.existsByUserIdAndPlayerIdIsNullAndRequestTypeAndStatusIn(
                userId, "EXPORT", ACTIVE_STATUSES)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "gdpr.exportInProgress");
        }

        coachProfileRepository.findByUserId(userId).ifPresent(cp -> {
            if (bookingRepository.existsByCoachIdAndStatusIn(cp.getId(), List.of("REQUESTED", "ACCEPTED"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "gdpr.activeBookingsExist");
            }
        });

        GdprRequest request = insertOrConflict(new GdprRequest(userId, "ERASURE", "PENDING"));
        eventPublisher.publishEvent(new GdprErasureRequestedEvent(this, request.getId(), userId));
        log.info("[GDPR_ERASURE_REQUESTED] requestId={} userId={}", request.getId(), userId);
        return request.getId();
    }

    /**
     * Parent-requested export of a parent-owned (shadow) child profile. Such a child has no account of
     * its own (chk_pp_owner: exactly one of parent_id/user_id is set), so the parent is the only party
     * able to exercise the child's Article 15/20 rights.
     */
    @Transactional
    public UUID requestChildExport(Long parentUserId, Long playerId) {
        requireOwnedLiveChild(parentUserId, playerId);
        if (gdprRequestRepository.existsByPlayerIdAndRequestTypeAndStatusIn(playerId, "EXPORT", ACTIVE_STATUSES)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "gdpr.requestAlreadyPending");
        }
        GdprRequest request = insertOrConflict(new GdprRequest(parentUserId, playerId, "EXPORT", "PENDING"));
        eventPublisher.publishEvent(new GdprExportRequestedEvent(this, request.getId(), parentUserId, playerId));
        log.info("[GDPR_CHILD_EXPORT_REQUESTED] requestId={} parentUserId={} playerId={}",
            request.getId(), parentUserId, playerId);
        return request.getId();
    }

    /**
     * Parent-requested erasure of a parent-owned (shadow) child profile — Article 17 for a data subject
     * with no account of their own. Refused while the child has a live booking (see
     * {@link #CHILD_ERASURE_BLOCKING_BOOKING_STATUSES}) or while an export for the same child is still
     * being built. Known residual, same as {@link #requestErasure}'s coach check: a booking created
     * between this check and the (synchronous, after-commit) erasure is not caught.
     */
    @Transactional
    public UUID requestChildErasure(Long parentUserId, Long playerId) {
        requireOwnedLiveChild(parentUserId, playerId);
        if (gdprRequestRepository.existsByPlayerIdAndRequestTypeAndStatusIn(playerId, "ERASURE", ACTIVE_STATUSES)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "gdpr.requestAlreadyPending");
        }
        if (gdprRequestRepository.existsByPlayerIdAndRequestTypeAndStatusIn(playerId, "EXPORT", ACTIVE_STATUSES)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "gdpr.exportInProgress");
        }
        if (bookingRepository.existsByPlayerIdAndStatusIn(playerId, CHILD_ERASURE_BLOCKING_BOOKING_STATUSES)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "gdpr.activeBookingsExist");
        }
        GdprRequest request = insertOrConflict(new GdprRequest(parentUserId, playerId, "ERASURE", "PENDING"));
        eventPublisher.publishEvent(new GdprErasureRequestedEvent(this, request.getId(), parentUserId, playerId));
        log.info("[GDPR_CHILD_ERASURE_REQUESTED] requestId={} parentUserId={} playerId={}",
            request.getId(), parentUserId, playerId);
        return request.getId();
    }

    /** The requester's own (self-scoped) requests, newest first. */
    @Transactional(readOnly = true)
    public List<GdprRequestStatusDto> listOwnRequests(Long userId) {
        return gdprRequestRepository.findTop20ByUserIdAndPlayerIdIsNullOrderByCreatedAtDesc(userId).stream()
            .map(GdprRequestService::toStatusDto)
            .toList();
    }

    /** A parent's requests for one of their children, newest first. */
    @Transactional(readOnly = true)
    public List<GdprRequestStatusDto> listChildRequests(Long parentUserId, Long playerId) {
        requireOwnedChild(parentUserId, playerId);
        return gdprRequestRepository.findTop20ByUserIdAndPlayerIdOrderByCreatedAtDesc(parentUserId, playerId)
            .stream()
            .map(GdprRequestService::toStatusDto)
            .toList();
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
            r.getId(), r.getUserId(), r.getPlayerId(), r.getRequestType(), r.getStatus(),
            r.getCreatedAt(), r.getCompletedAt()));
    }

    private static GdprRequestStatusDto toStatusDto(GdprRequest r) {
        boolean downloadable = "EXPORT".equals(r.getRequestType())
            && "COMPLETED".equals(r.getStatus())
            && r.getExpiresAt() != null
            && r.getExpiresAt().isAfter(Instant.now());
        return new GdprRequestStatusDto(r.getId(), r.getRequestType(), r.getStatus(), r.getCreatedAt(),
            r.getCompletedAt(), r.getExpiresAt(), downloadable ? r.getDownloadUrl() : null);
    }

    /**
     * Inserts and flushes so the scoped partial unique index (V172) is evaluated HERE rather than at
     * commit: two concurrent duplicate requests both pass the existence pre-check, and the loser's
     * INSERT then fails on the index. Mapped to the same 409 the pre-check raises, so a double-submit
     * is idempotent from the caller's point of view instead of a 500. (The transaction is rolled back
     * either way — this method rethrows.)
     */
    private GdprRequest insertOrConflict(GdprRequest request) {
        try {
            return gdprRequestRepository.saveAndFlush(request);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "gdpr.requestAlreadyPending", e);
        }
    }

    private PlayerProfile requireOwnedChild(Long parentUserId, Long playerId) {
        PlayerProfile child = playerProfileRepository.findById(playerId)
            .orElseThrow(() -> new ResourceNotFoundException("PlayerProfile", String.valueOf(playerId)));
        // Only a parent-owned profile qualifies: a self-registered player (parentId NULL, userId set)
        // exercises their own rights through the self-scoped endpoints.
        if (child.getParentId() == null || !child.getParentId().equals(parentUserId)) {
            throw new OperationNotAllowedException(
                "Only the owning parent can act on this child's data", SecurityError.MISSING_RIGHTS);
        }
        return child;
    }

    private void requireOwnedLiveChild(Long parentUserId, Long playerId) {
        PlayerProfile child = requireOwnedChild(parentUserId, playerId);
        if (child.getDevelopmentDataErasedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "gdpr.playerAlreadyErased");
        }
    }
}
