package com.softropic.skillars.platform.outbox.repo;

import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC1 requires a SKIP LOCKED claim so the AFTER_COMMIT drain, the scheduled sweep and other pods
 * cannot claim the same row and double-dispatch a handler. That property lives entirely in
 * annotations on {@link OutboxMessageRepository#claimNextDue}, and the skillars-deferred-91 code
 * review found it had no coverage at all: {@code OutboxChunkProcessorTest} mocked the repository, so
 * deleting {@code @Lock} or the {@code lock.timeout} hint would not have failed a single test.
 *
 * <p>Asserting on annotations is deliberate. The alternative — proving SKIP LOCKED behaviourally —
 * needs two concurrent transactions against a real PostgreSQL and belongs in an IT; this guards the
 * declaration itself, cheaply, in the unit phase where a regression is most likely to slip through.
 */
class OutboxMessageRepositoryContractTest {

    private static Method claimNextDue() throws NoSuchMethodException {
        return OutboxMessageRepository.class.getMethod(
            "claimNextDue", java.time.Instant.class, org.springframework.data.domain.Pageable.class);
    }

    @Test
    @DisplayName("the claim takes a pessimistic write lock")
    void claimTakesAPessimisticWriteLock() throws NoSuchMethodException {
        Lock lock = claimNextDue().getAnnotation(Lock.class);

        assertThat(lock).as("@Lock is what turns the SELECT into SELECT … FOR UPDATE").isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    @DisplayName("the claim uses SKIP LOCKED (lock.timeout = -2), not a blocking wait")
    void claimUsesSkipLocked() throws NoSuchMethodException {
        QueryHints hints = claimNextDue().getAnnotation(QueryHints.class);

        assertThat(hints).as("without the hint the claim BLOCKS on a locked row instead of skipping it")
            .isNotNull();
        assertThat(hints.value())
            .anySatisfy(hint -> {
                assertThat(hint.name()).isEqualTo("jakarta.persistence.lock.timeout");
                // -2 is Hibernate's LockOptions.SKIP_LOCKED. 0 (NO_WAIT) or a positive wait would
                // both be wrong: concurrent drainers must step over each other's rows, not fail.
                assertThat(hint.value()).isEqualTo("-2");
            });
    }

    @Test
    @DisplayName("the claim is ordered fewest-attempts-first and filtered on the backoff")
    void claimIsOrderedAndBackoffFiltered() throws NoSuchMethodException {
        Query query = claimNextDue().getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value())
            .as("a row still inside its backoff must not be claimable (review decision D6)")
            .contains("m.nextAttemptAt <= :now");
        assertThat(query.value())
            .as("a permanently failing row must sink below fresh work, not block the head of the queue")
            .contains("ORDER BY m.attempts ASC, m.id ASC");
    }
}
