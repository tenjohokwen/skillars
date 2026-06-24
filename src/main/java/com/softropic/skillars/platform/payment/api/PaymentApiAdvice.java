package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.infrastructure.message.ErrorDto;
import com.softropic.skillars.infrastructure.message.ErrorMsg;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.softropic.skillars.platform.payment.api")
public class PaymentApiAdvice {

    @ExceptionHandler(PaymentGatewayException.class)
    public ResponseEntity<ErrorDto> paymentGatewayExceptionHandler(PaymentGatewayException ex) {
        if ("payment.coachStripeNotConfigured".equals(ex.getErrorCode())) {
            log.warn("Booking blocked — coach Stripe not configured: {}", ex.getErrorCode());
        } else {
            log.error("Payment gateway error: {}", ex.getErrorCode(), ex);
        }
        return ResponseEntity.unprocessableEntity()
            .body(new ErrorDto(ex.getErrorCode(), new ErrorMsg(ex.getErrorCode(), ex.getErrorCode())));
    }
}
