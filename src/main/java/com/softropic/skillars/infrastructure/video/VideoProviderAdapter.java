package com.softropic.skillars.infrastructure.video;

public interface VideoProviderAdapter {

    UploadCredentials initializeUpload(String fileName, long fileSizeBytes);

    AssetStatus getAssetStatus(String providerAssetId);

    SignedPlaybackUrl generatePlaybackUrl(String providerAssetId, PlaybackTokenClaims claims);

    void deleteAsset(String providerAssetId);

    WebhookEvent verifyWebhook(String payload, String signature);

    default void archiveAsset(String providerAssetId) {
        throw new UnsupportedOperationException("archiveAsset not supported");
    }

    default void restoreAsset(String providerAssetId) {
        throw new UnsupportedOperationException("restoreAsset not supported");
    }

    default VideoMetadata getVideoMetadata(String providerAssetId) {
        throw new UnsupportedOperationException("getVideoMetadata not supported by this provider");
    }

    default String getThumbnailUrl(String providerAssetId) {
        throw new UnsupportedOperationException("thumbnails not supported");
    }

    default void addCaptionTrack(String providerAssetId, String language, String captionFileUrl) {
        throw new UnsupportedOperationException("captions not supported");
    }
}
