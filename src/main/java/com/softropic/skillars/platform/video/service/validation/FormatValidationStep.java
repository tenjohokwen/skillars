package com.softropic.skillars.platform.video.service.validation;

import com.softropic.skillars.platform.video.config.VideoProperties;
import com.softropic.skillars.platform.video.contract.UploadValidationRequest;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
import com.softropic.skillars.platform.video.service.VideoValidationStep;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(30)
@RequiredArgsConstructor
public class FormatValidationStep implements VideoValidationStep {

    private final VideoProperties properties;

    @Override
    public void validate(UploadValidationRequest request) {
        String format = request.containerFormat();
        if (format == null || properties.getUpload().getAllowedFormats().stream()
                .noneMatch(allowed -> allowed.equalsIgnoreCase(format))) {
            throw new VideoValidationException("Container format not allowed: " + format);
        }
    }
}
