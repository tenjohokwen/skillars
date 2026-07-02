package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.development.repo.SluTargetRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class NeglectedSkillDetectionService {

    // 80% of the PT30M lockAtMostFor on detectNeglectedSkills(). The per-player loop below is
    // un-chunked and this job only fires weekly, so — unlike a fixedDelay job — bailing out early
    // isn't safe (the remaining players wouldn't be picked up for another week). Instead this is a
    // runtime tripwire: crossing it logs loudly so lockAtMostFor can be raised (per-player work is
    // idempotent, guarded by the idx_neglected_flags_open partial unique index from V49) before player
    // growth actually lets ShedLock force-expire the lock mid-run and start an overlapping instance.
    private static final Duration RUNTIME_WARNING_THRESHOLD = Duration.ofMinutes(24);

    private final SluTargetRepository sluTargetRepository;
    private final NeglectedSkillProcessor processor;
    private final ConfigService configService;

    @PostConstruct
    void validateThresholdOnStartup() {
        BigDecimal threshold = readThreshold();
        if (threshold == null || !isInValidRange(threshold)) {
            log.warn("slu.neglected.threshold is out of valid range (0,1) or unreadable at startup: {} — "
                + "neglected-skill detection will be skipped until corrected", threshold);
        }
    }

    @Scheduled(cron = "${app.development.neglected-detection-cron:0 0 6 * * MON}")
    @SchedulerLock(name = "NeglectedSkillDetectionService_detect",
                   lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    public void detectNeglectedSkills() {
        BigDecimal threshold = readThreshold();
        if (threshold == null) {
            log.error("Neglected skill detection aborted — invalid or missing slu.neglected.threshold config");
            return;
        }
        if (!isInValidRange(threshold)) {
            log.error("Neglected skill detection aborted — slu.neglected.threshold {} is out of valid range (0,1)",
                threshold);
            return;
        }

        // Evaluate the PREVIOUS completed ISO week — the job fires Monday morning when
        // the current week has barely started.
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime evaluated = now.minusWeeks(1);
        short evalYear = (short) evaluated.get(IsoFields.WEEK_BASED_YEAR);
        short evalWeek = (short) evaluated.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);

        List<Long> playerIds = sluTargetRepository.findDistinctPlayerIds();
        Instant started = Instant.now();
        boolean runtimeWarningLogged = false;
        for (int i = 0; i < playerIds.size(); i++) {
            Long playerId = playerIds.get(i);
            try {
                processor.processPlayer(playerId, threshold, evalYear, evalWeek);
            } catch (Exception e) {
                log.error("Failed processing neglected skills for player={}: {}", playerId, e.getMessage(), e);
            }

            if (!runtimeWarningLogged && Duration.between(started, Instant.now()).compareTo(RUNTIME_WARNING_THRESHOLD) > 0) {
                runtimeWarningLogged = true;
                log.warn("Neglected skill detection has been running for over {} (lockAtMostFor budget is PT30M) "
                    + "after processing {}/{} players — raise lockAtMostFor on NeglectedSkillDetectionService_detect "
                    + "before player growth lets ShedLock force-expire the lock mid-run",
                    RUNTIME_WARNING_THRESHOLD, i + 1, playerIds.size());
            }
        }
    }

    private BigDecimal readThreshold() {
        try {
            return new BigDecimal(configService.getString("slu.neglected.threshold"));
        } catch (IllegalStateException | NumberFormatException | NullPointerException e) {
            log.warn("slu.neglected.threshold is missing or malformed: {}", e.getMessage());
            return null;
        }
    }

    private boolean isInValidRange(BigDecimal threshold) {
        return threshold.compareTo(BigDecimal.ZERO) > 0 && threshold.compareTo(BigDecimal.ONE) < 0;
    }
}
