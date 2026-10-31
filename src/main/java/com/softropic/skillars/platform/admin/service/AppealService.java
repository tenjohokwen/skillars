package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.infrastructure.security.AdminTierGuard;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.admin.contract.AdminActionType;
import com.softropic.skillars.platform.admin.contract.AdminAppealDto;
import com.softropic.skillars.platform.admin.contract.AppealDto;
import com.softropic.skillars.platform.admin.contract.AppealStatus;
import com.softropic.skillars.platform.admin.contract.AppealSubjectType;
import com.softropic.skillars.platform.admin.repo.AdminActionLog;
import com.softropic.skillars.platform.admin.repo.AdminActionLogRepository;
import com.softropic.skillars.platform.admin.repo.Appeal;
import com.softropic.skillars.platform.admin.repo.AppealRepository;
import com.softropic.skillars.platform.admin.repo.Dispute;
import com.softropic.skillars.platform.admin.repo.DisputeRepository;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrike;
import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrikeRepository;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus;
import com.softropic.skillars.platform.reviews.repo.CoachReview;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * FR-ADM-004 / FR-TSC-003 appeals: a user subject to an enforcement action may submit ONE appeal within
 * 14 days of it; an admin decides it (UPHELD or OVERTURNED) and that decision is final. See
 * {@link AppealSubjectType} for exactly which actions are appealable and what overturning each one does.
 *
 * <p>Concurrency: "one appeal per enforcement action" and "one OPEN appeal per subject" are DB invariants
 * (V172 unique indexes), so concurrent duplicate submissions resolve to one row + a 409. The decision is
 * an atomic conditional UPDATE on {@code status = 'OPEN'} ({@link AppealRepository#decideIfOpen}), and the
 * reversal runs in the same transaction, so a decision and its reversal commit or roll back together.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppealService {

    /** FR-TSC-003: "may submit one appeal within 14 days". */
    static final Duration APPEAL_WINDOW = Duration.ofDays(14);
    private static final int QUEUE_PAGE_SIZE = 20;
    private static final int AUDIT_REASON_MAX = 500;
    private static final Set<String> APPEALABLE_DISPUTE_STATUSES = Set.of("RESOLVED", "DISMISSED");

    private final AppealRepository appealRepository;
    private final CoachReliabilityStrikeRepository strikeRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final CoachReviewRepository coachReviewRepository;
    private final DisputeRepository disputeRepository;
    private final UserRepository userRepository;
    private final AdminCoachEnforcementService enforcementService;
    private final AdminReviewService adminReviewService;
    private final PessimisticLockRetryer lockRetryer;
    private final AdminActionLogRepository adminActionLogRepository;

    private record EnforcedSubject(Long ownerUserId, Instant enforcedAt, boolean stillEnforced) {}

    @Transactional
    public AppealDto submit(Long callerId, AppealSubjectType subjectType, UUID subjectId, String reason) {
        EnforcedSubject subject = resolveSubject(subjectType, subjectId);
        // Someone else's (or a non-existent) record answers 404 alike — no existence oracle.
        if (subject == null || !Objects.equals(subject.ownerUserId(), callerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "appeals.subjectNotFound");
        }
        if (!subject.stillEnforced() || subject.enforcedAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "appeals.notEnforced");
        }
        if (Instant.now().isAfter(subject.enforcedAt().plus(APPEAL_WINDOW))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "appeals.windowExpired");
        }
        // B6: a child-safety hold (CSAM match) is not an admin moderation decision and is not appealable
        // through this flow. The generic message deliberately does not say why.
        if (subjectType == AppealSubjectType.COACH_SUSPENSION && isSafetyHold(subjectId, subject.enforcedAt())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "appeals.notAppealable");
        }

        Appeal appeal = new Appeal();
        appeal.setAppellantUserId(callerId);
        appeal.setSubjectType(subjectType);
        appeal.setSubjectId(subjectId);
        appeal.setEnforcedAt(subject.enforcedAt());
        appeal.setReason(reason.trim());
        try {
            // Flushed here so uq_appeals_per_enforcement / uq_appeals_one_open_per_subject fire inside
            // this call: the loser of two concurrent submissions (or a resubmission after a decision —
            // the decision is final) gets 409 instead of a 500 at commit.
            appeal = appealRepository.saveAndFlush(appeal);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "appeals.alreadyAppealed", e);
        }
        log.info("[APPEAL_SUBMITTED] appealId={} subjectType={} subjectId={} appellant={}",
            appeal.getId(), subjectType, subjectId, callerId);
        return toDto(appeal);
    }

    @Transactional(readOnly = true)
    public List<AppealDto> listMine(Long callerId) {
        return appealRepository.findTop100ByAppellantUserIdOrderByCreatedAtDesc(callerId).stream()
            .map(AppealService::toDto)
            .toList();
    }

    @Transactional(readOnly = true)
    public Page<AdminAppealDto> getQueue(AppealStatus status, int page) {
        Page<Appeal> appeals = appealRepository.findByStatus(status,
            PageRequest.of(Math.max(page, 0), QUEUE_PAGE_SIZE, Sort.by(Sort.Direction.ASC, "createdAt")));
        Map<Long, String> names = appellantNames(appeals.getContent());
        return appeals.map(a -> toAdminDto(a, names.get(a.getAppellantUserId()), Map.of()));
    }

    @Transactional(readOnly = true)
    public AdminAppealDto getDetail(UUID appealId) {
        Appeal appeal = appealRepository.findById(appealId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "appeals.notFound"));
        return toAdminDto(appeal, appellantNames(List.of(appeal)).get(appeal.getAppellantUserId()),
            subjectSummary(appeal));
    }

    /**
     * Decides an OPEN appeal. OVERTURNED applies the subject's existing reversal action in this same
     * transaction — but only if the enforcement that was appealed still stands (e.g. the coach is still
     * suspended by THAT suspension); otherwise the decision is recorded with {@code reversalApplied =
     * false}. A failure in the reversal rolls the decision back too, so an appeal can never read
     * OVERTURNED while its enforcement silently survives.
     */
    @Transactional
    public AdminAppealDto decide(UUID appealId, AppealStatus outcome, String note, Long adminId) {
        AdminTierGuard.requireFullAdmin(); // S-28: not for ROLE_LTD_ADMIN
        if (outcome == null || outcome == AppealStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "appeals.invalidOutcome");
        }
        Appeal appeal = appealRepository.findById(appealId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "appeals.notFound"));
        AppealSubjectType subjectType = appeal.getSubjectType();
        UUID subjectId = appeal.getSubjectId();
        Instant enforcedAt = appeal.getEnforcedAt();

        int updated = appealRepository.decideIfOpen(appealId, outcome, note.trim(), adminId, Instant.now());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "appeals.alreadyDecided");
        }

        Boolean reversalApplied = null;
        if (outcome == AppealStatus.OVERTURNED) {
            boolean applied = applyReversal(appealId, subjectType, subjectId, enforcedAt, adminId);
            appealRepository.markReversalApplied(appealId, applied);
            reversalApplied = applied;
        }
        auditDecision(appealId, outcome, subjectType, subjectId, reversalApplied, note.trim(), adminId);
        log.info("[APPEAL_DECIDED] appealId={} outcome={} subjectType={} subjectId={} adminId={}",
            appealId, outcome, subjectType, subjectId, adminId);
        return getDetail(appealId);
    }

    /**
     * B6: appeal decisions are admin actions like any other and belong in {@code admin_action_log}
     * (previously only {@code appeals.decided_by} recorded them). Written in the decision's transaction,
     * after the conditional UPDATE won, so exactly one row per decided appeal. The reversal's own
     * actions (COACH_REINSTATE, COACH_STRIKE_DELETED, the review approve) are logged by the services
     * that perform them.
     */
    private void auditDecision(UUID appealId, AppealStatus outcome, AppealSubjectType subjectType, UUID subjectId,
                               Boolean reversalApplied, String note, Long adminId) {
        String reason = subjectType + " " + subjectId
            + (reversalApplied == null ? "" : " reversalApplied=" + reversalApplied) + ": " + note;
        AdminActionLog entry = new AdminActionLog();
        entry.setAdminId(adminId);
        entry.setActionType(outcome == AppealStatus.OVERTURNED
            ? AdminActionType.APPEAL_OVERTURN : AdminActionType.APPEAL_UPHOLD);
        entry.setReferenceId(appealId.toString());
        // admin_action_log.reason is varchar(500); the decision note alone may be up to 1000.
        entry.setReason(reason.length() > AUDIT_REASON_MAX ? reason.substring(0, AUDIT_REASON_MAX) : reason);
        adminActionLogRepository.save(entry);
    }

    private boolean applyReversal(UUID appealId, AppealSubjectType type, UUID subjectId, Instant enforcedAt,
                                  Long adminId) {
        // admin_action_log.reason is varchar(500) and deleteStrike/reinstateCoach prefix it — keep short.
        String auditReason = "Appeal " + appealId + " overturned";
        switch (type) {
            case RELIABILITY_STRIKE -> {
                CoachReliabilityStrike strike = strikeRepository.findById(subjectId).orElse(null);
                if (strike == null) {
                    return false; // already deleted (e.g. by an admin directly) — nothing left to reverse
                }
                enforcementService.deleteStrike(strike.getCoachId(), subjectId, auditReason, adminId);
                return true;
            }
            case COACH_SUSPENSION -> {
                // Locked read (same lock reinstateCoach then re-takes inside this transaction): the
                // "is this still the appealed suspension?" check and the reinstate must see the same row
                // state — otherwise a reinstate+re-suspend landing in between would be wrongly undone.
                CoachProfile coach = lockRetryer.withBoundedRetry("AppealService.reverseSuspension",
                    () -> coachProfileRepository.findByIdForUpdate(subjectId)).orElse(null);
                // Only reverse the suspension that was appealed: a coach since reinstated and suspended
                // AGAIN carries a newer statusChangedAt — that is a different enforcement action.
                if (coach == null || coach.getStatus() != CoachProfileStatus.SUSPENDED
                        || !Objects.equals(coach.getStatusChangedAt(), enforcedAt)
                        || isSafetyHold(subjectId, enforcedAt)) {
                    return false;
                }
                enforcementService.reinstateCoach(subjectId, auditReason, adminId);
                return true;
            }
            case REVIEW_BLOCK -> {
                // Locked read FIRST (the same NOWAIT lock approveReview re-takes in this transaction):
                // (a) the BLOCKED check and the approval see the same row state — an author's edit
                // landing in between re-queues the review for moderation, and approving that unseen
                // text would bypass moderation; (b) an earlier plain findById would leave a managed
                // entity whose stale state approveReview's own locked query would then return.
                CoachReview review = lockRetryer.withBoundedRetry("AppealService.reverseReviewBlock",
                    () -> coachReviewRepository.findByIdForUpdateNoWait(subjectId)).orElse(null);
                if (review == null || review.getModerationStatus() != ReviewModerationStatus.BLOCKED) {
                    return false;
                }
                adminReviewService.approveReview(subjectId, adminId);
                return true;
            }
            case DISPUTE_RESOLUTION -> {
                // No automatic reversal of a dispute's money movement exists; the admin applies any
                // correction manually. Recorded honestly as not applied.
                return false;
            }
            default -> throw new IllegalStateException("Unhandled appeal subject type " + type);
        }
    }

    /** B6: the coach's current suspension (stamped at {@code suspendedAt}) is a child-safety hold. */
    private boolean isSafetyHold(UUID coachId, Instant suspendedAt) {
        return suspendedAt != null && adminActionLogRepository.existsByReferenceIdAndActionTypeAndCreatedAtGreaterThanEqual(
            coachId.toString(), AdminActionType.COACH_SAFETY_HOLD, suspendedAt);
    }

    /** Null when the record does not exist. */
    private EnforcedSubject resolveSubject(AppealSubjectType type, UUID subjectId) {
        return switch (type) {
            case RELIABILITY_STRIKE -> strikeRepository.findById(subjectId)
                .flatMap(s -> coachProfileRepository.findById(s.getCoachId())
                    .map(c -> new EnforcedSubject(c.getUserId(), s.getCreatedAt().toInstant(), true)))
                .orElse(null);
            case COACH_SUSPENSION -> coachProfileRepository.findById(subjectId)
                .map(c -> new EnforcedSubject(c.getUserId(), c.getStatusChangedAt(),
                    c.getStatus() == CoachProfileStatus.SUSPENDED))
                .orElse(null);
            case REVIEW_BLOCK -> coachReviewRepository.findById(subjectId)
                .map(r -> new EnforcedSubject(r.getAuthorId(), r.getLastModifiedAt(),
                    r.getModerationStatus() == ReviewModerationStatus.BLOCKED))
                .orElse(null);
            case DISPUTE_RESOLUTION -> disputeRepository.findById(subjectId)
                .map(d -> new EnforcedSubject(d.getRaisedBy(), d.getResolvedAt(),
                    APPEALABLE_DISPUTE_STATUSES.contains(d.getStatus())))
                .orElse(null);
        };
    }

    private Map<String, Object> subjectSummary(Appeal appeal) {
        Map<String, Object> summary = new LinkedHashMap<>();
        UUID id = appeal.getSubjectId();
        switch (appeal.getSubjectType()) {
            case RELIABILITY_STRIKE -> strikeRepository.findById(id).ifPresent(s -> {
                summary.put("strikeReason", s.getReason());
                summary.put("bookingId", s.getBookingId());
                summary.put("coachId", s.getCoachId());
                coachProfileRepository.findById(s.getCoachId())
                    .ifPresent(c -> summary.put("coachName", c.getDisplayName()));
            });
            case COACH_SUSPENSION -> coachProfileRepository.findById(id).ifPresent(c -> {
                summary.put("coachName", c.getDisplayName());
                summary.put("coachStatus", c.getStatus().name());
            });
            case REVIEW_BLOCK -> coachReviewRepository.findById(id).ifPresent(r -> {
                summary.put("reviewRating", r.getRating());
                summary.put("reviewBody", r.getBody());
                summary.put("reviewStatus", r.getModerationStatus().name());
                summary.put("coachId", r.getCoachId());
            });
            case DISPUTE_RESOLUTION -> disputeRepository.findById(id).ifPresent((Dispute d) -> {
                summary.put("bookingId", d.getBookingId());
                summary.put("disputeReason", d.getReason());
                summary.put("disputeStatus", d.getStatus());
                summary.put("disputeResolution", d.getResolution());
                summary.put("disputeResolutionNote", d.getResolutionNote());
            });
            default -> { }
        }
        return summary.isEmpty() ? null : summary;
    }

    private Map<Long, String> appellantNames(List<Appeal> appeals) {
        Set<Long> ids = appeals.stream().map(Appeal::getAppellantUserId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(User::getId,
                u -> (Objects.toString(u.getFirstName(), "") + " " + Objects.toString(u.getLastName(), "")).trim(),
                (a, b) -> a));
    }

    private static AppealDto toDto(Appeal a) {
        return new AppealDto(a.getId(), a.getSubjectType(), a.getSubjectId(), a.getEnforcedAt(), a.getReason(),
            a.getStatus(), a.getDecisionNote(), a.getDecidedAt(), a.getCreatedAt());
    }

    private static AdminAppealDto toAdminDto(Appeal a, String appellantName, Map<String, Object> subject) {
        return new AdminAppealDto(a.getId(), a.getSubjectType(), a.getSubjectId(), a.getAppellantUserId(),
            appellantName, a.getEnforcedAt(), a.getReason(), a.getStatus(), a.getDecisionNote(), a.getDecidedBy(),
            a.getDecidedAt(), a.getReversalApplied(), a.getCreatedAt(), subject);
    }
}
