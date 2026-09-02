package com.softropic.skillars.infrastructure.web;

import org.apache.catalina.valves.AbstractAccessLogValve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.CharArrayWriter;

/**
 * Tomcat access log valve that emits each entry through SLF4J instead of writing it to a file.
 *
 * <p>Tomcat's stock {@code AccessLogValve} can only write to a directory on disk. That does not
 * work in this image and never did: {@code application.yaml} pointed it at the absolute path
 * {@code /usr/local/var/ledger}, which the non-root {@code appuser} created by the Dockerfile
 * cannot create, so every boot logged
 * {@code ERROR AccessLogValve - Failed to create directory [/usr/local/var/ledger]} and no access
 * log was ever produced — in any environment. Even if the directory had been writable, the entries
 * would have sat in a file inside the container that nothing collects.
 *
 * <p>Routing them through SLF4J instead puts them on the same logback pipeline as every other log
 * event, which means they reach both the console {@code JSON} appender and the {@code LOKI}
 * appender, and are queryable in Grafana alongside application logs and traces.
 *
 * <p>{@link AbstractAccessLogValve} carries all of Tomcat's pattern parsing and formatting; the
 * only abstract member is {@link #log(CharArrayWriter)}, so the access log format is still the
 * standard Tomcat pattern syntax configured by {@code app.access-log.pattern}.
 *
 * <p>Entries are logged at INFO under the dedicated {@link #ACCESS_LOGGER_NAME} logger, so the
 * access log can be silenced or re-levelled on its own without touching application logging — see
 * the matching {@code <logger>} element in {@code config/logback-spring.xml}.
 */
public class Slf4jAccessLogValve extends AbstractAccessLogValve {

    /**
     * Deliberately not this class's own name. The logger name is the handle operators use to
     * filter ({@code logger="skillars.access"} in Loki) and to re-level in logback, so it is part
     * of the observable contract — it should not change if this class is renamed or moved.
     */
    public static final String ACCESS_LOGGER_NAME = "skillars.access";

    /**
     * Access log for the actuator/management server (port 8367). A child of
     * {@link #ACCESS_LOGGER_NAME} on purpose: management traffic is overwhelmingly the container
     * healthcheck and Prometheus scrapes, so it is the half most likely to want silencing on its
     * own — while setting the parent to OFF still silences both.
     */
    public static final String MANAGEMENT_ACCESS_LOGGER_NAME = ACCESS_LOGGER_NAME + ".management";

    private final Logger accessLog;

    public Slf4jAccessLogValve() {
        this(ACCESS_LOGGER_NAME);
    }

    public Slf4jAccessLogValve(String loggerName) {
        this.accessLog = LoggerFactory.getLogger(loggerName);
    }

    @Override
    protected void log(CharArrayWriter message) {
        accessLog.info(message.toString());
    }
}
