package com.softropic.skillars.infrastructure.exception;

import org.sqids.Sqids;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ApplicationException extends RuntimeException {
    public static final String SUPPORT_ID = "SUPPORT_ID: ";
    private static final Sqids SQIDS = Sqids.builder().alphabet("ZG8K7aeb9hALF3OcTw5SNMQqC1oVJvtEsljDnIfx0zyH2rdRpmYUkP46guXiBW").build();

    protected final Map<String, Object> logContext = new HashMap<>();

    protected ErrorCode errorCode;

    private final String supportId;


    public ApplicationException(String msg) {
        super(msg);
        supportId = SQIDS.encode(List.of(Integer.toUnsignedLong(UUID.randomUUID().hashCode())));
    }

    public ApplicationException(String msg, ErrorCode errorCode) {
        this(msg, null, new HashMap<>(), errorCode);
    }

    public ApplicationException(String msg, Throwable cause, ErrorCode errorCode) {
        this(msg, cause, new HashMap<>(), errorCode);
    }

    public ApplicationException(String msg, Map<String, Object> logContext) {
        this(msg, null, logContext, null);
    }

    public ApplicationException(String msg, Map<String, Object> logContext, ErrorCode errorCode) {
        this(msg, null, logContext,errorCode);
    }

    public ApplicationException(String msg, Throwable cause, Map<String, Object> logContext, ErrorCode errorCode) {
        super(msg, cause);
        supportId = SQIDS.encode(List.of(Integer.toUnsignedLong(UUID.randomUUID().hashCode())));
        this.logContext.putAll(logContext);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    private String enhanceMsg(String msg) {
        return String.format("%s: %s,  ERROR_CODE: %s, MESSAGE: %s", SUPPORT_ID, supportId, errorCode, msg);
    }

    public Map<String, Object> getLogContext() {
        return logContext;
    }

    @Override
    public String getMessage() {
        return enhanceMsg(super.getMessage());
    }

    public String getSupportId() {
        return supportId;
    }

}
