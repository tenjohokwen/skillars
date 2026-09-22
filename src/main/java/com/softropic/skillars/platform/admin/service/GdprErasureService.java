package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.admin.repo.GdprRequest;
import com.softropic.skillars.platform.admin.repo.GdprRequestRepository;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.session.repo.HomeworkCompletionRepository;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlagRepository;
import com.softropic.skillars.platform.development.repo.PerformanceReportRepository;
import com.softropic.skillars.platform.development.repo.PlayerRadarBaselineRepository;
import com.softropic.skillars.platform.development.repo.PlayerRadarCompositeRepository;
import com.softropic.skillars.platform.development.repo.PlayerSluWeeklySnapshotAppliedRepository;
import com.softropic.skillars.platform.development.repo.PlayerTimelineRepository;
import com.softropic.skillars.platform.development.repo.RadarAssessmentRepository;
import com.softropic.skillars.platform.development.repo.SluRepository;
import com.softropic.skillars.platform.development.repo.SluTargetRepository;
import com.softropic.skillars.platform.development.repo.SluWeeklySnapshotRepository;
import com.softropic.skillars.platform.filestorage.service.BlobDeletionOutboxSupport;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus;
import com.softropic.skillars.platform.security.contract.AccountRole;
import com.softropic.skillars.platform.security.contract.SkillarsRole;
import com.softropic.skillars.platform.security.contract.event.AccountDeletionRequestedEvent;
import com.softropic.skillars.platform.security.contract.event.UserErasedEvent;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GdprErasureService {

    private final GdprRequestRepository gdprRequestRepository;
    private final AdminAlertRepository adminAlertRepository;
    private final UserRepository userRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final BookingRepository bookingRepository;
    private final MessageRepository messageRepository;
    private final CoachReviewRepository coachReviewRepository;
    private final SluRepository sluRepository;
    private final SluWeeklySnapshotRepository sluWeeklySnapshotRepository;
    private final PlayerSluWeeklySnapshotAppliedRepository playerSluWeeklySnapshotAppliedRepository;
    private final SluTargetRepository sluTargetRepository;
    private final RadarAssessmentRepository radarAssessmentRepository;
    private final NeglectedSkillFlagRepository neglectedSkillFlagRepository;
    private final PlayerRadarBaselineRepository playerRadarBaselineRepository;
    private final PlayerRadarCompositeRepository playerRadarCompositeRepository;
    private final PerformanceReportRepository performanceReportRepository;
    private final PlayerTimelineRepository playerTimelineRepository;
    private final HomeworkCompletionRepository homeworkCompletionRepository;
    private final BlobDeletionOutboxSupport blobDeletionOutboxSupport;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final PessimisticLockRetryer lockRetryer;
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void erase(UUID requestId, Long userId) {
        GdprRequest request = gdprRequestRepository.findById(requestId)
            .orElseThrow(() -> new RuntimeException("GdprRequest not found: " + requestId));
        request.setStatus("PROCESSING");
        gdprRequestRepository.save(request);

        User user = userRepository.findOneById(userId)
            .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        SkillarsRole role = user.getSkillarsRole();

        // Anonymise main.user — domain uses 2-char TLD to satisfy @Email(regexp = "[a-z]{2,3}") on the entity
        user.setLogin("deleted." + userId + "@erased.io");
        user.setEmail("deleted." + userId + "@erased.io");
        user.setFirstName("Deleted");
        user.setLastName("User");
        user.setPhone(null);
        user.setDateOfBirth(LocalDate.EPOCH); // dob is NOT NULL in DB; EPOCH is a neutral placeholder
        user.setActivationKey(null);
        user.setResetKey(null);
        user.setActivated(false);
        user.setLocked(true);
        user.getPersistentTokens().clear();
        userRepository.save(user);

        // Anonymise coach_profiles (if coach)
        coachProfileRepository.findByUserId(userId).ifPresent(cp -> {
            cp.setBio(null);
            cp.setCity(null);
            cp.setDistrict(null);
            coachProfileRepository.save(cp);
        });

        // Hard-delete messages (all rows including soft-deleted — Article 17)
        messageRepository.deleteAllBySenderId(userId);
        // Close any admin alert left pointing at a message we just erased — otherwise it sits OPEN
        // forever, since every admin action on it now 404s and there is no dismiss endpoint.
        adminAlertRepository.resolveOpenAlertsForDeletedMessages();

        // Hard-delete non-APPROVED reviews; anonymise APPROVED
        coachReviewRepository.deleteNonApprovedByAuthorId(userId, ReviewModerationStatus.APPROVED);
        coachReviewRepository.anonymiseApprovedReviews(userId);

        // skillars-deferred-90 AC13: collect every S3 storage key that needs deleting into a durable
        // outbox row instead of issuing N blocking deleteObject calls inside this transaction. The
        // AFTER_COMMIT drain in the generic platform.outbox deletes them off this request path and
        // retries failures on the next drain.
        List<String> blobKeysToDelete = new ArrayList<>();

        // Delete player development data
        // skillars-deferred-127 AC1: a player_profiles.id (TSID) is NOT the same as main.user.id, so
        // the PLAYER branch must resolve its own profile via findByUserId first — passing userId
        // straight to deletePlayerDevelopmentData (which now locks its argument as a player_profiles.id)
        // would either find no row (a genuine ResourceNotFoundException, since a TSID essentially never
        // equals a user id) or, worse, coincidentally lock and touch an unrelated profile. A PLAYER-role
        // account with no profile row yet has nothing to erase here — orElse-skip, not orElseThrow; a
        // missing profile is a legitimate "nothing to erase" case on a GDPR path, not an error.
        if (role == SkillarsRole.PLAYER) {
            playerProfileRepository.findByUserId(userId)
                .ifPresentOrElse(
                    pp -> deletePlayerDevelopmentData(pp.getId(), blobKeysToDelete),
                    // code review 2026-09-21: distinguishable from the intended "never built a
                    // profile" case in the logs — a silent no-op here would look identical to a
                    // successfully-completed erasure even if the absence ever had some other cause.
                    () -> log.warn("[GDPR_ERASURE] No player_profiles row found for PLAYER-role "
                        + "userId={} — skipping development-data deletion", userId));
        } else if (role == SkillarsRole.PARENT) {
            playerProfileRepository.findByParentIdOrderByIdAsc(userId)
                .forEach(pp -> deletePlayerDevelopmentData(pp.getId(), blobKeysToDelete));
        }

        // Revoke all refresh tokens so existing sessions are rejected on the next request
        refreshTokenRepository.markAllUsedByUserId(userId);

        // Delete old GDPR requests (>30 days)
        gdprRequestRepository.deleteExpiredByUserId(userId, Instant.now().minus(30, ChronoUnit.DAYS));

        // S3 files from previously COMPLETED export requests — enqueued, not deleted inline.
        gdprRequestRepository.findByUserIdAndRequestTypeAndStatus(userId, "EXPORT", "COMPLETED")
            .forEach(completedExport ->
                blobKeysToDelete.add("gdpr/exports/" + completedExport.getId() + ".zip"));

        // Persist the pending-deletion rows inside THIS transaction (so a post-commit S3 failure is
        // re-drivable) and ask for the drain to run once, after this transaction commits.
        blobDeletionOutboxSupport.enqueue(blobKeysToDelete);
        blobDeletionOutboxSupport.requestDrainAfterCommit();

        // Mark erasure complete
        request.setStatus("COMPLETED");
        request.setCompletedAt(Instant.now());
        gdprRequestRepository.save(request);

        // Both events are published within this TX and fire AFTER_COMMIT together
        eventPublisher.publishEvent(new UserErasedEvent(userId));

        // ADMIN role has no AccountRole equivalent and no video/player cascade — skip AccountDeletionRequestedEvent
        if (role != SkillarsRole.ADMIN) {
            String eventUserId;
            AccountRole accountRole;
            List<Long> linkedPlayerIds;
            if (role == SkillarsRole.COACH) {
                eventUserId = coachProfileRepository.findByUserId(userId)
                    .map(cp -> cp.getId().toString())
                    .orElse(String.valueOf(userId));
                accountRole = AccountRole.COACH;
                linkedPlayerIds = List.of();
            } else if (role == SkillarsRole.PARENT) {
                eventUserId = String.valueOf(userId);
                accountRole = AccountRole.PARENT;
                linkedPlayerIds = playerProfileRepository.findByParentIdOrderByIdAsc(userId).stream()
                    .map(PlayerProfile::getId)
                    .collect(Collectors.toList());
            } else {
                eventUserId = String.valueOf(userId);
                accountRole = AccountRole.PLAYER;
                linkedPlayerIds = List.of();
            }
            eventPublisher.publishEvent(new AccountDeletionRequestedEvent(eventUserId, accountRole, linkedPlayerIds));
        }

        log.info("[GDPR_ERASURE_COMPLETED] requestId={} userId={} role={}", requestId, userId, role);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID requestId) {
        gdprRequestRepository.findById(requestId).ifPresent(r -> {
            r.setStatus("FAILED");
            gdprRequestRepository.save(r);
            log.error("[GDPR_ERASURE_MARKED_FAILED] requestId={}", requestId);
        });
    }

    /**
     * skillars-deferred-127 AC1: takes the SAME {@code player_profiles} pessimistic lock
     * {@link com.softropic.skillars.platform.development.service.RadarCompositeCalculationService#recalculateComposite}
     * already uses (same {@code findByIdForUpdate} + {@link PessimisticLockRetryer#withBoundedRetry}
     * pattern), before deleting anything below. This fully serializes GDPR erasure against a
     * concurrent radar-composite recalculation for the same player — once one path holds this lock,
     * the other cannot even begin touching {@code player_radar_composites}/{@code player_radar_baselines},
     * closing both the resurrection race (a recalculation re-inserting composites/baselines from a
     * pre-erasure snapshot after this method already deleted them) and the lock-ordering deadlock
     * hazard (the two paths write/delete those same two tables in opposite order) as a structural
     * consequence of the shared lock, not a separate mechanism.
     *
     * <p>{@code playerId} here is always an already-resolved {@code player_profiles.id} (a TSID) —
     * never a {@code main.user.id} — resolved differently by each caller in {@link #erase}: the
     * PARENT branch already has it from {@code findByParentIdOrderByIdAsc}; the PLAYER branch resolves it via
     * {@code playerProfileRepository.findByUserId} first and only calls this method when a profile
     * row exists. A not-found here therefore means the row vanished between that resolution and lock
     * acquisition — a real error, not the normal "no profile" case the PLAYER branch already handles
     * one level up — so {@code orElseThrow} is correct at this point.
     *
     * <p>Each player's lock is acquired once, here, and — because {@code SELECT ... FOR UPDATE} row
     * locks release only at transaction end and {@link #erase} is a single
     * {@code Propagation.REQUIRES_NEW} transaction — accumulates with every other player's lock
     * already acquired earlier in the same {@link #erase} call until that whole transaction commits.
     * A PARENT with N children therefore holds N {@code player_profiles} locks simultaneously; this is
     * NOT "locked/unlocked independently" per player.
     *
     * <p><strong>The shared lock alone does not fully close the resurrection race</strong> (code
     * review, 2026-09-21): {@code RadarAssessmentService.submitAssessment} writes
     * {@code radar_assessment_entries} without taking this lock at all, so a coach submission that
     * commits while this method's delete is already running can leave rows this method's own
     * {@code radarAssessmentRepository.deleteAllByPlayerId} never saw (not yet committed under READ
     * COMMITTED at the moment it ran) — a later {@code recalculateComposite} run (live or
     * DLQ-retried) would then read those surviving rows and re-create composites/baselines for an
     * already-erased player. Stamping {@link PlayerProfile#getDevelopmentDataErasedAt()} here, under
     * this same lock, closes it: {@code recalculateComposite} checks this field immediately after
     * re-acquiring/refreshing the identical lock, before reading any aggregates, and skips entirely
     * once it is set — see that method's own Javadoc.
     */
    private void deletePlayerDevelopmentData(Long playerId, List<String> blobKeysToDelete) {
        var playerProfile = lockRetryer.withBoundedRetry(() -> playerProfileRepository.findByIdForUpdate(playerId)
            .orElseThrow(() -> new ResourceNotFoundException("Player not found: " + playerId, "player_profile")));
        entityManager.refresh(playerProfile, LockModeType.PESSIMISTIC_WRITE);

        playerTimelineRepository.deleteByPlayerId(playerId);
        sluRepository.deleteAllByPlayerId(playerId);
        sluWeeklySnapshotRepository.deleteAllByPlayerId(playerId);
        playerSluWeeklySnapshotAppliedRepository.deleteAllByPlayerId(playerId);
        sluTargetRepository.deleteAllByPlayerId(playerId);
        neglectedSkillFlagRepository.deleteAllByPlayerId(playerId);
        playerRadarBaselineRepository.deleteAllByPlayerId(playerId);
        playerRadarCompositeRepository.deleteAllByPlayerId(playerId);
        radarAssessmentRepository.deleteAllByPlayerId(playerId);
        performanceReportRepository.findByPlayerIdOrderByGeneratedAtDesc(playerId).forEach(report -> {
            // Deferred-77 AC2: a PENDING_UPLOAD/UPLOAD_FAILED report may have no storage_key yet.
            // skillars-deferred-90 AC13: enqueue the key, don't delete from S3 inside this transaction.
            if (report.getStorageKey() != null) {
                blobKeysToDelete.add(report.getStorageKey());
            }
        });
        performanceReportRepository.deleteAllByPlayerId(playerId);
        homeworkCompletionRepository.deleteAllByPlayerId(playerId);

        // Sticky tombstone — see this method's own Javadoc. Never reset back to null: a
        // player_profiles row is never "un-erased".
        playerProfile.setDevelopmentDataErasedAt(Instant.now());
        playerProfileRepository.save(playerProfile);
    }
}
