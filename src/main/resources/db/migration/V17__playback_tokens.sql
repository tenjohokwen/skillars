CREATE TABLE main.playback_tokens
(
    id         UUID      NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    video_id   UUID      NOT NULL,
    viewer_id  VARCHAR   NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_playback_token_video FOREIGN KEY (video_id) REFERENCES main.videos (id)
);

CREATE INDEX idx_playback_tokens_viewer_id_revoked_at ON main.playback_tokens (viewer_id, revoked_at);
