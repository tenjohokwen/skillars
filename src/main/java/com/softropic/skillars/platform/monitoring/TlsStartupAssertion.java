package com.softropic.skillars.platform.monitoring;

import com.softropic.skillars.infrastructure.exception.AppSetupException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Startup assertion that verifies TLS certificate verification is enabled for all
 * provider HTTP connections in non-dev environments.
 *
 * <p>Fires on {@link ApplicationReadyEvent} — after all beans are wired and the
 * application is ready to serve requests. This is the correct fail-fast point for
 * operational assertions (not {@code @PostConstruct} which runs before full wiring).
 *
 * <p>Pitfall 1 guard: skips assertion when the {@code dev} profile is active.
 * In dev/sandbox, {@code application.yaml} sets {@code checkCertificate: false} via
 * the {@code *DEFAULT_TCP} YAML anchor — the assertion must not block local development.
 *
 * <p>Production remediation: set {@code client.momo.tcp-config.check-certificate: true}
 * in production {@code application.yaml} (or environment-specific override).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TlsStartupAssertion implements ApplicationListener<ApplicationReadyEvent> {

    private final Environment env;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // Pitfall 1 fix: skip in dev profile — checkCertificate:false is expected in dev/sandbox
        if (Arrays.asList(env.getActiveProfiles()).contains("dev")) {
            log.debug("TlsStartupAssertion skipped — dev profile is active");
            return;
        }

        // Read the shared TLS flag. Both Orange and MTN use the same *DEFAULT_TCP anchor
        // in application.yaml (client.momo.tcpConfig.checkCertificate).
        // Default = true so the assertion passes if the property is absent (safe-by-default).
        boolean checkCertificate = env.getProperty(
                "client.momo.tcp-config.check-certificate", Boolean.class, true);

        if (!checkCertificate) {
            throw new AppSetupException(
                    "TLS certificate verification is disabled for one or more providers. " +
                    "Set checkCertificate: true in application.yaml for production deployments.");
        }

        log.info("TLS assertion passed — all provider connections have checkCertificate=true");
    }
}
