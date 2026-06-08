package com.softropic.skillars.platform.video.service.validation;

import com.softropic.skillars.platform.video.config.VideoProperties;
import com.softropic.skillars.platform.video.contract.UploadValidationRequest;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
import com.softropic.skillars.platform.video.service.VideoValidationStep;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component("videoMimeTypeValidationStep")
@Order(20)
@RequiredArgsConstructor
public class MimeTypeValidationStep implements VideoValidationStep {

    private final VideoProperties properties;

    @Override
    public void validate(UploadValidationRequest request) {
        String mimeType = request.mimeType();
        if (mimeType == null) {
            return; // mimeType not available (retry flow) — skip MIME check
        }
        if (properties.getUpload().getAllowedMimeTypes().stream()
                .noneMatch(allowed -> allowed.equalsIgnoreCase(mimeType))) {
            throw new VideoValidationException("MIME type not allowed: " + mimeType);
        }
    }
}
