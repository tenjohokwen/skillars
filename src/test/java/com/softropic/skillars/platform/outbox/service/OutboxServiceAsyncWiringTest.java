package com.softropic.skillars.platform.outbox.service;

import com.softropic.skillars.platform.outbox.contract.event.OutboxDrainRequestedEvent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The drain must not run on the thread that committed the producing transaction
 * (skillars-deferred-91 code review, decision D3).
 *
 * <p>AFTER_COMMIT synchronizations execute on the committing thread, so without {@code @Async} the
 * drain ran inline on the HTTP request thread for up to {@code CHUNK_SIZE * MAX_CHUNKS_PER_DRAIN}
 * rows of blocking SMTP/Stripe I/O — one unlucky user absorbed the entire backlog that accumulated
 * during an outage, and AC1's "off the request path" was false.
 *
 * <p>Asserted here rather than in an IT because the integration suite deliberately binds
 * {@code outboxDrainPool} to a {@code SyncTaskExecutor} (see {@code OutboxConfig} and
 * {@code app.outbox.drain-async: false}) so that ITs can assert on a drain's effects without racing
 * it. That makes this annotation exactly the kind of production-only wiring an IT cannot protect.
 */
class OutboxServiceAsyncWiringTest {

    private static Method drainListener() throws NoSuchMethodException {
        return OutboxService.class.getMethod("onOutboxDrainRequested", OutboxDrainRequestedEvent.class);
    }

    @Test
    @DisplayName("the AFTER_COMMIT drain listener is @Async on the dedicated outbox pool")
    void drainListenerIsAsyncOnTheOutboxPool() throws NoSuchMethodException {
        Async async = drainListener().getAnnotation(Async.class);

        assertThat(async)
            .as("without @Async the drain runs on the committing (request) thread")
            .isNotNull();
        assertThat(async.value())
            .as("must use the dedicated bounded pool, not the shared application executor")
            .isEqualTo("outboxDrainPool");
    }

    @Test
    @DisplayName("the drain fires AFTER_COMMIT, never before")
    void drainFiresAfterCommit() throws NoSuchMethodException {
        TransactionalEventListener listener = drainListener().getAnnotation(TransactionalEventListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.phase())
            .as("draining before commit would dispatch work for a transaction that may still roll back")
            .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    @DisplayName("the listener and drain() are not @Transactional")
    void drainIsNotTransactional() throws NoSuchMethodException {
        // AC1's explicit anti-regression clause: a @Transactional drain pins one pooled connection
        // across every handler call for the whole backlog.
        assertThat(drainListener().getAnnotation(org.springframework.transaction.annotation.Transactional.class))
            .isNull();
        assertThat(OutboxService.class.getMethod("drain")
            .getAnnotation(org.springframework.transaction.annotation.Transactional.class))
            .isNull();
        assertThat(OutboxService.class.getAnnotation(org.springframework.transaction.annotation.Transactional.class))
            .as("a class-level @Transactional would reintroduce the same problem")
            .isNull();
    }
}
