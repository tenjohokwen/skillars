package com.softropic.skillars.infrastructure.threadpool;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MdcDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // (Grab the current thread MDC data)
        final ClientThreadContext clientContext = ClientThreadContext.instance();
        return () -> {
            try {
                // Set the MDC data of caller thread in current thread
                clientContext.setClientMDCContext();
                runnable.run();
            } catch (Exception e) {
                //Log exception here so that MDC context for this thread is visible in log
                final Exception exception = ExecutorExceptionHandler.handleException(e, clientContext);
                log.error("Exception thrown from detached thread...", exception);
            } finally {
                MDC.clear();
            }
        };
    }
}
