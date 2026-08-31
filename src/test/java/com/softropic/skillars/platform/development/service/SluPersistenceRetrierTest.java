package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.repo.PlayerSkillStat;
import com.softropic.skillars.platform.development.repo.SluRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.TransactionSystemException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SluPersistenceRetrierTest {

    @Mock private SluRepository sluRepository;

    private SluPersistenceRetrier retrier;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        retrier = new SluPersistenceRetrier(sluRepository);
    }

    @Test
    void saveSluWithRetry_delegatesToRepository() {
        PlayerSkillStat stat = new PlayerSkillStat();
        stat.setSessionId(UUID.randomUUID());
        List<PlayerSkillStat> rows = List.of(stat);

        retrier.saveSluWithRetry(rows);

        verify(sluRepository).saveAll(rows);
    }

    @Test
    void recoverSluSaveFailure_logsAndDoesNotRethrow() {
        PlayerSkillStat stat = new PlayerSkillStat();
        stat.setSessionId(UUID.randomUUID());
        List<PlayerSkillStat> rows = List.of(stat);
        TransientDataAccessResourceException ex = new TransientDataAccessResourceException("db unavailable");

        // The @Recover method is the terminal handler after retries are exhausted — it must not
        // rethrow (matching the AOP contract: @Retryable's caller sees a clean return, not a
        // propagated exception), only log for ops visibility that the rows were lost.
        assertThatCode(() -> retrier.recoverSluSaveFailure(ex, rows)).doesNotThrowAnyException();
    }

    @Test
    void recoverSluSaveFailure_transactionException_logsAndDoesNotRethrow() {
        PlayerSkillStat stat = new PlayerSkillStat();
        stat.setSessionId(UUID.randomUUID());
        List<PlayerSkillStat> rows = List.of(stat);
        TransactionSystemException ex = new TransactionSystemException("commit failed");

        // The TransactionException @Recover overload is the terminal handler for a retried
        // transaction begin/commit failure — same no-rethrow contract as the DataAccessException one.
        assertThatCode(() -> retrier.recoverSluSaveFailure(ex, rows)).doesNotThrowAnyException();
    }

    @Test
    void saveSluWithRetry_repositoryThrows_propagatesToCaller() {
        List<PlayerSkillStat> rows = List.of(new PlayerSkillStat());
        TransientDataAccessResourceException ex = new TransientDataAccessResourceException("db unavailable");
        org.mockito.Mockito.doThrow(ex).when(sluRepository).saveAll(rows);

        // Without the Spring AOP proxy (plain unit instantiation), @Retryable never intercepts —
        // this pins that the un-proxied method itself is a thin pass-through with no retry logic
        // baked into the method body, confirming retries come from the proxy, not self-invocation.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> retrier.saveSluWithRetry(rows))
            .isSameAs(ex);
    }
}
