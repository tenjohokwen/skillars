package com.softropic.skillars.platform.outbox.contract;

/**
 * skillars-deferred-91 AC1: the handler-dispatch SPI for the generic outbox. Each domain that
 * enqueues onto {@code main.outbox_messages} registers one {@code @Component} implementing this
 * interface; {@code OutboxChunkProcessor} dispatches a claimed row to the handler whose
 * {@link #aggregateType()} matches the row's {@code aggregate_type}.
 *
 * <p><strong>{@link #handle} MUST be idempotent.</strong> A row is re-driven on the next drain
 * after any failure and is never dropped, so a repeat call on an already-completed operation must
 * be a documented no-op — mirroring {@code QuotaService.release()} and the Stripe-refund
 * idempotency key.
 */
public interface OutboxMessageHandler {

    /** The {@code aggregate_type} value this handler processes. Unique across all handlers. */
    String aggregateType();

    /**
     * Re-drives the operation described by {@code payload} (the JSON written at {@code enqueue}
     * time). Must complete the operation exactly once across any number of calls; throw to leave
     * the row for the next drain with {@code attempts++} / {@code last_error} recorded.
     */
    void handle(String payload);
}
