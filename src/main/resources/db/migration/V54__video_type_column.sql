ALTER TABLE main.videos
    ADD COLUMN video_type VARCHAR(30) NULL
        CONSTRAINT chk_videos_video_type CHECK (
            video_type IS NULL OR video_type IN ('HOMEWORK', 'DRILL_DEMO', 'COACH_REVIEW')
        );
