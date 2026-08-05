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
// assignableTypes is additive to basePackages (OR semantics): AdminReviewResource lives in
// platform.admin.api but raises review-domain errors, so it needs this advice's review-shaped
// responses. The class is referenced fully-qualified rather than imported to avoid an
// import-level reviews.api → admin.api dependency; admin.api already depends on reviews.contract.
// Note this routes EVERY exception from AdminReviewResource here, including bean-validation
// failures on /block — see AdminReviewQueueIT#blockReview_blankReason_... which pins that contract.
@RestControllerAdvice(
    basePackages = "com.softropic.skillars.platform.reviews.api",
    assignableTypes = com.softropic.skillars.platform.admin.api.AdminReviewResource.class)
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
                || ReviewErrorCode.ALREADY_FLAGGED.getErrorCode().equals(code)
                || ReviewErrorCode.ALREADY_APPROVED.getErrorCode().equals(code)
                || ReviewErrorCode.ALREADY_BLOCKED.getErrorCode().equals(code)) {
            status = HttpStatus.CONFLICT;
        } else if (ReviewErrorCode.COACH_PROFILE_MISSING.getErrorCode().equals(code)) {
            // Deliberately 500, not 409 (code review 2026-08-05 overrode deferred-13 AC4, which
            // specified CONFLICT). An orphaned coach_profiles row is a data-integrity failure the
            // caller did not cause and cannot resolve by retrying — unlike ALREADY_BLOCKED, which is
            // a genuine conflict. Filing it as 4xx would hide it in the "user error" bucket where
            // 5xx-keyed alerting never sees it. The state is unreachable in production today
            // (GdprErasureService anonymises coach profiles, never deletes them), so if this ever
            // fires it should page. Logged at ERROR below for the same reason.
            // This branch must stay explicit: the else fallback is 403, so an unlisted code would
            // silently become FORBIDDEN rather than reaching any 5xx.
            log.error("Data-integrity failure: {} — {}", code, ex.getMessage());
            status = HttpStatus.INTERNAL_SERVER_ERROR;
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
