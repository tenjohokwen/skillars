package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.repo.RadarCompositeDlqEntry;
import com.softropic.skillars.platform.development.repo.RadarCompositeDlqRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RadarCompositeDlqService {

    private final RadarCompositeDlqRepository dlqRepository;
    private final MeterRegistry meterRegistry;

    public void emitFailedCompositeCalculation(Long playerId, Long parentId, Set<String> skillCodes, Exception cause) {
        RadarCompositeDlqEntry entry = new RadarCompositeDlqEntry();
        entry.setPlayerId(playerId);
        entry.setParentId(parentId);
        entry.setSkillCodes(List.copyOf(skillCodes));
        entry.setLastError(cause.getMessage());
        dlqRepository.save(entry);

        meterRegistry.counter("radar.composite.dlq.count").increment();
        log.warn("Radar composite calculation failed for player={} — queued to DLQ entry={}",
            playerId, entry.getId(), cause);
    }
}
