package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.platform.filestorage.contract.ValidationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FileValidatorImpl implements FileValidator {

    private final ValidationChain validationChain;

    @Override
    public void validate(ValidationRequest request) {
        validationChain.validate(request);
    }
}
