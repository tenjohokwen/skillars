package com.softropic.skillars.platform.filestorage.service.validation;

import com.softropic.skillars.platform.filestorage.contract.ValidationRequest;
import com.softropic.skillars.platform.filestorage.contract.exception.StorageValidationException;
import com.softropic.skillars.platform.filestorage.service.ValidationStep;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
@Order(40)
public class ChecksumValidationStep implements ValidationStep {

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    @Override
    public void validate(ValidationRequest request) {
        String checksum = request.getChecksum();
        if (checksum == null || checksum.isBlank()) {
            return;
        }
        if (!SHA256_HEX.matcher(checksum).matches()) {
            throw new StorageValidationException(
                "Checksum must be a 64-character lowercase SHA-256 hex string, got: " + checksum);
        }
    }
}
