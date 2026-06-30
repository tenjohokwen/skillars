package com.softropic.skillars.platform.reviews.api;

import com.softropic.skillars.infrastructure.message.ErrorDto;
import com.softropic.skillars.infrastructure.message.ErrorMsg;
import com.softropic.skillars.platform.reviews.contract.ReviewErrorCode;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.softropic.skillars.platform.reviews.api")
public class ReviewApiAdvice {

    @ExceptionHandler(OperationNotAllowedException.class)
    public ResponseEntity<ErrorDto> handleOperationNotAllowed(OperationNotAllowedException ex) {
        String code = ex.getErrorCode() != null
            ? ex.getErrorCode().getErrorCode()
            : "reviews.error";
        ErrorDto body = new ErrorDto(code, new ErrorMsg(code, ex.getMessage()));
        HttpStatus status;
        if (ReviewErrorCode.ALREADY_SUBMITTED.getErrorCode().equals(code)
                || ReviewErrorCode.RESPONSE_ALREADY_SUBMITTED.getErrorCode().equals(code)
                || ReviewErrorCode.ALREADY_FLAGGED.getErrorCode().equals(code)) {
            status = HttpStatus.CONFLICT;
        } else if (ReviewErrorCode.BODY_TOO_LONG.getErrorCode().equals(code)
                || ReviewErrorCode.RESPONSE_TOO_LONG.getErrorCode().equals(code)) {
            status = HttpStatus.BAD_REQUEST;
        } else {
            // REVIEW_NOT_FOUND, CANNOT_FLAG_OWN_REVIEW, CANNOT_FLAG_OWN_COACHED_REVIEW → 403
            status = HttpStatus.FORBIDDEN;
        }
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorDto> handleValidation(MethodArgumentNotValidException ex) {
        boolean hasSizeViolationOnBody = ex.getBindingResult().getFieldErrors().stream()
            .anyMatch(fe -> "body".equals(fe.getField())
                && fe.getCode() != null && fe.getCode().startsWith("Size"));
        if (hasSizeViolationOnBody) {
            boolean isResponseRequest = "coachResponseRequest".equals(ex.getBindingResult().getObjectName());
            String code = isResponseRequest ? "reviews.responseTooLong" : "reviews.bodyTooLong";
            ErrorDto body = new ErrorDto(code, new ErrorMsg(code, "Field length exceeded"));
            return ResponseEntity.badRequest().body(body);
        }
        String detail = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
            .collect(Collectors.joining("; "));
        ErrorDto body = new ErrorDto("reviews.validationError",
            new ErrorMsg("reviews.validationError", detail));
        return ResponseEntity.badRequest().body(body);
    }
}
