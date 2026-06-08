package com.softropic.skillars.platform.filestorage.service.validation;

import com.softropic.skillars.platform.filestorage.contract.ValidationRequest;
import com.softropic.skillars.platform.filestorage.service.ValidationStep;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.text.Normalizer;

@Component
@Order(1)
public class FilenameSanitizationStep implements ValidationStep {

    @Override
    public void validate(ValidationRequest request) {
        String filename = request.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            request.setOriginalFilename("unnamed");
            return;
        }
        filename = Normalizer.normalize(filename, Normalizer.Form.NFC);
        filename = filename.replace("\0", "");
        filename = filename.replaceAll("[\\x00-\\x1F\\x7F]", "");
        filename = filename.replaceAll("\\.\\.[/\\\\]|[/\\\\]\\.\\.", "").replaceAll("^\\./", "");
        if (filename.length() > 255) {
            filename = filename.substring(0, 255);
        }
        request.setOriginalFilename(filename);
    }
}
