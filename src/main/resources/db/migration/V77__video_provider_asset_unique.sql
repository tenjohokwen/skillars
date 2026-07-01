-- Story deferred-3: prevent duplicate Bunny.net asset IDs; also makes findByProviderAssetId safe
-- NOTE: authoritative table is main.videos (@Table(name = "videos", schema = "main") on Video entity),
-- not "video.videos".
CREATE UNIQUE INDEX IF NOT EXISTS idx_videos_provider_asset_id_unique
    ON main.videos(provider_asset_id)
    WHERE provider_asset_id IS NOT NULL;
