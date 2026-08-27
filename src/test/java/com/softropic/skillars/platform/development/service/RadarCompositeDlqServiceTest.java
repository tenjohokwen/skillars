package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.repo.RadarCompositeDlqEntry;
import com.softropic.skillars.platform.development.repo.RadarCompositeDlqRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RadarCompositeDlqServiceTest {

    @Mock private RadarCompositeDlqRepository dlqRepository;

    private SimpleMeterRegistry meterRegistry;
    private RadarCompositeDlqService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new RadarCompositeDlqService(dlqRepository, meterRegistry);
    }

    @Test
    void emitFailedCompositeCalculation_savesEntryAndIncrementsMetric() {
        service.emitFailedCompositeCalculation(500L, 600L, Set.of("PAC", "SHO"), new RuntimeException("db down"));

        ArgumentCaptor<RadarCompositeDlqEntry> captor = ArgumentCaptor.forClass(RadarCompositeDlqEntry.class);
        verify(dlqRepository).save(captor.capture());
        RadarCompositeDlqEntry saved = captor.getValue();
        assertThat(saved.getPlayerId()).isEqualTo(500L);
        assertThat(saved.getParentId()).isEqualTo(600L);
        assertThat(saved.getSkillCodes()).containsExactlyInAnyOrder("PAC", "SHO");
        assertThat(saved.getLastError()).isEqualTo("db down");

        assertThat(meterRegistry.counter("radar.composite.dlq.count").count()).isEqualTo(1.0);
    }
}
