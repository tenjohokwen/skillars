package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlag;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlagRepository;
import com.softropic.skillars.platform.development.repo.PlayerSluWeeklySnapshot;
import com.softropic.skillars.platform.development.repo.SluTargetRepository;
import com.softropic.skillars.platform.development.repo.SluWeeklySnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NeglectedSkillDetectionServiceTest {

    @Mock private SluTargetRepository sluTargetRepository;
    @Mock private SluWeeklySnapshotRepository snapshotRepository;
    @Mock private NeglectedSkillFlagRepository flagRepository;
    @Mock private ConfigService configService;

    private NeglectedSkillProcessor processor;
    private NeglectedSkillDetectionService detectionService;

    private static final Long PLAYER_ID = 2000L;
    private static final short EVAL_YEAR = 2026;
    private static final short EVAL_WEEK = 24;
    private static final BigDecimal THRESHOLD = new BigDecimal("0.30");

    @BeforeEach
    void setUp() {
        processor = new NeglectedSkillProcessor(sluTargetRepository, snapshotRepository, flagRepository);
        detectionService = new NeglectedSkillDetectionService(sluTargetRepository, processor, configService);
    }

    @Test
    void detectNeglectedSkills_belowThreshold_createsFlag() {
        // actual=5, target=10, threshold=0.30 → lowerBound=7.0 → 5 < 7.0 → flagged
        when(sluTargetRepository.findMaxTargetPerSkill(PLAYER_ID))
            .thenReturn(maxTargets("PAC", new BigDecimal("10")));
        when(snapshotRepository.findByPlayerIdAndWeek(PLAYER_ID, EVAL_YEAR, EVAL_WEEK))
            .thenReturn(List.of(makeSnapshot(EVAL_YEAR, EVAL_WEEK, "PAC", new BigDecimal("5"))));

        processor.processPlayer(PLAYER_ID, THRESHOLD, EVAL_YEAR, EVAL_WEEK);

        ArgumentCaptor<NeglectedSkillFlag> captor = ArgumentCaptor.forClass(NeglectedSkillFlag.class);
        verify(flagRepository).save(captor.capture());
        assertThat(captor.getValue().getSkillCode()).isEqualTo("PAC");
        assertThat(captor.getValue().getResolvedAt()).isNull();
    }

    @Test
    void detectNeglectedSkills_aboveThreshold_doesNotCreateFlag() {
        // actual=8, target=10, threshold=0.30 → lowerBound=7.0 → 8 >= 7.0 → no flag
        when(sluTargetRepository.findMaxTargetPerSkill(PLAYER_ID))
            .thenReturn(maxTargets("PAC", new BigDecimal("10")));
        when(snapshotRepository.findByPlayerIdAndWeek(PLAYER_ID, EVAL_YEAR, EVAL_WEEK))
            .thenReturn(List.of(makeSnapshot(EVAL_YEAR, EVAL_WEEK, "PAC", new BigDecimal("8"))));
        when(flagRepository.findByPlayerIdAndResolvedAtIsNull(PLAYER_ID))
            .thenReturn(List.of());

        processor.processPlayer(PLAYER_ID, THRESHOLD, EVAL_YEAR, EVAL_WEEK);

        verify(flagRepository, never()).save(any());
    }

    @Test
    void detectNeglectedSkills_exactlyAtLowerBound_doesNotCreateFlag() {
        // actual=7.0, lowerBound=7.0 → not neglected (boundary exclusive: < lowerBound)
        when(sluTargetRepository.findMaxTargetPerSkill(PLAYER_ID))
            .thenReturn(maxTargets("PAC", new BigDecimal("10")));
        when(snapshotRepository.findByPlayerIdAndWeek(PLAYER_ID, EVAL_YEAR, EVAL_WEEK))
            .thenReturn(List.of(makeSnapshot(EVAL_YEAR, EVAL_WEEK, "PAC", new BigDecimal("7.0"))));
        when(flagRepository.findByPlayerIdAndResolvedAtIsNull(PLAYER_ID))
            .thenReturn(List.of());

        processor.processPlayer(PLAYER_ID, THRESHOLD, EVAL_YEAR, EVAL_WEEK);

        verify(flagRepository, never()).save(any());
    }

    @Test
    void detectNeglectedSkills_existingFlagAndStillNeglected_doesNotDuplicate() {
        // Open flag exists; actual still below threshold — no second flag
        when(sluTargetRepository.findMaxTargetPerSkill(PLAYER_ID))
            .thenReturn(maxTargets("PAC", new BigDecimal("10")));
        when(snapshotRepository.findByPlayerIdAndWeek(PLAYER_ID, EVAL_YEAR, EVAL_WEEK))
            .thenReturn(List.of(makeSnapshot(EVAL_YEAR, EVAL_WEEK, "PAC", new BigDecimal("3"))));
        NeglectedSkillFlag existingFlag = makeFlag();
        when(flagRepository.findByPlayerIdAndResolvedAtIsNull(PLAYER_ID))
            .thenReturn(List.of(existingFlag));

        processor.processPlayer(PLAYER_ID, THRESHOLD, EVAL_YEAR, EVAL_WEEK);

        verify(flagRepository, never()).save(any());
    }

    @Test
    void detectNeglectedSkills_existingFlagAndNowMet_resolvesFlag() {
        // actual=9 meets target=10 with threshold=0.30 → lowerBound=7; resolved
        when(sluTargetRepository.findMaxTargetPerSkill(PLAYER_ID))
            .thenReturn(maxTargets("PAC", new BigDecimal("10")));
        when(snapshotRepository.findByPlayerIdAndWeek(PLAYER_ID, EVAL_YEAR, EVAL_WEEK))
            .thenReturn(List.of(makeSnapshot(EVAL_YEAR, EVAL_WEEK, "PAC", new BigDecimal("9"))));
        NeglectedSkillFlag existingFlag = makeFlag();
        when(flagRepository.findByPlayerIdAndResolvedAtIsNull(PLAYER_ID))
            .thenReturn(List.of(existingFlag));

        processor.processPlayer(PLAYER_ID, THRESHOLD, EVAL_YEAR, EVAL_WEEK);

        ArgumentCaptor<NeglectedSkillFlag> captor = ArgumentCaptor.forClass(NeglectedSkillFlag.class);
        verify(flagRepository).save(captor.capture());
        assertThat(captor.getValue().getResolvedAt()).isNotNull();
    }

    @Test
    void detectNeglectedSkills_multipleCoachesUsesHighestTarget() {
        // coach A target=5, coach B target=10 → highest=10; actual=6 < 7.0 → flagged
        when(sluTargetRepository.findMaxTargetPerSkill(PLAYER_ID))
            .thenReturn(maxTargets("PAC", new BigDecimal("10"))); // MAX already computed by repo
        when(snapshotRepository.findByPlayerIdAndWeek(PLAYER_ID, EVAL_YEAR, EVAL_WEEK))
            .thenReturn(List.of(makeSnapshot(EVAL_YEAR, EVAL_WEEK, "PAC", new BigDecimal("6"))));

        processor.processPlayer(PLAYER_ID, THRESHOLD, EVAL_YEAR, EVAL_WEEK);

        ArgumentCaptor<NeglectedSkillFlag> captor = ArgumentCaptor.forClass(NeglectedSkillFlag.class);
        verify(flagRepository).save(captor.capture());
        assertThat(captor.getValue().getSkillCode()).isEqualTo("PAC");
    }

    @Test
    void detectNeglectedSkills_invalidConfig_abortsGracefully() {
        when(configService.getString("slu.neglected.threshold")).thenThrow(new IllegalStateException("missing"));

        // Should not throw and should not process any players
        detectionService.detectNeglectedSkills();

        verify(snapshotRepository, never()).findByPlayerIdAndWeek(anyLong(), anyShort(), anyShort());
    }

    private PlayerSluWeeklySnapshot makeSnapshot(short year, short week, String skill, BigDecimal slu) {
        PlayerSluWeeklySnapshot.PlayerSluSnapshotId id = new PlayerSluWeeklySnapshot.PlayerSluSnapshotId();
        id.setPlayerId(PLAYER_ID);
        id.setSkillCode(skill);
        id.setIsoYear(year);
        id.setIsoWeek(week);
        PlayerSluWeeklySnapshot s = new PlayerSluWeeklySnapshot();
        s.setId(id);
        s.setTotalSlu(slu);
        return s;
    }

    private List<Object[]> maxTargets(String skill, BigDecimal value) {
        List<Object[]> result = new java.util.ArrayList<>();
        result.add(new Object[]{skill, value});
        return result;
    }

    private NeglectedSkillFlag makeFlag() {
        NeglectedSkillFlag f = new NeglectedSkillFlag();
        f.setId(UUID.randomUUID());
        f.setPlayerId(PLAYER_ID);
        f.setSkillCode("PAC");
        f.setDetectedAt(Instant.now().minusSeconds(3600));
        return f;
    }
}
