package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.platform.filestorage.config.FileStorageProperties;
import com.softropic.skillars.platform.filestorage.contract.ValidationRequest;
import com.softropic.skillars.platform.filestorage.contract.exception.StorageValidationException;
import com.softropic.skillars.platform.filestorage.service.validation.ChecksumValidationStep;
import com.softropic.skillars.platform.filestorage.service.validation.ExtensionValidationStep;
import com.softropic.skillars.platform.filestorage.service.validation.FilenameSanitizationStep;
import com.softropic.skillars.platform.filestorage.service.validation.FileSizeValidationStep;
import com.softropic.skillars.platform.filestorage.service.validation.MimeTypeValidationStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class ValidationChainTest {

    private FileStorageProperties props;
    private FilenameSanitizationStep sanitization;
    private MimeTypeValidationStep mimeType;
    private ExtensionValidationStep extension;
    private FileSizeValidationStep fileSize;
    private ChecksumValidationStep checksum;
    private ValidationChain chain;

    @BeforeEach
    void setUp() {
        props = new FileStorageProperties();

        FileStorageProperties.Validation validationConfig = new FileStorageProperties.Validation();
        validationConfig.setAllowedMimeTypes(List.of("image/jpeg", "application/pdf"));
        validationConfig.setAllowedExtensions(List.of("jpg", "pdf"));
        validationConfig.setMaxSizeBytes(1024 * 1024L);
        props.setValidation(validationConfig);

        sanitization = new FilenameSanitizationStep();
        mimeType = new MimeTypeValidationStep(props);
        extension = new ExtensionValidationStep(props);
        fileSize = new FileSizeValidationStep(props);
        checksum = new ChecksumValidationStep();

        chain = new ValidationChain(List.of(sanitization, mimeType, extension, fileSize, checksum));
    }

    private ValidationRequest validRequest() {
        return ValidationRequest.builder()
            .originalFilename("test.jpg")
            .contentType("image/jpeg")
            .extension("jpg")
            .fileSizeBytes(512 * 1024L)
            .build();
    }

    // --- FilenameSanitizationStep tests ---

    @Test
    void filenameSanitization_stripsPathTraversal() {
        ValidationRequest req = ValidationRequest.builder().originalFilename("../../etc/passwd").build();
        sanitization.validate(req);
        assertThat(req.getOriginalFilename()).doesNotContain("../").doesNotContain("..");
    }

    @Test
    void filenameSanitization_stripsNullBytes() {
        ValidationRequest req = ValidationRequest.builder().originalFilename("file\0name.pdf").build();
        sanitization.validate(req);
        assertThat(req.getOriginalFilename()).doesNotContain("\0");
    }

    @Test
    void filenameSanitization_stripsControlChars() {
        ValidationRequest req = ValidationRequest.builder().originalFilename("filename.pdf").build();
        sanitization.validate(req);
        assertThat(req.getOriginalFilename()).doesNotContain("");
    }

    @Test
    void filenameSanitization_truncatesTo255() {
        String longName = "a".repeat(300);
        ValidationRequest req = ValidationRequest.builder().originalFilename(longName).build();
        sanitization.validate(req);
        assertThat(req.getOriginalFilename()).hasSize(255);
    }

    @Test
    void filenameSanitization_handlesNullFilename() {
        ValidationRequest req = ValidationRequest.builder().originalFilename(null).build();
        sanitization.validate(req);
        assertThat(req.getOriginalFilename()).isEqualTo("unnamed");
    }

    @Test
    void filenameSanitization_handlesBlankFilename() {
        ValidationRequest req = ValidationRequest.builder().originalFilename("   ").build();
        sanitization.validate(req);
        assertThat(req.getOriginalFilename()).isEqualTo("unnamed");
    }

    @Test
    void filenameSanitization_nfcNormalization() {
        String decomposed = "é.jpg";
        ValidationRequest req = ValidationRequest.builder().originalFilename(decomposed).build();
        sanitization.validate(req);
        assertThat(req.getOriginalFilename()).isEqualTo("é.jpg");
    }

    // --- MimeTypeValidationStep tests ---

    @Test
    void mimeType_passesAllowedType() {
        ValidationRequest req = ValidationRequest.builder().contentType("image/jpeg").build();
        assertThatNoException().isThrownBy(() -> mimeType.validate(req));
    }

    @Test
    void mimeType_rejectsUnlistedType() {
        ValidationRequest req = ValidationRequest.builder().contentType("application/x-executable").build();
        assertThatThrownBy(() -> mimeType.validate(req))
            .isInstanceOf(StorageValidationException.class);
    }

    // --- ExtensionValidationStep tests ---

    @Test
    void extension_passesAllowedExtension() {
        ValidationRequest req = ValidationRequest.builder().extension("pdf").build();
        assertThatNoException().isThrownBy(() -> extension.validate(req));
    }

    @Test
    void extension_rejectsUnlistedExtension() {
        ValidationRequest req = ValidationRequest.builder().extension("exe").build();
        assertThatThrownBy(() -> extension.validate(req))
            .isInstanceOf(StorageValidationException.class);
    }

    // --- FileSizeValidationStep tests ---

    @Test
    void fileSize_passesUnderLimit() {
        ValidationRequest req = ValidationRequest.builder().fileSizeBytes(512 * 1024L).build();
        assertThatNoException().isThrownBy(() -> fileSize.validate(req));
    }

    @Test
    void fileSize_rejectsOverLimit() {
        ValidationRequest req = ValidationRequest.builder().fileSizeBytes(props.getValidation().getMaxSizeBytes() + 1).build();
        assertThatThrownBy(() -> fileSize.validate(req))
            .isInstanceOf(StorageValidationException.class);
    }

    // --- ChecksumValidationStep tests ---

    @Test
    void checksum_passesValid64CharHex() {
        ValidationRequest req = ValidationRequest.builder().checksum("a".repeat(64)).build();
        assertThatNoException().isThrownBy(() -> checksum.validate(req));
    }

    @Test
    void checksum_rejectsWrongLength() {
        ValidationRequest req = ValidationRequest.builder().checksum("a".repeat(63)).build();
        assertThatThrownBy(() -> checksum.validate(req))
            .isInstanceOf(StorageValidationException.class);
    }

    @Test
    void checksum_rejectsUppercaseHex() {
        ValidationRequest req = ValidationRequest.builder().checksum("A".repeat(64)).build();
        assertThatThrownBy(() -> checksum.validate(req))
            .isInstanceOf(StorageValidationException.class);
    }

    @Test
    void checksum_passesOnNullChecksum() {
        ValidationRequest req = ValidationRequest.builder().checksum(null).build();
        assertThatNoException().isThrownBy(() -> checksum.validate(req));
    }

    @Test
    void checksum_passesOnBlankChecksum() {
        ValidationRequest req = ValidationRequest.builder().checksum("  ").build();
        assertThatNoException().isThrownBy(() -> checksum.validate(req));
    }

    // --- ValidationChain tests ---

    @Test
    void chain_haltsOnFirstFailure() {
        AtomicBoolean secondStepCalled = new AtomicBoolean(false);
        ValidationStep alwaysFails = req -> { throw new StorageValidationException("first fails"); };
        ValidationStep recorder = req -> secondStepCalled.set(true);
        ValidationChain testChain = new ValidationChain(List.of(alwaysFails, recorder));

        ValidationRequest req = validRequest();
        assertThatThrownBy(() -> testChain.validate(req))
            .isInstanceOf(StorageValidationException.class);
        assertThat(secondStepCalled).isFalse();
    }

    @Test
    void chain_passesWhenAllStepsPass() {
        ValidationRequest req = validRequest();
        assertThatNoException().isThrownBy(() -> chain.validate(req));
    }

    @Test
    void chain_sanitizationRunsFirst() {
        ValidationRequest req = ValidationRequest.builder()
            .originalFilename("../test.jpg")
            .contentType("image/jpeg")
            .extension("jpg")
            .fileSizeBytes(512 * 1024L)
            .build();
        assertThatNoException().isThrownBy(() -> chain.validate(req));
        assertThat(req.getOriginalFilename()).doesNotContain("../");
    }
}
