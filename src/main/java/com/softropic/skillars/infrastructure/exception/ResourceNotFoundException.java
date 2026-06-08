package com.softropic.skillars.infrastructure.exception;

import java.util.Map;

public class ResourceNotFoundException extends ApplicationException {
    private final String resourceName;
    public ResourceNotFoundException(String msg, String resourceName) {
        super(msg, ResourceError.RESOURCE_NOT_FOUND);
        this.resourceName = resourceName;
    }

    public ResourceNotFoundException(String msg, Map<String, Object> logContext, String resourceName) {
        super(msg, logContext, ResourceError.RESOURCE_NOT_FOUND);
        this.resourceName = resourceName;
    }

    public String getResourceName() {
        return resourceName;
    }

    public enum ResourceError implements ErrorCode {
        RESOURCE_NOT_FOUND;


        @Override
        public String getErrorCode() {
            return this.name();
        }
    }
}
