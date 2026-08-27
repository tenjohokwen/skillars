package com.softropic.skillars.platform.development.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.development.contract.AssessmentType;
import com.softropic.skillars.platform.development.contract.RadarEntrySubmittedEvent;
import com.softropic.skillars.platform.development.repo.PlayerRadarBaselineRepository;
import com.softropic.skillars.platform.development.repo.PlayerRadarCompositeRepository;
import com.softropic.skillars.platform.development.repo.RadarAssessmentRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RadarCompositeCalculatorTest {

    @Mock
    private RadarAssessmentRepository radarRepository;

    @Mock
    private PlayerRadarCompositeRepository compositeRepository;

    @Mock
    private PlayerRadarBaselineRepository baselineRepository;

    @Mock
    private PlayerProfileRepository playerProfileRepository;

    @Mock
    private PessimisticLockRetryer lockRetryer;

    @Mock
    private EntityManager entityManager;

    @Mock
    private RadarCompositeDlqService dlqService;

    private RadarCompositeCalculationService service;

    private static final Long PLAYER_ID = 9580000001L;
    private static final Long PARENT_ID = 9580000010L;

    @BeforeEach
    void setUp() {
        service = new RadarCompositeCalculationService(radarRepository, compositeRepository, baselineRepository,
            playerProfileRepository, lockRetryer, entityManager, dlqService);
        // onRadarEntrySubmitted delegates to `self.recalculateComposite(...)` — in production this is
        // an @Autowired @Lazy proxy reference; here it's wired directly to the same instance since
        // there is no Spring context, matching this codebase's other self-field unit tests.
        ReflectionTestUtils.setField(service, "self", service);

        PlayerProfile playerProfile = new PlayerProfile();
        lenient().when(playerProfileRepository.findByIdForUpdate(PLAYER_ID)).thenReturn(Optional.of(playerProfile));
        lenient().when(lockRetryer.withBoundedRetry(org.mockito.ArgumentMatchers.<Supplier<PlayerProfile>>any()))
            .thenAnswer(inv -> inv.getArgument(0, Supplier.class).get());
    }

    @Test
    void onRadarEntrySubmitted_singleCoachObjective_computesWeightedComposite() {
        // OBJECTIVE avg=80 → composite = 80 × 0.50 = 40.00
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"PAC", "OBJECTIVE", 80.0, 1L});
        when(radarRepository.findAggregatesByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(rows);
        when(radarRepository.findDistinctCoachCountsByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(List.<Object[]>of(new Object[]{"PAC", 1L}));

        service.onRadarEntrySubmitted(new RadarEntrySubmittedEvent(PLAYER_ID, PARENT_ID, Set.of("PAC")));

        ArgumentCaptor<BigDecimal> scoreCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(compositeRepository).upsertComposite(eq(PLAYER_ID), eq("PAC"), scoreCaptor.capture(), eq(1), eq(1));
        assertThat(scoreCaptor.getValue()).isEqualByComparingTo("40.00");
    }

    @Test
    void onRadarEntrySubmitted_acquiresPessimisticLockOnPlayerRowBeforeRecalculating() {
        // Deferred-77 AC10 Phase 1: recalculateComposite must lock the player row (via
        // PessimisticLockRetryer + findByIdForUpdate + explicit refresh) before reading aggregates,
        // so two concurrent submissions for the same player serialize instead of last-writer-wins.
        when(radarRepository.findAggregatesByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(List.of());
        when(radarRepository.findDistinctCoachCountsByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(List.of());

        service.onRadarEntrySubmitted(new RadarEntrySubmittedEvent(PLAYER_ID, PARENT_ID, Set.of("PAC")));

        verify(lockRetryer).withBoundedRetry(org.mockito.ArgumentMatchers.<Supplier<Object>>any());
        verify(playerProfileRepository).findByIdForUpdate(PLAYER_ID);
        verify(entityManager).refresh(any(), eq(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE));
    }

    @Test
    void onRadarEntrySubmitted_recalculationFails_emitsToDlqWithFailureLogged() {
        RuntimeException failure = new RuntimeException("transient db error");
        when(radarRepository.findAggregatesByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenThrow(failure);

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(RadarCompositeCalculationService.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        serviceLogger.addAppender(logCapture);
        try {
            service.onRadarEntrySubmitted(new RadarEntrySubmittedEvent(PLAYER_ID, PARENT_ID, Set.of("PAC")));
        } finally {
            serviceLogger.detachAppender(logCapture);
        }

        verify(dlqService).emitFailedCompositeCalculation(PLAYER_ID, PARENT_ID, Set.of("PAC"), failure);
        assertThat(logCapture.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("queued to DLQ");
        });
    }

    @Test
    void onRadarEntrySubmitted_allThreeTypes_computesCorrectComposite() {
        // OBJECTIVE avg=80, MATCH_OBS avg=70, COACH_EVAL avg=60 → 80×0.50 + 70×0.30 + 60×0.20 = 73.00
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"PAC", "OBJECTIVE",         80.0, 1L});
        rows.add(new Object[]{"PAC", "MATCH_OBSERVATION", 70.0, 1L});
        rows.add(new Object[]{"PAC", "COACH_EVALUATION",  60.0, 1L});
        when(radarRepository.findAggregatesByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(rows);
        when(radarRepository.findDistinctCoachCountsByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(List.<Object[]>of(new Object[]{"PAC", 1L}));

        service.onRadarEntrySubmitted(new RadarEntrySubmittedEvent(PLAYER_ID, PARENT_ID, Set.of("PAC")));

        ArgumentCaptor<BigDecimal> scoreCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(compositeRepository).upsertComposite(eq(PLAYER_ID), eq("PAC"), scoreCaptor.capture(), eq(3), eq(1));
        assertThat(scoreCaptor.getValue()).isEqualByComparingTo("73.00");
    }

    @Test
    void onRadarEntrySubmitted_multipleCoaches_aggregatesAcrossAllCoaches() {
        // coach A OBJECTIVE=80, coach B OBJECTIVE=60 → avgObjective=70 → composite=70×0.50=35.00; count=2
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"PAC", "OBJECTIVE", 70.0, 2L});
        when(radarRepository.findAggregatesByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(rows);
        when(radarRepository.findDistinctCoachCountsByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(List.<Object[]>of(new Object[]{"PAC", 2L}));

        service.onRadarEntrySubmitted(new RadarEntrySubmittedEvent(PLAYER_ID, PARENT_ID, Set.of("PAC")));

        ArgumentCaptor<BigDecimal> scoreCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(compositeRepository).upsertComposite(eq(PLAYER_ID), eq("PAC"), scoreCaptor.capture(), eq(2), eq(2));
        assertThat(scoreCaptor.getValue()).isEqualByComparingTo("35.00");
    }

    @Test
    void onRadarEntrySubmitted_onlyMatchObservation_computesPartialComposite() {
        // MATCH_OBS avg=50 → composite = 50×0.30 = 15.00 (partial)
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"SHO", "MATCH_OBSERVATION", 50.0, 1L});
        when(radarRepository.findAggregatesByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("SHO")))
            .thenReturn(rows);
        when(radarRepository.findDistinctCoachCountsByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("SHO")))
            .thenReturn(List.<Object[]>of(new Object[]{"SHO", 1L}));

        service.onRadarEntrySubmitted(new RadarEntrySubmittedEvent(PLAYER_ID, PARENT_ID, Set.of("SHO")));

        ArgumentCaptor<BigDecimal> scoreCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(compositeRepository).upsertComposite(eq(PLAYER_ID), eq("SHO"), scoreCaptor.capture(), eq(1), eq(1));
        assertThat(scoreCaptor.getValue()).isEqualByComparingTo("15.00");
    }

    @Test
    void onRadarEntrySubmitted_multipleSkills_upsertCalledPerSkill() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"PAC", "OBJECTIVE", 80.0, 1L});
        rows.add(new Object[]{"SHO", "OBJECTIVE", 60.0, 1L});
        when(radarRepository.findAggregatesByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC", "SHO")))
            .thenReturn(rows);
        when(radarRepository.findDistinctCoachCountsByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC", "SHO")))
            .thenReturn(List.<Object[]>of(new Object[]{"PAC", 1L}, new Object[]{"SHO", 1L}));

        service.onRadarEntrySubmitted(new RadarEntrySubmittedEvent(PLAYER_ID, PARENT_ID, Set.of("PAC", "SHO")));

        verify(compositeRepository, times(2)).upsertComposite(eq(PLAYER_ID), any(), any(), anyInt(), anyInt());
    }

    @Test
    void onRadarEntrySubmitted_distinctCoachCount_reflectsUniqueCoachesNotRowCount() {
        // 3 assessment rows for PAC, but findDistinctCoachCountsByPlayerAndSkills reports only 1
        // distinct coach (e.g. one prolific coach logging 3 assessments) — distinctCoachCount must
        // reflect the distinct-coach query's result, not the row-count-derived entryCount.
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"PAC", "OBJECTIVE", 80.0, 3L});
        when(radarRepository.findAggregatesByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(rows);
        when(radarRepository.findDistinctCoachCountsByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(List.<Object[]>of(new Object[]{"PAC", 1L}));

        service.onRadarEntrySubmitted(new RadarEntrySubmittedEvent(PLAYER_ID, PARENT_ID, Set.of("PAC")));

        verify(compositeRepository).upsertComposite(eq(PLAYER_ID), eq("PAC"), any(), eq(3), eq(1));

        // Now simulate a second coach also assessing PAC — same 3 rows, but 2 distinct coaches.
        when(radarRepository.findDistinctCoachCountsByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(List.<Object[]>of(new Object[]{"PAC", 2L}));

        service.onRadarEntrySubmitted(new RadarEntrySubmittedEvent(PLAYER_ID, PARENT_ID, Set.of("PAC")));

        verify(compositeRepository).upsertComposite(eq(PLAYER_ID), eq("PAC"), any(), eq(3), eq(2));
    }

    @Test
    void onRadarEntrySubmitted_sessionCountOverflow_throwsAndLogsInsteadOfWrapping() {
        // row[3] originates as a native-query long count; seed it beyond Integer.MAX_VALUE so the
        // narrowing cast to totalCount would silently wrap to a negative value if left unguarded.
        long overflowingCount = Integer.MAX_VALUE + 1L;
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"PAC", "OBJECTIVE", 80.0, overflowingCount});
        when(radarRepository.findAggregatesByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(rows);
        when(radarRepository.findDistinctCoachCountsByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(List.<Object[]>of(new Object[]{"PAC", 1L}));

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(RadarCompositeCalculationService.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        serviceLogger.addAppender(logCapture);
        try {
            service.onRadarEntrySubmitted(new RadarEntrySubmittedEvent(PLAYER_ID, PARENT_ID, Set.of("PAC")));
        } finally {
            serviceLogger.detachAppender(logCapture);
        }

        verify(compositeRepository, never()).upsertComposite(any(), any(), any(), anyInt(), anyInt());
        assertThat(logCapture.list)
            .as("overflow must surface as the existing absorbing-catch ERROR log, not a silent wrap")
            .anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("Composite recalculation failed");
            });
    }

    @Test
    void onRadarEntrySubmitted_matchObservationCountOverflow_throwsAndLogsInsteadOfWrapping() {
        // Same overflow guard, MATCH_OBSERVATION branch — the three narrowing casts share one helper
        // method, but each call site is independently regression-locked here.
        long overflowingCount = Integer.MAX_VALUE + 1L;
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"PAC", "MATCH_OBSERVATION", 70.0, overflowingCount});
        when(radarRepository.findAggregatesByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(rows);
        when(radarRepository.findDistinctCoachCountsByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(List.<Object[]>of(new Object[]{"PAC", 1L}));

        service.onRadarEntrySubmitted(new RadarEntrySubmittedEvent(PLAYER_ID, PARENT_ID, Set.of("PAC")));

        verify(compositeRepository, never()).upsertComposite(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void onRadarEntrySubmitted_coachEvaluationCountOverflow_throwsAndLogsInsteadOfWrapping() {
        // Same overflow guard, COACH_EVALUATION branch.
        long overflowingCount = Integer.MAX_VALUE + 1L;
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"PAC", "COACH_EVALUATION", 60.0, overflowingCount});
        when(radarRepository.findAggregatesByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(rows);
        when(radarRepository.findDistinctCoachCountsByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(List.<Object[]>of(new Object[]{"PAC", 1L}));

        service.onRadarEntrySubmitted(new RadarEntrySubmittedEvent(PLAYER_ID, PARENT_ID, Set.of("PAC")));

        verify(compositeRepository, never()).upsertComposite(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void onRadarEntrySubmitted_sessionCountExactlyMaxValue_stillSucceeds() {
        // Boundary-positive case: a count of exactly Integer.MAX_VALUE must NOT throw — only counts
        // beyond it should trip the overflow guard.
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"PAC", "OBJECTIVE", 80.0, (long) Integer.MAX_VALUE});
        when(radarRepository.findAggregatesByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(rows);
        when(radarRepository.findDistinctCoachCountsByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(List.<Object[]>of(new Object[]{"PAC", 1L}));

        service.onRadarEntrySubmitted(new RadarEntrySubmittedEvent(PLAYER_ID, PARENT_ID, Set.of("PAC")));

        verify(compositeRepository).upsertComposite(eq(PLAYER_ID), eq("PAC"), any(), eq(Integer.MAX_VALUE), eq(1));
    }
}
