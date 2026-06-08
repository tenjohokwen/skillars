package com.softropic.skillars.platform.filestorage.service.validation;

import com.softropic.skillars.platform.filestorage.config.FileStorageProperties;
import com.softropic.skillars.platform.filestorage.contract.ValidationRequest;
import com.softropic.skillars.platform.filestorage.contract.exception.StorageValidationException;
import com.softropic.skillars.platform.filestorage.service.ValidationStep;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(30)
@RequiredArgsConstructor
public class FileSizeValidationStep implements ValidationStep {

    private final FileStorageProperties properties;

    @Override
    public void validate(ValidationRequest request) {
        if (request.getFileSizeBytes() > properties.getValidation().getMaxSizeBytes()) {
            throw new StorageValidationException(
                "File size %d bytes exceeds maximum %d bytes".formatted(
                    request.getFileSizeBytes(), properties.getValidation().getMaxSizeBytes()));
        }
    }
}
