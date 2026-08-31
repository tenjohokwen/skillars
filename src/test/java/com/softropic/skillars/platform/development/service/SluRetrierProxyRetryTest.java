package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.repo.PlayerSkillStat;
import com.softropic.skillars.platform.development.repo.SluRepository;
import com.softropic.skillars.platform.development.repo.SnapshotBatchWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.TransactionSystemException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Proves the declarative {@code @Retryable}/{@code @Recover} AOP wiring on
 * {@link SluPersistenceRetrier} and {@link SnapshotPersistenceRetrier} is actually live — the six
 * plain-instantiation unit tests structurally cannot (they bypass the proxy by construction, and one
 * of them explicitly pins the un-proxied no-retry behaviour). Deleting {@code @Retryable} or
 * {@code @Recover} from either retrier, changing {@code retryFor}, or drifting a {@code @Recover}
 * signature would leave those green; this class fails. (It does <em>not</em> cover the production
 * {@code @EnableRetry} on {@code SkillarsApplication} — {@link Config} declares its own — so a
 * removal of that annotation is caught by the full application-context tests, not here.)
 *
 * <p>Named {@code *Test}, not {@code *IT}: it starts no container, so Surefire is the right phase,
 * and the {@code *Test} name keeps it out of {@code IntegrationTestConventionTest}'s {@code *IT}
 * scan (a {@code @SpringJUnitConfig} test is not a slice and does not extend
 * {@code AbstractIntegrationTest}). It loads one tiny standalone context ({@code @EnableRetry} +
 * two real retriers + two Mockito mocks), a deterministic {@code +1} on {@code missCount}.
 */
@SpringJUnitConfig(SluRetrierProxyRetryTest.Config.class)
class SluRetrierProxyRetryTest {

    private static final short ISO_YEAR = 2026;
    private static final short ISO_WEEK = 35;

    @Configuration
    @EnableRetry
    static class Config {

        @Bean
        SluRepository sluRepository() {
            return Mockito.mock(SluRepository.class);
        }

        @Bean
        SnapshotBatchWriter snapshotBatchWriter() {
            return Mockito.mock(SnapshotBatchWriter.class);
        }

        @Bean
        SluPersistenceRetrier sluPersistenceRetrier(SluRepository sluRepository) {
            return new SluPersistenceRetrier(sluRepository);
        }

        @Bean
        SnapshotPersistenceRetrier snapshotPersistenceRetrier(SnapshotBatchWriter snapshotBatchWriter) {
            return new SnapshotPersistenceRetrier(snapshotBatchWriter);
        }
    }

    @Autowired private SluPersistenceRetrier sluPersistenceRetrier;
    @Autowired private SnapshotPersistenceRetrier snapshotPersistenceRetrier;
    @Autowired private SluRepository sluRepository;
    @Autowired private SnapshotBatchWriter snapshotBatchWriter;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(sluRepository, snapshotBatchWriter);
    }

    private static List<PlayerSkillStat> oneRow() {
        return List.of(new PlayerSkillStat());
    }

    // --- SluPersistenceRetrier ---

    @Test
    void saveSluWithRetry_persistentDataAccessException_retriesToMaxThenRecovers() {
        doThrow(new TransientDataAccessResourceException("db unavailable"))
            .when(sluRepository).saveAll(any());

        assertThatCode(() -> sluPersistenceRetrier.saveSluWithRetry(oneRow()))
            .doesNotThrowAnyException();

        verify(sluRepository, times(3)).saveAll(any());
    }

    @Test
    void saveSluWithRetry_persistentTransactionException_retriesToMaxThenRecovers() {
        doThrow(new TransactionSystemException("commit failed"))
            .when(sluRepository).saveAll(any());

        assertThatCode(() -> sluPersistenceRetrier.saveSluWithRetry(oneRow()))
            .doesNotThrowAnyException();

        verify(sluRepository, times(3)).saveAll(any());
    }

    @Test
    void saveSluWithRetry_succeedsOnSecondAttempt_noRecover() {
        doThrow(new TransientDataAccessResourceException("blip"))
            .doReturn(List.of())
            .when(sluRepository).saveAll(any());

        assertThatCode(() -> sluPersistenceRetrier.saveSluWithRetry(oneRow()))
            .doesNotThrowAnyException();

        verify(sluRepository, times(2)).saveAll(any());
    }

    // --- SnapshotPersistenceRetrier ---

    @Test
    void writeAllWithRetry_persistentDataAccessException_retriesToMaxThenRecovers() {
        doThrow(new TransientDataAccessResourceException("db unavailable"))
            .when(snapshotBatchWriter).writeAll(any(), anyShort(), anyShort());

        assertThatCode(() -> snapshotPersistenceRetrier.writeAllWithRetry(oneRow(), ISO_YEAR, ISO_WEEK))
            .doesNotThrowAnyException();

        verify(snapshotBatchWriter, times(3)).writeAll(any(), anyShort(), anyShort());
    }

    @Test
    void writeAllWithRetry_persistentTransactionException_retriesToMaxThenRecovers() {
        doThrow(new TransactionSystemException("commit failed"))
            .when(snapshotBatchWriter).writeAll(any(), anyShort(), anyShort());

        assertThatCode(() -> snapshotPersistenceRetrier.writeAllWithRetry(oneRow(), ISO_YEAR, ISO_WEEK))
            .doesNotThrowAnyException();

        verify(snapshotBatchWriter, times(3)).writeAll(any(), anyShort(), anyShort());
    }

    @Test
    void writeAllWithRetry_succeedsOnSecondAttempt_noRecover() {
        doThrow(new TransientDataAccessResourceException("blip"))
            .doNothing()
            .when(snapshotBatchWriter).writeAll(any(), anyShort(), anyShort());

        assertThatCode(() -> snapshotPersistenceRetrier.writeAllWithRetry(oneRow(), ISO_YEAR, ISO_WEEK))
            .doesNotThrowAnyException();

        verify(snapshotBatchWriter, times(2)).writeAll(any(), anyShort(), anyShort());
    }
}
