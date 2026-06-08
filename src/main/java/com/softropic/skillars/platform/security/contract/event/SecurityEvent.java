package com.softropic.skillars.platform.security.contract.event;

import java.util.HashMap;
import java.util.Map;

public abstract class SecurityEvent<T> {

    protected final String message;
    protected final T exception;
    protected final Map<String, Object> context = new HashMap<>();
    protected final String helpCode;

    protected SecurityEvent(T exception, String helpCode) {
        this.helpCode = helpCode;
        this.message = "";
        this.exception = exception;
    }

    protected SecurityEvent(String message, T exception, String helpCode) {
        this.message = message;
        this.exception = exception;
        this.helpCode = helpCode;
    }

    protected SecurityEvent(String message, T exception, Map<String, Object> context, String helpCode) {
        this.message = message;
        this.exception = exception;
        this.helpCode = helpCode;
        this.context.putAll(context);
    }

    public String getMessage() {
        return message;
    }

    public T getException() {
        return exception;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public String getHelpCode() {
        return helpCode;
    }
}
