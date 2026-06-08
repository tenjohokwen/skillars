package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.contract.UploadValidationRequest;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;

public interface VideoValidationStep {
    void validate(UploadValidationRequest request) throws VideoValidationException;
}
