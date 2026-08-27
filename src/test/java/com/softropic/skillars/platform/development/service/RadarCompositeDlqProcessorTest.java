package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.development.repo.RadarCompositeDlqEntry;
import com.softropic.skillars.platform.development.repo.RadarCompositeDlqRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RadarCompositeDlqProcessorTest {

    @Mock private RadarCompositeDlqRepository dlqRepository;
    @Mock private RadarCompositeCalculationService compositeCalculationService;
    @Mock private ConfigService configService;
    @Mock private TransactionTemplate transactionTemplate;

    private RadarCompositeDlqProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new RadarCompositeDlqProcessor(dlqRepository, compositeCalculationService, configService, transactionTemplate);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
    }

    private RadarCompositeDlqEntry entry() {
        RadarCompositeDlqEntry row = new RadarCompositeDlqEntry();
        row.setId(UUID.randomUUID());
        row.setPlayerId(500L);
        row.setParentId(600L);
        row.setSkillCodes(List.of("PAC"));
        row.setAttempts(0);
        row.setNextRetryAt(Instant.now());
        return row;
    }

    @Test
    void process_successfulReplay_marksRowCompleted() {
        RadarCompositeDlqEntry row = entry();
        when(dlqRepository.findClaimedBatch()).thenReturn(List.of(row));

        processor.process();

        verify(compositeCalculationService).recalculateComposite(500L, 600L, Set.of("PAC"));
        assertThat(row.getStatus()).isEqualTo("COMPLETED");
        verify(dlqRepository).save(row);
    }

    @Test
    void process_failureBelowMaxAttempts_reschedulesAsPending() {
        RadarCompositeDlqEntry row = entry();
        when(dlqRepository.findClaimedBatch()).thenReturn(List.of(row));
        when(configService.getLong(eq("platform.development.radar_composite_dlq.max_attempts"), anyLong())).thenReturn(5L);
        doThrow(new RuntimeException("still failing")).when(compositeCalculationService)
            .recalculateComposite(500L, 600L, Set.of("PAC"));

        processor.process();

        assertThat(row.getStatus()).isEqualTo("PENDING");
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getNextRetryAt()).isAfter(Instant.now());
    }

    @Test
    void process_failureAtMaxAttempts_marksDead() {
        RadarCompositeDlqEntry row = entry();
        row.setAttempts(4);
        when(dlqRepository.findClaimedBatch()).thenReturn(List.of(row));
        when(configService.getLong(eq("platform.development.radar_composite_dlq.max_attempts"), anyLong())).thenReturn(5L);
        doThrow(new RuntimeException("still failing")).when(compositeCalculationService)
            .recalculateComposite(500L, 600L, Set.of("PAC"));

        processor.process();

        assertThat(row.getStatus()).isEqualTo("DEAD");
        assertThat(row.getAttempts()).isEqualTo(5);
    }
}
