package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final PessimisticLockRetryer lockRetryer;

    // skillars-deferred-122 AC3: REPEATABLE_READ so the status read and the strike-count read below
    // share one consistent snapshot. Every writer of this pair (deleteStrike, reinstateCoach,
    // suspendCoach, ReliabilityStrikeService.issue) now takes findByIdForUpdate before writing both
    // fields together in the same transaction, but this method's two reads were still two separate
    // READ COMMITTED statements — a second writer committing between them could produce a torn view
    // (e.g. status=ACTIVE with activeStrikes=5), exactly the pairing this DTO exists to let an admin
    // decide whether to reinstate. readOnly=true means no write-skew/serialization-failure risk.
    // NOTE: Spring's validateExistingTransaction defaults to false, so this isolation request is
    // silently ignored if this method ever runs inside an already-open ambient transaction —
    // AdminCoachEnforcementResource's HTTP call site opens no enclosing transaction, so a fresh
    // top-level REPEATABLE_READ transaction is genuinely created in production. Do not invoke this
    // method from inside a test-level @Transactional/TransactionTemplate block.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
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
        // Deferred-15 AC4: locked read. The three accept paths re-check SUSPENDED under this same
        // row lock — but two writers serialise only when BOTH take it. With a plain findById the
        // suspension could read, write and commit entirely inside the window an accept path holds
        // its lock, making that lock decorative.
        CoachProfile coach = lockRetryer.withBoundedRetry(() -> coachProfileRepository.findByIdForUpdate(coachId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile")));

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
        // skillars-deferred-121 AC1: locked read, mirroring suspendCoach's :107-108. A plain
        // findById let this method's transition decision be computed from a stale pre-lock read
        // while a concurrent locked writer (suspendCoach, or ReliabilityStrikeService.issue) was
        // still in flight. Note what this specifically fixes: SUSPENDED is (and remains) a legal
        // source status below, so a fresh SUSPENDED read does not block a reinstate — that is
        // intended, an explicit admin reinstate call takes precedence regardless of when the
        // suspension landed. What the lock actually closes is the case where the concurrent
        // writer's fresh state is ACTIVE: pre-fix, a stale non-ACTIVE read would sail past the
        // early-return below and re-run the write, publishing a second CoachReinstatedEvent and a
        // second COACH_REINSTATE admin_action_log row for a coach that was already reinstated.
        // Review note (2026-09-18, /bmad-code-review): findByIdForUpdate's SELECT ... FOR UPDATE
        // takes Postgres's FOR UPDATE lock, which (unlike the plain findById + save()'s implicit
        // FOR NO KEY UPDATE this replaces) conflicts with the FOR KEY SHARE any in-flight FK child
        // insert into this row holds (coach_reliability_strikes, bookings, coach_pricing, etc.) —
        // a new 409 surface if such an insert's transaction outlives PessimisticLockRetryer's
        // ~3.2s budget. suspendCoach has carried this exact property since skillars-deferred-15
        // without incident; accepted here for consistency rather than introducing a narrower,
        // asymmetric lock strategy for these two methods alone.
        //
        // [DECIDED 2026-09-18, skillars-deferred-122]: explicit admin intent always wins over a
        // concurrent suspension — this is intentional, not a residual bug. An admin's reinstate call
        // proceeds to ACTIVE even when the fresh, lock-protected read above observes SUSPENDED
        // because a *different* admin's suspendCoach call committed moments ago, fully applying that
        // suspension's side effects in the process. skillars-deferred-121's lock fix makes this read
        // fresh, not the decision suspension-aware — it only prevents a stale-ACTIVE double-reinstate,
        // not a fresh-SUSPENDED override. No code change.
        //
        // Code review 2026-09-18 (/bmad-code-review): REDUCED accepted alongside SUSPENDED and
        // PENDING_REVIEW. AC1's step 4 out-of-window guard in deleteStrike justifies itself by saying
        // "an admin must use reinstateCoach explicitly to clear a coach whose elevated status has
        // become stale purely from strike ageout" — true for PENDING_REVIEW (below), but pre-fix this
        // method threw BAD_REQUEST for REDUCED, leaving a REDUCED coach whose strikes have all aged
        // out with no admin path back to ACTIVE at all: deleteStrike's guard changes nothing for an
        // out-of-window delete, and this method rejected the status outright.
        CoachProfile coach = lockRetryer.withBoundedRetry(() -> coachProfileRepository.findByIdForUpdate(coachId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile")));

        if (coach.getStatus() == CoachProfileStatus.ACTIVE) {
            return;
        }
        if (coach.getStatus() != CoachProfileStatus.SUSPENDED
                && coach.getStatus() != CoachProfileStatus.PENDING_REVIEW
                && coach.getStatus() != CoachProfileStatus.REDUCED) {
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

        // Deliberately unlocked (existence check only). Do NOT "complete the pattern" by locking
        // this read: reliabilityStrikeService.issue() below is Propagation.REQUIRES_NEW, which
        // suspends this method's transaction and opens a second one on a separate connection. If
        // this line also took findByIdForUpdate, the outer transaction would hold FOR UPDATE on
        // the same coach row that issue()'s own findByIdForUpdate (NOWAIT) then tries to acquire
        // from its suspended-but-not-yet-committed sibling — a guaranteed self-block that
        // PessimisticLockRetryer's ~3.2s budget can never resolve, since the outer lock can't
        // release until issue() returns. Every manual strike call would fail after the full retry
        // budget. Review note (2026-09-18, /bmad-code-review).
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

        // skillars-deferred-122 AC1 Fix step 3: captured into a local before the bulk delete below —
        // the bulk @Modifying DELETE (AC2) bypasses the persistence context, leaving this already-
        // loaded `strike` entity managed but stale. Never read strike.getCreatedAt() again past this
        // point.
        OffsetDateTime strikeCreatedAt = strike.getCreatedAt();

        // skillars-deferred-122 AC2: bulk delete-by-id-and-coachId, replacing the entity-based
        // deleteById whose unconditional post-delete row-count check threw StaleStateException for
        // the loser of a concurrent duplicate deleteStrike call. A 0-row result here means this
        // exact race — translate it into the same 404 the ownership check above already throws.
        int deletedRows = strikeRepository.deleteByIdAndCoachId(strikeId, coachId);
        if (deletedRows == 0) {
            throw new ResourceNotFoundException("Strike not found", "coach_reliability_strike");
        }

        // skillars-deferred-121 AC1: locked read moved before the count computation, mirroring
        // ReliabilityStrikeService.issue's ordering — the count and the revert decision must be
        // read consistently under the same lock, not just the final write guarded by it.
        CoachProfile coach = lockRetryer.withBoundedRetry(() -> coachProfileRepository.findByIdForUpdate(coachId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile")));

        AdminActionLog actionLog = new AdminActionLog();
        actionLog.setAdminId(adminId);
        actionLog.setReferenceId(coachId.toString());

        // skillars-deferred-122 AC1 Fix step 2: one cutoff local, reused below for both the
        // out-of-window guard and the count query — matches countByCoachIdAndCreatedAtAfter's own
        // strict `>` (a derived ...After query), so a boundary strike is judged identically by both.
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(30);
        boolean isPendingOrReduced = coach.getStatus() == CoachProfileStatus.PENDING_REVIEW
            || coach.getStatus() == CoachProfileStatus.REDUCED;

        // skillars-deferred-122 AC1 Fix step 4: a strike already outside the 30-day count window was
        // never counted toward the qualifying count, so deleting it must never trigger a status/alert
        // side effect — only the log row below, which every deleteStrike call writes on every branch.
        if (isPendingOrReduced && strikeCreatedAt.isAfter(cutoff)) {
            long count = strikeRepository.countByCoachIdAndCreatedAtAfter(coachId, cutoff);
            long suspensionThreshold = configService.getBoundedLong(
                ReliabilityStrikeConfig.SUSPENSION_THRESHOLD_KEY, ReliabilityStrikeConfig.DEFAULT_SUSPENSION_THRESHOLD, 1L, Long.MAX_VALUE);
            long visibilityThreshold = configService.getBoundedLong(
                ReliabilityStrikeConfig.VISIBILITY_THRESHOLD_KEY, ReliabilityStrikeConfig.DEFAULT_VISIBILITY_THRESHOLD, 1L, Long.MAX_VALUE);

            // Three-tier logic, a true reverse mirror of ReliabilityStrikeService.issue's escalation,
            // evaluated top-tier-first: checking `count >= visibilityThreshold` alone (without also
            // requiring count < suspensionThreshold) would de-escalate a coach still above the
            // suspension bar, since the two facts about `count` are independent. This top-tier-first
            // ordering is what actually makes a wrongful revert/reduce structurally impossible — not
            // a clamp on visibilityThreshold (code review 2026-09-18 found a since-removed
            // `Math.min(visibilityThreshold, suspensionThreshold)` here was unreachable dead code:
            // this branch is only reached when `count < suspensionThreshold` already, at which point
            // `count >= visibilityThreshold` and `count >= min(visibilityThreshold, suspensionThreshold)`
            // are the same comparison). A live misconfiguration (visibilityThreshold >
            // suspensionThreshold) is still a real bug, just a different one: tier 2 below becomes
            // permanently unreachable, so a coach's visibility silently never reduces to REDUCED — see
            // ConfigStartupAssertion's cross-field check, which now names that failure mode correctly.
            if (count >= suspensionThreshold) {
                // Still too many in-window strikes to de-escalate at all — stays PENDING_REVIEW (or
                // REDUCED, if that was already the coach's status — this branch does not change it).
                actionLog.setActionType(AdminActionType.COACH_STRIKE_DELETED);
                actionLog.setReason("Strike deleted (no status change): " + reason);
                log.info("Strike deleted (no status change): coachId={} strikeId={}", coachId, strikeId);
            } else if (count >= visibilityThreshold) {
                if (coach.getStatus() != CoachProfileStatus.REDUCED) {
                    coach.setStatus(CoachProfileStatus.REDUCED);
                    coach.setStatusChangedAt(Instant.now());
                    coachProfileRepository.save(coach);
                }
                // resolveOpenStrikeAlert intentionally NOT called: only a full ACTIVE clear resolves
                // the STRIKE_THRESHOLD alert — a coach still REDUCED remains at an elevated strike
                // count worth the admin's attention.
                actionLog.setActionType(AdminActionType.COACH_STRIKE_DELETED);
                actionLog.setReason("Strike deleted (coach reduced to REDUCED): " + reason);
                log.info("Strike deleted, coach status reduced to REDUCED: coachId={} strikeId={}", coachId, strikeId);
            } else {
                coach.setStatus(CoachProfileStatus.ACTIVE);
                coach.setStatusChangedAt(Instant.now());
                coachProfileRepository.save(coach);

                resolveOpenStrikeAlert(coachId, adminId);

                actionLog.setActionType(AdminActionType.COACH_REINSTATE);
                actionLog.setReason("Strike deleted (coach reinstated to ACTIVE): " + reason);
                log.info("Strike deleted, coach status reverted to ACTIVE: coachId={} strikeId={}", coachId, strikeId);
            }
        } else {
            actionLog.setActionType(AdminActionType.COACH_STRIKE_DELETED);
            actionLog.setReason("Strike deleted (no status change): " + reason);
            log.info("Strike deleted (no status change): coachId={} strikeId={}", coachId, strikeId);
        }

        adminActionLogRepository.save(actionLog);
    }

    // skillars-deferred-122 AC3: identical torn-read shape and fix as getEnforcementProfile above —
    // status (via the paged query) and the rolling strike count are read consistently under one
    // REPEATABLE_READ snapshot instead of two separate READ COMMITTED statements. Same
    // validateExistingTransaction caveat applies: do not invoke from inside a test-level
    // @Transactional/TransactionTemplate block.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
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
        List<UUID> ids = coaches.getContent().stream().map(CoachProfile::getId).toList();
        Map<UUID, Long> strikeCounts = ids.isEmpty()
            ? Map.of()
            : strikeRepository.countByCoachIdInAndCreatedAtAfter(ids, since).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));

        return coaches.map(coach -> {
            long activeStrikes = strikeCounts.getOrDefault(coach.getId(), 0L);
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
