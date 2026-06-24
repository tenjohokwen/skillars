package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.infrastructure.message.ErrorDto;
import com.softropic.skillars.infrastructure.message.ErrorMsg;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.payment.contract.exception.WebhookSignatureException;
import com.softropic.skillars.platform.payment.service.StripeWebhookService;
import io.micrometer.observation.annotation.Observed;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/payment")
@Slf4j
@RequiredArgsConstructor
@Observed(name = "payment.stripe.webhook")
public class StripeWebhookResource {

    private final StripeWebhookService webhookService;

    private static final int MAX_WEBHOOK_BODY_BYTES = 1_048_576; // 1 MB — Stripe payloads are always small

    @PostMapping("/webhooks/stripe")
    @PreAuthorize(SecurityConstants.IS_PERMIT_ALL)
    public ResponseEntity<?> handleStripeWebhook(HttpServletRequest request) throws IOException {
        // P25: reject oversized bodies before buffering — IS_PERMIT_ALL endpoint is unauthenticated
        int contentLength = request.getContentLength();
        if (contentLength > MAX_WEBHOOK_BODY_BYTES) {
            log.warn("[STRIPE_WEBHOOK_OVERSIZED length={}]", contentLength);
            return ResponseEntity.status(413).build();
        }
        String payload = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String sigHeader = request.getHeader("Stripe-Signature");
        try {
            webhookService.processWebhook(payload, sigHeader);
            return ResponseEntity.ok().build();
        } catch (WebhookSignatureException e) {
            return ResponseEntity.badRequest()
                .body(new ErrorDto(e.getErrorCode(), new ErrorMsg(e.getErrorCode(), e.getErrorCode())));
        } catch (Exception e) {
            log.error("[STRIPE_WEBHOOK_PROCESSING_FAILED errorMessage={}]", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
