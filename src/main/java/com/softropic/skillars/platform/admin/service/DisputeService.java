package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.admin.contract.AdminDisputeDetailDto;
import com.softropic.skillars.platform.admin.contract.AdminActionType;
import com.softropic.skillars.platform.admin.contract.AdminAlertStatus;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import com.softropic.skillars.platform.admin.contract.CoachCancellationHistoryEntryDto;
import com.softropic.skillars.platform.admin.contract.CoachWarningIssuedEvent;
import com.softropic.skillars.platform.admin.contract.DisputeError;
import com.softropic.skillars.platform.admin.contract.DisputeRaisedEvent;
import com.softropic.skillars.platform.admin.contract.DisputeResolvedEvent;
import com.softropic.skillars.platform.admin.contract.DisputeResponse;
import com.softropic.skillars.platform.admin.repo.AdminActionLog;
import com.softropic.skillars.platform.admin.repo.AdminActionLogRepository;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.admin.repo.Dispute;
import com.softropic.skillars.platform.admin.repo.DisputeRepository;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.repo.BookingPayment;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.payment.repo.CoachCancellationHistoryRepository;
import com.softropic.skillars.platform.payment.service.CreditWalletService;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DisputeService {

    private static final List<String> ELIGIBLE_STATUSES = List.of(
        "COMPLETED", "CANCELLED", "CANCELLED_PARENT", "CANCELLED_COACH", "NO_SHOW_PLAYER", "NO_SHOW_COACH");

    private static final List<String> VALID_REASONS = List.of(
        "COACH_NO_SHOW", "SESSION_QUALITY", "SAFETY_CONCERN", "UNAUTHORISED_CHARGE", "OTHER");

    private final DisputeRepository disputeRepository;
    private final BookingRepository bookingRepository;
    private final BookingPaymentRepository bookingPaymentRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final CoachCancellationHistoryRepository coachCancellationHistoryRepository;
    private final AdminAlertRepository adminAlertRepository;
    private final AdminActionLogRepository adminActionLogRepository;
    private final ConfigService configService;
    private final CreditWalletService creditWalletService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public UUID raiseDispute(UUID bookingId, String reason, String details, Long raisedBy, String raisedByRole) {
        if (!VALID_REASONS.contains(reason)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "disputes.invalidReason");
        }

        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found", "Booking"));

        boolean statusEligible = ELIGIBLE_STATUSES.contains(booking.getStatus());
        boolean ownerEligible = raisedBy.equals(booking.getParentId()) || raisedBy.equals(booking.getPlayerId());
        if (!statusEligible || !ownerEligible) {
            throw new OperationNotAllowedException(
                "Not eligible to raise dispute for this booking", DisputeError.NOT_ELIGIBLE);
        }

        long windowDays = configService.getLong("disputes.submissionWindowDays", 14L);
        if (booking.getUpdatedAt().isBefore(Instant.now().minus(windowDays, ChronoUnit.DAYS))) {
            throw new OperationNotAllowedException("Dispute window expired", DisputeError.WINDOW_EXPIRED);
        }

        disputeRepository.findOpenByBookingId(bookingId).ifPresent(d -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "disputes.alreadyRaised");
        });

        Dispute dispute = new Dispute();
        dispute.setBookingId(bookingId);
        dispute.setRaisedBy(raisedBy);
        dispute.setRaisedByRole(raisedByRole);
        dispute.setReason(reason);
        dispute.setDetails(details);
        disputeRepository.save(dispute);

        eventPublisher.publishEvent(
            new DisputeRaisedEvent(this, dispute.getId(), bookingId, raisedBy, reason));

        log.info("Dispute raised: disputeId={} bookingId={} raisedBy={}", dispute.getId(), bookingId, raisedBy);
        return dispute.getId();
    }

    @Transactional(readOnly = true)
    public DisputeResponse getDispute(UUID disputeId, Long requesterId) {
        Dispute dispute = disputeRepository.findById(disputeId)
            .orElseThrow(() -> new ResourceNotFoundException("Dispute not found", "Dispute"));

        if (!dispute.getRaisedBy().equals(requesterId)) {
            throw new OperationNotAllowedException(
                "Cannot view another user's dispute", SecurityError.MISSING_RIGHTS);
        }

        return new DisputeResponse(
            dispute.getId(), dispute.getBookingId(), dispute.getReason(), dispute.getDetails(),
            dispute.getStatus(), dispute.getResolution(), dispute.getResolutionNote(), dispute.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public AdminDisputeDetailDto getAdminDisputeDetail(UUID disputeId) {
        Dispute dispute = disputeRepository.findById(disputeId)
            .orElseThrow(() -> new ResourceNotFoundException("Dispute not found", "Dispute"));

        Booking booking = bookingRepository.findById(dispute.getBookingId())
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found", "Booking"));

        String coachName = coachProfileRepository.findById(booking.getCoachId())
            .map(p -> p.getDisplayName())
            .orElse("[coach not found]");

        Optional<BookingPayment> paymentOpt = bookingPaymentRepository.findById(dispute.getBookingId());
        BigDecimal creditDebited = paymentOpt.map(BookingPayment::getCreditDebited).orElse(BigDecimal.ZERO);
        BigDecimal stripeCharged = paymentOpt.map(BookingPayment::getStripeCharged).orElse(BigDecimal.ZERO);
        BigDecimal sessionPrice = creditDebited.add(stripeCharged);

        List<CoachCancellationHistoryEntryDto> cancellationHistory =
            coachCancellationHistoryRepository.findByBookingId(dispute.getBookingId())
                .stream()
                .map(c -> new CoachCancellationHistoryEntryDto(
                    c.getId(), c.getCancelReason(), c.getBookingId(), c.getCreatedAt()))
                .toList();

        return new AdminDisputeDetailDto(
            dispute.getId(), dispute.getBookingId(), dispute.getRaisedBy(), dispute.getRaisedByRole(),
            dispute.getReason(), dispute.getDetails(), dispute.getStatus(), dispute.getResolution(),
            dispute.getResolutionNote(), dispute.getCreditAmount(),
            dispute.getCreatedAt(), dispute.getResolvedAt(), dispute.getResolvedBy(),
            booking.getCoachId(), coachName, booking.getRequestedStartTime(), booking.getStatus(),
            creditDebited, stripeCharged, sessionPrice,
            cancellationHistory);
    }

    @Transactional
    public void resolveDispute(UUID disputeId, String resolution, BigDecimal creditAmount,
                               String resolutionNote, Long adminId) {
        Dispute dispute = disputeRepository.findById(disputeId)
            .orElseThrow(() -> new ResourceNotFoundException("Dispute not found", "Dispute"));

        if ("RESOLVED".equals(dispute.getStatus()) || "DISMISSED".equals(dispute.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "disputes.alreadyResolved");
        }

        Booking booking = bookingRepository.findById(dispute.getBookingId())
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found", "Booking"));

        Optional<BookingPayment> paymentOpt = bookingPaymentRepository.findById(dispute.getBookingId());
        BigDecimal sessionPrice = paymentOpt
            .map(p -> p.getCreditDebited().add(p.getStripeCharged()))
            .orElse(BigDecimal.ZERO);

        switch (resolution) {
            case "FULL_CREDIT" -> {
                if (sessionPrice.compareTo(BigDecimal.ZERO) > 0) {
                    creditWalletService.writeLedgerEntry(
                        booking.getParentId(), sessionPrice, "BOOKING_REFUND",
                        booking.getId(), "Admin dispute resolution — full refund");
                } else {
                    log.warn("FULL_CREDIT resolution for disputeId={} issued no credit — sessionPrice=0 (pack-based or missing payment record)", disputeId);
                }
            }
            case "PARTIAL_CREDIT" -> {
                if (creditAmount == null
                        || creditAmount.compareTo(BigDecimal.ZERO) <= 0
                        || creditAmount.compareTo(sessionPrice) > 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, DisputeError.INVALID_CREDIT_AMOUNT.getErrorCode());
                }
                creditWalletService.writeLedgerEntry(
                    booking.getParentId(), creditAmount, "BOOKING_REFUND",
                    booking.getId(), "Admin dispute resolution — partial refund");
            }
            case "NO_ACTION" -> { /* no credit operation */ }
            case "COACH_WARNING" -> {
                if (creditAmount != null && creditAmount.compareTo(BigDecimal.ZERO) > 0) {
                    if (creditAmount.compareTo(sessionPrice) > 0) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, DisputeError.INVALID_CREDIT_AMOUNT.getErrorCode());
                    }
                    creditWalletService.writeLedgerEntry(
                        booking.getParentId(), creditAmount, "BOOKING_REFUND",
                        booking.getId(), "Admin dispute resolution — coach warning + partial refund");
                }
                eventPublisher.publishEvent(
                    new CoachWarningIssuedEvent(this, booking.getCoachId(), disputeId, dispute.getReason()));
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid resolution");
        }

        Instant now = Instant.now();
        dispute.setStatus("RESOLVED");
        dispute.setResolution(resolution);
        dispute.setResolutionNote(resolutionNote);
        dispute.setCreditAmount(creditAmount);
        dispute.setResolvedAt(now);
        dispute.setResolvedBy(adminId);
        disputeRepository.save(dispute);

        resolveDisputeAlert(dispute.getBookingId(), adminId, now);
        logAdminAction(adminId, AdminActionType.DISPUTE_RESOLVE, disputeId.toString(), resolutionNote);

        eventPublisher.publishEvent(
            new DisputeResolvedEvent(this, disputeId, dispute.getBookingId(), resolution, dispute.getRaisedBy()));

        log.info("Dispute resolved: disputeId={} resolution={} adminId={}", disputeId, resolution, adminId);
    }

    @Transactional
    public void dismissDispute(UUID disputeId, String reason, Long adminId) {
        Dispute dispute = disputeRepository.findById(disputeId)
            .orElseThrow(() -> new ResourceNotFoundException("Dispute not found", "Dispute"));

        if ("RESOLVED".equals(dispute.getStatus()) || "DISMISSED".equals(dispute.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "disputes.alreadyResolved");
        }

        Instant now = Instant.now();
        dispute.setStatus("DISMISSED");
        dispute.setResolvedAt(now);
        dispute.setResolvedBy(adminId);
        dispute.setResolutionNote(reason);
        disputeRepository.save(dispute);

        resolveDisputeAlert(dispute.getBookingId(), adminId, now);
        logAdminAction(adminId, AdminActionType.DISPUTE_RESOLVE, disputeId.toString(), reason);

        log.info("Dispute dismissed: disputeId={} adminId={}", disputeId, adminId);
    }

    private void resolveDisputeAlert(UUID bookingId, Long adminId, Instant now) {
        adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(
            bookingId.toString(), AdminAlertType.DISPUTE_RAISED, AdminAlertStatus.OPEN)
            .ifPresent(alert -> {
                alert.setStatus(AdminAlertStatus.RESOLVED);
                alert.setResolvedAt(now);
                alert.setResolvedBy(adminId);
                adminAlertRepository.save(alert);
            });
    }

    private void logAdminAction(Long adminId, AdminActionType actionType, String referenceId, String reason) {
        AdminActionLog log = new AdminActionLog();
        log.setAdminId(adminId);
        log.setActionType(actionType);
        log.setReferenceId(referenceId);
        log.setReason(reason);
        adminActionLogRepository.save(log);
    }
}
