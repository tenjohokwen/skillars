package com.softropic.skillars.infrastructure.ses;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.GetAccountRequest;
import software.amazon.awssdk.services.sesv2.model.GetAccountResponse;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hermetic unit test for {@link SesAccountChecker} — no Spring context, a mocked {@link
 * SesV2Client}. Mirrors {@code SesHealthIndicatorTest}'s pattern exactly. skillars-deferred-114 AC5.
 */
@DisplayName("SES Account Checker (live, uncached)")
class SesAccountCheckerTest {

    private SesV2Client client;
    private SesAccountChecker checker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(SesV2Client.class);
        // Same double-dispatch trick as SesHealthIndicatorTest: the Consumer overload is a default
        // method that builds a real GetAccountRequest and delegates to the abstract
        // getAccount(GetAccountRequest) overload, which is stubbed normally per test.
        when(client.getAccount(any(Consumer.class))).thenCallRealMethod();
        checker = new SesAccountChecker(client);
    }

    private void stubResponse(GetAccountResponse response) {
        when(client.getAccount(any(GetAccountRequest.class))).thenReturn(response);
    }

    @Test
    @DisplayName("a healthy account maps sendingEnabled/productionAccessEnabled/enforcementStatus straight through, no error")
    void healthyAccount_mapsFieldsThrough_noError() {
        stubResponse(GetAccountResponse.builder()
            .sendingEnabled(true)
            .productionAccessEnabled(true)
            .enforcementStatus("HEALTHY")
            .build());

        SesAccountChecker.AccountStatus status = checker.checkLive();

        assertThat(status.sendingEnabled()).isTrue();
        assertThat(status.productionAccessEnabled()).isTrue();
        assertThat(status.enforcementStatus()).isEqualTo("HEALTHY");
        assertThat(status.error()).isNull();
    }

    @Test
    @DisplayName("a partial response (missing fields) degrades to false/null rather than throwing")
    void partialResponse_degradesGracefully() {
        stubResponse(GetAccountResponse.builder().build());

        SesAccountChecker.AccountStatus status = checker.checkLive();

        assertThat(status.sendingEnabled()).isFalse();
        assertThat(status.productionAccessEnabled()).isFalse();
        assertThat(status.enforcementStatus()).isNull();
        assertThat(status.error()).isNull();
    }

    @Test
    @DisplayName("getAccount throws SdkException -> error populated, both booleans false")
    void getAccountThrowsSdkException_errorPopulated() {
        when(client.getAccount(any(GetAccountRequest.class)))
            .thenThrow(SdkException.builder().message("missing ses:GetAccount permission").build());

        SesAccountChecker.AccountStatus status = checker.checkLive();

        assertThat(status.error()).isEqualTo("missing ses:GetAccount permission");
        assertThat(status.sendingEnabled()).isFalse();
        assertThat(status.productionAccessEnabled()).isFalse();
        assertThat(status.enforcementStatus()).isNull();
    }

    @Test
    @DisplayName("every call issues a live GetAccount — never cached")
    void everyCall_issuesALiveGetAccount() {
        stubResponse(GetAccountResponse.builder()
            .sendingEnabled(true).productionAccessEnabled(true).enforcementStatus("HEALTHY").build());

        checker.checkLive();
        checker.checkLive();
        checker.checkLive();

        verify(client, times(3)).getAccount(any(GetAccountRequest.class));
    }
}
