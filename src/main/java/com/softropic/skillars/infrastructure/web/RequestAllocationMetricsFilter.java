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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records the heap each request allocates on its thread as {@code http.server.allocated} (bytes),
 * tagged by method and route, so the "Performance" dashboard can answer which calls load memory.
 * JVM-wide heap and GC metrics say that memory is under pressure but never which endpoint caused it.
 *
 * <p>Routes come from the matched Spring pattern, so cardinality is bounded by the app's mappings. A request
 * no controller matched reports the resource handler's catch-all pattern {@code /**}; {@code UNKNOWN} is only for
 * a request that never reached MVC (rejected by the security chain).
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
    /** Anything else (scanners send arbitrary tokens) shares one series, so the method tag is bounded. */
    private static final Set<String> KNOWN_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final String UNKNOWN = "UNKNOWN";
    private static final String OTHER_METHOD = "OTHER";

    private final ObjectProvider<MeterRegistry> meterRegistry;
    /** One summary per method and route: building and registering one per request would allocate in the filter that measures allocation. */
    private final ConcurrentHashMap<String, DistributionSummary> summaries = new ConcurrentHashMap<>();
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
            String method = KNOWN_METHODS.contains(request.getMethod()) ? request.getMethod() : OTHER_METHOD;
            String uri = route == null ? UNKNOWN : route.toString();
            summaries.computeIfAbsent(method + ' ' + uri, key -> DistributionSummary.builder(METRIC)
                    .baseUnit("bytes")
                    .description("Heap allocated on the request thread")
                    .tag("method", method)
                    .tag("uri", uri)
                    .register(registry))
                .record(threads.getThreadAllocatedBytes(threadId) - before);
        }
    }
}
