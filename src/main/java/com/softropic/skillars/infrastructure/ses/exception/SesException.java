package com.softropic.skillars.infrastructure.ses.exception;

public class SesException extends RuntimeException {

    public SesException(String message, Throwable cause) {
        super(message, cause);
    }
}
