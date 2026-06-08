package com.softropic.skillars.infrastructure.exception;

import java.util.Map;

public class ConsumerNotFoundException extends ApplicationException {
    public ConsumerNotFoundException(String msg) {
        super(msg);
    }

    public ConsumerNotFoundException(String msg, Map<String, Object> logContext) {
        super(msg, logContext);
    }
}
