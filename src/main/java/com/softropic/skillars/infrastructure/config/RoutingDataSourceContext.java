package com.softropic.skillars.infrastructure.config;

/**
 * Thread-scoped routing key consulted by {@link RoutingDataSource}. A caller sets a key immediately
 * before entering a transaction it wants routed to a non-default target {@code DataSource}, and
 * clears it in a {@code finally} block once that call has returned.
 *
 * <p>This only works because transaction begin (and therefore the real connection acquisition) is
 * synchronous on the calling thread in this codebase — no key must ever be set across an async
 * dispatch or a thread handoff, since it would then either leak onto an unrelated later transaction
 * on that pooled thread or simply never be observed by the intended one.
 */
public final class RoutingDataSourceContext {

    private static final ThreadLocal<String> CURRENT_KEY = new ThreadLocal<>();

    private RoutingDataSourceContext() {
    }

    public static void set(String key) {
        CURRENT_KEY.set(key);
    }

    public static String get() {
        return CURRENT_KEY.get();
    }

    public static void clear() {
        CURRENT_KEY.remove();
    }
}
