package com.softropic.skillars.infrastructure.message;

import com.softropic.skillars.infrastructure.exception.ApplicationException;

import org.slf4j.Logger;
import org.sqids.Sqids;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.entries;

/**
 * Shared "log an error and mint a support/help code" mechanism.
 *
 * <p>Extracted from {@code ApiAdvice.logError} (skillars-deferred-90 AC5) so filter-origin 401s
 * ({@code JWTAuthorizationFilter.writeUnauthorized}) get the same {@code helpCode} + error-log line
 * that {@code @RestControllerAdvice}-handled errors already do. The caller passes its own
 * {@link Logger} so each log line keeps the category of the class that produced the error.
 */
public final class ErrorLog {

    private static final Sqids SQIDS = Sqids.builder()
            .alphabet("ZG8K7aeb9hALF3OcTw5SNMQqC1oVJvtEsljDnIfx0zyH2rdRpmYUkP46guXiBW")
            .build();

    private ErrorLog() {
    }

    /**
     * Logs {@code throwable} at ERROR against {@code log} with a generated (or, for an
     * {@link ApplicationException}, reused) support id appended to {@code msgTemplate}, and returns
     * that id so it can be surfaced to the client as a {@code helpCode}.
     */
    public static String logError(final Logger log, final Throwable throwable, final String msgTemplate) {
        return log(log, throwable, msgTemplate, true);
    }

    /**
     * As {@link #logError}, but logs at WARN <em>without</em> the stack trace.
     *
     * <p>Code review of skillars-deferred-90 (D2): {@code JWTAuthorizationFilter.writeUnauthorized}
     * runs on an unauthenticated, unrate-limited path, and its two highest-volume causes —
     * {@code MissingAuthenticationException} (every tokenless request: crawlers, stale bookmarks,
     * pre-login SPA routes) and {@code JWTExpiredException} (every idle-out) — are ordinary, not
     * errors. Emitting ERROR + a full stack trace for those is the same volume problem that made
     * AC5 scope {@code SecurityAlertEvent} away from them. The helpCode is still minted and
     * returned to the client, so the AC5 contract is unchanged.
     */
    public static String logExpected(final Logger log, final Throwable throwable, final String msgTemplate) {
        return log(log, throwable, msgTemplate, false);
    }

    private static String log(final Logger log, final Throwable throwable, final String msgTemplate,
                              final boolean asError) {
        final String helpCode;
        Map<String, Object> ctx = new HashMap<>();
        if (throwable instanceof ApplicationException applicationException) {
            helpCode = applicationException.getSupportId();
            ctx = applicationException.getLogContext();
        } else {
            helpCode = SQIDS.encode(List.of(Integer.toUnsignedLong(UUID.randomUUID().hashCode())));
        }

        final String fullMsg = String.format(msgTemplate + " SUPPORT_ID: %s", helpCode);
        if (asError) {
            log.error(fullMsg, entries(ctx), throwable);
        } else {
            log.warn(fullMsg, entries(ctx));
        }
        return helpCode;
    }
}
