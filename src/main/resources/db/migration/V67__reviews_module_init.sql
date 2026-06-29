CREATE SCHEMA IF NOT EXISTS reviews;

CREATE TABLE reviews.coach_reviews (
    review_id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id            UUID        NOT NULL,
    author_id           BIGINT      NOT NULL,
    author_role         VARCHAR(10) NOT NULL,
    rating              SMALLINT    NOT NULL CHECK (rating BETWEEN 1 AND 5),
    body                VARCHAR(1000),
    moderation_status   VARCHAR(15) NOT NULL DEFAULT 'PENDING',
    coach_response_body VARCHAR(500),
    coach_response_at   TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_modified_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_coach_reviews_author_coach UNIQUE (author_id, coach_id)
);

CREATE INDEX idx_coach_reviews_coach_id  ON reviews.coach_reviews(coach_id);
CREATE INDEX idx_coach_reviews_author_id ON reviews.coach_reviews(author_id);

INSERT INTO main.platform_config (id, key, value, value_type, description)
VALUES (513, 'reviews.submissionWindowDays', '14', 'LONG',
        'Days within which a completed session must exist to submit/update a review');
