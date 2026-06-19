package com.softropic.skillars.platform.development.repo;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SnapshotBatchWriter {

    private final SluWeeklySnapshotRepository snapshotRepository;

    @Transactional
    public void writeAll(List<PlayerSkillStat> stats, short isoYear, short isoWeek) {
        for (PlayerSkillStat stat : stats) {
            snapshotRepository.upsertAdd(stat.getPlayerId(), stat.getSkillCode(),
                isoYear, isoWeek, stat.getSluValue());
        }
    }
}
