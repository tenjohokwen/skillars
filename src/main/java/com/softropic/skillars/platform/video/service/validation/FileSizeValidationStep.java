package com.softropic.skillars.platform.video.service.validation;

import com.softropic.skillars.platform.video.config.VideoProperties;
import com.softropic.skillars.platform.video.contract.UploadValidationRequest;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
import com.softropic.skillars.platform.video.service.VideoValidationStep;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component("videoFileSizeValidationStep")
@Order(10)
@RequiredArgsConstructor
public class FileSizeValidationStep implements VideoValidationStep {

    private final VideoProperties properties;

    @Override
    public void validate(UploadValidationRequest request) {
        if (request.fileSizeBytes() > properties.getUpload().getMaxBytes()) {
            throw new VideoValidationException(
                "File size %d bytes exceeds maximum %d bytes".formatted(
                    request.fileSizeBytes(), properties.getUpload().getMaxBytes()));
        }
    }
}
