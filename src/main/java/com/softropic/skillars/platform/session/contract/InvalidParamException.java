package com.softropic.skillars.platform.session.contract;

public class InvalidParamException extends RuntimeException {

    public InvalidParamException(String paramName) {
        super("Invalid or missing request parameter: " + paramName);
    }
}
