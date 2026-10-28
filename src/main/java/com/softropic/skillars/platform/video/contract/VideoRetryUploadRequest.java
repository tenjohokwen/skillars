package com.softropic.skillars.platform.video.contract;

import jakarta.validation.constraints.Min;

/**
 * Body of {@code POST /api/video/{id}/retry}: the size of the file the owner re-selected for the retry.
 * The video id comes from the path and the owner from the authenticated caller — never from the body.
 */
public record VideoRetryUploadRequest(@Min(1) long fileSizeBytes) {}
