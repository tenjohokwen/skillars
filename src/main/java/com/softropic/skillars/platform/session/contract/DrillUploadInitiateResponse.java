package com.softropic.skillars.platform.session.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * Credentials for the browser's TUS upload of a drill demo video. Carries the same Bunny TUS
 * authorization fields as the generic {@code InitializeUploadResponse}: Bunny rejects a TUS
 * request without AuthorizationSignature / AuthorizationExpire / LibraryId / VideoId headers, so
 * the four fields below are required for the upload to work at all.
 */
public record DrillUploadInitiateResponse(
    UUID videoId,
    UUID uploadSessionId,
    String signedUploadUrl,
    Instant expiresAt,
    String providerUploadId,
    String tusAuthorizationSignature,
    long tusAuthorizationExpire,
    long tusLibraryId
) {}
