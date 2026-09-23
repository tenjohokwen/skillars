package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.admin.repo.GdprRequest;
import com.softropic.skillars.platform.admin.repo.GdprRequestRepository;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
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
import com.softropic.skillars.platform.session.repo.HomeworkCompletionRepository;
import com.softropic.skillars.platform.security.contract.SkillarsRole;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-130 AC2. {@code deferred-work.md} D2 observed that
 * {@code GdprErasureService.deletePlayerDevelopmentData} re-types
 * {@code ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS}'s {@code [2L, 120L]} bounds as
 * literals at its {@code getBoundedLong} call site instead of reading them off the key's own
 * accessors. That framing does not hold up: {@code ConfigBounds}'s own class Javadoc documents that
 * call sites intentionally re-type bounds as literals so a {@code Mockito verify(...)} in the call
 * site's own unit test can pin the exact numbers as a drift detector — exactly the pattern
 * {@code RadarCompositeCalculatorTest#onRadarEntrySubmitted_readsLockTimeoutConfigWithTheDocumentedBoundsLiterally}
 * already establishes for the sibling {@code RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS} call site. The
 * actual gap: unlike that sibling, no such pinning test existed for this call site. This test closes
 * it — no production code changes with it.
 */
@ExtendWith(MockitoExtension.class)
class GdprErasureServiceTest {

    @Mock private GdprRequestRepository gdprRequestRepository;
    @Mock private AdminAlertRepository adminAlertRepository;
    @Mock private UserRepository userRepository;
    @Mock private CoachProfileRepository coachProfileRepository;
    @Mock private PlayerProfileRepository playerProfileRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private CoachReviewRepository coachReviewRepository;
    @Mock private SluRepository sluRepository;
    @Mock private SluWeeklySnapshotRepository sluWeeklySnapshotRepository;
    @Mock private PlayerSluWeeklySnapshotAppliedRepository playerSluWeeklySnapshotAppliedRepository;
    @Mock private SluTargetRepository sluTargetRepository;
    @Mock private RadarAssessmentRepository radarAssessmentRepository;
    @Mock private NeglectedSkillFlagRepository neglectedSkillFlagRepository;
    @Mock private PlayerRadarBaselineRepository playerRadarBaselineRepository;
    @Mock private PlayerRadarCompositeRepository playerRadarCompositeRepository;
    @Mock private PerformanceReportRepository performanceReportRepository;
    @Mock private PlayerTimelineRepository playerTimelineRepository;
    @Mock private HomeworkCompletionRepository homeworkCompletionRepository;
    @Mock private BlobDeletionOutboxSupport blobDeletionOutboxSupport;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PessimisticLockRetryer lockRetryer;
    @Mock private EntityManager entityManager;
    @Mock private PlatformTransactionManager txManager;
    @Mock private TransactionStatus transactionStatus;
    @Mock private ConfigService configService;
    @Mock private Query lockTimeoutQuery;

    private GdprErasureService service;

    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final Long USER_ID = 9590_000_001L;
    private static final Long PLAYER_PROFILE_ID = 9590_000_010L;

    @BeforeEach
    void setUp() {
        lenient().when(txManager.getTransaction(any())).thenReturn(transactionStatus);
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(lockTimeoutQuery);
        lenient().when(lockTimeoutQuery.setParameter(anyInt(), any())).thenReturn(lockTimeoutQuery);
        lenient().when(lockTimeoutQuery.getSingleResult()).thenReturn("on");
        lenient().when(lockRetryer.withBoundedRetry(org.mockito.ArgumentMatchers.<java.util.function.Supplier<PlayerProfile>>any()))
            .thenAnswer(inv -> inv.getArgument(0, java.util.function.Supplier.class).get());

        service = new GdprErasureService(
            gdprRequestRepository, adminAlertRepository, userRepository, coachProfileRepository,
            playerProfileRepository, bookingRepository, messageRepository, coachReviewRepository,
            sluRepository, sluWeeklySnapshotRepository, playerSluWeeklySnapshotAppliedRepository,
            sluTargetRepository, radarAssessmentRepository, neglectedSkillFlagRepository,
            playerRadarBaselineRepository, playerRadarCompositeRepository, performanceReportRepository,
            playerTimelineRepository, homeworkCompletionRepository, blobDeletionOutboxSupport,
            refreshTokenRepository, eventPublisher, lockRetryer, entityManager, txManager, configService);
        service.initTemplates();
    }

    @Test
    void deletePlayerDevelopmentData_readsLockTimeoutConfigWithTheDocumentedBoundsLiterally() {
        GdprRequest request = new GdprRequest();
        request.setId(REQUEST_ID);
        request.setUserId(USER_ID);
        when(gdprRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));

        User user = new User();
        user.setSkillarsRole(SkillarsRole.PLAYER);
        when(userRepository.findOneById(USER_ID)).thenReturn(Optional.of(user));

        when(coachProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        PlayerProfile playerProfile = new PlayerProfile();
        playerProfile.setId(PLAYER_PROFILE_ID);
        when(playerProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(playerProfile));
        when(playerProfileRepository.findByIdForUpdate(PLAYER_PROFILE_ID)).thenReturn(Optional.of(playerProfile));

        service.erase(REQUEST_ID, USER_ID);

        // This is the entire point of the test: Mockito's verify(...) pins the exact argument VALUES,
        // so a future edit that drifts the literal 5L/2L/120L at this call site away from
        // ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS's own declared bounds fails this
        // assertion (code review 2026-09-23: verify(...) only pins values, not the expression that
        // produces them — rewriting the call site to read the same values via .min()/.max() accessors
        // would pass identically; this test detects VALUE drift only, not a switch to accessor calls).
        verify(configService).getBoundedLong(
            eq(ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.key()), eq(5L), eq(2L), eq(120L));
    }
}
