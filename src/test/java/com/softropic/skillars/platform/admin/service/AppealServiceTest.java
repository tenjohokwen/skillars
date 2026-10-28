package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.admin.contract.AppealStatus;
import com.softropic.skillars.platform.admin.contract.AppealSubjectType;
import com.softropic.skillars.platform.admin.repo.Appeal;
import com.softropic.skillars.platform.admin.repo.AppealRepository;
import com.softropic.skillars.platform.admin.repo.DisputeRepository;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrike;
import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrikeRepository;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppealServiceTest {

    private static final Long COACH_USER = 11L;
    private static final Long ADMIN = 99L;

    @Mock AppealRepository appealRepository;
    @Mock CoachReliabilityStrikeRepository strikeRepository;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock CoachReviewRepository coachReviewRepository;
    @Mock DisputeRepository disputeRepository;
    @Mock UserRepository userRepository;
    @Mock AdminCoachEnforcementService enforcementService;
    @Mock AdminReviewService adminReviewService;
    @Mock com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer lockRetryer;

    @Mock com.softropic.skillars.platform.admin.repo.AdminActionLogRepository adminActionLogRepository;
    @InjectMocks AppealService service;

    @Test
    void submit_strikeWithinWindow_persistsWithStrikeTimestampAsEnforcedAt() {
        UUID strikeId = UUID.randomUUID();
        UUID coachId = UUID.randomUUID();
        OffsetDateTime struckAt = OffsetDateTime.now().minusDays(3);
        stubStrike(strikeId, coachId, struckAt);
        stubCoach(coachId, CoachProfileStatus.ACTIVE, null);
        when(appealRepository.saveAndFlush(any(Appeal.class))).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.submit(COACH_USER, AppealSubjectType.RELIABILITY_STRIKE, strikeId, "  unfair  ");

        assertThat(dto.enforcedAt()).isEqualTo(struckAt.toInstant());
        assertThat(dto.reason()).isEqualTo("unfair");
        assertThat(dto.status()).isEqualTo(AppealStatus.OPEN);
    }

    @Test
    void submit_uniqueViolation_mapsTo409AlreadyAppealed() {
        UUID strikeId = UUID.randomUUID();
        UUID coachId = UUID.randomUUID();
        stubStrike(strikeId, coachId, OffsetDateTime.now().minusDays(1));
        stubCoach(coachId, CoachProfileStatus.ACTIVE, null);
        when(appealRepository.saveAndFlush(any(Appeal.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> service.submit(COACH_USER, AppealSubjectType.RELIABILITY_STRIKE, strikeId, "x"))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getReason()).isEqualTo("appeals.alreadyAppealed");
            });
    }

    @Test
    void submit_windowBoundary_15DaysOld_rejected() {
        UUID strikeId = UUID.randomUUID();
        UUID coachId = UUID.randomUUID();
        stubStrike(strikeId, coachId, OffsetDateTime.now().minusDays(15));
        stubCoach(coachId, CoachProfileStatus.ACTIVE, null);

        assertThatThrownBy(() -> service.submit(COACH_USER, AppealSubjectType.RELIABILITY_STRIKE, strikeId, "x"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getReason()).isEqualTo("appeals.windowExpired"));
        verify(appealRepository, never()).saveAndFlush(any());
    }

    @Test
    void decide_openOutcome_isRejected() {
        assertThatThrownBy(() -> service.decide(UUID.randomUUID(), AppealStatus.OPEN, "n", ADMIN))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void decide_lostRace_returns409AndAppliesNoReversal() {
        UUID appealId = UUID.randomUUID();
        when(appealRepository.findById(appealId))
            .thenReturn(Optional.of(appeal(appealId, AppealSubjectType.RELIABILITY_STRIKE, UUID.randomUUID(), Instant.now())));
        when(appealRepository.decideIfOpen(eq(appealId), any(), anyString(), anyLong(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.decide(appealId, AppealStatus.OVERTURNED, "n", ADMIN))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getReason()).isEqualTo("appeals.alreadyDecided"));
        verify(enforcementService, never()).deleteStrike(any(), any(), any(), any());
    }

    @Test
    void decide_overturnedSuspension_supersededBySecondSuspension_doesNotReinstate() {
        UUID appealId = UUID.randomUUID();
        UUID coachId = UUID.randomUUID();
        Instant appealedSuspension = Instant.now().minus(5, ChronoUnit.DAYS);
        Appeal a = appeal(appealId, AppealSubjectType.COACH_SUSPENSION, coachId, appealedSuspension);
        when(appealRepository.findById(appealId)).thenReturn(Optional.of(a));
        when(appealRepository.decideIfOpen(eq(appealId), any(), anyString(), anyLong(), any())).thenReturn(1);
        // Reinstated and suspended AGAIN since — a different enforcement action.
        CoachProfile coach = new CoachProfile();
        coach.setId(coachId);
        coach.setUserId(COACH_USER);
        coach.setStatus(CoachProfileStatus.SUSPENDED);
        coach.setStatusChangedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(lockRetryer.withBoundedRetry(anyString(), any())).thenAnswer(
            inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
        when(coachProfileRepository.findByIdForUpdate(coachId)).thenReturn(Optional.of(coach));

        service.decide(appealId, AppealStatus.OVERTURNED, "n", ADMIN);

        verify(enforcementService, never()).reinstateCoach(any(), any(), any());
        verify(appealRepository).markReversalApplied(appealId, false);
    }

    @Test
    void decide_overturnedStrike_alreadyDeleted_recordsNotApplied() {
        UUID appealId = UUID.randomUUID();
        UUID strikeId = UUID.randomUUID();
        when(appealRepository.findById(appealId))
            .thenReturn(Optional.of(appeal(appealId, AppealSubjectType.RELIABILITY_STRIKE, strikeId, Instant.now())));
        when(appealRepository.decideIfOpen(eq(appealId), any(), anyString(), anyLong(), any())).thenReturn(1);
        when(strikeRepository.findById(strikeId)).thenReturn(Optional.empty());

        service.decide(appealId, AppealStatus.OVERTURNED, "n", ADMIN);

        verify(enforcementService, never()).deleteStrike(any(), any(), any(), any());
        verify(appealRepository).markReversalApplied(appealId, false);
    }

    @Test
    void decide_upheld_neverTouchesTheSubject() {
        UUID appealId = UUID.randomUUID();
        when(appealRepository.findById(appealId))
            .thenReturn(Optional.of(appeal(appealId, AppealSubjectType.REVIEW_BLOCK, UUID.randomUUID(), Instant.now())));
        when(appealRepository.decideIfOpen(eq(appealId), eq(AppealStatus.UPHELD), anyString(), anyLong(), any()))
            .thenReturn(1);

        service.decide(appealId, AppealStatus.UPHELD, "n", ADMIN);

        verify(adminReviewService, never()).approveReview(any(), any());
        verify(appealRepository, never()).markReversalApplied(any(), org.mockito.ArgumentMatchers.anyBoolean());
        // B6: the decision itself is in the admin action log.
        var logged = org.mockito.ArgumentCaptor.forClass(com.softropic.skillars.platform.admin.repo.AdminActionLog.class);
        verify(adminActionLogRepository).save(logged.capture());
        assertThat(logged.getValue().getActionType())
            .isEqualTo(com.softropic.skillars.platform.admin.contract.AdminActionType.APPEAL_UPHOLD);
        assertThat(logged.getValue().getReferenceId()).isEqualTo(appealId.toString());
        assertThat(logged.getValue().getAdminId()).isEqualTo(ADMIN);
    }

    @Test
    void submit_coachSuspensionThatIsASafetyHold_isNotAppealable() {
        UUID coachId = UUID.randomUUID();
        Instant heldAt = Instant.now().minus(1, ChronoUnit.DAYS);
        CoachProfile coach = new CoachProfile();
        coach.setId(coachId);
        coach.setUserId(COACH_USER);
        coach.setStatus(CoachProfileStatus.SUSPENDED);
        coach.setStatusChangedAt(heldAt);
        when(coachProfileRepository.findById(coachId)).thenReturn(Optional.of(coach));
        when(adminActionLogRepository.existsByReferenceIdAndActionTypeAndCreatedAtGreaterThanEqual(
            coachId.toString(), com.softropic.skillars.platform.admin.contract.AdminActionType.COACH_SAFETY_HOLD, heldAt))
            .thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.submit(COACH_USER, AppealSubjectType.COACH_SUSPENSION, coachId, "unfair"))
            .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                e -> assertThat(e.getReason()).isEqualTo("appeals.notAppealable"));
        verify(appealRepository, never()).saveAndFlush(any());
    }

    @Test
    void decide_overturned_auditsAppealOverturnWithReversalOutcome_andCapsTheReasonAt500() {
        UUID appealId = UUID.randomUUID();
        UUID strikeId = UUID.randomUUID();
        when(appealRepository.findById(appealId))
            .thenReturn(Optional.of(appeal(appealId, AppealSubjectType.RELIABILITY_STRIKE, strikeId, Instant.now())));
        when(appealRepository.decideIfOpen(eq(appealId), any(), anyString(), anyLong(), any())).thenReturn(1);
        when(strikeRepository.findById(strikeId)).thenReturn(Optional.empty());

        service.decide(appealId, AppealStatus.OVERTURNED, "x".repeat(1000), ADMIN);

        var logged = org.mockito.ArgumentCaptor.forClass(com.softropic.skillars.platform.admin.repo.AdminActionLog.class);
        verify(adminActionLogRepository).save(logged.capture());
        assertThat(logged.getValue().getActionType())
            .isEqualTo(com.softropic.skillars.platform.admin.contract.AdminActionType.APPEAL_OVERTURN);
        assertThat(logged.getValue().getReason())
            .startsWith("RELIABILITY_STRIKE " + strikeId + " reversalApplied=false: ")
            .hasSize(500);
    }

    @Test
    void decide_overturnedReviewBlock_stillBlockedUnderLock_approves() {
        UUID appealId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        stubDecidableAppeal(appealId, AppealSubjectType.REVIEW_BLOCK, reviewId);
        stubLockedReview(reviewId, com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus.BLOCKED);

        service.decide(appealId, AppealStatus.OVERTURNED, "n", ADMIN);

        verify(adminReviewService).approveReview(reviewId, ADMIN);
        verify(appealRepository).markReversalApplied(appealId, true);
        // The BLOCKED check is made on the LOCKED read (stale-state + TOCTOU), not a plain findById.
        verify(coachReviewRepository).findByIdForUpdateNoWait(reviewId);
    }

    @Test
    void decide_overturnedReviewBlock_editedAndRequeuedSinceBlock_doesNotApproveUnmoderatedText() {
        UUID appealId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        stubDecidableAppeal(appealId, AppealSubjectType.REVIEW_BLOCK, reviewId);
        stubLockedReview(reviewId, com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus.PENDING);

        service.decide(appealId, AppealStatus.OVERTURNED, "n", ADMIN);

        verify(adminReviewService, never()).approveReview(any(), any());
        verify(appealRepository).markReversalApplied(appealId, false);
    }

    private void stubDecidableAppeal(UUID appealId, AppealSubjectType type, UUID subjectId) {
        when(appealRepository.findById(appealId))
            .thenReturn(Optional.of(appeal(appealId, type, subjectId, Instant.now())));
        when(appealRepository.decideIfOpen(eq(appealId), any(), anyString(), anyLong(), any())).thenReturn(1);
    }

    private void stubLockedReview(UUID reviewId,
                                  com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus status) {
        com.softropic.skillars.platform.reviews.repo.CoachReview review =
            new com.softropic.skillars.platform.reviews.repo.CoachReview();
        review.setReviewId(reviewId);
        review.setModerationStatus(status);
        when(lockRetryer.withBoundedRetry(anyString(), any())).thenAnswer(
            inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
        when(coachReviewRepository.findByIdForUpdateNoWait(reviewId)).thenReturn(Optional.of(review));
    }

    private void stubStrike(UUID strikeId, UUID coachId, OffsetDateTime createdAt) {
        CoachReliabilityStrike strike = new CoachReliabilityStrike();
        strike.setId(strikeId);
        strike.setCoachId(coachId);
        strike.setCreatedAt(createdAt);
        when(strikeRepository.findById(strikeId)).thenReturn(Optional.of(strike));
    }

    private void stubCoach(UUID coachId, CoachProfileStatus status, Instant statusChangedAt) {
        CoachProfile coach = new CoachProfile();
        coach.setId(coachId);
        coach.setUserId(COACH_USER);
        coach.setStatus(status);
        coach.setStatusChangedAt(statusChangedAt);
        when(coachProfileRepository.findById(coachId)).thenReturn(Optional.of(coach));
    }

    private static Appeal appeal(UUID id, AppealSubjectType type, UUID subjectId, Instant enforcedAt) {
        Appeal a = new Appeal();
        a.setId(id);
        a.setAppellantUserId(COACH_USER);
        a.setSubjectType(type);
        a.setSubjectId(subjectId);
        a.setEnforcedAt(enforcedAt);
        a.setReason("r");
        return a;
    }
}
