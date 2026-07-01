package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.admin.contract.AdminActionType;
import com.softropic.skillars.platform.admin.contract.CoachCancellationHistoryEntryDto;
import com.softropic.skillars.platform.admin.contract.CoachEnforcementListItemDto;
import com.softropic.skillars.platform.admin.contract.CoachEnforcementProfileDto;
import com.softropic.skillars.platform.admin.contract.CoachReinstatedEvent;
import com.softropic.skillars.platform.admin.contract.CoachStrikeHistoryDto;
import com.softropic.skillars.platform.admin.contract.CoachSuspendedEvent;
import com.softropic.skillars.platform.admin.contract.CoachSuspensionNotificationEvent;
import com.softropic.skillars.platform.admin.repo.AdminActionLog;
import com.softropic.skillars.platform.admin.repo.AdminActionLogRepository;
import com.softropic.skillars.platform.admin.repo.AdminAlert;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.admin.contract.AdminAlertStatus;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import com.softropic.skillars.platform.booking.contract.BookingCancelledByAdminEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrike;
import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrikeRepository;
import com.softropic.skillars.platform.payment.repo.CoachCancellationHistoryRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.payment.service.ReliabilityStrikeConfig;
import com.softropic.skillars.platform.payment.service.ReliabilityStrikeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminCoachEnforcementService {

    private static final Set<String> VALID_STRIKE_REASONS = Set.of("COACH_CANCELLATION_UNEXCUSED", "COACH_NO_SHOW");

    private final CoachProfileRepository coachProfileRepository;
    private final CoachReliabilityStrikeRepository strikeRepository;
    private final CoachCancellationHistoryRepository cancellationHistoryRepository;
    private final AdminAlertRepository adminAlertRepository;
    private final AdminActionLogRepository adminActionLogRepository;
    private final BookingRepository bookingRepository;
    private final CoachPricingRepository coachPricingRepository;
    private final SessionPackPurchaseRepository sessionPackPurchaseRepository;
    private final ReliabilityStrikeService reliabilityStrikeService;
    private final ConfigService configService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public CoachEnforcementProfileDto getEnforcementProfile(UUID coachId) {
        CoachProfile coach = coachProfileRepository.findById(coachId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        long activeStrikes = strikeRepository.countByCoachIdAndCreatedAtAfter(coachId, OffsetDateTime.now().minusDays(30));

        List<CoachStrikeHistoryDto> strikeHistory = strikeRepository
            .findByCoachIdOrderByCreatedAtDesc(coachId)
            .stream()
            .map(s -> new CoachStrikeHistoryDto(
                s.getId(), s.getReason(), s.getBookingId(),
                s.getCreatedAt().toInstant(), s.isAcknowledged()))
            .toList();

        List<CoachCancellationHistoryEntryDto> cancellationHistory = cancellationHistoryRepository
            .findByCoachIdOrderByCreatedAtDesc(coachId)
            .stream()
            .map(c -> new CoachCancellationHistoryEntryDto(c.getId(), c.getCancelReason(), c.getBookingId(), c.getCreatedAt()))
            .toList();

        long openAlerts = adminAlertRepository.countOpenByReferenceId(coachId.toString());

        return new CoachEnforcementProfileDto(
            coach.getId(), coach.getDisplayName(), coach.getStatus().name(),
            activeStrikes, strikeHistory, cancellationHistory, openAlerts);
    }

    @Transactional
    public void suspendCoach(UUID coachId, String reason, boolean notifyCoach, Long adminId) {
        CoachProfile coach = coachProfileRepository.findById(coachId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        if (coach.getStatus() == CoachProfileStatus.SUSPENDED) {
            return;
        }

        coach.setStatus(CoachProfileStatus.SUSPENDED);
        coach.setStatusChangedAt(Instant.now());
        coachProfileRepository.save(coach);

        List<Booking> requestedBookings = bookingRepository
            .findByCoachIdAndStatusOrderByRequestedStartTimeAsc(coachId, "REQUESTED");

        for (Booking booking : requestedBookings) {
            booking.setStatus("CANCELLED");
            booking.setCancelReason("COACH_SUSPENDED_BY_ADMIN");
            bookingRepository.save(booking);

            BigDecimal sessionPrice = resolveAdminBookingPrice(booking);
            eventPublisher.publishEvent(new BookingCancelledByAdminEvent(
                this, booking.getId(), booking.getParentId(), coachId,
                booking.getSessionPackPurchaseId(), sessionPrice));
        }

        if (notifyCoach) {
            eventPublisher.publishEvent(new CoachSuspensionNotificationEvent(this, coachId, reason));
        }

        eventPublisher.publishEvent(new CoachSuspendedEvent(this, coachId, reason, adminId));

        AdminActionLog actionLog = new AdminActionLog();
        actionLog.setAdminId(adminId);
        actionLog.setActionType(AdminActionType.COACH_SUSPEND);
        actionLog.setReferenceId(coachId.toString());
        actionLog.setReason(reason);
        adminActionLogRepository.save(actionLog);

        log.info("Coach suspended by admin: coachId={} adminId={} cancelledBookings={}",
            coachId, adminId, requestedBookings.size());
    }

    @Transactional
    public void reinstateCoach(UUID coachId, String reason, Long adminId) {
        CoachProfile coach = coachProfileRepository.findById(coachId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        if (coach.getStatus() == CoachProfileStatus.ACTIVE) {
            return;
        }
        if (coach.getStatus() != CoachProfileStatus.SUSPENDED
                && coach.getStatus() != CoachProfileStatus.PENDING_REVIEW) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Coach cannot be reinstated from status: " + coach.getStatus());
        }

        coach.setStatus(CoachProfileStatus.ACTIVE);
        coach.setStatusChangedAt(Instant.now());
        coachProfileRepository.save(coach);

        resolveOpenStrikeAlert(coachId, adminId);

        eventPublisher.publishEvent(new CoachReinstatedEvent(this, coachId, adminId));

        AdminActionLog actionLog = new AdminActionLog();
        actionLog.setAdminId(adminId);
        actionLog.setActionType(AdminActionType.COACH_REINSTATE);
        actionLog.setReferenceId(coachId.toString());
        actionLog.setReason(reason);
        adminActionLogRepository.save(actionLog);

        log.info("Coach reinstated by admin: coachId={} adminId={}", coachId, adminId);
    }

    @Transactional
    public UUID issueManualStrike(UUID coachId, UUID bookingId, String reason, Long adminId) {
        if (!VALID_STRIKE_REASONS.contains(reason)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid strike reason");
        }

        coachProfileRepository.findById(coachId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Booking not found"));
        if (!booking.getCoachId().equals(coachId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Booking does not belong to this coach");
        }

        CoachReliabilityStrike strike = reliabilityStrikeService.issue(coachId, bookingId, reason);

        AdminActionLog actionLog = new AdminActionLog();
        actionLog.setAdminId(adminId);
        actionLog.setActionType(AdminActionType.COACH_STRIKE_ISSUED);
        actionLog.setReferenceId(coachId.toString());
        actionLog.setReason("Manual strike: " + reason);
        adminActionLogRepository.save(actionLog);

        log.info("Manual strike issued: coachId={} bookingId={} reason={} adminId={}", coachId, bookingId, reason, adminId);
        return strike.getId();
    }

    @Transactional
    public void deleteStrike(UUID coachId, UUID strikeId, String reason, Long adminId) {
        CoachReliabilityStrike strike = strikeRepository.findById(strikeId)
            .orElseThrow(() -> new ResourceNotFoundException("Strike not found", "coach_reliability_strike"));

        if (!strike.getCoachId().equals(coachId)) {
            throw new ResourceNotFoundException("Strike not found", "coach_reliability_strike");
        }

        strikeRepository.deleteById(strikeId);

        long count = strikeRepository.countByCoachIdAndCreatedAtAfter(coachId, OffsetDateTime.now().minusDays(30));
        long visibilityThreshold = configService.getBoundedLong(
            ReliabilityStrikeConfig.VISIBILITY_THRESHOLD_KEY, ReliabilityStrikeConfig.DEFAULT_VISIBILITY_THRESHOLD, 1L, Long.MAX_VALUE);

        CoachProfile coach = coachProfileRepository.findById(coachId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        AdminActionLog actionLog = new AdminActionLog();
        actionLog.setAdminId(adminId);
        actionLog.setReferenceId(coachId.toString());

        boolean reverted = count < visibilityThreshold
            && (coach.getStatus() == CoachProfileStatus.PENDING_REVIEW
                || coach.getStatus() == CoachProfileStatus.REDUCED);

        if (reverted) {
            coach.setStatus(CoachProfileStatus.ACTIVE);
            coach.setStatusChangedAt(Instant.now());
            coachProfileRepository.save(coach);

            resolveOpenStrikeAlert(coachId, adminId);

            actionLog.setActionType(AdminActionType.COACH_REINSTATE);
            actionLog.setReason("Strike deleted (coach reinstated to ACTIVE): " + reason);
            log.info("Strike deleted, coach status reverted to ACTIVE: coachId={} strikeId={}", coachId, strikeId);
        } else {
            actionLog.setActionType(AdminActionType.COACH_STRIKE_DELETED);
            actionLog.setReason("Strike deleted (no status change): " + reason);
            log.info("Strike deleted (no status change): coachId={} strikeId={}", coachId, strikeId);
        }

        adminActionLogRepository.save(actionLog);
    }

    @Transactional(readOnly = true)
    public Page<CoachEnforcementListItemDto> getCoachesUnderEnforcement(String statusParam, int page) {
        List<CoachProfileStatus> statuses;
        if (statusParam == null || statusParam.isBlank() || "ALL".equalsIgnoreCase(statusParam)) {
            statuses = List.of(CoachProfileStatus.PENDING_REVIEW, CoachProfileStatus.SUSPENDED);
        } else {
            try {
                statuses = List.of(CoachProfileStatus.valueOf(statusParam.toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid status");
            }
        }

        Pageable pageable = PageRequest.of(Math.max(0, page), 20);
        Page<CoachProfile> coaches = coachProfileRepository.findByStatusInOrderByStatusChangedAtAsc(statuses, pageable);

        OffsetDateTime since = OffsetDateTime.now().minusDays(30);
        return coaches.map(coach -> {
            long activeStrikes = strikeRepository.countByCoachIdAndCreatedAtAfter(coach.getId(), since);
            return new CoachEnforcementListItemDto(
                coach.getId(), coach.getDisplayName(), coach.getStatus().name(),
                activeStrikes, coach.getStatusChangedAt());
        });
    }

    private BigDecimal resolveAdminBookingPrice(Booking booking) {
        if (booking.getSessionPackPurchaseId() != null) {
            return sessionPackPurchaseRepository.findById(booking.getSessionPackPurchaseId())
                .map(p -> p.getPricePerSession())
                .orElseGet(() -> {
                    log.warn("Session pack not found for booking={}, defaulting to ZERO", booking.getId());
                    return BigDecimal.ZERO;
                });
        }
        return coachPricingRepository.findByCoachId(booking.getCoachId())
            .map(p -> p.getPerSessionPrice())
            .orElseGet(() -> {
                log.warn("Coach pricing not found for booking={}, defaulting to ZERO", booking.getId());
                return BigDecimal.ZERO;
            });
    }

    private void resolveOpenStrikeAlert(UUID coachId, Long adminId) {
        adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(
            coachId.toString(), AdminAlertType.STRIKE_THRESHOLD, AdminAlertStatus.OPEN)
            .ifPresent(alert -> {
                alert.setStatus(AdminAlertStatus.RESOLVED);
                alert.setResolvedAt(Instant.now());
                alert.setResolvedBy(adminId);
                adminAlertRepository.save(alert);
            });
    }
}
