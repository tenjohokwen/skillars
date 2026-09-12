package com.softropic.skillars.infrastructure.ses;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.GetAccountRequest;
import software.amazon.awssdk.services.sesv2.model.GetAccountResponse;
import software.amazon.awssdk.services.sesv2.model.SendQuota;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hermetic unit test for {@link SesHealthIndicator} — no Spring context, a mocked {@link
 * SesV2Client}. Story ses-1.3 AC6.
 */
@DisplayName("SES Health Indicator")
class SesHealthIndicatorTest {

    private SesV2Client client;
    private SesHealthIndicator indicator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(SesV2Client.class);
        // Same double-dispatch trick as SesEmailSenderTest: the Consumer overload is a default
        // method that builds a real GetAccountRequest and delegates to the abstract
        // getAccount(GetAccountRequest) overload, which is stubbed normally per test.
        when(client.getAccount(any(Consumer.class))).thenCallRealMethod();
        indicator = new SesHealthIndicator(client);
    }

    private void stubResponse(GetAccountResponse response) {
        when(client.getAccount(any(GetAccountRequest.class))).thenReturn(response);
    }

    private static GetAccountResponse.Builder healthyResponseBuilder() {
        return GetAccountResponse.builder()
            .sendingEnabled(true)
            .productionAccessEnabled(true)
            .enforcementStatus("HEALTHY")
            .sendQuota(SendQuota.builder()
                .max24HourSend(50000.0)
                .maxSendRate(14.0)
                .sentLast24Hours(120.0)
                .build());
    }

    @Test
    @DisplayName("all three conditions hold -> UP")
    void up_whenAllThreeConditionsHold() {
        stubResponse(healthyResponseBuilder().build());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry("sendingEnabled", true)
            .containsEntry("productionAccessEnabled", true)
            .containsEntry("enforcementStatus", "HEALTHY")
            .containsEntry("sendQuota.max24HourSend", 50000.0)
            .containsEntry("sendQuota.maxSendRate", 14.0)
            .containsEntry("sendQuota.sentLast24Hours", 120.0);
    }

    @Test
    @DisplayName("sendingEnabled=false -> DOWN even when the other two conditions hold")
    void down_whenSendingEnabledFalse() {
        stubResponse(healthyResponseBuilder().sendingEnabled(false).build());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    /**
     * The specific scenario this indicator exists to catch: a sandboxed account still reports
     * {@code sendingEnabled=true} (sandbox restricts recipients, not the sending switch).
     */
    @Test
    @DisplayName("productionAccessEnabled=false (sandboxed) -> DOWN even though sendingEnabled=true")
    void down_whenProductionAccessEnabledFalse() {
        stubResponse(healthyResponseBuilder().productionAccessEnabled(false).build());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("enforcementStatus=SHUTDOWN -> DOWN even when the other two conditions hold")
    void down_whenEnforcementStatusShutdown() {
        stubResponse(healthyResponseBuilder().enforcementStatus("SHUTDOWN").build());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("getAccount throws SdkException -> DOWN, message carried into the error detail")
    void down_whenGetAccountThrowsSdkException() {
        when(client.getAccount(any(GetAccountRequest.class)))
            .thenThrow(SdkException.builder().message("connection reset").build());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("error", "connection reset");
    }

    @Test
    @DisplayName("a null sendQuota produces a valid Health with the quota details simply absent, not an exception")
    void nullSendQuota_doesNotNpeAndOmitsQuotaDetails() {
        stubResponse(GetAccountResponse.builder()
            .sendingEnabled(true)
            .productionAccessEnabled(true)
            .enforcementStatus("HEALTHY")
            .build());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .doesNotContainKey("sendQuota.max24HourSend")
            .doesNotContainKey("sendQuota.maxSendRate")
            .doesNotContainKey("sendQuota.sentLast24Hours");
    }

    /**
     * Code review 2026-09-12. The decision logic was already null-safe via {@code Boolean.TRUE.equals},
     * but the detail writes passed the raw nullable SDK members into {@code Health.Builder.withDetail},
     * which does {@code Assert.notNull} on the value. A response missing all three top-level fields —
     * exactly the hand-written partial stub the class javadoc names — therefore threw
     * {@code IllegalArgumentException}, which is not an {@code SdkException} and so escaped the catch.
     * Worse, it escaped before {@code cache.set(...)}, leaving the TTL cache permanently unpopulated.
     */
    @Test
    @DisplayName("a response missing every top-level field degrades to DOWN with those details omitted, and still caches")
    void partialResponse_doesNotThrowAndStillPopulatesTheCache() {
        stubResponse(GetAccountResponse.builder().build());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
            .doesNotContainKey("sendingEnabled")
            .doesNotContainKey("productionAccessEnabled")
            .doesNotContainKey("enforcementStatus");

        // The cache must have been populated despite the missing fields — the regression that made
        // every scrape re-issue a live GetAccount call.
        indicator.health();
        verify(client, times(1)).getAccount(any(GetAccountRequest.class));
    }

    /**
     * A {@code SendQuota} that is present but only partly populated — the sibling of the
     * null-{@code sendQuota} case, and the one a hand-written stub is most likely to produce.
     */
    @Test
    @DisplayName("a partly-populated sendQuota omits only its absent fields")
    void partialSendQuota_omitsOnlyTheAbsentQuotaFields() {
        stubResponse(GetAccountResponse.builder()
            .sendingEnabled(true)
            .productionAccessEnabled(true)
            .enforcementStatus("HEALTHY")
            .sendQuota(SendQuota.builder().maxSendRate(14.0).build())
            .build());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry("sendQuota.maxSendRate", 14.0)
            .doesNotContainKey("sendQuota.max24HourSend")
            .doesNotContainKey("sendQuota.sentLast24Hours");
    }

    /**
     * Polarity check: an absent {@code enforcementStatus} means "could not read the enforcement
     * state", which is never grounds to report UP — the same polarity the two boxed booleans get
     * from {@code Boolean.TRUE.equals}.
     */
    @Test
    @DisplayName("an absent enforcementStatus is DOWN, not silently healthy")
    void absentEnforcementStatus_isDown() {
        stubResponse(GetAccountResponse.builder()
            .sendingEnabled(true)
            .productionAccessEnabled(true)
            .build());

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("a second call inside the TTL is served from cache — no second getAccount invocation")
    void secondCallInsideTtl_doesNotReinvokeGetAccount() {
        stubResponse(healthyResponseBuilder().build());

        Health first = indicator.health();
        Health second = indicator.health();

        assertThat(first.getStatus()).isEqualTo(Status.UP);
        assertThat(second.getStatus()).isEqualTo(Status.UP);
        verify(client, times(1)).getAccount(any(GetAccountRequest.class));
    }
}
