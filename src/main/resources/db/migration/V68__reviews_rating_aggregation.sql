ALTER TABLE marketplace.coach_profiles
    ADD COLUMN average_rating NUMERIC(3,1) DEFAULT NULL,
    ADD COLUMN review_count   INT          NOT NULL DEFAULT 0;
