package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.development.repo.SluTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class NeglectedSkillDetectionService {

    private final SluTargetRepository sluTargetRepository;
    private final NeglectedSkillProcessor processor;
    private final ConfigService configService;

    @Scheduled(cron = "${app.development.neglected-detection-cron:0 0 6 * * MON}")
    public void detectNeglectedSkills() {
        BigDecimal threshold;
        try {
            threshold = new BigDecimal(configService.getString("slu.neglected.threshold"));
        } catch (IllegalStateException | NumberFormatException | NullPointerException e) {
            log.error("Neglected skill detection aborted — invalid config: {}", e.getMessage());
            return;
        }

        // Evaluate the PREVIOUS completed ISO week — the job fires Monday morning when
        // the current week has barely started.
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime evaluated = now.minusWeeks(1);
        short evalYear = (short) evaluated.get(IsoFields.WEEK_BASED_YEAR);
        short evalWeek = (short) evaluated.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);

        List<Long> playerIds = sluTargetRepository.findDistinctPlayerIds();
        for (Long playerId : playerIds) {
            try {
                processor.processPlayer(playerId, threshold, evalYear, evalWeek);
            } catch (Exception e) {
                log.error("Failed processing neglected skills for player={}: {}", playerId, e.getMessage(), e);
            }
        }
    }
}
