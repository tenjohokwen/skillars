package com.softropic.skillars.platform.video.contract;

import java.util.UUID;

/**
 * @param subjectBookingId V184: the booking a coach video is about — validated by the caller
 *                         ({@code VideoSubjectService}); null for no subject
 * @param subjectPlayerId  V184: that booking's player (PlayerProfile id); null iff subjectBookingId is
 */
public record InitializeUploadRequest(
    String ownerId,
    String fileName,
    long fileSizeBytes,
    String mimeType,
    VideoType videoType,   // nullable — null means no type constraint applies
    UUID subjectBookingId,
    Long subjectPlayerId
) {

    /** No subject (player HOMEWORK, drill-library demos). */
    public InitializeUploadRequest(String ownerId, String fileName, long fileSizeBytes, String mimeType,
                                   VideoType videoType) {
        this(ownerId, fileName, fileSizeBytes, mimeType, videoType, null, null);
    }

    public InitializeUploadRequest {
        if ((subjectBookingId == null) != (subjectPlayerId == null)) {
            throw new IllegalArgumentException("subjectBookingId and subjectPlayerId are set together or not at all");
        }
    }
}
