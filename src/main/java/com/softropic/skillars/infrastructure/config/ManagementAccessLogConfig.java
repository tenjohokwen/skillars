package com.softropic.skillars.infrastructure.config;

import com.softropic.skillars.infrastructure.web.Slf4jAccessLogValve;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextType;
import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * Same SLF4J access log as {@link AccessLogConfig}, but for the actuator/management server.
 *
 * <p>A separate class is required because {@code management.server.port} (8367) puts the actuator
 * in its own <em>child</em> application context. Spring Boot's
 * {@code WebServerFactoryCustomizerBeanPostProcessor} resolves customizers from the context it
 * lives in and does not search ancestors, so the {@link WebServerFactoryCustomizer} bean declared
 * in {@link AccessLogConfig} never reaches the management server's Tomcat factory.
 *
 * <p>{@link ManagementContextConfiguration} is the supported hook for contributing beans to that
 * child context. It is not found by classpath scanning — the class is listed in
 * {@code META-INF/spring/org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration.imports},
 * and deleting that entry silently disables management access logging.
 *
 * <h2>Why the port guard in the customizer is load-bearing</h2>
 *
 * <p>{@link ManagementContextConfiguration} is meta-annotated {@code @Configuration}
 * ({@code @AliasFor(annotation = Configuration.class)}), so this class is registered
 * <strong>twice</strong>: once into the management child context by the imports file above, and
 * once into the <em>main</em> context by component scanning, because it sits under
 * {@code com.softropic.skillars} — the package {@code @SpringBootApplication} scans. Spring Boot's
 * own management configurations avoid this only by living outside the application's scan path.
 *
 * <p>Without the guard, the scanned copy adds a second valve to the <em>main</em> engine and every
 * request to 9990 is logged twice: once as {@code skillars.access} and again as
 * {@code skillars.access.management}. That was observed, not theorised.
 *
 * <p>Removing the duplicate registration outright is not available: {@code @SpringBootApplication}
 * exposes no {@code excludeFilters}, and a sibling {@code @ComponentScan} would not suppress the
 * meta-annotated one (both are collected as repeatable annotations, so both scans run). Dropping
 * the annotation is also not an option — {@code ManagementContextConfigurationImportSelector}
 * reads it via {@code getAnnotationAttributes} and would NPE on a class that lacks it. So the
 * duplicate bean is tolerated and made inert instead.
 *
 * <p>{@link ManagementContextType#CHILD} and {@link ManagementPortType#DIFFERENT} together
 * restrict this to the separate-port case, which is what makes the port comparison unambiguous: if
 * the management port were unset or equal to {@code server.port}, actuator endpoints would be
 * served by the main context and {@link AccessLogConfig}'s valve would already cover them.
 */
@Slf4j
@ManagementContextConfiguration(value = ManagementContextType.CHILD, proxyBeanMethods = false)
@ConditionalOnManagementPort(ManagementPortType.DIFFERENT)
@ConditionalOnProperty(name = "app.access-log.enabled", havingValue = "true", matchIfMissing = true)
public class ManagementAccessLogConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> managementAccessLogValveCustomizer(
            @Value("${app.access-log.pattern}") String pattern,
            @Value("${management.server.port}") int managementPort) {
        return factory -> {
            // See the class javadoc: this customizer also exists in the main context, where it
            // must do nothing. Ordering makes the comparison safe — Spring Boot's own factory
            // customizers set the port at order 0, while this bean declares no order and so runs
            // at LOWEST_PRECEDENCE, after the port is populated.
            if (factory.getPort() != managementPort) {
                return;
            }
            Slf4jAccessLogValve valve =
                new Slf4jAccessLogValve(Slf4jAccessLogValve.MANAGEMENT_ACCESS_LOGGER_NAME);
            valve.setPattern(pattern);
            valve.setRequestAttributesEnabled(true);
            factory.addEngineValves(valve);
            log.info("Management access log routed to SLF4J logger '{}'",
                Slf4jAccessLogValve.MANAGEMENT_ACCESS_LOGGER_NAME);
        };
    }
}
