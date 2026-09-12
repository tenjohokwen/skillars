package com.softropic.skillars.infrastructure.email.smtp;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.infrastructure.email.OutboundEmailRequest;
import com.softropic.skillars.infrastructure.email.OutboundEmailResult;
import com.softropic.skillars.infrastructure.email.OutboundEmailSender;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story ses-1.2 code review 2026-09-11, owner decision (a) — closes a real gap: nothing in the suite
 * previously booted the shipped {@code transport=smtp} path through real component scanning and real
 * YAML property binding.
 *
 * <p>{@code AbstractIntegrationTest} is {@code @ActiveProfiles({"dev","test"})}, and {@code
 * application-test.yaml:134} pins {@code app.email.transport: log} — the {@code test} profile is
 * applied last and wins, so every other IT sharing that context (including {@code MailManagerIT})
 * actually wires {@code LoggingEmailSender}, never {@code SmtpEmailSender}, whatever {@code
 * application-dev.yaml} says. {@code TransportWiringTest} proves bean gating with a hand-picked
 * {@code ApplicationContextRunner} — no component scan, no YAML binding at all. {@code
 * EmailTransportBootIT} boots an empty {@code @Configuration} with no component scan either. None of
 * those exercises the actual path that ships to dev/uat: component scan discovering {@code
 * SmtpEmailSender}, {@code app.email.smtp.provider-configs} binding into {@code SmtpProperties}, and
 * {@code MailService} receiving a real {@link OutboundEmailSender} bean it can send through.
 *
 * <h2>Why {@code @DynamicPropertySource}, not {@code @TestPropertySource}</h2>
 *
 * The fake SMTP server's port is only known at runtime, after it has bound to an ephemeral port — a
 * static {@code @TestPropertySource(properties = ...)} value can't express that. {@code
 * @DynamicPropertySource} is method-level, not a class-level {@code @TestPropertySource} annotation,
 * so {@code IntegrationTestConventionTest.testPropertySourceCountIsPinned()} — which only inspects
 * {@code getDeclaredAnnotation(TestPropertySource.class)} — does not (and structurally cannot) see
 * this class; its pinned count is unaffected. The underlying cost that governance test cares about
 * (one extra forked {@code ApplicationContext}) is the same either way, and is accepted here for the
 * same reason {@code @TestPropertySource} forks are accepted elsewhere: this is the only place the
 * shipped {@code transport=smtp} wiring can be verified end-to-end, not merely that a property bound.
 * Containers stay shared ({@link AbstractIntegrationTest}'s Postgres/Redis are JVM-static, not
 * context-scoped), so this forks a new {@code ApplicationContext} only, not new containers.
 */
class SmtpTransportBootIT extends AbstractIntegrationTest {

    private static FakeSmtpServer fakeSmtp;

    @DynamicPropertySource
    static void smtpTransportProperties(DynamicPropertyRegistry registry) {
        try {
            fakeSmtp = new FakeSmtpServer();
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to start FakeSmtpServer", ex);
        }
        registry.add("app.email.transport", () -> "smtp");
        registry.add("app.email.smtp.provider-configs[0].name", () -> "fake");
        registry.add("app.email.smtp.provider-configs[0].host", () -> "localhost");
        registry.add("app.email.smtp.provider-configs[0].port", () -> String.valueOf(fakeSmtp.port()));
        registry.add("app.email.smtp.provider-configs[0].username", () -> "fake-user");
        registry.add("app.email.smtp.provider-configs[0].password", () -> "fake-password");
    }

    @AfterAll
    static void stopFakeSmtp() {
        if (fakeSmtp != null) {
            fakeSmtp.close();
        }
    }

    @Autowired
    private OutboundEmailSender outboundEmailSender;

    @Test
    @DisplayName("the shipped transport=smtp path — real component scan + real YAML binding — completes a real send")
    void shippedSmtpTransport_wiresSmtpEmailSender_andCompletesARealSend() {
        assertThat(outboundEmailSender)
            .as("real component scan, not a hand-picked ApplicationContextRunner, must find SmtpEmailSender")
            .isInstanceOf(SmtpEmailSender.class);

        OutboundEmailRequest request = new OutboundEmailRequest(
            "player@example.com", "Boot IT subject", "<p>boot IT body</p>", null, "boot-it-correlation-id");

        OutboundEmailResult result = outboundEmailSender.send(request);

        assertThat(result.messageId()).startsWith("smtp:");
        assertThat(fakeSmtp.transcript())
            .as("a real SMTP conversation must have happened over the socket, not just bean wiring: %s",
                fakeSmtp.transcript())
            .anyMatch(line -> line.toUpperCase(java.util.Locale.ROOT).startsWith("MAIL FROM"))
            .anyMatch(line -> line.toUpperCase(java.util.Locale.ROOT).startsWith("RCPT TO"))
            .anyMatch(line -> line.toUpperCase(java.util.Locale.ROOT).startsWith("DATA"));
        assertThat(fakeSmtp.lastMessageBody())
            .as("the actual MIME message built by SmtpEmailSender must have reached the server")
            .contains("Subject: Boot IT subject")
            .contains("boot IT body");
    }
}
