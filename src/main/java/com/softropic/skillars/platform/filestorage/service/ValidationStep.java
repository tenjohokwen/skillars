package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.platform.filestorage.contract.ValidationRequest;

public interface ValidationStep {
    void validate(ValidationRequest request);
}
