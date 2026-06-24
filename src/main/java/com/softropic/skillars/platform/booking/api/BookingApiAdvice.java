package com.softropic.skillars.platform.booking.api;

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
@RestControllerAdvice(basePackages = "com.softropic.skillars.platform.booking.api")
public class BookingApiAdvice {

    @ExceptionHandler(PaymentGatewayException.class)
    public ResponseEntity<ErrorDto> paymentGatewayExceptionHandler(PaymentGatewayException ex) {
        log.warn("Booking blocked by payment gateway: {}", ex.getErrorCode());
        return ResponseEntity.unprocessableEntity()
            .body(new ErrorDto(ex.getErrorCode(), new ErrorMsg(ex.getErrorCode(), ex.getErrorCode())));
    }
}
