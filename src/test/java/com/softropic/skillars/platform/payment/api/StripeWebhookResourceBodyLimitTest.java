package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.platform.payment.service.StripeWebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * fix/payment-charge-idempotency (L4): a chunked request has no Content-Length, so the pre-read size check
 * never fired for it and the unauthenticated endpoint buffered whatever was streamed.
 */
class StripeWebhookResourceBodyLimitTest {

    private final StripeWebhookService webhookService = mock(StripeWebhookService.class);
    private final StripeWebhookResource resource = new StripeWebhookResource(webhookService);

    /** A body with no declared length: getContentLength() is -1, as for Transfer-Encoding: chunked. */
    private static MockHttpServletRequest chunked(int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/payment/webhooks/stripe") {
            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContent(new byte[bytes]);
        request.addHeader("Transfer-Encoding", "chunked");
        return request;
    }

    @Test
    void chunkedBodyOverTheLimit_isRefused413_withoutReachingTheService() throws Exception {
        ResponseEntity<?> response = resource.handleStripeWebhook(chunked(1_048_577));

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        verify(webhookService, never()).processWebhook(anyString(), any());
    }

    @Test
    void chunkedBodyWithinTheLimit_isProcessed() throws Exception {
        ResponseEntity<?> response = resource.handleStripeWebhook(chunked(512));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(webhookService).processWebhook(anyString(), any());
    }
}
