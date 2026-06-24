package com.softropic.skillars.platform.video.contract;

public record VideoQuotaResponse(
        long storageUsedBytes,
        long storageLimitBytes,
        long bandwidthUsedBytes,
        long bandwidthLimitBytes,
        String tier) {}
