package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.repo.PlayerSkillStat;
import com.softropic.skillars.platform.development.repo.SluRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Split into its own bean so {@code @Retryable} goes through the Spring AOP proxy — a call from
 * SluCalculationService.onBookingCompleted via {@code this.saveSluWithRetry(...)} would bypass the
 * proxy entirely and the retry would silently never fire (same self-invocation pitfall documented on
 * BookingService.acceptAndInitiatePayment and TimelineEventListener's {@code @Lazy @Autowired} self).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SluPersistenceRetrier {

    private final SluRepository sluRepository;

    @Retryable(
        retryFor = DataAccessException.class,
        maxAttemptsExpression = "${app.slu.retry.max-attempts:3}",
        backoff = @Backoff(
            delayExpression = "${app.slu.retry.backoff-initial-ms:100}",
            multiplierExpression = "${app.slu.retry.backoff-multiplier:2.0}"
        )
    )
    public void saveSluWithRetry(List<PlayerSkillStat> rows) {
        sluRepository.saveAll(rows);
    }

    @Recover
    public void recoverSluSaveFailure(DataAccessException ex, List<PlayerSkillStat> rows) {
        log.error("Failed to save SLU after retries — {} rows lost for session {}, manual recovery needed",
            rows.size(), rows.isEmpty() ? null : rows.get(0).getSessionId(), ex);
    }
}
