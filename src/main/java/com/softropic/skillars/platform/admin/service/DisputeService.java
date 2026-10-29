package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.admin.contract.AdminDisputeDetailDto;
import com.softropic.skillars.platform.admin.contract.AdminDisputeQueueDto;
import com.softropic.skillars.platform.admin.contract.AdminActionType;
import com.softropic.skillars.platform.admin.contract.AdminAlertStatus;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import com.softropic.skillars.platform.admin.contract.CoachCancellationHistoryEntryDto;
import com.softropic.skillars.platform.admin.contract.CoachWarningIssuedEvent;
import com.softropic.skillars.platform.admin.contract.DisputeCoachUpheldEvent;
import com.softropic.skillars.platform.admin.contract.DisputeDismissedEvent;
import com.softropic.skillars.platform.admin.contract.DisputeError;
import com.softropic.skillars.platform.admin.contract.DisputeRaisedEvent;
import com.softropic.skillars.platform.admin.contract.DisputeResolvedEvent;
import com.softropic.skillars.platform.admin.contract.DisputeResponse;
import com.softropic.skillars.platform.admin.contract.UpholdCoachOutcome;
import com.softropic.skillars.platform.admin.repo.AdminActionLog;
import com.softropic.skillars.platform.admin.repo.AdminActionLogRepository;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.admin.repo.Dispute;
import com.softropic.skillars.platform.admin.repo.DisputeRepository;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.BookingPaymentStatus;
import com.softropic.skillars.platform.payment.repo.BookingPayment;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.payment.repo.CoachCancellationHistoryRepository;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import com.softropic.skillars.platform.payment.service.CoachPayoutAdjustmentService;
import com.softropic.skillars.platform.payment.service.DisputeCreditService;
import com.softropic.skillars.platform.payment.service.RefundClawbackService;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
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

    /**
     * fix/booking-completion-integrity: a started session the parent (or the player) disputes — e.g. the coach
     * pressed Start then Pause and never coached. A live session cannot be cancelled by the parent, so without
     * this they had no way to contest it at all. Parent / player only: the coach controls a live session.
     * The booking keeps its status; resolving waits until it has settled ({@link #UNSETTLED_STATUSES}).
     */
    private static final List<String> LIVE_STATUSES = List.of("IN_PROGRESS", "PAUSED");

    /**
     * fix/booking-completion-integrity: a dispute on a booking in one of these statuses is not resolved yet —
     * its coach payout does not exist, so a credit could not be reconciled against it and the coach would
     * later be paid in full on top. Once it completes (the coach ends it, or UnresolvedSessionSweeperService
     * then the completion timeouts / admin force-complete), the payout is created frozen by the open dispute
     * and resolving reconciles it.
     */
    private static final List<String> UNSETTLED_STATUSES =
        List.of("IN_PROGRESS", "PAUSED", "COMPLETED_PENDING_CONFIRMATION");

    /** feat/payment-forfeit-payouts-and-clawback: the refund was not owed — claw it back, pay the coach in full. */
    static final String UPHOLD_COACH = "UPHOLD_COACH";

    /** The resolutions that may credit the payer (fix/payment-wallet-dispute-integrity: capped per booking). */
    private static final List<String> CREDIT_RESOLUTIONS = List.of("FULL_CREDIT", "PARTIAL_CREDIT", "COACH_WARNING");

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
    private final CoachPayoutRepository coachPayoutRepository;
    private final CoachPayoutAdjustmentService coachPayoutAdjustmentService;
    private final ApplicationEventPublisher eventPublisher;
    private final PlayerProfileRepository playerProfileRepository;
    private final AdminNotificationRecipientResolver recipientResolver;
    private final RefundClawbackService refundClawbackService;
    private final DisputeCreditService disputeCreditService;

    @Transactional
    public UUID raiseDispute(UUID bookingId, String reason, String details, Long raisedBy, String raisedByRole) {
        if (!VALID_REASONS.contains(reason)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "disputes.invalidReason");
        }

        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found", "Booking"));

        // skillars-deferred-148 Finding 2: booking.getPlayerId() is a PlayerProfile primary key,
        // not a User id (same cross-ID-space defect class already fixed in
        // ReviewSubmissionService.checkEligibility) — resolve it through PlayerProfile.getUserId()
        // rather than comparing raisedBy against it directly. booking.getParentId() is already
        // correctly a User id, so that disjunct is left untouched.
        boolean ownerEligible = raisedBy.equals(booking.getParentId());
        if (!ownerEligible) {
            ownerEligible = playerProfileRepository.findById(booking.getPlayerId())
                .map(PlayerProfile::getUserId)
                .map(raisedBy::equals)
                .orElse(false);
        }
        boolean raisedByPayerSide = ownerEligible;
        // Deferred-63 AC5: a coach may raise their own dispute on a booking (e.g. contesting a
        // NO_SHOW_COACH claim), not only the parent/player. booking.getCoachId() is the coach
        // *profile* UUID, not a user id, so this needs the same profile-to-user-id hop
        // getAdminDisputeDetail already does. Symmetric first-raise right only: the existing
        // findOpenByBookingId check below still blocks a second dispute on the same booking
        // regardless of who raises it first.
        if (!ownerEligible) {
            // Code review (2026-08-25): a suspended coach must not be able to raise a dispute either —
            // mirrors BookingDuplicationService.duplicateNextWeek's identical SUSPENDED guard (AC2).
            // Looked up lazily, only when the caller isn't already the parent/player, to avoid an
            // unconditional DB round-trip on the common (parent/player) path. Since
            // skillars-deferred-148's playerProfileRepository lookup above also runs on every
            // non-parent path, a coach-raised dispute now pays two profile lookups (player, then
            // coach), not one — negligible cost, but no longer a single round-trip.
            ownerEligible = coachProfileRepository.findById(booking.getCoachId())
                .filter(cp -> cp.getStatus() != CoachProfileStatus.SUSPENDED)
                .map(CoachProfile::getUserId)
                .map(raisedBy::equals)
                .orElse(false);
        }
        boolean statusEligible = ELIGIBLE_STATUSES.contains(booking.getStatus())
            || (raisedByPayerSide && LIVE_STATUSES.contains(booking.getStatus()));
        if (!statusEligible || !ownerEligible) {
            throw new OperationNotAllowedException(
                "Not eligible to raise dispute for this booking", DisputeError.NOT_ELIGIBLE);
        }

        // skillars-deferred-107 AC2: 0/neg → no dispute can ever be filed (failFast). Clamps to 14 + WARN.
        long windowDays = configService.getBoundedLong(ConfigBounds.DISPUTES_SUBMISSION_WINDOW_DAYS);
        // B2: anchored on when the booking reached its (terminal) status, not updatedAt — any later
        // write (recap edit, reminder stamp, GDPR anonymisation) bumps updatedAt and used to reopen
        // an expired window. getStatusAnchor falls back to updatedAt only for pre-V178 rows.
        if (booking.getStatusAnchor().isBefore(Instant.now().minus(windowDays, ChronoUnit.DAYS))) {
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

        // fix/payment-payouts-disputes-webhooks: the payout hold (48h) is shorter than the dispute window
        // (14 days), so an open dispute must stop the release itself — freeze the not-yet-released payout
        // under its row lock, in this transaction. Resolve/dismiss clears it.
        coachPayoutAdjustmentService.freezeForDispute(bookingId);

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
            dispute.getStatus(), dispute.getResolution(), dispute.getResolutionNote(), dispute.getCreatedAt(),
            dispute.getResolvedAt());
    }

    // skillars-deferred-165 AC1: a paged admin worklist of open disputes, mirroring
    // AdminReviewService.getUnderReviewQueue's single-status paged query shape.
    @Transactional(readOnly = true)
    public Page<AdminDisputeQueueDto> getDisputeQueue(int page) {
        Pageable pageable = PageRequest.of(Math.max(0, page), 20);
        return disputeRepository.findByStatusOrderByCreatedAtAsc("OPEN", pageable)
            .map(d -> new AdminDisputeQueueDto(
                d.getId(), d.getBookingId(), d.getRaisedByRole(), d.getReason(), d.getStatus(), d.getCreatedAt()));
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

        Optional<BookingPayment> paymentOpt = bookingPaymentRepository.findById(dispute.getBookingId())
            .filter(bp -> "CAPTURED".equals(bp.getStatus()));
        BigDecimal creditDebited = paymentOpt.map(BookingPayment::getCreditDebited).orElse(BigDecimal.ZERO);
        BigDecimal stripeCharged = paymentOpt.map(BookingPayment::getStripeCharged).orElse(BigDecimal.ZERO);
        BigDecimal sessionPrice = paidForSession(booking, paymentOpt);

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
            cancellationHistory, dispute.getRecoveredAmount(), dispute.getAbsorbedAmount());
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
        if (UNSETTLED_STATUSES.contains(booking.getStatus())) {
            // fix/booking-completion-integrity: see UNSETTLED_STATUSES. Dismissing stays possible.
            throw new ResponseStatusException(HttpStatus.CONFLICT, DisputeError.BOOKING_NOT_SETTLED.getErrorCode());
        }

        Optional<BookingPayment> paymentLookup = bookingPaymentRepository.findById(dispute.getBookingId());
        paymentLookup
            .filter(bp -> !BookingPaymentStatus.CAPTURED.equals(bp.getStatus()))
            .ifPresent(bp -> log.warn(
                "Dispute resolution proceeding with sessionPrice=0: BookingPayment status={} (not CAPTURED) "
                    + "for disputeId={} bookingId={} — automated credit/refund is not possible for a "
                    + "non-CAPTURED payment until non-CAPTURED handling is designed",
                bp.getStatus(), disputeId, dispute.getBookingId()));
        BigDecimal sessionPrice = paidForSession(booking,
            paymentLookup.filter(bp -> BookingPaymentStatus.CAPTURED.equals(bp.getStatus())));
        // B6: what was actually credited, for the raiser's notice — never the requested outcome alone
        // (FULL_CREDIT on a sessionPrice=0 booking credits nothing, and the email must say so).
        BigDecimal credited = BigDecimal.ZERO;
        RefundClawbackService.RefundClawback clawback = null;
        // fix/payment-wallet-dispute-integrity: a booking's dispute credits are capped at what was paid minus what
        // already went back to the payer (its cancellation refund, pending or written, and earlier dispute
        // credits) — not at the full price per dispute.
        BigDecimal remaining = DisputeService.CREDIT_RESOLUTIONS.contains(resolution)
            ? disputeCreditService.remainingRefundable(booking.getId(), sessionPrice) : BigDecimal.ZERO;

        switch (resolution) {
            case "FULL_CREDIT" -> {
                if (sessionPrice.compareTo(BigDecimal.ZERO) > 0) {
                    if (remaining.signum() <= 0) {
                        // Already refunded in full: a clear 409 instead of a raw unique-index violation.
                        throw new ResponseStatusException(HttpStatus.CONFLICT, DisputeError.ALREADY_REFUNDED.getErrorCode());
                    }
                    // What is left of the price (all of it when nothing was refunded yet).
                    disputeCreditService.credit(booking.getParentId(), booking.getId(), disputeId, remaining,
                        remaining.compareTo(sessionPrice) < 0
                            ? "Admin dispute resolution — refund of the remaining amount"
                            : "Admin dispute resolution — full refund");
                    credited = remaining;
                } else {
                    log.warn("FULL_CREDIT resolution for disputeId={} issued no credit — sessionPrice=0 (pack-based or missing payment record)", disputeId);
                }
            }
            case "PARTIAL_CREDIT" -> {
                if (creditAmount == null
                        || creditAmount.compareTo(BigDecimal.ZERO) <= 0
                        || creditAmount.compareTo(remaining) > 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, DisputeError.INVALID_CREDIT_AMOUNT.getErrorCode());
                }
                disputeCreditService.credit(booking.getParentId(), booking.getId(), disputeId, creditAmount,
                    "Admin dispute resolution — partial refund");
                credited = creditAmount;
            }
            case "NO_ACTION" -> { /* no credit operation */ }
            case "COACH_WARNING" -> {
                if (creditAmount != null && creditAmount.compareTo(BigDecimal.ZERO) > 0) {
                    if (creditAmount.compareTo(remaining) > 0) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, DisputeError.INVALID_CREDIT_AMOUNT.getErrorCode());
                    }
                    disputeCreditService.credit(booking.getParentId(), booking.getId(), disputeId, creditAmount,
                        "Admin dispute resolution — coach warning + partial refund");
                    credited = creditAmount;
                }
                eventPublisher.publishEvent(
                    new CoachWarningIssuedEvent(this, booking.getCoachId(), disputeId, dispute.getReason(),
                        recipientResolver.forCoach(booking.getCoachId()).orElse(null)));
            }
            case UPHOLD_COACH -> clawback = clawBackRefund(booking);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid resolution");
        }

        // skillars-deferred-106 AC10.2: reconcile the coach payout, in THIS transaction (resolveDispute
        // is @Transactional). A dispute that returns money to the parent must also reach the coach, by the
        // coach's proportional share — the same outcome whenever it lands
        // (fix/payment-payouts-disputes-webhooks):
        //   - payout RELEASED  -> enqueue a COACH_PAYOUT_REVERSAL for that share
        //   - payout PENDING_RELEASE / HOLD / FROZEN -> cut the pending net by that share (cancel only when
        //     it is the whole net), then lift the dispute freeze so it releases unless another freeze holds it
        //   - no row (session not completed, or nothing charged) -> nothing on the coach side
        //   - UPHOLD_COACH -> the coach is paid in full instead (below, once this dispute is no longer open)
        if (clawback == null) {
            reconcileCoachPayout(dispute.getBookingId(), resolution, credited, sessionPrice);
        }

        Instant now = Instant.now();
        dispute.setStatus("RESOLVED");
        dispute.setResolution(resolution);
        dispute.setResolutionNote(resolutionNote);
        dispute.setCreditAmount(clawback == null ? creditAmount : null);
        dispute.setResolvedAt(now);
        dispute.setResolvedBy(adminId);
        UpholdCoachOutcome upholdOutcome = null;
        if (clawback != null) {
            dispute.setRecoveredAmount(clawback.recovered());
            dispute.setAbsorbedAmount(clawback.absorbed());
            // feat/payment-forfeit-payouts-and-clawback: flushed RESOLVED first — the new payout's
            // existsOpenDisputeForBooking check must not freeze it for this very dispute.
            disputeRepository.saveAndFlush(dispute);
            BigDecimal coachNet = coachPayoutAdjustmentService.payInFull(booking.getId(), booking.getCoachId());
            upholdOutcome = new UpholdCoachOutcome(clawback.refunded(), clawback.recovered(), clawback.absorbed(),
                coachNet, clawback.packSession(), clawback.sessionRededucted());
            log.info("UPHOLD_COACH disputeId={} bookingId={} refunded={} recovered={} absorbed={} coachNet={}",
                disputeId, booking.getId(), clawback.refunded(), clawback.recovered(), clawback.absorbed(), coachNet);
        } else {
            disputeRepository.save(dispute);
        }

        resolveDisputeAlert(dispute.getBookingId(), adminId, now);
        logAdminAction(adminId, AdminActionType.DISPUTE_RESOLVE, disputeId.toString(),
            upholdOutcome == null ? resolutionNote : upholdAuditNote(resolutionNote, upholdOutcome));

        // B6: the raiser is told the outcome (AdminOutcomeEmailListener, BEFORE_COMMIT in this transaction).
        UpholdCoachOutcome.Audience raiserSide = "COACH".equals(dispute.getRaisedByRole())
            ? UpholdCoachOutcome.Audience.COACH : UpholdCoachOutcome.Audience.PARENT;
        String coachName = coachDisplayName(booking);
        eventPublisher.publishEvent(
            new DisputeResolvedEvent(this, disputeId, dispute.getBookingId(), resolution, dispute.getRaisedBy(),
                credited, resolutionNote, coachName, booking.getRequestedStartTime(),
                recipientResolver.forUser(dispute.getRaisedBy()).orElse(null),
                upholdOutcome, upholdOutcome == null ? null : raiserSide));
        if (upholdOutcome != null) {
            // feat/payment-forfeit-payouts-and-clawback: the other side hears it too — the payer whose refund
            // was reversed, or the coach who is now paid.
            boolean toCoach = raiserSide == UpholdCoachOutcome.Audience.PARENT;
            eventPublisher.publishEvent(new DisputeCoachUpheldEvent(disputeId, booking.getId(),
                toCoach ? UpholdCoachOutcome.Audience.COACH : UpholdCoachOutcome.Audience.PARENT, upholdOutcome,
                coachName, booking.getRequestedStartTime(),
                (toCoach ? recipientResolver.forCoach(booking.getCoachId())
                    : recipientResolver.forUser(booking.getParentId())).orElse(null)));
        }

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

        // fix/payment-payouts-disputes-webhooks: nothing refunded — lift the dispute freeze only.
        coachPayoutAdjustmentService.clearDisputeFreeze(dispute.getBookingId());

        resolveDisputeAlert(dispute.getBookingId(), adminId, now);
        // B6: its own action type — a dismissal used to be audited as DISPUTE_RESOLVE, indistinguishable
        // from a resolution in admin_action_log.
        logAdminAction(adminId, AdminActionType.DISPUTE_DISMISS, disputeId.toString(), reason);

        // B6: the raiser is told the dispute was dismissed (AdminOutcomeEmailListener).
        Booking booking = bookingRepository.findById(dispute.getBookingId()).orElse(null);
        eventPublisher.publishEvent(new DisputeDismissedEvent(disputeId, dispute.getBookingId(), dispute.getRaisedBy(),
            reason, booking == null ? null : coachDisplayName(booking),
            booking == null ? null : booking.getRequestedStartTime(),
            recipientResolver.forUser(dispute.getRaisedBy()).orElse(null)));

        log.info("Dispute dismissed: disputeId={} adminId={}", disputeId, adminId);
    }

    /**
     * feat/payment-forfeit-payouts-and-clawback: {@code UPHOLD_COACH} — only for a booking whose payer was
     * actually refunded (a wallet {@code BOOKING_REFUND} or {@code DISPUTE_CREDIT}, or a restored pack session), and only once per
     * booking; otherwise 400 before anything is written.
     */
    private RefundClawbackService.RefundClawback clawBackRefund(Booking booking) {
        if (disputeRepository.existsByBookingIdAndResolution(booking.getId(), UPHOLD_COACH)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, DisputeError.NO_REFUND_TO_REVERSE.getErrorCode());
        }
        return refundClawbackService.clawBack(booking.getId(), booking.getSessionPackPurchaseId())
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, DisputeError.NO_REFUND_TO_REVERSE.getErrorCode()));
    }

    private static String upholdAuditNote(String resolutionNote, UpholdCoachOutcome outcome) {
        // Amounts first: admin_action_log.reason is varchar(500), so a long note is what gets cut.
        String note = "[UPHOLD_COACH refunded=" + outcome.refunded() + " recovered=" + outcome.recovered()
            + " absorbed=" + outcome.absorbed() + " coachNet=" + outcome.coachNet() + "] " + resolutionNote;
        return note.length() > 500 ? note.substring(0, 500) : note;
    }

    /**
     * skillars-deferred-106 AC10.2/AC10.3: the coach-payout side of a resolved dispute. Idempotent —
     * the "already resolved" 409 at the top of {@code resolveDispute} and the per-reversal Stripe key
     * mean a dispute resolved twice never double-reverses or re-cuts.
     *
     * <p>fix/payment-payouts-disputes-webhooks: two steps, kept apart so further resolutions (e.g. one
     * that forfeits or upholds the coach's payout) slot in as their own refund/coach-share rule:
     * <ol>
     *   <li>{@code credited} — what actually went back to the parent;</li>
     *   <li>{@link CoachPayoutAdjustmentService#applyRefund} — the coach's proportional share of it
     *       (reversal if released, pro-rata cut if not), then the dispute freeze is lifted.</li>
     * </ol>
     *
     * @param parentPrice the full session price (credit + Stripe portions) — the denominator for the
     *     coach's proportional share of a partial refund
     */
    private void reconcileCoachPayout(UUID bookingId, String resolution, BigDecimal credited,
                                      BigDecimal parentPrice) {
        CoachPayout row = coachPayoutRepository.findByIdForUpdate(bookingId).orElse(null);
        if (row == null) {
            // No payout row (session not completed yet) — nothing has been transferred to the coach.
            return;
        }
        // fix/payment-wallet-dispute-integrity: what was actually credited — a FULL_CREDIT after an earlier refund
        // credits only the remainder, and the coach's share is cut by that, not by the full price again.
        coachPayoutAdjustmentService.applyRefund(row, credited, parentPrice, "DISPUTE_RESOLVED_" + resolution);
        if (row.isDisputeFrozen()) {
            row.setDisputeFrozen(false);
            coachPayoutAdjustmentService.releaseIfUnfrozen(row);
        }
    }

    /**
     * What the payer paid for this one session: card + wallet credit for a card/credit-funded booking.
     * feat/coach-payouts-pack-and-credit: a pack-funded booking has no per-booking charge
     * ({@code booking_payments} holds zeros), so its price is the session's share of the pack — the
     * gross its coach payout was computed from ({@code CoachPayoutOutboxSupport}). That makes a dispute
     * on a pack session refund the parent and cancel/reverse the coach payout exactly as for a card
     * session. Zero when the session has no payout row yet (not completed) — as before.
     */
    private BigDecimal paidForSession(Booking booking, Optional<BookingPayment> captured) {
        BigDecimal direct = captured.map(p -> p.getCreditDebited().add(p.getStripeCharged())).orElse(BigDecimal.ZERO);
        if (direct.signum() > 0 || captured.isEmpty() || booking.getSessionPackPurchaseId() == null) {
            return direct;
        }
        // A scalar read, not findById: an entity loaded here would be returned as-is (unrefreshed) by
        // reconcileCoachPayout's findByIdForUpdate later in this transaction, and its status switch would
        // then act on a stale PENDING_RELEASE that a drain has since turned RELEASED.
        return coachPayoutRepository.findGrossAmountByBookingId(booking.getId())
            .filter(gross -> gross.signum() > 0)
            .orElse(BigDecimal.ZERO);
    }

    private String coachDisplayName(Booking booking) {
        return coachProfileRepository.findById(booking.getCoachId()).map(CoachProfile::getDisplayName).orElse(null);
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
