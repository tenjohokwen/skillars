package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrike;
import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrikeRepository;
import com.softropic.skillars.platform.payment.contract.event.CoachVisibilityReducedEvent;
import com.softropic.skillars.platform.payment.contract.event.StrikeThresholdReachedEvent;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReliabilityStrikeService {

    private final CoachReliabilityStrikeRepository strikeRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final ConfigService configService;
    private final ApplicationEventPublisher eventPublisher;
    private final PessimisticLockRetryer lockRetryer;

    // skillars-deferred-100 code review (2026-09-08): REQUIRES_NEW, not the default REQUIRED. The
    // AFTER_COMMIT refund listeners (CancellationRefundService.onCoachNoShow /
    // onBookingCancelledByCoach) call issue() *after* refundOutboxSupport.enqueueBookingRefund(...)
    // has already written the refund row into their own REQUIRES_NEW transaction. If issue() joined
    // that transaction (REQUIRED) and its lockRetryer.withBoundedRetry exhausted
    // (PessimisticLockingFailureException), the shared transaction would be marked rollback-only and
    // the refund would be lost with the strike. A separate transaction here contains an issue()
    // failure to the strike alone; the two call sites additionally catch the exception so the refund
    // enqueue commits regardless. The admin path (AdminCoachEnforcementService) still sees the
    // propagated exception and surfaces it for an operator retry.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CoachReliabilityStrike issue(UUID coachId, UUID bookingId, String reason) {
        CoachReliabilityStrike strike = new CoachReliabilityStrike();
        strike.setCoachId(coachId);
        strike.setBookingId(bookingId);
        strike.setReason(reason);
        strike.setAcknowledged(false);
        // skillars-deferred-100 AC1: the strike INSERT stays *before* the lock. It happens
        // regardless of the threshold outcome, and keeping it here is the smaller diff. The
        // PessimisticLockRetryer flushes the persistence context at the start of every attempt, so
        // the row is durable before the locked read and is counted by the query below (same as the
        // pre-change autoflush-before-query behaviour). A full retry exhaustion still rolls the whole
        // transaction back, strike included — acceptable, see the lock comment below.
        CoachReliabilityStrike saved = strikeRepository.save(strike);

        long suspensionThreshold = configService.getBoundedLong(
            ReliabilityStrikeConfig.SUSPENSION_THRESHOLD_KEY, ReliabilityStrikeConfig.DEFAULT_SUSPENSION_THRESHOLD, 1L, Long.MAX_VALUE);
        long visibilityThreshold = configService.getBoundedLong(
            ReliabilityStrikeConfig.VISIBILITY_THRESHOLD_KEY, ReliabilityStrikeConfig.DEFAULT_VISIBILITY_THRESHOLD, 1L, Long.MAX_VALUE);

        // skillars-deferred-123 AC2: captured before the lock wait below, not inline at the count
        // query. AdminCoachEnforcementConcurrencyIT / AdminCoachEnforcementServiceIsolationTest are
        // the regression guard for this ordering — no dedicated ordering test is added (see the
        // story's AC2 Task 3: no Clock seam exists today and adding one solely for this is a larger
        // refactor than this fix warrants). Do not move this back below withBoundedRetry: under lock
        // contention (PessimisticLockRetryer's ~3.2s worst-case budget) the window's start would
        // silently slide, so a strike created seconds before the 30-day boundary could be counted for
        // an uncontended call and excluded for a contended one on the same coach.
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(30);

        // skillars-deferred-100 AC1: serialize the count -> threshold -> status decision on the
        // coach row. Two concurrent issue() calls for the same coach previously both read an
        // unlocked count = N and an unlocked status = ACTIVE, both passed the guard, and both
        // published StrikeThresholdReachedEvent / CoachVisibilityReducedEvent. CoachProfile carries
        // no @Version, so there was no optimistic-lock backstop either — both commits landed. Taking
        // the PESSIMISTIC_WRITE lock here makes the loser block until the winner commits, then
        // re-read status = PENDING_REVIEW / REDUCED so its guard suppresses the duplicate event and
        // the duplicate save. The count read is deliberately moved *below* this line so the count
        // and the status decision are consistent for the winner. No entityManager.refresh: this
        // method never pre-loads the coach row, so the locked read genuinely returns fresh state
        // (same rationale as BookingBatchService.acceptOneBooking / updateBatchStatusFromBooking).
        //
        // withBoundedRetry can still exhaust under sustained contention
        // (PessimisticLockingFailureException); issue() then propagates and its transaction rolls
        // back. That is acceptable and deliberately not given a bespoke retry: the refund-listener
        // callers (CancellationRefundService.onCoachCancellationUnexcused / onCoachNoShow) have no
        // retry, a dropped strike-escalation is far less harmful than the refund those listeners
        // exist to protect, and the admin path (AdminCoachEnforcementService.issueManualStrike)
        // surfaces the error for the operator to retry.
        CoachProfile coach = lockRetryer.withBoundedRetry("ReliabilityStrikeService.issue",
            () -> coachProfileRepository.findByIdForUpdate(coachId)
                .orElseThrow(() -> new ResourceNotFoundException("Coach not found", "coach_profile")));

        // skillars-deferred-123 AC1: a coach already off the marketplace has no enforcement value in
        // further escalation — a SUSPENDED coach cannot be knocked further, and could otherwise be
        // demoted PENDING_REVIEW->wrongly-not-suspended via a stale status guard; a DEACTIVATED coach
        // is gone. This is a deny-list (only these two statuses suppress), not a reject: the strike
        // save() above is unconditional, so the strike remains a durable compliance record even when
        // it can't change status right now — and every other status, including any added later, keeps
        // today's escalation behaviour by default (the safer failure mode for an
        // admin/automated-enforcement action). This is the single point both the manual
        // (AdminCoachEnforcementService.issueManualStrike) and automatic
        // (CancellationRefundService's no-show/cancellation listeners) strike paths converge on, so a
        // future third caller of issue() gets the same suppression for free.
        boolean alreadyOffMarketplace = coach.getStatus() == CoachProfileStatus.SUSPENDED
            || coach.getStatus() == CoachProfileStatus.DEACTIVATED;

        long count = strikeRepository.countByCoachIdAndCreatedAtAfter(coachId, cutoff);

        // Check PENDING_REVIEW threshold first (mutually exclusive per AC 9)
        if (!alreadyOffMarketplace && count >= suspensionThreshold) {
            if (coach.getStatus() != CoachProfileStatus.PENDING_REVIEW) {
                coach.setStatus(CoachProfileStatus.PENDING_REVIEW);
                coach.setStatusChangedAt(java.time.Instant.now());
                coachProfileRepository.save(coach);
                eventPublisher.publishEvent(new StrikeThresholdReachedEvent(this, coachId, bookingId, count));
                log.warn("Coach suspended for review: coachId={} rollingCount={}", coachId, count);
            }
        } else if (!alreadyOffMarketplace && count >= visibilityThreshold) {
            if (coach.getStatus() != CoachProfileStatus.REDUCED && coach.getStatus() != CoachProfileStatus.PENDING_REVIEW) {
                coach.setStatus(CoachProfileStatus.REDUCED);
                coach.setStatusChangedAt(java.time.Instant.now());
                coachProfileRepository.save(coach);
                eventPublisher.publishEvent(new CoachVisibilityReducedEvent(this, coachId, count));
                log.info("Coach visibility reduced: coachId={} rollingCount={}", coachId, count);
            }
        }

        return saved;
    }

    @Transactional
    public void acknowledge(UUID strikeId, Long coachUserId) {
        CoachReliabilityStrike strike = strikeRepository.findById(strikeId)
            .orElseThrow(() -> new ResourceNotFoundException("Strike not found", "coach_reliability_strike"));

        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        if (!strike.getCoachId().equals(coach.getId())) {
            throw new OperationNotAllowedException("Strike does not belong to this coach", SecurityError.MISSING_RIGHTS);
        }

        strike.setAcknowledged(true);
        strikeRepository.save(strike);
        log.info("Strike acknowledged: strikeId={} coachId={}", strikeId, coach.getId());
    }

    @Transactional(readOnly = true)
    public Page<CoachReliabilityStrike> getCoachStrikes(Long coachUserId, Pageable pageable) {
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        return strikeRepository.findByCoachId(coach.getId(), pageable);
    }
}
