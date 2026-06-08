package com.softropic.skillars.infrastructure.blobstore.contract;

import java.time.Instant;

public record StorageObjectMetadata(
    String key,
    String contentType,
    long contentLength,
    String eTag,
    Instant lastModified
) {}
