package com.softropic.skillars.infrastructure.threadpool;

import com.softropic.skillars.infrastructure.security.TenantContext;

import org.springframework.core.task.TaskDecorator;

/**
 * {@link TaskDecorator} that propagates the current thread's {@link TenantContext} value
 * to async worker threads and clears it after the task completes.
 *
 * <p>Mirrors the pattern established by {@link MdcDecorator} for MDC context propagation.
 * Registered in {@code com.softropic.skillars.infrastructure.config.AsyncConfig} via
 * {@code executor.setTaskDecorator()}.
 *
 * <p>Usage: the caller thread's {@code tenantId} is captured at task submission time
 * (before dispatch), then restored inside the worker thread for the duration of the task.
 * The {@code finally} block ensures cleanup even if the task throws.
 */
public class TenantContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable task) {
        String tenantId = TenantContext.get();   // captured on caller thread
        return () -> {
            try {
                TenantContext.set(tenantId);
                task.run();
            } finally {
                TenantContext.clear();
            }
        };
    }
}
