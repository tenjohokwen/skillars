-- skillars-12-2 AC1/AC2: the coach's "what we did / what's next" session recap, captured at
-- wrap-up time alongside the existing ratings/voice-note fields on this row. Additive, nullable
-- ADD COLUMN per the expand/contract standard (migration-conventions.md rule 1) -- no backfill is
-- possible (a completed session has no way to reconstruct this text retroactively), matching the
-- existing voice_note_text column's own precedent on this same table.
SET LOCAL lock_timeout = '5s';

ALTER TABLE booking.session_completion_data
    ADD COLUMN IF NOT EXISTS what_we_did varchar(500),
    ADD COLUMN IF NOT EXISTS whats_next varchar(500);
