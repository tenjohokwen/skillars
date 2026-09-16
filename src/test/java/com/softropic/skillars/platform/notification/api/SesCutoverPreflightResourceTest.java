package com.softropic.skillars.platform.notification.api;

import com.softropic.skillars.infrastructure.ses.SesAccountChecker;
import com.softropic.skillars.platform.notification.api.SesCutoverPreflightResource.SesCutoverPreflightRequest;
import com.softropic.skillars.platform.notification.api.SesCutoverPreflightResource.SesPreflightResult;
import com.softropic.skillars.platform.notification.contract.EmailDeliveryStatus;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntity;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;
import com.softropic.skillars.platform.notification.service.MailManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-114 AC5: hermetic unit test for {@link SesCutoverPreflightResource}'s
 * combining logic — no Spring context. The live SES SDK call itself is covered separately by
 * {@code SesAccountCheckerTest} (mirroring {@code SesHealthIndicatorTest}'s mocked-{@code
 * SesV2Client} pattern); {@link SesAccountChecker} is mocked here since
 * {@code EmailTransportArchitectureTest} forbids this {@code platform.notification.api} class from
 * ever importing the SES SDK itself. The send PATH (real circuit breaker, real retry template, real
 * send) is covered separately by {@link SesCutoverPreflightResourceIT}.
 */
@DisplayName("SES Cutover Preflight Resource — combining logic")
class SesCutoverPreflightResourceTest {

    private SesAccountChecker sesAccountChecker;
    private MailManager mailManager;
    private EnvelopeEntityRepository envelopeEntityRepository;
    private SesCutoverPreflightResource resource;

    @BeforeEach
    void setUp() {
        sesAccountChecker = mock(SesAccountChecker.class);
        mailManager = mock(MailManager.class);
        envelopeEntityRepository = mock(EnvelopeEntityRepository.class);
        resource = new SesCutoverPreflightResource(sesAccountChecker, mailManager, envelopeEntityRepository);
    }

    private void stubAccount(boolean sendingEnabled, boolean productionAccessEnabled, String enforcementStatus, String error) {
        when(sesAccountChecker.checkLive())
            .thenReturn(new SesAccountChecker.AccountStatus(sendingEnabled, productionAccessEnabled, enforcementStatus, error));
    }

    private void stubSentTestSend() {
        EnvelopeEntity sent = new EnvelopeEntity();
        sent.setStatus(EmailDeliveryStatus.SENT);
        when(envelopeEntityRepository.findBySendId(anyString())).thenReturn(sent);
    }

    private void stubFailedTestSend(String error) {
        EnvelopeEntity failed = new EnvelopeEntity();
        failed.setStatus(EmailDeliveryStatus.FAILED);
        failed.setError(error);
        when(envelopeEntityRepository.findBySendId(anyString())).thenReturn(failed);
    }

    @Test
    @DisplayName("healthy account + successful send -> ready=true, no errors")
    void healthyAccountAndSuccessfulSend_readyTrue() {
        stubAccount(true, true, "HEALTHY", null);
        stubSentTestSend();

        SesPreflightResult result = resource.runPreflight(new SesCutoverPreflightRequest("admin@skillars-test.com")).getBody();

        assertThat(result.accountCheck().sendingEnabled()).isTrue();
        assertThat(result.accountCheck().productionAccessEnabled()).isTrue();
        assertThat(result.accountCheck().enforcementStatus()).isEqualTo("HEALTHY");
        assertThat(result.accountCheck().error()).isNull();
        assertThat(result.testSend().status()).isEqualTo("SENT");
        assertThat(result.testSend().error()).isNull();
        assertThat(result.ready()).isTrue();
    }

    @Test
    @DisplayName("sandboxed account (productionAccessEnabled=false) -> ready=false, distinguishable from a send failure")
    void sandboxedAccount_readyFalse_distinctFromSendFailure() {
        stubAccount(true, false, "HEALTHY", null);
        stubSentTestSend();

        SesPreflightResult result = resource.runPreflight(new SesCutoverPreflightRequest("admin@skillars-test.com")).getBody();

        assertThat(result.accountCheck().productionAccessEnabled()).isFalse();
        assertThat(result.accountCheck().error()).isNull();
        assertThat(result.testSend().status()).isEqualTo("SENT");
        assertThat(result.ready())
            .as("an admin must be able to tell 'fix IAM/sandbox' apart from 'fix the send' — here the "
                + "account isn't ready even though the send itself succeeded")
            .isFalse();
    }

    @Test
    @DisplayName("account check reports an error -> distinguishable from a send failure")
    void accountCheckError_distinctFromSendFailure() {
        stubAccount(false, false, null, "missing ses:GetAccount permission");
        stubSentTestSend();

        SesPreflightResult result = resource.runPreflight(new SesCutoverPreflightRequest("admin@skillars-test.com")).getBody();

        assertThat(result.accountCheck().error()).isEqualTo("missing ses:GetAccount permission");
        assertThat(result.accountCheck().sendingEnabled()).isFalse();
        assertThat(result.accountCheck().productionAccessEnabled()).isFalse();
        assertThat(result.ready()).isFalse();
    }

    @Test
    @DisplayName("healthy account but a failed test send -> ready=false, testSend carries the error")
    void healthyAccountButFailedSend_readyFalse() {
        stubAccount(true, true, "HEALTHY", null);
        stubFailedTestSend("SES SendEmail permission denied");

        SesPreflightResult result = resource.runPreflight(new SesCutoverPreflightRequest("admin@skillars-test.com")).getBody();

        assertThat(result.accountCheck().error()).isNull();
        assertThat(result.testSend().status()).isEqualTo("FAILED");
        assertThat(result.testSend().error()).isEqualTo("SES SendEmail permission denied");
        assertThat(result.ready())
            .as("account fine but the send itself broke — distinguishable from an account-setup problem")
            .isFalse();
    }

    @Test
    @DisplayName("enforcementStatus=SHUTDOWN -> ready=false even when both booleans are true")
    void enforcementStatusShutdown_readyFalse() {
        stubAccount(true, true, "SHUTDOWN", null);
        stubSentTestSend();

        SesPreflightResult result = resource.runPreflight(new SesCutoverPreflightRequest("admin@skillars-test.com")).getBody();

        assertThat(result.ready()).isFalse();
    }
}
