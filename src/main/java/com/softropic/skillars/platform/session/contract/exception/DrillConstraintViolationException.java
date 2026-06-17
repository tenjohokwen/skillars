package com.softropic.skillars.platform.session.contract.exception;

public class DrillConstraintViolationException extends RuntimeException {

    private final String field;

    public DrillConstraintViolationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
