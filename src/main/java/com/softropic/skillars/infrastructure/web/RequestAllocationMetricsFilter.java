package com.softropic.skillars.infrastructure.web;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.lang.management.ManagementFactory;

/**
 * Records the heap each request allocates on its thread as {@code http.server.allocated} (bytes),
 * tagged by method and route, so the "Performance" dashboard can answer which calls load memory.
 * JVM-wide heap and GC metrics say that memory is under pressure but never which endpoint caused it.
 *
 * <p>This is allocation, not retained memory: it counts everything the request created, including
 * short-lived garbage, and is the measure that drives GC. Work a request hands to another thread (an
 * {@code @Async} listener, the rest of an SSE stream) is not attributed to it. Sits just inside
 * {@code ServerHttpObservationFilter} so it covers the security chain and response serialization.
 *
 * <p>The registry is an {@link ObjectProvider} because {@code @WebMvcTest} slices pick up every
 * {@code Filter} bean but have no {@code MeterRegistry}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RequestAllocationMetricsFilter extends OncePerRequestFilter {

    static final String METRIC = "http.server.allocated";
    private static final String UNMATCHED_ROUTE = "UNKNOWN";

    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final com.sun.management.ThreadMXBean threads;

    public RequestAllocationMetricsFilter(ObjectProvider<MeterRegistry> meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.threads = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
            && bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled() ? bean : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (threads == null || registry == null) {
            filterChain.doFilter(request, response);
            return;
        }
        long threadId = Thread.currentThread().getId();
        long before = threads.getThreadAllocatedBytes(threadId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Read after the chain: the route pattern only exists once Spring MVC has matched a handler.
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            DistributionSummary.builder(METRIC)
                .baseUnit("bytes")
                .description("Heap allocated on the request thread")
                .tag("method", request.getMethod())
                .tag("uri", route == null ? UNMATCHED_ROUTE : route.toString())
                .register(registry)
                .record(threads.getThreadAllocatedBytes(threadId) - before);
        }
    }
}
