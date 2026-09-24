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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
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
    @Mock private DataSource dataSource;

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
        lenient().when(lockRetryer.withBoundedRetry(anyString(), org.mockito.ArgumentMatchers.<java.util.function.Supplier<PlayerProfile>>any()))
            .thenAnswer(inv -> inv.getArgument(1, java.util.function.Supplier.class).get());

        service = new GdprErasureService(
            gdprRequestRepository, adminAlertRepository, userRepository, coachProfileRepository,
            playerProfileRepository, bookingRepository, messageRepository, coachReviewRepository,
            sluRepository, sluWeeklySnapshotRepository, playerSluWeeklySnapshotAppliedRepository,
            sluTargetRepository, radarAssessmentRepository, neglectedSkillFlagRepository,
            playerRadarBaselineRepository, playerRadarCompositeRepository, performanceReportRepository,
            playerTimelineRepository, homeworkCompletionRepository, blobDeletionOutboxSupport,
            refreshTokenRepository, eventPublisher, lockRetryer, entityManager, txManager, configService,
            dataSource);
        service.initTemplates();
        // skillars-deferred-132 AC1 Fix 4: erase() now delegates to self.eraseTransactional(...) so its
        // own connection-pool pre-check runs before the @Transactional(REQUIRES_NEW) proxy advice would
        // otherwise acquire a connection — self-referenced directly to the same instance since there is
        // no Spring context here (mirrors RadarCompositeCalculationServiceTest's identical pattern for
        // its own self field). dataSource is a plain Mockito mock, not a HikariDataSource, so the
        // pre-check itself is a no-op in this unit-test context.
        ReflectionTestUtils.setField(service, "self", service);
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

    /**
     * skillars-deferred-132 AC2 Fix 9. Before this fix, {@code eraseParentChildren}'s loop read this
     * config key once per child; this pins the fix directly: exactly once per {@code erase()} call for
     * the PARENT branch, regardless of child count — including the 0-children case, since the read is
     * hoisted unconditionally, before {@code children} is even computed.
     */
    @Test
    void erase_parentUser_zeroChildren_readsLockTimeoutConfigExactlyOnce() {
        stubParentErasurePreamble();
        when(playerProfileRepository.findByParentIdOrderByIdAsc(USER_ID)).thenReturn(List.of());

        service.erase(REQUEST_ID, USER_ID);

        verify(configService, times(1)).getBoundedLong(
            eq(ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.key()), eq(5L), eq(2L), eq(120L));
    }

    @Test
    void erase_parentUser_oneChild_readsLockTimeoutConfigExactlyOnce() {
        stubParentErasurePreamble();
        PlayerProfile child = parentChild(9590_777_001L);
        when(playerProfileRepository.findByParentIdOrderByIdAsc(USER_ID)).thenReturn(List.of(child));

        service.erase(REQUEST_ID, USER_ID);

        verify(configService, times(1)).getBoundedLong(
            eq(ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.key()), eq(5L), eq(2L), eq(120L));
    }

    @Test
    void erase_parentUser_threeChildren_readsLockTimeoutConfigExactlyOnce() {
        stubParentErasurePreamble();
        PlayerProfile childA = parentChild(9590_777_001L);
        PlayerProfile childB = parentChild(9590_777_002L);
        PlayerProfile childC = parentChild(9590_777_003L);
        when(playerProfileRepository.findByParentIdOrderByIdAsc(USER_ID))
            .thenReturn(List.of(childA, childB, childC));

        service.erase(REQUEST_ID, USER_ID);

        verify(configService, times(1)).getBoundedLong(
            eq(ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.key()), eq(5L), eq(2L), eq(120L));
    }

    private PlayerProfile parentChild(long id) {
        PlayerProfile child = new PlayerProfile();
        child.setId(id);
        lenient().when(playerProfileRepository.findByIdForUpdate(id)).thenReturn(Optional.of(child));
        return child;
    }

    private void stubParentErasurePreamble() {
        GdprRequest request = new GdprRequest();
        request.setId(REQUEST_ID);
        request.setUserId(USER_ID);
        when(gdprRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));

        User user = new User();
        user.setSkillarsRole(SkillarsRole.PARENT);
        when(userRepository.findOneById(USER_ID)).thenReturn(Optional.of(user));

        when(coachProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
    }
}
