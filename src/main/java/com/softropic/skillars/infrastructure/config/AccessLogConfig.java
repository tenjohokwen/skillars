package com.softropic.skillars.infrastructure.config;

import com.softropic.skillars.infrastructure.web.Slf4jAccessLogValve;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the Tomcat access log as an SLF4J-backed engine valve.
 *
 * <p>Replaces Spring Boot's file-based {@code server.tomcat.accesslog.*} valve, which was disabled
 * because it could never work here — see {@link Slf4jAccessLogValve} for the detail. Access log
 * entries now travel the normal logback pipeline and land in Loki alongside application logs.
 *
 * <p>This covers the main server (port 9990) only. Spring Boot runs the actuator in a separate
 * child context whose customizers are resolved without searching ancestors, so this bean never
 * reaches it; {@link ManagementAccessLogConfig} does the same job there.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.access-log.enabled", havingValue = "true", matchIfMissing = true)
public class AccessLogConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> accessLogValveCustomizer(
            @Value("${app.access-log.pattern}") String pattern) {
        return factory -> {
            Slf4jAccessLogValve valve = new Slf4jAccessLogValve();
            valve.setPattern(pattern);
            // The pattern logs %a (client address) while `server.forward-headers-strategy: native`
            // puts a RemoteIpValve ahead of this one. Without this flag %a reports the proxy's
            // address rather than the real client's, because the valve reads the raw connection
            // instead of the attributes RemoteIpValve sets.
            valve.setRequestAttributesEnabled(true);
            factory.addEngineValves(valve);
            log.info("Tomcat access log routed to SLF4J logger '{}'", Slf4jAccessLogValve.ACCESS_LOGGER_NAME);
        };
    }
}
