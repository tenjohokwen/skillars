package com.softropic.skillars.platform.video.api;

import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.infrastructure.message.ErrorDto;
import com.softropic.skillars.infrastructure.message.ErrorMsg;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import com.softropic.skillars.platform.video.contract.VideoErrorCode;
import com.softropic.skillars.platform.video.contract.exception.PlaybackDeniedException;
import com.softropic.skillars.platform.video.contract.exception.QuotaExceededException;
import com.softropic.skillars.platform.video.contract.exception.TerminalStateViolationException;
import com.softropic.skillars.platform.video.contract.exception.VideoDeletionNotAuthorisedException;
import com.softropic.skillars.platform.video.contract.exception.VideoApprovalNotFoundException;
import com.softropic.skillars.platform.video.contract.exception.VideoAlreadyResolvedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.contract.exception.VideoProviderException;
import com.softropic.skillars.platform.video.contract.exception.VideoSessionExpiredException;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
import com.softropic.skillars.platform.video.service.VideoMetrics;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.sqids.Sqids;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.entries;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class VideoApiAdvice {

    private static final Sqids SQIDS = Sqids.builder()
        .alphabet("ZG8K7aeb9hALF3OcTw5SNMQqC1oVJvtEsljDnIfx0zyH2rdRpmYUkP46guXiBW")
        .build();

    private final MessageSource messageSource;
    private final VideoMetrics videoMetrics;

    public VideoApiAdvice(MessageSource messageSource, VideoMetrics videoMetrics) {
        this.messageSource = messageSource;
        this.videoMetrics = videoMetrics;
    }

    @ExceptionHandler(VideoNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorDto videoNotFoundHandler(final VideoNotFoundException ex) {
        ErrorDto dto = logErrorAndReturnDTO(ex, "video.notFound", VideoErrorCode.VIDEO_NOT_FOUND.getErrorCode());
        videoMetrics.recordError(operationFromMdc(), VideoErrorCode.VIDEO_NOT_FOUND.getErrorCode());
        return dto;
    }

    @ExceptionHandler(VideoValidationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorDto videoValidationHandler(final VideoValidationException ex) {
        ErrorDto dto = logErrorAndReturnDTO(ex, "video.validationFailed", VideoErrorCode.VALIDATION_FAILED.getErrorCode());
        videoMetrics.recordError(operationFromMdc(), VideoErrorCode.VALIDATION_FAILED.getErrorCode());
        return dto;
    }

    @ExceptionHandler(QuotaExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ErrorDto videoQuotaExceededHandler(final QuotaExceededException ex) {
        ErrorDto dto = logErrorAndReturnDTO(ex, "video.quotaExceeded", VideoErrorCode.QUOTA_EXCEEDED.getErrorCode());
        videoMetrics.recordError(operationFromMdc(), VideoErrorCode.QUOTA_EXCEEDED.getErrorCode());
        return dto;
    }

    @ExceptionHandler(PlaybackDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorDto playbackDeniedHandler(final PlaybackDeniedException ex) {
        ErrorDto dto = logErrorAndReturnDTO(ex, "video.notAccessible", "video.notAccessible");
        videoMetrics.recordError(operationFromMdc(), VideoErrorCode.PLAYBACK_DENIED.getErrorCode());
        Map<String, Object> ctx = ex.getLogContext();
        if (ctx.containsKey("operationalState")) {
            dto.add("video", "operationalState",
                    new ErrorMsg("operationalState", (String) ctx.get("operationalState")));
        }
        if (ctx.containsKey("accessState")) {
            dto.add("video", "accessState",
                    new ErrorMsg("accessState", (String) ctx.get("accessState")));
        }
        return dto;
    }

    @ExceptionHandler(VideoProviderException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ErrorDto videoProviderHandler(final VideoProviderException ex) {
        ErrorDto dto = logErrorAndReturnDTO(ex, "video.providerError", VideoErrorCode.PROVIDER_ERROR.getErrorCode());
        videoMetrics.recordError(operationFromMdc(), VideoErrorCode.PROVIDER_ERROR.getErrorCode());
        return dto;
    }

    @ExceptionHandler(VideoSessionExpiredException.class)
    @ResponseStatus(HttpStatus.GONE)
    public ErrorDto videoSessionExpiredHandler(final VideoSessionExpiredException ex) {
        ErrorDto dto = logErrorAndReturnDTO(ex, "video.sessionExpired", VideoErrorCode.SESSION_EXPIRED.getErrorCode());
        videoMetrics.recordError(operationFromMdc(), VideoErrorCode.SESSION_EXPIRED.getErrorCode());
        return dto;
    }

    @ExceptionHandler(TerminalStateViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorDto terminalStateViolationHandler(final TerminalStateViolationException ex) {
        ErrorDto dto = logErrorAndReturnDTO(ex, "video.terminalStateViolation", VideoErrorCode.TERMINAL_STATE_VIOLATION.getErrorCode());
        videoMetrics.recordError(operationFromMdc(), VideoErrorCode.TERMINAL_STATE_VIOLATION.getErrorCode());
        return dto;
    }

    @ExceptionHandler(VideoDeletionNotAuthorisedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorDto videoDeletionNotAuthorisedHandler(final VideoDeletionNotAuthorisedException ex) {
        videoMetrics.recordError(operationFromMdc(), VideoErrorCode.DELETION_NOT_AUTHORISED.getErrorCode());
        return logErrorAndReturnDTO(ex, "video.deletionNotAuthorised", "video.deletionNotAuthorised");
    }

    @ExceptionHandler(VideoApprovalNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorDto videoApprovalNotFoundHandler(final VideoApprovalNotFoundException ex) {
        ErrorDto dto = logErrorAndReturnDTO(ex, "video.approvalNotFound", VideoErrorCode.VIDEO_APPROVAL_NOT_FOUND.getErrorCode());
        videoMetrics.recordError(operationFromMdc(), VideoErrorCode.VIDEO_APPROVAL_NOT_FOUND.getErrorCode());
        return dto;
    }

    @ExceptionHandler(VideoAlreadyResolvedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorDto videoAlreadyResolvedHandler(final VideoAlreadyResolvedException ex) {
        ErrorDto dto = logErrorAndReturnDTO(ex, "video.approvalAlreadyResolved", VideoErrorCode.VIDEO_APPROVAL_ALREADY_RESOLVED.getErrorCode());
        videoMetrics.recordError(operationFromMdc(), VideoErrorCode.VIDEO_APPROVAL_ALREADY_RESOLVED.getErrorCode());
        return dto;
    }

    private String operationFromMdc() {
        String op = MDC.get("operation");
        return op != null ? op : "unknown";
    }

    private ErrorDto logErrorAndReturnDTO(Throwable throwable, String defaultMsg, String msgKey) {
        final String helpCode = logError(throwable, defaultMsg);
        return toErrorDTO(msgKey, defaultMsg, helpCode);
    }

    private ErrorDto toErrorDTO(String msgKey, String defaultMessage, String helpCode) {
        final String chosenLang = RequestMetadataProvider.getClientInfo().getChosenLang();
        String message = defaultMessage;
        if (StringUtils.isNotBlank(msgKey)) {
            message = messageSource.getMessage(msgKey, null, defaultMessage, Locale.forLanguageTag(chosenLang));
        }
        return new ErrorDto(helpCode, new ErrorMsg(msgKey, message));
    }

    private String logError(Throwable throwable, String msgTemplate) {
        String errorCode;
        Map<String, Object> ctx = new HashMap<>();
        if (throwable instanceof ApplicationException applicationException) {
            errorCode = applicationException.getSupportId();
            ctx = applicationException.getLogContext();
        } else {
            errorCode = SQIDS.encode(List.of(Integer.toUnsignedLong(UUID.randomUUID().hashCode())));
        }
        final String fullMsg = String.format(msgTemplate + " SUPPORT_ID: %s", errorCode);
        log.error(fullMsg, entries(ctx), throwable);
        return errorCode;
    }
}
