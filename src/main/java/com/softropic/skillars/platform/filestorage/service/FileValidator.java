package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.platform.filestorage.contract.ValidationRequest;

public interface FileValidator {
    void validate(ValidationRequest request);
}
