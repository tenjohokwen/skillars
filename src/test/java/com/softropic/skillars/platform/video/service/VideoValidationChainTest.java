package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.config.VideoProperties;
import com.softropic.skillars.platform.video.contract.UploadValidationRequest;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
import com.softropic.skillars.platform.video.service.validation.FileSizeValidationStep;
import com.softropic.skillars.platform.video.service.validation.FormatValidationStep;
import com.softropic.skillars.platform.video.service.validation.MimeTypeValidationStep;
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
class VideoValidationChainTest {

    private VideoProperties props;
    private FileSizeValidationStep fileSize;
    private MimeTypeValidationStep mimeType;
    private FormatValidationStep format;
    private VideoValidationChain chain;

    @BeforeEach
    void setUp() {
        props = new VideoProperties();
        // Uses defaults: maxBytes=5368709120, allowedMimeTypes=[video/mp4,...], allowedFormats=[MP4,...]

        fileSize = new FileSizeValidationStep(props);
        mimeType = new MimeTypeValidationStep(props);
        format = new FormatValidationStep(props);

        chain = new VideoValidationChain(List.of(fileSize, mimeType, format));
    }

    private UploadValidationRequest validRequest() {
        return new UploadValidationRequest("video.mp4", 1024L, "video/mp4", "MP4");
    }

    // --- FileSizeValidationStep ---

    @Test
    void fileSize_passesUnderLimit() {
        UploadValidationRequest req = new UploadValidationRequest("video.mp4", 1024L, "video/mp4", "MP4");
        assertThatNoException().isThrownBy(() -> fileSize.validate(req));
    }

    @Test
    void fileSize_passesAtLimit() {
        UploadValidationRequest req = new UploadValidationRequest("video.mp4", props.getUpload().getMaxBytes(), "video/mp4", "MP4");
        assertThatNoException().isThrownBy(() -> fileSize.validate(req));
    }

    @Test
    void fileSize_rejectsOverLimit() {
        UploadValidationRequest req = new UploadValidationRequest("video.mp4", props.getUpload().getMaxBytes() + 1, "video/mp4", "MP4");
        assertThatThrownBy(() -> fileSize.validate(req))
            .isInstanceOf(VideoValidationException.class);
    }

    // --- MimeTypeValidationStep ---

    @Test
    void mimeType_passesAllowedType() {
        UploadValidationRequest req = new UploadValidationRequest("video.mp4", 1024L, "video/mp4", "MP4");
        assertThatNoException().isThrownBy(() -> mimeType.validate(req));
    }

    @Test
    void mimeType_rejectsUnlistedType() {
        UploadValidationRequest req = new UploadValidationRequest("video.mp4", 1024L, "application/octet-stream", "MP4");
        assertThatThrownBy(() -> mimeType.validate(req))
            .isInstanceOf(VideoValidationException.class);
    }

    @Test
    void mimeType_skipsNullMimeType() {
        UploadValidationRequest req = new UploadValidationRequest("video.mp4", 1024L, null, "MP4");
        assertThatNoException().isThrownBy(() -> mimeType.validate(req));
    }

    // --- FormatValidationStep ---

    @Test
    void format_passesAllowedFormat() {
        UploadValidationRequest req = new UploadValidationRequest("video.mp4", 1024L, "video/mp4", "MP4");
        assertThatNoException().isThrownBy(() -> format.validate(req));
    }

    @Test
    void format_passesAllowedFormatCaseInsensitive() {
        UploadValidationRequest req = new UploadValidationRequest("video.mp4", 1024L, "video/mp4", "mp4");
        assertThatNoException().isThrownBy(() -> format.validate(req));
    }

    @Test
    void format_rejectsUnlistedFormat() {
        UploadValidationRequest req = new UploadValidationRequest("video.mkv", 1024L, "video/mp4", "MKV");
        assertThatThrownBy(() -> format.validate(req))
            .isInstanceOf(VideoValidationException.class);
    }

    @Test
    void format_rejectsNullFormat() {
        UploadValidationRequest req = new UploadValidationRequest("video.mp4", 1024L, "video/mp4", null);
        assertThatThrownBy(() -> format.validate(req))
            .isInstanceOf(VideoValidationException.class);
    }

    // --- VideoValidationChain ---

    @Test
    void chain_passesWhenAllStepsPass() {
        assertThatNoException().isThrownBy(() -> chain.validate(validRequest()));
    }

    @Test
    void chain_haltsOnFirstFailure() {
        AtomicBoolean secondStepCalled = new AtomicBoolean(false);
        VideoValidationStep alwaysFails = req -> { throw new VideoValidationException("first fails"); };
        VideoValidationStep recorder = req -> secondStepCalled.set(true);
        VideoValidationChain testChain = new VideoValidationChain(List.of(alwaysFails, recorder));

        assertThatThrownBy(() -> testChain.validate(validRequest()))
            .isInstanceOf(VideoValidationException.class);
        assertThat(secondStepCalled).isFalse();
    }

    @Test
    void chain_stepsExecuteInOrder() {
        // FileSize (Order 10) runs before MimeType (Order 20): an oversized file with bad mime
        // only throws from fileSize (first step), never reaching mimeType
        AtomicBoolean mimeTypeCalled = new AtomicBoolean(false);
        VideoValidationStep trackingMimeType = req -> {
            mimeTypeCalled.set(true);
            mimeType.validate(req);
        };
        VideoValidationChain orderedChain = new VideoValidationChain(List.of(fileSize, trackingMimeType, format));

        UploadValidationRequest req = new UploadValidationRequest(
            "video.mp4", props.getUpload().getMaxBytes() + 1, "application/octet-stream", "MKV");

        assertThatThrownBy(() -> orderedChain.validate(req))
            .isInstanceOf(VideoValidationException.class);
        assertThat(mimeTypeCalled).isFalse();
    }
}
