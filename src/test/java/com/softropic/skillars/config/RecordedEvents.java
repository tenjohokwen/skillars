package com.softropic.skillars.config;

import org.springframework.context.ApplicationEvent;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records every event published in the shared integration-test context, for tests that assert an
 * event was (or was not) published.
 *
 * <p><strong>Why one shared bean and not a per-class capture config.</strong> Each class that
 * {@code @Import}-ed its own capture class (or declared a nested {@code @TestConfiguration}) forked
 * its own Spring context — four whole contexts spent on four list-of-events beans (story
 * skillars-deferred-160 AC3). Declared once in {@link TestConfig}, it is part of every context and
 * forks none.
 *
 * <p><strong>Why not Spring's {@code @RecordApplicationEvents}.</strong> {@code ApplicationEvents}
 * records per thread ({@code ApplicationEventsHolder} is a {@code ThreadLocal}). The concurrency
 * tests that use this publish their events on worker threads, which that mechanism silently drops.
 * This listener records on whichever thread publishes, at publish time — the same plain
 * {@code @EventListener} semantics the per-class captures had.
 *
 * <p>Cleared before every test method by {@link DatabaseResetTestExecutionListener}, after the async
 * executors are quiesced, so a test never sees the previous test's events. Tests that need a clean
 * slate mid-method may call {@link #clear()} themselves.
 */
public class RecordedEvents {

    private final List<Object> events = new CopyOnWriteArrayList<>();

    /**
     * {@code ApplicationEvent}, not {@code Object}: with an {@code Object} parameter Spring hands
     * over the {@link PayloadApplicationEvent} wrapper rather than the payload, so POJO events would
     * never match {@link #ofType}. Unwrapping here records what the publisher passed.
     */
    @EventListener
    public void on(ApplicationEvent event) {
        events.add(event instanceof PayloadApplicationEvent<?> payload ? payload.getPayload() : event);
    }

    public <T> List<T> ofType(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    public void clear() {
        events.clear();
    }
}
