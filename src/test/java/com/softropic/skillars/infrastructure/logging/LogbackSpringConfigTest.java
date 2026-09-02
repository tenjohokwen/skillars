package com.softropic.skillars.infrastructure.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.status.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards {@code config/logback-spring.xml} — the logging configuration every real deployment uses,
 * and which no other test touches.
 *
 * <h2>The gap this closes</h2>
 *
 * A broken logging config is not a degraded-logging problem. Spring Boot's
 * {@code LogbackLoggingSystem.reportConfigurationErrorsIfNecessary} treats any ERROR-level logback
 * status as fatal, so the JVM dies inside {@code LoggingApplicationListener} at
 * environment-prepared time — before a single bean is created, with no Spring banner and no
 * application logging to explain it. That is a hard crash loop in every environment at once.
 *
 * <p>Nothing else in the build would notice. {@code src/test/resources/application-test.yaml} sets
 * {@code logging.config: classpath:logback-test.xml}, so the {@code test} profile never parses the
 * production file; and neither {@code ci.yml} nor {@code pr-build.yml} starts the built image —
 * they run {@code mvn verify}, build, and scan. The first execution of this file is therefore on a
 * deployed node, where the only safety net is {@code deploy.yml}'s post-deploy health check and
 * auto-revert. That is exactly how PR #48 (loki4j {@code 1.5.2 -> 2.1.0}, 2026-08-13) merged green
 * while leaving the appender configured against the 1.x schema, which 2.x rejects with
 * {@code IncompatibleClassException}.
 *
 * <h2>Why {@code lokiEnabled} must be forced true</h2>
 *
 * The Loki appender lives inside {@code <if condition='property("lokiEnabled").equals("true")'>}.
 * With {@code LOKI_ENABLED} unset, {@code application.yaml} resolves {@code loki.enabled} to
 * {@code false}, the branch is skipped, and the appender is never instantiated — so a test that
 * merely parsed this file would pass against a completely broken appender. Verified: the pre-fix
 * config produces zero errors at {@code lokiEnabled=false} and exactly one at {@code true}.
 *
 * <h2>Why a plain {@link JoranConfigurator} is enough</h2>
 *
 * The file's {@code <springProperty>} elements are a Spring Boot extension, and Spring Boot's own
 * {@code SpringBootJoranConfigurator} is package-private. It is not needed here: logback downgrades
 * an unrecognised element to a WARN ("Ignoring unknown property [springProperty]"), so the rest of
 * the document — appenders, the conditional, the root logger — is parsed exactly as in production.
 * The five values those elements would have supplied are set directly on the context below. This
 * assertion is deliberately scoped to ERROR only; the file legitimately emits WARNs (the
 * {@code springProperty} notices above, plus logback's deprecation notice for the {@code condition}
 * attribute) and failing on those would make the test brittle for no safety gain.
 *
 * <p>Everything happens on a private {@link LoggerContext}, never the global one, so the suite's own
 * logging — including the {@code org.springframework.test.context.cache} DEBUG line that
 * {@code pr-build.yml}'s container-ceiling gate parses out of {@code logback-test.xml} — is
 * untouched.
 */
class LogbackSpringConfigTest {

    private static final String CONFIG = "config/logback-spring.xml";

    /** Mirrors the {@code <springProperty>} declarations at the top of the file. */
    private LoggerContext configure(String lokiEnabled) throws Exception {
        LoggerContext context = new LoggerContext();
        context.putProperty("appName", "skillars");
        context.putProperty("environment", "test");
        context.putProperty("appVersion", "test");
        context.putProperty("lokiUrl", "http://localhost:3100");
        context.putProperty("lokiEnabled", lokiEnabled);

        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CONFIG)) {
            assertThat(in).as("%s must be on the test classpath", CONFIG).isNotNull();
            configurator.doConfigure(in);
        }
        return context;
    }

    private static List<String> errors(LoggerContext context) {
        return context.getStatusManager().getCopyOfStatusList().stream()
            .filter(s -> s.getLevel() == Status.ERROR)
            .map(s -> s.getMessage() + (s.getThrowable() != null ? " (" + s.getThrowable() + ")" : ""))
            .toList();
    }

    @ParameterizedTest(name = "lokiEnabled={0}")
    @ValueSource(strings = {"true", "false"})
    void parsesWithoutErrors(String lokiEnabled) throws Exception {
        LoggerContext context = configure(lokiEnabled);
        try {
            assertThat(errors(context))
                .as("%s produced logback ERROR status(es) with lokiEnabled=%s. Spring Boot treats "
                    + "these as fatal, so this would be a startup crash loop, not a logging defect.",
                    CONFIG, lokiEnabled)
                .isEmpty();
        } finally {
            context.stop();
        }
    }

    private static final String LOKI_APPENDER = "com.github.loki4j.logback.Loki4jAppender";
    private static final String NOP_APPENDER = "ch.qos.logback.core.helpers.NOPAppender";

    /**
     * Both appenders are always attached to root by name; {@code lokiEnabled} switches which
     * <em>implementation</em> is bound to {@code LOKI}. That indirection is deliberate — {@code <root>}
     * is unconditional, so the disabled branch has to supply a {@link ch.qos.logback.core.helpers.NOPAppender}
     * rather than leave a dangling {@code appender-ref}.
     *
     * <p>Guards the two structural mistakes found while fixing the loki4j 2.x migration, both of which
     * parse cleanly and would otherwise be invisible: declaring {@code <root>} inside {@code <if>}
     * (logback processes {@code <root>} in an earlier phase, leaving the context with <em>no</em>
     * appenders at all), and dropping the NOP fallback so the reference dangles when Loki is off.
     */
    @ParameterizedTest(name = "lokiEnabled={0}")
    @ValueSource(strings = {"true", "false"})
    void attachesExpectedAppendersToRoot(String lokiEnabled) throws Exception {
        LoggerContext context = configure(lokiEnabled);
        try {
            Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            assertThat(root.getAppender("JSON"))
                .as("the console JSON appender must always be attached to root")
                .isNotNull();
            assertThat(root.iteratorForAppenders()).toIterable()
                .as("root must have at least one appender — a config that parses but attaches "
                    + "nothing logs absolutely nothing")
                .isNotEmpty();

            assertThat(root.getAppender("LOKI"))
                .as("LOKI must always resolve, so the unconditional appender-ref never dangles")
                .isNotNull();
            String expected = Boolean.parseBoolean(lokiEnabled) ? LOKI_APPENDER : NOP_APPENDER;
            assertThat(root.getAppender("LOKI").getClass().getName())
                .as("with lokiEnabled=%s the LOKI reference must bind to %s", lokiEnabled, expected)
                .isEqualTo(expected);
        } finally {
            context.stop();
        }
    }

    /**
     * The access log is routed through SLF4J by {@code Slf4jAccessLogValve} rather than written to a
     * file, so these two loggers are what carries it to the console and to Loki. Their names are a
     * contract shared with that class and with operators filtering in Grafana.
     */
    @Test
    void declaresAccessLogLoggers() throws Exception {
        LoggerContext context = configure("false");
        try {
            assertThat(context.exists("skillars.access"))
                .as("main-server access log logger must be declared")
                .isNotNull();
            assertThat(context.exists("skillars.access.management"))
                .as("management-server access log logger must be declared")
                .isNotNull();
            assertThat(Objects.requireNonNull(context.exists("skillars.access.management")).getName())
                .as("the management logger must remain a CHILD of skillars.access, so that setting "
                    + "the parent to OFF silences both")
                .startsWith("skillars.access.");
        } finally {
            context.stop();
        }
    }
}
