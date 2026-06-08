package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.contract.UploadValidationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VideoValidationChain {

    private final List<VideoValidationStep> steps;

    public void validate(UploadValidationRequest request) {
        for (VideoValidationStep step : steps) {
            step.validate(request);
        }
    }
}
