package com.softropic.skillars.platform.session.api;

import com.softropic.skillars.infrastructure.message.ErrorDto;
import com.softropic.skillars.infrastructure.message.ErrorMsg;
import com.softropic.skillars.platform.session.contract.exception.DrillConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class SessionApiAdvice {

    @ExceptionHandler(DrillConstraintViolationException.class)
    public ResponseEntity<ErrorDto> drillConstraintViolationHandler(DrillConstraintViolationException ex) {
        log.debug("Drill constraint violated: field={}, msg={}", ex.getField(), ex.getMessage());
        ErrorDto dto = new ErrorDto("video.constraintViolated", new ErrorMsg("video.constraintViolated", ex.getMessage()));
        dto.add("drill", ex.getField(), new ErrorMsg(ex.getField(), ex.getMessage()));
        return ResponseEntity.unprocessableEntity().body(dto);
    }
}
