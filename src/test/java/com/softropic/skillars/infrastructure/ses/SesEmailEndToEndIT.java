package com.softropic.skillars.infrastructure.ses;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.skillars.infrastructure.email.EmailAddressParser;
import com.softropic.skillars.infrastructure.email.OutboundEmailRequest;
import com.softropic.skillars.infrastructure.email.OutboundEmailResult;
import com.softropic.skillars.infrastructure.email.OutboundEmailSender;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story ses-1.1 Task 4b — an end-to-end SES call against a WireMock stub, proving
 * {@link SesEmailSender} is wired correctly end to end (config, credentials, client,
 * request-building) rather than just unit-tested against a mock client.
 *
 * <p>A <strong>sliced</strong> {@code @SpringBootTest(classes = ...)} — no database, no Redis, no
 * container — added to {@code IntegrationTestConventionTest.ALLOWLIST}. It deliberately does
 * <strong>not</strong> extend {@code AbstractIntegrationTest} or reuse its two named WireMock
 * servers ({@code bunny-service}, {@code stripe-service}): a third server name sharing that context
 * would fork the shared context for a test that doesn't need any of it. {@code app.ses.from-address}
 * is pinned as an explicit test property rather than left to inherit from any profile.
 *
 * <p>Story ses-1.3 Task 6: {@link SesEmailSender} gained a {@link SesSendRateLimiter} constructor
 * dependency (AC3) — added to the {@code classes} list below, or context startup fails with a
 * {@code NoSuchBeanDefinitionException}. {@link SesSendRateLimiter} in turn needs a
 * {@link MeterRegistry} bean: this sliced context does not scan for or auto-configure one (it lists
 * exactly the beans it needs rather than bootstrapping the whole application), so {@link
 * MeterRegistryTestConfig} below supplies a bare {@link SimpleMeterRegistry}, the same as {@code
 * TransportWiringTest}'s {@code ApplicationContextRunner.withBean(MeterRegistry.class,
 * SimpleMeterRegistry::new)}.
 */
@SpringBootTest(classes = {
    SesConfig.class, SesEmailSender.class, SesErrorClassifier.class, EmailAddressParser.class,
    SesSendRateLimiter.class, SesEmailEndToEndIT.MeterRegistryTestConfig.class
}, properties = {
    "app.email.transport=ses",
    "app.ses.from-address=noreply@example.com",
    // No real AWS call happens (endpoint-url points at WireMock), but SesConfig's
    // credentialsProvider still needs SOMETHING to resolve, or client construction fails before
    // ever reaching the stub.
    "app.ses.access-key=test-access-key",
    "app.ses.secret-key=test-secret-key"
})
@EnableConfigurationProperties(SesProperties.class)
@EnableWireMock({
    @ConfigureWireMock(name = "ses-service", baseUrlProperties = "app.ses.endpoint-url")
})
class SesEmailEndToEndIT {

    @Autowired
    private OutboundEmailSender outboundEmailSender;

    @InjectWireMock("ses-service")
    private WireMockServer wireMockServer;

    @TestConfiguration
    static class MeterRegistryTestConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    void send_callsSesOverTheStubbedEndpoint_andReturnsTheMessageId() {
        stubFor(any(anyUrl()).willReturn(okJson("{\"MessageId\":\"wiremock-message-id\"}")));

        OutboundEmailRequest request = new OutboundEmailRequest(
            "player@example.com", "Welcome!", "<p>Verify your account</p>", null, "cid-e2e-1");

        OutboundEmailResult result = outboundEmailSender.send(request);

        assertThat(result).isEqualTo(new OutboundEmailResult("wiremock-message-id"));

        verify(postRequestedFor(anyUrl())
            .withRequestBody(containing("player@example.com"))
            .withRequestBody(containing("Verify your account"))
            .withRequestBody(containing("cid-e2e-1")));
    }
}
