package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.platform.filestorage.contract.ValidationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ValidationChain {

    private final List<ValidationStep> steps;

    public void validate(ValidationRequest request) {
        for (ValidationStep step : steps) {
            step.validate(request);
        }
    }
}
