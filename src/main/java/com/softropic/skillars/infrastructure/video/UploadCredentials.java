package com.softropic.skillars.infrastructure.video;

public record UploadCredentials(
    String providerUploadId,
    String signedUploadUrl,
    String tusAuthorizationSignature,  // hex SHA-256(libraryId + apiKey + expireEpoch + videoId)
    long tusAuthorizationExpire,       // Unix epoch seconds (≥ 3600s from now)
    long tusLibraryId                  // numeric library ID, passed as LibraryId header by client
) {}
