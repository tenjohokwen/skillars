package com.softropic.skillars.infrastructure.web;

import com.softropic.skillars.infrastructure.util.Constants;
import com.softropic.skillars.infrastructure.security.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPatternParser;
import org.thymeleaf.util.Validate;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Servlet filter that emits structured lifecycle log events for every HTTP request.
 *
 * <p>Emits three events per request:
 * <ul>
 *   <li>{@code request_start} — logged at INFO before the filter chain runs</li>
 *   <li>{@code request_end} — logged at INFO after the filter chain completes (non-5xx)</li>
 *   <li>{@code request_error} — logged at ERROR after the filter chain completes (5xx only)</li>
 * </ul>
 *
 * <p>Populates MDC with {@code requestId} before the first log call so that all downstream
 * log lines within the same request thread carry the same requestId. Uses
 * {@link MDC#remove(String)} (never {@link MDC#clear()}) to preserve traceId/spanId injected
 * by micrometer-tracing-bridge-otel.
 *
 * <p>Satisfies LOG-REQ-01, LOG-REQ-02, LOG-REQ-03, and the requestId portion of LOG-MDC-01.
 */
@Slf4j
public class LoggingFilter extends OncePerRequestFilter {

    private final List<PathPatternRequestMatcher> staticResourcesMatchers;

    public LoggingFilter(List<String> ignoredStaticResources) {
        Validate.notEmpty(ignoredStaticResources, "The ignored static resources list should not be null");

        staticResourcesMatchers = ignoredStaticResources.stream()
                                                        .map(pattern -> PathPatternRequestMatcher.withPathPatternParser(PathPatternParser.defaultInstance)
                                                                                                 .matcher(pattern))
                                                        .toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Async re-entry: no lifecycle events, no MDC writes
        if (isAsyncDispatch(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 1. Resolve requestId — use incoming header or generate a new one
        String incomingId = request.getHeader(Constants.REQUEST_ID_HEADER_NAME);
        String requestId = (incomingId != null && !incomingId.isBlank())
                ? incomingId
                : UUID.randomUUID().toString();

        // 2. Populate MDC BEFORE the first log call so requestId appears in request_start
        MDC.put(Constants.REQUEST_ID_NAME, requestId);

        String method    = request.getMethod();
        String path      = request.getRequestURI();
        String operation = deriveOperation(method, path);
        String clientIp  = resolveClientIp(request);

        // 3. request_start — emitted before filter chain runs
        log.info("Request received",
                kv("event",     "request_start"),
                kv("operation", operation),
                kv("method",    method),
                kv("path",      path),
                kv("requestId", requestId),
                kv("clientIp",  clientIp));

        long startMs = System.currentTimeMillis();

        try {
            filterChain.doFilter(request, response);

            long   durationMs = System.currentTimeMillis() - startMs;
            int    httpStatus = response.getStatus();
            String tenantId   = TenantContext.get(); // null for JWT paths — expected

            if (httpStatus >= 500) {
                // request_error — 5xx only
                log.error("Request error",
                        kv("event",      "request_error"),
                        kv("operation",  operation),
                        kv("durationMs", durationMs),
                        kv("errorCode",  "HTTP_" + httpStatus),
                        kv("status",     "ERROR"),
                        kv("httpStatus", httpStatus));
            } else {
                // request_end — conditionally include tenantId when available
                List<Object> args = new ArrayList<>();
                args.add(kv("event",      "request_end"));
                args.add(kv("operation",  operation));
                args.add(kv("durationMs", durationMs));
                args.add(kv("status",     "SUCCESS"));
                args.add(kv("httpStatus", httpStatus));
                if (tenantId != null) {
                    args.add(kv("tenantId", tenantId));
                }
                log.info("Request completed", args.toArray());
            }

        } finally {
            // Remove only the key we set — never MDC.clear() — preserves traceId/spanId
            MDC.remove(Constants.REQUEST_ID_NAME);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return staticResourcesMatchers.stream().anyMatch(matcher -> matcher.matches(request));
    }

    /**
     * Maps HTTP method + path to a low-cardinality operation name suitable for Loki labels.
     * No UUIDs, amounts, or other per-request data must appear in the result.
     */
    private static String deriveOperation(String method, String path) {
        if (path.startsWith("/v1/account")) {
            return "account_" + method.toLowerCase();
        }
        // Fallback: method + sanitised path — strip leading/trailing underscores
        String sanitised = path.replace("/", "_")
                               .replaceAll("^_", "")
                               .replaceAll("_+$", "");
        return method.toLowerCase() + "_" + sanitised;
    }

    /**
     * Resolves the real client IP, preferring the {@code Forwarded} header set by a
     * reverse proxy over the raw remote address.
     */
    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("Forwarded");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded;
        }
        return request.getRemoteAddr();
    }
}
