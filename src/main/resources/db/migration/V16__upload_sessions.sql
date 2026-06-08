CREATE TABLE main.upload_sessions
(
    id                  UUID      NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    video_id            UUID      NOT NULL,
    provider_upload_id  VARCHAR,
    status              VARCHAR   NOT NULL CHECK (status IN ('PENDING', 'COMMITTED', 'EXPIRED')),
    reserved_bytes      BIGINT    NOT NULL,
    reservation_handle  VARCHAR,
    expires_at          TIMESTAMP NOT NULL,
    created_at          TIMESTAMP NOT NULL,
    CONSTRAINT fk_upload_session_video FOREIGN KEY (video_id) REFERENCES main.videos (id)
);

CREATE INDEX idx_upload_sessions_status_expires_at ON main.upload_sessions (status, expires_at);
