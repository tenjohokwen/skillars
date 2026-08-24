package com.softropic.skillars.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.jdbc.Work;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.Savepoint;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-tests {@link PessimisticLockRetryer}'s retry/backoff/give-up logic directly, with a
 * controllable failing {@code Supplier}. Every consuming service's own test mocks
 * {@code withBoundedRetry} to pass straight through to the wrapped lambda, so this class's actual
 * attempt-counting, savepoint rollback/release sequencing, and give-up behavior are otherwise
 * exercised only indirectly, through {@code SessionPackPurchaseLockContentionIT} for one of the
 * sixteen call sites.
 */
@ExtendWith(MockitoExtension.class)
class PessimisticLockRetryerTest {

    @Mock
    private EntityManager entityManager;
    @Mock
    private Session session;
    @Mock
    private Connection connection;
    @Mock
    private Savepoint savepoint;

    private PessimisticLockRetryer retryer;

    @BeforeEach
    void setUp() {
        retryer = new PessimisticLockRetryer();
        ReflectionTestUtils.setField(retryer, "entityManager", entityManager);
        ReflectionTestUtils.setField(retryer, "maxAttempts", 3);
        ReflectionTestUtils.setField(retryer, "initialBackoffMs", 5L);
        ReflectionTestUtils.setField(retryer, "maxBackoffMs", 10L);
        ReflectionTestUtils.setField(retryer, "backoffMultiplier", 1.5);
    }

    /** Only needed by tests that actually exercise {@code withBoundedRetry}, not {@code validateConfig}. */
    private void stubSessionAndConnectionPlumbing() throws Exception {
        when(entityManager.unwrap(Session.class)).thenReturn(session);
        when(connection.setSavepoint()).thenReturn(savepoint);
        doAnswer(invocation -> {
            Work work = invocation.getArgument(0);
            work.execute(connection);
            return null;
        }).when(session).doWork(any());
    }

    @Test
    void succeedsOnFirstAttempt_releasesSavepointAndReturnsResultWithoutRetrying() throws Exception {
        stubSessionAndConnectionPlumbing();
        AtomicInteger calls = new AtomicInteger();

        String result = retryer.withBoundedRetry(() -> {
            calls.incrementAndGet();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(1);
        verify(entityManager, times(1)).flush();
        verify(connection, times(1)).setSavepoint();
        verify(connection, times(1)).releaseSavepoint(savepoint);
        verify(connection, never()).rollback(any(Savepoint.class));
    }

    @Test
    void retriesFromASavepointAfterLockFailure_thenSucceeds() throws Exception {
        stubSessionAndConnectionPlumbing();
        AtomicInteger calls = new AtomicInteger();

        String result = retryer.withBoundedRetry(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new PessimisticLockingFailureException("row locked by another transaction");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(2);
        verify(entityManager, times(2)).flush();
        verify(connection, times(2)).setSavepoint();
        verify(connection, times(1)).rollback(savepoint);
        verify(connection, times(1)).releaseSavepoint(savepoint);
    }

    @Test
    void exhaustsRetryBudget_thenSurfacesTheLockingExceptionAfterExactlyMaxAttempts() throws Exception {
        stubSessionAndConnectionPlumbing();
        AtomicInteger calls = new AtomicInteger();
        PessimisticLockingFailureException persistent =
            new PessimisticLockingFailureException("row locked by another transaction");

        assertThatThrownBy(() -> retryer.withBoundedRetry(() -> {
            calls.incrementAndGet();
            throw persistent;
        })).isSameAs(persistent);

        assertThat(calls.get()).isEqualTo(3);
        verify(connection, times(3)).setSavepoint();
        verify(connection, times(2)).rollback(savepoint);
        verify(connection, never()).releaseSavepoint(any(Savepoint.class));
    }

    @Test
    void nonLockingException_propagatesImmediatelyWithoutRetrying() throws Exception {
        stubSessionAndConnectionPlumbing();
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException notFound = new IllegalStateException("booking not found");

        assertThatThrownBy(() -> retryer.withBoundedRetry(() -> {
            calls.incrementAndGet();
            throw notFound;
        })).isSameAs(notFound);

        assertThat(calls.get()).isEqualTo(1);
        verify(connection, never()).rollback(any(Savepoint.class));
        verify(connection, never()).releaseSavepoint(any(Savepoint.class));
    }

    @Test
    void validateConfig_rejectsZeroMaxAttempts() {
        ReflectionTestUtils.setField(retryer, "maxAttempts", 0);
        assertThatThrownBy(() -> retryer.validateConfig())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("max-attempts");
    }

    @Test
    void validateConfig_rejectsNonPositiveInitialBackoff() {
        ReflectionTestUtils.setField(retryer, "initialBackoffMs", 0L);
        assertThatThrownBy(() -> retryer.validateConfig())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("initial-backoff-ms");
    }

    @Test
    void validateConfig_rejectsMaxBackoffBelowInitialBackoff() {
        ReflectionTestUtils.setField(retryer, "initialBackoffMs", 100L);
        ReflectionTestUtils.setField(retryer, "maxBackoffMs", 50L);
        assertThatThrownBy(() -> retryer.validateConfig())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("max-backoff-ms");
    }

    @Test
    void validateConfig_rejectsBackoffMultiplierBelowOne() {
        ReflectionTestUtils.setField(retryer, "backoffMultiplier", 0.9);
        assertThatThrownBy(() -> retryer.validateConfig())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("backoff-multiplier");
    }

    @Test
    void validateConfig_acceptsTheDefaultConfiguration() {
        ReflectionTestUtils.setField(retryer, "maxAttempts", 8);
        ReflectionTestUtils.setField(retryer, "initialBackoffMs", 100L);
        ReflectionTestUtils.setField(retryer, "maxBackoffMs", 800L);
        ReflectionTestUtils.setField(retryer, "backoffMultiplier", 1.6);

        retryer.validateConfig();
    }
}
