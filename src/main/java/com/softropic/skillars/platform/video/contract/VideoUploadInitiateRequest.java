package com.softropic.skillars.platform.video.contract;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * @param subjectBookingId V184, coach uploads only: the session the video is about. Required for
 *                         COACH_REVIEW, optional for DRILL_DEMO, refused on a player upload.
 */
public record VideoUploadInitiateRequest(
    @NotBlank String fileName,
    @Min(1) long fileSizeBytes,
    @Pattern(regexp = "video/.+", message = "mimeType must be a video/* MIME type")
    @NotBlank String mimeType,
    @NotNull VideoType videoType,
    UUID subjectBookingId
) {

    public VideoUploadInitiateRequest(String fileName, long fileSizeBytes, String mimeType, VideoType videoType) {
        this(fileName, fileSizeBytes, mimeType, videoType, null);
    }
}
