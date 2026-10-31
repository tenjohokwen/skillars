-- feat/security-s06-related-party: detect a coach colluding with a second account of their own (self-booking to farm
-- reviews, completed-session counts and the new-coach payout release).
--
--   1. main.user_identity_signals: the hashed identifiers observed per user — normalised email, normalised phone,
--      Stripe card fingerprint, client-side browser fingerprint. Only a SHA-256 of the normalised value is stored,
--      never the value. Two accounts sharing a (kind, value_hash) are "linked"; RelatedPartyService compares the
--      payer of a booking with the booked coach only. Rows are deleted by the GDPR user-erasure path.
--   2. payment.related_party_bookings: one row per booking whose payer is linked to the booked coach. Presence of a row
--      IS the flag (idempotent: PK booking_id). payout_hold_until is the long payout hold, read by
--      CoachPayoutTransferHandler at release time. matched_kinds says which signal kinds matched (ops only, never
--      shown to a user).
--   3. reviews.coach_reviews.related_party: a review by a related-party payer is stored (so it cannot be resubmitted)
--      but never published and never counted in the coach's rating.
--
-- Additive: new empty tables and one constant-default column (catalog-only on PG 11+).
SET LOCAL lock_timeout = '5s';

-- New, empty tables: constraints inline, nothing to validate (V148/V175/V186/V200/V217 precedent).
CREATE TABLE IF NOT EXISTS main.user_identity_signals (
    signal_id  bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    bigint      NOT NULL,
    kind       varchar(16) NOT NULL,
    value_hash char(64)    NOT NULL,
    first_seen timestamptz NOT NULL DEFAULT now(),
    last_seen  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_identity_signal UNIQUE (user_id, kind, value_hash)
);

-- migration-lint: allow-blocking-index new empty table, nothing to scan.
CREATE INDEX IF NOT EXISTS idx_uis_kind_hash ON main.user_identity_signals USING btree (kind, value_hash);

CREATE TABLE IF NOT EXISTS payment.related_party_bookings (
    booking_id        uuid         PRIMARY KEY,
    coach_id          uuid         NOT NULL,
    payer_user_id     bigint       NOT NULL,
    player_user_id    bigint,
    matched_kinds     varchar(64)  NOT NULL,
    flagged_at        timestamptz  NOT NULL DEFAULT now(),
    payout_hold_until timestamptz  NOT NULL
);

-- migration-lint: allow-blocking-index new empty table, nothing to scan.
CREATE INDEX IF NOT EXISTS idx_rpb_coach_payer ON payment.related_party_bookings USING btree (coach_id, payer_user_id);

ALTER TABLE reviews.coach_reviews
    ADD COLUMN IF NOT EXISTS related_party boolean NOT NULL DEFAULT false;
