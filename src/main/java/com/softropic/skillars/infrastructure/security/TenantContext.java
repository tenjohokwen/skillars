package com.softropic.skillars.infrastructure.security;

/**
 * ThreadLocal holder for the current tenant identifier.
 *
 * <p>Set by {@code ApiKeyAuthenticationFilter} at the start of each /v1/** request and
 * cleared in the filter's {@code finally} block so servlet-container thread pools never
 * leak tenant identity between requests.
 *
 * <p>Uses {@link ThreadLocal#remove()} — not {@code set(null)} — to prevent memory leaks
 * in pooled-thread environments.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    public static void set(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String get() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();   // remove(), NOT set(null) — avoids memory leak in thread pools
    }

    private TenantContext() {}
}
