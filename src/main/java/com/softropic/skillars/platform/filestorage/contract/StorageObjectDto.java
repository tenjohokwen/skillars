package com.softropic.skillars.platform.filestorage.contract;

import java.time.Instant;
import java.util.Map;

public record StorageObjectDto(
    String key,
    String ownerId,
    String originalFilename,
    String contentType,
    long sizeBytes,
    String checksum,
    Map<String, String> tags,
    String provider,
    String bucket,
    Instant uploadConfirmedAt
) {}
