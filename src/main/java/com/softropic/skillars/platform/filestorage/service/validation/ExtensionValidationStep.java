package com.softropic.skillars.platform.filestorage.service.validation;

import com.softropic.skillars.platform.filestorage.config.FileStorageProperties;
import com.softropic.skillars.platform.filestorage.contract.ValidationRequest;
import com.softropic.skillars.platform.filestorage.contract.exception.StorageValidationException;
import com.softropic.skillars.platform.filestorage.service.ValidationStep;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
@RequiredArgsConstructor
public class ExtensionValidationStep implements ValidationStep {

    private final FileStorageProperties properties;

    @Override
    public void validate(ValidationRequest request) {
        String extension = request.getExtension();
        if (extension == null || properties.getValidation().getAllowedExtensions()
                .stream().noneMatch(allowed -> allowed.equalsIgnoreCase(extension))) {
            throw new StorageValidationException("Extension not allowed: " + extension);
        }
    }
}
