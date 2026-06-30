package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.admin.repo.GdprRequest;
import com.softropic.skillars.platform.admin.repo.GdprRequestRepository;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.session.repo.HomeworkCompletionRepository;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlagRepository;
import com.softropic.skillars.platform.development.repo.PerformanceReportRepository;
import com.softropic.skillars.platform.development.repo.PlayerRadarBaselineRepository;
import com.softropic.skillars.platform.development.repo.PlayerRadarCompositeRepository;
import com.softropic.skillars.platform.development.repo.PlayerTimelineRepository;
import com.softropic.skillars.platform.development.repo.RadarAssessmentRepository;
import com.softropic.skillars.platform.development.repo.SluRepository;
import com.softropic.skillars.platform.development.repo.SluTargetRepository;
import com.softropic.skillars.platform.development.repo.SluWeeklySnapshotRepository;
import com.softropic.skillars.platform.filestorage.service.FileStorageService;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GdprErasureService {

    private final GdprRequestRepository gdprRequestRepository;
    private final UserRepository userRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final BookingRepository bookingRepository;
    private final MessageRepository messageRepository;
    private final CoachReviewRepository coachReviewRepository;
    private final SluRepository sluRepository;
    private final SluWeeklySnapshotRepository sluWeeklySnapshotRepository;
    private final SluTargetRepository sluTargetRepository;
    private final RadarAssessmentRepository radarAssessmentRepository;
    private final NeglectedSkillFlagRepository neglectedSkillFlagRepository;
    private final PlayerRadarBaselineRepository playerRadarBaselineRepository;
    private final PlayerRadarCompositeRepository playerRadarCompositeRepository;
    private final PerformanceReportRepository performanceReportRepository;
    private final PlayerTimelineRepository playerTimelineRepository;
    private final HomeworkCompletionRepository homeworkCompletionRepository;
    private final FileStorageService fileStorageService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ApplicationEventPublisher eventPublisher;

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

        // Hard-delete non-APPROVED reviews; anonymise APPROVED
        coachReviewRepository.deleteNonApprovedByAuthorId(userId, ReviewModerationStatus.APPROVED);
        coachReviewRepository.anonymiseApprovedReviews(userId);

        // Delete player development data
        if (role == SkillarsRole.PLAYER) {
            deletePlayerDevelopmentData(userId);
        } else if (role == SkillarsRole.PARENT) {
            playerProfileRepository.findByParentId(userId).forEach(pp -> deletePlayerDevelopmentData(pp.getId()));
        }

        // Revoke all refresh tokens so existing sessions are rejected on the next request
        refreshTokenRepository.markAllUsedByUserId(userId);

        // Delete old GDPR requests (>30 days)
        gdprRequestRepository.deleteExpiredByUserId(userId, Instant.now().minus(30, ChronoUnit.DAYS));

        // Delete S3 files from previously COMPLETED export requests
        gdprRequestRepository.findByUserIdAndRequestTypeAndStatus(userId, "EXPORT", "COMPLETED")
            .forEach(completedExport -> {
                try {
                    fileStorageService.deleteRawBytes("gdpr/exports/" + completedExport.getId() + ".zip");
                } catch (Exception e) {
                    log.warn("[GDPR_ERASURE_S3_DELETE_WARN] Failed to delete export zip for requestId={} userId={}",
                        completedExport.getId(), userId, e);
                }
            });

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
                linkedPlayerIds = playerProfileRepository.findByParentId(userId).stream()
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

    private void deletePlayerDevelopmentData(Long playerId) {
        playerTimelineRepository.deleteByPlayerId(playerId);
        sluRepository.deleteAllByPlayerId(playerId);
        sluWeeklySnapshotRepository.deleteAllByPlayerId(playerId);
        sluTargetRepository.deleteAllByPlayerId(playerId);
        neglectedSkillFlagRepository.deleteAllByPlayerId(playerId);
        playerRadarBaselineRepository.deleteAllByPlayerId(playerId);
        playerRadarCompositeRepository.deleteAllByPlayerId(playerId);
        radarAssessmentRepository.deleteAllByPlayerId(playerId);
        performanceReportRepository.deleteAllByPlayerId(playerId);
        homeworkCompletionRepository.deleteAllByPlayerId(playerId);
    }
}
