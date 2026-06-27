package com.softropic.skillars.platform.messaging.api;

import com.softropic.skillars.infrastructure.message.ErrorDto;
import com.softropic.skillars.infrastructure.message.ErrorMsg;
import com.softropic.skillars.platform.messaging.contract.MessagingErrorCode;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.softropic.skillars.platform.messaging.api")
public class MessagingApiAdvice {

    @ExceptionHandler(OperationNotAllowedException.class)
    public ResponseEntity<ErrorDto> handleOperationNotAllowed(OperationNotAllowedException ex) {
        String code = ex.getErrorCode() != null
            ? ex.getErrorCode().getErrorCode()
            : "messaging.error";
        ErrorDto body = new ErrorDto(code, new ErrorMsg(code, ex.getMessage()));
        HttpStatus status;
        if (MessagingErrorCode.INVALID_CONTENT.getErrorCode().equals(code)) {
            status = HttpStatus.BAD_REQUEST;
        } else if (MessagingErrorCode.ALREADY_REPORTED.getErrorCode().equals(code)
                || MessagingErrorCode.ALREADY_DELETED.getErrorCode().equals(code)) {
            status = HttpStatus.CONFLICT;
        } else {
            status = HttpStatus.FORBIDDEN;
        }
        return ResponseEntity.status(status).body(body);
    }
}
