package com.softropic.skillars.platform.filestorage.service.validation;

import com.softropic.skillars.platform.filestorage.config.FileStorageProperties;
import com.softropic.skillars.platform.filestorage.contract.ValidationRequest;
import com.softropic.skillars.platform.filestorage.contract.exception.StorageValidationException;
import com.softropic.skillars.platform.filestorage.service.ValidationStep;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
@RequiredArgsConstructor
public class MimeTypeValidationStep implements ValidationStep {

    private final FileStorageProperties properties;

    @Override
    public void validate(ValidationRequest request) {
        String contentType = request.getContentType();
        if (contentType == null || properties.getValidation().getAllowedMimeTypes().stream()
                .noneMatch(allowed -> allowed.equalsIgnoreCase(contentType))) {
            throw new StorageValidationException("MIME type not allowed: " + contentType);
        }
    }
}
